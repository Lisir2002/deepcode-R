package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

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
