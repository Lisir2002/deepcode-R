package com.R.codecore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.R.codecore.core.util.AILogger
import com.R.codecore.core.util.FileLogger
import net.schmizz.sshj.common.SecurityUtils
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.credentials.data.GitCredentialsFileSync
import com.R.codecore.feature.agent.domain.mcp.McpManager
import com.R.codecore.feature.settings.data.repository.KeepaliveSettingsRepository
import com.R.codecore.feature.settings.data.repository.LogSettingsRepository
import com.R.codecore.feature.settings.data.repository.resolveSshConfigOrNull
import com.R.codecore.feature.settings.data.remote.ModelMetadataService
import com.R.codecore.feature.terminal.domain.TerminalKeepaliveService
import com.R.codecore.core.security.CredentialEncryptor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application() {

    private companion object {
        const val TAG = "AIEditorApp"
    }

    override fun attachBaseContext(base: android.content.Context) {
        // 最早入口：在任何 Hilt 注入/业务初始化之前就绪日志与崩溃落盘。
        // 启动早期（如 Hilt 注入链实例化 @Singleton 工具）的崩溃若发生在 FileLogger 初始化之前
        // 会不留任何痕迹，故把日志与全局崩溃处理器提到 attachBaseContext 最前。
        FileLogger.init(base)
        AILogger.init(base)
        installCrashHandler()
        super.attachBaseContext(base)
    }

    /** Hilt 字段注入：在 [onCreate] 的 super 调用后即可用。 */
    @Inject
    lateinit var logSettings: LogSettingsRepository

    /** 后台保活开关持久化。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    /** MCP 生命周期总管：启动即连接已配置的远程 server。 */
    @Inject
    lateinit var mcpManager: McpManager

    /** 内置 MCP 服务器管理：注入即触发构造（init 读 DataStore + 按 autoStart 自动拉起监听）。 */
    @Inject
    lateinit var mcpServerManager: com.R.codecore.feature.agent.domain.mcp.server.McpServerManager

    /** git 凭据/署名落盘同步器：启动即把 Room 凭据 + DataStore 署名写到容器持久挂载目录，
     *  供终端/AI/UI 三端 git 经 credential.helper=store 共用，兜底 rootfs 升级或文件被删。 */
    @Inject
    lateinit var gitCredentialsFileSync: GitCredentialsFileSync

    /** 三端 git 缺凭据的统一弹窗桥：监听容器内 credential helper 经文件 IPC 发来的未登录请求，
     *  暴露 StateFlow 供全局弹窗回填后回喂 git。必须在主线程启动（FileObserver 绑定主 Looper）。 */
    @Inject
    lateinit var credentialRequestBridge: com.R.codecore.feature.credentials.data.CredentialRequestBridge

    /** 执行模式仓库（本地 PRoot / 远程 SSH）。 */
    @Inject
    lateinit var executionModeRepository: com.R.codecore.feature.settings.data.repository.ExecutionModeRepository

    /** 执行模式同步缓存：启动时从 DataStore 读首帧注入 DI。 */
    @Inject
    lateinit var executionModeHolder: com.R.codecore.feature.settings.data.repository.ExecutionModeHolder

    /** 远程 SSH 连接管理器：远程模式下启动即用配置建立连接。 */
    @Inject
    lateinit var remoteSshConnection: com.R.codecore.feature.agent.domain.container.RemoteSshConnection

    /** 工作区仓库：SSH 重连成功后重新加载工作区。 */
    @Inject
    lateinit var workspaceRepository: com.R.codecore.feature.workspace.data.repository.WorkspaceRepository

    /** 远程主机配置仓库：冷启动按 activeConnectionId 从 Room 单源取主机 + 凭据（密码/私钥）。 */
    @Inject
    lateinit var remoteRepository: com.R.codecore.feature.workspace.domain.repository.RemoteRepository

    /** 容器 profile 仓库：冷启动按 active profile 拿 RootfsSource.RemoteSsh 的 workspacePath。 */
    @Inject
    lateinit var containerSettingsRepository: com.R.codecore.feature.settings.data.repository.ContainerSettingsRepository

    /** 模型元数据服务：启动即异步刷新 models.dev 目录（24h 缓存，失败静默，兜底内置数据）。 */
    @Inject
    lateinit var modelMetadataService: ModelMetadataService

    /** 凭据加密器：后台异步预热，不阻塞首帧。 */
    @Inject
    lateinit var credentialEncryptor: CredentialEncryptor

    /** 长驻作用域：持续把持久化的日志等级同步到 FileLogger。
     * RC61b：附加 [CoroutineExceptionHandler]，任何子协程未捕获的异常都兜底记日志，
     * 避免 scope 内一个子协程崩把 scope.job 整个 cancel。 */
    private val appScope: CoroutineScope = run {
        val eh = CoroutineExceptionHandler { _, throwable ->
            FileLogger.e("AppScope", "AppScope 未捕获异常（已隔离，不影响主流程）", throwable)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + eh)
    }

    override fun onCreate() {
        super.onCreate()
        registerBouncyCastle()
        createNotificationChannels()
        // 主线程启动凭据请求监听（FileObserver 必须主线程创建与 startWatching），
        // 监听容器内 credential helper 写来的 cred-req-* → 全局弹窗回填 → 回喂 git 续跑。
        credentialRequestBridge.start()
        // 启动即把最新的内置指南手册提取到私有配置目录
        appScope.launch {
            ContainerInstaller.extractDocs(this@AIEditorApp)
        }
        // 启动即把内置提示词全量释放到 ~/.rcodecore/prompts/（覆盖式，随 App 升级更新）；
        // 用户自定义覆盖放在 ~/.rcodecore/prompts.custom/，同名即覆盖，不被升级覆盖。
        appScope.launch {
            ContainerInstaller.extractPrompts(this@AIEditorApp)
        }
        // 启动即把 Room 凭据 + DataStore 署名落盘到容器持久挂载（/root/.rcodecore），
        // 让终端裸 git / AI 工具 / UI 三端共用同一份凭据与署名配置。
        appScope.launch {
            gitCredentialsFileSync.syncAll()
        }
        // 启动即异步刷新 models.dev 模型元数据（24h 缓存；失败静默，resolve 兜底内置 assets 数据）。
        appScope.launch {
            modelMetadataService.refreshFromNetworkIfStale()
        }
        // 启动即加载持久化等级，并随设置页改动实时生效（唯一同步点）。
        appScope.launch {
            logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }
        }
        // ============== RC61b 修正：首帧优先，延后重活 + 严格异常/超时隔离 ==============
        // 冷启动时把「加载执行模式 → 建立 SSH 连接」延后 500ms，
        // 确保 MainActivity 先把首帧画出来。
        // 设计要点：
        //   ① 不再用 NonCancellable：进程低内存杀或系统要求取消时，这条链要可被取消。
        //   ② SSH connect 包 withTimeout(15s)：避免 TCP SYN 超时几十秒僵住线程。
        //   ③ 每段 runCatching 包住：任何一环失败都只记日志，不向上抛崩。
        appScope.launch {
            // 先预热加密子系统（纯后台 IO，不抢首帧资源）
            runCatching {
                credentialEncryptor.ensureInitialized()
            }.onFailure {
                FileLogger.w(TAG, "CredentialEncryptor 预热失败（将在首次加密/解密时重试）", it)
            }
            // 延后 500ms 再启动「执行模式 → SSH」这条链
            delay(500L)
            runCatching {
                val mode = executionModeRepository.executionModeFlow.first()
                executionModeHolder.setMode(mode)
                if (mode == com.R.codecore.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
                    val settings = executionModeRepository.remoteConnectionFlow.first()
                    val cfg = settings?.resolveSshConfigOrNull(
                        remoteRepository = remoteRepository,
                        containerSettingsRepository = containerSettingsRepository,
                    )
                    if (cfg != null) {
                        runCatching {
                            // connect 设 15s 超时：sshj 默认 connect 超时慢，这里强制上限，
                            // 超时/失败不阻塞首帧，首次命令时 retry。
                            withTimeout(15_000L) {
                                remoteSshConnection.connect(cfg)
                            }
                            syncDocsToRemote()
                        }.onFailure { ex ->
                            val note = when (ex) {
                                is TimeoutCancellationException -> "SSH 连接超时 15s，将在首次命令时重试"
                                else -> "启动时 SSH 连接失败，将在首次命令时重试"
                            }
                            FileLogger.e(TAG, note, ex)
                        }
                    }
                    remoteSshConnection.startSupervisor(appScope) {
                        runCatching { workspaceRepository.initialize() }
                            .onFailure { FileLogger.w(TAG, "SSH 重连后重新加载工作区失败", it) }
                        syncDocsToRemote()
                    }
                }
            }.onFailure { ex ->
                FileLogger.e(TAG, "启动期执行模式/SSH 初始化整体失败（不影响 UI 启动，后续首次命令重试）", ex)
            }
        }
        // 后台保活常驻通知的唯一反应器：监听开关，启停 TerminalKeepaliveService 的常驻模式。
        // 既覆盖设置页实时切换，也覆盖冷启动恢复。仅在「由开变关」时发 disable，
        // 避免为关闭而凭空拉起从未开过的 Service。
        appScope.launch {
            var last: Boolean? = null
            keepaliveSettings.enabledFlow.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    TerminalKeepaliveService.enablePersistent(this@AIEditorApp)
                } else if (last == true) {
                    TerminalKeepaliveService.disablePersistent(this@AIEditorApp)
                }
                last = enabled
            }
        }
        // 连接已配置的 MCP server，把其工具注册进 ToolRegistry（内部自有 scope，失败不影响启动）。
        mcpManager.start()
    }

    /**
     * 读取 assets/docs 下所有内置文档，通过 SSH exec 同步到远程 ~/.rcodecore/docs/。
     * 远程模式下 AI 查阅 ~/.rcodecore/docs/ 的设置说明文档时，需要这些文件存在于远程服务器。
     * 连接成功与重连成功后调用，保证远程文档随 App 升级更新。失败仅记日志，不阻断流程。
     */
    private suspend fun syncDocsToRemote() {
        runCatching {
            val names = assets.list("docs") ?: return@runCatching
            val docs = linkedMapOf<String, String>()
            for (name in names) {
                val content = assets.open("docs/$name").bufferedReader().use { it.readText() }
                docs[name] = content
            }
            remoteSshConnection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "同步内置文档到远程失败", it) }
    }

    /** 注册完整版 BouncyCastle 取代 Android 自带的裁剪版。
     *  sshj 0.38.0 用 X25519 做密钥交换，Android 自带的 BC provider 不含 X25519 算法，
     *  需先移除裁剪版再注册 bcprov-jdk18on（sshj 传递依赖）的完整版，并告诉 sshj 使用它。
     *  必须在任何 sshj 调用之前完成。 */
    private fun registerBouncyCastle() {
        // 先移除 Android 自带的裁剪版 BC，再注册完整版 bcprov-jdk18on。
        // 用 addProvider 而非 insertProviderAt(…, 1)：BC 只需存在于 Provider 列表中供 sshj
        // 通过 SecurityUtils.setSecurityProvider("BC") 按名查到即可，无需排到最高优先级。
        // 若抬到第 1 位，会抢占 OkHttp/Conscrypt 初始化默认 SSLContext 时的 KeyStore 查找，
        // BC 注册了 BKS 类型却没有配套默认 truststore，导致抛 KeyStoreException: BKS not found
        // （表现为检测更新等 HTTPS 请求崩溃）。放末尾让系统自带 provider 继续负责 TLS。
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }

    /** 捕获未处理异常并落盘，随后交回系统默认处理器（保留原有崩溃弹窗/上报行为）。
     *
     *  RC61b hotfix3 关键修正：此前 FileLogger 把 CRASH 行通过 ioExecutor 异步落盘，
     *  但「调用 previous?.uncaughtException → 系统立即杀进程」之后排队任务根本没机会执行，
     *  结果就是**用户看到 1-2 秒闪退、日志里找不到任何 CRASH 记录**。此处改为两步：
     *    ① 先 e() 到 logcat（立即生效、不丢）；
     *    ② 再 flushSync 同步阻塞落盘（return 前一定写进文件，哪怕慢 10ms）；
     *  最后才交给系统默认处理器杀进程，保证**闪退之后一定能看到 CRASH 栈**。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val summary = "线程 ${thread.name} (id=${thread.id}) 未捕获异常，即将同步写入日志后交回系统"
            // Step 1: logcat（即时，即使后续写文件也炸一定有保底）
            android.util.Log.e("CRASH", summary, throwable)
            // Step 2: 同步写日志文件（确保 return 前已写入）
            runCatching {
                FileLogger.flushSync("FATAL", "CRASH", summary, throwable)
            }.onFailure {
                android.util.Log.e("CRASH", "⚠️ 连同步落盘都失败了，此时只能靠上面 logcat 追溯", it)
            }
            // Step 3: 交给系统原处理器（弹出"应用已停止运行"弹窗 + 收集 dropbox，最终杀进程）
            runCatching {
                previous?.uncaughtException(thread, throwable)
            }.getOrElse {
                // 默认处理器自己也炸（极个别定制 ROM），兜底强杀让整个进程彻底退出，
                // 绝不能「吞掉异常继续跑」——那会把 Application 留在半初始化状态。
                android.util.Log.e("CRASH", "default uncaught handler 也抛异常，兜底 killProcess", it)
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "terminal_service",
                "Terminal Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for background terminal tasks"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
