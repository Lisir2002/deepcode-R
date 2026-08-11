package com.deep.rcode.feature.agent.data.repository

import com.deep.rcode.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.deep.rcode.feature.agent.domain.zth.ZthCapabilityAuditResult
import com.deep.rcode.feature.agent.domain.zth.ZthPresetTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.9 CapabilityGuard + C.3.1 L0 压缩 审计 Repository。
 *
 * 两个独立子功能（不同表，不共享事务）：
 *   1) CapabilityGuard auditBatch 结果 → 转 Telemetry 事件（调用 ZthTelemetryRepository）
 *   2) L0SoftCompact 还原日志 → 读写 L0SoftCompactRestoreLogDao（崩溃恢复用）
 *
 * 不变性：
 *   CAP-INV-1：CapabilityGuard 的 NEED_LLM_FINAL_REVIEW 结果必须写遥测（tier≥2 强制）
 *   CAP-INV-2：L0 压缩 log 一旦插入，originalRowCount/tokensBefore 不可修改（只读审计）
 */
@Singleton
class ZthCapabilityAuditRepository @Inject constructor(
    private val l0Dao: L0SoftCompactRestoreLogDao,
    private val telemetry: ZthTelemetryRepository
) {

    /** preTool：把 ZthCapabilityGuard.auditBatch 结果写入遥测（14 指标之 CAPABILITY 类）。 */
    suspend fun writeAuditBatchTelemetry(
        sessionId: String?, tier: ZthPresetTier,
        results: List<ZthCapabilityAuditResult>, latencyMs: Long
    ) {
        if (results.isEmpty()) return
        val needConfirm = results.count {
            it.verdict.name.startsWith("NEED_USER") || it.verdict.name.startsWith("NEED_LLM")
        }
        val passed = results.size - needConfirm
        // 1) 总览事件
        telemetry.recordCapabilityAudit(
            sessionId = sessionId, tier = tier.tier,
            subKind = "BATCH_TOTAL",
            batchSize = results.size.toLong(), hitCount = needConfirm.toLong(),
            latencyMs = latencyMs
        )
        // 2) PASS vs NEED 分条（tier≥2 才写细分，减少写入）
        if (tier.tier >= 2 && passed > 0) {
            telemetry.recordCapabilityAudit(
                sessionId, tier.tier, "BATCH_PASS",
                passed.toLong(), 0L, 0L
            )
        }
        if (needConfirm > 0) {
            telemetry.recordCapabilityAudit(
                sessionId, tier.tier, "BATCH_NEED_CONFIRM",
                needConfirm.toLong(), 0L, 0L
            )
        }
        // 3) CAP-INV-1：NEED_LLM_FINAL_REVIEW / timedOut 必须单独打遥测
        results.filter { it.verdict.name == "NEED_LLM_FINAL_REVIEW" || it.timedOut }.forEach { r ->
            telemetry.recordCapabilityAudit(
                sessionId, tier.tier,
                if (r.timedOut) "ITEM_TIMEOUT" else "ITEM_NEED_LLM",
                1L, r.hitRuleIds.size.toLong(), 0L
            )
        }
    }

    // ── L0 软压缩还原日志（崩溃恢复 + LINK-INV 校验） ────────────────

    suspend fun insertL0Log(log: L0SoftCompactRestoreLogEntity) {
        l0Dao.upsert(log) // DAO 名是 upsert（OnConflict.REPLACE）
    }

    suspend fun markRestored(logId: String) {
        l0Dao.markRestored(logId)
    }

    /** C.4.3 崩溃恢复：列出会话下所有未过期 L0 压缩（用 getBySession 再 filter，DAO 未提供专用查询）。 */
    suspend fun listUnexpiredL0(sessionId: String, nowMs: Long = System.currentTimeMillis()):
            List<L0SoftCompactRestoreLogEntity> =
        l0Dao.getBySession(sessionId).filter {
            it.expireAtMs == -1L || it.expireAtMs > nowMs
        }

    /** 崩溃恢复专用：列出已过期但未 restoredFlag=1 的（DAO 已有此查询）。 */
    suspend fun listExpiredNotRestored(nowMs: Long = System.currentTimeMillis()):
            List<L0SoftCompactRestoreLogEntity> =
        l0Dao.getExpiredAndNotRestored(nowMs)


    // ── Phase 4.2 Firestore：L0 ↔ Dto 映射 ──────────────────────────

    fun l0ToDto(e: L0SoftCompactRestoreLogEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "sessionId" to e.sessionId,
        "firstMessageId" to e.firstMessageId, "lastMessageId" to e.lastMessageId,
        "originalRowCount" to e.originalRowCount,
        "tokensBefore" to e.tokensBefore, "tokensAfter" to e.tokensAfter,
        "expireAtMs" to e.expireAtMs, "restoredFlag" to e.restoredFlag,
        "createdAtMs" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
    )

    fun l0FromDto(m: Map<String, Any?>): L0SoftCompactRestoreLogEntity =
        L0SoftCompactRestoreLogEntity(
            id = m["id"] as? String ?: "",
            sessionId = m["sessionId"] as? String ?: "",
            firstMessageId = m["firstMessageId"] as? String ?: "",
            lastMessageId = m["lastMessageId"] as? String ?: "",
            originalRowCount = (m["originalRowCount"] as? Number)?.toInt() ?: 0,
            tokensBefore = (m["tokensBefore"] as? Number)?.toInt() ?: 0,
            tokensAfter = (m["tokensAfter"] as? Number)?.toInt() ?: 0,
            // 跨设备 s_compactSourceDigestCiphertext 不同步（本地解密才有用）
            s_compactSourceDigestCiphertext = "",
            expireAtMs = (m["expireAtMs"] as? Number)?.toLong() ?: -1L,
            restoredFlag = (m["restoredFlag"] as? Boolean) ?: false,
            createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )
}
