package com.R.codecore.feature.workspace.domain.repository

import com.R.codecore.core.security.CredentialEncryptor
import com.R.codecore.core.security.HostKeyManager
import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.repository.WorkspaceRepository as V2WorkspaceRepository
import com.R.codecore.feature.workspace.data.local.dao.RemoteConnectionDao
import com.R.codecore.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.R.codecore.feature.workspace.data.local.entity.RemoteMountEntity
import com.R.codecore.feature.workspace.domain.model.RemoteConnection
import com.R.codecore.feature.workspace.domain.model.RemoteMount
import com.R.codecore.feature.workspace.domain.model.RemoteProtocol
import com.R.codecore.feature.workspace.domain.remote.RemoteAuth
import com.R.codecore.feature.workspace.domain.remote.SyncEngine
import com.R.codecore.feature.workspace.domain.remote.ftp.FtpSyncClient
import com.R.codecore.feature.workspace.domain.remote.local.LocalSyncClient
import com.R.codecore.feature.workspace.domain.remote.sftp.SftpSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteRepository @Inject constructor(
    private val dao: RemoteConnectionDao,
    private val v2Workspace: V2WorkspaceRepository,
    private val readMode: DataReadModeHolder,
    private val syncSettings: com.R.codecore.feature.settings.data.repository.SyncSettingsRepository,
    private val hostKeyManager: HostKeyManager,
    private val encryptor: CredentialEncryptor,
) {
    private val activeEngines = ConcurrentHashMap<String, SyncEngine>()
    private val activeEngineIds = MutableStateFlow<Set<String>>(emptySet())

    private suspend fun isV2(): Boolean = readMode.currentMode() == DataReadMode.V2

    private fun isV2Sync(): Boolean = readMode.currentModeSync() == DataReadMode.V2

    private suspend fun getConnectionEntity(id: String): RemoteConnectionEntity? =
        if (isV2()) v2Workspace.getRemoteConnection(id)?.toEntity() else dao.getConnectionById(id)

    private suspend fun getMountEntity(id: String): RemoteMountEntity? =
        if (isV2()) v2Workspace.getRemoteMount(id)?.toEntity() else dao.getMountById(id)

    fun getConnections(): Flow<List<RemoteConnection>> =
        if (isV2Sync()) {
            v2Workspace.observeAllRemoteConnections().map { list ->
                list.map { it.toEntity().toDomainModel() }
            }
        } else {
            dao.getAllConnections().map { list -> list.map { it.toDomainModel() } }
        }

    /** 按 id 一次性读（用于冷启动 SSH 连接组装、Profile 激活时查主机配置）。 */
    suspend fun getConnectionById(id: String): RemoteConnection? {
        val entity = getConnectionEntity(id) ?: return null
        return entity.toDomainModel()
    }

    /** 按 id 一次性读出带原始 RemoteAuth（含密码或私钥+passphrase）的完整配置。
     * 用于 AIEditorApp 冷启动、SettingsViewModel 切远程时组装 SSH 连接——
     * RemoteConnection.domain 只暴露 password（PASSWORD 类型），但 PRIVATE_KEY
     * 类型时还需 passphrase，这个方法直接返回 auth 密封类。
     *
     * authType 兼容：rc60 之前存小写 "password"/"key"，rc60+ 统一写大写
     * "PASSWORD"/"PRIVATE_KEY"；读取时两种都认，老用户升级不丢认证。 */
    suspend fun getAuthById(id: String): RemoteAuth? {
        val entity = getConnectionEntity(id) ?: return null
        return resolveAuth(entity.authType, entity.authData, entity.passphrase)
    }

    fun getMounts(): Flow<List<RemoteMount>> = if (isV2Sync()) {
        combine(
            v2Workspace.observeAllRemoteMounts(),
            activeEngineIds
        ) { list, activeIds ->
            list.map { row ->
                val entity = row.toEntity()
                val connEntity = v2Workspace.getRemoteConnection(entity.connectionId)?.toEntity()
                entity.toDomainModel(connEntity?.toDomainModel()).copy(
                    isActive = activeIds.contains(entity.id)
                )
            }
        }
    } else {
        combine(
            dao.getAllMounts(),
            activeEngineIds
        ) { list, activeIds ->
            list.map { mountEntity ->
                val connEntity = dao.getConnectionById(mountEntity.connectionId)
                mountEntity.toDomainModel(connEntity?.toDomainModel()).copy(
                    isActive = activeIds.contains(mountEntity.id)
                )
            }
        }
    }

    /**
     * 解密凭据：优先当作密文用 CredentialEncryptor 解密，解密失败则回退为"旧版本明文"
     * 原样返回。这样老用户升级到加密版本后，所有已保存的旧明文密码/私钥 passphrase
     * 都不会失效，仍可正常登录，只是下次 save/update 时会被重新加密写入。
     */
    private suspend fun decryptCredential(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            encryptor.decrypt(raw)
        } catch (e: Exception) {
            raw // 兼容旧数据：解密失败当作明文
        }
    }

    /** 密码和私钥 passphrase 存库前一律用 Android Keystore AES-256-GCM 加密。 */
    private suspend fun encryptCredential(plain: String?): String {
        if (plain.isNullOrBlank()) return ""
        return encryptor.encrypt(plain)
    }

    suspend fun addConnection(conn: RemoteConnection, auth: RemoteAuth) {
        val authType = if (auth is RemoteAuth.Password) "PASSWORD" else "PRIVATE_KEY"
        // PASSWORD 类型：authData = 密码（加密后）；PRIVATE_KEY 类型：authData = 私钥文件路径（路径本身不需要加密，明文即可）
        val authData = when (auth) {
            is RemoteAuth.Password -> encryptCredential(auth.password)
            is RemoteAuth.PrivateKey -> auth.privateKeyPath
        }
        // passphrase 仅 PRIVATE_KEY 场景使用，同样加密（它也是凭据的一部分）。
        val passphrase = (auth as? RemoteAuth.PrivateKey)?.passphrase
        val passphraseEnc = if (passphrase.isNullOrBlank()) null else encryptCredential(passphrase)

        if (isV2()) {
            v2Workspace.upsertRemoteConnection(
                id = conn.id,
                name = conn.name,
                protocol = conn.protocol.name,
                host = conn.host,
                port = conn.port.toLong(),
                username = conn.username,
                authType = authType,
                authData = authData,
                passphrase = passphraseEnc,
            )
        } else {
            dao.insertConnection(
                RemoteConnectionEntity(
                    id = conn.id,
                    name = conn.name,
                    protocol = conn.protocol,
                    host = conn.host,
                    port = conn.port,
                    username = conn.username,
                    authType = authType,
                    authData = authData,
                    passphrase = passphraseEnc,
                )
            )
        }
    }

    suspend fun updateConnection(conn: RemoteConnection, auth: RemoteAuth) {
        // Will overwrite existing connection ID
        addConnection(conn, auth)
    }

    suspend fun deleteConnection(id: String) {
        val entity = getConnectionEntity(id)
        if (entity != null) {
            // Associated mounts will cascade delete in DB, but we should disconnect them
            activeEngines.keys.forEach { mountId -> disconnectMount(mountId) }
            if (isV2()) v2Workspace.deleteRemoteConnection(id)
            else dao.deleteConnection(entity)
        }
    }

    suspend fun addMount(mount: RemoteMount) {
        val entity = RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect
        )
        if (isV2()) {
            v2Workspace.upsertRemoteMount(
                id = entity.id,
                connectionId = entity.connectionId,
                remotePath = entity.remotePath,
                localMountPath = entity.localMountPath,
                isActive = if (entity.isActive) 1L else 0L,
                autoConnect = if (entity.autoConnect) 1L else 0L,
            )
        } else {
            dao.insertMount(entity)
        }
    }

    suspend fun updateMount(mount: RemoteMount) {
        val entity = RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect
        )
        if (isV2()) {
            v2Workspace.updateRemoteMount(
                id = entity.id,
                connectionId = entity.connectionId,
                remotePath = entity.remotePath,
                localMountPath = entity.localMountPath,
                isActive = if (entity.isActive) 1L else 0L,
                autoConnect = if (entity.autoConnect) 1L else 0L,
            )
        } else {
            dao.updateMount(entity)
        }
    }

    suspend fun deleteMount(mountId: String) {
        disconnectMount(mountId)
        val entity = getMountEntity(mountId)
        if (entity != null) {
            if (isV2()) v2Workspace.deleteRemoteMount(mountId)
            else dao.deleteMount(entity)
        }
    }

    suspend fun connectMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mountEntity = getMountEntity(mountId) ?: return@withContext Result.failure(Exception("Mount not found"))
            val connEntity = getConnectionEntity(mountEntity.connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))

            val conn = connEntity.toDomainModel()
            val mount = mountEntity.toDomainModel(conn)

            val client = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyManager.createVerifier())
                RemoteProtocol.FTP -> FtpSyncClient()
                RemoteProtocol.LOCAL -> LocalSyncClient()
            }

            val auth = resolveAuth(connEntity.authType, connEntity.authData, connEntity.passphrase)

            client.connect(conn.host, conn.port, conn.username, auth)

            val engine = SyncEngine(
                mount = mount,
                connection = conn,
                syncClient = client,
                ignoredPatternsStr = syncSettings.ignoredPatterns.value,
                useGitIgnore = syncSettings.useGitIgnore.value,
                maxSyncBatchSize = syncSettings.maxSyncBatchSize.value
            )
            // 移除默认的全量下载以免覆盖本地修改，交由用户手动点击同步
            engine.startWatching()     // 增量监听
            if (conn.protocol == RemoteProtocol.LOCAL) {
                engine.uploadWorkspace()
            }

            activeEngines[mountId] = engine
            activeEngineIds.update { it + mountId }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnectMount(mountId: String) {
        activeEngines[mountId]?.shutdown()
        activeEngines.remove(mountId)
        activeEngineIds.update { it - mountId }
    }

    suspend fun forceUploadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("请先连接该挂载点"))
            engine.uploadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forceDownloadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("请先连接该挂载点"))
            engine.downloadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = when (protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyManager.createVerifier())
                RemoteProtocol.FTP -> FtpSyncClient()
                RemoteProtocol.LOCAL -> LocalSyncClient()
            }
            client.connect(host, port, username, auth)
            client.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listRemoteDirectories(connectionId: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connEntity = getConnectionEntity(connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))
            val conn = connEntity.toDomainModel()

            val client = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyManager.createVerifier())
                RemoteProtocol.FTP -> FtpSyncClient()
                RemoteProtocol.LOCAL -> LocalSyncClient()
            }
            val auth = resolveAuth(connEntity.authType, connEntity.authData, connEntity.passphrase)

            client.connect(conn.host, conn.port, conn.username, auth)
            val files = client.listFiles(path).filter { it.isDirectory }.map { it.name }
            client.disconnect()
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 Entity 构造 Domain Model：密码字段已解密（只填到 RemoteConnection.password，
     * PASSWORD 类型为密码，PRIVATE_KEY 类型为空字符串，避免泄漏私钥 passphrase）。
     * 私钥 passphrase 只在真正构造 RemoteAuth 用于连接的地方 decrypt，不在 Domain 层
     * 暴露，减少内存明文驻留点。
     *
     * authType 兼容：新大写 PASSWORD/PRIVATE_KEY 与 旧小写 password/key 都认。
     */
    private suspend fun RemoteConnectionEntity.toDomainModel() = RemoteConnection(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = if (isPasswordAuth(authType)) decryptCredential(authData) else ""
    )

    private fun RemoteMountEntity.toDomainModel(conn: RemoteConnection?) = RemoteMount(
        id = id,
        connectionId = connectionId,
        remotePath = remotePath,
        localMountPath = localMountPath,
        isActive = isActive,
        autoConnect = autoConnect,
        connection = conn
    )

    // ── V2 行 → Room Entity 映射 ────────────────────────────────────────

    private fun com.R.codecore.datalayer.sqldelight.workspace.Remote_connections.toEntity() = RemoteConnectionEntity(
        id = id,
        name = name,
        protocol = runCatching { RemoteProtocol.valueOf(protocol) }.getOrDefault(RemoteProtocol.SFTP),
        host = host,
        port = port.toInt(),
        username = username,
        authType = auth_type,
        authData = auth_data,
        passphrase = passphrase,
    )

    private fun com.R.codecore.datalayer.sqldelight.workspace.Remote_mounts.toEntity() = RemoteMountEntity(
        id = id,
        connectionId = connection_id,
        remotePath = remote_path,
        localMountPath = local_mount_path,
        isActive = is_active == 1L,
        autoConnect = auto_connect == 1L,
    )

    // ── authType 新旧值兼容辅助 ──────────────────────────────────────────

    /**
     * 密码类型判断：rc60 之前写 "password"，rc60+ 统一写 "PASSWORD"。
     * 忽略大小写，两种都认。
     */
    private fun isPasswordAuth(authType: String): Boolean =
        authType.equals("PASSWORD", ignoreCase = true)
            || authType.equals("password", ignoreCase = true)

    /**
     * 私钥类型判断：rc60 之前写 "key"，rc60+ 统一写 "PRIVATE_KEY"。
     * 忽略大小写，两种都认。
     */
    private fun isPrivateKeyAuth(authType: String): Boolean =
        authType.equals("PRIVATE_KEY", ignoreCase = true)
            || authType.equals("key", ignoreCase = true)

    /**
     * 从 Entity 的 authType/authData/passphrase 三字段构造 RemoteAuth。
     * 密码 & passphrase 走 CredentialEncryptor 解密，decrypt 失败回退旧明文。
     * authType 未知时默认当 Password 处理（兜底：至少让用户能看到密码字段再决定）。
     */
    private suspend fun resolveAuth(
        authType: String,
        authData: String,
        passphrase: String?
    ): RemoteAuth = when {
        isPrivateKeyAuth(authType) ->
            RemoteAuth.PrivateKey(authData, decryptCredential(passphrase))
        // PASSWORD 或未知类型：统一当作密码登录，避免老数据 authType 被脏写就完全登不上。
        else -> RemoteAuth.Password(decryptCredential(authData))
    }
}
