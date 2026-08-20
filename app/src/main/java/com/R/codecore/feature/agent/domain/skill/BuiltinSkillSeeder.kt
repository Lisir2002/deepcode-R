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
 * - **幂等**：目标目录已存在则跳过，绝不覆盖；已存在的 SDK 升级仅补齐“不存在的目录”，满足“首启”语义。
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
     * 执行一次首启引导：检测到缺失的内置技能即从 assets 复制落地。
     * 每次扫描调用都幂等可重入。返回本次实际补齐的技能名列表（空表示无需补齐/无内置资产）。
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
                if (target.exists() && marker.exists()) continue // 首启已落地过，不覆盖
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