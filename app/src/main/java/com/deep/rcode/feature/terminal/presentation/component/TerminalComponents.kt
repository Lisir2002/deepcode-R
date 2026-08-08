package com.deep.rcode.feature.terminal.presentation.component

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.deep.rcode.R
import com.deep.rcode.core.theme.Elevation
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.terminal.data.repository.TerminalFontSizes
import com.deep.rcode.feature.terminal.domain.RunState
import com.deep.rcode.feature.terminal.domain.TerminalTab
import com.deep.rcode.feature.terminal.presentation.TerminalViewModel
import com.termux.view.TerminalView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Grid
import compose.icons.feathericons.Info
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlin.math.max

// ──────────────────────────────────────────────────────────
// 标签菜单栏
// ──────────────────────────────────────────────────────────

sealed interface TerminalMenuAnchor {
    data object Bar : TerminalMenuAnchor
    data class Terminal(val offset: Offset) : TerminalMenuAnchor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    hasNewOutputMap: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onTabLongPress: (TerminalTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)  // TabChip 40dp + 上下 6dp padding = 52dp
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    selected = tab.id == activeTabId,
                    hasNewOutput = hasNewOutputMap[tab.id] == true,
                    onClick = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                    onLongPress = { onTabLongPress(tab) }
                )
            }
            // 新建按钮：与 TabChip 一致的触摸区域与视觉
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(Radius.md)
                    )
                    .combinedClickable(onClick = onNew),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.common_new_tab),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    tab: TerminalTab,
    selected: Boolean,
    hasNewOutput: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongPress: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val running = tab.runState is RunState.Running
    val dot = when {
        !running -> MaterialTheme.colorScheme.outline
        tab.isBackground -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF22C55E)
    }
    val closeTint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .height(40.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)
        ) {
            BadgedBox(
                badge = {
                    if (hasNewOutput && !selected) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.offset { IntOffset(4.dp.roundToPx(), (-4).dp.roundToPx()) }
                        )
                    }
                }
            ) {
                // 状态点：8dp
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
            // 优先用 command 摘要（后台标签更可读），其次 title
            val title = tab.command?.takeIf { tab.isBackground && tab.title.startsWith("term-") }
                ?.let { summarizeCommand(it) }
                ?: tab.title
            Text(
                text = title,
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(100.dp)
                    .align(Alignment.CenterVertically)
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.X,
                    contentDescription = stringResource(R.string.terminal_close_tab),
                    tint = closeTint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 把完整命令摘要成一行可读标签（最多 18 字符）。 */
private fun summarizeCommand(cmd: String): String {
    val firstLine = cmd.lineSequence().firstOrNull().orEmpty().trim()
    val tokens = firstLine.split("\\s+".toRegex())
    val head = tokens.firstOrNull().orEmpty()
    val args = tokens.drop(1).take(2)
    val candidate = buildString {
        append(head)
        args.forEach { append(' '); append(it) }
    }
    return if (candidate.length <= 18) candidate else candidate.take(15) + "…"
}

// ──────────────────────────────────────────────────────────
// 终端视图 Surface：Termux TerminalView 包装
// ──────────────────────────────────────────────────────────
@Composable
fun TerminalSurface(
    tab: TerminalTab,
    viewModel: TerminalViewModel,
    fontSizeSp: Int,
    onLongPress: (Offset) -> Unit
) {
    val palette = rememberTerminalPalette()
    val context = LocalContext.current
    // TerminalView 没有公开的 textSize getter，用本地镜像追踪，避免重复 setTextSize
    var appliedPx by remember(fontSizeSp) { mutableIntStateOf(-1) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.containerBg),
        factory = { ctx ->
            val view = TerminalView(ctx, null)
            val d = ctx.resources.displayMetrics.density
            val px = (fontSizeSp * d).toInt()
            view.setTextSize(px)
            appliedPx = px
            // 主题调色板：Termux 内部颜色数组
            runCatching {
                val colorField = com.termux.view.TerminalView::class.java.getDeclaredField("mColors")
                colorField.isAccessible = true
                colorField.set(view, palette.toAnsiIntArray())
            }
            view.setTerminalViewClient(
                AppTerminalViewClient(
                    context = ctx,
                    viewProvider = { view },
                    modifiers = viewModel.modifiers
                ).also { client ->
                    client.setScaleListener { s -> viewModel.stepFontSize(s) }
                    client.setLongPressListener { xPx, yPx ->
                        onLongPress(Offset(xPx, yPx))
                    }
                }
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            tab.view = view
            view.attachSession(tab.session)
            view.onScreenUpdated()
            view.requestFocus()
            // 挂载后主动弹起软键盘
            view.post {
                view.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
            view
        },
        update = { view ->
            val d = view.resources.displayMetrics.density
            val px = (fontSizeSp * d).toInt()
            if (appliedPx != px) {
                view.setTextSize(px)
                appliedPx = px
                view.onScreenUpdated()
            }
        },
        onRelease = { view ->
            if (tab.view === view) tab.view = null
        }
    )
}

// ──────────────────────────────────────────────────────────
// 扩展按键行：简洁档/完整档切换
// ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysRow(viewModel: TerminalViewModel, full: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CtrlChipWithTooltip(viewModel)
                KeyChip("Esc") { viewModel.write("\u001B") }
                KeyChip("Tab") { viewModel.write("\t") }
                KeyChip("←") { viewModel.write("\u001B[D") }
                KeyChip("↑") { viewModel.write("\u001B[A") }
                KeyChip("↓") { viewModel.write("\u001B[B") }
                KeyChip("→") { viewModel.write("\u001B[C") }
                KeyChip("C-c") { viewModel.writeBytes(0x03) }
                KeyChip("C-d") { viewModel.writeBytes(0x04) }
                if (full) {
                    AltChipWithTooltip(viewModel)
                    KeyChip("Home") { viewModel.write("\u001B[H") }
                    KeyChip("End") { viewModel.write("\u001B[F") }
                    KeyChip("PgUp") { viewModel.write("\u001B[5~") }
                    KeyChip("PgDn") { viewModel.write("\u001B[6~") }
                    KeyChip("Ins") { viewModel.write("\u001B[2~") }
                    KeyChip("Del") { viewModel.write("\u007F") }
                    KeyChip("C-z") { viewModel.writeBytes(0x1A) }
                    KeyChip("C-l") { viewModel.writeBytes(0x0C) }
                    KeyChip("~") { viewModel.write("~") }
                }
                KeyChip("/") { viewModel.write("/") }
                KeyChip("-") { viewModel.write("-") }
                // 末尾切换简洁/完整档按钮
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .border(
                            1.dp,
                            if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(Radius.md)
                        )
                        .background(
                            if (full) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                        .combinedClickable(onClick = { viewModel.toggleFullExtraKeys() })
                        .padding(horizontal = Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            FeatherIcons.Grid,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (full) "完整" else "简洁",
                            fontSize = 12.sp,
                            color = if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CtrlChipWithTooltip(viewModel: TerminalViewModel) {
    val state = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text("先点击开启，再按键盘上的字母发送 Ctrl+X")
            }
        },
        state = state
    ) {
        KeyChip(
            "Ctrl",
            active = viewModel.modifiers.ctrl,
            onClick = { viewModel.modifiers.ctrl = !viewModel.modifiers.ctrl }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AltChipWithTooltip(viewModel: TerminalViewModel) {
    val state = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text("先点击开启，再按键盘上的字母发送 Alt+X")
            }
        },
        state = state
    ) {
        KeyChip(
            "Alt",
            active = viewModel.modifiers.alt,
            onClick = { viewModel.modifiers.alt = !viewModel.modifiers.alt }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .height(40.dp)
            .then(if (label.length <= 1) Modifier.width(44.dp) else Modifier)
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            softWrap = false
        )
    }
}

// ──────────────────────────────────────────────────────────
// Reconnect 下拉菜单
// ──────────────────────────────────────────────────────────
@Composable
fun ReconnectDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onReconnectActive: () -> Unit,
    onReconnectAll: () -> Unit,
    onRestartContainer: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.terminal_reconnect_tab)) },
            onClick = onReconnectActive,
            leadingIcon = {
                Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        DropdownMenuItem(
            text = { Text("重连全部标签") },
            onClick = onReconnectAll,
            leadingIcon = {
                Icon(FeatherIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(
            text = { Text("重启容器") },
            onClick = onRestartContainer,
            leadingIcon = {
                Icon(FeatherIcons.Trash2, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
    }
}

// ──────────────────────────────────────────────────────────
// 终端级操作菜单（复制/粘贴/全选/清屏/搜索/重命名/字号等）
// ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalOperationsMenu(
    anchor: TerminalMenuAnchor?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onClearScreen: () -> Unit,
    onSearch: () -> Unit,
    onRenameTab: () -> Unit,
    onSendToAI: (() -> Unit)?,
    onToggleFullKeys: () -> Unit,
    onFontSizeStepUp: () -> Unit,
    onFontSizeStepDown: () -> Unit,
    fullExtraKeys: Boolean,
    allowSendToAI: Boolean
) {
    val expanded = anchor != null
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("复制") }, onClick = onCopy,
            leadingIcon = { Icon(FeatherIcons.Copy, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("粘贴") }, onClick = onPaste,
            leadingIcon = { Icon(FeatherIcons.Terminal, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("全选") }, onClick = onSelectAll,
            leadingIcon = { Icon(FeatherIcons.Type, null, Modifier.size(18.dp)) })
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(text = { Text("清屏") }, onClick = onClearScreen,
            leadingIcon = { Icon(FeatherIcons.Trash2, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("搜索") }, onClick = onSearch,
            leadingIcon = { Icon(FeatherIcons.Search, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("重命名标签") }, onClick = onRenameTab,
            leadingIcon = { Icon(FeatherIcons.Edit3, null, Modifier.size(18.dp)) })
        if (allowSendToAI && onSendToAI != null) {
            DropdownMenuItem(text = { Text("发送最后 100 行给 AI") }, onClick = onSendToAI,
                leadingIcon = { Icon(FeatherIcons.Share2, null, Modifier.size(18.dp)) })
        }
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(text = { Text(if (fullExtraKeys) "完整键盘（开启中）" else "完整键盘（关闭中）") }, onClick = onToggleFullKeys,
            leadingIcon = { Icon(FeatherIcons.Grid, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("字号增大") }, onClick = onFontSizeStepUp,
            leadingIcon = { Icon(FeatherIcons.Plus, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text("字号减小") }, onClick = onFontSizeStepDown,
            leadingIcon = { Icon(FeatherIcons.X, null, Modifier.size(18.dp)) })
    }
}

// ──────────────────────────────────────────────────────────
// Tab 长按：菜单级对话框（重命名/关闭/关闭其他）
// ──────────────────────────────────────────────────────────
@Composable
fun TabLongPressDialog(
    tab: TerminalTab,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签「${tab.title}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("状态：${if (tab.runState is RunState.Running) "运行中" else "已结束（exit=${(tab.runState as? RunState.Finished)?.exitCode}）"}")
                if (tab.isBackground) {
                    Text("后台命令：${tab.command ?: "(无)"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Icon(FeatherIcons.X, null, Modifier.size(16.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text("关闭")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRename) {
                    Icon(FeatherIcons.Edit3, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text("重命名")
                }
                TextButton(onClick = onCloseOthers) {
                    Icon(FeatherIcons.Trash2, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text("关闭其他")
                }
            }
        }
    )
}

// ──────────────────────────────────────────────────────────
// 重命名标签输入对话框
// ──────────────────────────────────────────────────────────
@Composable
fun RenameTabDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名标签") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim().ifBlank { initial }) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ──────────────────────────────────────────────────────────
// 终端内容搜索浮层（关键字 + 上一个/下一个）
//  用 setTopRow 滚动（mTopRow=0 为底部，负值向上翻历史）
// ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.TerminalSearchOverlay(
    onDismiss: () -> Unit,
    tabs: List<TerminalTab>,
    activeTabId: String?
) {
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    val tab = tabs.firstOrNull { it.id == activeTabId }
    val matches: List<Int> = remember(query, tab?.id, tab?.session?.emulator?.screen?.transcriptText?.hashCode() ?: 0) {
        val text = tab?.session?.emulator?.screen?.transcriptText ?: return@remember emptyList()
        if (query.isBlank()) return@remember emptyList()
        val lines = text.lines()
        val out = mutableListOf<Int>()
        lines.forEachIndexed { i, line ->
            if (query in line) out += i
        }
        out
    }
    // 滚到匹配行：用 setTopRow 设置滚动位置
    LaunchedEffect(matchIndex, matches, tab?.id) {
        val view = tab?.view ?: return@LaunchedEffect
        if (matches.isEmpty()) return@LaunchedEffect
        val emulator = tab.session.emulator ?: return@LaunchedEffect
        val lineIdx = matches[matchIndex.coerceIn(0, matches.lastIndex)]
        val visibleRows = emulator.screen.activeRows
        val transcriptRows = emulator.screen.activeTranscriptRows
        val totalLines = (emulator.screen.transcriptText?.lines()?.size ?: 0)
        val bottomLine = totalLines - 1
        // 让匹配行尽可能出现在可视区顶部：目标最后一行 = lineIdx + visibleRows - 1
        val targetBottom = (lineIdx + visibleRows - 1).coerceAtMost(totalLines - 1)
        // 需向上滚动的行数：bottomLine 与 targetBottom 的差值
        val scrollUpBy = (bottomLine - targetBottom).coerceIn(0, transcriptRows)
        // mTopRow = 0 表示在最底部；负值表示向上进入 transcript 历史
        view.setTopRow(-scrollUpBy)
        delay(10)
        view.onScreenUpdated()
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            shape = RoundedCornerShape(Radius.lg),
            shadowElevation = Elevation.z3,
            tonalElevation = Elevation.z3
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; matchIndex = 0 },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索终端输出") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(FeatherIcons.Search, null, Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; matchIndex = 0 }) {
                                    Icon(FeatherIcons.X, null, Modifier.size(18.dp))
                                }
                            }
                        }
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(FeatherIcons.X, null, Modifier.size(20.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (matches.isEmpty()) "无匹配" else "${matchIndex.coerceIn(0, max(0, matches.lastIndex)) + 1} / ${matches.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        IconButton(
                            onClick = { matchIndex = (matchIndex - 1).coerceAtLeast(0) },
                            enabled = matches.isNotEmpty() && matchIndex > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(FeatherIcons.ArrowUp, null, Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { matchIndex = (matchIndex + 1).coerceAtMost(matches.lastIndex) },
                            enabled = matches.isNotEmpty() && matchIndex < matches.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(FeatherIcons.ArrowDown, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────
// Ctrl 首次使用提示气泡
// ──────────────────────────────────────────────────────────
@Composable
fun CtrlHintBubble(modifier: Modifier, onGotIt: () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(Radius.lg),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.z3)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(FeatherIcons.Info, null, Modifier.size(18.dp))
            Text(
                text = "先按 Ctrl 开启，再按键盘上的字母即可发送 Ctrl+X（例如 Ctrl+C 中断进程）。字母发出后自动关闭。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGotIt) { Text("知道了") }
        }
    }
}

// ──────────────────────────────────────────────────────────
// 容器初始化进度消息（StatusView/AppLoadingState 文案源）
// ──────────────────────────────────────────────────────────
fun containerInitMessage(context: Context, state: ContainerInitState): String = when (state) {
    is ContainerInitState.ExtractingRootfs ->
        context.getString(R.string.terminal_extracting_env, state.processed)
    ContainerInitState.DeployingProot ->
        context.getString(R.string.terminal_deploying_proot)
    is ContainerInitState.BundleInstalling -> {
        val bundleName = state.bundleId?.let { bid ->
            when (bid) {
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.PYTHON -> "Python 运行时"
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.NODE -> "Node.js 运行时"
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.RIPGREP -> "高速搜索 (rg)"
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.GIT -> "Git"
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.BASH -> "Bash 环境"
                com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId.NET -> "网络工具"
            }
        } ?: "环境"
        val tail = state.line?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
        "正在安装 $bundleName…$tail"
    }
    is ContainerInitState.BundleUninstalling ->
        "正在卸载包…"
    is ContainerInitState.Failed ->
        context.getString(R.string.terminal_preparing_env_failed, state.reason)
    ContainerInitState.Idle, is ContainerInitState.Ready ->
        context.getString(R.string.terminal_preparing_env_first_run)
    else ->
        context.getString(R.string.terminal_preparing_env_first_run)
}
