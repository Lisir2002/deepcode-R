package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.R.codecore.datalayer.sqldelight.AgentDb
import com.R.codecore.datalayer.sqldelight.agent.Agent_message
import com.R.codecore.datalayer.sqldelight.agent.Agent_session
import com.R.codecore.datalayer.sqldelight.agent.SelectAllSessionsWithCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * agent 域 Repository（设计 §11.1 / L2）：会话/消息/子块/工具调用/检查点 的访问门面。
 * 业务只依赖本门面，不直接写 SQL。
 *
 * v2-full-takeover P0-1：补 Flow 响应式读，对齐 Room DAO 的 22 个 Flow 查询。
 * v2-full-takeover P2-3：agent_session / agent_message 对齐 Room 实体全列，
 *  本门面补齐 ChatSessionDao(22) + AgentMessageDao(25) 全部等价方法。
 */
class AgentRepository(private val db: AgentDb) : WakeQueueStore {

    private val q get() = db.agentQueries

    // ── 会话（ChatSessionDao 等价）──────────────────────────────────────

    suspend fun upsertSession(
        id: String, title: String?, mode: String, model: String?, status: String,
        createdAtMs: Long, updatedAtMs: Long, workspacePath: String, reasoningEffort: String,
        providerId: String?, totalInputTokens: Long, totalOutputTokens: Long, lastInputTokens: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertSession(id, title, mode, model, status, createdAtMs, updatedAtMs, workspacePath, reasoningEffort, providerId, totalInputTokens, totalOutputTokens, lastInputTokens)
    }

    suspend fun createSession(
        id: String, title: String?, mode: String, model: String?, now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        q.insertSession(id, title, mode, model, "active", now, now, "", "MEDIUM", null, 0L, 0L, 0L)
    }

    suspend fun listSessions(): List<Agent_session> =
        withContext(Dispatchers.IO) { q.selectAllSessions().executeAsList() }

    suspend fun getSession(id: String): Agent_session? =
        withContext(Dispatchers.IO) { q.selectSessionById(id).executeAsOneOrNull() }

    suspend fun renameSession(id: String, title: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.updateSessionTitle(title, now, id) }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) { q.deleteSession(id) }

    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<Agent_session> =
        withContext(Dispatchers.IO) { q.selectSessionsByWorkspace(workspacePath).executeAsList() }

    fun observeAllSessionsByWorkspace(workspacePath: String): Flow<List<Agent_session>> =
        q.selectSessionsByWorkspace(workspacePath).asFlow().mapToList(Dispatchers.IO)

    fun observeAllSessionsWithCount(): Flow<List<SelectAllSessionsWithCount>> =
        q.selectAllSessionsWithCount().asFlow().mapToList(Dispatchers.IO)

    suspend fun getAllOnce(): List<Agent_session> =
        withContext(Dispatchers.IO) { q.selectAllSessions().executeAsList() }

    suspend fun getMostRecentOnce(): Agent_session? =
        withContext(Dispatchers.IO) { q.selectMostRecentSession().executeAsOneOrNull() }

    suspend fun getUnboundSessionsOnce(): List<Agent_session> =
        withContext(Dispatchers.IO) { q.selectUnboundSessions().executeAsList() }

    suspend fun countSessions(): Long =
        withContext(Dispatchers.IO) { q.countSessions().executeAsOne() }

    suspend fun getSessionPageAfter(lastUpdatedAtMs: Long, lastId: String, limit: Long): List<Agent_session> =
        withContext(Dispatchers.IO) { q.selectSessionPageAfter(lastUpdatedAtMs, lastUpdatedAtMs, lastId, limit).executeAsList() }

    suspend fun upsertAllSessions(sessions: List<Agent_session>) = withContext(Dispatchers.IO) {
        sessions.forEach { s ->
            q.upsertSession(s.id, s.title, s.mode, s.model, s.status, s.created_at, s.updated_at, s.workspace_path, s.reasoning_effort, s.provider_id, s.total_input_tokens, s.total_output_tokens, s.last_input_tokens)
        }
    }

    suspend fun getSessionById(id: String): Agent_session? =
        withContext(Dispatchers.IO) { q.selectSessionById(id).executeAsOneOrNull() }

    suspend fun updateTitle(id: String, title: String) =
        withContext(Dispatchers.IO) { q.updateSessionTitle(title, System.currentTimeMillis(), id) }

    suspend fun updateWorkspacePath(oldPath: String, newPath: String) =
        withContext(Dispatchers.IO) { q.updateSessionWorkspacePath(newPath, oldPath) }

    suspend fun setWorkspacePath(id: String, path: String) =
        withContext(Dispatchers.IO) { q.setSessionWorkspacePath(path, id) }

    suspend fun touch(id: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.touchSession(updatedAtMs, id) }

    suspend fun updateProviderModel(id: String, providerId: String?, model: String?) =
        withContext(Dispatchers.IO) { q.updateSessionProviderModel(providerId, model, id) }

    suspend fun updateReasoningEffort(id: String, effort: String) =
        withContext(Dispatchers.IO) { q.updateSessionReasoningEffort(effort, id) }

    suspend fun addTokenUsage(id: String, inputTokens: Long, outputTokens: Long) =
        withContext(Dispatchers.IO) { q.addSessionTokenUsage(inputTokens, outputTokens, id) }

    suspend fun updateLastInputTokens(id: String, lastInputTokens: Long) =
        withContext(Dispatchers.IO) { q.updateSessionLastInputTokens(lastInputTokens, id) }

    // ── 消息（AgentMessageDao 等价）──────────────────────────────────────

    suspend fun insertMessage(e: Agent_message) = withContext(Dispatchers.IO) {
        q.insertMessage(e.id, e.session_id, e.role, e.seq, e.created_at, e.task_id, e.content, e.tool_calls_json, e.tool_call_id, e.tool_name, e.tool_args, e.is_error, e.reasoning, e.signature, e.attachments_json, e.is_compacted, e.is_context_summary, e.is_compaction_marker, e.input_tokens, e.output_tokens, e.chunk_group_id, e.chunk_index)
    }

    suspend fun insertAllMessages(messages: List<Agent_message>) = withContext(Dispatchers.IO) {
        messages.forEach { insertMessage(it) }
    }

    fun observeMessagesBySession(sessionId: String): Flow<List<Agent_message>> =
        q.selectMessagesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeMessagesBySessionPaged(sessionId: String, limit: Long): Flow<List<Agent_message>> =
        q.selectMessagesBySessionPaged(sessionId, limit).asFlow().mapToList(Dispatchers.IO)

    suspend fun getMessagesBySessionOnce(sessionId: String): List<Agent_message> =
        withContext(Dispatchers.IO) { q.selectMessagesBySession(sessionId).executeAsList() }

    suspend fun deleteBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteMessagesBySession(sessionId) }

    suspend fun deleteMessagesBeforeTimestamp(sessionId: String, cutoffTimestamp: Long) =
        withContext(Dispatchers.IO) { q.deleteMessagesBeforeTimestamp(sessionId, cutoffTimestamp) }

    suspend fun markMessagesCompactedBeforeTimestamp(sessionId: String, cutoffTimestamp: Long) =
        withContext(Dispatchers.IO) { q.markMessagesCompactedBeforeTimestamp(sessionId, cutoffTimestamp) }

    suspend fun markMessagesCompactedInclusiveFromTimestamp(sessionId: String, cutoffTimestamp: Long) =
        withContext(Dispatchers.IO) { q.markMessagesCompactedInclusiveFromTimestamp(sessionId, cutoffTimestamp) }

    suspend fun deleteAllMessages() = withContext(Dispatchers.IO) { q.deleteAllMessages() }

    suspend fun getMessageById(id: String): Agent_message? =
        withContext(Dispatchers.IO) { q.selectMessageById(id).executeAsOneOrNull() }

    suspend fun updateMessageContent(id: String, content: String) =
        withContext(Dispatchers.IO) { q.updateMessageContent(content, id) }

    suspend fun deleteMessageById(id: String) =
        withContext(Dispatchers.IO) { q.deleteMessageById(id) }

    suspend fun deleteMessagesInclusiveFromTimestamp(sessionId: String, cutoffTimestamp: Long) =
        withContext(Dispatchers.IO) { q.deleteMessagesInclusiveFromTimestamp(sessionId, cutoffTimestamp) }

    suspend fun deleteMessagesExclusiveAfterTimestamp(sessionId: String, cutoffTimestamp: Long) =
        withContext(Dispatchers.IO) { q.deleteMessagesExclusiveAfterTimestamp(sessionId, cutoffTimestamp) }

    suspend fun markPendingToolsInterrupted(toolRole: String, pendingPrefix: String, interruptedContent: String): Long =
        withContext(Dispatchers.IO) { q.markPendingToolsInterrupted(interruptedContent, toolRole, pendingPrefix).value }

    suspend fun searchMessages(query: String): List<Agent_message> =
        withContext(Dispatchers.IO) { q.searchMessages(query).executeAsList() }

    suspend fun getAllMessagesOnce(): List<Agent_message> =
        withContext(Dispatchers.IO) { q.selectAllMessages().executeAsList() }

    suspend fun getMessagePageAfter(lastTimestamp: Long, lastId: String, limit: Long): List<Agent_message> =
        withContext(Dispatchers.IO) { q.selectMessagePageAfter(lastTimestamp, lastTimestamp, lastId, limit).executeAsList() }

    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Long): List<Agent_message> =
        withContext(Dispatchers.IO) { q.selectMessagePageBySessionAfter(sessionId, lastTimestamp, lastTimestamp, lastId, limit).executeAsList() }

    // ── 旧版（保留：V1toV2FullMigrator 迁移期使用；P3 后删除）───────────────

    suspend fun appendMessage(id: String, sessionId: String, role: String, seq: Long, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            q.insertMessage(id, sessionId, role, seq, now, "", "", null, null, null, null, 0L, null, null, null, 0L, 0L, 0L, 0L, 0L, "", 0L)
        }

    suspend fun appendPart(
        id: String, messageId: String, kind: String, seq: Long,
        text: String?, toolName: String?, toolArgs: String?, toolResult: String?, toolError: String?,
    ) = withContext(Dispatchers.IO) {
        q.insertMessagePart(id, messageId, kind, seq, text, toolName, toolArgs, toolResult, toolError)
    }

    suspend fun appendToolCall(
        id: String, messageId: String, name: String, argsJson: String?, resultJson: String?, status: String,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertToolCall(id, messageId, name, argsJson, resultJson, status, now) }

    suspend fun saveCheckpoint(id: String, sessionId: String, snapshotJson: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.insertCheckpoint(id, sessionId, snapshotJson, now) }

    suspend fun listCheckpoints(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_checkpoint> =
        withContext(Dispatchers.IO) { q.selectCheckpointsBySession(sessionId).executeAsList() }

    fun observeAllSessions(): Flow<List<Agent_session>> =
        q.selectAllSessions().asFlow().mapToList(Dispatchers.IO)

    fun observeSessionById(id: String): Flow<Agent_session?> =
        q.selectSessionById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun observeCheckpointsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_checkpoint>> =
        q.selectCheckpointsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeTodoItemsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Todo_items>> =
        q.selectTodoItemsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeFileEditHunksBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.File_edit_hunks>> =
        q.selectFileEditHunksBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeModeSwitchesBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Mode_switch_history>> =
        q.selectModeSwitchesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeGoalsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_goals>> =
        q.selectGoalsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observePlansBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_plans>> =
        q.selectPlansBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeJobsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_jobs>> =
        q.selectJobsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeSchedulesBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_schedules>> =
        q.selectSchedulesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeTrajectoriesBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_trajectories>> =
        q.selectTrajectoriesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observePlaybookRunsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs>> =
        q.selectPlaybookRunsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeAllSkillStates(): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Skill_state>> =
        q.selectAllSkillStates().asFlow().mapToList(Dispatchers.IO)

    fun observePendingWakeItems(): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Wake_queue>> =
        q.selectPendingWakeItems().asFlow().mapToList(Dispatchers.IO)

    fun observeSentinelsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_user_confirmed_sentinels>> =
        q.selectSentinelsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeFuse(scope: String, scopeId: String): Flow<com.R.codecore.datalayer.sqldelight.agent.Zth_hallucination_fuses?> =
        q.selectFuse(scope, scopeId).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun observeAllFuses(): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_hallucination_fuses>> =
        q.selectAllFuses().asFlow().mapToList(Dispatchers.IO)

    fun observeRejectionAuditsBySentinel(sentinelId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_sentinel_plan_rejection_audits>> =
        q.selectRejectionAuditsBySentinel(sentinelId).asFlow().mapToList(Dispatchers.IO)

    fun observeRestoreLogsBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_l0_soft_compact_restore_logs>> =
        q.selectRestoreLogsBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    fun observeTelemetryByKind(eventKind: String, limit: Long): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_telemetry_events>> =
        q.selectTelemetryByKind(eventKind, limit).asFlow().mapToList(Dispatchers.IO)

    fun observeAllTelemetry(): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Zth_telemetry_events>> =
        q.selectAllTelemetry().asFlow().mapToList(Dispatchers.IO)

    // ── 阶段 1 补表方法（todo / checkpoint 快照 / 编辑 hunk / 模式切换 / 能力覆盖 / 编排 / 技能 / wake / zth）──

    suspend fun insertTodo(
        id: String, sessionId: String, subject: String, description: String, status: String,
        priority: Long, order: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertTodoItem(id, sessionId, subject, description, status, priority, order, createdAtMs, updatedAtMs)
    }

    suspend fun listTodos(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Todo_items> =
        withContext(Dispatchers.IO) { q.selectTodoItemsBySession(sessionId).executeAsList() }

    suspend fun updateTodoStatus(id: String, status: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updateTodoItemStatus(status, updatedAtMs, id) }

    suspend fun deleteTodo(id: String) = withContext(Dispatchers.IO) { q.deleteTodoItem(id) }

    // ── P2-3 备份流式导出/还原（对齐 TodoItemDao 分页 + upsertAll）─────────

    suspend fun getTodoPageAfter(lastCreatedAtMs: Long, lastId: String, limit: Long): List<com.R.codecore.datalayer.sqldelight.agent.Todo_items> =
        withContext(Dispatchers.IO) { q.selectTodoPageAfter(lastCreatedAtMs, lastId, limit).executeAsList() }

    suspend fun getTodoPageBySessionAfter(sessionId: String, lastCreatedAtMs: Long, lastId: String, limit: Long): List<com.R.codecore.datalayer.sqldelight.agent.Todo_items> =
        withContext(Dispatchers.IO) { q.selectTodoPageBySessionAfter(sessionId, lastCreatedAtMs, lastId, limit).executeAsList() }

    suspend fun upsertAllTodos(todos: List<com.R.codecore.datalayer.sqldelight.agent.Todo_items>) = withContext(Dispatchers.IO) {
        todos.forEach { t ->
            q.upsertTodoItem(t.id, t.session_id, t.subject, t.description, t.status, t.priority, t.sort_order, t.created_at_ms, t.updated_at_ms)
        }
    }

    // ── P2-3 会话级联删除（对齐 SessionUseCase.deleteSession）─────────────

    suspend fun deleteTodosBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteTodoItemsBySession(sessionId) }

    suspend fun deleteFileEditHunksBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteFileEditHunksBySession(sessionId) }

    suspend fun deleteModeSwitchesBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteModeSwitchesBySession(sessionId) }

    suspend fun deleteWakeItemsBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteWakeItemsBySession(sessionId) }

    suspend fun deleteGoalsBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteGoalsBySession(sessionId) }

    suspend fun deletePlansBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deletePlansBySession(sessionId) }

    suspend fun deleteJobsBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteJobsBySession(sessionId) }

    suspend fun deleteSchedulesBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteSchedulesBySession(sessionId) }

    suspend fun insertCheckpointFull(
        id: String, sessionId: String, userMessageId: String, promptSnippet: String, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertCheckpointFull(id, sessionId, userMessageId, promptSnippet, createdAtMs)
    }

    suspend fun getCheckpointById(checkpointId: String): com.R.codecore.datalayer.sqldelight.agent.Session_checkpoints? =
        withContext(Dispatchers.IO) { q.selectCheckpointById(checkpointId).executeAsOneOrNull() }

    suspend fun getCheckpointByMessageId(messageId: String): com.R.codecore.datalayer.sqldelight.agent.Session_checkpoints? =
        withContext(Dispatchers.IO) { q.selectCheckpointByMessageId(messageId).executeAsOneOrNull() }

    suspend fun listCheckpointsForSession(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Session_checkpoints> =
        withContext(Dispatchers.IO) { q.selectSessionCheckpoints(sessionId).executeAsList() }

    suspend fun deleteCheckpointsBySession(sessionId: String) {
        withContext(Dispatchers.IO) { q.deleteCheckpointsBySession(sessionId) }
    }

    suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long) {
        withContext(Dispatchers.IO) { q.deleteCheckpointsBefore(cutoffTimestamp) }
    }

    suspend fun insertCheckpointFileSnapshot(
        id: String, checkpointId: String, filePath: String, snapshotRelativePath: String,
        changeType: String, createdAt: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertCheckpointFileSnapshot(id, checkpointId, filePath, snapshotRelativePath, changeType, createdAt)
    }

    suspend fun listCheckpointFileSnapshots(checkpointId: String): List<com.R.codecore.datalayer.sqldelight.agent.Checkpoint_file_snapshots> =
        withContext(Dispatchers.IO) { q.selectCheckpointFileSnapshots(checkpointId).executeAsList() }

    suspend fun countCheckpointFileSnapshot(checkpointId: String, filePath: String): Long =
        withContext(Dispatchers.IO) { q.countCheckpointFileSnapshot(checkpointId, filePath).executeAsOne() }

    suspend fun deleteCheckpointFileSnapshotsBySession(sessionId: String) {
        withContext(Dispatchers.IO) { q.deleteCheckpointFileSnapshotsBySession(sessionId) }
    }

    suspend fun insertFileEditHunk(
        id: String, sessionId: String, filePath: String, operation: String, hunk: String,
        oldContent: String, newContent: String, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertFileEditHunk(id, sessionId, filePath, operation, hunk, oldContent, newContent, createdAtMs)
    }

    suspend fun listFileEditHunks(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.File_edit_hunks> =
        withContext(Dispatchers.IO) { q.selectFileEditHunksBySession(sessionId).executeAsList() }

    suspend fun insertModeSwitch(
        sessionId: String, fromMode: String, toMode: String, reason: String, timestampMs: Long,
    ) = withContext(Dispatchers.IO) { q.insertModeSwitch(sessionId, fromMode, toMode, reason, timestampMs) }

    suspend fun listModeSwitches(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Mode_switch_history> =
        withContext(Dispatchers.IO) { q.selectModeSwitchesBySession(sessionId).executeAsList() }

    suspend fun upsertCapabilityOverride(
        id: String, providerType: String, modelId: String,
        overrideVision: Long?, overrideTools: Long?, overrideReasoning: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertCapabilityOverride(id, providerType, modelId, overrideVision, overrideTools, overrideReasoning, updatedAtMs)
    }

    suspend fun getCapabilityOverride(providerType: String, modelId: String): com.R.codecore.datalayer.sqldelight.agent.Model_capability_overrides? =
        withContext(Dispatchers.IO) { q.selectCapabilityOverride(providerType, modelId).executeAsOneOrNull() }

    fun observeCapabilityOverride(providerType: String, modelId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Model_capability_overrides>> =
        q.observeCapabilityOverride(providerType, modelId).asFlow().mapToList(Dispatchers.IO)

    suspend fun deleteCapabilityOverride(providerType: String, modelId: String) =
        withContext(Dispatchers.IO) { q.deleteCapabilityOverride(providerType, modelId) }

    suspend fun insertGoal(
        goalId: String, sessionId: String, text: String, status: String, revision: Long,
        parentGoalId: String, roundSeq: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertGoal(goalId, sessionId, text, status, revision, parentGoalId, roundSeq, createdAtMs, updatedAtMs)
    }

    suspend fun listGoals(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_goals> =
        withContext(Dispatchers.IO) { q.selectGoalsBySession(sessionId).executeAsList() }

    suspend fun updateGoalStatus(goalId: String, status: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updateGoalStatus(status, updatedAtMs, goalId) }

    suspend fun deleteGoal(goalId: String) = withContext(Dispatchers.IO) { q.deleteGoal(goalId) }

    // ── P2-4 GoalService 等价 ──────────────────────────────────────────

    suspend fun getGoalById(goalId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_goals? =
        withContext(Dispatchers.IO) { q.selectGoalById(goalId).executeAsOneOrNull() }

    suspend fun getActiveGoalBySession(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_goals? =
        withContext(Dispatchers.IO) { q.selectActiveGoalBySession(sessionId).executeAsOneOrNull() }

    /** 阻塞版（仅供 [runInTx] 事务块内使用，同线程）。 */
    fun getActiveGoalBySessionBlocking(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_goals? =
        q.selectActiveGoalBySession(sessionId).executeAsOneOrNull()

    suspend fun casUpdateGoalStatusAndText(
        goalId: String, status: String, text: String, newRevision: Long, expectedRevision: Long, updatedAtMs: Long,
    ): Long = withContext(Dispatchers.IO) {
        q.casUpdateGoalStatusAndText(status, text, newRevision, updatedAtMs, goalId, expectedRevision).value
    }

    suspend fun upsertGoal(
        goalId: String, sessionId: String, text: String, status: String, revision: Long,
        parentGoalId: String, roundSeq: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertGoal(goalId, sessionId, text, status, revision, parentGoalId, roundSeq, createdAtMs, updatedAtMs)
    }

    suspend fun insertPlan(
        planId: String, sessionId: String, title: String, steps: String, status: String,
        pendingSelection: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertPlan(planId, sessionId, title, steps, status, pendingSelection, createdAtMs, updatedAtMs)
    }

    suspend fun listPlans(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_plans> =
        withContext(Dispatchers.IO) { q.selectPlansBySession(sessionId).executeAsList() }

    suspend fun updatePlanStatus(planId: String, status: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updatePlanStatus(status, updatedAtMs, planId) }

    suspend fun deletePlan(planId: String) = withContext(Dispatchers.IO) { q.deletePlan(planId) }

    // ── P2-4 PlanService 等价 ──────────────────────────────────────────

    suspend fun getPlanById(planId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_plans? =
        withContext(Dispatchers.IO) { q.selectPlanById(planId).executeAsOneOrNull() }

    suspend fun getLatestPlanBySession(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_plans? =
        withContext(Dispatchers.IO) { q.selectLatestPlanBySession(sessionId).executeAsOneOrNull() }

    /** 阻塞版（仅供 [runInTx] 事务块内使用，同线程）。 */
    fun getLatestPlanBySessionBlocking(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_plans? =
        q.selectLatestPlanBySession(sessionId).executeAsOneOrNull()

    suspend fun updatePlanContent(
        planId: String, status: String, steps: String, pendingSelection: String, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updatePlanContent(status, steps, pendingSelection, updatedAtMs, planId)
    }

    suspend fun upsertPlan(
        planId: String, sessionId: String, title: String, steps: String, status: String,
        pendingSelection: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertPlan(planId, sessionId, title, steps, status, pendingSelection, createdAtMs, updatedAtMs)
    }

    suspend fun insertJob(
        jobId: String, sessionId: String, kind: String, title: String, status: String,
        exitCode: Long?, outputLocator: String, createdAtMs: Long, finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertJob(jobId, sessionId, kind, title, status, exitCode, outputLocator, createdAtMs, finishedAtMs, updatedAtMs)
    }

    suspend fun upsertJob(
        jobId: String, sessionId: String, kind: String, title: String, status: String,
        exitCode: Long?, outputLocator: String, createdAtMs: Long, finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertJob(jobId, sessionId, kind, title, status, exitCode, outputLocator, createdAtMs, finishedAtMs, updatedAtMs)
    }

    suspend fun getJobById(jobId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_jobs? =
        withContext(Dispatchers.IO) { q.selectJobById(jobId).executeAsOneOrNull() }

    suspend fun listJobs(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_jobs> =
        withContext(Dispatchers.IO) { q.selectJobsBySession(sessionId).executeAsList() }

    suspend fun listRunningJobs(): List<com.R.codecore.datalayer.sqldelight.agent.Agent_jobs> =
        withContext(Dispatchers.IO) { q.selectRunningJobs().executeAsList() }

    suspend fun updateJobStatus(
        jobId: String, status: String, exitCode: Long?, outputLocator: String,
        finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updateJobStatus(status, exitCode, outputLocator, finishedAtMs, updatedAtMs, jobId)
    }

    suspend fun updateJobResult(
        jobId: String, status: String, exitCode: Long?, finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updateJobResult(status, exitCode, finishedAtMs, updatedAtMs, jobId)
    }

    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) { q.deleteJob(jobId) }

    suspend fun insertSchedule(
        scheduleId: String, sessionId: String, rule: String, args: String, status: String,
        enabled: Long, createdAtMs: Long, lastFiredAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertSchedule(scheduleId, sessionId, rule, args, status, enabled, createdAtMs, lastFiredAtMs, updatedAtMs)
    }

    suspend fun upsertSchedule(
        scheduleId: String, sessionId: String, rule: String, args: String, status: String,
        enabled: Long, createdAtMs: Long, lastFiredAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertSchedule(scheduleId, sessionId, rule, args, status, enabled, createdAtMs, lastFiredAtMs, updatedAtMs)
    }

    suspend fun getScheduleById(scheduleId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_schedules? =
        withContext(Dispatchers.IO) { q.selectScheduleById(scheduleId).executeAsOneOrNull() }

    suspend fun listSchedules(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_schedules> =
        withContext(Dispatchers.IO) { q.selectSchedulesBySession(sessionId).executeAsList() }

    suspend fun getPendingSchedules(): List<com.R.codecore.datalayer.sqldelight.agent.Agent_schedules> =
        withContext(Dispatchers.IO) { q.selectPendingSchedules().executeAsList() }

    suspend fun updateScheduleStatus(scheduleId: String, status: String, lastFiredAtMs: Long?, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updateScheduleStatus(status, lastFiredAtMs, updatedAtMs, scheduleId) }

    suspend fun updateScheduleState(
        scheduleId: String, status: String, enabled: Long, lastFiredAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updateScheduleState(status, enabled, lastFiredAtMs, updatedAtMs, scheduleId)
    }

    suspend fun deleteSchedule(scheduleId: String) = withContext(Dispatchers.IO) { q.deleteSchedule(scheduleId) }

    suspend fun insertTrajectory(
        trajectoryId: String, sessionId: String, taskId: String, turnIndex: Long, kind: String,
        toolName: String, argsHash: String, resultSummary: String, isError: Long, durationMs: Long,
        tokensIn: Long, tokensOut: Long, ts: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertTrajectory(trajectoryId, sessionId, taskId, turnIndex, kind, toolName, argsHash, resultSummary, isError, durationMs, tokensIn, tokensOut, ts)
    }

    suspend fun listTrajectories(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_trajectories> =
        withContext(Dispatchers.IO) { q.selectTrajectoriesBySession(sessionId).executeAsList() }

    suspend fun listTrajectoriesByTask(taskId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_trajectories> =
        withContext(Dispatchers.IO) { q.selectTrajectoriesByTask(taskId).executeAsList() }

    suspend fun getLatestTaskId(sessionId: String): String? =
        withContext(Dispatchers.IO) { q.selectLatestTaskIdBySession(sessionId).executeAsOneOrNull() }

    suspend fun getMaxTurnIndex(sessionId: String, taskId: String): Int? =
        withContext(Dispatchers.IO) { q.selectMaxTurnIndexBySessionTask(sessionId, taskId).executeAsOneOrNull()?.MAX?.toInt() }

    suspend fun getTrajectoryAggregate(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.SelectTrajectoryAggregateBySession =
        withContext(Dispatchers.IO) { q.selectTrajectoryAggregateBySession(sessionId).executeAsOne() }

    suspend fun deleteTrajectories(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteTrajectoriesBySession(sessionId) }

    suspend fun insertPlaybookRun(
        playbookRunId: String, sessionId: String, playbookName: String, currentStageIndex: Long,
        stageStatuses: String, status: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertPlaybookRun(playbookRunId, sessionId, playbookName, currentStageIndex, stageStatuses, status, createdAtMs, updatedAtMs)
    }

    suspend fun upsertPlaybookRun(
        playbookRunId: String, sessionId: String, playbookName: String, currentStageIndex: Long,
        stageStatuses: String, status: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertPlaybookRun(playbookRunId, sessionId, playbookName, currentStageIndex, stageStatuses, status, createdAtMs, updatedAtMs)
    }

    suspend fun getPlaybookRunById(runId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs? =
        withContext(Dispatchers.IO) { q.selectPlaybookRunById(runId).executeAsOneOrNull() }

    suspend fun listPlaybookRuns(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs> =
        withContext(Dispatchers.IO) { q.selectPlaybookRunsBySession(sessionId).executeAsList() }

    suspend fun getLatestPlaybookBySession(sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs? =
        withContext(Dispatchers.IO) { q.selectLatestPlaybookBySession(sessionId).executeAsOneOrNull() }

    suspend fun getLatestPlaybookBySessionAndStatus(sessionId: String, status: String): com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs? =
        withContext(Dispatchers.IO) { q.selectLatestPlaybookBySessionAndStatus(sessionId, status).executeAsOneOrNull() }

    suspend fun listRunningPlaybookRuns(): List<com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs> =
        withContext(Dispatchers.IO) { q.selectRunningPlaybookRuns().executeAsList() }

    suspend fun updatePlaybookRun(
        playbookRunId: String, currentStageIndex: Long, stageStatuses: String, status: String, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updatePlaybookRun(currentStageIndex, stageStatuses, status, updatedAtMs, playbookRunId)
    }

    suspend fun deletePlaybookRunsBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deletePlaybookRunsBySession(sessionId) }

    suspend fun upsertSkillState(
        id: String, enabled: Long, version: String, source: String, installedAtMs: Long,
        scopeOverride: String?, agentTypeOverride: String?,
    ) = withContext(Dispatchers.IO) {
        q.insertSkillState(id, enabled, version, source, installedAtMs, scopeOverride, agentTypeOverride)
    }

    suspend fun getSkillState(id: String): com.R.codecore.datalayer.sqldelight.agent.Skill_state? =
        withContext(Dispatchers.IO) { q.selectSkillStateById(id).executeAsOneOrNull() }

    suspend fun listSkillStates(): List<com.R.codecore.datalayer.sqldelight.agent.Skill_state> =
        withContext(Dispatchers.IO) { q.selectAllSkillStates().executeAsList() }

    suspend fun upsertSkillConversationState(skillId: String, sessionId: String, enabled: Long) =
        withContext(Dispatchers.IO) { q.upsertSkillConversationState(skillId, sessionId, enabled) }

    suspend fun getSkillConversationState(skillId: String, sessionId: String): com.R.codecore.datalayer.sqldelight.agent.Skill_conversation_state? =
        withContext(Dispatchers.IO) { q.selectSkillConversationState(skillId, sessionId).executeAsOneOrNull() }

    suspend fun listSkillConversationStates(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Skill_conversation_state> =
        withContext(Dispatchers.IO) { q.selectSkillConversationStatesBySession(sessionId).executeAsList() }

    suspend fun listEnabledSkillIds(sessionId: String): List<String> =
        withContext(Dispatchers.IO) { q.selectSkillConversationStatesEnabledBySession(sessionId).executeAsList() }

    suspend fun listDisabledSkillIds(sessionId: String): List<String> =
        withContext(Dispatchers.IO) { q.selectSkillConversationStatesDisabledBySession(sessionId).executeAsList() }

    fun observeSkillConversationStatesBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Skill_conversation_state>> =
        q.observeSkillConversationStatesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

    suspend fun setSkillStateEnabled(id: String, enabled: Long) {
        withContext(Dispatchers.IO) { q.updateSkillStateEnabled(enabled, id) }
    }

    suspend fun setSkillStateScopeOverride(id: String, scope: String?, agentType: String?) {
        withContext(Dispatchers.IO) { q.updateSkillStateScopeOverride(scope, agentType, id) }
    }

    suspend fun listScopedSkillStates(): List<com.R.codecore.datalayer.sqldelight.agent.Skill_state> =
        withContext(Dispatchers.IO) { q.selectScopedSkillStates().executeAsList() }
            .map { row ->
                com.R.codecore.datalayer.sqldelight.agent.Skill_state(
                    id = row.id,
                    enabled = row.enabled,
                    version = row.version,
                    source = row.source,
                    installed_at_ms = row.installed_at_ms,
                    scope_override = row.scope_override,
                    agent_type_override = row.agent_type_override,
                )
            }

    suspend fun deleteSkillStateById(id: String) {
        withContext(Dispatchers.IO) { q.deleteSkillStateById(id) }
    }

    suspend fun deleteSkillConversationState(skillId: String, sessionId: String) {
        withContext(Dispatchers.IO) { q.deleteSkillConversationState(skillId, sessionId) }
    }

    suspend fun deleteSkillConversationStatesBySkill(skillId: String) {
        withContext(Dispatchers.IO) { q.deleteSkillConversationStatesBySkill(skillId) }
    }

    suspend fun deleteSkillConversationStatesBySession(sessionId: String) {
        withContext(Dispatchers.IO) { q.deleteSkillConversationStatesBySession(sessionId) }
    }

    suspend fun insertWakeItem(
        wakeId: String, sessionId: String, source: String, type: String, content: String,
        status: String, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertWakeItem(wakeId, sessionId, source, type, content, status, createdAtMs)
    }

    override suspend fun upsertWakeItem(
        wakeId: String, sessionId: String, source: String, type: String, content: String,
        status: String, createdAtMs: Long,
    ): Unit = withContext(Dispatchers.IO) {
        q.upsertWakeItem(wakeId, sessionId, source, type, content, status, createdAtMs)
        Unit
    }

    override suspend fun listPendingWakeItems(): List<com.R.codecore.datalayer.sqldelight.agent.Wake_queue> =
        withContext(Dispatchers.IO) { q.selectPendingWakeItems().executeAsList() }

    override suspend fun listWakeBySessionAndStatus(sessionId: String, status: String): List<com.R.codecore.datalayer.sqldelight.agent.Wake_queue> =
        withContext(Dispatchers.IO) { q.selectWakeBySessionAndStatus(status, sessionId).executeAsList() }

    suspend fun markWakeItemConsumed(wakeId: String) =
        withContext(Dispatchers.IO) { q.markWakeItemConsumed(wakeId) }

    override suspend fun markWakeItemsConsumedBatch(ids: List<String>, status: String): Unit =
        withContext(Dispatchers.IO) {
            q.markWakeItemsConsumedBatch(status, ids)
            Unit
        }

    suspend fun insertSentinel(
        id: String, sessionId: String, linkageVersion: Long, chainId: String, chainIndex: Long,
        cardTemplateId: String, triggerSubClass: String, sPlanPayloadCiphertext: String,
        sUserTextCiphertext: String?, sCardPayloadCiphertext: String, userChoice: String,
        swipeVerified: Long, sModifiedPlanCiphertext: String?, expireAtMs: Long, rollbackFlag: Long, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertSentinel(id, sessionId, linkageVersion, chainId, chainIndex, cardTemplateId, triggerSubClass, sPlanPayloadCiphertext, sUserTextCiphertext, sCardPayloadCiphertext, userChoice, swipeVerified, sModifiedPlanCiphertext, expireAtMs, rollbackFlag, createdAtMs)
    }

    suspend fun listSentinels(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Zth_user_confirmed_sentinels> =
        withContext(Dispatchers.IO) { q.selectSentinelsBySession(sessionId).executeAsList() }

    suspend fun listSentinelsByChain(chainId: String): List<com.R.codecore.datalayer.sqldelight.agent.Zth_user_confirmed_sentinels> =
        withContext(Dispatchers.IO) { q.selectSentinelsByChain(chainId).executeAsList() }

    suspend fun listSentinelsUnexpiredBySession(sessionId: String, nowMs: Long): List<com.R.codecore.datalayer.sqldelight.agent.Zth_user_confirmed_sentinels> =
        withContext(Dispatchers.IO) { q.selectSentinelsUnexpiredBySession(sessionId, nowMs).executeAsList() }

    suspend fun listAllSentinels(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_user_confirmed_sentinels> =
        withContext(Dispatchers.IO) { q.selectAllSentinels().executeAsList() }

    suspend fun markAllSentinelsRollbackBySession(sessionId: String) {
        withContext(Dispatchers.IO) { q.updateAllSentinelsRollbackBySession(sessionId) }
    }

    suspend fun deleteSentinelById(id: String) {
        withContext(Dispatchers.IO) { q.deleteSentinelById(id) }
    }

    suspend fun updateSentinelChoice(
        id: String, userChoice: String, swipeVerified: Long, sModifiedPlanCiphertext: String?,
    ) = withContext(Dispatchers.IO) { q.updateSentinelChoice(userChoice, swipeVerified, sModifiedPlanCiphertext, id) }

    suspend fun upsertFuse(
        id: String, scope: String, scopeId: String, state: String, linkageVersion: Long,
        failureCount: Long, openSinceMs: Long, lastProbeAtMs: Long, killSwitch1Triggered: Long,
        killSwitch2SoftDisabled: Long, lastTripSubclass: String?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertFuse(id, scope, scopeId, state, linkageVersion, failureCount, openSinceMs, lastProbeAtMs, killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs)
    }

    suspend fun getFuse(scope: String, scopeId: String): com.R.codecore.datalayer.sqldelight.agent.Zth_hallucination_fuses? =
        withContext(Dispatchers.IO) { q.selectFuse(scope, scopeId).executeAsOneOrNull() }

    suspend fun getFuseVersion(id: String): Long? =
        withContext(Dispatchers.IO) { q.selectFuseVersion(id).executeAsOneOrNull() }

    suspend fun casUpdateFuseState(id: String, expectedVersion: Long, newState: String, nowMs: Long): Long =
        withContext(Dispatchers.IO) {
            q.casUpdateFuseState(newState, nowMs, id, expectedVersion).value
        }

    suspend fun triggerFuseKillSwitch1(id: String, nowMs: Long) =
        withContext(Dispatchers.IO) { q.triggerFuseKillSwitch1(nowMs, id) }

    suspend fun listAllFuses(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_hallucination_fuses> =
        withContext(Dispatchers.IO) { q.selectAllFuses().executeAsList() }

    suspend fun updateFuseState(
        scope: String, scopeId: String, state: String, failureCount: Long, lastProbeAtMs: Long,
        killSwitch1Triggered: Long, killSwitch2SoftDisabled: Long, lastTripSubclass: String?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updateFuseState(state, failureCount, lastProbeAtMs, killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs, scope, scopeId)
    }

    suspend fun insertRejectionAudit(
        id: String, sentinelId: String, rejectionType: String, sReasonCiphertext: String?,
        sRejectedPlanSnapshotCiphertext: String, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertRejectionAudit(id, sentinelId, rejectionType, sReasonCiphertext, sRejectedPlanSnapshotCiphertext, createdAtMs)
    }

    suspend fun listRejectionAudits(sentinelId: String): List<com.R.codecore.datalayer.sqldelight.agent.Zth_sentinel_plan_rejection_audits> =
        withContext(Dispatchers.IO) { q.selectRejectionAuditsBySentinel(sentinelId).executeAsList() }

    suspend fun listAllRejectionAudits(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_sentinel_plan_rejection_audits> =
        withContext(Dispatchers.IO) { q.selectRejectionAuditsAll().executeAsList() }

    suspend fun insertDeleteAudit(
        id: String, sessionId: String, affectedTableName: String, sAffectedKeysCiphertext: String,
        triggerSubClass: String, rollbackApplied: Long, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertDeleteAudit(id, sessionId, affectedTableName, sAffectedKeysCiphertext, triggerSubClass, rollbackApplied, createdAtMs)
    }

    suspend fun listDeleteAuditsPendingRollback(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_hard_constraint_delete_audits> =
        withContext(Dispatchers.IO) { q.selectDeleteAuditsPendingRollback().executeAsList() }

    suspend fun listAllDeleteAudits(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_hard_constraint_delete_audits> =
        withContext(Dispatchers.IO) { q.selectAllDeleteAudits().executeAsList() }

    suspend fun markDeleteAuditRolledBack(id: String) {
        withContext(Dispatchers.IO) { q.updateDeleteAuditRolledBack(id) }
    }

    suspend fun insertRestoreLog(
        id: String, sessionId: String, firstMessageId: String, lastMessageId: String,
        originalRowCount: Long, tokensBefore: Long, tokensAfter: Long,
        sCompactSourceDigestCiphertext: String, expireAtMs: Long, restoredFlag: Long, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertRestoreLog(id, sessionId, firstMessageId, lastMessageId, originalRowCount, tokensBefore, tokensAfter, sCompactSourceDigestCiphertext, expireAtMs, restoredFlag, createdAtMs)
    }

    suspend fun listRestoreLogs(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Zth_l0_soft_compact_restore_logs> =
        withContext(Dispatchers.IO) { q.selectRestoreLogsBySession(sessionId).executeAsList() }

    suspend fun listRestoreLogsExpiredNotRestored(nowMs: Long): List<com.R.codecore.datalayer.sqldelight.agent.Zth_l0_soft_compact_restore_logs> =
        withContext(Dispatchers.IO) { q.selectRestoreLogsExpiredNotRestored(nowMs).executeAsList() }

    suspend fun listAllRestoreLogs(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_l0_soft_compact_restore_logs> =
        withContext(Dispatchers.IO) { q.selectAllRestoreLogs().executeAsList() }

    suspend fun markRestoreLogRestored(id: String) {
        withContext(Dispatchers.IO) { q.updateRestoreLogRestored(id) }
    }

    suspend fun insertTelemetryEvent(
        eventKind: String, eventSubKind: String, severityTier: Long, sessionSha256Prefix: String?,
        latencyMs: Long?, flagA: Long?, flagB: Long?, metricA: Long?, metricB: Long?, createdAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertTelemetryEvent(eventKind, eventSubKind, severityTier, sessionSha256Prefix, latencyMs, flagA, flagB, metricA, metricB, createdAtMs)
    }

    suspend fun listTelemetryByKind(eventKind: String, limit: Long): List<com.R.codecore.datalayer.sqldelight.agent.Zth_telemetry_events> =
        withContext(Dispatchers.IO) { q.selectTelemetryByKind(eventKind, limit).executeAsList() }

    suspend fun countTelemetryByKind(eventKind: String): Long =
        withContext(Dispatchers.IO) { q.countTelemetryByKind(eventKind).executeAsOne() }

    suspend fun listAllTelemetry(): List<com.R.codecore.datalayer.sqldelight.agent.Zth_telemetry_events> =
        withContext(Dispatchers.IO) { q.selectAllTelemetry().executeAsList() }

    suspend fun listTelemetryRange(fromMs: Long, toMs: Long): List<com.R.codecore.datalayer.sqldelight.agent.Zth_telemetry_events> =
        withContext(Dispatchers.IO) { q.selectTelemetryRange(fromMs, toMs).executeAsList() }

    suspend fun countAllTelemetry(): Long =
        withContext(Dispatchers.IO) { q.countAllTelemetry().executeAsOne() }

    suspend fun deleteTelemetryOlderThan(beforeMs: Long) {
        withContext(Dispatchers.IO) { q.deleteTelemetryOlderThan(beforeMs) }
    }

    // ── P2-4 事务封装（对齐 Room withTransaction 语义）────────────────────

    /**
     * 在单事务内执行 [block]（同线程顺序执行，SQLDelight 2.2.1 的 ThreadLocal 事务）。
     * 供 SessionUseCase.deleteSession 等「多表原子写」使用；事务块内只能用 [AgentTx] 暴露的
     * 阻塞方法（不得调用本 Repository 的 suspend 方法，否则 withContext 会跳线程破坏事务绑定）。
     */
    fun runInTx(block: (AgentTx) -> Unit) {
        db.transaction {
            block(AgentTx(q))
        }
    }
}

/** V2 agent 域事务门面：仅供 [AgentRepository.runInTx] 事务块内使用（阻塞执行，同线程）。 */
class AgentTx internal constructor(private val q: com.R.codecore.datalayer.sqldelight.agent.AgentQueries) {
    fun deleteBySession(sessionId: String) { q.deleteMessagesBySession(sessionId) }
    fun deleteTodosBySession(sessionId: String) { q.deleteTodoItemsBySession(sessionId) }
    fun deleteFileEditHunksBySession(sessionId: String) { q.deleteFileEditHunksBySession(sessionId) }
    fun deleteModeSwitchesBySession(sessionId: String) { q.deleteModeSwitchesBySession(sessionId) }
    fun deleteSkillConversationStatesBySession(sessionId: String) { q.deleteSkillConversationStatesBySession(sessionId) }
    fun deleteWakeItemsBySession(sessionId: String) { q.deleteWakeItemsBySession(sessionId) }
    fun deleteGoalsBySession(sessionId: String) { q.deleteGoalsBySession(sessionId) }
    fun deletePlansBySession(sessionId: String) { q.deletePlansBySession(sessionId) }
    fun deleteJobsBySession(sessionId: String) { q.deleteJobsBySession(sessionId) }
    fun deleteSchedulesBySession(sessionId: String) { q.deleteSchedulesBySession(sessionId) }
    fun deleteTrajectories(sessionId: String) { q.deleteTrajectoriesBySession(sessionId) }
    fun deleteSession(id: String) { q.deleteSession(id) }

    // TodoTool replaceTodos 事务：delete + upsert 原子替换
    fun replaceTodos(sessionId: String, todos: List<com.R.codecore.datalayer.sqldelight.agent.Todo_items>) {
        q.deleteTodoItemsBySession(sessionId)
        todos.forEach { t ->
            q.upsertTodoItem(t.id, t.session_id, t.subject, t.description, t.status, t.priority, t.sort_order, t.created_at_ms, t.updated_at_ms)
        }
    }

    // GoalService.activate 事务：CAS 放弃旧 ACTIVE + 插入新目标
    fun activateGoal(
        sessionId: String,
        old: com.R.codecore.datalayer.sqldelight.agent.Agent_goals?,
        goalId: String, text: String, status: String, revision: Long,
        parentGoalId: String, roundSeq: Long, createdAtMs: Long, updatedAtMs: Long,
    ) {
        old?.let { o ->
            q.casUpdateGoalStatusAndText("ABANDONED", o.text, o.revision + 1, updatedAtMs, o.goal_id, o.revision)
        }
        q.upsertGoal(goalId, sessionId, text, status, revision, parentGoalId, roundSeq, createdAtMs, updatedAtMs)
    }

    // PlanService.propose 事务：旧计划置 ABANDONED + 插入新 DRAFT
    fun proposePlan(
        sessionId: String,
        old: com.R.codecore.datalayer.sqldelight.agent.Agent_plans?,
        planId: String, title: String, steps: String, status: String, pendingSelection: String,
        createdAtMs: Long, updatedAtMs: Long,
    ) {
        old?.let { o ->
            q.updatePlanContent("ABANDONED", o.steps, o.pending_selection, updatedAtMs, o.plan_id)
        }
        q.upsertPlan(planId, sessionId, title, steps, status, pendingSelection, createdAtMs, updatedAtMs)
    }
}
