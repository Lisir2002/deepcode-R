package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.R
import com.deep.rcode.core.theme.AppSectionHeader
import com.deep.rcode.core.theme.AppTopAppBar
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
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
                navigationContentDescription = stringResource(R.string.common_back)
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
                viewModel.bundles().forEach { b ->
                    SharedBundleCard(
                        bundle = b,
                        state = bundleStates[b.id] ?: BundleInstallState.NotInstalled,
                        containerReady = containerInstalled,
                        onInstall = { viewModel.installBundle(b.id) },
                        onUninstall = { viewModel.uninstallBundle(b.id) }
                    )
                }
            }
        }
    }
}
