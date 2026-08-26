package com.R.codecore.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointDao
import com.R.codecore.feature.agent.data.local.dao.CheckpointFileSnapshotDao
import com.R.codecore.feature.agent.data.local.dao.FileEditHunkDao
import com.R.codecore.feature.agent.data.local.dao.GoalDao
import com.R.codecore.feature.agent.data.local.dao.HallucinationFuseDao
import com.R.codecore.feature.agent.data.local.dao.ModeSwitchHistoryDao
import com.R.codecore.feature.agent.data.local.dao.HardConstraintDeleteAuditDao
import com.R.codecore.feature.agent.data.local.dao.JobDao
import com.R.codecore.feature.agent.data.local.dao.L0SoftCompactRestoreLogDao
import com.R.codecore.feature.agent.data.local.dao.ModelCapabilityOverrideDao
import com.R.codecore.feature.agent.data.local.dao.PlanDao
import com.R.codecore.feature.agent.data.local.dao.PlaybookRunDao
import com.R.codecore.feature.agent.data.local.dao.ScheduleDao
import com.R.codecore.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.R.codecore.feature.agent.data.local.dao.TrajectoryDao
import com.R.codecore.feature.agent.data.local.dao.SkillConversationStateDao
import com.R.codecore.feature.agent.data.local.dao.SkillStateDao
import com.R.codecore.feature.agent.data.local.dao.TodoItemDao
import com.R.codecore.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.R.codecore.feature.agent.data.local.dao.WakeQueueDao
import com.R.codecore.feature.agent.data.local.dao.ZthTelemetryEventDao
import com.R.codecore.feature.agent.data.local.entity.AgentMessageEntity
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.R.codecore.feature.agent.data.local.entity.FileEditHunkEntity
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import com.R.codecore.feature.agent.data.local.entity.HallucinationFuseEntity
import com.R.codecore.feature.agent.data.local.entity.HardConstraintDeleteAuditEntity
import com.R.codecore.feature.agent.data.local.entity.JobEntity
import com.R.codecore.feature.agent.data.local.entity.L0SoftCompactRestoreLogEntity
import com.R.codecore.feature.agent.data.local.entity.ModeSwitchHistoryEntity
import com.R.codecore.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.R.codecore.feature.agent.data.local.entity.PlanEntity
import com.R.codecore.feature.agent.data.local.entity.PlaybookRunEntity
import com.R.codecore.feature.agent.data.local.entity.ScheduleEntity
import com.R.codecore.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.R.codecore.feature.agent.data.local.entity.SkillConversationStateEntity
import com.R.codecore.feature.agent.data.local.entity.SkillStateEntity
import com.R.codecore.feature.agent.data.local.entity.TodoItemEntity
import com.R.codecore.feature.agent.data.local.entity.TrajectoryEntity
import com.R.codecore.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.R.codecore.feature.agent.data.local.entity.WakeItemEntity
import com.R.codecore.feature.agent.data.local.entity.ZthTelemetryEventEntity

/**
 * 数据层重构（新写法）后的 agent 域独立库（v1 全新）。
 *
 * 拆分自旧单巨库 [LegacyAgentDatabase]（v49，见 T1a）。仅承载 agent 域
 * （消息/会话/todo/checkpoint/skill/wake/zth 等 17 实体 + 任务编排层
 * Goal/Plan/Job/Schedule 4 实体 + 运行轨迹 Trajectory + Playbook 剧本运行
 * PlaybookRun 实体，共 23 实体），与其他 4 个域库
 * （settings / credentials / workspace / t2i）完全解耦，任何 feature 改表
 * 不再挤进同一条迁移链。
 *
 * 新库 v1 起全新、无历史迁移链；v1→v2 为任务编排层新增表迁移（见
 * [AgentDatabaseMigrations.MIGRATION_1_2]，在 [com.R.codecore.di.DatabaseModule] 注册）；
 * v2→v3 为运行轨迹表新增迁移（见 [AgentDatabaseMigrations.MIGRATION_2_3]）；
 * v3→v4 为 Playbook 剧本运行表新增迁移（见 [AgentDatabaseMigrations.MIGRATION_3_4]）；
 * 旧库数据由 [LegacyAgentDatabase] 一次性移植。
 */
@Database(
    entities = [
        AgentMessageEntity::class,
        ChatSessionEntity::class,
        TodoItemEntity::class,
        CheckpointEntity::class,
        CheckpointFileSnapshotEntity::class,
        FileEditHunkEntity::class,
        ModeSwitchHistoryEntity::class,
        ModelCapabilityOverrideEntity::class,
        UserConfirmedSentinelEntity::class,
        HallucinationFuseEntity::class,
        SentinelPlanRejectionAuditEntity::class,
        HardConstraintDeleteAuditEntity::class,
        L0SoftCompactRestoreLogEntity::class,
        ZthTelemetryEventEntity::class,
        SkillConversationStateEntity::class,
        SkillStateEntity::class,
        WakeItemEntity::class,
        GoalEntity::class,
        PlanEntity::class,
        JobEntity::class,
        ScheduleEntity::class,
        TrajectoryEntity::class,
        PlaybookRunEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun checkpointFileSnapshotDao(): CheckpointFileSnapshotDao
    abstract fun fileEditHunkDao(): FileEditHunkDao
    abstract fun modeSwitchHistoryDao(): ModeSwitchHistoryDao
    abstract fun modelCapabilityOverrideDao(): ModelCapabilityOverrideDao
    abstract fun userConfirmedSentinelDao(): UserConfirmedSentinelDao
    abstract fun hallucinationFuseDao(): HallucinationFuseDao
    abstract fun sentinelPlanRejectionAuditDao(): SentinelPlanRejectionAuditDao
    abstract fun hardConstraintDeleteAuditDao(): HardConstraintDeleteAuditDao
    abstract fun l0SoftCompactRestoreLogDao(): L0SoftCompactRestoreLogDao
    abstract fun zthTelemetryEventDao(): ZthTelemetryEventDao
    abstract fun skillConversationStateDao(): SkillConversationStateDao
    abstract fun skillStateDao(): SkillStateDao
    abstract fun wakeQueueDao(): WakeQueueDao
    abstract fun goalDao(): GoalDao
    abstract fun planDao(): PlanDao
    abstract fun jobDao(): JobDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun trajectoryDao(): TrajectoryDao
    abstract fun playbookRunDao(): PlaybookRunDao

    companion object {
        const val SCHEMA_VERSION = 4
    }
}
