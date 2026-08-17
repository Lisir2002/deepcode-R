package com.deep.rcode.feature.agent.domain.container

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.progress.ApkStdoutParser
import com.deep.rcode.feature.agent.domain.container.progress.InstallPhase
import com.deep.rcode.feature.agent.domain.container.progress.ParallelPrefetchManager
import com.deep.rcode.feature.agent.domain.container.progress.PrefetchConcurrencyPolicy
import com.deep.rcode.feature.agent.domain.container.progress.ProgressSource
import com.deep.rcode.feature.agent.domain.container.progress.RealProgressAggregator
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val bundleRepository: TerminalBundleRepository,
    /** Level 2+3 融合：5 路真实信号聚合器；按 bundleId/custom(null) 各自独立，公开 StateFlow 给 UI。 */
    val progressAggregator: RealProgressAggregator,
    private val concurrencyPolicy: PrefetchConcurrencyPolicy,
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

    /** 按 id 解析 profile：内置返回 [ContainerProfile.BUILTIN_ALPINE] 或 [ContainerProfile.BUILTIN_ALPINE_X86]，
     *  否则从自定义列表找，找不到回退内置（arm64）。 */
    private suspend fun resolveProfile(id: String): ContainerProfile = when (id) {
        ContainerProfile.BUILTIN_ID -> ContainerProfile.BUILTIN_ALPINE
        ContainerProfile.BUILTIN_X86_ID -> ContainerProfile.BUILTIN_ALPINE_X86
        else -> containerSettingsRepository.customProfilesFlow.first()
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

        /** 命令超时上限（毫秒）：再大的请求也会被钳到此值，防止事实上的“无限等待”。
         *  为在手机上（经 qemu-user 模拟 x86_64）完成 Android Gradle release 构建预留足够长窗口：
         *  R8 + aapt2 + d8 单步在 qemu 下可 > 20 分钟，叠加完整构建，上限 3600 秒。 */
        const val MAX_TIMEOUT_MS = 3_600_000L

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

        /**
         * apk world 包名正则（Alpine apk-tools 规范）：
         * 首字符必须字母或数字，后续允许字母/数字/._+-；不允许大写、空白、URL、
         * WARNING/ERROR 等前缀大写的日志行，也不允许 : / 等路径字符。
         * 参考 apk-tools 源码 libapk/version.c 与 world 包名检查逻辑。
         */
        private val APK_PKG_NAME_REGEX = Regex("""^[a-z0-9][a-z0-9._+\-]*$""")
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
     * 把 execCaptured 包一层 Pair<String, Int?> 给 ParallelPrefetchManager 用，避免内部暴露 ExecResult。
     */
    private suspend fun execForManager(cmd: String, timeoutMs: Long): Pair<String, Int?> {
        val r = execCaptured(cmd, projectPath = null, timeoutMs = timeoutMs)
        return r.output to r.exitCode
    }

    /**
     * 某个 bundle 是否已安装（同步快照，避免协程）。
     * 供工具层（如 EnsureAndroidEnvTool）做只读判断，替代原先反射访问私有字段的方式。
     */
    fun isBundleInstalled(id: TerminalBundleId): Boolean =
        bundleRepository.isInstalledSnapshot(id)

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

            // ── Level 2+3 融合：开始会话（Prefetch + Aggregator） ──
            val conResult = concurrencyPolicy.calculate()
            val slotsN = conResult.slots
            val pkgs = bundle.packages.trim().split(Regex("""\s+""")).filter(String::isNotBlank)
            val prefetch = ParallelPrefetchManager(
                slotsCount = slotsN,
                runSync = ::execForManager,
                streamShell = { cmd, to -> streamExecNoInstall(cmd, projectPath = null, timeoutMs = to) },
            )
            progressAggregator.startInstallSession(
                slots = slotsN,
                totalDependsEstimate = pkgs.size * 6,
                bundleId = id,
            )
            // 监听 Prefetch 槽流 → Aggregator
            val slotsCollectJob = initScope.launch {
                prefetch.slots.collect { s -> progressAggregator.onSlotsFromPrefetch(s) }
            }
            var exitCode: Int? = null
            var failedReason: String? = null
            // RC61c S3：把 prefetch fire-and-forget 的 Job 存下来；失败分支/exitCode!=0 立刻 cancel
            var prefetchJob: kotlinx.coroutines.Job? = null
            try {
                // Level 3a：先查依赖图 → 并行预取（fail-open：单包失败交给 apk 兜底）
                runCatching {
                    val depends = prefetch.resolveDependencies(pkgs, timeoutMs = APK_LIST_TIMEOUT_MS)
                    // 预取异步跑；完成后让 Aggregator flush 失败合并摘要（Fix C 汇总不刷屏）
                    prefetchJob = initScope.launch {
                        val fin = runCatching { prefetch.prefetch(depends) }
                            .getOrNull()
                        if (fin is ParallelPrefetchManager.PrefetchEvent.Finished) {
                            runCatching {
                                progressAggregator.flushPrefetchFailures(fin.failedPackages)
                            }.onFailure { t -> FileLogger.w(TAG, "flush failures err", t) }
                        }
                    }
                    // 给预取 800ms 头启动时间（让 curl 先拿 Content-Length，slot 立刻从 WAITING→DLING，UI 首帧不空方块）
                    kotlinx.coroutines.delay(800)
                }.onFailure { t ->
                    FileLogger.w(TAG, "prefetch prepare 跳过：${t.message}")
                }

                val hookLines = bundle.postInstallHook?.trimIndent()?.lineSequence()?.count() ?: 0
                val script = buildString {
                    // D2 Fix：apk exit=2 自愈（Alpine apk exit=2 = 包名找不到/约束不满足，
                    // 常见于 APKINDEX 还没同步或裸名 <-> 带版本名的 world 冲突）。
                    // 策略：set -e 先不立即退出，apk add 失败时先 `apk update` 再重试一次，
                    // 最后 `set -e` 以真实 exit code 判定。
                    append("set +e\n")
                    append(apkMirrorAndUpdateScriptOnce())
                    append("apk add --no-cache ${bundle.packages}\n")
                    append("APK_EXIT=\$?\n")
                    append("if [ \$APK_EXIT -ne 0 ]; then\n")
                    append("  echo \"[retry] 首次 apk add exit=\$APK_EXIT，apk update 后再试一次…\"\n")
                    append("  apk update >/dev/null 2>&1\n")
                    append("  apk fix --no-cache >/dev/null 2>&1 || true\n")
                    append("  apk add --no-cache ${bundle.packages}\n")
                    append("  APK_EXIT=\$?\n")
                    append("fi\n")
                    bundle.postInstallHook?.let { hook ->
                        append("if [ \$APK_EXIT -eq 0 ]; then\n")
                        append("# post-install hook for ").append(id.stableKey).append('\n')
                        append(hook.trimIndent().prependIndent("  ")).append('\n')
                        append("  APK_EXIT=\$?\n")
                        append("fi\n")
                    }
                    append("exit \$APK_EXIT\n")
                }
                // 告诉 Aggregator：apk OK 之后进入 POST_HOOK 阶段，行数在这里
                if (hookLines > 0) {
                    // 我们还没到 OK（INSTALL phase），这里先把计划行数存一下，等 OK 语义命中再 enterPostHook
                }
                streamExecNoInstall(script, projectPath = null, timeoutMs = APK_ONE_BUNDLE_TIMEOUT_MS).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> {
                            bundleRepository.emitInstalling(id, line = event.text)
                            _initProgress.value = ContainerInitState.BundleInstalling(bundleId = id, line = event.text)
                            progressAggregator.onApkLine(event.text)
                            // RC61c S4：shell 里只要出现 WARNING fetching ... IO ERROR 就立刻 cancel 预取（避免继续占网）
                            val t = event.text
                            val lower = t.lowercase()
                            if ((lower.contains("warning: fetching") || lower.contains("io error")) && prefetchJob?.isActive == true) {
                                FileLogger.w(TAG, "apk 行内出现镜像 IO WARNING → 立刻 cancel prefetch")
                                prefetchJob?.cancel("apk stdout saw IO WARNING fetching")
                                runCatching { prefetch.shutdown("apk stdout IO WARNING") }
                            }
                            // 解析 OK 语义后，如果有 postHook 行数就切 phase 到 POST_HOOK
                            val sem = ApkStdoutParser.parse(event.text).semantic
                            if (sem is ApkStdoutParser.Semantic.Ok && hookLines > 0) {
                                initScope.launch { progressAggregator.enterPostHook(totalLines = hookLines.coerceAtLeast(1)) }
                            }
                            if (sem is ApkStdoutParser.Semantic.Installing && hookLines == 0) {
                                // noop
                            }
                            if (sem is ApkStdoutParser.Semantic.PostLine && hookLines > 0) {
                                initScope.launch { progressAggregator.advancePostHookLine() }
                            }
                        }
                        is CommandEvent.Exit -> {
                            exitCode = event.code
                            // RC61c S3：exitCode≠0 立刻 cancel prefetch（用户截图 FAILED 后还 5K/s 占网）
                            if (event.code != null && event.code != 0) {
                                FileLogger.w(TAG, "apk exit=${event.code} → 立刻 cancel prefetch + shutdown（回收并发槽/Socket）")
                                prefetchJob?.cancel("apk exit=${event.code} != 0")
                                runCatching { prefetch.shutdown("apk exit=${event.code}") }
                            }
                            initScope.launch {
                                progressAggregator.onExitCode(
                                    code = event.code,
                                    postHookDone = true,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "安装 bundle ${id.stableKey} 异常", e)
                failedReason = "安装 ${bundle.displayName} 失败：${e.message}"
                // RC61c S3：异常分支（比如 apk 解析炸/取消/etc.）也立刻释放预取资源
                prefetchJob?.cancel("installBundle exception: ${e.message}")
                runCatching { prefetch.shutdown("installBundle exception: ${e.message}") }
            } finally {
                // RC61c S3：finally 最后兜底 cancel + shutdown（成功/失败/异常 三分支都要释放）
                runCatching { prefetchJob?.cancel("installBundle finally") }
                // RC61f：finally 时 cleanupPartialCache → 无论成功/失败，.part 半截文件一定删除；
                // 只有 exit==0 且未失败，我们才清理 /var/cache/apk/*.apk（已装完，释放手机存储）
                val ok = (exitCode == 0 && failedReason == null)
                runCatching {
                    initScope.launch {
                        // cleanup 包在 initScope(SupervisorIO)：避免阻塞主线程；超时设 3s 防止卡住结束流程
                        kotlinx.coroutines.withTimeoutOrNull(3500) {
                            prefetch.cleanupPartialCache(cleanupInstalledApks = ok, timeoutMs = 2500)
                        }
                    }
                }
                // 原来的 shutdown 放后面（内部再跑一次 cleanupPartialCache，幂等）
                runCatching { prefetch.shutdown("installBundle finally (ok=$ok)") }
                slotsCollectJob.cancel()
                progressAggregator.endSession(
                    // RC61c S3：若 exit 非 0 或 catch 进了 failedReason → 强制以 FAILED 快照结束会话，
                    // UI 的「X 槽并行 · Y KB/s」会立刻归零，不会再显示「好像还在下载」的假速率。
                    forceFailSnapshot = (exitCode != null && exitCode != 0) || failedReason != null,
                )
                if (exitCode == 0 && failedReason == null) {
                    bundleRepository.markInstalled(id, bundle)
                    FileLogger.i(TAG, "Bundle 安装成功：${bundle.displayName} (${bundle.packages})")
                } else {
                    val reason = failedReason
                        ?: "安装 ${bundle.displayName} 失败（exit=$exitCode），请在终端设置中重试"
                    FileLogger.w(TAG, reason)
                    bundleRepository.markFailed(id, reason)
                }
                // ⚠️ 无论成功/失败/异常，最后都复位 _initProgress 回 Ready。
                val cur = _initProgress.value
                if (cur is ContainerInitState.BundleInstalling || cur is ContainerInitState.BundleUninstalling) {
                    _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = false)
                }
                if (failedReason != null) throw IllegalStateException(failedReason)
                if (exitCode != 0) {
                    val reason = "安装 ${bundle.displayName} 失败（exit=$exitCode），请在终端设置中重试"
                    throw IllegalStateException(reason)
                }
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
                return@withLock
            } finally {
                // apk del 的退出码我们相信为 0 就 OK。非 0 也把状态置为 NotInstalled——
                // 因为 apk del 报"该包未安装"也会 exit != 0，我们不希望 UI 永远停在 Installing/Installed。
                // 真失败下次 installBundle 会重新 apk add。
                bundleRepository.markUninstalled(id)
                FileLogger.i(TAG, "Bundle 卸载：${bundle.displayName} (exit=$exitCode)")
                // 无论成功/异常退出 withLock 前都复位 Ready，避免 UI 永远停在 BundleUninstalling。
                // 同 installBundle：不再依赖 isInstalledFor（currentProfile 异步更新可能导致假阴性），
                // 只看当前状态是否还停在 BundleInstalling/BundleUninstalling。
                val cur = _initProgress.value
                if (cur is ContainerInitState.BundleInstalling || cur is ContainerInitState.BundleUninstalling) {
                    _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = false)
                }
            }
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
        var failed: List<String> = pkgs
        try {
            bundleOpMutex.withLock {
                try {
                    val script = buildString {
                        // D2 Fix：exit=2 自愈，同 installBundle
                        append("set +e\n")
                        append(apkMirrorAndUpdateScriptOnce())
                        append("apk add --no-cache ").append(argLine).append('\n')
                        append("APK_EXIT=\$?\n")
                        append("if [ \$APK_EXIT -ne 0 ]; then\n")
                        append("  echo \"[retry] 首次 apk add exit=\$APK_EXIT，apk update 后再试一次…\"\n")
                        append("  apk update >/dev/null 2>&1\n")
                        append("  apk fix --no-cache >/dev/null 2>&1 || true\n")
                        append("  apk add --no-cache ").append(argLine).append('\n')
                        append("  APK_EXIT=\$?\n")
                        append("fi\n")
                        append("exit \$APK_EXIT\n")
                    }
                    streamExecNoInstall(script, projectPath = null, timeoutMs = APK_CUSTOM_TIMEOUT_MS).collect { event ->
                        when (event) {
                            is CommandEvent.Line -> {
                                lastLine = event.text
                                // 同步更新 _initProgress 的 line，让 UI 能看到最新输出行
                                _initProgress.value = ContainerInitState.BundleInstalling(bundleId = null, line = event.text)
                            }
                            is CommandEvent.Exit -> exitCode = event.code
                        }
                    }
                } catch (e: Exception) {
                    lastLine = e.message
                    exitCode = -1
                }
            }
            if (exitCode == 0) {
                failed = emptyList()
            } else {
                // ⚠️ 以前这里写：failed = pkgs（apk 非 0 退出就把所有输入包都标失败）。
                // 但 rc29 用户日志显示：「zsh zsh-vcs」输入两个包，apk 退出码非 0（报 "1 error; 15 MiB in 24 packages"），
                // 而 apk info 字符数从 230 → 295，证明 zsh 和 22 个依赖都已经装进去了，只有 zsh-vcs
                // （Alpine 3.21 根本没有这个包名）报错——把 zsh 也列为失败是错误的。
                // 修复：apk 非 0 退出时，逐个查每个输入包是否真的在 apk world 里，只把真正不存在的包列入 failed。
                val installedWorld: Set<String> = runCatching {
                    execCaptured("apk info 2>/dev/null", projectPath = null, timeoutMs = APK_LIST_TIMEOUT_MS)
                        .output.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && APK_PKG_NAME_REGEX.matches(it) }
                        .toSet()
                }.getOrDefault(emptySet())
                failed = pkgs.filter { pkg ->
                    // apk world 里完全没包含任何一个与 pkg 名字匹配的已安装条目，才算真正失败
                    installedWorld.none { installed -> installed == pkg || installed.startsWith("$pkg-") }
                }
                if (failed.size < pkgs.size) {
                    // 有部分包其实成功了，把成功的那些也加到自定义快照里（不要漏掉）
                    val okPkgs = pkgs - failed.toSet()
                    if (okPkgs.isNotEmpty()) {
                        bundleRepository.addCustomSnapshots(okPkgs)
                        FileLogger.i(TAG, "自定义包部分成功（exit=$exitCode，部分失败）：已安装=$okPkgs，失败=$failed")
                    }
                }
            }
            if (failed.isEmpty()) {
                // 全成功：加到自定义包快照（UI 列表/磁盘标记都更新）
                bundleRepository.addCustomSnapshots(pkgs)
                FileLogger.i(TAG, "自定义包安装成功：$argLine")
            } else if (failed.size == pkgs.size) {
                // 全部失败，记录 warn（部分成功的 warn 已经在上面分支里打了）
                FileLogger.w(TAG, "自定义包安装全部失败($lastLine)：$argLine")
            }
        } finally {
            // ⚠️ 无论成功/失败/异常，finally 都无条件复位 _initProgress 回 Ready。
            // 不再依赖 containerInstaller.isInstalledFor(currentProfile) 条件：
            //   - currentProfile 是 @Volatile，操作期间若被 profile flow 异步更新，条件可能假
            //   - 一旦跳过，UI 永久停在 BundleInstalling，用户看到"装完还在转圈"
            // 只要当前状态仍在 BundleInstalling/Uninstalling，就复位成 Ready；
            // Idle / ExtractingRootfs / DeployingProot / Failed 这些非本操作的状态不破坏。
            val cur = _initProgress.value
            if (cur is ContainerInitState.BundleInstalling || cur is ContainerInitState.BundleUninstalling) {
                _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = false)
            }
            // 成功/失败后都刷新一次"真实的"自定义包清单（减去和 bundle 包重叠的，留下用户真正装的）
            runCatching { refreshCustomPackagesSnapshot() }
        }
        return failed
    }

    /** 卸载一个自定义包。成功返回 true。 */
    suspend fun uninstallCustomPackage(pkg: String): Boolean {
        if (!containerInstaller.isInstalledFor(currentProfile)) {
            bundleRepository.removeCustomSnapshot(pkg)
            return true
        }
        var ok = false
        // 进入卸载前先把整体状态挂成 BundleUninstalling（bundleId=null 表示自定义包）
        _initProgress.value = ContainerInitState.BundleUninstalling(bundleId = null)
        try {
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
        } finally {
            // 无论成功/失败/异常都复位到 Ready，避免永久停在 Uninstalling
            val cur = _initProgress.value
            if (cur is ContainerInitState.BundleInstalling || cur is ContainerInitState.BundleUninstalling) {
                _initProgress.value = ContainerInitState.Ready(migratedFromLegacyProvisioned = false)
            }
            bundleRepository.removeCustomSnapshot(pkg)
            // 刷新一次真实列表
            runCatching { refreshCustomPackagesSnapshot() }
        }
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
            // 显式把 stderr 与 stdout 分家：apk info 的 warning/error 默认走 stderr，
            // 但 apk 在 PRoot/pty 下偶尔会把两条流合并，所以这里再 2>/dev/null 一次兜底。
            // 另外 -I 或默认都可以，我们只需要纯包名清单。
            execCaptured("apk info 2>/dev/null", projectPath = null, timeoutMs = APK_LIST_TIMEOUT_MS).output
        }.getOrDefault("")
        val installed = text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank()
                    // 纯包名：必须匹配 apk world 包名规则（小写字母/数字/._+-，无空白、无 URL、无前缀大写）
                    && APK_PKG_NAME_REGEX.matches(line)
            }
            .toSet()
        val bundled = TerminalBundles.ALL
            .flatMap { it.packages.split(" ") }
            .map { it.trim() }.filter { it.isNotBlank() }
            .toSet()
        val basePkgs = setOf(
            "alpine-base", "alpine-keys", "apk-tools", "busybox", "busybox-binsh", "musl", "musl-utils",
            "libc-utils", "zlib", "ssl_client", "ca-certificates-bundle"
        )
        val custom = (installed - bundled - basePkgs).sorted()
        bundleRepository.saveCustomPackagesSnapshot(custom)
    }

    /**
     * 联动检测：从容器真实 apk 世界刷新 bundle 安装状态。
     *
     * 聊天页 AI 通过 Bash 直接 `apk add` / `apk del` 安装/卸载环境时，
     * 不会走 [installBundle] / [uninstallBundle]，导致 [TerminalBundleRepository]
     * 的标记状态与实际容器不一致（例如聊天页装了 python，功能包页仍显示"安装"；
     * 聊天页卸载了环境，功能包页仍显示"已安装"）。
     *
     * 本方法用 `apk info` 拉取真实已装包，逐 bundle 判断其主包是否在列，
     * 同步 StateFlow 与磁盘标记，让功能包页面与聊天页环境安装联动。
     * 由终端功能包页进入 / 手动刷新时调用。
     */
    suspend fun refreshBundleStatesFromApk() {
        if (!containerInstaller.isInstalledFor(currentProfile)) return
        val text = runCatching {
            execCaptured("apk info 2>/dev/null", projectPath = null, timeoutMs = APK_LIST_TIMEOUT_MS).output
        }.getOrDefault("")
        val installed = text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() && APK_PKG_NAME_REGEX.matches(line)
            }
            .toSet()
        for (b in TerminalBundles.ALL) {
            val pkgs = b.packages.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            val primary = pkgs.firstOrNull()
            val present = primary != null && installed.contains(primary)
            val current = bundleRepository.states.value[b.id]
            if (present) {
                if (current !is BundleInstallState.Installed) {
                    FileLogger.i(TAG, "联动检测：${b.displayName} 已装（apk 世界命中 ${primary}），同步为已安装")
                    bundleRepository.markInstalled(b.id, b)
                }
            } else {
                if (current is BundleInstallState.Installed) {
                    FileLogger.i(TAG, "联动检测：${b.displayName} 已卸载（apk 世界无 ${primary}），同步为未安装")
                    bundleRepository.markUninstalled(b.id)
                }
            }
        }
        // 顺带刷新自定义包，与 apk 世界保持一致
        runCatching { refreshCustomPackagesSnapshot() }
    }

    /** 重置容器：物理删除 rootfs + proot 全套 + qemu（按当前 profile 架构），bundleRepo 状态重置。高风险操作。 */
    suspend fun resetContainer() {
        // initMutex 保护：不与正在初始化的 job 并发
        initMutex.withLock {
            initJob?.cancel()
            initJob = null
        }
        bundleRepository.resetAllToNotInstalled()
        val profile = currentProfile
        runCatching {
            containerInstaller.rootfsDirFor(profile).deleteRecursively()
        }
        // 清理宿主侧可执行（跨 profile 共用的 proot 全套，都清；x86_64 专用 qemu 只在当前或 x86_64 架构下清）
        runCatching { containerInstaller.prootBin.delete() }
        runCatching { containerInstaller.prootLoader.delete() }
        runCatching { containerInstaller.prootLoader32.delete() }
        if (profile.arch == ContainerArch.X86_64) {
            runCatching { containerInstaller.rootfsX86Dir.deleteRecursively() }
            runCatching { containerInstaller.qemuX86Bin.delete() }
            runCatching { containerInstaller.qemuX86Bin.parentFile?.listFiles { f -> f.name.startsWith("qemu-") }?.forEach { it.delete() } }
        }
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
     *
     * ⚠️ 历史演进（不得回退 pipefail+tail 或 mktemp）：
     *  1. 旧版 1：`apk update 2>&1 | tail -n 3`（无 pipefail）——tail 永远 0，apk update 的失败码
     *     被吞，仓库损坏时继续 apk add，apk info 吐脏 WARNING。
     *  2. 旧版 2：加上 `set -o pipefail` —— 当 apk update 输出行数 >10 时，tail 先读完就关管道，
     *     apk update 收到 SIGPIPE 异常退出(128+13=141)，pipefail 把整条管道退出码记为 141≠0，
     *     set -e 强制中止整个脚本，apk add 根本不执行。
     *  3. rc31 版：`$(mktemp)` —— Alpine busybox 不提供 `mktemp` 命令，返回空字符串，
     *     `tail -n 10 ""` 报 `tail: no files`，set -e 中止，apk add 不执行。
     *  4. 当前版：直接重定向到 `/tmp/apk_update.log`（`bundleOpMutex` 保证串行，无文件名冲突），
     *     apk update 退出码 100% 保真，无管道无 SIGPIPE。`tail -n 10` 展示给用户看最后进度。
     */
    private fun apkMirrorAndUpdateScriptOnce(mirror: String = ContainerInstaller.ALPINE_MIRROR): String = buildString {
        append("mkdir -p /etc/apk\n")
        append("cat > /etc/apk/repositories <<EOF\n")
        append("$mirror/${ContainerInstaller.ALPINE_BRANCH}/main\n")
        append("$mirror/${ContainerInstaller.ALPINE_BRANCH}/community\n")
        append("EOF\n")
        append("# apk update 输出先存文件再 tail（避免管道 SIGPIPE 杀 apk update），且不依赖 mktemp（Alpine busybox 无此命令）\n")
        append("apk update > /tmp/apk_update.log 2>&1\n")
        append("tail -n 10 /tmp/apk_update.log; rm -f /tmp/apk_update.log\n")
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

    /**
     * 运行时切换容器 profile（如 arm64 ↔ x86_64 双架构无感切换），由 AI 的
     * `switch_container_arch` 工具与设置页调用。
     *
     * 流程：
     * 1. 立即更新内存 [currentProfile]（不必等 DataStore flow collector 异步刷新），新命令即刻生效；
     * 2. 持久化选中 id（[ContainerSettingsRepository.setActiveProfile]，重启后保持）；
     * 3. 若目标架构 rootfs 未安装，复用 [initScope]/[initMutex] 后台 job 一次性安装并等待完成，
     *    保证切换返回后新容器立即可用（首次切到 x86_64 会解压 x86_64 rootfs + 部署 qemu 转译器）。
     *
     * @return 切换后的目标 profile（调用方可据 [ContainerProfile.arch] 回显）
     */
    suspend fun switchToProfile(id: String): ContainerProfile {
        val target = resolveProfile(id)
        if (target.id == currentProfile.id) return target
        FileLogger.i(TAG, "切换容器 profile: ${currentProfile.id} -> ${target.id} (arch=${target.arch})")
        currentProfile = target
        containerSettingsRepository.setActiveProfile(target.id)
        val job = initMutex.withLock {
            val existing = initJob
            if (existing == null || !existing.isActive) {
                initJob = initScope.launch { doInit(target) }
            }
            initJob!!
        }
        job.join()
        refreshContainerHome()
        return target
    }

    /** 在 [initScope] 中真正执行一次性初始化：只解压 rootfs + 部署 proot。 */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed 在真正解压/部署时回调更新进度（已安装则快路径不回调）
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }

        // x86_64 容器：无论 rootfs 是否已安装，都要补一遍 qemu 转译器部署（幂等）
        // 原因：isInstalledX86 为了避免 ETXTBSY 循环不把 qemu 存在性当硬条件，此处做"
        // 最后一英里"同步；copyAsset 内部有原子 rename + ETXTBSY 捕获，不会崩。
        if (profile.arch == ContainerArch.X86_64) {
            runCatching { containerInstaller.deployQemuX86() }
                .onFailure { t -> FileLogger.w(TAG, "doInit: deployQemuX86 失败 (不抛): ${t.message}") }
            // 若 qemu 仍然缺失则打告警，buildBaseProotArgv 也会同步打，两边齐提醒用户。
            if (!containerInstaller.qemuX86Bin.exists()) {
                FileLogger.w(TAG, "doInit: x86_64 profile 但 qemu 仍缺失（${containerInstaller.qemuX86Bin.absolutePath}），请再次点击初始化或重启 App")
            }
        }

        // RC61f：rootfs 装好后，异步清理上一次 APP crash/kill 遗留下来的 /var/cache/apk/*.part 半截文件
        // （用户反馈：安装失败不删垃圾，点按钮重试 N 次，手机存储里越堆越多几 MB 的 .apk.part 占空间）
        runCatching {
            val garbageCleaner = ParallelPrefetchManager(
                slotsCount = 1,
                streamShell = { cmd, to -> streamExecNoInstall(cmd, projectPath = null, timeoutMs = to) },
                runSync = { cmd, to ->
                    val r = execCaptured(cmd, projectPath = null, timeoutMs = to)
                    r.output to (r.exitCode ?: (if (r.output.isEmpty()) -1 else 0))
                },
            )
            garbageCleaner.cleanupLeftoversFromLastRun(timeoutMs = 2500)
        }.onFailure { t -> FileLogger.d(TAG, "首次启动清理 .part 垃圾失败（不阻塞启动）：${t.message}") }
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

        // x86_64 容器：proot 的 -q 注入静态 qemu 转译器（宿主 arm64 运行，翻译容器内 x86_64 用户态指令）。
        // -q 后的路径是「宿主侧」绝对路径（qemu 本体跑在 proot 之外，需宿主 loader 加载），
        // 且 qemu 二进制须在 rootfs 之外可见——proot 自身直接 exec 它，不走 rootfs 翻译。
        if (profile.arch == ContainerArch.X86_64) {
            val qemu = containerInstaller.qemuX86Bin
            if (qemu.exists()) {
                argv.add("-q")
                argv.add(qemu.absolutePath)
            } else {
                FileLogger.w(TAG, "x86_64 profile 已选但 qemu 转译器缺失（${qemu.absolutePath}），按原生 aarch64 启动将失败，请重新初始化容器")
            }
        }

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
