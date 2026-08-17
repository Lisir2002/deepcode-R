package com.R.codecore.feature.agent.domain.tool.todo

import androidx.room.withTransaction
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.TodoItemDao
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.agent.data.local.entity.TodoItemEntity
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.model.TodoItem
import com.R.codecore.feature.agent.domain.model.TodoStatus
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * 管理 AI Agent 当前会话的任务清单（待办列表）。
 *
 * 工具采用快照式接口：每次传入当前完整 items 列表，工具用它替换会话里的待办清单。
 * AI 不需要查询或记忆 todo_id，也不需要区分创建、更新、删除动作。
 */
class TodoTool @Inject constructor(
    private val todoItemDao: TodoItemDao,
    private val database: AgentDatabase
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TodoTool"
    }

    override val name = "todo"
    override val description = "用当前完整 items 列表替换会话任务清单。只需提交完整列表，" +
        "无需 action、todo_id 或单项更新。items 可为空数组表示清空；每项为 " +
        "{subject, description, status, priority}。status 可为 pending、in_progress、completed。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)

    /** L3 结构化结果协议：产出 todo.list 类型（完整待办列表）。 */
    override val provides = setOf("todo.list")

    /** items 数组中单个待办项的 JSON Schema */
    private val todoItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "subject" to mapOf(
                "type" to "string",
                "description" to "简短的待办标题（祈使句，如「分析项目结构」）"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "详细说明（可选）"
            ),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("pending", "in_progress", "completed"),
                "description" to "状态，默认 pending。in_progress 表示正在处理，completed 表示已完成。"
            ),
            "priority" to mapOf(
                "type" to "integer",
                "description" to "优先级，0=普通，越大越优先"
            )
        ),
        "required" to listOf("subject")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "items" to ToolParameter(
            name = "items",
            type = ParameterType.ARRAY,
            description = "当前完整待办列表。传空数组会清空任务清单；每项为含 subject 的对象。",
            required = true,
            itemsSchema = todoItemSchema
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")

        return try {
            val result = replaceTodos(args, sessionId)
            // L2 共享会话状态：变更成功后刷新待办快照，供 prompt 构建与增量发布消费。
            if (result is ToolResult.Success) {
                refreshSnapshot(context, sessionId)
            }
            result
        } catch (e: Exception) {
            FileLogger.e(TAG, "todo 工具执行失败: ${e.message}", e)
            ToolResult.Error("待办操作失败: ${e.message}")
        }
    }

    /** 刷新 [AgentContext.sessionState] 的待办快照（L2 共享会话状态）。 */
    private suspend fun refreshSnapshot(context: AgentContext, sessionId: String) {
        val items = todoItemDao.getBySessionOnce(sessionId)
        context.sessionState?.todoSnapshot = items.map { entity ->
            TodoItem(
                id = entity.id,
                sessionId = sessionId,
                subject = entity.subject,
                description = entity.description,
                status = runCatching { TodoStatus.valueOf(entity.status) }.getOrDefault(TodoStatus.PENDING),
                priority = entity.priority,
                order = entity.order,
                createdAt = entity.createdAtMs,
                updatedAt = entity.updatedAtMs
            )
        }
    }

    private suspend fun replaceTodos(args: Map<String, JsonElement>, sessionId: String): ToolResult {
        val itemElements = args["items"] as? JsonArray
            ?: return ToolResult.Error("需要 items 数组", "MISSING_ITEMS")
        val existingBySubject = todoItemDao.getBySessionOnce(sessionId)
            .groupBy { normalizeSubject(it.subject) }
            .mapValues { (_, items) -> items.toMutableList() }
        val now = System.currentTimeMillis()
        val entities = mutableListOf<TodoItemEntity>()

        for ((idx, element) in itemElements.withIndex()) {
            // D-3：先做 status 显式校验，非法值时返回明确错误码，避免被外层吞成模糊错误
            if (element is JsonObject) {
                val statusRaw = element["status"]?.jsonPrimitive?.contentOrNull
                if (statusRaw != null && parseStatus(statusRaw) == null) {
                    return ToolResult.Error(
                        "第 ${idx + 1} 项的 status「$statusRaw」无效，合法值：pending / in_progress / completed",
                        "INVALID_STATUS"
                    )
                }
            }

            val draft = parseTodoDraft(element, idx) ?: return ToolResult.Error(
                "第 ${idx + 1} 项需要字符串标题或含 subject 的对象",
                "INVALID_ITEM"
            )
            val previous = existingBySubject[normalizeSubject(draft.subject)]?.removeFirstOrNull()

            entities.add(TodoItemEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                subject = draft.subject,
                description = draft.description,
                status = draft.status.name,
                priority = draft.priority,
                order = idx,
                createdAtMs = previous?.createdAtMs ?: now,
                updatedAtMs = now
            ))
        }

        // D-1：delete + upsert 包 Room 事务，避免中间失败丢全部待办
        database.withTransaction {
            todoItemDao.deleteBySession(sessionId)
            if (entities.isNotEmpty()) {
                todoItemDao.upsertAll(entities)
            }
        }
        FileLogger.d(TAG, "todo replace: 同步了 ${entities.size} 项待办")

        return listTodos(sessionId)
    }

    private suspend fun listTodos(sessionId: String): ToolResult {
        val items = todoItemDao.getBySessionOnce(sessionId)
        val total = items.size
        val completed = items.count { it.status == "COMPLETED" }

        return ToolResult.Success(JsonObject(mapOf(
            "total" to JsonPrimitive(total),
            "completed" to JsonPrimitive(completed),
            "items" to kotlinx.serialization.json.JsonArray(
                items.map { entity ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(entity.id),
                        "subject" to JsonPrimitive(entity.subject),
                        "description" to JsonPrimitive(entity.description),
                        "status" to JsonPrimitive(entity.status.lowercase()),
                        "priority" to JsonPrimitive(entity.priority),
                        "order" to JsonPrimitive(entity.order),
                        // D-2：回传创建/更新时间，供 AI 判断待办新鲜度与完成时序
                        "created_at" to JsonPrimitive(entity.createdAtMs),
                        "updated_at" to JsonPrimitive(entity.updatedAtMs)
                    ))
                }
            )
        )))
    }

    private fun parseTodoDraft(element: JsonElement, index: Int): TodoDraft? {
        if (element is JsonPrimitive) {
            val subject = element.contentOrNull?.trim().orEmpty()
            return if (subject.isBlank()) null else TodoDraft(subject = subject)
        }

        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val subject = obj["subject"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (subject.isBlank()) return null

        // D-3：status 已在 replaceTodos 预校验，此处不再抛异常；异常情况下安全回退 pending
        val status = parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull) ?: TodoStatus.PENDING

        return TodoDraft(
            subject = subject,
            description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            status = status,
            priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun parseStatus(raw: String?): TodoStatus? {
        val normalized = raw
            ?.trim()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.uppercase()
            ?: return TodoStatus.PENDING
        return runCatching { TodoStatus.valueOf(normalized) }.getOrNull()
    }

    private fun normalizeSubject(subject: String): String {
        return subject.trim().lowercase()
    }
}

private data class TodoDraft(
    val subject: String,
    val description: String = "",
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: Int = 0
)
