package com.R.codecore.datalayer.parity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.R.codecore.datalayer.engine.ConnectionPool
import com.R.codecore.datalayer.engine.DatabaseDriverFactory
import com.R.codecore.datalayer.engine.LibName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * v2-full-takeover P1-3：[V2ParityChecker] 行数比对逻辑单测。
 *
 * 用 JdbcSqliteDriver 起真实 SQLite（内存库）做 V2 侧，fake [V1RowCountProvider] 做 V1 侧。
 * 表清单与 [V2ParityChecker.mappings] 对齐：V1 侧用 V1 表名（chat_sessions…），V2 侧物理表用 V2 表名（agent_session…）。
 *
 *  1. V1/V2 行数一致 → match=true；
 *  2. 任一表行数不一致 → 该表 match=false；
 *  3. V1 读不到（null）→ 不误判为 match；
 *  4. V2 表缺失 → v2Count=null 且 mismatch。
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

    /** (V1 表名, V2 域库, V2 表名)——与 [V2ParityChecker.mappings] 一一对应。 */
    private data class Tb(val v1: String, val lib: LibName, val v2: String)

    private val TABLES: List<Tb> = listOf(
        Tb("ai_providers", LibName.SETTINGS, "ai_providers"),
        Tb("git_credentials", LibName.CREDENTIALS, "git_credentials"),
        Tb("remote_connections", LibName.WORKSPACE, "remote_connections"),
        Tb("remote_mounts", LibName.WORKSPACE, "remote_mounts"),
        Tb("remote_audit_logs", LibName.WORKSPACE, "remote_audit_logs"),
        Tb("credential_encryption_state", LibName.WORKSPACE, "credential_encryption_state"),
        Tb("t2i_providers", LibName.T2I, "t2i_providers"),
        Tb("t2i_provider_models", LibName.T2I, "t2i_provider_models"),
        Tb("t2i_tasks", LibName.T2I, "t2i_task"),
        Tb("chat_sessions", LibName.AGENT, "agent_session"),
        Tb("agent_messages", LibName.AGENT, "agent_message"),
        Tb("todo_items", LibName.AGENT, "todo_items"),
        Tb("session_checkpoints", LibName.AGENT, "session_checkpoints"),
        Tb("checkpoint_file_snapshots", LibName.AGENT, "checkpoint_file_snapshots"),
        Tb("zth_user_confirmed_sentinels", LibName.AGENT, "zth_user_confirmed_sentinels"),
        Tb("zth_hallucination_fuses", LibName.AGENT, "zth_hallucination_fuses"),
        Tb("zth_sentinel_plan_rejection_audits", LibName.AGENT, "zth_sentinel_plan_rejection_audits"),
        Tb("zth_hard_constraint_delete_audits", LibName.AGENT, "zth_hard_constraint_delete_audits"),
        Tb("zth_l0_soft_compact_restore_logs", LibName.AGENT, "zth_l0_soft_compact_restore_logs"),
        Tb("zth_telemetry_events", LibName.AGENT, "zth_telemetry_events"),
    )

    private fun seedAll(): Map<String, Int> = TABLES.associate { it.v2 to 3 }
    private fun v1All(): Map<String, Long> = TABLES.associate { it.v1 to 3L }

    private fun setupPool(seed: Map<String, Int>): ConnectionPool {
        val pool = ConnectionPool(MemoryDriverFactory())
        for (t in TABLES) {
            pool.driver(t.lib).execute(null, "CREATE TABLE IF NOT EXISTS `${t.v2}` (id TEXT PRIMARY KEY, name TEXT)", 0)
        }
        for ((v2, rows) in seed) {
            val lib = TABLES.first { it.v2 == v2 }.lib
            val d = pool.driver(lib)
            repeat(rows) { i ->
                d.execute(null, "INSERT INTO `$v2` (id, name) VALUES ('$i', 'row$i')", 0)
            }
        }
        return pool
    }

    @Test
    fun `matching row counts all pass`() {
        val pool = setupPool(seedAll())
        val checker = V2ParityChecker(pool, FakeV1RowCount(v1All()))

        val results = runBlocking { checker.checkAll() }
        assertTrue("所有表应匹配: ${results.filter { !it.match }.map { it.summary }}", results.all { it.match })
        assertEquals(TABLES.size, results.size)
    }

    @Test
    fun `mismatch in one table fails only that table`() {
        val pool = setupPool(seedAll())
        val v1 = v1All() + ("ai_providers" to 5L)
        val checker = V2ParityChecker(pool, FakeV1RowCount(v1))

        val results = runBlocking { checker.checkAll() }
        val bad = results.first { !it.match }
        assertEquals("ai_providers", bad.table)
        assertEquals(5L, bad.v1Count)
        assertEquals(3L, bad.v2Count)
        assertFalse(runBlocking { checker.allMatch() })
    }

    @Test
    fun `v1 unavailable counts as mismatch not match`() {
        val pool = setupPool(seedAll())
        // V1 侧完全读不到（null）→ 任何表都不该被当作 match
        val checker = V2ParityChecker(pool, FakeV1RowCount(emptyMap()))

        val results = runBlocking { checker.checkAll() }
        assertTrue(results.none { it.match })
        assertFalse(runBlocking { checker.allMatch() })
    }

    @Test
    fun `v2 table missing yields null v2 count and mismatch`() {
        val pool = setupPool(seedAll())
        // 人为删一张 V2 表（模拟迁移没建出来）
        pool.driver(LibName.SETTINGS).execute(null, "DROP TABLE ai_providers", 0)
        val checker = V2ParityChecker(pool, FakeV1RowCount(v1All()))

        val results = runBlocking { checker.checkAll() }
        val bad = results.first { !it.match }
        assertEquals("ai_providers", bad.table)
        assertEquals(3L, bad.v1Count)
        assertEquals(null, bad.v2Count)
    }
}