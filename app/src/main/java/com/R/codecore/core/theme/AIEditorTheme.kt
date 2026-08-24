package com.R.codecore.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 10.dp
    val lg = 14.dp
    val pill = 999.dp
}

object Brand {
    val Blue = Color(0xFF2563EB)
    val Sky = Color(0xFF38BDF8)
    val Ice = Color(0xFFEFF6FF)
    val IconGray = Color(0xFF424242)
    val PageBg = Color(0xFFFAFAFA)

    /**
     * 语义色 - 成功/已就绪/在线 的统一状态绿（所有"状态小圆点"都走这一对，不再走品牌蓝）。
     * 色值参考 Material Design 3 Emerald 600 / Material You 标准绿，
     * 亮/暗模式下对比度均 ≥ 4.5:1，避免与品牌蓝（primary）混淆。
     */
    object StatusGreen {
        /** 亮色模式用：#16A34A（emerald-600），白底 7.1:1 对比度 */
        val Light = Color(0xFF16A34A)
        /** 暗色模式用：#4ADE80（emerald-400），#0D1B2E 底 7.8:1 对比度 */
        val Dark  = Color(0xFF4ADE80)
    }
}

/**
 * 聊天输入区功能语义色板：每个功能一个专属色相，避免图标重复与撞色。
 * 每个色都有 Light / Dark 双取值，配合 [LocalAppDarkMode] 实现日夜间切换。
 * 背景色（light/dark）与前景色（onLight/onDark）成对出现，保证对比度 ≥ 4.5:1。
 */
object ChatAccent {
    data class Tone(val light: Color, val dark: Color, val onLight: Color, val onDark: Color)

    /** 构建模式 · 琥珀（建造/锤子） */
    val Build = Tone(Color(0xFFB45309), Color(0xFFFBBF24), Color.White, Color(0xFF451A03))
    /** 规划模式 · 蓝（计划/地图） */
    val Plan = Tone(Color(0xFF2563EB), Color(0xFF60A5FA), Color.White, Color(0xFF0B3B76))
    /** 自动模式 · 青绿（自动/火箭） */
    val Auto = Tone(Color(0xFF0D9488), Color(0xFF2DD4BF), Color.White, Color(0xFF0B3B2E))
    /** 思考强度 · 紫罗兰（深度思考/大脑） */
    val Reasoning = Tone(Color(0xFF7C3AED), Color(0xFFA78BFA), Color.White, Color(0xFF2E1065))
    /** 技能 · 粉（技能/魔法星） */
    val Skill = Tone(Color(0xFFDB2777), Color(0xFFF472B6), Color.White, Color(0xFF500724))
}

/**
 * 消息流色线分档色板（Claude Code 风格日志流的淡彩分层）。
 * 每类消息一条淡色线，亮暗双取值（[LocalAppDarkMode] 切换）：
 * - [Content] 正文：淡主色蓝，整行线横贯一轮回复顶部；
 * - [Reasoning] 思考：淡紫，子块左侧短条 + 竖条贯穿；
 * - [Tool] 工具：淡蓝灰，子块左侧短条 + 竖条贯穿；
 * - [Skill] 技能：淡粉，子块左侧短条 + 竖条贯穿。
 * 色值刻意压低饱和度，避免回到旧版彩色卡片的花哨观感。
 */
object MessageAccent {
    data class Tone(val lightLine: Color, val darkLine: Color, val lightBg: Color, val darkBg: Color)

    /** 正文 · 淡主色蓝（blue-300/400） */
    val Content = Tone(
        lightLine = Color(0xFF93C5FD),
        darkLine = Color(0xFF60A5FA),
        lightBg = Color(0xFFEFF6FF),
        darkBg = Color(0xFF16283F)
    )
    /** 思考 · 淡紫（violet-300/400） */
    val Reasoning = Tone(
        lightLine = Color(0xFFC4B5FD),
        darkLine = Color(0xFFA78BFA),
        lightBg = Color(0xFFF5F3FF),
        darkBg = Color(0xFF221B3E)
    )
    /** 工具 · 淡蓝灰（slate-400/300） */
    val Tool = Tone(
        lightLine = Color(0xFF94A3B8),
        darkLine = Color(0xFF94A3B8),
        lightBg = Color(0xFFF1F5F9),
        darkBg = Color(0xFF1E293B)
    )
    /** 技能 · 淡粉（pink-300/400） */
    val Skill = Tone(
        lightLine = Color(0xFFF9A8D4),
        darkLine = Color(0xFFF472B6),
        lightBg = Color(0xFFFDF2F8),
        darkBg = Color(0xFF3A1E2E)
    )
    /** 贯穿整轮回复的长竖条 · 靛蓝（indigo-300/400），统一模型回复的纵向锚点 */
    val Spine = Tone(
        lightLine = Color(0xFF818CF8),
        darkLine = Color(0xFFA5B4FC),
        lightBg = Color.Transparent,
        darkBg = Color.Transparent
    )
}

/** 当前日夜模式下的色线颜色。 */
@Composable
fun MessageAccent.Tone.resolveLine(): Color = if (LocalAppDarkMode.current) darkLine else lightLine

/** 当前日夜模式下的淡背景色（气泡/浅底）。 */
@Composable
fun MessageAccent.Tone.resolveBg(): Color = if (LocalAppDarkMode.current) darkBg else lightBg

/** 当前日夜模式下的语义背景色（跟随应用主题设置 LocalAppDarkMode）。 */
@Composable
fun ChatAccent.Tone.resolve(): Color = if (LocalAppDarkMode.current) dark else light

/** 当前日夜模式下语义色上的前景色（文字 / 图标）。 */
@Composable
fun ChatAccent.Tone.resolveOn(): Color = if (LocalAppDarkMode.current) onDark else onLight

val LocalSpacing = staticCompositionLocalOf { Spacing }

/**
 * 应用实际生效的"当前是否为暗模式"。
 * 由 MainActivity 根据 ThemeSettingsRepository.themeModeFlow 计算后写入：
 *   AUTO  → 跟随系统
 *   DARK  → true
 *   LIGHT → false
 * 终端内容配色、各子页面"跟随程序"都统一读这一个 CompositionLocal，
 * 保证 APP 自己切主题（强制黑/强制白）时，所有"跟随程序"的子控件同步变化，
 * 而不是去读 android.content.res.Configuration 的系统 uiMode。
 */
val LocalAppDarkMode = androidx.compose.runtime.staticCompositionLocalOf<Boolean> { false }

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0F3A63),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    tertiary = Color(0xFF22C55E),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0D1B2E),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF13273F),
    onSurfaceVariant = Color(0xFFB8C7DA),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF44617F),
    outlineVariant = Color(0xFF223B57),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA)
)

private val LightColorScheme = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B3B76),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF16A34A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF4FF),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color.White,
    outline = Color(0xFFBBD7F2),
    outlineVariant = Color(0xFFDCEBFA),
    error = Color(0xFFDC2626),

    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    )
}

@Composable
fun AIEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
