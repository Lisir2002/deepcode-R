package com.core.deepcode.feature.browser.domain

import com.core.deepcode.core.util.FileLogger
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

/** 用户回填的登录凭据。 */
data class LoginPromptAnswer(
    val username: String?,
    val password: String?,
    /** true 表示用户取消。 */
    val cancelled: Boolean
)

/**
 * 管理浏览器登录凭据的 park-and-resume 流程（镜像 [AskUserQuestionManager]）。
 *
 * 工具侧（login 动作）调用 [awaitCredentials] 挂起；UI 侧观察 [pendingPrompt] 弹凭据输入面板；
 * 用户填写后调用 [resolve]/[cancel] 唤醒工具侧。
 */
@Singleton
class BrowserLoginPromptManager @Inject constructor() {

    private companion object {
        const val TAG = "BrowserLoginPromptManager"
        const val PROMPT_TIMEOUT_MS = 3 * 60 * 1000L
    }

    private val mutex = Mutex()

    private val _pendingPrompt = MutableStateFlow<PendingLoginPrompt?>(null)
    /** UI 观察此 StateFlow 来决定是否显示凭据输入面板。 */
    val pendingPrompt: StateFlow<PendingLoginPrompt?> = _pendingPrompt.asStateFlow()

    private var currentId: String? = null
    private var currentDeferred: CompletableDeferred<LoginPromptAnswer>? = null

    /** 工具侧调用：请求用户为该 host 提供凭据，挂起等待。 */
    suspend fun awaitCredentials(host: String): LoginPromptAnswer = mutex.withLock {
        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<LoginPromptAnswer>()
        currentId = id
        currentDeferred = deferred
        _pendingPrompt.value = PendingLoginPrompt(requestId = id, host = host, title = "需要登录 $host")

        try {
            withTimeout(PROMPT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            FileLogger.w(TAG, "等待用户输入凭据超时(${PROMPT_TIMEOUT_MS}ms)，放行")
            LoginPromptAnswer(username = null, password = null, cancelled = true)
        } finally {
            if (currentId == id) {
                currentId = null
                currentDeferred = null
                _pendingPrompt.value = null
            }
        }
    }

    /** UI 侧调用：用户填写完成，唤醒挂起的 [awaitCredentials]。 */
    @Synchronized
    fun resolve(id: String, answer: LoginPromptAnswer) {
        if (currentId != id) return
        _pendingPrompt.value = null
        currentDeferred?.complete(answer)
    }

    /** UI 侧调用：用户取消。 */
    @Synchronized
    fun cancel(id: String) {
        if (currentId != id) return
        _pendingPrompt.value = null
        currentDeferred?.complete(LoginPromptAnswer(null, null, cancelled = true))
    }
}
