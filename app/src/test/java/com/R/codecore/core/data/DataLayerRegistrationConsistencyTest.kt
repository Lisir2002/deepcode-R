package com.R.codecore.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileFilter

/**
 * 数据层重构（新写法）· 数据注册一致性闸门（纯静态解析，不依赖 Android / Room 运行时）。
 *
 * 「备份/恢复/自动迁移（DataRegistry）」与「旧单库→新 5 库一次性移植（DbSplitMigrator）」
 * 都依赖同一张「表清单」，任何一处漏登记都会造成数据不可见：
 * - DataRegistryModule 漏登记 → 该表不参与全量备份/无感自动迁移；
 * - DbSplitMigrator 漏登记 → 该表不参与旧包一次性移植。
 *
 * 因此强制：移植器登记的全部旧库表（26 张）必须 ⊆ 注册表（50 张）——注册表允许 registry-only
 * 新表（任务编排层 agent_goals/agent_plans/agent_jobs/agent_schedules + 轨迹 agent_trajectories +
 * 剧本 agent_playbook_runs，v1 之后新增、旧单巨库无此数据），
 * 并校验总量（50）、无重复登记、分布（Room 32：agent 23 + settings 1 + credentials 1 + workspace 4 + t2i 3；
 * SQLDelight 18：agent 5 + credentials 2 + settings 2 + workspace 2 + t2i 2 + infra 5）、
 * DataStore 域单独存在（目录级转储，不在表清单内）。
 */
class DataLayerRegistrationConsistencyTest {

    private val projectRoot: File by lazy {
        var f = File(System.getProperty("user.dir") ?: ".")
        for (i in 0..5) {
            val has = f.listFiles(FileFilter { it.name.startsWith("settings.gradle") })?.isNotEmpty() == true
            if (has) return@lazy f
            f = f.parentFile ?: break
        }
        File(System.getProperty("user.dir") ?: ".").resolve("../..").canonicalFile
    }

    private fun mainSource(relative: String): File {
        val p = projectRoot.resolve("app/src/main/java/$relative")
        assertTrue("源码文件不存在: ${p.path}", p.isFile)
        return p
    }

    /** 从 DataRegistryModule.kt 提取全部双引号字符串字面量 = 注册的表清单（Room 32 + SQLDelight v2 18）。 */
    private fun registeredTables(): List<String> =
        Regex(""""([^"]+)"""").findAll(mainSource("com/R/codecore/core/data/DataRegistryModule.kt").readText())
            .map { it.groupValues[1] }
            .toList()

    /** 从 DbSplitMigrator.kt 提取 TABLE_TO_DB 映射的全部 key = 待移植的旧表清单。 */
    private fun migratedTables(): List<String> =
        Regex(""""([^"]+)"\s+to\s+""").findAll(mainSource("com/R/codecore/core/db/DbSplitMigrator.kt").readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `注册表覆盖移植器的全部旧库表`() {
        val registered = registeredTables()
        val migrated = migratedTables()
        val setR = registered.toSet()
        val setM = migrated.toSet()

        val missingInRegistry = (setM - setR).toList().sorted()

        assertTrue(
            "DataRegistryModule 注册表清单为空（解析失败？）", setR.isNotEmpty()
        )
        assertTrue(
            "DbSplitMigrator 移植表清单为空（解析失败？）", setM.isNotEmpty()
        )

        if (missingInRegistry.isNotEmpty()) {
            fail(
                "数据注册一致性违规！\n" +
                    "DbSplitMigrator 在移植、但 DataRegistryModule 未登记（→ 该表不参与全量备份/无感自动迁移）:\n  - ${missingInRegistry.joinToString("\n  - ")}\n" +
                    "注册表完整清单(${setR.size}): ${setR.sorted().joinToString(", ")}\n" +
                    "移植表完整清单(${setM.size}): ${setM.sorted().joinToString(", ")}"
            )
        }
        // 任务编排层新增表（agent_goals/agent_plans/agent_jobs/agent_schedules）只存在于注册表，
        // 是 v1 之后的新表（旧单巨库无此数据），允许 registry-only（不要求出现在 DbSplitMigrator）。
    }

    @Test
    fun `注册表总量为 50 且无重复登记`() {
        val registered = registeredTables()
        assertEquals(
            "注册表应覆盖 50 张表（Room 32：agent 23 + settings 1 + credentials 1 + workspace 4 + t2i 3；" +
                "SQLDelight 18：agent 5 + credentials 2 + settings 2 + workspace 2 + t2i 2 + infra 5）",
            50, registered.size,
        )

        val dup = registered.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("存在重复登记的表: ${dup.keys}", dup.isEmpty())
    }

    @Test
    fun `5 个域库 + infra 表数分布与设计一致`() {
        val registered = registeredTables()
        // Room 32：agent 23 + settings 1 + credentials 1 + workspace 4 + t2i 3
        val agentTables = listOf(
            "agent_messages", "chat_sessions", "todo_items", "session_checkpoints",
            "checkpoint_file_snapshots", "file_edit_hunks", "mode_switch_history",
            "model_capability_overrides", "zth_user_confirmed_sentinels", "zth_hallucination_fuses",
            "zth_sentinel_plan_rejection_audits", "zth_hard_constraint_delete_audits",
            "zth_l0_soft_compact_restore_logs", "zth_telemetry_events", "skill_conversation_state",
            "skill_state", "wake_queue",
            "agent_goals", "agent_plans", "agent_jobs", "agent_schedules",
            "agent_trajectories", "agent_playbook_runs",
        )
        val workspaceTables = listOf(
            "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state",
        )
        val t2iTables = listOf("t2i_providers", "t2i_provider_models", "t2i_tasks")

        // SQLDelight v2 18：agent 5 + credentials 2 + settings 2 + workspace 2 + t2i 2 + infra 5
        val v2AgentTables = listOf(
            "agent_session", "agent_message", "agent_message_part", "agent_tool_call", "agent_checkpoint",
        )
        val v2CredentialsTables = listOf("cred_connection", "cred_secret")
        val v2SettingsTables = listOf("settings_profile", "settings_pref")
        val v2WorkspaceTables = listOf("workspace_project", "workspace_file")
        val v2T2iTables = listOf("t2i_task", "t2i_result")
        val v2InfraTables = listOf("kv_store", "doc_store", "queue_store", "blob_store", "ts_store")

        val expected = agentTables + listOf("ai_providers") + listOf("git_credentials") + workspaceTables + t2iTables +
            v2AgentTables + v2CredentialsTables + v2SettingsTables + v2WorkspaceTables + v2T2iTables + v2InfraTables
        assertEquals("注册表应精确等于 50 张表清单", expected.toSet(), registered.toSet())
    }
}
