package com.deep.rcode.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.deep.rcode.feature.agent.data.local.dao.AgentMessageDao
import com.deep.rcode.feature.agent.data.local.dao.ChatSessionDao
import com.deep.rcode.feature.agent.data.local.dao.CheckpointDao
import com.deep.rcode.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.deep.rcode.feature.agent.data.local.dao.TodoItemDao
import com.deep.rcode.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.deep.rcode.feature.agent.data.local.dao.HallucinationFuseDao
import com.deep.rcode.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.deep.rcode.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.deep.rcode.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.deep.rcode.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity
import com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
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
        // ZTH v1.0 新增 6 表（Phase 1 / SCHEMA v33 → v37；与 SQL 34/35/36/37 迁移顺序一致）
        UserConfirmedSentinelEntity::class,
        HallucinationFuseEntity::class,
        SentinelPlanRejectionAuditEntity::class,
        HardConstraintDeleteAuditEntity::class,
        L0SoftCompactRestoreLogEntity::class,
        ZthTelemetryEventEntity::class
    ],
    // 注意：version 必须使用字面量，不能使用 companion 常量引用。
    // 原因：AndroidX Room KSP 处理器 (2.7+) 在 forward reference companion const
    //   时，XAnnotation.getAsInt("version") 可能抛 "No property named version
    //   was found in annotation Database"（CI Kotlin 2.1.20 + KSP 2.1 复现）。
    // 字面量 37 与下方 companion SCHEMA_VERSION 常量保持严格手动双写一致。
    version = 37,
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

    // ── ZTH v1.0 新增 6 DAO ──────────────────────────────────────────────
    abstract fun userConfirmedSentinelDao(): UserConfirmedSentinelDao
    abstract fun hallucinationFuseDao(): HallucinationFuseDao
    abstract fun sentinelPlanRejectionAuditDao(): SentinelPlanRejectionAuditDao
    abstract fun hardConstraintDeleteAuditDao(): HardConstraintDeleteAuditDao
    abstract fun l0SoftCompactRestoreLogDao(): L0SoftCompactRestoreLogDao
    abstract fun zthTelemetryEventDao(): ZthTelemetryEventDao

    companion object {
        /**
         * SCHEMA 版本历史（为避免版本号跳号 + 保证 assets/migrations/*.sql 文件名一一对应）：
         * - v32 credential_encryption_state / remote_audit_logs（2 表）
         * - v33 model_capability_overrides（RC63）
         * - v34 zth_user_confirmed_sentinels（ZTH-0 铁律主表）
         * - v35 zth_hallucination_fuses（全局 + 会话级熔断状态机）
         * - v36 zth_sentinel_plan_rejection_audits / zth_hard_constraint_delete_audits / zth_l0_soft_compact_restore_logs（3 审计表）
         * - v37 zth_telemetry_events（埋点，Canvas 图）
         */
        const val SCHEMA_VERSION = 37
    }
}
