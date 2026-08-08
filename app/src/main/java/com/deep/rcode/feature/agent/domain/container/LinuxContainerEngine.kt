package com.deep.rcode.feature.agent.domain.container

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundles
import com.deep.rcode.feature.terminal.data.repository.TerminalBundleRepository
import com.deep.rcode.feature.workspace.domain.WorkspacePathMapper
import com.deep.rcode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 PRoot 容器命令执行后端，实现 [CommandEngine]。
 *
 * 原有逻辑零变化——仅加 `: CommandEngine` 并给公开方法补 `override`。
 * [CommandEvent] 与 [CommandResult] 已提升为顶层类型（见 [CommandEngine.kt]），
 * 本类不再自行定义。PRoot 专属方法（[startStdioProcess]/[buildProotInvocation]/
 * [incPromptInFlight] 等）不属于接口，仅供本地 MCP stdio / 凭据 helper / 终端 PTY 使用。
 */
/**
 * 一次 PRoot 调用的完整描述：可执行文件 + 参数列表 + 环境变量。
 *
 * [argv] 的第 0 个元素即 proot 二进制路径，其余为参数。
 *
 * 两种消费方都要拿到「完整 argv」：
 *  - [ProcessBuilder] 直接接收 argv 列表；
 *  - Termux TerminalSession 的 cmd 仅用于 execvp 查找可执行文件，真正的 argv 由它的
 *    args 参数原样构成（native execvp(cmd, argv)，不会自动补 argv[0]），故 args 必须
 *    也是「含 argv[0]=proot 二进制」的完整 argv，否则选项整体错位一位、proot 会把
 *    rootfs 路径误当客户机程序（"is not a regular file"）。[executable] 只用于前者的 cmd 槽位。
 */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>
) {
    val executable: String get() = argv.first()

    /**
     * 供 Termux [com.termux.terminal.TerminalSession] 使用的完整环境数组（"KEY=VALUE"）。
     *
     * [ProcessBuilder] 的 environment() 初始即为父进程（App）环境的副本，再叠加 [env]，
     * 因此 proot 能拿到 ANDROID_ROOT/ANDROID_DATA/LD_LIBRARY_PATH 等系统变量；但
     * TerminalSession 接收的是「完整、不继承父进程」的环境数组，只喂 [env] 会让 proot
     * 这个动态链接的 Android 可执行文件因缺系统环境而 exec 失败、瞬间退出（终端表现为
     * 「会话已结束」而日志无其他报错）。故在此显式合并父进程环境，复刻 ProcessBuilder 语义。
     */
    val ptyEnvArray: Array<String>
        get() = (System.getenv() + env).map { "${it.key}=${it.value}" }.toTypedArray()
}

@Singleton
class LinuxContainerEngine @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: com.deep.rcode.feature.settings.data.repository.ContainerSettingsRepository,
    private val workspacePathMapper: WorkspacePathMapper,
    private val bundleRepository: TerminalBundleRepository
) : CommandEngine {
    /** 容器初始化的实时进度，供所有入口（终端页/AI/后台终端/MCP）共享同一份状态。 */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** 串行化容器初始化（含后台 initScope 内的任务创建与执行），避免多入口并发重复解压/配置。 */
    private val initMutex = Mutex()

    /**
     * 容器初始化的独立协程作用域：不随任何页面/调用方取消而中断，
     * 保证退出终端页后初始化仍在后台继续，下次进入可复用或等待其完成。
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前在途的初始化 job（受 [initMutex] 保护），完成后置 null。 */
    private var initJob: Job? = null

    /**
     * 当前选中的 profile（缓存自 [containerSettingsRepository.activeProfileIdFlow]，避免同步读 DataStore）。
     * 启动首帧为内置 Alpine（等同改动前）；profile 切换后由 flow collector 更新。fallback 到内置保证安全。
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    /** 按 id 解析 profile：内置返回 [ContainerProfile.BUILTIN_ALPINE]，否则从自定义列表找，找不到回退内置。 */
    private suspend fun resolveProfile(id: String): ContainerProfile {
        if (id == ContainerProfile.BUILTIN_ID) return ContainerProfile.BUILTIN_ALPINE
        return containerSettingsRepository.customProfilesFlow.first()
            .firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 当前正在途的凭据请求计数（自定义 credential helper 阻塞等 app 弹窗回填的对数）。
     *
     * 由 [com.deep.rcode.feature.credentials.data.CredentialRequestBridge] 在收到 helper 的 cred-req 时 inc、
     * 写回 cred-resp 时 dec。[launchKillWatchdog] 据此放宽超时——helper 在途时用户的 git 命令是「正等
     * 用户填凭据」而非「卡死」，watchdog 不应按常规超时强杀，详见其改造注释。
     */
    private val credentialPromptInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 凭据请求进入途（bridge 收到 helper 的 cred-req 时调）。 */
    fun incPromptInFlight() { credentialPromptInFlight.incrementAndGet() }

    /** 凭据请求结束途（bridge 写回 cred-resp 时调）。 */
    fun decPromptInFlight() { credentialPromptInFlight.decrementAndGet() }

    companion object {
        private const val TAG = "LinuxContainerEngine"

        /** 命令默认超时（毫秒）：未显式指定时套用，避免命令卡死时永久占用会话。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 命令超时上限（毫秒）：再大的请求也会被钳到此值，防止事实上的“无限等待”。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** 超时后给进程的优雅退出宽限（毫秒），过后强杀。 */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /**
         * apk 安装单个 bundle / 自定义包的超时（毫秒）。
         * 原 PROVISION_TIMEOUT_MS=600_000（一次性装 8 个包），拆到单个 bundle 后按 8 分钟、
         * 自定义多包按 10 分钟给足网络慢场景时间。
         */
        private const val APK_ONE_BUNDLE_TIMEOUT_MS = 480_000L
        private const val APK_CUSTOM_TIMEOUT_MS = 600_000L

        /** 自定义包刷新（apk list）的短超时。 */
        private const val APK_LIST_TIMEOUT_MS = 60_000L
    }

    /**
     * 在容器内流式执行命令：每读到一行就 emit 一个 [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。首次调用会触发 rootfs 安装（幂等）。
     *
     * 与 [runCommandSync] 共用同一进程构建逻辑，区别仅在于输出按行实时下发，
     * 让终端能看到执行「过程」而非只有最终结果。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        // 未就绪（rootfs 未解压或基础包未配置）时不自动初始化，直接提示用户去终端页完成初始化。
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExecNoInstall(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)
    /**
     * 在容器内流式执行命令的「裸」实现：每读到一行就 emit [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。**不触发懒安装**（不调 [ensureInstalled]），假定 rootfs 已就绪。
     *
     * 抽出此方法供 [runCommandStream]（先 [ensureInstalled]）与 [provisionIfNeeded]（先
     * [ContainerInstaller.installRootfsIfNeed]，不能再触发 ensureInstalled 否则递归）共用，
     * 让 provision 也能逐行拿到 apk 输出以更新进度。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    private fun streamExecNoInstall(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(流式) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
        val process = startContainerProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        // 看门狗跑在独立 scope（独立 Job）上：若放进包裹 emit 的 coroutineScope 里，emit 的
        // Job 与 flow 收集者不一致会触发「Flow invariant is violated」。这里仅用它在超时时杀进程。
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "命令被取消，终止进程: $command")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(CommandEvent.Line(line!!))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: $command")
                else FileLogger.v(TAG, "命令完成(退出码 0): $command")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 看门狗超时 destroy 进程会关闭 stdout 管道，使阻塞中的 readLine 抛 IOException
            //（而非返回 null）。若不在此吸收，异常会让 flow 异常终止、CommandEvent.Exit 不再 emit，
            // 上层 executeStream 的 collect 随即中断，已逐行展示给用户的输出在最终 ToolResult 里丢失。
            // 故此处按是否超时分流：超时则 emit 超时提示 + Exit(null)，保留已 emit 的各行；
            // 非 IO 异常也转成一行提示 + Exit，避免 flow 异常终止丢掉已输出内容。
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止(readLine 异常): $command", e)
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): $command", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            // 协程取消（用户离开页面等）时确保子进程被回收，避免泄漏
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 在容器内同步执行命令并返回输出。首次调用会触发 rootfs 安装（幂等）。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程，返回已收集到的部分输出并在末尾追加超时提示。
     */
    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    /**
     * 同 [runCommandSync]，但一并返回退出码（超时/异常时为 null）。供需要据退出码判成败的调用方
     * 使用——如 git 写操作：git 非零退出码并非进程崩溃，[runCommandSync] 仅返回文本会让上层误报成功。
     */
    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode)
    }

    /** 一次容器内执行的内部结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
    private data class ExecResult(val output: String, val exitCode: Int?)

    /**
     * 仅在容器和基础包已就绪时执行命令；不会触发 rootfs 解压或 apk 装包。
     *
     * 供只读工具做性能增强使用：例如 search 可优先用 rg，但不能因为一次自动批准的搜索
     * 隐式初始化容器或联网安装环境。未就绪时返回 null，让调用方走纯宿主 fallback。
     */
    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!containerInstaller.isInstalledFor(currentProfile) || !isProvisioned()) return null
        val result = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(result.output, result.exitCode)
    }

    /**
     * 在容器内同步执行命令并捕获输出。**假定 rootfs 已安装**（不做懒安装/配置），
     * 供 [runCommandSync]（先 [ensureInstalled]）与 [provisionIfNeeded]（先 [installRootfsIfNeed]）复用，
     * 避免配置流程反向触发 [ensureInstalled] 形成递归。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），钳到 [MAX_TIMEOUT_MS]。超时则强杀进程，
     * 返回已收集的部分输出并在末尾追加超时提示，[ExecResult.exitCode] 记为 null。
     */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "执行命令(同步) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
            val process = startContainerProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "命令被取消，终止进程: $command")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            // 限幅累积：超大输出只保留开头+结尾，避免撑爆内存与模型上下文。
            val output = BoundedOutput()
            // 看门狗与读循环并发：超时则 destroy 进程，使阻塞的 readLine 立即返回 null 退出循环。
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            output.append(line!!)
                            output.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null)
            } else {
                FileLogger.v(TAG, "命令完成(退出码 $exitCode，输出 ${output.totalChars} 字符): $command")
                ExecResult(output.build(), exitCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "执行命令异常: $command", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    // ── Bundle / 自定义包 安装 & 卸载：公开 API（供终端设置页、AI Run 前置检测弹窗调用） ───

    /** 串行化 bundle 级的 apk 操作，避免两个按钮同时点触发两次 apk add。 */
    private val bundleOpMutex = Mutex()

    /**
     * 安装一个 Bundle（配置国内镜像源（幂等）+ apk update + apk add --no-cache <packages> + postInstallHook）。
     *
     * 前置要求：rootfs + proot 已安装（[ensureInstalled] 已完成、返回过 Ready）。
     * 未装直接抛异常，由 UI 弹"先初始化容器"提示。
     */
    suspend fun installBundle(id: TerminalBundleId) {
        val bundle = TerminalBundles.byId(id) ?: throw IllegalArgumentException("未知 bundle: $id")
        if (!containerInstaller.isInstalledFor(currentProfile)) throw IllegalStateException("容器未初始化，请先初始化 rootfs")
        bundleOpMutex.withLock {
            // 已装直接返回
            if (bundleRepository.isInstalledSnapshot(id)) return
            bundleRepository.emitInstalling(id, line = "准备中…")
            _initProgress.value = ContainerInitState.BundleInstalling(bundleId = id, line = "准备中…")
            var exitCode: Int? = null
            try {
                val script = buildString {
                    append("set -e\n")
                    append(apkMirrorAndUpdateScriptOnce())
                    append("apk add --no-cache ${bundle.packages}\n")
                    bundle.postInstallHook?.let { hook ->
                        append("# post-install hook for ").append(id.stableKey).append('\n')
                        append(hook.trimIndent()).append('\n')
                    }
                    // GIT 两个 credential helper 的全局配置：以前写在 monolithic provisionIfNeeded 末尾，
                    // 现在拆成 GIT bundle 自己的 post-install（定义在 TerminalBundles 里），保证不重复。
                    // 为了兼容"旧存量用户只升级 app、不重装 rootfs"，Bash/Git 旧版本 provision 没这些行，
                    // 我们在 GIT bundle 里强制重复配置：--replace-all / --add 语义天然幂等。
                }
                streamExecNoInstall(script, projectPath = null, timeoutMs = APK_ONE_BUNDLE_TIMEOUT_MS).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> {
                            bundleRepository.emitInstalling(id, line = event.text)
                            _initProgress.value = ContainerInitState.BundleInstalling(bundleId = id, line = event.text)
                        }
                        is CommandEvent.Exit -> exitCode = event.code
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "安装 bundle ${id.stableKey} 异常", e)
                bundleRepository.markFailed(id, e.message ?: "异常")
                _initProgress.value = ContainerInitState.Failed("安装 ${bundle.displayName} 失败：${e.message}")
                throw e
            }
            if (exitCode == 0) {
                bundleRepository.markInstalled(id, bundle)
                FileLogger.i(TAG, "Bundle 安装成功：${bundle.displayName} (${bundle.packages})")
            } else {
                val reason = "安装 ${bundle.displayName} 失败（exit=$exitCode），请在终端设置中重试"
                bundleRepository.markFailed(id, reason)
                _initProgress.value = ContainerInitState.Failed(reason)
                throw IllegalStateException(reason)
            }
        }
    }

    /** 批量安装多个 Bundle（AI 推荐组合一键安装使用）。遇到任一失败即停并向上抛。 */
    suspend fun installBundlesOrdered(ids: Iterable<TerminalBundleId>) {
        for (id in ids) installBundle(id)
    }

    /**
     * 卸载一个 Bundle：apk del --purge <packages> + 清 bundle 标记。
     *
     * 注意：不自动清理被依赖的其他包（其他 bundle 可能 share python3 等），
     * 也不回滚 post-install hook 的副作用（git config / shell 切换）。
     * UI 层给用户文案提示"卸载后会释放 X MB，git/bash 配置需手动恢复"。
     */
    suspend fun uninstallBundle(id: TerminalBundleId) {
        val bundle = TerminalBundles.byId(id) ?: throw IllegalArgumentException("未知 bundle: $id")
        if (!containerInstaller.isInstalledFor(currentProfile)) {
            // 容器没装自然没包装过，直接 NotInstalled
            bundleRepository.markUninstalled(id)
            return
        }
        bundleOpMutex.withLock {
            if (!bundleRepository.isInstalledSnapshot(id)) return
            bundleRepository.emitUninstalling(id)
            _initProgress.value = ContainerInitState.BundleUninstalling(bundleId = id)
            var exitCode: Int? = null
            try {
                streamExecNoInstall(
                    "apk del --purge ${bundle.packages}",
                    projectPath = null,
                    timeoutMs = APK_ONE_BUNDLE_TIMEOUT_MS
                ).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> Unit
                        is CommandEvent.Exit -> exitCode = event.code
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "卸载 bundle ${id.stableKey} 异常", e)
                // 异常不回退 state：实际可能是网络断等，让用户下次再试 apk del / 或重置容器。
                bundleRepository.markUninstalled(id)
                return
            }
            // apk del 的退出码我们相信为 0 就 OK。非 0 也把状态置为 NotInstalled——
            // 因为 apk del 报"该包未安装"也会 exit != 0，我们不希望 UI 永远停在 Installing/Installed。
            // 真失败下次 installBundle 会重新 apk add。
            bundleRepository.markUninstalled(id)
            FileLogger.i(TAG, "Bundle 卸载：${bundle.displayName} (exit=$exitCode)")
        }
    }

    /** 自定义 apk 包批量安装（高级折叠区入口）。返回失败列表；全成功返回 emptyList。 */
    suspend fun installCustomPackages(pkgs: List<String>): List<String> {
        if (pkgs.isEmpty()) return emptyList()
        if (!containerInstaller.isInstalledFor(currentProfile)) throw IllegalStateException("容器未初始化，请先初始化 rootfs")
        val argLine = pkgs.joinToString(" ")
        _initProgress.value = ContainerInitState.BundleInstalling(bundleId = null, line = "安装自定义包：$argLine …")
        var exitCode: Int? = null
        var lastLine: String? = null
        bundleOpMutex.withLock {
            try {
                val script = buildString {
                    append("set -e\n")
                    append(apkMirrorAndUpdateScriptOnce())
                    append("apk add --no-cache ").append(argLine).append('\n')
                }
                streamExecNoInstall(script, projectPath = null, timeoutMs = APK_CUSTOM_TIMEOUT_MS).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> lastLine = event.text
                        is CommandEvent.Exit -> exitCode = event.code
                    }
                }
            } catch (e: Exception) {
                lastLine = e.message
                exitCode = -1
            }
        }
        val failed = if (exitCode == 0) emptyList() else pkgs
        if (failed.isEmpty()) {
            // 成功：加到自定义包快照（UI 列表/磁盘标记都更新）
            bundleRepository.addCustomSnapshots(pkgs)
            FileLogger.i(TAG, "自定义包安装成功：$argLine")
        } else {
            FileLogger.w(TAG, "自定义包安装失败($lastLine)：$argLine")
            _initProgress.value = ContainerInitState.Failed("安装自定义包失败：$lastLine")
        }
        // 成功/失败后都刷新一次"真实的"自定义包清单（减去和 bundle 包重叠的，留下用户真正装的）
        runCatching { refreshCustomPackagesSnapshot() }
        return failed
    }

    /** 卸载一个自定义包。成功返回 true。 */
    suspend fun uninstallCustomPackage(pkg: String): Boolean {
        if (!containerInstaller.isInstalledFor(currentProfile)) {
            bundleRepository.removeCustomSnapshot(pkg)
            return true
        }
        var ok = false
        bundleOpMutex.withLock {
            try {
                streamExecNoInstall(
                    "apk del --purge $pkg",
                    projectPath = null,
                    timeoutMs = APK_ONE_BUNDLE_TIMEOUT_MS
                ).collect { event ->
                    if (event is CommandEvent.Exit) ok = (event.code == 0)
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "卸载自定义包异常: $pkg", e)
            }
        }
        bundleRepository.removeCustomSnapshot(pkg)
        // 刷新一次真实列表
        runCatching { refreshCustomPackagesSnapshot() }
        return ok
    }

    /**
     * 通过 `apk info` 把容器当前已安装的 apk 世界包列出来，
     * 减去 7 个 bundle 覆盖的包，再把"用户自定义的"那部分写回 TerminalBundleRepository。
     * 由终端设置页进入时 / 每次自定义包操作后调用一次，保证 UI 列表与实际一致。
     */
    suspend fun refreshCustomPackagesSnapshot() {
        if (!containerInstaller.isInstalledFor(currentProfile)) {
            bundleRepository.saveCustomPackagesSnapshot(emptyList())
            return
        }
        val text = runCatching {
            execCaptured("apk info", projectPath = null, timeoutMs = APK_LIST_TIMEOUT_MS).output
        }.getOrDefault("")
        val installed = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val bundled = TerminalBundles.ALL
            .flatMap { it.packages.split(" ") }
            .map { it.trim() }.filter { it.isNotBlank() }
            .toSet()
        // 额外减去 Alpine 自带 minirootfs 的 base world（避免 busybox / alpine-base / musl 出现在用户列表里）
        // 做法：凡是首次 apk add 没提到的包名一律先给出去；如果列表过大 UI 能接受——
        // （apk info 通常 100~200 包，减去 bundle/base 后只剩几个用户自定义包）。
        val basePkgs = setOf(
            "alpine-base", "alpine-keys", "apk-tools", "busybox", "busybox-binsh", "musl", "musl-utils",
            "libc-utils", "zlib", "ssl_client", "ca-certificates-bundle"
        )
        val custom = (installed - bundled - basePkgs).sorted()
        bundleRepository.saveCustomPackagesSnapshot(custom)
    }

    /** 重置容器：物理删除 rootfs + proot 目录，bundleRepo 状态重置。高风险操作。 */
    suspend fun resetContainer() {
        // initMutex 保护：不与正在初始化的 job 并发
        initMutex.withLock {
            initJob?.cancel()
            initJob = null
        }
        bundleRepository.resetAllToNotInstalled()
        runCatching { containerInstaller.rootfsDir.deleteRecursively() }
        runCatching { containerInstaller.prootBin.delete() }
        runCatching { containerInstaller.prootLoader.delete() }
        runCatching { containerInstaller.prootLoader32.delete() }
        _initProgress.value = ContainerInitState.Idle
    }

    /** 切换 Alpine 镜像源并立刻 apk update 让其生效。成功返回 true。 */
    suspend fun setApkMirrorAndUpdate(mirror: String = ContainerInstaller.ALPINE_MIRROR): Boolean {
        if (!containerInstaller.isInstalledFor(currentProfile)) return false
        var exitCode: Int? = null
        bundleOpMutex.withLock {
            try {
                val script = buildString {
                    append("set -e\n")
                    append(apkMirrorAndUpdateScriptOnce(mirror))
                }
                streamExecNoInstall(script, projectPath = null, timeoutMs = APK_LIST_TIMEOUT_MS * 3).collect { event ->
                    if (event is CommandEvent.Exit) exitCode = event.code
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "切换镜像源异常", e)
            }
        }
        return exitCode == 0
    }

    /**
     * 一次性写：镜像源写入 /etc/apk/repositories + apk update。
     * 同一个脚本被 installBundle/installCustomPackages 复用，所以单独抽出。
     * 每次安装前都写（幂等），保证：① 新解压 rootfs 立即有国内镜像；② 存量 rootfs（
     * 其 /etc/apk/repositories 还是官方源）也被同步更新成国内源。
     */
    private fun apkMirrorAndUpdateScriptOnce(mirror: String = ContainerInstaller.ALPINE_MIRROR): String = buildString {
        append("mkdir -p /etc/apk\n")
        append("cat > /etc/apk/repositories <<EOF\n")
        append("$mirror/${ContainerInstaller.ALPINE_BRANCH}/main\n")
        append("$mirror/${ContainerInstaller.ALPINE_BRANCH}/community\n")
        append("EOF\n")
        append("apk update 2>&1 | tail -n 3\n")
    }

    // ── End Bundle 公开 API ──────────────────────────────────────────────────

    /**
     * 启动看门狗：等待 [timeoutMs] 后若进程仍存活，则标记超时并优雅→强制终止，借此解除
     * 调用方阻塞中的 readLine。返回的 [Job] 由调用方在正常结束时 cancel 掉。
     *
     * **凭据弹窗在途时暂停**：到点若 [credentialPromptInFlight] > 0（自定义 git credential helper
     * 正阻塞等 app 弹窗回填用户的凭据请求），watchdog 不杀——这条 git 命令是「正等用户填凭据」
     * 而非「卡死」，按常规超时强杀会让用户离开几分钟回来发现推送已失败、得重来。改为每轮重查：
     * 在途则宽限一段（1min）再查，直至不在途才杀；绝对上限 [MAX_TIMEOUT_MS]（30min）即便在途
     * 也兜底杀，避免事实无限等待。终端 PTY 路径不经 watchdog（[TerminalSessionManager] 裸 tty），天然最耐等。
     */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        var remaining = timeoutMs
        var totalWaited = 0L
        while (true) {
            delay(remaining)
            if (!process.isAlive) return@launch
            totalWaited += remaining
            val inflight = credentialPromptInFlight.get()
            if (inflight > 0 && totalWaited < MAX_TIMEOUT_MS) {
                // 凭据弹窗在途：宽限 1min（不超过绝对上限），再回查。
                FileLogger.i(TAG, "凭据弹窗在途(${inflight})，watchdog 暂缓，再等 60000ms: $command")
                remaining = minOf(60_000L, MAX_TIMEOUT_MS - totalWaited)
                continue
            }
            // 不在途，或已达 30min 绝对上限：正常超时终止。
            timedOut.set(true)
            FileLogger.w(TAG, "命令执行超过 ${timeoutMs}ms（累计等待 ${totalWaited}ms，inflight=${inflight}），终止进程: $command")
            runCatching { process.destroy() }
            delay(TIMEOUT_KILL_GRACE_MS)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            return@launch
        }
    }

    /** 超时提示行（拼进输出，喂回模型/展示给用户）。 */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[命令执行超时：超过 ${timeoutMs}ms 已被强制终止]"

    /**
     * 启动进程。rootfs/proot 安装就绪则用 PRoot 进入容器；
     * 否则回退到 Android 原生 shell（rootfs 缺失时的兜底）。
     */
    private fun startContainerProcess(command: String, projectPath: String?): Process {
        val profile = currentProfile
        val useProot = containerInstaller.isInstalledFor(profile)

        val processBuilder = if (useProot) {
            buildProcessBuilder(buildProotInvocation(command, projectPath))
        } else {
            FileLogger.w(TAG, "PRoot 未安装，回退到原生 shell")
            buildNativeProcess(command, projectPath)
        }

        // Redirect stderr to stdout so we capture everything in one stream
        processBuilder.redirectErrorStream(true)
        return processBuilder.start()
    }

    /** 容器是否已安装就绪（按当前 profile）。 */
    override fun isContainerInstalled(): Boolean = containerInstaller.isInstalledFor(currentProfile)

    /**
     * "provisioned" 语义变化：以前=全量 8 包装完；现在=rootfs+proot 解压好（容器物理可用）。
     * 这是为了与 `ensureInstalled` 语义对齐（M1 后 ensureInstalled 不再自动装 bundle）。
     *
     * 自定义镜像不 provision（bundle 体系不覆盖 custom profile），自定义视为 true。
     */
    override fun isProvisioned(): Boolean {
        if (!currentProfile.isBuiltin) return true
        return containerInstaller.isInstalledFor(currentProfile)
    }

    /**
     * 容器未就绪（rootfs 未解压）时返回引导文案，就绪返回 null。
     * 与旧版区别：现在**不再要求 bundle 包全装完**才视为"ready"。rootfs 解压好就允许执行命令；
     * 缺 python3/rg/git 等工具的 command not found 会被 M2 彩色提示补"需要装 X bundle → 去终端设置"。
     */
    override fun notReadyHint(): String? {
        if (containerInstaller.isInstalledFor(currentProfile)) return null
        return context.getString(R.string.container_not_ready_hint)
    }

    /**
     * 默认命令 shell：
     *   - 自定义 profile → profile 指定或 /bin/sh
     *   - 内置 Alpine
     *       · BASH bundle 已装 → /bin/bash
     *       · 否则 → /bin/sh
     * （不再按 isProvisioned() 做全局判断——而是按具体 BASH bundle 的状态。）
     */
    override fun defaultShell(): String {
        val profile = currentProfile
        if (!profile.isBuiltin) return profile.shellPath?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        return if (bundleRepository.isInstalledSnapshot(TerminalBundleId.BASH)) "/bin/bash" else "/bin/sh"
    }

    /**
     * 幂等地确保容器**物理可用**（仅解压 rootfs + proot，**不再自动 provision 全量 8 个包**）。
     *
     * 完成后：
     *   - `containerInstaller.isInstalledFor(profile)` = true
     *   - `initProgress` = Ready(migratedFromLegacyProvisioned)：检测到旧版 .provisioned 标记会打
     *     全部 7 个 bundle 的 Installed 标记（存量用户零感知升级）
     *   - 刷新容器 $HOME 缓存
     *
     * 后续：用户去终端设置手动点 "初始化 Python / Node" 等 bundle 卡片，或者进终端页
     * 点 Banner 上的 CTA 跳到终端设置去点。
     */
    override suspend fun ensureInstalled() {
        val profile = currentProfile
        // 每次进入终端页前确保提取最新的内置文档
        containerInstaller.extractDocs()
        if (containerInstaller.isInstalledFor(profile)) {
            val migrated = bundleRepository.migrateIfNeededAfterBoot()
            _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = migrated)
            refreshContainerHome()
            return
        }
        // 启动或复用后台初始化 job；initMutex 只保护 job 的创建/复用，真正的耗时工作在 initScope 里跑。
        val job = initMutex.withLock {
            val existing = initJob
            if (existing == null || !existing.isActive) {
                initJob = initScope.launch { doInit(profile) }
            }
            initJob!!
        }
        // 等待完成；若调用方（终端页）被取消，join 抛 CancellationException，但后台 job 继续执行。
        job.join()
        if (containerInstaller.isInstalledFor(profile)) {
            val migrated = bundleRepository.migrateIfNeededAfterBoot()
            _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = migrated)
            refreshContainerHome()
        } else {
            val reason = "容器未安装（缺少 rootfs/proot）"
            _initProgress.value = ContainerInitState.Failed(reason)
            throw IllegalStateException(reason)
        }
    }

    /** 在 [initScope] 中真正执行一次性初始化：只解压 rootfs + 部署 proot。 */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed 在真正解压/部署时回调更新进度（已安装则快路径不回调）
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }
    }

    /** 查容器内 $HOME 并缓存到 [WorkspacePathMapper]，供文件工具展开 ~。 */
    private suspend fun refreshContainerHome() {
        runCatching {
            val result = execCaptured("echo \$HOME", projectPath = null, timeoutMs = 3000)
            val home = result.output.trim().ifEmpty { null }
            if (home != null) workspacePathMapper.containerHome = home
        }.onFailure { FileLogger.w(TAG, "查容器 \$HOME 失败", it) }
    }

    /**
     * 以长驻进程方式在容器内执行命令（如 `sshd -D`）。调用前需保证容器已安装。
     * 与 [runCommandStream] 不同，这里不读取/消费输出流，由调用方决定如何处理（通常丢弃）。
     */
    fun startProotProcess(command: String, projectPath: String?): Process {
        val pb = buildProcessBuilder(buildProotInvocation(command, projectPath))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /**
     * 以长驻进程方式在容器内启动一个程序并保留**分离的** stdin/stdout/stderr，供调用方
     * 双向流式通信（如 MCP stdio server：往 stdin 写 JSON-RPC、从 stdout 读 JSON-RPC）。
     *
     * 与 [startProotProcess] 的关键区别：**不** redirectErrorStream，stderr 独立保留，
     * 保证 stdout 是干净的协议流（server 的日志走 stderr，不会污染 JSON-RPC）。
     *
     * [program] 为容器内可执行文件名/路径（如 `npx`），[programArgs] 为其参数，二者经
     * `/bin/sh -c 'exec "$0" "$@"'` 逐项透传，避免 shell 引号/转义问题。[extraEnv] 叠加到
     * 容器默认环境（覆盖同名项）。调用前需保证容器已安装（[ensureInstalled]）。
     */
    fun startStdioProcess(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        val invocation = buildStdioInvocation(program, programArgs, projectPath, extraEnv)
        val pb = buildProcessBuilder(invocation)
        // 刻意不 redirectErrorStream：stdout 留给 JSON-RPC，stderr 由调用方单独消费。
        return pb.start()
    }

    /**
     * 构造「在容器内直接 exec 某程序（保留分离流）」的 PRoot 调用。
     * 用 `sh -c 'exec "$0" "$@"' program arg1 arg2 …` 把参数原样交给 execvp，规避引号问题。
     */
    private fun buildStdioInvocation(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String>
    ): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath)
        argv.add("/bin/sh")
        argv.add("-c")
        argv.add("exec \"\$0\" \"\$@\"")
        argv.add(program)
        argv.addAll(programArgs)
        return ProotInvocation(argv, buildContainerEnv() + extraEnv)
    }

    /**
     * 构造进入容器执行 [command] 的完整 PRoot 调用（argv + env）。
     * 暴露给终端会话：Termux TerminalSession 需要把可执行文件与参数分开传入。
     */
    fun buildProotInvocation(command: String, projectPath: String?): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath)
        // 用 [defaultShell]：bash 装好后 AI 命令与终端会话都走 bash；装机期间/失败回退 /bin/sh。
        argv.add(defaultShell())
        argv.add("-c")
        argv.add(command)
        return ProotInvocation(argv, buildContainerEnv())
    }

    /**
     * 构造 PRoot 调用的公共前缀 argv：proot 二进制 + rootfs + 标准绑定 + 伪 root + 工作区绑定，
     * 但**不含**最终的客户机命令（由各调用方自行追加 `/bin/sh -c …` 或 `exec` 形式）。
     */
    private fun buildBaseProotArgv(projectPath: String?): MutableList<String> {
        val profile = currentProfile
        val rootfs = containerInstaller.rootfsDirFor(profile).absolutePath
        val prootBin = containerInstaller.prootBin.absolutePath

        val argv = mutableListOf(
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",  // 绑定 /system 让宿主动态库可用
            "-0"              // 伪 root，apk 等需要
        )

        // 把当前工作区目录绑定到容器内 ~/workspace（即 /root/workspace），使命令与文件工具作用于同一目录
        if (projectPath != null) {
            argv.add("-b")
            argv.add("$projectPath:/root/workspace")
            argv.add("-w")
            argv.add("/root/workspace")
        }

        // 把 AI 配置目录绑定到容器内 /root/.rdeepcode（读写）：内含 skills/（load_skill 读到的指令常引用
        // skill 目录里的脚本，AI 用 execute_command 执行 `python /root/.rdeepcode/skills/<name>/x.py` 等）与
        // mcp.json（MCP 配置）。宿主物理目录独立于 rootfs，容器升级重装不丢用户数据。
        // 基础解释器 python3(3.12) 与 git 由 [provisionIfNeeded] 在首次初始化时自动 `apk add`；
        // node 等其他运行时仍由 skill / 用户自行保证。proot 的 -b 要求源路径存在，故先确保目录已建。
        val rdeepcodeDir = containerInstaller.rdeepcodeDir.apply { mkdirs() }
        argv.add("-b")
        argv.add("${rdeepcodeDir.absolutePath}:/root/.rdeepcode")

        // 自定义 profile 的额外绑定与参数（内置 profile 这俩为空，此段无操作，等价于改动前）
        for (b in profile.extraBindings) {
            argv.add("-b")
            argv.add(b)
        }
        argv.addAll(profile.extraArgs)

        return argv
    }

    /** 容器内进程的标准环境变量（proot loader / 动态库 / PATH / HOME 等）。 */
    private fun buildContainerEnv(): Map<String, String> {
        return mapOf(
            // Android proot 必需的环境变量
            "PROOT_TMP_DIR" to containerInstaller.prootTmpDir.absolutePath, // Android 没有 /tmp
            // Termux proot 的 loader 分离，必须用 PROOT_LOADER/_32 指向，否则无法注入子进程而起不来。
            "PROOT_LOADER" to containerInstaller.prootLoader.absolutePath,
            "PROOT_LOADER_32" to containerInstaller.prootLoader32.absolutePath,
            // Termux proot 动态链接 libtalloc.so.2 / libandroid-shmem.so，需让 linker64 能找到它们；
            // libc.so/liblog.so 走系统默认路径(/system/lib64)。
            "LD_LIBRARY_PATH" to "${containerInstaller.prootLibDir.absolutePath}:/system/lib64:/system/lib",
            // 说明（statx / seccomp）：旧 proot 5.1.0 的 seccomp 过滤表没有 statx，Node 用 statx 解析
            // 模块路径会拿到未翻译的 ~/workspace/xxx → ENOENT「Cannot find module」。Termux proot
            // (5.1.107.x) 的 seccomp 过滤表已包含 statx，默认 seccomp 模式即可正确翻译，故此处
            // **刻意不设 PROOT_NO_SECCOMP**——这正是 Termux 自己用 proot 的方式；强制全量 ptrace
            // (PROOT_NO_SECCOMP=1) 反而在本设备触发过 ptrace(PEEKDATA) I/O error。
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            // git 全局配置指向持久挂载里的 .gitconfig（/root/.rdeepcode 绑定到宿主 filesDir/rdeepcode，
            // 跨 rootfs 升级不丢）。git-credentials 同放该目录，credential.helper=store 经此读。
            // 让终端/AI/UI 三端 git 都读同一份配置与凭据，详见 GitCredentialsFileSync。
            "GIT_CONFIG_GLOBAL" to "/root/.rdeepcode/.gitconfig",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
    }

    private fun buildProcessBuilder(invocation: ProotInvocation): ProcessBuilder {
        val processBuilder = ProcessBuilder(invocation.argv)
        processBuilder.environment().putAll(invocation.env)
        return processBuilder
    }

    private fun buildNativeProcess(command: String, projectPath: String?): ProcessBuilder {
        // Fallback to Android's native shell
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
        if (projectPath != null) {
            processBuilder.directory(java.io.File(projectPath))
        }
        return processBuilder
    }
}
