package com.R.codecore.feature.agent.domain.hook

import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.wake.WakeQueueManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/**
 * 示例 hook —— 提交纪律检查（PostToolUse）。
 *
 * 对齐 AGENTS.md 纪律：当 Bash 执行 `git commit` / `git push` 时，静态检查命令文本：
 * - `git commit`：提交信息是否符合 Conventional Commits（`type(scope): subject`，
 *   type ∈ feat|fix|refactor|docs|style|chore|ci|build|perf|test，见 AGENTS.md「Git 提交规范」）；
 * - `git push`：提醒 push 前需确认 `./gradlew :app:testReleaseUnitTest` 通过（AGENTS.md「推送到远端前必跑单元测试」）。
 *
 * R02 接入：审查发现（notes 非空）经 [WakeQueueManager.enqueueAsync] 写入统一唤醒队列，
 * 下一轮会话开始前由 workflow 注入 system-reminder 唤醒（align design 11.3 asyncRewake 下轮注入）。
 * 挂载验证：StatefulAgentWorkflow PostToolUse 挂点（batchResults 组装后）→ HookDispatcher.dispatchPostToolUse。
 */
class CommitDisciplineHook @Inject constructor(
    private val wakeQueueManager: WakeQueueManager
) : PostToolUseHook {
    override val id = "commit-discipline"

    /** 是否命中：Bash 工具且命令文本含 git commit / git push。 */
    internal fun matches(toolCall: ToolCall): Boolean {
        if (toolCall.name != BASH_TOOL) return false
        val command = commandOf(toolCall) ?: return false
        return command.contains("git commit") || command.contains("git push")
    }

    override fun onPostToolUse(context: PostToolUseContext) {
        if (!matches(context.toolCall)) return
        val report = analyze(commandOf(context.toolCall).orEmpty())
        if (report.notes.isNotEmpty()) {
            // 审查发现入队（异步入队，不阻塞主流程；消费在下轮注入）。
            wakeQueueManager.enqueueAsync(
                sessionId = context.sessionId,
                source = SOURCE,
                type = TYPE,
                content = buildString {
                    append(if (report.isPush) "检测到 git push" else "检测到 git commit")
                    report.notes.forEach { append("\n- $it") }
                }
            )
        }
    }

    /**
     * 纯函数纪律检查（可 JUnit）：解析 git commit/push 命令文本，产出结构化报告。
     */
    internal fun analyze(command: String): CommitDisciplineReport {
        val isCommit = command.contains("git commit")
        val isPush = command.contains("git push")
        if (!isCommit && !isPush) {
            return CommitDisciplineReport(isCommit = false, isPush = false, message = null, conforms = null, notes = emptyList())
        }
        val message = extractCommitMessage(command)
        val conforms = message?.let { CONVENTIONAL_COMMIT_REGEX.matches(it.trim()) }
        val notes = buildList {
            if (conforms == false) {
                add("提交信息「$message」不符合 Conventional Commits 规范（type(scope): subject，type ∈ " +
                    "feat|fix|refactor|docs|style|chore|ci|build|perf|test），见 AGENTS.md「Git 提交规范」。")
            }
            if (isPush) {
                add("push 前需确认 ./gradlew :app:testReleaseUnitTest 全部通过，见 AGENTS.md「推送到远端前必跑单元测试」。")
            }
        }
        return CommitDisciplineReport(isCommit, isPush, message, conforms, notes)
    }

    /** 提取 `-m "..."` / `-m '...'` 的提交信息；多段 -m 取最后一段。未带引号 / 无 -m 返回 null。 */
    private fun extractCommitMessage(command: String): String? =
        MESSAGE_REGEX.findAll(command).lastOrNull()?.groupValues?.get(1)

    private fun commandOf(toolCall: ToolCall): String? =
        (toolCall.arguments["command"] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BASH_TOOL = "Bash"

        /** WakeQueue 来源标识（buildWakeReminder 按 source 分组展示）。 */
        const val SOURCE = "hook.commit-discipline"
        const val TYPE = "post-tool-use"

        // AGENTS.md「Git 提交规范」：type(scope): subject
        val CONVENTIONAL_COMMIT_REGEX = Regex("""^(feat|fix|refactor|docs|style|chore|ci|build|perf|test)(\([\w\-/]+\))?: .+""")

        val MESSAGE_REGEX = Regex("""-m\s+["']([^"']+)["']""")
    }
}

/** 提交纪律检查报告（纯数据，可单测断言）。 */
data class CommitDisciplineReport(
    val isCommit: Boolean,
    val isPush: Boolean,
    val message: String?,
    /** 是否合规；message 无法提取时为 null。 */
    val conforms: Boolean?,
    /** 纪律提示列表（空表示无提示）。 */
    val notes: List<String>
)
