package com.R.codecore.feature.agent.domain.tool.memory

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.memory.MemoryEdit
import com.R.codecore.feature.agent.domain.memory.MemoryEditResult
import com.R.codecore.feature.agent.domain.memory.MemoryRepository
import com.R.codecore.feature.agent.domain.memory.MemoryScope
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"

        /** M-1：单条记忆大小上限（字符数），防止超大记忆撑爆系统提示。 */
        const val MAX_CONTENT_CHARS = 50 * 1024
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    /** L3 结构化结果协议：产出 state.memory.updated 类型（记忆变更后广播，触发上下文增量刷新）。 */
    override val provides = setOf("state.memory.updated")

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "管理 AI 的长期记忆。当用户告知新的偏好、项目约定、架构设计，或者你发现了有价值的规律时，使用此工具将其永久记录。"

    /** L7 事件自声明：仅写操作（save/edit/delete）广播 state.memory.updated；read/list 不触发。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val action = (toolCall.arguments["action"] as? JsonPrimitive)?.contentOrNull
        if (action !in setOf("save", "edit", "delete")) return null
        val memoryKey = (toolCall.arguments["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val summary = (result as? ToolResult.Success)?.data
            ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
        return ToolEvent.StateMemoryUpdated(memoryKey = memoryKey, summary = summary, sessionId = context.sessionId)
    }

    /** edits 数组单个元素的结构，供 function-calling 的 items schema，语义与 editFile 一致。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与记忆当前正文精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：read=读取记忆正文；save=保存记忆（创建或全量覆盖）；edit=对已有记忆正文做局部编辑；delete=删除记忆；list=列出所有记忆摘要",
            enum = listOf("read", "save", "edit", "delete", "list"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "记忆的短名称（作为文件名，如 conventions）。list 操作可省略。",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "一句话摘要（save 可选，缺省时自动从正文首行生成）。",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "记忆的详细正文（Markdown 格式，save 必填）。",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "edit 操作要应用的编辑列表，按顺序依次生效，每个编辑在前一个的结果上匹配。" +
                "单处修改也用只含一个元素的数组。每个元素：{old_string, new_string, replace_all?}。",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "作用域：project=当前项目专属；global=跨项目通用。默认为 project。",
            enum = listOf("project", "global"),
            required = false
        ),
        // M-3：save 的 tags 标签数组
        "tags" to ToolParameter(
            name = "tags",
            type = ParameterType.ARRAY,
            description = "save 可选：标签数组（字符串），用于按主题分类记忆，如 [\"android\", \"build\"]；list 时可按 tag 过滤。",
            required = false
        ),
        // M-3：list 的 tag 过滤
        "tag" to ToolParameter(
            name = "tag",
            type = ParameterType.STRING,
            description = "list 可选：只列出包含该标签的记忆。省略则列出全部。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val scope = if (scopeStr == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot, args["tag"]?.jsonPrimitive?.contentOrNull?.trim())
                "read" -> handleRead(memoryName, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot)
                else -> ToolResult.Error("不支持的操作: $action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory 工具执行失败: ${e.message}", e)
            ToolResult.Error("记忆操作失败: ${e.message}")
        }
    }

    private fun handleList(projectRoot: String?, tag: String?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot)
        // M-3：按 tag 过滤（忽略大小写）
        val filtered = if (tag.isNullOrBlank()) memories
        else memories.filter { it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
        if (filtered.isEmpty()) {
            return if (tag.isNullOrBlank()) ToolResult.Success(JsonPrimitive("当前没有任何记忆。"))
            else ToolResult.Success(JsonPrimitive("当前没有带标签「$tag」的记忆。"))
        }

        // M-4：展示访问次数，便于 AI 判断常用记忆
        val list = filtered.joinToString("\n") {
            val tagNote = if (it.tags.isNotEmpty()) " [${it.tags.joinToString(", ")}]" else ""
            "- ${it.name} (${it.scope.name.lowercase()}，访问 ${it.accessCount} 次): ${it.description}$tagNote"
        }
        return ToolResult.Success(JsonPrimitive("当前记忆列表：\n$list"))
    }

    private fun handleRead(name: String?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("read 操作需要 name 参数", "MISSING_NAME")
        val content = memoryRepository.loadContent(name, projectRoot)
            ?: return ToolResult.Error("未找到记忆「$name」", "MEMORY_NOT_FOUND")
        // M-4：read 命中即递增访问次数（静默，失败不影响返回内容）
        memoryRepository.recordAccess(name, projectRoot)
        return ToolResult.Success(JsonPrimitive(content))
    }

    private fun handleSave(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("save 操作需要 name 参数", "MISSING_NAME")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("save 操作需要 content 参数", "MISSING_CONTENT")

        // M-1：单条大小上限，超出给出明确报错并引导拆分/精简。
        if (content.length > MAX_CONTENT_CHARS) {
            return ToolResult.Error(
                "记忆正文超过单条上限 $MAX_CONTENT_CHARS 字符（当前 ${content.length} 字符）。" +
                    "请按主题拆分为多条记忆分别保存，或精简冗余内容后再试。",
                "MEMORY_TOO_LARGE"
            )
        }

        // M-3：解析 tags 标签数组
        val tags = parseTags(args)

        // M-2：description 缺省时自动从正文生成一句话摘要，不再强制必填
        val explicitDescription = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
        val description = explicitDescription ?: autoSummary(content)
        val autoSummarized = explicitDescription == null

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法保存项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        val success = memoryRepository.saveMemory(name, description, content, scope, projectRoot, tags)
        return if (success) {
            val summaryNote = if (autoSummarized) "\ndescription 未提供，已自动生成摘要：$description" else ""
            val tagNote = if (tags.isNotEmpty()) "\n已标记标签：${tags.joinToString(", ")}" else ""
            ToolResult.Success(JsonPrimitive(
                "已成功保存记忆「$name」到 ${scope.name.lowercase()} 作用域。它将在下一次会话启动时自动注入摘要。当前会话若需立即使用，请通过 read 操作读取。$summaryNote$tagNote"
            ))
        } else {
            ToolResult.Error("保存记忆失败，请查看日志。", "SAVE_FAILED")
        }
    }

    /** M-3：解析 save 的 tags 参数（JsonArray → 清洗去重后的字符串列表，上限 10 个防异常参数）。 */
    private fun parseTags(args: Map<String, JsonElement>): List<String> {
        val arr = args["tags"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .distinct()
            .take(10)
    }

    /**
     * M-2：轻量启发式自动摘要。因工具层无直接 LLM 调用通道，采用启发式而非引入重型基础设施：
     * 取 content 去除空行后的第一个非空行；content 可能是 Markdown，先去除行首的 #、-、* 等标记；
     * 若该行超过 60 字符则截断到 60 字符并追加省略号；content 为空则返回占位文案。
     */
    private fun autoSummary(content: String): String {
        val firstLine = content.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>') }
            .firstOrNull { it.isNotEmpty() }
            ?: return "（无正文）"
        return if (firstLine.length > 60) firstLine.take(60) + "…" else firstLine
    }

    private fun handleEdit(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("edit 操作需要 name 参数", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("edit 操作需要 edits 参数：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("当前未选择工作区，无法编辑项目级记忆。请改用 scope=global", "NO_WORKSPACE")
        }

        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot)) {
            is MemoryEditResult.Success ->
                ToolResult.Success(JsonPrimitive("已成功编辑记忆「$name」的正文（${scope.name.lowercase()} 作用域）。"))
            is MemoryEditResult.NotFound ->
                ToolResult.Error("未找到记忆「${result.name}」，请先通过 save 创建，或确认 name 与作用域是否正确。", "MEMORY_NOT_FOUND")
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("delete 操作需要 name 参数", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            ToolResult.Success(JsonPrimitive("已成功删除 ${scope.name.lowercase()} 作用域的记忆「$name」。"))
        } else {
            ToolResult.Error("删除失败，记忆「$name」可能不存在于该作用域。", "DELETE_FAILED")
        }
    }
}
