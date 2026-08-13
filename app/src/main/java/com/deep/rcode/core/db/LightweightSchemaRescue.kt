package com.deep.rcode.core.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.deep.rcode.core.db.entity.CredentialEncryptionStateEntity
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity
import com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity
import com.deep.rcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import com.deep.rcode.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.deep.rcode.feature.agent.data.local.entity.SkillStateEntity
import com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity
import com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity
import com.deep.rcode.feature.credentials.data.local.entity.GitCredentialEntity
import com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteAuditLogEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.deep.rcode.feature.workspace.data.local.entity.RemoteMountEntity
import com.deep.rcode.feature.t2i.data.local.entity.T2IProviderEntity
import com.deep.rcode.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.deep.rcode.feature.t2i.data.local.entity.T2ITaskEntity
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DB-SHIELD-4 (Funnel 2): 「轻量抢救」模式（生产级实现 —— 只依赖 JDK java.lang.reflect +
 * AndroidX Room 注解，不引入 kotlin-reflect）。
 *
 * 触发条件：正常 Room 构建（保守迁移）失败 → 说明有 migration 缺失或对不上。
 * 我们不直接 destructive（DROP 老表 → 用户数据没了 + 删库过程中进程被杀=闪退），而是：
 *   1. 用 SQLite 原生 SupportSQLiteOpenHelper 打开旧库（绕过 Room 的 TableInfo 校验）；
 *   2. 对 ALL_ENTITY_CLASSES 每一个用 java.lang.reflect 解析：
 *      a) CREATE TABLE IF NOT EXISTS（幂等）
 *      b) PRAGMA table_info → 对比 Entity 字段；缺列 → ALTER TABLE ADD COLUMN（默认 NULL 安全）；
 *      c) CREATE INDEX IF NOT EXISTS（@Entity(indices=[...])）
 *   3. PRAGMA user_version = SCHEMA_VERSION。
 *
 * 语义：老表（chat_sessions / agent_messages 等用户历史数据）100% 保留，即使它的某一列
 *   和新 Entity 对不上也不 DROP，留着 Room 再做二次校验（最坏情况进入 Funnel 3）。
 */
object LightweightSchemaRescue {

    private const val TAG = "LightweightSchemaRescue"

    data class RescueReport(
        val tablesCreated: Int,
        val columnsAdded: Int,
        val indexesCreated: Int,
        val tablesSkippedExisting: Int,
        val failures: List<String>
    )

    /**
     * ALL_ENTITY_CLASSES：与 AgentDatabase.kt @Database(entities=[...]) 一一对应（18 项顺序一致）。
     * 加表/删表时必须同步更新；DbSCHIELDPreflightTest.ENTITY-COUNT-TEST CI 闸门强制校验。
     *
     * 注意：@Database entities[6] = com.deep.rcode.feature.credentials.data.local.entity.GitCredentialEntity
     *   （旧版 RC61 的 agent 包同名类在 RC62 credentials 模块拆分时已删除，现在真正被 Room
     *   注册的是 credentials 包的 GitCredentialEntity，AgentDatabase.kt L29 的 import 指向它）
     */
    val ALL_ENTITY_CLASSES: List<Class<*>> = listOf<Class<*>>(
        AgentMessageEntity::class.java,
        ChatSessionEntity::class.java,
        AIProviderEntity::class.java,
        RemoteConnectionEntity::class.java,
        RemoteMountEntity::class.java,
        TodoItemEntity::class.java,
        // 注意：@Database entities[6] = GitCredentialEntity（credentials.data.local.entity 包）
        //   因 agent.data.local.entity 子包的同名 GitCredentialEntity 在 RC62 模块拆分时已删除，
        //   AgentDatabase.kt L29 import 指向 credentials 包的类，这里必须一致。
        GitCredentialEntity::class.java,
        CheckpointEntity::class.java,
        CheckpointFileSnapshotEntity::class.java,
        CredentialEncryptionStateEntity::class.java,
        RemoteAuditLogEntity::class.java,
        ModelCapabilityOverrideEntity::class.java,
        UserConfirmedSentinelEntity::class.java,
        HallucinationFuseEntity::class.java,
        SentinelPlanRejectionAuditEntity::class.java,
        HardConstraintDeleteAuditEntity::class.java,
        L0SoftCompactRestoreLogEntity::class.java,
        ZthTelemetryEventEntity::class.java,
        // ══ RC69 T2I 新增：同步 AgentDatabase.kt @Database entities[18..20] ══
        T2IProviderEntity::class.java,
        T2IProviderModelEntity::class.java,
        T2ITaskEntity::class.java,
        // ══ RC74 Skill 新增：同步 AgentDatabase.kt @Database entities[21]（skill_state）══
        SkillStateEntity::class.java
    )

    fun rescue(context: Context, dbFile: File, dbVersion: Int): RescueReport {
        val reportBuilder = RescueReportBuilder()
        runCatching {
            val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(dbVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        doRescueOnDb(db, reportBuilder)
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

    private fun doRescueOnDb(db: SupportSQLiteDatabase, report: RescueReportBuilder) {
        db.beginTransaction()
        try {
            // 1) 迁移历史表先创建（保证后续 FileMigration 可继续写）
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, script_name TEXT, executed_at INTEGER)"
            )
            for (entityClass in ALL_ENTITY_CLASSES) {
                runCatching {
                    rescueOneEntity(db, entityClass, report)
                }.onFailure {
                    val m = "rescueOneEntity(${entityClass.simpleName}) failed: ${it.message}"
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
        entityClass: Class<*>,
        report: RescueReportBuilder
    ) {
        val entityAnn = entityClass.getAnnotation(Entity::class.java)
            ?: error("Class ${entityClass.simpleName} 缺少 @Entity 注解（ALL_ENTITY_CLASSES 清单错误？）")
        val tableName = entityAnn.tableName.ifBlank { entityClass.simpleName }

        val existingCols = readExistingColumns(db, tableName)
        if (existingCols.isNotEmpty()) report.tablesSkippedExisting++

        // 1) 解析字段（按声明顺序）、主键列、是否 autoGenerate
        val allFields = entityClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.name.contains("Companion") }
            .associateBy { it.name }

        // 2) 找主键列与 autoGenerate
        // RC67 P1-6 修复：新增解析 @Entity(primaryKeys=[...]) 复合主键；
        //   · 单主键字段 @PrimaryKey 继续正常支持
        //   · @Entity(primaryKeys=[a,b]) 复合主键：我们无法在列上逐个声明 PRIMARY KEY（SQLite 每张表只能 1 次 PRIMARY KEY 约束），
        //     所以退化成「建表时省略 PRIMARY KEY 约束，但把复合主键列在 ADD COLUMN 时标为 NOT NULL」，
        //     并向 RescueReport.failures 写入告警 —— Room 仍会因为 PRIMARY KEY 缺失导致 TableInfo 校验失败，
        //     但至少 Funnel 2/3 的保守 destructive 兜底还能继续，不会直接崩到 Funnel 4 全删。
        val pkSpec = findPrimaryKeySpec(
            entityAnn = entityAnn,
            fields = allFields.values,
            tableName = tableName,
            report = report
        )
        val pkColumnName: String? = pkSpec.singlePkColumnName
        val pkAutoGenerate: Boolean = pkSpec.singlePkAutoGenerate
        val compositePkColumns: List<String> = pkSpec.compositePkColumns

        // 3) 生成 (sql fragment, columnName) 列表，按字段名稳定排序
        val columnSpecs: List<Pair<Pair<String, String>, Unit>> = allFields.keys.sorted().map { fieldName ->
            val f = allFields[fieldName]!!
            val cname = fieldName  // 当前项目所有 Entity 不使用 @ColumnInfo(name=...)，直接字段名=列名
            val ctype = sqlTypeFor(f.type)
            val isSinglePk = (cname == pkColumnName)
            val isCompositePkPart = (cname in compositePkColumns)
            // RC91 SCHEMA 42 修复：解析 @ColumnInfo(defaultValue)，让 Funnel 2 抢救建出的表
            // 与 Room TableInfo 校验期望的 DEFAULT 完全一致（此前缺 DEFAULT → 校验失败 → 误入 Funnel 3 删表）。
            val defaultValue = f.getAnnotation(ColumnInfo::class.java)
                ?.takeIf { it.defaultValue != ColumnInfo.VALUE_UNSPECIFIED }
                ?.defaultValue
            // RC67 P1-6 补充：复合主键列 SQLite 规定强制 NOT NULL（即使是 String 等非 primitive 类型也不能 NULL）
            val primitiveForceNotNull = !f.type.isPrimitive
            val nullable: Boolean = if (isCompositePkPart) false else primitiveForceNotNull
            val frag = buildString {
                append("`$cname` ")
                append(ctype)
                if (isSinglePk) {
                    append(" PRIMARY KEY")
                    if (pkAutoGenerate && ctype == "INTEGER") append(" AUTOINCREMENT")
                }
                if (!nullable && !isSinglePk) append(" NOT NULL")
                if (defaultValue != null) append(" DEFAULT $defaultValue")
            }
            (frag to cname) to Unit  // Pair 结构兼容旧循环取值 (it.first.first=frag, it.first.second=cname)
        }

        // 4) CREATE TABLE IF NOT EXISTS（表不存在时，一次性建好所有列 + 表级复合主键约束）
        if (existingCols.isEmpty()) {
            val columnsClause = columnSpecs.joinToString(", ") { it.first.first }
            // RC67 P1-6 复合主键：如果 @Entity(primaryKeys=[...])，在所有列定义后面追加 ", PRIMARY KEY (c1,c2,...)"
            val pkClause: String = if (compositePkColumns.isNotEmpty()) {
                ", PRIMARY KEY (" + compositePkColumns.joinToString(", ") { "`$it`" } + ")"
            } else ""
            val ddl = "CREATE TABLE IF NOT EXISTS `$tableName` ($columnsClause$pkClause)"
            db.execSQL(ddl)
            report.tablesCreated++
        } else {
            // 5) 表已存在：缺列 ADD COLUMN（注意：ALTER TABLE SQLite 只能 ADD，不支持 DROP/ALTER）
            for (spec in columnSpecs) {
                val cname = spec.first.second
                if (existingCols.containsKey(cname)) continue
                val f = allFields[cname]!!
                val ctype = sqlTypeFor(f.type)
                val isCompositePkPart = (cname in compositePkColumns)
                val nullable = if (isCompositePkPart) false else !f.type.isPrimitive
                // RC91 SCHEMA 42 修复：ADD COLUMN 同样带上 @ColumnInfo(defaultValue)。
                // SQLite 规定「NOT NULL 的 ADD COLUMN 必须带非 NULL DEFAULT」，否则 ALTER 直接失败；
                // 且缺 DEFAULT 会导致 Room TableInfo 校验失败。
                val defaultValue = f.getAnnotation(ColumnInfo::class.java)
                    ?.takeIf { it.defaultValue != ColumnInfo.VALUE_UNSPECIFIED }
                    ?.defaultValue
                val alterPart = buildString {
                    append("`$cname` $ctype")
                    if (!nullable) append(" NOT NULL")
                    if (defaultValue != null) append(" DEFAULT $defaultValue")
                }
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

        // 6) 索引（@Entity indices）
        // RC91 SCHEMA 42 修复：
        //   · 索引名对齐 Room 约定 index_<table>_<cols>（旧版误用 idx_ 前缀，Room TableInfo 校验
        //     会因索引名不匹配失败 → 误入 Funnel 3 删表）；
        //   · 索引列带 ASC/DESC 排序（@Index(orders=[...])），Room 校验会比对每个列的排序方向；
        //   · 先清理旧版 rescue 遗留的 idx_ 前缀索引（DROP INDEX IF EXISTS），避免残留索引再次触发校验失败。
        val indices: Array<Index> = entityAnn.indices
        if (indices.isNotEmpty()) {
            runCatching {
                db.query("PRAGMA index_list(`$tableName`)").use { cur ->
                    val nameIdx = cur.getColumnIndex("name")
                    if (nameIdx >= 0) {
                        while (cur.moveToNext()) {
                            val name = cur.getString(nameIdx)
                            if (name.startsWith("idx_${tableName}_")) {
                                db.execSQL("DROP INDEX IF EXISTS `$name`")
                            }
                        }
                    }
                }
            }.onFailure {
                val m = "清理旧版 idx_ 遗留索引失败（非致命）: ${it.message}"
                FileLogger.w(TAG, m, it)
            }
            for (idx in indices) {
                val idxName = indexNameFor(tableName, idx)
                val cols = idx.value.mapIndexed { i, c ->
                    val order = idx.orders.getOrNull(i) ?: Index.Order.ASC
                    "`$c`" + if (order == Index.Order.DESC) " DESC" else " ASC"
                }.joinToString(", ")
                val uniquePart = if (idx.unique) " UNIQUE " else " "
                runCatching {
                    db.execSQL("CREATE${uniquePart}INDEX IF NOT EXISTS `$idxName` ON `$tableName` ($cols)")
                    report.indexesCreated++
                }.onFailure {
                    val m = "CREATE INDEX $idxName failed: ${it.message}"
                    FileLogger.w(TAG, m, it)
                    report.failures.add(m)
                }
            }
        }
    }

    // ── 反射工具（java.lang.reflect 原生）────────────────────────────────────
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

    // RC67 P1-6: 主键解析的返回结构（单主键 / 复合主键互斥）
    private data class PkSpec(
        val singlePkColumnName: String?,   // 非 null 表示使用字段级 @PrimaryKey
        val singlePkAutoGenerate: Boolean, // 仅当 singlePkColumnName != null 时有效
        val compositePkColumns: List<String> // 非空表示使用 @Entity(primaryKeys=[...]) 复合主键
    )

    private fun findPrimaryKeySpec(
        entityAnn: Entity,
        fields: Collection<Field>,
        tableName: String,
        report: RescueReportBuilder
    ): PkSpec {
        // 1) 优先识别 @Entity(primaryKeys=[...]) 复合主键（Room 官方支持多列主键）
        val composite: Array<String> = entityAnn.primaryKeys
        if (composite.isNotEmpty()) {
            val cols = composite.toList()
            val msg = ("Composite PK on `$tableName` (${cols.joinToString()}): " +
                    "Funnel2 会强制所有 PK 列 NOT NULL 并添加表级 PRIMARY KEY 约束；" +
                    "但如果老表已存在且缺少这些 PK 列，SQLite ALTER TABLE ADD COLUMN 无法追加 PRIMARY KEY 约束，" +
                    "Room TableInfo 校验可能仍失败 → 会进入 Funnel3 保守 destructive。")
            FileLogger.w(TAG, msg)
            report.failures.add(msg)
            return PkSpec(singlePkColumnName = null, singlePkAutoGenerate = false, compositePkColumns = cols)
        }
        // 2) 退回字段级 @PrimaryKey（单主键）
        for (f in fields) {
            val a = f.getAnnotation(PrimaryKey::class.java) ?: continue
            return PkSpec(
                singlePkColumnName = f.name,
                singlePkAutoGenerate = a.autoGenerate,
                compositePkColumns = emptyList()
            )
        }
        // 3) 都没有 → 理论上 Room 编译期就会报 @Entity 缺主键错误，
        //    这里给个 warn，继续无主键走（最坏也是 Funnel3 兜底）
        report.failures.add("Table `$tableName`: 既没有字段 @PrimaryKey，也没有 @Entity(primaryKeys=[...])，跳过主键抢救")
        return PkSpec(null, false, emptyList())
    }

    private fun sqlTypeFor(javaType: Class<*>): String = when {
        javaType == String::class.java -> "TEXT"
        javaType == Int::class.javaPrimitiveType || javaType == Int::class.javaObjectType -> "INTEGER"
        javaType == Long::class.javaPrimitiveType || javaType == Long::class.javaObjectType -> "INTEGER"
        javaType == Short::class.javaPrimitiveType || javaType == Short::class.javaObjectType -> "INTEGER"
        javaType == Byte::class.javaPrimitiveType || javaType == Byte::class.javaObjectType -> "INTEGER"
        javaType == Boolean::class.javaPrimitiveType || javaType == Boolean::class.javaObjectType -> "INTEGER"
        javaType == Float::class.javaPrimitiveType || javaType == Float::class.javaObjectType -> "REAL"
        javaType == Double::class.javaPrimitiveType || javaType == Double::class.javaObjectType -> "REAL"
        javaType == ByteArray::class.java -> "BLOB"
        else -> "TEXT"  // 枚举（存 .name）一律 TEXT
    }

    private fun indexNameFor(tableName: String, index: Index): String {
        // RC91 SCHEMA 42 修复：Room 生成的索引名统一为 index_<table>_<col1>_<col2>
        // （唯一索引同样如此，只是 CREATE UNIQUE INDEX 而非名字带后缀）。
        // 旧版返回 idx_<table>_<cols>[_unique] 与 Room 期望不符，会导致 TableInfo 校验失败。
        return "index_${tableName}_${index.value.joinToString("_")}"
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
