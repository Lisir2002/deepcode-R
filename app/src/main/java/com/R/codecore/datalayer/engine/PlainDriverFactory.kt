package com.R.codecore.datalayer.engine

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.R.codecore.datalayer.sqldelight.AgentDb
import com.R.codecore.datalayer.sqldelight.CredentialsDb
import com.R.codecore.datalayer.sqldelight.InfraDb
import com.R.codecore.datalayer.sqldelight.SettingsDb
import com.R.codecore.datalayer.sqldelight.T2iDb
import com.R.codecore.datalayer.sqldelight.WorkspaceDb

/**
 * 明文驱动工厂（自测期生效，设计 §8 / §12.2）。
 * 每个库经 AndroidSqliteDriver + FrameworkSQLiteOpenHelperFactory 创建（WAL + 单写者由 SQLDelight/SQLite 保证）。
 */
class PlainDriverFactory(
    private val context: Context,
    private val pathProvider: DatabasePathProvider,
) : DatabaseDriverFactory {

    private val helperFactory = FrameworkSQLiteOpenHelperFactory()

    override fun create(lib: LibName): SqlDriver {
        val name = lib.fileName
        return when (lib) {
            LibName.AGENT -> AndroidSqliteDriver(AgentDb.Schema, context, name, helperFactory)
            LibName.CREDENTIALS -> AndroidSqliteDriver(CredentialsDb.Schema, context, name, helperFactory)
            LibName.SETTINGS -> AndroidSqliteDriver(SettingsDb.Schema, context, name, helperFactory)
            LibName.WORKSPACE -> AndroidSqliteDriver(WorkspaceDb.Schema, context, name, helperFactory)
            LibName.T2I -> AndroidSqliteDriver(T2iDb.Schema, context, name, helperFactory)
            LibName.INFRA -> AndroidSqliteDriver(InfraDb.Schema, context, name, helperFactory)
        }
    }
}
