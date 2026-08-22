package com.R.codecore.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.Elevation
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.model.ChangeType
import com.R.codecore.feature.agent.domain.model.CodeChange
import com.R.codecore.feature.agent.domain.permission.PermissionChoice
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.R.codecore.feature.agent.presentation.AgentUIState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check

/**
 * 聊天区浮层面板集合：工具权限审批、状态横幅、变更预览、计划审批。
 *
 * 这些面板以浮层/卡片形式出现在聊天页中，与输入条（ChatInputBar）解耦，
 * 避免输入条文件无限膨胀。
 */

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
        tonalElevation = Elevation.z2,
        shadowElevation = Elevation.z2,
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
                                fontFamily = FontFamily.Monospace
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
    AnimatedVisibility(
        visible = state is AgentUIState.Error || state is AgentUIState.Applied,
        enter = fadeIn(),
        exit = fadeOut()
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
    icon: ImageVector
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
        shadowElevation = Elevation.z2,
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
        shadowElevation = Elevation.z2,
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
