package com.R.codecore.feature.agent.data.local.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileFilter

/**
 * 任务编排层新增表（agent_goals/agent_plans/agent_jobs/agent_schedules）的
 * 「程序化迁移 SQL ↔ Room 导出 schema」一致性闸门。
 *
 * 背景：agent 库 v1→v2 的程序化迁移 [AgentDatabaseMigrations.MIGRATION_1_2] 用
 * db.execSQL 建表，不走 assets/migrations 的 SQL 切分器，因此
 * [com.R.codecore.core.db.MigrationSchemaConsistencyTest]（只扫 assets/migrations）覆盖不到。
 * 若有人改 Entity 却忘了同步迁移 SQL（或反之），升级时 Room TableInfo 校验失败
 * （Migration didn't properly handle），轻则日志刷错、重则触发 Funnel 3 删表丢数据。
 *
 * 本测试以 Room 导出的 2.json 为权威基准，静态解析迁移 SQL 的字符串字面量
 * （Kotlin 字符串拼接格式），逐表对比：
 *   1) 列名集合一致
 *   2) 每列类型 / NOT NULL / DEFAULT 一致
 *   3) 索引集合一致（名称 / 列）
 */
class AgentMigrationSchemaConsistencyTest {

    private val projectRoot: File by lazy {
        var f = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..5) {
            val has = f.listFiles(FileFilter { it.name.startsWith("settings.gradle") })?.isNotEmpty() == true
            if (has) return@lazy f
            f = f.parentFile ?: break
        }
        File(System.getProperty("user.dir") ?: ".").resolve("../..").canonicalFile
    }

    private val migrationsKt: File by lazy {
        projectRoot.resolve(
            "app/src/main/java/com/R/codecore/feature/agent/data/local/database/AgentDatabaseMigrations.kt"
        )
    }

    private val schemaFile: File by lazy {
        projectRoot.resolve(
            "app/schemas/com.R.codecore.feature.agent.data.local.database.AgentDatabase/2.json"
        )
    }

    private val newTables = listOf("agent_goals", "agent_plans", "agent_jobs", "agent_schedules")

    // ── 解析模型 ────────────────────────────────────────────────
    private data class MigCol(val name: String, val type: String, val notNull: Boolean, val defaultValue: String?)
    private data class MigIdx(val name: String, val columns: List<String>)

    @Test
    fun `MIGRATION-1-2 - 新增 4 表迁移 SQL 与 Room schema 2 完全一致`() {
        assertTrue("AgentDatabaseMigrations.kt 不存在: ${migrationsKt.path}", migrationsKt.isFile)
        assertTrue("schema 2.json 不存在（先编译一次让 KSP 导出）: ${schemaFile.path}", schemaFile.isFile)

        val src = migrationsKt.readText()
        val schema = JSONObject(schemaFile.readText())
        val entities = schema.getJSONObject("database").getJSONArray("entities")

        val failures = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.optString("tableName", "?")
            if (tableName !in newTables) continue
            checkTable(src, entity, tableName, failures)
        }
        // 确保 4 张新表都出现在 schema 中（若实体被误删/改名，上面循环不会覆盖到）
        val schemaTables = (0 until entities.length()).map { entities.getJSONObject(it).optString("tableName") }
        val missing = newTables.filter { it !in schemaTables }
        if (missing.isNotEmpty()) {
            failures.add("schema 2.json 中缺失任务编排层表: $missing")
        }

        if (failures.isNotEmpty()) {
            val msg = "MIGRATION-1-2 与 Room schema 2.json 不一致（${failures.size} 处）——" +
                    "升级时会导致 Room TableInfo 校验失败（Migration didn't properly handle），" +
                    "触发 Funnel 2/3 抢救甚至删表丢数据。请同步修改 AgentDatabaseMigrations 或 Entity 定义。\n" +
                    failures.joinToString("\n")
            println("\n===== AGENT-MIGRATION SCHEMA-CONSISTENCY FAILURE =====")
            println(msg)
            println("======================================================")
            fail(msg)
        }
    }

    private fun checkTable(src: String, entity: JSONObject, tableName: String, failures: MutableList<String>) {
        val sql = extractCreateTableSql(src, tableName)
        if (sql == null) {
            failures.add("[$tableName] 在 AgentDatabaseMigrations.kt 中找不到 CREATE TABLE 语句")
            return
        }
        val createBody = sql.substringAfter('(', sql).substringBeforeLast(')')
        val body = createBody

        // 1) 迁移列
        val actualCols = mutableListOf<MigCol>()
        for (item in splitTopLevel(body)) {
            val upper = item.uppercase()
            if (upper.startsWith("PRIMARY KEY") ||
                upper.startsWith("FOREIGN KEY") ||
                upper.startsWith("CHECK ") ||
                upper.startsWith("UNIQUE")
            ) {
                continue
            }
            actualCols.add(parseColumnDef(item))
        }

        // 2) Room 期望列
        val fields = entity.getJSONArray("fields")
        val expectedCols = mutableListOf<ExpectedCol>()
        for (j in 0 until fields.length()) {
            val f = fields.getJSONObject(j)
            val defVal = f.optString("defaultValue", "undefined")
            expectedCols.add(
                ExpectedCol(
                    name = f.optString("columnName", "?"),
                    affinity = f.optString("affinity", ""),
                    notNull = f.optBoolean("notNull", false),
                    defaultValue = if (defVal == "undefined" || defVal == "null") null else defVal
                )
            )
        }

        // 3) 列名集合
        val actualNames = actualCols.map { it.name }.toSet()
        val expectedNames = expectedCols.map { it.name }.toSet()
        if (actualNames != expectedNames) {
            failures.add("[$tableName] 列名不一致：迁移=${actualNames.sorted()} 期望=${expectedNames.sorted()}")
            return
        }

        // 4) 逐列对比
        for (exp in expectedCols) {
            val act = actualCols.first { it.name == exp.name }
            if (act.type.uppercase() != exp.affinity.uppercase()) {
                failures.add("[$tableName] 列 ${exp.name} 类型不一致：迁移=${act.type} 期望=${exp.affinity}")
            }
            if (act.notNull != exp.notNull) {
                failures.add("[$tableName] 列 ${exp.name} NOT NULL 不一致：迁移=${act.notNull} 期望=${exp.notNull}")
            }
            if (act.defaultValue != exp.defaultValue) {
                failures.add(
                    "[$tableName] 列 ${exp.name} DEFAULT 不一致：迁移=${act.defaultValue ?: "无"} 期望=${exp.defaultValue ?: "无"}"
                )
            }
        }

        // 5) 索引对比
        val actualIndices = extractIndices(src, tableName).toSet()
        val expectedIndices = parseExpectedIndices(entity).toSet()
        if (actualIndices != expectedIndices) {
            failures.add(
                "[$tableName] 索引不一致：迁移=${actualIndices.map { it.name + it.columns }} " +
                        "期望=${expectedIndices.map { it.name + it.columns }}"
            )
        }
    }

    // ── 迁移 SQL 静态解析（Kotlin 字符串拼接格式）────────────────
    /** 提取某个 db.execSQL("..."+"..."+) 调用的拼接结果。 */
    private fun extractCreateTableSql(src: String, tableName: String): String? {
        val callRegex = Regex("""db\.execSQL\(\s*((?:"[^"]*"\s*(?:\+\s*)?)+)\)""")
        for (m in callRegex.findAll(src)) {
            val block = m.groupValues[1]
            if ("CREATE TABLE" in block && tableName in block) {
                return Regex("""\"([^"]*)\"""").findAll(block)
                    .joinToString("") { it.groupValues[1] }
            }
        }
        return null
    }

    private fun extractIndices(src: String, tableName: String): List<MigIdx> {
        val result = mutableListOf<MigIdx>()
        val regex = Regex(
            """CREATE INDEX IF NOT EXISTS `([^`]+)` ON `$tableName` \(([^)]*)\)"""
        )
        for (m in regex.findAll(src)) {
            val cols = m.groupValues[2].split(',').map { it.trim().trim('`') }
            result.add(MigIdx(m.groupValues[1], cols))
        }
        return result
    }

    private fun parseExpectedIndices(entity: JSONObject): List<MigIdx> {
        val indices = entity.optJSONArray("indices") ?: return emptyList()
        val result = mutableListOf<MigIdx>()
        for (i in 0 until indices.length()) {
            val idx = indices.getJSONObject(i)
            val colsArr = idx.optJSONArray("columnNames") ?: continue
            val cols = (0 until colsArr.length()).map { colsArr.getString(it) }
            result.add(MigIdx(idx.optString("name", "?"), cols))
        }
        return result
    }

    // ── SQL 列解析（复用 MigrationSchemaConsistencyTest 相同逻辑）──
    private data class ExpectedCol(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in body.indices) {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(body.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        parts.add(body.substring(start).trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun parseColumnDef(def: String): MigCol {
        val m = Regex("""^`?([^`\s]+)`?""").find(def)!!
        val name = m.groupValues[1]
        val rest = def.substring(m.range.last + 1).trim()
        val type = rest.substringBefore(' ').substringBefore('(').trim()
        val notNull = Regex("""\bNOT\s+NULL\b""", RegexOption.IGNORE_CASE).containsMatchIn(def)
        val default = extractDefaultValue(def)
        return MigCol(name, type, notNull, default)
    }

    private fun extractDefaultValue(def: String): String? {
        val m = Regex("""(?i)\bDEFAULT\s+""").find(def) ?: return null
        val rest = def.substring(m.range.last + 1).trim()
        return if (rest.startsWith("'")) {
            var j = 1
            while (j < rest.length) {
                if (rest[j] == '\'') {
                    if (j + 1 < rest.length && rest[j + 1] == '\'') {
                        j += 2
                        continue
                    }
                    return rest.substring(0, j + 1)
                }
                j++
            }
            rest
        } else {
            rest.substringBefore(' ').trim()
        }
    }
}
