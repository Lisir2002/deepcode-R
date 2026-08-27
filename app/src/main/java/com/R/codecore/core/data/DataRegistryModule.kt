package com.R.codecore.core.data

import android.content.Context
import com.R.codecore.datalayer.backup.SqlDelightDataProvider
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.LibName
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据注册表 DI：把全应用数据域（新数据层 6 个 SQLDelight 库 18 张表 + DataStore 目录）
 * 注册为 `List<DataProvider>`，供 [DataRegistry] 统一注入。
 *
 * 旧 Room 5 域库（AgentDatabase 等 32 张表）已在新数据层 V2 完全接管后移除，
 * 备份/恢复/自动迁移仅覆盖 V2 相关（key = 表名，见数据层重建设计文档 §11 / §6.6）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataRegistryModule {

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
        pool: ConnectionPool,
    ): List<DataProvider> {
        val providers = mutableListOf<DataProvider>()
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

    /** 为新数据层 [lib] 中的每张 [tables] 生成 SqlDelight 通用表转储 Provider（key = 表名）。 */
    private fun sqlDelightProviders(pool: ConnectionPool, lib: LibName, tables: List<String>): List<DataProvider> =
        tables.map { SqlDelightDataProvider(it, pool.driver(lib), it) }
}