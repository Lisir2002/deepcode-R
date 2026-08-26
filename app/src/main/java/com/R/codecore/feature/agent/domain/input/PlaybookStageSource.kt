package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.playbook.PlaybookGate
import com.R.codecore.feature.agent.domain.playbook.PlaybookStageView
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playbook 当前阶段注入（D1-1 登记 + D5-5 真实落地，importance=P1 常规，对齐 norm-chain §3.1.2 八源排序 + §3.3 阶段注入）：
 *
 * - **阶段注入**：由 [SystemPromptProvider.buildStepInjections] 每轮查询本会话最近 RUNNING 运行的
 *   当前阶段（[com.R.codecore.feature.agent.domain.playbook.PlaybookExecutor.currentStageView]，suspend）
 *   后经 [feed] 喂入内存；step 前由 [build] 注入「当前阶段：名称 + description + gates」
 *   （importance=P1 走预算裁剪）。
 * - **与 GoalService 完全独立**（§3.3 审计定稿）：不自动创建/覆盖 goal，阶段目标仅由本 Source 注入；
 *   运行期间挂起 goal 维护双信号（GoalStale / GoalAdjustEvent 不注入）由
 *   [SystemPromptProvider.buildStepInjections] 在 playbook 激活时跳过对应 Source（本类不感知）。
 *
 * 无运行中的剧本（[feed] null / 未喂）时 [build] 恒返回 null（无阶段可注入）。
 */
@Singleton
class PlaybookStageSource @Inject constructor() : SystemPromptProvider.PromptSource {

    /** 会话级当前阶段视图：sessionId -> 当前阶段（null/缺失 = 无活跃运行）。 */
    private val stages = ConcurrentHashMap<String, PlaybookStageView>()

    /**
     * 喂入本会话当前阶段视图（workflow step 前注入链每轮查询后调用）。
     * @param view null 表示无活跃运行（清除该会话缓存，[build] 返回 null）。
     */
    fun feed(sessionId: String?, view: PlaybookStageView?) {
        if (sessionId.isNullOrBlank()) return
        if (view == null) stages.remove(sessionId) else stages[sessionId] = view
    }

    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val view = stages[sessionId] ?: return null
        return buildString {
            appendLine("【当前剧本阶段】")
            append("```\n")
            appendLine("剧本: ${view.playbookName}（阶段 ${view.stageIndex + 1}/${view.stageCount}）")
            appendLine("阶段: ${view.stageName} —— ${view.stageDescription}")
            val constraints = mutableListOf<String>()
            if (view.gate == PlaybookGate.APPROVAL) {
                constraints.add("进入下一阶段前需用户批准（gates=approval，用户消息首字符 `!` 可跳过确认）")
            }
            if (view.agents.isNotEmpty()) {
                constraints.add("专项子代理: ${view.agents.joinToString()}")
            }
            if (constraints.isNotEmpty()) appendLine("约束: ${constraints.joinToString("；")}")
            appendLine("本阶段工作完成后调 playbook_advance(action=done) 进入下一阶段；声明失败用 playbook_advance(action=fail)。")
            append("```")
        }
    }
}
