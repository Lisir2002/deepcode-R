package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing

/**
 * 终端页面 UI 规格单一事实源。
 * 终端所有卡片、Tab、按键、行间距全部从这里拿 tokens，避免截图里出现不一致。
 */
@Immutable
object TerminalLayout {
    /** Banner / TabBar / TerminalSurface / ExtraKeysRow 之间的垂直节奏 */
    val sectionSpacingV: Dp = Spacing.sm  // 8 dp，保证 Banner 和 Tab 不贴一起

    /** Banner 卡片内垂直 padding（顶部 title → desc → cta） */
    val bannerInnerPadding = Spacing.md   // 12 dp

    /** 终端 Tab 标签内部水平 padding */
    val tabHorizontalPadding = Spacing.sm + Spacing.xs  // 12dp，不让绿点贴边缘

    /** 终端 Tab 内部项间距（绿点 ↔ 标题 ↔ ×） */
    val tabItemSpacingH = Spacing.sm

    /** Tab 标签固定高度（含 outline） */
    val tabHeight = 40.dp

    /** 新建 Tab 按钮尺寸（正方形） */
    val newTabButtonSize = 48.dp

    /** 额外按键（ExtraKey Row）：每个按键高度 */
    val keyHeight = 44.dp   // 原 40，再稍微高一点但不过度

    /** 额外按键：单字按键固定宽度（如箭头 / - / /） */
    val keyShortWidth = 48.dp

    /** 额外按键圆角（原 Radius.md=10dp，按键建议稍小一点） */
    val keyRadius = Radius.md

    /** 额外按键行顶部/底部 padding */
    val keyRowPaddingV = Spacing.xs + 2.dp  // 6 dp，行更紧凑

    /** 提示条内标题圆点尺寸（Banner/accent dot） */
    val accentDotSize = 10.dp

    /** 简洁/完整档切换按钮固定宽度：强制不参与 Row 的 weight 挤压，窄屏绝不被 clip */
    val switcherWidth = 72.dp
}

/**
 * 终端设置页面所有 Card 的共享规格（根绝 elevation+半透明合成黑边）。
 *  与首屏 TerminalFirstRunBanner 统一设计语言：
 *   ① elevation = z0（不要 elevation overlay，它是黑边根因）
 *   ② 用 1dp 软描边（outlineVariant×0.35 或 主题 accent 色×0.35）代替阴影提供边界
 *   ③ 容器背景 alpha 降到 0.16~0.22（避免色压头 + 降低合成 artifact）
 */
@Immutable
object TerminalCardsSpec {
    /** 统一 0 elevation：所有共享卡一律不要阴影层 */
    val Elevation = com.deep.rcode.core.theme.Elevation.z0

    /** 背景软 alpha：普通容器卡片（本地环境、自定义包列表等），对应 SemanticColors.ContainerAlphaSoft 重写降值 */
    const val BgSoftAlpha = 0.16f

    /** 背景略强 alpha：AI 推荐组合条等需要略强调的卡片，对应 ContainerAlphaStrong 重写降值 */
    const val BgStrongAlpha = 0.22f

    /** 1dp 软描边透明度：默认 outlineVariant * 0.35 */
    const val BorderAlpha = 0.35f
}

/**
 * 终端颜色配色方案（Dracula / Light），用于 TerminalView 颜色覆盖。
 *  解决截图中"米白背景 + 极浅前景"的 Dracula 反向 bug：fallback shell 时背景色不会被覆盖。
 */
@Immutable
object TerminalViewTheme {
    /**
     * Dracula Dark：经典 Dracula 终端配色。
     *  —— 架构强约束：background == ansi[0] (Black)；foreground == ansi[7] (White)；cursor == ansi[15] (BrightWhite)。
     *     这样 Termux 无论取 Emulator.defaultFg/Bg 还是取 ansi 索引，颜色完全一致，不会反色。
     */
    @Immutable
    object Dracula {
        val background = Color(0xFF282A36)
        val foreground = Color(0xFFF8F8F2)
        val cursor     = Color(0xFFFFFFFF)
        // ansi[0] Black   MUST == background
        val black   = Color(0xFF282A36)
        val red     = Color(0xFFFF5555)
        val green   = Color(0xFF50FA7B)
        val yellow  = Color(0xFFF1FA8C)
        val blue    = Color(0xFFBD93F9)
        val magenta = Color(0xFFFF79C6)
        val cyan    = Color(0xFF8BE9FD)
        // ansi[7] White   MUST == foreground
        val white   = Color(0xFFF8F8F2)
        val brightBlack   = Color(0xFF6272A4)
        val brightRed     = Color(0xFFFF6E6E)
        val brightGreen   = Color(0xFF69FF94)
        val brightYellow  = Color(0xFFFFF8A3)
        val brightBlue    = Color(0xFFD6ACFF)
        val brightMagenta = Color(0xFFFF92DF)
        val brightCyan    = Color(0xFFA4FFFF)
        // ansi[15] BrightWhite MUST == cursor
        val brightWhite   = Color(0xFFFFFFFF)
    }

    /**
     * Solarized Light：仅当用户显式选亮终端主题 / 浅色系统时启用。
     *  —— 架构强约束同上：background == ansi[0]；foreground == ansi[7]；cursor == ansi[15]。
     *  本次根因修复：之前 ansi[7]=base2=EEE8D5(米白) 和 background=FDF6E3 几乎同色 = 完全看不清。
     *  修法：ansi[7] 用 base01(#586E75 深青灰)，ansi[15] 用 base02(#073642 深蓝黑)，在米黄背景上对比度 ≥ 7:1。
     */
    @Immutable
    object SolarizedLight {
        val background = Color(0xFFFDF6E3)
        val foreground = Color(0xFF586E75)
        val cursor     = Color(0xFF073642)
        // ansi[0] Black MUST == background （Solarized Light 规范 Black=base02 深蓝黑）
        val black   = Color(0xFF073642)
        val red     = Color(0xFFDC322F)
        val green   = Color(0xFF859900)
        val yellow  = Color(0xFFB58900)
        val blue    = Color(0xFF268BD2)
        val magenta = Color(0xFFD33682)
        val cyan    = Color(0xFF2AA198)
        // ansi[7] White MUST == foreground （修：不再是米白 base2，是 Solarized base01=深青灰）
        val white   = Color(0xFF586E75)
        // ansi[8..14] Bright：按 Solarized 规范 base01/base0/…
        val brightBlack   = Color(0xFF002B36) // base03，更深的蓝黑
        val brightRed     = Color(0xFFCB4B16) // orange
        val brightGreen   = Color(0xFF586E75) // base01
        val brightYellow  = Color(0xFF657B83) // base00
        val brightBlue    = Color(0xFF839496) // base0
        val brightMagenta = Color(0xFF6C71C4) // violet
        val brightCyan    = Color(0xFF93A1A1) // base1
        // ansi[15] BrightWhite MUST == cursor （= base02，深对比色，确保 bold/bright 属性仍可读）
        val brightWhite   = Color(0xFF073642)
    }

    /** 字体大小步进默认值 */
    val DefaultFontSp = 14.sp
}

// ═══════════════════════════════════════════════════════════════
// TerminalSkinAdapter：终端色主导的壳 UI 语义颜色适配层
// ═══════════════════════════════════════════════════════════════
//
// 设计目标：壳 UI（TabBar / Banner / Cards / ExtraKeys）的颜色
// 不再直接吃 MaterialTheme.colorScheme，而是从当前 TerminalPalette
// 派生并混合少量 Material 色，与终端画面融为一体。
//
// 数据流：
//   TerminalPalette ← 当前终端主题（Dracula / SolarizedLight）
//        │
//        ▼
//   TerminalSkinAdapter ─→ 产出全部壳 UI 颜色 token
//        │
//        ▼
//   TabBar / Banner / Cards / ExtraKeys  → 只读 Adapter，不吃 raw MaterialTheme
//
// 混合比例：
//   surface        = palette.bg × 92% + Material.surface × 8%
//   surfaceVariant = palette.bg × 80% + Material.surface × 20%
//
// 语义色取自 ANSI 调色板（不再硬编码 #66BB6A / #FFA726）：
//   success = ansi[2]（绿）  warning = ansi[3]（黄橙）
//   error   = ansi[1]（红）  info    = ansi[4]（蓝）
// ═══════════════════════════════════════════════════════════════

/** 颜色混合：将两个 Color 按 weight 比例混合，weight 取值范围 0..1，越大越偏 a。 */
private fun Color.mixWith(b: Color, weight: Float): Color {
    val w = weight.coerceIn(0f, 1f)
    return Color(
        red   = red   * w + b.red   * (1 - w),
        green = green * w + b.green * (1 - w),
        blue  = blue  * w + b.blue  * (1 - w),
        alpha = 1f
    )
}

/**
 * 终端皮肤适配器 —— 壳 UI 颜色唯一入口。
 * 所有 TabBar / Banner / Cards / ExtraKeys / 语义色 全部从这里取，不再吃原始 MaterialTheme.colorScheme。
 *
 * @param palette 当前终端主题调色板（来自 rememberTerminalPalette）
 * @param mixWeight 与 Material.surface 混合比例（0.92 = 92% 终端色 + 8% Material，默认值）
 */
@Immutable
data class TerminalSkin(
    val palette: TerminalPalette,
    val mixWeight: Float = 0.92f
) {
    // ── 容器层级 ──
    /** 壳 UI 主背景色（Tab 栏/键区背景/卡片背景） */
    val surface: Color get() = intermix(palette.containerBg, mixWeight)
    /** 壳 UI 二级背景（TabChip/KeyChip 默认态、卡片变体） */
    val surfaceVariant: Color get() = intermix(palette.containerBg, mixWeight - 0.12f)
    /** 壳 UI 文字色（onSurface） */
    val onSurface: Color get() = palette.defaultForeground
    /** 壳 UI 次要文字色（onSurface 降透明度） */
    val onSurfaceVariant: Color get() = palette.defaultForeground.copy(alpha = 0.78f)

    // ── 选中态/主色 ──
    /** 选中/高亮背景（Tab 选中、主按钮背景、切换按钮选中态）—— 基于 ANSI blue 半透明叠加 */
    val primaryContainer: Color get() = palette.ansi[4].copy(alpha = 0.24f)
    /** 主色前景（onPrimaryContainer） */
    val primaryFg: Color get() = palette.ansi[4]
    /** 主色按钮/选中态上的文字色（自动选高对比色） */
    val onPrimaryContainer: Color get() =
        if (isDarkSurface(primaryContainer)) palette.ansi[15] else palette.ansi[0]

    // ── 语义色（全部取自 ANSI，不再硬编码） ──
    val semanticSuccess: Color get() = palette.ansi[2]   // 绿
    val semanticWarning: Color get() = palette.ansi[3]   // 黄橙
    val semanticError: Color get() = palette.ansi[1]     // 红
    val semanticInfo: Color get() = palette.ansi[4]      // 蓝

    // ── 细分隔线 ──
    /** Tab ↔ Terminal 之间、组件间的细分隔线 */
    val dividingLine: Color get() = palette.ansi[0].copy(alpha = 0.12f)

    // ── ExtraKeys 3 级分层色 ──
    /** A 类修饰键背景（Ctrl/Esc/Tab/C-z/C-l） */
    val keyGroupABg: Color get() = surfaceVariant
    /** A 类修饰键文字 */
    val keyGroupAFg: Color get() = onSurface
    /** A 类修饰键描边 */
    val keyGroupABorder: Color get() = primaryFg.copy(alpha = 0.50f)
    /** A 类修饰键描边宽度 */
    val keyGroupABorderWidth: Dp get() = 1.2.dp

    /** B 类普通键背景（/ - 等） */
    val keyGroupBBg: Color get() = intermix(palette.containerBg, 0.98f)
    /** B 类普通键文字 */
    val keyGroupBFg: Color get() = onSurface.copy(alpha = 0.85f)
    /** B 类普通键描边 */
    val keyGroupBBorder: Color get() = palette.ansi[0].copy(alpha = 0.25f)
    /** B 类普通键描边宽度 */
    val keyGroupBBorderWidth: Dp get() = 0.8.dp

    /** C 类方向键背景（←↑↓→）—— 同 A 类再加导航蓝 */
    val keyGroupCBg: Color get() = intermix(
        keyGroupABg,
        weight = 0.85f
    ).let { base ->
        Color(
            red   = base.red   * 0.88f + primaryFg.red   * 0.12f,
            green = base.green * 0.88f + primaryFg.green * 0.12f,
            blue  = base.blue  * 0.88f + primaryFg.blue  * 0.12f,
            alpha = 1f
        )
    }
    /** C 类方向键描边 —— 同 A 类 */
    val keyGroupCBorder: Color get() = keyGroupABorder
    /** C 类方向键描边宽度 */
    val keyGroupCBorderWidth: Dp get() = keyGroupABorderWidth

    /** D 类切换按钮背景（简洁/完整档切换按钮） */
    val keyGroupDBg: Color get() = primaryContainer
    /** D 类切换按钮文字 */
    val keyGroupDFg: Color get() = onPrimaryContainer
    /** D 类切换按钮描边 */
    val keyGroupDBorder: Color get() = primaryFg.copy(alpha = 0.60f)
    /** D 类切换按钮描边宽度 */
    val keyGroupDBorderWidth: Dp get() = 1.2.dp

    /** Ctrl/Alt 激活态背景 —— 跳 D 级 */
    val ctrlActiveBg: Color get() = primaryContainer
    /** Ctrl/Alt 激活态文字 */
    val ctrlActiveFg: Color get() = onPrimaryContainer
    /** Ctrl/Alt 激活态描边 */
    val ctrlActiveBorder: Color get() = primaryFg.copy(alpha = 0.70f)

    // ── 辅助方法 ──
    private fun intermix(base: Color, weight: Float): Color {
        val w = weight.coerceIn(0f, 1f)
        return Color(
            red   = base.red   * w + 0.5f * (1 - w),
            green = base.green * w + 0.5f * (1 - w),
            blue  = base.blue  * w + 0.5f * (1 - w),
            alpha = 1f
        )
    }

    companion object {
        /** 给定 TerminalPalette 生成 TerminalSkin。 */
        fun from(palette: TerminalPalette, mixWeight: Float = 0.92f): TerminalSkin =
            TerminalSkin(palette, mixWeight)
    }
}

/** 可组合的 TerminalSkin 快捷获取，传入当前 palette 即得对口壳 UI 色。 */
@Composable
fun rememberTerminalSkin(palette: TerminalPalette): TerminalSkin {
    return TerminalSkin.from(palette)
}
