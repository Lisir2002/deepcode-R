package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow

/**
 * 可横向滚动单行文本：内容宽度超出可用范围时横向滚动（轮播）展示，
 * 替代「一行省略号」截断，保证完整内容不丢失。
 *
 * 适合工具名、URL、路径、命令摘要等单行长内容。
 */
@Composable
internal fun HorizontalScrollableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    monospace: Boolean = false
) {
    val effectiveStyle = if (monospace) style.copy(fontFamily = FontFamily.Monospace) else style
    Text(
        text = text,
        color = color,
        style = effectiveStyle,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.horizontalScroll(rememberScrollState())
    )
}
