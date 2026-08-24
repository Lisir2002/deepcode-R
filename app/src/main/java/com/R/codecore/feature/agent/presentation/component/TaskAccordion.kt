package com.R.codecore.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.LocalAppDarkMode
import com.R.codecore.core.theme.MessageAccent
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.theme.resolveLine
import com.R.codecore.feature.agent.presentation.AgentUIMessage
import com.R.codecore.feature.agent.presentation.EnvironmentSnapshot
import com.R.codecore.feature.agent.presentation.MessageRole
import com.R.codecore.feature.agent.presentation.RunningToolOutput
import com.R.codecore.feature.agent.presentation.TaskGroup
import com.R.codecore.feature.agent.presentation.TaskSubGroup
import com.R.codecore.feature.agent.presentation.TaskSubGroupType
import com.R.codecore.feature.agent.presentation.hasVisibleContent
import com.R.codecore.feature.agent.domain.container.progress.InstallProgress
import com.R.codecore.feature.agent.domain.container.progress.InstallProgressParsers
import com.R.codecore.feature.agent.domain.permission.ShellCommandParser
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Sync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一级任务手风琴：一个任务（taskId）对应一个折叠面板。
 * 头部显示任务标题 / 流式状态 / 时间；展开后渲染该任务下的二级子手风琴（按消息类型分类）。
 *
 * 视觉设计：Claude Code 风格灰阶日志流——无卡片、无渐变徽章、无阴影、无彩色强调底。
 * 仅保留流式脉冲圆点作为正在生成的状态信号。
 */
@Composable
internal fun TaskAccordion(
    group: TaskGroup,
    markdownCache: MarkdownRenderCache?,
    onToggleTask: (String) -> Unit,
    onToggleSubGroup: (String, String) -> Unit,
    onEditClick: ((AgentUIMessage) -> Unit)? = null,
    onNewChatClick: ((AgentUIMessage) -> Unit)? = null,
    onViewChanges: ((TaskChangesSheetData) -> Unit)?,
    runningTool: List<RunningToolOutput>,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap(),
    modifier: Modifier = Modifier
) {
    // 任务内全部工具执行日志（含查询操作），供弹窗「日志」Tab 展示
    val toolLogs = remember(group.taskId, group.subGroups) { collectTaskLogs(group) }
    // 归并渲染单元 + 拆分正式回复：TOOL 片段嵌入紧随其后的 REPLY 片段；
    // 最后一个含正文的 REPLY 消息被拆出为「正式回复」，其思考/工具保留为过程内容。
    val split = remember(group.taskId, group.subGroups) { splitFormalReply(group.subGroups) }
    // 任务内容展开/收起过渡
    val taskExpand = expandVertically(
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Top
    ) + fadeIn(animationSpec = tween(350))
    val taskShrink = shrinkVertically(
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        shrinkTowards = Alignment.Top
    ) + fadeOut(animationSpec = tween(250))

    // 流式生成脉冲动画：任务头小圆点透明度呼吸
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

    Column(modifier = modifier.fillMaxWidth()) {
        // 用户消息（右侧气泡）：独立于大气泡（贯穿竖条）之外，仅展开时显示
        AnimatedVisibility(
            visible = group.isExpanded,
            enter = taskExpand,
            exit = taskShrink
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                split.processUnits
                    .filter { it.subGroup.type == TaskSubGroupType.USER }
                    .forEach { unit ->
                        SubAccordion(
                            group = group,
                            subGroup = unit.subGroup,
                            attachedTools = unit.attachedTools,
                            toolLogs = toolLogs,
                            markdownCache = markdownCache,
                            onToggleSubGroup = onToggleSubGroup,
                            onEditClick = onEditClick,
                            onNewChatClick = onNewChatClick,
                            onViewChanges = onViewChanges,
                            runningTool = runningTool,
                            environmentSnapshots = environmentSnapshots
                        )
                    }
            }
        }
        // 一轮回复顶部的整行淡主色线（横向锚点）
        FullWidthAccentBar(MessageAccent.Content.resolveLine())
        // 大气泡：贯穿竖条（靛蓝）包裹模型整轮回复（任务头 + 过程内容 + 正式回复）
        Box(modifier = Modifier.fillMaxWidth()) {
            // 贯穿竖条：不参与父布局尺寸计算，随内容高度伸缩，作为整轮回复的纵向时间轴锚点
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .width(3.dp)
                    .align(Alignment.CenterStart)
                    .background(MessageAccent.Spine.resolveLine())
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md)
            ) {
                // 任务头：灰阶日志行（无卡片 / 无渐变徽章 / 无阴影）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTask(group.taskId) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // 流式状态圆点：仅在生成中显示，脉冲呼吸
                    if (group.isStreaming) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                        )
                    }
                    // 任务标题：占据剩余空间（过长横向滚动，不再用省略号截断）
                    HorizontalScrollableText(
                        text = group.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (group.isStreaming) FontWeight.SemiBold else FontWeight.Medium,
                            lineHeight = 20.sp
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    // 流式状态文案
                    if (group.isStreaming) {
                        Text(
                            text = stringResource(R.string.chat_task_streaming),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        imageVector = if (group.isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (group.isExpanded) R.string.chat_task_collapse else R.string.chat_task_expand
                        ),
                        tint = Brand.IconGray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 任务过程内容：二级子手风琴（无底仅色线结构，不添加背景容器）
                AnimatedVisibility(
                    visible = group.isExpanded,
                    enter = taskExpand,
                    exit = taskShrink
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = Spacing.sm, bottom = Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        split.processUnits
                            .filter { it.subGroup.type != TaskSubGroupType.USER }
                            .forEach { unit ->
                                SubAccordion(
                                    group = group,
                                    subGroup = unit.subGroup,
                                    attachedTools = unit.attachedTools,
                                    toolLogs = toolLogs,
                                    markdownCache = markdownCache,
                                    onToggleSubGroup = onToggleSubGroup,
                                    onEditClick = onEditClick,
                                    onNewChatClick = onNewChatClick,
                                    onViewChanges = onViewChanges,
                                    runningTool = runningTool,
                                    environmentSnapshots = environmentSnapshots
                                )
                            }
                        // 正式回复关联的工具调用：独立折叠块（过程内容，无底仅色线）
                        if (split.formalTools.isNotEmpty()) {
                            EmbeddedToolAccordion(
                                attachedTools = split.formalTools,
                                markdownCache = markdownCache,
                                runningTool = runningTool,
                                environmentSnapshots = environmentSnapshots
                            )
                        }
                    }
                }
                // 正式回复：独立于过程内容，淡底 + 左侧主色竖条（独特样式）
                split.formalMessage?.let { formal ->
                    AgentMessageItem(
                        message = formal,
                        markdownCache = markdownCache,
                        formalMode = true,
                        environmentSnapshots = environmentSnapshots
                    )
                    // 查看修改：正式回复对应批次的文件变更
                    if (onViewChanges != null && split.formalTools.isNotEmpty()) {
                        val formalDiffs = remember(split.formalTools) { collectBatchFileDiffs(split.formalTools) }
                        if (formalDiffs.isNotEmpty()) {
                            ViewChangesButton(
                                fileDiffs = formalDiffs,
                                onClick = { onViewChanges(TaskChangesSheetData(formalDiffs, toolLogs)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 工具调用摘要行：把一批文件变更聚合为一行（「修改了 N 个文件 · +X -Y」）。
 * 作为回复消息内的工具调用子块折叠态，[onClick] 非空时可点击展开完整工具调用列表。
 * 视觉采用灰阶日志行，仅变更分类统计保留语义色（绿=增、蓝=改、红=删）。
 */
@Composable
private fun ToolSummaryRow(
    fileDiffs: List<EditDiff>,
    onClick: (() -> Unit)? = null
) {
    val createCount = fileDiffs.count { it.type == FileChangeType.CREATE }
    val deleteCount = fileDiffs.count { it.type == FileChangeType.DELETE }
    val modifyCount = fileDiffs.count { it.type == FileChangeType.MODIFY }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 工具子行短色条
        ShortAccentBar(MessageAccent.Tool.resolveLine())
        Spacer(Modifier.width(Spacing.sm))
        // 工具图标（灰阶）
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Construction,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.tool_summary_files, fileDiffs.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // 变更分类统计（语义色）
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
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 嵌入在回复消息顶部的工具调用子手风琴组件。
 * - 默认折叠：显示 ToolSummaryRow（有文件变更）或 ToolCallCountRow（仅工具名/次数）；
 * - 点击展开：显示该批次工具调用的完整列表（按连续相同工具名分组）。
 * 「查看修改」按钮由回复消息底部统一提供，此处不重复。
 */
@Composable
private fun EmbeddedToolAccordion(
    attachedTools: List<AgentUIMessage>,
    markdownCache: MarkdownRenderCache?,
    runningTool: List<RunningToolOutput>,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap()
) {
    if (attachedTools.isEmpty()) return
    val batchFileDiffs = remember(attachedTools) { collectBatchFileDiffs(attachedTools) }
    // 检测正在运行的安装命令进度（apt/apk/pip/sdkmanager），有则折叠态显示进度条
    val activeInstall = remember(attachedTools, runningTool) { resolveAttachedInstall(attachedTools, runningTool) }
    // 安装进行中自动展开：让执行安装的工具消息内联显示实时进度；安装完成后自动收起
    val hasActiveInstall = activeInstall != null
    var expanded by remember(hasActiveInstall) { mutableStateOf(hasActiveInstall) }

    Column {
        // 折叠态：安装进度 > 文件变更摘要 > 工具调用计数（展开时由工具消息内联展示进度，避免重复）
        if (!expanded && activeInstall != null) {
            InstallProgressRow(progress = activeInstall)
        } else if (!expanded && batchFileDiffs.isNotEmpty()) {
            ToolSummaryRow(
                fileDiffs = batchFileDiffs,
                onClick = { expanded = !expanded }
            )
        } else if (!expanded) {
            ToolCallCountRow(
                tools = attachedTools,
                environmentSnapshots = environmentSnapshots,
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
                    .padding(top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                groupConsecutiveToolCalls(attachedTools).forEach { (toolName, msgs) ->
                    if (msgs.size > 1) {
                        ToolCallGroup(
                            toolName = toolName ?: stringResource(R.string.common_tool),
                            messages = msgs,
                            runningTool = runningTool,
                            markdownCache = markdownCache,
                            environmentSnapshots = environmentSnapshots
                        )
                    } else {
                        val message = msgs.first()
                        val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                        AgentMessageItem(
                            message = message,
                            liveOutput = live,
                            markdownCache = markdownCache,
                            environmentSnapshots = environmentSnapshots
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具调用计数行：当工具调用不涉及文件变更时（如 websearch/todo/readFile 等），
 * 在回复消息顶部展示「N 个工具调用」可折叠摘要，避免非文件工具被完全隐藏。
 * 若该批次中有工具触发了环境探测，则在右侧显示紧凑状态条。
 */
@Composable
private fun ToolCallCountRow(
    tools: List<AgentUIMessage>,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap(),
    onClick: () -> Unit
) {
    val count = tools.size
    val toolNames = tools.mapNotNull { it.toolName }.distinct()
    // 找到该批次中触发了环境探测的消息对应的快照
    val envSnapshot = remember(environmentSnapshots, tools) {
        tools.firstNotNullOfOrNull { msg -> environmentSnapshots[msg.id] }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 工具子行短色条
        ShortAccentBar(MessageAccent.Tool.resolveLine())
        Spacer(Modifier.width(Spacing.sm))
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Construction,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.tool_summary_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (toolNames.isNotEmpty()) {
            // 过长工具名列表横向滚动展示，不再用省略号截断
            HorizontalScrollableText(
                text = toolNames.joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        // 环境探测状态条：紧凑展示，仅显示当前所需构建环境的检测状态
        if (envSnapshot != null) {
            EnvironmentStatusBubble(snapshot = envSnapshot)
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 构建环境状态条：紧凑展示当前所需构建环境的检测状态（如 `php ✓`）。
 * 仅显示探测结果摘要，不展开细节；完整状态条由展开列表内的工具消息提供。
 * 语义色：检测中=灰、就绪=绿、缺少=红。
 */
@Composable
private fun EnvironmentStatusBubble(snapshot: EnvironmentSnapshot) {
    val components = snapshot.components
    if (components.isEmpty() && !snapshot.probing) return
    val running = snapshot.probing
    val missing = components.filter { it.status == EnvironmentStatus.MISSING }
    val ready = missing.isEmpty() && components.isNotEmpty()
    val statusColor = when {
        running -> MaterialTheme.colorScheme.onSurfaceVariant
        ready -> Color(0xFF22C55E)
        else -> Color(0xFFEF4444)
    }
    val statusIcon = when {
        running -> Icons.Rounded.Sync
        ready -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Cancel
    }
    val label = when {
        running -> stringResource(R.string.env_bubble_checking)
        ready -> stringResource(R.string.env_bubble_ready, components.joinToString(", ") { it.name })
        else -> stringResource(R.string.env_bubble_missing, missing.joinToString(", ") { it.name })
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = statusColor
            )
        } else {
            androidx.compose.material3.Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 回复消息底部的「查看修改」按钮：对应批次有文件变更时显示，点击打开底部弹窗。
 * 灰阶日志行样式，仅保留一个主色图标作为可点击信号。
 */
@Composable
private fun ViewChangesButton(
    fileDiffs: List<EditDiff>,
    onClick: () -> Unit
) {
    val totalAdded = fileDiffs.sumOf { it.added }
    val totalRemoved = fileDiffs.sumOf { it.removed }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.tool_view_changes, fileDiffs.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (totalAdded > 0 || totalRemoved > 0) {
            Text(
                text = "+$totalAdded -$totalRemoved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
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
 * - TOOL 片段 → 暂存，不独立渲染；check_environment 等工具以紧凑状态条形态内嵌于回复顶部；
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
                pendingTools += subGroup.messages
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

/**
 * 一轮回复的拆分结果：过程区渲染单元 + 独立正式回复（含其关联工具调用）。
 * - [processUnits]：过程区渲染单元（用户消息 / 思考 / 工具 / 中间回复），无底仅色线；
 * - [formalMessage]：正式回复消息（正文独立渲染为「淡底 + 左侧主色竖条」块）；
 * - [formalTools]：正式回复关联的工具调用，独立折叠在过程区底部。
 */
private data class RenderSplit(
    val processUnits: List<RenderUnit>,
    val formalMessage: AgentUIMessage?,
    val formalTools: List<AgentUIMessage>
)

/**
 * 把任务内的渲染单元拆分为「过程区 + 正式回复」：
 * - 取最后一个含可见正文的 REPLY 片段，其正文作为正式回复独立渲染；
 * - 该片段的思考（reasoning）独立为 REASONING 过程单元（正文移入正式回复块，不重复展示）；
 * - 该片段嵌入的工具调用保留为 [RenderSplit.formalTools]，独立折叠在过程区底部，
 *   保持「思考 / 工具（过程区）→ 正式回复」的层级，不再把正式回复与思考混在一起。
 */
private fun splitFormalReply(subGroups: List<TaskSubGroup>): RenderSplit {
    val units = buildRenderUnits(subGroups)
    for (i in units.indices.reversed()) {
        val unit = units[i]
        if (unit.subGroup.type != TaskSubGroupType.REPLY) continue
        val idx = unit.subGroup.messages.indexOfLast { msg ->
            msg.role == MessageRole.ASSISTANT && msg.content.hasVisibleContent()
        }
        if (idx < 0) continue
        val formal = unit.subGroup.messages[idx]
        val processUnits = mutableListOf<RenderUnit>()
        // 正式回复消息的思考 → 独立为思考过程单元
        val reasoningOnly = formal.reasoning?.takeIf { it.hasVisibleContent() }
        if (reasoningOnly != null) {
            processUnits += RenderUnit(
                TaskSubGroup(
                    id = "${unit.subGroup.id}-reasoning",
                    type = TaskSubGroupType.REASONING,
                    messages = listOf(formal.copy(content = "")),
                    isExpanded = unit.subGroup.isExpanded
                )
            )
        }
        // 该 REPLY 单元内其余消息（若有）保留在过程区
        val remaining = unit.subGroup.messages.filterIndexed { j, msg -> j != idx }
        if (remaining.isNotEmpty()) {
            processUnits += RenderUnit(unit.subGroup.copy(messages = remaining))
        }
        return RenderSplit(
            processUnits = units.take(i) + processUnits + units.drop(i + 1),
            formalMessage = formal,
            formalTools = unit.attachedTools
        )
    }
    return RenderSplit(units, null, emptyList())
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
 * 每种片段以灰阶小标签区分，不再使用彩色强调色与淡色背景。
 * 片段按真实执行顺序排列，reasoning 内嵌在助手消息中，不单独拆组。
 */
@Composable
private fun SubAccordion(
    group: TaskGroup,
    subGroup: TaskSubGroup,
    attachedTools: List<AgentUIMessage>,
    toolLogs: List<ToolLogEntry>,
    markdownCache: MarkdownRenderCache?,
    onToggleSubGroup: (String, String) -> Unit,
    onEditClick: ((AgentUIMessage) -> Unit)?,
    onNewChatClick: ((AgentUIMessage) -> Unit)?,
    onViewChanges: ((TaskChangesSheetData) -> Unit)?,
    runningTool: List<RunningToolOutput>,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap()
) {
    val label = stringResource(subGroup.type.labelRes())
    // 片段类型分档色条：REPLY=正文蓝、REASONING=思考紫、TOOL=工具蓝灰、USER=中性灰
    val barColor = when (subGroup.type) {
        TaskSubGroupType.REPLY -> MessageAccent.Content.resolveLine()
        TaskSubGroupType.REASONING -> MessageAccent.Reasoning.resolveLine()
        TaskSubGroupType.TOOL -> MessageAccent.Tool.resolveLine()
        TaskSubGroupType.USER -> if (LocalAppDarkMode.current) Color(0xFF64748B) else Color(0xFFCBD5E1)
    }
    // 该片段关联的工具调用批次内的文件变更（增/删/改），按路径聚合
    val batchFileDiffs = remember(subGroup.id, attachedTools) { collectBatchFileDiffs(attachedTools) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 片段头：灰阶小标签 + 展开箭头（无彩色、无背景）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleSubGroup(group.taskId, subGroup.id) }
                .padding(top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 片段类型短色条
            ShortAccentBar(barColor)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Icon(
                imageVector = if (subGroup.isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Brand.IconGray,
                modifier = Modifier.size(14.dp)
            )
        }
        // 片段内容：消息（保持时间顺序）
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
                    .padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
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
                                    environmentSnapshots = environmentSnapshots
                                )
                            } else {
                                val message = msgs.first()
                                val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                                AgentMessageItem(
                                    message = message,
                                    liveOutput = live,
                                    markdownCache = markdownCache,
                                    onEditClick = onEditClick,
                                    onNewChatClick = onNewChatClick,
                                    environmentSnapshots = environmentSnapshots
                                )
                            }
                        }
                    }
                } else {
                    // REPLY 等类型：顶部嵌入工具调用子块
                    if (subGroup.type == TaskSubGroupType.REPLY && attachedTools.isNotEmpty()) {
                        EmbeddedToolAccordion(
                            attachedTools = attachedTools,
                            markdownCache = markdownCache,
                            runningTool = runningTool,
                            environmentSnapshots = environmentSnapshots
                        )
                    }
                    // 消息正文
                    subGroup.messages.forEach { message ->
                        val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                        AgentMessageItem(
                            message = message,
                            liveOutput = live,
                            markdownCache = markdownCache,
                            onEditClick = onEditClick,
                            onNewChatClick = onNewChatClick,
                            environmentSnapshots = environmentSnapshots
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
 * 从嵌入回复消息的工具调用中解析正在运行的安装进度。
 * 作用域限定在 attachedTools（回复消息的子块）。
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
