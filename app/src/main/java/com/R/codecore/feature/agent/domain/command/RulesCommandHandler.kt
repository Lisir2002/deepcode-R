package com.R.codecore.feature.agent.domain.command

import javax.inject.Inject

/**
 * /rules —— 列出或加载分层规则（D3-3，对齐 norm-chain-design.md §3.9.3）。
 *
 * - `/rules`（无参数）：列出全部四级规则（全局/项目/工作区/模块）的名称 + 层级 + 摘要。
 * - `/rules <name>`：加载指定规则的完整正文（摘要/正文两级形态的正文侧，与 load_rule 工具等价）。
 *
 * 系统提示只常驻注入规则摘要（少量 token），完整正文经本命令或 `load_rule` 工具显式加载。
 */
class RulesCommandHandler @Inject constructor() : SlashCommandHandler {
    override val trigger = "/rules"
    override val label = "分层规则"
    override val description = "列出/加载分层规则（/rules 或 /rules <name>）"

    override fun matches(input: String): Boolean {
        val t = input.trim()
        return t == trigger || t.startsWith("$trigger ")
    }

    // 实际执行统一走 executeWithInput（带原始输入解析参数）；无参 execute 空实现（仅满足接口）。
    override fun execute(context: SlashCommandContext) = Unit

    override fun executeWithInput(context: SlashCommandContext, input: String) {
        val arg = input.trim().removePrefix(trigger).trim()
        if (arg.isBlank()) {
            context.showRules()
        } else {
            context.showRule(arg)
        }
    }
}
