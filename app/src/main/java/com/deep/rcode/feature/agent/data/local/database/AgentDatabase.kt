package com.deep.rcode.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.deep.rcode.core.db.entity.CredentialEncryptionStateEntity
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointFileSnapshotDao
import com.deep.rcode.feature.agent.data.local.dao.HallucinationFuseDao
import com.deep.rcode.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.deep.rcode.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.deep.rcode.feature.agent.data.local.dao.SkillStateDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.deep.rcode.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import com.deep.rcode.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.SkillStateEntity
import com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity
import com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
import com.deep.rcode.feature.credentials.data.local.dao.GitCredentialDao
import com.deep.rcode.feature.credentials.data.local.entity.GitCredentialEntity
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity
import com.deep.rcode.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.deep.rcode.feature.workspace.data.local.dao.RemoteMountDao
import com.deep.rcode.feature.workspace.data.local.entity.RemoteAuditLogEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteMountEntity
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderDao
import com.deep.rcode.feature.t2i.data.local.dao.T2IProviderModelDao
import com.deep.rcode.feature.t2i.data.local.dao.T2ITaskDao
import com.deep.rcode.feature.t2i.data.local.entity.T2IProviderEntity
import com.deep.rcode.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.deep.rcode.feature.t2i.data.local.entity.T2ITaskEntity

@Database(
    entities = [
        AgentMessageEntity::class,
        ChatSessionEntity::class,
        AIProviderEntity::class,
        RemoteConnectionEntity::class,
        RemoteMountEntity::class,
        TodoItemEntity::class,
        GitCredentialEntity::class,
        CheckpointEntity::class,
        CheckpointFileSnapshotEntity::class,
        CredentialEncryptionStateEntity::class,
        RemoteAuditLogEntity::class,
        ModelCapabilityOverrideEntity::class,
        UserConfirmedSentinelEntity::class,
        HallucinationFuseEntity::class,
        SentinelPlanRejectionAuditEntity::class,
        HardConstraintDeleteAuditEntity::class,
        L0SoftCompactRestoreLogEntity::class,
        ZthTelemetryEventEntity::class,
        T2IProviderEntity::class,
        T2IProviderModelEntity::class,
        T2ITaskEntity::class,
        SkillStateEntity::class
    ],
    version = 41,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun remoteMountDao(): RemoteMountDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun gitCredentialDao(): GitCredentialDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun checkpointFileSnapshotDao(): CheckpointFileSnapshotDao
    abstract fun credentialEncryptionStateDao(): CredentialEncryptionStateDao
    abstract fun remoteAuditLogDao(): RemoteAuditLogDao
    abstract fun modelCapabilityOverrideDao(): ModelCapabilityOverrideDao
    abstract fun userConfirmedSentinelDao(): UserConfirmedSentinelDao
    abstract fun hallucinationFuseDao(): HallucinationFuseDao
    abstract fun sentinelPlanRejectionAuditDao(): SentinelPlanRejectionAuditDao
    abstract fun hardConstraintDeleteAuditDao(): HardConstraintDeleteAuditDao
    abstract fun l0SoftCompactRestoreLogDao(): L0SoftCompactRestoreLogDao
    abstract fun zthTelemetryEventDao(): ZthTelemetryEventDao
    abstract fun t2iProviderDao(): T2IProviderDao
    abstract fun t2iProviderModelDao(): T2IProviderModelDao
    abstract fun t2iTaskDao(): T2ITaskDao
    abstract fun skillStateDao(): SkillStateDao

    companion object {
        const val SCHEMA_VERSION = 41
    }
}
