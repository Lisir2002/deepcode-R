package com.core.deepcode.feature.agent.domain.schedule

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.datalayer.sqldelight.agent.Agent_schedules as V2Schedule
import com.core.deepcode.feature.agent.data.local.entity.ScheduleEntity
import com.core.deepcode.feature.agent.data.local.entity.ScheduleRule
import com.core.deepcode.feature.agent.data.local.entity.ScheduleStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * 会话级「定时提醒」服务（对齐 DSH schedule 契约）。
 *
 * 三种规则（对齐 [ScheduleRule]）：
 * - [ScheduleRule.AFTER]：延迟指定毫秒后触发一次（args.delayMs）；
 * - [ScheduleRule.AT]：到指定时间戳触发一次（args.atMs）；
 * - [ScheduleRule.EVERY]：按周期毫秒循环触发（args.intervalMs）。
 *
 * args 为 JSON 字符串，除规则参数外还承载投递正文 `prompt`（到点时作为一条带
 * "scheduled" 标记的 user/message 注入会话）。跨重启恢复靠持久化 + 调度循环
 * 启动扫描未投递项（[dueAt]）。
 *
 * 供 [schedule 工具] 与 [ScheduleScheduler]（App 启动的统一调度循环）共用。
 */
class ScheduleService @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {
    private companion object {
        const val TAG = "ScheduleService"
    }

    /** 定时参数解析结果：规则字段 + 投递正文。 */
    data class ScheduleArgs(
        val delayMs: Long = 0,
        val atMs: Long = 0,
        val intervalMs: Long = 0,
        val prompt: String = ""
    ) {
        fun toJson(): String = buildJsonObject {
            if (delayMs > 0) put("delayMs", JsonPrimitive(delayMs))
            if (atMs > 0) put("atMs", JsonPrimitive(atMs))
            if (intervalMs > 0) put("intervalMs", JsonPrimitive(intervalMs))
            if (prompt.isNotBlank()) put("prompt", JsonPrimitive(prompt))
        }.toString()
    }

    /** 创建定时项。rule 为 AFTER/AT/EVERY 名称（大写），args 为对应规则参数。 */
    suspend fun create(sessionId: String, rule: ScheduleRule, args: ScheduleArgs): ScheduleEntity {
        val now = System.currentTimeMillis()
        val entity = ScheduleEntity(
            scheduleId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            rule = rule.name,
            args = args.toJson(),
            status = ScheduleStatus.PENDING.name,
            enabled = 1,
            createdAtMs = now,
            lastFiredAtMs = null,
            updatedAtMs = now
        )
        v2Agent.upsertSchedule(
            scheduleId = entity.scheduleId,
            sessionId = entity.sessionId,
            rule = entity.rule,
            args = entity.args,
            status = entity.status,
            enabled = entity.enabled.toLong(),
            createdAtMs = entity.createdAtMs,
            lastFiredAtMs = entity.lastFiredAtMs,
            updatedAtMs = entity.updatedAtMs
        )
        FileLogger.d(TAG, "create: session=$sessionId rule=${rule.name} scheduleId=${entity.scheduleId}")
        return entity
    }

    /** 列出会话全部定时项（按创建时间升序）。 */
    suspend fun list(sessionId: String): List<ScheduleEntity> =
        v2Agent.listSchedules(sessionId).map { it.toEntity() }

    /** 取消定时项（置 CANCELLED）；不存在返回 false。 */
    suspend fun cancel(scheduleId: String): Boolean {
        val existing = v2Agent.getScheduleById(scheduleId)?.toEntity() ?: return false
        if (existing.statusEnum() == ScheduleStatus.CANCELLED) return true
        v2Agent.updateScheduleState(
            scheduleId = scheduleId,
            status = ScheduleStatus.CANCELLED.name,
            enabled = 0,
            lastFiredAtMs = existing.lastFiredAtMs,
            updatedAtMs = System.currentTimeMillis()
        )
        FileLogger.d(TAG, "cancel: scheduleId=$scheduleId")
        return true
    }

    /** 解析 args 字符串为结构化参数。 */
    fun parseArgs(entity: ScheduleEntity): ScheduleArgs =
        runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(entity.args) as JsonObject
            ScheduleArgs(
                delayMs = obj["delayMs"]?.jsonPrimitive?.longOrNull ?: 0,
                atMs = obj["atMs"]?.jsonPrimitive?.longOrNull ?: 0,
                intervalMs = obj["intervalMs"]?.jsonPrimitive?.longOrNull ?: 0,
                prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.getOrElse {
            FileLogger.w(TAG, "解析 schedule args 失败，按空参数处理: scheduleId=${entity.scheduleId}", it)
            ScheduleArgs()
        }

    /**
     * 是否到点（纯函数，供调度循环判断）。
     * - AFTER：创建时刻 + 延迟 <= now；
     * - AT：定点时间戳 <= now；
     * - EVERY：上次触发（无则创建时刻）+ 周期 <= now。
     */
    fun isDue(entity: ScheduleEntity, nowMs: Long): Boolean {
        val args = parseArgs(entity)
        return when (entity.ruleEnum()) {
            ScheduleRule.AFTER -> args.delayMs > 0 && entity.createdAtMs + args.delayMs <= nowMs
            ScheduleRule.AT -> args.atMs > 0 && args.atMs <= nowMs
            ScheduleRule.EVERY -> {
                if (args.intervalMs <= 0) return false
                val anchor = entity.lastFiredAtMs ?: entity.createdAtMs
                anchor + args.intervalMs <= nowMs
            }
        }
    }

    /** 扫描全部到点的待投递项（status=PENDING 且启用）。 */
    suspend fun dueAt(nowMs: Long): List<ScheduleEntity> =
        v2Agent.getPendingSchedules().map { it.toEntity() }.filter { isDue(it, nowMs) }

    /**
     * 投递后更新状态：一次性规则（AFTER/AT）置 FIRED；周期规则（EVERY）保持 PENDING
     * 并推进 lastFiredAtMs（供下一周期锚定）。返回更新后的实体。
     */
    suspend fun markFired(scheduleId: String, nowMs: Long): ScheduleEntity? {
        val existing = v2Agent.getScheduleById(scheduleId)?.toEntity() ?: return null
        val nextStatus = if (existing.ruleEnum() == ScheduleRule.EVERY) {
            ScheduleStatus.PENDING.name
        } else {
            ScheduleStatus.FIRED.name
        }
        val nextEnabled = if (existing.ruleEnum() == ScheduleRule.EVERY) 1 else 0
        v2Agent.updateScheduleState(
            scheduleId = scheduleId,
            status = nextStatus,
            enabled = nextEnabled.toLong(),
            lastFiredAtMs = nowMs,
            updatedAtMs = nowMs
        )
        FileLogger.d(TAG, "markFired: scheduleId=$scheduleId → $nextStatus")
        return v2Agent.getScheduleById(scheduleId)?.toEntity()
    }

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2Schedule.toEntity() = ScheduleEntity(
        scheduleId = schedule_id,
        sessionId = session_id,
        rule = rule,
        args = args,
        status = status,
        enabled = enabled.toInt(),
        createdAtMs = created_at_ms,
        lastFiredAtMs = last_fired_at_ms,
        updatedAtMs = updated_at_ms
    )
}
