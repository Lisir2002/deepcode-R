package com.core.deepcode.feature.agent.domain.command

import javax.inject.Inject

/**
 * /playbook —— 剧本编排入口（D5-4，对齐 norm-chain-design.md §3.3.7 双入口之「斜杠命令」）。
 *
 * - `/playbook`（无参数）：列出全部可用剧本资产（名称 + 简介），供用户显式选择。
 * - `/playbook <name>`：按名称精确启动剧本（等价 playbook_start；清单可见 + 精确匹配，未命中回退说明）。
 * - `/playbook resume`：恢复本会话最近一次中断（INTERRUPTED）的运行。
 * - `/playbook retry`：从本会话最近一次失败（ABORTED）运行的 FAILED 阶段恢复。
 * - `/playbook abort`：中止本会话最近一次进行中的运行。
 * - `/playbook status`：查询本会话最近一次运行的状态。
 *
 * 执行体在 AIAgentViewModel（协程内调 PlaybookExecutor）；playbook_auto 子开关不影响本显式入口。
 */
class PlaybookCommandHandler @Inject constructor() : SlashCommandHandler {
    override val trigger = "/playbook"
    override val label = "Playbook 剧本"
    override val description = "启动/恢复/管理剧本（/playbook 或 /playbook <name|resume|retry|abort|status>）"

    override fun matches(input: String): Boolean {
        val t = input.trim()
        return t == trigger || t.startsWith("$trigger ")
    }

    // 实际执行统一走 executeWithInput（带原始输入解析参数）；无参 execute 空实现（仅满足接口）。
    override fun execute(context: SlashCommandContext) = Unit

    override fun executeWithInput(context: SlashCommandContext, input: String) {
        val arg = input.trim().removePrefix(trigger).trim()
        context.runPlaybook(arg)
    }
}
