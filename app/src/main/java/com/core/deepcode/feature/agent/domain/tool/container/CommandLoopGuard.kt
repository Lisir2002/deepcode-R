package com.core.deepcode.feature.agent.domain.tool.container

/**
 * 命令「无界循环 + fork bomb」检测器：预防 AI 写出永不结束/危险爆炸的命令导致无限刷屏、空耗资源。
 *
 * 背景：容器为 Alpine（BusyBox ash），`terminal(action="start")` 的常驻模式没有超时——
 * AI 若把 `while true; do nc -q ...; done` 这类无界循环塞进常驻终端，会无限运行直到手动停止。
 * Bash 工具虽有超时兜底，但默认 120s 仍可能让用户看到长段刷屏；fork bomb（`:(){ :|:& };:`）
 * 经 `&` 后台自复制，超时无法可靠终止，必须直接拦截。
 *
 * 用法：
 * - [hasUnboundedLoop] 判定命令是否含无界循环（Bash 钳制超时 / terminal start 拒绝）；
 * - [isForkBomb] 判定是否命中经典 fork bomb（两者都直接拒绝）；
 * - [GUARDED_TIMEOUT_SECONDS] 为命中无界循环后 Bash 工具强制钳制的超时（秒）。
 *
 * 精确匹配「无条件循环」：`while true` / `while :` / `while [ 1 ]` / `while [[ 1 ]]` /
 * `until false` / `for ((;;))`（bash 双重括号语法）。刻意**不**命中常见的「有界/条件循环」：
 * `while read`（管道/文件逐行，EOF 结束）、`while [ -f x ]` / `until 条件`（条件满足即退出）、
 * `for i in ...`（有限集合）、`for (( i=0; i<10; i++ ))`（有终止条件），避免误伤正常用法。
 */
object CommandLoopGuard {
    /** 命中无界循环后，Bash 工具强制钳制的超时（秒）：比默认 120s 更早强制终止，减少刷屏窗口。 */
    const val GUARDED_TIMEOUT_SECONDS = 30L

    // 前导 `(?:^|[;&|()\s])` 保证是独立的循环关键字，避免误匹配 `mywhile` 之类；`[ \t]*` 容忍空格。
    // `for ((;;))` 是 bash 的双括号 C 风格循环，需匹配 `\(\(...\)\)`。
    private val UNBOUNDED_LOOP_REGEX = Regex(
        """(?:^|[;&|()\s])(?:while[ \t]+(?:true|:|\[{1,2}[ \t]*1[ \t]*\]{1,2})|until[ \t]+false|for[ \t]*\(\([ \t]*;[ \t]*;[ \t]*\)\))"""
    )

    /** 命令是否包含无界循环模式。 */
    fun hasUnboundedLoop(command: String): Boolean =
        UNBOUNDED_LOOP_REGEX.containsMatchIn(command)

    /** 是否命中经典 fork bomb `:(){ :|:& };:`（去空白后匹配，容忍空格/换行差异）。 */
    fun isForkBomb(command: String): Boolean {
        val normalized = command.filterNot { it.isWhitespace() }
        return normalized.contains(":(){:|:&};:")
    }

    /** 命中的话给出面向用户/AI 的无界循环说明文案。 */
    fun warningMessage(): String =
        "⚠️ 检测到命令含无界循环（while true/until false/for((;;)) 等），可能永不结束。"

    /** 命中的话给出面向用户/AI 的 fork bomb 说明文案。 */
    fun forkBombWarningMessage(): String =
        "⚠️ 检测到经典 fork bomb（:(){ :|:& };:），会瞬间耗尽容器 CPU/内存，已拦截。"
}
