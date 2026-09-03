package com.core.deepcode.feature.agent.domain.schedule

import com.core.deepcode.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定时提醒调度循环（对齐 DSH schedule 运行时，App 级单例）。
 *
 * 启动时由 [AIEditorApp] 用 appScope 启动 [start]，按固定间隔轮询扫描
 * [ScheduleService.dueAt] 到点的待投递项；到点项若对应会话已注册 [ScheduleFiredListener]
 * （由 AIAgentViewModel 在创建会话时注册），则回调注入一条带 "scheduled" 标记的
 * user/message，随后 [ScheduleService.markFired] 推进状态（一次性置 FIRED / 周期推进锚点）。
 *
 * 持久化屏障：会话未打开（无监听）时**不投递也不消费**，保留 PENDING，待会话打开后
 * 补投一次——跨重启/跨会话恢复靠 Room 持久化 + 启动重扫，不丢提醒。
 */
@Singleton
class ScheduleScheduler @Inject constructor(
    private val scheduleService: ScheduleService
) {
    private companion object {
        const val TAG = "ScheduleScheduler"
        /** 轮询间隔：EVERY 周期项按该粒度近似触发（过小周期会被夹到该粒度）。 */
        const val POLL_INTERVAL_MS = 30_000L
    }

    /** 到点投递回调（按会话注册）。 */
    fun interface ScheduleFiredListener {
        suspend fun onScheduleFired(scheduleId: String, prompt: String)
    }

    private val listeners = ConcurrentHashMap<String, ScheduleFiredListener>()

    /** 会话创建/打开时注册投递回调。 */
    fun register(sessionId: String, listener: ScheduleFiredListener) {
        listeners[sessionId] = listener
    }

    /** 会话销毁/ViewModel 清理时注销，避免泄漏与误投。 */
    fun unregister(sessionId: String) {
        listeners.remove(sessionId)
    }

    /** 在 [scope] 中启动周期调度循环（App 启动调用一次；scope 取消即停）。 */
    fun start(scope: CoroutineScope) {
        scope.launch {
            FileLogger.d(TAG, "调度循环已启动（轮询 ${POLL_INTERVAL_MS}ms）")
            while (currentCoroutineContext().isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "调度循环单次 tick 失败（隔离，继续下一轮）", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 扫描并投递当前到点的定时项。 */
    suspend fun tick() {
        val now = System.currentTimeMillis()
        val due = scheduleService.dueAt(now)
        if (due.isEmpty()) return
        for (entity in due) {
            val listener = listeners[entity.sessionId]
            if (listener == null) {
                // 会话未打开：不投递不消费，保留 PENDING 待补投（持久化屏障）。
                FileLogger.d(TAG, "到点但会话无监听，保留待投: scheduleId=${entity.scheduleId} session=${entity.sessionId}")
                continue
            }
            val prompt = scheduleService.parseArgs(entity).prompt
            if (prompt.isBlank()) {
                // 无提醒正文：无意义，直接消费防止反复扫描。
                scheduleService.markFired(entity.scheduleId, now)
                continue
            }
            runCatching { listener.onScheduleFired(entity.scheduleId, prompt) }
                .onSuccess { scheduleService.markFired(entity.scheduleId, now) }
                .onFailure { e ->
                    FileLogger.w(TAG, "定时投递失败，保留待下次重试: scheduleId=${entity.scheduleId}", e)
                }
        }
    }
}
