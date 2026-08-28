package com.R.codecore.feature.agent.data.local.entity

import com.R.codecore.core.util.EnumSafe

/**
 * 会话级「计划协作状态」（对齐 DSH plan mode + Claude Code Plan/Spec）：
 * 持久化当前计划（title + steps(JSON) + 生命周期 status），并保存未获批前的
 * pendingSelection（每轮 pre-step 追加到模型请求，用户批准后写入 user/message 替换）。
 *
 * 表名 agent_plans，agent 库 v1→v2 新增（见 AgentDatabaseMigrations）。
 */

data class PlanEntity(
     val planId: String,
    val sessionId: String,
    val title: String,
    /** steps 的 JSON 数组序列化（[ {text, status} ]），设计为 TEXT。 */
    
    val steps: String = "",
    /** [PlanStatus] 名称。 */
    
    val status: String = PlanStatus.DRAFT.name,
    /** 用户尚未批准的待定选择（注入 pre-step 用）；空串表示无待定项。 */
    
    val pendingSelection: String = "",
    /** 创建时间毫秒。 */
    val createdAtMs: Long,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun statusEnum(): PlanStatus =
        EnumSafe.valueOf(status, PlanStatus.DRAFT, tag = "PlanEntity.status")
}

/** 计划生命周期状态（对齐 DSH plan status）。 */
enum class PlanStatus {
    /** 草稿（含 pendingSelection，尚未获批）。 */
    DRAFT,
    /** 用户已批准，可进入执行。 */
    APPROVED,
    /** 执行中。 */
    EXECUTING,
    /** 已完成。 */
    COMPLETED,
    /** 已放弃/被替换。 */
    ABANDONED
}
