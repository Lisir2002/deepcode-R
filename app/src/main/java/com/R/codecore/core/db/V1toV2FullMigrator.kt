package com.R.codecore.core.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.AgentRepository
import com.R.codecore.datalayer.repository.CredentialsRepository
import com.R.codecore.datalayer.repository.SettingsRepository
import com.R.codecore.datalayer.repository.T2iRepository
import com.R.codecore.datalayer.repository.WorkspaceRepository
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
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                agentDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT * FROM chat_sessions", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.upsertSession(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            title = c.getString(c.getColumnIndexOrThrow("title")),
                            mode = c.getString(c.getColumnIndexOrThrow("mode")),
                            model = c.getString(c.getColumnIndexOrThrow("model")),
                            status = "active",
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                            workspacePath = c.getString(c.getColumnIndexOrThrow("workspacePath")),
                            reasoningEffort = c.getString(c.getColumnIndexOrThrow("reasoningEffort")),
                            providerId = c.getString(c.getColumnIndexOrThrow("providerId")),
                            totalInputTokens = c.getLong(c.getColumnIndexOrThrow("totalInputTokens")),
                            totalOutputTokens = c.getLong(c.getColumnIndexOrThrow("totalOutputTokens")),
                            lastInputTokens = c.getLong(c.getColumnIndexOrThrow("lastInputTokens")),
                        )
                        rows++
                    }
                }
                // P2-3 热表回填：agent_message 全列（content/toolCallsJson/分块/压缩/token 等）。
                db.rawQuery("SELECT * FROM agent_messages", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertMessage(
                            com.R.codecore.datalayer.sqldelight.agent.Agent_message(
                                id = c.getString(c.getColumnIndexOrThrow("id")),
                                session_id = c.getString(c.getColumnIndexOrThrow("sessionId")),
                                role = c.getString(c.getColumnIndexOrThrow("role")),
                                seq = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                                created_at = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                                task_id = c.getString(c.getColumnIndexOrThrow("taskId")),
                                content = c.getString(c.getColumnIndexOrThrow("content")),
                                tool_calls_json = c.getString(c.getColumnIndexOrThrow("toolCallsJson")),
                                tool_call_id = c.getString(c.getColumnIndexOrThrow("toolCallId")),
                                tool_name = c.getString(c.getColumnIndexOrThrow("toolName")),
                                tool_args = c.getString(c.getColumnIndexOrThrow("toolArgs")),
                                is_error = if (c.getInt(c.getColumnIndexOrThrow("isError")) != 0) 1L else 0L,
                                reasoning = c.getString(c.getColumnIndexOrThrow("reasoning")),
                                signature = c.getString(c.getColumnIndexOrThrow("signature")),
                                attachments_json = c.getString(c.getColumnIndexOrThrow("attachmentsJson")),
                                is_compacted = if (c.getInt(c.getColumnIndexOrThrow("isCompacted")) != 0) 1L else 0L,
                                is_context_summary = if (c.getInt(c.getColumnIndexOrThrow("isContextSummary")) != 0) 1L else 0L,
                                is_compaction_marker = if (c.getInt(c.getColumnIndexOrThrow("isCompactionMarker")) != 0) 1L else 0L,
                                input_tokens = c.getLong(c.getColumnIndexOrThrow("inputTokens")),
                                output_tokens = c.getLong(c.getColumnIndexOrThrow("outputTokens")),
                                chunk_group_id = c.getString(c.getColumnIndexOrThrow("chunkGroupId")),
                                chunk_index = c.getLong(c.getColumnIndexOrThrow("chunkIndex")),
                            )
                        )
                        rows++
                    }
                }
                // P2-2：todo / checkpoint / Zth 域表全量移植（V2 读源后这些表不能为空）
                db.rawQuery("SELECT * FROM todo_items", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertTodo(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            subject = c.getString(c.getColumnIndexOrThrow("subject")),
                            description = c.getString(c.getColumnIndexOrThrow("description")),
                            status = c.getString(c.getColumnIndexOrThrow("status")),
                            priority = c.getLong(c.getColumnIndexOrThrow("priority")),
                            order = c.getLong(c.getColumnIndexOrThrow("order")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM session_checkpoints", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertCheckpointFull(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            userMessageId = c.getString(c.getColumnIndexOrThrow("userMessageId")),
                            promptSnippet = c.getString(c.getColumnIndexOrThrow("promptSnippet")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM checkpoint_file_snapshots", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertCheckpointFileSnapshot(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            checkpointId = c.getString(c.getColumnIndexOrThrow("checkpointId")),
                            filePath = c.getString(c.getColumnIndexOrThrow("filePath")),
                            snapshotRelativePath = c.getString(c.getColumnIndexOrThrow("snapshotRelativePath")),
                            changeType = c.getString(c.getColumnIndexOrThrow("changeType")),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_hallucination_fuses", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.upsertFuse(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            scope = c.getString(c.getColumnIndexOrThrow("scope")),
                            scopeId = c.getString(c.getColumnIndexOrThrow("scopeId")),
                            state = c.getString(c.getColumnIndexOrThrow("state")),
                            linkageVersion = c.getLong(c.getColumnIndexOrThrow("linkageVersion")),
                            failureCount = c.getLong(c.getColumnIndexOrThrow("failureCount")),
                            openSinceMs = c.getLong(c.getColumnIndexOrThrow("openSinceMs")),
                            lastProbeAtMs = c.getLong(c.getColumnIndexOrThrow("lastProbeAtMs")),
                            killSwitch1Triggered = if (c.getInt(c.getColumnIndexOrThrow("killSwitch1Triggered")) != 0) 1L else 0L,
                            killSwitch2SoftDisabled = if (c.getInt(c.getColumnIndexOrThrow("killSwitch2SoftDisabled")) != 0) 1L else 0L,
                            lastTripSubclass = c.getString(c.getColumnIndexOrThrow("lastTripSubclass")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_user_confirmed_sentinels", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertSentinel(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            linkageVersion = c.getLong(c.getColumnIndexOrThrow("linkageVersion")),
                            chainId = c.getString(c.getColumnIndexOrThrow("chainId")),
                            chainIndex = c.getLong(c.getColumnIndexOrThrow("chainIndex")),
                            cardTemplateId = c.getString(c.getColumnIndexOrThrow("cardTemplateId")),
                            triggerSubClass = c.getString(c.getColumnIndexOrThrow("triggerSubClass")),
                            sPlanPayloadCiphertext = c.getString(c.getColumnIndexOrThrow("s_planPayloadCiphertext")),
                            sUserTextCiphertext = c.getString(c.getColumnIndexOrThrow("s_userTextCiphertext")),
                            sCardPayloadCiphertext = c.getString(c.getColumnIndexOrThrow("s_cardPayloadCiphertext")),
                            userChoice = c.getString(c.getColumnIndexOrThrow("userChoice")),
                            swipeVerified = if (c.getInt(c.getColumnIndexOrThrow("swipeVerified")) != 0) 1L else 0L,
                            sModifiedPlanCiphertext = c.getString(c.getColumnIndexOrThrow("s_modifiedPlanCiphertext")),
                            expireAtMs = c.getLong(c.getColumnIndexOrThrow("expireAtMs")),
                            rollbackFlag = if (c.getInt(c.getColumnIndexOrThrow("rollbackFlag")) != 0) 1L else 0L,
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_sentinel_plan_rejection_audits", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertRejectionAudit(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sentinelId = c.getString(c.getColumnIndexOrThrow("sentinelId")),
                            rejectionType = c.getString(c.getColumnIndexOrThrow("rejectionType")),
                            sReasonCiphertext = c.getString(c.getColumnIndexOrThrow("s_reasonCiphertext")),
                            sRejectedPlanSnapshotCiphertext = c.getString(c.getColumnIndexOrThrow("s_rejectedPlanSnapshotCiphertext")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_hard_constraint_delete_audits", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertDeleteAudit(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            affectedTableName = c.getString(c.getColumnIndexOrThrow("affectedTableName")),
                            sAffectedKeysCiphertext = c.getString(c.getColumnIndexOrThrow("s_affectedKeysCiphertext")),
                            triggerSubClass = c.getString(c.getColumnIndexOrThrow("triggerSubClass")),
                            rollbackApplied = if (c.getInt(c.getColumnIndexOrThrow("rollbackApplied")) != 0) 1L else 0L,
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_l0_soft_compact_restore_logs", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertRestoreLog(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            firstMessageId = c.getString(c.getColumnIndexOrThrow("firstMessageId")),
                            lastMessageId = c.getString(c.getColumnIndexOrThrow("lastMessageId")),
                            originalRowCount = c.getLong(c.getColumnIndexOrThrow("originalRowCount")),
                            tokensBefore = c.getLong(c.getColumnIndexOrThrow("tokensBefore")),
                            tokensAfter = c.getLong(c.getColumnIndexOrThrow("tokensAfter")),
                            sCompactSourceDigestCiphertext = c.getString(c.getColumnIndexOrThrow("s_compactSourceDigestCiphertext")),
                            expireAtMs = c.getLong(c.getColumnIndexOrThrow("expireAtMs")),
                            restoredFlag = if (c.getInt(c.getColumnIndexOrThrow("restoredFlag")) != 0) 1L else 0L,
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM zth_telemetry_events", null).use { c ->
                    while (c.moveToNext()) {
                        agentRepo.insertTelemetryEvent(
                            eventKind = c.getString(c.getColumnIndexOrThrow("eventKind")),
                            eventSubKind = c.getString(c.getColumnIndexOrThrow("eventSubKind")),
                            severityTier = c.getLong(c.getColumnIndexOrThrow("severityTier")),
                            sessionSha256Prefix = c.getString(c.getColumnIndexOrThrow("sessionSha256Prefix")),
                            latencyMs = c.getLong(c.getColumnIndexOrThrow("latencyMs")),
                            flagA = if (c.isNull(c.getColumnIndexOrThrow("flagA"))) null else if (c.getInt(c.getColumnIndexOrThrow("flagA")) != 0) 1L else 0L,
                            flagB = if (c.isNull(c.getColumnIndexOrThrow("flagB"))) null else if (c.getInt(c.getColumnIndexOrThrow("flagB")) != 0) 1L else 0L,
                            metricA = if (c.isNull(c.getColumnIndexOrThrow("metricA"))) null else c.getLong(c.getColumnIndexOrThrow("metricA")),
                            metricB = if (c.isNull(c.getColumnIndexOrThrow("metricB"))) null else c.getLong(c.getColumnIndexOrThrow("metricB")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                        )
                        rows++
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── settings 域 ──
        val settingsDb = context.getDatabasePath("rcodecore_settings_db")
        if (settingsDb.exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                settingsDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT * FROM ai_providers", null).use { c ->
                    while (c.moveToNext()) {
                        // saveProvider 内含 RC68 active 互斥（isActive 时先清全部）；
                        // 按 id 升序移植（与 V2 selectAllProviders 排序键一致），
                        // 使最终 active 行落在原 active 上，幂等重跑结果稳定。
                        settingsRepo.saveProvider(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            type = c.getString(c.getColumnIndexOrThrow("type")),
                            encryptedApiKey = c.getString(c.getColumnIndexOrThrow("encryptedApiKey")),
                            baseUrl = c.getString(c.getColumnIndexOrThrow("baseUrl")),
                            defaultModel = c.getString(c.getColumnIndexOrThrow("defaultModel")),
                            isActive = c.getInt(c.getColumnIndexOrThrow("isActive")) != 0,
                            models = c.getString(c.getColumnIndexOrThrow("models")),
                            isEnabled = c.getInt(c.getColumnIndexOrThrow("isEnabled")) != 0,
                            useFullUrl = c.getInt(c.getColumnIndexOrThrow("useFullUrl")) != 0,
                            useResponseApi = c.getInt(c.getColumnIndexOrThrow("useResponseApi")) != 0,
                        )
                        rows++
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── credentials 域 ──
        val credDb = context.getDatabasePath("rcodecore_credentials_db")
        if (credDb.exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                credDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT * FROM git_credentials", null).use { c ->
                    while (c.moveToNext()) {
                        credentialsRepo.insertGitCredential(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            host = c.getString(c.getColumnIndexOrThrow("host")),
                            username = c.getString(c.getColumnIndexOrThrow("username")),
                            encryptedToken = c.getString(c.getColumnIndexOrThrow("encryptedToken")),
                            label = c.getString(c.getColumnIndexOrThrow("label")),
                            isDefault = if (c.getInt(c.getColumnIndexOrThrow("isDefault")) != 0) 1L else 0L,
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                        )
                        rows++
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── workspace 域 ──
        val wsDb = context.getDatabasePath("rcodecore_workspace_db")
        if (wsDb.exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                wsDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT * FROM remote_connections", null).use { c ->
                    while (c.moveToNext()) {
                        workspaceRepo.insertRemoteConnection(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            protocol = c.getString(c.getColumnIndexOrThrow("protocol")),
                            host = c.getString(c.getColumnIndexOrThrow("host")),
                            port = c.getLong(c.getColumnIndexOrThrow("port")),
                            username = c.getString(c.getColumnIndexOrThrow("username")),
                            authType = c.getString(c.getColumnIndexOrThrow("authType")),
                            authData = c.getString(c.getColumnIndexOrThrow("authData")),
                            passphrase = c.getString(c.getColumnIndexOrThrow("passphrase")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM remote_mounts", null).use { c ->
                    while (c.moveToNext()) {
                        workspaceRepo.insertRemoteMount(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            connectionId = c.getString(c.getColumnIndexOrThrow("connectionId")),
                            remotePath = c.getString(c.getColumnIndexOrThrow("remotePath")),
                            localMountPath = c.getString(c.getColumnIndexOrThrow("localMountPath")),
                            isActive = if (c.getInt(c.getColumnIndexOrThrow("isActive")) != 0) 1L else 0L,
                            autoConnect = if (c.getInt(c.getColumnIndexOrThrow("autoConnect")) != 0) 1L else 0L,
                        )
                        rows++
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── t2i 域 ──
        val t2iDb = context.getDatabasePath("rcodecore_t2i_db")
        if (t2iDb.exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                t2iDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT * FROM t2i_providers", null).use { c ->
                    while (c.moveToNext()) {
                        t2iRepo.insertT2iProvider(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            type = c.getString(c.getColumnIndexOrThrow("type")),
                            baseUrl = c.getString(c.getColumnIndexOrThrow("baseUrl")),
                            encryptedApiKey = c.getString(c.getColumnIndexOrThrow("encryptedApiKey")),
                            endpointMode = c.getString(c.getColumnIndexOrThrow("endpointMode")),
                            isActive = if (c.getInt(c.getColumnIndexOrThrow("isActive")) != 0) 1L else 0L,
                            priority = c.getLong(c.getColumnIndexOrThrow("priority")),
                            isEnabled = if (c.getInt(c.getColumnIndexOrThrow("isEnabled")) != 0) 1L else 0L,
                            extraHeadersJson = c.getString(c.getColumnIndexOrThrow("extraHeadersJson")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                        )
                        rows++
                    }
                }
                db.rawQuery("SELECT * FROM t2i_provider_models", null).use { c ->
                    while (c.moveToNext()) {
                        t2iRepo.insertT2iProviderModel(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            providerId = c.getString(c.getColumnIndexOrThrow("providerId")),
                            modelId = c.getString(c.getColumnIndexOrThrow("modelId")),
                            displayName = c.getString(c.getColumnIndexOrThrow("displayName")),
                            supportsHd = if (c.getInt(c.getColumnIndexOrThrow("supportsHd")) != 0) 1L else 0L,
                            supportsInpaint = if (c.getInt(c.getColumnIndexOrThrow("supportsInpaint")) != 0) 1L else 0L,
                            defaultWidth = c.getLong(c.getColumnIndexOrThrow("defaultWidth")),
                            defaultHeight = c.getLong(c.getColumnIndexOrThrow("defaultHeight")),
                            maxSteps = c.getLong(c.getColumnIndexOrThrow("maxSteps")),
                            defaultSteps = c.getLong(c.getColumnIndexOrThrow("defaultSteps")),
                            costPerImageTokens = c.getLong(c.getColumnIndexOrThrow("costPerImageTokens")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                        )
                        rows++
                    }
                }
                // P2-2：t2i_task 全列对齐 Room T2ITaskEntity（30 列），补迁任务表。
                db.rawQuery("SELECT * FROM t2i_tasks", null).use { c ->
                    while (c.moveToNext()) {
                        t2iRepo.insertTask(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            sessionId = c.getString(c.getColumnIndexOrThrow("sessionId")),
                            messageId = c.getString(c.getColumnIndexOrThrow("messageId")),
                            prompt = c.getString(c.getColumnIndexOrThrow("prompt")),
                            negativePrompt = c.getString(c.getColumnIndexOrThrow("negativePrompt")),
                            width = c.getLong(c.getColumnIndexOrThrow("width")),
                            height = c.getLong(c.getColumnIndexOrThrow("height")),
                            steps = c.getLong(c.getColumnIndexOrThrow("steps")),
                            seed = c.getLong(c.getColumnIndexOrThrow("seed")),
                            hd = if (c.getInt(c.getColumnIndexOrThrow("hd")) != 0) 1L else 0L,
                            providerId = c.getString(c.getColumnIndexOrThrow("providerId")),
                            modelId = c.getString(c.getColumnIndexOrThrow("modelId")),
                            providerRef = c.getString(c.getColumnIndexOrThrow("providerRef")),
                            endpointModeRef = c.getString(c.getColumnIndexOrThrow("endpointModeRef")),
                            status = c.getString(c.getColumnIndexOrThrow("status")),
                            imagePath = c.getString(c.getColumnIndexOrThrow("imagePath")),
                            thumbnailPath = c.getString(c.getColumnIndexOrThrow("thumbnailPath")),
                            remoteTaskId = c.getString(c.getColumnIndexOrThrow("remoteTaskId")),
                            progressPercent = c.getLong(c.getColumnIndexOrThrow("progressPercent")),
                            retryCount = c.getLong(c.getColumnIndexOrThrow("retryCount")),
                            maxRetries = c.getLong(c.getColumnIndexOrThrow("maxRetries")),
                            errorCode = c.getString(c.getColumnIndexOrThrow("errorCode")),
                            errorMessage = c.getString(c.getColumnIndexOrThrow("errorMessage")),
                            permissionDecision = c.getString(c.getColumnIndexOrThrow("permissionDecision")),
                            quotaDeductedTokens = c.getLong(c.getColumnIndexOrThrow("quotaDeductedTokens")),
                            createdAtMs = c.getLong(c.getColumnIndexOrThrow("createdAtMs")),
                            updatedAtMs = c.getLong(c.getColumnIndexOrThrow("updatedAtMs")),
                            completedAtMs = c.getLong(c.getColumnIndexOrThrow("completedAtMs")),
                        )
                        rows++
                    }
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
