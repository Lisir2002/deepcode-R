package com.aicode.feature.credentials.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条 Git 远程仓库凭据：对应某 host 上的一个账号(username + token)。
 *
 * [token] 为明文 Room，已由 [encryptedToken] 替代，保留用于迁移过渡。
 * [encryptedToken] 使用 Android Keystore AES-256-GCM 加密存储。
 * [isDefault] 用于「同 host 多账号」时选定默认注入那条；不同 host 互不影响。
 */
@Entity(tableName = "git_credentials")
data class GitCredentialEntity(
    @PrimaryKey val id: String,
    /** 远程主机，归一小写（如 github.com）。匹配靠它。 */
    val host: String,
    /** 账号用户名。与 token 拼成 `user:token` 后 base64 注入 Authorization。 */
    val username: String,
    /** 访问令牌（PAT 等）。明文 Room，已由 [encryptedToken] 替代。 */
    val token: String,
    /** Android Keystore 加密后的访问令牌（AES-256-GCM）。优先使用此字段。 */
    val encryptedToken: String = "",
    /** 用户自定义别名，为空时 UI 显示 host · username。 */
    val label: String = "",
    /** 是否为该 host 的默认凭据（host 内唯一）。切换 default 时由仓储清同 host 其余。 */
    val isDefault: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
