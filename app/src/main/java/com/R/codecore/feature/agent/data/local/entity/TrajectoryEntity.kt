package com.R.codecore.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运行轨迹表（append-only，D2/§3.8）。
 *
 * 作为「步骤结果汇总」的统一数据源：每次工具执行完成追加 tool 轨迹；turn 边界 / 压缩 /
 * goal·plan 注入 / 错误 / 超时时追加轻量标记。独立于 agent_messages（不受上下文压缩影响，
 * 历史细节不丢失），供强制收敛摘要 / Playbook 阶段总结 / 审计回放消费。
 *
 * 体积控制：resultSummary 全工具截断控单条体积；turn 标记条数 ≤ 工具条数（轻量）；
 * 删除会话时级联清理；可设保留条数上限（超出仅删 turn 标记类）。
 */
@Entity(
    tableName = "agent_trajectories",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["taskId"]),
    ]
)
data class TrajectoryEntity(
    @PrimaryKey val trajectoryId: String,
    val sessionId: String,
    /** 任务分组 id：同一轮用户请求产出的轨迹共享同一 taskId；历史为空串。 */
    @ColumnInfo(defaultValue = "")
    val taskId: String,
    /** 回合序号（turn 边界轻量标记按轮次累积）。 */
    @ColumnInfo(defaultValue = "0")
    val turnIndex: Int = 0,
    /** 轨迹种类：tool / turn / compaction / inject / error / timeout。 */
    val kind: String,
    /** 仅 tool 类：工具名。 */
    @ColumnInfo(defaultValue = "")
    val toolName: String = "",
    /** 仅 tool 类：参数哈希（用于去重/审计，不存明文参数）。 */
    @ColumnInfo(defaultValue = "")
    val argsHash: String = "",
    /** 结果摘要（按工具定制提取，全工具截断）。 */
    @ColumnInfo(defaultValue = "")
    val resultSummary: String = "",
    /** 是否失败。 */
    @ColumnInfo(defaultValue = "0")
    val isError: Boolean = false,
    /** 执行耗时毫秒。 */
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0,
    /** 输入 token（本轨迹对应）。 */
    @ColumnInfo(defaultValue = "0")
    val tokensIn: Int = 0,
    /** 输出 token（本轨迹对应）。 */
    @ColumnInfo(defaultValue = "0")
    val tokensOut: Int = 0,
    /** 轨迹时间戳毫秒。 */
    val ts: Long
)
