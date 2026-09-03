package com.core.deepcode.feature.agent.domain.mcp.server

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.mcp.McpToolDescriptor
import com.core.deepcode.feature.agent.domain.permission.PermissionChoice
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionManager
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolRegistry
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * AgentTool ↔ MCP tool 双向映射（服务端侧）。
 *
 * - [listTools]：把 `ToolRegistry` 中**允许暴露**的工具转成 MCP tool 描述
 *   （复用 [AgentTool.toJsonSchema] 生成 inputSchema）。
 * - [callTool]：执行一次远程调用，走权限审批（复用 [ToolPermissionManager]），
 *   结果封装为 MCP `content` 块。
 *
 * 安全模型：
 * - 黑名单工具不暴露（[McpServerSecurity.isExposed]）；
 * - `requireApproval=true`（默认）时，即使工具 AUTO_APPROVE 也进入审批弹窗；
 *   关闭时仅对 ASK 策略工具弹窗（与本地行为一致）。
 */
class AgentToolMcpAdapter(
    private val toolRegistry: ToolRegistry,
    private val permissionManager: ToolPermissionManager,
    private val requireApproval: Boolean
) {
    private companion object {
        const val TAG = "McpServerAdapter"
        const val CALL_ID_PREFIX = "mcp-remote"
    }

    /** 对外暴露的工具描述列表（剔除黑名单）。 */
    fun listTools(): List<McpToolDescriptor> {
        return toolRegistry.getAvailableTools()
            .filter { McpServerSecurity.isExposed(it.name) }
            .map { tool ->
                McpToolDescriptor(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = toJsonElement(tool.toJsonSchema()) as? JsonObject
                )
            }
    }

    /**
     * 执行一次 `tools/call`，返回 MCP 结果对象（含 `content` 与 `isError`）。
     *
     * @param arguments MCP 客户端传入的参数（JSON-RPC params.arguments）。
     */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val tool = toolRegistry.getTool(name)
            ?: return errorResult("TOOL_NOT_FOUND", "未知工具: $name")
        if (!McpServerSecurity.isExposed(name)) {
            return errorResult("TOOL_NOT_EXPOSED", "工具 $name 未开放给远程 MCP 客户端")
        }

        val choice = approve(tool, arguments)
        if (choice == PermissionChoice.REJECT) {
            FileLogger.w(TAG, "远程调用 $name 被用户拒绝")
            return errorResult("PERMISSION_DENIED", "用户拒绝远程调用工具 $name")
        }

        val result = runCatching { tool.execute(arguments) }
            .getOrElse { e ->
                FileLogger.e(TAG, "远程调用 $name 执行异常", e)
                return errorResult("TOOL_EXECUTION_FAILED", e.message ?: "工具执行异常")
            }
        return wrapResult(name, result)
    }

    /** 权限审批：requireApproval 且工具非 AUTO_APPROVE 时弹窗；否则按策略放行。 */
    private suspend fun approve(tool: AgentTool, args: JsonObject): PermissionChoice {
        val autoApprove = tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE
        if (autoApprove && !requireApproval) return PermissionChoice.ONCE

        val preview = args.toString().take(500)
        val request = tool.buildPermissionRequest(
            callId = "$CALL_ID_PREFIX-${System.currentTimeMillis()}",
            args = args,
            argsPreview = preview
        ).copy(
            title = "外部 MCP 调用 · ${tool.name}",
            summary = "外部 MCP 客户端请求执行 ${tool.name}",
            // sessionId=null：审批卡对所有会话可见（AI 聊天面板会展示），
            // 避免远程请求因「会话不匹配」被 UI 过滤掉导致永远无法审批。
            sessionId = null
        )
        return permissionManager.awaitApproval(sessionId = null, request = request)
    }

    private fun wrapResult(toolName: String, result: ToolResult): JsonObject = when (result) {
        is ToolResult.Success -> successContent(result.data.toString())
        is ToolResult.Partial -> successContent(
            buildString {
                if (result.message.isNotBlank()) {
                    append(result.message)
                    append('\n')
                }
                append(result.data.toString())
            }
        )
        is ToolResult.Error -> {
            FileLogger.w(TAG, "远程调用 $toolName 返回错误 [${result.code}] ${result.message}")
            errorResult(result.code, result.message)
        }
    }

    private fun successContent(text: String): JsonObject = buildJsonObject {
        put("isError", JsonPrimitive(false))
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }

    private fun errorResult(code: String, message: String): JsonObject = buildJsonObject {
        put("isError", JsonPrimitive(true))
        put("code", code)
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", "Error[$code]: $message")
            }
        }
    }

    /** 把 AgentTool.toJsonSchema() 的 Map<String, Any> 转成 kotlinx JsonElement。 */
    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
