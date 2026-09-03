package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.core.util.LogLevel
import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化「日志最低记录等级」。等级以枚举名（字符串）存取，默认 [LogLevel.VERBOSE]（开发期全量）。
 */
@Singleton
class LogSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val LEVEL_KEY = "log_min_level"
        val DEFAULT_LEVEL = LogLevel.VERBOSE
    }

    val levelFlow: Flow<LogLevel> = kv.observeString(NS, LEVEL_KEY).map { stored ->
        stored?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: DEFAULT_LEVEL
    }

    suspend fun setLevel(level: LogLevel) {
        kv.putString(NS, LEVEL_KEY, level.name)
    }

    suspend fun snapshot(): String = levelFlow.first().name

    suspend fun restore(value: String?) {
        val level = value?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: return
        setLevel(level)
    }
}
