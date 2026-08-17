package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** 一次性读取（非 Flow），用于跨请求重建上下文历史。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /**
     * DB-SHIELD-RC68 P0-2 边界语义明确化：<cutoff 之前（严格早于 cutoff）的消息删除。
     * 原方法名 deleteMessagesBeforeTimestamp 虽然语义正确，但之前有 deleteMessagesFromTimestamp(>=) / deleteMessagesAfterTimestamp(>) 一对
     * 长得像、但边界分别是「含 / 不含」，很容易被调用方混淆 → 回放重建上下文时多删一条或少删一条。
     * 保留旧方法名（@Deprecated）+ 新增严格命名的 Strict 版，过渡期一起存在，FUTURE 统一删 Deprecated。
     */
    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun deleteMessagesBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** 将指定会话中 cutoff 时间戳之前的所有消息标记为已压缩（isCompacted=1），不再参与上下文回放和 UI 展示。 */
    @Query("UPDATE agent_messages SET isCompacted = 1 WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun markMessagesCompactedBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** 编辑重发专用：把「该用户消息及其之后的所有消息」标记为已截断（isCompacted=1）。
     *  不删除（UI 仍可见），仅不再参与新一轮上下文回放。 */
    @Query("UPDATE agent_messages SET isCompacted = 1 WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun markMessagesCompactedInclusiveFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    /** @Deprecated 建议用 deleteMessagesInclusiveFrom (>=)。名字里的 "From" 边界（>=）与 After（>）容易搞混。 */
    @Deprecated(
        "边界歧义：请改用 deleteMessagesInclusiveFromTimestamp(>=) / deleteMessagesExclusiveAfterTimestamp(>)",
        ReplaceWith("deleteMessagesInclusiveFromTimestamp(sessionId, cutoffTimestamp)")
    )
    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** @Deprecated 建议用 deleteMessagesExclusiveAfterTimestamp (>). */
    @Deprecated(
        "边界歧义：请改用 deleteMessagesExclusiveAfterTimestamp(>)",
        ReplaceWith("deleteMessagesExclusiveAfterTimestamp(sessionId, cutoffTimestamp)")
    )
    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** RC68 严格命名版：时间戳「大于等于 cutoff」（含边界）的消息删除，用于「截断到某个用户点后重新生成」。 */
    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesInclusiveFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** RC68 严格命名版：时间戳「严格大于 cutoff」（不含边界）的消息删除，用于「保留最后一条锚点」等场景。 */
    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesExclusiveAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把残留的「执行中」工具行（content 以占位标记开头）批量收尾为「已中断」。
     * 用于冷启动：上次进程被杀时正在执行的工具不可能仍在跑，否则其占位行会永久显示转圈。
     * 返回受影响的行数。
     */
    @Query("UPDATE agent_messages SET content = :interruptedContent, isError = 1 WHERE role = :toolRole AND content LIKE :pendingPrefix")
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** 分页读取（keyset：按 timestamp,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>
}
