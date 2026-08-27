package com.R.codecore.feature.agent.domain.tool.mode

import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_session as V2AgentSession
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.dao.ModeSwitchHistoryDao
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.data.local.entity.ModeSwitchHistoryEntity
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 可以主动申请切换当前会话的模式（PLAN / BUILD）。
 */
class SwitchModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val modeSwitchHistoryDao: ModeSwitchHistoryDao,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
) : AbstractContextualTool() {

    private companion object {
        /** G-3：频率限制时间窗口，5 分钟 */
        const val RATE_LIMIT_WINDOW_MS = 300_000L

        /** G-3：窗口内允许的最大切换次数 */
        const val RATE_LIMIT_MAX = 2
    }

    private suspend fun isV2(): Boolean = readMode.currentMode() == DataReadMode.V2

    /** G-3：同会话切换频率限流记录（sessionId -> 最近切换时间戳列表），仅内存态，会话重启即重置。 */
    private val switchHistory = mutableMapOf<String, MutableList<Long>>()

    override val name = "switchMode"
    override val description = "切换当前会话的模式。如果你当前处于 BUILD（构建）模式并认为你需要进入 PLAN（计划）模式来构思复杂逻辑，或者当前在 PLAN 模式下计划已经完成需要进入 BUILD 模式修改代码时，调用此工具主动申请切换。切换前需要用户授权。注意：AUTO（自动）模式只能由用户在界面上手动切换进入，本工具无法切换到 AUTO；但处于 AUTO 模式时，可通过本工具切换到 PLAN 模式退出自动模式（这是 AI 退出 AUTO 的唯一路径）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    /** L3 结构化结果协议：产出 state.mode.changed 类型（模式切换后广播，触发上下文增量刷新）。 */
    override val provides = setOf("state.mode.changed")

    override val parameters: Map<String, ToolParameter> = mapOf(
        "mode" to ToolParameter(
            name = "mode",
            type = ParameterType.STRING,
            description = "目标模式，必须是 'PLAN' 或 'BUILD'",
            required = true,
            enum = listOf("PLAN", "BUILD")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "切换模式的理由，将展示给用户",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val targetModeStr = args["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
            ?: return ToolResult.Error("缺少必需参数: mode", "MISSING_MODE")
            
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: reason", "MISSING_REASON")

        val targetMode = try {
            AgentMode.valueOf(targetModeStr)
        } catch (e: Exception) {
            return ToolResult.Error("无效的模式: $targetModeStr，只能是 PLAN 或 BUILD", "INVALID_MODE")
        }

        if (targetMode == AgentMode.AUTO) {
            return ToolResult.Error("AUTO 模式只能由用户在界面上手动切换，AI 无法通过工具切换", "AUTO_MODE_MANUAL_ONLY")
        }

        // AUTO 模式下 AI 只能切到 PLAN（唯一退出路径），不能直接切到 BUILD
        if (context.mode == AgentMode.AUTO && targetMode != AgentMode.PLAN) {
            return ToolResult.Error("当前处于 AUTO 模式，只能切换到 PLAN 模式退出自动模式", "AUTO_EXIT_PLAN_ONLY")
        }

        if (context.mode == targetMode) {
            return ToolResult.Success(JsonPrimitive("当前已处于 ${targetMode.name} 模式，无需切换。"))
        }

        val sessionId = context.sessionId
        if (sessionId == null) {
            return ToolResult.Error("未关联会话 ID，无法切换模式", "NO_SESSION")
        }

        val sessionEntity = (if (isV2()) v2Agent.getSessionById(sessionId)?.toEntity() else chatSessionDao.getById(sessionId))
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        // G-3：频率限制——同会话 5 分钟内最多允许 2 次切换，防止 PLAN↔BUILD 抖动。
        // 仅在真正执行切换前记录；「已处于目标模式」「校验失败」等分支已提前返回，不会记录。
        checkAndRecordSwitch(sessionId, context.mode)?.let { return it }

        // 切换模式并保存到数据库。UI 层通过 flow 监听，会自动更新外观与后续流程的上下文
        if (isV2()) {
            v2Agent.upsertSession(
                id = sessionEntity.id, title = sessionEntity.title, mode = targetMode.name,
                model = sessionEntity.model, status = "active",
                createdAtMs = sessionEntity.createdAtMs, updatedAtMs = sessionEntity.updatedAtMs,
                workspacePath = sessionEntity.workspacePath, reasoningEffort = sessionEntity.reasoningEffort,
                providerId = sessionEntity.providerId,
                totalInputTokens = sessionEntity.totalInputTokens.toLong(),
                totalOutputTokens = sessionEntity.totalOutputTokens.toLong(),
                lastInputTokens = sessionEntity.lastInputTokens.toLong(),
            )
        } else {
            chatSessionDao.upsert(sessionEntity.copy(mode = targetMode.name))
        }

        // G-1：记录本次切换历史到数据库（持久化，可用于回溯和频率统计）
        if (isV2()) {
            v2Agent.insertModeSwitch(sessionId, context.mode.name, targetMode.name, reason, System.currentTimeMillis())
        } else {
            modeSwitchHistoryDao.insert(
                ModeSwitchHistoryEntity(
                    sessionId = sessionId,
                    fromMode = context.mode.name,
                    toMode = targetMode.name,
                    reason = reason,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }

        return ToolResult.Success(JsonPrimitive("成功切换至 ${targetMode.name} 模式。"))
    }

    /** L7 事件自声明：切换成功后广播 state.mode.changed，触发上下文增量刷新。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val to = (toolCall.arguments["mode"] as? JsonPrimitive)?.contentOrNull ?: ""
        val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ToolEvent.StateModeChanged(from = context.mode.name, to = to, reason = reason, sessionId = context.sessionId)
    }

    /**
     * G-3：频率限制检查并记录一次切换（synchronized 保证并发安全）。
     * 先清理窗口外（5 分钟前）的旧时间戳；若窗口内已有 RATE_LIMIT_MAX 次，返回超限错误且不记录；
     * 否则将当前时间戳加入历史并返回 null（放行）。
     */
    @Synchronized
    private fun checkAndRecordSwitch(sessionId: String, currentMode: AgentMode): ToolResult? {
        val now = System.currentTimeMillis()
        val history = switchHistory.getOrPut(sessionId) { mutableListOf() }
        history.removeAll { now - it >= RATE_LIMIT_WINDOW_MS }
        if (history.size >= RATE_LIMIT_MAX) {
            return ToolResult.Error(
                "模式切换过于频繁：5 分钟内最多允许 2 次。当前仍处于 $currentMode 模式。如需调整请稍后再试。",
                "MODE_SWITCH_RATE_LIMITED"
            )
        }
        history.add(now)
        return null
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "无理由"
        
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "模式切换申请",
            summary = "AI 申请切换为 $mode 模式",
            details = "目标模式：$mode\n\n申请理由：$reason",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun V2AgentSession.toEntity() = ChatSessionEntity(
        id = id,
        title = title ?: "",
        createdAtMs = created_at,
        updatedAtMs = updated_at,
        workspacePath = workspace_path,
        mode = mode,
        reasoningEffort = reasoning_effort,
        providerId = provider_id,
        model = model,
        totalInputTokens = total_input_tokens.toInt(),
        totalOutputTokens = total_output_tokens.toInt(),
        lastInputTokens = last_input_tokens.toInt(),
    )
}
