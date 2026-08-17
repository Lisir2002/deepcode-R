package com.R.codecore.feature.agent.domain.container.progress

/**
 * 包管理器安装进度统一解析：把 apt / apk / pip / sdkmanager 等安装命令的
 * 逐行 stdout 解析为结构化的进度信息，供 UI 渲染「进度条 + 当前组件」。
 *
 * 设计原则：
 * - 纯函数、无副作用、无协程，与 [ApkStdoutParser] 同风格；
 * - 每种包管理器一个专用解析器（[InstallProgressParser] 实现），由
 *   [InstallProgressParsers.parserFor] 按命令内容路由；
 * - 解析不出结构化进度的行返回 null，UI 退化为「进行中」状态。
 */

/** 安装阶段（统一抽象，与容器初始化的 [InstallPhase] 解耦）。 */
enum class InstallPhaseType {
    /** 下载依赖包。 */
    DOWNLOAD,
    /** 解压/安装包。 */
    INSTALL,
    /** 完成。 */
    DONE,
    /** 失败。 */
    FAILED,
}

/**
 * 一行安装输出解析出的进度信息。
 * [percent] 为 0.0..1.0，null 表示无法估算（UI 显示跑马灯/进行中）。
 * [currentPackage] 为当前正在处理的包名（下载或安装中）。
 */
data class InstallProgress(
    val phase: InstallPhaseType,
    val percent: Float? = null,
    val currentPackage: String? = null,
    /** 当前行的可读描述（如「正在下载 openjdk-17-jdk」）。 */
    val detail: String? = null,
) {
    val isDone: Boolean get() = phase == InstallPhaseType.DONE
    val isError: Boolean get() = phase == InstallPhaseType.FAILED
}

/** 单个包管理器的进度解析器。 */
interface InstallProgressParser {
    /** 该解析器是否适用于给定命令（按命令内容判断包管理器）。 */
    fun matches(command: String): Boolean

    /** 解析一行输出；无法解析返回 null。 */
    fun parse(line: String): InstallProgress?

    /**
     * 判断工具结束后的完整输出是否表示安装成功完成。
     * 默认取最后一行解析并检查 DONE；apt 等无明确完成行的包管理器可覆写。
     * 新增包管理器解析器时覆写此方法，即可接入「完成播报」能力（开放式接口）。
     */
    fun isCompleted(result: String): Boolean {
        val lastLine = result.lineSequence().lastOrNull { it.isNotBlank() } ?: return false
        return parse(lastLine)?.isDone == true
    }
}

/** 各包管理器解析器 + 路由。 */
object InstallProgressParsers {

    /** 根据命令内容选择解析器；无法识别返回 null（UI 退化为进行中）。 */
    fun parserFor(command: String): InstallProgressParser? {
        val c = command.trim().lowercase()
        return when {
            c.contains("sdkmanager") || c.contains("sdk --install") -> SdkmanagerProgressParser
            c.contains("pip install") || c.contains("pip3 install") || c.contains("uv pip") -> PipProgressParser
            c.contains("apt-get") || c.contains("apt install") || c.contains("apt-get install") -> AptProgressParser
            c.contains("apk add") || c.contains("apk --") || c.contains("apk update") -> ApkProgressParser
            else -> null
        }
    }
}

/**
 * apk（Alpine）解析器：复用 [ApkStdoutParser] 的结构化语义。
 * 输出格式：
 *   fetch URL/pkg.apk 4.2 MiB/s
 *   (1/24) Installing musl (1.2.5-r0)
 *   OK: 24 packages, 15 MiB download, 80 MiB installed.
 */
object ApkProgressParser : InstallProgressParser {
    override fun matches(command: String): Boolean {
        val c = command.trim().lowercase()
        return c.contains("apk add") || c.contains("apk --") || c.contains("apk update")
    }

    override fun parse(line: String): InstallProgress? {
        return when (val parsed = ApkStdoutParser.parse(line)) {
            is ApkStdoutParser.Parsed -> when (val s = parsed.semantic) {
                is ApkStdoutParser.Semantic.Fetch -> InstallProgress(
                    phase = InstallPhaseType.DOWNLOAD,
                    currentPackage = s.pkgName,
                    detail = if (s.isIndex) "更新软件源索引" else "正在下载 ${s.pkgName}"
                )
                is ApkStdoutParser.Semantic.Installing -> InstallProgress(
                    phase = InstallPhaseType.INSTALL,
                    percent = if (s.total > 0) s.n.toFloat() / s.total else null,
                    currentPackage = s.pkg,
                    detail = "正在安装 ${s.pkg}"
                )
                is ApkStdoutParser.Semantic.Ok -> InstallProgress(
                    phase = InstallPhaseType.DONE,
                    percent = 1f,
                    detail = "完成：${s.packages} 个包"
                )
                is ApkStdoutParser.Semantic.Error -> InstallProgress(
                    phase = InstallPhaseType.FAILED,
                    detail = s.reason
                )
                is ApkStdoutParser.Semantic.PostLine -> InstallProgress(
                    phase = InstallPhaseType.INSTALL,
                    detail = s.text
                )
                null -> null
            }
        }
    }
}

/**
 * apt（Debian/Ubuntu）解析器。
 * 输出格式：
 *   Get:1 http://.../openjdk-17-jdk amd64 17.0.12 [2,345 kB]
 *   Preparing to unpack .../openjdk-17-jdk_17.0.12_amd64.deb ...
 *   Unpacking openjdk-17-jdk (17.0.12) ...
 *   Setting up openjdk-17-jdk (17.0.12) ...
 *   Processing triggers for ca-certificates ...
 */
object AptProgressParser : InstallProgressParser {
    private val RE_GET = Regex("""Get:\s*\d+\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+\[([\d.,]+)\s*(\w+)\]""")
    private val RE_UNPACK = Regex("""Unpacking\s+(\S+)\s+\(([^)]+)\)""")
    private val RE_SETUP = Regex("""Setting up\s+(\S+)\s+\(([^)]+)\)""")
    private val RE_PREPARE = Regex("""Preparing to unpack\s+(\S+)""")
    private val RE_ERROR = Regex("""(?:^|\s)(E|Err|dpkg: error|Sub-process /usr/bin/dpkg returned an error code)""", RegexOption.IGNORE_CASE)

    override fun matches(command: String): Boolean {
        val c = command.trim().lowercase()
        return c.contains("apt-get") || c.contains("apt install") || c.contains("apt-get install")
    }

    /** apt 无明确的完成行：工具正常结束且无错误标记即视为成功完成。 */
    override fun isCompleted(result: String): Boolean {
        return !result.contains("E: ") &&
            !result.contains("dpkg: error") &&
            !result.contains("Sub-process /usr/bin/dpkg returned an error code")
    }

    override fun parse(line: String): InstallProgress? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (RE_ERROR.containsMatchIn(t)) {
            return InstallProgress(phase = InstallPhaseType.FAILED, detail = t.take(120))
        }
        RE_GET.find(t)?.let { m ->
            val pkg = m.groupValues[1].substringAfterLast('/')
            return InstallProgress(
                phase = InstallPhaseType.DOWNLOAD,
                currentPackage = pkg,
                detail = "正在下载 $pkg"
            )
        }
        RE_UNPACK.find(t)?.let { m ->
            return InstallProgress(
                phase = InstallPhaseType.INSTALL,
                currentPackage = m.groupValues[1],
                detail = "正在解压 ${m.groupValues[1]}"
            )
        }
        RE_SETUP.find(t)?.let { m ->
            return InstallProgress(
                phase = InstallPhaseType.INSTALL,
                currentPackage = m.groupValues[1],
                detail = "正在配置 ${m.groupValues[1]}"
            )
        }
        RE_PREPARE.find(t)?.let { m ->
            return InstallProgress(
                phase = InstallPhaseType.INSTALL,
                currentPackage = m.groupValues[1].substringAfterLast('/'),
                detail = "准备安装 ${m.groupValues[1].substringAfterLast('/')}"
            )
        }
        if (t.contains("Setting up") || t.contains("Processing triggers")) {
            return InstallProgress(phase = InstallPhaseType.INSTALL, detail = t.take(80))
        }
        return null
    }
}

/**
 * pip（Python）解析器。
 * 输出格式：
 *   Collecting requests
 *     Downloading requests-2.31.0-py3-none-any.whl (62 kB)
 *      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ 62.6/62.6 kB 1.2 MB/s eta 0:00:00
 *   Installing collected packages: requests, urllib3
 *   Successfully installed requests-2.31.0
 */
object PipProgressParser : InstallProgressParser {
    private val RE_COLLECT = Regex("""Collecting\s+(\S+)""")
    private val RE_DOWNLOAD = Regex("""Downloading\s+(\S+)\s+\(([\d.]+)\s*(\w+)\)""")
    private val RE_PROGRESS = Regex("""\s*(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)\s*\w+\s+[\d.]+\s+\w+/s""")
    private val RE_INSTALLING = Regex("""Installing collected packages:\s*(.+)""")
    private val RE_SUCCESS = Regex("""Successfully installed\s+(.+)""")
    private val RE_ERROR = Regex("""ERROR:|Could not find a version|No matching distribution""", RegexOption.IGNORE_CASE)

    override fun matches(command: String): Boolean {
        val c = command.trim().lowercase()
        return c.contains("pip install") || c.contains("pip3 install") || c.contains("uv pip")
    }

    override fun parse(line: String): InstallProgress? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (RE_ERROR.containsMatchIn(t)) {
            return InstallProgress(phase = InstallPhaseType.FAILED, detail = t.take(120))
        }
        RE_SUCCESS.find(t)?.let {
            return InstallProgress(phase = InstallPhaseType.DONE, percent = 1f, detail = "完成：${it.groupValues[1]}")
        }
        RE_INSTALLING.find(t)?.let {
            return InstallProgress(phase = InstallPhaseType.INSTALL, detail = "正在安装 ${it.groupValues[1]}")
        }
        RE_PROGRESS.find(t)?.let { m ->
            val got = m.groupValues[1].toFloatOrNull()
            val total = m.groupValues[2].toFloatOrNull()
            if (got != null && total != null && total > 0) {
                return InstallProgress(
                    phase = InstallPhaseType.DOWNLOAD,
                    percent = (got / total).coerceIn(0f, 1f),
                    detail = "下载中 ${(got / 1024f).toInt()}/${(total / 1024f).toInt()} KB"
                )
            }
        }
        RE_DOWNLOAD.find(t)?.let { m ->
            return InstallProgress(
                phase = InstallPhaseType.DOWNLOAD,
                currentPackage = m.groupValues[1],
                detail = "正在下载 ${m.groupValues[1]}"
            )
        }
        RE_COLLECT.find(t)?.let { m ->
            return InstallProgress(
                phase = InstallPhaseType.DOWNLOAD,
                currentPackage = m.groupValues[1],
                detail = "正在获取 ${m.groupValues[1]}"
            )
        }
        return null
    }
}

/**
 * sdkmanager（Android SDK）解析器。
 * 输出格式：
 *   [=======================================] 100% Computing updates...
 *   [==========                              ]  25% Fetching...
 *   Warning: File ... already exists
 *   Done. 1 package installed.
 */
object SdkmanagerProgressParser : InstallProgressParser {
    private val RE_BAR = Regex("""\[(={0,50})\s*\]\s*(\d{1,3})%""")
    private val RE_DONE = Regex("""Done\.\s*(\d+)\s*package""", RegexOption.IGNORE_CASE)
    private val RE_ERROR = Regex("""(?:^|\s)(Error|Exception|Failed to|Warning:.*failed)""", RegexOption.IGNORE_CASE)

    override fun matches(command: String): Boolean {
        val c = command.trim().lowercase()
        return c.contains("sdkmanager") || c.contains("sdk --install")
    }

    override fun parse(line: String): InstallProgress? {
        val t = line.trim()
        if (t.isEmpty()) return null
        if (RE_ERROR.containsMatchIn(t)) {
            return InstallProgress(phase = InstallPhaseType.FAILED, detail = t.take(120))
        }
        RE_DONE.find(t)?.let {
            return InstallProgress(phase = InstallPhaseType.DONE, percent = 1f, detail = "完成：${it.groupValues[1]} 个包")
        }
        RE_BAR.find(t)?.let { m ->
            val percent = m.groupValues[2].toIntOrNull()?.coerceIn(0, 100)?.div(100f)
            val detail = t.substringAfter('%').trim().ifBlank { "安装中" }
            return InstallProgress(
                phase = InstallPhaseType.INSTALL,
                percent = percent,
                detail = detail
            )
        }
        return null
    }
}
