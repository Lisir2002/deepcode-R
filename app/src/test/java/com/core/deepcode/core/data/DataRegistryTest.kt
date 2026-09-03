package com.core.deepcode.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据注册表（数据层重构「新写法」）单元测试。
 *
 * 覆盖 DataRegistry 的纯编排逻辑（不依赖 Android / Room 运行时，全部用假 Provider 驱动）：
 * - [DataRegistry.pack] / [DataRegistry.unpack] tar 往返（自动迁移无感恢复的单文件落点）；
 * - [DataRegistry.snapshotAll] 全量导出 + 单域失败隔离（一个域挂不影响其余）；
 * - [DataRegistry.restoreAll] 按 key 匹配恢复 + 单域失败隔离；
 * - [DataRegistry.byKey] 查询。
 */
class DataRegistryTest {

    /** 可控的假 Provider：可注入载荷字节 / 指定导出或恢复抛错。 */
    private class FakeProvider(
        override val key: String,
        private val bytes: ByteArray = ByteArray(0),
        private val failSnapshot: Boolean = false,
        private val failRestore: Boolean = false,
    ) : DataProvider {
        override val category: DataCategory = DataCategory.TABLE
        var restored: ByteArray? = null

        override suspend fun snapshot(): DataBlob =
            if (failSnapshot) throw IllegalStateException("snapshot fail $key")
            else DataBlob(key, bytes)

        override suspend fun restore(blob: DataBlob) {
            if (failRestore) throw IllegalStateException("restore fail $key")
            restored = blob.bytes
        }
    }

    private fun registry(vararg providers: DataProvider): DataRegistry = DataRegistry(providers.toList())

    // ── pack / unpack ─────────────────────────────────────────────

    @Test
    fun `pack_unpack 往返保持 key 与字节完全一致`() = runBlocking {
        val a = DataBlob("agent_messages", "row1\nrow2".toByteArray(Charsets.UTF_8))
        val b = DataBlob("git_credentials", byteArrayOf(0x01, 0x02, 0x7f, 0x00, -1))
        val packed = registry().pack(listOf(a, b))

        val unpacked = registry().unpack(packed)
        assertEquals(2, unpacked.size)

        val byKey = unpacked.associateBy { it.key }
        assertTrue(byKey.containsKey("agent_messages"))
        assertTrue(byKey.containsKey("git_credentials"))
        assertArrayEquals("row1\nrow2".toByteArray(Charsets.UTF_8), byKey.getValue("agent_messages").bytes)
        // BLOB 含二进制值也要无损
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x7f, 0x00, -1), byKey.getValue("git_credentials").bytes)
    }

    @Test
    fun `unpack 空输入返回空列表`() = runBlocking {
        val unpacked = registry().unpack(ByteArray(0))
        assertTrue(unpacked.isEmpty())
    }

    @Test
    fun `unpack 空 tar 返回空列表`() = runBlocking {
        val emptyPack = registry().pack(emptyList())
        assertTrue(registry().unpack(emptyPack).isEmpty())
    }

    // ── snapshotAll ───────────────────────────────────────────────

    @Test
    fun `snapshotAll 收集全部域的载荷`() = runBlocking {
        val a = FakeProvider("t1", "data-a".toByteArray())
        val b = FakeProvider("t2", "data-b".toByteArray())
        val blobs = registry(a, b).snapshotAll()

        assertEquals(2, blobs.size)
        val byKey = blobs.associateBy { it.key }
        assertArrayEquals("data-a".toByteArray(), byKey.getValue("t1").bytes)
        assertArrayEquals("data-b".toByteArray(), byKey.getValue("t2").bytes)
    }

    @Test
    fun `snapshotAll 单域导出失败被跳过 不阻断其余域`() = runBlocking {
        val ok = FakeProvider("ok", "ok-data".toByteArray())
        val broken = FakeProvider("broken", failSnapshot = true)
        val blobs = registry(ok, broken).snapshotAll()

        // 失败的域不出现，成功的域仍然导出
        assertEquals(1, blobs.size)
        assertEquals("ok", blobs.single().key)
        assertArrayEquals("ok-data".toByteArray(), blobs.single().bytes)
    }

    // ── restoreAll ────────────────────────────────────────────────

    @Test
    fun `restoreAll 仅恢复传入 key 对应域 并按正确载荷写入`() = runBlocking {
        val a = FakeProvider("t1")
        val b = FakeProvider("t2")
        val c = FakeProvider("t3")
        val reg = registry(a, b, c)

        // 只传入 t1 / t3 的载荷：t2 不应被触碰
        reg.restoreAll(
            listOf(
                DataBlob("t1", "restored-1".toByteArray()),
                DataBlob("t3", "restored-3".toByteArray()),
            )
        )

        assertArrayEquals("restored-1".toByteArray(), a.restored)
        assertNull("t2 不在载荷中不应被恢复", b.restored)
        assertArrayEquals("restored-3".toByteArray(), c.restored)
    }

    @Test
    fun `restoreAll 单域恢复失败不阻断其余域`() = runBlocking {
        val ok = FakeProvider("ok")
        val broken = FakeProvider("broken", failRestore = true)
        val reg = registry(ok, broken)

        reg.restoreAll(
            listOf(
                DataBlob("ok", "x".toByteArray()),
                DataBlob("broken", "y".toByteArray()),
            )
        )

        // 失败的域抛错被吞掉，成功的域仍然恢复完成
        assertArrayEquals("x".toByteArray(), ok.restored)
    }

    @Test
    fun `restoreAll 空载荷不做任何写入`() = runBlocking {
        val a = FakeProvider("t1")
        registry(a).restoreAll(emptyList())
        assertNull(a.restored)
    }

    // ── byKey ─────────────────────────────────────────────────────

    @Test
    fun `byKey 命中返回对应 Provider 未命中返回 null`() {
        val a = FakeProvider("t1")
        val reg = registry(a)
        assertEquals(a, reg.byKey("t1"))
        assertNull(reg.byKey("no_such_domain"))
    }
}
