package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 把 Compose Color 转成 Android 标准 0xAARRGGBB Int（TerminalView 调色板格式）。
 */
private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 终端 16 色调色板 + 外层容器背景。
 *
 * 两种预设：
 *  - 暗色主题：深色背景（仿 Dracula-ish 色），与 Termux 16 色标准配合
 *  - 浅色主题：柔和米白背景，前景高对比，避免浅色主题下整个终端一大块突兀的纯黑
 */
@Immutable
data class TerminalPalette(
    val containerBg: Color,
    val foreground: Color,
    val cursorColor: Color,
    val selectionBg: Color,
    // ANSI 16 色（index 0..15），传给 TerminalView 的 mColors 映射
    val ansi: List<Color>
) {
    /** 把调色板编码成 Termux TerminalView 需要的 int[16]。 */
    fun toAnsiIntArray(): IntArray = IntArray(16) { i ->
        ansi[i].toArgbInt()
    }
}

private val AnsiDark = listOf(
    // 0-7 普通（略暗）
    Color(0xFF21222C), // Black
    Color(0xFFFF5555), // Red
    Color(0xFF50FA7B), // Green
    Color(0xFFF1FA8C), // Yellow
    Color(0xFFBD93F9), // Blue
    Color(0xFFFF79C6), // Magenta
    Color(0xFF8BE9FD), // Cyan
    Color(0xFFF8F8F2), // White
    // 8-15 高亮（更亮）
    Color(0xFF6272A4), // Bright Black
    Color(0xFFFF6E6E), // Bright Red
    Color(0xFF69FF94), // Bright Green
    Color(0xFFFFF59E), // Bright Yellow
    Color(0xFFD6ACFF), // Bright Blue
    Color(0xFFFF92DF), // Bright Magenta
    Color(0xFFA4FFFF), // Bright Cyan
    Color(0xFFFFFFFF)  // Bright White
)

private val AnsiLight = listOf(
    // 浅色终端适配：整体偏饱和的深色，保证在米白背景上可读
    Color(0xFF1F2937), // Black
    Color(0xFFDC2626), // Red
    Color(0xFF16A34A), // Green
    Color(0xFFCA8A04), // Yellow
    Color(0xFF2563EB), // Blue
    Color(0xFFDB2777), // Magenta
    Color(0xFF0891B2), // Cyan
    Color(0xFF4B5563), // White (dim foreground)
    // 高亮
    Color(0xFF6B7280), // Bright Black
    Color(0xFFB91C1C), // Bright Red
    Color(0xFF15803D), // Bright Green
    Color(0xFFA16207), // Bright Yellow
    Color(0xFF1D4ED8), // Bright Blue
    Color(0xFFBE185D), // Bright Magenta
    Color(0xFF0E7490), // Bright Cyan
    Color(0xFF111827)  // Bright White (high-contrast foreground)
)

val DarkTerminalPalette = TerminalPalette(
    containerBg = Color(0xFF14141A),
    foreground = Color(0xFFF8F8F2),
    cursorColor = Color(0xFFF8F8F2),
    selectionBg = Color(0xFF44475A),
    ansi = AnsiDark
)

val LightTerminalPalette = TerminalPalette(
    containerBg = Color(0xFFF7F5F1),  // 米白，不纯白，减少眩光
    foreground = Color(0xFF1F2937),
    cursorColor = Color(0xFF2563EB),
    selectionBg = Color(0xFFBFDBFE),
    ansi = AnsiLight
)

@Composable
fun rememberTerminalPalette(): TerminalPalette {
    // 与 Material3 主题同步：深色用 Dracula-ish，浅色用米白高对比
    return if (isSystemInDarkTheme()) DarkTerminalPalette else LightTerminalPalette
}

/** 供 ViewModel 直接查询（非 Compose 线程下）：读取 Theme 中 isSystemInDarkTheme 用 MaterialTheme.colorScheme.surface 的明度判断。 */
fun isDarkSurface(bgColor: Color): Boolean {
    // Luma 判断
    val luma = 0.299 * (bgColor.red * 255) +
            0.587 * (bgColor.green * 255) +
            0.114 * (bgColor.blue * 255)
    return luma < 160
}
