package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.PlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plans: List<PlanEntity>)

    @Query("SELECT * FROM agent_plans WHERE planId = :planId")
    suspend fun getById(planId: String): PlanEntity?

    /** 会话最近一份计划（计划协作模式下一会话单计划）。 */
    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId ORDER BY updatedAtMs DESC LIMIT 1")
    fun getLatestBySession(sessionId: String): Flow<PlanEntity?>

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun getLatestBySessionOnce(sessionId: String): PlanEntity?

    @Query("SELECT * FROM agent_plans WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    fun getBySession(sessionId: String): Flow<List<PlanEntity>>

    @Query("SELECT * FROM agent_plans")
    suspend fun getAllOnce(): List<PlanEntity>

    @Query("UPDATE agent_plans SET status = :status, steps = :steps, pendingSelection = :pendingSelection, updatedAtMs = :updatedAtMs WHERE planId = :planId")
    suspend fun updateContent(
        planId: String,
        status: String,
        steps: String,
        pendingSelection: String,
        updatedAtMs: Long
    )

    @Query("DELETE FROM agent_plans WHERE planId = :planId")
    suspend fun delete(planId: String)

    @Query("DELETE FROM agent_plans WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
