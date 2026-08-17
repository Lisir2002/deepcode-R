package com.R.codecore.feature.agent.data.repository

import com.R.codecore.core.security.ZthSensitiveColumnCrypto
import com.R.codecore.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.R.codecore.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.5 PlanApproval + C.4.11 LINK-INV 删除审计 Repository。
 *
 * 两个独立子功能：
 *   1) HardConstraintDeleteAuditDao：写「AI 删除了用户保留行/sentinel/checkpoint」审计
 *      （ZthDbAutoReconciler C.4.11 用本表做 LINK-INV 迁移回滚扫描）
 *   2) PlanApproval 本身的 choice 写入由 [PlanApprovalManager] 直接操作，
 *      这里只提供：结果写 Telemetry + Firestore HardConstraintDelete Dto 映射
 *
 * 不变性：
 *   PA-INV-1：写 HardConstraintDeleteAuditEntity 前必须加密 s_affectedKeysCiphertext（不能存明文 keys）
 *   PA-INV-2：rollbackApplied 单向置位（0→1→0 不允许；回滚完成后不能「撤销回滚」）
 */
@Singleton
class ZthPlanApprovalRepository @Inject constructor(
    private val deleteAuditDao: HardConstraintDeleteAuditDao,
    private val crypto: ZthSensitiveColumnCrypto,
    private val telemetry: ZthTelemetryRepository
) {

    /** Phase 5 onFailure：AI 删除了 sentinel/checkpoint/保留行 → 写审计表（加密 keys JSON）。 */
    suspend fun recordHardConstraintDelete(
        sessionId: String, affectedTableName: String,
        affectedKeysJsonPlaintext: String, triggerSubClass: String
    ): String {
        crypto.assertSensitiveColumnName("s_affectedKeysCiphertext")
        val cipher = crypto.encrypt(affectedKeysJsonPlaintext)
        val id = "HCD:${UUID.randomUUID()}"
        deleteAuditDao.upsert(
            HardConstraintDeleteAuditEntity(
                id = id, sessionId = sessionId,
                affectedTableName = affectedTableName,
                s_affectedKeysCiphertext = cipher,
                triggerSubClass = triggerSubClass
            )
        )
        telemetry.recordCapabilityAudit(
            sessionId, tier = 3, subKind = "HARD_CONSTRAINT_DELETE",
            batchSize = 1L, hitCount = 1L, latencyMs = 0L
        )
        return id
    }

    /** C.4.11 DB Migration 兜底：扫所有 rollbackApplied=0 的删除审计 → 调用者做回滚。 */
    suspend fun listPendingRollbacks(): List<HardConstraintDeleteAuditEntity> =
        deleteAuditDao.getPendingRollbacks()

    /** 回滚成功后：标记 rollbackApplied=1（PA-INV-2 单向）。 */
    suspend fun markRolledBack(auditId: String) {
        deleteAuditDao.markRolledBack(auditId)
    }

    // ── Phase 4.2 Firestore：HardConstraintDelete ↔ Dto 映射 ──────────
    // 说明：跨设备同步不存 s_affectedKeysCiphertext（只有本地 Keystore 能解开；同步只用于「统计与通知」）。

    fun deleteAuditToDto(e: HardConstraintDeleteAuditEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "sessionId" to e.sessionId,
        "affectedTableName" to e.affectedTableName,
        "triggerSubClass" to e.triggerSubClass,
        "rollbackApplied" to e.rollbackApplied,
        "createdAtMs" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
        // 注意：s_affectedKeysCiphertext 不跨设备同步（本地加密）
    )

    fun deleteAuditFromDto(m: Map<String, Any?>): HardConstraintDeleteAuditEntity =
        HardConstraintDeleteAuditEntity(
            id = m["id"] as? String ?: "",
            sessionId = m["sessionId"] as? String ?: "",
            affectedTableName = m["affectedTableName"] as? String ?: "",
            s_affectedKeysCiphertext = "", // 跨设备拉到后无明文意义（保留空）
            triggerSubClass = m["triggerSubClass"] as? String ?: "",
            rollbackApplied = (m["rollbackApplied"] as? Boolean) ?: false,
            createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )
}
