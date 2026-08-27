package com.R.codecore.datalayer.parity

import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.credentials.data.local.database.CredentialsDatabase
import com.R.codecore.feature.settings.data.local.database.SettingsDatabase
import com.R.codecore.feature.t2i.data.local.database.T2IDatabase
import com.R.codecore.feature.workspace.data.local.database.WorkspaceDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V1（5 个 Room 域库）行数读取实现（v2-full-takeover P1-3）。
 *
 * 经 RoomDatabase 直查 `SELECT COUNT(*)`；表不存在或库未初始化返回 null。
 */
@Singleton
class AndroidV1RowCountProvider @Inject constructor(
    private val agentDb: AgentDatabase,
    private val settingsDb: SettingsDatabase,
    private val credentialsDb: CredentialsDatabase,
    private val workspaceDb: WorkspaceDatabase,
    private val t2iDb: T2IDatabase,
) : V1RowCountProvider {

    private fun databaseFor(table: String): androidx.room.RoomDatabase? = when (table) {
        "chat_sessions", "agent_messages", "todo_items",
        "session_checkpoints", "checkpoint_file_snapshots", "file_edit_hunks",
        "mode_switch_history", "model_capability_overrides", "agent_goals", "agent_plans",
        "agent_jobs", "agent_schedules", "agent_trajectories", "agent_playbook_runs",
        "skill_state", "skill_conversation_state", "wake_queue",
        "zth_user_confirmed_sentinels", "zth_hallucination_fuses",
        "zth_sentinel_plan_rejection_audits", "zth_hard_constraint_delete_audits",
        "zth_l0_soft_compact_restore_logs", "zth_telemetry_events" -> agentDb
        "ai_providers" -> settingsDb
        "git_credentials" -> credentialsDb
        "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state" -> workspaceDb
        "t2i_providers", "t2i_provider_models", "t2i_tasks" -> t2iDb
        else -> null
    }

    override fun rowCount(table: String): Long? {
        val db = databaseFor(table) ?: return null
        return runCatching {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()
    }
}
