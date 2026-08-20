package com.R.codecore.feature.agent.domain.tool.container

/**
 * BusyBox 兼容性 + 挂起风险检测器：识别容器（Alpine / BusyBox ash）**不支持**的 GNU 专属参数，
 * 以及「不带终止条件的网络命令」。这类命令在 BusyBox 下要么解析即报错（如 `nc -q`）、
 * 要么一直跑不结束（如 `ping` 不带 `-c`），AI 重试再失败/挂起，是「命令反复失败+无限刷屏」
 * 一类事故的根源。
 *
 * 与 [CommandLoopGuard]（拦截无界循环/fork bomb）互补：本类只**预警**不拦截——
 * 命中后在权限卡 `details` 附加提示、并把提示拼进工具结果末尾，让 AI 看到并改用兼容写法；
 * 误伤面小（这些参数在 BusyBox 下几乎必然是错的）。
 *
 * 规则只收录**高置信度**的 GNU-only 参数（对照 BusyBox 帮助确认无此选项）：
 * `nc -q` / `grep -P` / grep 模式里的 `\d\s\w`（PCRE 转义）/ `find -printf` /
 * `xargs -d` / `cp --parents`；另加 `ping` 不带 `-c|-w` 的无限 ping 提醒。
 */
object BusyBoxCompatibilityGuard {

    private data class Rule(val pattern: Regex, val hint: String)

    private val RULES = listOf(
        Rule(
            Regex("""\bnc\b[^\n]*\s+-q\b"""),
            "nc 的 -q（EOF 后等待）是 GNU/OpenBSD netcat 专属，BusyBox 不支持（`nc: unrecognized option: q`）；改用 `nc -w <秒> host port`，或 `wget -T 2 -O /dev/null URL` / `curl --connect-timeout 2 -s`"
        ),
        Rule(
            Regex("""\bgrep\b[^\n]*\s+-[A-Za-z]*P[A-Za-z]*\b"""),
            "grep 的 -P（PCRE）是 GNU 专属，BusyBox 无此选项；改用 `grep -E` 加 POSIX 类 `[0-9]`/`[[:space:]]`/`[[:word:]]`"
        ),
        Rule(
            Regex("""\bgrep\b[^\n]*\\[dsw]\b"""),
            "grep 模式里的 `\\d`/`\\s`/`\\w` 是 PCRE 转义，BusyBox 不认（会把 `\\d` 当字面 d 匹配），改用 `[0-9]`/`[[:space:]]`/`[[:word:]]`"
        ),
        Rule(
            Regex("""\bfind\b[^\n]*\s+-printf\b"""),
            "find 的 -printf 是 GNU 专属，BusyBox 不支持；改用 `ls`/`stat` 或 shell 自行拼接输出"
        ),
        Rule(
            Regex("""\bxargs\b[^\n]*\s+-d\b"""),
            "xargs 的 -d（自定义分隔符）是 GNU 专属，BusyBox 无此选项；改用 `xargs -0` 或换行分隔"
        ),
        Rule(
            Regex("""\bcp\b[^\n]*\s+--parents\b"""),
            "cp 的 --parents（保留父目录结构）是 GNU 专属，BusyBox 不支持；需先自行 `mkdir -p` 再复制"
        )
    )

    /** 命中的全部兼容性问题提示；无则返回空列表。 */
    fun issues(command: String): List<String> =
        RULES.mapNotNull { rule ->
            if (rule.pattern.containsMatchIn(command)) rule.hint else null
        }.distinct()

    /** 网络命令无终止条件提醒：`ping` 不带 `-c|-w` 会一直 ping 下去。 */
    fun hangRiskHints(command: String): List<String> {
        val hints = mutableListOf<String>()
        val ping = Regex("""\bping\b[^\n]*""").find(command)
        if (ping != null &&
            !ping.value.contains("-c", ignoreCase = true) &&
            !ping.value.contains("-w", ignoreCase = true)
        ) {
            hints += "ping 未带次数限制（-c <次数> / -w <秒>）会一直 ping 下去；请补上：`ping -c 3 host`"
        }
        return hints
    }

    /** 汇总所有提示；无则返回 null。权限卡 details 与工具结果末尾使用。 */
    fun warningMessage(command: String): String? {
        val all = issues(command) + hangRiskHints(command)
        if (all.isEmpty()) return null
        return "⚠️ 命令含 BusyBox 不兼容/挂起风险（容器为 Alpine/BusyBox 非 GNU/Linux）：\n" +
            all.joinToString("\n") { "  - $it" }
    }

    /** 把兼容性/挂起提示拼到命令输出末尾（让 AI 看到并修正写法）；无风险则原样返回。 */
    internal fun appendHint(command: String, output: String): String {
        val hint = warningMessage(command) ?: return output
        return output.trimEnd() + "\n\n" + hint
    }
}
