package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deep.rcode.feature.terminal.data.repository.TerminalSettingsRepository
import com.deep.rcode.feature.terminal.data.repository.TerminalTheme
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext

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
 * 终端 16 色调色板 + 外层容器背景 + defaultFg/defaultBg/cursor。
 *  直接吃 TerminalViewTheme，这样颜色定义只有一份（TerminalUISpec.kt），一改全改。
 *  再也不会出现"米白背景 + Dracula 前景"的反色 bug。
 */
@Immutable
data class TerminalPalette(
    val containerBg: Color,
    val defaultForeground: Color,
    val defaultBackground: Color,
    val cursorColor: Color,
    val selectionBg: Color,
    /** ANSI 16 色：index 0..15 严格对应 Termux mColors 结构 */
    val ansi: List<Color>
) {
    fun toAnsiIntArray(): IntArray = IntArray(16) { i -> ansi[i].toArgbInt() }

    val defaultForegroundInt: Int get() = defaultForeground.toArgbInt()
    val defaultBackgroundInt: Int get() = defaultBackground.toArgbInt()
    val cursorInt: Int get() = cursorColor.toArgbInt()
}

private fun buildAnsi(
    black: Color, red: Color, green: Color, yellow: Color,
    blue: Color, magenta: Color, cyan: Color, white: Color,
    brightBlack: Color, brightRed: Color, brightGreen: Color, brightYellow: Color,
    brightBlue: Color, brightMagenta: Color, brightCyan: Color, brightWhite: Color
): List<Color> = listOf(
    black, red, green, yellow, blue, magenta, cyan, white,
    brightBlack, brightRed, brightGreen, brightYellow, brightBlue, brightMagenta, brightCyan, brightWhite
)

val DraculaTerminalPalette: TerminalPalette = run {
    val d = TerminalViewTheme.Dracula
    TerminalPalette(
        containerBg = d.background,
        defaultForeground = d.foreground,
        defaultBackground = d.background,
        cursorColor = d.cursor,
        selectionBg = d.selection,
        ansi = buildAnsi(
            d.black, d.red, d.green, d.yellow, d.blue, d.magenta, d.cyan, d.white,
            d.brightBlack, d.brightRed, d.brightGreen, d.brightYellow, d.brightBlue, d.brightMagenta, d.brightCyan, d.brightWhite
        )
    )
}

val SolarizedTerminalPalette: TerminalPalette = run {
    val l = TerminalViewTheme.SolarizedLight
    TerminalPalette(
        containerBg = l.background,
        defaultForeground = l.foreground,
        defaultBackground = l.background,
        cursorColor = l.cursor,
        selectionBg = l.selection,
        ansi = buildAnsi(
            l.black, l.red, l.green, l.yellow, l.blue, l.magenta, l.cyan, l.white,
            l.brightBlack, l.brightRed, l.brightGreen, l.brightYellow, l.brightBlue, l.brightMagenta, l.brightCyan, l.brightWhite
        )
    )
}

/**
 * 选主题：
 *  - 用户 TerminalTheme.DRACULA_DARK → 强制 Dracula
 *  - 用户 TerminalTheme.SOLARIZED_LIGHT → 强制 SolarizedLight
 *  - 用户 TerminalTheme.SYSTEM → 跟随系统 isSystemInDarkTheme
 */
@Composable
fun rememberTerminalPalette(
    settingsRepo: TerminalSettingsRepository = hiltRepo()
): TerminalPalette {
    val theme: TerminalTheme by settingsRepo.themeFlow.collectAsStateWithLifecycle(initialValue = TerminalTheme.SYSTEM)
    return when (theme) {
        TerminalTheme.DRACULA_DARK -> DraculaTerminalPalette
        TerminalTheme.SOLARIZED_LIGHT -> SolarizedTerminalPalette
        TerminalTheme.SYSTEM -> if (isSystemInDarkTheme()) DraculaTerminalPalette else SolarizedTerminalPalette
    }
}

/** 通过 Hilt EntryPoint 从 @ApplicationContext 拿 TerminalSettingsRepository（避免每处都要 VM 传参）。 */
@Composable
private fun hiltRepo(): TerminalSettingsRepository {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val entryPoint = EntryPointAccessors.fromApplication(
        ctx,
        TerminalPaletteEntryPoint::class.java
    )
    return entryPoint.terminalSettingsRepository()
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TerminalPaletteEntryPoint {
    fun terminalSettingsRepository(): TerminalSettingsRepository
}

/** 辅助：Compose Color 明度判断（避免未来又出现 light theme 上用浅前景）。 */
fun isDarkSurface(bgColor: Color): Boolean {
    val luma = 0.299 * (bgColor.red * 255) +
            0.587 * (bgColor.green * 255) +
            0.114 * (bgColor.blue * 255)
    return luma < 160
}
