package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * settings 模块统一 DataStore（数据层重构 T2：「旧版写法」settings 11 个碎片 DataStore →
 * 「新写法」每模块一个）。
 *
 * 11 个旧文件（theme_prefs / keepalive_prefs / container_prefs / log_prefs /
 * execution_mode_prefs / zth_tier_prefs / default_model_prefs / compaction_model_prefs /
 * compatibility_policy_prefs / log_filter_prefs / vision_model_prefs）全部收敛到本文件，
 * 所有 settings repository 共用同一个 delegate 实例（避免同文件多 delegate 的并发写风险）。
 * 旧文件值由 [SettingsDataStoreMigrator] 一次性搬迁后删除。
 *
 * 各 repository 的 PreferencesKey 名全局唯一（盘点确认无跨文件冲突），合并后语义不变。
 */
val Context.settingsDataStore by preferencesDataStore(name = "settings_prefs")
