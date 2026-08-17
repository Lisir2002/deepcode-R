package com.R.codecore.feature.agent.domain.tool

import com.R.codecore.feature.agent.domain.permission.PermissionChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ToolPermissionManager @Inject constructor() {
    private val requestMutex = Mutex()
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    private var currentId: String? = null
    private var currentSessionId: String? = null
    private var currentDecision: CompletableDeferred<PermissionChoice>? = null

    /**
     * 挂起等待用户在弹窗中的选择（拒绝/本次/始终）。同一时刻只允许一个待决请求（mutex 串行化）。
     *
     * @param sessionId 发起请求的 AI 会话 id；记录到 pending request 供 UI 按会话过滤、
     *   供 [cancelPending] 按会话清理，避免跨会话/对话结束后仍弹出确认卡。
     */
    suspend fun awaitApproval(sessionId: String?, request: PendingToolPermission): PermissionChoice = requestMutex.withLock {
        val decision = CompletableDeferred<PermissionChoice>()
        currentId = request.id
        currentSessionId = sessionId
        currentDecision = decision
        _pendingRequest.value = if (sessionId != null) request.copy(sessionId = sessionId) else request

        try {
            decision.await()
        } finally {
            if (currentId == request.id) {
                currentId = null
                currentSessionId = null
                currentDecision = null
                _pendingRequest.value = null
            }
        }
    }

    /** UI 回传用户选择，唤醒挂起的 [awaitApproval]。 */
    @Synchronized
    fun resolve(id: String, choice: PermissionChoice) {
        if (currentId != id) return
        _pendingRequest.value = null
        currentDecision?.complete(choice)
    }

    /**
     * 主动结束待决权限请求（对话停止 / 结束 / 切换会话时调用），防止弹窗残留。
     *
     * @param sessionId 要清理的会话 id；传 null 表示清理「任意会话」的待决请求
     *   （例如 stopAllAgents 全停）。没有匹配的待决请求时为空操作。
     * @param choice 以何种选择结束挂起的等待：默认拒绝（REJECT），对应工具返回
     *   「用户拒绝执行该工具」。
     */
    @Synchronized
    fun cancelPending(sessionId: String?, choice: PermissionChoice = PermissionChoice.REJECT) {
        if (currentId == null) return
        if (sessionId != null && currentSessionId != null && currentSessionId != sessionId) return
        _pendingRequest.value = null
        currentDecision?.complete(choice)
    }

    /** 当前是否有与指定会话匹配的待决请求（sessionId 为 null 时只看是否有任意待决请求）。 */
    @Synchronized
    fun hasPendingFor(sessionId: String?): Boolean {
        val current = _pendingRequest.value ?: return false
        if (sessionId == null) return true
        return current.sessionId == null || current.sessionId == sessionId
    }
}
