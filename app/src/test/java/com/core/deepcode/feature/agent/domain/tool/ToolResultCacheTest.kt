package com.core.deepcode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v46 工具系统优化回归测试：锁定 [ToolResultCache] 的缓存键与失效语义。
 *
 * 重点覆盖历史缺陷，防止回归：
 *  1. FILE_TOOLS 工具名失配（曾用 searchCode/listFiles，实际注册名为 search/list），
 *     导致文件类工具结果缓存「静默无法失效」。
 *  2. IGNORED_FIELDS 错误忽略分页/结果上限参数，导致 start_line 等影响返回内容的
 *     字段不参与缓存键，命中错误缓存、返回过期/错页结果。
 *  3. Bash 等命令改动文件系统后，通过 file.mutated 事件保守失效所有文件类缓存。
 */
class ToolResultCacheTest {

    private val session = "session-1"

    @Test
    fun fileMutated_invalidateAllFileTools_keepsNonFileTools() {
        val cache = ToolResultCache()
        val readKey = key(cache, "readFile", mapOf("path" to JsonPrimitive("/ws/a.kt")))
        val searchKey = key(cache, "search", mapOf("query" to JsonPrimitive("foo")))
        val listKey = key(cache, "list", mapOf("path" to JsonPrimitive("/ws")))
        val memoryKey = key(cache, "memory", mapOf("action" to JsonPrimitive("list")))

        cache.put(readKey, ToolResult.Success(JsonPrimitive("content")))
        cache.put(searchKey, ToolResult.Success(JsonPrimitive("matches")))
        cache.put(listKey, ToolResult.Success(JsonPrimitive("files")))
        cache.put(memoryKey, ToolResult.Success(JsonPrimitive("mem")))

        cache.invalidateByEvent("file.mutated", null)

        assertNull(cache.get(readKey))
        assertNull(cache.get(searchKey))
        assertNull(cache.get(listKey))
        // 非文件类工具（memory）不受 file.mutated 影响
        assertNotNull(cache.get(memoryKey))
    }

    @Test
    fun fileEdited_invalidateByPath_onlyMatchesContainingPath() {
        val cache = ToolResultCache()
        val hitKey = key(cache, "readFile", mapOf("path" to JsonPrimitive("/ws/a.kt")))
        val missKey = key(cache, "readFile", mapOf("path" to JsonPrimitive("/ws/b.kt")))

        cache.put(hitKey, ToolResult.Success(JsonPrimitive("A")))
        cache.put(missKey, ToolResult.Success(JsonPrimitive("B")))

        cache.invalidateByEvent("file.edited", "/ws/a.kt")

        assertNull(cache.get(hitKey))
        assertNotNull(cache.get(missKey))
    }

    @Test
    fun paginationArgs_affectCacheKey() {
        val cache = ToolResultCache()
        // 仅 start_line 不同；若被 IGNORED_FIELDS 错误忽略，二者缓存键将相同。
        val page1 = key(
            cache,
            "readFile",
            mapOf("path" to JsonPrimitive("/ws/a.kt"), "start_line" to JsonPrimitive(1), "end_line" to JsonPrimitive(100))
        )
        val page2 = key(
            cache,
            "readFile",
            mapOf("path" to JsonPrimitive("/ws/a.kt"), "start_line" to JsonPrimitive(2), "end_line" to JsonPrimitive(100))
        )

        assertNotEquals(page1.argsHash, page2.argsHash)
    }

    private fun key(cache: ToolResultCache, tool: String, args: Map<String, JsonElement>): CacheKey =
        cache.buildKey(tool, args, session)
}