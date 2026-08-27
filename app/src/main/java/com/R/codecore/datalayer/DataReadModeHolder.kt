package com.R.codecore.datalayer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.R.codecore.feature.settings.data.repository.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据层读源开关（v2-full-takeover P1-2）。
 *
 * 过渡期唯一的「一键回退」闸门：
 *  - [DataReadMode.ROOM]：业务继续走旧 Room 域库（升级前默认，行为与现状完全一致）；
 *  - [DataReadMode.V2]：业务读源切到 V2 SQLDelight 域库（逐批切换时由各 Repository 按批次置位）。
 *
 * 持久化于 settings 统一 DataStore（key = `data_read_mode_v2`），默认 ROOM ——
 * 即使某批切换异常，把开关拨回 ROOM 即可整体回退，无需改代码。
 *
 * P3 剔除旧层后本开关与枚举将整体删除（DoD 判据 3：无开关残留）。
 */
enum class DataReadMode { ROOM, V2 }

@Singleton
class DataReadModeHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("data_read_mode_v2")

    /** 当前模式（默认 ROOM，读侧订阅用）。 */
    val mode: Flow<DataReadMode> = context.settingsDataStore.data.map { prefs ->
        if (prefs[key] == true) DataReadMode.V2 else DataReadMode.ROOM
    }

    suspend fun setMode(mode: DataReadMode) {
        context.settingsDataStore.edit { it[key] = (mode == DataReadMode.V2) }
    }

    /** 同步快照（非 Flow 调用点用；首次读取会触发 DataStore 初始化）。 */
    suspend fun currentMode(): DataReadMode {
        val prefs = context.settingsDataStore.data.firstOrNull() ?: return DataReadMode.ROOM
        return if (prefs[key] == true) DataReadMode.V2 else DataReadMode.ROOM
    }
}
