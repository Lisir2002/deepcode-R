package com.R.codecore.datalayer.di

import android.content.Context
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.DatabaseDriverFactory
import com.R.codecore.datalayer.engine.DatabasePathProvider
import com.R.codecore.datalayer.engine.LibName
import com.R.codecore.datalayer.engine.PlainDriverFactory
import com.R.codecore.datalayer.migration.MigrationEngine
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
 * 加密插拔（设计 §8 / §12.2）：自测期绑定 [PlainDriverFactory]（明文）；
 * 未来启用 SQLCipher 只需把 [provideDriverFactory] 的返回换成 [com.R.codecore.datalayer.engine.CipherDriverFactory]，
 * 业务 / 迁移 / 备份零感知。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataLayerModule {

    // ── L0 引擎 ──────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun providePathProvider(@ApplicationContext context: Context): DatabasePathProvider =
        DatabasePathProvider(context)

    @Provides
    @Singleton
    fun provideDriverFactory(
        @ApplicationContext context: Context,
        pathProvider: DatabasePathProvider,
    ): DatabaseDriverFactory = PlainDriverFactory(context, pathProvider)

    @Provides
    @Singleton
    fun provideConnectionPool(factory: DatabaseDriverFactory): ConnectionPool =
        ConnectionPool(factory)

    @Provides
    @Singleton
    fun provideMigrationEngine(pathProvider: DatabasePathProvider): MigrationEngine =
        MigrationEngine(pathProvider)

    // ── 6 个 Database（每库打开时跑迁移/建库，版本链独立）───────────────

    @Provides
    @Singleton
    fun provideAgentDb(pool: ConnectionPool, engine: MigrationEngine): AgentDb {
        val driver = pool.driver(LibName.AGENT)
        engine.ensureSchema(LibName.AGENT, driver, AgentDb.Schema)
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
