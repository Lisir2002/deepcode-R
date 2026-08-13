package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.deep.rcode.core.util.EnumSafe
import com.deep.rcode.feature.agent.domain.model.TodoItem
import com.deep.rcode.feature.agent.domain.model.TodoStatus

@Entity(
    tableName = "todo_items",
    indices = [
        // RC91：与迁移 38 创建的 index_todo_items_sessionId_order_priority 完全对齐（含 DESC 排序）
        Index(
            value = ["sessionId", "order", "priority"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]
        ),
        // 与迁移 38 创建的 index_todo_items_createdAtMs 对齐
        Index(value = ["createdAtMs"])
    ]
)
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val subject: String,
    @ColumnInfo(defaultValue = "''")
    val description: String = "",
    @ColumnInfo(defaultValue = "'PENDING'")
    val status: String = "PENDING",
    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0,
    @ColumnInfo(defaultValue = "0")
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
