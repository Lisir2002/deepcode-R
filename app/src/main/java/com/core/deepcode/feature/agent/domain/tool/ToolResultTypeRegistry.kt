package com.core.deepcode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * L3 结构化结果协议：输出类型的中央注册表。
 *
 * 每个工具通过 [AgentTool.provides] 声明产出类型（如 "file.read"、"todo.list"），
 * 本注册表统一登记其 JSON Schema、生产者与版本，供：
 *  - 依赖感知调度（L4）：按类型直连消费其他工具的产物；
 *  - 结果缓存（L5）：按类型做失效与去重；
 *  - 上下文增量发布（L6）：按类型生成结构化索引；
 *  - 轨迹摘要提取（D2-3）：按工具名登记「轨迹摘要提取器」，供运行轨迹表 resultSummary 定制提取。
 *
 * 线程安全：内部使用 ConcurrentHashMap，注册/查询均为同步操作。
 */
class ToolResultTypeRegistry {

    /** type -> 当前版本 schema。 */
    private val current = ConcurrentHashMap<String, TypeSchema>()

    /** type -> 历史版本 schema（按 version 升序）。 */
    private val history = ConcurrentHashMap<String, MutableList<TypeSchema>>()

    /** type -> 生产者工具名。 */
    private val producers = ConcurrentHashMap<String, String>()

    /** 生产者工具名 -> 产出类型集合（反查索引）。 */
    private val byProducer = ConcurrentHashMap<String, MutableSet<String>>()

    /** 能力 -> 产出类型集合（反查索引）。 */
    private val byCapability = ConcurrentHashMap<ToolCapability, MutableSet<String>>()

    /** 工具名 -> 轨迹摘要提取器（D2-3，norm-chain §3.8.2：规则表挂本注册表，不另建第二套注册）。
     *  签名 (args, resultData)：args 供提取 path 等入参（如 readFile 留路径），resultData 为
     *  canonical JSON 结果（[ToolResult.Success]/[ToolResult.Error] 的 data）。 */
    private val summarizers = ConcurrentHashMap<String, (Map<String, JsonElement>, JsonElement) -> String>()

    /** 轨迹摘要通用截断长度（全工具兜底）。 */
    private companion object {
        const val DEFAULT_SUMMARY_CHARS = 200
        const val TRUNCATED_MARK = "…[truncated]"
    }

    // ---------- CRUD ----------

    fun register(type: String, schema: TypeSchema, producer: String, version: Int? = null) {
        val resolvedVersion = version ?: (current[type]?.version?.plus(1) ?: 1)
        val entry = schema.copy(version = resolvedVersion)

        current[type] = entry
        history.getOrPut(type) { mutableListOf() }.apply {
            removeAll { it.version == resolvedVersion }
            add(entry)
            sortBy { it.version }
        }
        producers[type] = producer
        byProducer.getOrPut(producer) { mutableSetOf() }.add(type)
        schema.capability?.let { byCapability.getOrPut(it) { mutableSetOf() }.add(type) }
    }

    fun unregister(type: String) {
        val producer = producers.remove(type)
        current.remove(type)
        history.remove(type)
        if (producer != null) {
            byProducer[producer]?.remove(type)
        }
        byCapability.values.forEach { it.remove(type) }
    }

    fun hasType(type: String): Boolean = current.containsKey(type)

    fun getSchema(type: String): TypeSchema? = current[type]

    fun getProducer(type: String): String? = producers[type]

    fun getVersions(type: String): List<TypeSchema> = history[type]?.toList() ?: emptyList()

    // ---------- 解析 + 迁移 ----------

    fun resolve(type: String, version: Int): TypeSchema? {
        return history[type]?.firstOrNull { it.version == version } ?: current[type]?.takeIf { it.version == version }
    }

    /**
     * 版本迁移：默认按字段名做兼容性合并（旧字段缺失时以新 schema 的 default 兜底）。
     * 工具可通过 [TypeSchema.migrator] 注册自定义迁移函数。
     */
    fun migrate(from: Int, to: Int, data: JsonElement): JsonElement {
        if (from == to) return data
        val fromSchema = history.values.flatten().firstOrNull { it.version == from }
        val toSchema = history.values.flatten().firstOrNull { it.version == to }
        if (fromSchema == null || toSchema == null) return data
        return toSchema.migrator?.invoke(data) ?: data
    }

    // ---------- 反查 ----------

    fun getTypesByProducer(toolName: String): Set<String> = byProducer[toolName]?.toSet() ?: emptySet()

    fun getTypesByCapability(cap: ToolCapability): Set<String> = byCapability[cap]?.toSet() ?: emptySet()

    // ---------- 轨迹摘要提取器（D2-3，norm-chain §3.8.2） ----------

    /**
     * 登记某工具名的轨迹摘要提取器。提取器接收该工具的调用参数 [args] 与结果 canonical JSON
     * [resultData]（[ToolResult.Success]/[ToolResult.Error] 的 data），返回一行轨迹摘要。
     */
    fun registerTrajectorySummarizer(toolName: String, extractor: (Map<String, JsonElement>, JsonElement) -> String) {
        summarizers[toolName] = extractor
    }

    fun unregisterTrajectorySummarizer(toolName: String) {
        summarizers.remove(toolName)
    }

    /**
     * 生成工具结果的轨迹摘要：优先用 [toolName] 已登记的定制提取器；
     * 无定制规则时走通用截断（前 N 字符 + truncated 标记，防单条体积膨胀）。
     */
    fun summarize(toolName: String, args: Map<String, JsonElement>, element: JsonElement): String {
        val extractor = summarizers[toolName]
        if (extractor != null) {
            return runCatching { extractor(args, element) }
                .getOrElse { genericSummary(element) }
        }
        return genericSummary(element)
    }

    /** 通用截断：把 canonical JSON 序列化为单行文本后截断。 */
    private fun genericSummary(element: JsonElement): String {
        val text = compactJson(element)
        return truncate(text)
    }

    private fun truncate(text: String): String {
        if (text.length <= DEFAULT_SUMMARY_CHARS) return text
        return text.take(DEFAULT_SUMMARY_CHARS) + TRUNCATED_MARK
    }

    private fun compactJson(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.toString()
        is JsonObject -> {
            // 只取常见短字段 + content 截断，避免整对象序列化把大输出全带进摘要。
            val preferred = listOf("path", "file", "file_path", "action", "status", "message", "result", "content", "output")
            val parts = preferred.mapNotNull { key ->
                (element[key] as? JsonPrimitive)?.let { key to it.toString() }
            }
            if (parts.isEmpty()) element.toString() else parts.joinToString(", ") { "${it.first}=${it.second}" }
        }
        else -> element.toString()
    }
}

/**
 * 输出类型的 JSON Schema 描述。
 */
data class TypeSchema(
    /** 类型名（如 "file.read"）。 */
    val type: String,
    /** 版本号，从 1 递增。 */
    val version: Int = 1,
    /** JSON Schema（原样透传给消费方 / 模型）。 */
    val schema: Map<String, Any> = emptyMap(),
    /** 产出该类型所需的能力（用于按能力反查）。 */
    val capability: ToolCapability? = null,
    /** 自定义版本迁移函数：from -> to 时对数据做转换。为 null 时按字段名兼容合并。 */
    val migrator: ((JsonElement) -> JsonElement)? = null
)
