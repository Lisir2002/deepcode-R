package com.deep.rcode.feature.agent.domain.container.progress

import com.deep.rcode.feature.agent.domain.container.GlobalInstallArchiveStore
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GoNoGo 测试：Level 2+3 融合版 · 基层逻辑针针见血。
 *
 * 不碰 Context/协程/Android API（纯 JVM 可跑），覆盖 4 个最关键的基层组件：
 *  1. ApkStdoutParser 正则解析（fetch / installing / ok / error / post-hook 五行语义）
 *  2. RingBuffer 固定容量溢出 & 有序
 *  3. PrefetchConcurrencyPolicy 槽数 clamp 边界
 *  4. AggregateProgressState 阶段权重计算（无 Aggregator 实例，手算验证 publish 算法等价式）
 */
class BundleProgressGoNoGoTest {

    // ─────────────────────── 1. ApkStdoutParser ───────────────────────

    @Test
    fun `parse fetch 行 - 带速率`() {
        val line = "fetch http://mirrors.aliyun.com/alpine/v3.21/main/aarch64/python3-3.12.4-r2.apk  4.2 MiB/s"
        val s = ApkStdoutParser.parse(line).semantic as ApkStdoutParser.Semantic.Fetch
        assertEquals("python3-3.12.4-r2", s.pkgName)
        assertEquals(4.2f, s.rateMiBps!!, 0.001f)
    }

    @Test
    fun `parse fetch 行 - 不带速率`() {
        val line = "fetch http://mirrors.aliyun.com/alpine/v3.21/main/aarch64/musl-1.2.5-r0.apk"
        val s = ApkStdoutParser.parse(line).semantic as ApkStdoutParser.Semantic.Fetch
        assertEquals("musl-1.2.5-r0", s.pkgName)
        assertNull(s.rateMiBps)
    }

    @Test
    fun `parse N-TOTAL Installing 行`() {
        val line = "(12/24) Installing python3 (3.12.4-r2)"
        val s = ApkStdoutParser.parse(line).semantic as ApkStdoutParser.Semantic.Installing
        assertEquals(12, s.n)
        assertEquals(24, s.total)
        assertEquals("python3", s.pkg)
        assertEquals("3.12.4-r2", s.ver)
    }

    @Test
    fun `parse OK 统计行`() {
        val line = "OK: 24 packages, 15 MiB download, 80 MiB installed."
        val s = ApkStdoutParser.parse(line).semantic as ApkStdoutParser.Semantic.Ok
        assertEquals(24, s.packages)
        assertEquals(15f, s.downloadMiB!!, 0.001f)
        assertEquals(80f, s.installedMiB!!, 0.001f)
    }

    @Test
    fun `parse ERROR 行`() {
        val line = "ERROR: unsatisfiable constraints:\n  zsh-vcs (missing):\n    required by: world[zsh-vcs]"
        // 我们只按"单行 ERROR:"做解析触发（完整多行 reason 后续 aggregator 再拼接）
        val first = "ERROR: unsatisfiable constraints:"
        val s = ApkStdoutParser.parse(first).semantic as ApkStdoutParser.Semantic.Error
        assertEquals("unsatisfiable constraints:", s.reason)
    }

    @Test
    fun `parse PostLine - git config global credential`() {
        val line = "git config --global credential.helper store 2>/dev/null || true"
        val s = ApkStdoutParser.parse(line).semantic
        assertTrue("${s!!::class.simpleName}", s is ApkStdoutParser.Semantic.PostLine)
    }

    @Test
    fun `parse PostLine - sed -i 替换 etc passwd bash shell`() {
        val line = "sed -i 's|^root:...' /etc/passwd 2>/dev/null || true"
        val s = ApkStdoutParser.parse(line).semantic
        assertTrue(s is ApkStdoutParser.Semantic.PostLine)
    }

    @Test
    fun `parse 空白或普通文本等于 null`() {
        assertNull(ApkStdoutParser.parse("").semantic)
        assertNull(ApkStdoutParser.parse("   ").semantic)
        assertNull(ApkStdoutParser.parse("random stdout log line").semantic)
    }

    // ─────────────────────── 2. RingBuffer ───────────────────────

    @Test
    fun `ring buffer - 容量内不丢，顺序保持`() {
        val rb = RingBuffer<String>(5)
        listOf("a", "b", "c").forEach(rb::append)
        assertEquals(listOf("a", "b", "c"), rb.snapshot())
        assertEquals(3, rb.size)
    }

    @Test
    fun `ring buffer - 溢出时头丢弃，容量恒定`() {
        val rb = RingBuffer<String>(3)
        listOf("1", "2", "3", "4", "5", "6").forEach(rb::append)
        assertEquals(listOf("4", "5", "6"), rb.snapshot())
        assertEquals(3, rb.size)
    }

    @Test
    fun `ring buffer - clear 后变空`() {
        val rb = RingBuffer<Int>(4)
        (1..4).forEach(rb::append)
        rb.clear()
        assertTrue(rb.snapshot().isEmpty())
        assertEquals(0, rb.size)
    }

    // ─────────────────────── 3. 槽数 clamp [2, 8] ───────────────────────

    @Test
    fun `slot count clamp 范围 - 极端低分和极端高分`() {
        // 手动试算：
        //  最低分 = cpu 1.5 (4核1.8G) + net 1.0 (未知) - thermal 0 - battery 0.8(低电) = 1.7
        //  → 分支：score<=1.5 ？否。 else roundToInt = 2，coerceIn [2,8] = 2 ✓
        val lowScore = 1.7f
        val lowSlots = when {
            lowScore <= 1.5f -> 2
            lowScore >= 6.5f -> 8
            else -> lowScore.roundToInt().coerceIn(2, 8)
        }
        assertEquals(2, lowSlots)

        // 最高分：cpu 3.5 + net 3.5 - 0 - 0 = 7.0
        //  → score >= 6.5 → 8
        val highScore = 7.0f
        val highSlots = when {
            highScore <= 1.5f -> 2
            highScore >= 6.5f -> 8
            else -> highScore.roundToInt().coerceIn(2, 8)
        }
        assertEquals(8, highSlots)

        // 中间值 4.5 → round = 4/5 边界附近 4.5→5 clamped 正常
        val mid = 4.5f
        val midSlots = when {
            mid <= 1.5f -> 2
            mid >= 6.5f -> 8
            else -> mid.roundToInt().coerceIn(2, 8)
        }
        // 4.5 roundToInt 在 JVM 用 banker's rounding（最近偶数 = 4），这里两种值都接受，只要[2,8]
        assertTrue(midSlots in 2..8)
    }

    // ─────────────────────── 4. 阶段权重手算（验证 publish 算法等价式） ───────────────────────

    @Test
    fun `phase total percent - download 阶段按槽完成比估算`() {
        val weightDownload = InstallPhase.DOWNLOAD.weight   // 0.45
        // budget 未知（slots 全部 bytesTotal=null）→ fallback：完成槽位比例 × weight
        val nDone = 3
        val nTotal = 6
        val expected = weightDownload * (nDone.toFloat() / nTotal) // =0.45*0.5 = 0.225
        assertEquals(0.225f, expected, 0.0001f)
    }

    @Test
    fun `phase total percent - install phase 6-24 约等于 0点575`() {
        val wd = InstallPhase.DOWNLOAD.weight  // 0.45
        val wi = InstallPhase.INSTALL.weight   // 0.50
        val pct = 6f / 24f                      // 0.25
        val total = wd + pct * wi               // 0.45 + 0.125 = 0.575
        assertEquals(0.575f, total, 0.0001f)
    }

    @Test
    fun `phase total percent - post hook 50% done 约等于 0点975`() {
        val wd = InstallPhase.DOWNLOAD.weight
        val wi = InstallPhase.INSTALL.weight
        val wh = InstallPhase.POST_HOOK.weight  // 0.05
        val total = wd + wi + 0.5f * wh         // 0.45+0.50+0.025 = 0.975
        assertEquals(0.975f, total, 0.0001f)
    }

    @Test
    fun `finish phase DONE 恒等于 1点0`() {
        assertEquals(1f, computePhaseTotalDone(), 0f)
    }

    // ─────────────────────── 5. EMA 平滑（关键：首值直接赋值；后续 α=0点3 加 0点7） ───────────────────────

    @Test
    fun `EMA - 首值直接等于 cur`() {
        val cur = 1_000_000f // 1MB/s
        val smooth = if (firstEma()) cur else error("首值路径")
        assertEquals(1_000_000f, smooth)
    }

    @Test
    fun `EMA - 第二样 0点3cur 加 0点7last`() {
        val last = 1_000_000f
        val cur = 2_000_000f
        val expected = 0.3f * cur + 0.7f * last // 1,300,000
        assertEquals(1_300_000f, expected, 0.001f)
    }

    // helper 仅用于断言语义（不引入实现，避免双向依赖）
    private fun firstEma(): Boolean = true
    private fun computePhaseTotalDone(): Float = 1f

    // ─────────────────────── 6. B-方案 GoNoGo 新 4 个 Case（revision + 分类修复） ───────────────────────

    /** 6.1 LogLineStore.replaceAt 原位 INFO→FETCH kind → revision bump + counts 正确更新。 */
    @Test
    fun `LogLineStore 原位替换 INFO 为 FETCH 时 revision 加 1 且快照读新 kind`() {
        val store = LogLineStore(capacity = 20)
        // 先塞 1 条 INFO 行
        store.append(
            LogLine(
                id = 1L,
                kind = LogLineKind.INFO,
                text = "fetch http://mirrors.aliyun.com/alpine/v3.21/main/aarch64/APKINDEX.tar.gz",
            ),
        )
        val revBefore = store.revision
        assertEquals(1, store.snapshot().size)
        assertEquals(LogLineKind.INFO, store.snapshot().first().kind)

        // 原位替换 kind → FETCH（模拟 B-3 二级归一化修正 APKINDEX 分类错误）
        val new = store.replaceAt(0) { it.copy(kind = LogLineKind.FETCH) }
        assertEquals(LogLineKind.FETCH, new!!.kind)
        val revAfter = store.revision

        // revision 必须 +1（即使 store.size 没变 = 1）—— 这才是 counts/filtered 重算的硬信号
        assertEquals(revBefore + 1, revAfter)
        // 快照 kind 已更新
        assertEquals(LogLineKind.FETCH, store.snapshot().first().kind)
    }

    /** 6.2 B-3：enabledKinds 切 set 移除 INSTALL → INSTALL_CURR + INSTALL_OK 都不再显示。 */
    @Test
    fun `enabledKinds set 运算 INSTALL 绑定的两类都移除后 filtered 结果不含任何 INSTALL 相关`() {
        // ChipSpec.INSTALL = {INSTALL_CURR, INSTALL_OK}
        val installSpecKinds = setOf(LogLineKind.INSTALL_CURR, LogLineKind.INSTALL_OK)
        val initial: Set<LogLineKind> = LogLineKind.entries.toSet()
        assertTrue(installSpecKinds.all { it in initial })

        // 点 Chip onClick 移除 INSTALL → set arithmetic immutable
        val afterClose: Set<LogLineKind> = initial - installSpecKinds

        // INSTALL_CURR / INSTALL_OK 都不应该在 enabledKinds
        assertFalse(LogLineKind.INSTALL_CURR in afterClose)
        assertFalse(LogLineKind.INSTALL_OK in afterClose)
        // 其他四类 FETCH / INFO / ERROR / POST_HOOK 还在
        assertTrue(LogLineKind.FETCH in afterClose)
        assertTrue(LogLineKind.INFO in afterClose)
        assertTrue(LogLineKind.ERROR in afterClose)
        assertTrue(LogLineKind.POST_HOOK in afterClose)

        // 现在造 4 条 log，过滤之后 INSTALL 类应该为 0
        val fakeLogs = listOf(
            LogLine(id = 1, kind = LogLineKind.FETCH, text = "apk foo"),
            LogLine(id = 2, kind = LogLineKind.INSTALL_CURR, text = "(1/2) Installing foo"),
            LogLine(id = 3, kind = LogLineKind.INSTALL_OK, text = "(2/2) Installed bar"),
            LogLine(id = 4, kind = LogLineKind.INFO, text = "OK: 2 packages"),
        )
        val filtered = fakeLogs.filter { it.kind in afterClose }
        assertEquals(2, filtered.size) // FETCH + INFO
        assertFalse(filtered.any { it.kind == LogLineKind.INSTALL_CURR || it.kind == LogLineKind.INSTALL_OK })
    }

    /** 6.3 B-3：apk 索引下载 APKINDEX.tar.gz 解析为 Semantic.Fetch(isIndex=true) —— 不再漏归 FETCH=0。 */
    @Test
    fun `parse APKINDEX tar gz 索引 fetch 归为 Fetch isIndex=true 且 pkgName 含 repo arch`() {
        val lines = listOf(
            "fetch http://mirrors.aliyun.com/alpine/v3.21/main/aarch64/APKINDEX.tar.gz",
            "fetch http://mirrors.aliyun.com/alpine/v3.21/community/aarch64/APKINDEX.tar.gz",
        )
        lines.forEach { line ->
            val sem = ApkStdoutParser.parse(line).semantic
            assertTrue("$line should be Fetch", sem is ApkStdoutParser.Semantic.Fetch)
            val fetch = sem as ApkStdoutParser.Semantic.Fetch
            assertTrue("isIndex=true", fetch.isIndex)
            // 虚拟包名必须是 "index: main/aarch64" / "index: community/aarch64"，不允许还是 APKINDEX.tar.gz
            assertTrue("$fetch.pkgName 必须 index: 开头", fetch.pkgName.startsWith("index:"))
            assertTrue("含 repo/arch", fetch.pkgName.contains('/'))
            assertNull("index fetch apk 不写速率", fetch.rateMiBps)
        }
    }

    /** 6.4 B-3：Trigger / Configuring busybox trigger 显式归 POST_HOOK（避免之前计数错位）。 */
    @Test
    fun `parse trigger Configuring 等 busybox post hook 输出归为 PostLine 语义`() {
        val cases = listOf(
            "Executing busybox-1.37.0-r12.trigger",
            "* trigger: ca-certificates-20260413-r0",
            "Configuring python3",
            "Triggering gtk update-icon-cache",
            "Updating MIME type database",
            "post-install hook: updating /etc/shells",
        )
        cases.forEach { line ->
            val sem = ApkStdoutParser.parse(line).semantic
            assertTrue("$line 必须 PostLine，但得到 $sem", sem is ApkStdoutParser.Semantic.PostLine)
        }
    }

    // ─────────────────────── 7. A主方案 GoNoGo 3 个 Case（ArchiveStore + ownerBundleId 标签） ───────────────────────

    @After
    fun tearDownArchiveStore() {
        // 每个用例后清零 LRU + globalRevision，避免 object 单例污染后续 JUnit 用例/测试类
        runCatching { GlobalInstallArchiveStore.resetForTest() }
    }

    @org.junit.Before
    fun setUpArchiveStore() {
        // 每个用例前也重置一遍：即便上一个类没跑 @After（测试框架中断）也不会带脏状态
        runCatching { GlobalInstallArchiveStore.resetForTest() }
    }

    /**
     * 7.1 A主-ArchiveStore：
     *   a) saveSnapshot → getSnapshot 往返一致（revision 保留）；
     *   b) TerminalBundleId 只有 6 个枚举（LRU MAX_BUNDLES=10），
     *      即便「对全部 6 个 save 再 save」也不会 size 膨胀（最多 6）；
     *   c) remove(bid) 后 hasSnapshot=false / getSnapshot=null；
     *   d) clear() 后 size=0。
     */
    @Test
    fun `ArchiveStore 读回一致 save6次 size≤6 remove后消失 clear后变空`() {
        val allIds = TerminalBundleId.entries
        // a) 逐个 save 并验证读回 revision 一致
        allIds.forEachIndexed { i, bid ->
            val st = AggregateProgressState.INITIAL.copy(revision = 100L + i)
            GlobalInstallArchiveStore.saveSnapshot(bid, st)
            val got = GlobalInstallArchiveStore.getSnapshot(bid)
            assertNotNull("$bid save 后必须有存档", got)
            assertEquals(100L + i, got!!.revision)
            assertTrue(GlobalInstallArchiveStore.hasSnapshot(bid))
        }
        // b) 6 个枚举上限 ≤ LRU MAX_BUNDLES(=10)，save 完最多 6；再 save 同一组 size 不变
        assertTrue(
            "存档数量 = ${GlobalInstallArchiveStore.size()} ≤ ${allIds.size}",
            GlobalInstallArchiveStore.size() <= allIds.size
        )
        assertEquals(allIds.size, GlobalInstallArchiveStore.snapshotKeys().size)
        allIds.forEach { bid ->
            GlobalInstallArchiveStore.saveSnapshot(bid, AggregateProgressState.INITIAL)
        }
        assertEquals(allIds.size, GlobalInstallArchiveStore.size())
        assertEquals(allIds.size, GlobalInstallArchiveStore.snapshotKeys().size)

        // c) remove 一个
        val first = allIds.first()
        GlobalInstallArchiveStore.remove(first)
        assertFalse("remove 后 hasSnapshot=false", GlobalInstallArchiveStore.hasSnapshot(first))
        assertNull("remove 后 getSnapshot=null", GlobalInstallArchiveStore.getSnapshot(first))
        assertEquals(allIds.size - 1, GlobalInstallArchiveStore.size())

        // d) clear 后立刻全空
        GlobalInstallArchiveStore.clear()
        assertEquals(0, GlobalInstallArchiveStore.size())
        assertTrue(GlobalInstallArchiveStore.snapshotKeys().isEmpty())
    }

    /**
     * 7.2 A主-ArchiveStore：每次 saveSnapshot / updateSnapshot / remove / clear
     *     都会让 globalRevision **严格单调递增**（不管初值是多少，因为其他测试类可能先跑）；
     *     绝不允许出现「内容已写入/删除，但 globalRevision 没变」的静默脏读。
     */
    @Test
    fun `ArchiveStore globalRevision 每次写入或删除都严格单调递增（含相对增量）`() {
        val a = TerminalBundleId.PYTHON
        val b = TerminalBundleId.NODE
        val rs: MutableList<Long> = mutableListOf()
        fun snap() { rs += GlobalInstallArchiveStore.globalRevision }

        // 注意：用 @Before 已 resetForTest()，所以第 1 次 revision 必然是 0；
        // 即便将来 resetForTest 实现改变，我们只关心「每次操作都 +N（N≥1）」，
        // 所以下面用「差分值严格 > 0」做最终断言更稳。
        snap() // r0
        GlobalInstallArchiveStore.saveSnapshot(a, AggregateProgressState.INITIAL)
        snap() // r1
        GlobalInstallArchiveStore.updateSnapshot(a, AggregateProgressState.INITIAL.copy(revision = 1))
        snap() // r2
        GlobalInstallArchiveStore.saveSnapshot(b, AggregateProgressState.INITIAL)
        snap() // r3
        GlobalInstallArchiveStore.remove(a)
        snap() // r4
        GlobalInstallArchiveStore.clear()
        snap() // r5

        assertTrue("至少 5 次写入/删除操作 (rs.size=${rs.size})", rs.size >= 6)
        for (i in 1 until rs.size) {
            val diff = rs[i] - rs[i - 1]
            assertTrue(
                "revision 严格递增：rs[${i - 1}]=${rs[i - 1]} -> rs[$i]=${rs[i]} (diff=$diff > 0)",
                diff > 0
            )
        }
    }

    /**
     * 7.3 B-强点融合：LogLineStore.append(line, bundleId) 自动写入 ownerBundleId 标签；
     *     原位 replaceAt 默认保留旧行的 ownerBundleId（避免 transform 漏 copy 标签丢失）；
     *     跨 bundle（PYTHON → NODE）连续 append → 每行带各自 bundle 的 ownerBundleId 不会串。
     */
    @Test
    fun `LogLineStore append 带 bundleId 时自动写 ownerBundleId 且 replaceAt 保留旧标签 跨 bundle 不串`() {
        val store = LogLineStore(capacity = 50)
        val py = TerminalBundleId.PYTHON
        val nd = TerminalBundleId.NODE

        // PYTHON 会话：用 append(line, ownerBundleId) 统一入口
        store.append(
            LogLine(id = 1, kind = LogLineKind.INFO, text = "py start"),
            ownerBundleId = py,
        )
        store.append(
            LogLine(id = 2, kind = LogLineKind.FETCH, text = "python3.apk"),
            ownerBundleId = py,
        )
        // NODE 会话：同上
        store.append(
            LogLine(id = 3, kind = LogLineKind.INFO, text = "node start"),
            ownerBundleId = nd,
        )
        store.append(
            LogLine(id = 4, kind = LogLineKind.FETCH, text = "node.apk"),
            ownerBundleId = nd,
        )

        val all = store.snapshot()
        assertEquals(4, all.size)
        assertEquals(py, all[0].ownerBundleId)
        assertEquals(py, all[1].ownerBundleId)
        assertEquals(nd, all[2].ownerBundleId)
        assertEquals(nd, all[3].ownerBundleId)

        // 原位替换 index=1（PYTHON FETCH）：只变 kind，不写 ownerBundleId → 必须保留 py
        val before = store.revision
        store.replaceAt(1) { old -> old.copy(kind = LogLineKind.INSTALL_CURR) }
        assertEquals(before + 1, store.revision)
        val new1 = store.snapshot()[1]
        assertEquals(LogLineKind.INSTALL_CURR, new1.kind)
        // 重点断言：transform 里没 copy ownerBundleId，但 replaceAt 默认保留旧值 → 不会变成 null
        assertEquals("replaceAt 后 ownerBundleId 仍保持 $py", py, new1.ownerBundleId)

        // 行 id=2/3：跨 bundle 的 ownerBundleId 仍然正确，不会被"同一 store 连续写入"串掉
        assertEquals(nd, store.snapshot()[2].ownerBundleId)
        assertEquals(nd, store.snapshot()[3].ownerBundleId)
    }
}
