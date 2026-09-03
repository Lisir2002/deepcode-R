package com.core.deepcode.feature.agent.domain.input

import com.core.deepcode.feature.agent.domain.model.AgentContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D0-7 目标调整事件：GoalAdjustEventDetector 触发范围/字段 + GoalAdjustEventSource 去重/消费/清空。
 * 对齐 norm-chain §3.10「GoalAdjustEvent 完整设计」六决策点验收。
 */
class GoalAdjustEventSourceTest {

    // —— GoalAdjustEventDetector ——

    @Test
    fun nonWhitelistTool_noEvent() {
        assertNull(GoalAdjustEventDetector.detect("readFile", GoalResultState.FAILED, "g1"))
    }

    @Test
    fun blankGoalId_noEvent() {
        assertNull(GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, ""))
    }

    @Test
    fun failedResult_highConfidenceUpdate() {
        val e = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "build failed")
        assertTrue(e != null)
        assertEquals(GoalAdjustEventType.GOAL_ADJUST_HINT, e!!.eventType)
        assertEquals(GoalCandidateAction.UPDATE, e.candidateAction)
        assertEquals(0.8, e.confidence, 0.001)
        assertEquals(GoalResultState.FAILED, e.resultState)
        assertEquals("g1", e.goalId)
    }

    @Test
    fun partialResult_mediumConfidenceUpdate() {
        val e = GoalAdjustEventDetector.detect("build", GoalResultState.PARTIAL, "g1")
        assertTrue(e != null)
        assertEquals(GoalCandidateAction.UPDATE, e!!.candidateAction)
        assertEquals(0.5, e.confidence, 0.001)
    }

    @Test
    fun successWithoutCompletionSign_noEvent() {
        assertNull(GoalAdjustEventDetector.detect("Bash", GoalResultState.SUCCESS, "g1", "command not found"))
    }

    @Test
    fun successWithCompletionSign_completeHint() {
        val e = GoalAdjustEventDetector.detect("test", GoalResultState.SUCCESS, "g1", "all tests passed")
        assertTrue(e != null)
        assertEquals(GoalAdjustEventType.GOAL_COMPLETE_HINT, e!!.eventType)
        assertEquals(GoalCandidateAction.COMPLETE, e.candidateAction)
        assertEquals(0.6, e.confidence, 0.001)
    }

    @Test
    fun summary_truncatedTo200Chars() {
        val e = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "x".repeat(500))
        assertTrue(e != null)
        assertTrue(e!!.summary.length <= 200)
    }

    // —— GoalAdjustEventSource ——

    private fun source() = GoalAdjustEventSource()

    @Test
    fun enqueue_duplicateKey_notRequeued() {
        val s = source()
        val e = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "build failed")!!
        assertTrue(s.enqueue("s1", e))
        assertFalse("同 key 事件已在队中，不重复入队", s.enqueue("s1", e))
        assertTrue(s.hasPending("s1"))
    }

    @Test
    fun build_injectsNewestEventAndMarksConsumed() {
        val s = source()
        val failed = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "build failed")!!
        s.enqueue("s1", failed)
        val injected = s.build(ctx("s1"))
        assertTrue(injected != null && injected.contains("目标调整建议"))
        assertTrue(injected?.contains("GOAL_ADJUST_HINT") == true)
        // 已注入消费：同事件不重复注入
        assertNull("同事件不重复注入", s.build(ctx("s1")))
    }

    @Test
    fun build_injectsRemainingDistinctEvents() {
        val s = source()
        val failed = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "build failed")!!
        val partial = GoalAdjustEventDetector.detect("build", GoalResultState.PARTIAL, "g2")!!
        s.enqueue("s1", failed)
        s.enqueue("s1", partial)
        assertTrue(s.build(ctx("s1")) != null)
        assertTrue(s.build(ctx("s1")) != null)
        assertNull(s.build(ctx("s1")))
    }

    @Test
    fun clear_removesSessionQueue() {
        val s = source()
        val e = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "build failed")!!
        s.enqueue("s1", e)
        s.clear("s1")
        assertFalse(s.hasPending("s1"))
        assertNull(s.build(ctx("s1")))
    }

    @Test
    fun enqueue_blankSessionOrGoalId_rejected() {
        val s = source()
        val e = GoalAdjustEventDetector.detect("Bash", GoalResultState.FAILED, "g1", "x")!!
        assertFalse(s.enqueue("", e))
        assertFalse(s.enqueue("s1", e.copy(goalId = "")))
    }

    private fun ctx(sessionId: String) = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "",
        language = null,
        sessionId = sessionId
    )
}
