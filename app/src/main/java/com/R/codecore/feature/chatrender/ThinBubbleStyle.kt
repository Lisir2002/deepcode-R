package com.R.codecore.feature.chatrender

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing

/**
 * 款式 B · 细线气泡：透明底 + 1dp 描边圆角闭合框，靠轮廓不靠填色。
 * - 用户气泡靠右、助手 / 工具气泡靠左，同风格描边区分；
 * - 工具卡同样套 1dp 细线圆角框；
 * - 任务组整体套细线圆角框，任务头灰阶位于框内顶部；
 * - 不显示时间戳。
 */
object ThinBubbleStyle : MessageBubbleStyle {
    private val shape = RoundedCornerShape(8.dp)

    @Composable
    private fun borderColor(): Color = MaterialTheme.colorScheme.outlineVariant

    private val lineWidth: Dp = 1.dp

    @Composable
    private fun bubble(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Box(
            modifier = modifier
                .clip(shape)
                .border(lineWidth, borderColor(), shape)
                .padding(Spacing.sm)
        ) {
            Column(content = content)
        }
    }

    @Composable
    override fun TaskGroupContainer(
        isExpanded: Boolean,
        isStreaming: Boolean,
        title: String,
        timestamp: Long,
        onToggle: () -> Unit,
        modifier: Modifier,
        headerTrailing: @Composable RowScope.() -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(lineWidth, borderColor(), shape)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        ) {
            Column(content = {
                BubbleTaskHeader(
                    isExpanded = isExpanded,
                    isStreaming = isStreaming,
                    title = title,
                    timestamp = timestamp,
                    onToggle = onToggle,
                    headerTrailing = headerTrailing
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.xs, end = Spacing.xs),
                    content = content
                )
            })
        }
    }

    @Composable
    override fun SubGroupHeader(
        type: BubbleSubGroupType,
        label: String,
        isExpanded: Boolean,
        isUser: Boolean,
        onToggle: () -> Unit,
        modifier: Modifier
    ) {
        BubbleSubLabel(
            label = label,
            isExpanded = isExpanded,
            onToggle = onToggle,
            modifier = modifier,
            alignEnd = isUser
        )
    }

    @Composable
    override fun UserContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            bubble(
                modifier = Modifier.fillMaxWidth(0.86f)
            ) {
                Column(horizontalAlignment = Alignment.End, content = content)
            }
        }
    }

    @Composable
    override fun AssistantContainer(
        isFormal: Boolean,
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        bubble(modifier = modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }

    @Composable
    override fun ToolContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        bubble(modifier = modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}
