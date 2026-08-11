package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity

@Dao
interface L0SoftCompactRestoreLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: L0SoftCompactRestoreLogEntity)

    /** C.4.3 崩溃恢复：找已过期但未还原 / 半完成的 L0。 */
    @Query("SELECT * FROM zth_l0_soft_compact_restore_logs WHERE expireAtMs > 0 AND expireAtMs <= :nowMs AND restoredFlag = 0 ORDER BY createdAtMs ASC")
    suspend fun getExpiredAndNotRestored(nowMs: Long): List<L0SoftCompactRestoreLogEntity>

    @Query("SELECT * FROM zth_l0_soft_compact_restore_logs WHERE sessionId = :sessionId ORDER BY createdAtMs ASC")
    suspend fun getBySession(sessionId: String): List<L0SoftCompactRestoreLogEntity>

    @Query("UPDATE zth_l0_soft_compact_restore_logs SET restoredFlag = 1 WHERE id = :id")
    suspend fun markRestored(id: String)

    @Query("SELECT * FROM zth_l0_soft_compact_restore_logs")
    suspend fun getAllOnce(): List<L0SoftCompactRestoreLogEntity>
}
