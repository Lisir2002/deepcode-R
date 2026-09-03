package com.core.deepcode.feature.settings.presentation.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.core.deepcode.R
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.domain.skill.Skill
import com.core.deepcode.feature.agent.domain.skill.SkillScope
import com.core.deepcode.feature.agent.domain.skill.SkillSourceType
import com.core.deepcode.feature.agent.domain.skill.SkillType
import com.core.deepcode.feature.settings.presentation.SkillsViewModel

/**
 * 技能中心（能力中心 → 技能 Tab）：分组 / 搜索 / 筛选 / 卡片增强 / 操作按钮化 / 导入导出。
 */
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    onOpenSkillDetail: (String) -> Unit,
    onEditSkill: (String) -> Unit = {}
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<SkillType?>(null) }
    var enabledFilter by remember { mutableStateOf<Boolean?>(null) }
    var autoFilter by remember { mutableStateOf<Boolean?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }

    // Toast 提示
    LaunchedEffect(message) {
        message?.let {
            // 简单处理：message 由 consumeMessage 消费（本页无 Toast 组件，用顶部横幅提示）
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(importState) {
        if (importState is SkillsViewModel.ImportUiState.Done) {
            viewModel.consumeImportDone()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 搜索 + 导入入口 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.skill_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(Spacing.sm))
            IconButton(onClick = { showImportSheet = true }) {
                Icon(
                    imageVector = Icons.Rounded.Upload,
                    contentDescription = stringResource(R.string.skill_import),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ── 筛选条 ──
        FilterBar(
            typeFilter = typeFilter,
            enabledFilter = enabledFilter,
            autoFilter = autoFilter,
            onType = { typeFilter = it },
            onEnabled = { enabledFilter = it },
            onAuto = { autoFilter = it }
        )

        // ── 列表 ──
        Box(modifier = Modifier.weight(1f)) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                val filtered = remember(skills, searchQuery, typeFilter, enabledFilter, autoFilter) {
                    skills.filter { s ->
                        (searchQuery.isBlank() || s.name.contains(searchQuery, true) ||
                            s.description.contains(searchQuery, true) ||
                            s.tags.any { it.contains(searchQuery, true) }) &&
                            (typeFilter == null || s.type == typeFilter) &&
                            (enabledFilter == null || s.enabled == enabledFilter) &&
                            (autoFilter == null || s.autoTrigger == autoFilter)
                    }.sortedBy { it.name.lowercase() }
                }
                if (filtered.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.skill_list_empty),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.skill_list_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val builtin = filtered.filter { it.source == SkillSourceType.BUILTIN }
                    val mine = filtered.filter { it.source == SkillSourceType.LOCAL }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Spacing.md, end = Spacing.md, bottom = Spacing.lg
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        if (builtin.isNotEmpty()) {
                            item(key = "group_builtin") { GroupHeader(stringResource(R.string.skill_group_builtin), builtin.size) }
                            items(builtin, key = { "b_${it.id}" }) { skill ->
                                SkillCard(
                                    skill = skill,
                                    onToggle = { viewModel.setEnabled(skill.id, it) },
                                    onOpen = { onOpenSkillDetail(skill.id) },
                                    onEdit = null,
                                    onDelete = null
                                )
                            }
                        }
                        if (mine.isNotEmpty()) {
                            item(key = "group_mine") { GroupHeader(stringResource(R.string.skill_group_mine), mine.size) }
                            items(mine, key = { "l_${it.id}" }) { skill ->
                                SkillCard(
                                    skill = skill,
                                    onToggle = { viewModel.setEnabled(skill.id, it) },
                                    onOpen = { onOpenSkillDetail(skill.id) },
                                    onEdit = { onEditSkill(skill.id) },
                                    onDelete = { pendingDelete = skill }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 卸载确认 ──
    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.skill_uninstall)) },
            text = { Text(stringResource(R.string.skill_uninstall_confirm, skill.name, skill.version)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(skill)
                    pendingDelete = null
                }) { Text(stringResource(R.string.skill_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.skill_cancel)) }
            }
        )
    }

    // ── 导入源选择弹窗 ──
    if (showImportSheet) {
        ImportSourceSheet(
            viewModel = viewModel,
            onDismiss = { showImportSheet = false }
        )
    }

    // ── 导入状态弹窗（预览 / 非法 / 冲突 / 完成） ──
    when (val state = importState) {
        is SkillsViewModel.ImportUiState.Preparing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.skill_importing)) },
                text = { CircularProgressIndicator() },
                confirmButton = {}
            )
        }
        is SkillsViewModel.ImportUiState.Ready -> ImportPreviewDialog(
            preview = state.preview,
            onConfirm = { viewModel.confirmImport(false) },
            onDismiss = { viewModel.cancelImport() }
        )
        is SkillsViewModel.ImportUiState.Illegal -> AlertDialog(
            onDismissRequest = { viewModel.dismissImport() },
            title = { Text(stringResource(R.string.skill_import_blocked)) },
            text = { Column { state.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissImport() }) { Text(stringResource(R.string.skill_confirm)) }
            }
        )
        is SkillsViewModel.ImportUiState.Conflict -> AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text(stringResource(R.string.skill_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.existingSource == SkillSourceType.BUILTIN) R.string.skill_conflict_builtin
                        else R.string.skill_conflict_overwrite
                    )
                )
            },
            confirmButton = {
                if (state.existingSource == SkillSourceType.LOCAL) {
                    TextButton(onClick = { viewModel.confirmImport(true) }) {
                        Text(stringResource(R.string.skill_overwrite))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) { Text(stringResource(R.string.skill_cancel)) }
            }
        )
        is SkillsViewModel.ImportUiState.Done -> AlertDialog(
            onDismissRequest = { viewModel.consumeImportDone() },
            title = { Text(stringResource(R.string.skill_import_success)) },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeImportDone() }) { Text(stringResource(R.string.skill_confirm)) }
            }
        )
        SkillsViewModel.ImportUiState.Idle -> Unit
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
    )
}

// ───────────────────────── 筛选条 ─────────────────────────

@Composable
private fun FilterBar(
    typeFilter: SkillType?,
    enabledFilter: Boolean?,
    autoFilter: Boolean?,
    onType: (SkillType?) -> Unit,
    onEnabled: (Boolean?) -> Unit,
    onAuto: (Boolean?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        FilterChip(
            selected = typeFilter == null,
            onClick = { onType(null) },
            label = { Text(stringResource(R.string.skill_filter_type_all)) }
        )
        SkillType.entries.forEach { t ->
            FilterChip(
                selected = typeFilter == t,
                onClick = { onType(if (typeFilter == t) null else t) },
                label = { Text(t.name) }
            )
        }
        FilterChip(
            selected = enabledFilter == null,
            onClick = { onEnabled(null) },
            label = { Text(stringResource(R.string.skill_filter_state_all)) }
        )
        FilterChip(
            selected = enabledFilter == true,
            onClick = { onEnabled(if (enabledFilter == true) null else true) },
            label = { Text(stringResource(R.string.skill_filter_enabled)) }
        )
        FilterChip(
            selected = enabledFilter == false,
            onClick = { onEnabled(if (enabledFilter == false) null else false) },
            label = { Text(stringResource(R.string.skill_filter_disabled)) }
        )
        FilterChip(
            selected = autoFilter == true,
            onClick = { onAuto(if (autoFilter == true) null else true) },
            label = { Text(stringResource(R.string.skill_filter_auto)) }
        )
    }
}

// ───────────────────────── 技能卡片 ─────────────────────────

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TypeBadge(type = skill.type)
            }
            Spacer(Modifier.height(Spacing.xs))
            // 徽章行：来源 / 自动触发 / 作用域
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (skill.source == SkillSourceType.BUILTIN) {
                    InfoChip(stringResource(R.string.skill_source_builtin), Color(0xFF9E9E9E))
                }
                if (skill.autoTrigger) {
                    InfoChip(stringResource(R.string.skill_auto_trigger), Color(0xFFFFB300))
                }
                InfoChip(
                    label = when (skill.scope) {
                        SkillScope.GLOBAL -> stringResource(R.string.skill_scope_global)
                        SkillScope.AGENT -> stringResource(R.string.skill_scope_agent)
                        SkillScope.CONVERSATION -> stringResource(R.string.skill_scope_conversation)
                    },
                    color = when (skill.scope) {
                        SkillScope.GLOBAL -> Color(0xFF26A69A)
                        SkillScope.AGENT -> Color(0xFF7E57C2)
                        SkillScope.CONVERSATION -> Color(0xFFEF5350)
                    }
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = skill.description.ifBlank { stringResource(R.string.skill_no_description) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            if (skill.tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = skill.tags.take(3).joinToString(" · ") { "#$it" } + if (skill.tags.size > 3) " · +${skill.tags.size - 3}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "v${skill.version}" + (skill.author?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 操作区
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(Spacing.sm))
                OutlinedButton(onClick = onOpen, modifier = Modifier.height(32.dp)) {
                    Icon(imageVector = Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.skill_view), style = MaterialTheme.typography.labelMedium)
                }
                if (skill.source == SkillSourceType.LOCAL) {
                    if (onEdit != null) {
                        Spacer(Modifier.width(Spacing.xs))
                        OutlinedButton(onClick = onEdit, modifier = Modifier.height(32.dp)) {
                            Icon(imageVector = Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.skill_edit), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.skill_uninstall),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

// ───────────────────────── 导入源选择 ─────────────────────────

@Composable
private fun ImportSourceSheet(
    viewModel: SkillsViewModel,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf<ImportMode>(ImportMode.SELECT) }
    var pasteName by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var urlText by remember { mutableStateOf("") }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.prepareZip(it) }
        onDismiss()
    }
    val mdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "skill.md"
            // 读取内容交给 importer（prepareFromMarkdown 由 ViewModel 内异步读取 Uri）
            viewModel.prepareMarkdownFromUri(it, fileName)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_import)) },
        text = {
            when (mode) {
                ImportMode.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ImportOption(stringResource(R.string.skill_import_zip)) { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }
                    ImportOption(stringResource(R.string.skill_import_md)) { mdPicker.launch(arrayOf("text/markdown", "text/plain", "*/*")) }
                    ImportOption(stringResource(R.string.skill_import_paste)) { mode = ImportMode.PASTE }
                    ImportOption(stringResource(R.string.skill_import_url)) { mode = ImportMode.URL }
                }
                ImportMode.PASTE -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = pasteName,
                        onValueChange = { pasteName = it },
                        label = { Text(stringResource(R.string.skill_paste_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it },
                        label = { Text(stringResource(R.string.skill_paste_content)) },
                        minLines = 6
                    )
                    TextButton(
                        enabled = pasteContent.isNotBlank(),
                        onClick = {
                            val name = pasteName.ifBlank { "pasted-skill" }
                            viewModel.prepareMarkdown(name, pasteContent)
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.skill_confirm)) }
                }
                ImportMode.URL -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text(stringResource(R.string.skill_url_hint)) },
                        singleLine = true
                    )
                    TextButton(
                        enabled = urlText.isNotBlank(),
                        onClick = {
                            viewModel.prepareUrl(urlText.trim())
                            onDismiss()
                        }
                    ) { Text(stringResource(R.string.skill_download)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_cancel)) }
        }
    )
}

private enum class ImportMode { SELECT, PASTE, URL }

@Composable
private fun ImportOption(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: com.core.deepcode.feature.settings.domain.SkillImporter.SkillImportPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_import_preview)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("${stringResource(R.string.skill_name)}: ${preview.name} (${preview.id})")
                Text("${stringResource(R.string.skill_version)}: v${preview.version}")
                Text("${stringResource(R.string.skill_type)}: ${preview.type.name}")
                if (preview.description.isNotBlank()) {
                    Text("${stringResource(R.string.skill_description)}: ${preview.description.take(120)}")
                }
                if (preview.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.skill_import_warnings),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                    preview.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.skill_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_cancel)) }
        }
    )
}
