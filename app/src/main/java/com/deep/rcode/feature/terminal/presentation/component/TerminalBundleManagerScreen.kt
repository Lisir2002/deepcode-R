package com.deep.rcode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.R
import com.deep.rcode.core.theme.AppSectionHeader
import com.deep.rcode.core.theme.AppTopAppBar
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.agent.domain.container.GlobalInstallArchiveStore
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import com.deep.rcode.feature.terminal.presentation.component.toUi
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.RefreshCw
import kotlinx.coroutines.launch

/**
 * 功能包 Bundle 管理子页面。
 *  - 顶部容器状态 + AI 推荐组合一键安装
 *  - 6 张独立 Bundle 卡片（安装/卸载/进度/失败重试）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalBundleManagerScreen(
    viewModel: TerminalSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val containerInstalled by viewModel.containerInstalled.collectAsStateWithLifecycle()
    val storageUsedMb by viewModel.storageUsedMb.collectAsStateWithLifecycle()
    val bundleStates by viewModel.bundleStates.collectAsStateWithLifecycle()
    val aiAllInstalled by viewModel.aiRecommendedAllInstalled.collectAsStateWithLifecycle()
    val errorToast by viewModel.errorToast.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(errorToast) {
        errorToast?.let {
            scope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long) }
            viewModel.consumeErrorToast()
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = "功能包管理",
                onNavigateBack = onNavigateBack,
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    // 手动刷新：从容器真实 apk 世界重新同步 bundle 安装状态（联动检测）
                    IconButton(onClick = viewModel::refreshBundles) {
                        Icon(
                            imageVector = FeatherIcons.RefreshCw,
                            contentDescription = "刷新安装状态",
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            SharedContainerEnvCard(
                containerInstalled = containerInstalled,
                initProgress = containerInit,
                storageUsedMb = storageUsedMb,
                mode = ContainerCardMode.INIT_ONLY,
                onInit = viewModel::ensureContainerInstalled
            )

            AppSectionHeader(text = "AI 推荐组合")
            SharedAiRecommendationStrip(
                allInstalled = aiAllInstalled,
                containerReady = containerInstalled,
                onInstallAll = viewModel::installAiRecommended
            )

            AppSectionHeader(text = "独立功能包")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                var openDialogFor by remember { mutableStateOf<TerminalBundleId?>(null) }
                val agg by viewModel.aggregateProgress.collectAsStateWithLifecycle()
                val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
                val ctx = LocalContext.current
                val snackbarHostStateHere = remember { SnackbarHostState() }
                val onCopyError: (String) -> Unit = { reason ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("install-error", reason))
                }
                // A主-3：判断某个 bundleId 是否为「当前正在进行的会话」
                fun isBundleInProgress(id: TerminalBundleId): Boolean {
                    val s = containerInit
                    return s is ContainerInitState.BundleInstalling && s.bundleId == id
                }
                // 监听 GlobalInstallArchiveStore 的 globalRevision，让"有没有存档"在有新写入时 recompose
                val archiveRev by remember(GlobalInstallArchiveStore.globalRevision) {
                    mutableStateOf(GlobalInstallArchiveStore.globalRevision)
                }
                viewModel.bundles().forEach { b ->
                    val bState = bundleStates[b.id] ?: BundleInstallState.NotInstalled
                    val inProgress = isBundleInProgress(b.id)
                    // 新 BundleInstallCard：3 行极简 + N 微槽块（按 Q9 选定：画在卡片内）；
                    // aggregate 为 null（走旧通路）时自动降级。
                    BundleInstallCard(
                        bundle = b.toUi(),
                        bundleState = bState,
                        aggregate = agg.takeIf { inProgress },
                        onInstallClick = { viewModel.installBundle(b.id) },
                        onUninstallClick = { viewModel.uninstallBundle(b.id) },
                        onOpenLogDialog = { openDialogFor = b.id },
                        onCopyError = onCopyError,
                        modifier = Modifier.padding(horizontal = Spacing.md),
                    )
                    val dialogBundle = openDialogFor
                    if (dialogBundle != null && dialogBundle == b.id) {
                        val isCurrent = isBundleInProgress(b.id)
                        val archiveSnapshot = run {
                            archiveRev // 强制 snapshot read：revision 变 archive 就重新取
                            GlobalInstallArchiveStore.getSnapshot(b.id)
                        }
                        BundleLogDialog(
                            bundle = b.toUi(),
                            state = agg,
                            isCurrentSession = isCurrent,
                            archiveSnapshot = archiveSnapshot,
                            onDismiss = { openDialogFor = null },
                        )
                    }
                }
            }
        }
    }
}
