package com.R.codecore.feature.t2i.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.R.codecore.feature.t2i.data.local.entity.T2ITaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface T2ITaskDao {

    @Query("SELECT * FROM t2i_tasks WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    fun getTasksForSession(sessionId: String): Flow<List<T2ITaskEntity>>

    @Query("SELECT * FROM t2i_tasks WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    suspend fun getTasksForSessionOnce(sessionId: String): List<T2ITaskEntity>

    @Query("SELECT * FROM t2i_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): T2ITaskEntity?

    @Query("SELECT * FROM t2i_tasks WHERE messageId = :messageId")
    suspend fun getTaskByMessageId(messageId: String): T2ITaskEntity?

    /**
     * 崩溃恢复扫描：冷启动时把「PENDING / RUNNING / PENDING_RETRY 且距今超过 30 分钟」的悬垂任务找出来。
     * @param cutoffMs 通常 = System.currentTimeMillis() - 30 * 60 * 1000
     */
    @Query("SELECT * FROM t2i_tasks WHERE status IN ('PENDING','RUNNING','PENDING_RETRY') AND updatedAtMs < :cutoffMs ORDER BY updatedAtMs ASC")
    suspend fun getDanglingTasks(cutoffMs: Long): List<T2ITaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: T2ITaskEntity)

    @Update
    suspend fun updateTask(task: T2ITaskEntity)

    @Query("UPDATE t2i_tasks SET status = :status, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAtMs: Long)

    @Query("UPDATE t2i_tasks SET status = :status, progressPercent = :progress, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateStatusAndProgress(id: String, status: String, progress: Int, updatedAtMs: Long)

    @Query("UPDATE t2i_tasks SET status = 'SUCCESS', imagePath = :imagePath, thumbnailPath = :thumbnailPath, progressPercent = 100, completedAtMs = :completedAtMs, updatedAtMs = :completedAtMs WHERE id = :id")
    suspend fun markSuccess(id: String, imagePath: String, thumbnailPath: String, completedAtMs: Long)

    @Query("UPDATE t2i_tasks SET status = :finalStatus, errorCode = :errorCode, errorMessage = :errorMessage, retryCount = :retryCount, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun markFailedOrRetry(
        id: String,
        finalStatus: String,
        errorCode: String,
        errorMessage: String,
        retryCount: Int,
        updatedAtMs: Long
    )

    @Query("UPDATE t2i_tasks SET remoteTaskId = :remoteTaskId, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun setRemoteTaskId(id: String, remoteTaskId: String, updatedAtMs: Long)

    @Query("UPDATE t2i_tasks SET permissionDecision = :decision, quotaDeductedTokens = :deducted, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun setPermissionDecision(id: String, decision: String, deducted: Int, updatedAtMs: Long)

    @Query("DELETE FROM t2i_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    /** 按会话删：用户清会话时顺带清理 T2I 任务行（但文件系统图片单独交由 CleanupWorker 删）。 */
    @Query("DELETE FROM t2i_tasks WHERE sessionId = :sessionId")
    suspend fun deleteTasksForSession(sessionId: String)

    /**
     * 配额统计（权限引擎 P2 日额度 / P3 会话额度）。
     * 只算 SUCCESS + FAILED（因为 FAILED 的也可能扣了额度且未退款；退款由仓储在失败路径里回加 quotaDeductedTokens 到对应额度池）。
     */
    @Query("SELECT COALESCE(SUM(quotaDeductedTokens), 0) FROM t2i_tasks WHERE createdAtMs >= :dayStartMs AND status IN ('SUCCESS','FAILED')")
    suspend fun sumDeductedTokensSince(dayStartMs: Long): Int

    @Query("SELECT COALESCE(SUM(quotaDeductedTokens), 0) FROM t2i_tasks WHERE sessionId = :sessionId AND status IN ('SUCCESS','FAILED')")
    suspend fun sumDeductedTokensForSession(sessionId: String): Int

    /** 统计单日成功生成的图片张数（用于 UI 统计卡片 + 渐进保护阈值 P5）。 */
    @Query("SELECT COUNT(*) FROM t2i_tasks WHERE createdAtMs >= :dayStartMs AND status = 'SUCCESS'")
    suspend fun countSuccessfulImagesSince(dayStartMs: Long): Int
}
