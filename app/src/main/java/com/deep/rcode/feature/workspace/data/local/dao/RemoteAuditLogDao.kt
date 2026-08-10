package com.deep.rcode.feature.workspace.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.workspace.data.local.entity.RemoteAuditLogEntity

@Dao
interface RemoteAuditLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: RemoteAuditLogEntity): Long

    /** 分页查询，按时间降序。 */
    @Query("SELECT * FROM remote_audit_logs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun pageDesc(offset: Int, limit: Int): List<RemoteAuditLogEntity>

    /** 按连接 ID 查询最近条目。 */
    @Query("SELECT * FROM remote_audit_logs WHERE connectionId = :cid ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByConnection(cid: String, limit: Int): List<RemoteAuditLogEntity>

    /** 多维筛选。 */
    @Query("""
        SELECT * FROM remote_audit_logs
        WHERE category IN (:cats)
          AND success IN (:successes)
          AND createdAt >= :sinceMs
        ORDER BY createdAt DESC LIMIT :limit
    """)
    suspend fun filter(
        cats: List<String>,
        successes: List<Boolean> = listOf(true, false),
        sinceMs: Long = 0L,
        limit: Int = 500
    ): List<RemoteAuditLogEntity>

    /** 清理 beforeEpochMs 之前的条目；返回删除数量。 */
    @Query("DELETE FROM remote_audit_logs WHERE createdAt < :beforeEpochMs")
    suspend fun purgeBefore(beforeEpochMs: Long): Int

    @Query("SELECT COUNT(1) FROM remote_audit_logs")
    suspend fun count(): Long

    /** 获取所有 distinct 分类。 */
    @Query("SELECT DISTINCT category FROM remote_audit_logs ORDER BY category")
    suspend fun listCategories(): List<String>
}