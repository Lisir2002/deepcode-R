package com.R.codecore.datalayer.parity

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V1（5 个旧域库文件）行数读取实现（v2-full-takeover P1-3）。
 *
 * 经 SQLiteDatabase 直读旧库文件 `SELECT COUNT(*)`；表不存在或库未初始化返回 null。
 */
@Singleton
class AndroidV1RowCountProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : V1RowCountProvider {

    private fun databasePathFor(table: String): String? = when (table) {
        "chat_sessions", "agent_messages", "todo_items",
        "session_checkpoints", "checkpoint_file_snapshots", "file_edit_hunks",
        "mode_switch_history", "model_capability_overrides", "agent_goals", "agent_plans",
        "agent_jobs", "agent_schedules", "agent_trajectories", "agent_playbook_runs",
        "skill_state", "skill_conversation_state", "wake_queue",
        "zth_user_confirmed_sentinels", "zth_hallucination_fuses",
        "zth_sentinel_plan_rejection_audits", "zth_hard_constraint_delete_audits",
        "zth_l0_soft_compact_restore_logs", "zth_telemetry_events" -> "rcodecore_agent_db_v1"
        "ai_providers" -> "rcodecore_settings_db"
        "git_credentials" -> "rcodecore_credentials_db"
        "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state" -> "rcodecore_workspace_db"
        "t2i_providers", "t2i_provider_models", "t2i_tasks" -> "rcodecore_t2i_db"
        else -> null
    }

    override fun rowCount(table: String): Long? {
        val dbName = databasePathFor(table) ?: return null
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return null
        return runCatching {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
            } finally {
                db.close()
            }
        }.getOrNull()
    }
}