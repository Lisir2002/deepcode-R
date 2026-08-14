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
import androidx.compose.runtime.remember
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
import com.deep.rcode.feature.agent.presentation.MessageRole
import com.deep.rcode.feature.agent.presentation.RunningToolOutput
import com.deep.rcode.feature.agent.presentation.TaskGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroup
import com.deep.rcode.feature.agent.presentation.TaskSubGroupType
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.FileText
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
    onToggleSubGroup: (String, String) -> Unit,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?,
    onViewChanges: ((List<EditDiff>) -> Unit)?,
    runningTool: List<RunningToolOutput>,
    modifier: Modifier = Modifier
) {
    val totalCount = group.subGroups.sumOf { it.messages.size }
    // 任务内所有 editFile/writeFile 的 diff，按路径聚合（同一文件多次修改合并）
    val fileDiffs = remember(group.taskId, group.subGroups) { collectTaskFileDiffs(group) }

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
                            fileDiffs = fileDiffs,
                            markdownCache = markdownCache,
                            onToggleSubGroup = onToggleSubGroup,
                            onRewindClick = onRewindClick,
                            onMoreClick = onMoreClick,
                            onViewChanges = onViewChanges,
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
 * 工具调用摘要行：把任务内所有文件修改聚合为一行（「修改了 N 个文件 · +X -Y」），
 * 点击打开底部弹窗查看详细 diff。避免多个文件修改气泡在聊天流中拥挤。
 */
@Composable
private fun ToolSummaryRow(
    fileDiffs: List<EditDiff>,
    onClick: () -> Unit
) {
    val totalAdded = fileDiffs.sumOf { it.added }
    val totalRemoved = fileDiffs.sumOf { it.removed }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 工具图标（琥珀强调色）
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.Tool,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.tool_summary_files, fileDiffs.size),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 变更统计
            if (totalAdded > 0) {
                Text(
                    text = "+$totalAdded",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddText,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (totalRemoved > 0) {
                Text(
                    text = "-$totalRemoved",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemoveText,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 查看箭头
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.tool_summary_view),
                tint = Brand.IconGray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 回复气泡底部的「查看修改」按钮：任务内有文件修改时显示，点击打开底部弹窗。
 */
@Composable
private fun ViewChangesButton(
    fileDiffs: List<EditDiff>,
    onClick: () -> Unit
) {
    val totalAdded = fileDiffs.sumOf { it.added }
    val totalRemoved = fileDiffs.sumOf { it.removed }
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = Brand.Blue.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Brand.Blue.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                tint = Brand.Blue,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.tool_view_changes, fileDiffs.size),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Brand.Blue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (totalAdded > 0 || totalRemoved > 0) {
                Text(
                    text = "+$totalAdded -$totalRemoved",
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.Blue.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium
                )
            }
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = Brand.Blue,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 收集任务内所有 editFile/writeFile 的结构化 diff，按文件路径聚合（同一文件多次修改合并 hunks）。
 */
private fun collectTaskFileDiffs(group: TaskGroup): List<EditDiff> {
    val byPath = LinkedHashMap<String, EditDiff>()
    group.subGroups.forEach { sub ->
        sub.messages.forEach { msg ->
            if (msg.role == MessageRole.TOOL && !msg.isError &&
                (msg.toolName == "editFile" || msg.toolName == "writeFile")
            ) {
                val diff = parseEditDiff(msg.content) ?: return@forEach
                val existing = byPath[diff.path]
                byPath[diff.path] = if (existing != null) {
                    existing.copy(
                        added = existing.added + diff.added,
                        removed = existing.removed + diff.removed,
                        hunks = existing.hunks + diff.hunks
                    )
                } else {
                    diff
                }
            }
        }
    }
    return byPath.values.toList()
}

/**
 * 二级片段手风琴：任务组内时间上连续的同类型消息片段（用户消息 / 助手回复 / 工具调用）。
 * 每种子类型有独立的强调色、图标和淡色背景，便于视觉分区。
 * 片段按真实执行顺序排列，reasoning 内嵌在助手消息气泡中，不单独拆组。
 */
@Composable
private fun SubAccordion(
    group: TaskGroup,
    subGroup: TaskSubGroup,
    fileDiffs: List<EditDiff>,
    markdownCache: MarkdownRenderCache?,
    onToggleSubGroup: (String, String) -> Unit,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?,
    onViewChanges: ((List<EditDiff>) -> Unit)?,
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
            // 二级片段头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleSubGroup(group.taskId, subGroup.id) }
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
            // 二级片段内容：消息气泡（保持时间顺序）
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
                    // TOOL 片段：折叠为一行摘要（文件修改聚合），点击打开弹窗
                    if (subGroup.type == TaskSubGroupType.TOOL) {
                        if (fileDiffs.isNotEmpty()) {
                            ToolSummaryRow(
                                fileDiffs = fileDiffs,
                                onClick = { onViewChanges?.invoke(fileDiffs) }
                            )
                        } else {
                            // 无文件修改的工具调用（如 websearch/todo 等）：保留紧凑列表
                            groupConsecutiveToolCalls(subGroup.messages).forEach { (toolName, msgs) ->
                                if (msgs.size > 1) {
                                    ToolCallGroup(
                                        toolName = toolName ?: stringResource(R.string.common_tool),
                                        messages = msgs,
                                        runningTool = runningTool,
                                        markdownCache = markdownCache,
                                        onRewindClick = onRewindClick,
                                        onMoreClick = onMoreClick
                                    )
                                } else {
                                    val message = msgs.first()
                                    val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                                    AgentMessageItem(
                                        message = message,
                                        liveOutput = live,
                                        markdownCache = markdownCache,
                                        onRewindClick = onRewindClick,
                                        onMoreClick = onMoreClick
                                    )
                                }
                            }
                        }
                    } else {
                        subGroup.messages.forEach { message ->
                            val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                            AgentMessageItem(
                                message = message,
                                liveOutput = live,
                                markdownCache = markdownCache,
                                onRewindClick = onRewindClick,
                                onMoreClick = onMoreClick
                            )
                        }
                        // REPLY 片段：底部加「查看修改」按钮（有文件修改时）
                        if (subGroup.type == TaskSubGroupType.REPLY && fileDiffs.isNotEmpty() && onViewChanges != null) {
                            ViewChangesButton(
                                fileDiffs = fileDiffs,
                                onClick = { onViewChanges(fileDiffs) }
                            )
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

/**
 * 把 TOOL 片段内的消息按「连续相同工具名」切分为子组，保持时间顺序。
 * 仅合并相邻同工具名的调用（如连续 writeFile 多个文件），不同工具名之间不重排。
 * 返回 (toolName, messages) 列表；toolName 为 null 时表示未知工具名。
 */
private fun groupConsecutiveToolCalls(messages: List<AgentUIMessage>): List<Pair<String?, List<AgentUIMessage>>> {
    val result = mutableListOf<Pair<String?, List<AgentUIMessage>>>()
    var currentName: String? = null
    var currentList = mutableListOf<AgentUIMessage>()
    for (msg in messages) {
        val name = msg.toolName
        if (currentName != null && name != currentName) {
            result += currentName to currentList
            currentList = mutableListOf()
        }
        currentName = name
        currentList += msg
    }
    if (currentName != null && currentList.isNotEmpty()) {
        result += currentName to currentList
    }
    return result
}