package com.core.deepcode.feature.agent.domain.prompt

/**
 * step 前注入块装配器（D1-2，纯逻辑、可 JVM 单测，对齐 norm-chain §3.1.2 注入预算）：
 *
 * 对 step 前已构建的 8 个注入源内容做「八源排序 + 注入预算裁剪」：
 * - **排序（理解优先）**：P0 → P1 → P2，同 importance 内按 [Entry.order]（八源固定顺序）；
 * - **预算裁剪**：注入块总字符超 [budgetChars] 时从尾部（注入顺序倒序）迭代裁剪，
 *   跳过 P0（永不裁）——天然实现设计裁剪顺序「先裁 P2（loop-advisory → GoalStale）
 *   再裁 P1（GoalAdjustEvent → playbook-stage → plan-pending → 行为模式 → 问判）」。
 *
 * 无 IO、无外部依赖，仅依赖 [Entry] 的 importance/order/content，可独立 JVM 单测。
 */
class StepInjectionAssembler(
    /** 注入块总字符上限（对齐设计「约 400~800，实施时可调」，默认取上限 800）。 */
    private val budgetChars: Int = DEFAULT_BUDGET_CHARS
) {

    /** importance 3 级：P0 必须 / P1 常规 / P2 可裁剪。 */
    enum class Importance { P0, P1, P2 }

    /** 一个已构建的注入源条目（content 保证非空、由调用方过滤）。 */
    data class Entry(
        val importance: Importance,
        /** 同 importance 内绝对注入顺序（八源排序，理解优先）。 */
        val order: Int,
        val content: String
    )

    /**
     * 排序 + 预算裁剪后拼接注入块；无条目或全部被裁时返回 null。
     * 注意：P0 永不裁剪，即使超预算也保留。
     */
    fun assemble(entries: List<Entry>): String? {
        if (entries.isEmpty()) return null
        val sorted = entries.sortedWith(compareBy({ it.importance.ordinal }, { it.order }))
        var total = sorted.sumOf { it.content.length } + SEPARATOR.length * (sorted.size - 1)
        val kept = sorted.toMutableList()
        for (i in kept.indices.reversed()) {
            if (total <= budgetChars) break
            if (kept[i].importance == Importance.P0) continue // P0 永不裁
            total -= kept[i].content.length + SEPARATOR.length
            kept.removeAt(i)
        }
        if (kept.isEmpty()) return null
        return kept.joinToString(SEPARATOR) { it.content }
    }

    private companion object {
        const val DEFAULT_BUDGET_CHARS = 800
        const val SEPARATOR = "\n\n"
    }
}
