package com.R.codecore.feature.backup.data

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.backup.domain.BackupManager
import com.R.codecore.feature.backup.domain.BackupOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机自动备份：把全量数据（含聊天历史）无口令导出到应用私有目录 `filesDir/auto-backups/`，
 * 按时间戳命名并轮转保留最近 [KEEP_MAX] 份。
 *
 * 背景：用户没有主动导出备份的习惯，一旦发生包名变更 / 数据被清空就不可找回。
 * 自动备份作为"数据安全网"：升级前自动执行一次 + 设置页可手动触发 + 数据丢失时一键恢复。
 *
 * 安全说明：备份为明文（无口令），但只写应用私有目录（其他 App 不可读、用户不可见）；
 * 如需跨设备保存，请用「导出备份」功能自行加密保存。
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
) {
    private companion object {
        const val TAG = "AutoBackup"
        /** 私有目录下保留的备份份数，超出按时间戳删除最旧的。 */
        const val KEEP_MAX = 7
        const val PREFIX = "backup-"
        const val SUFFIX = ".tar.gz"
    }

    private fun autoBackupDir(): File = File(context.filesDir, "auto-backups")

    /** 立即全量备份到私有目录。成功返回 true；失败记日志返回 false，绝不外溢异常。 */
    suspend fun backupNow(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            autoBackupDir().mkdirs()
            val file = File(autoBackupDir(), "$PREFIX${System.currentTimeMillis()}$SUFFIX")
            FileOutputStream(file).use { backupManager.export(null, BackupOptions(), it) }
            pruneLocked()
            FileLogger.i(TAG, "自动备份完成: ${file.name} (${file.length()} bytes)")
            true
        }.onFailure {
            FileLogger.e(TAG, "自动备份失败", it)
            false
        }.getOrDefault(false)
    }

    /** 私有目录下全部自动备份（按文件名时间戳降序，最新的在前）。 */
    fun backups(): List<File> = autoBackupDir()
        .listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
        ?.sortedByDescending { epochOf(it) }
        ?.toList()
        ?: emptyList()

    /** 最近一份自动备份文件；无则 null。 */
    fun latestBackup(): File? = backups().firstOrNull()

    /** 最近一次自动备份时间（epoch ms）；无则 null。 */
    fun lastBackupTime(): Long? = latestBackup()?.let { epochOf(it) }

    private fun epochOf(file: File): Long =
        file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: file.lastModified()

    /** 轮转：超过 [KEEP_MAX] 份时删除最旧的。调用方需保证单线程（backupNow 内已串行）。 */
    private fun pruneLocked() {
        val files = backups()
        excessBackupFiles(files, KEEP_MAX).forEach { runCatching { it.delete() } }
    }
}

/**
 * 自动备份轮转的**纯判定逻辑**（无 Android/IO 依赖，便于单元测试，见 D10）。
 *
 * 输入 [sortedNewestFirst] 为按时间戳降序（最新在前）的备份文件列表；
 * 返回超出 [keepMax] 份的最旧文件（应删除）。不超过 [keepMax] 时返回空列表。
 */
internal fun excessBackupFiles(sortedNewestFirst: List<File>, keepMax: Int): List<File> =
    if (sortedNewestFirst.size <= keepMax) emptyList() else sortedNewestFirst.drop(keepMax)
