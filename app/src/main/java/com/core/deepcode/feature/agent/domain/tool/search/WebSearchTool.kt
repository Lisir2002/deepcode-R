package com.core.deepcode.feature.agent.domain.tool.search

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * 实时网络搜索工具。经**共享 OkHttp**（注入 ProxyRouteHolder 的 ProxySelector）请求海外
 * Parallel AI MCP，代理启用时流量走 mihomo 出口，未启用时直连（可被墙阻断）。
 */
class WebSearchTool @Inject constructor(
    private val client: OkHttpClient
) : AgentTool() {

    private companion object {
        const val TAG = "WebSearchTool"
        // 使用与 opencode 一致的 Parallel AI 公开 MCP 接口
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"
        // W-2：1 次初始请求 + 最多 2 次额外重试
        const val MAX_ATTEMPTS = 3
        // 指数退避：第 1 次重试等待 800ms，第 2 次重试等待 1600ms
        val BACKOFF_DELAYS = listOf(800L, 1600L)
    }

    override val name = "websearch"
    override val description = "通过互联网搜索引擎获取实时信息，突破大模型的知识库时间截断限制。适用于需要最新资料或时效性信息的任务。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "搜索关键字。请提炼出准确、易于搜索的短语。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少 query 参数")

        return withContext(Dispatchers.IO) {
            var attempts = 0
            var lastError: Exception? = null

            // W-2：网络层异常 / HTTP 5xx 时指数退避重试，最多 2 次额外尝试
            for (attempt in 1..MAX_ATTEMPTS) {
                attempts = attempt
                try {
                    FileLogger.i(TAG, "发起 WebSearch 请求 (attempt $attempt/$MAX_ATTEMPTS): $query")
                    val rawResultText = performSearch(query)

                    val result = if (rawResultText.isNullOrBlank()) {
                        buildStructuredResult(
                            query,
                            "未能找到关于 '$query' 的搜索结果，请换个关键词重试。",
                            attempts,
                            noResults = true
                        )
                    } else {
                        buildStructuredResult(query, rawResultText, attempts)
                    }
                    return@withContext ToolResult.Success(result)
                } catch (e: Http4xxException) {
                    // HTTP 4xx（如 401/403/404）不重试，直接返回错误
                    FileLogger.e(TAG, "WebSearch 失败: HTTP ${e.code}（第 $attempt 次尝试，4xx 不重试）")
                    return@withContext ToolResult.Error("网络搜索失败 (HTTP ${e.code})")
                } catch (e: Exception) {
                    lastError = e
                    val retryable = e is IOException || e is SocketTimeoutException || e is Http5xxException
                    if (retryable && attempt < MAX_ATTEMPTS) {
                        val backoff = BACKOFF_DELAYS[attempt - 1]
                        FileLogger.w(TAG, "WebSearch 第 $attempt 次尝试失败，${backoff}ms 后重试: ${e.message}", e)
                        delay(backoff)
                    } else {
                        FileLogger.e(TAG, "WebSearch 失败（第 $attempt 次尝试）: ${e.message}", e)
                        return@withContext ToolResult.Error("搜索时发生异常: ${e.message}")
                    }
                }
            }
            ToolResult.Error("搜索时发生异常: ${lastError?.message}")
        }
    }

    /**
     * 单次执行完整的「建立连接→写请求→读响应→解析」流程。
     * 网络层异常（IOException/SocketTimeoutException）与 HTTP 5xx 抛出可重试异常，
     * HTTP 4xx 抛出 [Http4xxException] 由调用方直接返回错误不重试。
     * 返回 MCP content 各段 text 合并后的原始文本，无结果时返回 null。
     */
    private fun performSearch(query: String): String? {
        val requestBody = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(1),
                "method" to JsonPrimitive("tools/call"),
                "params" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("web_search"),
                        "arguments" to JsonObject(
                            mapOf(
                                "objective" to JsonPrimitive(query),
                                "search_queries" to JsonArray(listOf(JsonPrimitive(query))),
                                "session_id" to JsonPrimitive("deepcode-android")
                            )
                        )
                    )
                )
            )
        ).toString()

        // 共享 OkHttp（代理启用时经 mihomo 出口）：旧实现用 HttpURLConnection 直连海外
        // search.parallel.ai/mcp，完全绕过共享 OkHttp 的 ProxySelector，代理启用后依旧连不上。
        val req = Request.Builder()
            .url(PARALLEL_MCP_URL)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "deepcode/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            val responseCode = resp.code
            if (responseCode !in 200..299) {
                val errorStr = resp.body?.string().orEmpty().take(500)
                FileLogger.e(TAG, "WebSearch 失败: HTTP $responseCode, $errorStr")
                if (responseCode in 400..499) {
                    throw Http4xxException(responseCode)
                }
                throw Http5xxException(responseCode)
            }

            // 尝试直接读取普通 JSON 或解析 Event-Stream (SSE) 格式
            val responseBody = resp.body?.string().orEmpty()

            // 解析 MCP 返回体，合并所有 content 段的 text
            return parseMcpResponse(responseBody)
        }
    }

    /**
     * 兼容解析直接的 JSON 或 SSE (Server-Sent Events) 格式。
     * 收集所有 data 行中 result.content[].text 段，多段内容按行合并（W-1）。
     */
    private fun parseMcpResponse(body: String): String? {
        val merged = StringBuilder()
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            val jsonStr = if (line.startsWith("data: ")) {
                line.substring(6)
            } else if (line.startsWith("{")) {
                line
            } else {
                continue
            }

            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                if (json.containsKey("result")) {
                    val contentArr = json["result"]?.jsonObject?.get("content")?.jsonArray
                    contentArr?.forEach { contentItem ->
                        val text = contentItem.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                        if (!text.isNullOrBlank()) {
                            if (merged.isNotEmpty()) merged.append('\n')
                            merged.append(text)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors for partial lines
            }
        }
        return merged.toString().ifBlank { null }
    }

    /**
     * W-1 结果结构化：把合并后的 MCP 文本转为统一的
     * {"query", "results": [{title,url,snippet}...], "count", "content", "attempts"} 结构。
     * 若文本是 JSON 且含 results 数组则解析为对象列表；否则把自然语言文本按行拆为 snippet。
     */
    private fun buildStructuredResult(query: String, rawText: String, attempts: Int, noResults: Boolean = false): JsonObject {
        val content = rawText
        val results = if (noResults) {
            emptyList()
        } else {
            val parsed = runCatching { Json.parseToJsonElement(content.trim()) }.getOrNull()
            val resultsArr = (parsed as? JsonObject)?.get("results") as? JsonArray
            if (resultsArr != null) {
                resultsArr.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (title.isBlank() && url.isBlank() && snippet.isBlank()) {
                        null
                    } else {
                        JsonObject(
                            mapOf(
                                "title" to JsonPrimitive(title),
                                "url" to JsonPrimitive(url),
                                "snippet" to JsonPrimitive(snippet)
                            )
                        )
                    }
                }
            } else {
                // 自然语言文本：按行拆为 snippet
                content.trim().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map {
                        JsonObject(
                            mapOf(
                                "title" to JsonPrimitive(""),
                                "url" to JsonPrimitive(""),
                                "snippet" to JsonPrimitive(it)
                            )
                        )
                    }
            }
        }
        return JsonObject(
            buildMap {
                put("query", JsonPrimitive(query))
                put("results", JsonArray(results))
                put("count", JsonPrimitive(results.size))
                put("content", JsonPrimitive(content))
                put("attempts", JsonPrimitive(attempts))
            }
        )
    }

    /** HTTP 4xx：不可重试，直接返回错误。 */
    private class Http4xxException(val code: Int) : RuntimeException("HTTP $code")

    /** HTTP 5xx：瞬时服务端错误，可重试。 */
    private class Http5xxException(val code: Int) : IOException("HTTP $code")
}
