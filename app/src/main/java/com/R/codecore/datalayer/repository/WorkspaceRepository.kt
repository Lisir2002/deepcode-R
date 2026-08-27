package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.R.codecore.datalayer.sqldelight.WorkspaceDb
import com.R.codecore.datalayer.sqldelight.workspace.Workspace_file
import com.R.codecore.datalayer.sqldelight.workspace.Workspace_project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * workspace 域 Repository（设计 §11.4 / L2）：工程 + 文件索引门面。
 *
 * v2-full-takeover P0-1：补 Flow 响应式读，对齐 Room DAO 的 5 个 Flow 查询。
 */
class WorkspaceRepository(private val db: WorkspaceDb) {

    private val q get() = db.workspaceQueries

    suspend fun createProject(
        id: String, name: String, path: String, type: String?,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.insertProject(id, name, path, type, now, now) }

    suspend fun getProject(id: String): Workspace_project? =
        withContext(Dispatchers.IO) { q.selectProjectById(id).executeAsOneOrNull() }

    suspend fun listProjects(): List<Workspace_project> =
        withContext(Dispatchers.IO) { q.selectAllProjects().executeAsList() }

    suspend fun deleteProject(id: String) =
        withContext(Dispatchers.IO) { q.deleteProject(id) }

    suspend fun upsertFile(
        id: String, projectId: String, relPath: String, kind: String?,
        size: Long?, hash: String?, now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) { q.upsertFile(id, projectId, relPath, kind, size, hash, now) }

    suspend fun listFiles(projectId: String): List<Workspace_file> =
        withContext(Dispatchers.IO) { q.selectFilesByProject(projectId).executeAsList() }

    suspend fun deleteFile(id: String) =
        withContext(Dispatchers.IO) { q.deleteFile(id) }

    // ── 阶段 1 补表方法（remote_connections / remote_mounts / remote_audit_logs / credential_encryption_state）──

    suspend fun insertRemoteConnection(
        id: String, name: String, protocol: String, host: String, port: Long, username: String,
        authType: String, authData: String, passphrase: String?,
    ) = withContext(Dispatchers.IO) {
        q.insertRemoteConnection(id, name, protocol, host, port, username, authType, authData, passphrase)
    }

    suspend fun getRemoteConnection(id: String): com.R.codecore.datalayer.sqldelight.workspace.Remote_connections? =
        withContext(Dispatchers.IO) { q.selectRemoteConnectionById(id).executeAsOneOrNull() }

    suspend fun listRemoteConnections(): List<com.R.codecore.datalayer.sqldelight.workspace.Remote_connections> =
        withContext(Dispatchers.IO) { q.selectAllRemoteConnections().executeAsList() }

    suspend fun deleteRemoteConnection(id: String) =
        withContext(Dispatchers.IO) { q.deleteRemoteConnection(id) }

    suspend fun insertRemoteMount(
        id: String, connectionId: String, remotePath: String, localMountPath: String,
        isActive: Long, autoConnect: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertRemoteMount(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    }

    suspend fun listRemoteMounts(connectionId: String): List<com.R.codecore.datalayer.sqldelight.workspace.Remote_mounts> =
        withContext(Dispatchers.IO) { q.selectRemoteMountsByConnection(connectionId).executeAsList() }

    suspend fun deleteRemoteMounts(connectionId: String) =
        withContext(Dispatchers.IO) { q.deleteRemoteMountsByConnection(connectionId) }

    suspend fun insertAuditLog(
        category: String, action: String, connectionId: String?, connectionName: String?,
        remoteHost: String?, success: Long, message: String?, sourceIp: String?, createdAt: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertAuditLog(category, action, connectionId, connectionName, remoteHost, success, message, sourceIp, createdAt)
    }

    suspend fun listAuditLogs(connectionId: String): List<com.R.codecore.datalayer.sqldelight.workspace.Remote_audit_logs> =
        withContext(Dispatchers.IO) { q.selectAuditLogsByConnection(connectionId).executeAsList() }

    suspend fun listAllAuditLogs(): List<com.R.codecore.datalayer.sqldelight.workspace.Remote_audit_logs> =
        withContext(Dispatchers.IO) { q.selectAllAuditLogs().executeAsList() }

    suspend fun deleteAuditLogsOlderThan(createdAt: Long) =
        withContext(Dispatchers.IO) { q.deleteAuditLogsOlderThan(createdAt) }

    suspend fun getEncryptionState(): com.R.codecore.datalayer.sqldelight.workspace.Credential_encryption_state? =
        withContext(Dispatchers.IO) { q.selectEncryptionState().executeAsOneOrNull() }

    suspend fun upsertEncryptionState(
        masterKeyFingerprint: String, dekCiphertext: String, encScheme: String,
        lastRotatedAt: Long, rotationCounter: Long, biometricRequired: Long, migratedFromV1: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertEncryptionState(masterKeyFingerprint, dekCiphertext, encScheme, lastRotatedAt, rotationCounter, biometricRequired, migratedFromV1)
    }

    // ── P0-2 补齐列级 setter 与 REPLACE 语义 ──

    suspend fun upsertRemoteConnection(
        id: String, name: String, protocol: String, host: String, port: Long, username: String,
        authType: String, authData: String, passphrase: String?,
    ) = withContext(Dispatchers.IO) {
        q.insertOrReplaceRemoteConnection(id, name, protocol, host, port, username, authType, authData, passphrase)
    }

    suspend fun updateRemoteConnectionCredentials(id: String, authData: String, passphrase: String?) =
        withContext(Dispatchers.IO) { q.updateConnectionCredentials(authData, passphrase, id) }

    suspend fun upsertRemoteMount(
        id: String, connectionId: String, remotePath: String, localMountPath: String,
        isActive: Long, autoConnect: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertOrReplaceRemoteMount(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    }

    // ── P0-1 Flow 响应式读（对齐 Room DAO 的 5 个 Flow 查询）──

    fun observeAllProjects(): Flow<List<Workspace_project>> =
        q.selectAllProjects().asFlow().mapToList(Dispatchers.IO)

    fun observeFilesByProject(projectId: String): Flow<List<Workspace_file>> =
        q.selectFilesByProject(projectId).asFlow().mapToList(Dispatchers.IO)

    fun observeAllRemoteConnections(): Flow<List<com.R.codecore.datalayer.sqldelight.workspace.Remote_connections>> =
        q.selectAllRemoteConnections().asFlow().mapToList(Dispatchers.IO)

    fun observeRemoteMountsByConnection(connectionId: String): Flow<List<com.R.codecore.datalayer.sqldelight.workspace.Remote_mounts>> =
        q.selectRemoteMountsByConnection(connectionId).asFlow().mapToList(Dispatchers.IO)

    fun observeAllAuditLogs(): Flow<List<com.R.codecore.datalayer.sqldelight.workspace.Remote_audit_logs>> =
        q.selectAllAuditLogs().asFlow().mapToList(Dispatchers.IO)
}
