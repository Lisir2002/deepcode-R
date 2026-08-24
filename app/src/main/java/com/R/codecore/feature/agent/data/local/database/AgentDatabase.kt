package com.R.codecore.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.core.db.entity.CredentialEncryptionStateEntity
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointFileSnapshotDao
import com.R.codecore.feature.agent.data.local.dao.FileEditHunkDao
import com.R.codecore.feature.agent.data.local.dao.HallucinationFuseDao
import com.R.codecore.feature.agent.data.local.dao.ModeSwitchHistoryDao
import com.R.codecore.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.R.codecore.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.R.codecore.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.R.codecore.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.R.codecore.feature.agent.data.local.dao.SkillConversationStateDao
import com.R.codecore.feature.agent.data.local.dao.SkillStateDao
import com.R.codecore.feature.agent.data.local.dao.TodoItemDao
import com.R.codecore.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
import com.R.codecore.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.R.codecore.feature.agent.data.local.entity.AgentMessageEntity
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity
import com.R.codecore.feature.agent.data.local.entity.HallucinationFuseEntity
import com.R.codecore.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.R.codecore.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.R.codecore.feature.agent.data.local.entity.ModeSwitchHistoryEntity
import com.R.codecore.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.R.codecore.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.R.codecore.feature.agent.data.local.entity.SkillConversationStateEntity
import com.R.codecore.feature.agent.data.local.entity.SkillStateEntity
import com.R.codecore.feature.agent.data.local.entity.TodoItemEntity
import com.R.codecore.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import com.R.codecore.feature.agent.data.local.entity.ZthTelemetryEventEntity
import com.R.codecore.feature.credentials.data.local.dao.GitCredentialDao
import com.R.codecore.feature.credentials.data.local.entity.GitCredentialEntity
import com.R.codecore.feature.settings.data.local.dao.AIProviderDao
import com.R.codecore.feature.settings.data.local.entity.AIProviderEntity
import com.R.codecore.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteConnectionDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteMountDao
import com.R.codecore.feature.workspace.data.local.entity.RemoteAuditLogEntity
import com.R.codecore.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.R.codecore.feature.workspace.data.local.entity.RemoteMountEntity
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderDao
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderModelDao
import com.R.codecore.feature.t2i.data.local.dao.T2ITaskDao
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderEntity
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.R.codecore.feature.t2i.data.local.entity.T2ITaskEntity

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
        FileEditHunkEntity::class,
        ModeSwitchHistoryEntity::class,
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
        SkillConversationStateEntity::class,
        SkillStateEntity::class,
        WakeItemEntity::class
    ],
    version = 49,
    exportSchema = true
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
    abstract fun fileEditHunkDao(): FileEditHunkDao
    abstract fun modeSwitchHistoryDao(): ModeSwitchHistoryDao
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
    abstract fun skillConversationStateDao(): SkillConversationStateDao
    abstract fun skillStateDao(): SkillStateDao
    abstract fun wakeQueueDao(): WakeQueueDao

    companion object {
        const val SCHEMA_VERSION = 49
    }
}
