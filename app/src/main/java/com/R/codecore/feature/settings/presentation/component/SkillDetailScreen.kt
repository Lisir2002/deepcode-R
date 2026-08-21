package com.R.codecore.feature.settings.presentation.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppEmptyState
import com.R.codecore.core.theme.AppLoadingState
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillSourceType
import com.R.codecore.feature.agent.domain.skill.SkillType
import com.R.codecore.feature.agent.presentation.component.MarkdownContent
import com.R.codecore.feature.git.presentation.component.highlightCode
import com.R.codecore.feature.git.presentation.component.inferSyntaxLanguage
import com.R.codecore.feature.settings.presentation.SkillDetailViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Share2
import java.io.File

/**
 * 技能查看页（skill_detail 路由）。
 *
 * 顶部 Hero 卡片展示技能元信息（名称/类型/来源/自动触发/作用域/版本/描述/标签）；
 * 主内容按扩展名分派渲染（md→Markdown、代码→语法高亮、图片→采样解码、其他→元信息）；
 * 「当前文件」面包屑条 + 顶部「目录」按钮均可唤出可折叠的目录树弹窗切换文件；
 * LOCAL 技能提供「编辑」「导出」入口；BUILTIN 只读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    viewModel: SkillDetailViewModel,
    skillId: String,
    onNavigateBack: () -> Unit,
    onEditSkill: (String) -> Unit = {}
) {
    val skill by viewModel.skill.collectAsStateWithLifecycle()
    val fileTree by viewModel.fileTree.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var showDirectorySheet by remember { mutableStateOf(false) }
    var fileContent by remember { mutableStateOf<FileContent?>(null) }
    // 目录树折叠集合（存目录 path，展开状态随页面保留）
    var collapsedPaths by remember { mutableStateOf(setOf<String>()) }
    // 页内 Tab：0=文件，1=详情
    var selectedTab by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 每次 resume（含从编辑器返回）重新扫描，保证查看的是最新技能内容。
    // 不能用 LaunchedEffect(skillId)：返回本页时 key 不变不重跑，编辑保存后内容不刷新。
    LifecycleResumeEffect(Unit) {
        viewModel.load(skillId)
        onPauseOrDispose { }
    }
    // 初始默认选中：优先 SKILL.md / CLAUDE.md，否则取第一个文件，避免「技能无 SKILL.md 时空白页」。
    LaunchedEffect(skill, fileTree) {
        if (skill != null && selectedPath == null) {
            selectedPath = viewModel.findDefaultPath() ?: "SKILL.md"
        }
    }
    // 切文件时读取内容（异步 IO，避免 UI 线程读盘卡顿）
    LaunchedEffect(skill, selectedPath) {
        val path = selectedPath ?: return@LaunchedEffect
        val current = viewModel.skill.value ?: return@LaunchedEffect
        val dir = current.dir ?: return@LaunchedEffect
        fileContent = when (viewModel.classifyFile(path)) {
            SkillDetailViewModel.FileKind.MARKDOWN,
            SkillDetailViewModel.FileKind.CODE,
            SkillDetailViewModel.FileKind.TEXT -> {
                viewModel.readFile(path)?.let { FileContent.Text(it, viewModel.classifyFile(path)) }
                    ?: FileContent.Unavailable
            }
            SkillDetailViewModel.FileKind.IMAGE -> FileContent.Image(File(dir, path))
            SkillDetailViewModel.FileKind.BINARY -> FileContent.Unavailable
        }
    }
    // 操作结果（导出成功/失败/技能不存在）以 Snackbar 提示，杜绝静默消费
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = skill?.name ?: stringResource(R.string.skill_edit_title),
                onNavigateBack = onNavigateBack,
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.common_back)
            ) {
                if (skill?.source == SkillSourceType.LOCAL) {
                    IconButton(onClick = { viewModel.export() }) {
                        Icon(
                            imageVector = FeatherIcons.Share2,
                            contentDescription = stringResource(R.string.skill_detail_export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { onEditSkill(skillId) }) {
                        Icon(
                            imageVector = FeatherIcons.Edit2,
                            contentDescription = stringResource(R.string.skill_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = { showDirectorySheet = true }) {
                    Icon(
                        imageVector = FeatherIcons.Folder,
                        contentDescription = stringResource(R.string.skill_detail_directory),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 页内 Tab 导航：文件 / 详情 ──
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.skill_tab_files)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.skill_tab_detail)) }
                )
            }
            when (selectedTab) {
                0 -> FilesTab(
                    skill = skill,
                    loading = loading,
                    selectedPath = selectedPath,
                    fileContent = fileContent,
                    onOpenDirectory = { showDirectorySheet = true }
                )
                else -> DetailTab(skill = skill)
            }
        }
    }

    if (showDirectorySheet) {
        DirectorySheet(
            fileTree = fileTree,
            currentPath = selectedPath,
            collapsedPaths = collapsedPaths,
            onToggleCollapse = { path ->
                collapsedPaths = if (path in collapsedPaths) collapsedPaths - path else collapsedPaths + path
            },
            onSelect = { path ->
                selectedPath = path
                showDirectorySheet = false
            },
            onDismiss = { showDirectorySheet = false }
        )
    }
}

// ───────────────────────── 文件 Tab / 详情 Tab ─────────────────────────

/** 文件 Tab：当前文件面包屑 + 内容渲染（Markdown 可垂直滚动）。 */
@Composable
private fun FilesTab(
    skill: Skill?,
    loading: Boolean,
    selectedPath: String?,
    fileContent: FileContent?,
    onOpenDirectory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md)
    ) {
        // ── 当前文件面包屑条（点击唤出目录树）──
        if (skill != null) {
            Spacer(Modifier.height(Spacing.sm))
            CurrentFileBar(path = selectedPath ?: "", onClick = onOpenDirectory)
            Spacer(Modifier.height(Spacing.sm))
        }
        // ── 内容渲染区 ──
        Box(modifier = Modifier.weight(1f)) {
            when {
                loading -> AppLoadingState(stringResource(R.string.skill_loading))
                skill == null -> AppEmptyState(
                    icon = FeatherIcons.File,
                    title = stringResource(R.string.skill_not_found)
                )
                selectedPath == null -> AppEmptyState(
                    icon = FeatherIcons.Folder,
                    title = stringResource(R.string.skill_detail_no_files)
                )
                else -> {
                    val path = selectedPath ?: ""
                    when (val content = fileContent) {
                        null -> AppLoadingState(stringResource(R.string.skill_loading))
                        is FileContent.Text -> when (content.kind) {
                            SkillDetailViewModel.FileKind.MARKDOWN -> MarkdownContent(
                                text = content.text,
                                color = MaterialTheme.colorScheme.onBackground,
                                // SegmentedContent 自身无滚动容器，需外层提供垂直滚动
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            )
                            else -> CodeContentView(code = content.text, path = path)
                        }
                        is FileContent.Image -> ImageContentView(file = content.file)
                        FileContent.Unavailable -> BinaryPlaceholder(path = path)
                    }
                }
            }
        }
    }
}

/** 详情 Tab：技能元信息详情（可滚动）。 */
@Composable
private fun DetailTab(skill: Skill?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md)
    ) {
        if (skill == null) {
            AppEmptyState(
                icon = FeatherIcons.File,
                title = stringResource(R.string.skill_not_found)
            )
        } else {
            HeroCard(skill)
        }
    }
}

private sealed interface FileContent {
    data class Text(val text: String, val kind: SkillDetailViewModel.FileKind) : FileContent
    data class Image(val file: File) : FileContent
    data object Unavailable : FileContent
}

// ───────────────────────── Hero 元信息卡片 ─────────────────────────

@Composable
private fun HeroCard(skill: Skill) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            // 名称 + 类型徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                TypeBadge(skill.type)
            }
            // 徽章行：来源 / 自动触发 / 作用域
            Spacer(Modifier.height(Spacing.xs))
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
            // 描述
            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 标签
            if (skill.tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = skill.tags.take(3).joinToString("  ") { "#$it" } +
                        if (skill.tags.size > 3) " · +${skill.tags.size - 3}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
            // 版本 · 作者 · id
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = buildString {
                    append("v").append(skill.version)
                    if (!skill.author.isNullOrBlank()) append(" · ").append(skill.author)
                    append(" · ").append(skill.id)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ───────────────────────── 当前文件面包屑条 ─────────────────────────

@Composable
private fun CurrentFileBar(path: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.File,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ───────────────────────── 目录树弹窗（可折叠） ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectorySheet(
    fileTree: List<SkillDetailViewModel.SkillFileNode>,
    currentPath: String?,
    collapsedPaths: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.skill_detail_directory),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                fileTree.forEach { node ->
                    item(key = node.path) {
                        DirectoryNodeRow(
                            node = node,
                            depth = 0,
                            currentPath = currentPath,
                            collapsedPaths = collapsedPaths,
                            onToggleCollapse = onToggleCollapse,
                            onSelect = onSelect
                        )
                    }
                }
            }
        }
    }
}

/** 递归目录树节点：目录可折叠/展开（带 chevron），当前文件高亮 pill。 */
@Composable
private fun DirectoryNodeRow(
    node: SkillDetailViewModel.SkillFileNode,
    depth: Int,
    currentPath: String?,
    collapsedPaths: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val isDirectory = node.isDirectory
    val isExpanded = !isDirectory || node.path !in collapsedPaths
    val isSelected = node.path == currentPath

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .clickable { if (isDirectory) onToggleCollapse(node.path) else onSelect(node.path) }
                .padding(start = (depth * 16).dp + Spacing.xs, top = 6.dp, bottom = 6.dp, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDirectory) {
                Icon(
                    imageVector = if (isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Icon(
                imageVector = if (isDirectory) FeatherIcons.Folder else FeatherIcons.File,
                contentDescription = null,
                tint = if (isDirectory) MaterialTheme.colorScheme.primary
                else if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (isDirectory && isExpanded) {
            node.children.forEach { child ->
                DirectoryNodeRow(
                    node = child,
                    depth = depth + 1,
                    currentPath = currentPath,
                    collapsedPaths = collapsedPaths,
                    onToggleCollapse = onToggleCollapse,
                    onSelect = onSelect
                )
            }
        }
    }
}

// ───────────────────────── 文件内容渲染 ─────────────────────────

/** 代码/文本文件渲染：语言标签 + 卡片化 + 语法高亮 + 等宽字体。 */
@Composable
private fun CodeContentView(code: String, path: String) {
    val language = remember(path) { inferSyntaxLanguage(path) }
    val annotated = remember(code, language) { highlightCode(code, language) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.sm),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.File,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = path + (language?.let { "  ·  ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Surface(
            shape = RoundedCornerShape(Radius.sm),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = annotated ?: AnnotatedString(code),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(Spacing.md)
            )
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

/** 图片渲染：采样解码防 OOM + 内容适配居中 + 圆角。 */
@Composable
private fun ImageContentView(file: File) {
    val bitmap = remember(file) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (
                (bounds.outWidth / sample) > 2048 ||
                (bounds.outHeight / sample) > 2048
            ) {
                sample *= 2
            }
            val full = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, full)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        BinaryPlaceholder(file.name)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.sm))
            )
        }
    }
}

@Composable
private fun BinaryPlaceholder(path: String) {
    AppEmptyState(
        icon = FeatherIcons.File,
        title = stringResource(R.string.skill_detail_binary),
        subtitle = path
    )
}

// ───────────────────────── 通用徽章（与技能列表页保持一致） ─────────────────────────

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
