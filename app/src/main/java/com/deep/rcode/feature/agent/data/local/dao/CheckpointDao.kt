package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity

@Dao
interface CheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity>

    @Query("SELECT * FROM session_checkpoints WHERE userMessageId = :messageId LIMIT 1")
    suspend fun getCheckpointByMessageId(messageId: String): CheckpointEntity?

    @Query("SELECT * FROM session_checkpoints WHERE id = :checkpointId LIMIT 1")
    suspend fun getCheckpointById(checkpointId: String): CheckpointEntity?

    @Query("DELETE FROM session_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpointsForSession(sessionId: String)

    @Query("DELETE FROM session_checkpoints WHERE createdAtMs < :cutoffTimestamp")
    suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long)
}
