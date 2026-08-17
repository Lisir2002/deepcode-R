package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Tool

/** 文件变更类型对应的强调色：新增=绿、修改=蓝、删除=红。 */
private val createColor = Color(0xFF22C55E)
private val modifyColor = Color(0xFF3B82F6)
private val deleteColor = Color(0xFFEF4444)

/**
 * 任务变更底部弹窗：占屏约 8/10，双 Tab ——「文件修改」与「日志」。
 * - 文件修改 Tab：顶部文件切换标签（含类型标签），内容区展示选中文件的 diff 或删除提示。
 * - 日志 Tab：按时间顺序展示任务内全部工具执行日志。
 * 点击弹窗外部或下拉关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileDiffSheet(
    fileDiffs: List<EditDiff>,
    logs: List<ToolLogEntry>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
    ) {
        // 占屏 8/10：ModalBottomSheet 本身无 fillMaxHeight 参数，用内部 Column 撑满
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            // 标题栏
            Text(
                text = stringResource(R.string.file_diff_sheet_title, fileDiffs.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )

            // Tab 切换：文件修改 / 日志
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = stringResource(R.string.file_diff_tab_changes, fileDiffs.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = stringResource(R.string.file_diff_tab_logs, logs.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }

            HorizontalDivider()

            when (selectedTab) {
                0 -> FileChangesTab(
                    fileDiffs = fileDiffs,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { selectedIndex = it }
                )
                else -> ToolLogsTab(logs = logs)
            }
        }
    }
}

/** 文件修改 Tab：文件切换标签 + 选中文件的 diff / 删除提示。 */
@Composable
private fun FileChangesTab(
    fileDiffs: List<EditDiff>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit
) {
    if (fileDiffs.isEmpty()) {
        EmptyHint(text = stringResource(R.string.file_diff_empty))
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // 文件切换标签：横向滚动
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            fileDiffs.forEachIndexed { index, diff ->
                val typeColor = diff.type.changeColor()
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelectIndex(index) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = diff.path.substringAfterLast('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            // 类型标签：增/改/删
                            Text(
                                text = diff.type.labelRes().let { stringResource(it) },
                                style = MaterialTheme.typography.labelSmall,
                                color = typeColor,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (diff.added > 0 || diff.removed > 0) {
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = "+${diff.added} -${diff.removed}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (index == selectedIndex) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        HorizontalDivider()

        // Diff 内容区
        val current = fileDiffs[selectedIndex.coerceIn(0, fileDiffs.lastIndex)]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
            // 文件路径标题
            Text(
                text = current.path,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )

            when (current.type) {
                // 删除：无 diff，展示删除提示
                FileChangeType.DELETE -> {
                    Surface(
                        shape = RoundedCornerShape(Radius.md),
                        color = deleteColor.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, deleteColor.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.AlertCircle,
                                contentDescription = null,
                                tint = deleteColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(
                                    if (current.isWildcard) R.string.file_deleted_wildcard_hint
                                    else R.string.file_deleted_hint
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = deleteColor
                            )
                        }
                    }
                }
                // 新增/修改：逐段 diff
                else -> {
                    current.hunks.forEach { hunk ->
                        DiffView(diff = hunk.diff, startLine = hunk.startLine)
                        Spacer(Modifier.height(Spacing.sm))
                    }
                }
            }
        }
    }
}

/** 日志 Tab：按时间顺序展示任务内全部工具执行日志。 */
@Composable
private fun ToolLogsTab(logs: List<ToolLogEntry>) {
    if (logs.isEmpty()) {
        EmptyHint(text = stringResource(R.string.file_log_empty))
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        logs.forEach { log ->
            ToolLogRow(log = log)
        }
    }
}

/** 单条工具日志：工具名 + 参数摘要 + 结果（等宽字体），失败态红色强调。 */
@Composable
private fun ToolLogRow(log: ToolLogEntry) {
    val accent = if (log.isError) deleteColor else modifyColor
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = if (log.isError) deleteColor.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            // 头部：状态图标 + 工具名 + 参数摘要
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(
                    imageVector = if (log.isError) FeatherIcons.AlertCircle else FeatherIcons.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = log.toolName ?: stringResource(R.string.common_tool),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (log.isError) deleteColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!log.args.isNullOrBlank()) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = log.args,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            // 结果内容
            if (!log.result.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = log.result,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    ),
                    color = if (log.isError) deleteColor.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 空态提示。 */
@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = FeatherIcons.Tool,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun FileChangeType.changeColor(): Color = when (this) {
    FileChangeType.CREATE -> createColor
    FileChangeType.MODIFY -> modifyColor
    FileChangeType.DELETE -> deleteColor
}

private fun FileChangeType.labelRes(): Int = when (this) {
    FileChangeType.CREATE -> R.string.file_change_create
    FileChangeType.MODIFY -> R.string.file_change_modify
    FileChangeType.DELETE -> R.string.file_change_delete
}
