package com.core.deepcode.feature.credentials.data.repository

import com.core.deepcode.core.security.CredentialEncryptor
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.repository.CredentialsRepository as V2CredentialsRepository
import com.core.deepcode.feature.credentials.domain.model.GitCredential
import com.core.deepcode.feature.credentials.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git 凭据仓库 V2 实现（v2-full-takeover P2-1 批 1：credentials 域切 V2 读源）。
 *
 * 语义与 Room 版对齐，并修正两处旧实现缺陷：
 *  - 加密失败必须中止保存（RC71），绝不写空串覆盖已有密文（Room 版 toEntity 静默降级）；
 *  - 写操作事务化：isDefault 置位前先 clearDefaultForHost，避免 host 内出现多条默认。
 * 读走 V2 observeAllGitCredentials()（Flow 响应式，对齐 Room DAO 的 1 个 Flow 查询）。
 */
@Singleton
class CredentialRepositoryV2Impl @Inject constructor(
    private val v2: V2CredentialsRepository,
    private val encryptor: CredentialEncryptor,
) : CredentialRepository {

    private companion object {
        const val TAG = "CredentialRepoV2"
    }

    override fun getAll(): Flow<List<GitCredential>> {
        return v2.observeAllGitCredentials().map { list ->
            buildList { for (row in list) add(row.toDomain()) }
        }
    }

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return v2.findGitCredentialByHost(normalized)?.toDomain()
    }

    override suspend fun save(credential: GitCredential) {
        FileLogger.i(TAG, "保存凭据 id=${credential.id} host=${credential.host} user=${credential.username} default=${credential.isDefault}")
        val now = System.currentTimeMillis()
        val host = credential.host.trim().lowercase()
        // 凭据按 id 走 REPLACE：若该 id 已存在，先取旧密文，用户未改 token 时保留，避免空串覆盖。
        val existingEncrypted = runCatching {
            v2.getGitCredential(credential.id)?.encrypted_token ?: ""
        }.getOrDefault("")
        val encrypted = encryptTokenOrThrow(credential.token, existingEncrypted)
        if (credential.isDefault) v2.clearDefaultForHost(host)
        v2.upsertGitCredential(
            id = credential.id,
            host = host,
            username = credential.username,
            encryptedToken = encrypted,
            label = credential.label,
            isDefault = if (credential.isDefault) 1L else 0L,
            createdAtMs = if (credential.createdAt == 0L) now else credential.createdAt,
            updatedAtMs = now,
        )
    }

    override suspend fun delete(id: String) {
        FileLogger.i(TAG, "删除凭据 id=$id")
        v2.deleteGitCredential(id)
    }

    override suspend fun setDefault(id: String, isDefault: Boolean) {
        FileLogger.i(TAG, "切换默认凭据 id=$id default=$isDefault")
        val row = v2.getGitCredential(id) ?: return
        if (isDefault) v2.clearDefaultForHost(row.host)
        v2.setGitCredentialDefault(id, isDefault)
    }

    /**
     * RC71：token 非空 → 加密失败必须抛异常中止保存（绝不落空串）；
     * token 为空但已有密文 → 保留旧密文；都无 → 空串。
     */
    private suspend fun encryptTokenOrThrow(token: String, existingEncrypted: String): String = when {
        token.isNotBlank() -> {
            runCatching { encryptor.encrypt(token) }
                .onFailure {
                    FileLogger.e(TAG, "加密 token 失败，中止保存（避免覆盖已有密文）: ${it.message}", it)
                    throw IllegalStateException("Token 加密失败，保存已中止: ${it.message}", it)
                }
                .getOrThrow()
        }
        existingEncrypted.isNotBlank() -> existingEncrypted
        else -> ""
    }

    /** 只从密文列解密；解密失败 → 空串 + 日志，不崩 UI（RC68 SCHEMA 38）。 */
    private suspend fun decryptToken(encryptedToken: String): String {
        if (encryptedToken.isEmpty()) return ""
        return runCatching { encryptor.decrypt(encryptedToken) }
            .onFailure { FileLogger.w(TAG, "解密 token 失败，返回空串: ${it.message}") }
            .getOrDefault("")
    }

    private suspend fun com.core.deepcode.datalayer.sqldelight.credentials.Git_credentials.toDomain(): GitCredential =
        GitCredential(
            id = id,
            host = host,
            username = username,
            token = decryptToken(encrypted_token),
            label = label,
            isDefault = is_default == 1L,
            createdAt = created_at_ms,
            updatedAt = updated_at_ms,
        )
}
