package com.core.deepcode.core.data

/**
 * 数据层重构（新写法）· 数据注册表核心类型。
 *
 * 数据注册表是「备份 / 恢复 / 自动迁移 / 哨兵」共用的**单一事实源**：枚举全应用数据域
 * （Room 表 / DataStore / 关键文件），每项一个 [DataProvider]，统一导出/导入。
 * 新增数据域只需注册一个 Provider，全量备份/恢复自动覆盖，不再手工维护白名单（对应
 * 设计文档 R3/R5）。
 */

/** 数据域类别。 */
enum class DataCategory {
    /** Room 数据库表。 */
    TABLE,
    /** DataStore 偏好文件（可扩展到其他 KV 存储）。 */
    STORE,
    /** 关键文件（如容器 rootfs、备份密钥等；暂保留扩展位）。 */
    FILE,
}

/** 一次导出/恢复的数据载荷：按全局唯一 [key] 存取原始字节。 */
data class DataBlob(
    val key: String,
    val bytes: ByteArray,
)

/**
 * 数据提供者：每个数据域注册一个，供 [DataRegistry] 统一调用。
 * [key] 全应用唯一；[category] 供备份格式组织与排查。
 */
interface DataProvider {
    val key: String
    val category: DataCategory

    /** 导出当前数据为字节载荷。失败由调用方 [DataRegistry] 兜底跳过。 */
    suspend fun snapshot(): DataBlob

    /** 从 [blob] 恢复数据。失败由调用方 [DataRegistry] 兜底记录，不阻断其余域。 */
    suspend fun restore(blob: DataBlob)
}
