package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY updatedAtMs DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY updatedAtMs DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 会话总数。用于数据完整性哨兵（DataSentinel）区分「全新安装」与「数据丢失」。 */
    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun count(): Int

    /** 分页读取（keyset：按 updatedAtMs,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAtMs > :lastUpdatedAtMs OR (updatedAtMs = :lastUpdatedAtMs AND id > :lastId) ORDER BY updatedAtMs ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAtMs: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    /** 工作区重命名后批量更新其下所有会话的绑定路径（会话与工作区一对一绑定，路径随之迁移）。 */
    @Query("UPDATE chat_sessions SET workspacePath = :newPath WHERE workspacePath = :oldPath")
    suspend fun updateWorkspacePath(oldPath: String, newPath: String)

    @Query("UPDATE chat_sessions SET updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun touch(id: String, updatedAtMs: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)
}
