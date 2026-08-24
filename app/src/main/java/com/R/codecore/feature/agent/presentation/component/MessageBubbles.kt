package com.R.codecore.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.presentation.AgentUIMessage
import com.R.codecore.feature.agent.presentation.EnvironmentSnapshot
import com.R.codecore.feature.agent.presentation.hasVisibleContent
import com.R.codecore.feature.agent.presentation.MessageRole
import com.R.codecore.feature.chatrender.BubbleStyleProvider
import com.R.codecore.feature.chatrender.LocalBubbleStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 单条消息渲染：Claude Code 风格日志流。
 *
 * 设计原则：
 * - 无气泡容器、无左右对称布局、无彩色填充底；
 * - 灰阶正文，仅保留语义色（绿=成功、红=失败）与一个提示符强调色；
 * - 用户消息以终端提示符 `>` 前缀区分角色，助手消息左对齐直出 Markdown；
 * - 工具消息折叠为「状态圆点 + 工具名 + 参数摘要」一行。
 */
@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onEditClick: ((AgentUIMessage) -> Unit)? = null,
    onNewChatClick: ((AgentUIMessage) -> Unit)? = null,
    initiallyExpanded: Boolean = true,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap(),
    /**
     * 正式回复模式：仅渲染正文（含操作按钮），不渲染思考过程；
     * 正文置于「淡底 + 左侧主色竖条」容器中，与无底仅色线的过程内容区分。
     */
    formalMode: Boolean = false
) {
    if (message.isCompactionMarker) {
        CompactionDivider()
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }

    val hasReasoning = message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning) return

    val isUser = message.role == MessageRole.USER
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    // 款式分发：由 LocalBubbleStyle 决定当前生效的消息外框（切换只影响渲染层，不读写对话数据）
    val renderer = BubbleStyleProvider.provide(LocalBubbleStyle.current)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning && !formalMode) {
            ReasoningBubble(text = message.reasoning.orEmpty(), initiallyExpanded = false, cache = markdownCache)
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            when {
                // 用户消息：款式外框（纯文字右对齐 / 细线右框 / 终端日志顶格色块 / 时间线圆节点）
                isUser && (hasContent || hasAttachments) -> {
                    renderer.UserContainer(timestamp = message.timestamp) {
                        if (hasContent) {
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
                                )
                            }
                        }
                        if (hasAttachments) {
                            MessageAttachmentPreviewRow(attachments = message.attachments)
                        }
                    }
                }
                // 正式回复：款式外框（isFormal=true，款式据此轻微强调）
                message.role == MessageRole.ASSISTANT && hasContent && formalMode -> {
                    renderer.AssistantContainer(isFormal = true, timestamp = message.timestamp) {
                        SelectionContainer {
                            MarkdownContent(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                cache = markdownCache
                            )
                        }
                        if (hasAttachments) {
                            MessageAttachmentPreviewRow(attachments = message.attachments)
                        }
                    }
                }
                // 助手过程内容：款式外框（isFormal=false，与正式回复区分）
                message.role == MessageRole.ASSISTANT && hasContent -> {
                    renderer.AssistantContainer(isFormal = false, timestamp = message.timestamp) {
                        SelectionContainer {
                            MarkdownContent(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                cache = markdownCache
                            )
                        }
                        if (hasAttachments) {
                            MessageAttachmentPreviewRow(attachments = message.attachments)
                        }
                    }
                }
                // 工具消息：款式外框（内容自带折叠）
                message.role == MessageRole.TOOL -> {
                    renderer.ToolContainer(timestamp = message.timestamp) {
                        ToolMessageBody(
                            message,
                            liveOutput = liveOutput,
                            initiallyExpanded = initiallyExpanded,
                            environmentSnapshots = environmentSnapshots
                        )
                        if (hasAttachments) {
                            MessageAttachmentPreviewRow(attachments = message.attachments)
                        }
                    }
                }
                // 兜底：其余角色有正文时直出
                hasContent -> {
                    renderer.AssistantContainer(isFormal = false, timestamp = message.timestamp) {
                        SelectionContainer {
                            MarkdownContent(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                cache = markdownCache
                            )
                        }
                    }
                }
                hasAttachments -> {
                    MessageAttachmentPreviewRow(attachments = message.attachments)
                }
            }
            // 消息下方操作按钮（工具消息不显示）
            if (message.content.hasVisibleContent() && message.role != MessageRole.TOOL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    // 复制
                    MessageActionIconButton(
                        icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                        tint = iconTint,
                        onClick = {
                            copyScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("message", message.content))
                                )
                                copied = true
                            }
                        }
                    )
                    // 编辑（仅用户消息）：填入输入框，允许修改后重发
                    if (isUser && onEditClick != null) {
                        MessageActionIconButton(
                            icon = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.chat_action_edit),
                            tint = iconTint,
                            onClick = { onEditClick(message) }
                        )
                    }
                    // 创建新聊天（仅用户消息）
                    if (isUser && onNewChatClick != null) {
                        MessageActionIconButton(
                            icon = Icons.Rounded.ChatBubble,
                            contentDescription = stringResource(R.string.chat_action_new_chat),
                            tint = iconTint,
                            onClick = { onNewChatClick(message) }
                        )
                    }
                    if (message.role == MessageRole.ASSISTANT && (message.inputTokens > 0 || message.outputTokens > 0)) {
                        val inStr = formatTokenCount(message.inputTokens)
                        val outStr = formatTokenCount(message.outputTokens)
                        Text(
                            text = "↑$inStr ↓$outStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 复制成功 1.5s 后恢复图标
                if (copied) {
                    LaunchedEffect(copied) {
                        delay(1500)
                        copied = false
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(24.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(13.dp))
    }
}

/**
 * 后台任务完成通知的轻量提示条：不作为普通用户气泡展示，仅以紧凑横条形式告知用户
 * 哪个后台命令结束了、成功与否。从通知文本里提取 <status>/<summary> 字段。
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val content = message.content
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val isSuccess = statuses.all { it == "completed" }
    val dotColor = if (isSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            val failedCount = statuses.count { it != "completed" }
            val prefix = stringResource(R.string.ui______d1490ef3)
            val namePart = summaries.joinToString("、") { s -> s.removePrefix(prefix).substringBefore("」") }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount, namePart)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size, namePart)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactionDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = stringResource(R.string.chat_context_compressed),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}
