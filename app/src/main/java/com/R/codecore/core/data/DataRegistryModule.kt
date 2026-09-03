package com.R.codecore.core.data

import com.R.codecore.datalayer.backup.SqlDelightDataProvider
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.LibName
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据注册表 DI：把全应用数据域（新数据层 6 个 SQLDelight 库 18 张表）
 * 注册为 `List<DataProvider>`，供 [DataRegistry] 统一注入。
 *
 * 旧 Room 5 域库 + DataStore 目录（settings_prefs / workspace_prefs / terminal_prefs /
 * proxy_prefs / mcp_server_prefs / app_run_meta）均已迁移到 SQLDelight InfraDb.kv_store，
 * 备份/恢复全走单一 SQLDelight 事实源。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataRegistryModule {

    // ── 新数据层（SQLDelight 6 库）表清单：与 data-layer-redesign 设计文档 §11 / §6.6 一一对应 ──

    private val V2_AGENT_TABLES = listOf(
        "agent_session", "agent_message", "agent_message_part", "agent_tool_call", "agent_checkpoint",
    )
    private val V2_CREDENTIALS_TABLES = listOf("cred_connection", "cred_secret")
    private val V2_SETTINGS_TABLES = listOf("settings_profile", "settings_pref")
    private val V2_WORKSPACE_TABLES = listOf("workspace_project", "workspace_file")
    private val V2_T2I_TABLES = listOf("t2i_task", "t2i_result")
    /** InfraDb kv_store 已完全接管所有偏好项（前 DataStore 7 个文件的全部内容）。 */
    private val V2_INFRA_TABLES = listOf("kv_store", "doc_store", "queue_store", "blob_store", "ts_store")

    @Provides
    @Singleton
    fun provideDataProviders(pool: ConnectionPool): List<DataProvider> {
        val providers = mutableListOf<DataProvider>()
        providers += sqlDelightProviders(pool, LibName.AGENT, V2_AGENT_TABLES)
        providers += sqlDelightProviders(pool, LibName.CREDENTIALS, V2_CREDENTIALS_TABLES)
        providers += sqlDelightProviders(pool, LibName.SETTINGS, V2_SETTINGS_TABLES)
        providers += sqlDelightProviders(pool, LibName.WORKSPACE, V2_WORKSPACE_TABLES)
        providers += sqlDelightProviders(pool, LibName.T2I, V2_T2I_TABLES)
        providers += sqlDelightProviders(pool, LibName.INFRA, V2_INFRA_TABLES)
        return providers
    }

    private fun sqlDelightProviders(pool: ConnectionPool, lib: LibName, tables: List<String>): List<DataProvider> =
        tables.map { SqlDelightDataProvider(it, pool.driver(lib), it) }
}