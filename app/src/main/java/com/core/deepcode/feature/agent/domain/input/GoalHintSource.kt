package com.core.deepcode.feature.agent.domain.input

import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.prompt.SystemPromptProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前任务目标注入（D1-1，importance=P0 必注入，对齐 norm-chain §3.1.2 八源排序 + §3.1.1 闭环核对第 1 项）：
 *
 * 由 workflow 每轮 CallLlm 前把 [com.core.deepcode.feature.agent.domain.goal.GoalService] 当前 ACTIVE
 * 目标文本喂入 [feed]，step 前经 [build] 注入「当前任务目标」引导行 + 代码块正文（混合标记格式），
 * 让模型每轮锚定目标不跑偏（对齐 DSH goal 注入）。目标可追溯、可修订、可归因。
 *
 * importance=P0（§3.1.2：goal=**P0 必注入**），永不参与注入预算裁剪。
 * 无 IO：目标文本由调用方（workflow）读取后喂入，build 同步消费会话级缓存。
 */
@Singleton
class GoalHintSource @Inject constructor() : SystemPromptProvider.PromptSource {

    /** 会话级最近喂入的目标文本：sessionId -> goalText。 */
    private val goals = ConcurrentHashMap<String, String>()

    /** 每轮喂入当前目标文本（workflow 在 CallLlm 前调用）；null/空白清除该会话目标。 */
    fun feed(sessionId: String, goalText: String?) {
        if (sessionId.isBlank()) return
        if (goalText.isNullOrBlank()) goals.remove(sessionId) else goals[sessionId] = goalText
    }

    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val text = goals[sessionId]?.takeIf { it.isNotBlank() } ?: return null
        return "【当前任务目标】\n```\n$text\n```"
    }
}
