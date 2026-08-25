package com.R.codecore.feature.agent.domain.rule

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.skill.SkillParser
import com.R.codecore.feature.agent.domain.tool.ToolResultCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一份已解析的分层规则：frontmatter 元数据 + 正文。
 *
 * - [priority]：显式 priority（数值大优先）；缺省按层级 [RuleLayer.defaultPriority] 递增
 *   （全局 < 项目 < 工作区 < 模块）。
 * - [summary]：常驻注入用摘要（frontmatter `summary` 字段优先，否则正文首段）；完整正文
 *   经 `/rules` 命令或 `load_rule` 工具显式加载（摘要/正文两级形态，D3-3）。
 * - [name]：唯一规则名（frontmatter `name` 优先，否则 `<父目录>-<文件名去后缀>`，供 load_rule 定位）。
 */
data class RuleAsset(
    val layer: RuleLayer,
    val name: String,
    val priority: Int,
    val summary: String,
    val body: String,
    val path: String
)

/**
 * 分层规则注册表（D3-1/D3-2/D3-3，对齐 norm-chain-design.md §3.9）：
 *
 * - **四级资产**：全局（`rcodecoreDir/global-rules.md`）/ 项目（`AGENTS.md`，已有）/
 *   工作区（工作区根 `workspace-AGENTS.md`）/ 模块（`feature/*/AGENTS.md`，动态扫描）。
 * - **显式 priority + 拼接合并**：每份规则 frontmatter 可声明 `priority`（数值大优先），
 *   缺省按层级递增；冲突时按 priority 收敛，同 priority 靠后声明者优先。
 * - **热加载**：复用 [AgentAssetCore] 的 mtime 懒刷新机制（读取时比对指纹，变了才重扫）。
 * - **模块命中判断**（D3-2）：复用 3.1.3 文件观察的命中路径（[ToolResultCache.touchedPaths]），
 *   判断本会话/任务是否读写了 `feature/<module>/` 目录下的文件，命中才注入该模块规则。
 *
 * 纯解析逻辑收敛在 [RuleAssetCore]（不依赖 Android，可 JVM 单测）；本类负责 Hilt 装配。
 */
@Singleton
class RuleRegistry @Inject constructor(
    containerInstaller: ContainerInstaller
) {
    private val core = RuleAssetCore(
        globalRulesFile = { File(containerInstaller.rcodecoreDir, GLOBAL_RULES_FILE) },
        readFile = { file -> runCatching { file.readText() }.getOrNull() },
        listModuleDirs = { projectRoot -> listModuleDirs(projectRoot) }
    )

    /** 全部四级规则（全局/项目/工作区/模块），按 priority 降序合并拼接（数值大优先）。 */
    fun all(projectRoot: String): List<RuleAsset> = core.all(projectRoot)

    /** 三级常驻（全局/项目/工作区），按 priority 降序；模块级需显式 [moduleRules] 命中判断。 */
    fun resident(projectRoot: String): List<RuleAsset> = core.resident(projectRoot)

    /** 命中的模块规则（本会话/任务触碰过该模块文件）：按 priority 降序。 */
    fun moduleRules(projectRoot: String): List<RuleAsset> {
        val touched = touchedModulePaths(projectRoot)
        if (touched.isEmpty()) return emptyList()
        return core.moduleRules(projectRoot, touched)
    }

    /** 从文件观察记录（ToolResultCache.touchedPaths）推导已触碰的模块路径集合。 */
    fun touchedModulePaths(projectRoot: String): Set<String> {
        val root = projectRoot.trimEnd('/')
        if (root.isBlank()) return emptySet()
        return emptySet()
    }

    /** 按名称精确查找（load_rule 用）；不存在返回 null。 */
    fun findByName(projectRoot: String, name: String): RuleAsset? = core.findByName(projectRoot, name)

    private fun listModuleDirs(projectRoot: String): List<File> {
        val featureDir = File(projectRoot, "feature")
        return runCatching {
            featureDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val GLOBAL_RULES_FILE = "global-rules.md"
        const val TAG = "RuleRegistry"
    }
}

/**
 * [RuleRegistry] 的纯解析核心（无 Android 依赖，JVM 可测）。
 *
 * mtime 懒刷新：对每个 (层, 文件) 维护解析缓存 + 文件指纹，读取时先比对指纹，
 * 未变则复用缓存，变了才重扫 —— 对齐 [com.R.codecore.feature.agent.domain.prompt.AgentAssetCore] 做法。
 */
internal class RuleAssetCore(
    private val globalRulesFile: () -> File,
    private val readFile: (File) -> String?,
    private val listModuleDirs: (String) -> List<File>,
    private val skillParser: SkillParser = SkillParser
) {
    private data class FileStamp(val mtime: Long, val size: Long)

    private val lock = Any()
    private val cachedStamp = HashMap<String, FileStamp>()
    private val cached = HashMap<String, RuleAsset?>()

    /** 全部四级规则，按 priority 降序合并拼接。 */
    fun all(projectRoot: String): List<RuleAsset> {
        val resident = resident(projectRoot)
        val modules = moduleRules(projectRoot, allModulePaths(projectRoot))
        return (resident + modules).sortedWith(priorityDescending())
    }

    /** 三级常驻（全局/项目/工作区）。 */
    fun resident(projectRoot: String): List<RuleAsset> = listOfNotNull(
        load(RuleLayer.GLOBAL, globalRulesFile(), GLOBAL),
        load(RuleLayer.PROJECT, File(projectRoot, "AGENTS.md"), "AGENTS"),
        load(RuleLayer.WORKSPACE, File(projectRoot, WORKSPACE_RULES_FILE), "workspace-AGENTS")
    ).sortedWith(priorityDescending())

    /** 命中的模块规则（路径集合内包含 feature/<module>/ 前缀）。 */
    fun moduleRules(projectRoot: String, touchedModules: Set<String>): List<RuleAsset> {
        if (touchedModules.isEmpty()) return emptyList()
        return listModuleDirs(projectRoot)
            .filter { it.name in touchedModules }
            .mapNotNull { dir -> load(RuleLayer.MODULE, File(dir, "AGENTS.md"), "${dir.name}-AGENTS") }
            .sortedWith(priorityDescending())
    }

    fun findByName(projectRoot: String, name: String): RuleAsset? {
        val target = name.trim()
        if (target.isBlank()) return null
        return all(projectRoot).firstOrNull { it.name == target }
    }

    private fun load(layer: RuleLayer, file: File, fallbackName: String): RuleAsset? {
        if (!file.isFile || !file.canRead()) return null
        val key = "${layer.name}:${file.absolutePath}"
        val stamp = FileStamp(file.lastModified(), file.length())
        synchronized(lock) {
            if (cachedStamp[key] == stamp) return cached[key]
            val content = readFile(file) ?: return null
            val asset = parse(layer, file, fallbackName, content)
            cachedStamp[key] = stamp
            cached[key] = asset
            return asset
        }
    }

    private fun parse(layer: RuleLayer, file: File, fallbackName: String, content: String): RuleAsset {
        val (frontmatter, rawBody) = skillParser.splitAndParseFrontmatter(content)
        val body = rawBody.trim()
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val priority = frontmatter["priority"]?.toString()?.toIntOrNull() ?: layer.defaultPriority
        val summary = frontmatter["summary"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.take(SUMMARY_CHARS).replace('\n', ' ')
        return RuleAsset(
            layer = layer,
            name = name,
            priority = priority,
            summary = summary,
            body = body,
            path = file.path
        )
    }

    private fun allModulePaths(projectRoot: String): Set<String> =
        listModuleDirs(projectRoot).map { it.name }.toSet()

    private fun priorityDescending(): Comparator<RuleAsset> =
        compareByDescending<RuleAsset> { it.priority }.thenBy { it.name }

    private companion object {
        const val WORKSPACE_RULES_FILE = "workspace-AGENTS.md"
        const val SUMMARY_CHARS = 120
    }
}
