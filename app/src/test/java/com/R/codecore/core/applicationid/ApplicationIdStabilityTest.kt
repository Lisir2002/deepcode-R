package com.R.codecore.core.applicationid

import com.R.codecore.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 数据持久化守卫：release 变体的 applicationId 必须恒为 `com.R.codecore`。
 *
 * 背景：包名（applicationId）三次变更——`com.aicodeeditor` → `com.deep.rcode` → `com.R.codecore`。
 * 每次变更在 Android 眼里都是**完全不同的 App**，安装新包名是一次"全新安装"，其私有数据目录
 * （/data/data/<新包名>/）为空 → 旧包的全部历史对话"消失"，只剩新装后自动创建的那一条会话。
 * 这是历史上"每次更新历史对话清空"的直接根因。
 *
 * 本测试把 release applicationId 锁死，防止未来 rebrand 误改包名再次造成用户数据丢失。
 * 注意：debug 变体带 `.debug` 后缀（com.R.codecore.debug），因此本测试只对 release 变体成立，
 * 必须通过 `:app:testReleaseUnitTest`（CI 门禁同款）运行。
 */
class ApplicationIdStabilityTest {

    private val legacyPackages = setOf("com.aicodeeditor", "com.deep.rcode")

    @Test
    fun `release_applicationId_恒为_com_R_codecore`() {
        assertEquals("com.R.codecore", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `release_applicationId_不得回退为历史遗留包名`() {
        assertFalse(
            "applicationId 曾变更为 $legacyPackages 导致用户历史数据丢失，禁止回退",
            BuildConfig.APPLICATION_ID in legacyPackages
        )
    }

    @Test
    fun `release_applicationId_不应再引入新包名`() {
        // 任何 rebrand 都只允许改应用名/图标/namespace，禁止改 applicationId
        assertEquals("com.R.codecore", BuildConfig.APPLICATION_ID)
    }
}
