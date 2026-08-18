package com.R.codecore.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * L5 结果缓存去重：会话级缓存 + TTL。
 *
 * 按 (toolName, argsHash) 键控，同一会话跨请求复用，消除同参数重复执行。
 * 文件类工具结合文件 mtime 做双机制失效（文件变则缓存失效）；
 * 非文件类按 TTL 过期。L7 事件总线接入后，写文件事件可广播强制失效。
 *
 * 线程安全：ConcurrentHashMap + per-key 原子操作。
 */
class ToolResultCache {

    private val cache = ConcurrentHashMap<CacheKey, CachedEntry>()
    private val fileMtimes = ConcurrentHashMap<String, Long>()

    // ---------- 核心路径 ----------

    /**
     * 查缓存。命中且未过期则返回，否则清除并返回 null。
     * [fileMtime] 为文件当前 mtime，只有文件类工具传入（如 readFile 传 path 的 mtime）。
     */
    fun get(key: CacheKey, fileMtime: Long? = null): ToolResult? {
        val entry = cache[key] ?: return null
        if (entry.isExpired(fileMtime)) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    /** 写缓存。 */
    fun put(key: CacheKey, result: ToolResult, ttlMs: Long = DEFAULT_TTL_MS) {
        cache[key] = CachedEntry(result, System.currentTimeMillis() + ttlMs, ttlMs)
    }

    // ---------- 失效 ----------

    /** 按事件类型 + 关联路径失效（L7 事件总线回调）。 */
    fun invalidateByEvent(eventType: String, path: String? = null) {
        if (path != null) {
            // 文件变更：失效关联该文件的所有缓存（readFile、search 等）
            val pattern = path.removeSuffix("/")
            cache.keys.filter { key ->
                key.toolName in FILE_TOOLS && key.args.contains(pattern)
            }.forEach { cache.remove(it) }
            return
        }
        // 按事件类型批量失效
        when (eventType) {
            "file.edited", "file.written", "file.deleted", "file.mutated" -> {
                cache.keys.filter { it.toolName in FILE_TOOLS }.forEach { cache.remove(it) }
            }
            "cache.cleared" -> cache.clear()
        }
    }

    /** 清空指定会话的缓存。 */
    fun clear(sessionId: String) {
        cache.keys.filter { it.sessionId == sessionId }.forEach { cache.remove(it) }
    }

    /** 记录文件 mtime（读文件时调用）。 */
    fun recordFileMtime(path: String, mtime: Long) {
        fileMtimes[path] = mtime
    }

    // ---------- 键构造 ----------

    /**
     * 构建缓存键：对参数做规范化（排序 key、忽略无关字段），然后取 SHA-256。
     * 文件类工具额外在 args 中嵌入路径，供失效时按路径匹配。
     */
    fun buildKey(toolName: String, args: Map<String, JsonElement>, sessionId: String): CacheKey {
        val normalized = normalizeArgs(toolName, args)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(toolName.encodeToByteArray())
        digest.update(normalized.encodeToByteArray())
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return CacheKey(toolName = toolName, argsHash = hash, sessionId = sessionId, args = normalized)
    }

    /** 命中率统计（供调试与监控）。 */
    fun stats(): CacheStats = CacheStats(
        size = cache.size,
        keys = cache.keys.map { "${it.toolName}:${it.argsHash.take(8)}" }
    )

    // ---------- 内部 ----------

    private fun normalizeArgs(toolName: String, args: Map<String, JsonElement>): String {
        val ignored = IGNORED_FIELDS[toolName].orEmpty()
        val sorted = args.filterKeys { it !in ignored }.entries.sortedBy { it.key }
        return sorted.joinToString("&") { "${it.key}=${it.value}" }
    }

    private data class CachedEntry(
        val result: ToolResult,
        val expiresAtMs: Long,
        val ttlMs: Long
    ) {
        fun isExpired(fileMtime: Long?): Boolean {
            if (fileMtime != null && fileMtime > expiresAtMs - ttlMs) return true
            return System.currentTimeMillis() > expiresAtMs
        }
    }

    companion object {
        /** 默认 TTL：60 秒。 */
        const val DEFAULT_TTL_MS = 60_000L

        /**
         * 文件类工具名集合，用于缓存键判定与失效匹配。
         * 必须与 [com.R.codecore.di.AgentModule] 中的注册名保持一致，否则对应工具的结果缓存会静默失效。
         */
        val FILE_TOOLS = setOf("readFile", "search", "list")

        /**
         * 规范化时忽略的参数（如 sessionId、reason 等不影响结果语义的字段）。
         * 当前为空：所有声明参数都参与缓存键，确保分页窗口（start_line/end_line）、
         * 结果上限（max_matches）等影响返回内容的字段不被错误忽略，避免命中错误缓存。
         */
        val IGNORED_FIELDS: Map<String, Set<String>> = emptyMap()
    }
}

/** 缓存键。 */
data class CacheKey(
    val toolName: String,
    val argsHash: String,
    val sessionId: String,
    /** 规范化后的参数文本，用于文件路径匹配失效，不参与 equals/hashCode。 */
    val args: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheKey) return false
        return toolName == other.toolName && argsHash == other.argsHash && sessionId == other.sessionId
    }

    override fun hashCode(): Int = toolName.hashCode() * 31 + argsHash.hashCode() * 31 + sessionId.hashCode()
}

/** 缓存命中统计。 */
data class CacheStats(
    val size: Int,
    val keys: List<String>
)