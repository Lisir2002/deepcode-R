package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.presentation.ConversationSkillsViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close

/**
 * 对话技能面板（D5/D10）：对话页输入框上方「技能」按钮唤出。
 *
 * 三组内容：
 * - 本对话生效：GLOBAL/AGENT 生效技能 + 已添加的 CONVERSATION 技能 → 可对本对话临时禁用。
 * - 本对话被临时禁用：可恢复（移除绑定，回到跟随声明）。
 * - 可添加的对话级技能：未启用 CONVERSATION 技能 → 添加后本对话立即全面生效（D8）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSkillsSheet(
    viewModel: ConversationSkillsViewModel,
    sessionId: String,
    onDismiss: () -> Unit
) {
    val activeSkills by viewModel.activeSkills.collectAsStateWithLifecycle()
    val disabledSkills by viewModel.disabledInConversation.collectAsStateWithLifecycle()
    val addableSkills by viewModel.addableConversationSkills.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.load(sessionId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg)
                .padding(horizontal = Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.skill_conversation_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(Spacing.md))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (activeSkills.isNotEmpty()) {
                    item(key = "__h_active") {
                        SheetSectionTitle(stringResource(R.string.skill_conversation_active))
                    }
                    items(activeSkills, key = { "a_${it.id}" }) { skill ->
                        ConversationSkillRow(
                            skill = skill,
                            actionLabel = stringResource(R.string.skill_conversation_disable),
                            actionIcon = Icons.Rounded.Close,
                            onAction = { viewModel.disableInConversation(skill.id) }
                        )
                    }
                }

                if (disabledSkills.isNotEmpty()) {
                    item(key = "__h_disabled") {
                        Spacer(Modifier.height(Spacing.sm))
                        SheetSectionTitle(stringResource(R.string.skill_conversation_disabled))
                    }
                    items(disabledSkills, key = { "d_${it.id}" }) { skill ->
                        ConversationSkillRow(
                            skill = skill,
                            actionLabel = stringResource(R.string.skill_conversation_enable),
                            actionIcon = Icons.Rounded.Check,
                            onAction = { viewModel.restoreInConversation(skill.id) }
                        )
                    }
                }

                item(key = "__h_addable") {
                    Spacer(Modifier.height(Spacing.sm))
                    SheetSectionTitle(stringResource(R.string.skill_conversation_addable))
                }
                if (addableSkills.isEmpty()) {
                    item(key = "__empty_addable") {
                        Text(
                            text = stringResource(R.string.skill_conversation_no_addable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm)
                        )
                    }
                } else {
                    items(addableSkills, key = { "c_${it.id}" }) { skill ->
                        ConversationSkillRow(
                            skill = skill,
                            actionLabel = stringResource(R.string.skill_conversation_add),
                            actionIcon = Icons.Rounded.Add,
                            onAction = { viewModel.addToConversation(skill.id) }
                        )
                    }
                }

                if (activeSkills.isEmpty() && disabledSkills.isEmpty()) {
                    item(key = "__empty_active") {
                        Text(
                            text = stringResource(R.string.skill_conversation_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ConversationSkillRow(
    skill: Skill,
    actionLabel: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit
) {
    val scopeLabel = when (skill.scope) {
        SkillScope.GLOBAL -> stringResource(R.string.skill_scope_global)
        SkillScope.AGENT -> stringResource(R.string.skill_scope_agent)
        SkillScope.CONVERSATION -> stringResource(R.string.skill_scope_conversation)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$scopeLabel · ${skill.type.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        AgentActionButton(
            text = actionLabel,
            onClick = onAction,
            tone = AgentActionTone.Neutral
        )
    }
}
