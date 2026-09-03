package com.core.deepcode.feature.agent.domain.tool.plan

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.data.local.entity.PlanEntity
import com.core.deepcode.feature.agent.data.local.entity.PlanStatus
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.plan.PlanService
import com.core.deepcode.feature.agent.domain.tool.AbstractContextualTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 会话「计划协作状态」管理工具（对齐 DSH plan mode + Claude Code Plan/Spec）：
 * 把 AI 产出的实施计划以结构化状态机形式持久化（每会话单计划），支持
 * propose / get / update_steps / set_pending_selection / approve / abandon。
 *
 * - propose：提议新 DRAFT 计划（title + steps(JSON) + 可选 pendingSelection），
 *   事务内把会话旧未终态计划置 ABANDONED（见 [PlanService.propose]）。
 * - get：读取会话最近一份计划。
 * - set_pending_selection：更新待定选择——用户尚未拍板的方案，由 workflow 每轮
 *   step 前追加到模型请求，用户批准后置 APPROVED 并清空（对齐 Claude Code 待定选择）。
 * - approve：批准（DRAFT → APPROVED，清空 pendingSelection），可进入执行。
 * - abandon：放弃（被替换/取消）。
 *
 * 与 goal 工具的差异：goal 管「任务目标」（简短一句话），plan 管「结构化实施计划」
 * （多步骤 + 生命周期 + 待定选择），二者互补，均通过 workflow 每轮注入模型上下文。
 */
class PlanTool @Inject constructor(
    private val planService: PlanService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "PlanTool"
        const val MAX_STEPS_CHARS = 8_000

        /** steps 解析用宽松 Json（忽略未知键）。 */
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    override val name = "plan"
    override val description = "管理当前会话的实施计划（每会话单计划）。" +
        "action=propose 用 title + steps 提议新计划（可带 pending_selection 待定选择，供用户选择）；" +
        "action=get 读取当前计划；action=update_steps 用 steps 更新步骤（可传 plan_id 指定）；" +
        "action=set_pending_selection 用 pending_selection 更新待定选择；" +
        "action=approve 批准计划进入执行；action=abandon 放弃当前计划。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：propose / get / update_steps / set_pending_selection / approve / abandon",
            required = true,
            enum = listOf("propose", "get", "update_steps", "set_pending_selection", "approve", "abandon")
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "计划标题（action=propose 时必填）",
            required = false
        ),
        "steps" to ToolParameter(
            name = "steps",
            type = ParameterType.STRING,
            description = "步骤 JSON 数组文本（[ {text, status} ]，action=propose / update_steps 时必填）",
            required = false
        ),
        "pending_selection" to ToolParameter(
            name = "pending_selection",
            type = ParameterType.STRING,
            description = "待定选择文本（action=propose / set_pending_selection 时可选），非空时每轮注入模型请求",
            required = false
        ),
        "plan_id" to ToolParameter(
            name = "plan_id",
            type = ParameterType.STRING,
            description = "计划 id（action=update_steps / set_pending_selection / approve / abandon 时可选，缺省作用于会话最近计划）",
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
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()
        val steps = args["steps"]?.jsonPrimitive?.contentOrNull?.trim()
        val pendingSelection = args["pending_selection"]?.jsonPrimitive?.contentOrNull?.trim()
        val planId = args["plan_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            when (action) {
                "propose" -> {
                    if (title.isNullOrBlank()) {
                        return ToolResult.Error("action=propose 需要非空 title", "MISSING_TITLE")
                    }
                    if (steps.isNullOrBlank()) {
                        return ToolResult.Error("action=propose 需要非空 steps", "MISSING_STEPS")
                    }
                    if (steps.length > MAX_STEPS_CHARS) {
                        return ToolResult.Error("steps 过长（$MAX_STEPS_CHARS 字符上限），请精简后再提交", "STEPS_TOO_LONG")
                    }
                    if (!isStepsJson(steps)) {
                        return ToolResult.Error("steps 必须是 JSON 数组文本（如 [{\"text\":\"...\",\"status\":\"pending\"}]）", "INVALID_STEPS")
                    }
                    val plan = planService.propose(sessionId, title, steps, pendingSelection.orEmpty())
                    FileLogger.d(TAG, "plan propose: session=$sessionId planId=${plan.planId}")
                    successPlan(plan)
                }
                "get" -> {
                    val plan = planService.getLatest(sessionId)
                    if (plan == null) {
                        ToolResult.Success(JsonObject(mapOf("plan" to JsonPrimitive(null))))
                    } else {
                        successPlan(plan)
                    }
                }
                "update_steps" -> {
                    if (steps.isNullOrBlank()) {
                        return ToolResult.Error("action=update_steps 需要非空 steps", "MISSING_STEPS")
                    }
                    if (!isStepsJson(steps)) {
                        return ToolResult.Error("steps 必须是 JSON 数组文本", "INVALID_STEPS")
                    }
                    val targetId = planId ?: planService.getLatest(sessionId)?.planId
                        ?: return ToolResult.Error("当前会话无计划，请先用 action=propose 提议", "NO_PLAN")
                    val updated = updatePlan(targetId) { it.copy(steps = steps, updatedAtMs = System.currentTimeMillis()) }
                        ?: return ToolResult.Error("计划不存在或已处于终态（COMPLETED/ABANDONED），无法修改", "PLAN_UPDATE_FAILED")
                    successPlan(updated)
                }
                "set_pending_selection" -> {
                    if (pendingSelection.isNullOrBlank()) {
                        return ToolResult.Error("action=set_pending_selection 需要非空 pending_selection", "MISSING_PENDING_SELECTION")
                    }
                    val targetId = planId ?: planService.getLatest(sessionId)?.planId
                        ?: return ToolResult.Error("当前会话无计划，请先用 action=propose 提议", "NO_PLAN")
                    val updated = updatePlan(targetId) { it.copy(pendingSelection = pendingSelection, updatedAtMs = System.currentTimeMillis()) }
                        ?: return ToolResult.Error("计划不存在或已处于终态（COMPLETED/ABANDONED），无法修改", "PLAN_UPDATE_FAILED")
                    successPlan(updated)
                }
                "approve", "abandon" -> {
                    val targetId = planId ?: planService.getLatest(sessionId)?.planId
                        ?: return ToolResult.Error("当前会话无计划", "NO_PLAN")
                    val status = if (action == "approve") PlanStatus.APPROVED else PlanStatus.ABANDONED
                    val updated = if (status == PlanStatus.APPROVED) {
                        planService.approve(targetId)
                    } else {
                        planService.abandon(targetId)
                    }
                    if (updated == null) {
                        return ToolResult.Error("计划不存在，无法更新状态", "PLAN_UPDATE_FAILED")
                    }
                    successPlan(updated)
                }
                else -> ToolResult.Error("未知 action: $action（合法值 propose/get/update_steps/set_pending_selection/approve/abandon）", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "plan 工具执行失败: ${e.message}", e)
            ToolResult.Error("计划操作失败: ${e.message}")
        }
    }

    /**
     * 通用更新：仅在计划非终态（非 COMPLETED / 非 ABANDONED）时应用变更，
     * 终态计划不可再被修改（与 [PlanService] 生命周期语义一致）。
     */
    private suspend fun updatePlan(
        planId: String,
        transform: (PlanEntity) -> PlanEntity
    ): PlanEntity? {
        val existing = planService.getById(planId) ?: return null
        val status = existing.statusEnum()
        if (status == PlanStatus.COMPLETED || status == PlanStatus.ABANDONED) return null
        return planService.update(planId, transform(existing))
    }

    /** steps 必须是可解析的 JSON 数组（顶层是数组）。 */
    private fun isStepsJson(steps: String): Boolean = try {
        val parsed = json.parseToJsonElement(steps)
        parsed is JsonArray
    } catch (e: Exception) {
        false
    }

    private fun successPlan(plan: PlanEntity): ToolResult.Success = ToolResult.Success(
        JsonObject(mapOf(
            "plan" to JsonObject(mapOf(
                "plan_id" to JsonPrimitive(plan.planId),
                "title" to JsonPrimitive(plan.title),
                "status" to JsonPrimitive(plan.statusEnum().name.lowercase()),
                "steps" to parseSteps(plan.steps),
                "pending_selection" to JsonPrimitive(plan.pendingSelection),
                "created_at" to JsonPrimitive(plan.createdAtMs),
                "updated_at" to JsonPrimitive(plan.updatedAtMs)
            ))
        ))
    )

    /** 把 steps 文本（已校验为 JSON 数组）解析为 JsonArray；解析失败返回空数组兜底。 */
    private fun parseSteps(steps: String): JsonArray = try {
        json.parseToJsonElement(steps) as? JsonArray ?: buildJsonArray { }
    } catch (e: Exception) {
        buildJsonArray { }
    }
}
