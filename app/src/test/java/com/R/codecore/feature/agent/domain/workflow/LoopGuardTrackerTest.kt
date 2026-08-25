package com.R.codecore.feature.agent.domain.workflow

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTrackerTest {

    private fun args(vararg pairs: Pair<String, String>): Map<String, kotlinx.serialization.json.JsonElement> =
        pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun sameToolSameArgs_reachesThreshold3() {
        val tracker = LoopGuardTracker()
        assertNull(tracker.takeAdvisory())
        tracker.record("readFile", args("path" to "/a.txt"))
        tracker.record("readFile", args("path" to "/a.txt"))
        assertNull("未达阈值 3 前不应提醒", tracker.takeAdvisory())
        tracker.record("readFile", args("path" to "/a.txt"))
        val advisory = tracker.takeAdvisory()
        assertTrue("达阈值 3 应生成提醒", advisory?.contains("3 次") == true)
        assertTrue(advisory!!.contains("readFile"))
        // 一次性消费：再次取应为 null
        assertNull(tracker.takeAdvisory())
    }

    @Test
    fun threshold5_remindsAgainOnSameStreak() {
        val tracker = LoopGuardTracker()
        val a = args("path" to "/b.txt")
        repeat(3) { tracker.record("readFile", a) }
        assertTrue(tracker.takeAdvisory() != null)
        repeat(2) { tracker.record("readFile", a) }
        val advisory = tracker.takeAdvisory()
        assertTrue("同一 streak 到 5 次应再次提醒", advisory?.contains("5 次") == true)
    }

    @Test
    fun differentArgs_resetsStreak() {
        val tracker = LoopGuardTracker()
        tracker.record("readFile", args("path" to "/a.txt"))
        tracker.record("readFile", args("path" to "/a.txt"))
        // 参数变化 → streak 重置，不再触发阈值 3
        tracker.record("readFile", args("path" to "/b.txt"))
        tracker.record("readFile", args("path" to "/b.txt"))
        assertNull(tracker.takeAdvisory())
    }

    @Test
    fun differentTool_resetsStreak() {
        val tracker = LoopGuardTracker()
        val a = args("path" to "/a.txt")
        tracker.record("readFile", a)
        tracker.record("readFile", a)
        tracker.record("writeFile", a)
        tracker.record("writeFile", a)
        assertNull("换工具后不应触发阈值 3", tracker.takeAdvisory())
    }

    @Test
    fun currentCount_reflectsConsecutiveStreak() {
        val tracker = LoopGuardTracker()
        tracker.record("Bash", args("command" to "ls"))
        assertEquals(1, tracker.currentCount)
        tracker.record("Bash", args("command" to "ls"))
        assertEquals(2, tracker.currentCount)
        tracker.record("Bash", args("command" to "pwd"))
        assertEquals(1, tracker.currentCount)
    }
}
