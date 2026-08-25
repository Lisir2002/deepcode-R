package com.R.codecore.feature.agent.domain.trajectory

import com.R.codecore.feature.agent.data.local.dao.TrajectoryAggregate
import com.R.codecore.feature.agent.data.local.dao.TrajectoryDao
import com.R.codecore.feature.agent.data.local.entity.TrajectoryEntity
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.agent.domain.tool.ToolResultTypeRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行轨迹服务（D2-3/D2-5，对齐 norm-chain-design.md §3.8）。
 *
 * 承载 [TrajectoryEntity] 的写入（workflow 追加 tool 轨迹与 turn/compaction/inject/error/timeout
 * 轻量标记）、用量聚合（每回合增量 + 会话累计，D2-4 数据源）与轨迹消费（已做动作摘要，D2-5）。
 * append-only：只插入不更新，删除会话时由 [com.R.codecore.feature.agent.domain.session.SessionUseCase]
 * 级联清理。
 */
@Singleton
class TrajectoryService @Inject constructor(
    private val trajectoryDao: TrajectoryDao,
    private val toolResultTypeRegistry: ToolResultTypeRegistry
) {
    private companion object {
        /** 轨迹 kind 常量（与 Entity 注释一致）。 */
        const val KIND_TOOL = "tool"
        const val KIND_TURN = "turn"
        const val KIND_COMPACTION = "compaction"
        const val KIND_INJECT = "inject"
        const val KIND_ERROR = "error"
        const val KIND_TIMEOUT = "timeout"

        /** 已做动作摘要默认条数上限（D2-5，收敛/阶段总结消费）。 */
        const val ACTION_SUMMARY_MAX_ITEMS = 12
        const val ACTION_SUMMARY_CHARS = 80
    }

    /** 轨迹摘要提取：成功走注册表定制/通用截断；失败带错误码前缀。 */
    private fun buildSummary(toolName: String, args: Map<String, JsonElement>, result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> toolResultTypeRegistry.summarize(toolName, args, result.data)
            is ToolResult.Partial -> toolResultTypeRegistry.summarize(toolName, args, result.data)
            is ToolResult.Error ->
                "❌[${result.code}] ${result.message.take(ACTION_SUMMARY_CHARS)}"
        }
    }

    private fun argsHash(args: Map<String, JsonElement>): String =
        args.toString().hashCode().toString()

    /**
     * 追加一条 tool 轨迹（workflow 每次工具执行完成后调用）。
     * 轨迹 id 用 UUID，避免跨批冲突；sessionId 为空（无会话）时跳过。
     */
    suspend fun recordTool(
        sessionId: String?,
        taskId: String?,
        turnIndex: Int,
        toolName: String,
        args: Map<String, JsonElement>,
        result: ToolResult,
        isError: Boolean,
        durationMs: Long,
        tokensIn: Int,
        tokensOut: Int
    ) {
        if (sessionId == null) return
        trajectoryDao.insert(
            TrajectoryEntity(
                trajectoryId = "trj_${UUID.randomUUID().toString().replace("-", "")}",
                sessionId = sessionId,
                taskId = taskId.orEmpty(),
                turnIndex = turnIndex,
                kind = KIND_TOOL,
                toolName = toolName,
                argsHash = argsHash(args),
                resultSummary = buildSummary(toolName, args, result),
                isError = isError,
                durationMs = durationMs.coerceAtLeast(0),
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                ts = System.currentTimeMillis()
            )
        )
    }

    /** 追加一条轻量标记（turn 边界 / 压缩 / 注入 / 错误 / 超时）。 */
    suspend fun recordMark(
        sessionId: String?,
        taskId: String?,
        turnIndex: Int,
        kind: String,
        summary: String,
        tokensIn: Int = 0,
        tokensOut: Int = 0
    ) {
        if (sessionId == null) return
        trajectoryDao.insert(
            TrajectoryEntity(
                trajectoryId = "trj_${UUID.randomUUID().toString().replace("-", "")}",
                sessionId = sessionId,
                taskId = taskId.orEmpty(),
                turnIndex = turnIndex,
                kind = kind,
                resultSummary = summary,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                ts = System.currentTimeMillis()
            )
        )
    }

    /** 本回合（taskId 分组）用量：主显每回合增量（D2-4 数据源）。 */
    suspend fun turnUsage(sessionId: String?, taskId: String?): TurnUsage {
        if (sessionId == null || taskId.isNullOrBlank()) return TurnUsage()
        val entries = trajectoryDao.getByTask(taskId)
        if (entries.isEmpty()) return TurnUsage()
        return entries.aggregateUsage()
    }

    /** 会话累计用量：附一行累计（D2-4 数据源）。 */
    suspend fun sessionUsage(sessionId: String?): SessionUsage {
        if (sessionId == null) return SessionUsage()
        val agg = trajectoryDao.getSessionAggregate(sessionId)
        return SessionUsage(tokensIn = agg.tokensIn, tokensOut = agg.tokensOut, count = agg.count)
    }

    /** 已做动作摘要（D2-5：3.7 强制收敛返回 / Playbook 阶段总结 / 审计）。 */
    suspend fun buildActionSummary(sessionId: String?, maxItems: Int = ACTION_SUMMARY_MAX_ITEMS): String {
        if (sessionId == null) return ""
        val tools = trajectoryDao.getBySession(sessionId).filter { it.kind == KIND_TOOL }.takeLast(maxItems)
        if (tools.isEmpty()) return ""
        return tools.joinToString("\n") { t ->
            val marker = if (t.isError) "❌" else "•"
            val dur = if (t.durationMs > 0) " (${t.durationMs}ms)" else ""
            "$marker ${t.toolName}${if (t.toolName.isNotEmpty()) ": " else ""}${t.resultSummary}$dur"
        }
    }

    /** 审计回放：按会话查完整轨迹（时间升序）。 */
    suspend fun getTrajectory(sessionId: String?): List<TrajectoryEntity> {
        if (sessionId == null) return emptyList()
        return trajectoryDao.getBySession(sessionId)
    }

    /** 会话最近一个回合（taskId + turnIndex），供 UI 用量卡片定位。 */
    suspend fun latestTurn(sessionId: String?): Pair<String, Int>? {
        if (sessionId == null) return null
        val taskId = trajectoryDao.getLatestTaskId(sessionId) ?: return null
        val turnIndex = trajectoryDao.getMaxTurnIndex(sessionId, taskId) ?: 0
        return taskId to turnIndex
    }

    private fun List<TrajectoryEntity>.aggregateUsage(): TurnUsage {
        var tokensIn = 0L
        var tokensOut = 0L
        var durationMs = 0L
        var toolCalls = 0
        for (e in this) {
            tokensIn += e.tokensIn
            tokensOut += e.tokensOut
            durationMs += e.durationMs
            if (e.kind == KIND_TOOL) toolCalls++
        }
        return TurnUsage(
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            totalTokens = tokensIn + tokensOut,
            durationMs = durationMs,
            toolCalls = toolCalls
        )
    }
}

/**
 * 本回合用量（主显）：输入/输出/总 token、耗时、工具调用数。
 */
data class TurnUsage(
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val totalTokens: Long = 0,
    val durationMs: Long = 0,
    val toolCalls: Int = 0
)

/**
 * 会话累计用量（附一行累计，仅 token 不估成本）。
 */
data class SessionUsage(
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val count: Long = 0
)
