package com.deep.rcode.feature.agent.data.remote.zth

import com.deep.rcode.core.security.ZthSensitiveColumnCrypto
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import com.deep.rcode.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entity ↔ FirestoreDto 双向映射器。
 *
 * Phase 1 约束：
 *  - 只做纯字段一一映射，不引入任何 Firestore SDK 依赖（无 @DocumentId / FirebaseFirestore）。
 *  - s_* 加密列因为 Entity 里本身就是加密后 blob（经 ZthSensitiveColumnCrypto 写入 Room），
 *    映射时「原样复制」，Phase 4 Syncer 再用 shared_sync_key 对 Dto 密文**二次加解密**。
 *    所以本 Mapper 不需要接触 ZthSensitiveColumnCrypto（注入但 Phase 1 先不调，Phase 4 校验加调用）。
 *
 * 对应 C.4.18：两次加密（手机本地 = Keystore DEK + 跨设备云同步 = shared_sync_key）
 */
@Singleton
class ZthEntityMapper @Inject constructor(
    @Suppress("unused") // Phase 4 Syncer 启用二次加密时使用；目前强制注入保证依赖图编译
    private val crypto: ZthSensitiveColumnCrypto
) {

    // ── Sentinel ───────────────────────────────────────────────────────────

    fun toDto(e: UserConfirmedSentinelEntity): ZthSentinelFirestoreDto = ZthSentinelFirestoreDto(
        id = e.id,
        sessionId = e.sessionId,
        linkageVersion = e.linkageVersion,
        chainId = e.chainId,
        chainIndex = e.chainIndex,
        cardTemplateId = e.cardTemplateId,
        triggerSubClass = e.triggerSubClass,
        planPayloadCiphertext = e.s_planPayloadCiphertext,
        userTextCiphertext = e.s_userTextCiphertext,
        cardPayloadCiphertext = e.s_cardPayloadCiphertext,
        userChoice = e.userChoice,
        swipeVerified = e.swipeVerified,
        modifiedPlanCiphertext = e.s_modifiedPlanCiphertext,
        expireAtMs = e.expireAtMs,
        rollbackFlag = e.rollbackFlag,
        createdAtMs = e.createdAtMs
    )

    fun toEntity(d: ZthSentinelFirestoreDto): UserConfirmedSentinelEntity = UserConfirmedSentinelEntity(
        id = d.id,
        sessionId = d.sessionId,
        linkageVersion = d.linkageVersion,
        chainId = d.chainId,
        chainIndex = d.chainIndex,
        cardTemplateId = d.cardTemplateId,
        triggerSubClass = d.triggerSubClass,
        s_planPayloadCiphertext = d.planPayloadCiphertext,
        s_userTextCiphertext = d.userTextCiphertext,
        s_cardPayloadCiphertext = d.cardPayloadCiphertext,
        userChoice = d.userChoice,
        swipeVerified = d.swipeVerified,
        s_modifiedPlanCiphertext = d.modifiedPlanCiphertext,
        expireAtMs = d.expireAtMs,
        rollbackFlag = d.rollbackFlag,
        createdAtMs = d.createdAtMs
    )

    // ── Fuse ───────────────────────────────────────────────────────────────

    fun toDto(e: HallucinationFuseEntity): ZthFuseFirestoreDto = ZthFuseFirestoreDto(
        id = e.id,
        scope = e.scope,
        scopeId = e.scopeId,
        state = e.state,
        linkageVersion = e.linkageVersion,
        failureCount = e.failureCount,
        openSinceMs = e.openSinceMs,
        lastProbeAtMs = e.lastProbeAtMs,
        killSwitch1Triggered = e.killSwitch1Triggered,
        killSwitch2SoftDisabled = e.killSwitch2SoftDisabled,
        lastTripSubclass = e.lastTripSubclass,
        updatedAtMs = e.updatedAtMs
    )

    fun toEntity(d: ZthFuseFirestoreDto): HallucinationFuseEntity = HallucinationFuseEntity(
        id = d.id,
        scope = d.scope,
        scopeId = d.scopeId,
        state = d.state,
        linkageVersion = d.linkageVersion,
        failureCount = d.failureCount,
        openSinceMs = d.openSinceMs,
        lastProbeAtMs = d.lastProbeAtMs,
        killSwitch1Triggered = d.killSwitch1Triggered,
        killSwitch2SoftDisabled = d.killSwitch2SoftDisabled,
        lastTripSubclass = d.lastTripSubclass,
        updatedAtMs = d.updatedAtMs
    )

    // ── Rejection Audit ────────────────────────────────────────────────────

    fun toDto(e: SentinelPlanRejectionAuditEntity): ZthRejectionAuditFirestoreDto =
        ZthRejectionAuditFirestoreDto(
            id = e.id,
            sentinelId = e.sentinelId,
            rejectionType = e.rejectionType,
            reasonCiphertext = e.s_reasonCiphertext,
            rejectedPlanSnapshotCiphertext = e.s_rejectedPlanSnapshotCiphertext,
            createdAtMs = e.createdAtMs
        )

    fun toEntity(d: ZthRejectionAuditFirestoreDto): SentinelPlanRejectionAuditEntity =
        SentinelPlanRejectionAuditEntity(
            id = d.id,
            sentinelId = d.sentinelId,
            rejectionType = d.rejectionType,
            s_reasonCiphertext = d.reasonCiphertext,
            s_rejectedPlanSnapshotCiphertext = d.rejectedPlanSnapshotCiphertext,
            createdAtMs = d.createdAtMs
        )

    // ── Hard Delete Audit ──────────────────────────────────────────────────

    fun toDto(e: HardConstraintDeleteAuditEntity): ZthHardDeleteAuditFirestoreDto =
        ZthHardDeleteAuditFirestoreDto(
            id = e.id,
            sessionId = e.sessionId,
            affectedTableName = e.affectedTableName,
            affectedKeysCiphertext = e.s_affectedKeysCiphertext,
            triggerSubClass = e.triggerSubClass,
            rollbackApplied = e.rollbackApplied,
            createdAtMs = e.createdAtMs
        )

    fun toEntity(d: ZthHardDeleteAuditFirestoreDto): HardConstraintDeleteAuditEntity =
        HardConstraintDeleteAuditEntity(
            id = d.id,
            sessionId = d.sessionId,
            affectedTableName = d.affectedTableName,
            s_affectedKeysCiphertext = d.affectedKeysCiphertext,
            triggerSubClass = d.triggerSubClass,
            rollbackApplied = d.rollbackApplied,
            createdAtMs = d.createdAtMs
        )

    // ── L0 Restore Log ─────────────────────────────────────────────────────

    fun toDto(e: L0SoftCompactRestoreLogEntity): ZthL0RestoreLogFirestoreDto =
        ZthL0RestoreLogFirestoreDto(
            id = e.id,
            sessionId = e.sessionId,
            firstMessageId = e.firstMessageId,
            lastMessageId = e.lastMessageId,
            originalRowCount = e.originalRowCount,
            tokensBefore = e.tokensBefore,
            tokensAfter = e.tokensAfter,
            compactSourceDigestCiphertext = e.s_compactSourceDigestCiphertext,
            expireAtMs = e.expireAtMs,
            restoredFlag = e.restoredFlag,
            createdAtMs = e.createdAtMs
        )

    fun toEntity(d: ZthL0RestoreLogFirestoreDto): L0SoftCompactRestoreLogEntity =
        L0SoftCompactRestoreLogEntity(
            id = d.id,
            sessionId = d.sessionId,
            firstMessageId = d.firstMessageId,
            lastMessageId = d.lastMessageId,
            originalRowCount = d.originalRowCount,
            tokensBefore = d.tokensBefore,
            tokensAfter = d.tokensAfter,
            s_compactSourceDigestCiphertext = d.compactSourceDigestCiphertext,
            expireAtMs = d.expireAtMs,
            restoredFlag = d.restoredFlag,
            createdAtMs = d.createdAtMs
        )

    // ── Telemetry（默认不同步，仅留接口）─────────────────────────────────────

    fun toDto(e: ZthTelemetryEventEntity): ZthTelemetryEventFirestoreDto = ZthTelemetryEventFirestoreDto(
        id = e.id,
        eventKind = e.eventKind,
        eventSubKind = e.eventSubKind,
        severityTier = e.severityTier,
        sessionSha256Prefix = e.sessionSha256Prefix,
        latencyMs = e.latencyMs,
        flagA = e.flagA,
        flagB = e.flagB,
        metricA = e.metricA,
        metricB = e.metricB,
        createdAtMs = e.createdAtMs
    )

    fun toEntity(d: ZthTelemetryEventFirestoreDto): ZthTelemetryEventEntity = ZthTelemetryEventEntity(
        id = d.id,
        eventKind = d.eventKind,
        eventSubKind = d.eventSubKind,
        severityTier = d.severityTier,
        sessionSha256Prefix = d.sessionSha256Prefix,
        latencyMs = d.latencyMs,
        flagA = d.flagA,
        flagB = d.flagB,
        metricA = d.metricA,
        metricB = d.metricB,
        createdAtMs = d.createdAtMs
    )
}
