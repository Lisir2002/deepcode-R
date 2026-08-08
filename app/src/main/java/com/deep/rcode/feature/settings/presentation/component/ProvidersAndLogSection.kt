package com.deep.rcode.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.util.LogEntry
import com.deep.rcode.core.util.LogLevel
import com.deep.rcode.feature.settings.presentation.LogLevelHint
import com.deep.rcode.feature.settings.presentation.LogViewerUiState
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.RefreshCw
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R

/** 提供商二级页：列表 + 空态提示。新增/编辑由顶栏「+」与点击触发 [ProviderEditorScreen]。 */
@Composable
internal fun ProvidersSection(
    providers: List<AIProviderConfig>,
    onEdit: (AIProviderConfig) -> Unit
) {
    if (providers.isEmpty()) {
        EmptyHint(stringResource(R.string.providers_empty))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        items(providers) { provider ->
            ProviderItem(
                provider = provider,
                onEdit = { onEdit(provider) }
            )
        }
    }
}

/**
 * 系统日志二级页：融合写入等级 + 三维过滤（搜索/等级/分类）+ LazyColumn 彩色条目。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SystemLogsSection(
    currentLogLevel: LogLevel,
    onSelectLogLevel: (LogLevel) -> Unit,
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onSearch: (String) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onClearLevelFilter: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoryFilter: () -> Unit,
    onSyncDisplayFilter: () -> Unit,
    onConsumeHint: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // —— 记录等级（持久写入阈值）
        RecordLevelCard(current = currentLogLevel, onSelect = onSelectLogLevel)

        // —— 联动提示条：等级调严/调松
        state.levelHint?.let { hint ->
            LevelHintBanner(
                hint = hint,
                onSyncClick = {
                    onSyncDisplayFilter()
                    onConsumeHint()
                },
                onDismiss = onConsumeHint
            )
        }

        // —— 显示过滤：搜索 + 等级 + 分类
        DisplayFilterCard(
            state = state,
            onSearch = onSearch,
            onToggleLevel = onToggleLevel,
            onClearLevelFilter = onClearLevelFilter,
            onToggleCategory = onToggleCategory,
            onClearCategoryFilter = onClearCategoryFilter
        )

        // —— 文件选择 + 统计行
        FileAndSummaryCard(
            state = state,
            onSelectFile = onSelectFile
        )

        // —— 列表区：加载中 / 错误 / 空 / 无匹配 / 彩色条目
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(Radius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            when {
                state.loading -> LoadingBody()
                state.error != null -> ErrorBody(state.error)
                state.entries.isEmpty() -> NoEntriesHint(onRefresh)
                state.filteredEntries.isEmpty() -> NoMatchHint(onClearLevelFilter, onClearCategoryFilter)
                else -> LogEntryList(entries = state.filteredEntries)
            }
        }
    }
}

// =========================================
// 子组件 1：记录等级卡片
// =========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordLevelCard(
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
                text = stringResource(R.string.log_record_level) + "  ·  当前：${current.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_record_level_desc) +
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
                    val count = 0 // 记录等级不需要条数徽标
                    FilterChip(
                        selected = level == current,
                        onClick = { onSelect(level) },
                        label = { Text(level.name) },
                        leadingIcon = if (level != LogLevel.NONE) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(level.color(), CircleShape)
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}

// =========================================
// 子组件 2：联动提示条
// =========================================
@Composable
private fun LevelHintBanner(
    hint: LogLevelHint,
    onSyncClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val (bgColor, message) = when (hint) {
        is LogLevelHint.Tightened ->
            MaterialTheme.colorScheme.warningContainer() to
                stringResource(R.string.log_level_tightened, hint.newLevel.name)
        is LogLevelHint.Loosened ->
            MaterialTheme.colorScheme.infoContainer() to
                stringResource(R.string.log_level_loosened, hint.newLevel.name)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = bgColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = onWarningOrInfo(bgColor),
                modifier = Modifier.weight(1f)
            )
            if (hint is LogLevelHint.Tightened) {
                TextButton(onClick = onSyncClick) {
                    Text(stringResource(R.string.log_sync_display_filter))
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_hide))
            }
        }
    }
}

// =========================================
// 子组件 3：显示过滤（搜索栏 + 等级 Chip + 分类 Chip）
// =========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DisplayFilterCard(
    state: LogViewerUiState,
    onSearch: (String) -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onClearLevelFilter: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onClearCategoryFilter: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.log_display_filter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_display_filter_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
            )

            // 搜索栏
            SearchBarRow(
                query = state.searchQuery,
                onSearch = onSearch
            )

            // 等级过滤
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.log_level_filter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(60.dp)
                )
                if (state.selectedLevels.isNotEmpty()) {
                    TextButton(onClick = onClearLevelFilter, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(
                            text = stringResource(R.string.log_clear),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                LogLevel.values().filter { it != LogLevel.NONE }.forEach { level ->
                    val selected = state.selectedLevels.contains(level)
                    val count = state.levelCounts[level] ?: 0
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleLevel(level) },
                        label = {
                            Text(
                                if (count > 0) "${level.name} ($count)" else level.name,
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(level.color(), CircleShape)
                            )
                        }
                    )
                }
            }

            // 分类过滤
            if (state.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_category_filter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(60.dp)
                    )
                    if (state.selectedCategories.isNotEmpty()) {
                        TextButton(
                            onClick = onClearCategoryFilter,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.log_clear),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    state.categories.forEach { cat ->
                        val count = state.entries.count { it.category == cat }
                        AssistChip(
                            onClick = { onToggleCategory(cat) },
                            label = {
                                Text(
                                    "${cat.displayCategory()} ($count)",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = if (state.selectedCategories.contains(cat)) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            } else AssistChipDefaults.assistChipColors()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onSearch: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier
            .fillMaxWidth(),
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onSearch("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.log_clear_search))
                }
            }
        },
        placeholder = {
            Text(
                text = stringResource(R.string.log_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(Radius.md),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = { keyboard?.hide() }
        ),
        colors = OutlinedTextFieldDefaults.colors()
    )
}

// =========================================
// 子组件 4：文件选择 + 统计摘要
// =========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileAndSummaryCard(
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.log_files),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = logViewerSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                            label = {
                                Text(
                                    fileName.removePrefix("log-").removeSuffix(".txt"),
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.log_no_file),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

private fun logViewerSummary(state: LogViewerUiState): String {
    return if (state.shownCount != state.totalCount) {
        "显示 ${state.shownCount}/${state.totalCount} 条"
    } else {
        "共 ${state.totalCount} 条"
    }
}

// =========================================
// 子组件 5：LazyColumn 日志条目列表 + 彩色单行
// =========================================
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

@Composable
private fun LogEntryList(entries: List<LogEntry>) {
    val listState = rememberLazyListState()
    // 有新条目自动滚到底
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            items = entries,
            key = { System.identityHashCode(it) }
        ) { entry ->
            LogEntryRow(entry = entry)
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val expanded = remember(entry) { mutableStateOf(false) }
    val time = remember(entry.timestamp) {
        LocalTime.ofInstant(entry.timestamp, ZoneId.systemDefault()).format(TIME_FORMAT)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.throwableStack != null) expanded.value = !expanded.value }
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 等级色块（宽 3dp）
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .padding(top = 5.dp, end = 6.dp)
                    .size(width = 3.dp, height = 12.dp)
                    .background(entry.level.color(), RoundedCornerShape(1.5.dp))
            )
            // 时间
            Text(
                text = time,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
            // 等级缩写 V/D/I/W/E
            Surface(
                color = entry.level.color().copy(alpha = 0.15f),
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = entry.level.shortName(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        letterSpacing = 0.3.sp
                    ),
                    color = entry.level.color(),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            // Tag
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (entry.category == entry.tag) {
                    // 非 MCP 前缀（分类来自 tag 本身）
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    // MCP 条目（分类来自 message 前缀 [serverName]）
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.padding(end = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 消息
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = entry.level.textColor(),
                maxLines = if (expanded.value) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // 堆栈（默认折叠）
        if (entry.throwableStack != null && expanded.value) {
            Text(
                text = entry.throwableStack,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 2.dp, bottom = 2.dp)
            )
        }
    }
}

// =========================================
// 子组件 6：空态 / 加载 / 错误
// =========================================
@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(Spacing.sm))
            Text(
                stringResource(R.string.log_reading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBody(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.log_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(Spacing.xs))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 无日志空态：图标 + 标题 + 操作建议。 */
@Composable
private fun NoEntriesHint(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = FeatherIcons.FileText,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = stringResource(R.string.log_no_entries),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_no_entries_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(Spacing.xs))
            OutlinedButton(onClick = onRefresh) {
                Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.settings_refresh_logs))
            }
        }
    }
}

/** 筛选无匹配空态：图标 + 标题 + 清除筛选操作按钮。 */
@Composable
private fun NoMatchHint(onClearLevelFilter: () -> Unit, onClearCategoryFilter: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = stringResource(R.string.log_no_match),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_no_match_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { onClearLevelFilter(); onClearCategoryFilter() }) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.log_clear_all_filters))
                }
            }
        }
    }
}

/** 居中空态提示。 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =========================================
// 子组件 7：ProviderItem + ProviderLogoIcon（不动）
// =========================================
@Composable
fun ProviderItem(
    provider: AIProviderConfig,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogoIcon(
                provider = provider,
                size = 28.dp,
                modifier = Modifier.padding(end = Spacing.md)
            )
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(Radius.sm),
                color = if (provider.isEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = stringResource(if (provider.isEnabled) R.string.common_enabled else R.string.common_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (provider.isEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            IconButton(onClick = onEdit) {
                Icon(
                    FeatherIcons.Edit2,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// =========================================
// 辅助：LogLevel 颜色 / 缩写 / 图标；分类展示
// =========================================
private fun LogLevel.color(): Color = when (this) {
    LogLevel.VERBOSE -> Color(0xFF9E9E9E)
    LogLevel.DEBUG -> Color(0xFF1976D2)
    LogLevel.INFO -> Color(0xFF2E7D32)
    LogLevel.WARN -> Color(0xFFEF6C00)
    LogLevel.ERROR -> Color(0xFFD32F2F)
    LogLevel.NONE -> Color(0xFF616161)
}

@Composable
private fun LogLevel.textColor(): Color = when (this) {
    LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.DEBUG -> MaterialTheme.colorScheme.primary
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.WARN -> Color(0xFFEF6C00)
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun LogLevel.shortName(): String = when (this) {
    LogLevel.VERBOSE -> "V"
    LogLevel.DEBUG -> "D"
    LogLevel.INFO -> "I"
    LogLevel.WARN -> "W"
    LogLevel.ERROR -> "E"
    LogLevel.NONE -> "-"
}

@Composable
private fun String.displayCategory(): String = this

/** Material 3 主题扩展：warning / info 容器色。 */
@Composable
private fun ColorScheme.warningContainer(): Color =
    composite(surfaceVariant, 0xFF9E43) // orange

@Composable
private fun ColorScheme.infoContainer(): Color =
    composite(surfaceVariant, 0x1E88E5) // blue

/**
 * 用 Porter-Duff SRC_OVER 在 [background] 上叠一层 [foregroundRgb]（带固定 200/255 alpha），
 * 返回 compose Color。不依赖 ColorUtils。
 */
private fun composite(background: Color, foregroundRgb: Int): Color {
    val fa = 200f / 255f
    val fr = ((foregroundRgb shr 16) and 0xFF) / 255f
    val fg = ((foregroundRgb shr 8) and 0xFF) / 255f
    val fb = (foregroundRgb and 0xFF) / 255f
    val br = background.red
    val bg = background.green
    val bb = background.blue
    val a = fa + background.alpha * (1f - fa)
    val r = (fr * fa + br * background.alpha * (1f - fa)) / a
    val g = (fg * fa + bg * background.alpha * (1f - fa)) / a
    val b = (fb * fa + bb * background.alpha * (1f - fa)) / a
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
}

/**
 * 根据背景色的亮度返回前景文字颜色，保证对比度。
 */
private fun onWarningOrInfo(background: Color): Color {
    val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
    return if (luminance > 0.6f) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
}
