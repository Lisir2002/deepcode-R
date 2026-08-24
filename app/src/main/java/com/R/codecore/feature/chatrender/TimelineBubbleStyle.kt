package com.R.codecore.feature.chatrender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing

/**
 * 款式 D · 时间线：左侧角色节点轨道（圆 / 方 / 菱）+ 内容右排 + 时间戳。
 * - 用户=圆形、助手=方形、工具=菱形，形状 + 颜色双维度区分角色；
 * - 轨道竖线贯穿整个任务组，节点落在同一列中线；
 * - 节点旁小灰字时间，智能带日期（跨天加日期，当天仅时分）。
 */
object TimelineBubbleStyle : MessageBubbleStyle {
    override val id: BubbleStyle = BubbleStyle.TIMELINE

    private val trackWidth = BUBBLE_TRACK_WIDTH_DP.dp

    @Composable
    private fun nodeCell(shape: BubbleNodeShape, color: Color) {
        Box(
            modifier = Modifier.width(trackWidth),
            contentAlignment = Alignment.Center
        ) {
            BubbleNode(shape = shape, color = color)
        }
    }

    @Composable
    private fun nodeCellWithTime(shape: BubbleNodeShape, color: Color, timestamp: Long) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            nodeCell(shape, color)
            if (timestamp > 0) {
                Text(
                    text = formatBubbleTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun typeShape(type: BubbleSubGroupType): BubbleNodeShape = when (type) {
        BubbleSubGroupType.USER -> BubbleNodeShape.CIRCLE
        BubbleSubGroupType.REASONING -> BubbleNodeShape.CIRCLE
        BubbleSubGroupType.REPLY -> BubbleNodeShape.SQUARE
        BubbleSubGroupType.TOOL -> BubbleNodeShape.DIAMOND
    }

    @Composable
    private fun typeColor(type: BubbleSubGroupType): Color = when (type) {
        BubbleSubGroupType.USER -> BubblePalette.user()
        BubbleSubGroupType.REASONING -> BubblePalette.reasoning()
        BubbleSubGroupType.REPLY -> BubblePalette.assistant()
        BubbleSubGroupType.TOOL -> BubblePalette.tool()
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
        Box(modifier = modifier.fillMaxWidth()) {
            // 轨道竖线：贯穿整个任务组，对齐到节点列中线（14dp 列的中心 x=7dp）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(2.dp)
                    .offset(x = ((BUBBLE_TRACK_WIDTH_DP / 2) - 1).dp)
                    .background(BubblePalette.spine())
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                BubbleTaskHeader(
                    isExpanded = isExpanded,
                    isStreaming = isStreaming,
                    title = title,
                    timestamp = timestamp,
                    onToggle = onToggle,
                    leading = { nodeCell(BubbleNodeShape.SQUARE, BubblePalette.spine()) },
                    headerTrailing = headerTrailing
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.xs, end = Spacing.xs),
                    content = content
                )
            }
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
            leading = {
                nodeCell(shape = typeShape(type), color = typeColor(type))
            }
        )
    }

    @Composable
    override fun UserContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Row(modifier = modifier.fillMaxWidth()) {
            nodeCellWithTime(BubbleNodeShape.CIRCLE, BubblePalette.user(), timestamp)
            Spacer(Modifier.width(Spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.xs),
                content = content
            )
        }
    }

    @Composable
    override fun AssistantContainer(
        isFormal: Boolean,
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Row(modifier = modifier.fillMaxWidth()) {
            nodeCellWithTime(BubbleNodeShape.SQUARE, BubblePalette.assistant(), timestamp)
            Spacer(Modifier.width(Spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.xs),
                content = content
            )
        }
    }

    @Composable
    override fun ToolContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Row(modifier = modifier.fillMaxWidth()) {
            nodeCellWithTime(BubbleNodeShape.DIAMOND, BubblePalette.tool(), timestamp)
            Spacer(Modifier.width(Spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.xs),
                content = content
            )
        }
    }
}
