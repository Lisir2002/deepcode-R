package com.core.deepcode.feature.settings.data.repository

import com.core.deepcode.core.security.CredentialEncryptionContract
import com.core.deepcode.core.security.CredentialEncryptor
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.store.KVStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

/** 执行环境模式。 */
enum class ExecutionMode {
    LOCAL_PROOT, REMOTE_SSH
}

/** 远程 SSH 连接配置的内存形式（密码已解密）。 */
data class RemoteConnectionSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val remoteWorkspacePath: String,
    val activeConnectionId: String? = null,
)

/**
 * 持久化当前执行模式（本地 PRoot / 远程 SSH）与远程连接配置。
 *
 * v2 统一数据源：只存
 *   ssh_active_connection_id   — credentials 库 cred_connection.id
 *   ssh_active_profile_id      — 可选，记录从哪个 ContainerProfile 切来
 *   remote_workspace_path      — 远程工作区路径
 *
 * v1 legacy 字段：保留但仅 fallback（兼容老用户升级）。
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    private val kv: KVStore,
    private val encryptor: CredentialEncryptor,
) {
    private companion object {
        const val NS = "settings"
        const val MODE_KEY = "execution_mode"
        const val SSH_ACTIVE_CONN_ID_KEY = "ssh_active_connection_id"
        const val SSH_ACTIVE_PROFILE_ID_KEY = "ssh_active_profile_id"
        const val HOST_KEY = "remote_host"
        const val PORT_KEY = "remote_port"
        const val USERNAME_KEY = "remote_username"
        const val PASSWORD_KEY = "remote_password"
        const val REMOTE_PATH_KEY = "remote_workspace_path"
    }

    val executionModeFlow: Flow<ExecutionMode> = kv.observeString(NS, MODE_KEY).map { stored ->
        stored?.let { runCatching { ExecutionMode.valueOf(it) }.getOrNull() } ?: ExecutionMode.LOCAL_PROOT
    }

    val remoteConnectionFlow: Flow<RemoteConnectionSettings?> =
        kv.observeString(NS, SSH_ACTIVE_CONN_ID_KEY).mapLatest { activeConnId ->
            // v2 分支：已存 active connection id
            if (!activeConnId.isNullOrBlank()) {
                return@mapLatest RemoteConnectionSettings(
                    host = "", port = 22, username = "", password = "",
                    remoteWorkspacePath = kv.getString(NS, REMOTE_PATH_KEY) ?: "/workspace",
                    activeConnectionId = activeConnId,
                )
            }
            // v1 fallback
            val host = kv.getString(NS, HOST_KEY)?.takeIf { it.isNotBlank() } ?: return@mapLatest null
            val workspacePath = kv.getString(NS, REMOTE_PATH_KEY)
            RemoteConnectionSettings(
                host = host,
                port = kv.getString(NS, PORT_KEY)?.toIntOrNull() ?: 22,
                username = kv.getString(NS, USERNAME_KEY) ?: "",
                password = decryptCredentialCompat(kv.getString(NS, PASSWORD_KEY)),
                remoteWorkspacePath = workspacePath
                    ?: "/home/${kv.getString(NS, USERNAME_KEY) ?: ""}/workspace",
                activeConnectionId = null,
            )
        }

    private fun decryptCredentialCompat(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        if (raw.startsWith(CredentialEncryptionContract.SCHEME_V2)) {
            FileLogger.w(
                "ExecutionModeRepo",
                "legacy password 字段意外含 V2 前缀，降级返回空字符串"
            )
            return ""
        }
        return raw
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        kv.putString(NS, MODE_KEY, mode.name)
    }

    suspend fun setRemoteConnection(
        settings: RemoteConnectionSettings,
        activeProfileId: String? = null,
    ) {
        kv.putString(NS, REMOTE_PATH_KEY, settings.remoteWorkspacePath)
        val activeConnId = settings.activeConnectionId
        if (!activeConnId.isNullOrBlank()) {
            // v2 路径：只存 id + profile id + workspace path；清掉 legacy credential 字段
            kv.putString(NS, SSH_ACTIVE_CONN_ID_KEY, activeConnId)
            if (activeProfileId != null) kv.putString(NS, SSH_ACTIVE_PROFILE_ID_KEY, activeProfileId)
            kv.delete(NS, HOST_KEY); kv.delete(NS, PORT_KEY)
            kv.delete(NS, USERNAME_KEY); kv.delete(NS, PASSWORD_KEY)
        } else {
            // legacy v1 路径
            kv.delete(NS, SSH_ACTIVE_CONN_ID_KEY); kv.delete(NS, SSH_ACTIVE_PROFILE_ID_KEY)
            kv.putString(NS, HOST_KEY, settings.host)
            kv.putString(NS, PORT_KEY, settings.port.toString())
            kv.putString(NS, USERNAME_KEY, settings.username)
            kv.putString(NS, PASSWORD_KEY, if (settings.password.isBlank()) "" else encryptor.encrypt(settings.password))
        }
    }
}
