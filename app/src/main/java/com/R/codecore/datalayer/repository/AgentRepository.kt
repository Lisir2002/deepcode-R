package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.R.codecore.datalayer.sqldelight.AgentDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * agent 域 Repository（设计 §11.1 / L2）：会话/消息/子块/工具调用/检查点 的访问门面。
 * 业务只依赖本门面，不直接写 SQL。
 *
 * v2-full-takeover P0-1：补 Flow 响应式读，对齐 Room DAO 的 22 个 Flow 查询。
 */
class AgentRepository(private val db: AgentDb) {

    private val q get() = db.agentQueries

    suspend fun createSession(
        id: String, title: String?, mode: String, model: String?, now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertSession(id, title, mode, model, "active", now, now) }

    suspend fun listSessions(): List<com.R.codecore.datalayer.sqldelight.agent.Agent_session> =
        withContext(Dispatchers.IO) { q.selectAllSessions().executeAsList() }

    suspend fun getSession(id: String): com.R.codecore.datalayer.sqldelight.agent.Agent_session? =
        withContext(Dispatchers.IO) { q.selectSessionById(id).executeAsOneOrNull() }

    suspend fun renameSession(id: String, title: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.updateSessionTitle(title, now, id) }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) { q.deleteSession(id) }

    suspend fun appendMessage(id: String, sessionId: String, role: String, seq: Long, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.insertMessage(id, sessionId, role, seq, now) }

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

    suspend fun insertJob(
        jobId: String, sessionId: String, kind: String, title: String, status: String,
        exitCode: Long?, outputLocator: String, createdAtMs: Long, finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertJob(jobId, sessionId, kind, title, status, exitCode, outputLocator, createdAtMs, finishedAtMs, updatedAtMs)
    }

    suspend fun listJobs(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_jobs> =
        withContext(Dispatchers.IO) { q.selectJobsBySession(sessionId).executeAsList() }

    suspend fun updateJobStatus(
        jobId: String, status: String, exitCode: Long?, outputLocator: String,
        finishedAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updateJobStatus(status, exitCode, outputLocator, finishedAtMs, updatedAtMs, jobId)
    }

    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) { q.deleteJob(jobId) }

    suspend fun insertSchedule(
        scheduleId: String, sessionId: String, rule: String, args: String, status: String,
        enabled: Long, createdAtMs: Long, lastFiredAtMs: Long?, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertSchedule(scheduleId, sessionId, rule, args, status, enabled, createdAtMs, lastFiredAtMs, updatedAtMs)
    }

    suspend fun listSchedules(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_schedules> =
        withContext(Dispatchers.IO) { q.selectSchedulesBySession(sessionId).executeAsList() }

    suspend fun updateScheduleStatus(scheduleId: String, status: String, lastFiredAtMs: Long?, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updateScheduleStatus(status, lastFiredAtMs, updatedAtMs, scheduleId) }

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

    suspend fun deleteTrajectories(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteTrajectoriesBySession(sessionId) }

    suspend fun insertPlaybookRun(
        playbookRunId: String, sessionId: String, playbookName: String, currentStageIndex: Long,
        stageStatuses: String, status: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertPlaybookRun(playbookRunId, sessionId, playbookName, currentStageIndex, stageStatuses, status, createdAtMs, updatedAtMs)
    }

    suspend fun listPlaybookRuns(sessionId: String): List<com.R.codecore.datalayer.sqldelight.agent.Agent_playbook_runs> =
        withContext(Dispatchers.IO) { q.selectPlaybookRunsBySession(sessionId).executeAsList() }

    suspend fun updatePlaybookRun(
        playbookRunId: String, currentStageIndex: Long, stageStatuses: String, status: String, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.updatePlaybookRun(currentStageIndex, stageStatuses, status, updatedAtMs, playbookRunId)
    }

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

    suspend fun listPendingWakeItems(): List<com.R.codecore.datalayer.sqldelight.agent.Wake_queue> =
        withContext(Dispatchers.IO) { q.selectPendingWakeItems().executeAsList() }

    suspend fun markWakeItemConsumed(wakeId: String) =
        withContext(Dispatchers.IO) { q.markWakeItemConsumed(wakeId) }

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

    // ── P0-1 Flow 响应式读（对齐 Room DAO 的 22 个 Flow 查询）──

    fun observeAllSessions(): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_session>> =
        q.selectAllSessions().asFlow().mapToList(Dispatchers.IO)

    fun observeSessionById(id: String): Flow<com.R.codecore.datalayer.sqldelight.agent.Agent_session?> =
        q.selectSessionById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun observeMessagesBySession(sessionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.agent.Agent_message>> =
        q.selectMessagesBySession(sessionId).asFlow().mapToList(Dispatchers.IO)

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
}
