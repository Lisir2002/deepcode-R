package com.R.codecore.feature.agent.data.local.entity

import com.R.codecore.core.util.EnumSafe

/**
 * 会话级「Playbook 剧本运行」持久化（D5-3，对齐 norm-chain-design.md §3.3.6 结构定稿）：
 *
 * 多阶段剧本（feature-dev / code-review / bug-fix）的一次运行的**双状态机**：
 * - **运行级** [PlaybookRunStatus]：RUNNING / COMPLETED / ABORTED / INTERRUPTED；
 * - **阶段级** [PlaybookStageStatus]：PENDING / ACTIVE / DONE / FAILED（见 [PlaybookStageState]，JSON 存于 [stageStatuses]）。
 *
 * 中断/恢复对齐 [JobEntity] 语义：SessionStop hook 把该会话 RUNNING 运行置 INTERRUPTED；
 * 新会话需显式 `playbook_resume`（`/playbook resume`）继续，`playbook_start` 覆盖旧运行。
 *
 * 表名 agent_playbook_runs，agent 库 v3→v4 新增（见 AgentDatabaseMigrations.MIGRATION_3_4）。
 */

data class PlaybookRunEntity(
     val playbookRunId: String,
    val sessionId: String,
    /** 剧本资产名（playbook frontmatter name，如 feature-dev）。 */
    val playbookName: String,
    /** 当前阶段下标（0 起）。 */
    
    val currentStageIndex: Int = 0,
    /** 各阶段状态 JSON（[PlaybookStageState] 列表序列化，含产物清单），设计为 TEXT。 */
    
    val stageStatuses: String = "",
    /** [PlaybookRunStatus] 名称。 */
    
    val status: String = PlaybookRunStatus.RUNNING.name,
    /** 创建时间毫秒。 */
    val createdAtMs: Long,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun statusEnum(): PlaybookRunStatus =
        EnumSafe.valueOf(status, PlaybookRunStatus.RUNNING, tag = "PlaybookRunEntity.status")
}

/** Playbook 运行级状态（D5-3，双状态机之一；对齐 norm-chain §3.3.4）。 */
enum class PlaybookRunStatus {
    /** 运行中（阶段推进中）。 */
    RUNNING,
    /** 全部阶段 DONE，正常完成。 */
    COMPLETED,
    /** 阶段失败（模型声明失败/审批拒绝），运行中止；可 `playbook_retry` 从 FAILED 阶段恢复。 */
    ABORTED,
    /** 被中断（进程回收/SessionStop），可 `playbook_resume` 继续。 */
    INTERRUPTED
}

/** Playbook 阶段级状态（D5-3，双状态机之二；对齐 norm-chain §3.3.4）。 */
enum class PlaybookStageStatus {
    /** 未开始。 */
    PENDING,
    /** 进行中（当前阶段）。 */
    ACTIVE,
    /** 已完成（advance done）。 */
    DONE,
    /** 失败（advance fail / 审批拒绝）。 */
    FAILED
}

/**
 * 单个阶段的持久化状态（D5-3，序列化进 [PlaybookRunEntity.stageStatuses] 的 JSON 数组元素）。
 *
 * - [name]：阶段名（与 [com.R.codecore.feature.agent.domain.playbook.PlaybookStage.name] 对齐）。
 * - [status]：[PlaybookStageStatus] 名称。
 * - [artifacts]：阶段**产物清单**（D5-8，阶段 DONE 时记录本阶段已完成/产出的文件路径清单，
 *   `playbook_resume`/`playbook_retry` 时注入给模型对照跳过已完成操作；文件写按内容写入天然幂等）。
 */
@kotlinx.serialization.Serializable
data class PlaybookStageState(
    val name: String,
    val status: String = PlaybookStageStatus.PENDING.name,
    val artifacts: List<String> = emptyList()
)
