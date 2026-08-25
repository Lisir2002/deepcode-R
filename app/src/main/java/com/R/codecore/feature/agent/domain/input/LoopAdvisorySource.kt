package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import com.R.codecore.feature.agent.domain.workflow.LoopGuardTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 循环提醒注入（D1-1，importance=P2 可裁剪，对齐 norm-chain §3.1.2 八源排序 + 3.1.1 闭环核对第 2 项）：
 *
 * 包装 [LoopGuardTracker]（连续「相同工具 + 相同参数」调用阈值 3/5/8 的 advisory）：
 * workflow 每次 executeEvents 开始时经 [setTracker] 注入当次执行的循环追踪器，
 * step 前经 [build] 取走未消费提醒（[LoopGuardTracker.takeAdvisory]，一次性消费），
 * 不阻塞、不改写工具调用，仅作 advisory，决策留给模型。
 *
 * importance=P2（预算裁剪时最先被裁，先于 GoalStale）。
 * 无 IO、无状态跨会话：tracker 为当次执行私有的临时实例，由调用方（workflow）设置。
 */
@Singleton
class LoopAdvisorySource @Inject constructor() : SystemPromptProvider.PromptSource {

    /** 当次执行的循环追踪器（workflow 每次 executeEvents 开始设置）。 */
    @Volatile private var tracker: LoopGuardTracker? = null

    /** 设置当次执行的循环追踪器；null 表示无（暂停注入）。 */
    fun setTracker(tracker: LoopGuardTracker?) {
        this.tracker = tracker
    }

    override fun build(ctx: AgentContext): String? = tracker?.takeAdvisory()
}
