package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(schedules: List<ScheduleEntity>)

    @Query("SELECT * FROM agent_schedules WHERE scheduleId = :scheduleId")
    suspend fun getById(scheduleId: String): ScheduleEntity?

    @Query("SELECT * FROM agent_schedules WHERE scheduleId = :scheduleId")
    fun getByIdFlow(scheduleId: String): Flow<ScheduleEntity?>

    @Query("SELECT * FROM agent_schedules WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    fun getBySession(sessionId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM agent_schedules WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    suspend fun getBySessionOnce(sessionId: String): List<ScheduleEntity>

    /** 全部待投递项（status = PENDING 且启用），供统一调度循环扫描。 */
    @Query("SELECT * FROM agent_schedules WHERE status = 'PENDING' AND enabled = 1 ORDER BY createdAtMs ASC")
    suspend fun getPendingOnce(): List<ScheduleEntity>

    @Query("SELECT * FROM agent_schedules")
    suspend fun getAllOnce(): List<ScheduleEntity>

    @Query("UPDATE agent_schedules SET status = :status, enabled = :enabled, lastFiredAtMs = :lastFiredAtMs, updatedAtMs = :updatedAtMs WHERE scheduleId = :scheduleId")
    suspend fun updateState(
        scheduleId: String,
        status: String,
        enabled: Int,
        lastFiredAtMs: Long?,
        updatedAtMs: Long
    )

    @Query("DELETE FROM agent_schedules WHERE scheduleId = :scheduleId")
    suspend fun delete(scheduleId: String)

    @Query("DELETE FROM agent_schedules WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
