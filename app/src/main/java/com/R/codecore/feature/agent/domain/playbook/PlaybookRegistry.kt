package com.R.codecore.feature.agent.domain.playbook

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.permission.SandboxMode
import com.R.codecore.feature.agent.domain.prompt.AgentAssetRegistry
import com.R.codecore.feature.agent.domain.skill.SkillParser
import com.R.codecore.feature.agent.domain.sop.SopRegistry
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playbook 剧本编排注册表（D5-1，对齐 norm-chain-design.md §3.3.1）：
 *
 * - **资产位置**：`~/.rcodecore/playbooks/`（内置默认副本，启动由 [ContainerInstaller.extractPlaybooks] 全量释放）。
 * - **复用 frontmatter 解析**：复用 [SkillParser.splitAndParseFrontmatter] 解析 `name` / `description` /
 *   `stages`（YAML 列表）元数据；stages 字段定稿（§3.3.1）：`name / description(阶段目标) / agents[] /
 *   sop[] / gates(approval|auto) / guards(timeout)`，另含 §3.6 的 `async` / `sandbox`（三档权限）/
 *   `seed`（spawn|fork）。
 * - **热加载**：复用 [com.R.codecore.feature.agent.domain.prompt.AgentAssetCore] 的 mtime 懒刷新机制
 *   （读取时比对文件指纹，变了才重扫）。
 * - **存在性校验**：解析时校验 `agents[]`（专项 agent 资产）与 `sop[]`（SOP 资产）引用存在性，
 *   缺失仅告警不阻断（执行时按名称解析失败会给出可读提示）。
 * - **不并入 AgentAssetRegistry**（审计定稿）：独立注册表只扫 `assets/playbooks/`，避免目录/kind 混用。
 *
 * 纯解析逻辑收敛在 [PlaybookAssetCore]（不依赖 Android，可 JVM 单测）；本类负责 Hilt 装配 + 引用校验。
 */
@Singleton
class PlaybookRegistry @Inject constructor(
    containerInstaller: ContainerInstaller,
    private val agentAssetRegistry: AgentAssetRegistry,
    private val sopRegistry: SopRegistry
) {
    private val core = PlaybookAssetCore(
        playbookDir = { File(containerInstaller.rcodecoreDir, PLAYBOOK_DIR) },
        readFile = { file -> runCatching { file.readText() }.getOrNull() }
    )

    /** 全部剧本资产，按文件名排序（稳定）。 */
    fun all(): List<PlaybookAsset> = core.all()

    /** 按名称精确查找（playbook_start / /playbook 命令用）；不存在返回 null。 */
    fun findByName(name: String): PlaybookAsset? = core.findByName(name)

    /** 剧本清单摘要（名称 + 简介一句话），`/playbook` 无参命令与 playbook_start 工具描述用。 */
    fun listSummaries(): String {
        val all = all()
        if (all.isEmpty()) return "（暂无可用剧本资产）"
        return all.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无简介）" }}" }
    }

    /** 解析时校验 stages 引用的 agents/sop 存在性，缺失仅告警（不阻断解析）。 */
    private fun validateRefs(asset: PlaybookAsset) {
        val agentNames = runCatching { agentAssetRegistry.agents().map { it.name }.toSet() }.getOrElse { emptySet() }
        val sopNames = runCatching { sopRegistry.all().map { it.name }.toSet() }.getOrElse { emptySet() }
        asset.stages.forEach { stage ->
            stage.agents.filterNot { it in agentNames }.forEach { missing ->
                FileLogger.w(TAG, "剧本 ${asset.name} 阶段 ${stage.name} 引用专项 agent「$missing」不存在")
            }
            stage.sop.filterNot { it in sopNames }.forEach { missing ->
                FileLogger.w(TAG, "剧本 ${asset.name} 阶段 ${stage.name} 引用 SOP「$missing」不存在")
            }
        }
    }

    private companion object {
        const val PLAYBOOK_DIR = "playbooks"
        const val TAG = "PlaybookRegistry"
    }
}

/**
 * [PlaybookRegistry] 的纯解析核心（无 Android 依赖，JVM 可测）。
 *
 * mtime 懒刷新：对每个文件维护解析缓存 + 文件指纹，读取时先比对指纹，未变则复用缓存，
 * 变了才重扫 —— 对齐 [com.R.codecore.feature.agent.domain.prompt.AgentAssetCore] 做法。
 */
internal class PlaybookAssetCore(
    private val playbookDir: () -> File,
    private val readFile: (File) -> String?,
    private val skillParser: SkillParser = SkillParser
) {
    private data class FileStamp(val mtime: Long, val size: Long)

    private val lock = Any()
    private val cachedStamp = HashMap<String, FileStamp>()
    private val cached = HashMap<String, PlaybookAsset>()

    /** 全部剧本资产，按文件名排序（稳定）。 */
    fun all(): List<PlaybookAsset> {
        val dir = playbookDir()
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedBy { it.name }
                ?: emptyList()
        }.getOrElse { emptyList() }
        return files.mapNotNull { file -> load(file) }
    }

    fun findByName(name: String): PlaybookAsset? {
        val target = name.trim()
        if (target.isBlank()) return null
        return all().firstOrNull { it.name == target }
    }

    private fun load(file: File): PlaybookAsset? {
        if (!file.isFile || !file.canRead()) return null
        val key = file.absolutePath
        val stamp = FileStamp(file.lastModified(), file.length())
        synchronized(lock) {
            if (cachedStamp[key] == stamp) return cached[key]
            val content = readFile(file) ?: return null
            val asset = parse(file, content)
            cachedStamp[key] = stamp
            cached[key] = asset
            return asset
        }
    }

    private fun parse(file: File, content: String): PlaybookAsset {
        val (frontmatter, _) = skillParser.splitAndParseFrontmatter(content)
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val description = frontmatter["description"]?.toString()?.trim() ?: ""
        val stages = parseStages(frontmatter["stages"])
        return PlaybookAsset(
            name = name,
            description = description,
            stages = stages,
            path = file.path
        )
    }

    /** 解析 YAML 列表 stages（SnakeYAML 嵌套解析，对齐 §3.3.1 精简字段）。 */
    private fun parseStages(raw: Any?): List<PlaybookStage> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { item ->
            val map = (item as? Map<*, *>) ?: return@mapNotNull null
            val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val description = map["description"]?.toString()?.trim() ?: ""
            val agents = stringList(map["agents"])
            val sop = stringList(map["sop"])
            val gates = PlaybookGate.parse(map["gates"]?.toString())
            val async = boolOf(map["async"], default = false)
            val sandbox = SandboxMode.parse(map["sandbox"]?.toString()) ?: SandboxMode.READ_ONLY
            val seed = PlaybookSeed.parse(map["seed"]?.toString())
            val guardsTimeoutMs = parseGuardsTimeout(map["guards"])
            PlaybookStage(
                name = name,
                description = description,
                agents = agents,
                sop = sop,
                gates = gates,
                async = async,
                sandbox = sandbox,
                seed = seed,
                guardsTimeoutMs = guardsTimeoutMs
            )
        }
    }

    /** 解析 `guards: { timeout: 60000 }`（§3.3.1 guards(timeout)）。 */
    private fun parseGuardsTimeout(raw: Any?): Long? {
        val map = raw as? Map<*, *> ?: return null
        return (map["timeout"]?.toString()?.toLongOrNull())
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString() }.map { it.trim() }.filter { it.isNotBlank() }
        is String -> value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun boolOf(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> default
    }
}
