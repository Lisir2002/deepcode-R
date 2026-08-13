package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.deep.rcode.core.util.EnumSafe
import com.deep.rcode.feature.agent.domain.model.AgentMode
import com.deep.rcode.feature.agent.domain.model.ChatSession
import com.deep.rcode.feature.agent.domain.model.ReasoningEffort

@Entity(
    tableName = "chat_sessions",
    indices = [
        // RC91：与迁移 38 创建的 index_chat_sessions_workspacePath_updatedAtMs 完全对齐（含 DESC 排序）
        Index(
            value = ["workspacePath", "updatedAtMs"],
            orders = [Index.Order.ASC, Index.Order.DESC]
        )
    ]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** 创建时间毫秒（RC68 统一 Ms 后缀，避免“秒/毫秒”歧义）。 */
    val createdAtMs: Long,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "''")
    val workspacePath: String = "",
    @ColumnInfo(defaultValue = "'BUILD'")
    val mode: String = AgentMode.BUILD.name,
    @ColumnInfo(defaultValue = "'MEDIUM'")
    val reasoningEffort: String = ReasoningEffort.MEDIUM.name,
    val providerId: String? = null,
    val model: String? = null,
    @ColumnInfo(defaultValue = "0")
    val totalInputTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val totalOutputTokens: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val lastInputTokens: Int = 0
) {
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs,
        workspacePath = workspacePath,
        mode = EnumSafe.valueOf(mode, AgentMode.BUILD, tag = "ChatSessionEntity.mode"),
        reasoningEffort = EnumSafe.valueOf(reasoningEffort, ReasoningEffort.MEDIUM, tag = "ChatSessionEntity.reasoningEffort"),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAtMs = session.createdAt,
            updatedAtMs = session.updatedAt,
            workspacePath = session.workspacePath,
            mode = session.mode.name,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens
        )
    }
}
