package com.R.codecore.feature.backup.data.guard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据完整性哨兵纯判定逻辑测试（数据保全防线 D10）。
 *
 * 覆盖 SentinelLogic.evaluate 的五个分支：全新安装 / 包名变更 / 数据丢失 / 正常升级 / 普通运行。
 * 这是「区分历史对话被清空 vs 全新安装」的核心判定，必须全绿，防止回归。
 */
class SentinelLogicTest {

    private val meta = RunMeta(
        dataInitialized = true,
        lastVersionCode = 100,
        lastApplicationId = "com.R.codecore",
    )

    @Test
    fun `未初始化_首次运行_返回FIRST_RUN`() {
        val verdict = SentinelLogic.evaluate(
            meta = RunMeta(dataInitialized = false),
            currentVersionCode = 100,
            currentApplicationId = "com.R.codecore",
            sessionCount = 0,
        )
        assertEquals(SentinelVerdict.FIRST_RUN, verdict)
    }

    @Test
    fun `全新安装_即便有历史_也返回FIRST_RUN`() {
        // 新包名首次运行：即便之前旧包有很多会话，本包名下仍是全新安装 → FIRST_RUN（静默）
        val verdict = SentinelLogic.evaluate(
            meta = RunMeta(dataInitialized = false),
            currentVersionCode = 100,
            currentApplicationId = "com.deep.rcode",
            sessionCount = 0,
        )
        assertEquals(SentinelVerdict.FIRST_RUN, verdict)
    }

    @Test
    fun `包名变更_优先返回PACKAGE_CHANGED`() {
        // 包名变更时新包数据必为空（sessionCount=0），必须优先判为 PACKAGE_CHANGED 而非 DATA_LOST
        val verdict = SentinelLogic.evaluate(
            meta = meta,
            currentVersionCode = 101,
            currentApplicationId = "com.deep.rcode", // 与 meta.lastApplicationId 不一致
            sessionCount = 0,
        )
        assertEquals(SentinelVerdict.PACKAGE_CHANGED, verdict)
    }

    @Test
    fun `已初始化但会话为空_返回DATA_LOST`() {
        val verdict = SentinelLogic.evaluate(
            meta = meta,
            currentVersionCode = 100,
            currentApplicationId = "com.R.codecore",
            sessionCount = 0,
        )
        assertEquals(SentinelVerdict.DATA_LOST, verdict)
    }

    @Test
    fun `正常升级_返回UPGRADED`() {
        val verdict = SentinelLogic.evaluate(
            meta = meta,
            currentVersionCode = 101,
            currentApplicationId = "com.R.codecore",
            sessionCount = 5,
        )
        assertEquals(SentinelVerdict.UPGRADED, verdict)
    }

    @Test
    fun `同版本重复运行_返回NORMAL`() {
        val verdict = SentinelLogic.evaluate(
            meta = meta,
            currentVersionCode = 100,
            currentApplicationId = "com.R.codecore",
            sessionCount = 5,
        )
        assertEquals(SentinelVerdict.NORMAL, verdict)
    }

    @Test
    fun `版本回退_不算升级_返回NORMAL`() {
        // 版本回退（versionCode 变小）不应触发升级自动备份，按普通运行处理
        val verdict = SentinelLogic.evaluate(
            meta = meta,
            currentVersionCode = 99,
            currentApplicationId = "com.R.codecore",
            sessionCount = 5,
        )
        assertEquals(SentinelVerdict.NORMAL, verdict)
    }
}
