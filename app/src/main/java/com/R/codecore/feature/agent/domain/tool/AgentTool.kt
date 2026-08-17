package com.R.codecore.feature.agent.domain.tool

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class ToolResult {
    @Serializable
    @SerialName("success")
    data class Success(val data: JsonElement) : ToolResult()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val code: String = "UNKNOWN") : ToolResult()

    @Serializable
    @SerialName("partial")
    data class Partial(val data: JsonElement, val message: String) : ToolResult()
}

private val ToolResultTransportJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

fun ToolResult.toTransportString(): String {
    return ToolResultTransportJson.encodeToString(this)
}

/**
 * L3 错误分类：驱动调度器自动重试与用户介入决策。
 * 现有错误码按前缀推断归类（MISSING_*→INVALID_ARGS、FILE_NOT_FOUND→NOT_FOUND 等），
 * 工具也可在 [ToolResult.Error] 中显式声明 errorClass。
 */
enum class ToolErrorClass {
    /** 参数缺失/非法：MISSING_*、EMPTY_OLD_STRING、NO_OP、UNSUPPORTED_ACTION */
    INVALID_ARGS,
    /** 资源不存在：FILE_NOT_FOUND、MEMORY_NOT_FOUND、SKILL_NOT_FOUND、TOOL_NOT_FOUND */
    NOT_FOUND,
    /** 权限/状态：NO_WORKSPACE、NO_SESSION、SKILL_DISABLED、SKILL_MISSING_DEP */
    PERMISSION_STATE,
    /** 网络/外部服务：HTTP 错误、抓取失败、搜索失败 */
    NETWORK,
    /** 瞬时故障可重试：EDIT_ERROR、SAVE_FAILED、TOOL_EXECUTION_FAILED */
    TRANSIENT,
    /** 不可恢复：内部错误、未知异常 */
    FATAL
}

/**
 * L3 重试策略：默认 NETWORK/TRANSIENT 自动重试（指数退避 + 抖动，上限 3 次），
 * 其余分类不重试直接返回给模型。工具可通过 [AgentTool.retryPolicy] 覆盖。
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val retryable: Set<ToolErrorClass> = setOf(ToolErrorClass.NETWORK, ToolErrorClass.TRANSIENT)
)

/**
 * 错误码 → 分类推断：显式 errorClass 优先，其次按错误码前缀推断，最后兜底 FATAL。
 */
fun classifyError(code: String?, explicit: ToolErrorClass? = null): ToolErrorClass {
    explicit?.let { return it }
    val c = code?.uppercase() ?: return ToolErrorClass.FATAL
    return when {
        c.startsWith("MISSING_") || c == "EMPTY_OLD_STRING" || c == "NO_OP" ||
            c == "UNSUPPORTED_ACTION" || c == "INVALID_ARGS" -> ToolErrorClass.INVALID_ARGS
        c.endsWith("_NOT_FOUND") || c == "TOOL_NOT_FOUND" -> ToolErrorClass.NOT_FOUND
        c.startsWith("NO_") || c.startsWith("SKILL_") && c.contains("DISABLED") ||
            c == "SKILL_MISSING_DEP" -> ToolErrorClass.PERMISSION_STATE
        c.startsWith("HTTP_") || c == "NETWORK_ERROR" || c.contains("FETCH") ||
            c.contains("SEARCH") -> ToolErrorClass.NETWORK
        c.endsWith("_FAILED") || c == "EDIT_ERROR" || c == "TOOL_EXECUTION_FAILED" ||
            c == "SAVE_FAILED" -> ToolErrorClass.TRANSIENT
        else -> ToolErrorClass.FATAL
    }
}

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    /**
     * 当 [type] 为 [ParameterType.ARRAY] 时，描述数组元素的 JSON Schema（原样并入
     * function-calling 的 items 字段）。例如元素是对象时传
     * `{"type":"object","properties":{...},"required":[...]}`。为空则不输出 items。
     */
    val itemsSchema: Map<String, Any>? = null
)

enum class ParameterType {
    STRING, INTEGER, BOOLEAN, ARRAY, OBJECT
}

enum class ToolPermissionPolicy {
    AUTO_APPROVE, ASK
}

enum class ToolCapability {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_COMMANDS,
    NETWORK_READ,
    NETWORK_WRITE,
    READ_AGENT_CONFIG,
    MODIFY_AGENT_CONFIG,
    MODIFY_CONTAINER_ENV,
    USER_INTERACTION,
    MODIFY_SESSION_STATE,
    MODIFY_TODO_STATE,
    EXTERNAL_TOOL,
    /** C.4.4 ZTH：加载 MCP 第三方服务（install 动态 server）。 */
    LOAD_MCP_SERVER,
    /** C.4.4 ZTH：加载 Skill 文件（执行 .md/.skill 外部规则）。 */
    LOAD_SKILL_BUNDLE,
    /** C.4.8 ZTH：修改用户已确认 sentinel / 保留策略 / 硬约束。 */
    MODIFY_USER_CONFIRMED_STATE
}

data class PendingToolPermission(
    val id: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val details: String,
    val argsPreview: String,
    /**
     * 「始终允许」会记忆的模式列表（shell 命令为命令前缀：子命令分发器记 `git pull` 这类带子命令的
     * 前缀，普通程序记 `cat`/`ls`；非 shell 工具为 `*`）。
     * 为空表示该调用不可记忆（命令不可静态判定），UI 应禁用「始终允许」、只留单次放行。
     * 由 [com.R.codecore.feature.agent.domain.permission.ToolPermissionPolicyEngine] 评估后填入。
     */
    val rememberablePatterns: List<String> = emptyList(),
    val rememberDisabledReason: String? = null,
    /**
     * 发起该权限请求的 AI 会话 id。用于把弹窗绑定到具体会话：UI 只展示当前会话的待决请求，
     * 切换/停止会话时按会话清理，避免「对话已结束仍弹出确认卡」。
     */
    val sessionId: String? = null
)

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()

    /**
     * L3 结构化结果协议：本工具产出的输出类型（如 "file.read"、"todo.list"）。
     * 由 [com.R.codecore.feature.agent.domain.tool.ToolResultTypeRegistry] 统一登记 schema。
     */
    open val provides: Set<String> = emptySet()

    /**
     * L3 结构化结果协议：本工具消费的输出类型（按类型直连消费其他工具的产物）。
     */
    open val consumes: Set<String> = emptySet()

    /**
     * L4 依赖感知调度：本工具依赖的其他工具名（按工具名声明依赖，调度器构建依赖图）。
     */
    open val dependsOn: Set<String> = emptySet()

    /**
     * L3 错误分类重试：覆盖默认重试策略。为 null 时按 [ToolErrorClass] 分类默认处理
     * （NETWORK/TRANSIENT 自动重试，其余不重试）。
     */
    open val retryPolicy: RetryPolicy? = null

    /**
     * L7 事件总线：本工具声明式订阅的事件类型（如 "file.edited"、"todo.updated"）。
     */
    open val subscribedEvents: Set<String> = emptySet()

    open fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return capabilities
    }

    abstract suspend fun execute(args: Map<String, JsonElement>): ToolResult

    open suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.R.codecore.feature.agent.domain.model.AgentContext): ToolResult {
        return execute(args)
    }
    open fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行工具",
            summary = "AI 请求执行 $name",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    fun toToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = name,
            description = description,
            parameters = parameters.values.map {
                ParameterDefinition(
                    name = it.name,
                    type = it.type.name.lowercase(),
                    description = it.description,
                    required = it.required
                )
            }
        )
    }

    /**
     * 生成符合 JSON Schema 的参数描述，用于真正传给大模型的 function-calling 接口
     * （OpenAI 的 function.parameters / Anthropic 的 input_schema）。
     *
     * 设为 open：MCP 等外部工具携带的是任意原始 inputSchema，无法用受限的 [ParameterType]
     * 枚举表达，需要覆写本方法直接透传服务端 schema。
     */
    open fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        parameters.forEach { (key, param) ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.name.lowercase()
            prop["description"] = param.description
            param.enum?.let { prop["enum"] = it }
            if (param.type == ParameterType.ARRAY) param.itemsSchema?.let { prop["items"] = it }
            properties[key] = prop
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ParameterDefinition>
)

data class ParameterDefinition(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * 可流式执行的工具：在最终结果产生之前，逐步 emit 过程输出（如命令的逐行 stdout），
 * 让 UI 能实时显示「执行过程」而非只有最终结果。
 *
 * 实现类应同时实现 [AgentTool.execute] 作为非流式兜底；工作流优先走 [executeStream]。
 */
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: com.R.codecore.feature.agent.domain.model.AgentContext): Flow<ToolStreamEvent>
}

/** 流式工具执行过程中产生的事件。 */
sealed class ToolStreamEvent {
    /** 一段新的过程输出（通常是一行）。 */
    data class Progress(val chunk: String) : ToolStreamEvent()
    /** 执行结束，附最终聚合结果（喂回模型用）。 */
    data class Completed(val result: ToolResult) : ToolStreamEvent()
}
