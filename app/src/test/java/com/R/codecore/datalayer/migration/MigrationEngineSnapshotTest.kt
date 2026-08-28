package com.R.codecore.datalayer.migration

import com.R.codecore.datalayer.engine.DatabasePathProvider
import com.R.codecore.datalayer.engine.LibName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2-full-takeover P0-3：[MigrationEngine] 文件级快照安全网语义单测。
 *
 * 重点是 [MigrationEngine.restoreSnapshot] 的**拷贝方向**：
 * 历史实现为 `main.copyTo(bak)`，会用（迁移失败后的）主库覆盖唯一的安全网快照，
 * 回滚不但无效还销毁了救援手段。本测试同时钉住：
 *  1. 回滚后主库内容 == 快照内容；
 *  2. 回滚后快照内容**不变**（可重复回滚）；
 *  3. 无快照时返回 false 且不动主库。
 */
class MigrationEngineSnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** JVM 侧路径实现：不依赖 Android Context，仅覆盖引擎用到的三个解析方法。 */
    private class FolderPathProvider(root: File) : DatabasePathProvider {
        private val dbDir = root.resolve("databases").apply { mkdirs() }
        private val bakDir = root.resolve("backup").apply { mkdirs() }
        override fun mainDb(lib: LibName): File = dbDir.resolve(lib.fileName)
        override fun backupDir(): File = bakDir
    }

    private fun engine(): Pair<MigrationEngine, FolderPathProvider> {
        val p = FolderPathProvider(tmp.newFolder())
        return MigrationEngine(p) to p
    }

    @Test
    fun `snapshot copies main db with wal and shm sidecars`() {
        val (engine, p) = engine()
        val main = p.mainDb(LibName.SETTINGS).apply { writeText("GOOD") }
        main.resolveSibling("${main.name}-wal").writeText("WAL")
        main.resolveSibling("${main.name}-shm").writeText("SHM")

        engine.snapshot(LibName.SETTINGS, heavy = false)

        val bak = p.snapshotFile(LibName.SETTINGS)
        assertEquals("GOOD", bak.readText())
        assertEquals("WAL", bak.resolveSibling("${bak.name}-wal").readText())
        assertEquals("SHM", bak.resolveSibling("${bak.name}-shm").readText())
    }

    @Test
    fun `restoreSnapshot restores main from snapshot and keeps snapshot intact`() {
        val (engine, p) = engine()
        val main = p.mainDb(LibName.SETTINGS).apply { writeText("GOOD") }
        val wal = main.resolveSibling("${main.name}-wal").apply { writeText("WAL-GOOD") }

        engine.snapshot(LibName.SETTINGS, heavy = false)

        // 模拟迁移中途失败：主库与 -wal 已被写坏
        main.writeText("CORRUPTED")
        wal.writeText("WAL-BAD")

        assertTrue(engine.restoreSnapshot(LibName.SETTINGS))

        assertEquals("主库应回滚为快照内容", "GOOD", main.readText())
        assertEquals("wal 应回滚", "WAL-GOOD", wal.readText())

        val bak = p.snapshotFile(LibName.SETTINGS)
        assertEquals("回滚绝不允许破坏快照", "GOOD", bak.readText())

        // 快照仍在 → 可重复回滚
        main.writeText("CORRUPTED-AGAIN")
        assertTrue(engine.restoreSnapshot(LibName.SETTINGS))
        assertEquals("GOOD", main.readText())
    }

    @Test
    fun `restoreSnapshot without snapshot returns false and leaves main db untouched`() {
        val (engine, p) = engine()
        val main = p.mainDb(LibName.AGENT).apply { writeText("KEEP-ME") }

        assertFalse(engine.restoreSnapshot(LibName.AGENT))
        assertEquals("KEEP-ME", main.readText())
    }

    @Test
    fun `restoreSnapshot drops stale wal when snapshot has no wal`() {
        val (engine, p) = engine()
        val main = p.mainDb(LibName.T2I).apply { writeText("GOOD") }
        // 快照时没有 wal，回滚后主库却残留旧 wal —— 属于与新主库不一致的陈旧文件，必须清掉

        engine.snapshot(LibName.T2I, heavy = false)
        main.writeText("CORRUPTED")
        main.resolveSibling("${main.name}-wal").writeText("STALE-WAL")

        assertTrue(engine.restoreSnapshot(LibName.T2I))
        assertEquals("GOOD", main.readText())
        assertFalse("陈旧 wal 应被清除", main.resolveSibling("${main.name}-wal").exists())
    }

    @Test
    fun `snapshot then restore round trip is byte-identical`() {
        val (engine, p) = engine()
        val payload = ByteArray(4096) { (it % 251).toByte() }
        p.mainDb(LibName.INFRA).writeBytes(payload)

        engine.snapshot(LibName.INFRA, heavy = false)
        p.mainDb(LibName.INFRA).writeBytes(ByteArray(16))

        assertTrue(engine.restoreSnapshot(LibName.INFRA))
        assertArrayEquals(payload, p.mainDb(LibName.INFRA).readBytes())
    }
}
