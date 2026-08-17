package com.R.codecore.feature.credentials.data.repository

import com.R.codecore.core.security.CredentialEncryptor
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.credentials.data.local.dao.GitCredentialDao
import com.R.codecore.feature.credentials.data.local.entity.GitCredentialEntity
import com.R.codecore.feature.credentials.domain.model.GitCredential
import com.R.codecore.feature.credentials.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dao: GitCredentialDao,
    private val encryptor: CredentialEncryptor
) : CredentialRepository {

    private companion object {
        const val TAG = "CredentialRepo"
    }

    override fun getAll(): Flow<List<GitCredential>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return dao.findByHost(normalized)?.toDomain()
    }

    override suspend fun save(credential: GitCredential) {
        FileLogger.i(TAG, "保存凭据 id=${credential.id} host=${credential.host} user=${credential.username} default=${credential.isDefault}")
        val now = System.currentTimeMillis()
        val entity = credential.copy(
            host = credential.host.trim().lowercase(),
            createdAt = if (credential.createdAt == 0L) now else credential.createdAt,
            updatedAt = now
        ).toEntity()
        if (entity.isDefault) dao.clearDefaultForHost(entity.host)
        dao.upsert(entity)
    }

    override suspend fun delete(id: String) {
        FileLogger.i(TAG, "删除凭据 id=$id")
        dao.delete(id)
    }

    override suspend fun setDefault(id: String, isDefault: Boolean) {
        FileLogger.i(TAG, "切换默认凭据 id=$id default=$isDefault")
        val entity = dao.getById(id) ?: return
        if (isDefault) dao.clearDefaultForHost(entity.host)
        dao.setDefault(id, isDefault)
    }

    /**
     * RC68 SCHEMA 38 迁移后：明文 token 列已 DROP，唯一持久化 encryptedToken。
     * 若解密失败（极少），直接返回空串（避免 UI 崩），同时 FileLogger 一条。
     */
    private suspend fun GitCredentialEntity.resolveToken(): String {
        if (encryptedToken.isEmpty()) return ""
        return runCatching { encryptor.decrypt(encryptedToken) }
            .onFailure { FileLogger.w(TAG, "解密 token 失败，返回空串: ${it.message}") }
            .getOrDefault("")
    }

    private suspend fun GitCredentialEntity.toDomain(): GitCredential = GitCredential(
        id = id,
        host = host,
        username = username,
        token = resolveToken(),
        label = label,
        isDefault = isDefault,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs
    )

    /**
     * RC68 SCHEMA 38：只写 encryptedToken。明文 token 列已删除，Entity 不再接收明文字段。
     */
    private suspend fun GitCredential.toEntity(): GitCredentialEntity {
        val encrypted = runCatching { encryptor.encrypt(token) }
            .onFailure { FileLogger.w(TAG, "加密 token 失败：${it.message}（token 落库为空串）") }
            .getOrDefault("")
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
}
