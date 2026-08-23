package com.R.codecore.feature.agent.domain.prompt

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.skill.SkillParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个 prompt 资产的解析结果：frontmatter 元数据 + 正文。
 *
 * 无 frontmatter 的文件（R04 迁移前的存量）自动回退：name 取文件名去后缀、
 * order 取文件名数字前缀（如 `00-identity.md` → 0）、enabled=true、agent=false、
 * modes=default，保证迁移前的装配结果与硬编码顺序一致。
 */
data class AgentAsset(
    val fileName: String,
    val name: String,
    val description: String,
    val order: Int,
    val enabled: Boolean,
    val agent: Boolean,
    val modes: Set<String>,
    val tools: List<String>,
    val model: String,
    val includes: List<String>,
    val body: String
)

/**
 * 方向 #1 Agent 声明式定义 —— 资产注册表（R03 核心，design 第 8 节）。
 *
 * 扫描 `~/.rcodecore/prompts/`（内置默认副本，启动由 [ContainerInstaller.extractPrompts] 全量释放）
 * 与 `~/.rcodecore/prompts.custom/`（用户覆盖，同名即覆盖），解析 Markdown frontmatter
 * （name/description/order/enabled/agent/mode/tools/model/includes），按 order 排序组装。
 *
 * - **主 agent 组件**：`agent: false`（默认），参与系统提示词正文装配（R04 接入）。
 * - **专项 agent**：`agent: true`，可被 `/agent <name>` 触发（R04 落地）。
 * - **includes 组合引用**：按 name 引用其它资产，正文递归拼接（带循环引用防护）。
 * - **热加载双机制**：mtime 懒刷新（主，读取时比对目录指纹）+ FileObserver（辅，增删改即失效缓存）；
 *   FileObserver 被系统回收 / inotify 上限超限时，mtime 懒刷新仍兜底生效。
 *
 * 纯解析逻辑收敛在 [AgentAssetCore]（不依赖 Android，可 JVM 单测）；本类负责 Hilt 装配
 * 与 FileObserver 辅助机制。
 */
@Singleton
class AgentAssetRegistry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller
) {

    private val core = AgentAssetCore(
        promptsDir = File(containerInstaller.rcodecoreDir, "prompts"),
        customDir = File(containerInstaller.rcodecoreDir, "prompts.custom"),
        assetsList = { listAssets() },
        assetsRead = { name -> readAsset(name) }
    )

    init {
        // 热加载辅机制：FileObserver 监听目录增删改 → 失效缓存。自含 HandlerThread，无需外部接线。
        startWatching()
    }

    /** 主 agent 组件（enabled 且 agent:false），按 order 排序、includes 已展开。 */
    fun components(): List<AgentAsset> = core.components()

    /** 专项 agent（enabled 且 agent:true），按 order 排序、includes 已展开。 */
    fun agents(): List<AgentAsset> = core.agents()

    /** 全部资产（含 disabled 与专项 agent），按 order 排序、includes 已展开。 */
    fun all(): List<AgentAsset> = core.all()

    /** 按 name 精确查找（不存在返回 null）。 */
    fun findByName(name: String): AgentAsset? = core.findByName(name)

    // —— 热加载辅机制：FileObserver（幂等，自含 HandlerThread；失败仅降级，mtime 兜底） ——
    @Volatile private var watcherStarted = false

    @Synchronized
    private fun startWatching() {
        if (watcherStarted) return
        val thread = HandlerThread("AgentAssetRegistry-Watcher")
        runCatching {
            thread.start()
            val handler = Handler(thread.looper)
            val dirs = listOf(core.promptsDir, core.customDir)
            for (dir in dirs) {
                runCatching { dir.mkdirs() }
                handler.post {
                    runCatching {
                        val observer = object : FileObserver(dir, OBSERVE_EVENTS) {
                            override fun onEvent(event: Int, path: String?) {
                                core.invalidate()
                            }
                        }
                        observer.startWatching()
                    }.onFailure {
                        FileLogger.w(TAG, "FileObserver 监听失败 ${dir.path}: ${it.message}", it)
                    }
                }
            }
        }.onFailure {
            FileLogger.w(TAG, "FileObserver 线程启动失败（mtime 懒刷新兜底）: ${it.message}", it)
        }
        watcherStarted = true
    }

    private fun listAssets(): List<String> = try {
        context.assets.list("prompts")?.toList() ?: emptyList()
    } catch (e: Exception) {
        FileLogger.w(TAG, "枚举内置 prompts 失败: ${e.message}", e)
        emptyList()
    }

    private fun readAsset(name: String): String? = try {
        context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "AgentAssetRegistry"
        const val OBSERVE_EVENTS = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }
}

/**
 * [AgentAssetRegistry] 的纯解析核心（无 Android 依赖，JVM 可测）。
 *
 * 目录指纹 = 各目录下 `.md` 文件的 (mtime, size) 映射（custom 同名覆盖），每次读取先比对指纹，
 * 未变则复用缓存，变了才重扫 —— 对齐 [com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider.ProjectRuleSource]
 * 的 mtime 懒刷新做法。
 */
internal class AgentAssetCore(
    internal val promptsDir: File,
    internal val customDir: File,
    private val assetsList: () -> List<String> = { emptyList() },
    private val assetsRead: (String) -> String? = { null }
) {
    private data class FileStamp(val mtime: Long, val size: Long)

    private val lock = Any()
    private var cachedStamp: Map<String, FileStamp>? = null
    private var cached: List<AgentAsset>? = null

    /** 主 agent 组件（enabled 且 agent:false），按 order 排序、includes 已展开。 */
    fun components(): List<AgentAsset> = load().filter { it.enabled && !it.agent }

    /** 专项 agent（enabled 且 agent:true），按 order 排序、includes 已展开。 */
    fun agents(): List<AgentAsset> = load().filter { it.enabled && it.agent }

    /** 全部资产（含 disabled 与专项 agent），按 order 排序、includes 已展开。 */
    fun all(): List<AgentAsset> = load()

    /** 按 name 精确查找（不存在返回 null）。 */
    fun findByName(name: String): AgentAsset? = load().firstOrNull { it.name == name }

    /** 失效缓存（FileObserver 事件 / 主动刷新用）；下次读取强制重扫。 */
    fun invalidate() {
        synchronized(lock) {
            cachedStamp = null
            cached = null
        }
    }

    private fun load(): List<AgentAsset> {
        val stamp = buildStamp()
        synchronized(lock) {
            val c = cached
            if (c != null && cachedStamp == stamp) return c
            val parsed = scan(stamp)
            cachedStamp = stamp
            cached = parsed
            return parsed
        }
    }

    private fun buildStamp(): Map<String, FileStamp> {
        val result = LinkedHashMap<String, FileStamp>()
        fun addDir(dir: File) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return
            for (f in files) result[f.name] = FileStamp(f.lastModified(), f.length())
        }
        addDir(promptsDir)
        addDir(customDir) // 自定义同名覆盖（mtime 取自定义文件）
        if (result.isEmpty()) {
            // 本地目录尚未释放（极早期启动）：兜底内置 assets 清单（静态资产，时间戳固定）
            for (name in assetsList()) if (name.endsWith(".md")) result[name] = FileStamp(0L, 0L)
        }
        return result
    }

    private fun scan(stamp: Map<String, FileStamp>): List<AgentAsset> {
        val assets = LinkedHashMap<String, AgentAsset>()
        for (name in stamp.keys) {
            val content = readContent(name) ?: continue
            val asset = parseAsset(name, content) ?: continue
            assets[asset.name] = asset
        }
        // includes 递归展开正文（循环引用防护）
        val resolved = assets.values.map { a ->
            a.copy(body = resolveBody(a.name, assets, LinkedHashSet()))
        }
        return resolved.sortedWith(compareBy<AgentAsset> { it.order }.thenBy { it.name })
    }

    /** 三级优先级读取正文：prompts.custom/ > prompts/ > assets 内置。 */
    private fun readContent(name: String): String? {
        val customFile = File(customDir, name)
        if (customFile.isFile) {
            return runCatching { customFile.readText() }
                .onFailure { FileLogger.w(TAG, "读取自定义资产失败 $name: ${it.message}", it) }
                .getOrNull()
        }
        val defaultFile = File(promptsDir, name)
        if (defaultFile.isFile) {
            return runCatching { defaultFile.readText() }
                .onFailure { FileLogger.w(TAG, "读取本地资产失败 $name: ${it.message}", it) }
                .getOrNull()
        }
        return assetsRead(name)
    }

    private fun parseAsset(fileName: String, content: String): AgentAsset? {
        val (frontmatter, rawBody) = SkillParser.splitAndParseFrontmatter(content)
        val body = rawBody.replace(LEADING_COMMENT, "").trim()
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fileName.removeSuffix(".md")
        return AgentAsset(
            fileName = fileName,
            name = name,
            description = frontmatter["description"]?.toString()?.trim() ?: "",
            order = frontmatter["order"]?.toString()?.toIntOrNull() ?: numericPrefix(fileName),
            enabled = boolOf(frontmatter["enabled"], default = true),
            agent = boolOf(frontmatter["agent"], default = false),
            modes = stringList(frontmatter["mode"]).toSet().ifEmpty { setOf("default") },
            tools = stringList(frontmatter["tools"]),
            model = frontmatter["model"]?.toString()?.trim() ?: "",
            includes = stringList(frontmatter["includes"]),
            body = body
        )
    }

    /** 递归展开 includes：返回被引用资产正文（先序）+ 自身正文，循环引用跳过并告警。 */
    private fun resolveBody(
        name: String,
        all: Map<String, AgentAsset>,
        chain: MutableSet<String>
    ): String {
        val asset = all[name] ?: return ""
        if (!chain.add(name)) {
            // 循环引用：跳过该 include（不重复展开），防无限递归
            FileLogger.w(TAG, "includes 循环引用，跳过展开: $name")
            return ""
        }
        val included = asset.includes.mapNotNull { includedName ->
            resolveBody(includedName, all, chain).takeIf { it.isNotBlank() }
        }
        chain.remove(name)
        return (included + asset.body).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun numericPrefix(fileName: String): Int {
        PREFIX_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Int.MAX_VALUE
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
        is String -> value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun boolOf(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> default
    }

    private companion object {
        const val TAG = "AgentAssetCore"
        val PREFIX_REGEX = Regex("^(\\d+)[-_]")
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
