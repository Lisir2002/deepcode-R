package com.deep.rcode.feature.terminal.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalDataStore by preferencesDataStore(name = "terminal_prefs")

/**
 * 终端体验偏好：字号、扩展键盘档、主题等。
 */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val FONT_SIZE_SP_KEY = intPreferencesKey("font_size_sp")
        val FULL_EXTRA_KEYS_KEY = booleanPreferencesKey("full_extra_keys")
        val NEW_OUTPUT_INDICATOR_KEY = booleanPreferencesKey("new_output_indicator")
        val CTRL_HINT_SHOWN_KEY = booleanPreferencesKey("ctrl_hint_shown")
        val LAST_CWD_KEY = stringPreferencesKey("last_cwd")
    }

    /** 字号档位（SP）；默认 12sp。 */
    val fontSizeFlow: Flow<Int> = context.terminalDataStore.data.map {
        it[FONT_SIZE_SP_KEY] ?: 12
    }

    /** true = 完整扩展键盘档，false = 简洁档。 */
    val fullExtraKeysFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[FULL_EXTRA_KEYS_KEY] ?: false
    }

    /** 后台标签新输出红点提示，默认开启。 */
    val newOutputIndicatorFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[NEW_OUTPUT_INDICATOR_KEY] ?: true
    }

    /** Ctrl 首次用法提示是否已展示。 */
    val ctrlHintShownFlow: Flow<Boolean> = context.terminalDataStore.data.map {
        it[CTRL_HINT_SHOWN_KEY] ?: false
    }

    val lastCwdFlow: Flow<String> = context.terminalDataStore.data.map {
        it[LAST_CWD_KEY] ?: ""
    }

    suspend fun saveFontSize(sp: Int) {
        context.terminalDataStore.edit { it[FONT_SIZE_SP_KEY] = sp.coerceIn(8, 20) }
    }

    suspend fun saveFullExtraKeys(full: Boolean) {
        context.terminalDataStore.edit { it[FULL_EXTRA_KEYS_KEY] = full }
    }

    suspend fun saveNewOutputIndicator(enabled: Boolean) {
        context.terminalDataStore.edit { it[NEW_OUTPUT_INDICATOR_KEY] = enabled }
    }

    suspend fun markCtrlHintShown() {
        context.terminalDataStore.edit { it[CTRL_HINT_SHOWN_KEY] = true }
    }

    suspend fun saveLastCwd(cwd: String) {
        context.terminalDataStore.edit { it[LAST_CWD_KEY] = cwd }
    }

    suspend fun readFontSize(): Int = fontSizeFlow.first()
    suspend fun readFullExtraKeys(): Boolean = fullExtraKeysFlow.first()
    suspend fun readNewOutputIndicator(): Boolean = newOutputIndicatorFlow.first()
    suspend fun readCtrlHintShown(): Boolean = ctrlHintShownFlow.first()
}

/** 字号可选档位：用户长按菜单/双指缩放后会卡在一个档位上，避免任意数导致列数抖动。 */
object TerminalFontSizes {
    val STEPS = listOf(8, 10, 11, 12, 13, 14, 15, 16, 18, 20)
    val DEFAULT = 12
}
