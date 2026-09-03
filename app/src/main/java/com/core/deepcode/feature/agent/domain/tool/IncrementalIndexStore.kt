package com.core.deepcode.feature.agent.domain.tool

import com.core.deepcode.core.util.FileLogger
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * L6 上下文增量发布：工具结果与状态变化的增量索引存储。
 *
 * 记录工具动作（todo/memory/文件等）产生的数据摘要与每轮汇总快照，
 * 让 prompt 构建只注入变化部分，降低 token 消耗、提升 KV 命中。
 * 与 [com.core.deepcode.feature.agent.domain.workflow.ContextCompactor] 互补：
 * 压缩处理消息历史，增量索引处理工具产物。
 *
 * 存储形态：内存为主（ConcurrentHashMap 按会话分桶）+ 定期持久化（[persist]）。
 * 生命周期随会话，压缩触发时 [buildCompactionSummary] 并入摘要后 [clear]。
 */
class IncrementalIndexStore {

    private val sessions = ConcurrentHashMap<String, SessionIndex>()

    /** 会话级索引桶：动作列表 + 轮次快照 + 已持久化轮次。 */
    data class SessionIndex(
        val actions: MutableList<ActionIndex>,
        val rounds: MutableList<RoundSnapshot>,
        var lastPersistedRound: Int = 0
    )

    // ---------- 核心路径 ----------

    /** 记录单个工具动作。同 dataHash 的动作去重（引用计数递增）。 */
    suspend fun recordAction(sessionId: String, action: ActionIndex) {
        val index = sessions.getOrPut(sessionId) { SessionIndex(mutableListOf(), mutableListOf()) }
        synchronized(index) {
            val existing = index.actions.firstOrNull { it.dataHash == action.dataHash }
            if (existing != null) {
                val idx = index.actions.indexOf(existing)
                index.actions[idx] = existing.copy(refCount = existing.refCount + 1)
            } else {
                index.actions.add(action)
            }
        }
    }

    /** 记录一轮汇总快照，并递增该轮动作的引用计数。 */
    suspend fun recordRound(sessionId: String, round: RoundSnapshot) {
        val index = sessions.getOrPut(sessionId) { SessionIndex(mutableListOf(), mutableListOf()) }
        synchronized(index) {
            index.rounds.add(round)
            round.actions.forEach { action ->
                val existing = index.actions.firstOrNull { it.dataHash == action.dataHash }
                if (existing != null) {
                    val idx = index.actions.indexOf(existing)
                    index.actions[idx] = existing.copy(refCount = existing.refCount + 1)
                }
            }
        }
    }

    /** 定期持久化：把已记录但未持久化的轮次落盘（异步，不阻塞工具执行）。 */
    suspend fun persist(sessionId: String) {
        val index = sessions[sessionId] ?: return
        synchronized(index) {
            val pending = index.rounds.filter { it.round > index.lastPersistedRound }
            if (pending.isNotEmpty()) {
                FileLogger.i(TAG, "增量索引持久化: session=$sessionId, rounds=${pending.size}")
                index.lastPersistedRound = pending.maxOf { it.round }
            }
        }
    }

    // ---------- 查询 ----------

    /** 最近 N 条动作索引（最新在前）。 */
    fun getRecentActions(sessionId: String, limit: Int): List<ActionIndex> {
        return sessions[sessionId]?.actions?.toList()?.reversed()?.take(limit) ?: emptyList()
    }

    /** 最近 N 轮快照（最新在前）。 */
    fun getRecentRounds(sessionId: String, limit: Int): List<RoundSnapshot> {
        return sessions[sessionId]?.rounds?.toList()?.reversed()?.take(limit) ?: emptyList()
    }

    // ---------- 压缩衔接 ----------

    /** 把当前增量索引摘要拼成字符串，供压缩摘要消息并入。 */
    fun buildCompactionSummary(sessionId: String): String {
        val index = sessions[sessionId] ?: return ""
        synchronized(index) {
            if (index.actions.isEmpty()) return ""
            return buildString {
                append("【增量索引摘要】")
                append(index.actions.size)
                append(" 个工具动作：")
                index.actions.takeLast(20).forEach { action ->
                    append("\n- ")
                    append(action.actionType)
                    append(": ")
                    append(action.data.toString().take(200))
                }
            }
        }
    }

    /** 清空指定会话的索引（压缩触发时调用）。 */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** 当前活跃会话数（调试用）。 */
    fun sessionCount(): Int = sessions.size

    private companion object {
        const val TAG = "IncrementalIndexStore"
    }
}

/**
 * 单个工具动作的增量索引。
 */
data class ActionIndex(
    /** 动作类型（如 "todo.created"、"file.edited"）。 */
    val actionType: String,
    /** 动作产生的数据摘要。 */
    val data: JsonElement,
    /** 数据哈希，供去重与失效。 */
    val dataHash: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 引用计数，供事件总线失效时精确回收。 */
    val refCount: Int = 1
)

/**
 * 一轮工具执行的汇总快照。
 */
data class RoundSnapshot(
    /** 轮次号。 */
    val round: Int,
    /** 该轮所有动作摘要。 */
    val actions: List<ActionIndex>,
    /** 该轮汇总摘要。 */
    val summary: String
)
