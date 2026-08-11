package com.deep.rcode.feature.agent.data.remote.zth

import kotlinx.serialization.Serializable

/**
 * Firestore 数据层通用 envelope（Phase 4 接入后 C.4.18 LWW 合并用）。
 * 任何 Firestore 文档顶层必带这 6 列（Phase 4 FirestoreSyncer 读写时以此 envelope 存）。
 *  - `ct`：AES-256-GCM(shared_sync_key, dto_payload_json) base64
 *  - `hmac`：HMAC-SHA256(shared_sync_key, ct) base64 → 防未授权设备非法写入
 *  - `updatedAtMs` + `updatedAtDeviceId`：LWW 冲突合并（跨设备冲突用单调时间戳）
 *
 * 本文件 Dto 仅描述「解密后的 dto_payload_json」数据结构，外层 envelope 写在 Syncer 内部。
 */

/** UserConfirmedSentinelEntity 对应 Firestore Dto（解密后的 payload JSON）。 */
@Serializable
data class ZthSentinelFirestoreDto(
    val id: String,
    val sessionId: String,
    val linkageVersion: Long,
    val chainId: String,
    val chainIndex: Int,
    val cardTemplateId: String,
    val triggerSubClass: String,
    /** Firestore 同步时此值依然是加密后 blob（shared_sync_key 二次 wrap 后），Phase 4 解包。 */
    val planPayloadCiphertext: String,
    val userTextCiphertext: String? = null,
    val cardPayloadCiphertext: String,
    val userChoice: String,
    val swipeVerified: Boolean,
    val modifiedPlanCiphertext: String? = null,
    val expireAtMs: Long = -1L,
    val rollbackFlag: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class ZthFuseFirestoreDto(
    val id: String,
    val scope: String,
    val scopeId: String,
    val state: String,
    val linkageVersion: Long,
    val failureCount: Int = 0,
    val openSinceMs: Long = 0L,
    val lastProbeAtMs: Long = 0L,
    val killSwitch1Triggered: Boolean = false,
    val killSwitch2SoftDisabled: Boolean = false,
    val lastTripSubclass: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class ZthRejectionAuditFirestoreDto(
    val id: String,
    val sentinelId: String,
    val rejectionType: String,
    val reasonCiphertext: String? = null,
    val rejectedPlanSnapshotCiphertext: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class ZthHardDeleteAuditFirestoreDto(
    val id: String,
    val sessionId: String,
    val affectedTableName: String,
    val affectedKeysCiphertext: String,
    val triggerSubClass: String,
    val rollbackApplied: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class ZthL0RestoreLogFirestoreDto(
    val id: String,
    val sessionId: String,
    val firstMessageId: String,
    val lastMessageId: String,
    val originalRowCount: Int,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val compactSourceDigestCiphertext: String,
    val expireAtMs: Long,
    val restoredFlag: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

/** Telemetry 跨设备同步可选（默认不同步，只本机看），但 Dto 留一份以便 C.4.18 完整 6 表。 */
@Serializable
data class ZthTelemetryEventFirestoreDto(
    val id: Long,
    val eventKind: String,
    val eventSubKind: String,
    val severityTier: Int,
    val sessionSha256Prefix: String? = null,
    val latencyMs: Long? = null,
    val flagA: Boolean? = null,
    val flagB: Boolean? = null,
    val metricA: Long? = null,
    val metricB: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)
