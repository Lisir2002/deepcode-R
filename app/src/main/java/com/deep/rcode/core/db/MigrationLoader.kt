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
        // RC61b hotfix3：整个迁移加载套一层 try，任何 asset 解析失败都返回空数组（不抛）。
        // Hilt 注入链 AgentModule → buildAgentDatabase → MigrationLoader.loadMigrations 是
        // Application.onCreate 同步路径，一旦这里抛 RuntimeException 会穿透到 Hilt component
        // 构建失败 → 系统直接杀进程，且用户日志里没有 CRASH 记录（因 CrashHandler 注册虽早
        // 但异步写被杀前丢，已由 hotfix3(1) 同步落盘补上，这里再做"启动链不抛"的双保险）。
        return runCatching {
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

                runCatching {
                    migrations.add(FileMigration(version, fileName, statements))
                }.onFailure {
                    FileLogger.w(
                        "MigrationLoader",
                        "构造迁移 $fileName (v$version) 失败，跳过该条，应用仍可启动（若该迁移为必须，Room 首阶段会失败并自动转 destructive 兜底）",
                        it
                    )
                }
            }
            migrations.toTypedArray()
        }.getOrElse {
            FileLogger.e(
                "MigrationLoader",
                "迁移资产加载整体失败，返回空迁移数组，Room 首阶段必然失败，" +
                        "AgentModule 双阶段会自动降级 destructive 重建，应用仍能启动，历史迁移内容丢失。原因=${it.message}",
                it
            )
            emptyArray()
        }
    }
}
