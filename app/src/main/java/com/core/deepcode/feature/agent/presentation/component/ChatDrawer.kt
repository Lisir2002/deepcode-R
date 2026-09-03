package com.core.deepcode.feature.agent.presentation.component

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.deepcode.core.theme.LocalAppDarkMode
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.data.local.dao.ChatSessionWithCount
import com.core.deepcode.feature.agent.domain.model.ChatSession
import com.core.deepcode.feature.agent.presentation.AgentUIState
import com.core.deepcode.feature.settings.data.repository.AppThemeMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.core.deepcode.R
import com.core.deepcode.feature.workspace.domain.FileEntry
import com.core.deepcode.feature.workspace.domain.model.Workspace
import com.core.deepcode.feature.workspace.presentation.WorkspaceFileViewModel
import com.core.deepcode.feature.workspace.presentation.WorkspaceViewModel
import kotlinx.coroutines.launch

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
    sessionsWithCount: List<ChatSessionWithCount>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onRename: (ChatSession, String) -> Unit,
    onExport: (ChatSession) -> Unit,
    onUndoDelete: () -> Unit = {},
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
    /** 当前会话（供「更多配置 → 工作台绑定」展示绑定状态）。 */
    currentSession: ChatSession? = null,
    /** 手动绑定当前会话到指定工作台（「更多配置 → 工作台绑定」）。 */
    onBindWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                sessions = sessionsWithCount,
                currentSessionId = currentSessionId,
                agentStates = agentStates,
                onSelect = onSelect,
                onLongPress = { menuSession = it },
                onSwipeDelete = { pendingDelete = it.toDomain() },
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
            else -> MoreConfigPanel(
                workspaceViewModel = workspaceViewModel,
                currentSession = currentSession,
                onBindWorkspace = onBindWorkspace,
                modifier = Modifier.weight(1f)
            )
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

        // 删除后的 Snackbar（含「撤销」动作），位于侧边栏底部导航下方。
        SnackbarHost(hostState = snackbarHostState)
    }

    pendingDelete?.let { session ->
        val isExecuting = agentStates[session.id] is AgentUIState.Loading ||
            agentStates[session.id] is AgentUIState.Streaming
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_delete_session_confirm, session.title))
                    if (isExecuting) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.chat_delete_session_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                val deletedMsg = stringResource(R.string.chat_deleted_snackbar, session.title)
                val undoLabel = stringResource(R.string.chat_undo)
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                    // 删除成功后弹 Snackbar「已删除 · 撤销」；点「撤销」恢复会话+消息
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            onUndoDelete()
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.primary) }
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
        val canSubmit = renameText.isNotBlank() && renameText.trim() != session.title
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_session_name)) },
                    // C3：IME 回车 = 提交（与确认按钮等效）
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canSubmit) {
                            onRename(session, renameText)
                            pendingRename = null
                        }
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = canSubmit
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
                tint = MaterialTheme.colorScheme.primary,
                iconBgLight = Color(0xFF4C8DFF),
                iconBgDark = Color(0xFF2B4E9E),
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

/** 对话列表的扁平条目：分组头 或 会话行（A3 四档吸顶分组用）。 */
private sealed interface SessionListEntry {
    data class Header(val bucket: SessionBucket, val count: Int) : SessionListEntry
    data class Row(val session: ChatSessionWithCount) : SessionListEntry
}

/**
 * 按 updatedAtMs 将会话分档（今天/昨天/7天内/更早），并按档序（今天→昨天→7天内→更早）
 * 展平为 [SessionListEntry] 列表。组内保持输入顺序（数据源已按更新时间降序）。
 */
private fun buildSessionEntries(
    sessions: List<ChatSessionWithCount>,
    nowMs: Long
): List<SessionListEntry> {
    val grouped = sessions.groupBy { sessionBucket(it.updatedAtMs, nowMs) }
    val order = listOf(
        SessionBucket.TODAY,
        SessionBucket.YESTERDAY,
        SessionBucket.WITHIN_7D,
        SessionBucket.EARLIER
    )
    return buildList {
        for (bucket in order) {
            val list = grouped[bucket].orEmpty()
            if (list.isNotEmpty()) {
                add(SessionListEntry.Header(bucket, list.size))
                list.forEach { add(SessionListEntry.Row(it)) }
            }
        }
    }
}

/** 分组头：分档名 + 右侧会话计数，作为 LazyColumn stickyHeader 吸顶。 */
@Composable
private fun SessionGroupHeader(bucket: SessionBucket, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 吸顶时背景需不透明（surface），避免下方行内容透出
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (bucket) {
                SessionBucket.TODAY -> stringResource(R.string.chat_session_today)
                SessionBucket.YESTERDAY -> stringResource(R.string.chat_session_yesterday)
                SessionBucket.WITHIN_7D -> stringResource(R.string.chat_session_within_7d)
                SessionBucket.EARLIER -> stringResource(R.string.chat_session_earlier)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 左滑删除的会话行（B1）：外包 SwipeToDismissBox，仅允许 EndToStart（左滑）。
 * 滑到阈值时只触发 [onSwipeDelete]（上抛确认框），本行始终回弹；确认删除后
 * 行随数据源移除而消失，避免 LazyColumn key 复用串态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionRow(
    session: ChatSessionWithCount,
    selected: Boolean,
    isExecuting: Boolean,
    onSwipeDelete: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onSwipeDelete()
            }
            false // 始终回弹；删除须经确认框，此处不真正移除
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    ) {
        ChatSessionRow(
            session = session,
            selected = selected,
            isExecuting = isExecuting,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

/**
 * 侧边栏「对话列表」tab：中部历史记录列表（新建会话入口在聊天页顶栏）。
 * 四档日期分组（今天/昨天/7天内/更早）吸顶；列表项两行增强；支持左滑删除。
 * 每次进入该 tab 会自动滚动到当前会话（按分组后的全局下标换算）。
 */
@Composable
private fun ChatSessionListPanel(
    sessions: List<ChatSessionWithCount>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    onSelect: (ChatSession) -> Unit,
    onLongPress: (ChatSession) -> Unit,
    onSwipeDelete: (ChatSessionWithCount) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val entries = remember(sessions) { buildSessionEntries(sessions, System.currentTimeMillis()) }

    LaunchedEffect(currentSessionId, entries) {
        if (entries.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst {
            it is SessionListEntry.Row && it.session.id == currentSessionId
        }
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
                    entries.forEach { entry ->
                        when (entry) {
                            is SessionListEntry.Header -> stickyHeader(key = "header_${entry.bucket}") {
                                SessionGroupHeader(bucket = entry.bucket, count = entry.count)
                            }
                            is SessionListEntry.Row -> item(key = entry.session.id) {
                                val state = agentStates[entry.session.id]
                                val isExecuting =
                                    state is AgentUIState.Loading || state is AgentUIState.Streaming
                                SwipeableSessionRow(
                                    session = entry.session,
                                    selected = entry.session.id == currentSessionId,
                                    isExecuting = isExecuting,
                                    onSwipeDelete = { onSwipeDelete(entry.session) },
                                    onClick = { onSelect(entry.session.toDomain()) },
                                    onLongClick = { onLongPress(entry.session.toDomain()) }
                                )
                            }
                        }
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
            tint = if (entry.isDirectory) Color(0xFFF59E0B) else fileTypeIconTint(entry.name, MaterialTheme.colorScheme.onSurfaceVariant),
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

/** 文件图标配色：按类型给浅色着色，便于识别；未知类型回退 [fallback]。 */
private fun fileTypeIconTint(name: String, fallback: Color): Color {
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
        else -> fallback
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
                        enabled = false,
                        onClick = {}
                    )
                    sessions.isEmpty() -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_no_bound_sessions)) },
                        enabled = false,
                        onClick = {}
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
                            enabled = false,
                            onClick = {}
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

/**
 * 侧边栏「更多配置」tab：工作台绑定等配置项。
 *
 * 工作台绑定：会话与工作台一一绑定、不可中途切换。新会话创建时未绑定，用户发送首条消息时
 * 自动绑定当前工作台；也可在本面板把当前（未绑定）会话手动绑定到任一工作台。
 */
@Composable
private fun MoreConfigPanel(
    workspaceViewModel: WorkspaceViewModel?,
    currentSession: ChatSession?,
    onBindWorkspace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val workspaces = workspaceViewModel?.workspaces?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val currentWorkspacePath = currentWorkspace?.path.orEmpty()

    val sessionWorkspacePath = currentSession?.workspacePath.orEmpty()
    val isBound = sessionWorkspacePath.isNotBlank()
    val boundWorkspaceName = workspaces.firstOrNull { it.path == sessionWorkspacePath }?.name
        ?: sessionWorkspacePath.substringAfterLast('/').ifBlank { sessionWorkspacePath }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        item {
            Column(Modifier.padding(horizontal = Spacing.xs)) {
                // 板块标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = Spacing.md)
                ) {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.workspace_bind_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(R.string.workspace_bind_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))

                if (isBound) {
                    // 已绑定：展示绑定目标，绑定一次性、不可中途切换
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = Spacing.md, vertical = Spacing.md)
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_bind_bound, boundWorkspaceName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.workspace_bind_bound_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 未绑定：展示可绑定的工作台列表
                    Text(
                        text = stringResource(R.string.workspace_bind_unbound_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    when {
                        currentSession == null -> Text(
                            text = stringResource(R.string.workspace_bind_no_session),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm)
                        )
                        workspaces.isEmpty() -> Text(
                            text = stringResource(R.string.workspace_bind_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm)
                        )
                        else -> workspaces.forEach { ws ->
                            WorkspaceBindRow(
                                workspace = ws,
                                isCurrent = ws.path == currentWorkspacePath,
                                onClick = { onBindWorkspace(ws.path) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 手动绑定面板中的工作台选择行：点击即把当前会话绑定到该工作台。 */
@Composable
private fun WorkspaceBindRow(
    workspace: Workspace,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = workspace.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isCurrent) {
            Text(
                text = stringResource(R.string.workspace_current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            Icons.Rounded.Link,
            contentDescription = stringResource(R.string.workspace_bind_action),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}
