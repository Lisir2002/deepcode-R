package com.R.codecore.feature.agent.domain.mcp.server

import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionManager
import com.R.codecore.feature.agent.domain.tool.ToolRegistry
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 内置 MCP 服务器：协议会话层 + 工具映射 + 安全助手 的纯 JVM 单测。
 *
 * 覆盖 [McpServerSession] 的 initialize / tools/list / tools/call / ping / 通知 / 错误码，
 * [AgentToolMcpAdapter] 的黑名单过滤与结果包装，以及 [McpServerSecurity] 的 token/黑名单。
 * 使用 AUTO_APPROVE 工具 + requireApproval=false 避免审批挂起。
 */
class McpServerTest {

    private lateinit var registry: ToolRegistry
    private lateinit var permissionManager: ToolPermissionManager

    private class FakeAgentTool(
        override val name: String,
        override val description: String = "Fake tool",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            val echoed = (args["echo"] as? JsonPrimitive)?.content
            return ToolResult.Success(
                buildJsonObject { put("ok", echoed ?: "done") }
            )
        }
    }

    @Before
    fun setUp() {
        registry = ToolRegistry()
        permissionManager = ToolPermissionManager()
    }

    private fun session(requireApproval: Boolean = false): McpServerSession {
        val adapter = AgentToolMcpAdapter(
            toolRegistry = registry,
            permissionManager = permissionManager,
            requireApproval = requireApproval
        )
        return McpServerSession(adapter)
    }

    private fun initializeRequest(id: Int = 1): String =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}"""

    private fun resultOf(response: JsonObject): JsonObject =
        (response["result"] as? JsonObject) ?: JsonObject(emptyMap())

    // ── initialize ────────────────────────────────────────────────

    @Test
    fun initialize_returnsServerInfoAndCapabilities() = runBlocking {
        val response = session().handle(initializeRequest())!!

        assertEquals("2.0", (response["jsonrpc"] as JsonPrimitive).content)
        val result = resultOf(response)
        assertEquals("2025-06-18", (result["protocolVersion"] as JsonPrimitive).content)
        val serverInfo = result["serverInfo"] as JsonObject
        assertTrue((serverInfo["name"] as JsonPrimitive).content.contains("mcp"))
        assertNotNull(result["capabilities"])
    }

    // ── tools/list ────────────────────────────────────────────────

    @Test
    fun toolsList_returnsOnlyExposedTools() = runBlocking {
        registry.register("read_file", FakeAgentTool("read_file", "Read file"))
        registry.register("askUserQuestion", FakeAgentTool("askUserQuestion", "Ask user"))

        val response = session().handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
        val tools = (resultOf(response)["tools"] as? List<*>)!!

        assertEquals(1, tools.size)
        val tool = tools.first() as JsonObject
        assertEquals("read_file", (tool["name"] as JsonPrimitive).content)
        assertNotNull(tool["description"])
    }

    @Test
    fun adapterListTools_filtersBlacklist() {
        registry.register("browser", FakeAgentTool("browser"))
        registry.register("switchMode", FakeAgentTool("switchMode"))
        registry.register("safe_tool", FakeAgentTool("safe_tool"))

        val adapter = AgentToolMcpAdapter(registry, permissionManager, requireApproval = true)
        val names = adapter.listTools().map { it.name }

        assertFalse(names.contains("browser"))
        assertFalse(names.contains("switchMode"))
        assertTrue(names.contains("safe_tool"))
    }

    // ── tools/call ────────────────────────────────────────────────

    @Test
    fun toolsCall_success_returnsContent() = runBlocking {
        registry.register("read_file", FakeAgentTool("read_file", "Read file"))

        val response = session().handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_file","arguments":{"echo":"hi"}}}"""
        )!!
        val result = resultOf(response)

        assertEquals(JsonPrimitive(false), result["isError"])
        val content = (result["content"] as List<*>).first() as JsonObject
        assertTrue((content["text"] as JsonPrimitive).content.contains("hi"))
    }

    @Test
    fun toolsCall_unknownTool_returnsError() = runBlocking {
        val response = session().handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}"""
        )!!
        val result = resultOf(response)

        assertEquals(JsonPrimitive(true), result["isError"])
        assertTrue((result["content"] as List<*>).isNotEmpty())
    }

    @Test
    fun toolsCall_blacklistedTool_returnsNotExposed() = runBlocking {
        registry.register("manageMcp", FakeAgentTool("manageMcp", "Manage MCP"))

        val response = session().handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"manageMcp","arguments":{}}}"""
        )!!
        val result = resultOf(response)

        assertEquals(JsonPrimitive(true), result["isError"])
        val text = ((result["content"] as List<*>).first() as JsonObject)["text"] as JsonPrimitive
        assertTrue(text.content.contains("NOT_EXPOSED"))
    }

    @Test
    fun toolsCall_missingParams_returnsInvalidParams() = runBlocking {
        val response = session().handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call"}"""
        )!!
        assertNotNull(response["error"])
    }

    // ── ping / notifications ──────────────────────────────────────

    @Test
    fun ping_returnsEmptyResult() = runBlocking {
        val response = session().handle("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")!!
        assertTrue(resultOf(response).isEmpty())
    }

    @Test
    fun notification_returnsNull() = runBlocking {
        val response = session().handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(response)
    }

    // ── 错误码 ────────────────────────────────────────────────────

    @Test
    fun invalidJson_returnsParseError() = runBlocking {
        val response = session().handle("not json")!!
        val error = response["error"] as JsonObject
        assertEquals(JsonPrimitive(-32700), error["code"])
    }

    @Test
    fun unknownMethod_returnsMethodNotFound() = runBlocking {
        val response = session().handle("""{"jsonrpc":"2.0","id":8,"method":"tools/unknown"}""")!!
        val error = response["error"] as JsonObject
        assertEquals(JsonPrimitive(-32601), error["code"])
    }

    // ── McpServerSecurity ─────────────────────────────────────────

    @Test
    fun security_isExposed_blacklistExcluded() {
        assertFalse(McpServerSecurity.isExposed("terminal"))
        assertFalse(McpServerSecurity.isExposed("browser"))
        assertTrue(McpServerSecurity.isExposed("read_file"))
    }

    @Test
    fun security_generateToken_returns64HexChars() {
        val token = McpServerSecurity.generateToken()
        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        // 两次生成不应相同
        assertFalse(token == McpServerSecurity.generateToken())
    }

    @Test
    fun security_isValidToken_constantTimeAndBlankRejected() {
        val token = McpServerSecurity.generateToken()
        assertTrue(McpServerSecurity.isValidToken(token, token))
        assertTrue(McpServerSecurity.isValidToken(token, " $token "))
        assertFalse(McpServerSecurity.isValidToken(token, "wrong"))
        assertFalse(McpServerSecurity.isValidToken("", ""))
        assertFalse(McpServerSecurity.isValidToken(token, ""))
        assertFalse(McpServerSecurity.isValidToken(token, null))
    }
}
