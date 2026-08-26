package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.CredentialsDb
import com.R.codecore.datalayer.sqldelight.credentials.Cred_connection
import com.R.codecore.datalayer.sqldelight.credentials.Cred_secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * credentials 域 Repository（设计 §11.2 / L2）：远程连接与密钥的访问门面。
 * secret 走独立表 [Cred_secret]，与连接元数据隔离；库级加密启用时该库优先级最高（设计 §8）。
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
}
