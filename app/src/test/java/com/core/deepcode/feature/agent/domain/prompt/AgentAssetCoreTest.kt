package com.core.deepcode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AgentAssetCore 单测（R03）：frontmatter 解析 / order 排序 / custom 覆盖 /
 * includes 组合与循环防护 / 热加载失效 / 主组件 vs 专项 agent / assets 兜底。
 */
class AgentAssetCoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun promptsDir(): File = tempFolder.newFolder("prompts")
    private fun customDir(): File = tempFolder.newFolder("custom")

    private fun write(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    // ---------- 解析 ----------

    @Test
    fun parse_frontmatterFields() {
        val p = promptsDir()
        write(File(p, "10-comm.md"), """
            ---
            name: communication
            description: 沟通风格
            order: 5
            enabled: true
            agent: false
            mode: [default, plan]
            tools: [readFile, list]
            model: gpt-test
            includes: [identity]
            ---
            <body-text>
        """.trimIndent())

        val core = AgentAssetCore(p, customDir())
        val asset = core.findByName("communication")!!
        assertEquals("10-comm.md", asset.fileName)
        assertEquals("communication", asset.name)
        assertEquals("沟通风格", asset.description)
        assertEquals(5, asset.order) // frontmatter 优先于文件名前缀 10
        assertTrue(asset.enabled)
        assertFalse(asset.agent)
        assertEquals(setOf("default", "plan"), asset.modes)
        assertEquals(listOf("readFile", "list"), asset.tools)
        assertEquals("gpt-test", asset.model)
        assertEquals(listOf("identity"), asset.includes)
        assertEquals("<body-text>", asset.body)
    }

    @Test
    fun parse_noFrontmatter_fallsBackToFilenameAndNumericPrefix() {
        val p = promptsDir()
        write(File(p, "00-identity.md"), "<!-- 注释 -->\n角色正文")

        val core = AgentAssetCore(p, customDir())
        val asset = core.findByName("00-identity")!!
        assertEquals(0, asset.order)
        assertTrue(asset.enabled)
        assertFalse(asset.agent)
        assertEquals(setOf("default"), asset.modes)
        assertEquals("角色正文", asset.body) // 前置注释被剥离
    }

    @Test
    fun parse_stringBooleans() {
        val p = promptsDir()
        write(File(p, "a.md"), """
            ---
            name: a
            enabled: "false"
            agent: "true"
            ---
            body
        """.trimIndent())

        val core = AgentAssetCore(p, customDir())
        val asset = core.findByName("a")!!
        assertFalse(asset.enabled)
        assertTrue(asset.agent)
    }

    // ---------- 排序 ----------

    @Test
    fun ordering_byOrderThenName() {
        val p = promptsDir()
        write(File(p, "30-c.md"), "---\nname: c\norder: 30\n---\nC")
        write(File(p, "10-a.md"), "---\nname: a\norder: 10\n---\nA")
        write(File(p, "10-b.md"), "---\nname: b\norder: 10\n---\nB")
        write(File(p, "zz-no-prefix.md"), "无前缀数字，默认排最后")

        val core = AgentAssetCore(p, customDir())
        val names = core.components().map { it.name }
        assertEquals(listOf("a", "b", "c", "zz-no-prefix"), names)
        // 无前缀文件名缺省 order = Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, core.findByName("zz-no-prefix")!!.order)
    }

    // ---------- custom 覆盖 ----------

    @Test
    fun customDir_overridesSameNameInPrompts() {
        val p = promptsDir()
        val c = customDir()
        write(File(p, "10-a.md"), "---\nname: a\norder: 10\n---\n内置正文")
        write(File(c, "10-a.md"), "---\nname: a\norder: 99\n---\n用户覆盖正文")

        val core = AgentAssetCore(p, c)
        val asset = core.findByName("a")!!
        assertEquals("用户覆盖正文", asset.body)
        assertEquals(99, asset.order) // 元数据随文件一起被覆盖
    }

    @Test
    fun customDir_canAddNewAsset() {
        val p = promptsDir()
        val c = customDir()
        write(File(p, "10-a.md"), "---\nname: a\n---\nA")
        write(File(c, "90-new.md"), "---\nname: new\norder: 90\n---\nNEW")

        val core = AgentAssetCore(p, c)
        assertEquals(listOf("a", "new"), core.components().map { it.name })
    }

    // ---------- includes ----------

    @Test
    fun includes_composedIntoBody() {
        val p = promptsDir()
        write(File(p, "00-base.md"), "---\nname: base\norder: 0\n---\nBASE")
        write(File(p, "10-child.md"), "---\nname: child\norder: 10\nincludes: [base]\n---\nCHILD")

        val core = AgentAssetCore(p, customDir())
        val child = core.findByName("child")!!
        assertEquals("BASE\n\nCHILD", child.body)
        // 被引用方在 components 中仍然独立存在
        assertTrue(core.components().any { it.name == "base" })
    }

    @Test
    fun includes_missing_skipped() {
        val p = promptsDir()
        write(File(p, "10-child.md"), "---\nname: child\nincludes: [not-exist]\n---\nCHILD")

        val core = AgentAssetCore(p, customDir())
        assertEquals("CHILD", core.findByName("child")!!.body)
    }

    @Test
    fun includes_cycle_doesNotHang() {
        val p = promptsDir()
        write(File(p, "00-a.md"), "---\nname: a\nincludes: [b]\n---\nA")
        write(File(p, "10-b.md"), "---\nname: b\nincludes: [a]\n---\nB")

        val core = AgentAssetCore(p, customDir())
        val a = core.findByName("a")!!
        val b = core.findByName("b")!!
        // 循环处跳过，不无限递归
        assertEquals("B\n\nA", a.body)
        assertEquals("A\n\nB", b.body)
    }

    // ---------- 热加载失效 ----------

    @Test
    fun hotReload_mtimeChange_reflected() {
        val p = promptsDir()
        val f = File(p, "10-a.md")
        write(f, "---\nname: a\norder: 10\n---\nOLD")

        val core = AgentAssetCore(p, customDir())
        assertEquals("OLD", core.findByName("a")!!.body)

        // 修改文件内容 + mtime（写入会刷新 mtime；sleep 防同毫秒粒度）
        Thread.sleep(20)
        write(f, "---\nname: a\norder: 10\n---\nNEW")
        assertEquals("NEW", core.findByName("a")!!.body)
    }

    @Test
    fun hotReload_newFileAppears() {
        val p = promptsDir()
        write(File(p, "10-a.md"), "---\nname: a\n---\nA")

        val core = AgentAssetCore(p, customDir())
        assertEquals(1, core.components().size)

        Thread.sleep(20)
        write(File(p, "20-b.md"), "---\nname: b\n---\nB")
        assertEquals(listOf("a", "b"), core.components().map { it.name })
    }

    @Test
    fun hotReload_invalidateForcesRescan() {
        val p = promptsDir()
        write(File(p, "10-a.md"), "---\nname: a\n---\nA")

        val core = AgentAssetCore(p, customDir())
        assertEquals(1, core.components().size)

        // 文件未变，但主动失效也应重扫（FileObserver 语义）
        core.invalidate()
        assertEquals(1, core.components().size)
        assertEquals("A", core.findByName("a")!!.body)
    }

    // ---------- 主组件 vs 专项 agent ----------

    @Test
    fun components_excludesAgents_disabled() {
        val p = promptsDir()
        write(File(p, "00-main.md"), "---\nname: main\n---\nMAIN")
        write(File(p, "10-special.md"), "---\nname: special\nagent: true\n---\nSPECIAL")
        write(File(p, "20-disabled.md"), "---\nname: disabled\nenabled: false\n---\nX")

        val core = AgentAssetCore(p, customDir())
        assertEquals(listOf("main"), core.components().map { it.name })
        assertEquals(listOf("special"), core.agents().map { it.name })
        // all() 含 disabled 与专项 agent
        assertEquals(setOf("main", "special", "disabled"), core.all().map { it.name }.toSet())
        assertNull(core.findByName("not-exist"))
    }

    // ---------- assets 兜底（本地目录为空时） ----------

    @Test
    fun assetsFallback_usedWhenLocalDirsEmpty() {
        val p = promptsDir() // 空目录
        val c = customDir()
        val core = AgentAssetCore(
            p, c,
            assetsList = { listOf("00-asset.md", "readme.txt") },
            assetsRead = { name -> if (name == "00-asset.md") "---\nname: asset\n---\nBUILTIN" else null }
        )
        val asset = core.findByName("asset")!!
        assertEquals("BUILTIN", asset.body)
    }
}
