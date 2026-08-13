package com.deep.rcode.feature.credentials.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条 Git 远程仓库凭据：对应某 host 上的一个账号(username + token)。
 *
 * [encryptedToken] 唯一持久化：Android Keystore AES-256-GCM 加密存储。
 * [isDefault] 用于「同 host 多账号」时选定默认注入那条；不同 host 互不影响。
 *
 * RC68 SCHEMA 38：删除明文 token 列，从 `createdAt`/`updatedAt` 增加 Ms 后缀（消除「这是秒还是毫秒」的歧义）。
 */
@Entity(tableName = "git_credentials")
data class GitCredentialEntity(
    @PrimaryKey val id: String,
    /** 远程主机，归一小写（如 github.com）。匹配靠它。 */
    val host: String,
    /** 账号用户名。与 token 拼成 `user:token` 后 base64 注入 Authorization。 */
    val username: String,
    /** Android Keystore AES-256-GCM 加密后的访问令牌（PAT 等）。 */
    @ColumnInfo(defaultValue = "''")
    val encryptedToken: String = "",
    /** 用户自定义别名，为空时 UI 显示 host · username。 */
    @ColumnInfo(defaultValue = "''")
    val label: String = "",
    /** 是否为该 host 的默认凭据（host 内唯一）。切换 default 时由仓储清同 host 其余。 */
    @ColumnInfo(defaultValue = "0")
    val isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = 0
)
