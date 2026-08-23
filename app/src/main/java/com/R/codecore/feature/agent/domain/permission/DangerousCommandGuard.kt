package com.R.codecore.feature.agent.domain.permission

/**
 * 危险命令静态守卫（#6）：在权限引擎与工具入口双层拦截灾难性命令，
 * 并对中风险命令输出合并提示（Warn 通道，与 [BusyBoxCompatibilityGuard] 互补）。
 *
 * 两级判定：
 * - **Block**：命中即拒绝执行（AUTO/NORMAL 均生效），返回 `denyReason` 喂给模型（与灾难 rm / fork bomb 拦截一致）。
 * - **Warn**：放行，仅输出合并提示块（权限卡 details + 命令输出末尾）。
 *
 * 判定引擎：优先用 [ShellCommandParser] 段级 token 判定（B1 跨段管道、B2 chmod/chown token 序列），
 * B3 设备/磁盘用原始命令正则兜底。工作区路径不在系统目录集合内，自动放行。
 */
object DangerousCommandGuard {

    private val SYS_DIRS = setOf(
        "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64",
        "/opt", "/proc", "/root", "/run", "/sbin", "/sys", "/usr", "/var"
    )

    private val ENV_ASSIGN_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    /** chmod/chown 权限位 token：八进制（0777/777/644）或符号（u+rwx / a+rwx / g-rw）。 */
    private val MODE_TOKEN_RE = Regex("""^(?:[0-7]{3,4}|[ugoa]*[+=-][rwxXstugo,]+)$""")

    /** 危险 RCE 下载程序与解析 shell。 */
    private val PIPE_EXEC_PROGS = setOf("curl", "wget", "wget2")
    private val SHELL_PROGS = setOf("sh", "bash", "zsh", "dash", "ash")

    // ---------- B 类判定辅助 ----------

    private fun dropEnvAssigns(tokens: List<String>): List<String> {
        var i = 0
        while (i < tokens.size && ENV_ASSIGN_RE.matches(tokens[i])) i++
        return if (i == 0) tokens else tokens.subList(i, tokens.size)
    }

    private fun programOf(tokens: List<String>): String? =
        dropEnvAssigns(tokens).firstOrNull()

    private fun isSysDirPath(raw: String): Boolean {
        val p = raw.trim()
        val normalized = if (p.length > 1) p.trimEnd('/') else p
        return SYS_DIRS.any { normalized == it || normalized.startsWith("$it/") || normalized.startsWith("$it/*") }
    }

    /**
     * chmod/chown 命令段解析结果（仿 [ShellCommandParser.parseRmInfo] 风格）。
     * @param isChmodOrChown 是否为 chmod/chown 命令
     * @param isChown chown 专属
     * @param isRecursive 含 -R/-r/--recursive
     * @param mode chmod 权限位 token（如 777），非 chmod 或无则 null
     * @param targets 目标路径（不含权限位）
     */
    data class ChmodInfo(
        val isChmodOrChown: Boolean,
        val isRecursive: Boolean,
        val isChown: Boolean,
        val mode: String?,
        val targets: List<String>
    )

    /** 解析命令段是否为 chmod/chown 及其递归/权限位/目标路径。 */
    internal fun parseChmodInfo(tokens: List<String>): ChmodInfo {
        val eff = dropEnvAssigns(tokens)
        val program = eff.firstOrNull() ?: return ChmodInfo(false, false, false, null, emptyList())
        val isChown = program == "chown" || program.endsWith("/chown")
        val isChmod = program == "chmod" || program.endsWith("/chmod")
        if (!isChmod && !isChown) return ChmodInfo(false, false, isChown, null, emptyList())
        var isRecursive = false
        var mode: String? = null
        val targets = mutableListOf<String>()
        var hasDoubleDash = false
        for (i in 1 until eff.size) {
            val t = eff[i]
            if (!hasDoubleDash && t == "--") { hasDoubleDash = true; continue }
            if (!hasDoubleDash && t.startsWith("-") && t != "-") {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    isRecursive = true
                }
                continue
            }
            if (mode == null && isChmod && MODE_TOKEN_RE.matches(t)) {
                mode = t
            } else {
                targets.add(t)
            }
        }
        return ChmodInfo(true, isRecursive, isChown, mode, targets)
    }

    /** 宽松权限位（世界可写）：八进制末位 6/7，或符号含 a/go/o 的 +rwx/+rw。 */
    private fun isPermissiveMode(mode: String?): Boolean {
        if (mode == null) return false
        val m = mode.trim()
        if (Regex("""^[0-7]{3,4}$""").matches(m)) {
            return m.last() == '6' || m.last() == '7'
        }
        return Regex("""^(?:a|go|o|ugoa)[+=-]rwx?[,a-z0-9=+-]*$""", RegexOption.IGNORE_CASE).matches(m)
    }

    /** 顶层段之间的连接符扫描（引号/子 shell/反引号感知），顺序与 [ShellCommandParser] 段一致。 */
    private fun segmentJoiners(command: String): List<Char> {
        val joiners = mutableListOf<Char>()
        var quote = 0
        var parenDepth = 0
        var backtick = false
        var i = 0
        val n = command.length
        while (i < n) {
            val c = command[i]
            if (backtick) {
                if (c == '`') backtick = false
                i++; continue
            }
            when (quote) {
                1 -> if (c == '\'') quote = 0
                2 -> when (c) {
                    '"' -> quote = 0
                    '`' -> backtick = true
                    '$' -> if (i + 1 < n && command[i + 1] == '(') parenDepth++
                }
                else -> when {
                    c == '\'' -> quote = 1
                    c == '"' -> quote = 2
                    c == '\\' -> i++
                    c == '`' -> backtick = true
                    c == '$' && i + 1 < n && command[i + 1] == '(' -> parenDepth++
                    c == '(' -> parenDepth++
                    c == ')' -> if (parenDepth > 0) parenDepth--
                    parenDepth == 0 && (c == '\n' || c == ';') -> joiners.add(';')
                    parenDepth == 0 && c == '&' -> joiners.add('&')
                    parenDepth == 0 && c == '|' -> {
                        if (i + 1 < n && command[i + 1] == '|') { i++; joiners.add('&') } else joiners.add('|')
                    }
                }
            }
            i++
        }
        return joiners
    }

    // ---------- Block 判定 ----------

    private val PROCESS_SUB_RE = Regex("""(?i)<\s*\(\s*(?:curl|wget|wget2)\b""")
    private val KEYFILE_OVERWRITE_RE = Regex(
        """(?i)(?<![<>=])\s*>\s+/etc/(?:passwd|shadow|group|hosts|sudoers)\b"""
    )
    /** 单 `>` 重定向目标捕获（`>>` 排除），用于判定是否写设备。 */
    private val REDIRECT_TARGET_RE = Regex("""(?<![>])\s*>\s*(?!>)([^\s>|;&]+)""")

    /** 伪设备前缀：写入这些设备不造成存储破坏，排除在 B3 之外（误报防护）。 */
    private val PSEUDO_DEVICE_BASES = listOf(
        "null", "zero", "random", "urandom", "tty", "console", "full",
        "kmsg", "ptmx", "shm", "mqueue", "stdin", "stdout", "stderr"
    )

    /** 是否为真实存储设备写入目标（/dev/sd*、mmcblk*、nvme*、loop* 等），伪设备返回 false。 */
    private fun isDeviceWriteTarget(path: String): Boolean {
        val p = path.trim()
        if (!p.startsWith("/dev/") || p == "/dev" || p.endsWith("/dev")) return false
        val base = p.removePrefix("/dev/")
        if (base in PSEUDO_DEVICE_BASES) return false
        if (base.startsWith("tty") || base.startsWith("pts/") || base.startsWith("fd/") ||
            base.startsWith("shm/") || base.startsWith("mapper/control")
        ) {
            return false
        }
        return true
    }
    private val MKFS_PROGS = Regex("""^mkfs(?:\.\w+)?$""")
    private val FDISK_PROGS = Regex("""^fdisk$|/fdisk$""")
    private val FDISK_READONLY_FLAGS = setOf("-l", "-s", "--list", "-h", "--help")
    private val SHUTDOWN_PROGS = setOf("shutdown", "reboot", "halt", "poweroff")

    /**
     * Block 判定：命中返回拒绝原因（喂给模型），未命中返回 null。
     * 顺序 = B1 管道执行 → B2 系统目录权限 → B3 设备/磁盘 → B4 关机/杀进程。
     */
    fun blockReason(command: String): String? {
        val analysis = ShellCommandParser.analyze(command)
        val segments = analysis.segments
        if (segments.isEmpty()) return null

        // B1：RCE 管道（前段 curl/wget + 后段 sh/bash，跨段相邻 `|` 连接）；`&&` 断开不拦
        val joiners = segmentJoiners(command)
        for (i in 0 until minOf(segments.size - 1, joiners.size)) {
            if (joiners[i] != '|') continue
            val p1 = programOf(segments[i])
            val p2 = programOf(segments[i + 1])
            if (p1 != null && p2 != null && p1 in PIPE_EXEC_PROGS && p2 in SHELL_PROGS) {
                return "安全防护：禁止下载后直接执行远程脚本（供应链/代码执行风险）。请先 `curl -O` 下载检查，或用包管理器安装，而非 `curl URL | sh` 直接执行。"
            }
        }
        // B1 兜底：进程替换 `<(curl ...)`
        if (PROCESS_SUB_RE.containsMatchIn(command)) {
            return "安全防护：禁止下载后直接执行远程脚本（供应链/代码执行风险）。请先 `curl -O` 下载检查，而非 `bash <(curl URL)` 直接执行。"
        }

        // B2：chmod/chown 系统目录权限破坏（递归或宽松权限位）
        for (seg in segments) {
            val info = parseChmodInfo(seg)
            if (!info.isChmodOrChown) continue
            val sysTarget = info.targets.any { isSysDirPath(it) }
            if (!sysTarget) continue
            val gate = if (info.isChown) info.isRecursive else (info.isRecursive || isPermissiveMode(info.mode))
            if (gate) {
                return "安全防护：禁止修改系统目录权限（破坏容器稳定性/安全隐患）。工作区内的文件不受影响。"
            }
        }

        // B3：设备/磁盘破坏（dd 写 /dev、mkfs/fdisk、重定向到设备、覆盖关键文件）
        for (seg in segments) {
            val eff = dropEnvAssigns(seg)
            val program = eff.firstOrNull() ?: continue
            // dd 带 of=/dev/... 写设备（伪设备如 /dev/null 不拦，误报防护）
            if (program == "dd" || program.endsWith("/dd")) {
                if (eff.any { it.startsWith("of=") && isDeviceWriteTarget(it.substringAfter("of=")) }) {
                    return "安全防护：禁止对 /dev/ 设备执行写入操作（损坏存储/文件系统）。"
                }
            }
            // mkfs.* / fdisk（fdisk 仅拦写操作，-l 等只读列表放行）
            if (MKFS_PROGS.matches(program)) {
                return "安全防护：禁止格式化磁盘分区（mkfs.*），将造成数据不可恢复的丢失。"
            }
            if (FDISK_PROGS.matches(program)) {
                val args = eff.drop(1)
                val readOnly = args.isNotEmpty() && args.all { it in FDISK_READONLY_FLAGS }
                if (!readOnly) {
                    return "安全防护：禁止执行磁盘分区工具（fdisk）写操作，将改变分区表。"
                }
            }
        }
        // 重定向到存储设备（原始命令正则兜底，伪设备不拦）
        REDIRECT_TARGET_RE.findAll(command).forEach { m ->
            if (isDeviceWriteTarget(m.groupValues[1])) {
                return "安全防护：禁止对 /dev/ 设备执行写入操作（损坏存储/文件系统）。"
            }
        }
        // 覆盖关键系统文件
        if (KEYFILE_OVERWRITE_RE.containsMatchIn(command)) {
            return "安全防护：禁止覆盖系统关键文件（/etc/passwd|shadow|group|hosts|sudoers）。"
        }

        // B4：关机/重启/无目标杀进程
        for (seg in segments) {
            val eff = dropEnvAssigns(seg)
            val program = eff.firstOrNull() ?: continue
            if (SHUTDOWN_PROGS.any { program == it || program.endsWith("/$it") }) {
                return "安全防护：禁止关机/重启（shutdown|reboot|halt|poweroff），将导致容器/会话崩溃。"
            }
            if (program == "kill" || program.endsWith("/kill")) {
                if (eff.drop(1).any { it == "-1" }) {
                    return "安全防护：禁止 `kill -9 -1` 杀掉所有进程，将导致容器/会话崩溃。"
                }
            }
            if (program == "pkill" || program.endsWith("/pkill") || program == "killall" || program.endsWith("/killall")) {
                val args = eff.drop(1).filterNot { it == "--" }
                val hasTarget = args.any { !it.startsWith("-") || it == "-" }
                if (!hasTarget) {
                    return "安全防护：禁止无目标地杀掉全部进程（pkill/killall 未指定进程名），将导致容器/会话崩溃。"
                }
            }
        }

        return null
    }

    // ---------- Warn 判定 ----------

    private val SUDO_HIGH_RISK_RE = Regex(
        """(?i)\bsudo\b\s+(?:-[a-zA-Z]+\s+)*(?:chmod|chown|rm|shutdown|mkfs|dd|iptables|useradd|usermod)\b"""
    )
    private val SENSITIVE_READ_RE = Regex(
        """(?i)\b(?:cat|head|tail|base64)\b[^|;&\n]*/etc/(?:shadow|passwd|gshadow|sudoers)\b"""
    )
    private val SSH_KEY_OVERWRITE_RE = Regex(
        """(?i)(?<![<>=])\s*>\s*[^\s>|;&]*\.ssh/(?:authorized_keys|id_rsa|id_ed25519)\b"""
    )
    private val BASE64_EXEC_RE = Regex(
        """(?i)\bbase64\b[^|;&\n]*\|\s*(?:sh|bash|zsh|dash|ash)\b|\bpython[0-9.]*\s+-c\b[^\n]*base64"""
    )
    private val PLAINTEXT_SECRET_RE = Regex(
        """(?i)\b(?:passwd|password|pwd)\s*[:=]\s*["'][^"']{1,64}["']"""
    )
    private val FORCE_PUSH_RE = Regex(
        """(?i)\bgit\s+push\b[^|;&\n]*--force(?:-with-lease)?\b"""
    )
    private val REVERSE_SHELL_RE = Regex(
        """(?i)(?:/dev/tcp/|\bnc\b[^|;&\n]*\s+-e\b|\bsocat\b[^|;&\n]*\bexec\b)"""
    )
    private val TRUNCATE_RE = Regex(
        """(?i)(?:^|[;&\n])\s*:\s*>\s*\S+""")
    private val OVERWRITE_RE = Regex(
        """(?i)(?<![<>=])\s*>\s+(?!>)(?!/dev/|/etc/)[^\s>|;&]+"""
    )

    private fun downloadWarnings(command: String): List<String> {
        val analysis = ShellCommandParser.analyze(command)
        val warnings = mutableListOf<String>()
        for (seg in analysis.segments) {
            val eff = dropEnvAssigns(seg)
            val program = eff.firstOrNull() ?: continue
            if (program !in PIPE_EXEC_PROGS) continue
            val args = eff.drop(1)
            // W3：下载到工作区外绝对路径
            var i = 0
            while (i < args.size) {
                val a = args[i]
                val outputFlag = a == "-o" || a == "-O" || a.startsWith("--output=")
                if (outputFlag) {
                    val value = if (a.startsWith("--output=")) a.substringAfter("=") else args.getOrNull(i + 1)
                    if (value != null && value.startsWith("/")) {
                        warnings += "正在下载到工作区外绝对路径（${value}），请确认目标位置正确。"
                    }
                }
                i++
            }
            // W4：curl 无 -o/-O 且非静默 → 直出终端刷屏
            if (program == "curl") {
                val hasOutput = args.any { it == "-o" || it == "-O" || it.startsWith("--output=") || it == "-I" || it == "--head" }
                val hasSilent = args.any { it == "-s" || it == "-sS" || it == "--silent" || it == "-q" || it == "--quiet" }
                if (!hasOutput && !hasSilent) {
                    warnings += "curl 未带 `-o <文件>`/`-O` 或 `-s`（静默），输出可能直接刷屏；建议加 `-o <文件>` 或 `-s`。"
                }
            }
        }
        return warnings
    }

    private fun chmodWarnings(command: String): List<String> {
        val analysis = ShellCommandParser.analyze(command)
        val warnings = mutableListOf<String>()
        for (seg in analysis.segments) {
            val info = parseChmodInfo(seg)
            if (!info.isChmodOrChown || info.isChown) continue
            val sysTarget = info.targets.any { isSysDirPath(it) }
            // W1：对具体文件（非系统目录）设置过宽权限
            if (!sysTarget && info.targets.isNotEmpty() && isPermissiveMode(info.mode)) {
                warnings += "正在对 ${info.targets.joinToString("、")} 设置过宽权限（${info.mode}），建议收紧为 755/644。"
            }
        }
        return warnings
    }

    /** Warn 判定：返回所有命中提示的合并块；无则返回 null。 */
    fun warnMessage(command: String): String? {
        val warnings = mutableListOf<String>()
        warnings += chmodWarnings(command)          // W1
        warnings += downloadWarnings(command)       // W3、W4
        if (TRUNCATE_RE.containsMatchIn(command) || OVERWRITE_RE.containsMatchIn(command)) {
            warnings += "该命令会清空/覆盖目标文件（`> file` / `: > file`），请确认目标无误。"  // W2
        }
        if (SUDO_HIGH_RISK_RE.containsMatchIn(command)) {
            warnings += "正在以 sudo 执行高危操作（chmod/chown/rm/shutdown/mkfs/dd/iptables/useradd/usermod），确认无提权风险。"  // W5
        }
        if (SENSITIVE_READ_RE.containsMatchIn(command)) {
            warnings += "正在读取凭据类文件（/etc/shadow|passwd|gshadow|sudoers），注意泄露风险。"  // W6
        }
        if (SSH_KEY_OVERWRITE_RE.containsMatchIn(command)) {
            warnings += "正在覆盖 SSH 授权/密钥文件（.ssh/authorized_keys|id_rsa|id_ed25519），请确认意图。"  // W7
        }
        if (BASE64_EXEC_RE.containsMatchIn(command)) {
            warnings += "检测到 base64 解码后执行（`base64 -d | sh` / `python -c ...base64`），请确认来源可信。"  // W8
        }
        if (PLAINTEXT_SECRET_RE.containsMatchIn(command)) {
            warnings += "检测到明文密码写入（passwd/password/pwd 赋值），避免明文凭据写入脚本/提交。"  // W9
        }
        if (FORCE_PUSH_RE.containsMatchIn(command)) {
            warnings += "检测到 git 强制推送（--force），可能覆盖远端历史，请确认。"  // W10
        }
        if (REVERSE_SHELL_RE.containsMatchIn(command)) {
            warnings += "检测到疑似反向连接模式（nc -e / socat exec / /dev/tcp），若为合法网络调试可忽略，否则疑似反向 shell。"  // W11
        }
        if (warnings.isEmpty()) return null
        return "⚠️ 危险命令提示：\n" + warnings.distinct().joinToString("\n") { "  - $it" }
    }

    /** 把危险提示拼到命令输出末尾（让 AI 看到）；无风险则原样返回。 */
    internal fun appendHint(command: String, output: String): String {
        val hint = warnMessage(command) ?: return output
        return output.trimEnd() + "\n\n" + hint
    }
}
