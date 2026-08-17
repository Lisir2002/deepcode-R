package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.R.codecore.core.util.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.logFilterDataStore by preferencesDataStore(name = "log_filter_prefs")

/**
 * 持久化日志查看页面的筛选条件偏好。
 *
 * 持久化策略：
 * - 持久化：selectedLevels（等级）、selectedTags（来源 Tag）、dateRangeMode（日期模式）
 * - 不持久化：selectedDates（日期随文件变化）、dateRangeStart/End、filterPanelExpanded（默认收起）
 */
@Singleton
class LogFilterSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val LEVELS_KEY = stringPreferencesKey("filter_selected_levels")
        val TAGS_KEY = stringPreferencesKey("filter_selected_tags")
        val DATE_RANGE_MODE_KEY = booleanPreferencesKey("filter_date_range_mode")
    }

    /** 持久化的选中等级（逗号分隔的枚举名）；空字符串表示全部。 */
    val selectedLevelsFlow: Flow<Set<LogLevel>> = context.logFilterDataStore.data.map { prefs ->
        val raw = prefs[LEVELS_KEY] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split(",").mapNotNull { runCatching { LogLevel.valueOf(it.trim()) }.getOrNull() }.toSet()
    }

    /** 持久化的选中 Tag（逗号分隔）；空字符串表示全部。 */
    val selectedTagsFlow: Flow<Set<String>> = context.logFilterDataStore.data.map { prefs ->
        val raw = prefs[TAGS_KEY] ?: ""
        if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 日期选择模式：false=列表点选，true=日历范围。 */
    val dateRangeModeFlow: Flow<Boolean> = context.logFilterDataStore.data.map { prefs ->
        prefs[DATE_RANGE_MODE_KEY] ?: false
    }

    suspend fun saveSelectedLevels(levels: Set<LogLevel>) {
        context.logFilterDataStore.edit {
            it[LEVELS_KEY] = if (levels.isEmpty()) "" else levels.joinToString(",") { l -> l.name }
        }
    }

    suspend fun saveSelectedTags(tags: Set<String>) {
        context.logFilterDataStore.edit {
            it[TAGS_KEY] = if (tags.isEmpty()) "" else tags.joinToString(",")
        }
    }

    suspend fun saveDateRangeMode(rangeMode: Boolean) {
        context.logFilterDataStore.edit {
            it[DATE_RANGE_MODE_KEY] = rangeMode
        }
    }

    /** 读取当前持久化的选中等级（同步，用于 ViewModel init）。 */
    suspend fun readSelectedLevels(): Set<LogLevel> = selectedLevelsFlow.first()

    /** 读取当前持久化的选中 Tag（同步，用于 ViewModel init）。 */
    suspend fun readSelectedTags(): Set<String> = selectedTagsFlow.first()

    /** 读取当前持久化的日期模式（同步，用于 ViewModel init）。 */
    suspend fun readDateRangeMode(): Boolean = dateRangeModeFlow.first()
}