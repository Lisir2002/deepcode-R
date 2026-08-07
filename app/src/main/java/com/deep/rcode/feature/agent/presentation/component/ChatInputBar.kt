package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.ui.rememberImeBottomInset
import com.deep.rcode.feature.agent.domain.command.SlashCommandHandler
import com.deep.rcode.feature.agent.domain.model.AgentMode
import com.deep.rcode.feature.agent.domain.model.ChangeType
import com.deep.rcode.feature.agent.domain.model.CodeChange
import com.deep.rcode.feature.agent.domain.model.ReasoningEffort
import com.deep.rcode.feature.agent.domain.permission.PermissionChoice
import com.deep.rcode.feature.agent.domain.tool.PendingToolPermission
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.deep.rcode.feature.agent.presentation.AgentUIState
import com.deep.rcode.feature.agent.presentation.QueuedRequest
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.workspace.presentation.WorkspaceViewModel
import com.deep.rcode.feature.workspace.presentation.component.WorkspaceIconButton
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Square

@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isBusy: Boolean,
    workspaceViewModel: WorkspaceViewModel?,
    hasRunningSessions: () -> Boolean,
    onSwitchWorkspaceConfirmed: () -> Unit = {},
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
    tokenProgress: Float = 0f
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
            if (filteredCommands.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
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

            if (queuedRequests.isNotEmpty()) {
                QueuedRequestPanel(
                    queuedRequests = queuedRequests,
                    onRemoveQueued = onRemoveQueued
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(Radius.lg)
                    )
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                PendingAttachmentPreviewList(
                    attachments = pendingAttachments,
                    onRemoveAttachment = onRemoveAttachment
                )

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp, max = 140.dp),
                    placeholder = {
                        Text(
                            stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    enabled = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modeColor = when (currentMode) {
                            AgentMode.PLAN -> MaterialTheme.colorScheme.primaryContainer
                            AgentMode.AUTO -> MaterialTheme.colorScheme.error
                            AgentMode.BUILD -> MaterialTheme.colorScheme.tertiary
                        }
                        val modeTextColor = if (currentMode == AgentMode.PLAN) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = modeColor,
                            modifier = Modifier
                                .clickable {
                                    val nextMode = when (currentMode) {
                                        AgentMode.BUILD -> AgentMode.PLAN
                                        AgentMode.PLAN -> AgentMode.AUTO
                                        AgentMode.AUTO -> AgentMode.BUILD
                                    }
                                    onToggleMode(nextMode)
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(46.dp)
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = currentMode.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = modeTextColor
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.width(Spacing.xs))

                        ModelIconButton(
                            provider = activeProvider,
                            providers = providers,
                            onSelectModel = onSelectModel,
                            onManage = onNavigateToSettings
                        )

                        if (workspaceViewModel != null) {
                            WorkspaceIconButton(
                                viewModel = workspaceViewModel,
                                hasRunningSessions = hasRunningSessions,
                                onSwitchConfirmed = onSwitchWorkspaceConfirmed,
                                modifier = Modifier.size(36.dp),
                                iconSize = 20.dp
                            )
                        }

                        ReasoningEffortSelector(
                            effort = reasoningEffort,
                            onChange = onReasoningEffortChange,
                            enabled = !isBusy
                        )
                    }
                    UploadIconButton(
                        enabled = !isBusy,
                        icon = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chat_add_attachment),
                        onClick = { showAttachmentSheet = true }
                    )
                    SendButton(canSend = canSend, isBusy = isBusy, tokenProgress = tokenProgress, onSend = onSend, onStop = onStop)
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

@Composable
internal fun UploadIconButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SendButton(canSend: Boolean, isBusy: Boolean, tokenProgress: Float, onSend: () -> Unit, onStop: () -> Unit) {
    val clickable = isBusy || canSend
    val buttonColor = if (clickable) {
        if (isBusy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = if (clickable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val arcColor = buttonColor.copy(alpha = 0.85f)
    val clampedProgress = tokenProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .padding(Spacing.xs)
            .size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (clampedProgress > 0f) {
            Canvas(modifier = Modifier.size(44.dp)) {
                val stroke = 3.dp.toPx()
                val arcSize = size.minDimension - stroke
                val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f)
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(enabled = clickable, onClick = if (isBusy) onStop else onSend),
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                Icon(
                    FeatherIcons.Square,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun ToolPermissionPanel(
    request: PendingToolPermission,
    onChoice: (PermissionChoice) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = request.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (request.details.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = request.details,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val canRemember = request.rememberablePatterns.isNotEmpty()
            val rememberLabel = when {
                !canRemember -> request.rememberDisabledReason ?: stringResource(R.string.chat_perm_single_use_desc)
                request.rememberablePatterns == listOf("*") -> stringResource(R.string.chat_perm_always_tool_desc)
                else -> stringResource(R.string.chat_perm_always_prefix) + request.rememberablePatterns.joinToString("、")
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = rememberLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentActionButton(
                    text = stringResource(R.string.chat_perm_deny),
                    onClick = { onChoice(PermissionChoice.REJECT) },
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Danger
                )
                AgentActionButton(
                    text = stringResource(R.string.chat_perm_always_allow),
                    onClick = { onChoice(PermissionChoice.ALWAYS) },
                    modifier = Modifier.weight(1f),
                    enabled = canRemember,
                    tone = AgentActionTone.Neutral
                )
                AgentActionButton(
                    text = stringResource(R.string.common_allow),
                    onClick = { onChoice(PermissionChoice.ONCE) },
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Success
                )
            }
        }
    }
}

@Composable
internal fun StatusBanner(state: AgentUIState) {
    androidx.compose.animation.AnimatedVisibility(
        visible = state is AgentUIState.Error || state is AgentUIState.Applied,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
    ) {
        when (state) {
            is AgentUIState.Error -> InfoBanner(
                text = state.message,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                icon = FeatherIcons.AlertCircle
            )

            is AgentUIState.Applied -> InfoBanner(
                text = stringResource(R.string.chat_code_changes_applied),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = FeatherIcons.Check
            )

            else -> {}
        }
    }
}

@Composable
internal fun InfoBanner(
    text: String,
    container: Color,
    content: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = container,
        shape = RoundedCornerShape(Radius.md)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Brand.IconGray, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(text, color = content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ChangePreviewPanel(
    changes: List<CodeChange>,
    onApply: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                stringResource(R.string.chat_preview_changes, changes.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                modifier = Modifier.heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(changes) { change -> ChangeItem(change) }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentActionButton(
                    text = stringResource(R.string.chat_perm_deny),
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Danger
                )
                AgentActionButton(
                    text = stringResource(R.string.chat_apply),
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Success
                )
            }
        }
    }
}

@Composable
fun ChangeItem(change: CodeChange) {
    val accent = when (change.type) {
        ChangeType.CREATE -> MaterialTheme.colorScheme.tertiary
        ChangeType.DELETE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (change.type) {
                ChangeType.CREATE -> "+"
                ChangeType.DELETE -> "−"
                ChangeType.REPLACE -> "~"
                else -> "→"
            },
            modifier = Modifier.width(20.dp),
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${change.filePath.substringAfterLast('/')} · L${change.startLine}-${change.endLine}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 计划审查面板：AI 从 PLAN 模式切回 BUILD 时弹出，展示计划摘要供用户批准或继续反馈。
 * 风格与 ToolPermissionPanel / AskUserQuestionPanel 一致。
 */
@Composable
internal fun PlanApprovalPanel(
    state: PlanApprovalRequest,
    onApprove: () -> Unit,
    onRefine: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_plan_completed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (state.reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentActionButton(
                    text = stringResource(R.string.chat_continue_feedback),
                    onClick = onRefine,
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Neutral
                )
                AgentActionButton(
                    text = stringResource(R.string.chat_approve_and_implement),
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Success
                )
            }
        }
    }
}