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
import androidx.compose.foundation.layout.wrapContentWidth
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
                .height(TerminalLayout.tabHeight + Spacing.sm)  // 内容 + 上下间距
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs + 2.dp),
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
            // 新建按钮：尺寸统一 TerminalLayout.newTabButtonSize；图标严格居中
            Box(
                modifier = Modifier
                    .size(TerminalLayout.newTabButtonSize)
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
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center)
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
        else -> SemanticColors.Success
    }
    val closeTint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .height(TerminalLayout.tabHeight)
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = TerminalLayout.tabHorizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TerminalLayout.tabItemSpacingH, Alignment.CenterHorizontally)
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
                // 状态点：DotSize.Chip = 8dp，严格居中
                Box(
                    modifier = Modifier
                        .size(DotSize.Chip)
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
                    .wrapContentWidth(Alignment.Start)
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
            // 主题调色板：Termux 内部颜色数组 + 强制设置 Emulator 的 fg/bg/cursor，
            // 彻底修复"米白背景 + Dracula 白色前景"的反色 bug
            applyTerminalPalette(palette, view)

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
            // 每次 attach 后再应用一次颜色：session/emulator 会把默认前景带过来
            applyTerminalPalette(palette, view)
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
            // 主题变更时（用户改 TerminalTheme）需要立刻重新应用（palette 作为 key）
            applyTerminalPalette(palette, view)
            view.onScreenUpdated()
        },
        onRelease = { view ->
            if (tab.view === view) tab.view = null
        }
    )
}

/**
 * 一次性把 TerminalPalette 应用到 Termux TerminalView：
 *  1) view.setBackgroundColor：外层 View 背景（和 Modifier.background 对应，避免合成时透出系统色）
 *  2) mColors[16]：Termux 内部 16 色调色板
 *  3) Emulator.defaultFgColor / defaultBgColor / cursorForeColor：这是终端"提示符/正文默认字色"，
 *     之前**完全没设置**，就会在米白 containerBg 上仍使用 Termux 默认白前景 = 反色 bug 的根因。
 *  所有操作都 runCatching：即使反射失败也不崩，只是退回默认色。
 */
private fun applyTerminalPalette(palette: TerminalPalette, view: com.termux.view.TerminalView) {
    runCatching {
        // 1) View 级背景
        view.setBackgroundColor(palette.defaultBackgroundInt)
        // 2) mColors[16]
        val mColorsField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mColors").apply { isAccessible = true }
        mColorsField.set(view, palette.toAnsiIntArray())
        // 3) Emulator
        val emField = com.termux.view.TerminalView::class.java
            .getDeclaredField("mEmulator").apply { isAccessible = true }
        val emulator = emField.get(view) ?: return@runCatching
        val emulatorClass = emulator.javaClass
        runCatching {
            emulatorClass.getMethod("setDefaultFgColor", Int::class.javaPrimitiveType as Class<*>)
                .invoke(emulator, palette.defaultForegroundInt)
        }
        runCatching {
            emulatorClass.getMethod("setDefaultBgColor", Int::class.javaPrimitiveType as Class<*>)
                .invoke(emulator, palette.defaultBackgroundInt)
        }
        runCatching {
            emulatorClass.getMethod("setCursorForeColor", Int::class.javaPrimitiveType as Class<*>)
                .invoke(emulator, palette.cursorInt)
        }
        // 兼容：如果 Termux 内部没有上述公开 setter，直接暴力反射字段
        fun setIfExists(cl: Class<*>, name: String, value: Int) = runCatching {
            val f = cl.getDeclaredField(name).apply { isAccessible = true }
            f.setInt(emulator, value)
        }
        setIfExists(emulatorClass, "mDefaultFgColor", palette.defaultForegroundInt)
        setIfExists(emulatorClass, "mDefaultBgColor", palette.defaultBackgroundInt)
        setIfExists(emulatorClass, "mCursorForeColor", palette.cursorInt)
    }
}

// ──────────────────────────────────────────────────────────
// 扩展按键行：简洁档/完整档切换
//   简洁档：两行，不再水平滚动导致 → 方向键被截断
//   完整档：保留 horizontalScroll，适合在小屏滚动
//  —— 关键架构修复：切换按钮固定宽度 + 放在滚动区外面，
//     避免窄屏上左侧 chip 过多把切换按钮挤出可视区（clip）。
// ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysRow(viewModel: TerminalViewModel, full: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Spacing.md,
                vertical = TerminalLayout.keyRowPaddingV
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (full) {
                // ── 完整档：Row1 = [滚动键区(weight 1f)] + [切换按钮(固定宽度)] ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)   // ← 键区占满剩余宽度，不会挤压右侧切换按钮
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CtrlChipWithTooltip(viewModel)
                        AltChipWithTooltip(viewModel)
                        KeyChip("Esc") { viewModel.write("\u001B") }
                        KeyChip("Tab") { viewModel.write("\t") }
                        KeyChip("←") { viewModel.write("\u001B[D") }
                        KeyChip("↑") { viewModel.write("\u001B[A") }
                        KeyChip("↓") { viewModel.write("\u001B[B") }
                        KeyChip("→") { viewModel.write("\u001B[C") }
                        KeyChip("Home") { viewModel.write("\u001B[H") }
                        KeyChip("End") { viewModel.write("\u001B[F") }
                        KeyChip("PgUp") { viewModel.write("\u001B[5~") }
                        KeyChip("PgDn") { viewModel.write("\u001B[6~") }
                        KeyChip("Ins") { viewModel.write("\u001B[2~") }
                        KeyChip("Del") { viewModel.write("\u007F") }
                        KeyChip("C-c") { viewModel.writeBytes(0x03) }
                        KeyChip("C-d") { viewModel.writeBytes(0x04) }
                        KeyChip("C-z") { viewModel.writeBytes(0x1A) }
                        KeyChip("C-l") { viewModel.writeBytes(0x0C) }
                        KeyChip("~") { viewModel.write("~") }
                        KeyChip("/") { viewModel.write("/") }
                        KeyChip("-") { viewModel.write("-") }
                    }
                    // 切换按钮：固定宽度 72dp，始终可见，不被滚动
                    CompactFullSwitcher(
                        full = full,
                        onClick = viewModel::toggleFullExtraKeys
                    )
                }
            } else {
                // ── 简洁档 = 两行 ──
                // Row1：[Ctrl Esc Tab / - C-c C-d](weight 1f) + [切换按钮(固定 72dp)]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),  // ← 键区占满剩余宽度
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CtrlChipWithTooltip(viewModel)
                        KeyChip("Esc") { viewModel.write("\u001B") }
                        KeyChip("Tab") { viewModel.write("\t") }
                        KeyChip("/") { viewModel.write("/") }
                        KeyChip("-") { viewModel.write("-") }
                        KeyChip("C-c") { viewModel.writeBytes(0x03) }
                        KeyChip("C-d") { viewModel.writeBytes(0x04) }
                    }
                    // 切换按钮：固定宽度，不参与 weight 分配 → 窄屏始终完整可见
                    CompactFullSwitcher(
                        full = false,
                        onClick = viewModel::toggleFullExtraKeys
                    )
                }
                // Row2：← ↑ ↓ → 4 方向键 + C-l / C-z（纯键，无切换按钮，自然填满）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyChip("←") { viewModel.write("\u001B[D") }
                    KeyChip("↑") { viewModel.write("\u001B[A") }
                    KeyChip("↓") { viewModel.write("\u001B[B") }
                    KeyChip("→") { viewModel.write("\u001B[C") }
                    KeyChip("C-z") { viewModel.writeBytes(0x1A) }
                    KeyChip("C-l") { viewModel.writeBytes(0x0C) }
                }
            }
        }
    }
}

/** "简洁 ↔ 完整" 切换按钮，单独提出来避免写重复。
 *  固定宽度：保证无论 Row1 左侧塞多少 key chip，此按钮绝不被挤压/裁剪。
 *  宽度 = 图标 16 + 间距 2 + 文本("简洁"/"完整")约 48sp → 留出 72.dp 安全宽度。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompactFullSwitcher(full: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(TerminalLayout.keyHeight)
            .width(TerminalLayout.switcherWidth)   // ← 固定宽度：强制不参与 Row 的 weight 挤压
            .clip(RoundedCornerShape(TerminalLayout.keyRadius))
            .border(
                1.dp,
                if (full) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(TerminalLayout.keyRadius)
            )
            .background(
                if (full) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick)
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
                modifier = Modifier.size(ButtonSpec.ChipIndicatorSize),
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
            .height(TerminalLayout.keyHeight)
            .then(if (label.length <= 1) Modifier.width(TerminalLayout.keyShortWidth) else Modifier)
            .clip(RoundedCornerShape(TerminalLayout.keyRadius))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(TerminalLayout.keyRadius))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = Spacing.sm),
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
