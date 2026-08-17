package com.R.codecore.feature.agent.domain.tool

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * L4 依赖感知调度：按工具声明的 [AgentTool.dependsOn] 构建依赖图，拓扑排序分层。
 * 无依赖的工具并行执行、有依赖的自动串行，消除模型为排序消耗的多轮往返。
 *
 * 职责划分：本类只负责「顺序编排 + 重试策略」，工具执行由 workflow 注入的
 * execute 回调完成（封装 runToolSync / runToolStream），保持零耦合、不重复实现执行逻辑。
 */
class ToolDependencyScheduler {

    /**
     * 分层执行计划：每层内的 call 可并行，层间按依赖序串行。
     */
    data class SchedulePlan(
        val layers: List<List<ToolCall>>,
        /** 检测到循环依赖时的描述；非 null 表示调度失败，不应执行。 */
        val cycleMessage: String? = null
    ) {
        val hasCycle: Boolean get() = cycleMessage != null
    }

    /**
     * 构建依赖图并拓扑分层（Kahn 算法）。
     *
     * 依赖规则：call A 的工具声明 dependsOn 含工具 X，且本批中存在工具 X 的调用 B，
     * 则 A 依赖 B —— B 所在层先于 A 所在层执行。
     * 未声明的依赖（dependsOn 为空）全部归入首批并行。
     */
    fun buildSchedule(calls: List<ToolCall>, registry: ToolRegistry): SchedulePlan {
        if (calls.isEmpty()) return SchedulePlan(emptyList())

        // 本批中每个工具名 -> 首个 call（一个工具名通常只出现一次）
        val toolToCall = LinkedHashMap<String, ToolCall>()
        calls.forEach { call -> toolToCall.putIfAbsent(call.name, call) }

        // 依赖图：callId -> 依赖的 callId 集合（须先执行的）
        val deps = HashMap<String, Set<String>>()
        calls.forEach { call ->
            val depNames = registry.getTool(call.name)?.dependsOn.orEmpty()
            val depIds = depNames.mapNotNull { toolToCall[it]?.id }.toSet()
            deps[call.id] = depIds
        }

        // Kahn 拓扑排序：入度 + 依赖方索引
        val inDegree = HashMap<String, Int>()
        val dependents = HashMap<String, MutableSet<String>>()
        calls.forEach { call ->
            inDegree[call.id] = deps[call.id]?.size ?: 0
            deps[call.id]?.forEach { depId ->
                dependents.getOrPut(depId) { mutableSetOf() }.add(call.id)
            }
        }

        val byId = calls.associateBy { it.id }
        val remaining = calls.map { it.id }.toMutableSet()
        val layers = mutableListOf<List<ToolCall>>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { inDegree[it] == 0 }
            if (ready.isEmpty()) {
                // 剩余节点全是环内节点
                val cycle = remaining.joinToString(" -> ") { byId[it]?.name ?: it }
                return SchedulePlan(emptyList(), cycleMessage = "检测到循环依赖: $cycle")
            }
            layers.add(ready.mapNotNull { byId[it] })
            ready.forEach { id ->
                remaining.remove(id)
                dependents[id]?.forEach { dep ->
                    inDegree[dep] = (inDegree[dep] ?: 0) - 1
                }
            }
        }
        return SchedulePlan(layers)
    }

    /**
     * 指数退避重试：按 [isRetryable] 判定是否重试，退避 1s/2s/4s + 抖动，
     * 上限由工具的 [AgentTool.retryPolicy] 决定（默认 3 次）。
     *
     * @param execute 执行回调（workflow 注入），每次尝试调用一次。
     * @param isRetryable 判定结果是否可重试（workflow 按 errorClass 判定）。
     */
    suspend fun <R> runWithRetry(
        tool: AgentTool?,
        call: ToolCall,
        execute: suspend (AgentTool?, ToolCall) -> R,
        isRetryable: (R) -> Boolean
    ): R {
        val policy = tool?.retryPolicy ?: RetryPolicy()
        var attempt = 0
        var result = execute(tool, call)
        while (attempt < policy.maxRetries && isRetryable(result)) {
            attempt++
            delay(backoffDelay(attempt))
            result = execute(tool, call)
        }
        return result
    }

    /** 指数退避 + 抖动：1s/2s/4s（上限 4s），每次叠加 0~50% 随机抖动。 */
    private fun backoffDelay(attempt: Int): Long {
        val baseMs = 1_000L shl (attempt - 1).coerceAtMost(2)
        return baseMs + Random.nextLong(0, baseMs / 2)
    }
}
