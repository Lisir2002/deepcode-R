package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.LocalAppDarkMode
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.model.ChatSession
import com.R.codecore.feature.agent.presentation.AgentUIState
import com.R.codecore.feature.settings.data.repository.AppThemeMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.feature.workspace.domain.model.Workspace
import com.R.codecore.feature.workspace.presentation.WorkspaceViewModel

/**
 * 侧边栏内容：内部设有 tab 菜单（对话列表 / 工作目录 / 更多配置），
 * 底部保留主题切换与设置两个图标按钮（贴右排列）。
 * 由 MainActivity 的 ModalNavigationDrawer 承载，支持左上角按钮点击或右滑打开。
 *
 * 图标规范：彩色圆角图标块 + 白色图标（Material You 风格），日夜间各一套背景色，
 * 随 LocalAppDarkMode 自动切换。
 */
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onCreate: () -> Unit,
    onDelete: (ChatSession) -> Unit,
    onRename: (ChatSession, String) -> Unit,
    onExport: (ChatSession) -> Unit,
    onNavigateToSettings: () -> Unit,
    currentThemeMode: AppThemeMode,
    onCycleTheme: () -> Unit,
    workspaceViewModel: WorkspaceViewModel? = null,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchWorkspaceConfirmed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = Spacing.md, vertical = Spacing.lg)
    ) {
        // 顶部 tab 菜单：对话列表 / 工作目录 / 更多配置
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            DrawerTab(
                text = stringResource(R.string.chat_drawer_tab_chats),
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 }
            )
            DrawerTab(
                text = stringResource(R.string.chat_drawer_tab_workspace),
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 }
            )
            DrawerTab(
                text = stringResource(R.string.chat_drawer_tab_more),
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 }
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        when (selectedTab) {
            0 -> ChatSessionListPanel(
                sessions = sessions,
                currentSessionId = currentSessionId,
                agentStates = agentStates,
                onSelect = onSelect,
                onCreate = onCreate,
                onLongPress = { menuSession = it },
                modifier = Modifier.weight(1f)
            )
            1 -> if (workspaceViewModel != null) {
                WorkspaceDirPanel(
                    viewModel = workspaceViewModel,
                    hasRunningSessions = hasRunningSessions,
                    onSwitchConfirmed = onSwitchWorkspaceConfirmed,
                    modifier = Modifier.weight(1f)
                )
            } else {
                MoreConfigPlaceholder(modifier = Modifier.weight(1f))
            }
            else -> MoreConfigPlaceholder(modifier = Modifier.weight(1f))
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = Spacing.sm)
        )
        // 底部导航：主题切换 / 设置 两个图标按钮贴右排列（能力中心已移入设置页，浏览器入口保留在聊天顶栏右侧）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Spacing.md),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerBottomIconButton(
                icon = when (currentThemeMode) {
                    AppThemeMode.DARK -> Icons.Rounded.DarkMode
                    AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                    AppThemeMode.AUTO -> Icons.Rounded.BrightnessAuto
                },
                contentDescription = stringResource(
                    when (currentThemeMode) {
                        AppThemeMode.DARK -> R.string.theme_dark
                        AppThemeMode.LIGHT -> R.string.theme_light
                        AppThemeMode.AUTO -> R.string.theme_auto
                    }
                ),
                iconBgLight = Color(0xFFF59E0B),
                iconBgDark = Color(0xFF92400E),
                onClick = onCycleTheme
            )
            DrawerBottomIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.chat_settings),
                iconBgLight = Color(0xFF0EA5E9),
                iconBgDark = Color(0xFF0369A1),
                onClick = onNavigateToSettings
            )
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            onDismiss = { menuSession = null }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_session_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * 侧边栏导航图标块：彩色圆角背景 + 白色图标，日夜间背景色随主题切换。
 */
@Composable
private fun DrawerNavIcon(
    icon: ImageVector,
    iconBgLight: Color,
    iconBgDark: Color
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (LocalAppDarkMode.current) iconBgDark else iconBgLight),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 侧边栏底部导航图标按钮：点击整块触发，内部复用 [DrawerNavIcon] 的彩色圆角图标块。
 * 图标之间按 [Arrangement.End] 贴右排列，按钮本身不扩展占宽。
 */
@Composable
private fun DrawerBottomIconButton(
    icon: ImageVector,
    contentDescription: String,
    iconBgLight: Color,
    iconBgDark: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        DrawerNavIcon(
            icon = icon,
            iconBgLight = iconBgLight,
            iconBgDark = iconBgDark
        )
    }
}

/**
 * 会话行长按弹出的功能菜单：重命名 / 删除。底部 sheet 样式参照 git 分支的 RefActionSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Rounded.Edit,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                iconBgLight = Color(0xFF4C8DFF),
                iconBgDark = Color(0xFF2B4E9E),
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            SheetActionRow(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                iconBgLight = Color(0xFF22C55E),
                iconBgDark = Color(0xFF14693A),
                onClick = {
                    onDismiss()
                    onExport()
                }
            )
            SheetActionRow(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                iconBgLight = Color(0xFFEF4444),
                iconBgDark = Color(0xFFB91C1C),
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    iconBgLight: Color,
    iconBgDark: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (LocalAppDarkMode.current) iconBgDark else iconBgLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** 侧边栏顶部 tab 项：文字标签，随 PrimaryTabRow 主题样式。 */
@Composable
private fun DrawerTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/**
 * 侧边栏「对话列表」tab：顶部「新建会话」，中部历史记录列表。
 * 每次进入该 tab 会自动滚动到当前会话。
 */
@Composable
private fun ChatSessionListPanel(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onCreate: () -> Unit,
    onLongPress: (ChatSession) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentSessionId, sessions) {
        if (sessions.isEmpty()) return@LaunchedEffect
        val index = sessions.indexOfFirst { it.id == currentSessionId }
        listState.scrollToItem(if (index >= 0) index else 0)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .clickable { onCreate() }
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerNavIcon(
                icon = Icons.Rounded.Add,
                iconBgLight = Color(0xFF4C8DFF),
                iconBgDark = Color(0xFF2B4E9E)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = stringResource(R.string.chat_new_session),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.chat_history),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )

        Box(modifier = Modifier.weight(1f)) {
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.chat_no_sessions_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val state = agentStates[session.id]
                        val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                        ChatSessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            isExecuting = isExecuting,
                            onClick = { onSelect(session) },
                            onLongClick = { onLongPress(session) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 侧边栏「工作目录」tab：当前工作区 + 工作区列表，支持切换 / 新建 / 删除。
 * 数据源复用 WorkspaceViewModel，与输入框底部工作区弹窗一致。
 */
@Composable
private fun WorkspaceDirPanel(
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean,
    onSwitchConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }
    var pendingSwitch by remember { mutableStateOf<Workspace?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerNavIcon(
                icon = Icons.Rounded.Folder,
                iconBgLight = Color(0xFFF59E0B),
                iconBgDark = Color(0xFF92400E)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_drawer_tab_workspace),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = current?.name ?: stringResource(R.string.workspace_select),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(
                    Icons.Rounded.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.workspace_new))
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = Spacing.sm)
        )

        Box(modifier = Modifier.weight(1f)) {
            if (workspaces.isEmpty()) {
                Text(
                    stringResource(R.string.workspace_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(workspaces, key = { it.name }) { ws ->
                        WorkspaceDirRow(
                            workspace = ws,
                            selected = ws.name == current?.name,
                            canDelete = workspaces.size > 1,
                            onClick = {
                                if (hasRunningSessions()) {
                                    pendingSwitch = ws
                                } else {
                                    onSwitchConfirmed()
                                    viewModel.selectWorkspace(ws.name)
                                }
                            },
                            onDelete = { pendingDelete = ws }
                        )
                    }
                }
            }
        }
    }

    // 切换确认：当前工作区存在运行中的 AI 会话/终端时需用户确认
    pendingSwitch?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(R.string.workspace_switch)) },
            text = { Text(stringResource(R.string.workspace_switch_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    viewModel.selectWorkspace(ws.name)
                    pendingSwitch = null
                }) { Text(stringResource(R.string.workspace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 删除确认
    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.workspace_delete)) },
            text = { Text(stringResource(R.string.workspace_delete_confirm, ws.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorkspace(ws.name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 新建工作区
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        val existingNames = workspaces.map { it.name }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.workspace_new_workspace)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workspace_new_workspace)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() !in existingNames,
                    onClick = {
                        viewModel.createWorkspace(name.trim())
                        showCreateDialog = false
                    }
                ) { Text(stringResource(R.string.workspace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 工作区列表行：文件夹图标 + 名称，选中高亮，可删除（非空列表时）。 */
@Composable
private fun WorkspaceDirRow(
    workspace: Workspace,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = workspace.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 侧边栏「更多配置」tab：占位空白板块，后续按需填充。 */
@Composable
private fun MoreConfigPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.lg)
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.chat_drawer_tab_more_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
