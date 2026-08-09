package com.deep.rcode.feature.agent.domain.container.progress

import com.deep.rcode.core.util.FileLogger

/**
 * apk stdout 结构化解析（纯函数，无副作用，无协程）。
 *
 * 典型 apk 输出（apk fetch / install / ok 三段式）：
 *   (1) fetch http://.../main/x86_64/python3-3.12.4-r2.apk  4.2 MiB/s
 *   (2) fetch http://.../main/x86_64/libssl3-3.5.0-r0.apk    2.1 MiB/s
 *   ...
 *   (3) (1/24) Installing musl (1.2.5-r0)
 *   (4) (2/24) Installing libcrypto3 (3.5.0-r0)
 *   ...
 *   (25) OK: 24 packages, 15 MiB download, 80 MiB installed.
 *   (X) ERROR: unsatisfiable constraints: zsh-vcs (missing)
 *   (P) Executing post-install hook: git config ...  （自定义脚本里每行纯文本）
 */
/** apk 解析顶层别名（便于外部 `is ApkSemantic.Fetch` 直接写，无需每次写 `ApkStdoutParser.Semantic`）。 */
typealias ApkSemantic = ApkStdoutParser.Semantic

object ApkStdoutParser {
    data class Parsed(
        /** null = 本行非结构化（UI 原样显示）。 */
        val semantic: Semantic?,
    )

    sealed interface Semantic {
        /** 包下载中：提取包名 + 速率字符串（速率若为 null 表示 apk 没写速率）。 */
        data class Fetch(
            val pkgName: String,
            val rateMiBps: Float?,
        ) : Semantic

        /** 安装中 (N/TOTAL) Installing pkg (ver)。 */
        data class Installing(
            val n: Int,
            val total: Int,
            val pkg: String,
            val ver: String?,
        ) : Semantic

        /** 完成：包数、下载 MiB、安装占用 MiB（拿不到为 null）。 */
        data class Ok(
            val packages: Int,
            val downloadMiB: Float?,
            val installedMiB: Float?,
        ) : Semantic

        /** 错误：整段原因（非单一行）。解析到 `ERROR:` 前缀触发。 */
        data class Error(val reason: String) : Semantic

        /**
         * post-hook 脚本的普通文本输出行。命中条件：不是前面四种语义且包含如下关键字之一：
         *   - 行以 `Executing ` / `# post-install` / `Configuring ` / `Triggering ` 开头
         *   - 或当前 phase == POST_HOOK 的任意行（Aggregator 在 OK 后会切 POST_HOOK；到时每行均会 advance）
         * 这里 parser 纯函数不保存 phase，所以只对显式关键字做匹配；其他交给调用方处理。
         */
        data class PostLine(val text: String) : Semantic
    }

    private val RE_FETCH = Regex("""fetch\s+\S*/([^/\s]+\.apk)\s*(?:([\d.]+)\s*MiB/s)?""")
    private val RE_INSTALLING = Regex("""\((\d+)/(\d+)\)\s+Installing\s+(\S+)(?:\s+\(([^)]+)\))?""")
    private val RE_OK = Regex(
        """OK:\s*(\d+)\s+packages?,\s*([\d.]+)\s*MiB\s+download,\s*([\d.]+)\s*MiB\s+installed""",
    )
    private val RE_ERROR_PREFIX = Regex("""^ERROR:\s*(.*)$""")
    private val RE_POST = Regex(
        """^(#\s*post-install|Executing\s+|Configuring\s+|Triggering\s+|mkdir\s+-p\s+|cat\s+>|git\s+config\s|sed\s+-i\s|if\s+grep\s).*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(line: String): Parsed {
        val t = line.trim()
        if (t.isEmpty()) return Parsed(semantic = null)
        RE_FETCH.find(t)?.let { m ->
            val pkg = m.groupValues[1].removeSuffix(".apk")
            val rate = m.groupValues[2].toFloatOrNull()
            return Parsed(Semantic.Fetch(pkg, rate))
        }
        RE_INSTALLING.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return Parsed(null)
            val total = m.groupValues[2].toIntOrNull() ?: return Parsed(null)
            val pkg = m.groupValues[3]
            val ver = m.groupValues[4].ifEmpty { null }
            return Parsed(Semantic.Installing(n, total, pkg, ver))
        }
        RE_OK.find(t)?.let { m ->
            val pkgs = m.groupValues[1].toIntOrNull() ?: 0
            val d = m.groupValues[2].toFloatOrNull()
            val inst = m.groupValues[3].toFloatOrNull()
            return Parsed(Semantic.Ok(pkgs, d, inst))
        }
        RE_ERROR_PREFIX.find(t)?.let { m ->
            return Parsed(Semantic.Error(m.groupValues[1].trim()))
        }
        RE_POST.find(t)?.let {
            return Parsed(Semantic.PostLine(t))
        }
        return Parsed(semantic = null)
    }
}
