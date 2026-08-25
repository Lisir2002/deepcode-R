package com.R.codecore.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.entity.AgentMessageEntity
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import com.R.codecore.feature.agent.data.local.entity.HallucinationFuseEntity
import com.R.codecore.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.R.codecore.feature.agent.data.local.entity.JobEntity
import com.R.codecore.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.R.codecore.feature.agent.data.local.entity.ModeSwitchHistoryEntity
import com.R.codecore.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.R.codecore.feature.agent.data.local.entity.PlanEntity
import com.R.codecore.feature.agent.data.local.entity.ScheduleEntity
import com.R.codecore.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.R.codecore.feature.agent.data.local.entity.SkillConversationStateEntity
import com.R.codecore.feature.agent.data.local.entity.SkillStateEntity
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import com.R.codecore.feature.agent.data.local.entity.TodoItemEntity
import com.R.codecore.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.R.codecore.feature.agent.data.local.entity.ZthTelemetryEventEntity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DB-SHIELD-4 (Funnel 2): 「轻量抢救」模式（RC94 重写 —— 从「注解反射」升级为「Room 官方导出 schema JSON」）。
 *
 * ── 为什么重写 ─────────────────────────────────────────────────────
 * 旧版用 java.lang.reflect 读取 @Entity/@ColumnInfo/@Index 注解来重建表。但 Room 的注解
 * retention 是 BINARY（不是 RUNTIME），Release/R8 构建下 getAnnotation() 恒返回 null，
 * 线上日志反复出现 `Class XxxEntity 缺少 @Entity 注解` → Funnel 2 从未真正生效，直接连锁
 * 崩到 Funnel 3/4（删表/全删）。RC94 改为解析 Room 编译期导出的 schema JSON
 * （app/schemas/<db>/<version>.json，KSP 自动生成，build.gradle.kts 已打进 assets）：
 *   · createSql 就是 Room 生成的精确建表 SQL（含列类型/NOT NULL/DEFAULT/主键），
 *     抢救建出的表与 Room TableInfo 校验期望 100% 一致；
 *   · 索引 createSql 同样来自 Room，IF NOT EXISTS 幂等；
 *   · 不再依赖任何注解反射，Release/R8 下行为与 Debug 完全一致。
 *
 * 触发条件：正常 Room 构建（保守迁移）失败 → 说明有 migration 缺失或对不上。
 * 我们不直接 destructive（DROP 老表 → 用户数据没了 + 删库过程中进程被杀=闪退），而是：
 *   1. 用 SQLite 原生 SupportSQLiteOpenHelper 打开旧库（绕过 Room 的 TableInfo 校验）；
 *   2. 对 schema JSON 里每一个实体：
 *      a) 表不存在 → 直接用 createSql 建表（幂等）；
 *      b) 表已存在 → PRAGMA table_info 对比；缺列 → ALTER TABLE ADD COLUMN（带实体声明的 DEFAULT）；
 *      c) 用 JSON 里的索引 createSql 建索引（IF NOT EXISTS 幂等）；
 *   3. PRAGMA user_version = SCHEMA_VERSION。
 *
 * 语义：老表（chat_sessions / agent_messages 等用户历史数据）100% 保留，即使它的某一列
 *   和新 Entity 对不上也不 DROP，留着 Room 再做二次校验（最坏情况进入 Funnel 3）。
 */
object LightweightSchemaRescue {

    private const val TAG = "LightweightSchemaRescue"

    /**
     * Room 导出 schema JSON 在 assets 中的根目录。
     *
     * 注意：build.gradle.kts 用 `assets.srcDir("schemas")` 把 app/schemas 目录整体并入 assets，
     * AGP 会剥掉源目录名，因此 APK 内实际路径是
     *   assets/com.R.codecore.feature.agent.data.local.database.AgentDatabase/44.json
     * （没有 schemas/ 前缀）。此处根目录必须为空字符串，与 APK 实际布局保持一致，
     * 否则运行时 assets.open() 找不到文件 → Funnel 2 抢救失效。
     */
    private const val SCHEMA_ASSET_DIR = ""

    /** AgentDatabase 全限定名（Room 导出 schema 的子目录名 = 数据库类 FQCN）。 */
    private const val DATABASE_FQCN = "com.R.codecore.feature.agent.data.local.database.AgentDatabase"

    /** Room createSql 里的表名占位符。 */
    private const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"

    data class RescueReport(
        val tablesCreated: Int,
        val columnsAdded: Int,
        val indexesCreated: Int,
        val tablesSkippedExisting: Int,
        val failures: List<String>
    )

    /**
     * ALL_ENTITY_CLASSES：与瘦身 AgentDatabase.kt @Database(entities=[...]) 一一对应（21 项顺序一致）。
     * 数据层重构（新写法）后，本清单仅代表 agent 域库；其余 4 个域库各自独立、无救援清单。
     * 加表/删表时必须同步更新；DbSCHIELDPreflightTest.ENTITY-COUNT-TEST CI 闸门强制校验。
     *
     * 注意：RC94 起 Funnel 2 抢救不再用反射读注解（改读 schema JSON），此清单仅保留给
     *   CI 闸门做「@Database entities 与清单一致性」校验，防止加表时漏同步。
     */
    val ALL_ENTITY_CLASSES: List<Class<*>> = listOf<Class<*>>(
        AgentMessageEntity::class.java,
        ChatSessionEntity::class.java,
        TodoItemEntity::class.java,
        CheckpointEntity::class.java,
        CheckpointFileSnapshotEntity::class.java,
        FileEditHunkEntity::class.java,
        ModeSwitchHistoryEntity::class.java,
        ModelCapabilityOverrideEntity::class.java,
        UserConfirmedSentinelEntity::class.java,
        HallucinationFuseEntity::class.java,
        SentinelPlanRejectionAuditEntity::class.java,
        HardConstraintDeleteAuditEntity::class.java,
        L0SoftCompactRestoreLogEntity::class.java,
        ZthTelemetryEventEntity::class.java,
        SkillConversationStateEntity::class.java,
        SkillStateEntity::class.java,
        WakeItemEntity::class.java,
        GoalEntity::class.java,
        PlanEntity::class.java,
        JobEntity::class.java,
        ScheduleEntity::class.java
    )

    // ── schema JSON 解析模型（Room 官方导出格式）──────────────────────────
    private data class ColumnSchema(
        val columnName: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    private data class IndexSchema(
        val name: String,
        val createSql: String
    )

    private data class EntitySchema(
        val tableName: String,
        val createSql: String,
        val columns: List<ColumnSchema>,
        val indices: List<IndexSchema>
    )

    fun rescue(context: Context, dbFile: File, dbVersion: Int): RescueReport {
        val reportBuilder = RescueReportBuilder()

        // 1) 从 assets 加载 Room 官方导出的 schema JSON（权威 schema，替代失效的注解反射）
        val entities = loadSchemaEntities(context, dbVersion, reportBuilder)
        if (entities.isEmpty()) {
            FileLogger.e(
                TAG,
                "schema JSON 加载为空，Funnel 2 无法抢救（assets 缺 $SCHEMA_ASSET_DIR/$DATABASE_FQCN/$dbVersion.json？" +
                        "请确认 build.gradle.kts 已配置 assets.srcDir(\"schemas\") 且 KSP 已导出 schema）"
            )
        }

        runCatching {
            val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(dbVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        doRescueOnDb(db, entities, reportBuilder)
                        db.execSQL("PRAGMA user_version = $dbVersion")
                        FileLogger.i(TAG, "轻量抢救结束: created=${reportBuilder.tablesCreated} " +
                                "+cols=${reportBuilder.columnsAdded} +idxs=${reportBuilder.indexesCreated} " +
                                "skipped=${reportBuilder.tablesSkippedExisting} " +
                                "failures=${reportBuilder.failures.size}")
                    }
                })
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(config)
            try {
                // P1-1 修复：不能只 `.use { }` 关闭 writableDatabase，helper 本身也要 close，
                // 否则 FrameworkSQLiteOpenHelper 内部持有系统 SQLiteOpenHelper 的连接缓存，
                // 紧接着 Funnel2 retry Room build 同一份 dbName 可能触发 SQLITE_CANTOPEN / database is locked (code 5)
                helper.writableDatabase.use { /* onOpen 回调里执行抢救 */ }
            } finally {
                helper.close()
            }
        }.onFailure {
            FileLogger.e(TAG, "LightweightSchemaRescue 轻量抢救阶段抛异常", it)
            reportBuilder.failures.add("LightweightSchemaRescue overall failed: ${it.message}")
        }
        return reportBuilder.build()
    }

    // ── 加载 & 解析 Room schema JSON ──────────────────────────────────────
    private fun loadSchemaEntities(
        context: Context,
        dbVersion: Int,
        report: RescueReportBuilder
    ): List<EntitySchema> {
        val assetPath = if (SCHEMA_ASSET_DIR.isEmpty()) {
            "$DATABASE_FQCN/$dbVersion.json"
        } else {
            "$SCHEMA_ASSET_DIR/$DATABASE_FQCN/$dbVersion.json"
        }
        val jsonText = runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrElse {
            report.failures.add("无法读取 schema JSON asset: $assetPath (${it.message})")
            return emptyList()
        }
        return runCatching {
            parseEntities(JSONObject(jsonText))
        }.getOrElse {
            report.failures.add("解析 schema JSON 失败: ${it.message}")
            emptyList()
        }
    }

    private fun parseEntities(root: JSONObject): List<EntitySchema> {
        val db = root.getJSONObject("database")
        val arr = db.getJSONArray("entities")
        val out = mutableListOf<EntitySchema>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tableName = e.getString("tableName")
            val createSql = e.getString("createSql")

            val fields = e.getJSONArray("fields")
            val columns = mutableListOf<ColumnSchema>()
            for (j in 0 until fields.length()) {
                val f = fields.getJSONObject(j)
                columns.add(
                    ColumnSchema(
                        columnName = f.getString("columnName"),
                        affinity = f.getString("affinity"),
                        notNull = f.optBoolean("notNull", false),
                        defaultValue = if (f.has("defaultValue")) f.getString("defaultValue") else null
                    )
                )
            }

            val indices = mutableListOf<IndexSchema>()
            if (e.has("indices")) {
                val idxArr = e.getJSONArray("indices")
                for (j in 0 until idxArr.length()) {
                    val idx = idxArr.getJSONObject(j)
                    indices.add(
                        IndexSchema(
                            name = idx.getString("name"),
                            createSql = idx.optString("createSql", "")
                        )
                    )
                }
            }
            out.add(EntitySchema(tableName, createSql, columns, indices))
        }
        return out
    }

    private fun doRescueOnDb(
        db: SupportSQLiteDatabase,
        entities: List<EntitySchema>,
        report: RescueReportBuilder
    ) {
        db.beginTransaction()
        try {
            // 1) 迁移历史表先创建（保证后续 FileMigration 可继续写）
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, script_name TEXT, executed_at INTEGER)"
            )
            for (entity in entities) {
                runCatching {
                    rescueOneEntity(db, entity, report)
                }.onFailure {
                    val m = "rescueOneEntity(${entity.tableName}) failed: ${it.message}"
                    FileLogger.w(TAG, m, it)
                    report.failures.add(m)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── 单表抢救：CREATE / ADD COLUMNS / CREATE INDICES（全部 IF NOT EXISTS 幂等）──
    private fun rescueOneEntity(
        db: SupportSQLiteDatabase,
        entity: EntitySchema,
        report: RescueReportBuilder
    ) {
        val tableName = entity.tableName
        val existingCols = readExistingColumns(db, tableName)
        if (existingCols.isNotEmpty()) report.tablesSkippedExisting++

        if (existingCols.isEmpty()) {
            // 表不存在：直接用 Room 生成的 createSql（列类型/NOT NULL/DEFAULT/主键 100% 匹配 TableInfo 校验）
            val ddl = entity.createSql.replace(TABLE_NAME_PLACEHOLDER, tableName)
            db.execSQL(ddl)
            report.tablesCreated++
        } else {
            // 表已存在：缺列 ADD COLUMN（SQLite ALTER 只支持 ADD，不支持 DROP/ALTER）
            for (col in entity.columns) {
                if (existingCols.containsKey(col.columnName)) continue
                val alterPart = buildAddColumnDdl(col)
                runCatching {
                    db.execSQL("ALTER TABLE `$tableName` ADD COLUMN $alterPart")
                    report.columnsAdded++
                }.onFailure {
                    val m = "ALTER TABLE $tableName ADD $alterPart failed: ${it.message}"
                    FileLogger.w(TAG, m, it)
                    report.failures.add(m)
                }
            }
        }

        // 索引：用 JSON 里的 createSql（Room 生成，IF NOT EXISTS 幂等，索引名/列序/排序与校验期望一致）
        for (idx in entity.indices) {
            if (idx.createSql.isBlank()) continue
            runCatching {
                db.execSQL(idx.createSql.replace(TABLE_NAME_PLACEHOLDER, tableName))
                report.indexesCreated++
            }.onFailure {
                val m = "CREATE INDEX ${idx.name} failed: ${it.message}"
                FileLogger.w(TAG, m, it)
                report.failures.add(m)
            }
        }
    }

    /**
     * 构造 ADD COLUMN 片段。
     * 实体声明了 defaultValue → 原样使用（Room TableInfo 校验精确比对默认值）；
     * NOT NULL 且未声明默认值 → SQLite 规定必须带非 NULL DEFAULT，用类型安全默认值保证 ALTER 成功
     *   （数据不丢；Room 校验可能因默认值不匹配进入 Funnel 3，但不会崩）。
     */
    private fun buildAddColumnDdl(col: ColumnSchema): String {
        val sb = StringBuilder("`${col.columnName}` ${col.affinity}")
        if (col.notNull) {
            val def = col.defaultValue ?: safeDefaultFor(col.affinity)
            sb.append(" NOT NULL DEFAULT $def")
        } else if (col.defaultValue != null) {
            sb.append(" DEFAULT ${col.defaultValue}")
        }
        return sb.toString()
    }

    private fun safeDefaultFor(affinity: String): String = when (affinity.uppercase(Locale.US)) {
        "INTEGER" -> "0"
        "REAL" -> "0"
        "BLOB" -> "X''"
        else -> "''"
    }

    // ── SQLite 工具 ───────────────────────────────────────────────────────
    private fun readExistingColumns(db: SupportSQLiteDatabase, tableName: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            db.query("PRAGMA table_info(`$tableName`)").use { cur ->
                val nameIdx = cur.getColumnIndex("name")
                val typeIdx = cur.getColumnIndex("type")
                if (nameIdx < 0 || typeIdx < 0) return@use
                while (cur.moveToNext()) {
                    out[cur.getString(nameIdx)] = cur.getString(typeIdx) ?: ""
                }
            }
        }
        return out
    }

    // ── DB-SHIELD-4 (Funnel 4)：灾难前数据库文件备份 ──────────────────────
    fun snapshotDbFileForDisasterRecovery(context: Context, dbFile: File): File? = runCatching {
        val dir = File(context.filesDir, "database_crashes").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "${dbFile.name}_backup_$stamp.db")
        if (!dbFile.exists()) return null

        // ── P1-2 修复：备份前先 WAL checkpoint(TRUNCATE)，把 .wal/.shm 的最新写入合并入主 .db ──
        // 如果 checkpoint 失败（极端情况：Room 崩溃前 DB 正处于不可打开状态），退化为「一起 copy wal/shm」，
        // 保证用户最近聊天/设置不落空（默认 API 28+ Room 都启用 WAL）。
        runCatching {
            val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(config)
            try {
                helper.writableDatabase.use { db ->
                    val cursor = db.query("PRAGMA wal_checkpoint(TRUNCATE);")
                    if (cursor.moveToFirst()) {
                        val busy = cursor.getInt(cursor.getColumnIndexOrThrow("busy"))
                        val log = cursor.getInt(cursor.getColumnIndexOrThrow("log"))
                        val checkpointed = cursor.getInt(cursor.getColumnIndexOrThrow("checkpointed"))
                        FileLogger.i(TAG, "备份前 WAL checkpoint(TRUNCATE): busy=$busy log=$log checkpointed=$checkpointed")
                    }
                }
            } finally {
                helper.close()
            }
        }.onFailure {
            // checkpoint 失败 → 退而求其次拷贝 wal/shm：这样 Room 恢复时会自动加载它们
            FileLogger.w(TAG, "备份前 WAL checkpoint 失败，退化策略：同时 copy .wal 和 .shm 文件：${it.message}")
            val wal = File(dbFile.parent, "${dbFile.name}-wal")
            val shm = File(dbFile.parent, "${dbFile.name}-shm")
            if (wal.exists()) wal.copyTo(File(dir, "${outFile.name}-wal"), overwrite = true)
            if (shm.exists()) shm.copyTo(File(dir, "${outFile.name}-shm"), overwrite = true)
        }

        dbFile.copyTo(outFile, overwrite = true)
        // 只保留最近 5 份备份（每份备份 = .db + 可能的 .wal/.shm，这里按 .db 主文件排序）
        val all = dir.listFiles { f -> f.name.startsWith(dbFile.name + "_backup_") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (all.size > 5) {
            all.drop(5).forEach { backupDb ->
                backupDb.delete()
                // 主文件之外的同组 wal/shm 也一起删，避免留空壳垃圾文件
                File(dir, "${backupDb.name}-wal").takeIf { it.exists() }?.delete()
                File(dir, "${backupDb.name}-shm").takeIf { it.exists() }?.delete()
            }
        }
        FileLogger.i(TAG, "崩溃前 DB 备份 → ${outFile.absolutePath} (${outFile.length()} bytes)")
        outFile
    }.onFailure { FileLogger.w(TAG, "备份崩溃前 DB 失败：${it.message}", it) }.getOrNull()

    // ── 报告 Builder ─────────────────────────────────────────────────────────
    private class RescueReportBuilder {
        var tablesCreated = 0
        var columnsAdded = 0
        var indexesCreated = 0
        var tablesSkippedExisting = 0
        val failures = mutableListOf<String>()
        fun build() = RescueReport(tablesCreated, columnsAdded, indexesCreated, tablesSkippedExisting, failures.toList())
    }
}
