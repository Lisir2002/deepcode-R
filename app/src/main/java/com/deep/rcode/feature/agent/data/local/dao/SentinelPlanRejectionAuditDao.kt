package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SentinelPlanRejectionAuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SentinelPlanRejectionAuditEntity)

    @Query("SELECT * FROM zth_sentinel_plan_rejection_audits WHERE sentinelId = :sentinelId ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun getBySentinel(sentinelId: String): SentinelPlanRejectionAuditEntity?

    @Query("SELECT * FROM zth_sentinel_plan_rejection_audits ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SentinelPlanRejectionAuditEntity>>

    @Query("SELECT * FROM zth_sentinel_plan_rejection_audits")
    suspend fun getAllOnce(): List<SentinelPlanRejectionAuditEntity>
}
