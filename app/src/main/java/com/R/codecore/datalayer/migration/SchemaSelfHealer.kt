package com.R.codecore.datalayer.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * 幂等「结构自愈」器（MySQLite 数据保护 §5.7）。
 *
 * 背景：历史库存在「schema 版本已递增 / 版本号相同但表结构不一致」的坑
 * （见 [MigrationEngine] 里 v0.5.0-rc1 同款事故：加列/改列却未同步新增 .sqm，
 * 导致 `user_version == target` 命中 no-op，旧表缺列残留、首查即崩）。
 * 典型故障：`no such column: agent_message.id`。
 *
 * 本器通过 PRAGMA table_info 做「列存在性校验」，缺缺必要列时执行 SQLite 无损重建
 * （改名旧表 → 建新表 → 按列交集迁移数据 → 重建索引 → 删旧表）。全程不改 schema 版本、
 * 不丢数据、幂等（已修复则直接跳过）。新建库因 schema 本身就含全列，也会直接跳过。
 */
object SchemaSelfHealer {

    // ── agent_message 目标结构（与 sqldelight/agent/agent.sq 保持一致）──────────

    private val AGENT_MESSAGE_COLUMNS = listOf(
        "id", "session_id", "role", "seq", "created_at", "task_id", "content",
        "tool_calls_json", "tool_call_id", "tool_name", "tool_args", "is_error",
        "reasoning", "signature", "attachments_json", "is_compacted",
        "is_context_summary", "is_compaction_marker", "input_tokens", "output_tokens",
        "chunk_group_id", "chunk_index"
    )

    private val AGENT_MESSAGE_CREATE = """
        CREATE TABLE agent_message (
          id                   TEXT    NOT NULL PRIMARY KEY,
          session_id           TEXT    NOT NULL,
          role                 TEXT    NOT NULL,
          seq                  INTEGER NOT NULL,
          created_at           INTEGER NOT NULL,
          task_id              TEXT    NOT NULL DEFAULT '',
          content              TEXT    NOT NULL DEFAULT '',
          tool_calls_json      TEXT,
          tool_call_id         TEXT,
          tool_name            TEXT,
          tool_args            TEXT,
          is_error             INTEGER NOT NULL DEFAULT 0,
          reasoning            TEXT,
          signature            TEXT,
          attachments_json     TEXT,
          is_compacted         INTEGER NOT NULL DEFAULT 0,
          is_context_summary   INTEGER NOT NULL DEFAULT 0,
          is_compaction_marker INTEGER NOT NULL DEFAULT 0,
          input_tokens         INTEGER NOT NULL DEFAULT 0,
          output_tokens        INTEGER NOT NULL DEFAULT 0,
          chunk_group_id       TEXT    NOT NULL DEFAULT '',
          chunk_index          INTEGER NOT NULL DEFAULT 0,
          FOREIGN KEY (session_id) REFERENCES agent_session(id)
        );
    """.trimIndent()

    private const val AGENT_MESSAGE_SESSION_IDX =
        "CREATE INDEX agent_message_session_idx ON agent_message (session_id, seq);"

    // ── agent_session 目标结构（与 sqldelight/agent/agent.sq 保持一致）──────────
    // agent_session 与 agent_message 同属 P2-3「对齐全表」的演进表，结构漂移风险同类，一并自愈。

    private val AGENT_SESSION_COLUMNS = listOf(
        "id", "title", "mode", "model", "status", "created_at", "updated_at",
        "workspace_path", "reasoning_effort", "provider_id",
        "total_input_tokens", "total_output_tokens", "last_input_tokens"
    )

    private val AGENT_SESSION_CREATE = """
        CREATE TABLE agent_session (
          id                  TEXT    NOT NULL PRIMARY KEY,
          title               TEXT,
          mode                TEXT    NOT NULL,
          model               TEXT,
          status              TEXT    NOT NULL,
          created_at          INTEGER NOT NULL,
          updated_at          INTEGER NOT NULL,
          workspace_path      TEXT    NOT NULL DEFAULT '',
          reasoning_effort    TEXT    NOT NULL DEFAULT 'MEDIUM',
          provider_id         TEXT,
          total_input_tokens  INTEGER NOT NULL DEFAULT 0,
          total_output_tokens INTEGER NOT NULL DEFAULT 0,
          last_input_tokens   INTEGER NOT NULL DEFAULT 0
        );
    """.trimIndent()

    /** 修复 agent_message 缺 id（或其他目标列）的历史库。打开 AgentDb 后调用一次。 */
    fun healAgentMessage(driver: SqlDriver) {
        healTable(driver, "agent_message", AGENT_MESSAGE_COLUMNS, AGENT_MESSAGE_CREATE, listOf(AGENT_MESSAGE_SESSION_IDX))
    }

    /**
     * 保证性复核（在 [healAgentMessage] 之后调用）：若 `id` 列仍缺失（极端漂移 / 前次自愈半途未落地），
     * 立即对齐全列再次无损重建；仍失败则抛出明确异常（让启动上层可见，而非落入 confusing 的
     * `no such column` 崩溃）。确保「打开后 agent_message 一定可查询」。
     */
    fun ensureAgentMessageUsable(driver: SqlDriver) {
        if (hasColumn(driver, "agent_message", "id")) return
        healTable(driver, "agent_message", AGENT_MESSAGE_COLUMNS, AGENT_MESSAGE_CREATE, listOf(AGENT_MESSAGE_SESSION_IDX))
        if (!hasColumn(driver, "agent_message", "id")) {
            throw IllegalStateException(
                "agent_message 自愈后仍缺 id 列，表结构异常且无法自愈，请人工介入检查 agent.db"
            )
        }
    }

    /** 判断 [table] 是否含 [column]（PRAGMA table_info 命中）。 */
    fun hasColumn(driver: SqlDriver, table: String, column: String): Boolean =
        tableColumns(driver, table).contains(column)

    /** 与 agent_message 同风险的 agent_session 结构自愈。 */
    fun healAgentSession(driver: SqlDriver) {
        healTable(driver, "agent_session", AGENT_SESSION_COLUMNS, AGENT_SESSION_CREATE)
    }

    /** agent_session 保证性复核（同 [ensureAgentMessageUsable]）。 */
    fun ensureAgentSessionUsable(driver: SqlDriver) {
        if (hasColumn(driver, "agent_session", "id")) return
        healTable(driver, "agent_session", AGENT_SESSION_COLUMNS, AGENT_SESSION_CREATE)
        if (!hasColumn(driver, "agent_session", "id")) {
            throw IllegalStateException(
                "agent_session 自愈后仍缺 id 列，表结构异常且无法自愈，请人工介入检查 agent.db"
            )
        }
    }

    /**
     * 通用自愈：若 [table] 缺 [targetColumns] 中任意列，则无损重建为 [createSql] 定义的结构。
     *
     * 顺序：改名旧表 → 建新表 → 按列交集迁移 → 重建索引 → 删旧表。任一步失败则旧表保留，重启自愈重试，不丢数据。
     *
     * 幂等安全垫（否则自愈本身会在启动 main 线程再次 FATAL）：
     *  - [table] 整表缺失：PRAGMA table_info 返回 0 行 → 判定全列缺失，直接 RENAME 会抛 "no such table"，
     *    这里先判空跳过（整表缺失属于 ensureSchema 建表情景，缺列自愈不越权处理）。
     *  - 索引名冲突：SQLite 中 RENAME 后的旧表会保留原索引名，随后 CREATE INDEX 同名会抛 "index already exists"。
     *    这里在建新表前 DROP INDEX IF EXISTS 预清理（旧索引随旧表删表一并消失，属于待回收资源，先删安全）。
     *  - 缺列回填：被缺的 NOT NULL 无默认列（典型即 PK `id`）若直接省略则 INSERT 抛 "NOT NULL constraint failed"。
     *    迁移时对缺失列按「先自身 DEFAULT、再 id→生成 UUID、再按类型兜底」回填，保证不丢行、不撞约束。
     */
    fun healTable(
        driver: SqlDriver,
        table: String,
        targetColumns: List<String>,
        createSql: String,
        indexSqls: List<String> = emptyList(),
    ) {
        val existing = tableColumns(driver, table).toSet()
        // 0 列 = 表不存在（合法表不可能 0 列），跳过，避免 RENAME "no such table" 崩溃。
        if (existing.isEmpty()) return
        val missing = targetColumns.filter { it !in existing }
        if (missing.isEmpty()) return   // 结构完好，幂等跳过

        val legacy = "${table}_legacy"
        // 预清理待重建索引：旧表 RENAME 后索引名仍占用同名，先 DROP 避免建新索引冲突。
        indexSqls.forEach { sql ->
            val name = Regex("""(?i)CREATE\s+(UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\s+\"?([\w]+)\"?""").find(sql)?.groupValues?.get(2)
                ?: Regex("""(?i)CREATE\s+(UNIQUE\s+)?INDEX\s+\"?([\w]+)\"?""").find(sql)?.groupValues?.get(2)
            if (name != null) {
                try { exec(driver, "DROP INDEX IF EXISTS \"$name\";") } catch (_: Exception) { /* 索引导出即可，失败不阻断 */ }
            }
        }

        exec(driver, "ALTER TABLE $table RENAME TO $legacy;")
        exec(driver, createSql)

        val meta = columnMeta(createSql)
        // 迁移：常见列透传，缺失列按默认/类型兜底回填（不丢旧行、不撞 NOT NULL 约束）。
        val insertCols = ArrayList(targetColumns)
        val selectExprs = ArrayList<String>(targetColumns.size)
        for (col in targetColumns) {
            if (col in existing) {
                selectExprs += "\"$col\""
            } else {
                val m = meta[col]
                selectExprs += when {
                    col == "id" -> "lower(hex(randomblob(16)))"          // 主键 UUID，保证唯一且非空
                    m?.default != null -> m.default                       // 优先表定义 DEFAULT
                    m?.notNull == true -> if ((m.type ?: "").startsWith("INT")) "0" else "''" // 按类型兜底
                    else -> "NULL"
                }
            }
        }
        val cols = insertCols.joinToString(",") { "\"$it\"" }
        val exprs = selectExprs.joinToString(",")
        exec(driver, "INSERT INTO $table ($cols) SELECT $exprs FROM $legacy;")

        indexSqls.forEach { exec(driver, it) }
        exec(driver, "DROP TABLE $legacy;")
    }

    /**
     * 解析 CREATE TABLE 的列元数据：name → (type, notNull(含 DEFAULT), default 字面量或 null)。
     * 仅用于自愈回填缺列，面向受控的 [createSql]（本类定义），不追求通用 SQL 完备解析。
     */
    private fun columnMeta(createSql: String): Map<String, ColumnMeta> {
        val map = HashMap<String, ColumnMeta>()
        val body = createSql.substringAfter('(').substringBeforeLast(')')
        for (line in body.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("FOREIGN")) continue
            if (t.startsWith("CONSTRAINT")) continue
            val noTrail = t.removeSuffix(",").trim()
            val tokens = noTrail.split(Regex("\\s+"))
            if (tokens.size < 2) continue
            val name = tokens[0].removePrefix("\"").removeSuffix("\"")
            val upper = noTrail.uppercase()
            val notNull = upper.contains("NOT NULL")
            val type = tokens[1].uppercase().takeIf { it == "TEXT" || it == "INTEGER" || it == "INT" }
            val default = Regex("""(?i)\bDEFAULT\s+('[^']*'|\"(?:\"\")*[^\"]*\"|\S+)""").find(noTrail)?.groupValues?.get(1)
            map[name] = ColumnMeta(type = type, notNull = notNull, default = default)
        }
        return map
    }

    private data class ColumnMeta(val type: String?, val notNull: Boolean, val default: String?)

    /** 读取某表所有列名（PRAGMA table_info 的 name 列，index=1）。 */
    fun tableColumns(driver: SqlDriver, table: String): List<String> =
        driver.executeQuery(null, "PRAGMA table_info($table)", ::mapNameColumn, 0, null).value

    private fun mapNameColumn(cursor: SqlCursor): QueryResult<List<String>> {
        val names = ArrayList<String>()
        while (cursor.next().value) {
            cursor.getString(1)?.let { names += it }
        }
        return QueryResult.Value(names)
    }

    private fun exec(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0, null)
    }
}