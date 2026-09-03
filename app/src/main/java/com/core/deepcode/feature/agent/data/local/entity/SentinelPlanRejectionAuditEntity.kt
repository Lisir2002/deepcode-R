package com.core.deepcode.feature.agent.data.local.entity

/**
 * C.4.3 LINK-INV 审计：用户按 REJECT/MODIFY 的决策记录（Plan 被拒绝时强制写 1 条）。
 * 用于 C.4.17 ZTH DevMenu 第 3 页「拒绝决策追溯」。
 */

data class SentinelPlanRejectionAuditEntity(
    
    val id: String,
    /** 外键 → zth_user_confirmed_sentinels.id。 */
    val sentinelId: String,
    /** REJECT / MODIFY_AND_CONFIRM（用户最终选）。 */
    val rejectionType: String,
    /** 加密后：拒绝原因 + 用户手写理由（SwipeToConfirm 完成后写）。 */
    val s_reasonCiphertext: String? = null,
    /** 加密后：被拒绝的原始 plan JSON 快照。 */
    val s_rejectedPlanSnapshotCiphertext: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
