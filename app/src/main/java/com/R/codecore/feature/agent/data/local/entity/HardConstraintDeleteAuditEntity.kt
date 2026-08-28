package com.R.codecore.feature.agent.data.local.entity

/**
 * C.4.3 LINK-INV 审计：任何「硬约束被 AI 删除」的动作必须写此表（即使后来被 LINK-INV 迁移回滚）。
 * 由 ZthDbAutoReconciler（C.4.11 DB Migration 兜底）扫描本表 + 重建 sentinel 时使用。
 */

data class HardConstraintDeleteAuditEntity(
    
    val id: String,
    val sessionId: String,
    /** ZTH-0：被删除的 row/snapshot/file path 所属表名或 path 类型（如 session_checkpoints / 文件快照）。 */
    val affectedTableName: String,
    /** 加密后：原始被删的主键 JSON（敏感列用 Keystore 加密）。 */
    val s_affectedKeysCiphertext: String,
    /** 触发子 FailureSubClass.name。 */
    val triggerSubClass: String,
    /** LINK-INV 是否已回滚此删除（true=已恢复）。 */
    val rollbackApplied: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)
