package com.deep.rcode.feature.backup.data

import android.content.Context
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.database.AgentDatabase
import com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity
import com.deep.rcode.feature.agent.domain.mcp.McpConfigRepository
import com.deep.rcode.feature.agent.domain.mcp.McpManager
import com.deep.rcode.feature.agent.domain.permission.PermissionRulesRepository
import com.deep.rcode.feature.backup.domain.AgentMessageDto
import com.deep.rcode.feature.backup.domain.BackupCrypto
import com.deep.rcode.feature.backup.domain.BackupDecryptionException
import com.deep.rcode.feature.backup.domain.BackupManager
import com.deep.rcode.feature.backup.domain.BackupMetadata
import com.deep.rcode.feature.backup.domain.BackupOptions
import com.deep.rcode.feature.backup.domain.BackupSnapshot
import com.deep.rcode.feature.backup.domain.ChatSessionDto
import com.deep.rcode.feature.backup.domain.GitCredentialDto
import com.deep.rcode.feature.backup.domain.ProviderDto
import com.deep.rcode.feature.backup.domain.RemoteConnectionDto
import com.deep.rcode.feature.backup.domain.RemoteMountDto
import com.deep.rcode.feature.backup.domain.RestoreStats
import com.deep.rcode.feature.backup.domain.TodoItemDto
import com.deep.rcode.feature.backup.domain.toMetadata
import com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao
import com.deep.rcode.feature.credentials.data.local.entity.GitCredentialEntity
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity
import com.deep.rcode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.deep.rcode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.deep.rcode.feature.settings.data.repository.LogSettingsRepository
import com.deep.rcode.feature.settings.data.repository.SyncSettingsRepository
import com.deep.rcode.feature.settings.data.repository.ThemeSettingsRepository
import com.deep.rcode.feature.settings.data.repository.VisionModelSettingsRepository
import com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.deep.rcode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteMountEntity
import com.deep.rcode.feature.workspace.data.repository.WorkspaceRepository
import com.deep.rcode.feature.workspace.domain.model.RemoteProtocol
import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val gitCredentialDao: GitCredentialDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val encryptor: CredentialEncryptor
) : BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * RC61b：统一 DAO 访问安全壳。任何 DAO 调用（Room 首次 query 时会触发 onOpen schema 校验，
     * 校验失败抛 IllegalStateException 直接崩进程）都必须通过此函数。
     * 失败时：记 FileLogger + 按 [failValue] 返回，保证备份/导入流程失败不外溢到 UI 启动链。
     */
    private inline fun <T> safeDao(tag: String, failValue: T, block: () -> T): T {
        return runCatching(block).onFailure {
            FileLogger.e("BackupMgr", "safeDao[$tag] 失败，返回兜底值 failValue=$failValue", it)
        }.getOrDefault(failValue)
    }

    /** safeDao 的 suspend 版本。DAO 本身是 suspend fun，必须走 withContext(Dispatchers.IO) 的调用方已处理。 */
    private suspend inline fun <T> safeDaoSuspend(tag: String, failValue: T, crossinline block: suspend () -> T): T {
        return runCatching { block() }.onFailure {
            FileLogger.e("BackupMgr", "safeDaoSuspend[$tag] 失败，返回兜底值 failValue=$failValue", it)
        }.getOrDefault(failValue)
    }

    private fun currentSchemaVersion(): Int = AgentDatabase.SCHEMA_VERSION

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val temp = createTempFile()
            try {
                writeTarGz(temp, options)
                val pw = password?.takeIf { it.isNotEmpty() }
                FileInputStream(temp).use { input ->
                    if (pw != null) {
                        BackupCrypto.encryptStream(input, output, pw)
                    } else {
                        input.copyTo(output)
                    }
                }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun exportSession(sessionId: String, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val session = safeDaoSuspend("getSessionById", null) { chatSessionDao.getById(sessionId) }
                ?: error("Session not found: $sessionId")
            val temp = createTempFile()
            try {
                FileOutputStream(temp).use { fos ->
                    GzipCompressorOutputStream(fos).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            writeMetadataEntry(tar, BackupMetadata(
                                schemaVersion = currentSchemaVersion(),
                                appVersion = appVersionName(),
                                createdAt = System.currentTimeMillis()
                            ))
                            writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                                writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), session.toDto()))
                            }
                            writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = safeDaoSuspend(
                                        "getMsgPageAfter_$sessionId",
                                        emptyList()
                                    ) { agentMessageDao.getPageBySessionAfter(sessionId, lastTs, lastId, PAGE_SIZE) }
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().timestamp
                                    lastId = batch.last().id
                                }
                            }
                            writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                                var lastCreatedAtMs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = safeDaoSuspend(
                                        "getTodoPageAfter_$sessionId",
                                        emptyList()
                                    ) { todoItemDao.getBySessionPageAfter(sessionId, lastCreatedAtMs, lastId, PAGE_SIZE) }
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                    lastCreatedAtMs = batch.last().createdAtMs
                                    lastId = batch.last().id
                                }
                            }
                        }
                    }
                }
                FileInputStream(temp).use { it.copyTo(output) }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun import(input: InputStream, password: CharArray?): Result<RestoreStats> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (pw != null) {
                    val temp = createTempFile()
                    try {
                        BufferedInputStream(input).use { src ->
                            FileOutputStream(temp).use { dst -> BackupCrypto.decryptStream(src, dst, pw) }
                        }
                        FileInputStream(temp).use { p ->
                            GzipCompressorInputStream(p).use { gz ->
                                TarArchiveInputStream(gz).use { tar -> restoreFromTar(tar) }
                            }
                        }
                    } finally {
                        temp.delete()
                    }
                } else {
                    BufferedInputStream(input).use { p ->
                        GzipCompressorInputStream(p).use { gz ->
                            TarArchiveInputStream(gz).use { tar -> restoreFromTar(tar) }
                        }
                    }
                }
            }.recoverCatching { e ->
                when (e) {
                    is BackupDecryptionException -> throw e
                    is IllegalStateException -> throw e
                    else -> throw IllegalArgumentException(
                        if (pw != null) {
                            "备份文件已损坏，或口令与备份文件不匹配"
                        } else {
                            "不是有效的 R-DeepCode 备份文件；如果这是加密备份，请输入导出口令"
                        },
                        e
                    )
                }
            }
        }
    }

    // ── 导出辅助 ──────────────────────────────────────────────

    private suspend fun writeTarGz(file: File, options: BackupOptions) {
        FileOutputStream(file).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    writeMetadataEntry(tar, buildMetadata(options))
                    if (options.chatHistory) {
                        writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                            var lastUpdatedAtMs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = safeDaoSuspend(
                                    "getSessionPageAfter",
                                    emptyList()
                                ) { chatSessionDao.getPageAfter(lastUpdatedAtMs, lastId, PAGE_SIZE) }
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), it.toDto())) }
                                lastUpdatedAtMs = batch.last().updatedAtMs
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = safeDaoSuspend(
                                    "getMsgPageAfter",
                                    emptyList()
                                ) { agentMessageDao.getPageAfter(lastTs, lastId, PAGE_SIZE) }
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                lastTs = batch.last().timestamp
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                            var lastCreatedAtMs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = safeDaoSuspend(
                                    "getTodoPageAfter",
                                    emptyList()
                                ) { todoItemDao.getPageAfter(lastCreatedAtMs, lastId, PAGE_SIZE) }
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                lastCreatedAtMs = batch.last().createdAtMs
                                lastId = batch.last().id
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildMetadata(options: BackupOptions): BackupMetadata = BackupMetadata(
        schemaVersion = currentSchemaVersion(),
        appVersion = appVersionName(),
        createdAt = System.currentTimeMillis(),
        providers = if (options.providers) safeDaoSuspend("getAllProviders", emptyList()) { aiProviderDao.getAllProvidersOnce().map { it.toDto() } } else emptyList(),
        gitCredentials = if (options.gitCredentials) safeDaoSuspend("getAllGitCred", emptyList()) { gitCredentialDao.getAllOnce().map { it.toDto() } } else emptyList(),
        remoteConnections = if (options.remoteConnections) safeDaoSuspend("getAllRemoteConn", emptyList()) { remoteConnectionDao.getAllConnectionsOnce().map { it.toDto() } } else emptyList(),
        remoteMounts = if (options.remoteConnections) safeDaoSuspend("getAllRemoteMounts", emptyList()) { remoteConnectionDao.getAllMountsOnce().map { it.toDto() } } else emptyList(),
        mcpServers = if (options.mcpServers) mcpConfigRepository.getServers() else emptyList(),
        globalPermissionRules = if (options.permissionRules) permissionRulesRepository.getGlobalRulesOnce() else emptyList(),
        themeMode = if (options.appSettings) themeSettingsRepository.snapshot() else null,
        keepaliveEnabled = if (options.appSettings) keepaliveSettingsRepository.snapshot() else false,
        logLevel = if (options.appSettings) logSettingsRepository.snapshot() else null,
        visionProviderId = if (options.appSettings) visionModelSettingsRepository.getVisionProviderId() else "",
        visionModel = if (options.appSettings) visionModelSettingsRepository.getVisionModel() else "",
        compactionProviderId = if (options.appSettings) compactionModelSettingsRepository.getCompactionProviderId() else "",
        compactionModel = if (options.appSettings) compactionModelSettingsRepository.getCompactionModel() else "",
        syncSettings = if (options.appSettings) syncSettingsRepository.snapshot() else null
    )

    private fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        val content = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8)
        writeTarEntry(tar, FILE_METADATA, content)
    }

    private fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    /**
     * 先将 jsonl 写入一个临时文件，获取确切的 [File.length] 设置 TarArchiveEntry.size，
     * 然后流式拷入 TarArchiveOutputStream，避免在 Header 中 size 设为 0 导致写入越界异常。
     */
    private suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = createTempFile()
        try {
            FileOutputStream(tmp).use { fos ->
                val writer = JsonlWriter(fos)
                block(writer)
                writer.flush()
            }
            val entry = TarArchiveEntry(entryName).apply { size = tmp.length() }
            tar.putArchiveEntry(entry)
            FileInputStream(tmp).use { fis -> fis.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }

    // ── 导入辅助 ──────────────────────────────────────────────

    private suspend fun restoreFromTar(tar: TarArchiveInputStream): RestoreStats {
        var metadata: BackupMetadata? = null
        var stats = RestoreStats()
        var entry = tar.nextEntry
        while (entry != null) {
            when (entry.name) {
                FILE_LEGACY_SNAPSHOT -> {
                    val plain = tar.readBytes()
                    val snapshot = json.decodeFromString(BackupSnapshot.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(snapshot.schemaVersion)
                    return restoreLegacy(snapshot)
                }
                FILE_METADATA -> {
                    val plain = tar.readBytes()
                    metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(metadata.schemaVersion)
                }
                FILE_SESSIONS -> {
                    val currentWorkspacePath = runCatching { workspaceRepository.currentPath() }
                        .onFailure { FileLogger.w("BackupMgr", "读取当前 workspacePath 失败", it) }
                        .getOrDefault("")
                    stats += RestoreStats(chatSessions = restoreJsonl(tar, ChatSessionDto.serializer(), "upsertSessions") { dtos ->
                        safeDaoSuspend("upsertSessions", 0) {
                            chatSessionDao.upsertAll(dtos.map { it.copy(workspacePath = currentWorkspacePath).toEntity() })
                            dtos.size
                        }
                    })
                }
                FILE_MESSAGES -> {
                    stats += RestoreStats(agentMessages = restoreJsonl(tar, AgentMessageDto.serializer(), "insertMessages") { dtos ->
                        safeDaoSuspend("insertMessages", 0) {
                            agentMessageDao.insertAll(dtos.map { it.toEntity() })
                            dtos.size
                        }
                    })
                }
                FILE_TODOS -> {
                    stats += RestoreStats(todoItems = restoreJsonl(tar, TodoItemDto.serializer(), "upsertTodos") { dtos ->
                        safeDaoSuspend("upsertTodos", 0) {
                            todoItemDao.upsertAll(dtos.map { it.toEntity() })
                            dtos.size
                        }
                    })
                }
            }
            entry = tar.nextEntry
        }
        val meta = metadata ?: error("不是有效的 R-DeepCode 备份文件：缺少 metadata.json")
        return stats + restoreMeta(meta)
    }

    /** 逐行解析 jsonl 条目，每 [PAGE_SIZE] 条回调一次批量插入；返回该文件的总条数。 */
    private suspend fun <T> restoreJsonl(
        tar: TarArchiveInputStream,
        serializer: KSerializer<T>,
        tag: String,
        insert: suspend (List<T>) -> Int
    ): Int {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        val batch = ArrayList<T>(PAGE_SIZE)
        var count = 0
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    if (line.size() > 0) {
                        val parsed = runCatching {
                            json.decodeFromString(serializer, line.toString(Charsets.UTF_8))
                        }.onFailure { FileLogger.w("BackupMgr", "restoreJsonl[$tag] 单行解析失败，跳过", it) }
                            .getOrNull()
                        line.reset()
                        if (parsed != null) {
                            batch.add(parsed)
                            if (batch.size >= PAGE_SIZE) {
                                count += runCatching { insert(batch.toList()) }.getOrDefault(0)
                                batch.clear()
                            }
                        }
                    } else {
                        line.reset()
                    }
                } else {
                    line.write(buffer[i].toInt())
                }
            }
        }
        if (line.size() > 0) {
            runCatching { json.decodeFromString(serializer, line.toString(Charsets.UTF_8)) }
                .onFailure { FileLogger.w("BackupMgr", "restoreJsonl[$tag] 尾行解析失败，跳过", it) }
                .getOrNull()?.let { batch.add(it) }
        }
        if (batch.isNotEmpty()) {
            count += runCatching { insert(batch.toList()) }.getOrDefault(0)
        }
        return count
    }

    private fun checkVersion(schemaVersion: Int) {
        if (schemaVersion > currentSchemaVersion()) {
            error("备份的数据库版本 v$schemaVersion 高于本应用 v${currentSchemaVersion()}，请升级应用")
        }
    }

    /** 旧格式（单文件 snapshot.json 完整快照）还原。 */
    private suspend fun restoreLegacy(snapshot: BackupSnapshot): RestoreStats {
        var stats = restoreMeta(snapshot.toMetadata())
        if (snapshot.chatSessions.isNotEmpty()) {
            val currentWorkspacePath = runCatching { workspaceRepository.currentPath() }
                .onFailure { FileLogger.w("BackupMgr", "读取 workspacePath 失败(legacy)", it) }
                .getOrDefault("")
            safeDaoSuspend("legacyUpsertSessions", Unit) {
                chatSessionDao.upsertAll(snapshot.chatSessions.map { it.copy(workspacePath = currentWorkspacePath).toEntity() })
            }
        }
        if (snapshot.agentMessages.isNotEmpty()) {
            safeDaoSuspend("legacyInsertMessages", Unit) {
                agentMessageDao.insertAll(snapshot.agentMessages.map { it.toEntity() })
            }
        }
        if (snapshot.todoItems.isNotEmpty()) {
            safeDaoSuspend("legacyUpsertTodos", Unit) {
                todoItemDao.upsertAll(snapshot.todoItems.map { it.toEntity() })
            }
        }
        return stats + RestoreStats(
            chatSessions = snapshot.chatSessions.size,
            agentMessages = snapshot.agentMessages.size,
            todoItems = snapshot.todoItems.size
        )
    }

    /** 元数据段还原（小表 + 应用设置），新旧格式共用。 */
    private suspend fun restoreMeta(meta: BackupMetadata): RestoreStats {
        if (meta.providers.isNotEmpty()) {
            safeDaoSuspend("insertProviders", Unit) {
                aiProviderDao.insertAllProviders(meta.providers.map { it.toEntity() })
            }
        }
        if (meta.gitCredentials.isNotEmpty()) {
            safeDaoSuspend("upsertGitCreds", Unit) {
                gitCredentialDao.upsertAll(meta.gitCredentials.map { it.toEntity() })
            }
        }
        if (meta.remoteConnections.isNotEmpty()) {
            safeDaoSuspend("insertRemoteConns", Unit) {
                remoteConnectionDao.insertAllConnections(meta.remoteConnections.map { it.toEntity() })
            }
        }
        if (meta.remoteMounts.isNotEmpty()) {
            safeDaoSuspend("insertRemoteMounts", Unit) {
                remoteConnectionDao.insertAllMounts(meta.remoteMounts.map { it.toEntity() })
            }
        }
        if (meta.mcpServers.isNotEmpty()) {
            runCatching {
                mcpConfigRepository.setServers(meta.mcpServers)
                mcpManager.reload()
            }.onFailure { FileLogger.w("BackupMgr", "restoreMeta: MCP 恢复失败", it) }
        }
        if (meta.globalPermissionRules.isNotEmpty()) {
            runCatching { permissionRulesRepository.setGlobalRules(meta.globalPermissionRules) }
                .onFailure { FileLogger.w("BackupMgr", "restoreMeta: permissionRules 恢复失败", it) }
        }
        runCatching { meta.themeMode?.let { themeSettingsRepository.restore(it) } }
            .onFailure { FileLogger.w("BackupMgr", "restoreMeta: theme 恢复失败", it) }
        runCatching { keepaliveSettingsRepository.restore(meta.keepaliveEnabled) }
            .onFailure { FileLogger.w("BackupMgr", "restoreMeta: keepalive 恢复失败", it) }
        runCatching { logSettingsRepository.restore(meta.logLevel) }
            .onFailure { FileLogger.w("BackupMgr", "restoreMeta: logLevel 恢复失败", it) }
        if (meta.visionProviderId.isNotBlank() || meta.visionModel.isNotBlank()) {
            runCatching { visionModelSettingsRepository.setVisionModel(meta.visionProviderId, meta.visionModel) }
                .onFailure { FileLogger.w("BackupMgr", "restoreMeta: visionModel 恢复失败", it) }
        }
        if (meta.compactionProviderId.isNotBlank() || meta.compactionModel.isNotBlank()) {
            runCatching { compactionModelSettingsRepository.setCompactionModel(meta.compactionProviderId, meta.compactionModel) }
                .onFailure { FileLogger.w("BackupMgr", "restoreMeta: compactionModel 恢复失败", it) }
        }
        runCatching { meta.syncSettings?.let { syncSettingsRepository.restore(it) } }
            .onFailure { FileLogger.w("BackupMgr", "restoreMeta: syncSettings 恢复失败", it) }

        return RestoreStats(
            providers = meta.providers.size,
            gitCredentials = meta.gitCredentials.size,
            remoteConnections = meta.remoteConnections.size,
            remoteMounts = meta.remoteMounts.size,
            mcpServers = meta.mcpServers.size,
            globalPermissionRules = meta.globalPermissionRules.size
        )
    }

    private fun createTempFile(): File = File.createTempFile("backup", ".tmp", context.cacheDir)

    // ── Entity ↔ DTO 转换 ──────────────────────────────────────

    private suspend fun AIProviderEntity.toDto(): ProviderDto {
        // RC68 SCHEMA 38 后：Entity 中已无 apiKey/selectedModel 列。
        //  - ProviderDto.apiKey（备份明文）：从 encryptedApiKey 解密
        //  - ProviderDto.selectedModel（冗余语义，为旧备份格式兼容保留字段）：
        //    当前 defaultModel 就等于「当前选中的模型」，所以 selectedModel 与 defaultModel 同值
        val resolvedKey = if (encryptedApiKey.isNotEmpty()) {
            runCatching { encryptor.decrypt(encryptedApiKey) }
                .onFailure { FileLogger.w(TAG, "toDto 解密 encryptedApiKey 失败，导出空串: ${it.message}") }
                .getOrDefault("")
        } else ""
        return ProviderDto(
            id = id,
            name = name,
            type = type,
            apiKey = resolvedKey,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            isActive = isActive,
            models = models,
            selectedModel = defaultModel, // RC68 合并：与 defaultModel 语义一致
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi
        )
    }

    private suspend fun ProviderDto.toEntity(): AIProviderEntity {
        // RC68 SCHEMA 38 后：Entity 只接受 encryptedApiKey，不写 apiKey/selectedModel。
        val encrypted = if (apiKey.isNotEmpty()) {
            runCatching { encryptor.encrypt(apiKey) }
                .onFailure { FileLogger.w(TAG, "toEntity 加密 apiKey 失败：${it.message}（encryptedApiKey 落库为空串）") }
                .getOrDefault("")
        } else ""
        // selectedModel 非空时它就是「用户当前选中的模型」，否则用 defaultModel。
        val mergedModel = selectedModel.ifBlank { defaultModel }
        return AIProviderEntity(
            id = id,
            name = name,
            type = type,
            encryptedApiKey = encrypted,
            baseUrl = baseUrl,
            defaultModel = mergedModel,
            isActive = isActive,
            models = models,
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi
        )
    }

    private suspend fun GitCredentialEntity.toDto(): GitCredentialDto {
        // RC68 SCHEMA 38：Entity.token 已删除，只剩 encryptedToken；createdAt/updatedAt → Ms 后缀。
        val resolvedToken = if (encryptedToken.isNotEmpty()) {
            runCatching { encryptor.decrypt(encryptedToken) }
                .onFailure { FileLogger.w(TAG, "toDto GitCredential 解密 encryptedToken 失败：${it.message}") }
                .getOrDefault("")
        } else ""
        return GitCredentialDto(
            id = id,
            host = host,
            username = username,
            token = resolvedToken,
            label = label,
            isDefault = isDefault,
            createdAt = createdAtMs,
            updatedAt = updatedAtMs
        )
    }

    private suspend fun GitCredentialDto.toEntity(): GitCredentialEntity {
        val encrypted = if (token.isNotEmpty()) {
            runCatching { encryptor.encrypt(token) }
                .onFailure { FileLogger.w(TAG, "toEntity GitCredential 加密 token 失败：${it.message}") }
                .getOrDefault("")
        } else ""
        return GitCredentialEntity(
            id = id,
            host = host,
            username = username,
            encryptedToken = encrypted,
            label = label,
            isDefault = isDefault,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt
        )
    }

    private suspend fun RemoteConnectionEntity.toDto(): RemoteConnectionDto {
        // 备份导出明文：跨设备迁移时备份用户自己保管，恢复时用新设备的 Keystore 重新加密。
        // 规则：
        //  - PASSWORD 类型：authData = 加密后的密码 → 解密后导出明文密码
        //  - PRIVATE_KEY 类型：authData = 私钥文件路径（明文，无需加解密），passphrase = 加密后的 → 解密导出
        // 解密失败兜底：DB 中残留明文（历史数据），直接原样返回。
        val isPwd = authType.equals("PASSWORD", ignoreCase = true)
            || authType.equals("password", ignoreCase = true)
        val resolvedAuthData: String = if (isPwd) {
            try {
                encryptor.decrypt(authData)
            } catch (_: Throwable) {
                authData // 兼容旧数据：解密失败当作明文
            }
        } else {
            authData // PRIVATE_KEY: 路径本身是明文，不解密
        }
        val resolvedPassphrase: String? = if (!passphrase.isNullOrBlank()) {
            try {
                encryptor.decrypt(passphrase)
            } catch (_: Throwable) {
                passphrase // 兼容旧数据：解密失败当作明文
            }
        } else {
            null
        }
        return RemoteConnectionDto(
            id, name, protocol.name, host, port, username, authType, resolvedAuthData, resolvedPassphrase
        )
    }

    private suspend fun RemoteConnectionDto.toEntity(): RemoteConnectionEntity {
        // 恢复入库：用当前设备的 CredentialEncryptor 重新加密敏感字段
        //  - PASSWORD 类型：authData = 明文密码 → 加密存储
        //  - PRIVATE_KEY 类型：authData = 私钥路径（明文保持），passphrase 非空则加密
        val isPwd = authType.equals("PASSWORD", ignoreCase = true)
            || authType.equals("password", ignoreCase = true)
        val encryptedAuthData: String = if (isPwd && authData.isNotEmpty()) {
            encryptor.encrypt(authData)
        } else {
            authData // PRIVATE_KEY 路径无需加密
        }
        val encryptedPassphrase: String? = if (!passphrase.isNullOrBlank()) {
            encryptor.encrypt(passphrase)
        } else {
            null
        }
        return RemoteConnectionEntity(
            id, name, RemoteProtocol.valueOf(protocol), host, port, username, authType, encryptedAuthData, encryptedPassphrase
        )
    }

    private fun RemoteMountEntity.toDto() = RemoteMountDto(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    private fun RemoteMountDto.toEntity() = RemoteMountEntity(id, connectionId, remotePath, localMountPath, isActive, autoConnect)

    private fun ChatSessionEntity.toDto() = ChatSessionDto(
        id = id, title = title,
        createdAt = createdAtMs, updatedAt = updatedAtMs,
        workspacePath = workspacePath, mode = mode, reasoningEffort = reasoningEffort,
        providerId = providerId, model = model
    )
    private fun ChatSessionDto.toEntity() = ChatSessionEntity(
        id = id, title = title,
        createdAtMs = createdAt, updatedAtMs = updatedAt,
        workspacePath = workspacePath, mode = mode, reasoningEffort = reasoningEffort,
        providerId = providerId, model = model
    )

    private fun AgentMessageEntity.toDto() = AgentMessageDto(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker
    )

    private fun AgentMessageDto.toEntity() = AgentMessageEntity(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker
    )

    private fun TodoItemEntity.toDto() = TodoItemDto(
        id = id, sessionId = sessionId, subject = subject, description = description,
        status = status, priority = priority, order = order,
        createdAt = createdAtMs, updatedAt = updatedAtMs
    )
    private fun TodoItemDto.toEntity() = TodoItemEntity(
        id = id, sessionId = sessionId, subject = subject, description = description,
        status = status, priority = priority, order = order,
        createdAtMs = createdAt, updatedAtMs = updatedAt
    )

    private companion object {
        const val PAGE_SIZE = 500
        const val FILE_METADATA = "metadata.json"
        const val FILE_SESSIONS = "chatSessions.jsonl"
        const val FILE_MESSAGES = "messages.jsonl"
        const val FILE_TODOS = "todoItems.jsonl"
        const val FILE_LEGACY_SNAPSHOT = "snapshot.json"
    }
}

/** 带内部缓冲的 jsonl 写入器：行缓冲满 64KB 时刷入 tar 流，避免逐行写系统调用。 */
private class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}
