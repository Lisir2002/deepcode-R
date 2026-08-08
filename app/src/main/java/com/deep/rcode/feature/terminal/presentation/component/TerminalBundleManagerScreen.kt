package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.R
import com.deep.rcode.core.theme.AppSectionHeader
import com.deep.rcode.core.theme.AppTopAppBar
import com.deep.rcode.core.theme.Elevation
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/**
 * 功能包 Bundle 管理子页面。
 *  - 顶部容器状态 + AI 推荐组合一键安装
 *  - 7 张独立 Bundle 卡片（安装/卸载/进度/失败重试）
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
        containerColor = MaterialTheme.colorScheme.background,
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
            BundleContainerSummaryCard(
                containerInstalled = containerInstalled,
                initProgress = containerInit,
                storageUsedMb = storageUsedMb,
                onInit = viewModel::ensureContainerInstalled
            )

            AppSectionHeader(text = "AI 推荐组合")
            AiRecommendationStrip(
                allInstalled = aiAllInstalled,
                containerReady = containerInstalled,
                onInstallAll = viewModel::installAiRecommended
            )

            AppSectionHeader(text = "独立功能包")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                viewModel.bundles().forEach { b ->
                    BundleCard(
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

@Composable
private fun BundleContainerSummaryCard(
    containerInstalled: Boolean,
    initProgress: ContainerInitState,
    storageUsedMb: Long,
    onInit: () -> Unit
) {
    val statusText = when (initProgress) {
        is ContainerInitState.Idle -> "未初始化"
        is ContainerInitState.ExtractingRootfs -> "正在解压 rootfs…"
        ContainerInitState.DeployingProot -> "正在部署 proot…"
        is ContainerInitState.Ready -> "已就绪"
        is ContainerInitState.BundleInstalling ->
            "已就绪（正在安装${initProgress.bundleId?.stableKey ?: "功能包"}…）"
        is ContainerInitState.BundleUninstalling -> "已就绪（正在卸载…）"
        is ContainerInitState.Failed -> "失败：${initProgress.reason.take(20)}…"
    }
    val dotColor: Color = when {
        !containerInstalled && initProgress !is ContainerInitState.ExtractingRootfs
            && initProgress != ContainerInitState.DeployingProot -> Color(0xFFE57373)
        initProgress is ContainerInitState.Failed -> Color(0xFFE57373)
        containerInstalled -> Color(0xFF81C784)
        else -> Color(0xFFFFB74D)
    }
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.HardDrive,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(text = "容器状态", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = when {
                    containerInstalled -> "已占用 $storageUsedMb MB · Alpine 3.21 · arm64-v8a · PRoot"
                    else -> "先初始化容器（rootfs+proot，约 150 MB）后才能安装下面的功能包"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!containerInstalled) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Button(onClick = onInit) {
                    Icon(FeatherIcons.Plus, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("初始化容器")
                }
            }
        }
    }
}

@Composable
private fun AiRecommendationStrip(
    allInstalled: Boolean,
    containerReady: Boolean,
    onInstallAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                if (allInstalled) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f)
                else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.Cpu,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "AI 推荐组合（Python + rg + Git + Bash + Curl）",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = if (allInstalled) "全部组件已就绪，AI 运行代码块/搜索/git 开箱即用。"
                else "共约 75 MB，覆盖 AI 代码运行与搜索。推荐使用 AI 功能的用户一键安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (allInstalled) {
                AssistChip(
                    onClick = {},
                    leadingIcon = {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("已完成") },
                    enabled = false
                )
            } else {
                Button(onClick = onInstallAll, enabled = containerReady) {
                    Icon(FeatherIcons.Plus, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("一键安装")
                }
            }
        }
    }
}

@Composable
private fun BundleCard(
    bundle: TerminalBundle,
    state: BundleInstallState,
    containerReady: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    data class Status(val text: String, val color: Color, val showProgress: Boolean)
    val status = when (state) {
        is BundleInstallState.NotInstalled ->
            Status("未安装", MaterialTheme.colorScheme.onSurfaceVariant, false)
        is BundleInstallState.Installing ->
            Status("安装中…${state.line?.take(30) ?: ""}", MaterialTheme.colorScheme.secondary, true)
        is BundleInstallState.Uninstalling ->
            Status("卸载中…", MaterialTheme.colorScheme.secondary, true)
        is BundleInstallState.Failed ->
            Status("失败：${state.reason.take(16)}", Color(0xFFE57373), false)
        is BundleInstallState.Installed ->
            Status("已安装", Color(0xFF81C784), false)
    }

    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    bundleIcon(bundle),
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = bundle.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(status.color, shape = RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "约 ${bundle.sizeEstimateMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = when (state) {
                    is BundleInstallState.Installing -> "状态：${status.text.take(50)} …"
                    is BundleInstallState.Failed -> "状态：${status.text}"
                    is BundleInstallState.Installed -> "状态：已安装 · 配置版本 v${bundle.version}"
                    is BundleInstallState.Uninstalling -> "状态：正在卸载…"
                    else -> "状态：${status.text}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = status.color,
                fontFamily = FontFamily.Monospace
            )
            if (status.showProgress) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                )
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (state) {
                    is BundleInstallState.Installed -> {
                        OutlinedButton(onClick = onUninstall, modifier = Modifier.height(36.dp)) {
                            Icon(FeatherIcons.Trash2, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("卸载", fontSize = 13.sp)
                        }
                    }
                    is BundleInstallState.Installing, is BundleInstallState.Uninstalling -> {
                        TextButton(onClick = {}, enabled = false, modifier = Modifier.height(36.dp)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Text("处理中…", fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onInstall,
                            enabled = containerReady,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(FeatherIcons.Plus, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("安装", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun bundleIcon(b: TerminalBundle): ImageVector = when (b.id) {
    TerminalBundleId.PYTHON -> FeatherIcons.Cpu
    TerminalBundleId.NODE -> FeatherIcons.Box
    TerminalBundleId.RIPGREP -> FeatherIcons.Search
    TerminalBundleId.GIT -> FeatherIcons.GitBranch
    TerminalBundleId.BASH -> FeatherIcons.Terminal
    TerminalBundleId.NET -> FeatherIcons.Globe
}
