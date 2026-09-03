package com.core.deepcode.feature.agent.data.local.entity

import com.core.deepcode.core.util.EnumSafe

/**
 * 会话级「任务目标」状态机（对齐 DSH GoalService）：
 * 每会话唯一当前 goal，可追溯、可修订（revision CAS）、可归因（roundSeq）。
 * 变更通过 [AgentEvent.GoalChanged] 与消息同日志，压缩后可从日志折叠出目标快照。
 *
 * 表名 agent_goals，agent 库 v1→v2 新增（见 AgentDatabaseMigrations）。
 * 各列的 @ColumnInfo(defaultValue) 必须与 MIGRATION_1_2 的 SQL 保持一致（Room TableInfo 校验）。
 */

data class GoalEntity(
     val goalId: String,
    val sessionId: String,
    val text: String,
    /** [GoalStatus] 名称。 */
    
    val status: String = GoalStatus.ACTIVE.name,
    /** 单调递增修订号：变更采用 compare-and-set（读→改→按旧 revision 更新）。 */
    
    val revision: Int = 0,
    /** 父目标 id（支持子目标）；无父为空串。 */
    
    val parentGoalId: String = "",
    /** 归因到工作流的第几轮（roundSeq，供审计/折叠快照）。 */
    
    val roundSeq: Int = 0,
    /** 创建时间毫秒。 */
    val createdAtMs: Long,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun statusEnum(): GoalStatus =
        EnumSafe.valueOf(status, GoalStatus.ACTIVE, tag = "GoalEntity.status")
}

/** 目标生命周期状态（对齐 DSH GoalStatus）。 */
enum class GoalStatus {
    /** 已提出但尚未激活（可被替换）。 */
    PROPOSED,
    /** 当前生效目标。 */
    ACTIVE,
    /** 已完成。 */
    DONE,
    /** 已放弃（被新目标替换 / 用户取消）。 */
    ABANDONED
}
