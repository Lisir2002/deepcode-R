package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject

/**
 * 行为模式纪律行注入（D0-6，对齐 norm-chain §3.10 增量 5 生效方式）：
 * step 前注入「本轮行为模式」纪律行，约束模型行为姿态。
 *
 * - 会话级显式覆盖（`/mode` / `?` 标记）存在 → 注入该模式的锁定纪律行；
 * - 无覆盖 → 注入轻量引导行，提醒模型按输入自行判定行为模式并遵循对应纪律
 *   （模型判定依据见 prompts 资产「行为模式纪律」，D0-8）。
 *
 * importance=P1 常规（§3.1.2 八源排序），可被注入预算裁剪。
 */
class BehaviorModeSource @Inject constructor(
    private val manager: BehaviorModeManager
) : SystemPromptProvider.PromptSource {

    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val override = manager.overrideFor(sessionId) ?: return DEFAULT_DISCIPLINE
        return disciplineFor(override) ?: DEFAULT_DISCIPLINE
    }

    /** 显式锁定模式对应的纪律行。 */
    fun disciplineFor(mode: String): String? = when (mode) {
        BehaviorModeManager.MODE_DESIGN ->
            "【行为模式·design】当前为设计模式（已锁定）：只输出设计方案/评审意见，不调用写文件类工具；" +
                "如需写代码请先向用户说明并等待确认。允许只读调研（search/readFile）。"
        BehaviorModeManager.MODE_RESEARCH ->
            "【行为模式·research】当前为调研模式（已锁定）：先搜索/读取资料再作答，不写文件、不执行写操作。"
        BehaviorModeManager.MODE_CHAT ->
            "【行为模式·chat】当前为问答模式（已锁定）：普通对话，不调用工具。"
        BehaviorModeManager.MODE_EXECUTE ->
            "【行为模式·execute】当前为执行模式（已锁定）：正常执行工具调用完成用户任务。"
        else -> null
    }

    private companion object {
        const val TAG = "BehaviorModeSource"
        val DEFAULT_DISCIPLINE =
            "【行为模式】本轮请按输入自行判定行为模式并遵循对应纪律：" +
                "design（只设计不写码）/ execute（默认，正常执行）/ research（只读调研）/ chat（纯问答）；" +
                "plan 形态强制 design（批准前只出方案不改码）。可用 /mode 显式切换锁定。"
    }
}
