package com.R.codecore.feature.agent.data.repository

import com.R.codecore.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.R.codecore.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.R.codecore.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.R.codecore.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.1/C.4.3 ConfirmationCard Repository（主表 sentinel + 从表 rejection audit）。
 *
 * LINK-INV 4 写事务不在这里（在 ZthConfirmationCardManager 内显式 4 步 + CAS）。
 * 这里只提供：
 *   - Phase 5 prePlan 查「此 chainId 是否已被用户决策过」（幂等 ConfirmationCard 不重复弹）
 *   - UI sentinel 时间线 observeBySession
 *   - C.4.3 崩溃恢复 listUnexpiredBySession
 *   - C.4.2 一键回滚 markAllRollbackBySession
 *   - Phase 4.2 Firestore Dto 映射
 *
 * 不变性：
 *   CARD-REPO-INV-1：所有 s_* 加密列绝不在 Repo 层解密（解密仅在需要展示时由 UI VM 完成）
 *   CARD-REPO-INV-2：跨设备同步不发送 s_* 密文（每台设备 Keystore 密钥不同；同步只存元数据 + choice）
 */
@Singleton
class ZthConfirmationCardRepository @Inject constructor(
    private val sentinelDao: UserConfirmedSentinelDao,
    private val rejectionAuditDao: SentinelPlanRejectionAuditDao,
    private val telemetry: ZthTelemetryRepository
) {

    /** Phase 5 Facade：弹卡前查询 chainId 是否已决策（已存在 → 直接复用 choice，不重复弹卡）。 */
    suspend fun getSentinelsByChain(chainId: String): List<UserConfirmedSentinelEntity> =
        sentinelDao.getByChain(chainId)

    /** UI 时间线：流式观察会话内所有 sentinel（新→旧）。 */
    fun observeSentinelsBySession(sessionId: String): Flow<List<UserConfirmedSentinelEntity>> =
        sentinelDao.observeBySession(sessionId)

    /** C.4.3 崩溃恢复：会话下所有未过期 sentinel（expireAtMs=-1 永不过期）。 */
    suspend fun listUnexpiredBySession(sessionId: String, nowMs: Long = System.currentTimeMillis()):
            List<UserConfirmedSentinelEntity> =
        sentinelDao.getUnexpiredBySession(sessionId, nowMs)

    /** C.4.2 Red Banner 一键回滚：标记会话内所有 sentinel rollbackFlag=true。 */
    suspend fun markAllRollbackBySession(sessionId: String) {
        sentinelDao.markAllRollbackBySession(sessionId)
    }

    /** 审计查询：某 sentinel 的拒绝/修改理由（外键）。 */
    suspend fun getRejectionAudit(sentinelId: String): SentinelPlanRejectionAuditEntity? =
        rejectionAuditDao.getBySentinel(sentinelId)

    /** Manager 写入成功后，打一条 CARD.DECISION 遥测（Phase 4.1 14 指标写入路径之一）。 */
    suspend fun recordDecisionTelemetry(
        sessionId: String?, tier: Int, cardTemplateId: String,
        choice: String, swipeVerified: Boolean, latencyMs: Long
    ) {
        telemetry.recordCardDecision(sessionId, tier, cardTemplateId, choice, swipeVerified, latencyMs)
    }

    // ── Phase 4.2 Firestore：Sentinel + RejectionAudit ↔ Dto 映射 ────────
    // CARD-REPO-INV-2：不跨设备同步 s_* 加密列（本地 Keystore-only）；同步只含元数据。

    fun sentinelToDto(e: UserConfirmedSentinelEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "sessionId" to e.sessionId,
        "linkageVersion" to e.linkageVersion, "chainId" to e.chainId,
        "chainIndex" to e.chainIndex, "cardTemplateId" to e.cardTemplateId,
        "triggerSubClass" to e.triggerSubClass, "userChoice" to e.userChoice,
        "swipeVerified" to e.swipeVerified, "expireAtMs" to e.expireAtMs,
        "rollbackFlag" to e.rollbackFlag, "createdAtMs" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
        // 注意：s_planPayloadCiphertext / s_userTextCiphertext / s_cardPayloadCiphertext /
        // s_modifiedPlanCiphertext 不同步（CARD-REPO-INV-2）
    )

    fun sentinelFromDto(m: Map<String, Any?>): UserConfirmedSentinelEntity =
        UserConfirmedSentinelEntity(
            id = m["id"] as? String ?: "",
            sessionId = m["sessionId"] as? String ?: "",
            linkageVersion = (m["linkageVersion"] as? Number)?.toLong() ?: 0L,
            chainId = m["chainId"] as? String ?: "",
            chainIndex = (m["chainIndex"] as? Number)?.toInt() ?: 1,
            cardTemplateId = m["cardTemplateId"] as? String ?: "",
            triggerSubClass = m["triggerSubClass"] as? String ?: "",
            s_planPayloadCiphertext = "", // 跨设备拉到后空值（不影响只读决策展示）
            s_cardPayloadCiphertext = "",
            userChoice = m["userChoice"] as? String ?: "CONFIRM",
            swipeVerified = (m["swipeVerified"] as? Boolean) ?: false,
            expireAtMs = (m["expireAtMs"] as? Number)?.toLong() ?: -1L,
            rollbackFlag = (m["rollbackFlag"] as? Boolean) ?: false,
            createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    fun rejectionAuditToDto(e: SentinelPlanRejectionAuditEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "sentinelId" to e.sentinelId,
        "rejectionType" to e.rejectionType, "createdAtMs" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
        // s_reasonCiphertext / s_rejectedPlanSnapshotCiphertext 不同步
    )

    fun rejectionAuditFromDto(m: Map<String, Any?>): SentinelPlanRejectionAuditEntity =
        SentinelPlanRejectionAuditEntity(
            id = m["id"] as? String ?: "",
            sentinelId = m["sentinelId"] as? String ?: "",
            rejectionType = m["rejectionType"] as? String ?: "REJECT",
            s_reasonCiphertext = null,
            s_rejectedPlanSnapshotCiphertext = "",
            createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    // ── Phase 4.2 Sync 辅助：批量全量拉（push 到 Firestore） ─────────

    suspend fun getAllSentinels(): List<UserConfirmedSentinelEntity> = sentinelDao.getAllOnce()
    suspend fun getAllRejectionAudits(): List<SentinelPlanRejectionAuditEntity> =
        rejectionAuditDao.getAllOnce()
}
