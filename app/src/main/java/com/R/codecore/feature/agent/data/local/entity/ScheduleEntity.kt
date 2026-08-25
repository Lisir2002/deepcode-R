package com.R.codecore.feature.agent.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.R.codecore.core.util.EnumSafe

/**
 * 会话级「定时提醒」持久化（对齐 DSH schedule）：
 * 规则 AFTER(延迟)/AT(定点)/EVERY(周期) 三种，args 为 JSON 参数
 * （延迟毫秒 / 定点时间戳 / 周期毫秒）。跨重启恢复靠本表持久化 + 启动扫描未投递项。
 *
 * 表名 agent_schedules，agent 库 v1→v2 新增（见 AgentDatabaseMigrations）。
 */
@Entity(
    tableName = "agent_schedules",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["status"]),
        Index(value = ["enabled"])
    ]
)
data class ScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val sessionId: String,
    /** [ScheduleRule] 名称。 */
    val rule: String,
    /** 规则参数的 JSON 序列化（{delayMs}/{atMs}/{intervalMs}）。 */
    @ColumnInfo(defaultValue = "''")
    val args: String = "",
    /** [ScheduleStatus] 名称。 */
    @ColumnInfo(defaultValue = "'PENDING'")
    val status: String = ScheduleStatus.PENDING.name,
    /** 是否启用（Boolean 存 INTEGER：1 启用 / 0 停用）。 */
    @ColumnInfo(defaultValue = "1")
    val enabled: Int = 1,
    /** 创建时间毫秒。 */
    val createdAtMs: Long,
    /** 上次投递时间毫秒（未投递过为 null）。 */
    val lastFiredAtMs: Long? = null,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun ruleEnum(): ScheduleRule =
        EnumSafe.valueOf(rule, ScheduleRule.EVERY, tag = "ScheduleEntity.rule")

    fun statusEnum(): ScheduleStatus =
        EnumSafe.valueOf(status, ScheduleStatus.PENDING, tag = "ScheduleEntity.status")

    val isEnabled: Boolean get() = enabled != 0
}

/** 定时规则类型（对齐 DSH schedule rule）。 */
enum class ScheduleRule {
    /** 延迟指定毫秒后触发一次（args.delayMs）。 */
    AFTER,
    /** 到指定时间戳触发一次（args.atMs）。 */
    AT,
    /** 按周期毫秒循环触发（args.intervalMs）。 */
    EVERY
}

/** 定时项生命周期状态。 */
enum class ScheduleStatus {
    /** 等待触发。 */
    PENDING,
    /** 已触发（一次性规则触发后置此态）。 */
    FIRED,
    /** 已取消。 */
    CANCELLED
}
