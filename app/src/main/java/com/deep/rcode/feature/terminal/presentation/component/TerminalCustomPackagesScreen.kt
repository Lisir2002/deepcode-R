package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Grid
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/**
 * 自定义 APK 包子页面。
 *  - 顶部容器状态卡
 *  - 一键常用快捷包（htop / neofetch / openssh / rsync / fzf / tmux / lf / zsh）
 *  - 自定义包名输入 + 批量安装
 *  - 已安装自定义包列表（刷新/卸载）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalCustomPackagesScreen(
    viewModel: TerminalSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val containerInstalled by viewModel.containerInstalled.collectAsStateWithLifecycle()
    val customPkgs by viewModel.customPackages.collectAsStateWithLifecycle()
    val errorToast by viewModel.errorToast.collectAsStateWithLifecycle()

    // 自定义包安装/卸载状态由全局 containerInit 的 bundleId==null 推导
    //   bundleId != null → 官方 Bundle 操作；bundleId == null → 自定义包操作
    val customInstallState: BundleInstallState? = when (val p = containerInit) {
        is ContainerInitState.BundleInstalling -> if (p.bundleId == null) BundleInstallState.Installing(line = p.line) else null
        is ContainerInitState.BundleUninstalling -> if (p.bundleId == null) BundleInstallState.Uninstalling else null
        else -> null
    }

    var customInstallInput by remember { mutableStateOf("") }
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
                title = "自定义 APK 包",
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
            CustomContainerSummaryCard(
                containerInstalled = containerInstalled,
                initProgress = containerInit,
                customInstallState = customInstallState,
                onInit = viewModel::ensureContainerInstalled
            )

            AppSectionHeader(text = "常用快捷包")
            QuickPacksChipRow(
                containerReady = containerInstalled,
                customInstallState = customInstallState,
                installed = customPkgs.toSet(),
                onInstall = { names ->
                    viewModel.installCustom(names.joinToString(" "))
                }
            )

            AppSectionHeader(text = "自定义包名安装")
            Card(
                modifier = Modifier.padding(horizontal = Spacing.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        OutlinedTextField(
                            value = customInstallInput,
                            onValueChange = { customInstallInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            placeholder = { Text("包名空格分隔，如 htop neofetch openssh") }
                        )
                        Button(
                            onClick = {
                                viewModel.installCustom(customInstallInput)
                                customInstallInput = ""
                            },
                            enabled = containerInstalled && customInstallInput.isNotBlank() && customInstallState == null
                        ) {
                            Icon(FeatherIcons.Plus, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("安装")
                        }
                    }
                    if (customInstallState is BundleInstallState.Installing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "正在安装：${customInstallState.line?.take(40) ?: "准备中…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (customInstallState is BundleInstallState.Failed) {
                        Text(
                            text = "安装失败：${(customInstallState as BundleInstallState.Failed).reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE57373)
                        )
                    }
                    if (!containerInstalled) {
                        Text(
                            text = "容器未初始化，暂不可安装自定义包",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AppSectionHeader(text = "已安装自定义包")
            Card(
                modifier = Modifier
                    .padding(horizontal = Spacing.md)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            FeatherIcons.Grid,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = if (customPkgs.isEmpty()) "暂无自定义包" else "共 ${customPkgs.size} 个",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::refreshCustom) {
                            Icon(FeatherIcons.RefreshCw, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("刷新", fontSize = 12.sp)
                        }
                    }
                    when {
                        !containerInstalled -> {
                            Text(
                                "容器未初始化，无法查询自定义包",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        customPkgs.isEmpty() -> {
                            Text(
                                "可以从上面的快捷包一键安装，或自己输入 apk 包名。常用：htop / neofetch / tmux / fzf / rsync / lf / openssh",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                customPkgs.forEach { pkg ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFF81C784), shape = RoundedCornerShape(50))
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.sm))
                                        Text(
                                            text = pkg,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { viewModel.uninstallCustom(pkg) },
                                            enabled = customInstallState == null
                                        ) {
                                            Icon(FeatherIcons.Trash2, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("卸载", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomContainerSummaryCard(
    containerInstalled: Boolean,
    initProgress: ContainerInitState,
    customInstallState: BundleInstallState?,
    onInit: () -> Unit
) {
    val statusText = when (initProgress) {
        is ContainerInitState.Idle -> "未初始化"
        is ContainerInitState.ExtractingRootfs -> "正在解压 rootfs…"
        ContainerInitState.DeployingProot -> "正在部署 proot…"
        is ContainerInitState.Ready -> {
            if (customInstallState is BundleInstallState.Installing) "已就绪（正在安装自定义包…）"
            else if (customInstallState is BundleInstallState.Uninstalling) "已就绪（正在卸载自定义包…）"
            else "已就绪"
        }
        is ContainerInitState.BundleInstalling -> "已就绪（正在安装功能包…）"
        is ContainerInitState.BundleUninstalling -> "已就绪（正在卸载功能包…）"
        is ContainerInitState.Failed -> "失败：${initProgress.reason.take(20)}…"
    }
    val dotColor: Color = when {
        !containerInstalled -> Color(0xFFE57373)
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
                Box(
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
private fun QuickPacksChipRow(
    containerReady: Boolean,
    customInstallState: BundleInstallState?,
    installed: Set<String>,
    onInstall: (List<String>) -> Unit
) {
    val quickPacks = remember {
        listOf(
            "htop" to listOf("htop"),
            "neofetch" to listOf("neofetch"),
            "openssh" to listOf("openssh", "openssh-client", "openssh-server"),
            "rsync" to listOf("rsync"),
            "fzf" to listOf("fzf"),
            "tmux" to listOf("tmux"),
            "lf 文件管理器" to listOf("lf"),
            "zsh" to listOf("zsh", "zsh-vcs")
        )
    }
    val processing = customInstallState is BundleInstallState.Installing || customInstallState is BundleInstallState.Uninstalling
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z1),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                "一键安装 Alpine 社区常用包。安装后会出现在「已安装自定义包」列表里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                quickPacks.forEach { (label, pkgs) ->
                    val already = pkgs.all { it in installed }
                    androidx.compose.material3.AssistChip(
                        onClick = { onInstall(pkgs) },
                        enabled = containerReady && !processing && !already,
                        leadingIcon = {
                            if (already) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            } else if (processing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(FeatherIcons.Plus, null, modifier = Modifier.size(16.dp))
                            }
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}
