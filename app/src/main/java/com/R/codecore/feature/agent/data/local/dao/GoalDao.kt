package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(goals: List<GoalEntity>)

    @Query("SELECT * FROM agent_goals WHERE goalId = :goalId")
    suspend fun getById(goalId: String): GoalEntity?

    /** 每会话唯一当前 goal（status = ACTIVE）。 */
    @Query("SELECT * FROM agent_goals WHERE sessionId = :sessionId AND status = 'ACTIVE' ORDER BY updatedAtMs DESC LIMIT 1")
    fun getActiveBySession(sessionId: String): Flow<GoalEntity?>

    @Query("SELECT * FROM agent_goals WHERE sessionId = :sessionId AND status = 'ACTIVE' ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getActiveBySessionOnce(sessionId: String): GoalEntity?

    @Query("SELECT * FROM agent_goals WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    fun getBySession(sessionId: String): Flow<List<GoalEntity>>

    @Query("SELECT * FROM agent_goals")
    suspend fun getAllOnce(): List<GoalEntity>

    /** compare-and-set 修订（CAS）：仅当修订号仍为 [expectedRevision] 时更新，返回受影响行数。 */
    @Query(
        "UPDATE agent_goals SET status = :status, text = :text, revision = :newRevision, " +
                "updatedAtMs = :updatedAtMs WHERE goalId = :goalId AND revision = :expectedRevision"
    )
    suspend fun casUpdateStatusAndText(
        goalId: String,
        status: String,
        text: String,
        newRevision: Int,
        expectedRevision: Int,
        updatedAtMs: Long
    ): Int

    @Query("DELETE FROM agent_goals WHERE goalId = :goalId")
    suspend fun delete(goalId: String)

    @Query("DELETE FROM agent_goals WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
