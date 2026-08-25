package com.R.codecore.feature.agent.domain.tool.schedule

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.entity.ScheduleEntity
import com.R.codecore.feature.agent.data.local.entity.ScheduleRule
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.schedule.ScheduleService
import com.R.codecore.feature.agent.domain.tool.AbstractContextualTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolPermissionPolicy
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 会话「定时提醒」管理工具（对齐 DSH schedule 契约）：
 * 支持 action=create（after 延迟 / at 定点 / every 周期）+ list（列出本会话定时项）+ cancel（取消）。
 *
 * 到点时由调度循环（[com.R.codecore.feature.agent.domain.schedule.ScheduleScheduler]）
 * 把 [prompt] 作为一条带 "scheduled" 标记的 user/message 注入会话，唤醒 Agent 执行提醒任务。
 */
class ScheduleTool @Inject constructor(
    private val scheduleService: ScheduleService
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "ScheduleTool"
    }

    override val name = "schedule"
    override val description = "管理当前会话的定时提醒。" +
        "action=create 创建定时项：rule=after 用 delay_ms（毫秒，多久后触发一次）；" +
        "rule=at 用 at_ms（时间戳，到点触发一次）；rule=every 用 interval_ms（毫秒，周期性循环触发）；" +
        "prompt 为到点提醒正文。action=list 列出本会话全部定时项；action=cancel 用 schedule_id 取消定时项。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：create / list / cancel",
            required = true,
            enum = listOf("create", "list", "cancel")
        ),
        "rule" to ToolParameter(
            name = "rule",
            type = ParameterType.STRING,
            description = "定时规则（action=create 时必填）：after / at / every",
            required = false,
            enum = listOf("after", "at", "every")
        ),
        "delay_ms" to ToolParameter(
            name = "delay_ms",
            type = ParameterType.INTEGER,
            description = "延迟毫秒（rule=after 时必填），多久后触发一次",
            required = false
        ),
        "at_ms" to ToolParameter(
            name = "at_ms",
            type = ParameterType.INTEGER,
            description = "定点时间戳毫秒（rule=at 时必填）",
            required = false
        ),
        "interval_ms" to ToolParameter(
            name = "interval_ms",
            type = ParameterType.INTEGER,
            description = "周期毫秒（rule=every 时必填），周期性循环触发",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "到点提醒正文（action=create 时必填），将作为用户消息注入会话唤醒 Agent",
            required = false
        ),
        "schedule_id" to ToolParameter(
            name = "schedule_id",
            type = ParameterType.STRING,
            description = "定时项 id（action=cancel 时必填）",
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

        return try {
            when (action) {
                "create" -> create(args, sessionId)
                "list" -> {
                    val items = scheduleService.list(sessionId)
                    ToolResult.Success(JsonObject(mapOf(
                        "schedules" to kotlinx.serialization.json.JsonArray(
                            items.map { scheduleJson(it) }
                        )
                    )))
                }
                "cancel" -> {
                    val scheduleId = args["schedule_id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: return ToolResult.Error("action=cancel 需要 schedule_id", "MISSING_SCHEDULE_ID")
                    val ok = scheduleService.cancel(scheduleId)
                    if (!ok) return ToolResult.Error("定时项不存在: $scheduleId", "SCHEDULE_NOT_FOUND")
                    ToolResult.Success(JsonObject(mapOf(
                        "cancelled" to JsonPrimitive(true),
                        "schedule_id" to JsonPrimitive(scheduleId)
                    )))
                }
                else -> ToolResult.Error("未知 action: $action（合法值 create/list/cancel）", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "schedule 工具执行失败: ${e.message}", e)
            ToolResult.Error("定时操作失败: ${e.message}")
        }
    }

    private suspend fun create(args: Map<String, JsonElement>, sessionId: String): ToolResult {
        val ruleName = args["rule"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val rule = when (ruleName) {
            "after" -> ScheduleRule.AFTER
            "at" -> ScheduleRule.AT
            "every" -> ScheduleRule.EVERY
            else -> return ToolResult.Error("action=create 需要合法 rule（after/at/every）", "MISSING_RULE")
        }
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("action=create 需要非空 prompt", "MISSING_PROMPT")
        val delayMs = args["delay_ms"]?.jsonPrimitive?.longOrNull ?: 0
        val atMs = args["at_ms"]?.jsonPrimitive?.longOrNull ?: 0
        val intervalMs = args["interval_ms"]?.jsonPrimitive?.longOrNull ?: 0

        val sArgs = when (rule) {
            ScheduleRule.AFTER -> {
                if (delayMs <= 0) return ToolResult.Error("rule=after 需要正数 delay_ms", "INVALID_DELAY")
                ScheduleService.ScheduleArgs(delayMs = delayMs, prompt = prompt)
            }
            ScheduleRule.AT -> {
                if (atMs <= 0) return ToolResult.Error("rule=at 需要正数 at_ms", "INVALID_AT")
                ScheduleService.ScheduleArgs(atMs = atMs, prompt = prompt)
            }
            ScheduleRule.EVERY -> {
                if (intervalMs <= 0) return ToolResult.Error("rule=every 需要正数 interval_ms", "INVALID_INTERVAL")
                ScheduleService.ScheduleArgs(intervalMs = intervalMs, prompt = prompt)
            }
        }

        val entity = scheduleService.create(sessionId, rule, sArgs)
        FileLogger.d(TAG, "create: session=$sessionId rule=${rule.name} scheduleId=${entity.scheduleId}")
        return ToolResult.Success(JsonObject(mapOf(
            "schedule" to scheduleJson(entity)
        )))
    }

    private fun scheduleJson(entity: ScheduleEntity): JsonObject = buildJsonObject {
        put("schedule_id", JsonPrimitive(entity.scheduleId))
        put("rule", JsonPrimitive(entity.ruleEnum().name.lowercase()))
        put("status", JsonPrimitive(entity.statusEnum().name.lowercase()))
        put("enabled", JsonPrimitive(entity.isEnabled))
        put("prompt", JsonPrimitive(scheduleService.parseArgs(entity).prompt))
        put("created_at", JsonPrimitive(entity.createdAtMs))
        put("last_fired_at", entity.lastFiredAtMs?.let { JsonPrimitive(it) } ?: JsonPrimitive(null))
    }
}
