package com.R.codecore.feature.agent.domain.sop

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.skill.SkillParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一份已解析的 SOP 标准作业资产（D4-1，对齐 norm-chain-design.md §3.2）。
 *
 * 与 [com.R.codecore.feature.agent.domain.prompt.AgentAsset] 解耦（独立结构），字段定稿：
 * - [name]：规则名（frontmatter `name` 优先，否则文件名去后缀，如 `10-release`）。
 * - [order]：排序（frontmatter `order` 优先，否则文件名数字前缀）。
 * - [whenToUse]：适用场景一句话（frontmatter `whenToUse`，摘要常驻注入用）。
 * - [body]：结构化编号步骤正文（操作 + 判定 + 产出/出错处理）。
 * - [path]：资产源路径。
 */
data class SopAsset(
    val name: String,
    val order: Int,
    val whenToUse: String,
    val body: String,
    val path: String
)

/**
 * SOP 标准作业注册表（D4-1/D4-3，对齐 norm-chain-design.md §3.2）：
 *
 * - **资产位置**：`~/.rcodecore/sop/`（内置默认副本，启动由 [ContainerInstaller.extractSop] 全量释放）。
 * - **复用 frontmatter 解析**：复用 [SkillParser.splitAndParseFrontmatter] 解析
 *   `name` / `order` / `whenToUse` 元数据；正文为结构化编号步骤。
 * - **热加载**：复用 [com.R.codecore.feature.agent.domain.prompt.AgentAssetCore] 的 mtime 懒刷新机制
 *   （读取时比对文件指纹，变了才重扫）。
 * - **摘要常驻 + 按需取正文**（与 D3 分层规则共享形态约定，独立实现）：摘要（名称 + whenToUse）
 *   经 [com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider] 全量常驻注入；
 *   完整正文经 `loadSop` 工具按需加载。
 *
 * 纯解析逻辑收敛在 [SopAssetCore]（不依赖 Android，可 JVM 单测）；本类负责 Hilt 装配。
 */
@Singleton
class SopRegistry @Inject constructor(
    containerInstaller: ContainerInstaller
) {
    private val core = SopAssetCore(
        sopDir = { File(containerInstaller.rcodecoreDir, SOP_DIR) },
        readFile = { file -> runCatching { file.readText() }.getOrNull() }
    )

    /** 全部 SOP 资产，按 order 升序（编号步骤流程）。 */
    fun all(): List<SopAsset> = core.all()

    /** 按名称精确查找（loadSop 用）；不存在返回 null。 */
    fun findByName(name: String): SopAsset? = core.findByName(name)

    private companion object {
        const val SOP_DIR = "sop"
        const val TAG = "SopRegistry"
    }
}

/**
 * [SopRegistry] 的纯解析核心（无 Android 依赖，JVM 可测）。
 *
 * mtime 懒刷新：对每个文件维护解析缓存 + 文件指纹，读取时先比对指纹，未变则复用缓存，
 * 变了才重扫 —— 对齐 [com.R.codecore.feature.agent.domain.prompt.AgentAssetCore] 做法。
 */
internal class SopAssetCore(
    private val sopDir: () -> File,
    private val readFile: (File) -> String?,
    private val skillParser: SkillParser = SkillParser
) {
    private data class FileStamp(val mtime: Long, val size: Long)

    private val lock = Any()
    private val cachedStamp = HashMap<String, FileStamp>()
    private val cached = HashMap<String, SopAsset>()

    /** 全部 SOP 资产，按 order 升序。 */
    fun all(): List<SopAsset> {
        val dir = sopDir()
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedBy { it.name }
                ?: emptyList()
        }.getOrElse { emptyList() }
        return files.mapNotNull { file -> load(file) }
            .sortedWith(compareBy<SopAsset> { it.order }.thenBy { it.name })
    }

    fun findByName(name: String): SopAsset? {
        val target = name.trim()
        if (target.isBlank()) return null
        return all().firstOrNull { it.name == target }
    }

    private fun load(file: File): SopAsset? {
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

    private fun parse(file: File, content: String): SopAsset {
        val (frontmatter, rawBody) = skillParser.splitAndParseFrontmatter(content)
        val body = rawBody.trim()
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        val order = frontmatter["order"]?.toString()?.toIntOrNull() ?: numericPrefix(file.name)
        val whenToUse = frontmatter["whenToUse"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.take(WHEN_TO_USE_CHARS).replace('\n', ' ')
        return SopAsset(
            name = name,
            order = order,
            whenToUse = whenToUse,
            body = body,
            path = file.path
        )
    }

    private fun numericPrefix(fileName: String): Int =
        fileName.substringBefore('-').toIntOrNull() ?: Int.MAX_VALUE

    private companion object {
        const val WHEN_TO_USE_CHARS = 120
    }
}
