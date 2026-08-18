package com.R.codecore.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.AppSectionHeader
import com.R.codecore.core.theme.AppSectionGroup
import com.R.codecore.core.theme.CyberColors
import com.R.codecore.core.theme.CyberCard
import com.R.codecore.core.theme.CyberSectionHeader
import com.R.codecore.core.theme.CyberMenuRow
import com.R.codecore.core.theme.CyberSearchBar
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.util.LogLevel
import com.R.codecore.R
import com.R.codecore.feature.agent.domain.mcp.McpServerConfig
import com.R.codecore.feature.agent.domain.mcp.McpServerStatus
import com.R.codecore.feature.backup.presentation.BackupSection
import com.R.codecore.feature.settings.data.repository.AppThemeMode
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.settings.domain.model.ModelMetadata
import com.R.codecore.feature.settings.presentation.SecuritySettingsViewModel
import com.R.codecore.feature.settings.presentation.SettingsViewModel
import com.R.codecore.feature.settings.presentation.components.RemoteAuditLogsScreen
import com.R.codecore.feature.settings.presentation.components.SecuritySettingsScreen
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Info
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Search
import compose.icons.feathericons.Server
import compose.icons.feathericons.Terminal

/** 设置页内部二级菜单分区。Menu 为首页菜单，其余为各自的二级页。 */
enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    Providers(R.string.settings_providers),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    Mcp(R.string.settings_mcp),
    Container(R.string.settings_container),
    Logs(R.string.settings_logs),
    Permissions(R.string.settings_permissions),
    RemoteServers(R.string.settings_remote_servers),
    Backup(R.string.settings_backup),
    Security(R.string.settings_security),
    RemoteAuditLogs(R.string.settings_remote_audit_logs),
    About(R.string.settings_about)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToTerminalSettings: () -> Unit = {},
    onNavigateToSshHosts: () -> Unit = {},
    onStopAllAndCloseTerminal: () -> Unit = {}
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val activeProvider by viewModel.activeProvider.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logViewerState by viewModel.logViewerState.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpStatuses by viewModel.mcpStatuses.collectAsStateWithLifecycle()
    val mcpReloading by viewModel.mcpReloading.collectAsStateWithLifecycle()
    val globalRules by viewModel.globalRules.collectAsStateWithLifecycle()
    val projectRules by viewModel.projectRules.collectAsStateWithLifecycle()
    val currentProjectName by viewModel.currentProjectName.collectAsStateWithLifecycle()
    val keepaliveEnabled by viewModel.keepaliveEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val visionProviderId by viewModel.visionProviderId.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val compactionProviderId by viewModel.compactionProviderId.collectAsStateWithLifecycle()
    val compactionModel by viewModel.compactionModel.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val containerProfiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val remoteConnections by viewModel.remoteConnections.collectAsStateWithLifecycle()
    val storageShareEnabled by viewModel.storageShareEnabled.collectAsStateWithLifecycle()

    var section by remember { mutableStateOf(SettingsSection.Menu) }
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showAddProviderSheet by remember { mutableStateOf(false) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerConfig?>(null) }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.R.codecore.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }

    // RC62：跨屏跳转（terminal_settings → settings → RemoteServers）：接收来自 SettingsViewModel
    //   的 openSection 请求，切到 SettingsScreen 内部的 section。
    // 注意：这段必须写在 `var section` remember 之后，否则会引用 section 报 Unresolved。
    val pendingTick by viewModel.pendingOpenSectionTick.collectAsStateWithLifecycle()
    val consumedTick by viewModel.lastConsumedSectionTick.collectAsStateWithLifecycle()
    val lastRequestedSection by viewModel.lastRequestedSection.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTick, consumedTick) {
        if (pendingTick > consumedTick) {
            val sec = lastRequestedSection
            if (sec != null && section != sec) {
                section = sec
            }
            viewModel.markPendingSectionConsumed(pendingTick)
        }
    }

    // 处于二级页时，系统返回键先回到上一层；首页时交还给上层导航。
    BackHandler(enabled = section != SettingsSection.Menu) {
        when (section) {
            SettingsSection.ProviderEditor -> section = SettingsSection.Providers
            SettingsSection.Logs -> section = logReturnSection
            else -> section = SettingsSection.Menu
        }
    }

    // 提供商编辑为独立全屏页，直接渲染（不嵌套 Scaffold）
    if (section == SettingsSection.ProviderEditor) {
        ProviderEditorScreen(
            viewModel = viewModel,
            initialProvider = editingProvider,
            onNavigateBack = { section = SettingsSection.Providers },
            onSave = { provider ->
                viewModel.saveProvider(provider)
            },
            onDelete = { id ->
                viewModel.deleteProvider(id)
                section = SettingsSection.Providers
            }
        )
        return
    }

    if (section == SettingsSection.RemoteServers) {
        com.R.codecore.feature.workspace.presentation.remote.RemoteServerScreen(
            onNavigateBack = { section = SettingsSection.Menu }
        )
        return
    }

    Scaffold(
        containerColor = Color.White,
        contentColor = Color(0xFF101828),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = stringResource(section.titleRes),
                onNavigateBack = {
                    if (section == SettingsSection.Menu) {
                        onNavigateBack()
                    } else if (section == SettingsSection.Logs) {
                        section = logReturnSection
                    } else {
                        section = SettingsSection.Menu
                    }
                },
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back)
            ) {
                when (section) {
                    SettingsSection.Providers -> IconButton(
                        onClick = { showAddProviderSheet = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider), modifier = Modifier.size(20.dp))
                    }
                    SettingsSection.Mcp -> {
                        IconButton(onClick = { viewModel.reloadMcp() }, modifier = Modifier.size(40.dp)) {
                            if (mcpReloading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_reconnect), modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = { editingMcp = null; showMcpDialog = true }, modifier = Modifier.size(40.dp)) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_mcp_server), modifier = Modifier.size(20.dp))
                        }
                    }
                    SettingsSection.Container -> IconButton(onClick = { showContainerAddSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image), modifier = Modifier.size(20.dp))
                    }
                    SettingsSection.Logs -> {
                        IconButton(onClick = { viewModel.refreshLogs() }, modifier = Modifier.size(40.dp)) {
                            Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs), modifier = Modifier.size(20.dp))
                        }
                    }
                    else -> {}
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(padding)
        ) {
            when (section) {
                SettingsSection.Menu -> SettingsMenu(
                    providerCount = providers.size,
                    activeProviderName = activeProvider?.name,
                    activeContainerProfileName = containerProfiles.firstOrNull { it.id == activeProfileId }?.name,
                    visionProviderName = providers.firstOrNull { it.id == visionProviderId }?.name,
                    visionModel = visionModel,
                    compactionProviderName = providers.firstOrNull { it.id == compactionProviderId }?.name,
                    compactionModel = compactionModel,
                    mcpCount = mcpServers.size,
                    mcpConnected = mcpStatuses.count { it.state == McpServerStatus.State.CONNECTED },
                    logLevel = logLevel,
                    permissionRuleCount = projectRules.size + globalRules.size,
                    themeMode = themeMode,
                    onOpenThemeSheet = { showThemeSheet = true },
                    keepaliveEnabled = keepaliveEnabled,
                    onToggleKeepalive = { viewModel.setKeepaliveEnabled(it) },
                    currentLanguageDisplayName = currentLanguageDisplayName,
                    onOpenLanguageSheet = { showLanguageSheet = true },
                    onOpen = {
                        if (it == SettingsSection.Logs) {
                            logReturnSection = SettingsSection.Menu
                            viewModel.refreshLogs(filterServerName = null)
                        }
                        section = it
                    },
                    onNavigateToTerminalSettings = onNavigateToTerminalSettings
                )
                SettingsSection.Providers -> ProvidersSection(
                    providers = providers,
                    onEdit = {
                        editingProvider = it
                        section = SettingsSection.ProviderEditor
                    }
                )
                SettingsSection.DefaultModels -> DefaultModelsSection(
                    providers = providers,
                    visionProviderId = visionProviderId,
                    visionModel = visionModel,
                    compactionProviderId = compactionProviderId,
                    compactionModel = compactionModel,
                    modelMetadata = modelMetadata,
                    onLoadMetadata = { viewModel.loadAllModelMetadata() },
                    onSelectVisionModel = { pid, m -> viewModel.setVisionModel(pid, m) },
                    onClearVisionModel = { viewModel.clearVisionModel() },
                    onSelectCompactionModel = { pid, m -> viewModel.setCompactionModel(pid, m) },
                    onClearCompactionModel = { viewModel.clearCompactionModel() }
                )
                SettingsSection.Mcp -> McpSection(
                    servers = mcpServers,
                    statuses = mcpStatuses,
                    reloading = mcpReloading,
                    onReload = { viewModel.reloadMcp() },
                    onToggle = { name, enabled -> viewModel.setMcpServerEnabled(name, enabled) },
                    onEdit = {
                        editingMcp = it
                        showMcpDialog = true
                    },
                    onDelete = { viewModel.deleteMcpServer(it) }
                )
                SettingsSection.Container -> ContainerSection(
                    profiles = containerProfiles,
                    activeProfileId = activeProfileId,
                    showAddSheetExternal = showContainerAddSheet,
                    onDismissAddSheet = { showContainerAddSheet = false },
                    onSelect = { viewModel.setActiveContainerProfile(it) },
                    onSaveCustom = { viewModel.saveCustomContainerProfile(it) },
                    onEditCustom = { viewModel.editCustomContainerProfile(it) },
                    onDeleteCustom = { viewModel.deleteCustomContainerProfile(it) },
                    onSwitchConfirmed = onStopAllAndCloseTerminal,
                    onResetBuiltin = { viewModel.resetBuiltinContainer(it) },
                    remoteConnections = remoteConnections,
                    storageShareEnabled = storageShareEnabled,
                    onStorageShareChange = { viewModel.setStorageShareEnabled(it) }
                )
                SettingsSection.Logs -> LogsSection(
                    currentLogLevel = logLevel,
                    onSelectLogLevel = { viewModel.setLogLevel(it) },
                    logViewerState = logViewerState,
                    onSelectFile = { viewModel.selectLogFile(it) },
                    onRefresh = { viewModel.refreshLogs() },
                    onToggleFilterPanel = { viewModel.toggleFilterPanel() },
                    onCloseFilterPanel = { viewModel.closeFilterPanel() },
                    onSetSelectedDates = { viewModel.setSelectedDates(it) },
                    onSetDateRangeMode = { viewModel.setDateRangeMode(it) },
                    onSetDateRange = { start, end -> viewModel.setDateRange(start, end) },
                    onToggleLevel = { viewModel.toggleLevel(it) },
                    onToggleTag = { viewModel.toggleTag(it) },
                    onResetFilters = { viewModel.resetFilters() },
                    onSearchQuery = { viewModel.setSearchQuery(it) },
                    onToggleLiveTail = { viewModel.toggleLiveTail() },
                    onDismissNewLogs = { viewModel.dismissNewLogs() }
                )
                SettingsSection.Permissions -> PermissionsSection(
                    projectName = currentProjectName,
                    projectRules = projectRules,
                    globalRules = globalRules,
                    onDeleteProject = { viewModel.deleteProjectRule(it) },
                    onPromote = { viewModel.promoteRuleToGlobal(it) },
                    onDeleteGlobal = { viewModel.deleteGlobalRule(it) }
                )
                SettingsSection.Backup -> {
                    val backupViewModel: com.R.codecore.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
                }
                SettingsSection.Security -> {
                    val securityViewModel: SecuritySettingsViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    SecuritySettingsScreen(viewModel = securityViewModel)
                }
                SettingsSection.RemoteAuditLogs -> {
                    RemoteAuditLogsScreen(auditLogRepo = viewModel.auditLogRepository)
                }
                SettingsSection.ProviderEditor -> {} // 已在上方 early return 处理
                SettingsSection.RemoteServers -> {} // 已在上方 early return 处理
                SettingsSection.About -> AboutSection()
            }
        }
    }

    if (showMcpDialog) {
        McpServerEditDialog(
            initial = editingMcp,
            tools = viewModel.getMcpServerTools(editingMcp?.name),
            onRefreshTools = { viewModel.reloadMcp() },
            onOpenLogs = editingMcp?.let { existing ->
                {
                    showMcpDialog = false
                    logReturnSection = SettingsSection.Mcp
                    viewModel.refreshLogs(filterServerName = existing.name)
                    section = SettingsSection.Logs
                }
            },
            onDismiss = { showMcpDialog = false },
            onSave = { config ->
                viewModel.upsertMcpServer(editingMcp?.name, config)
                showMcpDialog = false
            },
            onDelete = editingMcp?.let { existing ->
                {
                    viewModel.deleteMcpServer(existing.name)
                    showMcpDialog = false
                }
            }
        )
    }

    if (showThemeSheet) {
        ThemeSelectionSheet(
            selected = themeMode,
            onSelected = { viewModel.setThemeMode(it) },
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showLanguageSheet) {
        LanguageSelectionSheet(
            currentTag = languageTag,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showAddProviderSheet) {
        AddProviderSheet(
            viewModel = viewModel,
            onDismiss = { showAddProviderSheet = false },
            onSave = { provider ->
                viewModel.saveProvider(provider)
                showAddProviderSheet = false
            }
        )
    }
}

internal data class MenuItem(
    val section: SettingsSection?,
    val group: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val keywords: List<String>,
    val action: () -> Unit,
    val trailing: @Composable (() -> Unit)? = null
)



/** 设置首页：每个分区一个可点击的二级菜单入口。 */
@Composable
internal fun SettingsMenu(
    providerCount: Int,
    activeProviderName: String?,
    activeContainerProfileName: String?,
    visionProviderName: String?,
    visionModel: String,
    compactionProviderName: String?,
    compactionModel: String,
    mcpCount: Int,
    mcpConnected: Int,
    logLevel: LogLevel,
    permissionRuleCount: Int,
    themeMode: AppThemeMode,
    onOpenThemeSheet: () -> Unit,
    keepaliveEnabled: Boolean,
    onToggleKeepalive: (Boolean) -> Unit,
    currentLanguageDisplayName: String,
    onOpenLanguageSheet: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    onNavigateToTerminalSettings: () -> Unit = {},
) {
    var searchQuery by remember { mutableStateOf("") }
    val themeLabel = stringResource(themeMode.labelRes)

    // 多语言分组名（全部走 strings.xml i18n）
    val groupAI = stringResource(R.string.settings_category_ai_agent)
    val groupEnv = stringResource(R.string.settings_category_environment)
    val groupData = stringResource(R.string.settings_category_data_security)
    val groupSystem = stringResource(R.string.settings_category_system_app)
    // 固定分组顺序（必须用上面 i18n 后的 group key，与 filteredGroups 对齐）
    val groupOrder = listOf(groupAI, groupEnv, groupData, groupSystem)
    val logLevelLabel = stringResource(
        when (logLevel) {
            LogLevel.VERBOSE -> R.string.log_level_verbose
            LogLevel.DEBUG -> R.string.log_level_debug
            LogLevel.INFO -> R.string.log_level_info
            LogLevel.WARN -> R.string.log_level_warn
            LogLevel.ERROR -> R.string.log_level_error
            LogLevel.NONE -> R.string.log_level_none
        }
    )

    // section=null 的菜单项，title 用独立的 i18n 资源
    val menuItems: List<MenuItem> = listOf(
        MenuItem(
            section = SettingsSection.Providers,
            group = groupAI,
            title = stringResource(SettingsSection.Providers.titleRes),
            subtitle = if (providerCount == 0) {
                stringResource(R.string.settings_providers_empty)
            } else {
                stringResource(R.string.settings_providers_count, providerCount) +
                    (activeProviderName?.let { stringResource(R.string.settings_providers_active, it) } ?: "")
            },
            icon = FeatherIcons.Cloud,
            keywords = listOf("provider", "模型", "api", "key", "providers", "提供商"),
            action = { onOpen(SettingsSection.Providers) }
        ),
        MenuItem(
            section = SettingsSection.DefaultModels,
            group = groupAI,
            title = stringResource(SettingsSection.DefaultModels.titleRes),
            subtitle = run {
                val parts = mutableListOf<String>()
                if (!visionProviderName.isNullOrBlank() && visionModel.isNotBlank()) {
                    parts.add(stringResource(R.string.settings_default_models_vision_dedicated, visionProviderName, visionModel))
                }
                if (!compactionProviderName.isNullOrBlank() && compactionModel.isNotBlank()) {
                    parts.add(stringResource(R.string.settings_default_models_compaction_dedicated, compactionProviderName, compactionModel))
                }
                if (parts.isEmpty()) stringResource(R.string.settings_default_models_empty) else parts.joinToString("\n")
            },
            icon = FeatherIcons.Cpu,
            keywords = listOf("model", "default", "默认", "识图", "vision", "压缩", "compaction", "模型", "多模态"),
            action = { onOpen(SettingsSection.DefaultModels) }
        ),
        MenuItem(
            section = SettingsSection.Mcp,
            group = groupAI,
            title = stringResource(SettingsSection.Mcp.titleRes),
            subtitle = if (mcpCount == 0)
                stringResource(R.string.settings_mcp_empty)
            else
                stringResource(R.string.settings_mcp_count_connected, mcpCount, mcpConnected),
            icon = FeatherIcons.Box,
            keywords = listOf("mcp", "server", "工具", "function", "协议", "服务器"),
            action = { onOpen(SettingsSection.Mcp) }
        ),
        MenuItem(
            section = SettingsSection.Permissions,
            group = groupAI,
            title = stringResource(SettingsSection.Permissions.titleRes),
            subtitle = if (permissionRuleCount == 0)
                stringResource(R.string.settings_permissions_empty)
            else
                stringResource(R.string.settings_permissions_count, permissionRuleCount),
            icon = FeatherIcons.Lock,
            keywords = listOf("perm", "授权", "规则", "permission", "allow", "工具", "tool"),
            action = { onOpen(SettingsSection.Permissions) }
        ),
        MenuItem(
            section = null,
            group = groupEnv,
            title = stringResource(R.string.settings_terminal),
            subtitle = stringResource(R.string.settings_terminal_subtitle),
            icon = FeatherIcons.Terminal,
            keywords = listOf("terminal", "终端", "ssh", "shell", "bash", "命令"),
            action = onNavigateToTerminalSettings
        ),
        MenuItem(
            section = SettingsSection.Container,
            group = groupEnv,
            title = stringResource(SettingsSection.Container.titleRes),
            subtitle = stringResource(
                R.string.settings_container_current,
                activeContainerProfileName ?: stringResource(R.string.settings_container_builtin_alpine)
            ),
            icon = FeatherIcons.HardDrive,
            keywords = listOf("container", "docker", "镜像", "alpine", "容器", "proot", "环境"),
            action = { onOpen(SettingsSection.Container) }
        ),
        MenuItem(
            section = SettingsSection.RemoteServers,
            group = groupEnv,
            title = stringResource(SettingsSection.RemoteServers.titleRes),
            subtitle = stringResource(R.string.settings_remote_subtitle),
            icon = FeatherIcons.Server,
            keywords = listOf("remote", "ssh", "sftp", "服务器", "工作区", "同步", "远程"),
            action = { onOpen(SettingsSection.RemoteServers) }
        ),
        MenuItem(
            section = SettingsSection.Backup,
            group = groupData,
            title = stringResource(SettingsSection.Backup.titleRes),
            subtitle = stringResource(R.string.settings_backup_subtitle),
            icon = FeatherIcons.Save,
            keywords = listOf("backup", "备份", "还原", "export", "导出", "导入", "加密"),
            action = { onOpen(SettingsSection.Backup) }
        ),
        MenuItem(
            section = SettingsSection.Security,
            group = groupData,
            title = stringResource(SettingsSection.Security.titleRes),
            subtitle = stringResource(R.string.settings_security_subtitle),
            icon = FeatherIcons.Lock,
            keywords = listOf("security", "加密", "生物识别", "凭据", "password", "安全", "pin"),
            action = { onOpen(SettingsSection.Security) }
        ),
        MenuItem(
            section = SettingsSection.RemoteAuditLogs,
            group = groupData,
            title = stringResource(SettingsSection.RemoteAuditLogs.titleRes),
            subtitle = stringResource(R.string.settings_remote_audit_subtitle),
            icon = FeatherIcons.FileText,
            keywords = listOf("audit", "审计", "log", "连接", "事件", "ssh", "备份"),
            action = { onOpen(SettingsSection.RemoteAuditLogs) }
        ),
        MenuItem(
            section = SettingsSection.Logs,
            group = groupData,
            title = stringResource(SettingsSection.Logs.titleRes),
            subtitle = stringResource(R.string.settings_log_subtitle, logLevelLabel),
            icon = FeatherIcons.FileText,
            keywords = listOf("log", "日志", "debug", "trace", "错误", "bug", "filter"),
            action = { onOpen(SettingsSection.Logs) }
        ),
        MenuItem(
            section = null,
            group = groupSystem,
            title = stringResource(R.string.settings_theme_title),
            subtitle = stringResource(R.string.settings_log_current, themeLabel),
            icon = FeatherIcons.Moon,
            keywords = listOf("theme", "appearance", "外观", "深色", "浅色", "模式", "主题"),
            action = onOpenThemeSheet
        ),
        MenuItem(
            section = null,
            group = groupSystem,
            title = stringResource(R.string.settings_language),
            subtitle = stringResource(R.string.settings_log_current, currentLanguageDisplayName),
            icon = FeatherIcons.Globe,
            keywords = listOf("language", "语言", "i18n", "多语言", "locale", "localeConfig"),
            action = onOpenLanguageSheet
        ),
        MenuItem(
            section = null,
            group = groupSystem,
            title = stringResource(R.string.settings_keepalive_title),
            subtitle = stringResource(R.string.settings_keepalive_subtitle),
            icon = FeatherIcons.RefreshCw,
            keywords = listOf("keepalive", "保活", "后台", "foreground", "通知", "杀死", "进程"),
            action = { onToggleKeepalive(!keepaliveEnabled) },
            trailing = {
                Switch(
                    checked = keepaliveEnabled,
                    onCheckedChange = onToggleKeepalive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFFFFFFF),
                        checkedTrackColor = Color(0xFF0984E3).copy(alpha = 0.55f)
                    )
                )
            }
        ),
        MenuItem(
            section = SettingsSection.About,
            group = groupSystem,
            title = stringResource(SettingsSection.About.titleRes),
            subtitle = stringResource(R.string.settings_about_subtitle),
            icon = FeatherIcons.Info,
            keywords = listOf("about", "关于", "version", "release", "更新", "许可证", "license", "作者"),
            action = { onOpen(SettingsSection.About) }
        )
    )

    val filteredGroups = remember(searchQuery, menuItems) {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            menuItems
        } else {
            // 使用 SearchUtils 打分排序
            menuItems.map { item ->
                item to SearchUtils.score(item, query)
            }
                .filter { (_, score) -> score > 0.0 }
                .sortedByDescending { (_, score) -> score }
                .map { (item, _) -> item }
        }
        filtered.groupBy { it.group }.filter { it.value.isNotEmpty() }
    }

    val searchResultCount = remember(searchQuery, filteredGroups) {
        if (searchQuery.isBlank()) null else filteredGroups.values.sumOf { it.size }
    }

    val hasSearchQuery = searchQuery.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // 搜索栏固定在顶部（不随滚动消失）
        CyberSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.settings_search_hint),
            resultCount = searchResultCount,
            onClear = { searchQuery = "" }
        )

        Spacer(Modifier.height(Spacing.sm))

        // 搜索结果区域（可滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (hasSearchQuery && searchResultCount == 0) {
                // 空结果提示
                EmptySearchResult(query = searchQuery)
            } else {
                for (groupName in groupOrder) {
                    val items = filteredGroups[groupName] ?: continue
                    CyberSectionHeader(text = groupName)
                    CyberCard {
                        Column {
                            items.forEachIndexed { index, item ->
                                CyberMenuRow(
                                    icon = item.icon,
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    onClick = item.action,
                                    showDivider = index < items.size - 1,
                                    highlightQuery = searchQuery,
                                    trailing = item.trailing ?: {
                                        Icon(
                                            imageVector = FeatherIcons.ChevronRight,
                                            contentDescription = null,
                                            tint = CyberColors.CyanDim
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun EmptySearchResult(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = FeatherIcons.Search,
            contentDescription = null,
            tint = Color(0xFFD0D5DD),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_search_empty_title),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFF475467)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_search_empty_subtitle, query),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF98A2B3)
        )
    }
}

@Composable
internal fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/** 分组内菜单行：无边框、无 Card，带可选底部分割线。 */
@Composable
internal fun GroupMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/** 分组内开关行：无边框、无 Card，带 Switch。 */
@Composable
internal fun GroupSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

/** 二级菜单入口行：图标 + 标题 + 摘要 + 右箭头。 */
@Composable
internal fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
