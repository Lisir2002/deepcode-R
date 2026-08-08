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
 * 终端 16 色调色板 + 外层容器背景 + fg/bg/cursor + 256 色扩展。
 *
 *  TerminalPalette 是应用层到 Termux 底层 TerminalView 的"颜色桥"：
 *  - 应用层: TerminalViewTheme 定义两套完整配色（Dracula / SolarizedLight）
 *  - 桥接: rememberTerminalPalette() 把主题色打包成 TerminalPalette
 *  - 底层: applyTerminalPalette() 把 TerminalPalette 写入 view.mEmulator.mColors.mCurrentColors[0..258]
 *  - 不再用反射，全部走 public 字段链
 *
 *  @param full256 可选 256 色扩展数组（index 16-255，共 240 个 int），
 *                 提供后 `applyTerminalPalette` 会一并写入。
 *                 不提供则只写入前 16 色 ANSI + fg/bg/cursor（256 色用 Termux 默认值）。
 */
@Immutable
data class TerminalPalette(
    val containerBg: Color,
    val defaultForeground: Color,
    val defaultBackground: Color,
    val cursorColor: Color,
    /** ANSI 16 色：index 0..15 严格对应 Termux mColors 结构 */
    val ansi: List<Color>,
    /** 256 色调色板扩展 (index 16-255)，null 时不写入 256 色 */
    val full256: IntArray? = null
) {
    fun toAnsiIntArray(): IntArray = IntArray(16) { i -> ansi[i].toArgbInt() }

    val defaultForegroundInt: Int get() = defaultForeground.toArgbInt()
    val defaultBackgroundInt: Int get() = defaultBackground.toArgbInt()
    val cursorInt: Int get() = cursorColor.toArgbInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalPalette) return false
        return containerBg == other.containerBg &&
                defaultForeground == other.defaultForeground &&
                defaultBackground == other.defaultBackground &&
                cursorColor == other.cursorColor &&
                ansi == other.ansi &&
                full256.contentEquals(other.full256)
    }

    override fun hashCode(): Int {
        var result = containerBg.hashCode()
        result = 31 * result + defaultForeground.hashCode()
        result = 31 * result + defaultBackground.hashCode()
        result = 31 * result + cursorColor.hashCode()
        result = 31 * result + ansi.hashCode()
        result = 31 * result + (full256?.contentHashCode() ?: 0)
        return result
    }
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

// ──────────────────────────────────────────────────────────
// 256 色 cube 生成器：基于给定"6 色阶"生成 216 色 cube (index 16-231)
//   标准 xterm 6 色阶: 0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff
//   每个主题可自定义色阶来匹配主题调性
// ──────────────────────────────────────────────────────────

/** 基于 6 级色阶生成 216 色 cube（index 16-231 的 6×6×6 RGB 立方体）。 */
private fun buildColorCube(steps: IntArray = intArrayOf(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)): IntArray {
    val cube = IntArray(216)
    var i = 0
    for (r in steps) for (g in steps) for (b in steps) {
        cube[i++] = (0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()).toInt()
    }
    return cube
}

/** 基于 24 级灰阶生成灰阶渐变（index 232-255）。 */
private fun buildGreyRamp(): IntArray {
    val ramp = IntArray(24)
    for (i in 0 until 24) {
        val v = 8 + i * 10  // 0x08, 0x12, 0x1c, ... 0xee
        ramp[i] = (0xFF000000L or (v.toLong() shl 16) or (v.toLong() shl 8) or v.toLong()).toInt()
    }
    return ramp
}

/** 扩展 256 色数组 (index 16-255)：cube 216 + grey 24。 */
private fun buildFull256(cube: IntArray, grey: IntArray): IntArray {
    val full = IntArray(240)  // 256 - 16 = 240
    System.arraycopy(cube, 0, full, 0, 216)
    System.arraycopy(grey, 0, full, 216, 24)
    return full
}

// ── Dracula 256 色扩展 ──
// Dracula 色阶精度：背景紫灰 #282A36 → 前景亮白 #F8F8F2 → 中间结合 Dracula 色相
private val DraculaCubeSteps = intArrayOf(0x1E, 0x44, 0x62, 0x8A, 0xBD, 0xF8)
private val DraculaCube = buildColorCube(DraculaCubeSteps)
private val DraculaGrey = buildGreyRamp()
private val Dracula256 = buildFull256(DraculaCube, DraculaGrey)

// ── SolarizedLight 256 色扩展 ──
// Solarized 色阶精度：基于 Solarized 的 base03→base3 色调
// 暖色阶偏米黄系，冷色阶偏青蓝系
private val SolarizedCubeSteps = intArrayOf(0x00, 0x2B, 0x58, 0x83, 0xB5, 0xEE)
private val SolarizedCube = buildColorCube(SolarizedCubeSteps)
private val SolarizedGrey = buildGreyRamp()
private val Solarized256 = buildFull256(SolarizedCube, SolarizedGrey)

val DraculaTerminalPalette: TerminalPalette = run {
    val d = TerminalViewTheme.Dracula
    TerminalPalette(
        containerBg = d.background,
        defaultForeground = d.foreground,
        defaultBackground = d.background,
        cursorColor = d.cursor,
        ansi = buildAnsi(
            d.black, d.red, d.green, d.yellow, d.blue, d.magenta, d.cyan, d.white,
            d.brightBlack, d.brightRed, d.brightGreen, d.brightYellow, d.brightBlue, d.brightMagenta, d.brightCyan, d.brightWhite
        ),
        full256 = Dracula256
    )
}

val SolarizedTerminalPalette: TerminalPalette = run {
    val l = TerminalViewTheme.SolarizedLight
    TerminalPalette(
        containerBg = l.background,
        defaultForeground = l.foreground,
        defaultBackground = l.background,
        cursorColor = l.cursor,
        ansi = buildAnsi(
            l.black, l.red, l.green, l.yellow, l.blue, l.magenta, l.cyan, l.white,
            l.brightBlack, l.brightRed, l.brightGreen, l.brightYellow, l.brightBlue, l.brightMagenta, l.brightCyan, l.brightWhite
        ),
        full256 = Solarized256
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