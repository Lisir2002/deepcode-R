package com.deep.rcode.core.worker

import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.security.CredentialEncryptionContract
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.deep.rcode.feature.workspace.data.local.entity.CredentialEncryptionStateEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.deep.rcode.feature.workspace.domain.RemoteAuditAction
import com.deep.rcode.feature.workspace.domain.RemoteAuditCategory
import com.deep.rcode.feature.workspace.domain.repository.RemoteAuditLogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V1（旧单层密文/明文）→ V2（双层 DEK）迁移 Worker。
 *
 * 扫描 remote_connections / ai_providers / git_credentials 表，
 * 将所有无 "V2:" 前缀的加密字段重新解密后用 V2 加密。
 *
 * 迁移失败的字段（B64 像密文但解不开）→ 清空 + 标记到 SharedPreferences，
 * Settings 页红色 banner 提示用户手动重设。
 */
@Singleton
class V1toV2MigrationWorker @Inject constructor(
    private val encryptor: CredentialEncryptor,
    private val stateDao: CredentialEncryptionStateDao,
    private val connectionDao: RemoteConnectionDao,
    private val aiProviderDao: AIProviderDao,
    private val gitCredentialDao: GitCredentialDao,
    private val auditLogRepo: RemoteAuditLogRepository
) {
    private companion object {
        const val TAG = "V1toV2Migration"
        const val PREFS_NAME = "credential_migration"
        const val KEY_FAILED_IDS = "migration_failed_connection_ids"
    }

    /**
     * 执行迁移。应在 IO 协程上调用。
     * @return 迁移统计：total / migrated / failed / unmigrateable
     */
    suspend fun doWork(): MigrationResult {
        val startMs = System.currentTimeMillis()
        var migrated = 0
        var failed = 0
        var unmigrateable = 0
        val failedIds = mutableListOf<String>()

        // 1. 迁移 remote_connections
        val connections = connectionDao.getAllConnectionsOnce()
        for (conn in connections) {
            val result = migrateConnection(conn)
            when (result) {
                FieldMigrationResult.SUCCESS -> migrated++
                FieldMigrationResult.FAILED -> failed++
                FieldMigrationResult.UNMIGRATEABLE -> {
                    unmigrateable++
                    failedIds.add(conn.id)
                }
                FieldMigrationResult.SKIP -> {} // 已经是 V2
            }
        }

        // 2. 迁移 ai_providers
        val providers = aiProviderDao.getAllProvidersOnce()
        for (provider in providers) {
            if (provider.encryptedApiKey.isNotEmpty() && !provider.encryptedApiKey.startsWith("V2:")) {
                try {
                    val plain = encryptor.decrypt(provider.encryptedApiKey)
                    val newEncrypted = encryptor.encrypt(plain)
                    aiProviderDao.updateEncryptedApiKey(provider.id, newEncrypted)
                    migrated++
                } catch (e: Exception) {
                    FileLogger.w(TAG, "AI Provider ${provider.id} API Key 迁移失败", e)
                    failed++
                }
            }
        }

        // 3. 迁移 git_credentials
        val credentials = gitCredentialDao.getAllOnce()
        for (cred in credentials) {
            if (cred.encryptedToken.isNotEmpty() && !cred.encryptedToken.startsWith("V2:")) {
                try {
                    val plain = encryptor.decrypt(cred.encryptedToken)
                    val newEncrypted = encryptor.encrypt(plain)
                    gitCredentialDao.updateEncryptedToken(cred.id, newEncrypted)
                    migrated++
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Git Credential ${cred.id} Token 迁移失败", e)
                    failed++
                }
            }
        }

        // 4. 更新 state 标记
        val existing = stateDao.getSingleOrNull()
        if (existing != null) {
            stateDao.upsert(existing.copy(migratedFromV1 = true))
        }

        // 5. 审计
        val durationMs = System.currentTimeMillis() - startMs
        auditLogRepo.append(
            category = RemoteAuditCategory.CREDENTIAL,
            action = RemoteAuditAction.CRED_V1_V2_MIGRATE,
            success = failed == 0 && unmigrateable == 0,
            message = "迁移完成: 成功=$migrated, 失败=$failed, 不可迁移=$unmigrateable, 耗时=${durationMs}ms"
        )

        if (failedIds.isNotEmpty()) {
            FileLogger.w(TAG, "迁移失败连接 ID: $failedIds")
        }

        return MigrationResult(
            total = connections.size + providers.size + credentials.size,
            migrated = migrated,
            failed = failed,
            unmigrateable = unmigrateable,
            durationMs = durationMs
        )
    }

    private suspend fun migrateConnection(conn: RemoteConnectionEntity): FieldMigrationResult {
        // 已经是 V2: 跳过
        if (conn.authData.startsWith("V2:")) return FieldMigrationResult.SKIP

        val isPwd = conn.authType.equals("PASSWORD", ignoreCase = true)
        val isKey = conn.authType.equals("PRIVATE_KEY", ignoreCase = true) || conn.authType.equals("key", ignoreCase = true)

        try {
            // 解密 authData
            val plainAuthData = if (isPwd && conn.authData.isNotEmpty()) {
                encryptor.decrypt(conn.authData)
            } else {
                conn.authData // 私钥路径不解密
            }

            // 解密 passphrase
            val plainPassphrase = if (isKey && !conn.passphrase.isNullOrBlank()) {
                encryptor.decrypt(conn.passphrase)
            } else {
                conn.passphrase
            }

            // 用 V2 重新加密
            val newAuthData = if (isPwd && conn.authData.isNotEmpty()) {
                encryptor.encrypt(plainAuthData)
            } else {
                conn.authData
            }
            val newPassphrase = if (isKey && !plainPassphrase.isNullOrBlank()) {
                encryptor.encrypt(plainPassphrase)
            } else {
                plainPassphrase
            }

            // 更新
            connectionDao.updateCredentials(conn.id, newAuthData, newPassphrase)
            return FieldMigrationResult.SUCCESS
        } catch (e: Exception) {
            // 检查是否是 unmigrateable（B64 像密文但解不开）
            if (conn.authData.isNotEmpty() && CredentialEncryptionContract.isLikelyV1Ciphertext(conn.authData)) {
                FileLogger.w(TAG, "连接 ${conn.id} 凭据无法解密（B64 密文但 Keystore 无法解开），清空字段", e)
                connectionDao.updateCredentials(conn.id, "", null)
                return FieldMigrationResult.UNMIGRATEABLE
            }
            FileLogger.w(TAG, "连接 ${conn.id} 迁移失败", e)
            return FieldMigrationResult.FAILED
        }
    }

    data class MigrationResult(
        val total: Int,
        val migrated: Int,
        val failed: Int,
        val unmigrateable: Int,
        val durationMs: Long
    )

    private enum class FieldMigrationResult {
        SUCCESS, FAILED, UNMIGRATEABLE, SKIP
    }
}