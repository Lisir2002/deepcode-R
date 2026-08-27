package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.CheckpointFileSnapshotEntity

/**
 * DB-SHIELD-RC68 P2-4：把 CheckpointFileSnapshot 相关操作从 CheckpointDao 抽离为独立 DAO。
 * 原 CheckpointDao 一张管 session_checkpoints + checkpoint_file_snapshots 两张表 → 将来加审计闸门时
 * `getAllOnce()` 等方法语义模糊（checkpoint 还是 snapshot？）。
 */
@Dao
interface CheckpointFileSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity)

    @Query("SELECT * FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId")
    suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity>

    @Query("SELECT COUNT(*) FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId AND filePath = :filePath")
    suspend fun countSnapshot(checkpointId: String, filePath: String): Int

    @Query("DELETE FROM checkpoint_file_snapshots WHERE checkpointId IN (SELECT id FROM session_checkpoints WHERE sessionId = :sessionId)")
    suspend fun deleteFileSnapshotsForSession(sessionId: String)

    @Query("SELECT * FROM checkpoint_file_snapshots")
    suspend fun getAllOnce(): List<CheckpointFileSnapshotEntity>
}
