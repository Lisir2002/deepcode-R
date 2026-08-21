package com.R.codecore.feature.settings.domain

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.R.codecore.BuildConfig
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.skill.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能导出（§4.9/D13）：把 LOCAL 技能目录打包为 `{id}-v{version}.zip`，
 * 写入应用缓存目录并提供系统分享 Intent。内置技能（BUILTIN）只读，不可导出。
 */
@Singleton
class SkillExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SkillExporter"
    }

    /** 打包技能目录为 zip，返回生成的 zip 文件（缓存目录）。失败返回 null。 */
    fun exportToZip(skill: Skill): File? {
        val dir = skill.dir ?: return null
        if (!dir.isDirectory) {
            FileLogger.w(TAG, "导出失败：技能目录不存在 ${skill.id}")
            return null
        }
        val zipFile = File(context.cacheDir, "${skill.id}-v${skill.version}.zip")
        return try {
            ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
                dir.walkTopDown().forEach { f ->
                    if (!f.isFile) return@forEach
                    val rel = f.relativeTo(dir).path
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            FileLogger.i(TAG, "技能已导出: ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            FileLogger.e(TAG, "导出技能失败: ${skill.id}", e)
            runCatching { zipFile.delete() }
            null
        }
    }

    /** 系统分享技能 zip（FileProvider 授权，复用 file_paths 的 cache-path）。 */
    fun shareZip(skill: Skill): Boolean {
        val zipFile = exportToZip(skill) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "分享技能「${skill.name}」")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "分享技能失败: ${skill.id}", e)
            false
        }
    }
}
