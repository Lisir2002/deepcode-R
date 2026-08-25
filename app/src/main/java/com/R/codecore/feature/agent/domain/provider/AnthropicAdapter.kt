package com.R.codecore.feature.agent.domain.provider

import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicApi
import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicMessage
import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicThinkingConfig
import com.R.codecore.feature.agent.data.remote.anthropic.AnthropicToolDefinition
import com.R.codecore.core.network.SseFieldExtractor
import com.R.codecore.core.util.AILogger
import com.R.codecore.feature.agent.domain.model.AgentImage
import com.R.codecore.feature.agent.domain.model.AgentMessage
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.settings.domain.model.ProviderType
import com.R.codecore.feature.settings.domain.model.defaultProviderApiPath
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** P1 定点字段抽取路径：Anthropic 流式各事件所需的标量字段（点连接 = 嵌套路径，数组下标为路径段）。 */
private val ANTHROPIC_STREAM_PATHS: List<List<String>> = listOf(
    listOf("type"),
    listOf("error", "type"),
    listOf("error", "message"),
    listOf("message", "usage", "input_tokens"),
    listOf("index"),
    listOf("content_block", "type"),
    listOf("content_block", "id"),
    listOf("content_block", "name"),
    listOf("delta", "type"),
    listOf("delta", "text"),
    listOf("delta", "thinking"),
    listOf("delta", "signature"),
    listOf("delta", "partial_json"),
    listOf("delta", "stop_reason"),
    listOf("usage", "output_tokens")
)

class AnthropicAdapter @Inject constructor(
    private val api: AnthropicApi
) : AIProvider {

    override var apiKey = ""
    override var baseUrl = "https://api.anthropic.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "claude-3-5-sonnet-20241022"
    override var logSessionId: String? = null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val anthropicMessages = convertToAnthropicMessages(messages)

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema()
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val thinking = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = systemPrompt.ifBlank { null },
            temperature = if (thinking != null) null else 0.7f,
            thinking = thinking,
            tools = toolDefs,
            stream = false
        )
        AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)

        val response = try {
            retryStaircase {
                api.createMessage(url = url, apiKey = apiKey, request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Anthropic", response)

        var contentText = ""
        var thinkingText = ""
        var signature: String? = null
        val toolCalls = mutableListOf<ToolCall>()

        for (block in response.content) {
            when (block.type) {
                "text" -> contentText += block.text ?: ""
                "thinking" -> {
                    thinkingText += block.thinking ?: ""
                    signature = block.signature ?: signature
                }
                "tool_use" -> {
                    val arguments = block.input?.let { mapToJson(it) } ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = block.id ?: "",
                            name = block.name ?: "",
                            arguments = arguments
                        )
                    )
                }
            }
        }

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = response.stop_reason, reasoning = thinkingText.ifEmpty { null }, signature = signature, inputTokens = response.usage.input_tokens, outputTokens = response.usage.output_tokens)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val anthropicMessages = convertToAnthropicMessages(messages)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema()
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val thinking = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = systemPrompt.ifBlank { null },
            temperature = if (thinking != null) null else 0.7f,
            thinking = thinking,
            tools = toolDefs,
            stream = true
        )
        AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)
        // 累积原始 SSE，整轮结束（或失败）后整体落盘，避免高频写盘。
        val rawSse = StringBuilder()

        // 首字节前失败可安全重试；一旦开始吐字（onProduced 已调用）再失败则上抛，避免重复文本。
        try {
            streamWithStaircaseRetry(
                attemptOnce = { onProduced ->
            val textBuilder = StringBuilder()
            // content block index -> 累积中的 tool_use（仅 tool_use 块建条目，保序）。
            val toolBlocks = LinkedHashMap<Int, ToolBlockAcc>()
            var stopReason: String? = null
            var streamInputTokens = 0
            var streamOutputTokens = 0
            // thinking block 的加密签名（signature_delta 事件携带），随 Final 上抛供工具循环回传。
            var signature: String? = null

            val body = api.streamMessage(url = url, apiKey = apiKey, request = request)

            body.use { rb ->
                // 首字节超时 watchdog：60s 内未收到首个内容块则关闭流，触发可重试的 IOException。
                val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)
                val watchdog = launchFirstByteWatchdog({ rb.close() }) { firstByteReceived.get() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val source = rb.source()
                    // 收到服务端 message_stop 事件即 break 正常结束；readUtf8Line() 返回 null 则视为
                    // 流被异常截断（网络中断/TCP 重置/readTimeout），必须抛异常让重试/日志接管——
                    // 否则原本会用截断数据「正常完成」，表现为 AI 突然中断且无任何错误日志。
                    // （收到 message_stop 即 break，故走到 readUtf8Line()==null 时必然未收到过结束标记。）
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line()
                            ?: throw IOException("SSE 流被中断：未收到 message_stop 结束标记（疑似网络断开）")
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        rawSse.append(line).append('\n')
                        // P1 定点字段抽取：JsonReader 流式逐 token 只取目标标量，不建整棵 JSON 树，
                        // 显著降低流式主路径的解析 CPU 与 GC（旧实现每行 parseString 建整树）。
                        val m = runCatching { SseFieldExtractor.extract(data, ANTHROPIC_STREAM_PATHS) }.getOrElse { emptyMap() }
                        // 单行 SSE 解析：不同上游/模型的字段类型偶有出入，宽松解析——
                        // 缺失/类型不符的字段从结果中缺失，按「无该字段」处理，出错仅跳过该行；
                        // 必须放行 CancellationException。
                        try {
                            when (m["type"]) {
                                "error" -> {
                                    val code = m["error.type"]
                                    val msg = m["error.message"] ?: "未知错误"
                                    throw StreamApiException(code, msg)
                                }
                                "message_start" -> {
                                    streamInputTokens = m["message.usage.input_tokens"]?.toIntOrNull() ?: 0
                                }
                                "content_block_start" -> {
                                    val index = m["index"]?.toIntOrNull() ?: continue
                                    if (m["content_block.type"] == "tool_use") {
                                        toolBlocks[index] = ToolBlockAcc(
                                            id = m["content_block.id"] ?: "",
                                            name = m["content_block.name"] ?: ""
                                        )
                                    }
                                }
                                "content_block_delta" -> {
                                    val index = m["index"]?.toIntOrNull()
                                    when (m["delta.type"]) {
                                        "text_delta" -> {
                                            val t = m["delta.text"] ?: ""
                                            if (t.isNotEmpty()) {
                                                textBuilder.append(t)
                                                if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                onProduced()
                                                emit(AIStreamChunk.TextDelta(t))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val t = m["delta.thinking"] ?: ""
                                            if (t.isNotEmpty()) {
                                                // 思考内容不落库、可重试重流出，但收到即说明连接已活，取消首字节超时。
                                                if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                emit(AIStreamChunk.ReasoningDelta(t))
                                            }
                                        }
                                        "signature_delta" -> {
                                            val sig = m["delta.signature"] ?: ""
                                            if (sig.isNotEmpty()) signature = sig
                                        }
                                        "input_json_delta" -> {
                                            val partial = m["delta.partial_json"] ?: ""
                                            if (index != null) toolBlocks[index]?.args?.append(partial)
                                        }
                                    }
                                }
                                "message_stop" -> break
                                "message_delta" -> {
                                    m["delta.stop_reason"]?.let { stopReason = it }
                                    m["usage.output_tokens"]?.toIntOrNull()?.let { streamOutputTokens = it }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // 该行 SSE 解析失败，跳过；不影响已累积文本与后续行。
                        }
                    }
                } finally {
                    watchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolBlocks.values.map { acc ->
                ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.toString()))
            }
            onProduced()
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = stopReason, signature = signature, inputTokens = streamInputTokens, outputTokens = streamOutputTokens)))
                },
                onRetry = { attempt, max -> emit(AIStreamChunk.Retrying(attempt, max)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched)
            throw enriched
        } finally {
            // 无论成功/失败/取消，把已收到的原始 SSE 落盘（重试时会从上次中断处续写）。
            AILogger.logResponseStream(logSessionId, "Anthropic", rawSse.toString())
        }
    }.flowOn(Dispatchers.IO)

    /** 流式过程中按 content block index 累积的 tool_use 状态。 */
    private class ToolBlockAcc(val id: String, val name: String) {
        val args = StringBuilder()
    }

    /** 思考强度 → Anthropic thinking 预算。budget_tokens 最小 1024，且须小于 max_tokens(16384)。 */
    private fun buildThinkingConfig(reasoningEffort: String?): AnthropicThinkingConfig? {
        if (reasoningEffort == null) return null
        val budget = when (reasoningEffort) {
            "low" -> 1024
            "medium" -> 4096
            "high" -> 8192
            else -> return null
        }
        return AnthropicThinkingConfig(budget_tokens = budget)
    }

    /** 把累积的工具入参 JSON 字符串解析为 JsonObject；为空或非法时回退为空对象。 */
    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun convertToAnthropicMessages(messages: List<AgentMessage>): MutableList<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()
        // 防御性跟踪：上一个 assistant 消息是否包含 tool_use
        var lastAssistantHadToolUse = false

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    result.add(AnthropicMessage(role = "user", content = message.toAnthropicUserContent()))
                    lastAssistantHadToolUse = false
                }
                is AgentMessage.AssistantMessage -> {
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()
                    // 工具循环/多轮时须把上轮 thinking block（含 signature）原样回传，否则 400。
                    // 仅当 signature 存在时回传：旧数据/备份恢复没有 signature，不回传也不会报错。
                    if (message.signature.isNotEmpty()) {
                        contentBlocks.add(
                            AnthropicContentBlock(
                                type = "thinking",
                                thinking = message.reasoning,
                                signature = message.signature
                            )
                        )
                    }
                    if (message.content.isNotEmpty()) {
                        contentBlocks.add(AnthropicContentBlock(type = "text", text = message.content))
                    }

                    for (toolCall in message.toolCalls) {
                         @Suppress("UNCHECKED_CAST")
                         val inputMap = jsonElementToMap(JsonObject(toolCall.arguments)) as Map<String, Any>

                         contentBlocks.add(
                            AnthropicContentBlock(
                                type = "tool_use",
                                id = toolCall.id,
                                name = toolCall.name,
                                input = inputMap
                            )
                        )
                    }

                    lastAssistantHadToolUse = message.toolCalls.isNotEmpty()

                    if (contentBlocks.isNotEmpty()) {
                        result.add(AnthropicMessage(role = "assistant", content = contentBlocks))
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // 防御性清理：跳过没有配对 tool_use 的孤立 tool_result
                    if (!lastAssistantHadToolUse) continue
                    result.add(
                        AnthropicMessage(
                            role = "user",
                            content = listOf(
                                AnthropicContentBlock(
                                    type = "tool_result",
                                    tool_use_id = message.id,
                                    content = message.result
                                )
                            )
                        )
                    )
                }
            }
        }

        return result
    }

    /** Convert a Map<String, Any> (from Gson) to a JsonObject */
    private fun mapToJson(map: Map<String, Any>): JsonObject {
        val mutable = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in map) {
            mutable[k] = when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    mapToJson(v as Map<String, Any>)
                }
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(mutable)
    }

    /** Convert a JsonObject back to Map<String, Any> for Anthropic API */
    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Any {
        return when (element) {
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToMap(v) }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToMap(it) }
            is JsonPrimitive -> element.contentOrNull ?: ""
        }
    }

    private fun AgentMessage.UserMessage.toAnthropicUserContent(): Any {
        if (images.isEmpty()) return content

        val blocks = mutableListOf<AnthropicContentBlock>()
        if (content.isNotBlank()) {
            blocks.add(AnthropicContentBlock(type = "text", text = content))
        }
        images.forEach { image ->
            blocks.add(image.toAnthropicImageBlock())
        }
        return blocks
    }

    private fun AgentImage.toAnthropicImageBlock(): AnthropicContentBlock {
        return AnthropicContentBlock(
            type = "image",
            source = mapOf(
                "type" to "base64",
                "media_type" to mimeType,
                "data" to base64Data
            )
        )
    }
}
