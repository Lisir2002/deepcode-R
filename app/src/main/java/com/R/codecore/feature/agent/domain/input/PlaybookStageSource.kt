package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playbook 当前阶段注入（D1-1 登记，importance=P1 常规，对齐 norm-chain §3.1.2 八源排序 + §3.3 阶段注入）：
 *
 * 在 8 个 step 前注入 Source 中**一次登记**占位（保证八源排序与预算裁剪顺序完整）；
 * 实际注入逻辑由 D5（Playbook + 子代理批次）落地：
 * - D5-5 实施 `PlaybookStageSource` 时，本类将读取当前会话最近 RUNNING 运行的当前阶段，
 *   注入「当前阶段：名称 + description + gates」（对齐 §3.3 阶段注入，importance=P1 走预算裁剪）；
 * - 运行期间挂起 goal 维护双信号（GoalStale / GoalAdjustEvent 不注入）的挂起逻辑也在 D5 处理。
 *
 * D5 落地前无 playbook 运行时，[build] 恒返回 null（无运行中的剧本 → 无阶段可注入）。
 */
@Singleton
class PlaybookStageSource @Inject constructor() : SystemPromptProvider.PromptSource {

    override fun build(ctx: AgentContext): String? = null
}
