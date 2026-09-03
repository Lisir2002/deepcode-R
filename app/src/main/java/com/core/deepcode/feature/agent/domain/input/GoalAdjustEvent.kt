package com.core.deepcode.feature.agent.domain.input

/**
 * 目标状态候选迁移事件（D0-7，对齐 norm-chain §3.10「持续意图维护闭环」完整设计）：
 *
 * 由六段式工具流水线 post-execute 段为**关键工具白名单**生成（失败/异常优先，成功仅在
 * 「任务完成迹象」时触发），入队后在下一轮 step 前按 importance=P1 注入给模型，
 * 模型按 should_update_goal 准则（prompts 资产，D0-8）决定是否经 GoalService 迁移目标。
 *
 * 字段集对齐设计定稿：eventType / toolName / resultState / candidateAction / goalId /
 * confidence / source（来源分类）。
 */
enum class GoalAdjustEventType { GOAL_ADJUST_HINT, GOAL_COMPLETE_HINT }

enum class GoalResultState { SUCCESS, FAILED, PARTIAL }

enum class GoalCandidateAction { CONTINUE, UPDATE, COMPLETE, ABANDON }

data class GoalAdjustEvent(
    val eventType: GoalAdjustEventType,
    val toolName: String,
    val resultState: GoalResultState,
    val candidateAction: GoalCandidateAction,
    val goalId: String,
    /** 0-1，越高越建议迁移。 */
    val confidence: Double,
    /** 来源分类：关键工具失败 / 关键工具部分完成 / 成功迹象。 */
    val source: String,
    /** 结果摘要（截断），供模型判断是否需调整目标。 */
    val summary: String = ""
)

/**
 * 目标调整事件检测器（D0-7，纯逻辑、可 JVM 单测）：
 *
 * 触发范围 = 关键工具白名单（有明确成败的长任务类工具）+ 当前目标存在；
 * 失败/异常优先触发（UPDATE），部分完成中频触发，成功仅在「任务完成迹象」时触发
 * （GOAL_COMPLETE_HINT → COMPLETE，低置信），否则不产生事件。
 */
object GoalAdjustEventDetector {

    /** 关键工具白名单：有明确成败的长任务类工具。 */
    val WHITELIST = setOf("Bash", "run_code", "build", "test", "job_start", "check_environment")

    /** 任务完成迹象关键词（命中即视为「成功且接近完成」）。 */
    private val COMPLETION_SIGNS = listOf("passed", "success", "完成", "通过", "全绿", "ok")

    fun isWhitelisted(toolName: String): Boolean = WHITELIST.contains(toolName)

    /**
     * 检测一次工具结果是否生成目标调整事件。
     * @return 命中白名单且满足触发条件时返回事件；否则 null。
     */
    fun detect(
        toolName: String,
        resultState: GoalResultState,
        goalId: String,
        summary: String = ""
    ): GoalAdjustEvent? {
        if (!isWhitelisted(toolName)) return null
        if (goalId.isBlank()) return null
        val detection = when (resultState) {
            GoalResultState.FAILED ->
                Detection(GoalAdjustEventType.GOAL_ADJUST_HINT, GoalCandidateAction.UPDATE, 0.8, "关键工具失败")
            GoalResultState.PARTIAL ->
                Detection(GoalAdjustEventType.GOAL_ADJUST_HINT, GoalCandidateAction.UPDATE, 0.5, "关键工具部分完成")
            GoalResultState.SUCCESS -> {
                // 成功结果仅在「任务完成迹象」时触发（GOAL_COMPLETE_HINT，低置信由模型裁定）。
                if (!COMPLETION_SIGNS.any { summary.contains(it, ignoreCase = true) }) return null
                Detection(GoalAdjustEventType.GOAL_COMPLETE_HINT, GoalCandidateAction.COMPLETE, 0.6, "成功迹象")
            }
        }
        return GoalAdjustEvent(
            eventType = detection.eventType,
            toolName = toolName,
            resultState = resultState,
            candidateAction = detection.candidateAction,
            goalId = goalId,
            confidence = detection.confidence,
            source = detection.source,
            summary = summary.take(200)
        )
    }

    /** when 分支的 4 字段临时载体（避免 Triple 仅 3 元导致解构失败）。 */
    private data class Detection(
        val eventType: GoalAdjustEventType,
        val candidateAction: GoalCandidateAction,
        val confidence: Double,
        val source: String
    )
}
