package com.deep.rcode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deep.rcode.core.security.CredentialEncryptionContract
import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.executionModeDataStore by preferencesDataStore(name = "execution_mode_prefs")

/** 执行环境模式。 */
enum class ExecutionMode {
    /** 本地 PRoot 容器（原有行为）。 */
    LOCAL_PROOT,
    /** 远程 SSH 服务器。 */
    REMOTE_SSH
}

/** 远程 SSH 连接配置的内存形式（密码已解密）。 */
data class RemoteConnectionSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val remoteWorkspacePath: String,
    /**
     * 若此值非空，说明这份配置来自「统一数据源」模式：
     * - 所有 host/port/username/password 凭据都应由 Room `remote_connections` 表
     *   （通过 `CredentialEncryptor` 加密）提供，DataStore 里的 HOST/PORT/USERNAME/PASSWORD
     *   字段忽略（它们仅作为「旧版本迁移 fallback」保留）。
     * - `remoteWorkspacePath` 仍从 ContainerProfile `RootfsSource.RemoteSsh.remoteWorkspacePath`
     *   读，保存在 DataStore 的 REMOTE_PATH_KEY 字段中。
     * - 为保持构造器兼容性，仍保留 host/port/username/password 字段，
     *   调用方可按需从 Room 自行填充（见 ExecutionModeRepositoryKt）。
     */
    val activeConnectionId: String? = null,
)

/**
 * 持久化当前执行模式（本地 PRoot / 远程 SSH）与远程连接配置。
 *
 * 数据结构演进：
 *
 * ### v1（历史遗留）：
 * ```
 *   remote_host / remote_port / remote_username / remote_password(明文) / remote_workspace_path
 * ```
 *   全部存于 DataStore，与 Room `remote_connections` 表并行两套来源，容易漂移不一致。
 *   密码明文存储，安全事故风险高。
 *
 * ### v2（rc60 之后）：
 *   统一数据源：只存
 *   ```
 *     ssh_active_connection_id   — Room remote_connections.id，指向当前激活的主机
 *     ssh_active_profile_id      — 可选，记录从哪个 ContainerProfile 切来（用于
 *                                  remote_workspace_path 溯源）
 *     remote_workspace_path      — 远程工作区路径（保留，与 ContainerProfile 同步写）
 *   ```
 *   真正的 host/port/username/密码/passphrase 全部从 Room `remote_connections` 表读，
 *   且 authData/passphrase 经 [CredentialEncryptor] AES-256-GCM 加密，不再存 DataStore。
 *
 *   为兼容老用户升级：
 *   - [remoteConnectionFlow] 若读到 `ssh_active_connection_id` 则走「v2 新路径」
 *     （返回 `activeConnectionId != null` 的 [RemoteConnectionSettings]，调用方负责
 *     再去 Room 表按 id 取凭据并填充字段）。
 *   - 否则回退读 legacy HOST/PORT/USERNAME/PASSWORD 字段，`PASSWORD_KEY` 先尝
 *     试 CredentialEncryptor 解密，解密失败就当作旧明文用——保证升级不丢凭据。
 *   - `PASSWORD_KEY` **写入**时一律加密（rc60+ 新设置不再有明文在 DataStore）。
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val encryptor: CredentialEncryptor,
) {
    private companion object {
        val MODE_KEY = stringPreferencesKey("execution_mode")
        // v2 统一数据源：只存这两个 SSH 专用键
        val SSH_ACTIVE_CONN_ID_KEY = stringPreferencesKey("ssh_active_connection_id")
        val SSH_ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("ssh_active_profile_id")
        // v1 legacy 字段：保留但仅 fallback
        val HOST_KEY = stringPreferencesKey("remote_host")
        val PORT_KEY = stringPreferencesKey("remote_port")
        val USERNAME_KEY = stringPreferencesKey("remote_username")
        val PASSWORD_KEY = stringPreferencesKey("remote_password")
        val REMOTE_PATH_KEY = stringPreferencesKey("remote_workspace_path")
    }

    /** 当前执行模式；无值时默认本地 PRoot。 */
    val executionModeFlow: Flow<ExecutionMode> = context.executionModeDataStore.data.map { prefs ->
        prefs[MODE_KEY]?.let {
            runCatching { ExecutionMode.valueOf(it) }.getOrNull()
        } ?: ExecutionMode.LOCAL_PROOT
    }

    /**
     * 远程 SSH 连接配置。
     *
     * 注意返回值的 [RemoteConnectionSettings.activeConnectionId]：
     * - 非 null → v2 模式，host/port/username/password 可能为空占位，
     *   调用方（AIEditorApp / SettingsViewModel）需再按 id 到 Room 表查真实凭据。
     * - null → v1 fallback 模式，host/port/username/password 已从 legacy 字段填好。
     */
    val remoteConnectionFlow: Flow<RemoteConnectionSettings?> =
        context.executionModeDataStore.data.mapLatest { prefs ->
            val activeConnId = prefs[SSH_ACTIVE_CONN_ID_KEY]
            val workspacePath = prefs[REMOTE_PATH_KEY]
            // v2 分支：已存 active connection id，返回占位配置（host 等由调用方从 Room 填）
            if (!activeConnId.isNullOrBlank()) {
                return@mapLatest RemoteConnectionSettings(
                    host = "",
                    port = 22,
                    username = "",
                    password = "",
                    remoteWorkspacePath = workspacePath ?: "/workspace",
                    activeConnectionId = activeConnId,
                )
            }
            // v1 fallback：读 legacy HOST/PORT/USERNAME/PASSWORD，兼容老用户
            val host = prefs[HOST_KEY]?.takeIf { it.isNotBlank() } ?: return@mapLatest null
            RemoteConnectionSettings(
                host = host,
                port = prefs[PORT_KEY]?.toIntOrNull() ?: 22,
                username = prefs[USERNAME_KEY] ?: "",
                password = decryptCredentialCompat(prefs[PASSWORD_KEY]),
                remoteWorkspacePath = workspacePath
                    ?: "/home/${prefs[USERNAME_KEY] ?: ""}/workspace",
                activeConnectionId = null,
            )
        }

    /**
     * 解密 DataStore 里的 legacy password 字段：
     *  - rc60 之后写入的：经 CredentialEncryptor 加密，格式"V2:xxx"
     *  - rc60 之前的：字段是明文，直接用
     *  - 空/空白：直接 ""
     *
     * ============= RC61b hotfix3（RC60 后闪退根因级修复 #2） =============
     * 旧实现：`runCatching { encryptor.decrypt(raw) }.getOrElse { raw }`
     * 问题：encryptor.decrypt → ensureInitialized → stateDao.getSingleOrNull() → **强制 DB open**，
     * 而 remoteConnectionFlow 是冷启动 Flow，会在 Hilt 构造链「CredentialRequestBridge →
     * LinuxContainerEngine → 任意 ExecutionModeHolder/Repository 订阅点」时被首个订阅
     * 同步触发；和主线程 Hilt.provideAgentDatabase 同时抢同一 RoomDB 实例 + Keystore
     * MasterKey 生成，形成启动期两线程争用伪死锁 → ANR → 系统 1-2s 杀进程无弹窗。
     *
     * 修复：v1 legacy 路径的 password 字段本身就是明文（RC60 之前没加密），真正的 V2
     * 密码根本**不会保存在 DataStore**（RC61+ 已改成 Room 单源 + DataStore 只存
     * ssh_active_connection_id 指针），所以在这里解 V2 实际上是死路径。
     * 我们改成「只看 raw 前缀」来区分，不调用任何需要 DB/Keystore 的方法：
     *   - V2: 前缀 → 返回 ""（提示上层去 Room 单源读；v2 路径本来就不该到这里）
     *   - 否则 → 直接返回 raw（RC60 前明文 legacy 语义）
     * 避免在冷启动 Flow 上触发任何加密子系统初始化。
     */
    private fun decryptCredentialCompat(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // V2: 前缀意味着这是 RC61+ 的 DEK 密文——但 v2 路径本就不会把密码写到 legacy
        // DataStore（v2 只写 Room + activeConnectionId）。若真遇到：兜底空串，上层
        // resolveSshConfigOrNull 会走 Room 单源重新查（v2 预期路径）。
        if (raw.startsWith(CredentialEncryptionContract.SCHEME_V2)) {
            FileLogger.w(
                "ExecutionModeRepo",
                "legacy password 字段意外含 V2 前缀（本应只出现在 Room 单源），" +
                        "降级返回空字符串，由上层 resolveSshConfigOrNull 走 Room 重新按 activeConnectionId 查"
            )
            return ""
        }
        // RC60 之前 legacy：明文直接返回（符合该 fallback 原本语义）
        return raw
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        context.executionModeDataStore.edit { it[MODE_KEY] = mode.name }
    }

    /**
     * 写远程配置（v2 统一数据源入口）。
     *
     * 用法：
     *  - `activeConnectionId != null`：走 Room 单源，不写 legacy HOST/PORT/USERNAME/PASSWORD。
     *  - `activeConnectionId == null`：仍走 legacy v1 DataStore 存全字段（用于历史兼容与测试）。
     */
    suspend fun setRemoteConnection(
        settings: RemoteConnectionSettings,
        activeProfileId: String? = null,
    ) {
        context.executionModeDataStore.edit { prefs ->
            prefs[REMOTE_PATH_KEY] = settings.remoteWorkspacePath
            val activeConnId = settings.activeConnectionId
            if (!activeConnId.isNullOrBlank()) {
                // v2 路径：只写 id + profile id + workspace path；清掉 legacy credential 字段
                // （不再重复保存，避免与 Room 漂移）
                prefs[SSH_ACTIVE_CONN_ID_KEY] = activeConnId
                if (activeProfileId != null) prefs[SSH_ACTIVE_PROFILE_ID_KEY] = activeProfileId
                prefs.remove(HOST_KEY)
                prefs.remove(PORT_KEY)
                prefs.remove(USERNAME_KEY)
                prefs.remove(PASSWORD_KEY)
            } else {
                // legacy v1 路径：照旧写全到 DataStore，密码一律加密（rc60+ 不再明文）
                prefs.remove(SSH_ACTIVE_CONN_ID_KEY)
                prefs.remove(SSH_ACTIVE_PROFILE_ID_KEY)
                prefs[HOST_KEY] = settings.host
                prefs[PORT_KEY] = settings.port.toString()
                prefs[USERNAME_KEY] = settings.username
                prefs[PASSWORD_KEY] = if (settings.password.isBlank()) "" else encryptor.encrypt(settings.password)
            }
        }
    }
}
