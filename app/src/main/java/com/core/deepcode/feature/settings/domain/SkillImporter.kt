package com.core.deepcode.feature.settings.domain

import android.content.Context
import android.net.Uri
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.skill.LocalDirectorySkillSource
import com.core.deepcode.feature.agent.domain.skill.Skill
import com.core.deepcode.feature.agent.domain.skill.SkillParser
import com.core.deepcode.feature.agent.domain.skill.SkillScope
import com.core.deepcode.feature.agent.domain.skill.SkillSourceType
import com.core.deepcode.feature.agent.domain.skill.SkillStateRepository
import com.core.deepcode.feature.agent.domain.skill.SkillType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 技能导入管线（§4.3）：统一四种来源（ZIP / 单 MD 文件 / 粘贴文本 / URL 下载），
 * 归一化为临时技能目录，经「预校验 → 冲突检测 → 安装」三步落盘。
 *
 * 安全模型（§4.3.6）：导入仅做文件落盘，App 不执行任何导入内容中的脚本；
 * SCRIPT 技能仍需模型发起调用且经 [com.core.deepcode.feature.agent.domain.skill.SkillExecutor] 强制审批后才执行。
 *
 * 使用流程：
 * 1. [prepareFromZip] / [prepareFromMarkdown] / [prepareFromUrl] 产出 [SkillPrepareResult]（含解析预览与临时目录）。
 * 2. UI 展示预览与警告；非法直接阻断。
 * 3. 用户确认后 [commit] 做冲突检测并安装/覆盖。
 */
@Singleton
class SkillImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillStateRepository: SkillStateRepository
) {
    private companion object {
        const val TAG = "SkillImporter"
        const val SKILL_MD = "SKILL.md"
        const val CLAUDE_MD = "CLAUDE.md"
        const val BUILTIN_MARKER = ".builtin"
        const val MAX_ZIP_BYTES = 50L * 1024 * 1024 // 50MB
        const val MAX_ENTRIES = 2000
        const val MAX_URL_BYTES = 50L * 1024 * 1024
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
    }

    /** 解析预览：临时目录中的技能元信息 + 警告/非法原因。 */
    data class SkillImportPreview(
        val id: String,
        val name: String,
        val version: String,
        val type: SkillType,
        val scope: SkillScope,
        val description: String,
        val warnings: List<String>
    )

    /** 预校验结果：Ok=可进入冲突检测；Illegal=阻断（逐条错误）。 */
    sealed interface SkillPrepareResult {
        data class Ok(val preview: SkillImportPreview, val stageDir: File) : SkillPrepareResult
        data class Illegal(val errors: List<String>) : SkillPrepareResult
    }

    /** 提交结果。 */
    sealed interface SkillCommitResult {
        data class Installed(val skill: Skill, val updated: Boolean) : SkillCommitResult
        data class Conflict(val existingSource: SkillSourceType) : SkillCommitResult
        data class Failed(val message: String) : SkillCommitResult
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /** 生成一次性的临时技能目录（同一次导入复用，commit 后清理）。 */
    private fun newStageDir(): File =
        File(context.cacheDir, "skill_import_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}")
            .also { it.mkdirs() }

    // ───────────────────────── 四来源入口 ─────────────────────────

    /** ZIP 压缩包（仅单技能，§4.3.1）。 */
    suspend fun prepareFromZip(uri: Uri): SkillPrepareResult = withContext(Dispatchers.IO) {
        val stage = newStageDir()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext SkillPrepareResult.Illegal(listOf("无法读取所选文件"))
            extractZip(input, stage)
            validateSkillDir(stage)
        } catch (e: Exception) {
            FileLogger.w(TAG, "ZIP 导入失败", e)
            runCatching { stage.deleteRecursively() }
            SkillPrepareResult.Illegal(listOf("ZIP 解压失败: ${e.message ?: "未知错误"}"))
        }
    }

    /** 单 MD（文件选择 / 粘贴文本统一走此入口）。文件名去扩展名作为 id（D2）。 */
    suspend fun prepareFromMarkdown(fileName: String, content: String): SkillPrepareResult =
        withContext(Dispatchers.IO) {
            if (content.isBlank()) return@withContext SkillPrepareResult.Illegal(listOf("内容为空"))
            val stage = newStageDir()
            try {
                val id = sanitizeId(fileName.substringBeforeLast('.').ifBlank { "skill" })
                val dir = File(stage, id).also { it.mkdirs() }
                File(dir, SKILL_MD).writeText(content.trim())
                validateSkillDir(stage)
            } catch (e: Exception) {
                FileLogger.w(TAG, "MD 导入失败", e)
                runCatching { stage.deleteRecursively() }
                SkillPrepareResult.Illegal(listOf("导入失败: ${e.message ?: "未知错误"}"))
            }
        }

    /** URL 下载：按 Content-Type / 扩展名分流 zip / md（§4.3.3）。 */
    suspend fun prepareFromUrl(url: String): SkillPrepareResult = withContext(Dispatchers.IO) {
        val stage = newStageDir()
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SkillPrepareResult.Illegal(listOf("下载失败: HTTP ${response.code}"))
                }
                val body = response.body ?: return@withContext SkillPrepareResult.Illegal(listOf("下载内容为空"))
                val bytes = body.byteStream().use { it.readBytes() }
                if (bytes.size > MAX_URL_BYTES) {
                    return@withContext SkillPrepareResult.Illegal(listOf("文件超过 50MB 上限"))
                }
                val contentType = body.contentType()?.toString() ?: ""
                val lowerUrl = url.lowercase()
                val isZip = contentType.contains("zip") || lowerUrl.endsWith(".zip") ||
                    contentType.contains("octet-stream")
                val isMd = contentType.contains("markdown") || contentType.contains("text/plain") ||
                    lowerUrl.endsWith(".md") || lowerUrl.endsWith(".txt") || lowerUrl.endsWith(".markdown")
                when {
                    isZip -> {
                        extractZip(bytes.inputStream(), stage)
                        validateSkillDir(stage)
                    }
                    isMd -> {
                        val fileName = lowerUrl.substringAfterLast('/').ifBlank { "skill.md" }
                        prepareFromMarkdown(fileName, bytes.toString(Charsets.UTF_8))
                    }
                    else -> SkillPrepareResult.Illegal(listOf("无法识别的文件类型（仅支持 zip / md / txt）"))
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "URL 导入失败", e)
            runCatching { stage.deleteRecursively() }
            SkillPrepareResult.Illegal(listOf("下载失败: ${e.message ?: "未知错误"}"))
        }
    }

    // ───────────────────────── ZIP 解压与结构识别 ─────────────────────────

    private fun extractZip(input: java.io.InputStream, target: File) {
        ZipInputStream(input).use { zis ->
            var total = 0L
            var entries = 0
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entries++ > MAX_ENTRIES) throw IllegalStateException("条目数超限")
                val name = entry.name
                // Zip Slip 防护：拒绝绝对路径与 ../
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    throw IllegalStateException("非法路径: $name")
                }
                val outFile = File(target, name)
                if (!outFile.canonicalPath.startsWith(target.canonicalPath)) {
                    throw IllegalStateException("非法路径越界: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (zis.read(buf).also { read = it } != -1) {
                            total += read
                            if (total > MAX_ZIP_BYTES) throw IllegalStateException("超过 50MB 上限")
                            os.write(buf, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 结构识别：根目录直接含 SKILL.md/CLAUDE.md → 整包即技能；仅一个子目录含 → 剥一层；其他 → 非法。 */
    private fun locateSkillDir(stage: File): File? {
        val rootHasSkill = File(stage, SKILL_MD).exists() || File(stage, CLAUDE_MD).exists()
        if (rootHasSkill) return stage
        val candidateDirs = stage.listFiles()?.filter { it.isDirectory }?.filter { dir ->
            File(dir, SKILL_MD).exists() || File(dir, CLAUDE_MD).exists()
        } ?: emptyList()
        return if (candidateDirs.size == 1) candidateDirs.first() else null
    }

    /** 预校验分档（§4.3.4）：非法阻断 / 警告可继续 / 合法。 */
    private fun validateSkillDir(stage: File): SkillPrepareResult {
        val skillDir = locateSkillDir(stage)
            ?: return SkillPrepareResult.Illegal(listOf("未找到技能定义文件（需包含 SKILL.md 或 CLAUDE.md）"))

        val parsed = SkillParser.parse(skillDir, SkillSourceType.LOCAL)
            ?: return SkillPrepareResult.Illegal(listOf("SKILL.md 解析失败：frontmatter 格式不正确"))

        val warnings = mutableListOf<String>()
        if (parsed.name.isEmpty()) warnings.add("frontmatter 缺少 name，已回退使用目录名「${parsed.id}」")
        if (parsed.description.isBlank()) warnings.add("description 为空，建议补充技能描述")
        if (parsed.author.isNullOrBlank()) warnings.add("缺少 author")
        if (parsed.tags.isEmpty()) warnings.add("缺少 tags")

        // SCRIPT 校验：entry 指向的脚本必须存在。
        if (parsed.type == SkillType.SCRIPT && !parsed.entry.isNullOrBlank()) {
            val entryFile = File(skillDir, parsed.entry)
            if (!entryFile.isFile) {
                return SkillPrepareResult.Illegal(listOf("入口脚本不存在: ${parsed.entry}"))
            }
        }
        return SkillPrepareResult.Ok(
            preview = SkillImportPreview(
                id = parsed.id,
                name = parsed.name.ifBlank { parsed.id },
                version = parsed.version,
                type = parsed.type,
                scope = parsed.scope,
                description = parsed.description,
                warnings = warnings
            ),
            stageDir = stage
        )
    }

    // ───────────────────────── 冲突检测与安装 ─────────────────────────

    /**
     * 提交：冲突检测（§4.3.5）→ 安装/覆盖。
     *
     * @param overwrite 目标 id 已存在且为 LOCAL 技能时，是否覆盖更新（用户已确认）。
     * @return [SkillCommitResult.Conflict] 表示需用户进一步决策（BUILTIN 永远拒绝 / LOCAL 询问）。
     */
    suspend fun commit(stageDir: File, overwrite: Boolean): SkillCommitResult = withContext(Dispatchers.IO) {
        val probe = SkillParser.parse(stageDir, SkillSourceType.LOCAL)
            ?: return@withContext SkillCommitResult.Failed("技能解析失败")
        val target = File(skillStateRepository.skillsRoot(), probe.id)
        if (target.exists()) {
            if (File(target, BUILTIN_MARKER).exists()) {
                return@withContext SkillCommitResult.Conflict(SkillSourceType.BUILTIN)
            }
            if (!overwrite) {
                return@withContext SkillCommitResult.Conflict(SkillSourceType.LOCAL)
            }
        }
        val updated = target.exists()
        val installed = skillStateRepository.install(stageDir)
        runCatching { stageDir.deleteRecursively() }
        if (installed == null) return@withContext SkillCommitResult.Failed("安装失败")
        SkillCommitResult.Installed(installed, updated)
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "-").replace(Regex("-{2,}"), "-").trim('-').ifBlank { "skill" }
}
