package com.R.codecore.feature.agent.data.local.dao

import com.R.codecore.core.util.EnumSafe
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.model.ChatSession

/**
 * 会话 + 消息条数聚合投影（Room POJO，非实体）。
 *
 * 供对话列表使用：在会话基础字段上追加该会话的消息条数，
 * 用于列表项「N 条消息」展示，避免 UI 层逐一查消息量。
 * 设计文档 chat-session-list-refactor-design A1。
 */
data class ChatSessionWithCount(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val workspacePath: String,
    val mode: String,
    /** 该会话消息条数（LEFT JOIN COUNT，空会话为 0）。 */
    val messageCount: Int
) {
    /** 转为领域模型（供侧边栏长按菜单/删除确认等基于 [ChatSession] 的既有链路复用）。 */
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs,
        workspacePath = workspacePath,
        mode = EnumSafe.valueOf(mode, AgentMode.BUILD, tag = "ChatSessionWithCount.mode")
    )
}
