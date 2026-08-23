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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.R.codecore.feature.workspace.domain.FileEntry
import com.R.codecore.feature.workspace.domain.model.Workspace
import com.R.codecore.feature.workspace.presentation.WorkspaceFileViewModel
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
    onDelete: (ChatSession) -> Unit,
    onRename: (ChatSession, String) -> Unit,
    onExport: (ChatSession) -> Unit,
    onNavigateToSettings: () -> Unit,
    currentThemeMode: AppThemeMode,
    onCycleTheme: () -> Unit,
    workspaceViewModel: WorkspaceViewModel? = null,
    workspaceFileViewModel: WorkspaceFileViewModel? = null,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchWorkspaceConfirmed: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    /** 查询某工作区绑定的会话（供「所有工作台 → 查看对话绑定」展示）。 */
    boundSessionsForWorkspace: suspend (String) -> List<ChatSession> = { emptyList() },
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
        // 顶部 tab 菜单：对话列表 / 工作目录 / 更多配置。
        // 高度固定 44dp，与全局 AppTopAppBar 标题栏高度完全一致，保持视觉对齐。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            DrawerTopTab(
                text = stringResource(R.string.chat_drawer_tab_chats),
                selected = selectedTab == 0,
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = 0 }
            )
            DrawerTopTab(
                text = stringResource(R.string.chat_drawer_tab_workspace),
                selected = selectedTab == 1,
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = 1 }
            )
            DrawerTopTab(
                text = stringResource(R.string.chat_drawer_tab_more),
                selected = selectedTab == 2,
                modifier = Modifier.weight(1f),
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
                onLongPress = { menuSession = it },
                modifier = Modifier.weight(1f)
            )
            1 -> if (workspaceViewModel != null && workspaceFileViewModel != null) {
                WorkspaceDirPanel(
                    viewModel = workspaceViewModel,
                    fileViewModel = workspaceFileViewModel,
                    hasRunningSessions = hasRunningSessions,
                    onSwitchConfirmed = onSwitchWorkspaceConfirmed,
                    onOpenFile = onOpenFile,
                    boundSessionsForWorkspace = boundSessionsForWorkspace,
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
        // 底部导航：左侧「设置」（图标 + 文字），右侧「主题切换」纯图标按钮。
        // 两侧各留 Spacing.md 边距，与侧边栏内容左右对齐，视觉更舒适。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DrawerSettingsButton(
                contentDescription = stringResource(R.string.chat_settings),
                onClick = onNavigateToSettings
            )
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
 * 侧边栏底部「设置」按钮：图标 + 文字 的整块可点击区域（位于底部左侧）。
 */
@Composable
private fun DrawerSettingsButton(
    contentDescription: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DrawerNavIcon(
            icon = Icons.Rounded.Settings,
            iconBgLight = Color(0xFF0EA5E9),
            iconBgDark = Color(0xFF0369A1)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = stringResource(R.string.chat_settings),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
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

/**
 * 侧边栏顶部 tab 项：文字标签 + 底部选中指示条，高 44dp 与全局标题栏一致。
 * 选中态以主色文字 + 圆角指示条表达（替代 Material3 TabRow 的 48dp 默认高度）。
 */
@Composable
private fun DrawerTopTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .width(20.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
        }
    }
}

/**
 * 侧边栏「对话列表」tab：中部历史记录列表（新建会话入口在聊天页顶栏）。
 * 每次进入该 tab 会自动滚动到当前会话。
 */
@Composable
private fun ChatSessionListPanel(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
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
 * 侧边栏「工作目录」tab：内嵌子 tab（当前工作台 / 所有工作台）。
 * - 当前工作台：以 [WorkspaceFileViewModel] 浏览当前工作区文件树，点击文件跳转独立阅读页；
 * - 所有工作台：工作区列表管理（切换 / 新建 / 删除）。
 * 工作区数据源复用 WorkspaceViewModel，与输入框底部工作区弹窗一致。
 */
@Composable
private fun WorkspaceDirPanel(
    viewModel: WorkspaceViewModel,
    fileViewModel: WorkspaceFileViewModel,
    hasRunningSessions: () -> Boolean,
    onSwitchConfirmed: () -> Unit,
    onOpenFile: (String) -> Unit,
    boundSessionsForWorkspace: suspend (String) -> List<ChatSession>,
    modifier: Modifier = Modifier
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()

    var subTab by rememberSaveable { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }
    var pendingSwitch by remember { mutableStateOf<Workspace?>(null) }
    var pendingRename by remember { mutableStateOf<Workspace?>(null) }

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

        // 内嵌子 tab：当前工作台（文件浏览） / 所有工作台（列表管理）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            WorkspaceSubTab(
                text = stringResource(R.string.workspace_tab_current),
                selected = subTab == 0,
                modifier = Modifier.weight(1f),
                onClick = { subTab = 0 }
            )
            WorkspaceSubTab(
                text = stringResource(R.string.workspace_tab_all),
                selected = subTab == 1,
                modifier = Modifier.weight(1f),
                onClick = { subTab = 1 }
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        Box(modifier = Modifier.weight(1f)) {
            when (subTab) {
                0 -> CurrentWorkspacePanel(
                    viewModel = fileViewModel,
                    onOpenFile = onOpenFile,
                    modifier = Modifier.fillMaxSize()
                )
                else -> AllWorkspacesPanel(
                    workspaces = workspaces,
                    currentName = current?.name,
                    onSwitchRequest = { ws ->
                        if (hasRunningSessions()) {
                            pendingSwitch = ws
                        } else {
                            onSwitchConfirmed()
                            viewModel.selectWorkspace(ws.name)
                        }
                    },
                    onDeleteRequest = { pendingDelete = it },
                    onRenameRequest = { pendingRename = it },
                    boundSessionsForWorkspace = boundSessionsForWorkspace,
                    modifier = Modifier.fillMaxSize()
                )
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

    // 重命名工作区
    pendingRename?.let { ws ->
        var renameText by remember(ws.name) { mutableStateOf(ws.name) }
        val existingNames = workspaces.map { it.name } - ws.name
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.workspace_rename)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.workspace_rename_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (renameText.isNotBlank() && renameText.trim() in existingNames) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.workspace_name_exists),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() &&
                        renameText.trim() != ws.name &&
                        renameText.trim() !in existingNames,
                    onClick = {
                        viewModel.renameWorkspace(ws.name, renameText.trim())
                        pendingRename = null
                    }
                ) { Text(stringResource(R.string.workspace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
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

/** 「当前工作台」子 tab 的 tab 项：圆角选中高亮的文字按钮。 */
@Composable
private fun WorkspaceSubTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「当前工作台」子 tab：当前工作区文件树浏览器。
 * 数据源 [WorkspaceFileViewModel] 维护目录导航栈，目录可逐级进入，点击文件回调 [onOpenFile] 跳转独立阅读页。
 */
@Composable
private fun CurrentWorkspacePanel(
    viewModel: WorkspaceFileViewModel,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val dirStack by viewModel.dirStack.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        // 路径栏：返回上级按钮 + 当前相对路径
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = viewModel::goUp,
                enabled = dirStack.isNotEmpty(),
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.workspace_file_up),
                    tint = if (dirStack.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = if (dirStack.isEmpty()) {
                    stringResource(R.string.workspace_file_root)
                } else {
                    dirStack.joinToString("/")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            entries.isEmpty() -> Text(
                text = stringResource(R.string.workspace_file_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(entries, key = { it.name }) { entry ->
                    WorkspaceFileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                viewModel.enterDirectory(entry.name)
                            } else {
                                onOpenFile(viewModel.containerPathFor(entry))
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 文件树行：文件夹用文件夹图标，文件用文件图标 + 大小，点击整行触发。 */
@Composable
private fun WorkspaceFileRow(
    entry: FileEntry,
    onClick: () -> Unit
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
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else fileTypeIcon(entry.name),
            contentDescription = null,
            tint = if (entry.isDirectory) Color(0xFFF59E0B) else fileTypeIconTint(entry.name),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!entry.isDirectory && entry.size > 0) {
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatFileSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「所有工作台」子 tab：工作区列表。点击行弹出下拉菜单（切换 / 重命名 / 删除 / 查看对话绑定），
 * 不再直接切换；「查看对话绑定」以手风琴形式展开该工作区绑定的会话列表。
 */
@Composable
private fun AllWorkspacesPanel(
    workspaces: List<Workspace>,
    currentName: String?,
    onSwitchRequest: (Workspace) -> Unit,
    onDeleteRequest: (Workspace) -> Unit,
    onRenameRequest: (Workspace) -> Unit,
    boundSessionsForWorkspace: suspend (String) -> List<ChatSession>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
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
                        selected = ws.name == currentName,
                        canDelete = workspaces.size > 1,
                        onSwitch = { onSwitchRequest(ws) },
                        onRename = { onRenameRequest(ws) },
                        onDelete = { onDeleteRequest(ws) },
                        boundSessions = { boundSessionsForWorkspace(ws.path) }
                    )
                }
            }
        }
    }
}

/** 文件大小人类可读格式化（B / KB / MB）。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 提取文件小写扩展名（无扩展名返回空串）。 */
private fun fileExtension(name: String): String {
    val idx = name.lastIndexOf('.')
    return if (idx > 0 && idx < name.length - 1) name.substring(idx + 1).lowercase() else ""
}

/**
 * 按文件后缀返回对应图标，覆盖代码 / 文档 / 图片 / 压缩包 / 表格 / 数据库 / 配置 / 脚本等常见类型，
 * 无法识别时回退通用文件图标。目录走文件夹图标，不进入本函数。
 */
private fun fileTypeIcon(name: String): ImageVector {
    return when (fileExtension(name)) {
        // 图片
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "heic", "avif" -> Icons.Rounded.Image
        // PDF / 文档
        "pdf" -> Icons.Rounded.PictureAsPdf
        "doc", "docx", "odt", "rtf", "pages" -> Icons.Rounded.Description
        "md", "markdown", "txt", "rst", "adoc", "log", "text" -> Icons.Rounded.Article
        // 表格 / 数据
        "xls", "xlsx", "ods", "csv", "tsv" -> Icons.Rounded.TableChart
        // 数据库
        "db", "sqlite", "sqlite3", "sql" -> Icons.Rounded.Storage
        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst", "jar", "apk", "aab" -> Icons.Rounded.FolderZip
        // Web / 标记 / 配置
        "html", "htm", "css", "scss", "sass", "less", "xml", "json", "yml", "yaml", "toml",
        "ini", "conf", "env", "properties", "gradle", "gradle.kts", "editorconfig" -> Icons.Rounded.Language
        // 脚本 / 终端
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1" -> Icons.Rounded.Terminal
        // 代码
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "jsx", "tsx", "go", "rs", "c", "h",
        "cpp", "cc", "cxx", "hpp", "hxx", "swift", "rb", "php", "lua", "scala", "groovy", "dart",
        "ex", "exs", "erl", "hs", "clj", "cljs", "cljc", "vue", "svelte", "proto" -> Icons.Rounded.Code
        // 通用
        else -> Icons.Rounded.InsertDriveFile
    }
}

/** 文件图标配色：按类型给浅色着色，便于识别；未知类型回退中性灰。 */
private fun fileTypeIconTint(name: String): Color {
    return when (fileExtension(name)) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "heic", "avif" -> Color(0xFF8B5CF6)
        "pdf" -> Color(0xFFEF4444)
        "md", "markdown", "txt", "rst", "adoc", "log", "text" -> Color(0xFF64748B)
        "doc", "docx", "odt", "rtf", "pages" -> Color(0xFF2563EB)
        "xls", "xlsx", "ods", "csv", "tsv" -> Color(0xFF16A34A)
        "db", "sqlite", "sqlite3", "sql" -> Color(0xFF0EA5E9)
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst", "jar", "apk", "aab" -> Color(0xFFF59E0B)
        "html", "htm", "css", "scss", "sass", "less", "xml", "json", "yml", "yaml", "toml",
        "ini", "conf", "env", "properties", "gradle", "gradle.kts", "editorconfig" -> Color(0xFF0EA5E9)
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1" -> Color(0xFF22C55E)
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "jsx", "tsx", "go", "rs", "c", "h",
        "cpp", "cc", "cxx", "hpp", "hxx", "swift", "rb", "php", "lua", "scala", "groovy", "dart",
        "ex", "exs", "erl", "hs", "clj", "cljs", "cljc", "vue", "svelte", "proto" -> Color(0xFF6366F1)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * 工作区列表行：点击整行弹出下拉菜单（切换 / 重命名 / 删除 / 查看对话绑定）。
 * 「查看对话绑定」手风琴式展开：点击后加载并展示该工作区绑定的会话列表。
 */
@Composable
private fun WorkspaceDirRow(
    workspace: Workspace,
    selected: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    boundSessions: suspend () -> List<ChatSession>,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var bindingsExpanded by remember { mutableStateOf(false) }
    var boundSessionsState by remember { mutableStateOf<List<ChatSession>?>(null) }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .clickable { menuExpanded = true }
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
            if (selected) {
                Text(
                    text = stringResource(R.string.workspace_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(R.string.workspace_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.width(260.dp)
        ) {
            if (!selected) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_switch_to)) },
                    leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                    onClick = {
                        menuExpanded = false
                        onSwitch()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = {
                    menuExpanded = false
                    onRename()
                }
            )
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_delete)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_view_bindings)) },
                leadingIcon = { Icon(Icons.Rounded.Forum, null) },
                trailingIcon = {
                    Icon(
                        if (bindingsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null
                    )
                },
                onClick = { bindingsExpanded = !bindingsExpanded }
            )
            if (bindingsExpanded) {
                LaunchedEffect(workspace.path) {
                    boundSessionsState = boundSessions()
                }
                val sessions = boundSessionsState
                when {
                    sessions == null -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_loading)) },
                        enabled = false
                    )
                    sessions.isEmpty() -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_no_bound_sessions)) },
                        enabled = false
                    )
                    else -> sessions.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = s.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            enabled = false
                        )
                    }
                }
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
