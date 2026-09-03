package com.core.deepcode.feature.agent.domain.container.progress

import com.core.deepcode.core.util.FileLogger

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
        /**
         * 包下载中。B-3 深度归一化设计：不再只认 .apk 后缀，下列格式全统一收归 Fetch：
         *   - `fetch URL/pkg.apk [x.y MiB/s]`          → apk 实际下载（原匹配）
         *   - `fetch URL/APKINDEX.tar.gz`              → repo 索引下载（原 bug：被 INFO 兜底 → FETCH(0)！）
         *   - `fetch URL/any.sfx [x.y MiB/s]`          → 其他 apk 抓取格式（兜底，只要 fetch+URL 就算）
         */
        data class Fetch(
            /** 虚拟包名；若为 APKINDEX 则是「index: <repo>/<arch>」格式，UI 正确归属 FETCH kind。 */
            val pkgName: String,
            val rateMiBps: Float?,
            /** true = 索引下载（APKINDEX.tar.gz），用于以后 Fetch 方块左上角画 repo 角标。 */
            val isIndex: Boolean = false,
        ) : Semantic

        /** 安装中 (N/TOTAL) Installing pkg (ver) — 原格式不变。 */
        data class Installing(
            val n: Int,
            val total: Int,
            val pkg: String,
            val ver: String?,
        ) : Semantic

        /**
         * 完成：包数、下载 MiB、安装占用 MiB（拿不到为 null）。
         * B-3 放宽：允许只写 "OK: N packages" 或 "OK: N packages, X MiB download" 而不一定有 installed 字段 ——
         * 国内镜像某些版本不完整时也能正确进入 INFO→OK 语义，不落到 INFO 兜底漏分类。
         */
        data class Ok(
            val packages: Int,
            val downloadMiB: Float? = null,
            val installedMiB: Float? = null,
        ) : Semantic

        /** 错误：整段原因。B-3 放宽：匹配 "ERROR:" 开头或 "exit=N error" / "unsatisfiable" 关键字行。 */
        data class Error(val reason: String) : Semantic

        /**
         * post-hook 脚本普通文本输出行。B-3 放宽：新增：
         *   - `* trigger: busybox-*`（post-install trigger start，之前 fall 到 INFO 导致 POST 类计数=0）
         *   - `Configuring pkg`
         *   - `(N/N) Installing pkg` 之后的 `OK:`、`Executing busybox-*.trigger` — 显式识别为 POST
         */
        data class PostLine(val text: String) : Semantic
    }

    // ─── B-3 新版正则组：13 类全覆盖（与归一化矩阵对应） ───

    /**
     * B-3 真正的 FETCH 正则（修复 APKINDEX.tar.gz 掉到 INFO 导致 FETCH(0)）：
     *   `fetch` 后面一个 URL 段，支持：.apk / .tar.gz / .apk.sig / 其他任意后缀 / 无后缀
     *   末尾可选 "x.y MiB/s" 速率。
     *
     *  capture groups:
     *   1 = URL 最后一段（文件名，可能是 pkg.apk 或 APKINDEX.tar.gz 或 xxx.sig 等）
     *   2 = rate（若有）
     */
    private val RE_FETCH_APK = Regex("""fetch\s+\S*/([^/\s]+)\s*(?:([\d.]+)\s*MiB/s)?""")
    /** 显式抓"APKINDEX.<suffix>"，归到 Fetch(isIndex=true)。与上面正则是"先宽抓再细判"两阶段。 */
    private val RE_INDEX_NAME = Regex("""^APKINDEX(?:\.|\b)""", RegexOption.IGNORE_CASE)

    /** 安装中 (N/T) Installing pkg (ver) — 原格式，保持兼容；额外允许 ver 没括号直接贴在 pkg 后面的变体。 */
    private val RE_INSTALLING = Regex("""\((\d+)/(\d+)\)\s+Installing\s+(\S+)(?:\s+\(?([^()\n]*?)\)?)?\s*$""")
    /** Installing 的完成行 —— 以前漏判，把 "Installing pkg done/ok" 这类当 INFO；这里显式识别 INSTALL_OK。 */
    private val RE_INSTALLED_TAG = Regex("""^\(?(\d+)/(\d+)\)?\s*Installed\s+(\S+)(?:\s+\(?([^()\n]*?)\)?)?\s*$""")

    /** OK: N packages[, X MiB download[, Y MiB installed]] — 放宽，后两段可为 null；另外还接受 "OK: X MiB in Y packages" 变体。 */
    private val RE_OK = Regex(
        """OK:\s*(?:(\d+)\s+packages?(?:\s*,\s*([\d.]+)\s*MiB\s+download(?:\s*,\s*([\d.]+)\s*MiB\s+installed)?)?|([\d.]+)\s+MiB\s+in\s+(\d+)\s+packages)""",
    )

    /** ERROR: 开头；或者包含 "ERROR:" 的行；或者 "exit=[非0]" + "error/失败" 关键字 直接 ERROR。 */
    private val RE_ERROR_PREFIX = Regex("""(?:^|\s)ERROR:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val RE_EXIT_CODE_ERR = Regex("""exit\s*=\s*([1-9]\d*)""", RegexOption.IGNORE_CASE)

    /** POST_HOOK — B-3 大幅扩充，把之前容易漏掉的 busybox trigger、Configuring、Triggering 全收。 */
    private val RE_POST = Regex(
        """^(#\s*post-install|Executing\s+|Configuring\s+|Triggering\s+|trigger:\s+|\*\s*trigger:\s+|mkdir\s+-p\s+|cat\s+>|git\s+config\s|sed\s+-i\s|if\s+grep\s|chmod\s+|chown\s+|ln\s+-s[fn]?\s+|install\s+-[Ddm]\s+|post-install\s+|pre-install\s+|post-deinstall\s+|pre-deinstall\s+|Updating\s+).*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(line: String): Parsed {
        val t = line.trim()
        if (t.isEmpty()) return Parsed(semantic = null)

        // 1. 错误：优先级最高，ERROR: 开头优先
        RE_ERROR_PREFIX.find(t)?.let { m ->
            val reason = m.groupValues[1].trim().ifEmpty { t }
            return Parsed(Semantic.Error(reason))
        }
        RE_EXIT_CODE_ERR.find(t)?.let { m ->
            return Parsed(Semantic.Error("exit=${m.groupValues[1]}，安装未成功"))
        }

        // 2. 下载（B-3 新版：接受 .apk / APKINDEX.tar.gz / 其他后缀任意 URL）
        RE_FETCH_APK.find(t)?.let { m ->
            val rawName = m.groupValues[1]
            val rate = m.groupValues[2].toFloatOrNull()
            val isIndex = RE_INDEX_NAME.containsMatchIn(rawName)
            val pkg = when {
                isIndex -> {
                    // 提取 repo/arch，拼成 "index: main/aarch64" 这种虚拟包名，避免多个 APKINDEX 全叫 APKINDEX.tar.gz 无区分
                    val urlSeg = t.substringAfter("fetch ").trim().substringBefore(' ')
                    val parts = urlSeg.split('/').filter { it.isNotEmpty() }.takeLast(4)
                    // .../alpine/v3.21/main/aarch64/APKINDEX.tar.gz → arch=aarch64, repo=main
                    val arch = parts.getOrNull(parts.size - 2) ?: "repo"
                    val repo = parts.getOrNull(parts.size - 3) ?: arch
                    "index: $repo/$arch"
                }
                rawName.endsWith(".apk", ignoreCase = true) -> rawName.removeSuffix(".apk").removeSuffix(".APK")
                else -> rawName
            }
            return Parsed(Semantic.Fetch(pkg, rate, isIndex))
        }

        // 3. 安装中
        RE_INSTALLING.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let null
            val total = m.groupValues[2].toIntOrNull() ?: return@let null
            val pkg = m.groupValues[3]
            val ver = m.groupValues[4].ifBlank { null }
            return Parsed(Semantic.Installing(n, total, pkg, ver))
        }
        RE_INSTALLED_TAG.find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let null
            val total = m.groupValues[2].toIntOrNull() ?: return@let null
            val pkg = m.groupValues[3]
            val ver = m.groupValues[4].ifBlank { null }
            // 完成态 → 用 Installing(n+1? 标记)；Aggregator 在写入时 INSTALL_OK 会单独标记，这里只做语义归类
            return Parsed(Semantic.Installing(n, total, pkg, ver))
        }

        // 4. OK：两种格式 —— "OK: N packages, X MiB download, Y MiB installed" / "OK: X MiB in Y packages"
        RE_OK.find(t)?.let { m ->
            val v1 = m.groupValues[1]
            if (v1.isNotEmpty()) {
                val pkgs = v1.toIntOrNull() ?: 0
                val d = m.groupValues[2].toFloatOrNull()
                val inst = m.groupValues[3].toFloatOrNull()
                return Parsed(Semantic.Ok(pkgs, d, inst))
            } else {
                // v4/v5 变体
                val dMiB = m.groupValues[4].toFloatOrNull()
                val pkgs = m.groupValues[5].toIntOrNull() ?: 0
                return Parsed(Semantic.Ok(packages = pkgs, downloadMiB = dMiB, installedMiB = null))
            }
        }

        // 5. POST_HOOK — B-3 扩充后，busybox trigger / Configuring / Triggering / Updating 都进来
        RE_POST.find(t)?.let {
            return Parsed(Semantic.PostLine(t))
        }

        // 6. 兜底：没匹配到 → null。Aggregator 会 append INFO，避免用户漏看上下文；
        //    同时 Aggregator 在 appendLogLine 之后会跑 B-3 二级归一化（比如 "fetch" 关键字但正则没中 → 修正 kind）
        return Parsed(semantic = null)
    }
}
