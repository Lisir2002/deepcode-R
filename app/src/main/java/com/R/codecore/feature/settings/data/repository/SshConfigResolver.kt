package com.R.codecore.feature.settings.data.repository

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.ContainerProfile
import com.R.codecore.feature.agent.domain.container.RemoteConnectionConfig
import com.R.codecore.feature.agent.domain.container.RootfsSource
import com.R.codecore.feature.workspace.domain.repository.RemoteRepository
import kotlinx.coroutines.flow.first

private const val TAG = "SshConfigResolver"

/**
 * 把 `ExecutionModeRepository.remoteConnectionFlow` 里可能「空 host + 带 activeConnectionId」
 * 的 v2 占位配置，解析成真正可用于 `RemoteSshConnection.connect()` 的
 * `RemoteConnectionConfig`（host/port/username/auth/workspacePath 全部填好）。
 *
 * 两种路径：
 *
 * 1. `activeConnectionId != null`（rc60+ 新路径 / 统一数据源 / 推荐）：
 *    - 先按 `activeConnectionId` 从 Room `remote_connections` 表查 host/port/username/auth，
 *      密码 & 私钥 passphrase 经 `RemoteRepository` 自带的 CredentialEncryptor 解密；
 *    - 再结合 `containerSettingsRepository` 读当前 active profile（或 DataStore
 *      `ssh_active_profile_id` 作为兜底），取 `RootfsSource.RemoteSsh.remoteWorkspacePath`
 *      作为工作区路径，优先拿 profile 里的值，若空则取 `this.remoteWorkspacePath`
 *      最后回退 `/home/$username/workspace`。
 *
 * 2. `activeConnectionId == null`（rc59 及以下老用户 fallback 路径）：
 *    - 直接把 `host/port/username/password/workspacePath` 原样组装成 Password 认证配置。
 *    - 这时候 `this.remoteWorkspacePath` 从 DataStore 老字段直接读。
 *    - 对没升级 DataStore 老字段的纯密码用户，这是必须支持的。
 *
 * 两种路径查不到必要信息（比如 connectionId 被删、host 为空）统一返回 null，
 * 调用方负责打日志并进入"首次命令触发重试"逻辑。
 */
suspend fun RemoteConnectionSettings.resolveSshConfigOrNull(
    remoteRepository: RemoteRepository,
    containerSettingsRepository: ContainerSettingsRepository,
): RemoteConnectionConfig? = runCatching {
    val connectionId = activeConnectionId
    if (!connectionId.isNullOrBlank()) {
        // ========== v2 新路径（统一数据源） ==========
        val conn = remoteRepository.getConnectionById(connectionId)
            ?: run { FileLogger.w(TAG, "active connection $connectionId 不存在，跳过启动连接"); return@runCatching null }
        val auth = remoteRepository.getAuthById(connectionId)
            ?: run { FileLogger.w(TAG, "active connection $connectionId 无法解析 auth 类型"); return@runCatching null }

        // workspace path 解析优先级：
        //   a) 从 ContainerProfile（当前激活的）里的 RootfsSource.RemoteSsh.remoteWorkspacePath，
        //      因为用户在 ProfileEditSheet 填的是这个值，profile 本身才是"唯一真源"
        //   b) RemoteConnectionSettings.remoteWorkspacePath（DataStore REMOTE_PATH_KEY），
        //      用来兜底"profile 被删但 ssh_active_connection_id 还残留"的情况
        //   c) /home/${username}/workspace
        val activeProfileId = containerSettingsRepository.activeProfileIdFlow.first()
        val customProfiles = containerSettingsRepository.customProfilesFlow.first()
        val profile: ContainerProfile? =
            (customProfiles.firstOrNull { it.id == activeProfileId }
                ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeProfileId }
                ?: ContainerProfile.BUILTIN_ALPINE_X86.takeIf { it.id == activeProfileId })
        val sshRootfs = (profile?.rootfsSource as? RootfsSource.RemoteSsh)
            ?.takeIf { it.connectionId == connectionId }
        val workspacePath =
            (sshRootfs?.remoteWorkspacePath?.takeIf { it.isNotBlank() }
                ?: remoteWorkspacePath.takeIf { it.isNotBlank() }
                ?: "/home/${conn.username}/workspace")

        return@runCatching RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = auth,
            remoteWorkspacePath = workspacePath,
        )
    } else {
        // ========== v1 legacy fallback ==========
        if (host.isBlank()) {
            FileLogger.w(TAG, "legacy 路径下 remote_host 为空，跳过启动连接")
            return@runCatching null
        }
        return@runCatching RemoteConnectionConfig(
            host = host,
            port = port,
            username = username,
            auth = com.R.codecore.feature.workspace.domain.remote.RemoteAuth.Password(password),
            remoteWorkspacePath = remoteWorkspacePath.ifBlank { "/home/$username/workspace" },
        )
    }
}.onFailure {
    FileLogger.e(TAG, "解析 SSH 启动配置异常", it)
}.getOrNull()
