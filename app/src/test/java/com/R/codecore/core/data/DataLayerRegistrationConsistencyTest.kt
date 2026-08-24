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
 * 因此强制两边登记的 Room 表集合**完全一致**（含总量 26），并校验：
 * - 无重复登记；
 * - DataStore 域单独存在（目录级转储，不在表清单内）。
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

    /** 从 DataRegistryModule.kt 提取全部双引号字符串字面量 = 注册的 Room 表清单。 */
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
    fun `注册表与移植器登记的 Room 表集合完全一致`() {
        val registered = registeredTables()
        val migrated = migratedTables()
        val setR = registered.toSet()
        val setM = migrated.toSet()

        val missingInMigrator = (setR - setM).toList().sorted()
        val extraInMigrator = (setM - setR).toList().sorted()

        assertTrue(
            "DataRegistryModule 注册表清单为空（解析失败？）", setR.isNotEmpty()
        )
        assertTrue(
            "DbSplitMigrator 移植表清单为空（解析失败？）", setM.isNotEmpty()
        )

        if (missingInMigrator.isNotEmpty() || extraInMigrator.isNotEmpty()) {
            fail(
                "数据注册一致性违规！\n" +
                    (if (missingInMigrator.isNotEmpty())
                        "DataRegistryModule 登记了、但 DbSplitMigrator 未移植（→ 该表不参与旧包一次性移植，迁移会丢数据）:\n  - ${missingInMigrator.joinToString("\n  - ")}\n" else "") +
                    (if (extraInMigrator.isNotEmpty())
                        "DbSplitMigrator 在移植、但 DataRegistryModule 未登记（→ 该表不参与全量备份/无感自动迁移）:\n  - ${extraInMigrator.joinToString("\n  - ")}\n" else "") +
                    "两边完整清单:\n  - registry(${setR.size}): ${setR.sorted().joinToString(", ")}\n  - migrator(${setM.size}): ${setM.sorted().joinToString(", ")}"
            )
        }
    }

    @Test
    fun `Room 表清单总量为 26 且无重复登记`() {
        val registered = registeredTables()
        assertEquals("注册表/移植器应覆盖全部 26 张 Room 表", 26, registered.size)

        val dup = registered.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("存在重复登记的表: ${dup.keys}", dup.isEmpty())
    }

    @Test
    fun `5 个域库表数分布与设计一致`() {
        val registered = registeredTables()
        // agent 17 + settings 1 + credentials 1 + workspace 4 + t2i 3
        val agentTables = listOf(
            "agent_messages", "chat_sessions", "todo_items", "session_checkpoints",
            "checkpoint_file_snapshots", "file_edit_hunks", "mode_switch_history",
            "model_capability_overrides", "zth_user_confirmed_sentinels", "zth_hallucination_fuses",
            "zth_sentinel_plan_rejection_audits", "zth_hard_constraint_delete_audits",
            "zth_l0_soft_compact_restore_logs", "zth_telemetry_events", "skill_conversation_state",
            "skill_state", "wake_queue",
        )
        val workspaceTables = listOf(
            "remote_connections", "remote_mounts", "remote_audit_logs", "credential_encryption_state",
        )
        val t2iTables = listOf("t2i_providers", "t2i_provider_models", "t2i_tasks")

        val expected = agentTables + listOf("ai_providers") + listOf("git_credentials") + workspaceTables + t2iTables
        assertEquals(expected.toSet(), registered.toSet())
    }
}
