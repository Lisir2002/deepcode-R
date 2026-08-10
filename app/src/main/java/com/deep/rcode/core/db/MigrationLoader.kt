package com.deep.rcode.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.deep.rcode.core.util.FileLogger

class FileMigration(
    val version: Int,
    val scriptName: String,
    val sqlStatements: List<String>
) : Migration(version - 1, version) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS migration_history (" +
                    "version INTEGER PRIMARY KEY, " +
                    "script_name TEXT, " +
                    "executed_at INTEGER)"
        )
        for (sql in sqlStatements) {
            if (sql.isNotBlank()) {
                db.execSQL(sql)
            }
        }
        db.execSQL(
            "INSERT INTO migration_history (version, script_name, executed_at) VALUES (?, ?, ?)",
            arrayOf<Any>(version, scriptName, System.currentTimeMillis())
        )
        FileLogger.i("MigrationLoader", "Applied migration: $scriptName")
    }
}

object MigrationLoader {
    @Volatile
    private var cached: Array<Migration>? = null

    /**
     * 加载 migrations 目录下的 SQL 文件。结果进程级缓存，后续调用直接返回缓存，
     * 避免 Hilt 注入链 AgentModule.provideAgentDatabase（主线程）重复走
     * AssetManager.list/open 导致的冷启动阻塞。
     */
    fun loadMigrations(context: Context): Array<Migration> {
        val cur = cached
        if (cur != null) return cur
        return synchronized(this) {
            val cur2 = cached
            if (cur2 != null) cur2 else doLoad(context).also { cached = it }
        }
    }

    private fun doLoad(context: Context): Array<Migration> {
        val assetManager = context.assets
        val migrationsDir = "migrations"
        val files = runCatching { assetManager.list(migrationsDir) }.getOrNull() ?: emptyArray()

        val migrations = mutableListOf<Migration>()

        for (fileName in files) {
            if (!fileName.endsWith(".sql")) continue

            val versionStr = fileName.substringBefore('_')
            val version = versionStr.toIntOrNull() ?: continue

            val sqlContent = runCatching {
                assetManager.open("$migrationsDir/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            val statements = sqlContent.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            migrations.add(FileMigration(version, fileName, statements))
        }

        return migrations.toTypedArray()
    }
}
