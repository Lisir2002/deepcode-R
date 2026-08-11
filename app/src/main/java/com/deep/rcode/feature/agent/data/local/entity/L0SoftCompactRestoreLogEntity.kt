package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * C.3.1 L0 软压缩 Context 的还原日志（崩溃恢复和审计还原必须读）。
 * sentinel 过期后 C.4.3 自动失效链路基于本表 expireAtMs 判定。
 */
@Entity(
    tableName = "zth_l0_soft_compact_restore_logs",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["expireAtMs"]),
        Index(value = ["createdAtMs"])
    ]
)
data class L0SoftCompactRestoreLogEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val sessionId: String,
    /** L0 压缩前 MessageId 范围：[firstMessageId, lastMessageId]。 */
    val firstMessageId: String,
    val lastMessageId: String,
    /** 压缩前 row_count（验收闸门：还原后 row_count ≥ 0.95x 才算成功）。 */
    val originalRowCount: Int,
    /** 压缩前后 tokens 估计（验收闸门 C.3.1）。 */
    val tokensBefore: Int,
    val tokensAfter: Int,
    /** 加密后：原始未压缩 JSON 摘要（哈希 + 部分原文用于审计）。 */
    val s_compactSourceDigestCiphertext: String,
    /** 过期时间戳；C.4.3 默认 sentinel 48h；-1L = 用户已确认永不删。 */
    val expireAtMs: Long,
    /** C.4.3 还原是否成功（仅审计字段）。 */
    val restoredFlag: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)
