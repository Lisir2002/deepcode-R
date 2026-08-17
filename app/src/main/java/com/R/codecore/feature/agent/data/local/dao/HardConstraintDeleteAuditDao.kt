package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity

@Dao
interface HardConstraintDeleteAuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HardConstraintDeleteAuditEntity)

    /** C.4.11 DB Migration 兜底：扫描未回滚删除 + 重建 sentinel。 */
    @Query("SELECT * FROM zth_hard_constraint_delete_audits WHERE rollbackApplied = 0 ORDER BY createdAtMs ASC")
    suspend fun getPendingRollbacks(): List<HardConstraintDeleteAuditEntity>

    @Query("UPDATE zth_hard_constraint_delete_audits SET rollbackApplied = 1 WHERE id = :id")
    suspend fun markRolledBack(id: String)

    @Query("SELECT * FROM zth_hard_constraint_delete_audits")
    suspend fun getAllOnce(): List<HardConstraintDeleteAuditEntity>
}
