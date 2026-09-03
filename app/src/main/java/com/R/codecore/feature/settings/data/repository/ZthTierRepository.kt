package com.R.codecore.feature.settings.data.repository

import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.store.KVStore
import com.R.codecore.feature.agent.domain.zth.ZthPerformanceClass
import com.R.codecore.feature.agent.domain.zth.ZthPresetTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZTH 档位 + 性能等级 + Swipe 开关（KVStore）。
 * 三个 Key：
 *   zth_tier         → ZthPresetTier.name（DISABLED / MINIMAL / BALANCED / STRICT）
 *   zth_perf_class   → ZthPerformanceClass.name（LOW_END_SKIP_LLM / MID_RANGE / HIGH_END）
 *   zth_swipe_on     → BOOLEAN 存 STRING "true"/"false"
 */
@Singleton
class ZthTierRepository @Inject constructor(
    private val kv: KVStore
) {
    private companion object {
        const val NS = "settings"
        const val KEY_TIER = "zth_tier"
        const val KEY_PERF = "zth_perf_class"
        const val KEY_SWIPE = "zth_swipe_on"
        const val SWIPE_TRUE = "true"
        const val SWIPE_FALSE = "false"
        val TAG = "ZthTierRepo"
    }

    /** 默认档位 = BALANCED。 */
    val tierFlow: Flow<ZthPresetTier> = kv.observeString(NS, KEY_TIER).map { stored ->
        stored?.let { runCatching { ZthPresetTier.valueOf(it) }.getOrNull() }
            ?: ZthPresetTier.BALANCED
    }

    val perfClassFlow: Flow<ZthPerformanceClass> = kv.observeString(NS, KEY_PERF).map { stored ->
        stored?.let { runCatching { ZthPerformanceClass.valueOf(it) }.getOrNull() }
            ?: ZthPerformanceClass.HIGH_END
    }

    val swipeEnabledFlow: Flow<Boolean> = kv.observeString(NS, KEY_SWIPE).map { stored ->
        when (stored) {
            SWIPE_TRUE -> true
            SWIPE_FALSE -> false
            else -> true
        }
    }

    suspend fun setTier(tier: ZthPresetTier) {
        kv.putString(NS, KEY_TIER, tier.name)
        FileLogger.i(TAG, "ZTH 档位设置 → ${tier.name}")
    }

    suspend fun setPerformanceClass(cls: ZthPerformanceClass) {
        kv.putString(NS, KEY_PERF, cls.name)
        FileLogger.i(TAG, "ZTH 性能等级 → ${cls.name}")
    }

    suspend fun setSwipeEnabled(enabled: Boolean, currentTier: ZthPresetTier) {
        if (currentTier.tier >= 2 && !enabled) {
            FileLogger.w(TAG, "tier$currentTier ≥ 2：禁止关闭 SwipeToConfirm")
            return
        }
        kv.putString(NS, KEY_SWIPE, if (enabled) SWIPE_TRUE else SWIPE_FALSE)
        FileLogger.i(TAG, "ZTH Swipe 开关 → enabled=$enabled (tier=$currentTier)")
    }

    // ── 同步 getter ──

    suspend fun getCurrentTier(): ZthPresetTier = tierFlow.first()
    suspend fun getCurrentPerformanceClass(): ZthPerformanceClass = perfClassFlow.first()
    suspend fun getCurrentSwipeEnabled(): Boolean = swipeEnabledFlow.first()
}
