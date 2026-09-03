package com.core.deepcode.feature.agent.data.repository

import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.16 埋点 Repository（薄封装 DAO；无业务判断）。
 *
 *
 * 14 指标写入路径（eventKind 枚举严格 5 大类）：
 *   FUSE       → 熔断 OPEN / HALF_OPEN / kill-switch-1 激活
 *   CARD       → ConfirmationCard 弹出 / 用户 CONFIRM/REJECT/MODIFY/CANCEL
 *   CAPABILITY → CapabilityGuard auditBatch 通过 / 命中规则 / NEED_USER_CONFIRM
 *   OFFLINE    → 离线检测、PlanApproval 模型不可用
 *   SYNC       → Firestore 双向同步 push/pull/conflict/merge
 *
 * 不变性：
 *   BURIED-INV-1：绝不存明文 sessionId/userId → 写前 sha256(sessionId) 前 16 字符 hex
 *   BURIED-INV-2：insertEvent 只追加不更新（只读审计，避免历史被重写）
 */
@Singleton
class ZthTelemetryRepository @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {

    // ── 写入：14 指标路径（Phase 5 Facade 逐个调）────────────────────────

    suspend fun recordFuseTripped(sessionId: String?, tier: Int, subClass: String, failureCount: Long) {
        insertSha("FUSE", "TRIPPED_$subClass", tier, sessionId, metricA = failureCount)
    }

    suspend fun recordFuseCooledToHalfOpen(sessionId: String?, tier: Int, waitMin: Long) {
        insertSha("FUSE", "COOLED_HALF_OPEN", tier, sessionId, metricA = waitMin)
    }

    suspend fun recordFuseKillSwitch1(scopeLabel: String) {
        insertSha("FUSE", "KILL_SWITCH_1", 3, null, flagA = true, metricB = 1L,
            eventSubKindExtra = scopeLabel)
    }

    suspend fun recordCardShown(sessionId: String?, tier: Int, cardTemplateId: String) {
        insertSha("CARD", "SHOWN_$cardTemplateId", tier, sessionId)
    }

    suspend fun recordCardDecision(
        sessionId: String?, tier: Int, cardTemplateId: String,
        choice: String, swipeVerified: Boolean, latencyMs: Long
    ) {
        insertSha("CARD", "DECISION_${cardTemplateId}_$choice", tier, sessionId,
            latencyMs = latencyMs, flagA = swipeVerified)
    }

    suspend fun recordCapabilityAudit(
        sessionId: String?, tier: Int, subKind: String,
        batchSize: Long, hitCount: Long, latencyMs: Long
    ) {
        insertSha("CAPABILITY", subKind, tier, sessionId,
            metricA = batchSize, metricB = hitCount, latencyMs = latencyMs)
    }

    suspend fun recordOfflineDetected(sessionId: String?, tier: Int, executionMode: String) {
        insertSha("OFFLINE", "MODE_$executionMode", tier.coerceAtLeast(1), sessionId, flagA = true)
    }

    suspend fun recordPlanApprovalModelUnavailable(sessionId: String?, tier: Int) {
        insertSha("OFFLINE", "PLAN_APPROVAL_MODEL_DOWN", tier.coerceAtLeast(2), sessionId)
    }

    suspend fun recordSyncPush(pushed: Int, latencyMs: Long) {
        insertSha("SYNC", "PUSH", 1, null, metricA = pushed.toLong(), latencyMs = latencyMs)
    }

    suspend fun recordSyncPull(pulled: Int, latencyMs: Long) {
        insertSha("SYNC", "PULL", 1, null, metricA = pulled.toLong(), latencyMs = latencyMs)
    }

    suspend fun recordSyncConflict(kind: String, merged: Boolean) {
        insertSha("SYNC", "CONFLICT_$kind", 2, null, flagA = merged, metricA = 1L)
    }

    // ── 读取：4 张 Canvas 图表（设置页 ZTH 卡片 自绘柱状/折线/饼图） ────────

    fun observeAll(): Flow<List<ZthTelemetryEventEntity>> =
        v2Agent.observeAllTelemetry().map { list -> list.map { it.toEntity() } }

    suspend fun getRange(fromMs: Long, toMs: Long): List<ZthTelemetryEventEntity> =
        v2Agent.listTelemetryRange(fromMs, toMs).map { it.toEntity() }

    suspend fun countAll(): Long =
        v2Agent.countAllTelemetry()

    /** 后台 job：清理 > 90d 的遥测事件（避免 DB 无限膨胀）。 */
    suspend fun deleteOlderThan(days: Int = 90) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        v2Agent.deleteTelemetryOlderThan(cutoff)
    }

    // ── Phase 4.2 Firestore：Entity ↔ Dto 映射入口（SyncManager 调） ────────

    fun toFirestoreDto(e: ZthTelemetryEventEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "eventKind" to e.eventKind, "eventSubKind" to e.eventSubKind,
        "severityTier" to e.severityTier, "sessionSha256Prefix" to e.sessionSha256Prefix,
        "latencyMs" to e.latencyMs, "flagA" to e.flagA, "flagB" to e.flagB,
        "metricA" to e.metricA, "metricB" to e.metricB, "createdAtMs" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
    )

    fun fromFirestoreDto(m: Map<String, Any?>): ZthTelemetryEventEntity = ZthTelemetryEventEntity(
        id = (m["id"] as? Number)?.toLong() ?: 0L,
        eventKind = m["eventKind"] as? String ?: "UNKNOWN",
        eventSubKind = m["eventSubKind"] as? String ?: "",
        severityTier = (m["severityTier"] as? Number)?.toInt() ?: 0,
        sessionSha256Prefix = m["sessionSha256Prefix"] as? String,
        latencyMs = (m["latencyMs"] as? Number)?.toLong(),
        flagA = m["flagA"] as? Boolean,
        flagB = m["flagB"] as? Boolean,
        metricA = (m["metricA"] as? Number)?.toLong(),
        metricB = (m["metricB"] as? Number)?.toLong(),
        createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    // ── 内部工具 ─────────────────────────────────────────────────────────

    private suspend fun insertSha(
        kind: String, subKind: String, tier: Int, sessionId: String?,
        latencyMs: Long? = null, flagA: Boolean? = null, flagB: Boolean? = null,
        metricA: Long? = null, metricB: Long? = null,
        eventSubKindExtra: String? = null
    ) {
        val sha = sessionId?.let { sha256Prefix16(it) }
        val sk = if (eventSubKindExtra == null) subKind else "${subKind}_$eventSubKindExtra"
        v2Agent.insertTelemetryEvent(
            eventKind = kind, eventSubKind = sk, severityTier = tier.toLong(),
            sessionSha256Prefix = sha, latencyMs = latencyMs,
            flagA = flagA?.let { if (it) 1L else 0L },
            flagB = flagB?.let { if (it) 1L else 0L },
            metricA = metricA, metricB = metricB,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private fun sha256Prefix16(plain: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(plain.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun com.core.deepcode.datalayer.sqldelight.agent.Zth_telemetry_events.toEntity() = ZthTelemetryEventEntity(
        id = id,
        eventKind = event_kind,
        eventSubKind = event_sub_kind,
        severityTier = severity_tier.toInt(),
        sessionSha256Prefix = session_sha256_prefix,
        latencyMs = latency_ms,
        flagA = flag_a?.let { it == 1L },
        flagB = flag_b?.let { it == 1L },
        metricA = metric_a,
        metricB = metric_b,
        createdAtMs = created_at_ms,
    )
}