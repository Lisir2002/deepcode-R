package com.R.codecore.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * L3 结构化结果协议：输出类型的中央注册表。
 *
 * 每个工具通过 [AgentTool.provides] 声明产出类型（如 "file.read"、"todo.list"），
 * 本注册表统一登记其 JSON Schema、生产者与版本，供：
 *  - 依赖感知调度（L4）：按类型直连消费其他工具的产物；
 *  - 结果缓存（L5）：按类型做失效与去重；
 *  - 上下文增量发布（L6）：按类型生成结构化索引。
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
