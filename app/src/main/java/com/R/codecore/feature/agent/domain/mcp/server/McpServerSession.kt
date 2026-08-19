package com.R.codecore.feature.agent.domain.mcp.server

import com.R.codecore.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP server 协议会话层：解析 JSON-RPC 报文并产出响应（纯 JVM 逻辑，可单测）。
 *
 * 实现 [builtin-mcp-server-design.md] §5.2 的四种方法：
 * - `initialize`：校验 protocolVersion，回 serverInfo + capabilities；
 * - `tools/list`：经 [AgentToolMcpAdapter] 拉取暴露工具；
 * - `tools/call`：执行远程调用（含权限审批），回 content 块；
 * - `ping`：回 `{}` 保活。
 * notifications/initialized 等通知无 id，返回 null → HTTP 层回 202 空响应。
 *
 * 响应以 [JsonObject] 直出（完整 JSON-RPC 报文），id 原样回显（支持数字/字符串），
 * 便于与外部客户端（Claude Desktop / Trae / Cursor）互通。
 */
class McpServerSession(
    private val adapter: AgentToolMcpAdapter,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private companion object {
        const val TAG = "McpServerSession"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SERVER_NAME = "rcodecore-mcp"
        const val SERVER_VERSION = "0.1.0"
    }

    /**
     * 处理一条请求/通知文本。
     *
     * @return 需要回给客户端的 JSON-RPC 响应（[JsonObject]）；通知类返回 null。
     */
    suspend fun handle(jsonText: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(jsonText) as JsonObject }
            .getOrElse { e ->
                FileLogger.w(TAG, "JSON-RPC 解析失败: ${e.message}")
                return errorResponse(JsonNull, -32700, "Parse error")
            }

        val id: JsonElement = root["id"] ?: JsonNull
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull

        if (method == null) {
            return errorResponse(id, -32600, "Invalid Request")
        }
        // 通知：无 id、服务端不应答。
        if (method.startsWith("notifications/")) {
            FileLogger.d(TAG, "收到通知 $method（不应答）")
            return null
        }

        return when (method) {
            "initialize" -> resultResponse(id, initializeResult())
            "ping" -> resultResponse(id, buildJsonObject { })
            "tools/list" -> handleListTools(id)
            "tools/call" -> handleCallTool(id, root)
            else -> {
                FileLogger.w(TAG, "未知方法 $method")
                errorResponse(id, -32601, "Method not found: $method")
            }
        }
    }

    // ── 各方法实现 ──────────────────────────────────────────────

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("tools") { }
        }
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", SERVER_VERSION)
        }
        put(
            "instructions",
            "R-CodeCore 内置 MCP 服务器：把设备的 Linux 编码后端（容器/文件/git/搜索/AI 工具）开放给外部 MCP 客户端。"
        )
    }

    private fun handleListTools(id: JsonElement): JsonObject {
        val tools = adapter.listTools()
        val result = buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        tool.description?.let { put("description", it) }
                        tool.inputSchema?.let { put("inputSchema", it) }
                    })
                }
            }
        }
        return resultResponse(id, result)
    }

    private suspend fun handleCallTool(id: JsonElement, root: JsonObject): JsonObject {
        val params = root["params"] as? JsonObject
            ?: return errorResponse(id, -32602, "Invalid params")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return errorResponse(id, -32602, "Missing tool name")
        val arguments = params["arguments"] as? JsonObject ?: buildJsonObject { }

        val callResult = adapter.callTool(name, arguments)
        return resultResponse(id, callResult)
    }

    // ── 响应构造 ────────────────────────────────────────────────

    private fun resultResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}
