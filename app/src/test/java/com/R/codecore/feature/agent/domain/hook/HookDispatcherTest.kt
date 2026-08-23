package com.R.codecore.feature.agent.domain.hook

import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.wake.WakeQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDispatcherTest {

    // ---------- 构造辅助 ----------

    /** 内存版 WakeQueueDao（测试用 fake，行为对齐真实 DAO 语义）。 */
    private class FakeWakeQueueDao : WakeQueueDao {
        val store = mutableListOf<WakeItemEntity>()
        override suspend fun insert(entity: WakeItemEntity) { store += entity }
        override suspend fun insertAll(entities: List<WakeItemEntity>) { store += entities }
        override suspend fun getBySessionAndStatus(sessionId: String, status: String): List<WakeItemEntity> =
            store.filter { it.sessionId == sessionId && it.status == status }.sortedBy { it.createdAtMs }
        override suspend fun getByStatus(status: String): List<WakeItemEntity> =
            store.filter { it.status == status }.sortedBy { it.createdAtMs }
        override suspend fun updateStatus(ids: List<String>, status: String) {
            store.indices.forEach { i ->
                if (store[i].wakeId in ids) store[i] = store[i].copy(status = status)
            }
        }
        override suspend fun deleteByIds(ids: List<String>) { store.removeAll { it.wakeId in ids } }
    }

    private fun commitDisciplineHook(dao: WakeQueueDao = FakeWakeQueueDao()): CommitDisciplineHook =
        CommitDisciplineHook(WakeQueueManager(dao))

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
