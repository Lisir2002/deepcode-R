package com.R.codecore.feature.settings.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillType
import com.R.codecore.feature.settings.presentation.SkillEditViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Copy
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Trash2

/**
 * 用户技能编辑器（skill_edit 路由，仅 LOCAL 技能）。
 *
 * 结构化编辑：frontmatter 表单 + SKILL.md 正文 + 脚本/文件（可切换、可新增/删除），
 * 保存前预校验（frontmatter 可解析、SCRIPT entry 存在）；支持「另存为新技能」。
 * 内置技能（BUILTIN）只读，不进入本页。
 */
@Composable
fun SkillEditScreen(
    viewModel: SkillEditViewModel,
    skillId: String,
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit = {}
) {
    val id by viewModel.id.collectAsStateWithLifecycle()
    val isBuiltin by viewModel.isBuiltin.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val currentFile by viewModel.currentFile.collectAsStateWithLifecycle()
    val currentFileContent by viewModel.currentFileContent.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val version by viewModel.version.collectAsStateWithLifecycle()
    val author by viewModel.author.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val autoTrigger by viewModel.autoTrigger.collectAsStateWithLifecycle()
    val triggerConditions by viewModel.triggerConditions.collectAsStateWithLifecycle()
    val triggerKeywords by viewModel.triggerKeywords.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val agentType by viewModel.agentType.collectAsStateWithLifecycle()
    val mcpTool by viewModel.mcpTool.collectAsStateWithLifecycle()
    val requiresRuntime by viewModel.requiresRuntime.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var showAddFile by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    LaunchedEffect(skillId) { viewModel.load(skillId) }
    LaunchedEffect(saved) {
        if (saved) {
            onSaved(skillId)
            viewModel.load(skillId)
        }
    }
    LaunchedEffect(message) {
        message?.let { viewModel.consumeMessage() }
    }

    AppTopAppBar(
        title = stringResource(R.string.skill_edit_title),
        onNavigateBack = onNavigateBack,
        navigationIcon = FeatherIcons.ArrowLeft,
        navigationContentDescription = stringResource(R.string.common_back)
    ) {
        IconButton(onClick = { viewModel.duplicate()?.let { onSaved(it) } }) {
            Icon(
                imageVector = FeatherIcons.Copy,
                contentDescription = stringResource(R.string.skill_edit_duplicate),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        TextButton(onClick = { viewModel.save() }) {
            Text(stringResource(R.string.skill_edit_save), fontWeight = FontWeight.Bold)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 文件切换条 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            files.forEach { path ->
                FilterChip(
                    selected = currentFile == path,
                    onClick = { viewModel.switchFile(path) },
                    label = { Text(path, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = if (path != "SKILL.md" && path != "CLAUDE.md" && !isBuiltin) {
                        {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.skill_edit_delete_file),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else null
                )
            }
            if (!isBuiltin) {
                AssistChip(
                    onClick = { showAddFile = true },
                    label = { Text(stringResource(R.string.skill_edit_add_file)) },
                    leadingIcon = {
                        Icon(
                            imageVector = FeatherIcons.FilePlus,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }

        // ── 编辑区 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
        ) {
            if (currentFile == "SKILL.md" || currentFile == "CLAUDE.md") {
                // frontmatter 表单
                SectionTitle(stringResource(R.string.skill_edit_frontmatter_title))
                FormField(stringResource(R.string.skill_name), name) { viewModel.name.value = it }
                FormField(stringResource(R.string.skill_description), description, minLines = 2) { viewModel.description.value = it }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = version,
                        onValueChange = { viewModel.version.value = it },
                        label = { Text(stringResource(R.string.skill_version)) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { viewModel.author.value = it },
                        label = { Text(stringResource(R.string.skill_edit_author)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                FormField(stringResource(R.string.skill_edit_tags), tags) { viewModel.tags.value = it }
                FormField(stringResource(R.string.skill_edit_required_runtime), requiresRuntime) { viewModel.requiresRuntime.value = it }
                Text(
                    text = stringResource(R.string.skill_edit_required_runtime_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // type 选择
                Text(
                    text = stringResource(R.string.skill_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SkillType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = {
                                viewModel.type.value = t
                                if (t != SkillType.SCRIPT) viewModel.entry.value = ""
                            },
                            label = { Text(t.name) }
                        )
                    }
                }
                if (type == SkillType.SCRIPT) {
                    FormField(stringResource(R.string.skill_edit_entry), entry) { viewModel.entry.value = it }
                }
                if (type == SkillType.MCP) {
                    FormField(stringResource(R.string.skill_edit_mcp_tool), mcpTool) { viewModel.mcpTool.value = it }
                }

                // scope 选择
                Text(
                    text = stringResource(R.string.skill_edit_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SkillScope.entries.forEach { s ->
                        FilterChip(
                            selected = scope == s,
                            onClick = {
                                viewModel.scope.value = s
                                if (s != SkillScope.AGENT) viewModel.agentType.value = ""
                            },
                            label = { Text(scopeLabel(s)) }
                        )
                    }
                }
                if (scope == SkillScope.AGENT) {
                    FormField(stringResource(R.string.skill_edit_agent_type), agentType) { viewModel.agentType.value = it }
                }

                // 自动触发
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.skill_edit_auto_trigger),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.skill_edit_mode_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoTrigger, onCheckedChange = { viewModel.autoTrigger.value = it })
                }
                if (autoTrigger) {
                    FormField(stringResource(R.string.skill_edit_trigger_conditions), triggerConditions, minLines = 2) { viewModel.triggerConditions.value = it }
                    FormField(stringResource(R.string.skill_edit_trigger_keywords), triggerKeywords) { viewModel.triggerKeywords.value = it }
                }

                Spacer(Modifier.height(Spacing.sm))
                SectionTitle(stringResource(R.string.skill_edit_body_title))
                OutlinedTextField(
                    value = body,
                    onValueChange = { viewModel.body.value = it },
                    label = { Text("SKILL.md") },
                    minLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            } else {
                // 脚本/其他文件编辑
                SectionTitle(currentFile)
                OutlinedTextField(
                    value = currentFileContent,
                    onValueChange = { viewModel.currentFileContent.value = it },
                    minLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (showAddFile) {
        AlertDialog(
            onDismissRequest = { showAddFile = false },
            title = { Text(stringResource(R.string.skill_edit_new_file)) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text(stringResource(R.string.skill_edit_file_name)) },
                    placeholder = { Text("scripts/tool.py") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFileName.isNotBlank(),
                    onClick = {
                        if (viewModel.addFile(newFileName.trim())) showAddFile = false
                        newFileName = ""
                    }
                ) { Text(stringResource(R.string.skill_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddFile = false }) { Text(stringResource(R.string.skill_cancel)) }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun FormField(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
    )
}

@Composable
private fun scopeLabel(scope: SkillScope): String = when (scope) {
    SkillScope.GLOBAL -> "GLOBAL"
    SkillScope.AGENT -> "AGENT"
    SkillScope.CONVERSATION -> "CONVERSATION"
}
