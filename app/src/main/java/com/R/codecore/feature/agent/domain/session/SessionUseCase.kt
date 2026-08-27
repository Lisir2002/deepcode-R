package com.R.codecore.feature.agent.domain.session

import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_session as V2AgentSession
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {
    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** 工具占位行前缀：标记「执行中、结果未回」的孤儿，UI 与回放据此识别。 */
        const val PENDING_TOOL_MARKER = "[running]"
        /** 历史版本 emoji 前缀；冷启动收尾与回放仍需识别。 */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** 历史版本停止 emoji 前缀；UI 剥离结果文本时兼容。 */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "执行被中断（应用已关闭）"
    }

    /** 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。 */
    suspend fun initColdStartCleanup() {
        runCatching {
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                v2Agent.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "冷启动收尾 $n 条残留「执行中」工具行为已中断")
        }.onFailure { FileLogger.e(TAG, "回收残留执行中工具行失败", it) }
    }

    fun newSessionEntity(workspacePath: String): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            workspacePath = workspacePath,
            createdAtMs = now,
            updatedAtMs = now
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "新对话" }
        else clean.take(TITLE_MAX) + "…"
    }

    /**
     * 删除会话，返回需要清理的状态 id 集合，由 ViewModel 执行状态清理。
     *
     * 级联清理覆盖 10 张关联表（设计文档 chat-session-list-refactor-design C2 + D2-3 轨迹表）：
     * todo / hunk / 模式切换历史 / 技能会话态 / 唤醒队列(该会话行) / 任务编排 4 表 / 运行轨迹表。
     * 审计类（zth_*、hallucination_fuses）与全局项（wake_queue 空 session）明确保留。
     *
     * V2 分支：SQLDelight [V2Tx] 事务封装（单 driver 顺序执行，语义对齐 Room 事务）。
     */
    suspend fun deleteSession(id: String): String {
        v2Agent.runInTx { tx ->
            tx.deleteBySession(id)
            tx.deleteTodosBySession(id)
            tx.deleteFileEditHunksBySession(id)
            tx.deleteModeSwitchesBySession(id)
            tx.deleteSkillConversationStatesBySession(id)
            tx.deleteWakeItemsBySession(id)
            tx.deleteGoalsBySession(id)
            tx.deletePlansBySession(id)
            tx.deleteJobsBySession(id)
            tx.deleteSchedulesBySession(id)
            tx.deleteTrajectories(id)
            tx.deleteSession(id)
        }
        return id
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return v2Agent.getAllSessionsByWorkspaceOnce(workspacePath).firstOrNull()?.toEntity()
    }

    /** 最近一条「未绑定工作台」的会话（按更新时间降序，工作台绑定在首条消息时自动发生，此前会话处于未绑定态）。 */
    suspend fun getFirstUnboundSession(): ChatSessionEntity? {
        return v2Agent.getUnboundSessionsOnce().firstOrNull()?.toEntity()
    }

    /** 全局最近一条会话（任意工作台，删当前会话后重选兜底）。 */
    suspend fun getMostRecentSession(): ChatSessionEntity? {
        return v2Agent.getMostRecentOnce()?.toEntity()
    }

    /** 绑定/解绑会话工作台路径。绑定即一次性的（会话中途不可切换工作台）：仅未绑定会话可绑定，已绑定则忽略。 */
    suspend fun bindWorkspace(sessionId: String, workspacePath: String) {
        if (workspacePath.isBlank()) return
        val current = v2Agent.getSessionById(sessionId)?.workspace_path ?: ""
        if (current.isNotBlank()) return
        v2Agent.setWorkspacePath(sessionId, workspacePath)
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        v2Agent.upsertSession(
            id = entity.id, title = entity.title, mode = entity.mode, model = entity.model, status = "active",
            createdAtMs = entity.createdAtMs, updatedAtMs = entity.updatedAtMs,
            workspacePath = entity.workspacePath, reasoningEffort = entity.reasoningEffort,
            providerId = entity.providerId, totalInputTokens = entity.totalInputTokens.toLong(),
            totalOutputTokens = entity.totalOutputTokens.toLong(), lastInputTokens = entity.lastInputTokens.toLong(),
        )
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。长度兜底截断对齐 TITLE_MAX。 */
    suspend fun updateTitle(sessionId: String, title: String) {
        v2Agent.updateTitle(sessionId, title.trim().take(TITLE_MAX))
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        v2Agent.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return v2Agent.getSessionById(id)?.toEntity()
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = getSessionById(sessionId) ?: return
        v2Agent.upsertSession(
            id = s.id, title = s.title, mode = mode, model = s.model, status = "active",
            createdAtMs = s.createdAtMs, updatedAtMs = s.updatedAtMs,
            workspacePath = s.workspacePath, reasoningEffort = s.reasoningEffort,
            providerId = s.providerId, totalInputTokens = s.totalInputTokens.toLong(),
            totalOutputTokens = s.totalOutputTokens.toLong(), lastInputTokens = s.lastInputTokens.toLong(),
        )
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        v2Agent.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        v2Agent.updateReasoningEffort(sessionId, effort)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return v2Agent.getMessagesBySessionOnce(sessionId).isEmpty()
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
