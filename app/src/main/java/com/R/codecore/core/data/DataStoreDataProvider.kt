package com.R.codecore.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * DataStore 文件级转储 Provider（数据注册表 STORE 域实现）。
 *
 * 直接备份 `files/datastore/` 目录下的全部偏好文件（`*.preferences_pb`）：
 * 不做逐 key 枚举，天然覆盖所有模块的 DataStore（settings_prefs / workspace_prefs /
 * terminal_prefs / mcp_server_prefs / proxy_prefs / app_run_meta 等），未来新增
 * DataStore 无需改注册表。
 *
 * 恢复：解包覆盖 datastore 目录（备份中不存在的文件删除，保持目录与备份一致）。
 * 注意：若某 DataStore 在当前进程已被读取，文件覆盖不会刷新内存缓存——该限制不影响
 * 无感自动迁移场景（发生在 PACKAGE_CHANGED 全新包首次启动、各 store 尚未读取）；
 * 手动导入仍走 BackupManager 的 repository 级恢复（restoreMeta 会重写并刷新内存）。
 */
class DataStoreDataProvider(
    context: Context,
) : DataProvider {
    override val key: String = "datastore_dir"
    override val category: DataCategory = DataCategory.STORE

    private val dir = File(context.filesDir, "datastore")

    override suspend fun snapshot(): DataBlob = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext DataBlob(key, ByteArray(0))
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            dir.listFiles { f -> f.isFile }?.forEach { f ->
                val entry = TarArchiveEntry(f.name).apply { size = f.length() }
                tar.putArchiveEntry(entry)
                FileInputStream(f).use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
        DataBlob(key, out.toByteArray())
    }

    override suspend fun restore(blob: DataBlob) {
        if (blob.bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            if (!dir.exists() && !dir.mkdirs()) return@withContext
            // 先清空现有 datastore 文件，保证恢复后目录与备份完全一致（防残留旧 store 干扰）。
            dir.listFiles { f -> f.isFile }?.forEach { runCatching { it.delete() } }
            TarArchiveInputStream(ByteArrayInputStream(blob.bytes)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(dir, entry.name)
                        FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}
