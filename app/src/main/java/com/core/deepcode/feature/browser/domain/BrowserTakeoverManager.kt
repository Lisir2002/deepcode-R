package com.core.deepcode.feature.browser.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** 用户对接管请求的答复。 */
data class TakeoverAnswer(
    /** true=用户已亲自完成操作并让模型继续；false=用户取消/跳过。 */
    val confirmed: Boolean,
    /** 用户可选附加说明。 */
    val note: String? = null
)

/** 模型发起的「请用户接管浏览器」请求。 */
data class PendingTakeover(
    val requestId: String,
    val title: String,
    val message: String
)

/**
 * 浏览器「用户接管」park-and-resume 流程（镜像 [BrowserLoginPromptManager]）。
 *
 * 模型遇到无法自动完成的操作（验证码、支付、二次认证、需真人判断的页面）时，
 * 调用 [awaitTakeover] 挂起并弹出接管提示；用户在浏览器页亲自完成后点「已完成」，
 * 通过 [resolve] 唤醒模型侧继续。用户取消则 [cancel]。
 */
@Singleton
class BrowserTakeoverManager @Inject constructor() {

    private companion object {
        const val TAG = "BrowserTakeoverManager"
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val mutex = Mutex()

    private val _pending = MutableStateFlow<PendingTakeover?>(null)
    /** UI 观察此 StateFlow 来决定是否显示接管提示面板。 */
    val pending: StateFlow<PendingTakeover?> = _pending.asStateFlow()

    private var currentId: String? = null
    private var currentDeferred: CompletableDeferred<TakeoverAnswer>? = null

    /** 工具侧调用：请求用户接管当前页面，挂起等待用户完成/取消。 */
    suspend fun awaitTakeover(title: String, message: String): TakeoverAnswer = mutex.withLock {
        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TakeoverAnswer>()
        currentId = id
        currentDeferred = deferred
        _pending.value = PendingTakeover(requestId = id, title = title, message = message)

        try {
            withTimeout(TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            TakeoverAnswer(confirmed = false, note = null)
        } finally {
            if (currentId == id) {
                currentId = null
                currentDeferred = null
                _pending.value = null
            }
        }
    }

    /** UI 侧：用户已完成接管，唤醒挂起的工具侧。 */
    @Synchronized
    fun resolve(id: String, answer: TakeoverAnswer) {
        if (currentId != id) return
        _pending.value = null
        currentDeferred?.complete(answer)
    }

    /** UI 侧：用户取消接管。 */
    @Synchronized
    fun cancel(id: String) {
        resolve(id, TakeoverAnswer(confirmed = false, note = null))
    }
}