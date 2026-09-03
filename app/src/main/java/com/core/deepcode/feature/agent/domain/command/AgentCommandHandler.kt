package com.core.deepcode.feature.agent.domain.command

import javax.inject.Inject

/**
 * /agent —— 列出或切换专项 Agent（#1 会话内 agent 切换，design 8.5）。
 *
 * - `/agent`（无参数）：列出全部可切换的专项 agent 及其描述。
 * - `/agent <name>`：切换到指定专项 agent；切换后系统提示词正文整体替换为该 agent 资产。
 * - 再次 `/agent <name>`（当前已在该 agent）：切回主 agent（默认）。
 *
 * tools/model 仅建议语义（不强制切换 provider、不拦截白名单外工具），由资产 frontmatter 承载。
 */
class AgentCommandHandler @Inject constructor() : SlashCommandHandler {
    override val trigger = "/agent"
    override val label = "切换 Agent"
    override val description = "列出/切换专项 Agent（/agent 或 /agent <name>）"

    override fun matches(input: String): Boolean {
        val t = input.trim()
        return t == trigger || t.startsWith("$trigger ")
    }

    // 实际执行统一走 executeWithInput（带原始输入解析参数）；无参 execute 空实现（仅满足接口）。
    override fun execute(context: SlashCommandContext) = Unit

    override fun executeWithInput(context: SlashCommandContext, input: String) {
        val arg = input.trim().removePrefix(trigger).trim()
        if (arg.isBlank()) {
            context.listAgents()
        } else {
            context.switchAgent(arg)
        }
    }
}
