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
            val v = f.name.substringBefore('_').toIntOrNull()
                ?: fail("迁移文件名无法解析版本号前缀: ${f.name}（格式应为 <version>_xxx.sql）")
            MigrationFile(f.name, v, f)
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
}
