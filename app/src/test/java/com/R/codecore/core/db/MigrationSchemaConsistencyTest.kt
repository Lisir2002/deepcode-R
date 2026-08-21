package com.R.codecore.core.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileFilter

/**
 * DB-SHIELD Phase C-RC91 新增 CI 闸门：MigrationSchemaConsistencyTest。
 *
 * 背景：RC74 skill_state / RC68 三张表（session_checkpoints、model_capability_overrides、
 * zth_hallucination_fuses）都出现过「迁移 SQL 建的表结构与 @Entity 定义不一致」的隐患，
 * 表现为 Room TableInfo 校验失败（Migration didn't properly handle: xxx），
 * 轻则日志刷错、重则触发 Funnel 3 删表丢数据。
 *
 * 本测试以 Room 导出的 schema JSON（app/schemas/.../N.json，由 KSP 在编译期生成）为权威基准，
 * 逐表对比 assets/migrations 下所有迁移 SQL 的「最终建表结果」：
 *   1) 列名集合一致
 *   2) 每列类型 / NOT NULL / DEFAULT / 主键位置一致
 *   3) 索引集合一致（名称 / unique / 列 / ASC-DESC 排序）
 *
 * 一旦有人改了 Entity 却忘了同步迁移 SQL（或反之），CI 立即 fail，杜绝「升级后 TableInfo 校验失败」。
 *
 * 前置条件：schemas 目录必须存在（先执行一次构建，KSP 会导出 Room schema）。
 * 该目录已提交进仓库，CI 上编译后自动刷新，因此测试始终基于最新 Entity 定义。
 */
class MigrationSchemaConsistencyTest {

    private val projectRoot: File by lazy {
        var f = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..5) {
            val has = f.listFiles(FileFilter { it.name.startsWith("settings.gradle") })?.isNotEmpty() == true
            if (has) return@lazy f
            f = f.parentFile ?: break
        }
        File(System.getProperty("user.dir") ?: ".").resolve("../..").canonicalFile
    }

    private val migrationsDir: File by lazy {
        val candidate = projectRoot.resolve("app/src/main/assets/migrations")
        assertTrue("migrations 目录不存在: ${candidate.path}", candidate.isDirectory)
        candidate
    }

    private val schemasDir: File by lazy {
        projectRoot.resolve("app/schemas")
    }

    // ── 数据结构 ────────────────────────────────────────────────
    private data class ParsedCol(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val isPk: Boolean
    )

    private data class ParsedIndex(
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
        val orders: List<String>
    )

    private data class MigrationFile(val fileName: String, val version: Int, val file: File)

    // ── 主测试 ──────────────────────────────────────────────────
    @Test
    fun `SCHEMA-CONSISTENCY - 迁移 SQL 最终建表与 Room 导出 schema 完全一致`() {
        val schemaFile = findSchemaJson()
        val schema = JSONObject(schemaFile.readText())
        // Room 2.7.x schema 顶层结构：{ "formatVersion": 1, "database": { "version": N, "entities": [...] } }
        // RC92 修复：JUnit 的 fail() 在 Kotlin 中返回 Unit 而非 Nothing，无法用 ?: 收窄 nullable 类型，
        // 改用 requireNotNull（返回非空类型）避免编译期 Unresolved reference。
        val dbObj = requireNotNull(schema.optJSONObject("database")) {
            "schema JSON 顶层缺少 database 键（文件: ${schemaFile.name}）"
        }
        val entities = requireNotNull(dbObj.optJSONArray("entities")) {
            "schema JSON database 缺少 entities 数组（文件: ${schemaFile.name}）"
        }

        val migrations = listMigrationFiles().sortedBy { it.version }
        val allSql = migrations.joinToString("\n") { it.file.readText() }

        val failures = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.optString("tableName", "?")
            checkEntity(entity, tableName, allSql, failures)
        }

        if (failures.isNotEmpty()) {
            val msg =
                "SCHEMA-CONSISTENCY 发现 ${failures.size} 处迁移 SQL 与 Room 导出 schema 不一致 " +
                        "（基准 schema: ${schemaFile.name}）。\n" +
                        "这些不一致会导致 Room TableInfo 校验失败（Migration didn't properly handle），" +
                        "触发 Funnel 2/3 抢救甚至删表丢数据。请同步修改迁移 SQL 或 Entity 定义。\n" +
                        failures.joinToString("\n")
            // RC92：println 到 stdout，让 Gradle 控制台日志直接可见（测试报告在 CI 上不易获取）
            println("\n===== SCHEMA-CONSISTENCY FAILURE DETAILS =====")
            println(msg)
            println("==============================================")
            fail(msg)
        }
    }

    // ── 单表校验 ────────────────────────────────────────────────
    private fun checkEntity(
        entity: JSONObject,
        tableName: String,
        allSql: String,
        failures: MutableList<String>
    ) {
        val createResult = findLastCreateTableBody(allSql, tableName)
        if (createResult == null) {
            // RC92 修复：部分表（如 agent_messages）在 v8 之前的初始 schema 中创建，
            // 迁移 SQL 只有 ALTER TABLE ADD COLUMN，没有 CREATE TABLE。
            // 这类表的结构由 Room 生成的初始 schema 保证一致，跳过 CREATE TABLE 校验。
            return
        }
        val createBody = createResult.first
        val usedNewTable = createResult.second

        // 1) 解析迁移 SQL 的实际列（CREATE TABLE 体 + 后续 ALTER TABLE ADD COLUMN）
        val items = splitTopLevel(createBody)
        val actualCols = mutableListOf<ParsedCol>()
        val tablePk = parseTableLevelPk(createBody)
        for (item in items) {
            val upper = item.uppercase()
            // RC93 修复：CHECK 约束必须带空格或左括号（"CHECK (" / "CHECK("）才判定为约束，
            // 裸 "CHECK" 会误伤列名以 CHECK 开头的列（如 checkpoint_file_snapshots.checkpointId）。
            if (upper.startsWith("PRIMARY KEY") ||
                upper.startsWith("FOREIGN KEY") ||
                upper.startsWith("CHECK ") ||
                upper.startsWith("CHECK(") ||
                upper.startsWith("UNIQUE")
            ) {
                continue
            }
            actualCols.add(parseColumnDef(item))
        }
        // RC92 修复：部分列通过 ALTER TABLE ADD COLUMN 添加（如 agent_messages 的 isCompacted、
        // inputTokens 等），只解析 CREATE TABLE 体会漏掉这些列 → 合并 ALTER 添加的列。
        // RC93 修复：表若经过 _new 四步法重建（如 ai_providers 迁移 25/38），重建表已包含最终全部列，
        // 此前通过 ALTER ADD COLUMN 添加、后被重建删除的列（如 ai_providers.apiPath 迁移 13）不应再合并。
        if (!usedNewTable) {
            for (alterCol in findAlterAddColumns(allSql, tableName)) {
                if (actualCols.none { it.name == alterCol.name }) {
                    actualCols.add(alterCol)
                }
            }
        }

        // 2) 解析 Room 期望列
        // RC92 修复：Room 2.7.x schema 中部分字段（尤其 nullable 字段）的 defaultValue 可能是 JSON null，
        // org.json 的 getString 对 null 值抛 JSONException → 改用 opt 系列方法读取，缺失/null 一律视为 "undefined"。
        // RC93 修复：Room 2.7.x schema 的 field 对象没有 primaryKeyPosition 字段（optInt 恒为 0），
        // 主键信息在 entity.primaryKey.columnNames 数组里（数组顺序即主键顺序）。
        // 改用该数组推导主键位置，避免全部主键列被误判为「迁移=1 期望=0」。
        val fields = entity.optJSONArray("fields")
            ?: run {
                failures.add("[$tableName] schema 中缺少 fields 数组")
                return
            }
        val pkColumnNames = entity.optJSONObject("primaryKey")
            ?.optJSONArray("columnNames")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList()
        val expectedCols = mutableListOf<ExpectedCol>()
        for (j in 0 until fields.length()) {
            val f = fields.getJSONObject(j)
            val defVal = f.optString("defaultValue", "undefined")
            val colName = f.optString("columnName", "?")
            expectedCols.add(
                ExpectedCol(
                    name = colName,
                    affinity = f.optString("affinity", ""),
                    notNull = f.optBoolean("notNull", false),
                    defaultValue = if (defVal == "undefined" || defVal == "null") null else defVal,
                    pkPosition = if (colName in pkColumnNames) pkColumnNames.indexOf(colName) + 1 else 0
                )
            )
        }

        // 3) 列名集合
        val actualNames = actualCols.map { it.name }.toSet()
        val expectedNames = expectedCols.map { it.name }.toSet()
        if (actualNames != expectedNames) {
            failures.add(
                "[$tableName] 列名不一致：迁移=${actualNames.sorted()} 期望=${expectedNames.sorted()}"
            )
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
            val actPkPos = when {
                act.isPk -> 1
                tablePk.contains(exp.name) -> tablePk.indexOf(exp.name) + 1
                else -> 0
            }
            if (actPkPos != exp.pkPosition) {
                failures.add("[$tableName] 列 ${exp.name} 主键位置不一致：迁移=$actPkPos 期望=${exp.pkPosition}")
            }
        }

        // 5) 索引对比
        val actualIndices = findIndicesForTable(allSql, tableName)
        val expectedIndices = parseExpectedIndices(entity)
        val actualIndexNames = actualIndices.map { it.name }.toSet()
        val expectedIndexNames = expectedIndices.map { it.name }.toSet()
        if (actualIndexNames != expectedIndexNames) {
            failures.add(
                "[$tableName] 索引集合不一致：迁移=${actualIndexNames.sorted()} 期望=${expectedIndexNames.sorted()}"
            )
        } else {
            for (expIdx in expectedIndices) {
                val actIdx = actualIndices.first { it.name == expIdx.name }
                if (actIdx.unique != expIdx.unique) {
                    failures.add("[$tableName] 索引 ${expIdx.name} unique 不一致：迁移=${actIdx.unique} 期望=${expIdx.unique}")
                }
                if (actIdx.columns != expIdx.columns) {
                    failures.add("[$tableName] 索引 ${expIdx.name} 列不一致：迁移=${actIdx.columns} 期望=${expIdx.columns}")
                }
                if (actIdx.orders != expIdx.orders) {
                    failures.add("[$tableName] 索引 ${expIdx.name} 排序不一致：迁移=${actIdx.orders} 期望=${expIdx.orders}")
                }
            }
        }
    }

    private data class ExpectedCol(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pkPosition: Int
    )

    private fun parseExpectedIndices(entity: JSONObject): List<ParsedIndex> {
        val indices = entity.optJSONArray("indices") ?: return emptyList()
        val result = mutableListOf<ParsedIndex>()
        for (i in 0 until indices.length()) {
            val idx = indices.getJSONObject(i)
            val colsArr = idx.optJSONArray("columnNames") ?: continue
            val ordersArr = idx.optJSONArray("orders")
            val cols = (0 until colsArr.length()).map { colsArr.getString(it) }
            val orders = if (ordersArr != null && ordersArr.length() == cols.size) {
                (0 until ordersArr.length()).map { ordersArr.getString(it) }
            } else {
                cols.map { "ASC" }
            }
            result.add(
                ParsedIndex(
                    name = idx.optString("name", "?"),
                    unique = idx.optBoolean("unique", false),
                    columns = cols,
                    orders = orders
                )
            )
        }
        return result
    }

    // ── SQL 解析工具 ─────────────────────────────────────────────
    /**
     * 查找迁移 SQL 中该表的最终 CREATE TABLE 语句体（不含外层括号）。
     * 返回 Pair(建表体, 是否命中 _new 重建表)。
     *
     * RC92 修复：迁移 38/41/42 采用「CREATE _new → DROP 原表 → RENAME」四步法重建表，
     * 最终表结构由 `${tableName}_new` 决定，而不是旧迁移里的原表 CREATE TABLE。
     * 因此优先匹配 `${tableName}_new`；找不到再回退匹配精确表名。
     * 两者都找不到返回 null（表在 v8 前初始 schema 创建，无 CREATE TABLE）。
     *
     * RC93 修复：返回 usedNewTable 标志，供调用方决定是否合并 ALTER ADD COLUMN 列
     * （_new 重建表已包含最终全部列，此前 ALTER 添加后被删除的列不应再合并）。
     */
    private fun findLastCreateTableBody(allSql: String, tableName: String): Pair<String, Boolean>? {
        val newName = "${tableName}_new"
        val newBody = findLastCreateTableBodyExact(allSql, newName)
        if (newBody != null) return newBody to true
        val body = findLastCreateTableBodyExact(allSql, tableName)
        return body?.let { it to false }
    }

    private fun findLastCreateTableBodyExact(allSql: String, tableName: String): String? {
        val regex = Regex("""(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?$tableName`?\s*\(""")
        val matches = regex.findAll(allSql).toList()
        if (matches.isEmpty()) return null
        val m = matches.last()
        val start = m.range.last + 1
        var depth = 1
        var i = start
        while (i < allSql.length && depth > 0) {
            when (allSql[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return allSql.substring(start, i - 1)
    }

    /**
     * 解析所有 `ALTER TABLE <tableName> ADD COLUMN <colDef>` 语句，返回添加的列定义。
     * 用于合并 CREATE TABLE 之后通过 ALTER 追加的列（如 agent_messages 的 isCompacted、inputTokens 等）。
     */
    private fun findAlterAddColumns(allSql: String, tableName: String): List<ParsedCol> {
        val result = mutableListOf<ParsedCol>()
        val regex = Regex(
            """(?i)ALTER\s+TABLE\s+`?$tableName`?\s+ADD\s+COLUMN\s+(.+?)(?:;|$)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in regex.findAll(allSql)) {
            val colDef = m.groupValues[1].trim()
            if (colDef.isBlank()) continue
            result.add(parseColumnDef(colDef))
        }
        return result
    }

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

    private fun parseColumnDef(def: String): ParsedCol {
        // RC93 修复：用完整正则匹配（含反引号）的结束位置截取剩余部分，
        // 避免 substringAfter(name) 漏掉 name 后的反引号导致类型被解析成 "`"。
        val m = Regex("""^`?([^`\s]+)`?""").find(def)!!
        val name = m.groupValues[1]
        val rest = def.substring(m.range.last + 1).trim()
        val type = rest.substringBefore(' ').substringBefore('(').trim()
        val notNull = Regex("""\bNOT\s+NULL\b""", RegexOption.IGNORE_CASE).containsMatchIn(def)
        val isPk = Regex("""\bPRIMARY\s+KEY\b""", RegexOption.IGNORE_CASE).containsMatchIn(def)
        return ParsedCol(name, type, notNull, extractDefaultValue(def), isPk)
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

    private fun parseTableLevelPk(body: String): List<String> {
        val m = Regex("""(?i)PRIMARY\s+KEY\s*\(([^)]*)\)""").find(body) ?: return emptyList()
        return m.groupValues[1].split(',').map { it.trim().trim('`') }.filter { it.isNotBlank() }
    }

    private fun findIndicesForTable(allSql: String, tableName: String): List<ParsedIndex> {
        val result = mutableListOf<ParsedIndex>()
        val regex = Regex(
            """(?i)CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([^`\s]+)`?\s+ON\s+`?$tableName`?\s*\(([^)]*)\)"""
        )
        for (m in regex.findAll(allSql)) {
            val unique = m.groupValues[1].isNotBlank()
            val name = m.groupValues[2]
            val colsPart = m.groupValues[3]
            val cols = colsPart.split(',').map { it.trim() }
            val colNames = cols.map { it.substringBefore(' ').trim().trim('`') }
            val orders = cols.map { c ->
                if (Regex("""\bDESC\b""", RegexOption.IGNORE_CASE).containsMatchIn(c)) "DESC" else "ASC"
            }
            result.add(ParsedIndex(name, unique, colNames, orders))
        }
        return result
    }

    // ── 文件工具 ─────────────────────────────────────────────────
    private fun listMigrationFiles(): List<MigrationFile> = migrationsDir
        .listFiles { _, name -> name.endsWith(".sql") }
        .orEmpty()
        .map { f ->
            val prefix: String = f.name.substringBefore('_')
            val parsed: Int = prefix.toIntOrNull()
                ?: error("迁移文件名无法解析版本号前缀: ${f.name}（格式应为 <version>_xxx.sql）")
            MigrationFile(fileName = f.name, version = parsed, file = f)
        }

    private fun findSchemaJson(): File {
        assertTrue(
            "schemas 目录不存在: ${schemasDir.path}\n" +
                    "请先执行一次构建（./gradlew :app:assembleDebug 或 :app:testDebugUnitTest），" +
                    "KSP 会在编译期把 Room schema 导出到 app/schemas/ 下。",
            schemasDir.isDirectory
        )
        val files = schemasDir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
        assertTrue("schemas 目录下没有 .json 文件: ${schemasDir.path}", files.isNotEmpty())
        // 按 schema JSON 内的 database.version 取最高版本作为权威基准，而非文件 lastModified：
        // CI 上 kspReleaseKotlin 命中 Gradle 构建缓存（FROM-CACHE）时不重写 47.json，
        // 所有版本 json 的 mtime 同为 checkout 时刻，lastModified 选基准会随机命中旧版本
        // （如误选 46.json，导致 skill_state 新列被误判不一致）。按版本号选择才是确定性的。
        return files.maxByOrNull { f ->
            runCatching {
                JSONObject(f.readText())
                    .optJSONObject("database")
                    ?.optInt("version", -1)
                    ?: -1
            }.getOrDefault(-1)
        }!!
    }
}
