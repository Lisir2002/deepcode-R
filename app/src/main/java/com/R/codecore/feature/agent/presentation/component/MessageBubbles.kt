package com.R.codecore.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.presentation.AgentUIMessage
import com.R.codecore.feature.agent.presentation.EnvironmentSnapshot
import com.R.codecore.feature.agent.presentation.hasVisibleContent
import com.R.codecore.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.MessageSquare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onEditClick: ((AgentUIMessage) -> Unit)? = null,
    onNewChatClick: ((AgentUIMessage) -> Unit)? = null,
    initiallyExpanded: Boolean = true,
    environmentSnapshots: Map<String, EnvironmentSnapshot> = emptyMap()
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
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 紧凑优化：用户气泡放宽到 92% 屏宽，减少换行、降低整体纵向占用
    val maxUserBubbleWidth = remember(screenWidthDp) { (screenWidthDp * 0.92).dp }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning) {
            ReasoningBubble(text = message.reasoning.orEmpty(), initiallyExpanded = false, cache = markdownCache)
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // 助手消息左对齐，用户消息右对齐
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = if (isUser) {
                                RoundedCornerShape(Radius.md, Radius.md, Radius.xs, Radius.md)
                            } else {
                                RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs)
                            },
                            color = when (message.role) {
                                MessageRole.USER -> MaterialTheme.colorScheme.primary
                                MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surface
                                MessageRole.TOOL -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            border = if (message.role == MessageRole.ASSISTANT) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            } else null,
                            // 用户气泡按内容自适应宽度；AI/工具气泡填满可用宽度，两侧外边距由 LazyColumn contentPadding 统一提供
                            modifier = if (isUser) {
                                Modifier.widthIn(max = maxUserBubbleWidth)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        ) {
                        if (message.role == MessageRole.TOOL) {
                            ToolMessageBody(message, liveOutput = liveOutput, initiallyExpanded = initiallyExpanded, environmentSnapshots = environmentSnapshots)
                        } else {
                            val textColor = when (message.role) {
                                MessageRole.USER -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            SelectionContainer {
                                val selectionColors = if (isUser) {
                                    TextSelectionColors(
                                        handleColor = MaterialTheme.colorScheme.onPrimary,
                                        backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
                                    )
                                } else {
                                    TextSelectionColors(
                                        handleColor = MaterialTheme.colorScheme.primary,
                                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    )
                                }
                                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                                    if (isUser) {
                                        Text(
                                            text = message.content,
                                            color = textColor,
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                                        )
                                    } else {
                                        MarkdownContent(
                                            text = message.content,
                                            color = textColor,
                                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                            cache = markdownCache
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
                if (isUser && hasAttachments) {
                    MessageAttachmentPreviewRow(attachments = message.attachments)
                }
                // 气泡下方操作按钮（工具消息不显示）
                if (message.content.hasVisibleContent() && message.role != MessageRole.TOOL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        // 复制
                        MessageActionIconButton(
                            icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
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
                                icon = FeatherIcons.Edit2,
                                contentDescription = stringResource(R.string.chat_action_edit),
                                tint = iconTint,
                                onClick = { onEditClick(message) }
                            )
                        }
                        // 创建新聊天（仅用户消息）
                        if (isUser && onNewChatClick != null) {
                            MessageActionIconButton(
                                icon = FeatherIcons.MessageSquare,
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
            val namePart = summaries.joinToString("、") { s -> s.removePrefix("后台任务「").substringBefore("」") }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount, namePart)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size, namePart)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
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
}

@Composable
private fun CompactionDivider() {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
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
}