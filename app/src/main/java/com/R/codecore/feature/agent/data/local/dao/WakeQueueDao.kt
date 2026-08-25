package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity

/**
 * 统一唤醒队列 DAO（R02 新增，SCHEMA v48）。
 */
@Dao
interface WakeQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WakeItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WakeItemEntity>)

    /** 某会话指定状态的唤醒（按入队时间升序）。 */
    @Query(
        "SELECT * FROM wake_queue WHERE status = :status AND session_id = :sessionId " +
            "ORDER BY created_at_ms"
    )
    suspend fun getBySessionAndStatus(sessionId: String, status: String): List<WakeItemEntity>

    /** 全量指定状态唤醒（启动重扫待注入队列用）。 */
    @Query("SELECT * FROM wake_queue WHERE status = :status ORDER BY created_at_ms")
    suspend fun getByStatus(status: String): List<WakeItemEntity>

    /** 批量更新状态（消费确认：PENDING → CONSUMED）。调用方需保证 ids 非空。 */
    @Query("UPDATE wake_queue SET status = :status WHERE wake_id IN (:ids)")
    suspend fun updateStatus(ids: List<String>, status: String)

    @Query("DELETE FROM wake_queue WHERE wake_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** 会话删除时级联清理其唤醒项（全局项 session_id='' 不在此列，保留）。 */
    @Query("DELETE FROM wake_queue WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
