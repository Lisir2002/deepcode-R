package com.R.codecore.feature.agent.domain.wake

import com.R.codecore.datalayer.repository.WakeQueueStore
import com.R.codecore.datalayer.sqldelight.agent.Wake_queue as V2WakeItem
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * WakeQueueManager 单测（R02）：写入 / 注入读取 / 消费确认 / 失败保留。
 */
class WakeQueueManagerTest {

    private class FakeWakeQueueStore(
        private val failOnUpdate: Boolean = false
    ) : WakeQueueStore {
        val store = mutableListOf<V2WakeItem>()
        override suspend fun upsertWakeItem(
            wakeId: String, sessionId: String, source: String, type: String, content: String,
            status: String, createdAtMs: Long,
        ) { store += V2WakeItem(wakeId, sessionId, source, type, content, status, createdAtMs) }

        override suspend fun listWakeBySessionAndStatus(sessionId: String, status: String): List<V2WakeItem> =
            store.filter { it.session_id == sessionId && it.status == status }.sortedBy { it.created_at_ms }

        override suspend fun markWakeItemsConsumedBatch(ids: List<String>, status: String) {
            if (failOnUpdate) throw IllegalStateException("update failed")
            store.indices.forEach { i ->
                if (store[i].wake_id in ids) store[i] = store[i].copy(status = status)
            }
        }

        override suspend fun listPendingWakeItems(): List<V2WakeItem> =
            store.filter { it.status == WakeItemEntity.STATUS_PENDING }.sortedBy { it.created_at_ms }
    }

    // ---------- 写入 ----------

    @Test
    fun enqueue_thenPendingForSession_returnsItem() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueStore())

        manager.enqueue("s1", "hook.commit-discipline", "post-tool-use", "commit 不符合规范")

        val pending = manager.pendingForSession("s1")
        assertEquals(1, pending.size)
        assertEquals("hook.commit-discipline", pending[0].source)
        assertEquals(WakeItemEntity.STATUS_PENDING, pending[0].status)
    }

    @Test
    fun enqueue_blankContent_skipped() = runBlocking {
        val store = FakeWakeQueueStore()
        val manager = WakeQueueManager(store)

        manager.enqueue("s1", "src", "type", "   ")

        assertEquals(0, store.store.size)
        assertEquals(0, manager.pendingForSession("s1").size)
    }

    @Test
    fun enqueue_sessionIsolated() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueStore())

        manager.enqueue("s1", "src", "type", "a")
        manager.enqueue("s2", "src", "type", "b")
        // 全局唤醒（sessionId 为空串）与具体会话互不可见
        manager.enqueue("", "src", "type", "g")

        assertEquals(1, manager.pendingForSession("s1").size)
        assertEquals(1, manager.pendingForSession("s2").size)
        assertEquals(1, manager.pendingForSession("").size)
    }

    // ---------- 注入读取 ----------

    @Test
    fun pendingForSession_ordersByCreatedAt() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueStore())

        manager.enqueue("s1", "src-a", "type", "first")
        Thread.sleep(2)
        manager.enqueue("s1", "src-b", "type", "second")

        val pending = manager.pendingForSession("s1")
        assertEquals(listOf("first", "second"), pending.map { it.content })
    }

    // ---------- 消费确认 ----------

    @Test
    fun markConsumed_removesFromPending() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueStore())
        manager.enqueue("s1", "src", "type", "content")
        val ids = manager.pendingForSession("s1").map { it.wakeId }

        manager.markConsumed(ids)

        assertEquals(0, manager.pendingForSession("s1").size)
    }

    @Test
    fun markConsumed_emptyIds_isSafe() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueStore())

        manager.markConsumed(emptyList())

        assertEquals(0, manager.pendingForSession("s1").size)
    }

    // ---------- 失败保留（防丢失：宁可重复不可丢失） ----------

    @Test
    fun markConsumed_failureKeepsPending() = runBlocking {
        val store = FakeWakeQueueStore(failOnUpdate = true)
        val manager = WakeQueueManager(store)
        manager.enqueue("s1", "src", "type", "content")
        val ids = manager.pendingForSession("s1").map { it.wakeId }

        try {
            manager.markConsumed(ids)
            fail("markConsumed 应抛出存储异常")
        } catch (e: IllegalStateException) {
            // 预期：消费确认失败向上传播（workflow 层捕获并保留待下轮重扫）
        }

        // 失败后仍为待注入（保留待下次，防丢失）
        val pending = manager.pendingForSession("s1")
        assertEquals(1, pending.size)
        assertEquals(WakeItemEntity.STATUS_PENDING, pending[0].status)
    }

    // ---------- 异步入队（供同步 Hook 回调） ----------

    @Test
    fun enqueueAsync_eventuallyPersists() = runBlocking {
        val store = FakeWakeQueueStore()
        val manager = WakeQueueManager(store)

        manager.enqueueAsync("s1", "hook.commit-discipline", "post-tool-use", "push 前需跑单测")

        // 异步写入：等待独立 IO scope 完成
        repeat(50) {
            if (store.store.isNotEmpty()) return@repeat
            delay(10)
        }
        delay(20)
        assertEquals(1, manager.pendingForSession("s1").size)
    }

    @Test
    fun enqueueAsync_blankContent_skipped() {
        val store = FakeWakeQueueStore()
        val manager = WakeQueueManager(store)

        manager.enqueueAsync("s1", "src", "type", "")

        assertEquals(0, store.store.size)
    }
}