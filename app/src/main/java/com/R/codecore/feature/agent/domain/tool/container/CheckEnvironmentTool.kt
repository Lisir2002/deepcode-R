package com.R.codecore.feature.agent.domain.tool.container

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import com.R.codecore.feature.workspace.domain.DelegatingFileAccess
import com.R.codecore.feature.workspace.domain.FileAccessProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface CheckEnvironmentToolEntryPoint {
    fun fileAccess(): FileAccessProvider
}

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
    private val workspaceRepository: WorkspaceRepository,
    @ApplicationContext private val appContext: dagger.Lazy<Context>
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

        /**
         * 默认探测组件：**不再硬编码全量 14 项**。
         *
         * 改为调用 [inferComponentsFromProjectStack]，通过扫描当前工作区的标志性文件
         * （build.gradle / pom.xml / package.json / Cargo.toml / go.mod 等）动态推断
         * 项目实际需要的构建组件，避免 Android 项目、Rust 项目、Go 项目、前端项目
         * 被无谓检测为"构建环境未就绪（缺 Java/Gradle/Android SDK…）"。
         */
        val DEFAULT_COMPONENTS: List<String>
            get() = _lastInferred ?: GENERIC_DEFAULTS

        /** 项目栈无任何特征标识时的通用兜底（仅保留跨项目通用的基础工具）。 */
        private val GENERIC_DEFAULTS: List<String> = listOf(
            "Git"
        )

        @Volatile
        private var _lastInferred: List<String>? = null

        /** 更新项目栈推断结果（供 [inferComponentsFromProjectStack] 写入，execute 读取）。 */
        internal fun updateInferredStack(components: List<String>?) {
            _lastInferred = components?.ifEmpty { GENERIC_DEFAULTS }
        }

        /**
         * 构建链路核心组件：旁路探测（BUILD 类命令后自动触发）的兜底集合，
         * 仅在命令无法推断出所需组件时使用。作为 [DEFAULT_COMPONENTS] 的子风格保持一致。
         */
        val BUILD_CORE_COMPONENTS: List<String> = listOf(
            "Java", "Gradle", "Android SDK", "Android NDK"
        )

        /** 标志性文件名 → 需要探测的构建组件（用于从工作区推断项目栈）。 */
        private val PROJECT_MARKERS: List<Pair<String, List<String>>> = listOf(
            // Android / Gradle 项目
            "settings.gradle.kts" to listOf("Java", "Gradle", "Android SDK", "Android NDK"),
            "settings.gradle" to listOf("Java", "Gradle", "Android SDK", "Android NDK"),
            "build.gradle.kts" to listOf("Java", "Gradle"),
            "build.gradle" to listOf("Java", "Gradle"),
            "local.properties" to listOf("Android SDK"),
            // Maven / 纯 Java
            "pom.xml" to listOf("Java", "Maven"),
            // Node 前端 / 后端
            "package.json" to listOf("Node", "npm"),
            "pnpm-lock.yaml" to listOf("Node", "npm"),
            "yarn.lock" to listOf("Node", "npm"),
            "package-lock.json" to listOf("Node", "npm"),
            // Python
            "requirements.txt" to listOf("Python"),
            "pyproject.toml" to listOf("Python"),
            "Pipfile" to listOf("Python"),
            "setup.py" to listOf("Python"),
            // Go
            "go.mod" to listOf("Go"),
            // Rust
            "Cargo.toml" to listOf("Cargo"),
            // PHP
            "composer.json" to listOf("PHP"),
            // C / C++ / CMake
            "CMakeLists.txt" to listOf("CMake"),
            "Makefile" to listOf("CMake"),
            // Docker
            "Dockerfile" to listOf("Docker"),
            "docker-compose.yml" to listOf("Docker"),
            "docker-compose.yaml" to listOf("Docker")
        )

        /**
         * 从当前工作区根目录扫描标志性文件，推断项目实际需要的构建组件列表。
         *
         * 设计原则：**宁可少探测、不可乱探测**——无法识别的项目就只检测通用 Git，
         * 不把 Java/Gradle/Android SDK 等无关项渲染成"缺失"状态打扰用户。
         */
        fun inferComponentsFromProjectStack(workspacePath: String, fileAccess: FileAccessProvider): List<String> {
            val names = runCatching {
                fileAccess.listFiles(workspacePath).map { it.name }
            }.getOrElse { emptyList() }
            val nameSet = names.toSet()
            val result = LinkedHashSet<String>()
            // Git 是几乎所有项目都需要的基础工具，默认带上
            result.add("Git")
            for ((marker, components) in PROJECT_MARKERS) {
                if (marker in nameSet) {
                    components.forEach { result.add(it) }
                }
            }
            FileLogger.d(TAG, "项目栈推断(${workspacePath.takeLast(40)}): $result")
            return result.toList().ifEmpty { GENERIC_DEFAULTS }
        }

        /**
         * 包管理器 token → 对应构建组件名。
         * 例如 `pip install` 只说明需要 Python，不需要探测 pip 本身；
         * `apk add` 只说明在安装依赖，不代表项目构建一定需要 apk。
         */
        private val PACKAGE_MANAGER_TO_COMPONENT: Map<String, String?> = mapOf(
            "apk" to null,
            "apt" to null,
            "apt-get" to null,
            "dpkg" to null,
            "rpm" to null,
            "yum" to null,
            "dnf" to null,
            "pacman" to null,
            "pip" to "Python",
            "pip3" to "Python",
            "pip2" to "Python",
            "npm" to "npm",
            "npx" to "Node",
            "yarn" to "npm",
            "pnpm" to "npm",
            "composer" to "PHP",
            "go" to "Go",         // 包管理 + 构建
            "cargo" to "Cargo"
        )

        /** 命令首 token（小写） → 规范化后的已知组件名。 */
        private val TOKEN_TO_COMPONENT: Map<String, String> = mapOf(
            "java" to "Java",
            "javac" to "Java",
            "gradle" to "Gradle",
            "gradlew" to "Gradle",
            "sdkmanager" to "Android SDK",
            "ndk-build" to "Android NDK",
            "mvn" to "Maven",
            "mvnw" to "Maven",
            "python" to "Python",
            "python3" to "Python",
            "python2" to "Python",
            "node" to "Node",
            "nodejs" to "Node",
            "npm" to "npm",
            "yarn" to "npm",
            "pnpm" to "npm",
            "php" to "PHP",
            "composer" to "PHP",
            "git" to "Git",
            "go" to "Go",
            "cargo" to "Cargo",
            "rustc" to "Cargo",
            "docker" to "Docker",
            "docker-compose" to "Docker",
            "cmake" to "CMake",
            "make" to "CMake",
            "gcc" to "CMake",
            "g++" to "CMake",
            "clang" to "CMake",
            "clang++" to "CMake"
        )

        /**
         * 从命令推断「当前所需构建环境」组件列表（开放、不写死）。
         *
         * 关键修复：
         * - 包管理器 token（apk/apt）不返回自身作为组件，避免探测与项目构建无关的包管理器名；
         * - 命令程序名规范化为已知组件名（python3→Python、node→Node），确保 UI 展示和 bin 映射一致；
         * - 段首 rawToken 必须是「合理的程序名」：纯数字（`head -1` 中的 `1`、`tail -n 4` 中的 `4` 段首被错误解析时）、
         *   以 `-` 开头的 flag（`--version`、`-n` 被当段首）、绝对/相对路径（`/tmp/x.log`）、含 `.ext` 的路径片段
         *   一律过滤为非组件，避免 UI 出现名为「1」「2」「/tmp/sdk.log」的无意义「缺失」项（BusyBox 管道输出段曾被误拆为段首）。
         */
        fun inferComponentsFromCommand(command: String): List<String> {
            if (command.isBlank()) return emptyList()
            val result = LinkedHashSet<String>()
            val analysis = com.R.codecore.feature.agent.domain.permission.ShellCommandParser.analyze(command)
            for (segment in analysis.segments) {
                val effective = segment.dropWhile { ENV_ASSIGN.matches(it) }
                val rawToken = effective.firstOrNull()
                    ?.removePrefix("./")
                    ?.substringAfterLast('/')
                    ?.lowercase()
                    ?.trim()
                    ?: continue
                if (rawToken.isBlank()) continue
                if (looksLikeNonProgramToken(rawToken)) continue
                // 1. 已知映射优先（规范化组件名）
                val normalized = TOKEN_TO_COMPONENT[rawToken]
                if (normalized != null) {
                    result.add(normalized)
                    continue
                }
                // 2. 是包管理器 token：按映射决定是否返回对应构建组件（pip→Python，apk→跳过）
                if (PACKAGE_MANAGER_TO_COMPONENT.containsKey(rawToken)) {
                    val mapped = PACKAGE_MANAGER_TO_COMPONENT[rawToken]
                    if (mapped != null) result.add(mapped)
                    continue
                }
                // 3. 其他：作为"开放探测"组件名保留（如 ./my-custom-build.sh）
                result.add(rawToken)
            }
            return result.toList()
        }

        private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

        /**
         * rawToken 看起来像「非程序名」应直接跳过：纯数字、flag、路径、常见日志后缀、空壳。
         * 宁可少探测不可乱探测——命中 1 次 "1" 的「缺失」条目比漏探测一个罕见 build.sh 要难看得多。
         */
        private fun looksLikeNonProgramToken(raw: String): Boolean {
            if (raw.isEmpty()) return true
            // 纯数字（head/seq/tail 的参数、管道中的段）
            if (raw.all { it.isDigit() }) return true
            // flag（--version、-n 之类）
            if (raw.startsWith("-")) return true
            // 绝对/相对路径（含 / 的路径片段；取 basename 后仍含 / 一般不可能）
            if (raw.contains('/')) return true
            // 典型路径/文件后缀：日志/临时/脚本路径文本，不会是程序名
            if (raw.endsWith(".log") || raw.endsWith(".txt") ||
                raw.endsWith(".tmp") || raw.endsWith(".out") ||
                raw.endsWith(".err") || raw.endsWith(".json") ||
                raw.endsWith(".zip") || raw.endsWith(".apk")
            ) return true
            // 完全由非字母数字符号组成（| & ; 的边界残片）
            if (raw.none { it.isLetterOrDigit() }) return true
            return false
        }

        /**
         * 把 "--version" 的结果里"看起来像 stderr 报错"的内容清空，避免污染 UI 的「版本号」列。
         * 脚本层已用退出码 rc==0 做了主保护；这里做解析层兜底，防止 BusyBox 工具在某些情况下
         * 退出码仍为 0 但输出仍然是 Usage/unrecognized option 时（如旧脚本版本回退/未来回归）
         * 把报错文本当「installed 版本号」展示。只做启发式，误伤面为 0（清空即不显示版本号）。
         *
         * 访问级别：internal + @PublishedApi，方便同包测试与外层断言。
         */
        private val STDERR_TELLTALES = listOf(
            "unrecognized option",
            "invalid option",
            "unknown option",
            "Usage:",
            "Usage :",
            "illegal option",
            "try --help",
            "Try '--help'",
            "option requires an argument",
            "Bad port",
            "No such file or directory",
            "Permission denied"
        )

        internal fun sanitizeVersion(ver: String): String {
            val v = ver.trim()
            if (v.isEmpty()) return ""
            val lower = v.lowercase()
            if (STDERR_TELLTALES.any { sig -> sig.lowercase() in lower }) return ""
            // BusyBox 的 Usage 段典型 "multi-call binary" 前缀也像报错
            if ("multi-call binary" in lower) return ""
            return v
        }
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
            // 关键修复：components 为空时 按项目栈启发式推断，不再探测全量 14 项无关组件
            val workspacePath = workspaceRepository.currentPath()
            val components: List<String> = if (requested.isNullOrEmpty()) {
                val fileAccess = runCatching {
                    EntryPointAccessors.fromApplication(
                        appContext.get(),
                        CheckEnvironmentToolEntryPoint::class.java
                    ).fileAccess()
                }.getOrNull()
                val inferred = if (fileAccess != null) {
                    inferComponentsFromProjectStack(workspacePath, fileAccess)
                } else {
                    GENERIC_DEFAULTS
                }
                updateInferredStack(inferred)
                inferred
            } else {
                requested
            }

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
                projectPath = workspacePath,
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
                local raw_ver rc
                # 用临时文件捕获 stderr/stdout 与真实退出码；管道 `| head` 会把 $? 覆盖为 head 的退出码，
                # 导致 BusyBox 工具无 --version 支持时的错误 stderr 被误判为版本号（退出码 0）。
                raw_ver="$("${'$'}bin" --version 2>&1)"
                rc="${'$'}?"
                if [ "${'$'}rc" -eq 0 ]; then
                  local ver
                  ver="$(printf '%s\n' "${'$'}raw_ver" | grep -v '^${'$'}' | head -1)"
                else
                  # 程序不支持 --version（典型 BusyBox 工具 tail/grep 等）：留空 version，
                  # 不让 stderr 的 "unrecognized option: version" 被 UI 当版本号展示为「已安装」。
                  ver=""
                fi
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
                    val version = sanitizeVersion(parts.getOrElse(3) { "" })
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
