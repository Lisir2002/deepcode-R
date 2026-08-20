package com.R.codecore.feature.agent.domain.skill

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDirectorySkillSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) : SkillSource, MutableSkillSource {

    private companion object {
        const val TAG = "LocalDirectorySkillSource"
        const val BUILTIN_MARKER = ".builtin"
    }

    /** 内置技能首启引导（普通辅助类，非 DI 节点，直接构造）。 */
    private val builtinSkillSeeder = BuiltinSkillSeeder(context, containerInstaller)

    override val skillsRoot: File by lazy {
        File(containerInstaller.rcodecoreDir, "skills").also { it.mkdirs() }
    }

    override fun listSkills(): List<Skill> {
        // 首启引导：确保内置技能资产已落地（幂等，缺失才补齐），再走统一扫描。
        builtinSkillSeeder.seedIfNeeded()
        if (!skillsRoot.exists()) return emptyList()
        val skillFiles = skillsRoot.walkTopDown()
            .maxDepth(4) // 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）
            .filter { it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true)) }
            .toList()

        return skillFiles.mapNotNull { file -> file.parentFile }
            .distinct() // 如果同一个目录下同时存在这两种文件，只解析一次
            .mapNotNull { dir ->
                (SkillParser.parse(dir, SkillSourceType.LOCAL))?.let {
                    // 带内置标记的目录识别为只读 BUILTIN 来源（首启由 BuiltinSkillSeeder 落地）。
                    if (File(dir, BUILTIN_MARKER).exists()) it.copy(source = SkillSourceType.BUILTIN) else it
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadInstructions(name: String): String? {
        return listSkills()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.instructions
    }

    override fun install(sourceDir: File): Skill? {
        if (!sourceDir.isDirectory) {
            FileLogger.w(TAG, "安装技能失败：源不是目录 ${sourceDir.absolutePath}")
            return null
        }
        // 先校验源目录可解析（含 SKILL.md 且 name 合法），不合法直接拒绝，避免写入半成品。
        val probe = SkillParser.parse(sourceDir, SkillSourceType.LOCAL)
            ?: run {
                FileLogger.w(TAG, "安装技能失败：源目录无合法 SKILL.md ${sourceDir.absolutePath}")
                return null
            }

        val target = File(skillsRoot, probe.id)
        // 内置技能只读：同名内置技能禁止覆盖式安装/更新（首启由 BuiltinSkillSeeder 落地）。
        if (target.exists() && File(target, BUILTIN_MARKER).exists()) {
            FileLogger.w(TAG, "拒绝覆盖内置技能（只读）: ${probe.id}")
            return null
        }
        try {
            // 目标已存在则先清理（覆盖式安装），保证幂等。
            if (target.exists()) target.deleteRecursively()
            sourceDir.copyRecursively(target, overwrite = true)
        } catch (e: Exception) {
            FileLogger.e(TAG, "安装技能失败（复制异常）: ${probe.id}", e)
            // 复制失败回滚：删除可能残留的半成品目录
            if (target.exists()) runCatching { target.deleteRecursively() }
            return null
        }

        val installed = SkillParser.parse(target, SkillSourceType.LOCAL)
        if (installed == null) {
            FileLogger.w(TAG, "安装技能后解析失败，回滚: ${probe.id}")
            runCatching { target.deleteRecursively() }
            return null
        }
        FileLogger.i(TAG, "技能已安装: ${installed.id} v${installed.version}")
        return installed
    }

    override fun uninstall(id: String): Boolean {
        val target = File(skillsRoot, id)
        if (!target.exists()) return true // 技能不存在视为成功
        // 内置技能只读：禁止卸载（首启由 BuiltinSkillSeeder 落地，带内置标记）。
        if (File(target, BUILTIN_MARKER).exists()) {
            FileLogger.w(TAG, "拒绝卸载内置技能（只读）: $id")
            return false
        }
        return try {
            target.deleteRecursively()
            FileLogger.i(TAG, "技能已卸载: $id")
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "卸载技能失败: $id", e)
            false
        }
    }

    override fun update(id: String, sourceDir: File): Skill? {
        if (!sourceDir.isDirectory) {
            FileLogger.w(TAG, "更新技能失败：源不是目录 ${sourceDir.absolutePath}")
            return null
        }
        val probe = SkillParser.parse(sourceDir, SkillSourceType.LOCAL)
            ?: run {
                FileLogger.w(TAG, "更新技能失败：源目录无合法 SKILL.md ${sourceDir.absolutePath}")
                return null
            }
        if (probe.id != id) {
            FileLogger.w(TAG, "更新技能失败：源 id(${probe.id}) 与目标 id($id) 不一致")
            return null
        }
        val target = File(skillsRoot, id)
        if (!target.exists()) {
            FileLogger.w(TAG, "更新技能失败：目标技能不存在 $id")
            return null
        }
        if (File(target, BUILTIN_MARKER).exists()) {
            FileLogger.w(TAG, "拒绝更新内置技能（只读）: $id")
            return null
        }
        return try {
            target.deleteRecursively()
            sourceDir.copyRecursively(target, overwrite = true)
            val updated = SkillParser.parse(target, SkillSourceType.LOCAL)
            FileLogger.i(TAG, "技能已更新: $id v${updated?.version}")
            updated
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新技能失败（复制异常）: $id", e)
            null
        }
    }
}
