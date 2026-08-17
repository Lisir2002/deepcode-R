package com.R.codecore.core.worker

import com.R.codecore.core.security.CredentialEncryptor
import com.R.codecore.core.security.OperationResult
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.credentials.data.local.dao.GitCredentialDao
import com.R.codecore.feature.settings.data.local.dao.AIProviderDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteConnectionDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEK 轮换 Worker：生成新 DEK，遍历所有加密字段重新加密。
 * 不依赖 WorkManager。
 */
@Singleton
class CredentialRotationWorker @Inject constructor(
    private val encryptor: CredentialEncryptor,
    private val connectionDao: RemoteConnectionDao,
    private val aiProviderDao: AIProviderDao,
    private val gitCredentialDao: GitCredentialDao
) {
    private companion object {
        const val TAG = "CredentialRotation"
    }

    /**
     * 执行轮换。先调用 encryptor.scheduleRotateDek() 更新 state 表，
     * 然后逐表重新加密。
     */
    suspend fun doWork(): RotationResult {
        val startMs = System.currentTimeMillis()
        var totalRows = 0

        // 1. 先轮换 DEK（更新 state 表 + 内存缓存）
        val rotateResult = encryptor.scheduleRotateDek()
        if (rotateResult !is OperationResult.Success) {
            val error = (rotateResult as? OperationResult.Failure)?.error
            return RotationResult(0, 0, 0, error?.message ?: "DEK 轮换失败")
        }

        // 2. 重新加密 remote_connections
        val connections = connectionDao.getAllConnectionsOnce()
        for (conn in connections) {
            try {
                val isPwd = conn.authType.equals("PASSWORD", ignoreCase = true)
                val isKey = conn.authType.equals("PRIVATE_KEY", ignoreCase = true) || conn.authType.equals("key", ignoreCase = true)

                val plainAuth = if (isPwd && conn.authData.isNotEmpty()) {
                    encryptor.decrypt(conn.authData)
                } else {
                    conn.authData
                }
                val plainPP = if (isKey && !conn.passphrase.isNullOrBlank()) {
                    encryptor.decrypt(conn.passphrase)
                } else {
                    conn.passphrase
                }
                val newAuth = if (isPwd && conn.authData.isNotEmpty()) {
                    encryptor.encrypt(plainAuth)
                } else {
                    conn.authData
                }
                val newPP = if (isKey && !plainPP.isNullOrBlank()) {
                    encryptor.encrypt(plainPP)
                } else {
                    plainPP
                }
                connectionDao.updateCredentials(conn.id, newAuth, newPP)
                totalRows++
            } catch (e: Exception) {
                FileLogger.w(TAG, "连接 ${conn.id} 轮换重加密失败", e)
            }
        }

        // 3. 重新加密 ai_providers
        val providers = aiProviderDao.getAllProvidersOnce()
        for (provider in providers) {
            try {
                if (provider.encryptedApiKey.isNotEmpty()) {
                    val plain = encryptor.decrypt(provider.encryptedApiKey)
                    val newEnc = encryptor.encrypt(plain)
                    aiProviderDao.updateEncryptedApiKey(provider.id, newEnc)
                    totalRows++
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Provider ${provider.id} API Key 轮换重加密失败", e)
            }
        }

        // 4. 重新加密 git_credentials
        val credentials = gitCredentialDao.getAllOnce()
        for (cred in credentials) {
            try {
                if (cred.encryptedToken.isNotEmpty()) {
                    val plain = encryptor.decrypt(cred.encryptedToken)
                    val newEnc = encryptor.encrypt(plain)
                    gitCredentialDao.updateEncryptedToken(cred.id, newEnc)
                    totalRows++
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Git Credential ${cred.id} Token 轮换重加密失败", e)
            }
        }

        val durationMs = System.currentTimeMillis() - startMs
        FileLogger.i(TAG, "DEK 轮换完成：重加密 $totalRows 行，耗时 ${durationMs}ms")
        return RotationResult(totalRows, 0, 0, null)
    }

    data class RotationResult(
        val totalRows: Int,
        val failed: Int,
        val skipped: Int,
        val error: String?
    )
}