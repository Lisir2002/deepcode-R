package com.R.codecore.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.R.codecore.BuildConfig
import com.R.codecore.core.util.FileLogger

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
     * 目前资产目录中最早可用的 SQL 版本（08_add_remote_servers.sql）。
     * 当用户从比 v7 更早的 APK 升级上来时，v8 以下无 SQL → 视为「旧库全重建」场景，
     * LightweightSchemaRescue 会单独处理（见 AgentModule Funnel 2）。
     */
    const val MIN_REQUIRED_START_VERSION = 8

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

    /**
     * DB-SHIELD-1 (SCHEMA_GAP) 连续性校验：
     *   - 传入所有 FileMigration 的 version 值（按 Int 升序）
     *   - 必须是 [MIN_REQUIRED_START_VERSION .. declaredDbVersion] 的连续整数
     *   - 缺版本 / 重复版本 / 悬空版本：
     *       · Debug 构建 → 抛 IllegalStateException（让开发者/CI 立刻看到坏版本，防止打包入 Release）
     *       · Release 构建 → 仅 FileLogger.e 写 FATAL 级日志（保持启动链 RC61b hotfix3 安全语义：永不阻断启动）
     */
    fun assertContinuity(
        loadedVersionsSorted: List<Int>,
        declaredDbVersion: Int,
        onWarn: (String) -> Unit = { msg -> FileLogger.e("MigrationLoader", msg) }
    ): List<Int> {
        val missing = mutableListOf<Int>()
        val duplicates = loadedVersionsSorted.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        if (duplicates.isNotEmpty()) {
            val msg = "SCHEMA_GAP[DUPLICATE]: 重复迁移版本号：$duplicates"
            onWarn(msg)
            if (BuildConfig.DEBUG) error(msg)
        }
        val have = loadedVersionsSorted.toSet()
        for (v in MIN_REQUIRED_START_VERSION .. declaredDbVersion) {
            if (!have.contains(v)) missing.add(v)
        }
        if (missing.isNotEmpty()) {
            val msg = "SCHEMA_GAP[MISSING]: 期望覆盖 v${MIN_REQUIRED_START_VERSION}..v${declaredDbVersion}，缺失版本：$missing"
            onWarn(msg)
            if (BuildConfig.DEBUG) error(msg)
        }
        val dangling = loadedVersionsSorted.filter { it > declaredDbVersion }
        if (dangling.isNotEmpty()) {
            val msg = "SCHEMA_GAP[DANGLING]: 迁移版本号高于 @Database(version=$declaredDbVersion)：$dangling"
            onWarn(msg)
            if (BuildConfig.DEBUG) error(msg)
        }
        return missing
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

            // RC94 修复：追加程序化迁移 v43→v44（RobustMigration44）。
            // 该迁移用 PRAGMA table_info 探测实际 schema，幂等修复 5 张表（session_checkpoints /
            // model_capability_overrides / zth_hallucination_fuses / remote_audit_logs /
            // zth_telemetry_events），解决「迁移 42 引用 createdAt 列」导致的线上崩溃。
            migrations.add(RobustMigration44.MIGRATION_43_44)

            // DB-SHIELD-1: 连续性闸门（不抛异常，但写 FATAL 级日志供 CrashHandler 同步落盘 + 下次诊断用）
            val declaredDbVersion = com.R.codecore.feature.agent.data.local.database.AgentDatabase.SCHEMA_VERSION
            val versionsSorted = migrations
                .mapNotNull { migration ->
                    when (migration) {
                        is FileMigration -> migration.version
                        else -> migration.endVersion
                    }
                }
                .distinct()
                .sorted()
            assertContinuity(versionsSorted, declaredDbVersion)

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
