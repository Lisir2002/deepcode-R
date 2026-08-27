package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.R.codecore.datalayer.sqldelight.CredentialsDb
import com.R.codecore.datalayer.sqldelight.credentials.Cred_connection
import com.R.codecore.datalayer.sqldelight.credentials.Cred_secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * credentials 域 Repository（设计 §11.2 / L2）：远程连接与密钥的访问门面。
 * secret 走独立表 [Cred_secret]，与连接元数据隔离；库级加密启用时该库优先级最高（设计 §8）。
 *
 * v2-full-takeover P0-1：补 Flow 响应式读，对齐 Room DAO 的 1 个 Flow 查询。
 */
class CredentialsRepository(private val db: CredentialsDb) {

    private val q get() = db.credentialsQueries

    suspend fun createConnection(
        id: String, name: String, type: String, host: String, port: Long,
        username: String?, authType: String, optionsJson: String?,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        q.insertConnection(id, name, type, host, port, username, authType, optionsJson, now)
    }

    suspend fun listConnections(): List<Cred_connection> =
        withContext(Dispatchers.IO) { q.selectAllConnections().executeAsList() }

    suspend fun getConnection(id: String): Cred_connection? =
        withContext(Dispatchers.IO) { q.selectConnectionById(id).executeAsOneOrNull() }

    suspend fun deleteConnection(id: String) =
        withContext(Dispatchers.IO) { q.deleteConnection(id) }

    suspend fun saveSecret(id: String, connectionId: String, kind: String, value: String) =
        withContext(Dispatchers.IO) { q.insertSecret(id, connectionId, kind, value) }

    suspend fun listSecrets(connectionId: String): List<Cred_secret> =
        withContext(Dispatchers.IO) { q.selectSecretsByConnection(connectionId).executeAsList() }

    suspend fun deleteSecrets(connectionId: String) =
        withContext(Dispatchers.IO) { q.deleteSecretByConnection(connectionId) }

    // ── 阶段 1 补表方法（git_credentials）──

    suspend fun insertGitCredential(
        id: String, host: String, username: String, encryptedToken: String, label: String,
        isDefault: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertGitCredential(id, host, username, encryptedToken, label, isDefault, createdAtMs, updatedAtMs)
    }

    suspend fun getGitCredential(id: String): com.R.codecore.datalayer.sqldelight.credentials.Git_credentials? =
        withContext(Dispatchers.IO) { q.selectGitCredentialById(id).executeAsOneOrNull() }

    suspend fun listGitCredentials(): List<com.R.codecore.datalayer.sqldelight.credentials.Git_credentials> =
        withContext(Dispatchers.IO) { q.selectAllGitCredentials().executeAsList() }

    suspend fun getDefaultGitCredential(): com.R.codecore.datalayer.sqldelight.credentials.Git_credentials? =
        withContext(Dispatchers.IO) { q.selectDefaultGitCredential().executeAsOneOrNull() }

    suspend fun deleteGitCredential(id: String) =
        withContext(Dispatchers.IO) { q.deleteGitCredential(id) }

    // ── P0-1 Flow 响应式读（对齐 Room DAO 的 1 个 Flow 查询）──

    fun observeAllGitCredentials(): Flow<List<com.R.codecore.datalayer.sqldelight.credentials.Git_credentials>> =
        q.selectAllGitCredentials().asFlow().mapToList(Dispatchers.IO)
}
