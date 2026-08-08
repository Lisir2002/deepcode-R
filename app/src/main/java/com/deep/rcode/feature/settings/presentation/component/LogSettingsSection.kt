package com.deep.rcode.feature.settings.presentation.component

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.util.LogLevel
import com.deep.rcode.feature.settings.presentation.LogViewerUiState
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Filter
import compose.icons.feathericons.X
import com.deep.rcode.core.util.LogLineParser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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
    onSetSelectedDates: (Set<String>) -> Unit = {},
    onSetDateRangeMode: (Boolean) -> Unit = {},
    onSetDateRange: (String?, String?) -> Unit = { _, _ -> },
    onToggleLevel: (LogLevel) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onResetFilters: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.settings_log),
        stringResource(R.string.settings_log_viewer)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                androidx.compose.material3.Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        if (index == 1) onRefresh()
                    },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> LogLevelCard(current = currentLogLevel, onSelect = onSelectLogLevel)
            1 -> LogViewerContent(
                state = logViewerState,
                onSelectFile = onSelectFile,
                onToggleFilterPanel = onToggleFilterPanel,
                onSetSelectedDates = onSetSelectedDates,
                onSetDateRangeMode = onSetDateRangeMode,
                onSetDateRange = onSetDateRange,
                onToggleLevel = onToggleLevel,
                onToggleTag = onToggleTag,
                onResetFilters = onResetFilters
            )
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

/** 日志查看内容：文件选择 + 筛选面板 + 日志内容。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LogViewerContent(
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onToggleFilterPanel: () -> Unit,
    onSetSelectedDates: (Set<String>) -> Unit,
    onSetDateRangeMode: (Boolean) -> Unit,
    onSetDateRange: (String?, String?) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onToggleTag: (String) -> Unit,
    onResetFilters: () -> Unit
) {
    val context = LocalContext.current

    // ── 文件选择 + 筛选按钮行 ──
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

                // 筛选按钮（带活跃条件指示点）
                val hasActiveFilters = state.selectedLevels.isNotEmpty() ||
                    state.selectedTags.isNotEmpty() ||
                    state.selectedDates.isNotEmpty() ||
                    state.dateRangeStart != null

                IconButton(onClick = onToggleFilterPanel) {
                    BadgedIcon(
                        hasBadge = hasActiveFilters,
                        icon = {
                            Icon(
                                imageVector = FeatherIcons.Filter,
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

    // ── 筛选面板（内联展开/收起） ──
    AnimatedVisibility(
        visible = state.filterPanelExpanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        FilterPanel(
            state = state,
            onSetSelectedDates = onSetSelectedDates,
            onSetDateRangeMode = onSetDateRangeMode,
            onSetDateRange = onSetDateRange,
            onToggleLevel = onToggleLevel,
            onToggleTag = onToggleTag,
            onResetFilters = onResetFilters
        )
    }

    // ── 日志内容显示卡片 ──
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
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
    }
}

/** 筛选面板内部组件。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: LogViewerUiState,
    onSetSelectedDates: (Set<String>) -> Unit,
    onSetDateRangeMode: (Boolean) -> Unit,
    onSetDateRange: (String?, String?) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onToggleTag: (String) -> Unit,
    onResetFilters: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            // 标题行
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
                // 范围模式：起始/结束日期输入
                DateRangeInput(
                    start = state.dateRangeStart,
                    end = state.dateRangeEnd,
                    files = state.files,
                    onApply = { start, end -> onSetDateRange(start, end) }
                )
            } else {
                // 列表模式：日期 Chip 多选
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
                    FilterChip(
                        selected = level in state.selectedLevels,
                        onClick = { onToggleLevel(level) },
                        label = { Text(level.name) }
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
    val context = LocalContext.current

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
        // 仅显示日期列表供快速选择起止
        if (allDates.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                allDates.forEach { date ->
                    FilterChip(
                        selected = date == start || date == end,
                        onClick = {
                            // 先点选起始，再点选结束
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
    // 追加活跃筛选摘要
    val activeFilters = buildList {
        if (state.selectedLevels.isNotEmpty()) {
            add(state.selectedLevels.joinToString("/") { it.name })
        }
        if (state.selectedTags.isNotEmpty()) {
            val shown = state.selectedTags.take(2).joinToString(", ")
            if (state.selectedTags.size > 2) add("$shown+${state.selectedTags.size - 2}")
            else add(shown)
        }
    }
    val filterSummary = if (activeFilters.isNotEmpty()) {
        " · [${activeFilters.joinToString("; ")}]"
    } else ""
    return "$file · $scope · $count$filterSummary"
}