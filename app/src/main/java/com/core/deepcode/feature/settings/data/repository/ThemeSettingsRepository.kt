package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.R
import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppThemeMode(val labelRes: Int) {
    AUTO(R.string.theme_auto),
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light);

    companion object {
        fun fromPersisted(value: String?): AppThemeMode? = entries.firstOrNull { it.name == value }
    }
}

/** 持久化 App 外观主题。默认跟随系统，也兼容旧版深色开关。 */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val THEME_MODE_KEY = "theme_mode"
        const val DARK_THEME_KEY = "dark_theme_enabled"
    }

    val themeModeFlow: Flow<AppThemeMode> = kv.observeString(NS, THEME_MODE_KEY).map { stored ->
        stored?.let { AppThemeMode.fromPersisted(it) }
            ?: kv.getBool(NS, DARK_THEME_KEY)?.let { if (it) AppThemeMode.DARK else AppThemeMode.LIGHT }
            ?: AppThemeMode.AUTO
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        kv.putString(NS, THEME_MODE_KEY, mode.name)
    }

    /** 备份快照：返回当前持久化的主题模式名（未设置时为 null，导入时回退默认）。 */
    suspend fun snapshot(): String? = themeModeFlow.first().name

    /** 从备份还原主题模式；null 时清除键回退默认。 */
    suspend fun restore(value: String?) {
        if (value == null) kv.delete(NS, THEME_MODE_KEY) else kv.putString(NS, THEME_MODE_KEY, value)
    }
}
