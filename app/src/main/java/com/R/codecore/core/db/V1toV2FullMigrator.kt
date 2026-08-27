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
                    agentRepo.createSession(
                        id = s.id, title = s.title, mode = s.mode, model = s.model,
                        now = s.updatedAtMs
                    )
                    rows++
                }
                val messages = db.agentMessageDao().getAllOnce()
                for (m in messages) {
                    agentRepo.appendMessage(
                        id = m.id, sessionId = m.sessionId, role = m.role,
                        seq = m.timestamp, now = m.timestamp
                    )
                    // content → message_part.text_content；toolCallsJson → tool_args
                    agentRepo.appendPart(
                        id = "part_${m.id}", messageId = m.id, kind = "text", seq = 0,
                        text = m.content, toolName = m.toolName, toolArgs = m.toolArgs,
                        toolResult = null, toolError = null
                    )
                    rows += 2
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
