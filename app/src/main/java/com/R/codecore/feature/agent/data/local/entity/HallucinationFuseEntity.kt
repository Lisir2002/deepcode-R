package com.R.codecore.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * ZTH HallucinationCircuitBreaker 持久化状态机。
 * RC68 SCHEMA 38：改为原生复合主键 (scope, scopeId)，之前用 id 做拼接字符串主键 + (scope, scopeId) 独立唯一索引，冗余。
 * Room 实体保留单独的 id 字段（为了兼容 Funnel2 LightweightSchemaRescue 的单主键反射解析分支 + 其他 @Query 用 id 定位的旧代码），
 * 但 DDL 层是复合主键（UNIQUE 约束 + @Entity primaryKeys=[...]），应用侧 INSERT 同 (scope, scopeId) 两次直接冲突（SQLite 报错 ON CONFLICT REPLACE）。
 */
@Entity(
    tableName = "zth_hallucination_fuses",
    primaryKeys = ["scope", "scopeId"],
    indices = [
        Index(value = ["state"]),
        Index(value = ["updatedAtMs"])
    ]
)
data class HallucinationFuseEntity(
    /** 兼容旧 id（UI/DAO 仍用它定位）。值保持 `composeGlobalId()` / `composeSessionId()` 的产物即可。 */
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
    /** Double kill-switch：killSwitch1 单向置位；true → 强制 OPEN，UI 弹 Red Banner。 */
    val killSwitch1Triggered: Boolean = false,
    /** kill-switch-2：DataStore 软标志镜像（用于 DB 级查询过滤）。 */
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
