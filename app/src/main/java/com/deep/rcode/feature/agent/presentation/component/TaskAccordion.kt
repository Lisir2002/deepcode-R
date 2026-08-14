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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.deep.rcode.feature.agent.domain.container.progress.InstallProgress
import com.deep.rcode.feature.agent.domain.container.progress.InstallProgressParsers
import com.deep.rcode.feature.agent.domain.permission.ShellCommandParser
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    onViewChanges: ((TaskChangesSheetData) -> Unit)?,
    runningTool: List<RunningToolOutput>,
    modifier: Modifier = Modifier,
    onRefreshEnvironment: (() -> Unit)? = null
) {
    val totalCount = group.subGroups.sumOf { it.messages.size }
    // 任务内全部工具执行日志（含查询操作），供弹窗「日志」Tab 展示
    val toolLogs = remember(group.taskId, group.subGroups) { collectTaskLogs(group) }
    // 归并渲染单元：TOOL 片段嵌入紧随其后的 REPLY 片段（作为回复气泡的子气泡）
    val renderUnits = remember(group.taskId, group.subGroups) { buildRenderUnits(group.subGroups) }
    // 环境总览：任务内 check_environment 工具结果 + 正在运行的安装进度
    val envComponents = remember(group.taskId, group.subGroups) { collectEnvironmentComponents(group) }
    val activeInstall = remember(group.taskId, group.subGroups, runningTool) {
        resolveActiveInstall(group, runningTool)
    }
    // 安装是否刚完成：任务内最近一次安装类工具已结束且结果为完成
    val justCompleted = remember(group.taskId, group.subGroups, runningTool) {
        resolveInstallJustCompleted(group, runningTool)
    }

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
                    // 环境总览卡片：任务内环境探测结果 + 安装进度 + 刷新 + 完成播报
                    if (envComponents.isNotEmpty() || activeInstall != null || justCompleted) {
                        EnvironmentOverviewCard(
                            components = envComponents,
                            activeInstall = activeInstall,
                            onRefresh = onRefreshEnvironment,
                            justCompleted = justCompleted
                        )
                    }
                    renderUnits.forEach { unit ->
                        SubAccordion(
                            group = group,
                            subGroup = unit.subGroup,
                            attachedTools = unit.attachedTools,
                            toolLogs = toolLogs,
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
 * 工具调用摘要行：把一批文件变更聚合为一行（「修改了 N 个文件 · +X -Y」）。
 * 作为回复气泡内的工具调用子气泡折叠态，[onClick] 非空时可点击展开完整工具调用列表。
 * 视觉采用工具子气泡样式（橙色淡底 + 琥珀强调色），与回复气泡形成层次。
 */
@Composable
private fun ToolSummaryRow(
    fileDiffs: List<EditDiff>,
    onClick: (() -> Unit)? = null
) {
    val createCount = fileDiffs.count { it.type == FileChangeType.CREATE }
    val deleteCount = fileDiffs.count { it.type == FileChangeType.DELETE }
    val modifyCount = fileDiffs.count { it.type == FileChangeType.MODIFY }
    val visual = subGroupVisual(TaskSubGroupType.TOOL)
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = visual.bg,
        border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 工具图标（琥珀强调色）
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.Tool,
                contentDescription = null,
                tint = visual.accent,
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
            // 变更分类统计
            if (createCount > 0) {
                Text(
                    text = stringResource(R.string.tool_summary_create, createCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF22C55E),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (modifyCount > 0) {
                Text(
                    text = stringResource(R.string.tool_summary_modify, modifyCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF3B82F6),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (deleteCount > 0) {
                Text(
                    text = stringResource(R.string.tool_summary_delete, deleteCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF4444),
                    fontWeight = FontWeight.SemiBold
                )
            }
            // 可展开箭头
            if (onClick != null) {
                androidx.compose.material3.Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = visual.accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 嵌入在回复气泡顶部的工具调用子手风琴组件。
 * - 默认折叠：显示 ToolSummaryRow（有文件变更）或 ToolCallCountRow（仅工具名/次数）；
 * - 点击展开：显示该批次工具调用的完整列表（按连续相同工具名分组）。
 * 「查看修改」按钮由回复气泡底部统一提供，此处不重复。
 */
@Composable
private fun EmbeddedToolAccordion(
    attachedTools: List<AgentUIMessage>,
    markdownCache: MarkdownRenderCache?,
    runningTool: List<RunningToolOutput>,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?
) {
    if (attachedTools.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val batchFileDiffs = remember(attachedTools) { collectBatchFileDiffs(attachedTools) }
    // 检测正在运行的安装命令进度（apt/apk/pip/sdkmanager），有则折叠态显示进度条
    val activeInstall = remember(attachedTools, runningTool) { resolveAttachedInstall(attachedTools, runningTool) }

    Column {
        // 折叠态：安装进度 > 文件变更摘要 > 工具调用计数
        if (activeInstall != null) {
            InstallProgressRow(progress = activeInstall)
        } else if (batchFileDiffs.isNotEmpty()) {
            ToolSummaryRow(
                fileDiffs = batchFileDiffs,
                onClick = { expanded = !expanded }
            )
        } else {
            ToolCallCountRow(
                tools = attachedTools,
                onClick = { expanded = !expanded }
            )
        }

        // 展开列表
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
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
                    .padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                groupConsecutiveToolCalls(attachedTools).forEach { (toolName, msgs) ->
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
        }
    }
}

/**
 * 工具调用计数行：当工具调用不涉及文件变更时（如 websearch/todo/readFile 等），
 * 在回复气泡顶部展示「N 个工具调用」可折叠摘要，避免非文件工具被完全隐藏。
 */
@Composable
private fun ToolCallCountRow(
    tools: List<AgentUIMessage>,
    onClick: () -> Unit
) {
    val count = tools.size
    val toolNames = tools.mapNotNull { it.toolName }.distinct()
    val visual = subGroupVisual(TaskSubGroupType.TOOL)
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = visual.bg,
        border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.2f)),
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
                imageVector = FeatherIcons.Tool,
                contentDescription = null,
                tint = visual.accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.tool_summary_count, count),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (toolNames.isNotEmpty()) {
                Text(
                    text = toolNames.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = visual.accent.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            androidx.compose.material3.Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = visual.accent.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 回复气泡底部的「查看修改」按钮：对应批次有文件变更时显示，点击打开底部弹窗。
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
 * 渲染单元：一个二级片段 + 嵌入其顶部的工具调用消息列表。
 * 正常场景下 TOOL 片段被归并到紧随其后的 REPLY 片段；仅当任务内无任何 REPLY 时，
 * TOOL 片段才作为独立兜底单元渲染（避免工具调用信息丢失）。
 */
private data class RenderUnit(
    val subGroup: TaskSubGroup,
    val attachedTools: List<AgentUIMessage> = emptyList()
)

/**
 * 把任务内的二级片段归并为渲染单元，保持消息真实时间顺序：
 * - TOOL 片段 → 暂存，不独立渲染（check_environment 等系统探测工具被过滤，不嵌入回复气泡）；
 * - REPLY 片段 → 携带暂存的工具调用（嵌入回复顶部），并成为「最近回复」；
 * - 其他片段（USER）→ 若暂存非空，嵌入最近回复；无最近回复则兜底独立 TOOL 单元；
 * - 任务结束 → 若暂存非空，同样嵌入最近回复或兜底。
 */
private fun buildRenderUnits(subGroups: List<TaskSubGroup>): List<RenderUnit> {
    val units = mutableListOf<RenderUnit>()
    val pendingTools = mutableListOf<AgentUIMessage>()
    var lastReplyIndex = -1
    subGroups.forEach { subGroup ->
        when (subGroup.type) {
            TaskSubGroupType.TOOL -> {
                // 过滤系统探测工具（如 check_environment），它们已在环境总览卡片展示，不嵌入回复气泡
                val filtered = subGroup.messages.filter { it.toolName != "check_environment" }
                pendingTools += filtered
            }
            TaskSubGroupType.REPLY -> {
                units += RenderUnit(subGroup, pendingTools.toList())
                pendingTools.clear()
                lastReplyIndex = units.lastIndex
            }
            else -> {
                flushPendingTools(pendingTools, units, lastReplyIndex)
                units += RenderUnit(subGroup)
            }
        }
    }
    flushPendingTools(pendingTools, units, lastReplyIndex)
    return units
}

/** 把暂存的工具调用嵌入最近回复；无最近回复时兜底为独立 TOOL 单元。 */
private fun flushPendingTools(
    pendingTools: MutableList<AgentUIMessage>,
    units: MutableList<RenderUnit>,
    lastReplyIndex: Int
) {
    if (pendingTools.isEmpty()) return
    if (lastReplyIndex >= 0) {
        val prev = units[lastReplyIndex]
        units[lastReplyIndex] = prev.copy(attachedTools = prev.attachedTools + pendingTools)
    } else {
        units += RenderUnit(
            TaskSubGroup(
                id = "orphan-tool-${pendingTools.first().id}",
                type = TaskSubGroupType.TOOL,
                messages = pendingTools.toList()
            )
        )
    }
    pendingTools.clear()
}

/**
 * 从一批工具调用消息中收集文件变更（增 / 改 / 删），按文件路径聚合（同一文件多次修改合并 hunks）。
 * - editFile / writeFile(覆盖) → MODIFY
 * - writeFile(created=true) → CREATE
 * - Bash 中的 rm 命令 → DELETE
 */
private fun collectBatchFileDiffs(messages: List<AgentUIMessage>): List<EditDiff> {
    val byPath = LinkedHashMap<String, EditDiff>()
    messages.forEach { msg ->
        if (msg.role != MessageRole.TOOL || msg.isError) return@forEach
        when (msg.toolName) {
            "editFile", "writeFile" -> {
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
            "Bash" -> {
                parseBashDeletes(msg.toolArgs).forEach { (path, isWildcard) ->
                    if (byPath[path] == null) {
                        byPath[path] = EditDiff(
                            path = path,
                            added = 0,
                            removed = 0,
                            hunks = emptyList(),
                            type = FileChangeType.DELETE,
                            isWildcard = isWildcard
                        )
                    }
                }
            }
        }
    }
    return byPath.values.toList()
}

/**
 * 收集任务内全部工具调用的执行日志（含 readFile/list 等查询操作），按时间顺序排列，
 * 供弹窗「日志」Tab 展示。
 */
private fun collectTaskLogs(group: TaskGroup): List<ToolLogEntry> {
    val logs = mutableListOf<ToolLogEntry>()
    group.subGroups.forEach { sub ->
        sub.messages.forEach { msg ->
            if (msg.role != MessageRole.TOOL) return@forEach
            val rawResult = formatToolResult(msg.content)
            logs += ToolLogEntry(
                toolName = msg.toolName,
                args = toolArgHint(msg.toolArgs),
                result = rawResult.take(LOG_RESULT_LIMIT),
                isError = msg.isError
            )
        }
    }
    return logs
}

/**
 * 从 Bash 工具参数中解析删除文件的命令（rm），返回被删除的路径列表及是否通配符模式。
 *
 * 复用 [ShellCommandParser]（授权系统的安全静态分析器）：
 * - [ShellCommandParser.analyze] 按 `&&`/`;`/`|`/换行 拆顶层段，引号感知；
 * - [ShellCommandParser.parseRmInfo] 解析每段是否为 rm，支持多路径、`-r/-R/--recursive`、
 *   `--` 分隔符、环境赋值前缀（`FOO=bar rm ...`）、通配符（`*`/`?`）。
 * 通配符路径保留为模式条目（如 `*.log`），由弹窗以「删除模式」差异化展示。
 */
private fun parseBashDeletes(toolArgs: String?): List<Pair<String, Boolean>> {
    if (toolArgs.isNullOrBlank()) return emptyList()
    return runCatching {
        val obj = Json.parseToJsonElement(toolArgs).jsonObject
        val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val analysis = ShellCommandParser.analyze(command)
        val result = mutableListOf<Pair<String, Boolean>>()
        analysis.segments.forEach { tokens ->
            val info = ShellCommandParser.parseRmInfo(tokens)
            if (info.isRm) {
                info.targetPaths.forEach { path ->
                    val trimmed = path.trim('"', '\'')
                    if (trimmed.isNotEmpty()) {
                        // 逐路径判定通配符，避免 `rm a.txt *.log` 把 a.txt 也误标为通配模式
                        result += trimmed to (trimmed.contains('*') || trimmed.contains('?'))
                    }
                }
            }
        }
        result
    }.getOrDefault(emptyList())
}

/** 日志 Tab 单条结果的最大字符数，超出截断避免长输出撑爆弹窗。 */
private const val LOG_RESULT_LIMIT = 2000

/**
 * 二级片段手风琴：任务组内时间上连续的同类型消息片段（用户消息 / 助手回复 / 工具调用）。
 * 每种子类型有独立的强调色、图标和淡色背景，便于视觉分区。
 * 片段按真实执行顺序排列，reasoning 内嵌在助手消息气泡中，不单独拆组。
 */
@Composable
private fun SubAccordion(
    group: TaskGroup,
    subGroup: TaskSubGroup,
    attachedTools: List<AgentUIMessage>,
    toolLogs: List<ToolLogEntry>,
    markdownCache: MarkdownRenderCache?,
    onToggleSubGroup: (String, String) -> Unit,
    onRewindClick: ((String) -> Unit)?,
    onMoreClick: ((AgentUIMessage) -> Unit)?,
    onViewChanges: ((TaskChangesSheetData) -> Unit)?,
    runningTool: List<RunningToolOutput>
) {
    val label = stringResource(subGroup.type.labelRes())
    val visual = subGroupVisual(subGroup.type)
    // 该片段关联的工具调用批次内的文件变更（增/删/改），按路径聚合
    val batchFileDiffs = remember(subGroup.id, attachedTools) { collectBatchFileDiffs(attachedTools) }

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
                    // TOOL 片段（兜底独立单元）：折叠为一行摘要
                    if (subGroup.type == TaskSubGroupType.TOOL) {
                        // 兜底单元无 attachedTools，基于自身消息收集文件变更
                        val toolFileDiffs = remember(subGroup.id, subGroup.messages) {
                            collectBatchFileDiffs(subGroup.messages)
                        }
                        if (toolFileDiffs.isNotEmpty()) {
                            ToolSummaryRow(fileDiffs = toolFileDiffs)
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
                        // REPLY 等类型：顶部嵌入工具调用子气泡
                        if (subGroup.type == TaskSubGroupType.REPLY && attachedTools.isNotEmpty()) {
                            EmbeddedToolAccordion(
                                attachedTools = attachedTools,
                                markdownCache = markdownCache,
                                runningTool = runningTool,
                                onRewindClick = onRewindClick,
                                onMoreClick = onMoreClick
                            )
                        }
                        // 消息正文
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
                        // REPLY 片段底部：查看修改按钮（用批次数据）
                        if (subGroup.type == TaskSubGroupType.REPLY && batchFileDiffs.isNotEmpty() && onViewChanges != null) {
                            ViewChangesButton(
                                fileDiffs = batchFileDiffs,
                                onClick = { onViewChanges(TaskChangesSheetData(batchFileDiffs, toolLogs)) }
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

/**
 * 从任务组中收集 [check_environment] 工具的结果，解析为环境组件状态列表。
 * 取任务内最后一个完成的 check_environment 结果。
 */
private fun collectEnvironmentComponents(group: TaskGroup): List<EnvironmentComponentState> {
    val envTools = group.subGroups
        .flatMap { it.messages }
        .filter { it.role == MessageRole.TOOL && it.toolName == "check_environment" && !it.isError && !it.content.startsWith("[running]") }
    if (envTools.isEmpty()) return emptyList()
    // 取最后一个完整结果
    val last = envTools.last()
    return parseEnvironmentComponents(last.content)
}

/**
 * 从任务中解析正在运行的安装进度：检查 runningTool 中是否有 Bash 命令
 * 包含安装类命令（apt/apk/pip/sdkmanager），若有则用对应解析器解析最新输出行。
 */
private fun resolveActiveInstall(group: TaskGroup, runningTool: List<RunningToolOutput>): InstallProgress? {
    // 找到正在运行的 Bash 工具
    val bashRunning = runningTool.filter { it.toolName == "Bash" || it.toolName == "" }
    for (rt in bashRunning) {
        val text = rt.text
        if (text.isBlank()) continue
        // 获取工具消息的 toolArgs 来判断命令内容
        val toolMsg = group.subGroups
            .flatMap { it.messages }
            .firstOrNull { it.id == rt.messageId && it.role == MessageRole.TOOL }
        val command = extractCommandFromArgs(toolMsg?.toolArgs)
        val parser = InstallProgressParsers.parserFor(command) ?: continue
        // 取最后一行解析
        val lastLine = text.lineSequence().lastOrNull { it.isNotBlank() } ?: continue
        val progress = parser.parse(lastLine)
        if (progress != null && !progress.isDone) return progress
    }
    return null
}

/**
 * 检测任务内最近一次安装类工具是否已「成功完成」。
 * 规则：找到任务内最后一个已结束（不在 runningTool 中）的安装类 Bash 工具，
 * 用其解析器的 [InstallProgressParser.isCompleted] 判断结果是否表示完成。
 * 用于环境总览卡片的「完成播报」横幅。
 */
private fun resolveInstallJustCompleted(group: TaskGroup, runningTool: List<RunningToolOutput>): Boolean {
    val runningIds = runningTool.map { it.messageId }.toSet()
    val installTools = group.subGroups
        .flatMap { it.messages }
        .filter { it.role == MessageRole.TOOL && !it.isError && !it.content.startsWith("[running]") }
        .filter { msg ->
            val command = extractCommandFromArgs(msg.toolArgs)
            InstallProgressParsers.parserFor(command) != null && msg.id !in runningIds
        }
    if (installTools.isEmpty()) return false
    val last = installTools.last()
    val parser = InstallProgressParsers.parserFor(extractCommandFromArgs(last.toolArgs)) ?: return false
    return parser.isCompleted(last.content)
}

/**
 * 从嵌入回复气泡的工具调用中解析正在运行的安装进度。
 * 与 [resolveActiveInstall] 类似，但作用域限定在 attachedTools（回复气泡的子气泡）。
 */
private fun resolveAttachedInstall(
    attachedTools: List<AgentUIMessage>,
    runningTool: List<RunningToolOutput>
): InstallProgress? {
    for (msg in attachedTools) {
        if (msg.role != MessageRole.TOOL) continue
        val live = runningTool.firstOrNull { it.messageId == msg.id } ?: continue
        if (live.text.isBlank()) continue
        val parser = InstallProgressParsers.parserFor(extractCommandFromArgs(msg.toolArgs)) ?: continue
        val lastLine = live.text.lineSequence().lastOrNull { it.isNotBlank() } ?: continue
        val progress = parser.parse(lastLine)
        if (progress != null && !progress.isDone) return progress
    }
    return null
}

/** 从工具参数 JSON 预览（argsPreview）中提取 command 字段。 */
private fun extractCommandFromArgs(argsPreview: String?): String {
    if (argsPreview.isNullOrBlank()) return ""
    return runCatching {
        val obj = Json.parseToJsonElement(argsPreview).jsonObject
        obj["command"]?.jsonPrimitive?.contentOrNull ?: ""
    }.getOrDefault("")
}