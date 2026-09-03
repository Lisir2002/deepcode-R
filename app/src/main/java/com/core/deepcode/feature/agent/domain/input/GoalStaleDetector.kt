package com.core.deepcode.feature.agent.domain.input

/**
 * 目标失配（stale）检测器（D0-7，纯逻辑、可 JVM 单测，对齐 norm-chain §3.10 增量 6）：
 *
 * 语义失配检测粒度——规则层先轻量筛「疑似失配」（当前 goal 关键词不命中最近用户输入
 * 且非澄清轮），仅在疑似时由调用方（workflow）调 LLM 判相关度（复用 provider 调用，
 * 项目无 embedding 不做向量）；同一 goal 的失配 LLM 确认每 N 轮（默认 10）最多 1 次（频控）。
 *
 * 提醒通道：连续 N 轮（默认 2，可配置）触发、排除澄清轮。规则层为纯关键词近似，
 * 最终是否提醒由「规则疑似 + 频控 + 连续轮数」共同决定。
 */
class GoalStaleDetector(
    /** 连续疑似失配轮数阈值（默认 2，对齐设计）。 */
    private val consecutiveRounds: Int = 2,
    /** 同一 goal 的失配确认频控轮数（默认 10 轮 1 次）。 */
    private val frequencyCapRounds: Int = 10
) {

    /** 一次判定结果。 */
    data class StaleVerdict(
        /** 规则层是否「疑似失配」。 */
        val suspected: Boolean,
        /** 是否建议做一次 LLM 相关度确认（受频控约束）。 */
        val confirmDue: Boolean,
        /** 连续疑似轮数。 */
        val consecutiveCount: Int,
        /** 已建议的确认次数（含本次）。 */
        val confirmCount: Int
    )

    /** 目标关键词：从 goal 文本提取的判定关键词（中文按 2-4 字核心词粗筛，英文按词）。 */
    fun goalKeywords(goalText: String): List<String> {
        val t = goalText.trim()
        if (t.isEmpty()) return emptyList()
        return buildList {
            // 英文单词（≥3 字母）
            Regex("[a-zA-Z]{3,}").findAll(t).forEach { add(it.value.lowercase()) }
            // 中文：2 字核心词（避免单字噪声，2-4 字核心词粗筛取最小粒度利于命中），最多取前 12 个
            val zh = Regex("[\\u4e00-\\u9fa5]{2}")
            zh.findAll(t).take(12).forEach { add(it.value) }
        }
    }

    /**
     * 规则层疑似失配判定：goal 关键词在最近用户输入（已排除澄清轮文本）中是否**全部不命中**。
     * @param goalText 当前目标文本
     * @param recentUserInput 最近用户输入（原样）
     * @return 疑似失配（true = 目标可能已过期）
     */
    fun suspectedMismatch(goalText: String, recentUserInput: String): Boolean {
        val keywords = goalKeywords(goalText)
        if (keywords.isEmpty() || recentUserInput.isBlank()) return false
        val input = recentUserInput.lowercase()
        return keywords.none { input.contains(it) }
    }

    /** 是否澄清轮：输入含 askUserQuestion 回填特征（问答/候选选择）或明确澄清标记。 */
    fun isClarificationRound(recentUserInput: String): Boolean {
        val t = recentUserInput.trim()
        if (t.isEmpty()) return false
        return t.contains("选了") || t.contains("选择") || t.startsWith("澄清") ||
            t.contains("候选人") || t.contains("明白了")
    }

    /** 状态机：单轮推进，返回是否触发失配提醒。 */
    fun advance(state: State, goalText: String, recentUserInput: String): Pair<State, StaleVerdict> {
        if (recentUserInput.isBlank()) return state to StaleVerdict(false, false, state.consecutive, state.confirmCount)
        if (isClarificationRound(recentUserInput)) {
            // 澄清轮：排除，不累计（也不清零，避免「澄清后立刻再疑」误报）
            return state to StaleVerdict(false, false, state.consecutive, state.confirmCount)
        }
        val suspected = suspectedMismatch(goalText, recentUserInput)
        val consecutive = if (suspected) state.consecutive + 1 else 0
        val goalChanged = state.lastGoalText != goalText
        // 目标文本变化 → 为新目标重新起算（本次疑似轮计入新目标第 1 轮，不累加旧目标计数）；
        // 频控计数同样按「同一 goal」口径重置。首轮 lastGoalText 为空亦视为初始化（新目标第 1 轮）。
        val baseConsecutive = if (goalChanged) (if (suspected) 1 else 0) else consecutive
        val baseConfirmCount = if (goalChanged) 0 else state.confirmCount
        val confirmDue = baseConsecutive >= consecutiveRounds &&
            baseConfirmCount < maxConfirmPerGoal()
        val newConfirmCount = if (confirmDue) baseConfirmCount + 1 else baseConfirmCount
        val newState = State(
            lastGoalText = goalText,
            consecutive = baseConsecutive,
            confirmCount = newConfirmCount
        )
        return newState to StaleVerdict(
            suspected = suspected,
            confirmDue = confirmDue,
            consecutiveCount = baseConsecutive,
            confirmCount = newConfirmCount
        )
    }

    /** 频控上限：目标生命周期内最多确认次数（按轮数预算粗估）。 */
    private fun maxConfirmPerGoal(): Int = 1

    /** 会话级失配跟踪状态。 */
    data class State(
        val lastGoalText: String = "",
        val consecutive: Int = 0,
        val confirmCount: Int = 0
    )
}
