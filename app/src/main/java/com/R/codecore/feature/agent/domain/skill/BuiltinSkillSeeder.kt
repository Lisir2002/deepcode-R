package com.R.codecore.feature.agent.domain.skill

import android.content.Context
import android.content.res.AssetManager
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import java.io.File

/**
 * 内置技能首启引导（Seeder）。
 *
 * 把随 App 预置的 `assets/skills`（每个子目录一个技能：SKILL.md + entry/ 等）在**首次**扫描时
 * 引导（copy）进本地技能根目录 `skillsRoot`，使内置技能与用户技能走同一 [SkillSource] 扫描链路。
 *
 * - **幂等**：目标目录已存在则跳过，绝不覆盖；已存在的 SDK 升级仅补齐"不存在的目录"，满足"首启"语义。
 * - **升级覆盖（内置技能内容随版本走）**：对已落地且带 `.builtin` 标记的内置技能，按 `SKILL.md`
 *   frontmatter 的 `version` 字段与 assets 侧比对：**不一致时重新落地为新版**（官方内容升级随 App 生效），
 *   一致则不动。这保证内置技能 bug 修复/功能演进能随新包自动到达用户，无需手动清理旧副本。
 * - **只读标记**：落地后在技能目录写入隐藏标记文件 `.builtin`，供 [LocalDirectorySkillSource] 识别为
 *   [SkillSourceType.BUILTIN]（只读、禁止卸载覆盖）。原生 assets 本身不可写，无需再做写保护。
 *
 * 说明：这是纯**辅助类（非 DI 节点）**，由 [LocalDirectorySkillSource] 直接构造。刻意不进 Hilt 图，
 * 避免「新增 @Inject 类在 KSP 同轮被引用却无法解析」的 Hilt 已知问题。
 */
class BuiltinSkillSeeder(
    private val context: Context,
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "BuiltinSkillSeeder"
        const val SKILLS_ASSET_DIR = "skills"
        const val BUILTIN_MARKER = ".builtin"
    }

    /** 本地技能根目录（与 LocalDirectorySkillSource 同源，均由 rcodecoreDir 派生）。 */
    private val skillsRoot: File by lazy {
        File(containerInstaller.rcodecoreDir, "skills").also { it.mkdirs() }
    }

    /**
     * 执行一次首启引导：检测到缺失的内置技能即从 assets 复制落地；
     * 已落地的内置技能按 `version` 比对，不一致时升级为新版。
     * 每次扫描调用都幂等可重入。返回本次实际补齐/升级的技能名列表（空表示无需处理）。
     */
    fun seedIfNeeded(): List<String> {
        return runCatching {
            val assetNames = context.assets.list(SKILLS_ASSET_DIR) ?: return emptyList()
            val seeded = mutableListOf<String>()
            for (name in assetNames) {
                // 只处理顶层目录（一个内置技能一个目录）；跳过资产中的散文件。
                if (!isAssetDir(SKILLS_ASSET_DIR, name)) continue
                val target = File(skillsRoot, name)
                val marker = File(target, BUILTIN_MARKER)
                if (target.exists() && marker.exists()) {
                    // 已落地过：仅当 assets 侧 version 与本地不一致时升级覆盖（内置技能官方升级）
                    if (shouldUpgradeBuiltin(name, target)) {
                        target.deleteRecursively() // 干净重建，避免旧文件残留
                        copyAssetDir(context.assets, listOf(SKILLS_ASSET_DIR, name), target, marker)
                        seeded += name
                        FileLogger.i(TAG, "内置技能已升级: $name -> v${assetVersionOf(name) ?: "?"}")
                    }
                    continue
                }
                if (target.exists()) {
                    // 已存在但无内置标记（可能是同名用户技能）：不覆盖，视为跳过，避免误删用户数据。
                    continue
                }
                copyAssetDir(context.assets, listOf(SKILLS_ASSET_DIR, name), target, marker)
                seeded += name
                FileLogger.i(TAG, "内置技能已落地: $name -> ${target.absolutePath}")
            }
            seeded
        }.onFailure { e ->
            FileLogger.e(TAG, "内置技能首启引导失败", e)
        }.getOrDefault(emptyList())
    }

    /**
     * 内置技能是否需要升级覆盖：**version 或内容 hash 任一不一致**即覆盖。
     *
     * 双保险：
     * - **version 比对**：官方语义化版本升级（显式 bump 时覆盖）。
     * - **内容 hash 比对**：即便忘记 bump version，只要技能内容（SKILL.md / entry / 其它资产）
     *   与 assets 侧不一致，也会覆盖升级。这从根上保证「改了技能内容就一定随新包到达老用户设备」，
     *   避免只改 frontmatter 字段（如新增 auto_trigger / trigger_keywords）却因 version 未变而永不生效。
     */
    private fun shouldUpgradeBuiltin(name: String, target: File): Boolean {
        val assetVersion = assetVersionOf(name) ?: return false
        val localVersion = fileVersionOf(File(target, "SKILL.md")) ?: return false
        if (assetVersion != localVersion) return true
        // 内容 hash 比对：两侧都按「相对技能目录的路径 + 文件内容」算，基准一致才能正确反映内容差异。
        val assetHash = assetDirContentHash(name)
        val localHash = localDirContentHash(target)
        return assetHash != localHash
    }

    /**
     * 计算 assets 侧某技能目录的「相对路径 + 内容」SHA-256 组合 hash。
     * 相对路径以技能目录为根（如 `SKILL.md`、`entry/run.sh`），与本地侧基准一致。
     */
    private fun assetDirContentHash(name: String): String? {
        val root = listOf(SKILLS_ASSET_DIR, name).joinToString("/")
        return runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            fun walk(path: String) {
                context.assets.list(path)?.forEach { child ->
                    val childPath = "$path/$child"
                    val subs = context.assets.list(childPath)
                    if (subs.isNullOrEmpty()) {
                        // 以「相对技能目录」的路径为 key（剥离 `skills/<name>/` 前缀）
                        val rel = childPath.removePrefix("$root/")
                        md.update(rel.toByteArray(Charsets.UTF_8))
                        md.update(0)
                        context.assets.open(childPath).use { input -> digestBytes(md, input) }
                    } else {
                        walk(childPath)
                    }
                }
            }
            walk(root)
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * 计算本地某技能目录的「相对路径 + 内容」SHA-256 组合 hash。
     * 相对路径以技能目录为根，与 assets 侧基准一致；排除 `.builtin` 只读标记文件。
     */
    private fun localDirContentHash(target: File): String? {
        return runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            target.walkTopDown()
                .filter { it.isFile && it.name != BUILTIN_MARKER }
                .sortedBy { it.relativeTo(target).path }
                .forEach { file ->
                    val rel = file.relativeTo(target).path
                    md.update(rel.toByteArray(Charsets.UTF_8))
                    md.update(0)
                    file.inputStream().use { input -> digestBytes(md, input) }
                }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** 把输入流内容全部喂给 MessageDigest。 */
    private fun digestBytes(md: java.security.MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    /** 读取 assets 侧技能 SKILL.md 的 frontmatter `version` 字段。 */
    private fun assetVersionOf(name: String): String? {
        return runCatching {
            context.assets.open("$SKILLS_ASSET_DIR/$name/SKILL.md").use { input ->
                extractVersion(input.bufferedReader().readText())
            }
        }.getOrNull()
    }

    /** 读取本地技能 SKILL.md 的 frontmatter `version` 字段。 */
    private fun fileVersionOf(skillFile: File): String? {
        if (!skillFile.isFile) return null
        return runCatching { extractVersion(skillFile.readText()) }.getOrNull()
    }

    /** 从 SKILL.md 文本中提取 frontmatter 的 `version: x.y.z`（兼容行首空白）。 */
    private fun extractVersion(text: String): String? {
        return text.lineSequence()
            .firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("version:")) {
                    trimmed.removePrefix("version:").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
    }

    private fun isAssetDir(prefix: String, name: String): Boolean {
        val path = buildAssetPath(prefix, name)
        val subs = context.assets.list(path) ?: return false
        // 资产目录 list 返回非空；SKILL.md 所在目录必有文件/子目录。
        return subs.isNotEmpty()
    }

    private fun copyAssetDir(assets: AssetManager, segments: List<String>, target: File, marker: File) {
        val path = segments.joinToString("/")
        val entries = assets.list(path) ?: return
        target.mkdirs()
        for (entry in entries) {
            val childSegs = segments + entry
            val isDir = (assets.list(childSegs.joinToString("/")) ?: emptyArray<String>()).isNotEmpty()
            val child = File(target, entry)
            if (isDir) {
                copyAssetDir(assets, childSegs, child, marker) // marker 只在根目录写一次
            } else {
                assets.open(childSegs.joinToString("/")).use { input ->
                    child.apply { parentFile?.mkdirs() }
                    child.outputStream().use { input.copyTo(it) }
                }
            }
        }
        marker.writeText("builtin-v1\n")
    }

    private fun buildAssetPath(prefix: String, child: String): String = "$prefix/$child"
}