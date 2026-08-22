package com.R.codecore

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.R.codecore.core.theme.pageEnterTransition
import com.R.codecore.core.theme.pageExitTransition
import com.R.codecore.core.theme.pagePopEnterTransition
import com.R.codecore.core.theme.pagePopExitTransition
import com.R.codecore.core.theme.terminalEnterTransition
import com.R.codecore.core.theme.terminalExitTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.R.codecore.core.theme.AIEditorTheme
import com.R.codecore.feature.agent.presentation.AIAgentViewModel
import com.R.codecore.feature.agent.presentation.component.AIChatPanel
import com.R.codecore.feature.agent.presentation.component.ChatDrawerContent
import com.R.codecore.feature.git.presentation.GitViewModel
import com.R.codecore.feature.git.presentation.component.GitScreen
import com.R.codecore.feature.settings.data.repository.KeepaliveSettingsRepository
import com.R.codecore.feature.settings.data.repository.AppThemeMode
import com.R.codecore.feature.settings.data.repository.ThemeSettingsRepository
import com.R.codecore.feature.settings.presentation.SettingsViewModel
import com.R.codecore.feature.settings.presentation.component.SettingsScreen
import com.R.codecore.feature.terminal.domain.TerminalKeepaliveService
import com.R.codecore.feature.terminal.presentation.TerminalSettingsViewModel
import com.R.codecore.feature.terminal.presentation.TerminalViewModel
import com.R.codecore.feature.terminal.presentation.component.TerminalBundleManagerScreen
import com.R.codecore.feature.terminal.presentation.component.TerminalScreen
import com.R.codecore.feature.terminal.presentation.component.TerminalSettingsScreen
import com.R.codecore.feature.workspace.presentation.WorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.R.codecore.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 用于冷启动时在前台恢复常驻保活通知（App.onCreate 的启动可能被后台 FGS 限制挡掉）。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    /** App 回到前台时，远程模式下若 SSH 断了触发重连。 */
    @Inject
    lateinit var remoteSshConnection: com.R.codecore.feature.agent.domain.container.RemoteSshConnection

    @Inject
    lateinit var executionModeHolder: com.R.codecore.feature.settings.data.repository.ExecutionModeHolder

    /** 三端（UI/AI Bash/交互终端）git 缺凭据统一弹窗桥：在 AIEditorApp 启动后监听 helper 的文件 IPC 请求。 */
    @Inject
    lateinit var credentialRequestBridge: com.R.codecore.feature.credentials.data.CredentialRequestBridge

    /** 内置服务浏览器：用户与模型共享的 WebView 会话（浏览器页/模型工具共用）。 */
    @Inject
    lateinit var browserController: com.R.codecore.feature.browser.domain.BrowserController

    /** 浏览器登录凭据输入流程（模型登录时请求用户提供账号密码）。 */
    @Inject
    lateinit var browserLoginPromptManager: com.R.codecore.feature.browser.domain.BrowserLoginPromptManager

    /** 浏览器「用户接管」流程（验证码/支付/二次认证时请求用户亲自完成）。 */
    @Inject
    lateinit var browserTakeoverManager: com.R.codecore.feature.browser.domain.BrowserTakeoverManager

    /** 数据保全通知器：观察哨兵判定结果，数据疑似丢失/包名变更时弹启动级全局告警（D8b）。 */
    @Inject
    lateinit var dataSafetyNotifier: com.R.codecore.feature.backup.data.DataSafetyNotifier

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (granted) {
            // 权限授予后把日志目录切换到公共外部存储（卸载后仍保留）
            com.R.codecore.core.util.FileLogger.onExternalStorageGranted(this)
            com.R.codecore.core.util.AILogger.onExternalStorageGranted(this)
        } else {
            Toast.makeText(
                this,
                getString(R.string.main_no_storage_permission),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION") // 全局更新 application resources locale，createConfigurationContext 无法替代
    override fun onCreate(savedInstanceState: Bundle?) {
        // RC61b：enableEdgeToEdge() 必须在 super.onCreate() 之后调用（ComponentActivity 基类要求）。
        // 早于 super 调用时，基类内部的 mSavedStateRegistry / mConfigChangeTracker 尚未初始化，
        // 会在低版本 AndroidX Activity 上触发 NPE 或警告，导致冷启动偶发 1-2s 秒退。
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLegacyStoragePermissionIfNeeded()
        // API 30+：全局切到 ADJUST_NOTHING，由 rememberImeBottomInset() 接管键盘内边距。
        // 必须在 Activity 级别统一设置，不能在每个 composable 里各自 save/restore——
        // NavHost 过渡动画期间新旧页面共存，旧页面 dispose 恢复 softInputMode 会触发窗口重布局导致白屏。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContent {
            val themeMode by themeSettings.themeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.AUTO -> systemDarkTheme
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            AIEditorTheme(darkTheme = darkTheme) {
                // 将 MainActivity 算好的"APP 实际暗模式"通过 CompositionLocal 下发，
                // 子树里的终端内容配色、跟随程序开关都读这同一个值，
                // 保证 APP 切到"强制白/强制黑"时，终端颜色不会还停留在系统主题。
                androidx.compose.runtime.CompositionLocalProvider(
                    com.R.codecore.core.theme.LocalAppDarkMode provides darkTheme
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation(
                            browserController = browserController,
                            browserLoginPromptManager = browserLoginPromptManager,
                            browserTakeoverManager = browserTakeoverManager,
                            dataSafetyNotifier = dataSafetyNotifier
                        )
                        // 全局凭据弹窗：覆盖所有页面，命令行 git 缺凭据在任意页面都能弹。
                        com.R.codecore.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                            bridge = credentialRequestBridge
                        )
                    }
                }
            }
        }

        // 此时处于前台，启动前台服务一定被允许：若用户曾开启常驻保活，补上通知。
        lifecycleScope.launch {
            if (keepaliveSettings.isEnabled()) {
                TerminalKeepaliveService.enablePersistent(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 远程模式回到前台时，若 SSH 断了立即触发重连，不等 supervisor 轮询
        if (executionModeHolder.currentMode() == com.R.codecore.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
            lifecycleScope.launch {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
            }
        }
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            storagePermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/**
 * 根导航容器。
 *
 * [ModalNavigationDrawer] 放在 [NavHost] **外面**，使 Drawer 的生命周期独立于页面切换。
 *
 * NavHost 禁用了全部过渡动画（enterTransition / exitTransition = None）——
 * Terminal 页面的 [AndroidView] 不参与 Compose 的 graphicsLayer alpha 动画，
 * 如果保留默认 fadeIn/fadeOut，过渡期间新旧 composable 共存，TerminalView 以满不透明度
 * 覆盖在新页面之上；过渡结束后原生 View 被移除触发 View 层级重布局，恰与 Drawer 打开动画
 * 叠加导致渲染管线中断——表现为「退出终端后立即点侧边栏白屏」。
 *
 * ViewModel 提升到这一层创建，以便 Drawer 内容和 AIChatPanel 共享同一实例。
 */
@Composable
fun AppNavigation(
    browserController: com.R.codecore.feature.browser.domain.BrowserController,
    browserLoginPromptManager: com.R.codecore.feature.browser.domain.BrowserLoginPromptManager,
    browserTakeoverManager: com.R.codecore.feature.browser.domain.BrowserTakeoverManager,
    dataSafetyNotifier: com.R.codecore.feature.backup.data.DataSafetyNotifier
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 用于判断当前路由：仅在聊天页允许 Drawer 手势。
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Activity 级别的 ViewModel——Drawer 和 AIChatPanel 共享同一个实例。
    val agentViewModel: AIAgentViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()

    // 侧边栏打开时，系统返回键先收起侧边栏。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 侧边栏需要的数据。
    val currentWorkspace by workspaceViewModel.current.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(currentWorkspace) {
        // 远程模式连接未就绪时 currentWorkspace 为 null，不触发 setWorkspace，避免空路径点燃 session 加载
        val path = currentWorkspace?.path ?: return@LaunchedEffect
        agentViewModel.setWorkspace(path)
    }

    val sessions by agentViewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by agentViewModel.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agentViewModel.agentStates.collectAsStateWithLifecycle()

    // ── 导出会话：SAF 保存文件 ──
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val sessionId = pendingExportSessionId
        if (uri != null && sessionId != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    agentViewModel.exportSession(sessionId, os) { success ->
                        Toast.makeText(
                            context,
                            context.getString(if (success) R.string.chat_export_session_done else R.string.chat_export_session_failed),
                            if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_export_session_failed), Toast.LENGTH_LONG).show()
                }
                pendingExportSessionId = null
            }
        } else {
            pendingExportSessionId = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 仅在聊天页启用手势滑出；其他页面禁止（但已打开时始终可关闭）。
        gesturesEnabled = currentRoute == "chat" || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 0.dp,
                modifier = Modifier.width(300.dp)
            ) {
                ChatDrawerContent(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    onSelect = {
                        agentViewModel.selectSession(it.id)
                        scope.launch { drawerState.close() }
                    },
                    onCreate = {
                        agentViewModel.newSession()
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { agentViewModel.deleteSession(it.id) },
                    onRename = { session, title -> agentViewModel.renameSession(session.id, title) },
                    onExport = { session ->
                        pendingExportSessionId = session.id
                        val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
                        sessionExportLauncher.launch("rcodecore-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
                    },
                    onNavigateToSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
                    },
                    onNavigateToCapabilityCenter = {
                        scope.launch { drawerState.close() }
                        navController.navigate("capability_center")
                    },
                    onNavigateToBrowser = {
                        scope.launch { drawerState.close() }
                        navController.navigate("browser")
                    }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = "chat",
            enterTransition = {
                when (targetState.destination.route) {
                    "terminal" -> terminalEnterTransition
                    else -> pageEnterTransition
                }
            },
            exitTransition = {
                when (initialState.destination.route) {
                    "terminal" -> terminalExitTransition
                    else -> pageExitTransition
                }
            },
            popEnterTransition = {
                when (targetState.destination.route) {
                    "terminal" -> terminalEnterTransition
                    else -> pagePopEnterTransition
                }
            },
            popExitTransition = {
                when (initialState.destination.route) {
                    "terminal" -> terminalExitTransition
                    else -> pagePopExitTransition
                }
            }
        ) {
            composable("chat") {
                AIChatPanel(
                    viewModel = agentViewModel,
                    settingsViewModel = settingsViewModel,
                    workspaceViewModel = workspaceViewModel,
                    drawerState = drawerState,
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToTerminal = { navController.navigate("terminal") },
                    onNavigateToGit = { navController.navigate("git") },
                    onNavigateToBrowser = { navController.navigate("browser") }
                )
            }
            composable("settings") {
                // 复用 Activity 级 settingsViewModel（MainActivity 顶部已创建并 init），
                // 避免进入设置页时再建一个 NavBackStackEntry 级实例、重复跑 init 与 9 路 flow 订阅，
                // 这是侧边栏点设置「卡一下」的主因。
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { 
                        navController.popBackStack() 
                        scope.launch { drawerState.open() }
                    },
                    onNavigateToTerminalSettings = { navController.navigate("terminal_settings") },
                    // RC62：用户点「管理 SSH 主机配置」不再是占位。
                    // 从 SettingsScreen（本路由内部）点 → 直接让 SettingsScreen 的 section 切换 RemoteServers。
                    // 实现方式：利用 SettingsScreen 内部已经消费的 SettingsViewModel.openSection() 机制，
                    //   它内部 section 是 remember mutableState，但 LaunchedEffect(pendingTick) 会在 next frame
                    //   把它赋值成 RemoteServers。
                    onNavigateToSshHosts = {
                        settingsViewModel.openSection(
                            com.R.codecore.feature.settings.presentation.component.SettingsSection.RemoteServers
                        )
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() },
                    onNavigateToNetProxy = { navController.navigate("proxy_config") }
                )
            }
            composable("capability_center") {
                val capabilityViewModel: com.R.codecore.feature.capability.presentation.CapabilityCenterViewModel = hiltViewModel()
                val currentSessionMode by agentViewModel.currentSessionMode.collectAsStateWithLifecycle()
                com.R.codecore.feature.capability.presentation.component.CapabilityCenterScreen(
                    viewModel = capabilityViewModel,
                    currentSessionMode = currentSessionMode,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSkillDetail = { skillId ->
                        navController.navigate("skill_detail/$skillId")
                    },
                    onEditSkill = { skillId ->
                        navController.navigate("skill_edit/$skillId")
                    }
                )
            }
            composable("skill_detail/{skillId}") { entry ->
                val skillId = entry.arguments?.getString("skillId") ?: ""
                val detailViewModel: com.R.codecore.feature.settings.presentation.SkillDetailViewModel = hiltViewModel()
                com.R.codecore.feature.settings.presentation.component.SkillDetailScreen(
                    viewModel = detailViewModel,
                    skillId = skillId,
                    onNavigateBack = { navController.popBackStack() },
                    onEditSkill = { id ->
                        navController.navigate("skill_edit/$id")
                    }
                )
            }
            composable("skill_edit/{skillId}") { entry ->
                val skillId = entry.arguments?.getString("skillId") ?: ""
                val editViewModel: com.R.codecore.feature.settings.presentation.SkillEditViewModel = hiltViewModel()
                com.R.codecore.feature.settings.presentation.component.SkillEditScreen(
                    viewModel = editViewModel,
                    skillId = skillId,
                    onNavigateBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.popBackStack()
                    }
                )
            }
            composable("proxy_config") {
                val proxyViewModel: com.R.codecore.feature.proxy.presentation.ProxyViewModel = hiltViewModel()
                com.R.codecore.feature.proxy.presentation.component.ProxyConfigScreen(
                    viewModel = proxyViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToNodes = { navController.navigate("proxy_nodes") }
                )
            }
            composable("proxy_nodes") {
                val proxyViewModel: com.R.codecore.feature.proxy.presentation.ProxyViewModel = hiltViewModel()
                com.R.codecore.feature.proxy.presentation.component.ProxyNodesScreen(
                    viewModel = proxyViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("terminal") {
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate("terminal_settings") }
                )
            }
            composable("terminal_settings") {
                val terminalSettingsVM: TerminalSettingsViewModel = hiltViewModel()
                TerminalSettingsScreen(
                    viewModel = terminalSettingsVM,
                    onNavigateBack = { navController.popBackStack() },
                    // RC62：TerminalSettings 里点「管理 SSH 主机配置」→ 跨路由栈切到 Settings 的 RemoteServers 分区。
                    // 顺序必须是「先发 openSection（写入 SettingsViewModel 单例，CONFLATED Channel 只保留最新）
                    // 再 pop 回 settings 路由」，因为 SettingsScreen 在 composable 首帧就会 consume pendingTick：
                    //   tick(1) > consumed(-1) → 读 lastRequestedSection=RemoteServers → section=RemoteServers。
                    onNavigateToSshHosts = {
                        settingsViewModel.openSection(
                            com.R.codecore.feature.settings.presentation.component.SettingsSection.RemoteServers
                        )
                        val popped = navController.popBackStack("settings", inclusive = false)
                        if (!popped) {
                            // 理论上不会发生，因为 terminal_settings 一定是从 settings 导航过来的；
                            // 兜底直接 go settings，路由创建时 LaunchedEffect(pendingTick) 也会消费。
                            navController.navigate("settings")
                        }
                    },
                    onNavigateToBundleManager = { navController.navigate("terminal_bundle_manager") }
                )
            }
            composable("terminal_bundle_manager") {
                val terminalSettingsVM: TerminalSettingsViewModel = hiltViewModel()
                TerminalBundleManagerScreen(
                    viewModel = terminalSettingsVM,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                val credentialViewModel: com.R.codecore.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    credentialViewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("browser") {
                com.R.codecore.feature.browser.presentation.ServiceBrowserScreen(
                    browserController = browserController,
                    loginPromptManager = browserLoginPromptManager,
                    takeoverManager = browserTakeoverManager,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // 数据疑似丢失 / 包名变更 → 启动级全局告警（D8b）：
        // 冷启动后用户打开 App 第一眼就能看到「历史数据异常」提示，可一键跳转备份与还原页恢复。
        DataSafetyStartupAlert(
            notifier = dataSafetyNotifier,
            onGoToBackup = {
                settingsViewModel.openSection(
                    com.R.codecore.feature.settings.presentation.component.SettingsSection.Backup
                )
                navController.navigate("settings")
            }
        )
    }
}

/**
 * 数据保全启动级全局告警弹窗（数据保全防线 D8b，解决「数据消失却不报错」）。
 *
 * 哨兵（DataSentinel）判定本包名下数据疑似为空（DATA_LOST）或包名被改动（PACKAGE_CHANGED）时，
 * 覆盖所有页面弹出，提示用户历史数据可能丢失，并提供「去备份与还原」入口。用户选择「我知道了」
 * 仅本次会话不再弹；数据恢复后下次启动判定自然回落为 UPGRADED/NORMAL，不再告警。
 */
@Composable
private fun DataSafetyStartupAlert(
    notifier: com.R.codecore.feature.backup.data.DataSafetyNotifier,
    onGoToBackup: () -> Unit
) {
    val verdict by notifier.verdict.collectAsStateWithLifecycle()
    if (!notifier.shouldShowStartupAlert) return
    val isPackageChanged = verdict == com.R.codecore.feature.backup.data.guard.SentinelVerdict.PACKAGE_CHANGED
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { notifier.dismissStartupAlert() },
        title = {
            androidx.compose.material3.Text(
                stringResource(
                    if (isPackageChanged) R.string.backup_package_changed_title
                    else R.string.backup_data_lost_title
                )
            )
        },
        text = {
            androidx.compose.material3.Text(
                stringResource(
                    if (isPackageChanged) R.string.backup_package_changed_desc
                    else R.string.backup_data_lost_desc
                )
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    notifier.dismissStartupAlert()
                    onGoToBackup()
                }
            ) {
                androidx.compose.material3.Text(stringResource(R.string.backup_data_safety_alert_go))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { notifier.dismissStartupAlert() }) {
                androidx.compose.material3.Text(stringResource(R.string.backup_data_safety_alert_dismiss))
            }
        }
    )
}
