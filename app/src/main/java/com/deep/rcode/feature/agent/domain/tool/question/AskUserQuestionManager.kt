package com.deep.rcode.feature.agent.domain.tool.question

import com.deep.rcode.core.util.FileLogger
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

/**
 * 管理 ask_user_question 工具的 park-and-resume 流程。
 *
 * 镜像 [com.deep.rcode.feature.agent.domain.tool.ToolPermissionManager] 的设计：
 * 工具侧调用 [awaitAnswer] 挂起；UI 侧观察 [pendingQuestion] 渲染面板；
 * 用户选择后调用 [resolve] 唤醒工具侧。
 *
 * 同一时刻只允许一个待决问题（mutex 串行化），与权限弹窗互不干扰。
 */
@Singleton
class AskUserQuestionManager @Inject constructor() {

    private companion object {
        const val TAG = "AskUserQuestionManager"
        // Q-3：用户回答超时时间，超时放行避免会话无限阻塞
        const val ANSWER_TIMEOUT_MS = 3 * 60 * 1000L
    }

    private val requestMutex = Mutex()

    private val _pendingQuestion = MutableStateFlow<PendingUserQuestion?>(null)

    /** UI 观察此 StateFlow 来决定是否显示问题面板。 */
    val pendingQuestion: StateFlow<PendingUserQuestion?> = _pendingQuestion.asStateFlow()

    private var currentId: String? = null
    private var currentDecision: CompletableDeferred<UserQuestionAnswer>? = null

    /**
     * 工具侧调用：发起一个问题请求，挂起等待用户回答。
     * 同一时刻只允许一个请求（由 [requestMutex] 保证串行）。
     */
    suspend fun awaitAnswer(question: PendingUserQuestion): UserQuestionAnswer = requestMutex.withLock {
        val decision = CompletableDeferred<UserQuestionAnswer>()
        currentId = question.id
        currentDecision = decision
        _pendingQuestion.value = question

        try {
            // Q-3：加超时，用户长时间未回答时放行，防止会话被无限挂起阻塞
            withTimeout(ANSWER_TIMEOUT_MS) { decision.await() }
        } catch (e: TimeoutCancellationException) {
            FileLogger.w(TAG, "ask_user_question 等待用户回答超时(${ANSWER_TIMEOUT_MS}ms)，放行")
            UserQuestionAnswer(answers = emptyList())
        } finally {
            if (currentId == question.id) {
                currentId = null
                currentDecision = null
                _pendingQuestion.value = null
            }
        }
    }

    /**
     * UI 侧调用：用户完成选择后，将答案回传，唤醒挂起的 [awaitAnswer]。
     */
    @Synchronized
    fun resolve(id: String, answer: UserQuestionAnswer) {
        if (currentId != id) return
        _pendingQuestion.value = null
        currentDecision?.complete(answer)
    }
}
