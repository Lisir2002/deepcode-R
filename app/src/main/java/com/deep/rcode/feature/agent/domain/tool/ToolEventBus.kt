package com.deep.rcode.feature.agent.domain.tool

import com.deep.rcode.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * L7 事件总线：工具间事件驱动的协作中枢。
 *
 * 发布-订阅模型：工具/组件通过 [subscribe] 动态订阅，[publish] 分发事件。
 * 三重循环防护（深度计数 + 同源去重 + 因果链检测）保证事件传播不陷入死循环；
 * 完整事件日志按会话隔离（上限 1000 条 FIFO），供审计与调试。
 *
 * 分发策略：关键事件（cache.invalidated / file.edited 等缓存失效路径）同步分发
 * 保证关键路径顺序；单监听器异常不影响其他监听器。
 */
class ToolEventBus {

    private val listeners = CopyOnWriteArrayList<ToolEventListener>()

    /** 事件日志：sessionId -> 事件列表（FIFO，上限 [MAX_LOG_PER_SESSION]）。 */
    private val eventLog = ConcurrentHashMap<String, ArrayDeque<ToolEvent>>()

    /** 同源去重：source:type -> 最近时间戳。 */
    private val dedupTracker = ConcurrentHashMap<String, Long>()

    // ---------- 发布 ----------

    suspend fun publish(event: ToolEvent) {
        if (!checkDepth(event)) return
        if (!checkDedup(event)) return
        if (!checkCausality(event)) return
        log(event)
        dispatch(event)
    }

    // ---------- 订阅 ----------

    fun subscribe(listener: ToolEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unsubscribe(listener: ToolEventListener) {
        listeners.remove(listener)
    }

    fun listenerCount(): Int = listeners.size

    // ---------- 事件日志 ----------

    /** 读取指定会话的事件日志（最新在前）。 */
    fun getEventLog(sessionId: String, limit: Int = 100): List<ToolEvent> {
        return eventLog[sessionId]?.toList()?.reversed()?.take(limit) ?: emptyList()
    }

    fun clearLog(sessionId: String) {
        eventLog.remove(sessionId)
    }

    // ---------- 内部 ----------

    private suspend fun dispatch(event: ToolEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "事件监听器处理失败: type=${event.type}, err=${e.message}", e)
            }
        }
    }

    private fun log(event: ToolEvent) {
        val sessionId = event.sessionId ?: return
        val deque = eventLog.getOrPut(sessionId) { ArrayDeque() }
        synchronized(deque) {
            if (deque.size >= MAX_LOG_PER_SESSION) {
                deque.removeFirst()
            }
            deque.addLast(event)
        }
    }

    /** 深度计数：传播深度超过 [MAX_DEPTH] 丢弃并告警。 */
    private fun checkDepth(event: ToolEvent): Boolean {
        if (event.depth > MAX_DEPTH) {
            FileLogger.w(TAG, "事件深度超限丢弃: type=${event.type}, depth=${event.depth}")
            return false
        }
        return true
    }

    /** 同源去重：同一来源的同一事件类型在 [DEDUP_WINDOW_MS] 内只处理一次。 */
    private fun checkDedup(event: ToolEvent): Boolean {
        val key = "${event.source}:${event.type}"
        val now = System.currentTimeMillis()
        val last = dedupTracker[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            return false
        }
        dedupTracker[key] = now
        return true
    }

    /** 因果链检测：链中节点重复则阻断（防止循环传播）。 */
    private fun checkCausality(event: ToolEvent): Boolean {
        val chain = event.causalChain
        if (chain.size > MAX_DEPTH) return false
        return chain.toSet().size == chain.size
    }

    private companion object {
        const val TAG = "ToolEventBus"
        const val MAX_DEPTH = 5
        const val DEDUP_WINDOW_MS = 500L
        const val MAX_LOG_PER_SESSION = 1000
    }
}
