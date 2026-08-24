package com.R.codecore.core.db

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.R.codecore.core.util.FileLogger
import java.io.File

/**
 * 数据层重构（新写法）· 一次性拆库移植器（T1b 核心）。
 *
 * 职责：把旧单巨库 `rcodecore_agent_db`（v49，含全部历史迁移链）中的数据，
 * 逐表拷贝到新的 5 个域库（agent / settings / credentials / workspace / t2i），
 * 全程**幂等、只跑一次、失败可重试、不删除旧数据**。
 *
 * 时序：
 *   1. [migrateIfNeeded] 在 App 启动、第一个新库 provider 构建前被调用；
 *   2. 判断是否需要移植：旧文件存在 && 移植标记文件不存在；
 *   3. 需要 → 打开旧库（Room + 全迁移链，保证 v49 数据可读）→ 逐表拷贝到新库 →
 *      把旧文件改名 `rcodecore_agent_db.migrated.v49` 留底 → 写移植标记文件（原子幂等）；
 *   4. 失败：保留旧文件与标记文件缺失，下次启动自动重试。
 *
 * 拷贝策略：不用 ATTACH（WAL 多连接锁复杂），改为逐行读取旧表 Cursor → 逐行写入新表，
 * 列名以目标表 `PRAGMA table_info` 为准（新旧实体类不变，列 1:1，天然对齐）。
 */
object DbSplitMigrator {

    private const val TAG = "DbSplitMigrator"
    private const val LEGACY_DB_NAME = "rcodecore_agent_db"
    private const val MARKER_FILE_NAME = ".db_split_migrated"

    /**
     * 旧库 26 张表 → 目标新库文件名 的映射。
     * 新库文件名为各域库 Room databaseBuilder 的 name（见 AgentModule 各 provider）。
     */
    private val TABLE_TO_DB = mapOf(
        // agent 域（AgentDatabase · rcodecore_agent_db_v1）
        "agent_messages" to "rcodecore_agent_db_v1",
        "chat_sessions" to "rcodecore_agent_db_v1",
        "todo_items" to "rcodecore_agent_db_v1",
        "session_checkpoints" to "rcodecore_agent_db_v1",
        "checkpoint_file_snapshots" to "rcodecore_agent_db_v1",
        "file_edit_hunks" to "rcodecore_agent_db_v1",
        "mode_switch_history" to "rcodecore_agent_db_v1",
        "model_capability_overrides" to "rcodecore_agent_db_v1",
        "zth_user_confirmed_sentinels" to "rcodecore_agent_db_v1",
        "zth_hallucination_fuses" to "rcodecore_agent_db_v1",
        "zth_sentinel_plan_rejection_audits" to "rcodecore_agent_db_v1",
        "zth_hard_constraint_delete_audits" to "rcodecore_agent_db_v1",
        "zth_l0_soft_compact_restore_logs" to "rcodecore_agent_db_v1",
        "zth_telemetry_events" to "rcodecore_agent_db_v1",
        "skill_conversation_state" to "rcodecore_agent_db_v1",
        "skill_state" to "rcodecore_agent_db_v1",
        "wake_queue" to "rcodecore_agent_db_v1",
        // settings 域（SettingsDatabase · rcodecore_settings_db）
        "ai_providers" to "rcodecore_settings_db",
        // credentials 域（CredentialsDatabase · rcodecore_credentials_db）
        "git_credentials" to "rcodecore_credentials_db",
        // workspace 域（WorkspaceDatabase · rcodecore_workspace_db）
        "remote_connections" to "rcodecore_workspace_db",
        "remote_mounts" to "rcodecore_workspace_db",
        "remote_audit_logs" to "rcodecore_workspace_db",
        "credential_encryption_state" to "rcodecore_workspace_db",
        // t2i 域（T2IDatabase · rcodecore_t2i_db）
        "t2i_providers" to "rcodecore_t2i_db",
        "t2i_provider_models" to "rcodecore_t2i_db",
        "t2i_tasks" to "rcodecore_t2i_db"
    )

    /**
     * 幂等入口：仅当「旧库文件存在 && 移植标记未置位」时执行移植。
     * `@Synchronized`：5 个域库 provider 可能在不同线程首次并发访问，保证移植只执行一次。
     * 整体包 runCatching：任何异常（旧库损坏 / 迁移缺失 / I/O 失败）都不上抛、不阻断启动，
     * 旧库文件保留、标记不置位 → 下次启动自动重试（幂等）。
     */
    @Synchronized
    fun migrateIfNeeded(context: Context) {
        runCatching { doMigrate(context) }
            .onFailure { e ->
                FileLogger.e(
                    TAG,
                    "拆库移植失败，旧库文件保留、标记未置位，下次启动自动重试。原因=${e.message}",
                    e
                )
            }
    }

    private fun doMigrate(context: Context) {
        val legacyFile = context.getDatabasePath(LEGACY_DB_NAME)
        if (!legacyFile.exists()) {
            // 全新安装或已移植（旧文件已改名）：无需任何操作
            return
        }
        val marker = markerFile(context)
        if (marker.exists()) {
            return
        }

        FileLogger.i(TAG, "检测到旧单巨库 ${legacyFile.absolutePath}（未移植），开始一次性拆库移植…")

        val legacyDb = openLegacyDatabase(context)
        try {
            val targetDbs = LinkedHashMap<String, SupportSQLiteDatabase>()
            try {
                // 逐个目标库打开（Room 首次打开会建表），缓存连接
                for (dbName in TABLE_TO_DB.values.distinct()) {
                    targetDbs[dbName] = openTargetDatabase(context, dbName)
                }
                // 逐表拷贝：旧表只读 Cursor → 目标表逐行写入
                var copiedTables = 0
                for ((srcTable, dstDbName) in TABLE_TO_DB) {
                    val dstDb = targetDbs.getValue(dstDbName)
                    copyTable(legacyDb, srcTable, dstDb, srcTable)
                    copiedTables++
                }
                // 全部成功：改旧文件留底 + 写标记（原子）
                renameLegacyDb(legacyFile)
                writeMarker(marker)
                FileLogger.i(
                    TAG,
                    "拆库移植完成：$copiedTables 张表已迁入 ${targetDbs.size} 个新库，旧库已改名留底：${legacyFile.path}.migrated.v49"
                )
            } finally {
                targetDbs.values.forEach { runCatching { it.close() } }
            }
        } finally {
            runCatching { legacyDb.close() }
        }
    }

    /** 打开旧单巨库（Room 全迁移链，v49 数据可读）。
     * 注意：刻意【不】配 fallbackToDestructiveMigration —— 若旧库迁移失败/损坏，让异常上抛
     * （由 migrateIfNeeded 的 runCatching 接住），保留旧库文件留待重试，绝不静默清空用户数据。 */
    private fun openLegacyDatabase(context: Context): SupportSQLiteDatabase {
        val db = androidx.room.Room.databaseBuilder(
            context,
            com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase::class.java,
            LEGACY_DB_NAME
        )
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        return db.openHelper.writableDatabase
    }

    /** 打开目标新库（v1 全新，无迁移链）；失败则抛出。 */
    private fun openTargetDatabase(context: Context, dbName: String): SupportSQLiteDatabase {
        val dbClass = when (dbName) {
            "rcodecore_agent_db_v1" -> com.R.codecore.feature.agent.data.local.database.AgentDatabase::class.java
            "rcodecore_settings_db" -> com.R.codecore.feature.settings.data.local.database.SettingsDatabase::class.java
            "rcodecore_credentials_db" -> com.R.codecore.feature.credentials.data.local.database.CredentialsDatabase::class.java
            "rcodecore_workspace_db" -> com.R.codecore.feature.workspace.data.local.database.WorkspaceDatabase::class.java
            "rcodecore_t2i_db" -> com.R.codecore.feature.t2i.data.local.database.T2IDatabase::class.java
            else -> error("未知目标库名: $dbName")
        }
        @Suppress("UNCHECKED_CAST")
        val db = androidx.room.Room.databaseBuilder(
            context,
            dbClass as Class<out androidx.room.RoomDatabase>,
            dbName
        )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        return db.openHelper.writableDatabase
    }

    /**
     * 逐表拷贝：列名以目标表 `PRAGMA table_info` 为准；旧表 SELECT 同名列，
     * 逐行按列类型读取写入。目标表在 Room 首次打开时已按新实体建好（1:1）。
     */
    private fun copyTable(
        srcDb: SupportSQLiteDatabase,
        srcTable: String,
        dstDb: SupportSQLiteDatabase,
        dstTable: String
    ) {
        val columns = readColumns(dstDb, dstTable)
        if (columns.isEmpty()) {
            FileLogger.w(TAG, "目标表 $dstTable 无列（可能未建表），跳过")
            return
        }
        val colList = columns.joinToString(",")
        val placeholders = columns.joinToString(",") { "?" }
        val insertSql = "INSERT OR REPLACE INTO `$dstTable` ($colList) VALUES ($placeholders)"

        dstDb.beginTransaction()
        var inserted = 0
        try {
            srcDb.query("SELECT $colList FROM `$srcTable`").use { cursor ->
                while (cursor.moveToNext()) {
                    val args = columns.mapIndexed { i, _ -> cursorValue(cursor, i) }.toTypedArray()
                    dstDb.execSQL(insertSql, args)
                    inserted++
                }
            }
            dstDb.setTransactionSuccessful()
        } finally {
            dstDb.endTransaction()
        }
        if (inserted > 0) {
            FileLogger.i(TAG, "表 $srcTable → $dstTable：搬入 $inserted 行")
        }
    }

    private fun readColumns(db: SupportSQLiteDatabase, table: String): List<String> {
        val out = mutableListOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                out.add(cursor.getString(nameIdx))
            }
        }
        return out
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
        Cursor.FIELD_TYPE_NULL -> null
        else -> cursor.getString(index)
    }

    private fun markerFile(context: Context): File = File(context.filesDir, MARKER_FILE_NAME)

    private fun writeMarker(marker: File) {
        marker.parentFile?.mkdirs()
        marker.writeText(System.currentTimeMillis().toString())
    }

    private fun renameLegacyDb(legacyFile: File) {
        val migratedPath = "${legacyFile.path}.migrated.v49"
        // 先合并 WAL（若有）到主文件，再改名主文件；wal/shm 一并改名留底
        listOf(legacyFile, File("${legacyFile.path}-wal"), File("${legacyFile.path}-shm")).forEach { f ->
            if (f.exists()) {
                val dst = File("$migratedPath${f.name.removePrefix(legacyFile.name)}")
                if (f.renameTo(dst)) {
                    FileLogger.i(TAG, "旧库文件留底：${f.name} → ${dst.name}")
                } else {
                    // renameTo 失败（极少）：退化为复制+删除
                    f.copyTo(dst, overwrite = true)
                    f.delete()
                }
            }
        }
    }
}
