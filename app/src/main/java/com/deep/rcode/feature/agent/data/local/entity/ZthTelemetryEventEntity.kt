package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * C.4.16 埋点事件表（方案 B 自绘 Canvas，0 导出 0 网络 SDK）。
 * 所有「用户可识别信息」字段禁止存在（BURIED-INV-1：图表不展示明文）。
 * 如需关联 session → 存 sessionSha256（SHA-256 截断前 8 字节 → 展示时 hash 化，不可逆）。
 */
@Entity(
    tableName = "zth_telemetry_events",
    indices = [
        Index(value = ["eventKind", "eventSubKind"]),
        Index(value = ["createdAtMs"]),
        Index(value = ["severityTier"])
    ]
)
data class ZthTelemetryEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** C.4.16 5 大类：FUSE / CARD / CAPABILITY / OFFLINE / SYNC。 */
    val eventKind: String,
    /** 子类：FuseState.name / ConfirmationCard 模板 id / FailureSubClass.name 等。 */
    val eventSubKind: String,
    /** 0~3 档位。 */
    val severityTier: Int,
    /** SHA-256(sessionId) 前 16 字符 hex；不可逆（BURIED-INV-1 0 明文）。 */
    val sessionSha256Prefix: String? = null,
    /** 耗时 ms（用于 C.4.7 性能直方图）。 */
    val latencyMs: Long? = null,
    /** 布尔字段 1（通用，具体含义由 eventKind 约定，如卡片 swipeVerified）。 */
    val flagA: Boolean? = null,
    /** 布尔字段 2。 */
    val flagB: Boolean? = null,
    /** 数值字段 1（通用，如 fuse failureCount）。 */
    val metricA: Long? = null,
    /** 数值字段 2（通用，如 CapabilityGuard 批大小）。 */
    val metricB: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)
