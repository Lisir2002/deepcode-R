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
 * 终端内容区颜色桥：仅 fg/bg/cursor + 256 色扩展。
 * 按你的决策：终端内容区只有两套配色（纯黑底白字 / 纯白底黑字），
 * ANSI 16 色与 256 色一律按"相对亮度阈值"压成 fg 或 bg 二值（极简二值模式）。
 *
 *  @param full256 240 个 ARGB int（index 16-255），此处全部二值化。
 */
@Immutable
data class TerminalPalette(
    val containerBg: Color,
    val defaultForeground: Color,
    val defaultBackground: Color,
    val cursorColor: Color,
    /** ANSI 16 色：这里已按 0..255 全部二值化 (bg/fg) */
    val ansi: List<Color>,
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

// 计算标准相对亮度 Y (sRGB linear)，阈值 0.179 以下视为"深色"
private fun relativeLuminance(r: Float, g: Float, b: Float): Float {
    fun f(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    return 0.2126f * f(r) + 0.7152f * f(g) + 0.0722f * f(b)
}

/** 二值化：任一 ARGB int → 根据亮度映射为 fgInt 或 bgInt（纯二值，无中间态）。 */
private fun quantizeArgb(argb: Int, fgInt: Int, bgInt: Int): Int {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return if (relativeLuminance(r, g, b) >= 0.5f) fgInt else bgInt
}

/** 生成二值化 256 色（Tango 原始 256 → 全部压成 fg/bg 二值）。 */
private fun buildBinary256(fgInt: Int, bgInt: Int): IntArray {
    // 先生成 xterm 标准 256 色原始值 (6 色阶 cube + 24 级灰阶)
    val cubeSteps = intArrayOf(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)
    val rawCube = IntArray(216)
    var i = 0
    for (r in cubeSteps) for (g in cubeSteps) for (b in cubeSteps) {
        rawCube[i++] = (0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()).toInt()
    }
    val rawGrey = IntArray(24)
    for (g in 0 until 24) {
        val v = 8 + g * 10
        rawGrey[g] = (0xFF000000L or (v.toLong() shl 16) or (v.toLong() shl 8) or v.toLong()).toInt()
    }
    val full = IntArray(240)
    System.arraycopy(rawCube, 0, full, 0, 216)
    System.arraycopy(rawGrey, 0, full, 216, 24)
    // 统一二值化
    for (k in full.indices) full[k] = quantizeArgb(full[k], fgInt, bgInt)
    return full
}

private fun buildBinaryAnsi(fg: Color, bg: Color): List<Color> {
    // ANSI 0-7：黑=BG 其余=FG；ANSI 8-15 bright：全部=FG（简化为二值，不保留中间色）
    return listOf(
        bg, fg, fg, fg, fg, fg, fg, fg,
        fg, fg, fg, fg, fg, fg, fg, fg
    )
}

// ─── 黑底白字（PURE_BLACK） ───────────────────────────────────
private val BlackColor = Color(0xFF000000)
private val WhiteColor = Color(0xFFFFFFFF)
private val BlackInt = BlackColor.toArgbInt()
private val WhiteInt = WhiteColor.toArgbInt()

private val PureBlackBinary256 = buildBinary256(fgInt = WhiteInt, bgInt = BlackInt)
private val PureBlackAnsi = buildBinaryAnsi(fg = WhiteColor, bg = BlackColor)

val PureBlackPalette: TerminalPalette = TerminalPalette(
    containerBg = BlackColor,
    defaultForeground = WhiteColor,
    defaultBackground = BlackColor,
    cursorColor = WhiteColor,
    ansi = PureBlackAnsi,
    full256 = PureBlackBinary256
)

// ─── 白底黑字（PURE_WHITE） ───────────────────────────────────
private val PureWhiteBinary256 = buildBinary256(fgInt = BlackInt, bgInt = WhiteInt)
private val PureWhiteAnsi = buildBinaryAnsi(fg = BlackColor, bg = WhiteColor)

val PureWhitePalette: TerminalPalette = TerminalPalette(
    containerBg = WhiteColor,
    defaultForeground = BlackColor,
    defaultBackground = WhiteColor,
    cursorColor = BlackColor,
    ansi = PureWhiteAnsi,
    full256 = PureWhiteBinary256
)

/**
 * 选主题：
 *  - PURE_BLACK → 强制黑底白字
 *  - PURE_WHITE → 强制白底黑字
 *  - SYSTEM     → 跟随系统暗/亮
 *  旧 DataStore 值 DRACULA_DARK / SOLARIZED_LIGHT 已在 settingsRepo.themeFlow 阶段
 *  自动映射为 PURE_BLACK / PURE_WHITE，此处不再出现。
 */
@Composable
fun rememberTerminalPalette(
    settingsRepo: TerminalSettingsRepository = hiltRepo()
): TerminalPalette {
    val theme: TerminalTheme by settingsRepo.themeFlow.collectAsStateWithLifecycle(initialValue = TerminalTheme.SYSTEM)
    return when (theme) {
        TerminalTheme.PURE_BLACK -> PureBlackPalette
        TerminalTheme.PURE_WHITE -> PureWhitePalette
        TerminalTheme.SYSTEM -> if (isSystemInDarkTheme()) PureBlackPalette else PureWhitePalette
    }
}

/** 通过 Hilt EntryPoint 从 @ApplicationContext 拿 TerminalSettingsRepository。 */
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
