package com.R.codecore

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.SharedPreferences
import android.os.Build
import app.cash.sqldelight.db.QueryResult
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
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.LibName
import com.R.codecore.datalayer.sqldelight.AgentDb
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application() {

    private companion object {
        const val TAG = "AIEditorApp"

        /** 预防闸门诊断标记存储（见 [diagnoseLastExit] / [recordTrimCritical]）。 */
        const val DIAG_PREFS = "rcodecore_diag"

        /** 上次运行触发内存临界（RUNNING_CRITICAL/lowMemory）的时间戳，供下次启动诊断「无日志闪退」。 */
        const val KEY_LAST_TRIM_CRITICAL = "last_trim_critical_ms"

        /**
         * 启动预热 AGENT 库是否已成功完成（rc6 指纹）。崩溃快照首行输出该标记：
         *  - `true` = 本进程已执行过 AGENT 库结构自愈/对齐，若此刻仍出现 `no such column`，
         *    说明坏表残留但自愈未修复（真·数据层问题），据此继续深挖；
         *  - `false` = 本进程根本没跑到预热，直接指向「跑的不是含 rc6 代码的包 / 旧进程残留」。
         * 一次性坐实「是不是装对包」，结束此前反复的版本扯皮。
         */
        val agentPreheatCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
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

    /** 网络层优化 C1：三家 AI host 连接预热（启动后台预建 DNS+TCP+TLS+HTTP2，降低首字延迟）。 */
    @Inject
    lateinit var connectionPrewarmer: com.R.codecore.core.network.ConnectionPrewarmer

    /** 凭据加密器：后台异步预热，不阻塞首帧。 */
    @Inject
    lateinit var credentialEncryptor: CredentialEncryptor

    /** 定时提醒调度循环：启动即轮询扫描到点的 schedule 项，投递给对应会话（DSH schedule）。 */
    @Inject
    lateinit var scheduleScheduler: com.R.codecore.feature.agent.domain.schedule.ScheduleScheduler

    /** Room 数据库（数据层重构后为 5 个域库）：启动期后台做完整性检查，提前暴露损坏
     * （损坏的 DB 会触发 SQLite 原生崩溃，绕过 Java CrashHandler，正是「模型输出时闪退却无日志」的典型盲区）。 */
    /** 内置服务浏览器控制器（@Singleton）：低内存临界时释放其持有的页面快照大对象缓存，
     *  降低 LMKD 静默杀进程概率（LMKD 杀绕过 Java CrashHandler，是「模型输出时闪退却无日志」高概率根因）。 */
    @Inject
    lateinit var browserController: com.R.codecore.feature.browser.domain.BrowserController



    /** 数据保全通知器：启动即跑哨兵（D4）+ 升级前双保险自动备份（D5），并把判定结果发布给
     *  MainActivity 的启动级全局告警弹窗（解决「数据消失却不报错」，数据保全防线 D8b）。 */
    @Inject
    lateinit var dataSafetyNotifier: com.R.codecore.feature.backup.data.DataSafetyNotifier

    /** 数据层连接池（rc5）：启动即无条件预热 AGENT 库触发结构自愈，见 [onCreate]。 */
    @Inject
    lateinit var connectionPool: ConnectionPool

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
        // ============== rc5 关键修复：启动无条件预热 AGENT 库，铁定先于任何 UI 查询 ==============
        // 此前 AGENT 库的 ensureSchema + SchemaSelfHealer 依赖 ViewModel / DataRegistry 的「懒加载触发」，
        // 理论上存在「首个 UI 数据查询先于自愈」的竞态窗口，表现为启动后一打开会话页就
        // no such column: agent_message.id。
        // 这里在 Application 层**同步、无条件**触发 ConnectionPool.onOpened——其内部对 AGENT 库
        // 先跑 ensureSchema，再跑 SchemaSelfHealer 幂等自愈（缺列即无损重建）。确保自愈铁定先于
        // 任何页面/查询完成。正常库只是两次 PRAGMA 幂等检查（毫秒级），缺失才重建。
        // 若自愈仍异常，让其向上冒泡 → 由 installCrashHandler 落**语义明确**的崩溃日志
        // （自愈自定义错误），而非毫无信息的 "no such column"，便于继续定位。
        FileLogger.i(TAG, "启动：无条件预热 AGENT 数据层（触发结构自愈）")
        val agentDriver = connectionPool.driver(LibName.AGENT)
        agentPreheatCompleted.set(true)
        FileLogger.i(TAG, "启动预热 AGENT 数据层完成（agent_message / agent_session 结构已就绪）")

        // ── rc7 诊断：在同一 driver 上立刻做一次真实查询 ──
        // 如果这次就报 no such column，说明 schema.create 本身有问题（而不是查询路径绕过）。
        runCatching {
            agentDriver.executeQuery(
                null,
                "PRAGMA table_info(agent_message)",
                { cursor ->
                    val cols = mutableListOf<String>()
                    while (cursor.next().value) {
                        val name: String? = cursor.getString(1)
                        if (name != null) cols.add(name)
                    }
                    FileLogger.i(TAG, "【rc8 诊断】agent_message 真实列: ${cols.joinToString()}")
                    QueryResult.Unit
                },
                0,
                null,
            ).value
            // 冒烟测试：直接调用 SQLDelight **生成的那条查询**，而不是手写一段「看起来像」的 SQL。
            //
            // rc8 事故教训：此前这里手写 "SELECT agent_message.id FROM agent_message LIMIT 1"，
            // 其形态（FROM 真实表）与真实生成 SQL（FROM 匿名嵌套子查询）**不同**——
            // 手写版编译通过并打出「✅」，业务侧却照崩，诊断给出假阳性绿灯，
            // 直接误导 rc1~rc7 把 7 轮精力投在「表结构缺列」的错误方向上。
            //
            // 改为直接构造 AgentDb 调 agentQueries.selectMessagesBySessionPaged：
            // 与线上崩溃路径 100% 同构，且随 .sq 变更自动同步，永不漂移。
            // 任何「生成的 SQL 编译不过 / 列绑定失败」都会在启动阶段就地暴露，
            // 而不是等用户在主线程收集 Flow 时才炸。
            AgentDb(agentDriver).agentQueries
                .selectMessagesBySessionPaged("__startup_smoke__", 1L)
                .executeAsList()
            FileLogger.i(TAG, "【rc9 冒烟】selectMessagesBySessionPaged 启动期执行成功 ✅")
        }.onFailure { e ->
            // 冒烟失败说明生成的 SQL 本身就不可编译/绑定——不要放行到 UI，
            // 否则就是 rc8 那种「启动灯全绿、一进会话页就崩」。
            FileLogger.e(TAG, "【rc9 冒烟】selectMessagesBySessionPaged 启动期执行失败（生成 SQL 有问题，非表结构问题）", e)
        }
        // 数据层重构：前 DataStore（settings_prefs / workspace_prefs / terminal_prefs / proxy_prefs /
        // mcp_server_prefs / app_run_meta / ftp_server_prefs）全部迁移到 SQLDelight InfraDb.kv_store，
        // 启动期不再需要 DataStore 收敛搬迁器，首次打开 SQLite 自动走 KVStore observe。
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
        // 启动即自动导出上一轮日志到公共外部存储 Download/RCodeCore/logs/。
        // 目的：解决「进入应用即闪退」时崩溃处理来不及同步导出（或 CrashHandler 前的早期崩溃）
        // 拿不到日志的问题——只要 App 能再次启动，上一轮的全部日志（含 CRASH 记录）就会自动
        // 落到文件管理器可见的公共目录（API 29+ MediaStore 免权限，卸载后仍保留）。
        // 与崩溃时同步导出互补：崩溃时导出保证当次崩溃可被捕获，启动时导出保证"漏网"的早期崩溃
        // 也能在下次启动后被拿到。失败仅记日志，不阻断启动。
        appScope.launch {
            runCatching { FileLogger.exportLogsToDownloads(this@AIEditorApp) }
                .onFailure { FileLogger.w(TAG, "启动时自动导出日志到公共目录失败（忽略，私有日志仍在）", it) }
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
        // 网络层优化 C1：后台预热三家 AI host 连接（DNS+TCP+TLS+HTTP2 握手留在共享连接池），
        // 正式请求复用后省掉 1~3 RTT 首字延迟；失败静默，绝不影响主链路与首帧。
        appScope.launch {
            runCatching { connectionPrewarmer.warmDefaults() }
                .onFailure { FileLogger.w(TAG, "连接预热异常（忽略，不影响主链路）", it) }
        }
        // 启动即后台做数据库完整性检查：损坏的 DB 会触发 SQLite 原生崩溃（SIGSEGV/SIGABRT），
        // 完全绕过 Java CrashHandler，表现为「模型输出/写入时突然闪退、日志无报错」。
        // 这里提前把损坏状态写进日志（并在崩溃时随快照导出），让下次闪退有迹可循。
        // 失败仅记日志，不阻断启动；integrity_check 在 IO 线程跑，不抢首帧。
        // 预防闸门（异常退出自诊断）：上次运行若触发过内存临界（RUNNING_CRITICAL/lowMemory），
        // 进程可能已被 LMKD 静默回收（无 Java 日志）——本次启动读出标记并写日志留痕，
        // 配合启动时自动导出日志，让「无报错闪退」在下一次启动自动留下排查证据。
        appScope.launch {
            runCatching { diagnoseLastExit() }
                .onFailure { FileLogger.w(TAG, "上次退出诊断异常（忽略，不影响启动）", it) }
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

        // 数据保全（D4/D5/D8b）：启动即跑数据完整性哨兵（区分全新安装/正常升级/数据丢失/包名被改），
        // 判定为「正常升级」时自动双保险备份（本机私有 + 外部公共目录）；判定结果发布给
        // MainActivity，数据疑似丢失/包名变更时弹启动级全局告警（用户第一眼就能看到，不再静默）。
        // 设计要点：延后 500ms 不抢首帧；runCatching 隔离，任何失败只记日志不阻断启动。
        appScope.launch {
            delay(500L)
            runCatching { dataSafetyNotifier.run() }
                .onFailure { FileLogger.w(TAG, "数据保全（哨兵/自动备份/通知）失败，不影响启动", it) }
        }
        // 定时提醒调度循环：启动即轮询扫描到点的 schedule 项（内部异常隔离，失败不影响启动）。
        scheduleScheduler.start(appScope)
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
            // Step 2.5: 崩溃日志同步导出到公共外部存储 Download/RCodeCore/logs/。
            //   私有目录（Android/data/...）在 Android 11+ 文件管理器不可见，用户拿不到日志；
            //   这里把全部日志 + 一份带时间戳的崩溃快照写进公共 Downloads（API 29+ MediaStore 免权限），
            //   保证闪退后用户/开发者能在文件管理器直接定位崩溃栈。
            runCatching {
                val stamp = java.time.Instant.now().toString().replace(":", "-")
                val snapshot = buildString {
                    append("RCodeCore 崩溃快照  ").append(stamp).append('\n')
                    append("version=").append(BuildConfig.VERSION_NAME).append('\n')
                    append("schemaVersion=AGENT-v3").append('\n')
                    append("agentPreheatRan=").append(AIEditorApp.agentPreheatCompleted.get()).append('\n')
                    append("=".repeat(60)).append('\n')
                    append(summary).append('\n')
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    append(sw.toString()).append('\n')
                    append("=".repeat(60)).append('\n')
                    append("日志已同步落盘到私有目录，本快照用于快速定位。完整日志见同目录 log-*.txt。\n")
                }
                FileLogger.exportLogsToDownloads(this@AIEditorApp, "crash-$stamp.log" to snapshot)
            }.onFailure {
                android.util.Log.e("CRASH", "⚠️ 崩溃日志导出到公共目录失败，仍保留私有日志", it)
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

    /**
     * 内存压力回调：系统在 LMKD 杀进程前会先逐级回调这里。流式输出 + 工具执行是本应用
     * 内存峰值时刻，若在此被 LMKD 静默杀死，Java CrashHandler 完全感知不到（无 CRASH 日志）。
     * 这里把逐级压力 + 可用内存写进日志，让「看不见报错」的闪退留下最后一次内存记录。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        val avail = info.availMem / (1024 * 1024)
        val total = info.totalMem / (1024 * 1024)
        val levelTag = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            else -> "level=$level"
        }
        FileLogger.w("Memory", "onTrimMemory $levelTag, heapUsed=${heap}MB, avail=${avail}MB/$total" +
            "MB, lowMemory=${info.lowMemory}")
        if (info.lowMemory) {
            FileLogger.e("Memory", "系统内存严重不足（lowMemory=true），极可能被 LMKD 静默杀进程 → 闪退且无 Java 日志")
        }
        // 预防闸门（内存峰值自愈）：只在最危险级别（RUNNING_CRITICAL / lowMemory）主动降负——
        //  ① 释放浏览器页面快照大对象缓存（降低当前进程堆峰值，提高被 LMKD 保留的概率）；
        //  ② 对所有域库做 WAL checkpoint(TRUNCATE)：WAL 模式被 LMKD 杀进程时，未合并回主库的
        //     WAL/SHM 在下次启动可能触发 SQLite 原生崩溃（绕过 Java CrashHandler）——主动合并
        //     显著缩小该损坏窗口；
        //  ③ 落「内存临界」时间戳标记，供下次启动 diagnoseLastExit 自诊断「无日志闪退」。
        //  ①② 均在 appScope 异步执行（onTrimMemory 在主线程回调，不能做 IO）。
        val critical = info.lowMemory || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (critical) {
            runCatching { browserController.onMemoryPressure() }
                .onFailure { FileLogger.w(TAG, "低内存释放浏览器快照缓存失败（忽略）", it) }
            recordTrimCritical()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FileLogger.e("Memory", "onLowMemory 回调：进程即将被系统回收，流式输出/工具执行期间最易在此闪退")
    }

    

    /** 预防闸门（自诊断）：落「上次触发内存临界」时间戳标记，供下次启动 [diagnoseLastExit] 读取。 */
    private fun recordTrimCritical() {
        diagPrefs().edit().putLong(KEY_LAST_TRIM_CRITICAL, System.currentTimeMillis()).apply()
    }

    /**
     * 预防闸门（异常退出自诊断）：读取上次运行是否触发过内存临界（[recordTrimCritical] 落盘）。
     * 若在 12 小时内，判定「上次进程可能被 LMKD 静默回收」（该路径无 Java 日志，是「模型输出时
     * 闪退却无报错」的典型盲区），写日志留痕；随后清除标记，避免同一条告警反复刷屏。
     */
    private fun diagnoseLastExit() {
        val last = diagPrefs().getLong(KEY_LAST_TRIM_CRITICAL, 0L)
        if (last > 0L) {
            val agoMin = (System.currentTimeMillis() - last) / 60_000
            if (agoMin < 12 * 60) {
                FileLogger.w(
                    TAG,
                    "预防闸门：上次运行 ${agoMin} 分钟前曾触发内存临界（RUNNING_CRITICAL/lowMemory），" +
                        "进程可能已被系统静默回收（LMKD 杀无 Java 日志）；" +
                        "排查见 Download/RCodeCore/logs/（本次启动已自动导出）"
                )
            }
        }
        diagPrefs().edit().remove(KEY_LAST_TRIM_CRITICAL).apply()
    }

    private fun diagPrefs(): SharedPreferences = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
}