package com.R.codecore.feature.agent.domain.wake

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一唤醒队列管理器（R02 骨架）。
 *
 * 承载 #4 hook 后台审查结果 + #10 耗时任务结果（统一 WakeQueue，一套机制两处消费）：
 * - [enqueue]：后台任务完成时写入待注入唤醒（Room 持久化，App 被杀不丢）。
 * - [enqueueAsync]：非阻塞异步入队（供同步 Hook 回调调用，异常隔离 + 记日志）。
 * - [pendingForSession]：下轮会话开始前读取待注入唤醒（供 workflow 拼入 system-reminder）。
 * - [markConsumed]：注入成功后原子化消费确认（防重复注入；失败则保留待下次）。
 *
 * 生命周期：Room 持久化 + 重扫——App 被杀后下次启动重扫待注入队列继续唤醒（R10 阶段落地重扫入口）。
 *
 * 设计依据：docs/plan-docs/claude-code-study-design.md 第 11 节（11.3 asyncRewake 下轮注入）
 * 与第 16 节（16.2 统一 WakeQueue）。
 */
@Singleton
class WakeQueueManager @Inject constructor(
    private val dao: WakeQueueDao
) {
    // 异步写入用独立 scope（SupervisorJob 隔离单个任务失败），风格对齐 FtpServerManager/SyncEngine。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 写入一条待注入唤醒。content 空白时跳过（无内容不入队）。 */
    suspend fun enqueue(sessionId: String?, source: String, type: String, content: String) {
        if (content.isBlank()) return
        dao.insert(
            WakeItemEntity(
                wakeId = UUID.randomUUID().toString(),
                sessionId = sessionId.orEmpty(),
                source = source,
                type = type,
                content = content,
                status = WakeItemEntity.STATUS_PENDING,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * 非阻塞异步入队：供同步回调（如 HookDispatcher 的 onPostToolUse）安全调用，
     * 不阻塞调用线程；单个任务失败被隔离并记日志，不抛出到调用方。
     */
    fun enqueueAsync(sessionId: String?, source: String, type: String, content: String) {
        if (content.isBlank()) return
        scope.launch {
            try {
                enqueue(sessionId, source, type, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "WakeQueue 异步入队失败 source=$source type=$type", e)
            }
        }
    }

    /** 读取某会话的全部待注入唤醒（按入队时间升序）。sessionId 为 null/空串时仅匹配全局唤醒。 */
    suspend fun pendingForSession(sessionId: String?): List<WakeItemEntity> =
        dao.getBySessionAndStatus(sessionId.orEmpty(), WakeItemEntity.STATUS_PENDING)

    /** 消费确认：把已成功注入的唤醒标记为 CONSUMED（防重复注入）。空列表安全返回。 */
    suspend fun markConsumed(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.updateStatus(ids, WakeItemEntity.STATUS_CONSUMED)
    }

    /** 全部待注入唤醒（启动重扫用）。 */
    suspend fun allPending(): List<WakeItemEntity> =
        dao.getByStatus(WakeItemEntity.STATUS_PENDING)

    private companion object {
        const val TAG = "WakeQueueManager"
    }
}
