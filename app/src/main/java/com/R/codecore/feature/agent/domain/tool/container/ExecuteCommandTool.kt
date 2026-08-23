package com.R.codecore.feature.agent.domain.tool.container

import com.R.codecore.feature.agent.domain.container.BoundedOutput
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.container.CommandEvent
import com.R.codecore.feature.agent.domain.container.CommandResult
import com.R.codecore.feature.agent.domain.container.LinuxContainerEngine
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.permission.DangerousCommandGuard
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.StreamingAgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.agent.domain.tool.ToolStreamEvent
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * 命令在当前选中工作区目录下执行，使 AI 的 shell 操作（npm install、git 等）
 * 与文件工具作用于同一目录。
 *
 * 同时实现 [StreamingAgentTool]：优先逐行流式输出，让聊天里能实时看到命令执行过程；
 * [execute] 作为非流式兜底保留，最终聚合结果两者一致（喂回模型不变）。
 *
 * 联动检测：AI 通过 Bash 直接 `apk add` / `apk del` 安装/卸载环境时，不会走
 * [LinuxContainerEngine.installBundle] / [uninstallBundle]，导致终端功能包页的
 * 安装状态与实际容器不一致。命令执行完成后若命中 apk 增删，自动触发一次
 * [LinuxContainerEngine.refreshBundleStatesFromApk] 校准 bundle 状态。
 */
class ExecuteCommandTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerEngine: LinuxContainerEngine
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** 默认超时（秒），与 [LinuxContainerEngine.DEFAULT_TIMEOUT_MS] 对齐。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时上限（秒），与 [LinuxContainerEngine.MAX_TIMEOUT_MS] 对齐。
         *  在 aarch64 手机上经 qemu 模拟 x86_64 构建 Android 时，gradle release 构建常超
         *  30 分钟，故给足 3600 秒。常用建议 timeout：
         *    - gradlew assembleDebug/assembleRelease: 1800~2400 秒
         *    - 大项目 + R8 fullMode：3600 秒（上限）
         *    - apk add/sdkmanager 拉组件: 480~900 秒
         */
        const val MAX_TIMEOUT_SECONDS = 3_600L

        /** strict 模式错误信息中附带的末尾输出行数，用于帮助 AI 定位失败点。 */
        private const val STRICT_ERROR_TAIL_LINES = 20

        /** 命中即视为「改动了 apk 世界」的命令片段，触发 bundle 状态联动刷新。 */
        private val APK_MUTATION_REGEX = Regex("""\bapk\s+(add|del|remove|fix|upgrade|delete)\b""")

        /**
         * T-2：只读命令白名单。这些命令无副作用，偶发失败（网络抖动、容器瞬态问题）时允许自动重试一次；
         * 其余命令（可能产生副作用）一律不重试，避免重复执行造成意外影响。
         */
        private val READ_ONLY_COMMAND_REGEX = Regex(
            """^\s*(?:ls|pwd|cat|head|tail|wc|echo|date|whoami|which|type|uname|env|printenv|du|df|stat|file|find|grep|rg|git\s+(?:status|log|diff|branch|remote|rev-parse|show|ls-files|stash\s+list))\b.*""",
            RegexOption.IGNORE_CASE
        )
    }

    /** L7 事件自声明：shell 命令可能改动工作区文件，广播 file.mutated 保守失效文件类缓存。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val command = (toolCall.arguments["command"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ToolEvent.FileSystemMutated(reason = command.take(200), sessionId = context.sessionId)
    }

    /** 联动刷新用的后台作用域：fire-and-forget，不阻塞工具结果返回。 */
    private val linkageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 危险命令（DangerousCommandGuard）与 BusyBox 兼容性提示合并为同一提示块。
     * 在权限卡 details 与命令输出末尾两处统一调用，一条命令一次展示。
     */
    private fun mergedWarnBlock(command: String): String? {
        val hints = listOfNotNull(
            DangerousCommandGuard.warnMessage(command),
            BusyBoxCompatibilityGuard.warningMessage(command)
        )
        if (hints.isEmpty()) return null
        return hints.joinToString("\n")
    }

    /** 把合并后的危险/兼容提示拼到命令输出末尾；无提示则原样返回。 */
    private fun appendHints(command: String, output: String): String {
        val hint = mergedWarnBlock(command) ?: return output
        return output.trimEnd() + "\n\n" + hint
    }

    /**
     * 若命令命中 apk 增删，异步触发一次 bundle 状态联动刷新（从容器真实 apk 世界校准）。
     * 幂等、失败静默，不干扰主流程。
     */
    private fun maybeSyncBundleStates(command: String) {
        if (!APK_MUTATION_REGEX.containsMatchIn(command)) return
        FileLogger.i(TAG, "检测到 apk 增删命令，联动刷新 bundle 安装状态")
        linkageScope.launch {
            runCatching { containerEngine.refreshBundleStatesFromApk() }
                .onFailure { FileLogger.w(TAG, "联动刷新 bundle 状态失败: ${it.message}", it) }
        }
    }

    override val name = "Bash"
    override val description = "在当前执行环境（本地 Linux 容器或远程 SSH 服务器）中执行 Shell 命令。支持 npm、git 等绝大多数终端操作。对于耗时任务（如安装大量依赖、启动服务器等），请不要在此命令末尾加 '&' 挂后台，而是强烈建议改用 `terminal` 工具（action=\"start\"）来创建常驻终端页面，这样才能方便后续查看实时输出结果和管理进程。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "The shell command to execute",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。耗时命令（如安装依赖）可适当调大。",
            required = false
        ),
        "strict" to ToolParameter(
            name = "strict",
            type = ParameterType.BOOLEAN,
            description = "strict=true 时命令非零退出码返回 ToolResult.Error（错误信息附末尾输出），便于 AI 快速感知失败；默认 false 时保持旧行为（非零退出码仍返回 Success 并含完整输出）。",
            required = false
        )
    )

    /** 解析 timeout（秒）参数并钳到合法范围，返回毫秒；缺省用默认值。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    /**
     * 解析最终生效超时：检测到无界循环（while true/until false/for((;;)) 等）时，
     * 把超时钳制到 [CommandLoopGuard.GUARDED_TIMEOUT_SECONDS]，避免长段刷屏/空耗。
     * @return (超时毫秒, 是否因无界循环被钳制)
     */
    private fun resolveEffectiveTimeoutMs(
        args: Map<String, JsonElement>,
        command: String
    ): Pair<Long, Boolean> {
        val base = resolveTimeoutMs(args)
        if (CommandLoopGuard.hasUnboundedLoop(command)) {
            val guarded = CommandLoopGuard.GUARDED_TIMEOUT_SECONDS * 1000L
            return if (guarded < base) guarded to true else base to false
        }
        return base to false
    }

    /** 解析 strict 参数：缺省 false，保持旧行为兼容。 */
    private fun resolveStrict(args: Map<String, JsonElement>): Boolean =
        args["strict"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val (timeoutMs, loopGuarded) = resolveEffectiveTimeoutMs(args, command)
        val timeoutSeconds = timeoutMs / 1000L
        val strict = resolveStrict(args)
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行命令",
            summary = command,
            details = buildString {
                append("将在当前执行环境中执行。\n超时：${timeoutSeconds} 秒")
                if (CommandLoopGuard.isForkBomb(command)) {
                    append("\n⚠️ ${CommandLoopGuard.forkBombWarningMessage()}")
                }
                if (loopGuarded) {
                    append("\n⚠️ ${CommandLoopGuard.warningMessage()}超时已自动钳制为 ${timeoutSeconds} 秒，到点强制终止。")
                }
                if (strict) {
                    append("\n严格模式：非零退出码将按失败返回")
                }
                mergedWarnBlock(command)?.let { append("\n$it") }
            },
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")
        if (CommandLoopGuard.isForkBomb(command)) {
            FileLogger.e(TAG, "拦截 fork bomb 命令: $command")
            return ToolResult.Error(CommandLoopGuard.forkBombWarningMessage() + "禁止执行此类命令。")
        }
        DangerousCommandGuard.blockReason(command)?.let { reason ->
            FileLogger.e(TAG, "拦截危险命令: $command")
            return ToolResult.Error(reason)
        }

        return try {
            // 在当前工作区目录内执行，与文件工具保持同一根目录
            val workdir = workspaceRepository.currentPath()
            val (timeoutMs, loopGuarded) = resolveEffectiveTimeoutMs(args, command)
            val strict = resolveStrict(args)
            FileLogger.d(TAG, "execute_command (timeout=${timeoutMs}ms, strict=$strict, loopGuarded=$loopGuarded): $command")
            if (loopGuarded) {
                FileLogger.w(TAG, "命令含无界循环，超时钳制为 ${timeoutMs / 1000}s: $command")
            }
            // 用 runCommandSyncWithExit 拿真实退出码（超时/异常时为 null），供 strict 模式判定失败；
            // 其 output 与旧 runCommandSync 返回的同一份 BoundedOutput 限幅结果，非 strict 行为不变。
            var result: CommandResult = commandEngine.runCommandSyncWithExit(command, workdir, timeoutMs)
            // T-2：只读命令偶发失败时自动重试一次（白名单外 / 成功执行不重试，避免副作用与浪费）。
            if (isReadOnlyCommand(command) && result.exitCode != 0) {
                FileLogger.w(TAG, "只读命令失败(exit=${result.exitCode})，自动重试一次: $command")
                result = commandEngine.runCommandSyncWithExit(command, workdir, timeoutMs)
            }
            FileLogger.v(TAG, "execute_command 完成，输出 ${result.output.length} 字符，exit=${result.exitCode}")
            maybeSyncBundleStates(command)
            aggregateResult(appendHints(command, result.output), result.exitCode, strict)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command 失败: $command", e)
            ToolResult.Error("执行命令失败: ${e.message}")
        }
    }

    /**
     * 流式执行：逐行 emit [ToolStreamEvent.Progress]，命令结束 emit [ToolStreamEvent.Completed]，
     * 其最终结果与 [execute] 等价（同样经 [BoundedOutput] 限幅：超大输出仅保留开头+结尾），
     * 保证喂回模型的内容一致且不会撑爆上下文。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.R.codecore.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("缺少必需参数: command")))
            return@flow
        }
        if (CommandLoopGuard.isForkBomb(command)) {
            FileLogger.e(TAG, "拦截 fork bomb 命令(流式): $command")
            emit(ToolStreamEvent.Completed(ToolResult.Error(CommandLoopGuard.forkBombWarningMessage() + "禁止执行此类命令。")))
            return@flow
        }
        DangerousCommandGuard.blockReason(command)?.let { reason ->
            FileLogger.e(TAG, "拦截危险命令(流式): $command")
            emit(ToolStreamEvent.Completed(ToolResult.Error(reason)))
            return@flow
        }
        val strict = resolveStrict(args)

        // 限幅累积：喂回模型的最终结果只保留开头+结尾，避免超大输出撑爆上下文。
        var accumulated = BoundedOutput()
        // 记录命令真实退出码：CommandEvent.Exit 是流结束前最后一个事件，超时/异常时为 null。
        var exitCode: Int? = null
        try {
            val workdir = workspaceRepository.currentPath()
            val (timeoutMs, loopGuarded) = resolveEffectiveTimeoutMs(args, command)
            FileLogger.d(TAG, "execute_command(流式, timeout=${timeoutMs}ms, strict=$strict, loopGuarded=$loopGuarded): $command")
            if (loopGuarded) {
                FileLogger.w(TAG, "命令含无界循环，超时钳制为 ${timeoutMs / 1000}s(流式): $command")
            }
            // 单次流式执行；@retry 时复用同一累积器，使重试输出并入结果。
            suspend fun runOnce() {
                commandEngine.runCommandStream(command, workdir, timeoutMs).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> {
                            accumulated.append(event.text)
                            accumulated.append("\n")
                            emit(ToolStreamEvent.Progress(event.text))
                        }
                        is CommandEvent.Exit -> { exitCode = event.code }
                    }
                }
            }
            runOnce()
            // T-2：只读命令偶发失败时自动重试一次（白名单外 / 成功执行不重试）。
            if (isReadOnlyCommand(command) && exitCode != 0) {
                FileLogger.w(TAG, "只读命令失败(exit=$exitCode)，自动重试一次: $command")
                exitCode = null
                runOnce()
            }
            FileLogger.v(TAG, "execute_command(流式) 完成，输出 ${accumulated.totalChars} 字符，exit=$exitCode")
            maybeSyncBundleStates(command)
            emit(ToolStreamEvent.Completed(aggregateResult(appendHints(command, accumulated.build()), exitCode, strict)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：底层 flow 异常终止时，已逐行 emit 给用户的 Progress 仍应作为最终结果保留，
            // 而不是被这里抛出的空 Error 覆盖掉（否则模型只看到“执行失败”，之前展示的输出全丢）。
            FileLogger.e(TAG, "execute_command(流式) 异常(已保留此前输出 ${accumulated.totalChars} 字符): $command", e)
            val saved = accumulated.build()
            val result = if (strict) {
                // strict 模式：执行失败即视为错误，错误信息带已捕获的末尾输出帮助 AI 定位。
                val tail = saved.trimEnd().split("\n").takeLast(STRICT_ERROR_TAIL_LINES).joinToString("\n")
                ToolResult.Error(
                    if (tail.isNotBlank()) "执行命令失败: ${e.message}\n末尾输出...\n$tail"
                    else "执行命令失败: ${e.message}"
                )
            } else if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(saved))
            } else {
                ToolResult.Error("执行命令失败: ${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }

    /** T-2：命令是否命中只读白名单，决定是否允许自动重试。 */
    private fun isReadOnlyCommand(command: String): Boolean =
        READ_ONLY_COMMAND_REGEX.containsMatchIn(command.trim())

    /**
     * 按 strict 聚合最终结果：strict=true 且命令未以退出码 0 结束（非零，或超时/异常导致的 null）
     * → [ToolResult.Error]（错误信息附末尾输出）；否则保持旧行为 [ToolResult.Success]（纯文本输出）。
     */
    private fun aggregateResult(output: String, exitCode: Int?, strict: Boolean): ToolResult =
        if (strict && exitCode != 0) {
            ToolResult.Error(strictExitError(exitCode, output))
        } else {
            ToolResult.Success(JsonPrimitive(output))
        }

    /** strict 模式错误信息：附退出码 + 末尾若干行输出，帮助 AI 定位失败点。 */
    private fun strictExitError(exitCode: Int?, output: String): String {
        val tail = output.trimEnd().split("\n").takeLast(STRICT_ERROR_TAIL_LINES).joinToString("\n")
        val reason = if (exitCode != null) "命令退出码非零(exit=$exitCode)" else "命令未正常结束(超时或异常)"
        return if (tail.isNotBlank()) "$reason: 末尾输出...\n$tail" else reason
    }
}
