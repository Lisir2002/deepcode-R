package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目标失配（stale）提醒注入（D0-7，P2 可裁剪，对齐 norm-chain §3.10 增量 6 + 3.1.2 八源排序）：
 *
 * 由 workflow 每轮把「当前 goal 文本 + 最近用户输入」喂入 [feed]，内部用 [GoalStaleDetector]
 * 做规则疑似失配判定 + 连续 N 轮（默认 2）+ 同 goal 频控（10 轮 ≤1 次），连续命中才触发；
 * step 前由 [build] 注入「当前目标可能已过期」提醒（P2，可被注入预算裁剪），提醒后本轮即消费。
 *
 * 仅提示不阻断：模型按 should_update_goal 准则（prompts 资产）决定是否经 goal 工具更新目标；
 * LLM 相关度确认由 workflow（D1）复用 provider 调用完成，本类只做规则层轻量筛。
 *
 * 无状态依赖（纯内存 + 纯逻辑，可 JVM 单测）；goal 文本由调用方（workflow）提供，
 * 不直接查 Room（build 同步调用，保持无 IO）。
 */
@Singleton
class GoalStaleSource(
    private val detectorFactory: (Int, Int) -> GoalStaleDetector
) : SystemPromptProvider.PromptSource {
    /**
     * Hilt 注入入口：默认检测参数（连续 2 轮、10 轮频控）。
     * 主构造器保留 detectorFactory 供测试注入自定义 detector；Dagger 认 secondary @Inject 构造器，
     * 避免「带默认参数的 @Inject 主构造器」被 Kotlin 生成两个注入构造器导致 Dagger 冲突。
     */
    @Inject constructor() : this({ consecutive, cap -> GoalStaleDetector(consecutive, cap) })

    /** 会话级跟踪状态：sessionId -> (detector, state)。 */
    private val sessions = ConcurrentHashMap<String, Entry>()

    /** 已触发提醒、待注入的会话集合（build 时消费）。 */
    private val dueSessions = ConcurrentHashMap.newKeySet<String>()

    private data class Entry(
        val detector: GoalStaleDetector,
        var state: GoalStaleDetector.State = GoalStaleDetector.State()
    )

    /**
     * 每轮喂入目标文本与最近用户输入，推进失配检测状态机。
     * @return 是否已触发待注入的失配提醒（true = 本轮 [build] 会注入）。
     */
    fun feed(sessionId: String, goalText: String, recentUserInput: String): Boolean {
        if (sessionId.isBlank()) return false
        val entry = sessions.computeIfAbsent(sessionId) { Entry(detectorFactory(2, 10)) }
        val (newState, verdict) = entry.detector.advance(entry.state, goalText, recentUserInput)
        entry.state = newState
        if (verdict.confirmDue) {
            dueSessions.add(sessionId)
            return true
        }
        return false
    }

    /** 会话目标变化/结束时的清理（workflow 在目标变更/终态时调用）。 */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
        dueSessions.remove(sessionId)
    }

    override fun build(ctx: AgentContext): String? {
        val sessionId = ctx.sessionId ?: return null
        return if (dueSessions.remove(sessionId)) REMINDER else null
    }

    private companion object {
        const val TAG = "GoalStaleSource"
        val REMINDER = """
            【目标失配提醒】当前任务目标可能已与你的最新输入不一致（连续数轮未命中目标关键词）：
            请先内省核对目标是否仍匹配用户意图；若不匹配，按「should_update_goal」准则经 goal 工具
            更新/新建/完成目标，或向用户澄清确认，不要继续按旧目标执行。
        """.trimIndent()
    }
}
