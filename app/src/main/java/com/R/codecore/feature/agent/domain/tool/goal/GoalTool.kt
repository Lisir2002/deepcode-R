package com.R.codecore.feature.agent.domain.tool.goal

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import com.R.codecore.feature.agent.data.local.entity.GoalStatus
import com.R.codecore.feature.agent.domain.goal.GoalService
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 会话「任务目标」管理工具（对齐 DSH goal 契约）：把当前任务目标以结构化状态机形式
 * 持久化（每会话唯一 ACTIVE），支持 set / get / update / done / abandon。
 *
 * 与 [AgentEvent.GoalChanged] 联动：goal 变更由 workflow 在工具批次后推送事件，与消息同日志。
 * 当前目标在每轮 step 前注入模型 system prompt，使目标可追溯、可修订、可归因。
 */
class GoalTool @Inject constructor(
    private val goalService: GoalService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "GoalTool"
    }

    override val name = "goal"
    override val description = "管理当前会话的任务目标（每会话唯一当前目标）。" +
        "action=set 用 text 设定/替换当前目标；action=get 读取当前目标；" +
        "action=update 用 text 修订目标文本（可传 goal_id 指定，缺省当前目标）；" +
        "action=done 标记完成；action=abandon 放弃（被替换或取消）。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：set / get / update / done / abandon",
            required = true,
            enum = listOf("set", "get", "update", "done", "abandon")
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "目标文本（action=set / update 时必填）",
            required = false
        ),
        "goal_id" to ToolParameter(
            name = "goal_id",
            type = ParameterType.STRING,
            description = "目标 id（action=update / done / abandon 时可选，缺省作用于当前 ACTIVE 目标）",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话", "NO_SESSION")
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()
        val goalId = args["goal_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            when (action) {
                "set" -> {
                    if (text.isNullOrBlank()) {
                        return ToolResult.Error("action=set 需要非空 text", "MISSING_TEXT")
                    }
                    // roundSeq 归因：用会话工具输出计数近似当前轮次（ToolSessionState.outputCount）
                    val roundSeq = context.sessionState?.outputCount ?: 0
                    val goal = goalService.activate(sessionId, text, roundSeq)
                    FileLogger.d(TAG, "goal set: session=$sessionId goalId=${goal.goalId}")
                    successGoal(goal)
                }
                "get" -> {
                    val goal = goalService.getActive(sessionId)
                    if (goal == null) {
                        ToolResult.Success(JsonObject(mapOf("goal" to JsonPrimitive(null))))
                    } else {
                        successGoal(goal)
                    }
                }
                "update" -> {
                    if (text.isNullOrBlank()) {
                        return ToolResult.Error("action=update 需要非空 text", "MISSING_TEXT")
                    }
                    val targetId = goalId ?: goalService.getActive(sessionId)?.goalId
                        ?: return ToolResult.Error("当前会话无 ACTIVE 目标，请先用 action=set 设定", "NO_ACTIVE_GOAL")
                    val updated = goalService.updateText(targetId, text)
                    if (updated == null) {
                        return ToolResult.Error("目标不存在或已处于终态（DONE/ABANDONED），无法修改", "GOAL_UPDATE_FAILED")
                    }
                    successGoal(updated)
                }
                "done", "abandon" -> {
                    val targetId = goalId ?: goalService.getActive(sessionId)?.goalId
                        ?: return ToolResult.Error("当前会话无 ACTIVE 目标", "NO_ACTIVE_GOAL")
                    val status = if (action == "done") GoalStatus.DONE else GoalStatus.ABANDONED
                    val updated = goalService.setStatus(targetId, status)
                    if (updated == null) {
                        return ToolResult.Error("目标不存在，无法更新状态", "GOAL_UPDATE_FAILED")
                    }
                    successGoal(updated)
                }
                else -> ToolResult.Error("未知 action: $action（合法值 set/get/update/done/abandon）", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "goal 工具执行失败: ${e.message}", e)
            ToolResult.Error("目标操作失败: ${e.message}")
        }
    }

    /** L7 事件自声明：目标状态机变更广播 goal.changed（订阅者：会话快照 / 缓存失效）。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val action = (toolCall.arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        if (action == "get") return null
        return ToolEvent.GoalChanged(
            goalId = (result as? ToolResult.Success)?.data?.let { data ->
                (data as? JsonObject)?.get("goal")?.let { g -> (g as? JsonObject)?.get("goal_id")?.let { it.jsonPrimitive.contentOrNull } }
            } ?: "",
            status = (result as? ToolResult.Success)?.data?.let { data ->
                (data as? JsonObject)?.get("goal")?.let { g -> (g as? JsonObject)?.get("status")?.let { it.jsonPrimitive.contentOrNull } }
            } ?: "",
            sessionId = context.sessionId
        )
    }

    private fun successGoal(goal: GoalEntity): ToolResult.Success = ToolResult.Success(
        JsonObject(mapOf(
            "goal" to JsonObject(mapOf(
                "goal_id" to JsonPrimitive(goal.goalId),
                "text" to JsonPrimitive(goal.text),
                "status" to JsonPrimitive(goal.statusEnum().name.lowercase()),
                "revision" to JsonPrimitive(goal.revision),
                "created_at" to JsonPrimitive(goal.createdAtMs),
                "updated_at" to JsonPrimitive(goal.updatedAtMs)
            ))
        ))
    )
}
