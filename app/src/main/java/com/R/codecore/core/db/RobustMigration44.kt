package com.R.codecore.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.R.codecore.core.util.FileLogger

/**
 * RC94 程序化迁移 v43→v44：修复线上两类 Room 崩溃，彻底解决「迁移 42 引用 createdAt 列」的隐患。
 *
 * ── 背景 ─────────────────────────────────────────────────────────────
 * 1) 新 schema 设备（RC91+ 全新安装，session_checkpoints 由实体直接建出 createdAtMs 列）升级时，
 *    文件迁移 42 固定执行 `SELECT ... createdAt FROM session_checkpoints`，SQLite 在 prepare 阶段
 *    报 `no such column: createdAt` → Funnel 1 失败 → Funnel 2（LightweightSchemaRescue 反射注解
 *    在 Release/R8 下丢失 @Entity）→ Funnel 3/4 连锁全崩 → 应用闪退。
 * 2) 已崩溃设备被 LightweightSchemaRescue 把 `PRAGMA user_version` 提前置为 43，但迁移 42/43 从未
 *    真正执行，其 schema 停留在 v41 状态 → 升级到 v44 时 Room 只跑 43→44，必须在此把迁移 42/43
 *    该修的 5 张表全部补齐，否则 onPostMigrate 的 TableInfo 校验失败。
 *
 * ── 方案 ─────────────────────────────────────────────────────────────
 * 用 Kotlin + `PRAGMA table_info` 探测每张表实际结构，幂等地把表重建为与 @Entity 完全一致：
 *   · session_checkpoints      ：createdAt → createdAtMs（旧 schema 才需要重建）
 *   · model_capability_overrides：单主键 id → 复合主键 (providerType, modelId)
 *   · zth_hallucination_fuses  ：单主键 id + UNIQUE 索引 → 复合主键 (scope, scopeId)
 *   · remote_audit_logs        ：id 补 NOT NULL（AUTOINCREMENT 主键）
 *   · zth_telemetry_events     ：id 补 NOT NULL（AUTOINCREMENT 主键）
 * 所有重建均采用「CREATE NEW → INSERT OR IGNORE 回拷 → DROP IF EXISTS → RENAME」幂等四步法，
 * 中途被 SIGKILL 重跑也不会崩；新 schema 表则直接跳过，零开销。
 */
object RobustMigration44 {

    private const val TAG = "RobustMigration44"

    /** 程序化迁移版本集合（供 DbSCHIELDPreflightTest GAP-TEST 豁免文件缺失校验）。 */
    val PROGRAMMATIC_MIGRATION_VERSIONS: Set<Int> = setOf(44)

    val MIGRATION_43_44: Migration = object : Migration(43, 44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fixSessionCheckpoints(db)
            fixModelCapabilityOverrides(db)
            fixZthHallucinationFuses(db)
            fixRemoteAuditLogs(db)
            fixZthTelemetryEvents(db)
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────
    private data class ColInfo(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
            return c.moveToFirst()
        }
    }

    private fun tableInfo(db: SupportSQLiteDatabase, table: String): List<ColInfo> {
        val result = mutableListOf<ColInfo>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                result.add(
                    ColInfo(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) != 0,
                        pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                    )
                )
            }
        }
        return result
    }

    private fun pkColumns(info: List<ColInfo>): List<String> =
        info.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }

    // ── 1/5 session_checkpoints：createdAt → createdAtMs ──────────────
    private fun fixSessionCheckpoints(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "session_checkpoints")) return
        val cols = tableInfo(db, "session_checkpoints").map { it.name }
        if ("createdAtMs" in cols) {
            // 新 schema：表结构已正确，只需确保索引存在
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_session_checkpoints_sessionId ON session_checkpoints(sessionId)"
            )
            FileLogger.i(TAG, "session_checkpoints 已是 createdAtMs schema，跳过重建")
            return
        }
        if ("createdAt" in cols) {
            // 旧 schema：重建为 createdAtMs 并保留数据
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS session_checkpoints_new (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "sessionId TEXT NOT NULL, " +
                        "userMessageId TEXT NOT NULL, " +
                        "promptSnippet TEXT NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO session_checkpoints_new " +
                        "(id, sessionId, userMessageId, promptSnippet, createdAtMs) " +
                        "SELECT id, sessionId, userMessageId, promptSnippet, createdAt FROM session_checkpoints"
            )
            db.execSQL("DROP TABLE IF EXISTS session_checkpoints")
            db.execSQL("ALTER TABLE session_checkpoints_new RENAME TO session_checkpoints")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_session_checkpoints_sessionId ON session_checkpoints(sessionId)"
            )
            FileLogger.i(TAG, "session_checkpoints 旧 schema（createdAt）→ 已重建为 createdAtMs")
        } else {
            FileLogger.w(TAG, "session_checkpoints 列名异常，跳过重建: $cols")
        }
    }

    // ── 2/5 model_capability_overrides：单主键 id → 复合主键 ─────────
    private fun fixModelCapabilityOverrides(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "model_capability_overrides")) return
        val info = tableInfo(db, "model_capability_overrides")
        if (pkColumns(info) == listOf("providerType", "modelId")) {
            FileLogger.i(TAG, "model_capability_overrides 已是复合主键，跳过重建")
            return
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS model_capability_overrides_new (" +
                    "id TEXT NOT NULL, " +
                    "providerType TEXT NOT NULL, " +
                    "modelId TEXT NOT NULL, " +
                    "overrideVision INTEGER, " +
                    "overrideTools INTEGER, " +
                    "overrideReasoning INTEGER, " +
                    "updatedAtMs INTEGER NOT NULL, " +
                    "PRIMARY KEY (providerType, modelId))"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO model_capability_overrides_new " +
                    "(id, providerType, modelId, overrideVision, overrideTools, overrideReasoning, updatedAtMs) " +
                    "SELECT id, providerType, modelId, overrideVision, overrideTools, overrideReasoning, updatedAtMs " +
                    "FROM model_capability_overrides"
        )
        db.execSQL("DROP TABLE IF EXISTS model_capability_overrides")
        db.execSQL("ALTER TABLE model_capability_overrides_new RENAME TO model_capability_overrides")
        FileLogger.i(TAG, "model_capability_overrides → 已重建为复合主键 (providerType, modelId)")
    }

    // ── 3/5 zth_hallucination_fuses：单主键 id → 复合主键 ────────────
    private fun fixZthHallucinationFuses(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "zth_hallucination_fuses")) return
        val info = tableInfo(db, "zth_hallucination_fuses")
        if (pkColumns(info) == listOf("scope", "scopeId")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_state ON zth_hallucination_fuses(state)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_updatedAtMs ON zth_hallucination_fuses(updatedAtMs)"
            )
            FileLogger.i(TAG, "zth_hallucination_fuses 已是复合主键，跳过重建")
            return
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS zth_hallucination_fuses_new (" +
                    "id TEXT NOT NULL, " +
                    "scope TEXT NOT NULL, " +
                    "scopeId TEXT NOT NULL, " +
                    "state TEXT NOT NULL, " +
                    "linkageVersion INTEGER NOT NULL, " +
                    "failureCount INTEGER NOT NULL, " +
                    "openSinceMs INTEGER NOT NULL, " +
                    "lastProbeAtMs INTEGER NOT NULL, " +
                    "killSwitch1Triggered INTEGER NOT NULL, " +
                    "killSwitch2SoftDisabled INTEGER NOT NULL, " +
                    "lastTripSubclass TEXT, " +
                    "updatedAtMs INTEGER NOT NULL, " +
                    "PRIMARY KEY (scope, scopeId))"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO zth_hallucination_fuses_new " +
                    "(id, scope, scopeId, state, linkageVersion, failureCount, openSinceMs, lastProbeAtMs, " +
                    "killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs) " +
                    "SELECT id, scope, scopeId, state, linkageVersion, failureCount, openSinceMs, lastProbeAtMs, " +
                    "killSwitch1Triggered, killSwitch2SoftDisabled, lastTripSubclass, updatedAtMs " +
                    "FROM zth_hallucination_fuses"
        )
        db.execSQL("DROP TABLE IF EXISTS zth_hallucination_fuses")
        db.execSQL("ALTER TABLE zth_hallucination_fuses_new RENAME TO zth_hallucination_fuses")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_state ON zth_hallucination_fuses(state)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_zth_hallucination_fuses_updatedAtMs ON zth_hallucination_fuses(updatedAtMs)"
        )
        FileLogger.i(TAG, "zth_hallucination_fuses → 已重建为复合主键 (scope, scopeId)")
    }

    // ── 4/5 remote_audit_logs：id 补 NOT NULL ─────────────────────────
    private fun fixRemoteAuditLogs(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "remote_audit_logs")) return
        val info = tableInfo(db, "remote_audit_logs")
        val idCol = info.firstOrNull { it.name == "id" }
        if (idCol?.notNull == true) {
            FileLogger.i(TAG, "remote_audit_logs.id 已是 NOT NULL，跳过重建")
            return
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS remote_audit_logs_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "action TEXT NOT NULL, " +
                    "connectionId TEXT, " +
                    "connectionName TEXT, " +
                    "remoteHost TEXT, " +
                    "success INTEGER NOT NULL, " +
                    "message TEXT, " +
                    "sourceIp TEXT, " +
                    "createdAt INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO remote_audit_logs_new " +
                    "(id, category, action, connectionId, connectionName, remoteHost, success, message, sourceIp, createdAt) " +
                    "SELECT id, category, action, connectionId, connectionName, remoteHost, success, message, sourceIp, createdAt " +
                    "FROM remote_audit_logs"
        )
        db.execSQL("DROP TABLE IF EXISTS remote_audit_logs")
        db.execSQL("ALTER TABLE remote_audit_logs_new RENAME TO remote_audit_logs")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_audit_logs_createdAt ON remote_audit_logs(createdAt)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_audit_logs_category ON remote_audit_logs(category)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_remote_audit_logs_connectionId ON remote_audit_logs(connectionId)"
        )
        FileLogger.i(TAG, "remote_audit_logs → id 已补 NOT NULL")
    }

    // ── 5/5 zth_telemetry_events：id 补 NOT NULL ─────────────────────
    private fun fixZthTelemetryEvents(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "zth_telemetry_events")) return
        val info = tableInfo(db, "zth_telemetry_events")
        val idCol = info.firstOrNull { it.name == "id" }
        if (idCol?.notNull == true) {
            FileLogger.i(TAG, "zth_telemetry_events.id 已是 NOT NULL，跳过重建")
            return
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS zth_telemetry_events_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "eventKind TEXT NOT NULL, " +
                    "eventSubKind TEXT NOT NULL, " +
                    "severityTier INTEGER NOT NULL, " +
                    "sessionSha256Prefix TEXT, " +
                    "latencyMs INTEGER, " +
                    "flagA INTEGER, " +
                    "flagB INTEGER, " +
                    "metricA INTEGER, " +
                    "metricB INTEGER, " +
                    "createdAtMs INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO zth_telemetry_events_new " +
                    "(id, eventKind, eventSubKind, severityTier, sessionSha256Prefix, latencyMs, flagA, flagB, metricA, metricB, createdAtMs) " +
                    "SELECT id, eventKind, eventSubKind, severityTier, sessionSha256Prefix, latencyMs, flagA, flagB, metricA, metricB, createdAtMs " +
                    "FROM zth_telemetry_events"
        )
        db.execSQL("DROP TABLE IF EXISTS zth_telemetry_events")
        db.execSQL("ALTER TABLE zth_telemetry_events_new RENAME TO zth_telemetry_events")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_eventKind_eventSubKind ON zth_telemetry_events(eventKind, eventSubKind)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_createdAtMs ON zth_telemetry_events(createdAtMs)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_zth_telemetry_events_severityTier ON zth_telemetry_events(severityTier)"
        )
        FileLogger.i(TAG, "zth_telemetry_events → id 已补 NOT NULL")
    }
}
