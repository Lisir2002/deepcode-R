package com.R.codecore.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.model.ReasoningEffort
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.workspace.presentation.WorkspaceViewModel
import com.R.codecore.feature.workspace.presentation.component.WorkspaceIconButton
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Square
import compose.icons.feathericons.Zap

/**
 * 输入条工具栏：模式 pill / 模型 / 工作区 常驻，思考强度与技能收纳进「更多」展开行。
 * 附件＋与发送按钮保留在右侧。
 */
@Composable
internal fun ChatInputToolbar(
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    activeProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    onSelectModel: (String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    workspaceViewModel: WorkspaceViewModel?,
    hasRunningSessions: () -> Boolean,
    onSwitchWorkspaceConfirmed: () -> Unit,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    isBusy: Boolean,
    onOpenSkills: () -> Unit,
    onOpenAttachmentSheet: () -> Unit,
    canSend: Boolean,
    tokenProgress: Float,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    var showMore by remember { mutableStateOf(false) }

    Column {
        // 收纳展开行：思考强度 + 技能对话入口
        AnimatedVisibility(
            visible = showMore,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.chat_toolbar_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                ReasoningEffortSelector(
                    effort = reasoningEffort,
                    onChange = onReasoningEffortChange,
                    enabled = !isBusy
                )
                UploadIconButton(
                    enabled = !isBusy,
                    icon = FeatherIcons.Zap,
                    contentDescription = stringResource(R.string.skill_conversation_entry),
                    onClick = onOpenSkills
                )
            }
        }

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
                ModePill(currentMode = currentMode, onToggleMode = onToggleMode)
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
            }

            // 收纳开关：展开/收起思考强度与技能
            IconButton(
                onClick = { showMore = !showMore },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    FeatherIcons.MoreHorizontal,
                    contentDescription = stringResource(R.string.chat_toolbar_more),
                    tint = if (showMore) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            UploadIconButton(
                enabled = !isBusy,
                icon = FeatherIcons.Plus,
                contentDescription = stringResource(R.string.chat_add_attachment),
                onClick = onOpenAttachmentSheet
            )
            SendButton(canSend = canSend, isBusy = isBusy, tokenProgress = tokenProgress, onSend = onSend, onStop = onStop)
        }
    }
}

/** 模式 pill：BUILD/PLAN/AUTO 循环切换，带图标 + 按压缩放反馈。 */
@Composable
private fun ModePill(
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit
) {
    val modeColor = when (currentMode) {
        AgentMode.PLAN -> MaterialTheme.colorScheme.primaryContainer
        AgentMode.AUTO -> MaterialTheme.colorScheme.error
        AgentMode.BUILD -> MaterialTheme.colorScheme.tertiary
    }
    val modeTextColor = if (currentMode == AgentMode.PLAN) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
    val modeIcon = when (currentMode) {
        AgentMode.BUILD -> FeatherIcons.Zap
        AgentMode.PLAN -> FeatherIcons.Clipboard
        AgentMode.AUTO -> FeatherIcons.Play
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "modePillScale")

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = modeColor,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                val nextMode = when (currentMode) {
                    AgentMode.BUILD -> AgentMode.PLAN
                    AgentMode.PLAN -> AgentMode.AUTO
                    AgentMode.AUTO -> AgentMode.BUILD
                }
                onToggleMode(nextMode)
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                modeIcon,
                contentDescription = null,
                tint = modeTextColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = currentMode.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = modeTextColor
                )
            )
        }
    }
}

@Composable
internal fun UploadIconButton(
    enabled: Boolean,
    icon: ImageVector,
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
            androidx.compose.foundation.Canvas(modifier = Modifier.size(44.dp)) {
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
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
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
