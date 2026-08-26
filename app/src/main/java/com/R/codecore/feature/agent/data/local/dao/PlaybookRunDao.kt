package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.PlaybookRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * Playbook 剧本运行表 DAO（D5-3，对齐 norm-chain-design.md §3.3.6）。
 *
 * 每会话同一时刻至多一份「活跃运行」（RUNNING / INTERRUPTED 视为可继续），
 * `playbook_start` 覆盖旧运行（新建时把旧非终态运行置 ABORTED）。查询走会话 + 状态。
 */
@Dao
interface PlaybookRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: PlaybookRunEntity)

    @Query("SELECT * FROM agent_playbook_runs WHERE playbookRunId = :runId")
    suspend fun getById(runId: String): PlaybookRunEntity?

    /** 会话最近一次运行（任意状态）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE sessionId = :sessionId ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestBySession(sessionId: String): PlaybookRunEntity?

    /** 会话最近一次运行（Flow，供 UI/注入监听）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE sessionId = :sessionId ORDER BY updatedAtMs DESC LIMIT 1")
    fun getLatestBySessionFlow(sessionId: String): Flow<PlaybookRunEntity?>

    /** 会话最近一次 RUNNING 运行（playbook_advance 默认作用目标）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE sessionId = :sessionId AND status = 'RUNNING' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestRunningBySession(sessionId: String): PlaybookRunEntity?

    /** 会话最近一次 INTERRUPTED 运行（playbook_resume 目标）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE sessionId = :sessionId AND status = 'INTERRUPTED' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestInterruptedBySession(sessionId: String): PlaybookRunEntity?

    /** 会话最近一次 ABORTED 运行（playbook_retry 目标）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE sessionId = :sessionId AND status = 'ABORTED' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestAbortedBySession(sessionId: String): PlaybookRunEntity?

    @Query("SELECT * FROM agent_playbook_runs")
    suspend fun getAllOnce(): List<PlaybookRunEntity>

    /** 全部 RUNNING 运行（启动恢复扫描用：进程回收后置 INTERRUPTED，对齐 JobService.markInterruptedOnBoot）。 */
    @Query("SELECT * FROM agent_playbook_runs WHERE status = 'RUNNING'")
    suspend fun getRunningOnce(): List<PlaybookRunEntity>

    /** 按会话清空（删除会话时级联清理）。 */
    @Query("DELETE FROM agent_playbook_runs WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
