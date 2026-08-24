package com.R.codecore.core.data

import com.R.codecore.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据注册表：聚合全应用 [DataProvider]，提供统一的全量导出/导入与打包能力。
 *
 * - [snapshotAll]：逐 provider 导出（单个失败跳过、不阻断其余）；
 * - [restoreAll]：逐 provider 恢复（单个失败记录、不阻断其余）；
 * - [pack]/[unpack]：把若干 [DataBlob] 打包为单个 tar 字节（条目名 `providers/<key>`），
 *   供外部存储加密备份等「单文件落点」使用（自动迁移无感恢复的关键）。
 *
 * 依赖注入：构造注入 `List<DataProvider>`，由 [DataRegistryModule] 提供（含全部 Room 表 +
 * DataStore 目录）。注册表自身不感知任何具体数据源，保持「单一事实源」的薄编排角色。
 */
@Singleton
class DataRegistry @Inject constructor(
    providers: List<@JvmSuppressWildcards DataProvider>,
) {
    private companion object {
        const val TAG = "DataRegistry"
        const val ENTRY_PREFIX = "providers/"
    }

    private val allProviders: List<DataProvider> = providers

    /** 全部已注册的数据提供者（只读）。 */
    val providers: List<DataProvider> get() = allProviders

    /** 按全局唯一 key 查找提供者；不存在返回 null。 */
    fun byKey(key: String): DataProvider? = allProviders.firstOrNull { it.key == key }

    /**
     * 全量导出：逐 provider 调用 [DataProvider.snapshot]。
     * 单个失败仅记日志并跳过（该域导出空），绝不因单个域失败而中断整体。
     */
    suspend fun snapshotAll(): List<DataBlob> = withContext(Dispatchers.IO) {
        allProviders.mapNotNull { p ->
            runCatching { p.snapshot() }
                .onFailure { FileLogger.w(TAG, "snapshot 失败，跳过域 ${p.key}", it) }
                .getOrNull()
        }
    }

    /**
     * 全量恢复：按 key 匹配传入的 [blobs] 并逐 provider 恢复。
     * 单个失败仅记日志，继续恢复其余域，保证一次迁移尽可能完整。
     */
    suspend fun restoreAll(blobs: List<DataBlob>) {
        val byKey = blobs.associateBy { it.key }
        for (p in allProviders) {
            val blob = byKey[p.key] ?: continue
            runCatching { p.restore(blob) }
                .onFailure { FileLogger.w(TAG, "restore 失败，域 ${p.key}", it) }
        }
    }

    /** 把若干 [DataBlob] 打包为 tar 字节（条目名 `providers/<key>`，逐条目直写，单表内存峰值可控）。 */
    fun pack(blobs: List<DataBlob>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            for (blob in blobs) {
                val entry = TarArchiveEntry(ENTRY_PREFIX + blob.key).apply { size = blob.bytes.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(blob.bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /** 从 [pack] 生成的 tar 字节解包回 [DataBlob] 列表。 */
    fun unpack(bytes: ByteArray): List<DataBlob> {
        if (bytes.isEmpty()) return emptyList()
        val result = mutableListOf<DataBlob>()
        TarArchiveInputStream(ByteArrayInputStream(bytes)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val key = entry.name.removePrefix(ENTRY_PREFIX)
                    if (key.isNotEmpty()) {
                        val data = ByteArrayOutputStream()
                        tar.copyTo(data)
                        result.add(DataBlob(key, data.toByteArray()))
                    }
                }
                entry = tar.nextEntry
            }
        }
        return result
    }
}
