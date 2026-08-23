package com.R.codecore.feature.agent.domain.wake

import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
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

    private class FakeWakeQueueDao(
        private val failOnUpdate: Boolean = false
    ) : WakeQueueDao {
        val store = mutableListOf<WakeItemEntity>()
        override suspend fun insert(entity: WakeItemEntity) { store += entity }
        override suspend fun insertAll(entities: List<WakeItemEntity>) { store += entities }
        override suspend fun getBySessionAndStatus(sessionId: String, status: String): List<WakeItemEntity> =
            store.filter { it.sessionId == sessionId && it.status == status }.sortedBy { it.createdAtMs }
        override suspend fun getByStatus(status: String): List<WakeItemEntity> =
            store.filter { it.status == status }.sortedBy { it.createdAtMs }
        override suspend fun updateStatus(ids: List<String>, status: String) {
            if (failOnUpdate) throw IllegalStateException("update failed")
            store.indices.forEach { i ->
                if (store[i].wakeId in ids) store[i] = store[i].copy(status = status)
            }
        }
        override suspend fun deleteByIds(ids: List<String>) { store.removeAll { it.wakeId in ids } }
    }

    // ---------- 写入 ----------

    @Test
    fun enqueue_thenPendingForSession_returnsItem() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueDao())

        manager.enqueue("s1", "hook.commit-discipline", "post-tool-use", "commit 不符合规范")

        val pending = manager.pendingForSession("s1")
        assertEquals(1, pending.size)
        assertEquals("hook.commit-discipline", pending[0].source)
        assertEquals(WakeItemEntity.STATUS_PENDING, pending[0].status)
    }

    @Test
    fun enqueue_blankContent_skipped() = runBlocking {
        val dao = FakeWakeQueueDao()
        val manager = WakeQueueManager(dao)

        manager.enqueue("s1", "src", "type", "   ")

        assertEquals(0, dao.store.size)
        assertEquals(0, manager.pendingForSession("s1").size)
    }

    @Test
    fun enqueue_sessionIsolated() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueDao())

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
        val manager = WakeQueueManager(FakeWakeQueueDao())

        manager.enqueue("s1", "src-a", "type", "first")
        Thread.sleep(2)
        manager.enqueue("s1", "src-b", "type", "second")

        val pending = manager.pendingForSession("s1")
        assertEquals(listOf("first", "second"), pending.map { it.content })
    }

    // ---------- 消费确认 ----------

    @Test
    fun markConsumed_removesFromPending() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueDao())
        manager.enqueue("s1", "src", "type", "content")
        val ids = manager.pendingForSession("s1").map { it.wakeId }

        manager.markConsumed(ids)

        assertEquals(0, manager.pendingForSession("s1").size)
    }

    @Test
    fun markConsumed_emptyIds_isSafe() = runBlocking {
        val manager = WakeQueueManager(FakeWakeQueueDao())

        manager.markConsumed(emptyList())

        assertEquals(0, manager.pendingForSession("s1").size)
    }

    // ---------- 失败保留（防丢失：宁可重复不可丢失） ----------

    @Test
    fun markConsumed_failureKeepsPending() = runBlocking {
        val dao = FakeWakeQueueDao(failOnUpdate = true)
        val manager = WakeQueueManager(dao)
        manager.enqueue("s1", "src", "type", "content")
        val ids = manager.pendingForSession("s1").map { it.wakeId }

        try {
            manager.markConsumed(ids)
            fail("markConsumed 应抛出 DAO 异常")
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
        val dao = FakeWakeQueueDao()
        val manager = WakeQueueManager(dao)

        manager.enqueueAsync("s1", "hook.commit-discipline", "post-tool-use", "push 前需跑单测")

        // 异步写入：等待独立 IO scope 完成
        repeat(50) {
            if (dao.store.isNotEmpty()) return@repeat
            delay(10)
        }
        delay(20)
        assertEquals(1, manager.pendingForSession("s1").size)
    }

    @Test
    fun enqueueAsync_blankContent_skipped() {
        val dao = FakeWakeQueueDao()
        val manager = WakeQueueManager(dao)

        manager.enqueueAsync("s1", "src", "type", "")

        assertEquals(0, dao.store.size)
    }
}
