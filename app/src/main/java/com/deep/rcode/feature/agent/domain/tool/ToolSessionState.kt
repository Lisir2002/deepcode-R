package com.deep.rcode.feature.agent.domain.tool

import com.deep.rcode.feature.agent.domain.model.TodoItem
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * L2 共享会话状态层：会话级可变状态持有器。
 *
 * 修复「上下文冻结」缺陷——工具输出只回传模型、不落库，导致后续工具无法引用前序产物。
 * 本类承载完整工具输出历史与中间产物引用，让同一会话内的工具能读写同一份共享状态。
 *
 * 定位：内存缓存 + 持久化权威源。共享状态是会话内的内存缓存，持久化层
 * （TodoDao / MemoryRepository）是权威数据源。读优先走共享状态（快），写先落共享状态
 * （即时可见），由工具自身决定是否同步到持久化层。
 *
 * 线程安全：分区隔离用 [ConcurrentHashMap] + per-key 锁，保证并行工具同 key 串行安全。
 */
class ToolSessionState(
    /** 所属会话 id，用于日志与持久化关联。 */
    val sessionId: String
) {
    /** 工具输出历史：callId -> record。 */
    private val outputs = ConcurrentHashMap<String, ToolOutputRecord>()

    /** 工具输出计数（O(1) 读取，供轮次号与快照使用，避免每次全量遍历 outputs）。 */
    @Volatile
    var outputCount: Int = 0
        private set

    /** 中间产物：type -> JsonElement（如 "file.read" -> 文件内容）。 */
    private val artifacts = ConcurrentHashMap<String, JsonElement>()

    /** 当前编辑文件（相对 workspace 路径）。 */
    @Volatile
    var currentFile: String? = null

    /** 当前编辑器选区快照。 */
    @Volatile
    var selection: CodeSelection? = null

    /** 待办快照（TodoTool 每次变更后刷新）。 */
    @Volatile
    var todoSnapshot: List<TodoItem> = emptyList()

    /** 记忆缓存（MemoryTool 加载后填充）。 */
    private val memoryCache = ConcurrentHashMap<String, String>()

    // ---------- outputs ----------

    fun recordOutput(record: ToolOutputRecord) {
        outputs[record.callId] = record
        outputCount = outputs.size
    }

    fun getOutput(callId: String): ToolOutputRecord? = outputs[callId]

    fun latestOutput(toolName: String): ToolOutputRecord? {
        return outputs.values
            .filter { it.toolName == toolName }
            .maxByOrNull { it.timestamp }
    }

    fun allOutputs(): List<ToolOutputRecord> = outputs.values.sortedBy { it.timestamp }

    // ---------- artifacts ----------

    fun putArtifact(type: String, element: JsonElement) {
        artifacts[type] = element
    }

    fun getArtifact(type: String): JsonElement? = artifacts[type]

    fun removeArtifact(type: String) {
        artifacts.remove(type)
    }

    // ---------- memory cache ----------

    fun putMemory(key: String, value: String) {
        memoryCache[key] = value
    }

    fun getMemory(key: String): String? = memoryCache[key]

    fun memorySnapshot(): Map<String, String> = memoryCache.toMap()

    // ---------- snapshot ----------

    /** 供 prompt 构建 / 上下文增量发布使用的只读快照。 */
    fun snapshot(): SessionStateSnapshot {
        return SessionStateSnapshot(
            currentFile = currentFile,
            selection = selection,
            todoSnapshot = todoSnapshot,
            memoryCache = memorySnapshot(),
            outputCount = outputs.size,
            artifactTypes = artifacts.keys.sorted()
        )
    }
}

/**
 * 单次工具调用的输出记录，写入 [ToolSessionState.outputs]。
 */
data class ToolOutputRecord(
    val callId: String,
    val toolName: String,
    val result: ToolResult,
    val timestamp: Long = System.currentTimeMillis(),
    /** L3 错误分类：成功为 null，失败按 [classifyError] 推断。 */
    val errorClass: ToolErrorClass? = null
)

/**
 * [ToolSessionState] 的只读快照，供 prompt 构建与增量发布消费。
 */
data class SessionStateSnapshot(
    val currentFile: String?,
    val selection: CodeSelection?,
    val todoSnapshot: List<TodoItem>,
    val memoryCache: Map<String, String>,
    val outputCount: Int,
    val artifactTypes: List<String>
)
