package com.R.codecore.feature.terminal.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing

/** 计算标准相对亮度 Y (sRGB linear)，阈值 0.179 以下视为"深色" */
private fun Color.relativeLuminance(): Float {
    fun f(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    return 0.2126f * f(red) + 0.7152f * f(green) + 0.0722f * f(blue)
}

/**
 * 终端页面 UI 规格单一事实源。
 * 终端所有卡片、Tab、按键、行间距全部从这里拿 tokens，避免截图里出现不一致。
 */
@Immutable
object TerminalLayout {
    /** Banner → TabBar 之间的节奏（Banner 与 Tab 之间保留轻微缝隙，避免贴） */
    val sectionSpacingV: Dp = Spacing.sm  // 8 dp
    /** TabBar → TerminalSurface 之间不再留空白：仅 1dp 细分隔线 + 0 padding */
    val tabTerminalDivider: Dp = 1.dp
    /** TerminalSurface → ExtraKeysRow 之间不留额外空白（0） */
    val terminalKeysDivider: Dp = 0.dp

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
    val Elevation = com.R.codecore.core.theme.Elevation.z0

    /** 背景软 alpha：普通容器卡片（本地环境、自定义包列表等） */
    const val BgSoftAlpha = 0.16f

    /** 背景略强 alpha：AI 推荐组合条等需要略强调的卡片 */
    const val BgStrongAlpha = 0.22f

    /** 1dp 软描边透明度：默认 outlineVariant * 0.35 */
    const val BorderAlpha = 0.35f
}

/** 终端视图字体大小步进默认值（兼容旧 API） */
object TerminalViewTheme {
    val DefaultFontSp = 14.sp
}

/**
 * 终端外壳 UI 颜色（TabBar/Banner/ExtraKeys/语义色）单一事实源。
 *
 *  【架构约束】：终端内容区配色（纯黑底白字/白底黑字，仅终端画面内部）
 *  与外壳 UI（TabBar、Banner、ExtraKeys 等）完全解耦：
 *    - 终端画面内：TerminalPalette（仅 fg/bg/cursor/256 色，二值化）
 *    - 终端外壳：直接读取 MaterialTheme.colorScheme.*（跟随软件主题）
 *
 *  所有值都是 @Composable getter，调用处用 `TerminalSkin.surface`
 *  即可拿到与当前 Material 主题一致的颜色。
 */
@Immutable
object TerminalSkin {

    // ── 容器层级 ──
    val surface: Color @Composable get() = MaterialTheme.colorScheme.surface
    val surfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val onSurface: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    // ── 选中态/主色 ──
    val primaryContainer: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val primaryFg: Color @Composable get() = MaterialTheme.colorScheme.primary
    val onPrimaryContainer: Color @Composable get() =
        MaterialTheme.colorScheme.onPrimaryContainer

    // ── 语义色（来自 Material theme） ──
    val semanticSuccess: Color @Composable get() = MaterialTheme.colorScheme.primary
    val semanticWarning: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val semanticError: Color @Composable get() = MaterialTheme.colorScheme.error
    val semanticInfo: Color @Composable get() = MaterialTheme.colorScheme.secondary

    // ── 细分隔线 ──
    val dividingLine: Color @Composable get() =
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)

    // ── ExtraKeys 4 级分组色 ──
    val keyGroupABg: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val keyGroupAFg: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val keyGroupABorder: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
    val keyGroupABorderWidth: Dp get() = 1.2.dp

    val keyGroupBBg: Color @Composable get() =
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val keyGroupBFg: Color @Composable get() =
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val keyGroupBBorder: Color @Composable get() =
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val keyGroupBBorderWidth: Dp get() = 0.8.dp

    val keyGroupCBg: Color @Composable get() =
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f)
    val keyGroupCBorder: Color @Composable get() = keyGroupABorder
    val keyGroupCBorderWidth: Dp get() = keyGroupABorderWidth

    val keyGroupDBg: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val keyGroupDFg: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val keyGroupDBorder: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
    val keyGroupDBorderWidth: Dp get() = 1.2.dp

    /** Ctrl/Alt 激活态背景 —— 跳 D 级 */
    val ctrlActiveBg: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val ctrlActiveFg: Color @Composable get() =
        MaterialTheme.colorScheme.onPrimaryContainer
    val ctrlActiveBorder: Color @Composable get() =
        MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
}

/** 兼容旧 API：不再需要 remember，直接用 TerminalSkin.*；为了不改动所有调用处的调用形式，
 *  返回一个"代理对象"把所有 Composable getter 映射到 TerminalSkin 对象。
 *  （因为我们把 TerminalSkin 改成了 object 带 @Composable getters，而外部调用
 *   `val skin = rememberTerminalSkin(palette)` 后面写 `skin.surface`，
 *   此时 .surface 不是 composable 调用无法直接取。因此用一个 data class 存静态拷贝。） */
@Composable
fun rememberTerminalSkin(@Suppress("UNUSED_PARAMETER") palette: TerminalPalette): TerminalSkinSnapshot {
    return TerminalSkinSnapshot(
        surface = MaterialTheme.colorScheme.surface,
        surfaceVariant = MaterialTheme.colorScheme.surfaceVariant,
        onSurface = MaterialTheme.colorScheme.onSurface,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primaryContainer = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
        primaryFg = MaterialTheme.colorScheme.primary,
        onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer,
        semanticSuccess = MaterialTheme.colorScheme.primary,
        semanticWarning = MaterialTheme.colorScheme.tertiary,
        semanticError = MaterialTheme.colorScheme.error,
        semanticInfo = MaterialTheme.colorScheme.secondary,
        dividingLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
        keyGroupABg = MaterialTheme.colorScheme.surfaceVariant,
        keyGroupAFg = MaterialTheme.colorScheme.onSurface,
        keyGroupABorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.50f),
        keyGroupBBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        keyGroupBFg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        keyGroupBBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        keyGroupCBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f),
        keyGroupCBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.50f),
        keyGroupDBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        keyGroupDFg = MaterialTheme.colorScheme.onPrimaryContainer,
        keyGroupDBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.60f),
        ctrlActiveBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ctrlActiveFg = MaterialTheme.colorScheme.onPrimaryContainer,
        ctrlActiveBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.70f),
        dark = MaterialTheme.colorScheme.background.relativeLuminance() < 0.30f
    )
}

/** 静态颜色快照，方便 shell UI 组件里 `skin.xxx` 非 composable 读取 */
@Immutable
data class TerminalSkinSnapshot(
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primaryContainer: Color,
    val primaryFg: Color,
    val onPrimaryContainer: Color,
    val semanticSuccess: Color,
    val semanticWarning: Color,
    val semanticError: Color,
    val semanticInfo: Color,
    val dividingLine: Color,
    val keyGroupABg: Color,
    val keyGroupAFg: Color,
    val keyGroupABorder: Color,
    val keyGroupBBg: Color,
    val keyGroupBFg: Color,
    val keyGroupBBorder: Color,
    val keyGroupCBg: Color,
    val keyGroupCBorder: Color,
    val keyGroupDBg: Color,
    val keyGroupDFg: Color,
    val keyGroupDBorder: Color,
    val ctrlActiveBg: Color,
    val ctrlActiveFg: Color,
    val ctrlActiveBorder: Color,
    val dark: Boolean,
) {
    val keyGroupABorderWidth: Dp get() = 1.2.dp
    val keyGroupBBorderWidth: Dp get() = 0.8.dp
    val keyGroupCBorderWidth: Dp get() = 1.2.dp
    val keyGroupDBorderWidth: Dp get() = 1.2.dp
}
