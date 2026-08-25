package com.R.codecore.feature.agent.domain.session

import androidx.room.withTransaction
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val agentDatabase: AgentDatabase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao
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
                agentMessageDao.markPendingToolsInterrupted(
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
     * DB 侧单事务原子执行：删消息 + 级联清理全部带 sessionId 的关联表 + 删会话，
     * 中途失败整体回滚，不留孤儿数据。
     *
     * 级联清理覆盖 10 张关联表（设计文档 chat-session-list-refactor-design C2 + D2-3 轨迹表）：
     * todo / hunk / 模式切换历史 / 技能会话态 / 唤醒队列(该会话行) / 任务编排 4 表 / 运行轨迹表。
     * 审计类（zth_*、hallucination_fuses）与全局项（wake_queue 空 session）明确保留。
     */
    suspend fun deleteSession(id: String): String {
        agentDatabase.withTransaction {
            agentMessageDao.deleteBySession(id)
            agentDatabase.todoItemDao().deleteBySession(id)
            agentDatabase.fileEditHunkDao().deleteBySession(id)
            agentDatabase.modeSwitchHistoryDao().deleteBySession(id)
            agentDatabase.skillConversationStateDao().deleteBySession(id)
            agentDatabase.wakeQueueDao().deleteBySession(id)
            agentDatabase.goalDao().deleteBySession(id)
            agentDatabase.planDao().deleteBySession(id)
            agentDatabase.jobDao().deleteBySession(id)
            agentDatabase.scheduleDao().deleteBySession(id)
            agentDatabase.trajectoryDao().deleteBySession(id)
            chatSessionDao.delete(id)
        }
        return id
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /** 最近一条「未绑定工作台」的会话（按更新时间降序，工作台绑定在首条消息时自动发生，此前会话处于未绑定态）。 */
    suspend fun getFirstUnboundSession(): ChatSessionEntity? {
        return chatSessionDao.getUnboundSessionsOnce().firstOrNull()
    }

    /** 全局最近一条会话（任意工作台，删当前会话后重选兜底）。 */
    suspend fun getMostRecentSession(): ChatSessionEntity? {
        return chatSessionDao.getMostRecentOnce()
    }

    /** 绑定/解绑会话工作台路径。绑定即一次性的（会话中途不可切换工作台）：仅未绑定会话可绑定，已绑定则忽略。 */
    suspend fun bindWorkspace(sessionId: String, workspacePath: String) {
        if (workspacePath.isBlank()) return
        val current = chatSessionDao.getById(sessionId)?.workspacePath ?: return
        if (current.isNotBlank()) return
        chatSessionDao.setWorkspacePath(sessionId, workspacePath)
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。长度兜底截断对齐 TITLE_MAX。 */
    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title.trim().take(TITLE_MAX))
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = chatSessionDao.getById(sessionId) ?: return
        chatSessionDao.upsert(s.copy(mode = mode))
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return agentMessageDao.getMessagesBySessionOnce(sessionId).isEmpty()
    }
}
