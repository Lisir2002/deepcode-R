package com.core.deepcode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StepInjectionAssembler 单测（D1-2，对齐 norm-chain-design.md §3.1.2）：
 * 八源排序（P0 → P1 → P2，同 importance 按 order）+ 注入预算裁剪（先裁 P2 → 再裁 P1，P0 永不裁）。
 */
class StepInjectionAssemblerTest {

    private val goal = "goal"                    // P0
    private val intent = "intent-ask"            // P1 order 1
    private val behavior = "behavior-mode"       // P1 order 2
    private val planPending = "plan-pending"     // P1 order 3
    private val playbook = "playbook-stage"      // P1 order 4
    private val adjustEvent = "goal-adjust"      // P1 order 5
    private val stale = "goal-stale"             // P2 order 6
    private val loop = "loop-advisory"           // P2 order 7

    // ---------- 八源排序 ----------

    @Test
    fun sort_ordersByImportanceThenByOrder() {
        val assembler = StepInjectionAssembler(budgetChars = 10_000)
        // 打乱输入顺序，验证输出仍按 P0→P1→P2 + 同 P1 内固定顺序
        val entries = listOf(
            entry(StepInjectionAssembler.Importance.P2, 7, loop),
            entry(StepInjectionAssembler.Importance.P1, 1, intent),
            entry(StepInjectionAssembler.Importance.P0, 0, goal),
            entry(StepInjectionAssembler.Importance.P2, 6, stale),
            entry(StepInjectionAssembler.Importance.P1, 5, adjustEvent),
            entry(StepInjectionAssembler.Importance.P1, 2, behavior),
            entry(StepInjectionAssembler.Importance.P1, 4, playbook),
            entry(StepInjectionAssembler.Importance.P1, 3, planPending)
        )
        val out = assembler.assemble(entries)!!
        val parts = out.split("\n\n")
        // P0 → 全部 P1（按 order）→ 全部 P2（按 order）
        assertEquals(goal, parts[0])
        assertEquals(listOf(intent, behavior, planPending, playbook, adjustEvent), parts.subList(1, 6))
        assertEquals(listOf(stale, loop), parts.subList(6, 8))
    }

    // ---------- 预算裁剪 ----------

    @Test
    fun trim_whenOverBudget_trimsP2First() {
        // 预算只够 P0 + 全部 P1（不含分隔符），P2 全部被裁
        val p0p1 = goal.length + intent.length + behavior.length + planPending.length +
            playbook.length + adjustEvent.length + 5 * 2 // 6 条 P0/P1 之间的 5 个分隔符
        val assembler = StepInjectionAssembler(budgetChars = p0p1)
        val entries = fullEntries()
        val out = assembler.assemble(entries)!!
        val parts = out.split("\n\n")
        assertFalse("P2 应被裁剪", parts.contains(stale))
        assertFalse("P2 应被裁剪", parts.contains(loop))
        assertTrue("P0 保留", parts.contains(goal))
        assertTrue("P1 保留", parts.contains(intent))
    }

    @Test
    fun trim_whenSeverelyOverBudget_trimsP2ThenP1_keepsP0() {
        // 预算极小（只够 P0 + 第一条 P1），P2 全裁 + 大部分 P1 裁，P0 永不裁
        val tiny = goal.length + intent.length + 2 // goal + intent + 分隔符
        val assembler = StepInjectionAssembler(budgetChars = tiny)
        val out = assembler.assemble(fullEntries())!!
        val parts = out.split("\n\n")
        // 同 P1 按注入顺序倒序裁剪：adjustEvent → playbook → planPending → behavior → intent（intent 最后裁）
        assertEquals(listOf(goal, intent), parts)
    }

    @Test
    fun trim_whenP0Only_neverTrimsP0() {
        // 预算比 P0 本身还小，P0 仍必须保留（P0 永不裁）
        val assembler = StepInjectionAssembler(budgetChars = 1)
        val out = assembler.assemble(fullEntries())
        assertTrue(out!!.contains(goal))
    }

    @Test
    fun trim_whenWithinBudget_keepsAll() {
        val assembler = StepInjectionAssembler(budgetChars = 10_000)
        val entries = fullEntries()
        val out = assembler.assemble(entries)!!
        assertEquals(entries.size, out.split("\n\n").size)
    }

    // ---------- 边界 ----------

    @Test
    fun emptyEntries_returnsNull() {
        assertNull(StepInjectionAssembler().assemble(emptyList()))
    }

    @Test
    fun allEntriesTrimmed_returnsNull() {
        // 只有 P1/P2（无 P0），预算为 0 → 全部被裁 → null
        val assembler = StepInjectionAssembler(budgetChars = 0)
        val out = assembler.assemble(
            listOf(
                entry(StepInjectionAssembler.Importance.P1, 1, intent),
                entry(StepInjectionAssembler.Importance.P2, 7, loop)
            )
        )
        assertNull(out)
    }

    // ---------- 工具 ----------

    private fun entry(importance: StepInjectionAssembler.Importance, order: Int, content: String) =
        StepInjectionAssembler.Entry(importance, order, content)

    /** 完整 8 源（含 P0），模拟真实注入块。 */
    private fun fullEntries() = listOf(
        entry(StepInjectionAssembler.Importance.P2, 7, loop),
        entry(StepInjectionAssembler.Importance.P1, 1, intent),
        entry(StepInjectionAssembler.Importance.P0, 0, goal),
        entry(StepInjectionAssembler.Importance.P2, 6, stale),
        entry(StepInjectionAssembler.Importance.P1, 5, adjustEvent),
        entry(StepInjectionAssembler.Importance.P1, 2, behavior),
        entry(StepInjectionAssembler.Importance.P1, 4, playbook),
        entry(StepInjectionAssembler.Importance.P1, 3, planPending)
    )
}
