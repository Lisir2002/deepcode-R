package com.core.deepcode.feature.agent.domain.input

import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.prompt.SystemPromptProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待批准计划选择注入（D1-1，importance=P1 常规，对齐 norm-chain §3.1.2 八源排序）：
 *
 * 由 workflow 每轮 CallLlm 前把 [com.core.deepcode.feature.agent.domain.plan.PlanService] 最近计划的
 * pendingSelection（非空 + 非终态）喂入 [feed]，step 前经 [build] 注入「待定选择（未批准）」，
 * 供模型在用户尚未拍板的方案间继续权衡（对齐 DSH plan）。
 *
 * importance=P1（可被注入预算裁剪），注入顺序位于 行为模式 之后、playbook-stage 之前。
 * 无 IO：pendingSelection 由调用方（workflow）读取后喂入，build 同步消费会话级缓存。
 */
@Singleton
class PlanPendingHintSource @Inject constructor() : SystemPromptProvider.PromptSource {

    /** 会话级最近喂入的待定选择文本：sessionId -> pendingSelection。 */
    private val pendings = ConcurrentHashMap<String, String>()

    /** 每轮喂入待定选择文本（workflow 在 CallLlm 前调用）；null/空白清除该会话待定选择。 */
    fun feed(sessionId: String, pendingSelection: String?) {
        if (sessionId.isBlank()) return
        if (pendingSelection.isNullOrBlank()) pendings.remove(sessionId) else pendings[sessionId] = pendingSelection
    }

    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val text = pendings[sessionId]?.takeIf { it.isNotBlank() } ?: return null
        return "【待定选择（未批准）】\n```\n$text\n```"
    }
}
