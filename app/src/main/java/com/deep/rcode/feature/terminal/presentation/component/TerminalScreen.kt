package com.deep.rcode.feature.terminal.presentation.component

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
import com.deep.rcode.R
import com.deep.rcode.core.theme.AppEmptyState
import com.deep.rcode.core.theme.AppLoadingState
import com.deep.rcode.core.theme.AppTopAppBar
import com.deep.rcode.core.theme.Elevation
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.terminal.data.repository.TerminalFontSizes
import com.deep.rcode.feature.terminal.domain.RunState
import com.deep.rcode.feature.terminal.domain.TabColorMarker
import com.deep.rcode.feature.terminal.domain.TerminalTab
import com.deep.rcode.feature.terminal.presentation.TerminalViewModel
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

                    // 用终端 palette.containerBg 作为 Ready 区块统一底色，
                    // 避免 TabBar ↔ TerminalSurface 之间出现「白条」（Scaffold 底色透出）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(palette.containerBg)
                    ) {
                        // 首屏 Banner：容器未装 / Python 未装
                        //  注意：Banner 在终端内容底色 palette.containerBg 这一块里展示，
                        //  所以卡片背景也必须等于 palette.containerBg，不能用外壳的
                        //  MaterialTheme.colorScheme.primaryContainer——否则用户终端选
                        //  「白底黑字」但外壳是暗主题时，Banner 周围会出现一大块
                        //  「深蓝卡片 + 白边 + 白内容区」的三层断层（你现在看到的 bug）。
                        val banner by viewModel.currentBanner.collectAsStateWithLifecycle()
                        banner?.let { b ->
                            TerminalFirstRunBanner(
                                banner = b,
                                skin = skin,
                                palette = palette,
                                onGoSettings = onNavigateToSettings,
                                onInstallRecommended = { viewModel.installAiRecommended() },
                                onInitContainer = { viewModel.prepare() },
                                onDismiss = viewModel::dismissFirstRunBanner
                            )
                        }

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
    com.deep.rcode.core.ui.rememberImeBottomInset()

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
    palette: TerminalPalette,
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
            title = "本地容器还未初始化"
            desc = "容器未就绪时仅可使用手机原生 shell（命令较少）。\n点下方「初始化环境」解锁 Alpine Linux + PRoot 以及 Python / Node / Git 等 6 大功能包。"
            showDismiss = false
        }
        TerminalViewModel.BannerType.PythonMissing -> {
            title = "AI 代码运行需要 Python"
            desc = "AI 的「Run Code / Search / git diff」依赖 Python 运行时。建议先一键安装 AI 推荐组合。"
            showDismiss = true
        }
    }

    // Banner 新规范（消除「终端白底 + Banner 深蓝外壳 + 白边」三层断层）：
    //  ① Banner 卡片背景 = palette.containerBg（与下方终端内容区同色，视觉融为一体）
    //  ② 提示卡片靠「accent 0.08 alpha 大圆角背景块 + 1dp accent 描边」表达提示层级，
    //     而不是 primaryContainer 整块换色（会和终端内容底色冲突）
    //  ③ 文字主色 = palette.defaultForeground（终端 fg，终端是黑底就白字、白底就黑字）
    //     「强调点」用 accentColor（外壳 tertiary/secondary，来自 MaterialTheme，
    //     对比度：终端黑底→白字→accent(secondary)=#7DD3FC(蓝)≥5.8:1 白底黑字→#0284C7≥5.2:1）
    val cardBg = palette.containerBg
    val textFg = palette.defaultForeground
    val textFgDim = palette.defaultForeground.copy(alpha = 0.80f)
    val accentSoftBg = accentColor.copy(alpha = 0.08f)
    val accentStroke = accentColor.copy(alpha = 0.55f)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .fillMaxWidth()
            .border(1.dp, accentStroke, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(
            containerColor = cardBg
        ),
        shape = RoundedCornerShape(Radius.md),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z0)
    ) {
        // 「重点提示背景层」：放在 Row 上方，让标题/说明整片区与下方按钮区分层
        Column(modifier = Modifier.padding(TerminalLayout.bannerInnerPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accentSoftBg, RoundedCornerShape(Radius.sm))
                    .padding(Spacing.sm),
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
                    color = textFg,
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
                            contentColor = textFgDim,
                        )
                    ) {
                        Text(
                            "暂不提醒",
                            fontSize = ButtonSpec.TextFontSize,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = textFgDim
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (banner) {
                    TerminalViewModel.BannerType.ContainerNotInstalled -> {
                        // 主按钮：初始化容器
                        PrimaryButton(
                            onClick = onInitContainer,
                            icon = FeatherIcons.Plus,
                            text = "初始化环境"
                        )
                        // 次按钮：跳设置
                        SecondaryButton(
                            onClick = onGoSettings,
                            icon = FeatherIcons.Settings,
                            text = "去终端设置"
                        )
                    }
                    TerminalViewModel.BannerType.PythonMissing -> {
                        // 主按钮：立即装 AI 推荐组合
                        PrimaryButton(
                            onClick = onInstallRecommended,
                            icon = FeatherIcons.Cpu,
                            text = "立即装 Python"
                        )
                        // 次按钮：跳设置
                        SecondaryButton(
                            onClick = onGoSettings,
                            icon = FeatherIcons.Settings,
                            text = "去终端设置"
                        )
                    }
                }
            }
            if (banner == TerminalViewModel.BannerType.ContainerNotInstalled) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    "（当前会话使用原生 Android /system/bin/sh 作为 fallback，基础命令可用，但无法安装 apk 包。）",
                    style = MaterialTheme.typography.bodySmall,
                    color = textFgDim
                )
            }
        }
    }
}
