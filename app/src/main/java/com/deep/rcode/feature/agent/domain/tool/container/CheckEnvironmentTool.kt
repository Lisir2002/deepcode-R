package com.deep.rcode.feature.agent.domain.tool.container

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.CommandEngine
import com.deep.rcode.feature.agent.domain.tool.AgentTool
import com.deep.rcode.feature.agent.domain.tool.ParameterType
import com.deep.rcode.feature.agent.domain.tool.ToolCapability
import com.deep.rcode.feature.agent.domain.tool.ToolParameter
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.deep.rcode.feature.agent.domain.tool.ToolResult
import com.deep.rcode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 环境探测工具：检查当前执行环境（本地 Linux 容器或远程 SSH 服务器）中已安装的
 * 构建/开发组件，返回结构化 JSON，供模型判断「缺什么、装什么」以及 UI 渲染环境总览卡片。
 *
 * 探测命令批量执行（一次往返），输出为 `NAME|STATUS|PATH|VERSION` 管道分隔行，
 * 由本工具解析为结构化 JSON：
 * ```json
 * {
 *   "os": "Alpine Linux v3.21",
 *   "arch": "aarch64",
 *   "components": [
 *     {"name": "Java", "status": "installed", "path": "/usr/bin/java", "version": "openjdk 17.0.12"},
 *     {"name": "Gradle", "status": "missing", "path": null, "version": null}
 *   ]
 * }
 * ```
 */
class CheckEnvironmentTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool() {

    // ── C-1 结果缓存：环境少变，同一 components 请求在 TTL 内直接复用上次探测结果 ──
    @Volatile
    private var cachedComponents: List<String>? = null
    @Volatile
    private var cachedResult: ToolResult? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    companion object {
        const val TAG = "CheckEnvironmentTool"
        const val PROBE_TIMEOUT_MS = 60_000L

        /** 探测结果缓存 TTL：避免 AI 在 30s 内重复执行 60s 的批量探测。 */
        const val CACHE_TTL_MS = 30_000L

        /** 默认探测的组件：构建链路优先，随后是通用开发组件。 */
        val DEFAULT_COMPONENTS: List<String> = listOf(
            "Java", "Gradle", "Android SDK", "Android NDK", "Maven",
            "Python", "Node", "npm", "PHP", "Git", "Go", "Cargo", "Docker", "CMake"
        )

        /**
         * 构建链路核心组件：旁路探测（构建/环境变更命令后自动触发）的兜底集合，
         * 仅在命令无法推断出所需组件时使用。作为 [DEFAULT_COMPONENTS] 的子集，单一数据源。
         */
        val BUILD_CORE_COMPONENTS: List<String> = listOf(
            "Java", "Gradle", "Android SDK", "Android NDK"
        )

        /**
         * 从命令推断「当前所需构建环境」组件列表（开放、不写死）。
         *
         * 复用 [com.deep.rcode.feature.agent.domain.permission.ShellCommandParser.analyze] 拆段
         * （引号感知、`&&`/`;`/`|` 分隔、环境赋值跳过），逐段取首 token（可执行程序名，
         * 去 `./` 前缀、取 basename、小写化）作为所需构建环境组件名。
         *
         * 不依赖任何硬编码映射：任何命令都能推断出所需环境（如 `php -S` → `php`、
         * `python3 app.py` → `python3`、`node server.js` → `node`），开放支持任意构建环境。
         * 返回去重后的程序名列表；无法推断时返回空列表。
         */
        fun inferComponentsFromCommand(command: String): List<String> {
            if (command.isBlank()) return emptyList()
            val result = LinkedHashSet<String>()
            val analysis = com.deep.rcode.feature.agent.domain.permission.ShellCommandParser.analyze(command)
            for (segment in analysis.segments) {
                val effective = segment.dropWhile { ENV_ASSIGN.matches(it) }
                val tool = effective.firstOrNull()
                    ?.removePrefix("./")
                    ?.substringAfterLast('/')
                    ?.lowercase()
                    ?.trim()
                    ?: continue
                if (tool.isBlank()) continue
                result.add(tool)
            }
            return result.toList()
        }

        private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
    }

    override val name = "check_environment"
    override val description = "检查当前执行环境（本地 Linux 容器或远程 SSH 服务器）中已安装的开发/构建组件（Java、Gradle、Android SDK、Python、Node、Git 等），返回结构化 JSON 列表（组件名、状态 installed/missing、路径、版本）。适合在安装依赖前后调用，确认环境状态。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "components" to ToolParameter(
            name = "components",
            type = ParameterType.ARRAY,
            description = "要探测的组件名列表（如 [\"Java\", \"Gradle\"]）。不填则探测全部默认组件。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val requested = (args["components"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }?.distinct()
            val components = if (requested.isNullOrEmpty()) DEFAULT_COMPONENTS else requested

            // C-1 缓存命中：components 一致且未超过 TTL，直接复用上次探测结果（附 cached=true 标记）
            val now = System.currentTimeMillis()
            val cachedData = (cachedResult as? ToolResult.Success)?.data as? JsonObject
            if (cachedComponents == components && cachedData != null && now - cachedAtMs < CACHE_TTL_MS) {
                FileLogger.d(TAG, "环境探测缓存命中 (components=${components.joinToString(",")})")
                return ToolResult.Success(withCachedFlag(cachedData, cached = true))
            }

            val script = buildProbeScript(components)
            FileLogger.d(TAG, "探测环境组件: ${components.joinToString(",")}")
            val output = commandEngine.runCommandSync(
                command = script,
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = PROBE_TIMEOUT_MS
            )
            val parsed = parseProbeOutput(output, components)
            FileLogger.v(TAG, "探测完成: ${parsed.size} 个组件")
            val result = ToolResult.Success(withCachedFlag(parsed, cached = false))
            cachedComponents = components
            cachedResult = result
            cachedAtMs = now
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "环境探测失败", e)
            ToolResult.Error("环境探测失败: ${e.message}")
        }
    }

    /** 给探测结果 JSON 附加 "cached" 布尔字段（缓存命中为 true）。 */
    private fun withCachedFlag(json: JsonObject, cached: Boolean): JsonObject =
        JsonObject(json + ("cached" to JsonPrimitive(cached)))

    /** 构建批量探测脚本：一次往返探测全部组件，输出 `NAME|STATUS|PATH|VERSION` 行。 */
    private fun buildProbeScript(components: List<String>): String {
        val sb = StringBuilder()
        // 注意：三引号原生字符串中 \ 不是转义字符，必须用 ${'$'} 生成字面 $ 符号。
        // 例如 "${'$'}bin" 在运行时输出 "$bin"（shell 变量引用）。
        sb.append(
            """
            set +e
            probe() {
              local name="${'$'}1" bin="${'$'}2"
              if command -v "${'$'}bin" >/dev/null 2>&1; then
                local path="${'$'}(command -v "${'$'}bin")"
                local ver="${'$'}("${'$'}bin" --version 2>&1 | grep -v '^${'$'}' | head -1)"
                echo "${'$'}name|installed|${'$'}path|${'$'}ver"
              else
                echo "${'$'}name|missing||"
              fi
            }
            """.trimIndent()
        )
        sb.append("\n")
        components.forEach { name ->
            val bin = binFor(name)
            if (bin != null) {
                sb.append("probe \"$name\" \"$bin\"\n")
            } else {
                sb.append("echo \"$name|missing||\"\n")
            }
        }
        // Android SDK 特殊：sdkmanager 可能不在 PATH，但 ANDROID_HOME 已设置
        if (components.contains("Android SDK")) {
            sb.append(
                """
                if [ -n "${'$'}ANDROID_HOME" ] && [ -d "${'$'}ANDROID_HOME" ]; then
                  echo "Android SDK|installed|${'$'}ANDROID_HOME|${'$'}(ls "${'$'}ANDROID_HOME/platforms" 2>/dev/null | tr '\n' ' ')"
                fi
                """.trimIndent()
            )
            sb.append("\n")
        }
        // 系统信息
        sb.append("echo \"__OS__|${'$'}(cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d= -f2 | tr -d '\"')\"\n")
        sb.append("echo \"__ARCH__|${'$'}(uname -m 2>/dev/null)\"\n")
        return sb.toString()
    }

    /** 组件名 → 探测用的可执行文件名。动态推断的组件名（命令首 token）直接返回自身，开放检测任意程序。 */
    private fun binFor(name: String): String? = when (name) {
        "Java" -> "java"
        "Gradle" -> "gradle"
        "Android SDK" -> "sdkmanager"
        "Android NDK" -> "ndk-build"
        "Maven" -> "mvn"
        "Python" -> "python3"
        "Node" -> "node"
        "npm" -> "npm"
        "PHP" -> "php"
        "Git" -> "git"
        "Go" -> "go"
        "Cargo" -> "cargo"
        "Docker" -> "docker"
        "CMake" -> "cmake"
        // 动态推断的组件名：组件名即可执行文件名，直接探测（如 php、python3、node、composer 等）。
        else -> name
    }

    /** 解析探测脚本输出为结构化 JSON。 */
    private fun parseProbeOutput(output: String, components: List<String>): JsonObject {
        val found = mutableMapOf<String, Triple<String, String, String>>() // name -> (status, path, version)
        var os = ""
        var arch = ""
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split("|", limit = 4)
            when {
                parts.size >= 2 && parts[0] == "__OS__" -> os = parts.getOrElse(1) { "" }
                parts.size >= 2 && parts[0] == "__ARCH__" -> arch = parts.getOrElse(1) { "" }
                parts.size >= 4 -> {
                    val name = parts[0]
                    val status = parts[1]
                    val path = parts[2]
                    val version = parts.getOrElse(3) { "" }
                    // 已存在（如 Android SDK 的 ANDROID_HOME 补充行）时保留非 missing 状态
                    if (status == "installed" || !found.containsKey(name)) {
                        found[name] = Triple(status, path, version)
                    }
                }
            }
        }
        val componentJson = components.map { name ->
            val (status, path, version) = found[name] ?: Triple("missing", "", "")
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(name),
                    "status" to JsonPrimitive(status),
                    "path" to (if (path.isBlank()) JsonNull else JsonPrimitive(path)),
                    "version" to (if (version.isBlank()) JsonNull else JsonPrimitive(version))
                )
            )
        }
        return JsonObject(
            mapOf(
                "os" to (if (os.isBlank()) JsonNull else JsonPrimitive(os)),
                "arch" to (if (arch.isBlank()) JsonNull else JsonPrimitive(arch)),
                "components" to JsonArray(componentJson)
            )
        )
    }
}
