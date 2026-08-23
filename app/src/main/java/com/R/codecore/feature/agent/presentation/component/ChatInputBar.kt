package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.ui.rememberImeBottomInset
import com.R.codecore.feature.agent.domain.command.SlashCommandHandler
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.model.ReasoningEffort
import com.R.codecore.feature.agent.presentation.QueuedRequest
import com.R.codecore.feature.settings.domain.model.AIProviderConfig

/**
 * 聊天输入条编排入口：胶囊浮动条容器 + 输入框（ChatInputField）+ 工具栏（ChatInputToolbar）。
 *
 * 斜杠命令菜单与队列面板浮于胶囊上方，不拉高输入条；附件弹层仍由本组件管理。
 * 面板类组件（权限审批 / 状态横幅 / 变更预览 / 计划审批）见 ChatPanels.kt。
 * 工作区管理入口已移至侧边栏「工作目录」tab，输入条不再持有工作区选择按钮。
 */
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean,
    activeProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    onSelectModel: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    pendingAttachments: List<PendingUploadAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    canUploadFiles: Boolean,
    canUploadImages: Boolean,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    slashCommands: List<SlashCommandHandler> = emptyList(),
    queuedRequests: List<QueuedRequest> = emptyList(),
    onRemoveQueued: (String) -> Unit = {},
    tokenProgress: Float = 0f,
    onOpenSkills: () -> Unit = {}
) {
    val canSend = (value.isNotBlank() || pendingAttachments.isNotEmpty()) && !isBusy
    var showAttachmentSheet by remember { mutableStateOf(false) }
    val showSlashMenu = !isBusy && slashCommands.isNotEmpty() &&
        value.startsWith("/") && !value.contains("\n")
    val filteredCommands = if (showSlashMenu) {
        if (value == "/") slashCommands
        else slashCommands.filter { it.trigger.startsWith(value) }
    } else emptyList()

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = rememberImeBottomInset())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            // 斜杠命令菜单：胶囊上方独立浮层
            if (filteredCommands.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    ) {
                        filteredCommands.forEach { command ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable { onValueChange(command.trigger) }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    command.trigger,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 队列面板：胶囊上方独立浮层
            if (queuedRequests.isNotEmpty()) {
                QueuedRequestPanel(
                    queuedRequests = queuedRequests,
                    onRemoveQueued = onRemoveQueued
                )
            }

            // 胶囊浮动条：输入框 + 工具栏
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    ChatInputField(
                        value = value,
                        onValueChange = onValueChange,
                        onSend = onSend,
                        isBusy = isBusy,
                        pendingAttachments = pendingAttachments,
                        onRemoveAttachment = onRemoveAttachment
                    )

                    ChatInputToolbar(
                        currentMode = currentMode,
                        onToggleMode = onToggleMode,
                        activeProvider = activeProvider,
                        providers = providers,
                        onSelectModel = onSelectModel,
                        onNavigateToSettings = onNavigateToSettings,
                        reasoningEffort = reasoningEffort,
                        onReasoningEffortChange = onReasoningEffortChange,
                        isBusy = isBusy,
                        onOpenSkills = onOpenSkills,
                        onOpenAttachmentSheet = { showAttachmentSheet = true },
                        canSend = canSend,
                        tokenProgress = tokenProgress,
                        onSend = onSend,
                        onStop = onStop
                    )
                }
            }
        }
    }

    if (showAttachmentSheet) {
        AttachmentSheet(
            canUploadFiles = canUploadFiles && !isBusy,
            canUploadImages = canUploadImages && !isBusy,
            onUploadFile = {
                showAttachmentSheet = false
                onUploadFile()
            },
            onUploadImage = {
                showAttachmentSheet = false
                onUploadImage()
            },
            onTakePhoto = {
                showAttachmentSheet = false
                onTakePhoto()
            },
            onDismiss = { showAttachmentSheet = false }
        )
    }
}
