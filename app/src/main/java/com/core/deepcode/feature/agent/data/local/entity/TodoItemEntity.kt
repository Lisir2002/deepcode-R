package com.core.deepcode.feature.agent.data.local.entity

import com.core.deepcode.core.util.EnumSafe
import com.core.deepcode.feature.agent.domain.model.TodoItem
import com.core.deepcode.feature.agent.domain.model.TodoStatus

data class TodoItemEntity(
     val id: String,
    val sessionId: String,
    val subject: String,
    
    val description: String = "",
    
    val status: String = "PENDING",
    
    val priority: Int = 0,
    
    val order: Int = 0,
    /** 创建时间毫秒（RC68 统一 Ms 后缀）。 */
    val createdAtMs: Long,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun toDomain(): TodoItem = TodoItem(
        id = id,
        sessionId = sessionId,
        subject = subject,
        description = description,
        status = EnumSafe.valueOf(status, TodoStatus.PENDING, tag = "TodoItemEntity.status"),
        priority = priority,
        order = order,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs
    )

    companion object {
        fun fromDomain(item: TodoItem): TodoItemEntity = TodoItemEntity(
            id = item.id,
            sessionId = item.sessionId,
            subject = item.subject,
            description = item.description,
            status = item.status.name,
            priority = item.priority,
            order = item.order,
            createdAtMs = item.createdAt,
            updatedAtMs = item.updatedAt
        )
    }
}
