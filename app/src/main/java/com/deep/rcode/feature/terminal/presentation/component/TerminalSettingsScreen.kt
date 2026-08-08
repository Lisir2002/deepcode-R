package com.deep.rcode.feature.terminal.presentation.component

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.R
import com.deep.rcode.core.theme.AppSectionGroup
import com.deep.rcode.core.theme.AppSectionHeader
import com.deep.rcode.core.theme.AppTopAppBar
import com.deep.rcode.core.theme.Elevation
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundle
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.data.repository.SshHeartbeatSeconds
import com.deep.rcode.feature.terminal.data.repository.TerminalFontSizes
import com.deep.rcode.feature.terminal.data.repository.TerminalTheme
import com.deep.rcode.feature.terminal.presentation.TerminalSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Grid
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Server
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.launch

/**
 * 终端设置页。包含：
 *  - 容器环境大卡片
 *  - 子页入口：功能包管理 / 自定义 APK 包（各跳独立子页面）
 *  - 4 分组开关：外观 / 键盘交互 / 行为 / SSH 常用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    viewModel: TerminalSettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSshHosts: () -> Unit,
    onNavigateToBundleManager: () -> Unit = {},
    onNavigateToCustomPackages: () -> Unit = {}
) {
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val containerInstalled by viewModel.containerInstalled.collectAsStateWithLifecycle()
    val storageUsedMb by viewModel.storageUsedMb.collectAsStateWithLifecycle()
    val bundleStates by viewModel.bundleStates.collectAsStateWithLifecycle()
    val customPkgs by viewModel.customPackages.collectAsStateWithLifecycle()
    val aiAllInstalled by viewModel.aiRecommendedAllInstalled.collectAsStateWithLifecycle()

    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val theme by viewModel.terminalTheme.collectAsStateWithLifecycle()
    val showTabBar by viewModel.showTabBar.collectAsStateWithLifecycle()

    val fullExtraKeys by viewModel.fullExtraKeys.collectAsStateWithLifecycle()
    val scalePersists by viewModel.scaleGesturePersists.collectAsStateWithLifecycle()
    val autoPopIme by viewModel.autoPopImeOnSwitch.collectAsStateWithLifecycle()

    val newOutputIndicator by viewModel.newOutputIndicator.collectAsStateWithLifecycle()
    val autoNewTabOnCloseLast by viewModel.autoNewTabOnCloseLast.collectAsStateWithLifecycle()
    val keepSession by viewModel.keepSessionWhenLeave.collectAsStateWithLifecycle()
    val pasteAsPlain by viewModel.pasteAsPlainText.collectAsStateWithLifecycle()

    val sshAutoReconnect by viewModel.sshAutoReconnect.collectAsStateWithLifecycle()
    val sshHeartbeat by viewModel.sshHeartbeatSeconds.collectAsStateWithLifecycle()
    val sshKeepalive by viewModel.sshKeepalive.collectAsStateWithLifecycle()

    val errorToast by viewModel.errorToast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(errorToast) {
        errorToast?.let {
            scope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long) }
            viewModel.consumeErrorToast()
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showMirrorPicker by remember { mutableStateOf(false) }
    var showHeartbeatPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.settings_terminal),
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
                mode = ContainerCardMode.FULL,
                onInit = viewModel::ensureContainerInstalled,
                onReset = { showResetConfirm = true },
                onPickMirror = { showMirrorPicker = true }
            )

            AppSectionHeader(text = "容器与依赖")
            AppSectionGroup {
                val installedBundleCount = bundleStates.count { it.value is BundleInstallState.Installed }
                _MenuRow(
                    icon = FeatherIcons.Box,
                    title = "功能包管理",
                    subtitle = "官方 Bundle · 共 ${viewModel.bundles().size} 个，已安装 $installedBundleCount${if (aiAllInstalled) " · AI 组合已就绪" else ""}",
                    onClick = onNavigateToBundleManager,
                    showDivider = true
                )
                _MenuRow(
                    icon = FeatherIcons.Grid,
                    title = "自定义 APK 包",
                    subtitle = if (customPkgs.isEmpty()) "安装 Alpine 社区任意 apk 包（htop / neofetch / tmux 等）"
                    else "已安装 ${customPkgs.size} 个自定义包：${customPkgs.take(4).joinToString(" ")}${if (customPkgs.size > 4) "…" else ""}",
                    onClick = onNavigateToCustomPackages,
                    showDivider = false
                )
            }

            // G1 外观
            AppSectionHeader(text = stringResource(R.string.settings_category_appearance))
            AppSectionGroup {
                _StepperRow(
                    icon = FeatherIcons.Type,
                    title = "终端字号",
                    subtitle = "${fontSizeSp} sp（推荐 11-14 sp）",
                    showDivider = true,
                    onDecrease = {
                        val steps = TerminalFontSizes.STEPS
                        val idx = steps.binarySearch(fontSizeSp).let { if (it < 0) -it - 1 else it }
                        if (idx - 1 >= 0) viewModel.setFontSizeSp(steps[idx - 1])
                    },
                    onIncrease = {
                        val steps = TerminalFontSizes.STEPS
                        val idx = steps.binarySearch(fontSizeSp).let { if (it < 0) -it - 1 else it }
                        if (idx + 1 <= steps.lastIndex) viewModel.setFontSizeSp(steps[idx + 1])
                    }
                )
                _MenuRow(
                    icon = FeatherIcons.Moon,
                    title = "终端内容配色",
                    subtitle = when (theme) {
                        TerminalTheme.SYSTEM -> "跟随系统（暗/亮自动切换）"
                        TerminalTheme.PURE_BLACK -> "黑底白字（对比最强）"
                        TerminalTheme.PURE_WHITE -> "白底黑字（对比最强）"
                    },
                    onClick = { showThemePicker = true },
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Grid,
                    title = "显示 Tab 栏",
                    subtitle = "关闭后多标签完全靠键盘切换",
                    checked = showTabBar,
                    onCheckedChange = viewModel::setShowTabBar,
                    showDivider = false
                )
            }

            // G2 键盘 & 交互
            AppSectionHeader(text = "键盘 & 交互")
            AppSectionGroup {
                _SwitchRow(
                    icon = FeatherIcons.Grid,
                    title = "完整功能键行",
                    subtitle = "关闭为精简布局（仅 Ctrl/Alt/Fn/方向）",
                    checked = fullExtraKeys,
                    onCheckedChange = viewModel::setFullExtraKeys,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.RefreshCw,
                    title = "缩放手势持久化字号",
                    subtitle = "关闭时双指缩放只临时改变显示，不记忆",
                    checked = scalePersists,
                    onCheckedChange = viewModel::setScaleGesturePersists,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Terminal,
                    title = "切标签自动弹键盘",
                    subtitle = "关闭时切换后需手动点屏幕唤起键盘",
                    checked = autoPopIme,
                    onCheckedChange = viewModel::setAutoPopImeOnSwitch,
                    showDivider = true
                )
                _MenuRow(
                    icon = FeatherIcons.Settings,
                    title = "重置 Ctrl 使用提示",
                    subtitle = "下次点 Ctrl 按钮时再显示首次气泡",
                    onClick = viewModel::resetCtrlHint,
                    showDivider = false
                )
            }

            // G3 行为
            AppSectionHeader(text = "行为")
            AppSectionGroup {
                _SwitchRow(
                    icon = FeatherIcons.XCircle,
                    title = "后台标签新输出显示红点",
                    subtitle = "非当前 Tab 有新输出时右上角红点",
                    checked = newOutputIndicator,
                    onCheckedChange = viewModel::setNewOutputIndicator,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Plus,
                    title = "关闭最后一标签自动新建",
                    subtitle = "保证总有一个可交互标签",
                    checked = autoNewTabOnCloseLast,
                    onCheckedChange = viewModel::setAutoNewTabOnCloseLast,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Server,
                    title = "离开终端页保持会话",
                    subtitle = "关闭则每次离开后台会话会销毁",
                    checked = keepSession,
                    onCheckedChange = viewModel::setKeepSessionWhenLeave,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Database,
                    title = "粘贴为纯文本",
                    subtitle = "粘贴时自动剥掉富文本格式",
                    checked = pasteAsPlain,
                    onCheckedChange = viewModel::setPasteAsPlainText,
                    showDivider = false
                )
            }

            // G4 SSH 常用
            AppSectionHeader(text = "SSH 远程终端")
            AppSectionGroup {
                _SwitchRow(
                    icon = FeatherIcons.RefreshCw,
                    title = "断线自动重连",
                    subtitle = "网络切换/恢复时自动重新连接",
                    checked = sshAutoReconnect,
                    onCheckedChange = viewModel::setSshAutoReconnect,
                    showDivider = true
                )
                _SwitchRow(
                    icon = FeatherIcons.Server,
                    title = "TCP KeepAlive",
                    subtitle = "定期发心跳包避免连接被运营商静默中断",
                    checked = sshKeepalive,
                    onCheckedChange = viewModel::setSshKeepalive,
                    showDivider = true
                )
                _MenuRow(
                    icon = FeatherIcons.Cpu,
                    title = "心跳间隔",
                    subtitle = SshHeartbeatSeconds.fromSeconds(sshHeartbeat).display,
                    onClick = { showHeartbeatPicker = true },
                    showDivider = true
                )
                _MenuRow(
                    icon = FeatherIcons.Server,
                    title = "管理 SSH 主机配置",
                    subtitle = "新增/编辑/删除 SSH 主机和密钥",
                    onClick = onNavigateToSshHosts,
                    showDivider = false
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置容器？") },
            text = {
                Text(
                    "会物理删除 rootfs 和 proot，所有已安装的 Bundle 和自定义包都会丢失。\n" +
                        "app 配置（本页所有开关、SSH 主机、凭据、聊天历史）不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetContainer { showResetConfirm = false }
                }) {
                    Text("确认重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showMirrorPicker) {
        MirrorPickerDialog(
            current = com.deep.rcode.feature.agent.domain.container.ContainerInstaller.ALPINE_MIRROR,
            onDismiss = { showMirrorPicker = false },
            onConfirm = { mirror ->
                viewModel.setMirrorAndRefresh(mirror)
                showMirrorPicker = false
            }
        )
    }

    if (showThemePicker) {
        ThemePickerDialog(
            current = theme,
            onDismiss = { showThemePicker = false },
            onConfirm = { t ->
                viewModel.setTheme(t)
                showThemePicker = false
            }
        )
    }

    if (showHeartbeatPicker) {
        HeartbeatPickerDialog(
            current = sshHeartbeat,
            onDismiss = { showHeartbeatPicker = false },
            onConfirm = { s ->
                viewModel.setSshHeartbeatSeconds(s)
                showHeartbeatPicker = false
            }
        )
    }
}

// ================================================================
// 本地小型行组件：外观对齐 SettingsScreen 内部 GroupMenuRow / GroupSwitchRow
// ================================================================
@Composable
private fun _MenuRow(
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

@Composable
private fun _SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun _StepperRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    showDivider: Boolean = true
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecrease, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Remove, null, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onIncrease, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ================================================================
// Dialogs
// ================================================================
@Composable
private fun MirrorPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val options = remember {
        listOf(
            "阿里云镜像（默认）" to "https://mirrors.aliyun.com/alpine",
            "清华 TUNA" to "https://mirrors.tuna.tsinghua.edu.cn/alpine",
            "中科大 USTC" to "https://mirrors.ustc.edu.cn/alpine",
            "官方 dl-cdn" to "https://dl-cdn.alpinelinux.org/alpine",
            "自定义（保留当前）" to current
        )
    }
    var selected by remember {
        mutableStateOf(options.firstOrNull { it.second == current }?.first ?: options[0].first)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 apk 镜像源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                options.forEach { (label, url) ->
                    FilterChip(
                        selected = label == selected,
                        onClick = { selected = label },
                        label = {
                            Column {
                                Text(label)
                                Text(
                                    url,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options.first { it.first == selected }.second) }) {
                Text("确认")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ThemePickerDialog(
    current: TerminalTheme,
    onDismiss: () -> Unit,
    onConfirm: (TerminalTheme) -> Unit
) {
    val options = listOf(
        TerminalTheme.SYSTEM to "跟随系统（暗/亮自动切换）",
        TerminalTheme.PURE_BLACK to "黑底白字 · 对比最强",
        TerminalTheme.PURE_WHITE to "白底黑字 · 对比最强"
    )
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("终端内容配色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                options.forEach { (v, label) ->
                    FilterChip(
                        selected = selected == v,
                        onClick = { selected = v },
                        label = { Text(label) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun HeartbeatPickerDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val options = SshHeartbeatSeconds.entries
    var selected by remember { mutableStateOf(SshHeartbeatSeconds.fromSeconds(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 心跳间隔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                options.forEach { o ->
                    FilterChip(
                        selected = selected == o,
                        onClick = { selected = o },
                        label = { Text(o.display) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.seconds) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
