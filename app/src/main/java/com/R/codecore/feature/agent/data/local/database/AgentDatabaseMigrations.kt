package com.R.codecore.feature.agent.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * agent 域库结构演进迁移（v1→v2 任务编排层新表；v2→v3 运行轨迹表）。
 *
 * 全部为**新增表**（无列变更/无数据搬迁），迁移 SQL 与 Entity 定义逐列对齐，
 * 保证 Room TableInfo 校验通过（Migration didn't properly handle 防御）。
 *
 * 说明：本迁移为程序化 Migration（不走 assets/migrations 的 SQL 文件切分器），
 * 通过 [DatabaseModule] 的 AgentDatabase builder 注册。
 */
object AgentDatabaseMigrations {

    /**
     * v1 → v2：新增 agent_goals / agent_plans / agent_jobs / agent_schedules 四张表。
     * 索引名遵循 Room 约定 `index_<table>_<cols...>`（与 @Entity indices 一致）。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── agent_goals（Goal 状态机）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_goals` (" +
                        "`goalId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "`revision` INTEGER NOT NULL DEFAULT 0, " +
                        "`parentGoalId` TEXT NOT NULL DEFAULT '', " +
                        "`roundSeq` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`goalId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_goals_sessionId` ON `agent_goals` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_goals_status` ON `agent_goals` (`status`)"
            )

            // ── agent_plans（Plan 协作状态）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_plans` (" +
                        "`planId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL DEFAULT '', " +
                        "`status` TEXT NOT NULL DEFAULT 'DRAFT', " +
                        "`pendingSelection` TEXT NOT NULL DEFAULT '', " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`planId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_plans_sessionId` ON `agent_plans` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_plans_status` ON `agent_plans` (`status`)"
            )

            // ── agent_jobs（后台任务）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_jobs` (" +
                        "`jobId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'RUNNING', " +
                        "`exitCode` INTEGER, " +
                        "`outputLocator` TEXT NOT NULL DEFAULT '', " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`finishedAtMs` INTEGER, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`jobId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_jobs_sessionId` ON `agent_jobs` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_jobs_status` ON `agent_jobs` (`status`)"
            )

            // ── agent_schedules（定时提醒）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_schedules` (" +
                        "`scheduleId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`rule` TEXT NOT NULL, " +
                        "`args` TEXT NOT NULL DEFAULT '', " +
                        "`status` TEXT NOT NULL DEFAULT 'PENDING', " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`lastFiredAtMs` INTEGER, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`scheduleId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_schedules_sessionId` ON `agent_schedules` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_schedules_status` ON `agent_schedules` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_schedules_enabled` ON `agent_schedules` (`enabled`)"
            )
        }
    }

    /**
     * v2 → v3：新增 agent_trajectories 运行轨迹表（D2-3，对齐 norm-chain-design.md §3.8）。
     *
     * append-only 轨迹表：字段与 [com.R.codecore.feature.agent.data.local.entity.TrajectoryEntity]
     * 逐列对齐（Room TableInfo 校验），索引仅 sessionId / taskId（查询走会话/任务分组，
     * turnIndex 随 taskId 过滤，不单独建索引）。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── agent_trajectories（运行轨迹，append-only）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_trajectories` (" +
                        "`trajectoryId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`taskId` TEXT NOT NULL DEFAULT '', " +
                        "`turnIndex` INTEGER NOT NULL DEFAULT 0, " +
                        "`kind` TEXT NOT NULL, " +
                        "`toolName` TEXT NOT NULL DEFAULT '', " +
                        "`argsHash` TEXT NOT NULL DEFAULT '', " +
                        "`resultSummary` TEXT NOT NULL DEFAULT '', " +
                        "`isError` INTEGER NOT NULL DEFAULT 0, " +
                        "`durationMs` INTEGER NOT NULL DEFAULT 0, " +
                        "`tokensIn` INTEGER NOT NULL DEFAULT 0, " +
                        "`tokensOut` INTEGER NOT NULL DEFAULT 0, " +
                        "`ts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`trajectoryId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_trajectories_sessionId` ON `agent_trajectories` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_trajectories_taskId` ON `agent_trajectories` (`taskId`)"
            )
        }
    }

    /**
     * v3 → v4：新增 agent_playbook_runs 剧本运行表（D5-3，对齐 norm-chain-design.md §3.3.6）。
     *
     * 字段与 [com.R.codecore.feature.agent.data.local.entity.PlaybookRunEntity] 逐列对齐
     * （Room TableInfo 校验），索引仅 sessionId / status（查询走会话 + 状态分组）。
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── agent_playbook_runs（Playbook 剧本运行，双状态机持久化）──
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `agent_playbook_runs` (" +
                        "`playbookRunId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`playbookName` TEXT NOT NULL, " +
                        "`currentStageIndex` INTEGER NOT NULL DEFAULT 0, " +
                        "`stageStatuses` TEXT NOT NULL DEFAULT '', " +
                        "`status` TEXT NOT NULL DEFAULT 'RUNNING', " +
                        "`createdAtMs` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`playbookRunId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_playbook_runs_sessionId` ON `agent_playbook_runs` (`sessionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_playbook_runs_status` ON `agent_playbook_runs` (`status`)"
            )
        }
    }
}
