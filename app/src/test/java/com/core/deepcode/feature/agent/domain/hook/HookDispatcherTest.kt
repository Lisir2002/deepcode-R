package com.core.deepcode.feature.agent.domain.hook

import com.core.deepcode.datalayer.repository.WakeQueueStore
import com.core.deepcode.datalayer.sqldelight.agent.Wake_queue as V2WakeItem
import com.core.deepcode.feature.agent.data.local.entity.WakeItemEntity
import com.core.deepcode.feature.agent.domain.model.AgentMode
import com.core.deepcode.feature.agent.domain.tool.ToolCall
import com.core.deepcode.feature.agent.domain.wake.WakeQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDispatcherTest {

    // ---------- 构造辅助 ----------

    /** 内存版 WakeQueueStore（测试用 fake，行为对齐真实 V2 语义）。 */
    private class FakeWakeQueueStore : WakeQueueStore {
        val store = mutableListOf<V2WakeItem>()
        override suspend fun upsertWakeItem(
            wakeId: String, sessionId: String, source: String, type: String, content: String,
            status: String, createdAtMs: Long,
        ) { store += V2WakeItem(wakeId, sessionId, source, type, content, status, createdAtMs) }
        override suspend fun listWakeBySessionAndStatus(sessionId: String, status: String): List<V2WakeItem> =
            store.filter { it.session_id == sessionId && it.status == status }.sortedBy { it.created_at_ms }
        override suspend fun markWakeItemsConsumedBatch(ids: List<String>, status: String) {
            store.indices.forEach { i ->
                if (store[i].wake_id in ids) store[i] = store[i].copy(status = status)
            }
        }
        override suspend fun listPendingWakeItems(): List<V2WakeItem> =
            store.filter { it.status == WakeItemEntity.STATUS_PENDING }.sortedBy { it.created_at_ms }
    }

    private fun commitDisciplineHook(store: WakeQueueStore = FakeWakeQueueStore()): CommitDisciplineHook =
        CommitDisciplineHook(WakeQueueManager(store))

    private fun bashCall(command: String = "git commit -m \"feat(agent): add hook\"") = ToolCall(
        id = "call-1",
        name = "Bash",
        arguments = mapOf("command" to JsonPrimitive(command))
    )

    private fun postToolUseCtx(toolCall: ToolCall) = PostToolUseContext(
        sessionId = "s1",
        toolCall = toolCall,
        result = "{}",
        isError = false,
        mode = AgentMode.BUILD
    )

    private class CountingPostToolUseHook : PostToolUseHook {
        override val id = "counting"
        var calls = 0
        override fun onPostToolUse(context: PostToolUseContext) { calls++ }
    }

    private class ThrowingPostToolUseHook : PostToolUseHook {
        override val id = "throwing"
        override fun onPostToolUse(context: PostToolUseContext) { throw IllegalStateException("boom") }
    }

    private class DisabledPostToolUseHook : PostToolUseHook {
        override val id = "disabled"
        override val enabled = false
        override fun onPostToolUse(context: PostToolUseContext) {}
    }

    private class CancellingPostToolUseHook : PostToolUseHook {
        override val id = "cancelling"
        override fun onPostToolUse(context: PostToolUseContext) { throw CancellationException("cancel") }
    }

    // ---------- 事件分发 ----------

    @Test
    fun dispatchPostToolUse_callsAllRegisteredHooks() {
        val counting = CountingPostToolUseHook()
        val dispatcher = HookDispatcher(setOf<HookHandler>(counting, commitDisciplineHook()))

        val outcomes = dispatcher.dispatchPostToolUse(postToolUseCtx(bashCall()))

        // 两个已注册的 PostToolUseHook 都被调用
        assertEquals(setOf("counting", "commit-discipline"), outcomes.map { it.handlerId }.toSet())
        assertEquals(1, counting.calls)
        assertTrue(outcomes.all { it.error == null })
    }

    @Test
    fun dispatch_userPromptSubmitAndStopDistribute() {
        val counting = CountingPostToolUseHook()
        val dispatcher = HookDispatcher(setOf<HookHandler>(counting))

        val userOutcomes = dispatcher.dispatchUserPromptSubmit(
            UserPromptSubmitContext("s1", "帮我看看", AgentMode.BUILD)
        )
        val stopOutcomes = dispatcher.dispatchStop(StopContext("s1", isFinished = true, iterations = 1))

        assertTrue(userOutcomes.isEmpty()) // CountingPostToolUseHook 不实现 UserPromptSubmit
        assertTrue(stopOutcomes.isEmpty())
    }

    // ---------- 异常隔离 ----------

    @Test
    fun exceptionIsolation_singleHookFailureDoesNotAffectOthers() {
        val counting = CountingPostToolUseHook()
        val dispatcher = HookDispatcher(setOf<HookHandler>(ThrowingPostToolUseHook(), counting))

        val outcomes = dispatcher.dispatchPostToolUse(postToolUseCtx(bashCall()))

        val throwing = outcomes.first { it.handlerId == "throwing" }
        val counted = outcomes.first { it.handlerId == "counting" }
        assertNotNull(throwing.error)
        assertNull(counted.error)
        assertEquals(1, counting.calls) // 抛错的 hook 不阻断后续 hook
    }

    @Test
    fun disabledHook_isSkipped() {
        val dispatcher = HookDispatcher(setOf<HookHandler>(DisabledPostToolUseHook()))

        val outcomes = dispatcher.dispatchPostToolUse(postToolUseCtx(bashCall()))

        assertTrue(outcomes.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun dispatch_rethrowsCancellationException() {
        val dispatcher = HookDispatcher(setOf<HookHandler>(CancellingPostToolUseHook()))
        dispatcher.dispatchPostToolUse(postToolUseCtx(bashCall()))
    }

    // ---------- 示例 hook：CommitDisciplineHook ----------

    @Test
    fun commitDiscipline_analyze_flagsNonConventionalCommit() {
        val report = commitDisciplineHook().analyze("git commit -m \"fix bug\"")

        assertTrue(report.isCommit)
        assertEquals("fix bug", report.message)
        assertEquals(false, report.conforms)
        assertTrue(report.notes.any { it.contains("不符合 Conventional Commits") })
    }

    @Test
    fun commitDiscipline_analyze_acceptsConventionalCommit() {
        val report = commitDisciplineHook().analyze("git commit -m \"feat(agent): add hook\"")

        assertEquals(true, report.conforms)
        assertTrue(report.notes.isEmpty())
    }

    @Test
    fun commitDiscipline_analyze_pushRemindsUnitTest() {
        val report = commitDisciplineHook().analyze("git push origin main")

        assertTrue(report.isPush)
        assertTrue(report.notes.any { it.contains("testReleaseUnitTest") })
    }

    @Test
    fun commitDiscipline_analyze_nonGitCommandReturnsEmpty() {
        val report = commitDisciplineHook().analyze("ls -la")

        assertEquals(false, report.isCommit)
        assertEquals(false, report.isPush)
        assertTrue(report.notes.isEmpty())
    }

    @Test
    fun commitDiscipline_matches_onlyBashGitCommand() {
        val hook = commitDisciplineHook()

        assertTrue(hook.matches(bashCall("git commit -m \"x\"")))
        assertTrue(hook.matches(bashCall("git push origin main")))
        assertEquals(false, hook.matches(bashCall("echo hi")))
        assertEquals(false, hook.matches(ToolCall("c2", "editFile", emptyMap())))
    }

    // ---------- 全链路：注册的示例 hook 被分发 ----------

    @Test
    fun dispatch_postToolUse_reachesRegisteredExampleHook() {
        val dispatcher = HookDispatcher(setOf<HookHandler>(commitDisciplineHook()))

        val outcomes = dispatcher.dispatchPostToolUse(postToolUseCtx(bashCall()))

        assertEquals(listOf("commit-discipline"), outcomes.map { it.handlerId })
        assertTrue(outcomes.all { it.error == null })
    }
}
