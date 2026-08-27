package com.R.codecore.datalayer.parity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.DatabaseDriverFactory
import com.R.codecore.datalayer.engine.LibName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2-full-takeover P1-3：[V2ParityChecker] 行数比对逻辑单测。
 *
 * 用 JdbcSqliteDriver 起真实 SQLite（内存库）做 V2 侧，fake [V1RowCountProvider] 做 V1 侧：
 *  1. V1/V2 行数一致 → match=true；
 *  2. 任一表行数不一致 → 该表 match=false；
 *  3. V1 读不到（null）→ 不误判为 match。
 */
class V2ParityCheckerTest {

    private class FakeV1RowCount(private val counts: Map<String, Long>) : V1RowCountProvider {
        override fun rowCount(table: String): Long? = counts[table]
    }

    /** 内存库驱动工厂：所有 lib 共用一个 in-memory driver（测试用）。 */
    private class MemoryDriverFactory : DatabaseDriverFactory {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        override fun create(lib: LibName): SqlDriver = driver
    }

    private fun setupPool(
        tableDdl: List<Pair<LibName, List<String>>>,
        seed: Map<String, Int>,
    ): ConnectionPool {
        val pool = ConnectionPool(MemoryDriverFactory())
        for ((lib, ddl) in tableDdl) {
            val d = pool.driver(lib)
            ddl.forEach { d.execute(null, it, 0) }
        }
        // 种子数据：每张表插 seed[table] 行
        for ((table, rows) in seed) {
            val d = pool.driver(libOf(table))
            repeat(rows) { i ->
                d.execute(null, "INSERT INTO `$table` (id, name) VALUES ('$i', 'row$i')", 0)
            }
        }
        return pool
    }

    private fun libOf(table: String): LibName = when (table) {
        "ai_providers" -> LibName.SETTINGS
        "git_credentials" -> LibName.CREDENTIALS
        "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state" -> LibName.WORKSPACE
        "t2i_providers", "t2i_provider_models", "t2i_task" -> LibName.T2I
        "agent_session", "agent_message", "todo_items" -> LibName.AGENT
        else -> throw IllegalArgumentException("unknown table $table")
    }

    private fun tableDdl(): List<Pair<LibName, List<String>>> = listOf(
        LibName.SETTINGS to listOf("CREATE TABLE ai_providers (id TEXT PRIMARY KEY, name TEXT)"),
        LibName.CREDENTIALS to listOf("CREATE TABLE git_credentials (id TEXT PRIMARY KEY, name TEXT)"),
        LibName.WORKSPACE to listOf(
            "CREATE TABLE remote_connections (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE remote_mounts (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE remote_audit_logs (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE credential_encryption_state (id INTEGER PRIMARY KEY, name TEXT)",
        ),
        LibName.T2I to listOf(
            "CREATE TABLE t2i_providers (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE t2i_provider_models (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE t2i_task (id TEXT PRIMARY KEY, name TEXT)",
        ),
        LibName.AGENT to listOf(
            "CREATE TABLE agent_session (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE agent_message (id TEXT PRIMARY KEY, name TEXT)",
            "CREATE TABLE todo_items (id TEXT PRIMARY KEY, name TEXT)",
        ),
    )

    private val allTables = listOf(
        "ai_providers", "git_credentials",
        "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state",
        "t2i_providers", "t2i_provider_models", "t2i_task",
        "agent_session", "agent_message", "todo_items",
    )

    @Test
    fun `matching row counts all pass`() {
        val pool = setupPool(tableDdl(), seed = allTables.associateWith { 3 })
        val v1 = FakeV1RowCount(allTables.associateWith { 3L })
        val checker = V2ParityChecker(pool, v1)

        val results = checker.checkAll()
        assertTrue("所有表应匹配", results.all { it.match })
        assertEquals(allTables.size, results.size)
    }

    @Test
    fun `mismatch in one table fails only that table`() {
        val pool = setupPool(tableDdl(), seed = allTables.associateWith { 3 })
        val v1 = FakeV1RowCount(allTables.associateWith { 3L } + ("ai_providers" to 5L))
        val checker = V2ParityChecker(pool, v1)

        val results = checker.checkAll()
        val bad = results.first { !it.match }
        assertEquals("ai_providers", bad.table)
        assertEquals(5L, bad.v1Count)
        assertEquals(3L, bad.v2Count)
        assertFalse(checker.allMatch())
    }

    @Test
    fun `v1 unavailable counts as mismatch not match`() {
        val pool = setupPool(tableDdl(), seed = allTables.associateWith { 3 })
        // V1 侧完全读不到（null）→ 任何表都不该被当作 match
        val v1 = FakeV1RowCount(emptyMap())
        val checker = V2ParityChecker(pool, v1)

        val results = checker.checkAll()
        assertTrue(results.none { it.match })
        assertFalse(checker.allMatch())
    }

    @Test
    fun `v2 table missing yields null v2 count and mismatch`() {
        val pool = setupPool(tableDdl(), seed = allTables.associateWith { 3 })
        // 人为删一张 V2 表（模拟迁移没建出来）
        pool.driver(LibName.SETTINGS).execute(null, "DROP TABLE ai_providers", 0)
        val v1 = FakeV1RowCount(allTables.associateWith { 3L })
        val checker = V2ParityChecker(pool, v1)

        val results = checker.checkAll()
        val bad = results.first { !it.match }
        assertEquals("ai_providers", bad.table)
        assertEquals(3L, bad.v1Count)
        assertEquals(null, bad.v2Count)
    }
}
