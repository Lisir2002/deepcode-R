package com.core.deepcode.feature.agent.domain.container.progress

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.core.deepcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 并行预取并发槽数策略（Spec v1.0 5.2 节）。
 *
 * 三维评分：CPU 能力 × 网络能力 − 散热/功耗惩罚 → 映射到槽数 [2, 8]。
 * 所有 API 均无需动态权限；拿不到就用保守兜底值。
 */
@Singleton
class PrefetchConcurrencyPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "PrefetchConcurrency"

    data class Result(
        val slots: Int,
        val score: Float,
        val cpuCores: Int,
        val maxFreqGhz: Float,
        val networkBandwidthKbps: Int?,
        val networkIsWifi: Boolean,
        val networkIs5g: Boolean,
        val thermalPenalty: Float,
        val batteryPenalty: Float,
    )

    fun calculate(): Result {
        // ── 维度 1：CPU ──
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val maxFreqGhz = readMaxCpuFreqGhz()
        val cpuScore = when {
            cpuCores >= 8 && maxFreqGhz >= 2.8f -> 3.5f
            cpuCores >= 8 && maxFreqGhz >= 2.2f -> 3.0f
            cpuCores >= 4 && maxFreqGhz >= 1.8f -> 2.0f
            else -> 1.5f
        }

        // ── 维度 2：网络带宽 + 类型 ──
        val conn = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps: NetworkCapabilities? = runCatching {
            conn.getNetworkCapabilities(conn.activeNetwork)
        }.getOrNull()
        // 注意：NetworkCapabilities.downstreamBandwidthKbps 属性及 getLinkDownstreamBandwidthKbps() 在部分 API 级编译期不可见，
        // 统一改成反射读取，拿不到则为 null，保持类能跨 compileSdk 兼容。
        val bandwidthKbps = caps?.let { readDownstreamKbpsCompat(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val is5g =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                    (readDownstreamKbpsCompat(caps) ?: 0) >= 1_000_000 // 5G NR 粗判：下行 >= 1Gbps
            } else {
                false
            }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val netScore = when {
            isWifi && (bandwidthKbps ?: 0) >= 200_000 -> 3.5f
            isWifi && (bandwidthKbps ?: 0) >= 50_000 -> 2.5f
            is5g -> 2.5f
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1.5f
            else -> 1.0f
        }.let { if (!validated) it - 0.5f else it }

        // ── 维度 3：散热 + 电量功耗惩罚 ──
        val thermalPenalty = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val headroom =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.currentThermalStatus
                else 0
            when (headroom) {
                PowerManager.THERMAL_STATUS_MODERATE -> 0.5f
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_SHUTDOWN,
                -> 1.5f
                else -> 0f
            }
        }.getOrDefault(0f)

        val (batteryPct, isCharging) = readBatteryState()
        val batteryPenalty = when {
            batteryPct != null && batteryPct < 20 && !isCharging -> 0.8f
            else -> 0f
        }

        // ── 汇总 → clamp 槽数 ──
        val score = (cpuScore + netScore - thermalPenalty - batteryPenalty)
            .coerceAtLeast(0f)
        val slots = when {
            // score 范围：最低 ~1.0（4G + 低电 4 核），最高 7.0（Wi-Fi 6 旗舰）
            // [2, 8] clamp：槽数太小没收益，太大会把 TCP 握手打爆
            score <= 1.5f -> 2
            score >= 6.5f -> 8
            else -> score.roundToInt().coerceIn(2, 8)
        }

        FileLogger.i(
            TAG,
            buildString {
                append("calc slots=$slots score=%.2f".format(score))
                append(" (cpu=$cpuCores/%.1fGHz".format(maxFreqGhz))
                append(" net=wifi$isWifi/5g$is5g/bw=${bandwidthKbps}kbps")
                append(" thermal=-$thermalPenalty")
                append(" bat=${batteryPct}%/$isCharging -$batteryPenalty)")
            },
        )
        return Result(
            slots = slots,
            score = score,
            cpuCores = cpuCores,
            maxFreqGhz = maxFreqGhz,
            networkBandwidthKbps = bandwidthKbps,
            networkIsWifi = isWifi,
            networkIs5g = is5g,
            thermalPenalty = thermalPenalty,
            batteryPenalty = batteryPenalty,
        )
    }

    // ──────────────────────── 系统信息读取辅助（全 fail-safe，无权限无崩溃） ────────────────────────

    /** 所有核心最大频率的最大值。读不到就兜底 2.2 GHz（次旗舰档）。 */
    private fun readMaxCpuFreqGhz(): Float {
        var maxKhz = 0L
        for (cpuIdx in 0 until Runtime.getRuntime().availableProcessors()) {
            val f = File("/sys/devices/system/cpu/cpu$cpuIdx/cpufreq/cpuinfo_max_freq")
            val khz = runCatching { f.readText().trim().toLongOrNull() }.getOrNull()
            if (khz != null && khz > maxKhz) maxKhz = khz
        }
        return if (maxKhz > 0) maxKhz / 1_000_000f else 2.2f
    }

    /** 返回 (剩余电量 0..100, 是否正在充电)。拿不到返回 (null, false)。 */
    private fun readBatteryState(): Pair<Int?, Boolean> {
        val sticky: Intent? = runCatching {
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val pct = sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) 100 * level / scale else null
        }
        val charging = sticky?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } == true
        return pct to charging
    }

    /**
     * 兼容层：NetworkCapabilities 的 downstreamBandwidthKbps/getLinkDownstreamBandwidthKbps 在
     * 某些 compileSdk/设备上不可见或为 0；通过反射统一读取，失败返回 null 让上层走兜底。
     */
    private fun readDownstreamKbpsCompat(caps: NetworkCapabilities): Int? = runCatching {
        // 优先：方法 getLinkDownstreamBandwidthKbps()
        val m = caps.javaClass.methods.firstOrNull {
            it.name == "getLinkDownstreamBandwidthKbps" && it.parameterTypes.isEmpty()
        }
        val v1 = m?.invoke(caps) as? Int
        if (v1 != null && v1 > 0) return@runCatching v1
        // 兜底：Kotlin 属性 downstreamBandwidthKbps / 字段 mDownstreamBandwidthKbps
        for (name in listOf("downstreamBandwidthKbps", "mDownstreamBandwidthKbps")) {
            val f = runCatching { caps.javaClass.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                val n = (f.get(caps) as? Number)?.toInt()
                if (n != null && n > 0) return@runCatching n
            }
        }
        null
    }.getOrNull()
}
