package com.R.codecore.core.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.AgentRepository
import com.R.codecore.datalayer.repository.CredentialsRepository
import com.R.codecore.datalayer.repository.SettingsRepository
import com.R.codecore.datalayer.repository.T2iRepository
import com.R.codecore.datalayer.repository.WorkspaceRepository
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.credentials.data.local.database.CredentialsDatabase
import com.R.codecore.feature.settings.data.local.database.SettingsDatabase
import com.R.codecore.feature.t2i.data.local.database.T2IDatabase
import com.R.codecore.feature.workspace.data.local.database.WorkspaceDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据层全面接管（阶段 3）：旧 Room 5 域库 → V2 SQLDelight 6 库 的一次性全量移植。
 *
 * 与 [DbSplitMigrator]（旧单巨库 → 5 个 Room 域库）不同，本迁移器把「当前正在使用的
 * Room 域库」（rcodecore_agent_db_v1 / settings / credentials / workspace / t2i）中的数据
 * 搬到 V2（rcodecore_*_v2.db），使业务切换（阶段 2 后半）后 V2 立即拥有全量历史数据。
 *
 * 语义映射（非机械列拷贝）：
 *   - chat_sessions        → agent_session（title/mode/model/status；workspacePath 等裁剪）
 *   - agent_messages       → agent_message（id/session_id/role/seq）+ agent_message_part
 *                            （content 进 text_content，toolCallsJson 进 tool_args 等）
 *   - 其余 24 表           → 同名 V2 表，camelCase 列 → snake_case（zth 密文列保留原名）
 *
 * 幂等 / 安全（对齐 DbSplitMigrator）：
 *   - 仅当「旧域库文件存在 && V2 标记未置位」时执行；
 *   - 成功后写标记 `.v2_migrated`；失败保留旧库与标记缺失 → 下次启动自动重试；
 *   - 绝不删除旧库（阶段 4 才删）；旧文件改名留底由调用方决定。
 */
@Singleton
class V1toV2FullMigrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentRepo: AgentRepository,
    private val settingsRepo: SettingsRepository,
    private val credentialsRepo: CredentialsRepository,
    private val workspaceRepo: WorkspaceRepository,
    private val t2iRepo: T2iRepository,
) {

    private companion object {
        const val TAG = "V1toV2FullMigrator"
        const val MARKER_FILE_NAME = ".v2_migrated"
    }

    private fun markerFile(): File = File(context.filesDir, MARKER_FILE_NAME)

    /**
     * 幂等入口：仅当「任一旧域库存在 && 标记未置位」时执行全量移植。
     * @return 本次是否实际执行了移植
     */
    private val mutex = Mutex()

    /**
     * 幂等入口：仅当「任一旧域库存在 && 标记未置位」时执行全量移植。
     * @return 本次是否实际执行了移植
     */
    suspend fun migrateIfNeeded(): Boolean = mutex.withLock {
        if (markerFile().exists()) return@withLock false
        val oldDbNames = listOf(
            "rcodecore_agent_db_v1", "rcodecore_settings_db",
            "rcodecore_credentials_db", "rcodecore_workspace_db", "rcodecore_t2i_db",
        )
        val anyExists = oldDbNames.any { context.getDatabasePath(it).exists() }
        if (!anyExists) {
            // 全新安装（无旧数据）：直接置位标记，避免每次启动扫描
            writeMarker()
            return@withLock false
        }
        FileLogger.i(TAG, "检测到旧 Room 域库，开始 V1→V2 全量移植…")
        runCatching { doMigrate() }.onFailure {
            FileLogger.e(TAG, "V1→V2 移植失败，旧库保留、标记未置位，下次启动重试。原因=${it.message}", it)
        }.getOrDefault(false)
    }

    private suspend fun doMigrate(): Boolean {
        val start = System.currentTimeMillis()
        var rows = 0L

        // ── agent 域 ──
        val agentDb = context.getDatabasePath("rcodecore_agent_db_v1")
        if (agentDb.exists()) {
            val db = androidx.room.Room.databaseBuilder(
                context, AgentDatabase::class.java, "rcodecore_agent_db_v1"
            ).build()
            try {
                val sessions = db.chatSessionDao().getAllOnce()
                for (s in sessions) {
                    agentRepo.upsertSession(
                        id = s.id, title = s.title, mode = s.mode, model = s.model, status = "active",
                        createdAtMs = s.createdAtMs, updatedAtMs = s.updatedAtMs,
                        workspacePath = s.workspacePath, reasoningEffort = s.reasoningEffort,
                        providerId = s.providerId, totalInputTokens = s.totalInputTokens.toLong(),
                        totalOutputTokens = s.totalOutputTokens.toLong(), lastInputTokens = s.lastInputTokens.toLong(),
                    )
                    rows++
                }
                // P2-3 热表回填：agent_message 全列（content/toolCallsJson/分块/压缩/token 等）。
                // 旧迁移把 content 拆进了 agent_message_part，现重建热表后直接从 Room 全列回填。
                val messages = db.agentMessageDao().getAllOnce()
                for (m in messages) {
                    agentRepo.insertMessage(
                        com.R.codecore.datalayer.sqldelight.agent.Agent_message(
                            id = m.id,
                            session_id = m.sessionId,
                            role = m.role,
                            seq = m.timestamp,
                            created_at = m.timestamp,
                            task_id = m.taskId,
                            content = m.content,
                            tool_calls_json = m.toolCallsJson,
                            tool_call_id = m.toolCallId,
                            tool_name = m.toolName,
                            tool_args = m.toolArgs,
                            is_error = if (m.isError) 1L else 0L,
                            reasoning = m.reasoning,
                            signature = m.signature,
                            attachments_json = m.attachmentsJson,
                            is_compacted = if (m.isCompacted) 1L else 0L,
                            is_context_summary = if (m.isContextSummary) 1L else 0L,
                            is_compaction_marker = if (m.isCompactionMarker) 1L else 0L,
                            input_tokens = m.inputTokens.toLong(),
                            output_tokens = m.outputTokens.toLong(),
                            chunk_group_id = m.chunkGroupId,
                            chunk_index = m.chunkIndex.toLong(),
                        )
                    )
                    rows++
                }
                // P2-2：todo / checkpoint / Zth 域表全量移植（V2 读源后这些表不能为空）
                for (t in db.todoItemDao().getAllOnce()) {
                    agentRepo.insertTodo(
                        id = t.id, sessionId = t.sessionId, subject = t.subject,
                        description = t.description, status = t.status, priority = t.priority.toLong(),
                        order = t.order.toLong(),
                        createdAtMs = t.createdAtMs, updatedAtMs = t.updatedAtMs,
                    )
                    rows++
                }
                for (c in db.checkpointDao().getAllOnce()) {
                    agentRepo.insertCheckpointFull(
                        id = c.id, sessionId = c.sessionId, userMessageId = c.userMessageId,
                        promptSnippet = c.promptSnippet, createdAtMs = c.createdAtMs,
                    )
                    rows++
                }
                for (s in db.checkpointFileSnapshotDao().getAllOnce()) {
                    agentRepo.insertCheckpointFileSnapshot(
                        id = s.id, checkpointId = s.checkpointId, filePath = s.filePath,
                        snapshotRelativePath = s.snapshotRelativePath, changeType = s.changeType,
                        createdAt = s.createdAt,
                    )
                    rows++
                }
                for (f in db.hallucinationFuseDao().getAllOnce()) {
                    agentRepo.upsertFuse(
                        id = f.id, scope = f.scope, scopeId = f.scopeId, state = f.state,
                        linkageVersion = f.linkageVersion, failureCount = f.failureCount.toLong(),
                        openSinceMs = f.openSinceMs, lastProbeAtMs = f.lastProbeAtMs,
                        killSwitch1Triggered = if (f.killSwitch1Triggered) 1L else 0L,
                        killSwitch2SoftDisabled = if (f.killSwitch2SoftDisabled) 1L else 0L,
                        lastTripSubclass = f.lastTripSubclass, updatedAtMs = f.updatedAtMs,
                    )
                    rows++
                }
                for (se in db.userConfirmedSentinelDao().getAllOnce()) {
                    agentRepo.insertSentinel(
                        id = se.id, sessionId = se.sessionId,
                        linkageVersion = se.linkageVersion, chainId = se.chainId,
                        chainIndex = se.chainIndex.toLong(), cardTemplateId = se.cardTemplateId,
                        triggerSubClass = se.triggerSubClass,
                        sPlanPayloadCiphertext = se.s_planPayloadCiphertext,
                        sUserTextCiphertext = se.s_userTextCiphertext,
                        sCardPayloadCiphertext = se.s_cardPayloadCiphertext,
                        userChoice = se.userChoice,
                        swipeVerified = if (se.swipeVerified) 1L else 0L,
                        sModifiedPlanCiphertext = se.s_modifiedPlanCiphertext,
                        expireAtMs = se.expireAtMs,
                        rollbackFlag = if (se.rollbackFlag) 1L else 0L,
                        createdAtMs = se.createdAtMs,
                    )
                    rows++
                }
                for (ra in db.sentinelPlanRejectionAuditDao().getAllOnce()) {
                    agentRepo.insertRejectionAudit(
                        id = ra.id, sentinelId = ra.sentinelId, rejectionType = ra.rejectionType,
                        sReasonCiphertext = ra.s_reasonCiphertext,
                        sRejectedPlanSnapshotCiphertext = ra.s_rejectedPlanSnapshotCiphertext,
                        createdAtMs = ra.createdAtMs,
                    )
                    rows++
                }
                for (da in db.hardConstraintDeleteAuditDao().getAllOnce()) {
                    agentRepo.insertDeleteAudit(
                        id = da.id, sessionId = da.sessionId,
                        affectedTableName = da.affectedTableName,
                        sAffectedKeysCiphertext = da.s_affectedKeysCiphertext,
                        triggerSubClass = da.triggerSubClass,
                        rollbackApplied = if (da.rollbackApplied) 1L else 0L,
                        createdAtMs = da.createdAtMs,
                    )
                    rows++
                }
                for (rl in db.l0SoftCompactRestoreLogDao().getAllOnce()) {
                    agentRepo.insertRestoreLog(
                        id = rl.id, sessionId = rl.sessionId,
                        firstMessageId = rl.firstMessageId, lastMessageId = rl.lastMessageId,
                        originalRowCount = rl.originalRowCount.toLong(),
                        tokensBefore = rl.tokensBefore.toLong(), tokensAfter = rl.tokensAfter.toLong(),
                        sCompactSourceDigestCiphertext = rl.s_compactSourceDigestCiphertext,
                        expireAtMs = rl.expireAtMs,
                        restoredFlag = if (rl.restoredFlag) 1L else 0L,
                        createdAtMs = rl.createdAtMs,
                    )
                    rows++
                }
                for (te in db.zthTelemetryEventDao().getAllOnce()) {
                    agentRepo.insertTelemetryEvent(
                        eventKind = te.eventKind, eventSubKind = te.eventSubKind,
                        severityTier = te.severityTier.toLong(),
                        sessionSha256Prefix = te.sessionSha256Prefix,
                        latencyMs = te.latencyMs,
                        flagA = te.flagA?.let { if (it) 1L else 0L },
                        flagB = te.flagB?.let { if (it) 1L else 0L },
                        metricA = te.metricA, metricB = te.metricB,
                        createdAtMs = te.createdAtMs,
                    )
                    rows++
                }
            } finally {
                db.close()
            }
        }

        // ── settings 域 ──
        val settingsDb = context.getDatabasePath("rcodecore_settings_db")
        if (settingsDb.exists()) {
            val db = androidx.room.Room.databaseBuilder(
                context, SettingsDatabase::class.java, "rcodecore_settings_db"
            ).build()
            try {
                val providers = db.aiProviderDao().getAllProvidersOnce()
                for (p in providers) {
                    // saveProvider 内含 RC68 active 互斥（isActive 时先清全部）；
                    // 按 id 升序移植（与 V2 selectAllProviders 排序键一致），
                    // 使最终 active 行落在原 active 上，幂等重跑结果稳定。
                    settingsRepo.saveProvider(
                        id = p.id, name = p.name, type = p.type,
                        encryptedApiKey = p.encryptedApiKey, baseUrl = p.baseUrl,
                        defaultModel = p.defaultModel,
                        isActive = p.isActive,
                        models = p.models,
                        isEnabled = p.isEnabled,
                        useFullUrl = p.useFullUrl,
                        useResponseApi = p.useResponseApi,
                    )
                    rows++
                }
            } finally {
                db.close()
            }
        }

        // ── credentials 域 ──
        val credDb = context.getDatabasePath("rcodecore_credentials_db")
        if (credDb.exists()) {
            val db = androidx.room.Room.databaseBuilder(
                context, CredentialsDatabase::class.java, "rcodecore_credentials_db"
            ).build()
            try {
                val creds = db.gitCredentialDao().getAllOnce()
                for (c in creds) {
                    credentialsRepo.insertGitCredential(
                        id = c.id, host = c.host, username = c.username,
                        encryptedToken = c.encryptedToken, label = c.label,
                        isDefault = if (c.isDefault) 1L else 0L,
                        createdAtMs = c.createdAtMs, updatedAtMs = c.updatedAtMs,
                    )
                    rows++
                }
            } finally {
                db.close()
            }
        }

        // ── workspace 域 ──
        val wsDb = context.getDatabasePath("rcodecore_workspace_db")
        if (wsDb.exists()) {
            val db = androidx.room.Room.databaseBuilder(
                context, WorkspaceDatabase::class.java, "rcodecore_workspace_db"
            ).build()
            try {
                val conns = db.remoteConnectionDao().getAllConnectionsOnce()
                for (c in conns) {
                    workspaceRepo.insertRemoteConnection(
                        id = c.id, name = c.name, protocol = c.protocol.name,
                        host = c.host, port = c.port.toLong(), username = c.username,
                        authType = c.authType, authData = c.authData, passphrase = c.passphrase,
                    )
                    rows++
                }
                val mounts = db.remoteConnectionDao().getAllMountsOnce()
                for (m in mounts) {
                    workspaceRepo.insertRemoteMount(
                        id = m.id, connectionId = m.connectionId, remotePath = m.remotePath,
                        localMountPath = m.localMountPath,
                        isActive = if (m.isActive) 1L else 0L,
                        autoConnect = if (m.autoConnect) 1L else 0L,
                    )
                    rows++
                }
            } finally {
                db.close()
            }
        }

        // ── t2i 域 ──
        val t2iDb = context.getDatabasePath("rcodecore_t2i_db")
        if (t2iDb.exists()) {
            val db = androidx.room.Room.databaseBuilder(
                context, T2IDatabase::class.java, "rcodecore_t2i_db"
            ).build()
            try {
                val providers = db.t2iProviderDao().getAllProvidersOnce()
                for (p in providers) {
                    t2iRepo.insertT2iProvider(
                        id = p.id, name = p.name, type = p.type, baseUrl = p.baseUrl,
                        encryptedApiKey = p.encryptedApiKey, endpointMode = p.endpointMode,
                        isActive = if (p.isActive) 1L else 0L,
                        priority = p.priority.toLong(),
                        isEnabled = if (p.isEnabled) 1L else 0L,
                        extraHeadersJson = p.extraHeadersJson,
                        createdAtMs = p.createdAtMs, updatedAtMs = p.updatedAtMs,
                    )
                    rows++
                }
                val models = db.t2iProviderModelDao().getAllModelsOnce()
                for (m in models) {
                    t2iRepo.insertT2iProviderModel(
                        id = m.id, providerId = m.providerId, modelId = m.modelId,
                        displayName = m.displayName,
                        supportsHd = if (m.supportsHd) 1L else 0L,
                        supportsInpaint = if (m.supportsInpaint) 1L else 0L,
                        defaultWidth = m.defaultWidth.toLong(),
                        defaultHeight = m.defaultHeight.toLong(),
                        maxSteps = m.maxSteps.toLong(),
                        defaultSteps = m.defaultSteps.toLong(),
                        costPerImageTokens = m.costPerImageTokens.toLong(),
                        createdAtMs = m.createdAtMs, updatedAtMs = m.updatedAtMs,
                    )
                    rows++
                }
                // P2-2：t2i_task 全列对齐 Room T2ITaskEntity（30 列），补迁任务表。
                for (t in db.t2iTaskDao().getAllTasksOnce()) {
                    t2iRepo.insertTask(
                        id = t.id, sessionId = t.sessionId, messageId = t.messageId,
                        prompt = t.prompt, negativePrompt = t.negativePrompt,
                        width = t.width.toLong(), height = t.height.toLong(), steps = t.steps.toLong(),
                        seed = t.seed.toLong(), hd = if (t.hd) 1L else 0L,
                        providerId = t.providerId, modelId = t.modelId,
                        providerRef = t.providerRef, endpointModeRef = t.endpointModeRef,
                        status = t.status, imagePath = t.imagePath, thumbnailPath = t.thumbnailPath,
                        remoteTaskId = t.remoteTaskId, progressPercent = t.progressPercent.toLong(),
                        retryCount = t.retryCount.toLong(), maxRetries = t.maxRetries.toLong(),
                        errorCode = t.errorCode, errorMessage = t.errorMessage,
                        permissionDecision = t.permissionDecision,
                        quotaDeductedTokens = t.quotaDeductedTokens.toLong(),
                        createdAtMs = t.createdAtMs, updatedAtMs = t.updatedAtMs,
                        completedAtMs = t.completedAtMs,
                    )
                    rows++
                }
            } finally {
                db.close()
            }
        }

        writeMarker()
        FileLogger.i(TAG, "V1→V2 全量移植完成：$rows 行迁入 5 个 V2 域库，耗时 ${System.currentTimeMillis() - start}ms")
        return true
    }

    private fun writeMarker() {
        markerFile().parentFile?.mkdirs()
        markerFile().writeText(System.currentTimeMillis().toString())
        FileLogger.i(TAG, "V2 移植标记已置位：${markerFile().path}")
    }
}
