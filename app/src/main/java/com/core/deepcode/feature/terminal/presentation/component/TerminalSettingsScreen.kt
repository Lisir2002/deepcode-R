package com.core.deepcode.feature.terminal.presentation.component

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ZoomIn
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
import com.core.deepcode.R
import com.core.deepcode.core.theme.AppSectionGroup
import com.core.deepcode.core.theme.AppSectionHeader
import com.core.deepcode.core.theme.AppTopAppBar
import com.core.deepcode.core.theme.Elevation
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.domain.container.ContainerInitState
import com.core.deepcode.feature.terminal.data.bundle.BundleInstallState
import com.core.deepcode.feature.terminal.data.bundle.TerminalBundle
import com.core.deepcode.feature.terminal.data.bundle.TerminalBundleId
import com.core.deepcode.feature.terminal.data.repository.SshHeartbeatSeconds
import com.core.deepcode.feature.terminal.data.repository.TerminalFontSizes
import com.core.deepcode.feature.terminal.data.repository.TerminalTheme
import com.core.deepcode.feature.terminal.presentation.TerminalSettingsViewModel
import kotlinx.coroutines.launch

/**
 * 终端设置页。包含：
 *  - 容器环境大卡片
 *  - 子页入口：功能包管理（含预设独立功能包 + 自定义功能包两个 Tab）
 *  - 4 分组开关：外观 / 键盘交互 / 行为 / SSH 常用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    viewModel: TerminalSettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSshHosts: () -> Unit,
    onNavigateToBundleManager: () -> Unit = {}
) {
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val containerInstalled by viewModel.containerInstalled.collectAsStateWithLifecycle()
    val storageUsedMb by viewModel.storageUsedMb.collectAsStateWithLifecycle()
    val activeProfileArch by viewModel.activeProfileArch.collectAsStateWithLifecycle()
    val bundleStates by viewModel.bundleStates.collectAsStateWithLifecycle()
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
                navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
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
                profileArch = activeProfileArch,
                mode = ContainerCardMode.FULL,
                onInit = viewModel::ensureContainerInstalled,
                onReset = { showResetConfirm = true },
                onPickMirror = { showMirrorPicker = true }
            )

            AppSectionHeader(text = stringResource(R.string.ui_______b37d4c61))
            AppSectionGroup {
                val installedBundleCount = bundleStates.count { it.value is BundleInstallState.Installed }
                _MenuRow(
                    icon = Icons.Rounded.Inventory2,
                    title = stringResource(R.string.ui_______dbda4e51_2),
                    subtitle = "官方 Bundle · 共 ${viewModel.bundles().size} 个，已安装 $installedBundleCount${if (aiAllInstalled) " · AI 组合已就绪" else ""}",
                    onClick = onNavigateToBundleManager,
                    showDivider = false
                )
            }

            // G1 外观
            AppSectionHeader(text = stringResource(R.string.settings_category_appearance))
            AppSectionGroup {
                _StepperRow(
                    icon = Icons.Rounded.TextFields,
                    title = stringResource(R.string.ui______58a3ff82),
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
                    icon = Icons.Rounded.DarkMode,
                    title = stringResource(R.string.ui________48cee970),
                    subtitle = when (theme) {
                        TerminalTheme.FOLLOW_APP -> stringResource(R.string.ui______40b081ab)
                        TerminalTheme.PURE_BLACK -> stringResource(R.string.ui______f5242d83)
                        TerminalTheme.PURE_WHITE -> stringResource(R.string.ui______c68108f0)
                    },
                    onClick = { showThemePicker = true },
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.ViewColumn,
                    title = stringResource(R.string.ui____caa23c17),
                    subtitle = stringResource(R.string.ui______________aeee5eb0),
                    checked = showTabBar,
                    onCheckedChange = viewModel::setShowTabBar,
                    showDivider = false
                )
            }

            // G2 键盘 & 交互
            AppSectionHeader(text = stringResource(R.string.ui____e1dd53a4))
            AppSectionGroup {
                _SwitchRow(
                    icon = Icons.Rounded.Dashboard,
                    title = stringResource(R.string.ui________bd4b33a0),
                    subtitle = "关闭为精简布局（仅 Ctrl/Alt/Fn/方向）",
                    checked = fullExtraKeys,
                    onCheckedChange = viewModel::setFullExtraKeys,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.ZoomIn,
                    title = stringResource(R.string.ui___________19a36c2d),
                    subtitle = stringResource(R.string.ui______________82f9dac5),
                    checked = scalePersists,
                    onCheckedChange = viewModel::setScaleGesturePersists,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.Terminal,
                    title = stringResource(R.string.ui__________a6cbf757),
                    subtitle = stringResource(R.string.ui______________05546ade),
                    checked = autoPopIme,
                    onCheckedChange = viewModel::setAutoPopImeOnSwitch,
                    showDivider = true
                )
                _MenuRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.ui____2959acd6),
                    subtitle = stringResource(R.string.ui_____18a3cbe4),
                    onClick = viewModel::resetCtrlHint,
                    showDivider = false
                )
            }

            // G3 行为
            AppSectionHeader(text = stringResource(R.string.ui____a0496123))
            AppSectionGroup {
                _SwitchRow(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.ui_____________996140c5),
                    subtitle = stringResource(R.string.ui_____46f74d41),
                    checked = newOutputIndicator,
                    onCheckedChange = viewModel::setNewOutputIndicator,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.ui_____________0184da1b),
                    subtitle = stringResource(R.string.ui_____________73b957c1),
                    checked = autoNewTabOnCloseLast,
                    onCheckedChange = viewModel::setAutoNewTabOnCloseLast,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.Archive,
                    title = stringResource(R.string.ui___________37172e85),
                    subtitle = stringResource(R.string.ui______________427e9b33),
                    checked = keepSession,
                    onCheckedChange = viewModel::setKeepSessionWhenLeave,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.ContentPaste,
                    title = stringResource(R.string.ui________52007b14),
                    subtitle = stringResource(R.string.ui______________eb6dcb95),
                    checked = pasteAsPlain,
                    onCheckedChange = viewModel::setPasteAsPlainText,
                    showDivider = false
                )
            }

            // G4 SSH 常用
            AppSectionHeader(text = stringResource(R.string.ui_ssh_ac7515bd))
            AppSectionGroup {
                _SwitchRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.ui________66d1f9aa),
                    subtitle = stringResource(R.string.ui______f096b834),
                    checked = sshAutoReconnect,
                    onCheckedChange = viewModel::setSshAutoReconnect,
                    showDivider = true
                )
                _SwitchRow(
                    icon = Icons.Rounded.Dns,
                    title = "TCP KeepAlive",
                    subtitle = stringResource(R.string.ui______________8a0ba94d),
                    checked = sshKeepalive,
                    onCheckedChange = viewModel::setSshKeepalive,
                    showDivider = true
                )
                _MenuRow(
                    icon = Icons.Rounded.MonitorHeart,
                    title = stringResource(R.string.ui______9901e9b6),
                    subtitle = SshHeartbeatSeconds.fromSeconds(sshHeartbeat).display,
                    onClick = { showHeartbeatPicker = true },
                    showDivider = true
                )
                _MenuRow(
                    icon = Icons.Rounded.Dns,
                    title = stringResource(R.string.ui____9c828e97),
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
            title = { Text(stringResource(R.string.ui______8415b836)) },
            text = {
                Text(
                    stringResource(R.string.ui_______f68e836c) +
                        stringResource(R.string.ui_app_d6e3250f)
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
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.ui____625fb26b_3)) }
            }
        )
    }

    if (showMirrorPicker) {
        MirrorPickerDialog(
            current = com.core.deepcode.feature.agent.domain.container.ContainerInstaller.ALPINE_MIRROR,
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
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
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
        title = { Text(stringResource(R.string.ui____1d3311a8)) },
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
                Text(stringResource(R.string.ui____e83a256e))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui____625fb26b_4)) } }
    )
}

@Composable
private fun ThemePickerDialog(
    current: TerminalTheme,
    onDismiss: () -> Unit,
    onConfirm: (TerminalTheme) -> Unit
) {
    val options = listOf(
        TerminalTheme.FOLLOW_APP to stringResource(R.string.ui______40b081ab_2),
        TerminalTheme.PURE_BLACK to stringResource(R.string.ui______27771d06),
        TerminalTheme.PURE_WHITE to stringResource(R.string.ui______909e0e94)
    )
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui________48cee970_2)) },
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
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.ui____e83a256e_2)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui____625fb26b_5)) } }
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
        title = { Text(stringResource(R.string.ui_ssh_1fd07b48)) },
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
        confirmButton = { TextButton(onClick = { onConfirm(selected.seconds) }) { Text(stringResource(R.string.ui____e83a256e_3)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui____625fb26b_6)) } }
    )
}
