package com.R.codecore.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.zth.ZthPerformanceClass
import com.R.codecore.feature.agent.domain.zth.ZthPresetTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZTH 档位 + 性能等级 + Swipe 开关（DataStore）。
 *
 * 完全模仿 [ExecutionModeRepository] 风格，保证和现有 DataStore 代码架构一致（C.6.2 P14 纠正）。
 * 三个 Key：
 *   zth_tier         → ZthPresetTier.name（DISABLED / MINIMAL / BALANCED / STRICT）
 *   zth_perf_class   → ZthPerformanceClass.name（LOW_END_SKIP_LLM / MID_RANGE / HIGH_END）
 *   zth_swipe_on     → BOOLEAN 存 STRING "true"/"false"（C.4.8 方案 C 默认 true；档位 ≤1 允许关）
 */
@Singleton
class ZthTierRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val STORE = "zth_tier_prefs"
        private val Context.zthDataStore by preferencesDataStore(name = STORE)
        val KEY_TIER = stringPreferencesKey("zth_tier")
        val KEY_PERF = stringPreferencesKey("zth_perf_class")
        val KEY_SWIPE = stringPreferencesKey("zth_swipe_on")
        const val SWIPE_TRUE = "true"
        const val SWIPE_FALSE = "false"
        val TAG = "ZthTierRepo"
    }

    /** 默认档位 = BALANCED（C.4.2 推荐默认 2）。 */
    val tierFlow: Flow<ZthPresetTier> = context.zthDataStore.data
        .catch { e -> if (e is IOException) { FileLogger.w(TAG, "DataStore 读失败：${e.message}"); emit(emptyPreferences()) } else throw e }
        .map { prefs ->
            prefs[KEY_TIER]?.let { runCatching { ZthPresetTier.valueOf(it) }.getOrNull() }
                ?: ZthPresetTier.BALANCED
        }

    val perfClassFlow: Flow<ZthPerformanceClass> = context.zthDataStore.data
        .catch { e -> if (e is IOException) { FileLogger.w(TAG, "DataStore 读失败：${e.message}"); emit(emptyPreferences()) } else throw e }
        .map { prefs ->
            prefs[KEY_PERF]?.let { runCatching { ZthPerformanceClass.valueOf(it) }.getOrNull() }
                ?: ZthPerformanceClass.HIGH_END
        }

    val swipeEnabledFlow: Flow<Boolean> = context.zthDataStore.data
        .catch { e -> if (e is IOException) { FileLogger.w(TAG, "DataStore 读失败：${e.message}"); emit(emptyPreferences()) } else throw e }
        .map { prefs ->
            when (prefs[KEY_SWIPE]) {
                SWIPE_TRUE -> true
                SWIPE_FALSE -> false
                else -> true  // C.4.8 方案 C：默认开 Swipe（最严格）
            }
        }

    suspend fun setTier(tier: ZthPresetTier) {
        context.zthDataStore.edit { it[KEY_TIER] = tier.name }
        FileLogger.i(TAG, "ZTH 档位设置 → ${tier.name}")
    }

    suspend fun setPerformanceClass(cls: ZthPerformanceClass) {
        context.zthDataStore.edit { it[KEY_PERF] = cls.name }
        FileLogger.i(TAG, "ZTH 性能等级 → ${cls.name}")
    }

    suspend fun setSwipeEnabled(enabled: Boolean, currentTier: ZthPresetTier) {
        // C.4.8 方案 C：tier≥2（BALANCED/STRICT）禁止关 swipe（SEC-INV-1）
        if (currentTier.tier >= 2 && !enabled) {
            FileLogger.w(TAG, "tier$currentTier ≥ 2：禁止关闭 SwipeToConfirm（C.4.8 最严格）")
            return
        }
        context.zthDataStore.edit { it[KEY_SWIPE] = if (enabled) SWIPE_TRUE else SWIPE_FALSE }
        FileLogger.i(TAG, "ZTH Swipe 开关 → enabled=$enabled (tier=$currentTier)")
    }

    private fun emptyPreferences(): androidx.datastore.preferences.core.Preferences =
        androidx.datastore.preferences.core.emptyPreferences()

    // ── 同步 getter（Phase 5 Facade 调用：Flow 取 first()；避免 Facade 层 collect Flow）────

    /** C.4.2：取当前档位（BALANCED 默认）。供 ZthGuardAggregateFacade.prepareEnv 调用。 */
    suspend fun getCurrentTier(): ZthPresetTier = tierFlow.first()

    /** C.4.7：取当前性能等级（HIGH_END 默认）。 */
    suspend fun getCurrentPerformanceClass(): ZthPerformanceClass = perfClassFlow.first()

    /** C.4.8：取当前 Swipe 开关（tier≥2 永远 true；C 默认 true）。 */
    suspend fun getCurrentSwipeEnabled(): Boolean = swipeEnabledFlow.first()
}
