package com.core.deepcode.datalayer.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.core.deepcode.datalayer.sqldelight.agent.Agent_message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rc8 崩溃回归测试：[AgentDb.agentQueries.selectMessagesBySessionPaged]。
 *
 * ## 事故背景
 * rc8 启动后一进会话页必崩 `no such column: agent_message.id (code 1 SQLITE_ERROR)`，
 * 且崩溃快照里 `agentPreheatRan=true`（结构自愈已成功跑完）。
 *
 * ## 真实根因（rc1~rc7 全部找错了方向）
 * **不是数据库缺列，而是生成的 SQL 形态不合法。**
 *
 * `agent.sq` 原写法：
 * ```
 * SELECT * FROM ( SELECT * FROM agent_message WHERE ... LIMIT ? ) ORDER BY seq ASC;
 * ```
 * SQLDelight 会把外层 `SELECT *` 展开为**带表名前缀**的列（`agent_message.id, ...`）。
 * 而子查询是**匿名**的，SQLite 外层作用域里不存在名为 `agent_message` 的表或别名，
 * 展开后的 `agent_message.id` 无处绑定，**在 prepare 阶段**就失败。
 *
 * 决定性特征：**表结构完全正确（22 列齐全、含 id）时同样必崩**。
 * 所以 rc1~rc7 围绕「表缺列 → SchemaSelfHealer 无损重建 → 换库文件名」的 7 轮修复，
 * 从机制上不可能生效——它们修的是一个并不存在的问题。
 *
 * 错误文本里的 `agent_message.id` 是**带表名前缀**的，这正是外层作用域解析失败的指纹；
 * 若真是表缺列，SQLite 通常在子查询内层就报错。
 *
 * ## 本测试的价值
 * 直接跑 SQLDelight **编译生成**的那条 SQL（而非手写近似 SQL），
 * 任何人删掉 `.sq` 里的 `AS agent_message` 别名，本测试立刻失败，
 * 从而把 rc8 这类「结构看着没问题、SQL 编译不过」的事故永久钉死在 CI 上。
 */
class AgentPagedQueryRegressionTest {

    /** 全新内存库：走 AgentDb.Schema.create，表结构与 .sq 定义完全一致。 */
    private fun freshDb(): AgentDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDb.Schema.create(driver)
        return AgentDb(driver)
    }

    private fun AgentDb.insertMessage(seq: Long, sessionId: String = "s1") {
        val e = Agent_message(
            id = "m$seq",
            session_id = sessionId,
            role = "USER",
            seq = seq,
            created_at = 1_000L + seq,
            task_id = "",
            content = "content-$seq",
            tool_calls_json = null,
            tool_call_id = null,
            tool_name = null,
            tool_args = null,
            is_error = 0L,
            reasoning = null,
            signature = null,
            attachments_json = null,
            is_compacted = 0L,
            is_context_summary = 0L,
            is_compaction_marker = 0L,
            input_tokens = 0L,
            output_tokens = 0L,
            chunk_group_id = "",
            chunk_index = 0L,
        )
        agentQueries.insertMessage(
            e.id, e.session_id, e.role, e.seq, e.created_at, e.task_id, e.content,
            e.tool_calls_json, e.tool_call_id, e.tool_name, e.tool_args, e.is_error,
            e.reasoning, e.signature, e.attachments_json, e.is_compacted,
            e.is_context_summary, e.is_compaction_marker, e.input_tokens, e.output_tokens,
            e.chunk_group_id, e.chunk_index,
        )
    }

    /**
     * 核心回归：结构 100% 正确的库上，分页查询必须能编译并执行。
     *
     * 修复前（匿名子查询）此用例必抛 `SQLiteException: no such column: agent_message.id`，
     * 与 rc8 线上崩溃**逐字一致**。
     */
    @Test
    fun `paged query compiles and executes on a fully migrated schema`() {
        val db = freshDb()
        db.insertMessage(1)

        // 不抛异常即是胜利：崩溃发生在 prepare 阶段，executeAsList 必然触达。
        val rows = db.agentQueries.selectMessagesBySessionPaged("s1", 50L).executeAsList()

        assertEquals(1, rows.size)
        assertEquals("m1", rows.first().id)
    }

    /** 语义回归：内层倒序取最近 N 条 → 外层正序返回，不能因为修 SQL 而改坏语义。 */
    @Test
    fun `paged query returns the latest N rows in ascending seq order`() {
        val db = freshDb()
        repeat(10) { db.insertMessage(it.toLong()) } // seq 0..9

        val rows = db.agentQueries.selectMessagesBySessionPaged("s1", 3L).executeAsList()

        assertEquals("应只取最近 3 条", 3, rows.size)
        assertEquals(
            "外层必须按 seq 正序返回（倒序取、正序出）",
            listOf(7L, 8L, 9L),
            rows.map { it.seq },
        )
    }

    /** limit 大于总行数时不应越界，应返回全部并正序。 */
    @Test
    fun `paged query handles limit larger than row count`() {
        val db = freshDb()
        repeat(4) { db.insertMessage(it.toLong()) }

        val rows = db.agentQueries.selectMessagesBySessionPaged("s1", 99L).executeAsList()

        assertEquals(4, rows.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), rows.map { it.seq })
    }

    /** 分页查询必须按 session 隔离，不能串会话。 */
    @Test
    fun `paged query is scoped to the requested session`() {
        val db = freshDb()
        db.insertMessage(1, sessionId = "s1")
        db.insertMessage(2, sessionId = "s2")

        val rows = db.agentQueries.selectMessagesBySessionPaged("s1", 50L).executeAsList()

        assertEquals(1, rows.size)
        assertTrue(rows.all { it.session_id == "s1" })
    }

    /** 空会话：返回空列表，不抛异常（UI 新建会话首帧即走此路径）。 */
    @Test
    fun `paged query returns empty list for unknown session`() {
        val db = freshDb()
        val rows = db.agentQueries.selectMessagesBySessionPaged("__none__", 50L).executeAsList()
        assertTrue(rows.isEmpty())
    }
}
