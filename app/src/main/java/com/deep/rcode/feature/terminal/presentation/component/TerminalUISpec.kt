package com.deep.rcode.feature.terminal.presentation.component

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
}

/**
 * 终端首屏 Banner 规格：
 *  容器未初始化  accent = Warning
 *  Python 缺失    accent = Info (Secondary)
 *  背景 alpha 走 SemanticColors 统一（避免出现截图中"粗边框 + 超大圆角 + alpha 太淡合成出粗边"）
 */
@Immutable
object TerminalBannerSpec {
    /** 容器未初始化强调色：柔和橙（Warning）*/
    val ContainerAccent: Color = Color(0xFFFFA726)
    /** Python 缺失强调色：主题二级色（亮/暗/动态色自适应） */
    fun pythonAccent(secondary: Color): Color = secondary
}

/**
 * 终端颜色配色方案（Dracula / Light），用于 TerminalView 颜色覆盖。
 *  解决截图中"米白背景 + 极浅前景"的 Dracula 反向 bug：fallback shell 时背景色不会被覆盖。
 */
@Immutable
object TerminalViewTheme {
    /** Dracula Dark：截图要求深色终端 */
    @Immutable
    object Dracula {
        val background = Color(0xFF282A36)
        val foreground = Color(0xFFF8F8F2)
        val cursor = Color(0xFFF8F8F2)
        val selection = Color(0xFF44475A)
        val black   = Color(0xFF21222C)
        val red     = Color(0xFFFF5555)
        val green   = Color(0xFF50FA7B)
        val yellow  = Color(0xFFF1FA8C)
        val blue    = Color(0xFFBD93F9)
        val magenta = Color(0xFFFF79C6)
        val cyan    = Color(0xFF8BE9FD)
        val white   = Color(0xFFBFBFBF)
        val brightBlack   = Color(0xFF6272A4)
        val brightRed     = Color(0xFFFF6E6E)
        val brightGreen   = Color(0xFF69FF94)
        val brightYellow  = Color(0xFFFFF8A3)
        val brightBlue    = Color(0xFFD6ACFF)
        val brightMagenta = Color(0xFFFF92DF)
        val brightCyan    = Color(0xFFA4FFFF)
        val brightWhite   = Color(0xFFFFFFFF)
    }

    /** Solarized Light：仅当用户显式选亮终端主题时启用 */
    @Immutable
    object SolarizedLight {
        val background = Color(0xFFFDF6E3)
        val foreground = Color(0xFF586E75)
        val cursor = Color(0xFF586E75)
        val selection = Color(0xFFEEE8D5)
        val black   = Color(0xFF073642)
        val red     = Color(0xFFDC322F)
        val green   = Color(0xFF859900)
        val yellow  = Color(0xFFB58900)
        val blue    = Color(0xFF268BD2)
        val magenta = Color(0xFFD33682)
        val cyan    = Color(0xFF2AA198)
        val white   = Color(0xFFEEE8D5)
        val brightBlack   = Color(0xFF002B36)
        val brightRed     = Color(0xFFCB4B16)
        val brightGreen   = Color(0xFF586E75)
        val brightYellow  = Color(0xFF657B83)
        val brightBlue    = Color(0xFF839496)
        val brightMagenta = Color(0xFF6C71C4)
        val brightCyan    = Color(0xFF93A1A1)
        val brightWhite   = Color(0xFFFDF6E3)
    }

    /** 字体大小步进默认值 */
    val DefaultFontSp = 14.sp
}
