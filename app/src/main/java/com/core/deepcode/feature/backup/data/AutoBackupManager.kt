package com.core.deepcode.feature.backup.data

import android.content.Context
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.R
import com.core.deepcode.feature.backup.domain.BackupManager
import com.core.deepcode.feature.backup.domain.BackupOptions
import com.core.deepcode.feature.backup.domain.BackupCrypto
import com.core.deepcode.feature.backup.domain.RestoreStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动备份（数据安全网）：本机私有目录明文备份 + 外部公共目录加密备份双保险。
 *
 * - **本机（私有目录）**：无口令明文写 `filesDir/auto-backups/`，仅本应用可读、升级前后立即可用；
 * - **外部（公共目录）**：用 [SignatureKeyStore] 派生的签名密钥加密写
 *   `Download/DeepCore-Code/backups`（见 [ExternalBackupStore]），包名无关、卸载/包名变更后仍保留，
 *   是「包名变更后找回历史数据」的底层保证。
 *
 * 背景：applicationId 变更 = 全新安装，私有目录随包名隔离，旧备份无法用于找回；
 * 外部安全网让备份不随包名/卸载消失，从架构上堵住「历史数据丢失且不可恢复」的缺口。
 *
 * 安全说明：外部公共目录其他应用可读，外部备份一律用签名派生密钥加密（含 API Key / Git Token）；
 * 本机私有备份保持明文（其他 App 不可读）。如需跨设备保存请用「导出备份」自行加密。
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val externalBackupStore: ExternalBackupStore,
    private val signatureKeyStore: SignatureKeyStore,
) {
    private companion object {
        const val TAG = "AutoBackup"
        /** 备份保留份数（私有与外部各自轮转），超出按时间戳删除最旧的。 */
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

    /** 备份全部落点：私有明文 + 外部加密（尽力而为，任一步失败不影响整体返回）。 */
    suspend fun backupAll(): Boolean {
        val local = backupNow()
        val external = backupToExternal()
        return local || external
    }

    /**
     * 把最近一份本机明文备份加密后写入外部公共目录（包名无关安全网）。
     * 无本机备份时先自动生成一份。外部不可用/签名密钥失败时返回 false（静默，不影响主流程）。
     */
    suspend fun backupToExternal(): Boolean = withContext(Dispatchers.IO) {
        if (!externalBackupStore.isAvailable()) {
            FileLogger.d(TAG, "外部备份跳过：公共存储不可用（未授权或未挂载）")
            return@withContext false
        }
        val password = signatureKeyStore.signaturePassword()
            ?: run { FileLogger.w(TAG, "外部备份跳过：签名密钥派生失败"); return@withContext false }
        runCatching {
            var src = latestBackup()
            if (src == null) {
                backupNow()
                src = latestBackup()
            }
            val source = src ?: return@withContext false
            val ok = externalBackupStore.write { out ->
                FileInputStream(source).use { BackupCrypto.encryptStream(it, out, password) }
            }
            if (ok) pruneExternalLocked()
            FileLogger.i(TAG, "外部加密备份完成（基于 ${source.name}）")
            ok
        }.onFailure {
            FileLogger.e(TAG, "外部加密备份失败", it)
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

    // ── 外部公共目录安全网（包名无关，见 ExternalBackupStore） ──────────

    /** 外部加密备份列表（最新在前）。 */
    fun externalBackups(): List<ExternalBackupStore.Item> = externalBackupStore.list()

    /** 最近一份外部加密备份；无则 null。 */
    fun latestExternalBackup(): ExternalBackupStore.Item? = externalBackups().firstOrNull()

    /** 最近一次外部备份时间（epoch ms）；无则 null。 */
    fun lastExternalBackupTime(): Long? = latestExternalBackup()?.epochMs

    /** 外部备份轮转：超出 [KEEP_MAX] 份删除最旧的。 */
    private fun pruneExternalLocked() {
        excessExternalBackups(externalBackups(), KEEP_MAX).forEach { externalBackupStore.delete(it) }
    }

    /**
     * 从最近一份外部加密备份恢复（签名密钥解密后导入）。
     * 失败返回 [Result.failure]（含无外部备份 / 密钥派生失败 / 解密失败）。
     */
    suspend fun restoreFromLatestExternal(): Result<RestoreStats> = withContext(Dispatchers.IO) {
        val item = latestExternalBackup()
        val password = signatureKeyStore.signaturePassword()
        if (item == null) {
            return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.backup_external_none))
            )
        }
        if (password == null) {
            return@withContext Result.failure(
                IllegalArgumentException(context.getString(R.string.backup_external_key_failed))
            )
        }
        runCatching {
            val input = externalBackupStore.openInput(item)
                ?: throw IllegalArgumentException(context.getString(R.string.backup_external_read_failed))
            input.use { backupManager.import(it, password) }.getOrThrow()
        }
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

/**
 * 外部备份轮转的**纯判定逻辑**（无 Android/IO 依赖，便于单元测试）。
 *
 * 输入 [sortedNewestFirst] 为按时间戳降序（最新在前）的外部备份条目；
 * 返回超出 [keepMax] 份的最旧条目（应删除）。不超过 [keepMax] 时返回空列表。
 */
internal fun excessExternalBackups(
    sortedNewestFirst: List<ExternalBackupStore.Item>,
    keepMax: Int,
): List<ExternalBackupStore.Item> =
    if (sortedNewestFirst.size <= keepMax) emptyList() else sortedNewestFirst.drop(keepMax)
