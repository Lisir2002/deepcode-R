package com.deep.rcode

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
import com.deep.rcode.core.theme.pageEnterTransition
import com.deep.rcode.core.theme.pageExitTransition
import com.deep.rcode.core.theme.pagePopEnterTransition
import com.deep.rcode.core.theme.pagePopExitTransition
import com.deep.rcode.core.theme.terminalEnterTransition
import com.deep.rcode.core.theme.terminalExitTransition
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
import com.deep.rcode.core.theme.AIEditorTheme
import com.deep.rcode.feature.agent.presentation.AIAgentViewModel
import com.deep.rcode.feature.agent.presentation.component.AIChatPanel
import com.deep.rcode.feature.agent.presentation.component.ChatDrawerContent
import com.deep.rcode.feature.git.presentation.GitViewModel
import com.deep.rcode.feature.git.presentation.component.GitScreen
import com.deep.rcode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.deep.rcode.feature.settings.data.repository.AppThemeMode
import com.deep.rcode.feature.settings.data.repository.ThemeSettingsRepository
import com.deep.rcode.feature.settings.presentation.SettingsViewModel
import com.deep.rcode.feature.settings.presentation.component.SettingsScreen
import com.deep.rcode.feature.terminal.domain.TerminalKeepaliveService
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import com.deep.rcode.feature.terminal.presentation.TerminalViewModel
import com.deep.rcode.feature.terminal.presentation.component.TerminalScreen
import com.deep.rcode.feature.terminal.presentation.component.TerminalSettingsScreen
import com.deep.rcode.feature.workspace.presentation.WorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 用于冷启动时在前台恢复常驻保活通知（App.onCreate 的启动可能被后台 FGS 限制挡掉）。 */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    /** 语言偏好：attachBaseContext 时同步读取以应用 locale，变化时 recreate。 */
    @Inject
    lateinit var languageSettings: com.deep.rcode.feature.settings.data.repository.LanguageSettingsRepository

    /** App 回到前台时，远程模式下若 SSH 断了触发重连。 */
    @Inject
    lateinit var remoteSshConnection: com.deep.rcode.feature.agent.domain.container.RemoteSshConnection

    @Inject
    lateinit var executionModeHolder: com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder

    /** 三端（UI/AI Bash/交互终端）git 缺凭据统一弹窗桥：在 AIEditorApp 启动后监听 helper 的文件 IPC 请求。 */
    @Inject
    lateinit var credentialRequestBridge: com.deep.rcode.feature.credentials.data.CredentialRequestBridge

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (!granted) {
            Toast.makeText(
                this,
                getString(R.string.main_no_storage_permission),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        // 在 Activity 创建前同步应用用户选择的语言，确保冷启动也生效。
        // Hilt 尚未注入，直接从 SharedPreferences 同步读取。
        val tag = newBase.getSharedPreferences("language_prefs_sync", android.content.Context.MODE_PRIVATE)
            .getString("language_tag", null)
        val context = if (tag.isNullOrBlank()) {
            newBase
        } else {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    @Suppress("DEPRECATION") // 全局更新 application resources locale，createConfigurationContext 无法替代
    override fun onCreate(savedInstanceState: Bundle?) {
        // 绘制到系统状态栏/导航栏之下，让应用背景与系统栏融为一体（消除割裂的色块）。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 监听语言偏好变化，更新 Application/Activity locale 后重建。
        lifecycleScope.launch {
            languageSettings.languageFlow.drop(1).distinctUntilChanged().collect { tag ->
                // 同步更新 Application context 的 Configuration，确保非 Activity 来源的
                // Resources（如 ViewModel 中 context.getString）也能拿到正确 locale。
                val app = applicationContext
                if (tag.isNullOrBlank()) {
                    // 跟随系统：重置为系统默认 Configuration
                    val sysConfig = android.content.res.Configuration(resources.configuration)
                    sysConfig.setLocale(java.util.Locale.getDefault())
                    app.resources.updateConfiguration(sysConfig, app.resources.displayMetrics)
                } else {
                    val config = android.content.res.Configuration(app.resources.configuration)
                    config.setLocale(java.util.Locale.forLanguageTag(tag))
                    app.resources.updateConfiguration(config, app.resources.displayMetrics)
                }
                recreate()
            }
        }
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                    // 全局凭据弹窗：覆盖所有页面，命令行 git 缺凭据在任意页面都能弹。
                    com.deep.rcode.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                        bridge = credentialRequestBridge
                    )
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
        if (executionModeHolder.currentMode() == com.deep.rcode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
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
fun AppNavigation() {
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
                        sessionExportLauncher.launch("rdeepcode-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
                    },
                    onNavigateToSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
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
                    onNavigateToGit = { navController.navigate("git") }
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
                    onNavigateToSshHosts = {
                        // SSH 主机管理：目前还没独立页，先跳到设置页的 RemoteServers 入口，
                        // 等 SSH 页补上后改成直接路由。
                        if (!navController.popBackStack()) {
                            navController.navigate("settings")
                        }
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() }
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
                    onNavigateToSshHosts = {
                        // 同 RemoteServers 占位：先回 Settings 的对应分区。
                        navController.popBackStack("settings", inclusive = false)
                    }
                )
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                val credentialViewModel: com.deep.rcode.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    credentialViewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
