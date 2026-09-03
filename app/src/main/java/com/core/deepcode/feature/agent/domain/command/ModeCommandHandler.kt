package com.core.deepcode.feature.agent.domain.command

import com.core.deepcode.feature.agent.domain.input.BehaviorModeManager
import javax.inject.Inject

/**
 * /mode —— 手动切换行为模式（D0-6，对齐 norm-chain §3.10 增量 5 触发权）。
 *
 * 四档 behaviorMode：`design`（只设计不写码）/ `execute`（默认执行）/ `research`（只读调研）/ `chat`（纯问答）。
 * 显式 `/mode <mode>` 切换后锁定到会话级覆盖，直到再次显式切换或 `/mode default` 解除。
 * 切换结果以 AI 气泡输出（对齐 /agent 命令的反馈方式）。
 */
class ModeCommandHandler @Inject constructor(
    private val behaviorModeManager: BehaviorModeManager
) : SlashCommandHandler {

    override val trigger = "/mode"
    override val label = "行为模式"
    override val description = "切换行为模式（/mode design|execute|research|chat|default）"

    override fun matches(input: String): Boolean {
        val t = input.trim()
        return t == trigger || t.startsWith("$trigger ")
    }

    // 实际执行统一走 executeWithInput（带原始输入解析参数）；无参 execute 空实现（仅满足接口）。
    override fun execute(context: SlashCommandContext) = Unit

    override fun executeWithInput(context: SlashCommandContext, input: String) {
        val arg = input.trim().removePrefix(trigger).trim().lowercase()
        context.switchBehaviorMode(arg)
    }
}
