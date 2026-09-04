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
import kotlinx.coroutines.delay

private const val TYPEWRITER_CARET = "▍"

/**
 * 打字机（分子组 · AppTypewriterText）：逐字显现文本并带光标，营造"正在生成 / 流式输出"
 * 的 AI 会话感；[active] 关闭时直接显示全文。
 */
@Composable
fun AppTypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = true,
    showCaret: Boolean = true,
    charIntervalMillis: Long = 28,
    initialDelayMillis: Long = 0,
) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text, active) {
        if (!active) {
            shown = text.length
            return@LaunchedEffect
        }
        shown = 0
        if (initialDelayMillis > 0) delay(initialDelayMillis)
        while (shown < text.length) {
            shown++
            delay(charIntervalMillis)
        }
    }
    val visible = text.take(shown)
    val caretVisible = showCaret && active && shown < text.length
    Text(
        text = if (caretVisible) "$visible$TYPEWRITER_CARET" else visible,
        modifier = modifier,
        style = style,
        color = color,
    )
}