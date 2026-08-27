package com.R.codecore.feature.agent.domain.tool.mode

import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_session as V2AgentSession
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理计划审查流程的挂起/恢复：
 * 当 AI 从 PLAN 模式切回 BUILD 模式时，workflow 调用 [awaitApproval] 挂起，
 * 等 UI 上的计划审查面板得到用户决策后恢复。
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {
    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentDecision: CompletableDeferred<PlanApprovalChoice>? = null
    private var currentSessionId: String? = null

    private suspend fun getSessionEntity(sid: String): ChatSessionEntity? =
        v2Agent.getSessionById(sid)?.toEntity()

    private suspend fun upsertMode(sid: String, entity: ChatSessionEntity, mode: String) {
        v2Agent.upsertSession(
            id = entity.id, title = entity.title, mode = mode, model = entity.model, status = "active",
            createdAtMs = entity.createdAtMs, updatedAtMs = entity.updatedAtMs,
            workspacePath = entity.workspacePath, reasoningEffort = entity.reasoningEffort,
            providerId = entity.providerId, totalInputTokens = entity.totalInputTokens.toLong(),
            totalOutputTokens = entity.totalOutputTokens.toLong(), lastInputTokens = entity.lastInputTokens.toLong(),
        )
    }

    /** 挂起等待用户在计划审查面板中的决策。 */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val decision = CompletableDeferred<PlanApprovalChoice>()
        currentDecision = decision
        currentSessionId = sessionId
        _pendingApproval.value = PlanApprovalRequest(reason = reason)

        try {
            return decision.await()
        } finally {
            currentDecision = null
            currentSessionId = null
            _pendingApproval.value = null
        }
    }

    /** UI 回传用户选择，唤醒挂起的 [awaitApproval]。 */
    fun resolve(choice: PlanApprovalChoice) {
        _pendingApproval.value = null

        // 用户拒绝时，回滚数据库中的模式到 PLAN（SwitchModeTool 已写入 BUILD）
        if (choice == PlanApprovalChoice.REFINE) {
            val sid = currentSessionId
            if (sid != null) {
                scope.launch {
                    val entity = getSessionEntity(sid)
                    if (entity != null && entity.mode != "PLAN") {
                        upsertMode(sid, entity, "PLAN")
                    }
                }
            }
        }

        currentDecision?.complete(choice)
    }

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun V2AgentSession.toEntity() = ChatSessionEntity(
        id = id,
        title = title ?: "",
        createdAtMs = created_at,
        updatedAtMs = updated_at,
        workspacePath = workspace_path,
        mode = mode,
        reasoningEffort = reasoning_effort,
        providerId = provider_id,
        model = model,
        totalInputTokens = total_input_tokens.toInt(),
        totalOutputTokens = total_output_tokens.toInt(),
        lastInputTokens = last_input_tokens.toInt(),
    )
}

data class PlanApprovalRequest(val reason: String)

enum class PlanApprovalChoice {
    /** 批准计划，切换到 BUILD 模式执行 */
    APPROVE,
    /** 拒绝，留在 PLAN 模式继续反馈 */
    REFINE
}
