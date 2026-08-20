package com.R.codecore.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppEmptyState
import com.R.codecore.core.theme.AppLoadingState
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Elevation
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.container.ContainerInitState
import com.R.codecore.feature.terminal.data.repository.TerminalFontSizes
import com.R.codecore.feature.terminal.domain.RunState
import com.R.codecore.feature.terminal.domain.TabColorMarker
import com.R.codecore.feature.terminal.domain.TerminalTab
import com.R.codecore.feature.terminal.presentation.TerminalViewModel
import com.termux.view.TerminalView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Grid
import compose.icons.feathericons.Menu
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onSendToAI: ((tailOutput: String) -> Unit)? = null
) {
    val prepareState by viewModel.prepareState.collectAsStateWithLifecycle()
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val fullExtraKeys by viewModel.fullExtraKeys.collectAsStateWithLifecycle()
    val errorToast by viewModel.errorToast.collectAsStateWithLifecycle()
    val hasNewOutputMap by viewModel.hasNewOutputFlow.collectAsState(initial = emptyMap())

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // errorToast → Snackbar
    LaunchedEffect(errorToast) {
        errorToast?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            }
            viewModel.consumeErrorToast()
        }
    }

    // 终端操作弹窗状态
    var showReconnectMenu by remember { mutableStateOf(false) }
    var showTerminalMenu by remember { mutableStateOf<TerminalMenuAnchor?>(null) }
    var showTabLongPressMenu by remember { mutableStateOf<TerminalTab?>(null) }
    var renameDialogForTab by remember { mutableStateOf<TerminalTab?>(null) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var ctrlHintVisible by remember { mutableStateOf(true) }
    val ctrlHintShown by viewModel.ctrlHintShown.collectAsStateWithLifecycle()
    val confirmAction by viewModel.confirmAction.collectAsStateWithLifecycle()

    // 终端颜色：当前主题调色板 + 壳 UI 皮肤适配器（Tab/Banner/Keys 全部从这里取色）
    val palette = rememberTerminalPalette()
    val skin = rememberTerminalSkin(palette)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.terminal_title),
                onNavigateBack = onNavigateBack,
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back)
            ) {
                // 重连下拉：当前 / 全部 / 重启容器
                Box {
                    IconButton(onClick = { showReconnectMenu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.terminal_reconnect_tab),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    ReconnectDropdown(
                        expanded = showReconnectMenu,
                        onDismiss = { showReconnectMenu = false },
                        onReconnectActive = { showReconnectMenu = false; viewModel.reconnectActive() },
                        onReconnectAll = { showReconnectMenu = false; viewModel.requestReconnectAll() },
                        onRestartContainer = { showReconnectMenu = false; viewModel.requestRestartContainer() }
                    )
                }
                // 终端设置（齿轮 → 跳转终端设置页）
                IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(40.dp)) {
                    Icon(
                        FeatherIcons.Settings,
                        contentDescription = stringResource(R.string.settings_terminal),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // 终端设置/菜单（主菜单入口，点击后弹出终端级菜单；对 TabChip 长按也会弹）
                Box {
                    IconButton(onClick = { showTerminalMenu = TerminalMenuAnchor.Bar }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            FeatherIcons.MoreVertical,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TerminalOperationsMenu(
                        anchor = showTerminalMenu,
                        onDismiss = { showTerminalMenu = null },
                        onCopy = { showTerminalMenu = null; sendCopyToClipboard(tabs, activeTabId) },
                        onPaste = { showTerminalMenu = null; sendPasteFromClipboard(tabs, activeTabId, viewModel) },
                        onSelectAll = { showTerminalMenu = null; performSelectAll(tabs, activeTabId) },
                        onClearScreen = { showTerminalMenu = null; performClearScreen(tabs, activeTabId, viewModel) },
                        onSearch = { showTerminalMenu = null; showSearchOverlay = true },
                        onRenameTab = {
                            showTerminalMenu = null
                            renameDialogForTab = tabs.firstOrNull { it.id == activeTabId }
                        },
                        onSendToAI = onSendToAI?.let { callback ->
                            {
                                showTerminalMenu = null
                                tabs.firstOrNull { it.id == activeTabId }
                                    ?.session?.emulator?.screen?.transcriptText
                                    ?.trimEnd('\n')?.lines()?.takeLast(100)?.joinToString("\n")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { t -> callback(t) }
                            }
                        },
                        onToggleFullKeys = { showTerminalMenu = null; viewModel.toggleFullExtraKeys() },
                        onFontSizeStepUp = { showTerminalMenu = null; viewModel.setFontSizeSp(stepFont(fontSizeSp, +1)) },
                        onFontSizeStepDown = { showTerminalMenu = null; viewModel.setFontSizeSp(stepFont(fontSizeSp, -1)) },
                        fullExtraKeys = fullExtraKeys,
                        allowSendToAI = onSendToAI != null
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = rememberImeBottomInsetForTerminal())
        ) {
            when (val state = prepareState) {
                is TerminalViewModel.PrepareState.Loading -> AppLoadingState(
                    loadingText = containerInitMessage(LocalContext.current, containerInit)
                )
                is TerminalViewModel.PrepareState.Error -> AppEmptyState(
                    title = stringResource(R.string.terminal_start_failed, state.message),
                    actionLabel = stringResource(R.string.terminal_retry),
                    onAction = { viewModel.prepare() }
                )
                is TerminalViewModel.PrepareState.Ready -> {
                    @Suppress("UNUSED_EXPRESSION") revision

                    // ========== 布局分层（按你的规则） ==========
                    // 1) Tab 以上（Banner + 周围空白）→ 完全跟随程序主题（Scaffold surface）
                    //    不管终端内容选了「白底黑字」，这里不会被终端底色污染。
                    // 2) Tab 及以下（TabBar / 细分隔线 / TerminalSurface 内容区 /
                    //    Tab→Terminal 的交界）→ 用 palette.containerBg 统一包裹，
                    //    这一部分才会尊重终端配色三档（跟随程序/黑底/白底）；
                    //    TabBar 属于外壳 UI，它的颜色走 skin.terminalSurface
                    //    （本来就跟随程序 MaterialTheme），但它放在 palette 容器里
                    //    是为了避免 Tab↔Terminal 缝里漏白条。
                    val banner by viewModel.currentBanner.collectAsStateWithLifecycle()
                    banner?.let { b ->
                        TerminalFirstRunBanner(
                            banner = b,
                            skin = skin,
                            onGoSettings = onNavigateToSettings,
                            onInstallRecommended = { viewModel.installAiRecommended() },
                            onInitContainer = { viewModel.prepare() },
                            onDismiss = viewModel::dismissFirstRunBanner
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(palette.containerBg)
                    ) {
                        TabBar(
                            skin = skin,
                            tabs = tabs,
                            activeTabId = activeTabId,
                            hasNewOutputMap = hasNewOutputMap,
                            onSelect = viewModel::activate,
                            onClose = viewModel::closeTab,
                            onNew = { viewModel.newTab() },
                            onTabLongPress = { tab -> showTabLongPressMenu = tab }
                        )
                        // TabBar 与终端内容区之间：仅 1dp 细分隔线，不再留 8dp 空白（消除白条）
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(TerminalLayout.tabTerminalDivider)
                                .background(skin.dividingLine)
                        )

                        Box(modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                        ) {
                            val active = tabs.firstOrNull { it.id == activeTabId }
                            if (active == null) {
                                AppEmptyState(
                                    title = stringResource(R.string.terminal_no_open_tabs),
                                    actionLabel = stringResource(R.string.common_new_tab),
                                    onAction = { viewModel.newTab() }
                                )
                            } else {
                                androidx.compose.runtime.key(active.id) {
                                    TerminalSurface(
                                        tab = active,
                                        viewModel = viewModel,
                                        fontSizeSp = fontSizeSp
                                    )
                                }
                            }
                            // 搜索浮层（BoxScope 扩展函数，可在此调用 .align）
                            if (showSearchOverlay) {
                                with(this@Box as androidx.compose.foundation.layout.BoxScope) {
                                    TerminalSearchOverlay(
                                        onDismiss = { showSearchOverlay = false },
                                        tabs = tabs,
                                        activeTabId = activeTabId
                                    )
                                }
                            }
                            // Ctrl 首次提示气泡（仅 Ctrl 按钮首次启用时显示）
                            if (ctrlHintVisible && !ctrlHintShown && viewModel.modifiers.ctrl) {
                                CtrlHintBubble(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    onGotIt = {
                                        ctrlHintVisible = false
                                        viewModel.markCtrlHintShown()
                                    }
                                )
                            }
                        }
                    }

                    if (activeTabId != null) {
                        ExtraKeysRow(
                            viewModel = viewModel,
                            full = fullExtraKeys,
                            skin = skin
                        )
                    }
                }
            }
        }
    }

    // 标签长按弹窗
    showTabLongPressMenu?.let { tab ->
        TabLongPressDialog(
            tab = tab,
            skin = skin,
            onDismiss = { showTabLongPressMenu = null },
            onRename = {
                renameDialogForTab = tab
                showTabLongPressMenu = null
            },
            onClose = {
                viewModel.requestCloseTab(tab.id)
                showTabLongPressMenu = null
            },
            onCloseOthers = {
                viewModel.requestCloseOtherTabs(tab.id)
                showTabLongPressMenu = null
            },
            onTogglePin = { viewModel.togglePin(tab.id) },
            onSetColorMarker = { marker -> viewModel.setColorMarker(tab.id, marker) }
        )
    }

    // 重命名输入弹窗
    renameDialogForTab?.let { tab ->
        RenameTabDialog(
            initial = tab.title,
            onDismiss = { renameDialogForTab = null },
            onConfirm = { newTitle ->
                viewModel.renameTab(tab.id, newTitle)
                renameDialogForTab = null
            }
        )
    }

    // 二次确认弹窗
    confirmAction?.let { action ->
        ConfirmActionDialog(
            action = action,
            onConfirm = {
                viewModel.executeConfirmedAction(action)
            },
            onDismiss = {
                viewModel.consumeConfirmAction()
            }
        )
    }
}

/** 通过 LocalComposition 向 rememberImeBottomInset 提供 Activity 上下文；避免每次导入。 */
@Composable
private fun rememberImeBottomInsetForTerminal() =
    com.R.codecore.core.ui.rememberImeBottomInset()

private fun stepFont(current: Int, direction: Int): Int {
    val steps = TerminalFontSizes.STEPS
    val idx = steps.binarySearch(current).let { if (it < 0) -it - 1 else it }
    return steps[(idx + direction).coerceIn(0, steps.lastIndex)]
}

// ── 工具：直接操作 clipboard / TerminalView（通过 Tab.view） ────────────────
private fun sendCopyToClipboard(tabs: List<TerminalTab>, activeId: String?) {
    val tab = tabs.firstOrNull { it.id == activeId } ?: return
    val view = tab.view
    // Termux 没有公开 selection/copyCurrentSelection API；兜底：取 transcript 最后 200 行。
    // 如果视图正在文本选择模式（isSelectingText），尽量不打断用户——但缺 API 无法精确取选中文本。
    val ctx = view?.context ?: return
    val transcript = tab.session.emulator?.screen?.transcriptText
    // 优先：有正在选择的（不可读，退化成整屏可见区）；否则：最后 200 行
    val text: String = if (view.isSelectingText) {
        // 缺 API，取可见区（屏幕底部 activeRows 行）
        transcript?.lines()?.takeLast(tab.session.emulator?.screen?.activeRows ?: 50)
            ?.joinToString("\n")
    } else {
        transcript?.lines()?.takeLast(200)?.joinToString("\n")
    } ?: return
    if (text.isBlank()) return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
}

private fun sendPasteFromClipboard(tabs: List<TerminalTab>, activeId: String?, vm: TerminalViewModel) {
    val tab = tabs.firstOrNull { it.id == activeId } ?: return
    val ctx = tab.view?.context ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() } ?: return
    vm.write(text)
}

private fun performSelectAll(tabs: List<TerminalTab>, activeId: String?) {
    val view = tabs.firstOrNull { it.id == activeId }?.view ?: return
    // 缺 setSelection 公开 API：触发「进入文本选择模式」作为语义等价。
    // 从 (0, 0) 附近的虚拟坐标触发 startTextSelectionMode；实际位置仅用于光标渲染。
    runCatching {
        val e = android.view.MotionEvent.obtain(
            android.os.SystemClock.uptimeMillis(),
            android.os.SystemClock.uptimeMillis(),
            android.view.MotionEvent.ACTION_DOWN,
            10f, 10f, 0
        )
        view.startTextSelectionMode(e)
        e.recycle()
    }
}

private fun performClearScreen(tabs: List<TerminalTab>, activeId: String?, vm: TerminalViewModel) {
    // 用 Ctrl-L（0x0C）：对大多数 shell 及 curses 应用，等价于清屏；同时清除 scrollback 思路靠命令侧。
    vm.writeBytes(0x0C)
}

// ================================================================
// Banner：容器未初始化 / Python 未装 首屏提示
//  统一使用 TerminalLayout / SemanticColors / Shared* 按钮组件规格
// ================================================================
@Composable
private fun TerminalFirstRunBanner(
    banner: TerminalViewModel.BannerType,
    skin: TerminalSkinSnapshot,
    onGoSettings: () -> Unit,
    onInstallRecommended: () -> Unit,
    onInitContainer: () -> Unit,
    onDismiss: () -> Unit
) {
    val accentColor: Color = when (banner) {
        TerminalViewModel.BannerType.ContainerNotInstalled -> skin.semanticWarning
        TerminalViewModel.BannerType.PythonMissing -> skin.semanticInfo
    }
    val title: String
    val desc: String
    val showDismiss: Boolean
    when (banner) {
        TerminalViewModel.BannerType.ContainerNotInstalled -> {
            title = stringResource(R.string.ui___________aac0fde9)
            desc = "容器未就绪时仅可使用手机原生 shell（命令较少）。\n点下方「初始化环境」解锁 Alpine Linux + PRoot 以及 Python / Node / Git 等 6 大功能包。"
            showDismiss = false
        }
        TerminalViewModel.BannerType.PythonMissing -> {
            title = stringResource(R.string.ui_ai_8049975d)
            desc = "AI 的「Run Code / Search / git diff」依赖 Python 运行时。建议先一键安装 AI 推荐组合。"
            showDismiss = true
        }
    }

    // Tab 以上的「提示框」完全属于外壳 UI：
    //  ① 卡片背景 = MaterialTheme.colorScheme.primaryContainer（程序主题）
    //  ② 文字 = onPrimaryContainer（自动 WCAG ≥ 4.5:1）
    //  ③ 强调点（小圆点 / 描边）= accentColor × 合适透明度
    //  这样不管终端内容底色选了什么，Banner 本身 + 周围 padding
    //  都是统一的 MaterialTheme.surface / primaryContainer 外壳色。
    val bannerBorderColor = accentColor.copy(alpha = 0.55f)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .fillMaxWidth()
            .border(1.dp, bannerBorderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(Radius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z0)
    ) {
        Column(modifier = Modifier.padding(TerminalLayout.bannerInnerPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(TerminalLayout.accentDotSize)
                        .background(accentColor, shape = RoundedCornerShape(50))
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (showDismiss) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(ButtonSpec.Height)
                            .align(Alignment.CenterVertically),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    ) {
                        Text(
                            stringResource(R.string.ui______c8c4516c),
                            fontSize = ButtonSpec.TextFontSize,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (banner) {
                    TerminalViewModel.BannerType.ContainerNotInstalled -> {
                        PrimaryButton(
                            onClick = onInitContainer,
                            icon = FeatherIcons.Plus,
                            text = stringResource(R.string.ui_______30947f5e)
                        )
                        SecondaryButton(
                            onClick = onGoSettings,
                            icon = FeatherIcons.Settings,
                            text = stringResource(R.string.ui_______dc25a273)
                        )
                    }
                    TerminalViewModel.BannerType.PythonMissing -> {
                        PrimaryButton(
                            onClick = onInstallRecommended,
                            icon = FeatherIcons.Cpu,
                            text = stringResource(R.string.ui_____ebd237c5)
                        )
                        SecondaryButton(
                            onClick = onGoSettings,
                            icon = FeatherIcons.Settings,
                            text = stringResource(R.string.ui_______dc25a273_2)
                        )
                    }
                }
            }
            if (banner == TerminalViewModel.BannerType.ContainerNotInstalled) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    "（当前会话使用原生 Android /system/bin/sh 作为 fallback，基础命令可用，但无法安装 apk 包。）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.80f)
                )
            }
        }
    }
}
