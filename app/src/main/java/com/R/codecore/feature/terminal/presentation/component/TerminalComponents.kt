package com.R.codecore.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.R.codecore.R
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
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Info
import compose.icons.feathericons.Layout
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Power
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Send
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

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
    skin: TerminalSkinSnapshot,
    tabs: List<TerminalTab>,
    activeTabId: String?,
    hasNewOutputMap: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
    onTabLongPress: (TerminalTab) -> Unit
) {
    val sortedTabs = remember(tabs) {
        tabs.sortedByDescending { it.isPinned }
    }

    Surface(
        color = skin.surface,
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
            sortedTabs.forEach { tab ->
                TabChip(
                    skin = skin,
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
                    .background(skin.surfaceVariant)
                    .border(
                        1.dp,
                        skin.dividingLine,
                        RoundedCornerShape(Radius.md)
                    )
                    .combinedClickable(onClick = onNew),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.common_new_tab),
                    tint = skin.onSurfaceVariant,
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
    skin: TerminalSkinSnapshot,
    tab: TerminalTab,
    selected: Boolean,
    hasNewOutput: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongPress: () -> Unit
) {
    val bg = if (selected) skin.primaryContainer else skin.surfaceVariant
    val fg = if (selected) skin.onPrimaryContainer else skin.onSurface
    val borderColor = if (selected) skin.primaryFg.copy(alpha = 0.50f) else skin.dividingLine
    val running = tab.runState is RunState.Running
    val dot = when {
        !running -> skin.onSurfaceVariant
        tab.isBackground -> skin.semanticWarning
        else -> skin.semanticSuccess
    }

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
    ) {
        // 颜色标记条（左侧 3dp 竖条）
        if (tab.colorMarker != TabColorMarker.NONE) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(topStart = Radius.md, bottomStart = Radius.md))
                    .background(tab.colorMarker.color)
            )
        }

        // 主内容行
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TerminalLayout.tabHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TerminalLayout.tabItemSpacingH)
        ) {
            // Pin 图标（固定标签）
            if (tab.isPinned) {
                Text(
                    text = "\uD83D\uDCCC",
                    fontSize = 12.sp
                )
            }

            // 状态点
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
                Box(
                    modifier = Modifier
                        .size(DotSize.Chip)
                        .clip(CircleShape)
                        .background(dot)
                )
            }

            // 标题
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
                modifier = Modifier.wrapContentWidth(Alignment.Start)
            )

            // 退出码（已结束标签）
            if (tab.runState is RunState.Finished) {
                val exitCode = (tab.runState as RunState.Finished).exitCode
                Text(
                    text = "exit $exitCode",
                    color = skin.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 关闭按钮（固定标签无关闭按钮）
            if (!tab.isPinned) {
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
                        tint = if (selected) skin.onPrimaryContainer else skin.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 底部激活指示条
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
                    .background(skin.primaryFg)
                    .clip(RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md))
            )
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
    fontSizeSp: Int
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
            applyTerminalPalette(palette, view)

            view.setTerminalViewClient(
                AppTerminalViewClient(
                    context = ctx,
                    viewProvider = { view },
                    modifiers = viewModel.modifiers
                ).also { client ->
                    client.setScaleListener { s -> viewModel.stepFontSize(s) }
                }
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            // 防御：如果 tab 已被会话管理器移除（closeTab 后 session 正在 finish），
            // 就不要 attach 也不要写 tab.view 了，避免与 finishIfRunning 交错导致 Native crash。
            val stillAlive = runCatching {
                viewModel.tabs.value.any { it.id == tab.id }
            }.getOrDefault(true)
            if (stillAlive) {
                tab.view = view
                runCatching { view.attachSession(tab.session) }
                applyTerminalPalette(palette, view)
                runCatching { view.onScreenUpdated() }
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
            applyTerminalPalette(palette, view)
            // session 已 finish 时，emulator 可能已销毁，onScreenUpdated 内部有 null guard。
            runCatching { view.onScreenUpdated() }
        },
        onRelease = { view ->
            // 解绑顺序严格对齐 closeTab：先 client=null → attachSession(null) → 再 tab.view 断引用
            runCatching {
                view.setTerminalViewClient(null)
                view.attachSession(null)
            }
            if (tab.view === view) tab.view = null
        }
    )
}

private fun copyTextToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
}

/**
 * 一次性把 TerminalPalette 应用进 Termux TerminalView 的 mColors.mCurrentColors[0..258]。
 *
 *  Termux 颜色存储结构（全部 public，不需反射）：
 *    view.mEmulator                      ← TerminalEmulator    (public)
 *       .mColors                         ← TerminalColors      (public final)
 *          .mCurrentColors[0..15]        ← ANSI 16 色
 *          .mCurrentColors[16..231]      ← 216 色 cube
 *          .mCurrentColors[232..255]     ← 24 级灰阶
 *          .mCurrentColors[256]          ← COLOR_INDEX_FOREGROUND
 *          .mCurrentColors[257]          ← COLOR_INDEX_BACKGROUND
 *          .mCurrentColors[258]          ← COLOR_INDEX_CURSOR
 *
 *  !! 之前反射 TerminalView.mColors（不存在）→ NoSuchFieldException → 静默吞掉，
 *     导致前景/光标/ANSI 16 色全部没有写入，始终是 Termux 默认纯白 `#FFFFFF`。
 *     现在直接走 public 字段链，不再反射。
 */
private fun applyTerminalPalette(palette: TerminalPalette, view: com.termux.view.TerminalView) {
    // 1) View 级背景（始终有效，且 TerminalSurface 的 Modifier.background 也用了 palette.containerBg）
    view.setBackgroundColor(palette.defaultBackgroundInt)

    // 2) mEmulator 在 attachSession 之前为 null，此时只写背景
    val emulator = view.mEmulator ?: return
    val colors = emulator.mColors.mCurrentColors

    // 3) ANSI 16 色 (index 0-15)
    val ansi = palette.toAnsiIntArray()
    System.arraycopy(ansi, 0, colors, 0, 16)

    // 4) 256 色扩展 (index 16-255) —— 如果 palette 提供了完整 259 色调色板
    if (palette.full256 != null) {
        System.arraycopy(palette.full256, 0, colors, 16, 240)
    }

    // 5) 前景 / 背景 / 光标 (index 256 / 257 / 258)
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = palette.defaultForegroundInt
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = palette.defaultBackgroundInt
    colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = palette.cursorInt

    // 6) 对比度验证：始终运行，如果主题配色正确则静默通过
    assertColorContrast(
        label = "fg vs bg",
        fore = palette.defaultForegroundInt,
        back = palette.defaultBackgroundInt,
        minRatio = 4.5f
    )
    assertColorContrast(
        label = "cursor vs bg",
        fore = palette.cursorInt,
        back = palette.defaultBackgroundInt,
        minRatio = 7.0f
    )

    // 7) 触发重绘
    view.invalidate()
}

/** Debug 对比度断言：计算两个 ARGB int 的 WCAG 相对亮度比，低于阈值抛 AssertionError。 */
@Suppress("SameParameterValue")
private fun assertColorContrast(label: String, fore: Int, back: Int, minRatio: Float) {
    fun relativeLuminance(c: Int): Double {
        fun linearize(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = linearize((c shr 16) and 0xFF)
        val g = linearize((c shr 8) and 0xFF)
        val b = linearize(c and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val l1 = relativeLuminance(fore)
    val l2 = relativeLuminance(back)
    val ratio = ((maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)).toFloat()
    check(ratio >= minRatio) {
        "[$label] contrast ratio ${"%.2f".format(ratio)} < $minRatio — " +
        "fore=#${fore.toString(16).padStart(8, '0')} " +
        "back=#${back.toString(16).padStart(8, '0')}"
    }
}

/** ExtraKeys 按键分组枚举 */
private enum class KeyGroup { A, B, C, D }

// ──────────────────────────────────────────────────────────
// 扩展按键行：简洁档/完整档切换
//   简洁档：两行，不再水平滚动导致 → 方向键被截断
//   完整档：保留 horizontalScroll，适合在小屏滚动
//  —— 关键架构修复：切换按钮固定宽度 + 放在滚动区外面，
//     避免窄屏上左侧 chip 过多把切换按钮挤出可视区（clip）。
// ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysRow(viewModel: TerminalViewModel, full: Boolean, skin: TerminalSkinSnapshot) {
    Surface(
        color = skin.surface,
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
                        CtrlChipWithTooltip(viewModel, skin)
                        AltChipWithTooltip(viewModel, skin)
                        KeyChip("Esc", skin, KeyGroup.A) { viewModel.write("\u001B") }
                        KeyChip("Tab", skin, KeyGroup.A) { viewModel.write("\t") }
                        KeyChip("←", skin, KeyGroup.C) { viewModel.write("\u001B[D") }
                        KeyChip("↑", skin, KeyGroup.C) { viewModel.write("\u001B[A") }
                        KeyChip("↓", skin, KeyGroup.C) { viewModel.write("\u001B[B") }
                        KeyChip("→", skin, KeyGroup.C) { viewModel.write("\u001B[C") }
                        KeyChip("Home", skin, KeyGroup.C) { viewModel.write("\u001B[H") }
                        KeyChip("End", skin, KeyGroup.C) { viewModel.write("\u001B[F") }
                        KeyChip("PgUp", skin, KeyGroup.C) { viewModel.write("\u001B[5~") }
                        KeyChip("PgDn", skin, KeyGroup.C) { viewModel.write("\u001B[6~") }
                        KeyChip("Ins", skin, KeyGroup.C) { viewModel.write("\u001B[2~") }
                        KeyChip("Del", skin, KeyGroup.C) { viewModel.write("\u007F") }
                        KeyChip("C-c", skin, KeyGroup.A) { viewModel.writeBytes(0x03) }
                        KeyChip("C-d", skin, KeyGroup.A) { viewModel.writeBytes(0x04) }
                        KeyChip("C-z", skin, KeyGroup.A) { viewModel.writeBytes(0x1A) }
                        KeyChip("C-l", skin, KeyGroup.A) { viewModel.writeBytes(0x0C) }
                        KeyChip("~", skin, KeyGroup.B) { viewModel.write("~") }
                        KeyChip("/", skin, KeyGroup.B) { viewModel.write("/") }
                        KeyChip("-", skin, KeyGroup.B) { viewModel.write("-") }
                    }
                    // 切换按钮：固定宽度 72dp，始终可见，不被滚动
                    CompactFullSwitcher(full = full, onClick = viewModel::toggleFullExtraKeys, skin = skin)
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
                        CtrlChipWithTooltip(viewModel, skin)
                        KeyChip("Esc", skin, KeyGroup.A) { viewModel.write("\u001B") }
                        KeyChip("Tab", skin, KeyGroup.A) { viewModel.write("\t") }
                        KeyChip("/", skin, KeyGroup.B) { viewModel.write("/") }
                        KeyChip("-", skin, KeyGroup.B) { viewModel.write("-") }
                        KeyChip("C-c", skin, KeyGroup.A) { viewModel.writeBytes(0x03) }
                        KeyChip("C-d", skin, KeyGroup.A) { viewModel.writeBytes(0x04) }
                    }
                    // 切换按钮：固定宽度，不参与 weight 分配 → 窄屏始终完整可见
                    CompactFullSwitcher(full = false, onClick = viewModel::toggleFullExtraKeys, skin = skin)
                }
                // Row2：← ↑ ↓ → 4 方向键 + C-l / C-z（纯键，无切换按钮，自然填满）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyChip("←", skin, KeyGroup.C) { viewModel.write("\u001B[D") }
                    KeyChip("↑", skin, KeyGroup.C) { viewModel.write("\u001B[A") }
                    KeyChip("↓", skin, KeyGroup.C) { viewModel.write("\u001B[B") }
                    KeyChip("→", skin, KeyGroup.C) { viewModel.write("\u001B[C") }
                    KeyChip("C-z", skin, KeyGroup.A) { viewModel.writeBytes(0x1A) }
                    KeyChip("C-l", skin, KeyGroup.A) { viewModel.writeBytes(0x0C) }
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
private fun CompactFullSwitcher(full: Boolean, onClick: () -> Unit, skin: TerminalSkinSnapshot) {
    Box(
        modifier = Modifier
            .height(TerminalLayout.keyHeight)
            .width(TerminalLayout.switcherWidth)   // ← 固定宽度：强制不参与 Row 的 weight 挤压
            .clip(RoundedCornerShape(TerminalLayout.keyRadius))
            .border(
                if (full) skin.keyGroupDBorderWidth else skin.keyGroupABorderWidth,
                if (full) skin.keyGroupDBorder else skin.keyGroupABorder,
                RoundedCornerShape(TerminalLayout.keyRadius)
            )
            .background(
                if (full) skin.keyGroupDBg else skin.keyGroupABg
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
                FeatherIcons.Layout,
                contentDescription = null,
                modifier = Modifier.size(ButtonSpec.ChipIndicatorSize),
                tint = if (full) skin.keyGroupDFg else skin.onSurfaceVariant
            )
            Text(
                text = if (full) stringResource(R.string.ui____63c59813) else stringResource(R.string.ui____e7e07e58),
                fontSize = 12.sp,
                color = if (full) skin.keyGroupDFg else skin.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CtrlChipWithTooltip(viewModel: TerminalViewModel, skin: TerminalSkinSnapshot) {
    val state = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.ui_______35e60ae6))
            }
        },
        state = state
    ) {
        KeyChip(
            "Ctrl",
            skin = skin,
            group = KeyGroup.A,
            active = viewModel.modifiers.ctrl,
            onClick = { viewModel.modifiers.ctrl = !viewModel.modifiers.ctrl }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AltChipWithTooltip(viewModel: TerminalViewModel, skin: TerminalSkinSnapshot) {
    val state = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(stringResource(R.string.ui_______13de17e1))
            }
        },
        state = state
    ) {
        KeyChip(
            "Alt",
            skin = skin,
            group = KeyGroup.A,
            active = viewModel.modifiers.alt,
            onClick = { viewModel.modifiers.alt = !viewModel.modifiers.alt }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeyChip(label: String, skin: TerminalSkinSnapshot, group: KeyGroup = KeyGroup.A, active: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        active -> skin.ctrlActiveBg
        group == KeyGroup.D -> skin.keyGroupDBg
        group == KeyGroup.C -> skin.keyGroupCBg
        group == KeyGroup.B -> skin.keyGroupBBg
        else -> skin.keyGroupABg
    }
    val borderColor = when {
        active -> skin.ctrlActiveBorder
        group == KeyGroup.D -> skin.keyGroupDBorder
        group == KeyGroup.C -> skin.keyGroupCBorder
        group == KeyGroup.B -> skin.keyGroupBBorder
        else -> skin.keyGroupABorder
    }
    val borderWidth = when {
        group == KeyGroup.B -> skin.keyGroupBBorderWidth
        else -> skin.keyGroupABorderWidth  // A, C, D = 1.2dp
    }
    val fg = when {
        active -> skin.ctrlActiveFg
        group == KeyGroup.D -> skin.keyGroupDFg
        group == KeyGroup.C -> skin.keyGroupAFg
        group == KeyGroup.B -> skin.keyGroupBFg
        else -> skin.keyGroupAFg
    }
    Box(
        modifier = Modifier
            .height(TerminalLayout.keyHeight)
            .then(if (label.length <= 1) Modifier.width(TerminalLayout.keyShortWidth) else Modifier)
            .clip(RoundedCornerShape(TerminalLayout.keyRadius))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(TerminalLayout.keyRadius))
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
            text = { Text(stringResource(R.string.ui________1614f33f)) },
            onClick = onReconnectAll,
            leadingIcon = {
                Icon(FeatherIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui______fbf624a8)) },
            onClick = onRestartContainer,
            leadingIcon = {
                Icon(FeatherIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
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
        DropdownMenuItem(text = { Text(stringResource(R.string.ui____79d3abe9)) }, onClick = onCopy,
            leadingIcon = { Icon(FeatherIcons.Copy, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui____eafbece1)) }, onClick = onPaste,
            leadingIcon = { Icon(FeatherIcons.Clipboard, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui____66eeacd9)) }, onClick = onSelectAll,
            leadingIcon = { Icon(FeatherIcons.Type, null, Modifier.size(18.dp)) })
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(text = { Text(stringResource(R.string.ui____91382d9c)) }, onClick = onClearScreen,
            leadingIcon = { Icon(FeatherIcons.Trash2, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui____e5f71fc3)) }, onClick = onSearch,
            leadingIcon = { Icon(FeatherIcons.Search, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_______a4986174)) }, onClick = onRenameTab,
            leadingIcon = { Icon(FeatherIcons.Edit3, null, Modifier.size(18.dp)) })
        if (allowSendToAI && onSendToAI != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui______38154828)) }, onClick = onSendToAI,
                leadingIcon = { Icon(FeatherIcons.Send, null, Modifier.size(18.dp)) })
        }
        HorizontalDivider(Modifier.padding(horizontal = Spacing.sm))
        DropdownMenuItem(text = { Text(if (fullExtraKeys) stringResource(R.string.ui______6a988d97) else stringResource(R.string.ui______1ae98d49)) }, onClick = onToggleFullKeys,
            leadingIcon = { Icon(FeatherIcons.Layout, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui______3b73382e)) }, onClick = onFontSizeStepUp,
            leadingIcon = { Icon(FeatherIcons.Plus, null, Modifier.size(18.dp)) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui______9dd74977)) }, onClick = onFontSizeStepDown,
            leadingIcon = { Icon(FeatherIcons.Minus, null, Modifier.size(18.dp)) })
    }
}

// ──────────────────────────────────────────────────────────
// Tab 长按：菜单级对话框（重命名/关闭/关闭其他/固定/颜色标记）
// ──────────────────────────────────────────────────────────
@Composable
fun TabLongPressDialog(
    tab: TerminalTab,
    skin: TerminalSkinSnapshot,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onTogglePin: () -> Unit,
    onSetColorMarker: (TabColorMarker) -> Unit
) {
    val runtimeSeconds = (System.currentTimeMillis() - tab.sessionStartTime) / 1000
    val runtimeText = when {
        runtimeSeconds < 60 -> "${runtimeSeconds}秒"
        runtimeSeconds < 3600 -> "${runtimeSeconds / 60}分${runtimeSeconds % 60}秒"
        else -> "${runtimeSeconds / 3600}时${(runtimeSeconds % 3600) / 60}分"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("标签「${tab.title}」${if (tab.isPinned) "📌" else ""}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // 信息区
                Text("运行时长：$runtimeText", fontSize = 13.sp)
                Text(
                    "状态：${if (tab.runState is RunState.Running) "运行中" else "已结束（exit=${(tab.runState as? RunState.Finished)?.exitCode}）"}",
                    fontSize = 13.sp
                )
                Text("子进程数：${tab.childProcessCount}", fontSize = 13.sp)
                if (tab.isBackground) {
                    Text("后台命令：${tab.command ?: "(无)"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                HorizontalDivider()

                // Pin/Unpin 按钮
                TextButton(
                    onClick = onTogglePin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (tab.isPinned) stringResource(R.string.ui______a2b5d9e1) else stringResource(R.string.ui______cb33d371))
                }

                HorizontalDivider()

                // 颜色标记选择
                Text(stringResource(R.string.ui______aedaf872), fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabColorMarker.selectable.forEach { marker ->
                        val isSelected = tab.colorMarker == marker
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(marker.color)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, Color.White, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onSetColorMarker(marker) }
                        )
                    }
                    // 清除颜色标记
                    TextButton(
                        onClick = { onSetColorMarker(TabColorMarker.NONE) },
                        enabled = tab.colorMarker != TabColorMarker.NONE
                    ) {
                        Text(stringResource(R.string.ui____4403fca0), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Icon(FeatherIcons.X, null, Modifier.size(16.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.ui____b15d9127))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRename) {
                    Icon(FeatherIcons.Edit3, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.ui_____c8ce4b36))
                }
                Spacer(Modifier.size(Spacing.sm))
                TextButton(onClick = onCloseOthers) {
                    Icon(FeatherIcons.Trash2, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.ui______6816da19))
                }
            }
        }
    )
}

// ──────────────────────────────────────────────────────────
// 确认操作对话框
// ──────────────────────────────────────────────────────────
@Composable
fun ConfirmActionDialog(
    action: TerminalViewModel.ConfirmAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val (title, text, isDestructive) = when (action) {
        is TerminalViewModel.ConfirmAction.CloseTab -> {
            Triple("关闭标签「${action.title}」", stringResource(R.string.ui____________b8ee30bb), true)
        }
        is TerminalViewModel.ConfirmAction.CloseOtherTabs -> {
            Triple(stringResource(R.string.ui________c65c616b), stringResource(R.string.ui______________9b871ba4), true)
        }
        is TerminalViewModel.ConfirmAction.RestartContainer -> {
            Triple(stringResource(R.string.ui______fbf624a8_2), stringResource(R.string.ui______________d8a0d499), true)
        }
        is TerminalViewModel.ConfirmAction.ReconnectAll -> {
            Triple(stringResource(R.string.ui________1614f33f_2), stringResource(R.string.ui____________c8c18a50), false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.textButtonColors()
                }
            ) {
                Text(stringResource(R.string.ui____38cf16f2))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui____625fb26b))
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
        title = { Text(stringResource(R.string.ui_______a4986174_2)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim().ifBlank { initial }) }) { Text(stringResource(R.string.ui____be5fbbe3)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui____625fb26b_2)) }
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
                        placeholder = { Text(stringResource(R.string.ui________a7e95b4c)) },
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
                        text = if (matches.isEmpty()) stringResource(R.string.ui_____b5738da7) else "${matchIndex.coerceIn(0, max(0, matches.lastIndex)) + 1} / ${matches.size}",
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
                text = stringResource(R.string.ui____d59d86f6),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGotIt) { Text(stringResource(R.string.ui_____ce26955a)) }
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
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.PYTHON -> "Python 运行时"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.NODE -> "Node.js 运行时"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.RIPGREP -> "高速搜索 (rg)"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.GIT -> "Git"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.BASH -> "Bash 环境"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.NET -> "网络工具"
                com.R.codecore.feature.terminal.data.bundle.TerminalBundleId.QEMU_X86_TRANSLATOR -> "x86 构建转译器"
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