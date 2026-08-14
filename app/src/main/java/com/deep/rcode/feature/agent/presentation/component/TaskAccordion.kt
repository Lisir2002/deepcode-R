package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.presentation.AgentUIMessage
import com.deep.rcode.feature.agent.presentation.MessageRole
import com.deep.rcode.feature.agent.presentation.RunningToolOutput
import com.deep.rcode.feature.agent.presentation.TaskGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroupType
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.MessageSquare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一级任务手风琴：一个任务（taskId）对应一个折叠面板。
 * 头部显示任务标题 / 消息数 / 时间；展开后渲染该任务下的二级子手风琴（按消息类型分类）。
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
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // 一级手风琴头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTask(group.taskId) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = FeatherIcons.MessageSquare,
                    contentDescription = null,
                    tint = Brand.Blue,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTaskTime(group.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(Radius.sm),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "$totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                androidx.compose.material3.Icon(
                    imageVector = if (group.isExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = stringResource(
                        if (group.isExpanded) R.string.chat_task_collapse else R.string.chat_task_expand
                    ),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            // 一级手风琴内容：二级子手风琴
            AnimatedVisibility(
                visible = group.isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.sm),
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

/**
 * 二级子手风琴：任务组内按消息类型聚合（用户消息 / 思考过程 / 助手回复 / 工具调用）。
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
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                androidx.compose.material3.Icon(
                    imageVector = if (subGroup.isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = Brand.IconGray,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${subGroup.messages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 二级手风琴内容：消息气泡
            AnimatedVisibility(
                visible = subGroup.isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
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

private fun formatTaskTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("")
}
