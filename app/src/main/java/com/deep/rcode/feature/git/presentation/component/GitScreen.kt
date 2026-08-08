package com.deep.rcode.feature.git.presentation.component

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.deep.rcode.core.theme.AppTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.R
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.credentials.domain.model.GitCredential
import com.deep.rcode.feature.credentials.presentation.CredentialViewModel
import com.deep.rcode.feature.credentials.presentation.component.CredentialEditorSheet
import com.deep.rcode.feature.credentials.presentation.component.CredentialListSection
import com.deep.rcode.feature.git.domain.model.GitStatus
import com.deep.rcode.feature.git.domain.model.GitTab
import com.deep.rcode.feature.git.presentation.GitViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    viewModel: GitViewModel,
    credentialViewModel: CredentialViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val credState by credentialViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // toast → Snackbar 一次性消费。
    LaunchedEffect(state.toast, credState.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
        credState.toast?.let {
            snackbarHostState.showSnackbar(it)
            credentialViewModel.consumeToast()
        }
    }

    var showCommitDialog by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    // 凭据列表态拦截系统返回键：退回 Git 主视图而非退出整个 Git 页。
    BackHandler(enabled = showCredentials) { showCredentials = false }
    // editingCredential != null -> 编辑现有；editingCredential == null && isAddingCredential -> 新增；否则列表态。
    // 编辑/新增态直接在 [Scaffold] 之外独立渲染全屏 [CredentialEditorScreen]（它自带 Scaffold/TopAppBar/BackHandler），
    // 避免与本页 Scaffold 嵌套产生双层顶栏，返回由其自身 BackHandler 接管。
    var editingCredential by remember { mutableStateOf<GitCredential?>(null) }
    var isAddingCredential by remember { mutableStateOf(false) }

    // diff 视图：独立全屏页，不进入下方 GitScreen 的 Scaffold，避免双层顶栏。
    val diffData = state.diffData
    if (diffData != null) {
        DiffViewerScreen(
            diffData = diffData,
            onBack = { viewModel.clearDiff() }
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = if (showCredentials) stringResource(R.string.git_credentials_and_identity) else "Git",
                onNavigateBack = {
                    if (showCredentials) showCredentials = false else onNavigateBack()
                },
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back)
            ) {
                if (!showCredentials) {
                    IconButton(onClick = { showCredentials = true }, modifier = Modifier.size(40.dp)) {
                        Icon(FeatherIcons.Key, contentDescription = stringResource(R.string.git_credentials_and_identity), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy, modifier = Modifier.size(40.dp)) {
                        Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.git_refresh), modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(onClick = { isAddingCredential = true }, modifier = Modifier.size(40.dp)) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.credential_add), modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showCredentials) {
                // 每次进入凭据页重新读署名：用户可能在终端改过项目级/全局署名，避免回显陈旧空值。
                LaunchedEffect(Unit) { credentialViewModel.refreshIdentity() }
                CredentialListSection(
                    credentials = credState.credentials,
                    userName = credState.userName,
                    userEmail = credState.userEmail,
                    globalUserName = credState.globalUserName,
                    repoUrl = credState.repoUrl,
                    onEdit = { editingCredential = it },
                    onToggleDefault = { id, isDefault -> credentialViewModel.setDefault(id, isDefault) },
                    onSaveIdentity = { name, email, repoUrl -> credentialViewModel.saveUserIdentity(name, email, repoUrl) }
                )
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                GitTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.setTab(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    GitTab.STATUS -> stringResource(R.string.git_tab_status)
                                    GitTab.BRANCHES -> stringResource(R.string.git_tab_branches)
                                    GitTab.LOG -> stringResource(R.string.git_tab_commits)
                                }
                            )
                        }
                    )
                }
            }

            when {
                state.diffLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Spacing.sm))
                        Text(stringResource(R.string.git_computing_diff), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.notARepo -> NotARepoState(onInit = viewModel::initRepo)
                else -> when (state.tab) {
                    GitTab.STATUS -> StatusTab(
                        status = state.status,
                        busy = state.busy,
                        hasRemote = state.hasRemote,
                        hasIdentity = state.hasIdentity,
                        onStage = viewModel::stage,
                        onUnstage = viewModel::unstage,
                        onStageAll = viewModel::stageAll,
                        onCommit = { showCommitDialog = true },
                        onPull = viewModel::pull,
                        onPush = viewModel::push,
                        onFileDiff = viewModel::loadWorktreeDiff
                    )
                    GitTab.BRANCHES -> BranchesTab(
                        branches = state.branches,
                        tags = state.tags,
                        branchesLoading = state.branchesLoading,
                        branchesLoaded = state.branchesLoaded,
                        checkoutLoading = state.checkoutLoading,
                        onCheckout = viewModel::checkoutBranch,
                        onCreateBranch = viewModel::createBranch,
                        onDeleteBranch = viewModel::deleteBranch,
                        onDeleteRemoteBranch = viewModel::deleteRemoteBranch,
                        onRenameBranch = viewModel::renameBranch,
                        onCreateTag = viewModel::createTag,
                        onDeleteTag = viewModel::deleteTag
                    )
                    GitTab.LOG -> LogTab(
                        graph = state.graph,
                        expandedCommits = state.expandedCommits,
                        commitFiles = state.commitFiles,
                        loadingCommit = state.loadingCommit,
                        graphLoadingMore = state.graphLoadingMore,
                        onToggleCommit = viewModel::toggleCommit,
                        onFileDiff = viewModel::loadCommitFileDiff,
                        onLoadMore = viewModel::loadMoreCommits
                    )
                }
            }
        }
    }

    if (showCommitDialog) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            onConfirm = { msg ->
                showCommitDialog = false
                viewModel.commit(msg)
            }
        )
    }

    val editing = editingCredential
    if (editing != null) {
        CredentialEditorSheet(
            initial = editing,
            onDismiss = { editingCredential = null },
            onSave = { credentialViewModel.saveCredential(it); editingCredential = null },
            onDelete = { credentialViewModel.deleteCredential(it); editingCredential = null }
        )
    }

    if (isAddingCredential) {
        CredentialEditorSheet(
            initial = null,
            onDismiss = { isAddingCredential = false },
            onSave = { credentialViewModel.saveCredential(it); isAddingCredential = false }
        )
    }
}

@Composable
internal fun StatusMetric(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.sm),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 状态码 → 配色（容器色 + 前景色）。
 *
 * 与多数 Git 客户端约定一致：新增=绿、修改=琥珀、删除=红、重命名/复制=蓝、未跟踪=灰、
 * 冲突=紫红、类型变更=青。仅取首字符判定，porcelain 的 X/Y 两列统一映射。
 */
private fun statusColor(code: String): Pair<Color, Color> = when (code.firstOrNull()) {
    'A' -> Color(0xFF16A34A) to Color(0xFFFFFFFF)            // 新增
    'M' -> Color(0xFFD97706) to Color(0xFFFFFFFF)            // 修改
    'D' -> Color(0xFFDC2626) to Color(0xFFFFFFFF)            // 删除
    'R', 'C' -> Color(0xFF2563EB) to Color(0xFFFFFFFF)       // 重命名/复制
    '?' -> Color(0xFF94A3B8) to Color(0xFFFFFFFF)            // 未跟踪
    'U' -> Color(0xFF9333EA) to Color(0xFFFFFFFF)            // 冲突
    'T' -> Color(0xFF0891B2) to Color(0xFFFFFFFF)            // 类型变更
    else -> Color(0xFF64748B) to Color(0xFFFFFFFF)           // 兜底
}

@Composable
internal fun StatusChip(text: String) {
    val (bg, fg) = statusColor(text)
    Surface(
        color = bg,
        shape = RoundedCornerShape(Radius.xs),
        modifier = Modifier.size(width = 28.dp, height = 22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.take(2),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = fg
            )
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 非仓库态：文案 + 「初始化 Git 仓库」按钮（跑 `git init`，成功后自动刷新进仓库态）。 */
@Composable
private fun NotARepoState(onInit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                stringResource(R.string.git_not_a_repo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.git_init_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onInit) {
                Icon(FeatherIcons.GitBranch, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.git_init_repo))
            }
        }
    }
}

@Composable
private fun CommitDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_tab_commits)) },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(stringResource(R.string.git_commit_message)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (message.isNotBlank()) onConfirm(message.trim()) },
                enabled = message.isNotBlank()
            ) { Text(stringResource(R.string.git_tab_commits)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
