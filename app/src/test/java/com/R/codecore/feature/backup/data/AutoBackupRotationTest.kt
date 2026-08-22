package com.R.codecore.feature.backup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动备份轮转纯判定逻辑测试（数据保全防线 D10）。
 *
 * 覆盖 `excessBackupFiles`：不超过保留上限时全保留；超过时只删最旧的超出部分。
 * 这是「本机自动备份不无限膨胀 / 不误删最新备份」的核心约束。
 */
class AutoBackupRotationTest {

    private val keepMax = 7

    private fun files(n: Int): List<File> =
        (0 until n).map { File("backup-${it}.tar.gz") }

    @Test
    fun `不超过保留上限_全部保留不删除`() {
        val list = files(keepMax)
        assertTrue(excessBackupFiles(list, keepMax).isEmpty())
    }

    @Test
    fun `空列表_无删除`() {
        assertTrue(excessBackupFiles(emptyList(), keepMax).isEmpty())
    }

    @Test
    fun `超出上限_仅删除最旧的超出部分`() {
        val list = files(keepMax + 3) // 10 份
        val excess = excessBackupFiles(list, keepMax)
        assertEquals(3, excess.size)
        // 最新在前，保留前 7 份，删除索引 7..9（最旧的 3 份）
        assertEquals(listOf("backup-7.tar.gz", "backup-8.tar.gz", "backup-9.tar.gz"), excess.map { it.name })
    }

    @Test
    fun `超出上限_最新备份始终保留`() {
        val list = files(keepMax + 10)
        val excess = excessBackupFiles(list, keepMax).toSet()
        val kept = list.filterNot { it in excess }
        assertEquals(keepMax, kept.size)
        // 最新的第一份必然被保留
        assertEquals(list.first(), kept.first())
    }

    // ── 外部安全备份轮转（包名无关安全网，D6b） ──────────────────────

    private fun externalItems(n: Int): List<ExternalBackupStore.Item> =
        (0 until n).map {
            ExternalBackupStore.Item(
                // uri 仅用于真实读写，纯 JVM 单测无需 android Uri，置 null（可空字段）
                uri = null,
                name = "backup-${it}.tar.gz",
                epochMs = 1_000_000_000_000L + it * 1000L,
            )
        }

    @Test
    fun `外部备份_不超过保留上限_全部保留不删除`() {
        val list = externalItems(keepMax)
        assertTrue(excessExternalBackups(list, keepMax).isEmpty())
    }

    @Test
    fun `外部备份_超出上限_仅删除最旧的超出部分`() {
        val list = externalItems(keepMax + 3)
        val excess = excessExternalBackups(list, keepMax)
        assertEquals(3, excess.size)
        assertEquals(
            listOf("backup-7.tar.gz", "backup-8.tar.gz", "backup-9.tar.gz"),
            excess.map { it.name }
        )
    }
}
