package com.deep.rcode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.util.LogLevel
import com.deep.rcode.R
import com.deep.rcode.feature.agent.domain.mcp.McpServerConfig
import com.deep.rcode.feature.agent.domain.mcp.McpServerStatus
import com.deep.rcode.feature.backup.presentation.BackupSection
import com.deep.rcode.feature.settings.data.repository.AppThemeMode
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.settings.domain.model.ModelMetadata
import com.deep.rcode.feature.settings.presentation.SettingsViewModel
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
import compose.icons.feathericons.Server

/** 设置页内部二级菜单分区。Menu 为首页菜单，其余为各自的二级页。 */
internal enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    Providers(R.string.settings_providers),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    Mcp(R.string.settings_mcp),
    Container(R.string.settings_container),
    Log(R.string.settings_log),
    LogViewer(R.string.settings_log_viewer),
    Permissions(R.string.settings_permissions),
    RemoteServers(R.string.settings_remote_servers),
    Backup(R.string.settings_backup),
    About(R.string.settings_about)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
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

    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.deep.rcode.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }

    var section by remember { mutableStateOf(SettingsSection.Menu) }
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerConfig?>(null) }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    // 处于二级页时，系统返回键先回到上一层；首页时交还给上层导航。
    BackHandler(enabled = section != SettingsSection.Menu) {
        when (section) {
            SettingsSection.ProviderEditor -> section = SettingsSection.Providers
            SettingsSection.LogViewer -> section = logReturnSection
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
        com.deep.rcode.feature.workspace.presentation.remote.RemoteServerScreen(
            onNavigateBack = { section = SettingsSection.Menu }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section.titleRes)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (section == SettingsSection.Menu) {
                            onNavigateBack()
                        } else if (section == SettingsSection.LogViewer) {
                            section = logReturnSection
                        } else {
                            section = SettingsSection.Menu
                        }
                    }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    when (section) {
                        SettingsSection.Providers -> IconButton(onClick = {
                            editingProvider = null
                            section = SettingsSection.ProviderEditor
                        }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider))
                        }
                        SettingsSection.Mcp -> {
                            IconButton(onClick = { viewModel.reloadMcp() }) {
                                if (mcpReloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_reconnect))
                                }
                            }
                            IconButton(onClick = {
                                editingMcp = null
                                showMcpDialog = true
                            }) {
                                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_mcp_server))
                            }
                        }
                        SettingsSection.Container -> IconButton(onClick = { showContainerAddSheet = true }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image))
                        }
                        SettingsSection.LogViewer -> {
                            IconButton(onClick = { viewModel.refreshLogs() }) {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs))
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                        if (it == SettingsSection.LogViewer) {
                            logReturnSection = SettingsSection.Menu
                            viewModel.refreshLogs(filterServerName = null)
                        }
                        section = it
                    }
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
                    onResetBuiltin = { viewModel.resetBuiltinContainer() },
                    remoteConnections = remoteConnections
                )
                SettingsSection.Log -> LogSection(
                    current = logLevel,
                    onSelect = { viewModel.setLogLevel(it) }
                )
                SettingsSection.LogViewer -> LogViewerSection(
                    state = logViewerState,
                    onSelectFile = { viewModel.selectLogFile(it) }
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
                    val backupViewModel: com.deep.rcode.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
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
                    section = SettingsSection.LogViewer
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
}

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
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── AI 配置 ──
        SectionHeader(text = stringResource(R.string.settings_category_ai))
        MenuRow(
            icon = FeatherIcons.Cloud,
            title = stringResource(SettingsSection.Providers.titleRes),
            subtitle = if (providerCount == 0) {
                stringResource(R.string.settings_providers_empty)
            } else {
                stringResource(R.string.settings_providers_count, providerCount) +
                    (activeProviderName?.let { stringResource(R.string.settings_providers_active, it) } ?: "")
            },
            onClick = { onOpen(SettingsSection.Providers) }
        )
        MenuRow(
            icon = FeatherIcons.Cpu,
            title = stringResource(SettingsSection.DefaultModels.titleRes),
            subtitle = run {
                val parts = mutableListOf<String>()
                if (!visionProviderName.isNullOrBlank() && visionModel.isNotBlank()) {
                    parts.add(stringResource(R.string.settings_vision_model) + ": " + (visionProviderName?.let { "$it · $visionModel" } ?: visionModel))
                }
                if (!compactionProviderName.isNullOrBlank() && compactionModel.isNotBlank()) {
                    parts.add(stringResource(R.string.settings_compaction_model) + ": " + (compactionProviderName?.let { "$it · $compactionModel" } ?: compactionModel))
                }
                if (parts.isEmpty()) stringResource(R.string.settings_default_models_subtitle) else parts.joinToString("\n")
            },
            onClick = { onOpen(SettingsSection.DefaultModels) }
        )
        MenuRow(
            icon = FeatherIcons.Box,
            title = stringResource(SettingsSection.Mcp.titleRes),
            subtitle = if (mcpCount == 0) stringResource(R.string.settings_mcp_empty) else stringResource(R.string.settings_mcp_count_connected, mcpCount, mcpConnected),
            onClick = { onOpen(SettingsSection.Mcp) }
        )

        // ── 运行环境 ──
        SectionHeader(text = stringResource(R.string.settings_category_environment))
        MenuRow(
            icon = FeatherIcons.HardDrive,
            title = stringResource(SettingsSection.Container.titleRes),
            subtitle = stringResource(R.string.settings_container_current, activeContainerProfileName ?: stringResource(R.string.settings_container_builtin_alpine)),
            onClick = { onOpen(SettingsSection.Container) }
        )
        MenuRow(
            icon = FeatherIcons.Server,
            title = stringResource(SettingsSection.RemoteServers.titleRes),
            subtitle = stringResource(R.string.settings_remote_subtitle),
            onClick = { onOpen(SettingsSection.RemoteServers) }
        )

        // ── 工具与权限 ──
        SectionHeader(text = stringResource(R.string.settings_category_tools))
        MenuRow(
            icon = FeatherIcons.Lock,
            title = stringResource(SettingsSection.Permissions.titleRes),
            subtitle = if (permissionRuleCount == 0) stringResource(R.string.settings_permissions_empty) else stringResource(R.string.settings_permissions_count, permissionRuleCount),
            onClick = { onOpen(SettingsSection.Permissions) }
        )
        MenuRow(
            icon = FeatherIcons.FileText,
            title = stringResource(SettingsSection.Log.titleRes),
            subtitle = stringResource(R.string.settings_log_current, logLevel.name),
            onClick = { onOpen(SettingsSection.Log) }
        )
        MenuRow(
            icon = FeatherIcons.FileText,
            title = stringResource(SettingsSection.LogViewer.titleRes),
            subtitle = stringResource(R.string.settings_log_viewer_subtitle),
            onClick = { onOpen(SettingsSection.LogViewer) }
        )

        // ── 外观与语言 ──
        SectionHeader(text = stringResource(R.string.settings_category_appearance))
        MenuRow(
            icon = FeatherIcons.Moon,
            title = stringResource(R.string.settings_theme_title),
            subtitle = stringResource(themeMode.labelRes),
            onClick = onOpenThemeSheet
        )
        MenuRow(
            icon = FeatherIcons.Globe,
            title = stringResource(R.string.settings_language),
            subtitle = currentLanguageDisplayName,
            onClick = onOpenLanguageSheet
        )

        // ── 其他 ──
        SectionHeader(text = stringResource(R.string.settings_category_other))
        SwitchRow(
            icon = FeatherIcons.RefreshCw,
            title = stringResource(R.string.settings_keepalive_title),
            subtitle = stringResource(R.string.settings_keepalive_subtitle),
            checked = keepaliveEnabled,
            onCheckedChange = onToggleKeepalive
        )
        MenuRow(
            icon = FeatherIcons.Save,
            title = stringResource(SettingsSection.Backup.titleRes),
            subtitle = stringResource(R.string.settings_backup_subtitle),
            onClick = { onOpen(SettingsSection.Backup) }
        )
        MenuRow(
            icon = FeatherIcons.Info,
            title = stringResource(SettingsSection.About.titleRes),
            subtitle = stringResource(R.string.settings_about_subtitle),
            onClick = { onOpen(SettingsSection.About) }
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

/** 分组小标题。 */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
    )
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
