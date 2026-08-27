package com.R.codecore.datalayer.migration

import com.R.codecore.core.data.DataRegistry
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.parity.V2ParityChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V1→V2 切换前置安全闸（v2-full-takeover P1-1）。
 *
 * 在业务读源切到 V2 之前执行三件事，任何一项失败都**拒绝放行**（保持 ROOM 读）：
 *  1. 全量数据经 [DataRegistry] 落一份外部加密备份（可恢复）；
 *  2. [V2ParityChecker] 逐表行数一致（V1 与 V2 数据对齐）；
 *  3. 把 [DataReadMode] 置为 V2。
 *
 * 幂等：已置 V2 或已打过备份标记则直接返回。
 */
@Singleton
class V2TakeoverGate @Inject constructor(
    private val dataRegistry: DataRegistry,
    private val parityChecker: V2ParityChecker,
    private val readMode: DataReadModeHolder,
) {
    private companion object {
        const val TAG = "V2TakeoverGate"
        const val BACKUP_MARKER = ".v2_takeover_backup"
    }

    /** 备份落点：App 私有 filesDir 下，文件名带时间戳（不上云备份目录）。 */
    private fun backupFile(dir: File): File = dir.resolve("backup").also { it.mkdirs() }
        .resolve("v2_takeover_${System.currentTimeMillis()}.dat")

    /** 返回是否已放行（true = 已切 V2，无需再切）。 */
    suspend fun alreadyPassed(): Boolean = readMode.currentMode() == DataReadMode.V2

    /**
     * 尝试放行 V2。返回 true = 已切 V2；false = 前置未通过，保持 ROOM。
     */
    suspend fun tryEnableV2(filesDir: File): Boolean = withContext(Dispatchers.IO) {
        if (alreadyPassed()) return@withContext true

        // 1) 切换前强制全量备份
        val backupOk = runCatching {
            val blobs = dataRegistry.snapshotAll()
            val packed = dataRegistry.pack(blobs)
            val marker = File(filesDir, BACKUP_MARKER)
            if (packed.isNotEmpty()) {
                backupFile(filesDir).writeBytes(packed)
                marker.writeText(System.currentTimeMillis().toString())
                FileLogger.i(TAG, "切换前全量备份成功：${packed.size} 字节，${blobs.size} 个域")
            }
            marker.exists()
        }.getOrDefault(false)
        if (!backupOk) {
            FileLogger.e(TAG, "切换前全量备份失败，拒绝切 V2，保持 ROOM")
            return@withContext false
        }

        // 2) parity 校验
        val parityOk = runCatching { parityChecker.allMatch() }.getOrDefault(false)
        if (!parityOk) {
            FileLogger.e(TAG, "V1/V2 parity 不一致，拒绝切 V2，保持 ROOM")
            return@withContext false
        }

        // 3) 置位读源
        runCatching { readMode.setMode(DataReadMode.V2) }
            .onFailure { FileLogger.e(TAG, "置位 DataReadMode.V2 失败，保持 ROOM", it) }
            .getOrElse { return@withContext false }

        FileLogger.i(TAG, "V2 切换放行完成")
        true
    }
}
