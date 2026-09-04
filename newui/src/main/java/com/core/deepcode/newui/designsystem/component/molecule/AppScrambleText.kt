package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlin.random.Random
import kotlinx.coroutines.delay

private const val SCRAMBLE_CHARS = "!<>-_\\/[]{}=+*^?#________"

/**
 * 乱码重组（分子组 · AppScrambleText）：激活时文本字符逐一由随机符号翻转为目标文字，
 * 科技感标题 / 加载占位 / Agent 正在解析状态的俏皮点缀。
 */
@Composable
fun AppScrambleText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = true,
    intervalMillis: Long = 45,
) {
    var revealed by remember(text) { mutableIntStateOf(0) }
    var tick by remember(text) { mutableIntStateOf(0) }

    LaunchedEffect(text, active) {
        if (!active) {
            revealed = text.length
            return@LaunchedEffect
        }
        revealed = 0
        while (revealed < text.length) {
            revealed++
            tick++
            delay(intervalMillis)
        }
    }

    val display = remember(revealed, tick) {
        buildString {
            text.forEachIndexed { i, c ->
                append(if (i < revealed) c else SCRAMBLE_CHARS.random(Random))
            }
        }
    }
    Text(text = display, modifier = modifier, style = style, color = color)
}