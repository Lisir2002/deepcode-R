package com.R.codecore.datalayer.di

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.engine.AndroidDatabasePathProvider
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.DatabaseDriverFactory
import com.R.codecore.datalayer.engine.DatabasePathProvider
import com.R.codecore.datalayer.engine.LibName
import com.R.codecore.datalayer.engine.PlainDriverFactory
import com.R.codecore.datalayer.migration.MigrationEngine
import com.R.codecore.datalayer.migration.SchemaSelfHealer
import com.R.codecore.datalayer.repository.AgentRepository
import com.R.codecore.datalayer.repository.CredentialsRepository
import com.R.codecore.datalayer.repository.SettingsRepository
import com.R.codecore.datalayer.repository.T2iRepository
import com.R.codecore.datalayer.repository.WorkspaceRepository
import com.R.codecore.datalayer.sqldelight.AgentDb
import com.R.codecore.datalayer.sqldelight.CredentialsDb
import com.R.codecore.datalayer.sqldelight.InfraDb
import com.R.codecore.datalayer.sqldelight.SettingsDb
import com.R.codecore.datalayer.sqldelight.T2iDb
import com.R.codecore.datalayer.sqldelight.WorkspaceDb
import com.R.codecore.datalayer.store.BlobStore
import com.R.codecore.datalayer.store.DocumentStore
import com.R.codecore.datalayer.store.KVStore
import com.R.codecore.datalayer.store.Queue
import com.R.codecore.datalayer.store.TimeSeries
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 新数据层（data-layer-redesign）DI 模块（设计 §12：L0 引擎）。
 *
 * 拓扑：6 个物理库（5 核心域 + 1 infra），每库独立 Database 类与版本链；
 * 每库打开时经 [MigrationEngine.ensureSchema] 完成「全新建库 / 版本迁移 + 快照安全网」。
 *
 * v2-full-takeover P3-紧急加固：ConnectionPool 在首次创建 driver 后、返回给任何调用者之前，
 * 立刻触发 onOpened 回调，对该库跑 ensureSchema + 必要时 SchemaSelfHealer 自愈。
 * 这解决了「DataRegistryModule.provideDataProviders 先于 provideAgentDb 拿到 driver
 * → ensureSchema + 自愈未执行 → 业务查询遇到缺列的旧表 → 启动即崩」的竞态窗口。
 *
 * 加密插拔（设计 §8 / §12.2）：自测期绑定 [PlainDriverFactory]（明文）；
 * 未来启用 SQLCipher 只需把 [provideDriverFactory] 的返回换成 [CipherDriverFactory]，
 * 业务 / 迁移 / 备份零感知。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataLayerModule {

    private const val TAG = "DatalayerBootstrap"

    // ── L0 引擎 ──────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun providePathProvider(@ApplicationContext context: Context): DatabasePathProvider =
        AndroidDatabasePathProvider(context)

    @Provides
    @Singleton
    fun provideDriverFactory(
        @ApplicationContext context: Context,
        pathProvider: DatabasePathProvider,
    ): DatabaseDriverFactory = PlainDriverFactory(context, pathProvider)

    /**
     * Schema 映射表：6 个库 → 各自的 SQLDelight Schema。
     * 供 ConnectionPool.onOpened 回调在 driver 创建时立即 ensureSchema 使用——
     * 不依赖具体库的 DI（比如 AgentDb），避免 provideAgentDb 的 provideDataProviders
     * 之间出现注入顺序竞态。
     */
    private val SCHEMA_MAP = mapOf(
        LibName.AGENT to AgentDb.Schema,
        LibName.CREDENTIALS to CredentialsDb.Schema,
        LibName.SETTINGS to SettingsDb.Schema,
        LibName.WORKSPACE to WorkspaceDb.Schema,
        LibName.T2I to T2iDb.Schema,
        LibName.INFRA to InfraDb.Schema,
    )

    @Provides
    @Singleton
    fun provideConnectionPool(
        factory: DatabaseDriverFactory,
        engine: MigrationEngine,
    ): ConnectionPool {
        val hook: (LibName, app.cash.sqldelight.db.SqlDriver) -> Unit = hook@{ lib, driver ->
            val schema = SCHEMA_MAP[lib]
            if (schema == null) {
                FileLogger.w(TAG, "未知 LibName=$lib，跳过 ensureSchema")
                return@hook
            }
            FileLogger.i(TAG, "ensureSchema($lib) target=${schema.version}")
            runCatching {
                engine.ensureSchema(lib, driver, schema)
                FileLogger.i(TAG, "ensureSchema($lib) 完成")
            }.onFailure {
                FileLogger.e(TAG, "ensureSchema($lib) 失败（忽略，下次打开重试）", it)
            }

            // 对 AGENT 库额外跑 SchemaSelfHealer 自愈 + 保证性复核：
            // 历史设备可能因 MigrationEngine.ensureSchema 的「版本相等 no-op」分支
            // 遇到 user_version 已对齐但 agent_message/agent_session 表缺关键列的旧表。
            // 自愈幂等（结构完好则跳过），只在首次打开时跑一次。
            if (lib == LibName.AGENT) {
                FileLogger.i(TAG, "开始 AGENT 库结构自愈（agent_session + agent_message）")
                runCatching {
                    SchemaSelfHealer.healAgentSession(driver)
                    SchemaSelfHealer.healAgentMessage(driver)
                    SchemaSelfHealer.ensureAgentMessageUsable(driver)
                    SchemaSelfHealer.ensureAgentSessionUsable(driver)
                    FileLogger.i(TAG, "AGENT 库结构自愈完成，agent_message.id 列已确认存在")
                }.onFailure {
                    // 自愈失败是 FATAL：AGENT 库若 agent_message 缺 id，任何消息查询都会崩。
                    // 让异常向上冒泡，进程以明确的错误崩溃（不再落回 confusing 的 no such column）。
                    FileLogger.e(TAG, "AGENT 库结构自愈失败（FATAL，进程将崩溃）", it)
                    throw it
                }
            }
        }
        return ConnectionPool(factory, hook)
    }

    @Provides
    @Singleton
    fun provideMigrationEngine(pathProvider: DatabasePathProvider): MigrationEngine =
        MigrationEngine(pathProvider)

    // ── 6 个 Database：ConnectionPool.onOpened 已确保 ensureSchema + 自愈先跑，
    //   这里再调一遍是幂等安全网（provideAgentDb 里的自愈会在 ConnectionPool 之后再跑一次，
    //   但对已修好的表只会做一次「结构完好，跳过」的幂等检查）───────────────────────

    @Provides
    @Singleton
    fun provideAgentDb(pool: ConnectionPool, engine: MigrationEngine): AgentDb {
        val driver = pool.driver(LibName.AGENT)
        // ConnectionPool.onOpened 已跑过 ensureSchema + 自愈，这里再补一遍幂等复核，
        // 且无论 onOpened 是否执行（理论上 AGENT 一定执行过），都保证 provideAgentDb 返回的
        // AgentDb 所操作的库一定是结构完整的。
        engine.ensureSchema(LibName.AGENT, driver, AgentDb.Schema)
        SchemaSelfHealer.healAgentSession(driver)
        SchemaSelfHealer.healAgentMessage(driver)
        SchemaSelfHealer.ensureAgentMessageUsable(driver)
        SchemaSelfHealer.ensureAgentSessionUsable(driver)
        return AgentDb(driver)
    }

    @Provides
    @Singleton
    fun provideCredentialsDb(pool: ConnectionPool, engine: MigrationEngine): CredentialsDb {
        val driver = pool.driver(LibName.CREDENTIALS)
        engine.ensureSchema(LibName.CREDENTIALS, driver, CredentialsDb.Schema)
        return CredentialsDb(driver)
    }

    @Provides
    @Singleton
    fun provideSettingsDb(pool: ConnectionPool, engine: MigrationEngine): SettingsDb {
        val driver = pool.driver(LibName.SETTINGS)
        engine.ensureSchema(LibName.SETTINGS, driver, SettingsDb.Schema)
        return SettingsDb(driver)
    }

    @Provides
    @Singleton
    fun provideWorkspaceDb(pool: ConnectionPool, engine: MigrationEngine): WorkspaceDb {
        val driver = pool.driver(LibName.WORKSPACE)
        engine.ensureSchema(LibName.WORKSPACE, driver, WorkspaceDb.Schema)
        return WorkspaceDb(driver)
    }

    @Provides
    @Singleton
    fun provideT2iDb(pool: ConnectionPool, engine: MigrationEngine): T2iDb {
        val driver = pool.driver(LibName.T2I)
        engine.ensureSchema(LibName.T2I, driver, T2iDb.Schema)
        return T2iDb(driver)
    }

    @Provides
    @Singleton
    fun provideInfraDb(pool: ConnectionPool, engine: MigrationEngine): InfraDb {
        val driver = pool.driver(LibName.INFRA)
        engine.ensureSchema(LibName.INFRA, driver, InfraDb.Schema)
        return InfraDb(driver)
    }

    // ── 5 个一等 Store（设计 §6）─────────────────────────────────────────

    @Provides
    @Singleton
    fun provideKVStore(db: InfraDb): KVStore = KVStore(db)

    @Provides
    @Singleton
    fun provideDocumentStore(db: InfraDb, pool: ConnectionPool): DocumentStore =
        DocumentStore(db, pool.driver(LibName.INFRA))

    @Provides
    @Singleton
    fun provideQueue(db: InfraDb): Queue = Queue(db)

    @Provides
    @Singleton
    fun provideBlobStore(db: InfraDb): BlobStore = BlobStore(db)

    @Provides
    @Singleton
    fun provideTimeSeries(db: InfraDb): TimeSeries = TimeSeries(db)

    // ── 5 个域 Repository（设计 §11 / L2 门面）────────────────────────────

    @Provides
    @Singleton
    fun provideAgentRepository(db: AgentDb): AgentRepository = AgentRepository(db)

    @Provides
    @Singleton
    fun provideWakeQueueStore(agent: AgentRepository): com.R.codecore.datalayer.repository.WakeQueueStore = agent

    @Provides
    @Singleton
    fun provideCredentialsRepository(db: CredentialsDb): CredentialsRepository = CredentialsRepository(db)

    @Provides
    @Singleton
    fun provideSettingsRepository(db: SettingsDb): SettingsRepository = SettingsRepository(db)

    @Provides
    @Singleton
    fun provideWorkspaceRepository(db: WorkspaceDb): WorkspaceRepository = WorkspaceRepository(db)

    @Provides
    @Singleton
    fun provideT2iRepository(db: T2iDb): T2iRepository = T2iRepository(db)
}
