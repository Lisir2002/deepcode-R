package com.R.codecore.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.util.LogLevel
import com.R.codecore.feature.settings.presentation.LogViewerUiState
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.R.codecore.core.util.LogLineParser
import com.R.codecore.core.util.ParsedLogLine
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 日志等级颜色映射 ──
private val LogLevelColor = mapOf(
    LogLevel.ERROR to Color(0xFFEF4444),
    LogLevel.WARN to Color(0xFFF97316),
    LogLevel.INFO to Color(0xFF3B82F6),
    LogLevel.DEBUG to Color(0xFF9CA3AF),
    LogLevel.VERBOSE to Color(0xFFD1D5DB)
)

/**
 * 日志二级页：顶部 Tab 切换「日志等级」和「日志查看」两个子页面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogsSection(
    currentLogLevel: LogLevel,
    onSelectLogLevel: (LogLevel) -> Unit,
    logViewerState: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onRefresh: () -> Unit,
    // 筛选回调
    onToggleFilterPanel: () -> Unit = {},
    onCloseFilterPanel: () -> Unit = {},
    onSetSelectedDates: (Set<String>) -> Unit = {},
    onSetDateRangeMode: (Boolean) -> Unit = {},
    onSetDateRange: (String?, String?) -> Unit = { _, _ -> },
    onToggleLevel: (LogLevel) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onResetFilters: () -> Unit = {},
    // 搜索
    onSearchQuery: (String) -> Unit = {},
    // 实时尾随
    onToggleLiveTail: () -> Unit = {},
    onDismissNewLogs: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.settings_log),
        stringResource(R.string.settings_log_viewer)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // 顶栏与 PrimaryTabRow 背景色同为 surface，视觉贴合为一体
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        if (index == 1) onRefresh()
                    },
                    text = { Text(title) }
                )
            }
        }
        // 内容区：水平两侧 padding(lg)，上方 padding(md) 与 TabRow 分隔
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.lg)
        ) {
            when (selectedTab) {
                0 -> LogLevelCard(current = currentLogLevel, onSelect = onSelectLogLevel)
                1 -> LogViewerContent(
                    state = logViewerState,
                    onSelectFile = onSelectFile,
                    onToggleFilterPanel = onToggleFilterPanel,
                    onCloseFilterPanel = onCloseFilterPanel,
                    onSetSelectedDates = onSetSelectedDates,
                    onSetDateRangeMode = onSetDateRangeMode,
                    onSetDateRange = onSetDateRange,
                    onToggleLevel = onToggleLevel,
                    onToggleTag = onToggleTag,
                    onResetFilters = onResetFilters,
                    onSearchQuery = onSearchQuery,
                    onToggleLiveTail = onToggleLiveTail,
                    onDismissNewLogs = onDismissNewLogs
                )
            }
        }
    }
}

/** 日志等级卡片。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogLevelCard(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_log_current, current.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_level_desc) +
                    stringResource(R.string.log_file_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                LogLevel.values().forEach { level ->
                    FilterChip(
                        selected = level == current,
                        onClick = { onSelect(level) },
                        label = { Text(level.name) }
                    )
                }
            }
        }
    }
}

/** 日志查看内容：文件选择 + 筛选面板 + 搜索 + 日志内容。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LogViewerContent(
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onToggleFilterPanel: () -> Unit,
    onCloseFilterPanel: () -> Unit,
    onSetSelectedDates: (Set<String>) -> Unit,
    onSetDateRangeMode: (Boolean) -> Unit,
    onSetDateRange: (String?, String?) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onToggleTag: (String) -> Unit,
    onResetFilters: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onToggleLiveTail: () -> Unit,
    onDismissNewLogs: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val exportedTip = stringResource(R.string.logs_export_toast)
    val exportNoneTip = stringResource(R.string.logs_export_none)

    // ── 文件选择 + 操作按钮行 ──
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.filterServerName?.let { stringResource(R.string.log_mcp_prefix, it) }
                            ?: stringResource(R.string.log_all),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = logViewerSummary(context, state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }

                // 导出日志到公共 Downloads（文件管理器可见；失败 Snackbar 提示）
                IconButton(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.R.codecore.core.util.FileLogger.exportLogsToDownloads(context)
                                }.getOrElse { emptyList() }
                            }
                            snackbarHostState.showSnackbar(
                                if (result.isEmpty()) {
                                    exportNoneTip
                                } else {
                                    exportedTip
                                }
                            )
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = stringResource(R.string.logs_export_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 实时尾随按钮
                IconButton(onClick = onToggleLiveTail) {
                    Icon(
                        imageVector = if (state.liveTailEnabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.liveTailEnabled) stringResource(R.string.ui______f5c9f705) else stringResource(R.string.ui______87c117ed),
                        tint = if (state.liveTailEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // 筛选按钮（带活跃条件指示点）
                val hasActiveFilters = state.selectedLevels.isNotEmpty() ||
                    state.selectedTags.isNotEmpty() ||
                    state.selectedDates.isNotEmpty() ||
                    state.dateRangeStart != null ||
                    state.searchQuery.isNotBlank()

                IconButton(onClick = onToggleFilterPanel) {
                    BadgedIcon(
                        hasBadge = hasActiveFilters,
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = stringResource(R.string.log_filter_title),
                                tint = if (state.filterPanelExpanded || hasActiveFilters) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    )
                }
            }

            // 文件选择 Chips
            if (state.files.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    state.files.forEach { fileName ->
                        FilterChip(
                            selected = fileName == state.selectedFileName,
                            onClick = { onSelectFile(fileName) },
                            label = { Text(fileName.removePrefix("log-").removeSuffix(".txt")) }
                        )
                    }
                }
            }
        }
    }

    // ── 筛选面板（可滚动，最大高度限制，不自动关闭） ──
    AnimatedVisibility(
        visible = state.filterPanelExpanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        FilterPanel(
            state = state,
            onClose = onCloseFilterPanel,
            onSetSelectedDates = onSetSelectedDates,
            onSetDateRangeMode = onSetDateRangeMode,
            onSetDateRange = onSetDateRange,
            onToggleLevel = onToggleLevel,
            onToggleTag = onToggleTag,
            onResetFilters = onResetFilters
        )
    }

    // ── 搜索栏 ──
    if (state.filterPanelExpanded) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.log_search_hint)) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQuery("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui____4403fca0), modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }

    // ── "新日志"浮动按钮 ──
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // 主内容区域
        LogContentCard(
            state = state
        )

        // 导出结果提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 新日志提示按钮
        if (state.hasNewLogs && !state.liveTailEnabled) {
            FilledTonalButton(
                onClick = onDismissNewLogs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.sm),
                shape = RoundedCornerShape(Radius.pill)
            ) {
                Text(
                    text = stringResource(R.string.log_new_logs),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 日志内容卡片（带等级着色 + 关键词高亮）。 */
@Composable
private fun LogContentCard(state: LogViewerUiState) {
    Card(
        modifier = Modifier
            .fillMaxSize(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        val verticalScroll = rememberScrollState()
        val text = when {
            state.loading -> stringResource(R.string.log_reading)
            state.error != null -> state.error
            state.content.isBlank() -> stringResource(R.string.log_no_match)
            else -> state.content
        }

        if (state.loading || state.error != null || state.content.isBlank()) {
            // 简单文本模式
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(Spacing.md),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (state.error == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        } else {
            // 着色 + 高亮模式
            val lines = state.content.split("\n")
            val query = state.searchQuery.trim()

            SelectionContainer {
                Text(
                    text = buildColorizedLogLines(lines, query),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .padding(Spacing.md),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

/** 构建着色 + 关键词高亮的日志文本。 */
private fun buildColorizedLogLines(lines: List<String>, query: String) = buildAnnotatedString {
    for ((index, line) in lines.withIndex()) {
        if (index > 0) append("\n")

        val parsed = LogLineParser.parse(line)
        if (parsed != null) {
            // 等级着色
            val levelColor = LogLevelColor[parsed.level] ?: Color(0xFFE0E0E0)
            val levelTag = parsed.raw.substringBefore("]") + "]"

            // 等级标签部分（含时间戳和等级）
            withStyle(style = SpanStyle(color = levelColor)) {
                appendHighlighted(levelTag, query)
            }
            append(" ")

            // 消息部分
            val message = parsed.raw.substringAfter("] ").substringAfter("] ")
            appendHighlighted(message, query)
        } else {
            // 附属行灰色
            withStyle(style = SpanStyle(color = Color(0xFF9CA3AF))) {
                appendHighlighted(line, query)
            }
        }
    }
}

/** 追加带关键词高亮的文本。 */
private fun AnnotatedString.Builder.appendHighlighted(text: String, query: String) {
    if (query.isEmpty()) {
        append(text)
        return
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var start = 0
    while (true) {
        val idx = lowerText.indexOf(lowerQuery, start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        if (idx > start) {
            append(text.substring(start, idx))
        }
        withStyle(style = SpanStyle(background = Color(0xFFFFF176), color = Color(0xFF1A1A1A))) {
            append(text.substring(idx, idx + query.length))
        }
        start = idx + query.length
    }
}

/** 筛选面板（可滚动 + 最大高度限制 + 关闭按钮）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: LogViewerUiState,
    onClose: () -> Unit,
    onSetSelectedDates: (Set<String>) -> Unit,
    onSetDateRangeMode: (Boolean) -> Unit,
    onSetDateRange: (String?, String?) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onToggleTag: (String) -> Unit,
    onResetFilters: () -> Unit
) {
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(scrollState)
                .padding(Spacing.md)
        ) {
            // 标题行 + 关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.log_filter_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onResetFilters) {
                    Text(stringResource(R.string.log_filter_reset))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            // ── 日期筛选 ──
            Text(
                text = stringResource(R.string.log_filter_date),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.dateRangeMode,
                    onClick = { onSetDateRangeMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.log_filter_date_list)) }
                SegmentedButton(
                    selected = state.dateRangeMode,
                    onClick = { onSetDateRangeMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.log_filter_date_range)) }
            }
            Spacer(Modifier.height(Spacing.xs))
            if (state.dateRangeMode) {
                DateRangeInput(
                    start = state.dateRangeStart,
                    end = state.dateRangeEnd,
                    files = state.files,
                    onApply = { start, end -> onSetDateRange(start, end) }
                )
            } else {
                val allDates = state.files.map { it.removePrefix("log-").removeSuffix(".txt") }
                if (allDates.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        allDates.forEach { date ->
                            FilterChip(
                                selected = date in state.selectedDates,
                                onClick = {
                                    val newDates = if (date in state.selectedDates) {
                                        state.selectedDates - date
                                    } else {
                                        state.selectedDates + date
                                    }
                                    onSetSelectedDates(newDates)
                                },
                                label = { Text(date) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.log_filter_no_dates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            // ── 等级筛选 ──
            Text(
                text = stringResource(R.string.log_filter_level),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                LogLevel.values().filter { it != LogLevel.NONE }.forEach { level ->
                    val chipColor = LogLevelColor[level]
                    FilterChip(
                        selected = level in state.selectedLevels,
                        onClick = { onToggleLevel(level) },
                        label = {
                            Text(
                                text = level.name,
                                color = if (level in state.selectedLevels && chipColor != null) {
                                    chipColor
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))

            // ── 来源 (Tag) 筛选 ──
            Text(
                text = stringResource(R.string.log_filter_tag),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            if (state.allAvailableTags.isNotEmpty()) {
                val visibleTags = state.allAvailableTags
                val maxVisible = 8
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    visibleTags.take(maxVisible).forEach { tag ->
                        FilterChip(
                            selected = tag in state.selectedTags,
                            onClick = { onToggleTag(tag) },
                            label = {
                                Text(
                                    text = tag,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                    if (visibleTags.size > maxVisible) {
                        Text(
                            text = stringResource(R.string.log_filter_more_tags, visibleTags.size - maxVisible),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm, top = Spacing.xs)
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.log_filter_no_tags),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 日期范围输入组件：两个日期按钮 + 应用按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeInput(
    start: String?,
    end: String?,
    files: List<String>,
    onApply: (String?, String?) -> Unit
) {
    val allDates = files.map { it.removePrefix("log-").removeSuffix(".txt") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.log_filter_date_from),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = start ?: stringResource(R.string.log_filter_date_select),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.log_filter_date_to),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = end ?: stringResource(R.string.log_filter_date_select),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        if (allDates.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                allDates.forEach { date ->
                    FilterChip(
                        selected = date == start || date == end,
                        onClick = {
                            if (start == null || (end == null && start != null)) {
                                if (start == null) onApply(date, null)
                                else if (date >= start) onApply(start, date)
                                else onApply(date, start)
                            } else {
                                onApply(null, null)
                            }
                        },
                        label = { Text(date) }
                    )
                }
            }
        }
    }
}

/** 带 Badge 的图标包装。 */
@Composable
private fun BadgedIcon(
    hasBadge: Boolean,
    icon: @Composable () -> Unit
) {
    if (hasBadge) {
        androidx.compose.material3.BadgedBox(
            badge = {
                Badge(modifier = Modifier.size(8.dp))
            }
        ) {
            icon()
        }
    } else {
        icon()
    }
}

private fun logViewerSummary(context: Context, state: LogViewerUiState): String {
        val file = state.selectedFileName?.let {
            it.removePrefix("log-").removeSuffix(".txt")
        } ?: context.getString(R.string.log_no_file)
        val scope = state.filterServerName?.let { context.getString(R.string.log_filter_prefix, it) }
            ?: context.getString(R.string.log_no_filter)
        val count = if (state.totalLines > state.shownLines) {
            context.getString(R.string.log_show_last_lines, state.shownLines, state.totalLines)
        } else {
            context.getString(R.string.log_show_lines, state.shownLines)
        }
        val activeFilters = buildList {
            if (state.selectedLevels.isNotEmpty()) {
                add(state.selectedLevels.joinToString("/") { it.name })
            }
            if (state.selectedTags.isNotEmpty()) {
                val shown = state.selectedTags.take(2).joinToString(", ")
                if (state.selectedTags.size > 2) add("$shown+${state.selectedTags.size - 2}")
                else add(shown)
            }
            if (state.searchQuery.isNotBlank()) {
                add("[S] ${state.searchQuery}")
            }
        }
        val filterSummary = if (activeFilters.isNotEmpty()) {
            " · [${activeFilters.joinToString("; ")}]"
        } else ""
        val liveTail = if (state.liveTailEnabled) " · LIVE" else ""
        return "$file · $scope · $count$filterSummary$liveTail"
    }