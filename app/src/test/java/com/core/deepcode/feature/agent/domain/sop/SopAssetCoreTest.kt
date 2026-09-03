package com.core.deepcode.feature.agent.domain.sop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SopAssetCore 单测（D4-1/D4-4，对齐 norm-chain-design.md §3.2 SOP 标准作业）：
 * frontmatter 解析（name/order/whenToUse）/ order 回退文件名数字前缀 /
 * whenToUse 回退正文首段 / 编号步骤正文保持 / order 排序 / mtime 热加载。
 *
 * 验收对照（§3.2 验收）：JVM 单测解析 frontmatter（whenToUse/order/步骤编号）；
 * findByName 返回完整结构化正文（loadSop 按需取正文的数据源，§3.2 正文契约「操作 + 判定 + 产出/出错处理」）。
 */
class SopAssetCoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sopDir(): File = tempFolder.newFolder("sop")!!

    private fun write(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun core(dir: File) = SopAssetCore(
        sopDir = { dir },
        readFile = { file -> runCatching { file.readText() }.getOrNull() }
    )

    // ---------- frontmatter 解析 ----------

    @Test
    fun parse_frontmatterFields() {
        val dir = sopDir()
        write(File(dir, "10-release.md"), """
            ---
            name: 10-release
            order: 10
            whenToUse: 需要发版时
            source: AGENTS.md「发版流程」
            ---
            ## 1. 判定是否需要先发 RC
            - **操作**：按改动面判定。
            - **判定**：含新功能必须先发 RC。
            - **产出**：得出 RC 结论。
        """.trimIndent())

        val asset = core(dir).findByName("10-release")!!
        assertEquals("10-release", asset.name)
        assertEquals(10, asset.order)
        assertEquals("需要发版时", asset.whenToUse)
        // 编号步骤正文契约：操作 + 判定 + 产出
        assertTrue(asset.body.contains("## 1. 判定是否需要先发 RC"))
        assertTrue(asset.body.contains("**操作**"))
        assertTrue(asset.body.contains("**判定**"))
        assertTrue(asset.body.contains("**产出**"))
    }

    @Test
    fun parse_noFrontmatter_fallsBackToFilenameAndNumericPrefix() {
        val dir = sopDir()
        write(File(dir, "20-migration.md"), "## 1. 判断改动归属\n正文")

        val asset = core(dir).findByName("20-migration")!!
        assertEquals("20-migration", asset.name)
        assertEquals(20, asset.order)
        assertTrue(asset.whenToUse.isNotEmpty()) // 回退正文首段
    }

    @Test
    fun parse_whenToUse_fallsBackToBodyPrefix() {
        val dir = sopDir()
        write(File(dir, "30-asset-sync.md"), "## 1. 判定改动类别\n- **操作**：…")

        val asset = core(dir).findByName("30-asset-sync")!!
        assertTrue(asset.whenToUse.contains("判定改动类别"))
    }

    @Test
    fun all_sortedByOrder() {
        val dir = sopDir()
        write(File(dir, "60-ai-conduct.md"), sop("60-ai-conduct", 60, "AI 行为纪律"))
        write(File(dir, "10-release.md"), sop("10-release", 10, "发版"))
        write(File(dir, "40-git-commit.md"), sop("40-git-commit", 40, "提交"))

        val names = core(dir).all().map { it.name }
        assertEquals(listOf("10-release", "40-git-commit", "60-ai-conduct"), names)
    }

    @Test
    fun findByName_missing_returnsNull() {
        val dir = sopDir()
        write(File(dir, "10-release.md"), "正文")
        assertNull(core(dir).findByName("not-exist"))
    }

    // ---------- 热加载（mtime 懒刷新） ----------

    @Test
    fun hotReload_refreshesWhenFileChanges() {
        val dir = sopDir()
        val f = File(dir, "10-release.md")
        write(f, sop("10-release", 10, "v1"))
        val c = core(dir)
        assertEquals("v1", c.findByName("10-release")!!.whenToUse)

        Thread.sleep(10) // 确保 mtime 变化
        write(f, sop("10-release", 10, "v2"))
        assertEquals("v2", c.findByName("10-release")!!.whenToUse)
    }

    // ---------- loadSop 取正文数据源（结构化正文完整返回） ----------

    @Test
    fun findByName_returnsFullStructuredBody() {
        val dir = sopDir()
        val body = """
            ## 1. 检查改动面并确认分支
            - **操作**：先确认改动类型与所在分支。
            - **判定**：新功能 → 分支；日常修复 → main 直提。
            - **产出**：确定提交归属。

            ## 2. 提交前必跑冒烟
            - **操作**：改完编译型代码先 assembleDebug。
            - **判定**：编译通过。
            - **产出**：可编译。
        """.trimIndent()
        write(File(dir, "40-git-commit.md"), """---
name: 40-git-commit
order: 40
whenToUse: 需要提交代码时
---
$body
""".trimIndent())

        val asset = core(dir).findByName("40-git-commit")!!
        assertEquals(body, asset.body)
        assertTrue(asset.body.contains("## 1."))
        assertTrue(asset.body.contains("## 2."))
    }

    private fun sop(name: String, order: Int, whenToUse: String): String = """
        ---
        name: $name
        order: $order
        whenToUse: $whenToUse
        ---
        ## 1. 步骤一
        - **操作**：…
        - **判定**：…
        - **产出**：…
    """.trimIndent()
}
