package com.deep.rcode.core.db

import android.content.Context
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.local.database.AgentDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * DB-SHIELD-4 (Funnel 2): 「轻量抢救」模式。
 *
 * 触发条件：正常 Room 构建（保守迁移）失败 → 说明有 migration 缺失或对不上。
 * 我们不直接 destructive（DROP 老表 → 用户数据没了 + 删库过程中进程被杀=闪退），
 * 而是：
 *   1. 用 SQLite 原生 SupportSQLiteOpenHelper 打开旧库（绕过 Room 的 TableInfo 校验）；
 *   2. 对 AgentDatabase.ALL_ENTITY_CLASSES 每一个反射：
 *      a) CREATE TABLE IF NOT EXISTS（不会覆盖老数据，幂等）；
 *      b) PRAGMA table_info(表) → 对比 Entity 列；缺列 → ALTER TABLE ADD COLUMN（默认 NULL 安全）；
 *      c) CREATE INDEX IF NOT EXISTS（@Entity indices）。
 *   3. 最后 PRAGMA user_version = SCHEMA_VERSION。
 *
 * 语义：
 *   - 老表（chat_sessions / agent_messages 等用户历史数据）100% 保留，即使它的某一列
 *     和新 Entity 对不上也不 DROP，留着 Room 再做一次二次校验（最坏情况进入 Funnel 3）。
 *   - 新表（如 model_capability_overrides / zth_sentinels）自动创建。
 *   - 所有操作幂等（IF NOT EXISTS），可反复重入。
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
     * AgentDatabase.ALL_ENTITY_CLASSES：手动从 @Database(entities=[...]) 拷贝同步。
     * 将来加表时必须同步追加；Phase C PreflightDbSchemaCompareTest CI 会校验这里数量
     * 和 entities=[] 数组长度一致（不一致直接 FAIL，防漏更新）。
     */
    val ALL_ENTITY_CLASSES: List<KClass<*>> = listOf(
        com.deep.rcode.feature.agent.data.local.entity.AgentMessageEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.ChatSessionEntity::class,
        com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.RemoteConnectionEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.RemoteMountEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.TodoItemEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.GitCredentialEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.CheckpointEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.CredentialEncryptionStateEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.RemoteAuditLogEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity::class,
        com.deep.rcode.feature.agent.data.local.entity.ZthTelemetryEventEntity::class
    )

    fun rescue(context: Context, dbFile: File, dbVersion: Int): RescueReport {
        val reportBuilder = RescueReportBuilder()
        runCatching {
            val openConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)      // 注意：FrameworkSQLite 直接用路径
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(dbVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        doRescueOnDb(db, reportBuilder)
                        // 最后强制写 user_version 到 SCHEMA_VERSION
                        db.execSQL("PRAGMA user_version = $dbVersion")
                        FileLogger.i(TAG, "轻量抢救结束后 user_version=${dbVersion} " +
                                "created=${reportBuilder.tablesCreated} +cols=${reportBuilder.columnsAdded} " +
                                "+idxs=${reportBuilder.indexesCreated} skipped=${reportBuilder.tablesSkippedExisting}")
                    }
                })
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(openConfig)
            // 触发 onOpen（在 writableDatabase 打开时调用）
            helper.writableDatabase.use { /* no-op，onOpen 已经 doRescueOnDb */ }
        }.onFailure {
            FileLogger.e(TAG, "LightweightSchemaRescue 轻量抢救阶段抛异常", it)
            reportBuilder.failures.add("LightweightSchemaRescue overall failed: ${it.message}")
        }
        return reportBuilder.build()
    }

    // ── 实际抢救逻辑（跑在 SQLite 的 onOpen 回调里，保证事务安全）────────────────
    private fun doRescueOnDb(db: SupportSQLiteDatabase, report: RescueReportBuilder) {
        db.beginTransaction()
        try {
            // 1) 迁移历史表先创建（保证即使 FileMigration 后续也能继续跑）
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

    private fun rescueOneEntity(
        db: SupportSQLiteDatabase,
        entityClass: KClass<*>,
        report: RescueReportBuilder
    ) {
        val entityAnn = entityClass.findAnnotation<Entity>() ?: run {
            FileLogger.w(TAG, "Class ${entityClass.simpleName} 缺少 @Entity，跳过")
            return
        }
        val tableName = entityAnn.tableName.ifBlank { entityClass.simpleName!! }

        // 检查该表是否已经存在（PRAGMA table_info 返回空 = 不存在）
        val existingCols = readExistingColumns(db, tableName)
        if (existingCols.isNotEmpty()) {
            report.tablesSkippedExisting++
        }

        // 2a) 列信息解析：memberProperties + @PrimaryKey/@ColumnInfo(缺省用属性名)
        val pkProp = findPrimaryKeyProperty(entityClass)
        val pkAutoGenerate = pkProp?.javaField
            ?.getAnnotation(PrimaryKey::class.java)
            ?.autoGenerate == true
        val pkColumnName = pkProp?.let { columnNameFor(it) }
        val columnFragments = mutableListOf<String>()
        for (prop in entityClass.memberProperties) {
            val cname = columnNameFor(prop)
            val isPk = cname == pkColumnName
            val ctype = sqlTypeFor(prop)
            val nullable = prop.returnType.isMarkedNullable
            val frag = buildString {
                append(cname)
                append(' ')
                append(ctype)
                if (isPk) {
                    append(" PRIMARY KEY")
                    if (pkAutoGenerate && ctype == "INTEGER") append(" AUTOINCREMENT")
                }
                if (!nullable && !isPk) append(" NOT NULL")
            }
            columnFragments.add(frag to cname)
        }

        // 2b) CREATE TABLE IF NOT EXISTS（表不存在时走，所有列全部正确）
        if (existingCols.isEmpty()) {
            val ddl = "CREATE TABLE IF NOT EXISTS `$tableName` (" +
                    columnFragments.joinToString(", ") { it.first } + ")"
            db.execSQL(ddl)
            report.tablesCreated++
        } else {
            // 2c) 表已存在：对比 existingCols vs Entity 列；缺列 → ALTER TABLE ADD COLUMN
            for ((frag, cname) in columnFragments) {
                if (existingCols.containsKey(cname)) continue
                // ALTER TABLE 语法：只需要 <name> <type> [NOT NULL]，不重复 PRIMARY KEY/AUTOINCREMENT
                val ctype = sqlTypeFor(findPropertyByName(entityClass, cname)!!)
                val nullable = findPropertyByName(entityClass, cname)!!.returnType.isMarkedNullable
                val alterPart = "$cname $ctype" + if (!nullable) " NOT NULL" else ""
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

        // 3) 索引（@Entity(indices=[...])）全部 IF NOT EXISTS
        val indices = entityAnn.indices
        if (indices.isNotEmpty()) {
            for (idx in indices) {
                val idxName = indexNameFor(tableName, idx)
                val cols = idx.value.joinToString(", ") { "`$it`" }
                val unique = if (idx.unique) " UNIQUE " else " "
                runCatching {
                    db.execSQL("CREATE${unique}INDEX IF NOT EXISTS `$idxName` ON `$tableName` ($cols)")
                    report.indexesCreated++
                }.onFailure {
                    val m = "CREATE INDEX $idxName failed: ${it.message}"
                    FileLogger.w(TAG, m, it)
                    report.failures.add(m)
                }
            }
        }
    }

    // ── 反射工具方法 ─────────────────────────────────────────────────
    private fun readExistingColumns(db: SupportSQLiteDatabase, tableName: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        runCatching {
            db.query("PRAGMA table_info(`$tableName`)").use { cur ->
                val nameIdx = cur.getColumnIndex("name")
                val typeIdx = cur.getColumnIndex("type")
                if (nameIdx < 0 || typeIdx < 0) return@use
                while (cur.moveToNext()) {
                    val n = cur.getString(nameIdx)
                    val t = cur.getString(typeIdx) ?: ""
                    out[n] = t
                }
            }
        }
        return out
    }

    private fun findPrimaryKeyProperty(entityClass: KClass<*>): KProperty1<*, *>? {
        for (prop in entityClass.memberProperties) {
            val jf = prop.javaField ?: continue
            if (jf.isAnnotationPresent(PrimaryKey::class.java)) return prop
        }
        // @Entity(primaryKeys=[...]) 形式暂未在当前项目使用，直接返回 null
        return null
    }

    private fun findPropertyByName(entityClass: KClass<*>, name: String): KProperty1<*, *>? =
        entityClass.memberProperties.firstOrNull { columnNameFor(it) == name }

    private fun columnNameFor(prop: KProperty1<*, *>): String {
        // 当前项目实体都不写 @ColumnInfo，直接用属性名
        // 将来加 @ColumnInfo(name = "xxx") 时在这里读注解优先返回即可
        // val a = prop.javaField?.getAnnotation(ColumnInfo::class.java)?.name; if (a!=null) return a
        return prop.name
    }

    private fun sqlTypeFor(prop: KProperty1<*, *>): String = when (val cls =
        prop.returnType.classifier as? KClass<*> ?: return "TEXT") {
        String::class -> "TEXT"
        Int::class, Long::class, Short::class, Byte::class, Boolean::class -> "INTEGER"
        Float::class, Double::class -> "REAL"
        ByteArray::class -> "BLOB"
        else -> "TEXT"  // 所有枚举（存 .name）都映射 TEXT
    }

    private fun indexNameFor(tableName: String, index: Index): String {
        val base = "idx_${tableName}_${index.value.joinToString("_")}"
        return if (index.unique) "${base}_unique" else base
    }

    // ── DB-SHIELD-4 (Funnel 4)：数据库文件备份 ──────────────────────
    fun snapshotDbFileForDisasterRecovery(
        context: Context,
        dbFile: File
    ): File? = runCatching {
        val dir = File(context.filesDir, "database_crashes").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "${dbFile.name}_backup_$stamp.db")
        if (!dbFile.exists()) return null
        dbFile.copyTo(outFile, overwrite = true)
        // 保留最近 5 份
        val all = dir.listFiles { f -> f.name.startsWith(dbFile.name + "_backup_") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (all.size > 5) {
            all.drop(5).forEach { it.delete() }
        }
        FileLogger.i(TAG, "崩溃前自动备份 ${dbFile.name} → ${outFile.absolutePath} (${outFile.length()} bytes)")
        outFile
    }.onFailure {
        FileLogger.w(TAG, "备份崩溃前 DB 失败：${it.message}", it)
    }.getOrNull()

    // ── 辅助类：抢救报告 Builder ──────────────────────────────────────
    private class RescueReportBuilder {
        var tablesCreated = 0
        var columnsAdded = 0
        var indexesCreated = 0
        var tablesSkippedExisting = 0
        val failures = mutableListOf<String>()
        fun build() = RescueReport(
            tablesCreated = tablesCreated,
            columnsAdded = columnsAdded,
            indexesCreated = indexesCreated,
            tablesSkippedExisting = tablesSkippedExisting,
            failures = failures.toList()
        )
    }
}
