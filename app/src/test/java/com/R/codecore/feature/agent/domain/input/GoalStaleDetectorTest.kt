package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D0-7 目标失配检测：GoalStaleDetector 纯逻辑 + GoalStaleSource 注入消费。
 * 对齐 norm-chain §3.10 增量 6 验收：失配判定 + 连续 N 轮触发 + 排除澄清轮 + 频控 + 目标变更重置。
 */
class GoalStaleDetectorTest {

    private val detector = GoalStaleDetector(consecutiveRounds = 2, frequencyCapRounds = 10)

    @Test
    fun goalKeywords_extractsChineseAndEnglish() {
        val kw = detector.goalKeywords("修复登录崩溃 fix login crash")
        assertTrue(kw.contains("修复"))
        assertTrue(kw.contains("login"))
        assertFalse(kw.isEmpty())
    }

    @Test
    fun suspectedMismatch_goalKeywordsAbsentFromInput() {
        val goal = "实现用户登录功能"
        assertTrue(detector.suspectedMismatch(goal, "帮我看看天气"))
        assertFalse(detector.suspectedMismatch(goal, "实现登录功能"))
    }

    @Test
    fun isClarificationRound_detectsAskBackfill() {
        assertTrue(detector.isClarificationRound("我选了方案B"))
        assertTrue(detector.isClarificationRound("明白了，继续"))
        assertFalse(detector.isClarificationRound("实现登录功能"))
    }

    @Test
    fun advance_requiresConsecutiveRounds_toConfirm() {
        val s0 = GoalStaleDetector.State()
        val (s1, v1) = detector.advance(s0, "实现登录", "帮我看看天气")
        assertTrue(v1.suspected)
        assertFalse("连续 1 轮不应触发", v1.confirmDue)
        val (s2, v2) = detector.advance(s1, "实现登录", "帮我看看天气")
        assertTrue("连续 2 轮应触发", v2.confirmDue)
        assertEquals(2, v2.consecutiveCount)
    }

    @Test
    fun advance_hitGoal_resetsConsecutive() {
        val s0 = GoalStaleDetector.State()
        val (s1, _) = detector.advance(s0, "实现登录", "帮我看看天气")
        val (s2, v2) = detector.advance(s1, "实现登录", "继续实现登录")
        assertFalse(v2.suspected)
        assertEquals(0, v2.consecutiveCount)
    }

    @Test
    fun advance_clarificationRound_excludedButNotReset() {
        val s0 = GoalStaleDetector.State()
        val (s1, v1) = detector.advance(s0, "实现登录", "帮我看看天气")
        val (s2, v2) = detector.advance(s1, "实现登录", "我选了方案B")
        assertFalse("澄清轮不累计", v2.confirmDue)
        val (s3, v3) = detector.advance(s2, "实现登录", "帮我看看天气")
        assertTrue("澄清轮后连续计数未清零，再疑一次即触发", v3.confirmDue)
    }

    @Test
    fun advance_goalChanged_resetsCounts() {
        val s0 = GoalStaleDetector.State()
        val (s1, v1) = detector.advance(s0, "实现登录", "帮我看看天气")
        assertTrue(v1.suspected)
        // 目标文本变化 → 重置连续计数
        val (s2, v2) = detector.advance(s1, "重构首页", "帮我看看天气")
        assertEquals(1, v2.consecutiveCount)
        assertFalse(v2.confirmDue)
    }

    // —— GoalStaleSource 注入消费（detectorFactory 注入真 detector） ——

    @Test
    fun source_feedThenBuild_injectsReminderAndConsumes() {
        val source = GoalStaleSource { _, _ -> GoalStaleDetector(2, 10) }
        // 连续 1 轮：不触发
        assertFalse(source.feed("s1", "实现登录", "帮我看看天气"))
        // 连续 2 轮：触发
        assertTrue(source.feed("s1", "实现登录", "帮我看看天气"))
        val injected = source.build(ctx("s1"))
        assertTrue(injected != null && injected.contains("目标失配"))
        // 一次性消费：再次 build 无注入
        assertNull(source.build(ctx("s1")))
    }

    @Test
    fun source_clear_removesSessionState() {
        val source = GoalStaleSource { _, _ -> GoalStaleDetector(1, 10) }
        source.feed("s1", "实现登录", "帮我看看天气")
        source.clear("s1")
        assertNull(source.build(ctx("s1")))
    }

    private fun ctx(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "",
        language = null,
        sessionId = sessionId
    )
}
