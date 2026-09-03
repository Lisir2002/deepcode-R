package com.R.codecore.feature.settings.data.repository

import com.R.codecore.core.util.LogLevel
import com.R.codecore.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化日志查看页面的筛选条件偏好。
 *
 * 持久化策略：
 * - 持久化：selectedLevels（等级）、selectedTags（来源 Tag）、dateRangeMode（日期模式）
 * - 不持久化：selectedDates（日期随文件变化）、dateRangeStart/End、filterPanelExpanded（默认收起）
 */
@Singleton
class LogFilterSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val LEVELS_KEY = "filter_selected_levels"
        const val TAGS_KEY = "filter_selected_tags"
        const val DATE_RANGE_MODE_KEY = "filter_date_range_mode"
    }

    val selectedLevelsFlow: Flow<Set<LogLevel>> = kv.observeString(NS, LEVELS_KEY).map { raw ->
        if (raw.isNullOrBlank()) emptySet()
        else raw.split(",").mapNotNull { runCatching { LogLevel.valueOf(it.trim()) }.getOrNull() }.toSet()
    }

    val selectedTagsFlow: Flow<Set<String>> = kv.observeString(NS, TAGS_KEY).map { raw ->
        if (raw.isNullOrBlank()) emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    val dateRangeModeFlow: Flow<Boolean> = kv.observeBool(NS, DATE_RANGE_MODE_KEY).map { it ?: false }

    suspend fun saveSelectedLevels(levels: Set<LogLevel>) {
        kv.putString(NS, LEVELS_KEY, if (levels.isEmpty()) "" else levels.joinToString(",") { l -> l.name })
    }

    suspend fun saveSelectedTags(tags: Set<String>) {
        kv.putString(NS, TAGS_KEY, if (tags.isEmpty()) "" else tags.joinToString(","))
    }

    suspend fun saveDateRangeMode(rangeMode: Boolean) {
        kv.putBool(NS, DATE_RANGE_MODE_KEY, rangeMode)
    }

    suspend fun readSelectedLevels(): Set<LogLevel> = selectedLevelsFlow.first()
    suspend fun readSelectedTags(): Set<String> = selectedTagsFlow.first()
    suspend fun readDateRangeMode(): Boolean = dateRangeModeFlow.first()
}
