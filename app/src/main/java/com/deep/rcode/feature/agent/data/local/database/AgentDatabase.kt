package com.deep.rcode.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity
import com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao
import com.deep.rcode.feature.credentials.data.local.entity.GitCredentialEntity
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity
import com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.deep.rcode.feature.workspace.data.local.entity.CredentialEncryptionStateEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteAuditLogEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteMountEntity

@Database(
    entities = [AgentMessageEntity::class, ChatSessionEntity::class, AIProviderEntity::class, RemoteConnectionEntity::class, RemoteMountEntity::class, TodoItemEntity::class, GitCredentialEntity::class, CheckpointEntity::class, CheckpointFileSnapshotEntity::class, CredentialEncryptionStateEntity::class, RemoteAuditLogEntity::class, ModelCapabilityOverrideEntity::class],
    version = AgentDatabase.SCHEMA_VERSION,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun gitCredentialDao(): GitCredentialDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun credentialEncryptionStateDao(): CredentialEncryptionStateDao
    abstract fun remoteAuditLogDao(): RemoteAuditLogDao
    /** RC63 备选方案④：单模型三能力复选框手动覆盖。 */
    abstract fun modelCapabilityOverrideDao(): ModelCapabilityOverrideDao

    companion object {
        const val SCHEMA_VERSION = 33
    }
}
