package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目标状态候选迁移事件注入（D0-7，P1 常规，对齐 norm-chain §3.10「GoalAdjustEvent 完整设计」+ 3.1.2 八源排序）：
 *
 * 事件由六段式工具流水线 post-execute 段为**关键工具白名单**生成（[GoalAdjustEventDetector]，
 * 失败/异常优先，成功仅在任务完成迹象时触发）后经 [enqueue] 入队；step 前由 [build] 注入给模型，
 * 模型按 should_update_goal 准则（prompts 资产）决定是否经 goal 工具迁移目标。
 *
 * 闭环纪律（对齐设计六决策点）：
 * - 新事件优先、未消费保留（队列不因注入即出队，直到 [clear]）；
 * - 防重复：同 `goalId+eventType+candidateAction` 事件注入一次后不再重复注入（消费标记）；
 * - 目标变更/终态（DONE/ABANDONED）/切换时由调用方 [clear] 清空队列并停止产生。
 *
 * 纯内存 + 纯逻辑（可 JVM 单测）；goal 状态查询与变更由 workflow（D1）经 GoalService 完成。
 */
@Singleton
class GoalAdjustEventSource @Inject constructor() : SystemPromptProvider.PromptSource {

    private data class Queue(
        val events: ArrayDeque<GoalAdjustEvent> = ArrayDeque(),
        /** 已注入消费标记：`goalId::eventType::candidateAction` 集合。 */
        val injected: MutableSet<String> = LinkedHashSet()
    )

    private val sessions = ConcurrentHashMap<String, Queue>()

    /** 单会话队列上限：防事件堆积膨胀注入块（设计「终态/切换清空」之外的内存兜底）。 */
    private fun maxQueueSize(): Int = 16

    private fun keyOf(e: GoalAdjustEvent): String =
        "${e.goalId}::${e.eventType}::${e.candidateAction}"

    /**
     * 入队一个目标调整事件（去重：同 goalId+eventType+candidateAction 已在队且已注入的不再入队）。
     * @return true = 事件已入队（待注入）。
     */
    fun enqueue(sessionId: String, event: GoalAdjustEvent): Boolean {
        if (sessionId.isBlank() || event.goalId.isBlank()) return false
        val q = sessions.computeIfAbsent(sessionId) { Queue() }
        synchronized(q) {
            val key = keyOf(event)
            // 同源事件已注入未消费 → 不重复注入（设计「防重复」）
            if (q.injected.contains(key)) return false
            // 队列内已有同 key 未注入事件 → 不重复入队
            if (q.events.any { keyOf(it) == key }) return false
            if (q.events.size >= maxQueueSize()) {
                q.events.removeLast()
            }
            q.events.addFirst(event) // 新事件优先（注入时从头取）
            return true
        }
    }

    /** 是否已有待注入事件（供 workflow 判断是否需要消费）。 */
    fun hasPending(sessionId: String): Boolean =
        sessions[sessionId]?.events?.isNotEmpty() == true

    /** 清空队列与消费标记（目标变更/终态/切换时调用，设计「终态/切换清空」）。 */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * step 前注入：取队列中最新的未注入事件（新事件优先），标记已注入后返回格式化文本；
     * 全部已注入则返回 null（不重复注入）。事件本身保留在队列直到 [clear]。
     */
    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        val q = sessions[sessionId] ?: return null
        synchronized(q) {
            val event = q.events.firstOrNull { !q.injected.contains(keyOf(it)) } ?: return null
            q.injected.add(keyOf(event))
            return format(event)
        }
    }

    private fun format(e: GoalAdjustEvent): String = buildString {
        appendLine("【目标调整建议】检测到当前目标可能需要调整：")
        appendLine("- 事件：${e.eventType}")
        appendLine("- 来源工具：${e.toolName}，结果：${e.resultState}")
        appendLine("- 建议动作：${e.candidateAction}（置信度 ${e.confidence}）")
        appendLine("- 来源分类：${e.source}")
        if (e.summary.isNotBlank()) {
            appendLine("- 结果摘要：${e.summary}")
        }
        append("请按「should_update_goal」准则判断是否需要经 goal 工具更新/完成/放弃当前目标；调整完成后该提醒自动清空。")
    }

    private companion object {
        const val TAG = "GoalAdjustEventSource"
    }
}
