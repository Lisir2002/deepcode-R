package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ZTH HallucinationCircuitBreaker 持久化状态机（C.4.3/C.4.6）。
 * 两条记录规则：
 * 1) scope=GLOBAL scopeId=GLOBAL_SCOPE_ID → 全局熔断（Phase 3 C.4.6 AtomicReference 乐观锁读写）
 * 2) scope=SESSION scopeId=sessionId → 会话级熔断（session 级独立 fuse，C.4.6 PHASE-INV-1 隔离）
 */
@Entity(
    tableName = "zth_hallucination_fuses",
    indices = [
        Index(value = ["scope", "scopeId"], unique = true),
        Index(value = ["state"]),
        Index(value = ["updatedAtMs"])
    ]
)
data class HallucinationFuseEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    /** "GLOBAL" 或 "SESSION"。 */
    val scope: String,
    /** GLOBAL_SCOPE_ID 或真实 ChatSessionEntity.id。 */
    val scopeId: String,
    /** FuseState.name（CLOSED/HALF_OPEN/OPEN/TRANSITIONING）。 */
    val state: String,
    /** LINK-INV 四方联动单调版本号；@Transaction 修改 sentinel 时 compare-and-set。 */
    val linkageVersion: Long,
    /** OPEN → 累计失败计数（HalfOpen 探针失败时 +1）。 */
    val failureCount: Int = 0,
    /** OPEN → 进入 OPEN 的时间 ms；用于 T_cool 到期切 HALF_OPEN。 */
    val openSinceMs: Long = 0L,
    /** HALF_OPEN → 最近一次 probe 尝试时间 ms（冷却计算用）。 */
    val lastProbeAtMs: Long = 0L,
    /** C.4.2 Double kill-switch：killSwitch1 单向置位；true → 强制 OPEN，UI 弹 Red Banner。 */
    val killSwitch1Triggered: Boolean = false,
    /** C.4.2 kill-switch-2：DataStore 软标志镜像（用于 DB 级查询过滤）。 */
    val killSwitch2SoftDisabled: Boolean = false,
    /** 最近一次触发熔断的 FailureSubClass.name（审计横幅用）。 */
    val lastTripSubclass: String? = null,

    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val GLOBAL_SCOPE_ID = "__zth_global__"
        fun composeGlobalId(): String = "GLOBAL:$GLOBAL_SCOPE_ID"
        fun composeSessionId(sessionId: String): String = "SESSION:$sessionId"
    }
}
