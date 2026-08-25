package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.TrajectoryEntity

/**
 * 运行轨迹表 DAO（D2-3，对齐 norm-chain-design.md §3.8）。
 *
 * append-only 语义：只插入不更新（除删除会话级联清理外），供轨迹消费方
 * （已做动作摘要 / 阶段总结 / 审计回放）作为单一数据源查询。
 */
@Dao
interface TrajectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trajectory: TrajectoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(trajectories: List<TrajectoryEntity>)

    /** 按会话查完整轨迹（审计回放，时间升序）。 */
    @Query("SELECT * FROM agent_trajectories WHERE sessionId = :sessionId ORDER BY ts ASC")
    suspend fun getBySession(sessionId: String): List<TrajectoryEntity>

    /** 按任务分组查完整轨迹（审计回放，时间升序）。 */
    @Query("SELECT * FROM agent_trajectories WHERE taskId = :taskId ORDER BY ts ASC")
    suspend fun getByTask(taskId: String): List<TrajectoryEntity>

    /** 本回合（taskId + turnIndex）轨迹：用量卡片本回合增量统计。 */
    @Query("SELECT * FROM agent_trajectories WHERE taskId = :taskId AND turnIndex = :turnIndex ORDER BY ts ASC")
    suspend fun getByTaskTurn(taskId: String, turnIndex: Int): List<TrajectoryEntity>

    /** 本回合 tool 轨迹（工具执行明细，不含 turn 等轻量标记）。 */
    @Query("SELECT * FROM agent_trajectories WHERE taskId = :taskId AND turnIndex = :turnIndex AND kind = 'tool' ORDER BY ts ASC")
    suspend fun getToolsByTaskTurn(taskId: String, turnIndex: Int): List<TrajectoryEntity>

    /** 会话内最近一次非空 taskId（供用量卡片按最新回合定位）。 */
    @Query("SELECT taskId FROM agent_trajectories WHERE sessionId = :sessionId AND taskId != '' ORDER BY ts DESC LIMIT 1")
    suspend fun getLatestTaskId(sessionId: String): String?

    /** 会话内最近一个回合号（供用量卡片定位本回合）。 */
    @Query("SELECT MAX(turnIndex) FROM agent_trajectories WHERE sessionId = :sessionId AND taskId = :taskId")
    suspend fun getMaxTurnIndex(sessionId: String, taskId: String): Int?

    /** 会话累计用量（全轨迹 token 合计，含 turn 边界轻量标记携带的 token）。 */
    @Query("SELECT COALESCE(SUM(tokensIn), 0) as tokensIn, COALESCE(SUM(tokensOut), 0) as tokensOut, COUNT(*) as count FROM agent_trajectories WHERE sessionId = :sessionId")
    suspend fun getSessionAggregate(sessionId: String): TrajectoryAggregate

    /** 删除会话时级联清理（对齐 SessionUseCase.deleteSession 九表级联）。 */
    @Query("DELETE FROM agent_trajectories WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

/**
 * 轨迹聚合结果（用量卡片 / 消费方统计用）。
 */
data class TrajectoryAggregate(
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val count: Long = 0
)
