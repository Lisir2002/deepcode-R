package com.R.codecore.datalayer.parity

import com.R.codecore.datalayer.engine.LibName
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V1（Room 域库）→ V2（SQLDelight 域库）数据一致性校验器（v2-full-takeover P1-3）。
 *
 * 切换业务读源前，逐表比对 V1 与 V2 的行数，输出每表报告；
 * 任何表不匹配时 [checkAll] 返回结果中对应 [TableParity.match] = false，
 * 调用方（启动链路 / 切换前置）应据此拒绝切 V2 读或把 [com.R.codecore.datalayer.DataReadMode] 拨回 ROOM。
 *
 * 本类只读，不写任何数据；V1 侧行数经 [V1RowCountProvider] 抽象注入，
 * Android 实现见 `core/db/AndroidV1RowCountProvider`（基于 5 个 Room 域库）。
 */
@Singleton
class V2ParityChecker @Inject constructor(
    private val pool: ConnectionPool,
    private val v1RowCount: V1RowCountProvider,
) {
    private companion object {
        const val TAG = "V2ParityChecker"
    }

    data class TableParity(
        val table: String,
        val v1Count: Long?,
        val v2Count: Long?,
        val match: Boolean,
    ) {
        val summary: String
            get() = "$table: V1=${v1Count ?: "N/A"} V2=${v2Count ?: "N/A"} ${if (match) "✓" else "✗ MISMATCH"}"
    }

    /** V1 Room 表名 → (V2 库, V2 表名)。与 DataRegistryModule / DbSplitMigrator 的登记保持一一对应。 */
    private data class Mapping(val v1Table: String, val lib: LibName, val v2Table: String)

    private val mappings: List<Mapping> = listOf(
        // settings 域
        Mapping("ai_providers", LibName.SETTINGS, "ai_providers"),
        // credentials 域
        Mapping("git_credentials", LibName.CREDENTIALS, "git_credentials"),
        // workspace 域
        Mapping("remote_connections", LibName.WORKSPACE, "remote_connections"),
        Mapping("remote_mounts", LibName.WORKSPACE, "remote_mounts"),
        Mapping("remote_audit_logs", LibName.WORKSPACE, "remote_audit_logs"),
        Mapping("credential_encryption_state", LibName.WORKSPACE, "credential_encryption_state"),
        // t2i 域
        Mapping("t2i_providers", LibName.T2I, "t2i_providers"),
        Mapping("t2i_provider_models", LibName.T2I, "t2i_provider_models"),
        Mapping("t2i_tasks", LibName.T2I, "t2i_task"),
        // agent 域（核心表；V2 表名与 V1 不完全同名）
        Mapping("chat_sessions", LibName.AGENT, "agent_session"),
        Mapping("agent_messages", LibName.AGENT, "agent_message"),
        Mapping("todo_items", LibName.AGENT, "todo_items"),
    )

    /** 执行全部登记表的行数比对。 */
    suspend fun checkAll(): List<TableParity> = withContext(Dispatchers.IO) {
        mappings.map { m ->
            val v1 = v1RowCount.rowCount(m.v1Table)
            val v2 = countV2(m.lib, m.v2Table)
            val match = v1 != null && v2 != null && v1 == v2
            val p = TableParity(m.v1Table, v1, v2, match)
            if (!match) FileLogger.w(TAG, "parity MISMATCH: ${p.summary}")
            p
        }
    }

    /** 便捷入口：全部一致才为 true。 */
    suspend fun allMatch(): Boolean = checkAll().all { it.match }

    private fun countV2(lib: LibName, table: String): Long? = runCatching {
        val driver = pool.driver(lib)
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM `$table`",
            { cursor ->
                val v = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                app.cash.sqldelight.db.QueryResult.Value(v)
            },
            0,
            {},
        ).value
    }.getOrNull()

    /** 便捷打印全部报告（供日志 / 设置页展示）。 */
    suspend fun summaryLines(): List<String> = checkAll().map { it.summary }
}
