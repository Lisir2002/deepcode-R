package com.core.deepcode.feature.agent.domain.tool.container

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.container.CommandEngine
import com.core.deepcode.feature.agent.domain.permission.DangerousCommandGuard
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.PendingToolPermission
import com.core.deepcode.feature.agent.domain.tool.ToolCall
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolEvent
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import com.core.deepcode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * 工程编程（run_code 轻量版）：模型提交一段可执行脚本（sh / python3），宿主在容器/远程环境
 * 一次性执行并返回结构化结果 `{value, stdout, stderr, exitCode}`。
 *
 * 对齐 DSH run_code / CodeRunResult 契约（轻量形态，不移植类型化 SDK 全套）：
 * - **批量执行**：把多轮 Bash / 文件工具往返压成单次，中间值不进对话历史，省 token（移动端价值高）；
 * - **canonical output**：脚本内约定用 `echo '{"ok":true}'` 输出一行 JSON，宿主解析为 `value` 字段，
 *   减少模型对输出做二次解析；
 * - **结构化超时**：脚本超时（[CommandEngine] 看门狗强杀）返回 `ToolResult.Error(code="TOOL_TIMEOUT")`，
 *   模型可据此信号重试 / 换策略（对齐 DSH 结构化超时护栏）；
 * - **复用现有护栏**：脚本内容经 [DangerousCommandGuard] 评估（高危操作直接拦截），权限走 ASK 审批，
 *   与 `Bash` 同一套治理。
 *
 * stdout/stderr 分离：容器执行层把 stderr 合并进 stdout（`redirectErrorStream(true)`），
 * 因此用 shell 重定向到临时文件 + 分隔标记（`__RUNCODE_*__`）在脚本执行后分拣，还原两路输出。
 */
class RunCodeTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {

    private companion object {
        const val TAG = "RunCodeTool"

        /** 默认超时（秒）。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** run_code 脚本超时上限（秒）：脚本化批量执行通常是短任务，600 秒足够。 */
        const val MAX_TIMEOUT_SECONDS = 600L

        /** 结构化超时错误码：模型可据 `error.code == TOOL_TIMEOUT` 决定重试或换策略。 */
        const val ERROR_TIMEOUT = "TOOL_TIMEOUT"

        /** 超时错误信息附带的末尾输出行数，帮助 AI 定位卡点。 */
        private const val ERROR_TAIL_LINES = 20

        /** shell 分隔标记（命名足够独特，避免与脚本自身输出冲突）。 */
        const val EXIT_MARKER = "__RUNCODE_EXIT__"
        const val STDOUT_MARKER = "__RUNCODE_STDOUT__"
        const val STDERR_MARKER = "__RUNCODE_STDERR__"
        const val HEREDOC_DELIMITER = "RC_EOF"
    }

    override val name = "run_code"
    override val description = "在容器/远程执行环境内一次性批量执行一段脚本（sh 或 python3），" +
        "可包含多步操作（建目录、批量读写、跑测试/构建等），把多轮工具调用压成一次，省 token。" +
        "脚本内建议用 `echo '{\"key\":\"value\"}'` 输出一行 JSON 作为结构化结果（宿主解析到返回的 value 字段）。" +
        "返回 {value, stdout, stderr, exitCode}；脚本超时返回错误码 TOOL_TIMEOUT。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "script" to ToolParameter(
            name = "script",
            type = ParameterType.STRING,
            description = "要执行的脚本内容（多行）。默认用 sh（Alpine BusyBox ash）解释执行；language=python3 时用 python3。",
            required = true
        ),
        "language" to ToolParameter(
            name = "language",
            type = ParameterType.STRING,
            description = "脚本语言：sh（默认，BusyBox 兼容）或 python3。",
            required = false,
            enum = listOf("sh", "python3")
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "脚本最长执行时间（秒），超时强制终止并返回 TOOL_TIMEOUT。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        ),
        "workdir" to ToolParameter(
            name = "workdir",
            type = ParameterType.STRING,
            description = "脚本工作目录（容器内路径），默认当前工作区根目录 ~/workspace。",
            required = false
        )
    )

    /** 解析 timeout（秒）并钳到合法范围，返回毫秒。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    /** 解析语言：缺省 sh，非法值回退 sh。 */
    private fun resolveLanguage(args: Map<String, JsonElement>): String {
        val raw = args["language"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        return if (raw == "python3") "python3" else "sh"
    }

    /** L7 事件自声明：脚本可能改动工作区文件，广播 file.mutated 保守失效文件类缓存（与 Bash 一致）。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: com.core.deepcode.feature.agent.domain.model.AgentContext
    ): ToolEvent? {
        val script = (toolCall.arguments["script"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ToolEvent.FileSystemMutated(reason = script.take(200), sessionId = context.sessionId)
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val script = args["script"]?.jsonPrimitive?.contentOrNull ?: "未知脚本"
        val language = resolveLanguage(args)
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行脚本",
            summary = script.lines().firstOrNull()?.trim()?.take(60).orEmpty(),
            details = buildString {
                append("将在当前执行环境（容器/远程）中执行 $language 脚本。\n超时：${timeoutSeconds} 秒\n---\n")
                append(script.take(2000))
                DangerousCommandGuard.warnMessage(script)?.let { append("\n$it") }
            },
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val script = args["script"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: script", "MISSING_SCRIPT")
        if (script.isBlank()) return ToolResult.Error("script 不能为空", "EMPTY_SCRIPT")

        // 复用现有危险命令护栏：脚本含高危操作（下载即执行 / 破坏系统 / 灾难性 rm 等）直接拦截。
        DangerousCommandGuard.blockReason(script)?.let { reason ->
            FileLogger.e(TAG, "拦截危险脚本: $reason")
            return ToolResult.Error(reason, "DANGEROUS_SCRIPT")
        }

        val language = resolveLanguage(args)
        val timeoutMs = resolveTimeoutMs(args)
        val workdir = args["workdir"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: workspaceRepository.currentPath()
        val id = UUID.randomUUID().toString().replace("-", "").take(8)

        return try {
            FileLogger.d(TAG, "run_code (lang=$language, timeout=${timeoutMs}ms, workdir=$workdir): ${script.take(120)}")
            val result = commandEngine.runCommandSyncWithExit(
                buildCommand(script, language, id),
                workdir,
                timeoutMs
            )
            // 结构化超时：区别于普通失败，返回 TOOL_TIMEOUT 让模型可据此重试/换策略。
            if (result.timedOut) {
                FileLogger.w(TAG, "run_code 超时(${timeoutMs}ms)已强制终止")
                val tail = result.output.trimEnd().split("\n").takeLast(ERROR_TAIL_LINES).joinToString("\n")
                return ToolResult.Error(
                    if (tail.isNotBlank()) {
                        "脚本执行超时（超过 ${timeoutMs / 1000} 秒已强制终止）。末尾输出...\n$tail"
                    } else {
                        "脚本执行超时（超过 ${timeoutMs / 1000} 秒已强制终止）。"
                    },
                    ERROR_TIMEOUT
                )
            }
            FileLogger.v(TAG, "run_code 完成，输出 ${result.output.length} 字符，engine exit=${result.exitCode}")
            buildSuccess(result.output, result.exitCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "run_code 执行失败: ${e.message}", e)
            ToolResult.Error("脚本执行失败: ${e.message}", "TOOL_EXECUTION_FAILED")
        }
    }

    /**
     * 构造一次性 wrapper 命令：把脚本经 heredoc 写入 /tmp，执行后分拣 stdout/stderr 并输出分隔标记。
     * 脚本进程退出码经 `rc=$?` 捕获，避免依赖外层 shell 的整体退出码。
     */
    private fun buildCommand(script: String, language: String, id: String): String {
        val ext = if (language == "python3") "py" else "sh"
        val runner = if (language == "python3") "python3" else "sh"
        val scriptPath = "/tmp/rc_$id.$ext"
        val outPath = "/tmp/rc_$id.out"
        val errPath = "/tmp/rc_$id.err"
        return buildString {
            append("cat > $scriptPath <<'$HEREDOC_DELIMITER'\n")
            append(script)
            append("\n$HEREDOC_DELIMITER\n")
            append("$runner $scriptPath > $outPath 2> $errPath\n")
            append("rc=\$?\n")
            append("echo \"$EXIT_MARKER:\$rc\"\n")
            append("echo \"$STDOUT_MARKER\"\n")
            append("cat $outPath\n")
            append("echo \"$STDERR_MARKER\"\n")
            append("cat $errPath\n")
            append("rm -f $scriptPath $outPath $errPath\n")
        }
    }

    /**
     * 组装结构化成功结果：解析分隔标记还原 stdout/stderr/exitCode，
     * 并从 stdout 提取 canonical JSON 作为 value（取最后一个能解析为合法 JSON 的行）。
     */
    private fun buildSuccess(rawOutput: String, engineExitCode: Int?): ToolResult {
        val stdout = extractBetween(rawOutput, STDOUT_MARKER, STDERR_MARKER)
        val stderr = extractAfter(rawOutput, STDERR_MARKER)
        val scriptExit = parseMarkerInt(rawOutput, EXIT_MARKER)
        val canonical = parseCanonicalJson(stdout)
        return ToolResult.Success(JsonObject(mapOf(
            "value" to (canonical ?: JsonNull),
            "stdout" to JsonPrimitive(stdout.trimEnd()),
            "stderr" to JsonPrimitive(stderr.trimEnd()),
            "exitCode" to JsonPrimitive(scriptExit ?: engineExitCode ?: -1)
        )))
    }

    /** 解析形如 `__RUNCODE_EXIT__:<int>` 的标记行。 */
    private fun parseMarkerInt(raw: String, marker: String): Int? {
        val line = raw.lineSequence().lastOrNull { it.startsWith("$marker:") } ?: return null
        return line.removePrefix("$marker:").trim().toIntOrNull()
    }

    /** 取 [startMarker] 之后、第一个 [endMarker] 之前的文本。 */
    private fun extractBetween(raw: String, startMarker: String, endMarker: String): String {
        val startIdx = raw.indexOf(startMarker)
        if (startIdx < 0) return ""
        val contentStart = startIdx + startMarker.length
        val endIdx = raw.indexOf(endMarker, contentStart)
        if (endIdx < 0) return raw.substring(contentStart)
        return raw.substring(contentStart, endIdx)
    }

    /** 取第一个 [marker] 之后的文本。 */
    private fun extractAfter(raw: String, marker: String): String {
        val idx = raw.indexOf(marker)
        if (idx < 0) return ""
        return raw.substring(idx + marker.length)
    }

    /** canonical output：从 stdout 逐行解析，取最后一个能解析为合法 JSON 的行；无则返回 null。 */
    private fun parseCanonicalJson(stdout: String): JsonElement? {
        var last: JsonElement? = null
        stdout.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            runCatching { Json.parseToJsonElement(trimmed) }
                .onSuccess { last = it }
        }
        return last
    }
}
