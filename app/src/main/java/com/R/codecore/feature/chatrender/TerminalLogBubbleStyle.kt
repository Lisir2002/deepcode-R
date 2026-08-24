package com.R.codecore.feature.chatrender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Spacing

/**
 * 款式 C · 终端日志（默认 / 主设计）：开发者日志流。
 * - 全部左对齐顶格，靠行首色线 / 色块区分角色；
 * - 用户消息行首加粗色块（深档强调色），助手行首浅色线；
 * - 任务组：贯穿竖条 + 顶部整行色线，形成「拐角钉」纵向锚点；
 * - 工具调用折叠为命令行（内容自带折叠）；
 * - 纯箭头前缀（`>`）由消息内容呈现，本模块提供行首色线；
 * - 不显示时间戳。
 */
object TerminalLogBubbleStyle : MessageBubbleStyle {
    override val id: BubbleStyle = BubbleStyle.TERMINAL_LOG

    @Composable
    private fun typeColor(type: BubbleSubGroupType): Color = when (type) {
        BubbleSubGroupType.USER -> BubblePalette.user()
        BubbleSubGroupType.REASONING -> BubblePalette.reasoning()
        BubbleSubGroupType.REPLY -> BubblePalette.assistant()
        BubbleSubGroupType.TOOL -> BubblePalette.tool()
    }

    /** 行首短色块（3dp 宽 × 14dp 高），片段头 / 色标用。 */
    @Composable
    private fun shortBar(color: Color) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
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
            // 贯穿竖条：不参与父布局尺寸计算，随内容高度伸缩（纵向锚点）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(3.dp)
                    .align(Alignment.CenterStart)
                    .background(BubblePalette.spine())
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                // 一轮回复顶部整行淡色线（横向锚点，与贯穿竖条左端交汇）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(BubblePalette.assistant().copy(alpha = 0.6f))
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.md)
                ) {
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
                            .padding(end = Spacing.sm),
                        content = content
                    )
                }
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
            leading = { shortBar(typeColor(type)) }
        )
    }

    @Composable
    override fun UserContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Box(modifier = modifier.fillMaxWidth()) {
            // 行首加粗色块：贯穿内容高度（用户深档强调色）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(3.dp)
                    .align(Alignment.CenterStart)
                    .background(BubblePalette.user())
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.sm + 3.dp, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
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
        Box(modifier = modifier.fillMaxWidth()) {
            // 行首色线：正式回复 3dp 粗（左侧竖条），过程回复 2dp 细
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(if (isFormal) 3.dp else 2.dp)
                    .align(Alignment.CenterStart)
                    .background(BubblePalette.assistant())
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isFormal) Spacing.sm + 3.dp else Spacing.xs + 2.dp,
                        end = Spacing.xs,
                        top = Spacing.xs,
                        bottom = Spacing.xs
                    ),
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
        Box(modifier = modifier.fillMaxWidth()) {
            // 行首色线（工具灰）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(2.dp)
                    .align(Alignment.CenterStart)
                    .background(BubblePalette.tool())
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xs + 2.dp, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                content = content
            )
        }
    }
}
