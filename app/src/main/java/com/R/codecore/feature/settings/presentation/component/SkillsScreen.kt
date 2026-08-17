package com.R.codecore.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillType
import com.R.codecore.feature.settings.presentation.SkillsViewModel

/**
 * 技能中心（RC74 新增）：浏览、启用/禁用、卸载技能。
 */
@Composable
fun SkillsScreen(viewModel: SkillsViewModel) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var expandedSkill by remember { mutableStateOf<Skill?>(null) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(Spacing.md)) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (skills.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "暂无技能",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "技能存放在容器 skills 目录（SKILL.md），或通过后续导入功能安装。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggle = { viewModel.setEnabled(skill.id, it) },
                        onClick = { expandedSkill = skill },
                        onDelete = { pendingDelete = skill }
                    )
                }
            }
        }
    }

    // 详情弹窗
    expandedSkill?.let { skill ->
        SkillDetailDialog(skill = skill, onDismiss = { expandedSkill = null })
    }

    // 卸载确认
    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("卸载技能") },
            text = { Text("确定卸载技能「${skill.name}」（v${skill.version}）吗？此操作会删除技能文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(skill)
                    pendingDelete = null
                }) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    TypeBadge(type = skill.type)
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = skill.description.ifBlank { "（无描述）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "v${skill.version}" + (skill.author?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = skill.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun TypeBadge(type: SkillType) {
    val (label, color) = when (type) {
        SkillType.PROMPT -> "PROMPT" to Color(0xFF4CAF50)
        SkillType.SCRIPT -> "SCRIPT" to Color(0xFFFF9800)
        SkillType.MCP -> "MCP" to Color(0xFF2196F3)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SkillDetailDialog(skill: Skill, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name) },
        text = {
            Column {
                Text("类型: ${skill.type.name}", style = MaterialTheme.typography.bodyMedium)
                Text("版本: v${skill.version}", style = MaterialTheme.typography.bodyMedium)
                skill.author?.let { Text("作者: $it", style = MaterialTheme.typography.bodyMedium) }
                if (skill.tags.isNotEmpty()) {
                    Text("标签: ${skill.tags.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                }
                if (skill.dependencies.isNotEmpty()) {
                    Text("依赖: ${skill.dependencies.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                }
                skill.entry?.let { Text("入口: $it", style = MaterialTheme.typography.bodyMedium) }
                skill.mcpTool?.let { Text("MCP 工具: $it", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = skill.description.ifBlank { "（无描述）" },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
