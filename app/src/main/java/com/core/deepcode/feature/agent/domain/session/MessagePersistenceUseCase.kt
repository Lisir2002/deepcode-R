package com.core.deepcode.feature.agent.domain.session

import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.datalayer.sqldelight.agent.Agent_message as V2AgentMessage
import com.core.deepcode.feature.agent.data.local.entity.AgentMessageEntity
import com.core.deepcode.feature.agent.domain.model.AgentMessage
import com.core.deepcode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.core.deepcode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.core.deepcode.feature.agent.domain.tool.ToolCall
import com.core.deepcode.feature.agent.presentation.AgentAttachment
import com.core.deepcode.feature.agent.presentation.MessageRole
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePersistenceUseCase @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // 单调递增时间戳：保证同毫秒内多次落库的顺序稳定（assistant 永远在其 tool 结果之前）。
    @Volatile
    private var lastTimestamp = 0L

    @Synchronized
    fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    suspend fun persist(
        sessionId: String,
        role: MessageRole,
        content: String,
        id: String = UUID.randomUUID().toString(),
        taskId: String = "",
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        isError: Boolean = false,
        reasoning: String? = null,
        signature: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        isCompacted: Boolean = false
    ) {
        // 先剥离内嵌 base64 图片（不截断），避免超大 data URL 直接进 content。
        val clean = stripInlineImages(content)
        val toolCallsJson = if (toolCalls.isNotEmpty()) json.encodeToString(toolCalls) else null
        // reasoning 走单行兜底（分块优先级低于正文，且单行 200k 已安全）。
        val cleanReasoning = reasoning?.let { sanitizeContent(it) }
        val attachmentsJson = if (attachments.isNotEmpty()) json.encodeToString(attachments) else null

        if (clean.length <= CHUNK_SIZE) {
            val entity = buildEntity(
                id = id,
                sessionId = sessionId,
                taskId = taskId,
                role = role,
                content = clean,
                timestamp = nextTimestamp(),
                chunkGroupId = "",
                chunkIndex = 0,
                toolCallsJson = toolCallsJson,
                toolCallId = toolCallId,
                toolName = toolName,
                toolArgs = toolArgs,
                isError = isError,
                reasoning = cleanReasoning,
                signature = signature,
                attachmentsJson = attachmentsJson,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                isCompacted = isCompacted
            )
            v2Agent.insertMessage(entity.toV2())
        } else {
            // 超长内容分块落库：主行（chunk 0）携带全部元数据，续块行仅携带内容。
            // 全组共享同一 timestamp（块号递增），保证按时间序查询时块与块邻接、不被其它消息穿插，
            // 且主行 id 字典序在续块行之前（`<id>` < `<id>#c1` < `<id>#c2` …），keyset 分页也稳定。
            val chunks = clean.chunked(CHUNK_SIZE)
            val ts = nextTimestamp()
            val rows = chunks.mapIndexed { i, chunkText ->
                buildEntity(
                    id = if (i == 0) id else "$id$CHUNK_ID_SUFFIX_PREFIX$i",
                    sessionId = sessionId,
                    taskId = taskId,
                    role = role,
                    content = chunkText,
                    timestamp = ts,
                    chunkGroupId = id,
                    chunkIndex = i,
                    // 元数据只写主行，续块行复用主行的会话/角色/timestamp 即可。
                    toolCallsJson = if (i == 0) toolCallsJson else null,
                    toolCallId = if (i == 0) toolCallId else null,
                    toolName = if (i == 0) toolName else null,
                    toolArgs = if (i == 0) toolArgs else null,
                    isError = if (i == 0) isError else false,
                    reasoning = if (i == 0) cleanReasoning else null,
                    signature = if (i == 0) signature else null,
                    attachmentsJson = if (i == 0) attachmentsJson else null,
                    inputTokens = if (i == 0) inputTokens else 0,
                    outputTokens = if (i == 0) outputTokens else 0,
                    isCompacted = isCompacted
                )
            }
            v2Agent.insertAllMessages(rows.map { it.toV2() })
        }
    }

    private fun buildEntity(
        id: String,
        sessionId: String,
        taskId: String,
        role: MessageRole,
        content: String,
        timestamp: Long,
        chunkGroupId: String,
        chunkIndex: Int,
        toolCallsJson: String?,
        toolCallId: String?,
        toolName: String?,
        toolArgs: String?,
        isError: Boolean,
        reasoning: String?,
        signature: String?,
        attachmentsJson: String?,
        inputTokens: Int,
        outputTokens: Int,
        isCompacted: Boolean
    ): AgentMessageEntity = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        taskId = taskId,
        role = role.name,
        content = content,
        timestamp = timestamp,
        chunkGroupId = chunkGroupId,
        chunkIndex = chunkIndex,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgs = toolArgs,
        isError = isError,
        reasoning = reasoning,
        signature = signature,
        attachmentsJson = attachmentsJson,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        isCompacted = isCompacted
    )

    suspend fun updateContent(messageId: String, newContent: String) {
        v2Agent.updateMessageContent(messageId, newContent)
    }

    companion object {
        /**
         * 单条消息字段单行持久化上限（字符数）。分块行 / reasoning 等非分块字段都以它兜底，
         * 防止超大单行读取时触发 [android.database.sqlite.SQLiteBlobTooBigException]。
         */
        const val MAX_CONTENT_CHARS = 200_000

        /**
         * 分块大小（字符数）：单块 UTF-8 约 ≤300KB，远低于 SQLite CursorWindow 单行约 2MB 的硬限制。
         * 超长消息按 CHUNK_SIZE 拆多行落库，读取侧（mergeChunks）按 chunk_index 序拼接，
         * 最终消息长度不再受限。
         */
        const val CHUNK_SIZE = 100_000

        /** 续块行 id 后缀前缀：`<主id>#c<i>`，保证同组块按 id 字典序也相邻。 */
        const val CHUNK_ID_SUFFIX_PREFIX = "#c"

        const val IMAGE_OMITTED_MARKER = "[图片已省略：内嵌图片数据过大]"
        const val CONTENT_TRUNCATED_MARKER = "…[内容过长，已截断]"

        /** 内嵌 base64 图片 data URL（`data:image/...;base64,...`）。 */
        private val INLINE_BASE64_IMAGE = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\r\n]+""")

        /**
         * 仅剥离内嵌 base64 图片 data URL（替换为占位说明），不截断。
         * 长内容的截断 / 分块由调用方按需处理（persist 先剥离再分块）。
         */
        internal fun stripInlineImages(raw: String): String {
            if (!raw.contains("data:image/", ignoreCase = true)) return raw
            return INLINE_BASE64_IMAGE.replace(raw, IMAGE_OMITTED_MARKER)
        }

        /**
         * 单行兜底净化：剥离 base64 图片 + 超长截断到 [MAX_CONTENT_CHARS]。
         * 供 reasoning 等非分块字段使用；content 正文走分块路径（见 [persist]），不再截断。
         */
        internal fun sanitizeContent(raw: String): String {
            if (raw.length <= MAX_CONTENT_CHARS && !raw.contains("data:image/", ignoreCase = true)) {
                return raw
            }
            val text = stripInlineImages(raw)
            return if (text.length > MAX_CONTENT_CHARS) {
                text.take(MAX_CONTENT_CHARS) + CONTENT_TRUNCATED_MARKER
            } else {
                text
            }
        }

        /**
         * 把分块存储的消息按 [chunk_index] 序拼接回单条完整消息（保持原时间顺序输出）。
         * 未分块消息原样透传。用于 UI 流 / buildHistory 等所有读取侧。
         * 分页边界被截断的病态场景（单条 > 3M 字符）下按已加载块拼接，loadMore 拉全后自动补齐。
         */
        internal fun mergeChunks(entities: List<AgentMessageEntity>): List<AgentMessageEntity> {
            if (entities.none { it.chunkGroupId.isNotBlank() }) return entities
            val byGroup = HashMap<String, MutableList<AgentMessageEntity>>()
            for (e in entities) {
                if (e.chunkGroupId.isNotBlank()) {
                    byGroup.getOrPut(e.chunkGroupId) { mutableListOf() }.add(e)
                }
            }
            val emitted = HashSet<String>(byGroup.size)
            val result = ArrayList<AgentMessageEntity>(entities.size)
            for (e in entities) {
                val gid = e.chunkGroupId
                if (gid.isBlank()) {
                    result.add(e)
                    continue
                }
                // 同一分块组只合并一次，输出位置取该组首次出现的块（各块共享 timestamp，时间序一致）。
                if (!emitted.add(gid)) continue
                val group = byGroup.getValue(gid).sortedBy { it.chunkIndex }
                val base = group.first()
                val content = group.joinToString("") { it.content }
                val reasoning = group.mapNotNull { it.reasoning }.joinToString("").ifEmpty { null }
                result.add(
                    base.copy(
                        content = content,
                        reasoning = reasoning,
                        chunkGroupId = "",
                        chunkIndex = 0
                    )
                )
            }
            return result
        }
    }

    /**
     * 从持久化的消息重建合法的上下文历史。
     * 关键：只保留「assistant 的 tool_call」与「tool 结果」能配对成功的部分，
     * 丢弃任何一方缺失的悬挂项，避免回放出现孤儿 tool_use / tool_result 违反 API 约束。
     * 已被上下文压缩标记的消息（isCompacted=true）不参与回放。
     */
    suspend fun buildHistory(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        // 先按 chunk_index 拼接分块消息（chunk 行共享 timestamp，压缩标记也是全组一致），再过滤已压缩行。
        val raw = v2Agent.getMessagesBySessionOnce(sessionId).map { it.toEntity() }
        val entities = mergeChunks(raw).filter { !it.isCompacted }

        // 第一遍：求 assistant 声明的 toolCallId 与 tool 结果 toolCallId 的交集。
        val declaredIds = mutableSetOf<String>()
        val resultIds = mutableSetOf<String>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.ASSISTANT -> e.toolCallsJson?.let {
                    runCatching { json.decodeFromString<List<ToolCall>>(it) }
                        .getOrNull()?.forEach { tc -> declaredIds.add(tc.id) }
                }
                MessageRole.TOOL -> {
                    // 只有真正完成的结果才计入配对；执行中占位行（完成事件未回来的孤儿）不算。
                    if (!e.content.startsWith(pendingToolMarker) &&
                        !e.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
                    ) {
                        e.toolCallId?.let { resultIds.add(it) }
                    }
                }
                else -> {}
            }
        }
        val validIds = declaredIds intersect resultIds

        // 第二遍：构建消息，过滤掉无法配对的工具调用 / 工具结果。
        val result = mutableListOf<AgentMessage>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val rawContent = if (e.isCompactionMarker) CONTEXT_COMPACTION_MARKER else e.content
                    val attachments = if (!e.isCompactionMarker) {
                        e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                        } ?: emptyList()
                    } else emptyList()

                    val finalContent = if (attachments.isNotEmpty()) {
                        val attachmentText = buildString {
                            append("附件：")
                            attachments.forEach { att ->
                                append('\n')
                                append("- ")
                                append(att.fileName)
                                append("：")
                                append(att.containerPath)
                            }
                        }
                        if (rawContent.isBlank()) attachmentText else "${rawContent.trimEnd()}\n\n$attachmentText"
                    } else {
                        rawContent
                    }

                    val images = attachments.mapNotNull { it.toAgentImage() }

                    result.add(
                        AgentMessage.UserMessage(
                            id = e.id,
                            content = finalContent,
                            images = images
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = e.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
                    }?.filter { it.id in validIds } ?: emptyList()
                    if (e.content.isNotBlank() || toolCalls.isNotEmpty()) {
                        val previous = result.lastOrNull()
                        if (
                            e.isContextSummary &&
                            !(previous is AgentMessage.UserMessage && previous.content == CONTEXT_COMPACTION_MARKER)
                        ) {
                            result.add(AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER))
                        }
                        result.add(
                            AgentMessage.AssistantMessage(
                                id = e.id,
                                content = e.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart(),
                                toolCalls = toolCalls,
                                reasoning = e.reasoning ?: "",
                                signature = e.signature ?: ""
                            )
                        )
                    }
                }
                MessageRole.TOOL -> {
                    val tcId = e.toolCallId
                    if (tcId != null && tcId in validIds) {
                        result.add(
                            AgentMessage.ToolResultMessage(
                                id = tcId,
                                toolName = e.toolName ?: "unknown",
                                result = e.content
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun AgentAttachment.toAgentImage(): com.core.deepcode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        return try {
            val bytes = file.readBytes()
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
            com.core.deepcode.feature.agent.domain.model.AgentImage(
                mimeType = mimeType.ifBlank { "image/jpeg" },
                base64Data = base64,
                path = containerPath
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun AgentMessageEntity.toV2() = V2AgentMessage(
        id = id,
        session_id = sessionId,
        role = role,
        seq = timestamp,
        created_at = timestamp,
        task_id = taskId,
        content = content,
        tool_calls_json = toolCallsJson,
        tool_call_id = toolCallId,
        tool_name = toolName,
        tool_args = toolArgs,
        is_error = if (isError) 1L else 0L,
        reasoning = reasoning,
        signature = signature,
        attachments_json = attachmentsJson,
        is_compacted = if (isCompacted) 1L else 0L,
        is_context_summary = if (isContextSummary) 1L else 0L,
        is_compaction_marker = if (isCompactionMarker) 1L else 0L,
        input_tokens = inputTokens.toLong(),
        output_tokens = outputTokens.toLong(),
        chunk_group_id = chunkGroupId,
        chunk_index = chunkIndex.toLong(),
    )

    private fun V2AgentMessage.toEntity() = AgentMessageEntity(
        id = id,
        sessionId = session_id,
        taskId = task_id,
        role = role,
        content = content,
        timestamp = seq,
        toolCallsJson = tool_calls_json,
        toolCallId = tool_call_id,
        toolName = tool_name,
        toolArgs = tool_args,
        isError = is_error == 1L,
        reasoning = reasoning,
        signature = signature,
        attachmentsJson = attachments_json,
        isCompacted = is_compacted == 1L,
        isContextSummary = is_context_summary == 1L,
        isCompactionMarker = is_compaction_marker == 1L,
        inputTokens = input_tokens.toInt(),
        outputTokens = output_tokens.toInt(),
        chunkGroupId = chunk_group_id,
        chunkIndex = chunk_index.toInt(),
    )
}
