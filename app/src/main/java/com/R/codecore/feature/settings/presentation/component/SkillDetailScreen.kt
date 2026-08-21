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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.skill.SkillSourceType
import com.R.codecore.feature.agent.presentation.component.MarkdownContent
import com.R.codecore.feature.git.presentation.component.highlightCode
import com.R.codecore.feature.git.presentation.component.inferSyntaxLanguage
import com.R.codecore.feature.settings.presentation.SkillDetailViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.File
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Share2
import compose.icons.feathericons.X

/**
 * 技能查看页（skill_detail 路由）。
 *
 * 默认渲染 SKILL.md；顶部「目录」按钮唤出半屏目录树弹窗查看全部文件（规则文档 / 脚本等）。
 * 文件按扩展名分派：md→Markdown、代码→语法高亮、图片→Image、其他/二进制→元信息。
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
    val message by viewModel.message.collectAsStateWithLifecycle()

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var showDirectorySheet by remember { mutableStateOf(false) }
    var fileContent by remember { mutableStateOf<FileContent?>(null) }

    // 默认选中 SKILL.md；技能加载后若未指定文件则切到 SKILL.md
    LaunchedEffect(skillId) {
        viewModel.load(skillId)
    }
    LaunchedEffect(skill) {
        if (skill != null && selectedPath == null) {
            selectedPath = "SKILL.md"
        }
    }
    // 切文件时读取内容
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
            SkillDetailViewModel.FileKind.IMAGE -> {
                FileContent.Image(java.io.File(dir, path))
            }
            SkillDetailViewModel.FileKind.BINARY -> FileContent.Unavailable
        }
    }
    LaunchedEffect(message) {
        message?.let { viewModel.consumeMessage() }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (skill == null) {
            Text(
                text = stringResource(R.string.skill_list_empty),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            when (val content = fileContent) {
                null -> Unit
                is FileContent.Text -> when (content.kind) {
                    SkillDetailViewModel.FileKind.MARKDOWN -> MarkdownContent(
                        text = content.text,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    else -> CodeContentView(code = content.text, path = selectedPath ?: "")
                }
                is FileContent.Image -> ImageContentView(file = content.file)
                FileContent.Unavailable -> BinaryPlaceholder(path = selectedPath ?: "")
            }
        }
    }

    if (showDirectorySheet) {
        ModalBottomSheet(onDismissRequest = { showDirectorySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.lg)
            ) {
                Text(
                    text = stringResource(R.string.skill_detail_directory),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    fileTree.forEach { node ->
                        item(key = node.path) {
                            DirectoryNodeRow(
                                node = node,
                                depth = 0,
                                currentPath = selectedPath,
                                onSelect = { path ->
                                    selectedPath = path
                                    showDirectorySheet = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface FileContent {
    data class Text(val text: String, val kind: SkillDetailViewModel.FileKind) : FileContent
    data class Image(val file: java.io.File) : FileContent
    data object Unavailable : FileContent
}

/** 递归渲染目录树节点（可点击文件切换主内容）。 */
@Composable
private fun DirectoryNodeRow(
    node: SkillDetailViewModel.SkillFileNode,
    depth: Int,
    currentPath: String?,
    onSelect: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !node.isDirectory) { if (!node.isDirectory) onSelect(node.path) }
                .padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (node.isDirectory) FeatherIcons.Folder else FeatherIcons.File,
                contentDescription = null,
                tint = if (node.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (node.path == currentPath) FontWeight.Bold else FontWeight.Normal,
                color = if (node.path == currentPath) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
        node.children.forEach { child ->
            DirectoryNodeRow(child, depth + 1, currentPath, onSelect)
        }
    }
}

/** 代码/文本文件渲染：语法高亮 + 等宽字体 + 纵向滚动。 */
@Composable
private fun CodeContentView(code: String, path: String) {
    val annotated = remember(code, path) { highlightCode(code, inferSyntaxLanguage(path)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md)
    ) {
        Text(
            text = annotated ?: AnnotatedString(code),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun ImageContentView(file: java.io.File) {
    val bitmap = remember(file) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        BinaryPlaceholder(file.name)
    } else {
        Box(modifier = Modifier.fillMaxSize().padding(Spacing.md), contentAlignment = Alignment.Center) {
            Image(bitmap = bitmap, contentDescription = file.name)
        }
    }
}

@Composable
private fun BinaryPlaceholder(path: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = FeatherIcons.File,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.skill_detail_binary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
