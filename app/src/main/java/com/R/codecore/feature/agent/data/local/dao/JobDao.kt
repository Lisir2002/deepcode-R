package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.JobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(jobs: List<JobEntity>)

    @Query("SELECT * FROM agent_jobs WHERE jobId = :jobId")
    suspend fun getById(jobId: String): JobEntity?

    @Query("SELECT * FROM agent_jobs WHERE jobId = :jobId")
    fun getByIdFlow(jobId: String): Flow<JobEntity?>

    @Query("SELECT * FROM agent_jobs WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    fun getBySession(sessionId: String): Flow<List<JobEntity>>

    @Query("SELECT * FROM agent_jobs WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    suspend fun getBySessionOnce(sessionId: String): List<JobEntity>

    @Query("SELECT * FROM agent_jobs WHERE status = 'RUNNING'")
    suspend fun getRunningOnce(): List<JobEntity>

    @Query("SELECT * FROM agent_jobs")
    suspend fun getAllOnce(): List<JobEntity>

    @Query("UPDATE agent_jobs SET status = :status, exitCode = :exitCode, finishedAtMs = :finishedAtMs, updatedAtMs = :updatedAtMs WHERE jobId = :jobId")
    suspend fun updateResult(
        jobId: String,
        status: String,
        exitCode: Int?,
        finishedAtMs: Long?,
        updatedAtMs: Long
    )

    @Query("DELETE FROM agent_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM agent_jobs WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
