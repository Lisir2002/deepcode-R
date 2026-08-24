package com.R.codecore.feature.workspace.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.core.db.entity.CredentialEncryptionStateEntity
import com.R.codecore.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteConnectionDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteMountDao
import com.R.codecore.feature.workspace.data.local.entity.RemoteAuditLogEntity
import com.R.codecore.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.R.codecore.feature.workspace.data.local.entity.RemoteMountEntity

/**
 * 数据层重构（新写法）后的 workspace 域独立库（v1 全新）。
 *
 * 拆分自旧单巨库 [com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase]（v49）。
 * 仅承载 workspace 域（RemoteConnection / RemoteMount / RemoteAuditLog / CredentialEncryptionState），
 * 与其他域库完全解耦。旧库数据由移植逻辑一次性搬入。
 */
@Database(
    entities = [
        RemoteConnectionEntity::class,
        RemoteMountEntity::class,
        RemoteAuditLogEntity::class,
        CredentialEncryptionStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun remoteMountDao(): RemoteMountDao
    abstract fun remoteAuditLogDao(): RemoteAuditLogDao
    abstract fun credentialEncryptionStateDao(): CredentialEncryptionStateDao

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
