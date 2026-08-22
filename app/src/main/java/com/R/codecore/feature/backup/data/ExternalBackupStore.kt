package com.R.codecore.feature.backup.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部公共存储备份存储：包名无关的「数据安全网」落点。
 *
 * 背景：自动备份此前只写应用私有目录（filesDir/auto-backups/）。applicationId（包名）变更 =
 * 全新安装，私有目录随包名隔离，旧备份「看得到摸不着」，无法用于找回——这正是历史对话丢失且
 * 无法自动恢复的底层原因之一。本存储把备份写到「公共外部存储」Download/RCodeCore/backups：
 *   - Android 10+（API 29+）：MediaStore.Downloads 集合（写入 Downloads 无需权限，卸载/包名变更后仍保留）；
 *   - Android 9-（API <29）：Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
 *     （需 WRITE_EXTERNAL_STORAGE，MainActivity 已运行时申请）。
 * 公共目录不随包名/卸载而消失 → 包名变更或重装后仍能读取历史加密备份找回数据。
 *
 * 安全注意：公共目录其他应用可读，写入内容必须由调用方加密（见 AutoBackupManager + SignatureKeyStore），
 * 绝不允许明文备份落公共目录。
 */
@Singleton
class ExternalBackupStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "ExternalBackupStore"
        const val RELATIVE_SUB = "RCodeCore/backups"
        const val RELATIVE_PATH_PREFIX = "Download/$RELATIVE_SUB"
        const val PREFIX = "backup-"
        const val SUFFIX = ".tar.gz"
    }

    /** 外部备份条目（低版本为文件 Uri，高版本为 MediaStore Uri）。 */
    data class Item(val uri: Uri, val name: String, val epochMs: Long)

    /** 当前是否能写公共外部存储。Android 11+ 走 MediaStore 始终可写；低版本需挂载 + WRITE 权限。 */
    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED)

    /** 写入一个备份文件。[writeBlock] 收到输出流，负责写入（加密后的）内容。成功返回 true。 */
    fun write(writeBlock: (OutputStream) -> Unit): Boolean = runCatching {
        val name = "$PREFIX${System.currentTimeMillis()}$SUFFIX"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(name, writeBlock)
        } else {
            writeViaLegacyFile(name, writeBlock)
        }
    }.onFailure {
        FileLogger.w(TAG, "写入外部备份失败", it)
    }.getOrDefault(false)

    /** 列出全部外部备份（最新在前）。 */
    fun list(): List<Item> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listViaMediaStore()
        else listViaLegacyFile()

    /** 打开备份读取流；失败返回 null。 */
    fun openInput(item: Item): InputStream? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.openInputStream(item.uri)
        } else {
            File(item.uri.path ?: return@runCatching null).inputStream()
        }
    }.onFailure {
        FileLogger.w(TAG, "读取外部备份失败: ${item.name}", it)
    }.getOrNull()

    /** 删除一个备份（轮转）。失败仅记日志。 */
    fun delete(item: Item) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(item.uri, null, null)
            } else {
                File(item.uri.path ?: return@runCatching).delete()
            }
        }.onFailure { FileLogger.w(TAG, "删除外部备份失败: ${item.name}", it) }
    }

    // ── API 29+：MediaStore.Downloads ──────────────────────────────────
    private fun writeViaMediaStore(name: String, writeBlock: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
            put(MediaStore.Downloads.RELATIVE_PATH, "$RELATIVE_PATH_PREFIX/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 插入失败")
        try {
            resolver.openOutputStream(uri)?.use(writeBlock)
                ?: throw IllegalStateException("MediaStore 打开输出流失败")
        } finally {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun listViaMediaStore(): List<Item> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED,
        )
        val items = mutableListOf<Item>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH_PREFIX/%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (c.moveToNext()) {
                val name = c.getString(nameCol)
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) continue
                val id = c.getLong(idCol)
                val epochSec = c.getLong(dateCol)
                items.add(
                    Item(
                        uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                        name = name,
                        epochMs = epochSec * 1000L,
                    )
                )
            }
        }
        return items
    }

    // ── API <29：直接公共目录文件 ───────────────────────────────────────
    @Suppress("DEPRECATION") // targetSdk=28 下 getExternalStoragePublicDirectory 仍可用
    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RELATIVE_SUB)

    private fun writeViaLegacyFile(name: String, writeBlock: (OutputStream) -> Unit) {
        val dir = legacyDir()
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建外部备份目录")
        FileOutputStream(File(dir, name)).use(writeBlock)
    }

    @Suppress("DEPRECATION")
    private fun listViaLegacyFile(): List<Item> =
        legacyDir().listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending {
                it.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: it.lastModified()
            }
            ?.map { Item(Uri.fromFile(it), it.name, it.lastModified()) }
            ?: emptyList()
}
