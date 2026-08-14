package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.LocalAppDarkMode
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.presentation.AgentUIMessage
import com.deep.rcode.feature.agent.presentation.RunningToolOutput
import com.deep.rcode.feature.agent.presentation.TaskGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroupType
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Star
import compose.icons.feathericons.Tool
import compose.icons.feathericons.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一级任务手风琴：一个任务（taskId）对应一个折叠面板。
 * 头部显示任务标题 / 消息数 / 时间；展开后渲染该任务下的二级子手风琴（按消息类型分类）。
 *
 * 视觉设计：卡片式阴影 + 渐变图标徽章 + 流式脉冲动画 + 子分类强调色体系
 */
@Composable
internal fun TaskAccordion(
    group: TaskGroup,
    markdownCache: MarkdownRenderCache?,
    onToggleTask: (String) -> Unit,
    onToggleSubGroup: (String, TaskSubGroupType) -> Unit,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?,
    runningTool: List<RunningToolOutput>,
    modifier: Modifier = Modifier
) {
    val totalCount = group.subGroups.sumOf { it.messages.size }

    // 流式生成脉冲动画：动态调整边框高亮
    val infiniteTransition = rememberInfiniteTransition(label = "streamingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val borderAlpha = if (group.isStreaming) pulseAlpha else 0.6f

    // 根据状态动态调整卡片阴影
    val elevation by animateDpAsState(
        targetValue = if (group.isStreaming) 4.dp else if (group.isExpanded) 2.dp else 1.dp,
        animationSpec = tween(300),
        label = "cardElevation"
    )

    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (group.isStreaming) 2.dp else 1.dp,
            color = if (group.isStreaming) {
                Brand.Blue.copy(alpha = borderAlpha)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)
            }
        ),
        tonalElevation = 0.dp,
        shadowElevation = elevation,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // 一级手风琴头部：渐变图标徽章 + 标题 + 时间 + 计数 + 展开箭头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTask(group.taskId) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // 渐变图标徽章：品牌蓝渐变底 + 白色图标，形成任务视觉锚点
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Brand.Blue,
                                    Brand.Sky
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = FeatherIcons.MessageSquare,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 任务标题：占据剩余空间
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (group.isStreaming) FontWeight.SemiBold else FontWeight.Medium,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 流式生成状态徽章
                if (group.isStreaming) {
                    StreamingBadge(infiniteTransition)
                }

                // 消息计数徽章
                MessageCountBadge(totalCount)

                // 时间戳
                if (group.timestamp > 0) {
                    Text(
                        text = formatTaskTime(group.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 展开/折叠箭头
                androidx.compose.material3.Icon(
                    imageVector = if (group.isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = stringResource(
                        if (group.isExpanded) R.string.chat_task_collapse else R.string.chat_task_expand
                    ),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 一级手风琴内容：二级子手风琴
            AnimatedVisibility(
                visible = group.isExpanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(350)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(250))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    group.subGroups.forEach { subGroup ->
                        SubAccordion(
                            group = group,
                            subGroup = subGroup,
                            markdownCache = markdownCache,
                            onToggleSubGroup = onToggleSubGroup,
                            onRewindClick = onRewindClick,
                            onMoreClick = onMoreClick,
                            runningTool = runningTool
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBadge(transition: InfiniteTransition) {
    val dotAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = Brand.Blue.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Brand.Blue.copy(alpha = dotAlpha))
            )
            Text(
                text = stringResource(R.string.chat_task_streaming),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Blue,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MessageCountBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = stringResource(R.string.chat_task_message_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 二级子手风琴：任务组内按消息类型聚合（用户消息 / 思考过程 / 助手回复 / 工具调用）。
 * 每种子类型有独立的强调色、图标和淡色背景，便于视觉分区。
 */
@Composable
private fun SubAccordion(
    group: TaskGroup,
    subGroup: TaskSubGroup,
    markdownCache: MarkdownRenderCache?,
    onToggleSubGroup: (String, TaskSubGroupType) -> Unit,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?,
    runningTool: List<RunningToolOutput>
) {
    val label = stringResource(subGroup.type.labelRes())
    val visual = subGroupVisual(subGroup.type)

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = visual.bg,
        border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 二级手风琴头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleSubGroup(group.taskId, subGroup.type) }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // 子分类图标
                androidx.compose.material3.Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accent,
                    modifier = Modifier.size(16.dp)
                )
                // 子分类标签
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = visual.accent,
                    modifier = Modifier.weight(1f)
                )
                // 消息计数
                Text(
                    text = "${subGroup.messages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = visual.accent.copy(alpha = 0.7f)
                )
                // 展开/折叠箭头
                androidx.compose.material3.Icon(
                    imageVector = if (subGroup.isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = visual.accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            // 二级手风琴内容：消息气泡
            AnimatedVisibility(
                visible = subGroup.isExpanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = tween(200),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(150))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    subGroup.messages.forEach { message ->
                        when (subGroup.type) {
                            TaskSubGroupType.REASONING -> {
                                ReasoningBubble(
                                    text = message.reasoning.orEmpty(),
                                    initiallyExpanded = false,
                                    cache = markdownCache
                                )
                            }
                            else -> {
                                val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                                // REPLY 子分组中消息的 reasoning 已在 REASONING 子分组单独展示，这里剥离避免重复。
                                val renderMessage = if (subGroup.type == TaskSubGroupType.REPLY && message.reasoning != null) {
                                    message.copy(reasoning = null)
                                } else {
                                    message
                                }
                                AgentMessageItem(
                                    message = renderMessage,
                                    liveOutput = live,
                                    markdownCache = markdownCache,
                                    onRewindClick = onRewindClick,
                                    onMoreClick = onMoreClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun TaskSubGroupType.labelRes(): Int = when (this) {
    TaskSubGroupType.USER -> R.string.chat_task_user
    TaskSubGroupType.REASONING -> R.string.chat_task_reasoning
    TaskSubGroupType.REPLY -> R.string.chat_task_reply
    TaskSubGroupType.TOOL -> R.string.chat_task_tool
}

/**
 * 子分类视觉定义：每种消息类型有独立的图标 + 强调色 + 淡色背景。
 * 明暗模式使用不同色阶，保证对比度与可读性。
 */
private data class SubGroupVisual(
    val icon: ImageVector,
    val accent: Color,
    val bg: Color
)

@Composable
private fun subGroupVisual(type: TaskSubGroupType): SubGroupVisual {
    val isDark = LocalAppDarkMode.current
    return when (type) {
        TaskSubGroupType.USER -> SubGroupVisual(
            icon = FeatherIcons.User,
            accent = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB),
            bg = if (isDark) Color(0xFF60A5FA).copy(alpha = 0.14f) else Color(0xFF2563EB).copy(alpha = 0.06f)
        )
        TaskSubGroupType.REASONING -> SubGroupVisual(
            icon = FeatherIcons.Star,
            accent = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
            bg = if (isDark) Color(0xFFA78BFA).copy(alpha = 0.14f) else Color(0xFF7C3AED).copy(alpha = 0.06f)
        )
        TaskSubGroupType.REPLY -> SubGroupVisual(
            icon = FeatherIcons.MessageSquare,
            accent = if (isDark) Color(0xFF34D399) else Color(0xFF059669),
            bg = if (isDark) Color(0xFF34D399).copy(alpha = 0.14f) else Color(0xFF059669).copy(alpha = 0.06f)
        )
        TaskSubGroupType.TOOL -> SubGroupVisual(
            icon = FeatherIcons.Tool,
            accent = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706),
            bg = if (isDark) Color(0xFFFBBF24).copy(alpha = 0.14f) else Color(0xFFD97706).copy(alpha = 0.06f)
        )
    }
}

private fun formatTaskTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("")
}