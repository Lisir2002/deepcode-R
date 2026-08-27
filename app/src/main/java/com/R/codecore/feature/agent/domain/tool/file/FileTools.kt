package com.R.codecore.feature.agent.domain.tool.file

import com.R.codecore.core.util.FileLogger
import com.R.codecore.core.util.LineDiff
import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.feature.agent.data.local.dao.FileEditHunkDao
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

private const val TAG = "FileTools"

/** F-3：hunk 落库时单条快照（old/new content）保存的最大字符数，防止超大文件撑爆数据库。 */
private const val HUNK_SNAPSHOT_MAX_CHARS = 50_000

class ReadFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val fileEditHunkDao: FileEditHunkDao,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
) : AgentTool() {
    override val name = "readFile"
    override val description = "读取指定路径的文件内容。支持工作区文件或容器绝对路径的系统文件。单次读取受文件大小限制，超大文件可通过 start_line 分段读取。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    /** L3 结构化结果协议：产出 file.read 类型，供 editFile 等消费方按类型直连。 */
    override val provides = setOf("file.read")
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "开始行号（从 1 计）。", required = false),
        "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "结束行号；与 start_line 的跨度最多 2000 行，超出按 2000 行截断。", required = false),
        "force_total_lines" to ToolParameter("force_total_lines", ParameterType.BOOLEAN, "F-4：是否强制统计并返回文件总行数。默认按文件大小自动决策（≤1MB 统计，大文件跳过）；设 true 则始终统计（大文件代价较高）。", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = executeInternal(args, null)

    /** F-3：优先走 executeWithContext 以获得会话 ID，用于 hunk 落库；无上下文时降级为不落库。 */
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return executeInternal(args, context.sessionId)
    }

    private suspend fun executeInternal(args: Map<String, JsonElement>, sessionId: String?): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "read_file 缺少 path 参数")
                return ToolResult.Error("路径参数缺失", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file 文件不存在: $path")
                return ToolResult.Error("文件不存在: $path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // F-4：行窗口上界——显式 end_line 与 start_line+MAX_LINES 取较小值，缺省则按 MAX_LINES 上限。
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // F-4：total_lines 自动决策——文件 ≤ AUTO_TOTAL_LINES_BYTES 时才统计总行数，
            // 大文件跳过统计（返回 -1），避免为求一个展示数字把整个大文件读一遍。
            val forceTotalLines = args["force_total_lines"]?.jsonPrimitive?.booleanOrNull ?: false
            val countTotalLines = forceTotalLines || fileAccess.fileSize(path) <= AUTO_TOTAL_LINES_BYTES
            var totalLines = if (countTotalLines) 0 else -1

            // 逐行流式读取，避免 readText() 整篇进内存导致移动端 OOM；
            // 只保留 [startLine, endCap] 窗口，并在累计字节超过 MAX_BYTES 时提前停止。
            val sb = StringBuilder()
            var emittedLines = 0
            var byteCount = 0
            var truncatedByBytes = false
            var lineNo = 0
            fileAccess.readLines(path).forEach { line ->
                lineNo++
                if (countTotalLines) totalLines = lineNo
                if (lineNo < startLine) return@forEach
                if (lineNo > endCap) {
                    // 已越过窗口，但仍需继续计数以得到准确 total_lines（仅在需统计时）。
                    return@forEach
                }
                if (!truncatedByBytes) {
                    val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                    if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                        truncatedByBytes = true
                    } else {
                        if (emittedLines > 0) sb.append('\n')
                        sb.append(line)
                        byteCount += lineBytes
                        emittedLines++
                    }
                }
            }

            val lastEmittedLine = startLine + emittedLines - 1
            // 用户想要的窗口末行：给了 end_line 取 min(end_line, EOF)，否则到 EOF。
            // 我们只发到了 lastEmittedLine，若它落在窗口末行之前，说明被截断、还有内容可读。
            val wantedEnd = if (countTotalLines) {
                if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            } else {
                // F-4：未统计总行数时无法精确判断是否读到 EOF，统一按「已截断」提示分页续读。
                requestedEnd ?: Int.MAX_VALUE
            }
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "已达 ${MAX_BYTES / 1024}KB 上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                !countTotalLines -> "文件较大，未统计总行数；如内容被截断，从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                else -> "已达 $MAX_LINES 行上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
            }

            FileLogger.v(TAG, "read_file 成功 path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
            val resultMap = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(sb.toString()),
                "total_lines" to JsonPrimitive(totalLines),
                "start_line" to JsonPrimitive(startLine),
                "end_line" to JsonPrimitive(maxOf(lastEmittedLine, startLine - 1)),
                "read_lines" to JsonPrimitive(emittedLines),
                "truncated" to JsonPrimitive(truncated)
            )
            if (note != null) resultMap["note"] = JsonPrimitive(note)

            // F-3：read 落库快照（operation=read，无差异），支撑「撤销编辑」时回溯读前内容。
            if (sessionId != null) recordHunk(sessionId, path, operation = "read", hunk = "", oldContent = sb.toString(), newContent = sb.toString())

            ToolResult.Success(JsonObject(resultMap))
        } catch (e: Exception) {
            FileLogger.e(TAG, "read_file 异常", e)
            ToolResult.Error(e.message ?: "读取文件失败", "READ_ERROR")
        }
    }

    /** F-3：写入一条 hunk 记录，失败静默不阻塞主流程。 */
    private suspend fun recordHunk(
        sessionId: String,
        path: String,
        operation: String,
        hunk: String,
        oldContent: String,
        newContent: String
    ) {
        try {
            val entity = FileEditHunkEntity(
                id = "hunk_${UUID.randomUUID().toString().replace("-", "")}",
                sessionId = sessionId,
                filePath = path,
                operation = operation,
                hunk = hunk,
                oldContent = oldContent.take(HUNK_SNAPSHOT_MAX_CHARS),
                newContent = newContent.take(HUNK_SNAPSHOT_MAX_CHARS),
                createdAtMs = System.currentTimeMillis()
            )
            if (readMode.currentMode() == DataReadMode.V2) {
                v2Agent.insertFileEditHunk(
                    id = entity.id, sessionId = entity.sessionId, filePath = entity.filePath,
                    operation = entity.operation, hunk = entity.hunk,
                    oldContent = entity.oldContent, newContent = entity.newContent,
                    createdAtMs = entity.createdAtMs
                )
            } else {
                fileEditHunkDao.upsert(entity)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "记录文件 hunk 失败: $path", e)
        }
    }

    private companion object {
        /** 单次最多返回的行数，超出截断并提示用 start_line 续读。 */
        const val MAX_LINES = 2000
        /** 单次最多返回的字节数（UTF-8，约 200KB），防止超大行撑爆内存/上下文。 */
        const val MAX_BYTES = 200 * 1024
        /** F-4：文件大小超过此值（字节，1MB）时默认不统计总行数。 */
        const val AUTO_TOTAL_LINES_BYTES = 1024 * 1024L
    }
}

/**
 * 写入整个文件，文件不存在时会自动创建（含父目录）。
 *
 * 通过 overwrite 参数吸收了旧 create_file 的「不覆盖已有文件」语义：
 * overwrite=false 且目标已存在时报错，可用于安全地新建文件。
 */
class WriteFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val fileEditHunkDao: FileEditHunkDao,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
) : AgentTool() {
    override val name = "writeFile"
    override val description = "向指定路径写入完整文件内容。若文件存在则根据 overwrite 决定是否覆盖。支持写入工作区文件或容器系统文件。局部修改推荐使用 editFile。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    /** L3 结构化结果协议：产出 file.written 类型；L7 事件总线据此广播缓存失效。 */
    override val provides = setOf("file.written")
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "content" to ToolParameter("content", ParameterType.STRING, "文件内容", required = true),
        "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "目标已存在时是否覆盖。默认 true；设为 false 时若文件已存在则报错。", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "未知文件"
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认写入文件",
            summary = "AI 请求写入 $path",
            details = "字符数：${content.length}\n行数：${content.lines().size}\n允许覆盖：${if (overwrite) "是" else "否"}",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = executeInternal(args, null)

    /** F-3：优先走 executeWithContext 以获得会话 ID，用于 hunk 落库；无上下文时降级为不落库。 */
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return executeInternal(args, context.sessionId)
    }

    /** L7 事件自声明：写入成功后广播 file.written（field 级 hash/diff 由执行结果附带）。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull ?: return null
        return ToolEvent.FileWritten(path = path, size = 0, hash = "", sessionId = context.sessionId)
    }

    private suspend fun executeInternal(args: Map<String, JsonElement>, sessionId: String?): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "write_file 缺少 path 参数")
                return ToolResult.Error("路径参数缺失", "MISSING_PATH")
            }
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} 字符, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file 文件已存在且 overwrite=false: $path")
                return ToolResult.Error("文件已存在: $path（overwrite=false）", "FILE_EXISTS")
            }

            // 写前留存旧内容，供生成「旧→新」差异（与 edit_file 同构，UI 据此渲染彩色 diff）。
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // 生成统一差异文本：新建文件按「整体新增」呈现（旧内容视为空，避免一行伪删除）；
            // 覆盖写则计算旧→新的行级增删。LineDiff 为 O(n·m) 内存，超大文件重写时跳过 LCS、
            // 退化为整体新增，防止移动端因构造 DP 表而 OOM。
            val diff = buildWriteDiff(existed, oldContent, content)
            val added = diff.lines().count { it.startsWith("+") }
            val removed = diff.lines().count { it.startsWith("-") }
            val hunksJson = JsonArray(listOf(
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(1),
                    "added" to JsonPrimitive(added),
                    "removed" to JsonPrimitive(removed),
                    "diff" to JsonPrimitive(diff)
                ))
            ))

            // F-3：write 落库快照（operation=write，含旧→新差异），支撑「撤销编辑」。
            if (sessionId != null) {
                try {
                    val hunkEntity = FileEditHunkEntity(
                        id = "hunk_${UUID.randomUUID().toString().replace("-", "")}",
                        sessionId = sessionId,
                        filePath = path,
                        operation = "write",
                        hunk = diff,
                        oldContent = oldContent.take(HUNK_SNAPSHOT_MAX_CHARS),
                        newContent = content.take(HUNK_SNAPSHOT_MAX_CHARS),
                        createdAtMs = System.currentTimeMillis()
                    )
                    if (readMode.currentMode() == DataReadMode.V2) {
                        v2Agent.insertFileEditHunk(
                            id = hunkEntity.id, sessionId = hunkEntity.sessionId, filePath = hunkEntity.filePath,
                            operation = hunkEntity.operation, hunk = hunkEntity.hunk,
                            oldContent = hunkEntity.oldContent, newContent = hunkEntity.newContent,
                            createdAtMs = hunkEntity.createdAtMs
                        )
                    } else {
                        fileEditHunkDao.upsert(hunkEntity)
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "记录文件 hunk 失败: $path", e)
                }
            }

            FileLogger.v(TAG, "write_file 成功 path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
            ToolResult.Success(
                JsonObject(mapOf(
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.length),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "write_file 异常", e)
            ToolResult.Error(e.message ?: "写入文件失败", "WRITE_ERROR")
        }
    }

    /**
     * 构造 write_file 的统一差异文本（每行以 `+`/`-`/` ` 起头）：
     * - 新建文件：旧内容视为空，整篇按「全部新增」呈现，不跑 LCS；
     * - 覆盖写且规模可控：计算旧→新的行级 LCS 差异；
     * - 覆盖写但任一侧行数超过 [MAX_DIFF_LINES]：跳过 O(n·m) 的 LCS（移动端易 OOM），
     *   退化为整篇「全部新增」，仅展示落盘后的内容。
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file 文件过大跳过 LCS 差异 (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** 旧/新任一侧行数超过此值即跳过 LCS：DP 表为 O(n·m) ints，过大会拖垮移动端内存。 */
        const val MAX_DIFF_LINES = 2000
    }
}
