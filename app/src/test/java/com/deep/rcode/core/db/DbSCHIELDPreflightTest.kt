package com.deep.rcode.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileFilter

/**
 * DB-SHIELD Phase C 闸门测试（Unit Test，不依赖 Android 真机）：
 *
 * 1) GAP-TEST：assets/migrations 目录下所有版本化 SQL（例如 08_xxx.sql / 33_xxx.sql）
 *    的版本号必须连续覆盖
 *    MigrationLoader.MIN_REQUIRED_START_VERSION .. AgentDatabase.SCHEMA_VERSION
 *    且无重复、无悬空（version > SCHEMA_VERSION）
 *
 * 2) ENTITY-LIST-COUNT-TEST：LightweightSchemaRescue.ALL_ENTITY_CLASSES.size
 *    必须与 AgentDatabase.kt 里 @Database(entities=[...]) 数组 size 一致。
 *    不一致 → 说明"将来加表时忘了同步 ALL_ENTITY_CLASSES"，轻量抢救会漏掉新表。
 *
 * 3) FILE-ORDERING-TEST：所有 assets/migrations 目录下 SQL 文件名前缀补零（08/09/10...），
 *    保证 ASCII 排序 = 数字升序，日志顺序和诊断结果永远对齐。
 */
class DbSCHIELDPreflightTest {

    private val projectRoot: File by lazy {
        // 运行单元测试时 working dir 通常是模块根或项目根；逐步向上探测直到找到 settings.gradle(.kts)
        var f = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..5) {
            val has = f.listFiles(FileFilter { it.name.startsWith("settings.gradle") })?.isNotEmpty() == true
            if (has) return@lazy f
            f = f.parentFile ?: break
        }
        // 找不到就用相对路径 fallback：../.. 回到项目根
        File(System.getProperty("user.dir") ?: ".").resolve("../..").canonicalFile
    }

    private val migrationsDir: File by lazy {
        val candidate = projectRoot.resolve("app/src/main/assets/migrations")
        assertTrue("migrations 目录不存在: ${candidate.path}", candidate.isDirectory)
        candidate
    }

    private val agentDatabaseKt: File by lazy {
        val p = projectRoot.resolve("app/src/main/java/com/deep/rcode/feature/agent/data/local/database/AgentDatabase.kt")
        assertTrue("AgentDatabase.kt 不存在: ${p.path}", p.isFile)
        p
    }

    private data class MigrationFile(val fileName: String, val version: Int, val file: File)

    private fun listMigrationFiles(): List<MigrationFile> = migrationsDir
        .listFiles { _, name -> name.endsWith(".sql") }
        .orEmpty()
        .map { f ->
            val prefix: String = f.name.substringBefore('_')
            val parsed: Int = prefix.toIntOrNull()
                ?: error("迁移文件名无法解析版本号前缀: ${f.name}（格式应为 <version>_xxx.sql）")
            MigrationFile(fileName = f.name, version = parsed, file = f)
        }

    // ─────────────────────────────────────────────────────────────
    // 1) GAP-TEST: 连续覆盖 + 无重复 + 无悬空
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `GAP-TEST - migration 版本号连续覆盖 MIN 到 SCHEMA_VERSION`() {
        val migrations = listMigrationFiles().sortedBy { it.version }
        val MIN = MigrationLoader.MIN_REQUIRED_START_VERSION
        val DECLARED = findDeclaredSchemaVersionInAgentDatabaseKt()

        val have = migrations.map { it.version }.toSortedSet()
        val missing = (MIN .. DECLARED).filterNot { have.contains(it) }
        assertTrue(
            "SCHEMA_GAP[MISSING]：期望覆盖 v${MIN}..v${DECLARED}，缺失=$missing；" +
                    "请在 ${migrationsDir.path} 下补对应 SQL 迁移文件（命名 <version>_<desc>.sql）",
            missing.isEmpty()
        )

        val duplicates = migrations.groupingBy { it.version }.eachCount().filter { it.value > 1 }
        assertTrue(
            "SCHEMA_GAP[DUPLICATE]：存在重复迁移版本号: ${duplicates.keys}",
            duplicates.isEmpty()
        )

        val dangling = have.filter { it > DECLARED }
        assertTrue(
            "SCHEMA_GAP[DANGLING]：迁移版本号高于 @Database(version=$DECLARED)：$dangling；" +
                    "请同步升级 @Database(version = N) 或删除多余 SQL",
            dangling.isEmpty()
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 2) ENTITY-LIST-COUNT-TEST: ALL_ENTITY_CLASSES 与 @Database entities 数量一致
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `ENTITY-COUNT-TEST - LightweightSchemaRescue ALL_ENTITY_CLASSES 数量与 AgentDatabase entities 数组一致`() {
        val rescueCount = LightweightSchemaRescue.ALL_ENTITY_CLASSES.size
        val dbEntitiesCount = countEntitiesInAgentDatabaseKt()
        assertEquals(
            "LightweightSchemaRescue.ALL_ENTITY_CLASSES.size=$rescueCount 与 " +
                    "@Database(entities=[...]) 数组 size=$dbEntitiesCount 不一致；" +
                    "加表/删表时两个地方必须同步改，否则 Funnel 2 轻量抢救漏建表/多建表",
            dbEntitiesCount, rescueCount
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 3) FILE-ORDERING-TEST: 文件名补零 → ASCII 排序 = 数字升序
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `ORDERING-TEST - 文件名前缀零位补齐确保 ASCII 排序等于数字升序`() {
        val migrations = listMigrationFiles()
        val asciiSorted = migrations.sortedBy { it.fileName }.map { it.version }
        val numericSorted = migrations.map { it.version }.sorted()
        assertEquals(
            "文件名 ASCII 排序结果与数字版本号升序不一致；请把 1/2 位数用 01..09 前缀补零，" +
                    "保证日志与 MigrationLoader 读取顺序永远对齐。\n" +
                    "ASCII-sorted  versions=$asciiSorted\n" +
                    "Numeric-sorted versions=$numericSorted",
            numericSorted, asciiSorted
        )

        // 额外：没有哪个版本号 >= 100（防止将来 08/09/10 前缀变成 100+ 又错位）
        migrations.forEach {
            assertFalse(
                "迁移 ${it.fileName} 版本 ≥ 100，请升级 ORDERING-TEST 规则以支持 3 位数前缀",
                it.version >= 100
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 辅助：从 AgentDatabase.kt 静态解析 @Database(version = N) 和 entities 数组大小
    // ─────────────────────────────────────────────────────────────
    private fun findDeclaredSchemaVersionInAgentDatabaseKt(): Int {
        val content = agentDatabaseKt.readText()
        // 优先匹配 @Database(version = 37)
        val versionPattern = Regex("""@Database\s*\([\s\S]*?version\s*=\s*(\d+)""")
        val m = versionPattern.find(content)
        assertTrue("在 ${agentDatabaseKt.path} 里找不到 @Database(version = N)", m != null)
        return m!!.groupValues[1].toInt()
    }

    private fun countEntitiesInAgentDatabaseKt(): Int {
        val content = agentDatabaseKt.readText()
        // 定位 @Database( entities = [ ... ], version = ...) 片段
        val startIdx = content.indexOf("entities = [")
        assertTrue("找不到 entities = [", startIdx >= 0)
        val bracketStart = content.indexOf('[', startIndex = startIdx)
        var depth = 0
        var i = bracketStart
        while (i < content.length) {
            when (content[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        assertTrue("entities = [...] 未闭合", depth == 0 && i < content.length)
        val inside = content.substring(bracketStart + 1, i)
        // 按逗号切；每个元素是 XxxEntity::class（可能换行 + 空格）
        val items = inside.split(',')
            .map { it.trim() }
            .filter { it.contains("::class") }
        assertTrue("entities 数组解析到 0 项", items.isNotEmpty())
        return items.size
    }

    // ══════════════════════════════════════════════════════════════
    // Phase C-RC67 新增 3 道闸门（P1-4 + P2-3）
    // ══════════════════════════════════════════════════════════════

    // ─────────────────────────────────────────────────────────────
    // 4) DUPLICATE-VERSION-TEST: 单个版本号对应多个 SQL 文件时 fail（防止 MigrationLoader 覆盖丢数据）
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `DUPLICATE-VERSION-TEST - 单个版本号只允许一个 SQL，防止后加入的 migration 静默覆盖前一个`() {
        val migrations = listMigrationFiles()
        val dup = migrations.groupBy { it.version }.filterValues { it.size >= 2 }
        if (dup.isEmpty()) return
        val preview = dup.entries.take(5).joinToString("; ") { (v, files) ->
            "v$v → ${files.joinToString(", ") { it.fileName }}"
        }
        fail(
            "发现 ${dup.size} 个版本号被写成多个 SQL 文件（如 33_a.sql + 33_b.sql）。" +
                    "MigrationLoader 以 Map<Int, Migration> 存储，后加入的会把前一个的迁移脚本完全覆盖丢到！" +
                    "请把同一版本多条语句写进同一个 SQL 里（用 ; 分隔），不要拆多个同名前缀文件。\n违规列表: $preview"
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 5) SQL-SEMICOLON-TEST: SQL 字符串字面量内不允许出现 ';'，防止 MigrationLoader.split(';') 把语句切两半
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `SQL-SEMICOLON-TEST - 迁移 SQL 字符串字面量内不得包含分号字符（防 split(';') 错切）`() {
        val migrations = listMigrationFiles()
        val bad = mutableListOf<Pair<String, Int>>()
        for (mf in migrations) {
            val txt = mf.file.readText()
            // 简易解析 SQL 字符串字面量：以 ' 开头直到下一个非转义 '
            // 规则：遇到单引号开启字符串；字符串内 '' 是 SQLite 转义的单引号；遇到单独的 ' 结束
            var inString = false
            var line = 1
            var j = 0
            while (j < txt.length) {
                val c = txt[j]
                if (c == '\n') {
                    line++; j++; continue
                }
                if (!inString && c == '\'') {
                    inString = true; j++; continue
                }
                if (inString) {
                    if (c == '\'') {
                        // 看 j+1 是不是也是 ' → SQLite '' 转义
                        if (j + 1 < txt.length && txt[j + 1] == '\'') {
                            j += 2; continue
                        } else {
                            inString = false; j++; continue
                        }
                    }
                    if (c == ';') {
                        bad.add(mf.fileName to line)
                        // 一个文件命中一次就够，跳过这个文件
                        break
                    }
                }
                j++
            }
        }
        if (bad.isEmpty()) return
        fail(
            "发现 ${bad.size} 处 SQL 字符串字面量内含 ';' 字符。\n" +
                    "MigrationLoader L112 用 split(';') 切语句，字符串里的 ';' 会被当作语句分隔，导致：\n" +
                    "  - 前半截 execSQL 抛 SQLITE_ERROR（字符串字面量未闭合）\n" +
                    "  - 后半截变成残缺语句也抛错\n" +
                    "请把这些 SQL 重写（例如把 ; 改为 SQLite char(59)，或改变字符串编码）。\n" +
                    "违规文件：\n  - " + bad.joinToString("\n  - ") { "${it.first}:${it.second}" }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 6) ENTITY-IDENTITY-TEST: 除数量一致外，两边 entity 的 FQCN 集合必须完全相等，防止错位（AEntity vs BEntity 数量一样但顺序不同）
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `ENTITY-IDENTITY-FQCN-TEST - ALL_ENTITY_CLASSES 与 @Database entities 数组 FQCN 逐项严格相等`() {
        val rescueFqcns: List<String> = LightweightSchemaRescue.ALL_ENTITY_CLASSES.map { it.canonicalName }
        val dbEntityFqcns: List<String> = parseEntityFqcnListFromAgentDatabaseKt()
        assertEquals(
            "两侧 entity 数量不一致（前面的 ENTITY-COUNT-TEST 应该已经挡住，但若走到这里说明解析器 bug）",
            dbEntityFqcns.size, rescueFqcns.size
        )
        val mismatches = mutableListOf<String>()
        for (i in rescueFqcns.indices) {
            val r = rescueFqcns[i]
            val a = dbEntityFqcns[i]
            if (r != a) {
                mismatches.add("  位置 ${i + 1}: rescue = $r  vs  AgentDatabase.entities[$i] = $a")
            }
        }
        // 另外比集合内容（防止顺序一致但集合不一样，不过上面逐项已经 catch 了）
        val setR = rescueFqcns.toSet()
        val setA = dbEntityFqcns.toSet()
        val missingInR = (setA - setR).toList()
        val extraInR = (setR - setA).toList()
        if (missingInR.isNotEmpty() || extraInR.isNotEmpty() || mismatches.isNotEmpty()) {
            fail(
                "ENTITY-IDENTITY 不一致！加表/删表/改包时两边一定要对应。\n" +
                        (if (mismatches.isNotEmpty()) "顺序错位 (${mismatches.size} 处):\n${mismatches.joinToString("\n")}\n" else "") +
                        (if (missingInR.isNotEmpty()) "ALL_ENTITY_CLASSES 缺失 FQCN: ${missingInR.joinToString(", ")}\n" else "") +
                        (if (extraInR.isNotEmpty()) "@Database entities 缺失 FQCN（ALL_ENTITY_CLASSES 有但 entities 没写）: ${extraInR.joinToString(", ")}\n" else "") +
                        "两边完整列表:\n" +
                        "  rescue ALL_ENTITY_CLASSES: \n    - ${rescueFqcns.joinToString("\n    - ")}\n" +
                        "  AgentDatabase.entities: \n    - ${dbEntityFqcns.joinToString("\n    - ")}"
            )
        }
    }

    // ── ENTITY-IDENTITY-TEST 辅助：静态解析 AgentDatabase.kt @Database entities=[...] 到 FQCN 列表 ──
    private fun parseEntityFqcnListFromAgentDatabaseKt(): List<String> {
        val content = agentDatabaseKt.readText()
        // 1) 先扫整个文件的 import → shortName -> FQCN 映射
        val imports = mutableMapOf<String, String>()
        Regex("""^\s*import\s+([a-zA-Z0-9_\.]+)\s*$""", RegexOption.MULTILINE)
            .findAll(content)
            .forEach { m ->
                val fq = m.groupValues[1]
                imports[fq.substringAfterLast('.')] = fq
            }
        // 2) 再取 entities = [...] 里面（复用上面 countEntitiesInAgentDatabaseKt 的逻辑）
        val startIdx = content.indexOf("entities = [")
        assertTrue("找不到 entities = [", startIdx >= 0)
        val bracketStart = content.indexOf('[', startIndex = startIdx)
        var depth = 0
        var i = bracketStart
        while (i < content.length) {
            when (content[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        assertTrue("entities = [...] 未闭合", depth == 0 && i < content.length)
        val inside = content.substring(bracketStart + 1, i)
        // 3) 按逗号拆 / 过滤含 ::class 的项
        val result = mutableListOf<String>()
        inside.split(',').map { it.trim() }.filter { it.contains("::class") }.forEach { raw ->
            // 去掉行内 // 注释
            val line = raw.substringBefore("//").trim()
            val m = Regex("""([A-Za-z0-9_\.]+)::class""").find(line)
            assertTrue("无法从 entity 行解析 Class 名: $raw", m != null)
            val name = m!!.groupValues[1]
            val fqcn = if ('.' in name) name else imports[name]
            assertTrue(
                "entity 标识符 $name 无法映射到 FQCN（import 是否漏了？）raw=$raw",
                fqcn != null
            )
            result.add(fqcn!!)
        }
        assertTrue("entity FQCN 列表空", result.isNotEmpty())
        return result
    }
}
