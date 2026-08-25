package com.R.codecore.feature.agent.domain.ext

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件分发管理器（对齐 Claude Code plugins + DSH B3）。
 *
 * 插件 = 一个目录（`plugins/<name>/`）+ 极简 `plugin.json` manifest，内含声明式扩展：
 * - `commands/` 下的 `.md` 文件：声明式斜杠命令（并入 [ExtensionLoader] 命令扫描）；
 * - `hooks/hooks.json`：声明式 hooks（并入 [HookConfigLoader]）；
 * - `skills/` 与 `agents/` 下的 `.md` 文件：技能与子代理（未来接入，目录随包分发）。
 *
 * 来源两级（用户优先级更高）：
 * - 内置：`assets/ext/plugins/<name>/`（打包只读，随 App 升级更新）；
 * - 用户：`<rcodecore>/ext/plugins/<name>/`（zip 导入解压，可删除禁用）。
 *
 * 安全边界（对齐设计 B3）：插件**只能声明**扩展内容，**不能**注册任意原生代码；
 * zip 导入做 Zip Slip 防护（拒绝绝对路径 / `..` / 越界），并强制校验 manifest 合法。
 */
@Singleton
class PluginManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "PluginManager"
        const val ASSET_ROOT = "ext/plugins"
        const val MAX_ZIP_BYTES = 50L * 1024 * 1024
        const val MAX_ENTRIES = 200
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }

    /** 用户插件根目录 `<rcodecore>/ext/plugins/`（首次访问建目录）。 */
    val userPluginsDir: File by lazy {
        File(containerInstaller.rcodecoreDir, "ext/plugins").also { it.mkdirs() }
    }

    /** 全部已安装插件（内置 + 用户；用户覆盖同名内置）。 */
    fun installedPlugins(): List<PluginInfo> {
        val byName = LinkedHashMap<String, PluginInfo>()
        for (info in scanUserPlugins()) byName[info.name] = info
        // 内置优先级低，后写入（同名已被用户覆盖，跳过）
        for (info in scanBuiltinPlugins()) byName.putIfAbsent(info.name, info)
        return byName.values.sortedBy { it.name }
    }

    // ───────────────────────── 扫描 ─────────────────────────

    private fun scanBuiltinPlugins(): List<PluginInfo> {
        val names = try {
            context.assets.list(ASSET_ROOT)?.toList() ?: emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "枚举内置插件资产失败: ${e.message}", e)
            emptyList()
        }
        return names.filter { PluginManifest.isValidName(it) }.mapNotNull { name ->
            val manifest = readAssetManifest(name)
            if (manifest == null) {
                FileLogger.w(TAG, "内置插件 $name 缺少合法 plugin.json，忽略")
                null
            } else {
                PluginInfo(
                    name = manifest.name,
                    version = manifest.version,
                    description = manifest.description,
                    author = manifest.author,
                    provides = manifest.provides,
                    builtin = true
                )
            }
        }
    }

    private fun scanUserPlugins(): List<PluginInfo> {
        return userPluginsDir.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val manifest = readUserManifest(dir)
            if (manifest == null) {
                FileLogger.w(TAG, "用户插件 ${dir.name} 缺少合法 plugin.json，忽略")
                null
            } else {
                PluginInfo(
                    name = manifest.name,
                    version = manifest.version,
                    description = manifest.description,
                    author = manifest.author,
                    provides = manifest.provides,
                    builtin = false
                )
            }
        } ?: emptyList()
    }

    private fun readAssetManifest(name: String): PluginManifest? = try {
        val content = context.assets.open("$ASSET_ROOT/$name/${PluginManifest.MANIFEST_FILE}")
            .bufferedReader().use { it.readText() }
        PluginManifest.parse(content)?.takeIf { it.name == name }
    } catch (e: Exception) {
        null
    }

    private fun readUserManifest(dir: File): PluginManifest? {
        val file = File(dir, PluginManifest.MANIFEST_FILE)
        if (!file.isFile) return null
        val content = try {
            if (file.length() > MAX_MANIFEST_BYTES) return null
            file.readText()
        } catch (e: Exception) {
            null
        } ?: return null
        return PluginManifest.parse(content)?.takeIf { it.name == dir.name }
    }

    // ───────────────────────── 命令 / hooks 内容源（供 ExtensionLoader / HookConfigLoader 合并） ─────────────────────────

    /**
     * 聚合所有已安装插件的 `commands/` 下 `.md` 文件（key=文件名，value=内容），供命令扫描合并。
     * 优先级：用户插件 > 内置插件（同名文件用户版本覆盖）。
     */
    fun commandFiles(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (info in scanBuiltinPlugins()) {
            result.putAll(readPluginCommandFiles(info.name, builtin = true))
        }
        for (info in scanUserPlugins()) {
            result.putAll(readPluginCommandFiles(info.name, builtin = false))
        }
        return result
    }

    /** 聚合所有已安装插件的 `hooks/hooks.json` 文本，供 HookConfigLoader 合并（用户插件优先）。 */
    fun hookConfigs(): List<String> {
        val result = mutableListOf<String>()
        for (info in scanBuiltinPlugins()) {
            readPluginHookConfig(info.name, builtin = true)?.let { result.add(it) }
        }
        for (info in scanUserPlugins()) {
            readPluginHookConfig(info.name, builtin = false)?.let { result.add(it) }
        }
        return result
    }

    private fun readPluginCommandFiles(name: String, builtin: Boolean): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val entries = if (builtin) {
            try {
                context.assets.list("$ASSET_ROOT/$name/commands")?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            File(userPluginsDir, "$name/commands").listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()
        }
        for (fileName in entries.filter { it.endsWith(".md") }) {
            val content = if (builtin) {
                try {
                    context.assets.open("$ASSET_ROOT/$name/commands/$fileName").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            } else {
                runCatching { File(userPluginsDir, "$name/commands/$fileName").readText() }.getOrNull()
            }
            if (!content.isNullOrBlank()) result[fileName] = content
        }
        return result
    }

    private fun readPluginHookConfig(name: String, builtin: Boolean): String? {
        val hookName = "$ASSET_ROOT/$name/hooks/hooks.json"
        return if (builtin) {
            try {
                context.assets.open(hookName).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        } else {
            val file = File(userPluginsDir, "$name/hooks/hooks.json")
            runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        }
    }

    // ───────────────────────── 导入 / 删除 ─────────────────────────

    /**
     * 从 zip 字节导入插件到用户插件目录。
     * 结构识别：根目录直接含 plugin.json → 整包即插件；仅一个子目录含 → 剥一层；其他 → 非法。
     * Zip Slip 防护：拒绝绝对路径 / `..` / 越界；限条目数与总大小。
     */
    fun importFromZip(bytes: ByteArray): PluginImportResult {
        if (bytes.isEmpty()) return PluginImportResult.Invalid(listOf("zip 内容为空"))
        if (bytes.size > MAX_ZIP_BYTES) return PluginImportResult.Invalid(listOf("zip 超过 50MB 上限"))

        // 解压到临时目录（结构识别后再移动）
        val stage = File(userPluginsDir, ".stage-${System.currentTimeMillis()}")
        return try {
            stage.mkdirs()
            extractZip(bytes.inputStream(), stage)
            val pluginDir = locatePluginDir(stage)
            if (pluginDir == null) {
                return PluginImportResult.Invalid(listOf("zip 内未找到 plugin.json（根目录或仅一个子目录下）"))
            }
            val manifest = readUserManifest(pluginDir)
            if (manifest == null) {
                return PluginImportResult.Invalid(listOf("plugin.json 缺失或格式非法（需 name 与 version）"))
            }
            // 移动到位：覆盖同名旧插件
            val target = File(userPluginsDir, manifest.name)
            if (target.exists()) target.deleteRecursively()
            if (!pluginDir.renameTo(target)) {
                // rename 跨同盘应成功；失败回退复制
                copyRecursively(pluginDir, target)
            }
            val info = PluginInfo(
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                author = manifest.author,
                provides = manifest.provides,
                builtin = false
            )
            PluginImportResult.Success(info)
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件导入失败: ${e.message}", e)
            PluginImportResult.Invalid(listOf("导入失败: ${e.message ?: "未知错误"}"))
        } finally {
            runCatching { stage.deleteRecursively() }
        }
    }

    /** 删除用户插件（禁用）；内置插件不可删（返回 false）。 */
    fun remove(name: String): Boolean {
        if (!PluginManifest.isValidName(name)) return false
        val dir = File(userPluginsDir, name)
        if (!dir.isDirectory) return false
        return runCatching {
            dir.deleteRecursively()
            FileLogger.d(TAG, "删除插件 $name")
            true
        }.getOrDefault(false)
    }

    // ───────────────────────── 内部工具 ─────────────────────────

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

    /** 结构识别：根目录直接含 plugin.json → 整包即插件；仅一个子目录含 → 剥一层；其他 → null。 */
    private fun locatePluginDir(stage: File): File? {
        if (File(stage, PluginManifest.MANIFEST_FILE).isFile) return stage
        val candidateDirs = stage.listFiles()?.filter { it.isDirectory }?.filter { dir ->
            File(dir, PluginManifest.MANIFEST_FILE).isFile
        } ?: emptyList()
        return if (candidateDirs.size == 1) candidateDirs.first() else null
    }

    private fun copyRecursively(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child -> copyRecursively(child, File(dst, child.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }
}

/** 已安装插件信息（供设置页展示 / 导入结果回显）。 */
data class PluginInfo(
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val provides: List<String>,
    /** true=打包内置；false=用户导入（可删除）。 */
    val builtin: Boolean
)

/** zip 导入结果。 */
sealed interface PluginImportResult {
    data class Success(val info: PluginInfo) : PluginImportResult
    data class Invalid(val errors: List<String>) : PluginImportResult
}
