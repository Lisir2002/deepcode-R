package com.R.codecore.core.data

import android.content.Context
import com.R.codecore.datalayer.backup.SqlDelightDataProvider
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.LibName
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.credentials.data.local.database.CredentialsDatabase
import com.R.codecore.feature.settings.data.local.database.SettingsDatabase
import com.R.codecore.feature.t2i.data.local.database.T2IDatabase
import com.R.codecore.feature.workspace.data.local.database.WorkspaceDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据注册表 DI：把全应用数据域（5 个域库 32 张 Room 表 + DataStore 目录 +
 * 新数据层 6 个 SQLDelight 库 18 张表）注册为 `List<DataProvider>`，供 [DataRegistry] 统一注入。
 *
 * 表清单与 [com.R.codecore.core.db.DbSplitMigrator] 的旧库表映射保持一一对应（实体类不变，
 * 列 1:1）。任何新增表只需在此登记，备份/恢复/自动迁移自动覆盖（设计文档 R3/R5）。
 *
 * 新数据层（data-layer-redesign）：核心 5 域 + infra 的表经 [SqlDelightDataProvider] 注册
 * （key = 表名，与新层各库表一一对应，见数据层重建设计文档 §11 / §6.6）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataRegistryModule {

    /** agent 域库 23 张表（瘦身 [AgentDatabase] 实体 + 任务编排层 Goal/Plan/Job/Schedule 4 表 + 运行轨迹/剧本 2 表）。 */
    private val AGENT_TABLES = listOf(
        "agent_messages", "chat_sessions", "todo_items", "session_checkpoints",
        "checkpoint_file_snapshots", "file_edit_hunks", "mode_switch_history",
        "model_capability_overrides", "zth_user_confirmed_sentinels", "zth_hallucination_fuses",
        "zth_sentinel_plan_rejection_audits", "zth_hard_constraint_delete_audits",
        "zth_l0_soft_compact_restore_logs", "zth_telemetry_events", "skill_conversation_state",
        "skill_state", "wake_queue",
        "agent_goals", "agent_plans", "agent_jobs", "agent_schedules",
        "agent_trajectories", "agent_playbook_runs",
    )

    /** workspace 域库 4 张表。 */
    private val WORKSPACE_TABLES = listOf(
        "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state",
    )

    /** t2i 域库 3 张表。 */
    private val T2I_TABLES = listOf("t2i_providers", "t2i_provider_models", "t2i_tasks")

    // ── 新数据层（SQLDelight 6 库）表清单：与 data-layer-redesign 设计文档 §11 / §6.6 一一对应 ──

    /** agent 域新库（AgentDb）5 张表。 */
    private val V2_AGENT_TABLES = listOf(
        "agent_session", "agent_message", "agent_message_part", "agent_tool_call", "agent_checkpoint",
    )

    /** credentials 域新库（CredentialsDb）2 张表。 */
    private val V2_CREDENTIALS_TABLES = listOf("cred_connection", "cred_secret")

    /** settings 域新库（SettingsDb）2 张表。 */
    private val V2_SETTINGS_TABLES = listOf("settings_profile", "settings_pref")

    /** workspace 域新库（WorkspaceDb）2 张表。 */
    private val V2_WORKSPACE_TABLES = listOf("workspace_project", "workspace_file")

    /** t2i 域新库（T2iDb）2 张表。 */
    private val V2_T2I_TABLES = listOf("t2i_task", "t2i_result")

    /** infra 库（InfraDb）5 张表（doc_fts 为 FTS5 虚表，随基表 trigger 维护，不单独注册）。 */
    private val V2_INFRA_TABLES = listOf("kv_store", "doc_store", "queue_store", "blob_store", "ts_store")

    @Provides
    @Singleton
    fun provideDataProviders(
        @ApplicationContext context: Context,
        agentDb: AgentDatabase,
        settingsDb: SettingsDatabase,
        credentialsDb: CredentialsDatabase,
        workspaceDb: WorkspaceDatabase,
        t2iDb: T2IDatabase,
        pool: ConnectionPool,
    ): List<DataProvider> {
        val providers = mutableListOf<DataProvider>()
        providers += tableProviders(agentDb, AGENT_TABLES)
        providers += tableProviders(settingsDb, listOf("ai_providers"))
        providers += tableProviders(credentialsDb, listOf("git_credentials"))
        providers += tableProviders(workspaceDb, WORKSPACE_TABLES)
        providers += tableProviders(t2iDb, T2I_TABLES)
        // DataStore 目录级转储（覆盖全部偏好文件，未来新增无需登记）。
        providers += DataStoreDataProvider(context)
        // 新数据层 6 库：SqlDelight 通用表转储（key = 表名）。
        providers += sqlDelightProviders(pool, LibName.AGENT, V2_AGENT_TABLES)
        providers += sqlDelightProviders(pool, LibName.CREDENTIALS, V2_CREDENTIALS_TABLES)
        providers += sqlDelightProviders(pool, LibName.SETTINGS, V2_SETTINGS_TABLES)
        providers += sqlDelightProviders(pool, LibName.WORKSPACE, V2_WORKSPACE_TABLES)
        providers += sqlDelightProviders(pool, LibName.T2I, V2_T2I_TABLES)
        providers += sqlDelightProviders(pool, LibName.INFRA, V2_INFRA_TABLES)
        return providers
    }

    /** 为 [database] 中的每张 [tables] 生成通用表转储 Provider（key = 表名）。 */
    private fun tableProviders(database: androidx.room.RoomDatabase, tables: List<String>): List<DataProvider> =
        tables.map { TableDataProvider(it, database, it) }

    /** 为新数据层 [lib] 中的每张 [tables] 生成 SqlDelight 通用表转储 Provider（key = 表名）。 */
    private fun sqlDelightProviders(pool: ConnectionPool, lib: LibName, tables: List<String>): List<DataProvider> =
        tables.map { SqlDelightDataProvider(it, pool.driver(lib), it) }
}
