package com.R.codecore.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.R.codecore.core.db.DbSplitMigrator
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointFileSnapshotDao
import com.R.codecore.feature.agent.data.local.dao.FileEditHunkDao
import com.R.codecore.feature.agent.data.local.dao.GoalDao
import com.R.codecore.feature.agent.data.local.dao.HallucinationFuseDao
import com.R.codecore.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.R.codecore.feature.agent.data.local.dao.JobDao
import com.R.codecore.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.R.codecore.feature.agent.data.local.dao.ModeSwitchHistoryDao
import com.R.codecore.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.R.codecore.feature.agent.data.local.dao.PlanDao
import com.R.codecore.feature.agent.data.local.dao.PlaybookRunDao
import com.R.codecore.feature.agent.data.local.dao.ScheduleDao
import com.R.codecore.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.R.codecore.feature.agent.data.local.dao.SkillConversationStateDao
import com.R.codecore.feature.agent.data.local.dao.SkillStateDao
import com.R.codecore.feature.agent.data.local.dao.TodoItemDao
import com.R.codecore.feature.agent.data.local.dao.TrajectoryDao
import com.R.codecore.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
import com.R.codecore.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.agent.data.local.database.AgentDatabaseMigrations
import com.R.codecore.feature.credentials.data.local.dao.GitCredentialDao
import com.R.codecore.feature.credentials.data.local.database.CredentialsDatabase
import com.R.codecore.feature.settings.data.local.dao.AIProviderDao
import com.R.codecore.feature.settings.data.local.database.SettingsDatabase
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderDao
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderModelDao
import com.R.codecore.feature.t2i.data.local.dao.T2ITaskDao
import com.R.codecore.feature.t2i.data.local.database.T2IDatabase
import com.R.codecore.feature.workspace.data.local.dao.CredentialEncryptionStateDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteConnectionDao
import com.R.codecore.feature.workspace.data.local.dao.RemoteMountDao
import com.R.codecore.feature.workspace.data.local.database.WorkspaceDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层重构（新写法）后的统一数据库 DI 模块。
 *
 * 单一事实源：提供 5 个按域拆分的独立库（agent / settings / credentials / workspace / t2i），
 * 并把全部 DAO 分发到各自归属的库。任何 feature 只依赖自己域库的 DAO，
 * 改表不再挤进同一条迁移链 —— 数据库不再被其他因素影响。
 *
 * 每个库 provider 在构建前都会调用 [DbSplitMigrator.migrateIfNeeded]：
 * 幂等、只跑一次，把旧单巨库（[com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase]）
 * 数据一次性搬到新域库后，旧文件改名留底。首次访问任意库时触发，Hilt 单例保证全局唯一。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** 各域库 Room databaseBuilder 的 name（与 [DbSplitMigrator] 表映射保持一致）。 */
    const val AGENT_DB_NAME = "rcodecore_agent_db_v1"
    const val SETTINGS_DB_NAME = "rcodecore_settings_db"
    const val CREDENTIALS_DB_NAME = "rcodecore_credentials_db"
    const val WORKSPACE_DB_NAME = "rcodecore_workspace_db"
    const val T2I_DB_NAME = "rcodecore_t2i_db"

    // ══════════════════════════ 5 个域库 provider ══════════════════════════

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        DbSplitMigrator.migrateIfNeeded(context)
        return buildNewDb(
            context,
            AgentDatabase::class.java,
            AGENT_DB_NAME,
            AgentDatabaseMigrations.MIGRATION_1_2,
            AgentDatabaseMigrations.MIGRATION_2_3,
            AgentDatabaseMigrations.MIGRATION_3_4
        )
    }

    @Provides
    @Singleton
    fun provideSettingsDatabase(@ApplicationContext context: Context): SettingsDatabase {
        DbSplitMigrator.migrateIfNeeded(context)
        return buildNewDb(context, SettingsDatabase::class.java, SETTINGS_DB_NAME)
    }

    @Provides
    @Singleton
    fun provideCredentialsDatabase(@ApplicationContext context: Context): CredentialsDatabase {
        DbSplitMigrator.migrateIfNeeded(context)
        return buildNewDb(context, CredentialsDatabase::class.java, CREDENTIALS_DB_NAME)
    }

    @Provides
    @Singleton
    fun provideWorkspaceDatabase(@ApplicationContext context: Context): WorkspaceDatabase {
        DbSplitMigrator.migrateIfNeeded(context)
        return buildNewDb(context, WorkspaceDatabase::class.java, WORKSPACE_DB_NAME)
    }

    @Provides
    @Singleton
    fun provideT2IDatabase(@ApplicationContext context: Context): T2IDatabase {
        DbSplitMigrator.migrateIfNeeded(context)
        return buildNewDb(context, T2IDatabase::class.java, T2I_DB_NAME)
    }

    /**
     * 新域库统一构建：v1 全新、无历史迁移链（agent 库 v1→v2 起有结构演进，经 [migrations] 注册），
     * 只保留 WAL + 多实例失效通知两个基础设施项。
     * 旧版的 DB-SHIELD 四阶段 Funnel / 崩溃备份等「数据保全重武器」不再需要——
     * 它们的存在正是单巨库历史包袱（49 版迁移链）的产物，新库从零开始无需携带。
     */
    private fun <T : RoomDatabase> buildNewDb(
        context: Context,
        dbClass: Class<T>,
        name: String,
        vararg migrations: Migration
    ): T {
        val builder = Room.databaseBuilder(context, dbClass, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .enableMultiInstanceInvalidation()
        if (migrations.isNotEmpty()) {
            builder.addMigrations(*migrations)
        }
        return builder.build()
    }

    // ══════════════════════════ agent 域 DAO ══════════════════════════

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao =
        database.agentMessageDao()

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao =
        database.chatSessionDao()

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao =
        database.todoItemDao()

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao =
        database.checkpointDao()

    @Provides
    @Singleton
    fun provideCheckpointFileSnapshotDao(database: AgentDatabase): CheckpointFileSnapshotDao =
        database.checkpointFileSnapshotDao()

    @Provides
    @Singleton
    fun provideFileEditHunkDao(database: AgentDatabase): FileEditHunkDao =
        database.fileEditHunkDao()

    @Provides
    @Singleton
    fun provideModeSwitchHistoryDao(database: AgentDatabase): ModeSwitchHistoryDao =
        database.modeSwitchHistoryDao()

    @Provides
    @Singleton
    fun provideModelCapabilityOverrideDao(database: AgentDatabase): ModelCapabilityOverrideDao =
        database.modelCapabilityOverrideDao()

    @Provides
    @Singleton
    fun provideUserConfirmedSentinelDao(database: AgentDatabase): UserConfirmedSentinelDao =
        database.userConfirmedSentinelDao()

    @Provides
    @Singleton
    fun provideHallucinationFuseDao(database: AgentDatabase): HallucinationFuseDao =
        database.hallucinationFuseDao()

    @Provides
    @Singleton
    fun provideSentinelPlanRejectionAuditDao(database: AgentDatabase): SentinelPlanRejectionAuditDao =
        database.sentinelPlanRejectionAuditDao()

    @Provides
    @Singleton
    fun provideHardConstraintDeleteAuditDao(database: AgentDatabase): HardConstraintDeleteAuditDao =
        database.hardConstraintDeleteAuditDao()

    @Provides
    @Singleton
    fun provideL0SoftCompactRestoreLogDao(database: AgentDatabase): L0SoftCompactRestoreLogDao =
        database.l0SoftCompactRestoreLogDao()

    @Provides
    @Singleton
    fun provideZthTelemetryEventDao(database: AgentDatabase): ZthTelemetryEventDao =
        database.zthTelemetryEventDao()

    @Provides
    @Singleton
    fun provideSkillConversationStateDao(database: AgentDatabase): SkillConversationStateDao =
        database.skillConversationStateDao()

    @Provides
    @Singleton
    fun provideSkillStateDao(database: AgentDatabase): SkillStateDao =
        database.skillStateDao()

    @Provides
    @Singleton
    fun provideWakeQueueDao(database: AgentDatabase): WakeQueueDao =
        database.wakeQueueDao()

    @Provides
    @Singleton
    fun provideGoalDao(database: AgentDatabase): GoalDao =
        database.goalDao()

    @Provides
    @Singleton
    fun providePlanDao(database: AgentDatabase): PlanDao =
        database.planDao()

    @Provides
    @Singleton
    fun provideJobDao(database: AgentDatabase): JobDao =
        database.jobDao()

    @Provides
    @Singleton
    fun provideScheduleDao(database: AgentDatabase): ScheduleDao =
        database.scheduleDao()

    @Provides
    @Singleton
    fun provideTrajectoryDao(database: AgentDatabase): TrajectoryDao =
        database.trajectoryDao()

    @Provides
    @Singleton
    fun providePlaybookRunDao(database: AgentDatabase): PlaybookRunDao =
        database.playbookRunDao()

    // ══════════════════════════ settings 域 DAO ══════════════════════════

    @Provides
    @Singleton
    fun provideAIProviderDao(database: SettingsDatabase): AIProviderDao =
        database.aiProviderDao()

    // ══════════════════════════ credentials 域 DAO ══════════════════════════

    @Provides
    @Singleton
    fun provideGitCredentialDao(database: CredentialsDatabase): GitCredentialDao =
        database.gitCredentialDao()

    // ══════════════════════════ workspace 域 DAO ══════════════════════════

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: WorkspaceDatabase): RemoteConnectionDao =
        database.remoteConnectionDao()

    @Provides
    @Singleton
    fun provideRemoteMountDao(database: WorkspaceDatabase): RemoteMountDao =
        database.remoteMountDao()

    @Provides
    @Singleton
    fun provideRemoteAuditLogDao(database: WorkspaceDatabase): RemoteAuditLogDao =
        database.remoteAuditLogDao()

    @Provides
    @Singleton
    fun provideCredentialEncryptionStateDao(database: WorkspaceDatabase): CredentialEncryptionStateDao =
        database.credentialEncryptionStateDao()

    // ══════════════════════════ t2i 域 DAO ══════════════════════════

    @Provides
    @Singleton
    fun provideT2IProviderDao(database: T2IDatabase): T2IProviderDao =
        database.t2iProviderDao()

    @Provides
    @Singleton
    fun provideT2IProviderModelDao(database: T2IDatabase): T2IProviderModelDao =
        database.t2iProviderModelDao()

    @Provides
    @Singleton
    fun provideT2ITaskDao(database: T2IDatabase): T2ITaskDao =
        database.t2iTaskDao()
}
