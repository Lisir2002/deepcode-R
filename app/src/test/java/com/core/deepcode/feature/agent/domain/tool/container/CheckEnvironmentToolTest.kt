package com.core.deepcode.feature.agent.domain.tool.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境探测相关单测（纯 Kotlin，沙箱无 Android SDK 时也可运行）。
 *
 * 覆盖两个历史事故点：
 * 1) 脚本层 `tail --version` 会把 BusyBox stderr (`tail: unrecognized option: version`)
 *    填进 version，UI 显示为 "已安装 ✓ 版本=tail: unrecognized option: version"。
 *    修复：脚本层看退出码 + 解析层 sanitizeVersion 兜底（本测试覆盖 sanitizeVersion）。
 * 2) inferComponentsFromCommand 会把管道片段数字/flag/路径当组件名，UI 上出现
 *    名为 "1" 的「缺失」项。修复：looksLikeNonProgramToken。
 */
class CheckEnvironmentToolTest {

    // ---------- sanitizeVersion：把 stderr 风格的假版本号清空 ----------

    @Test
    fun sanitizeVersion_stripsBusyBoxUnrecognizedOption() {
        assertTrue(CheckEnvironmentTool.sanitizeVersion("tail: unrecognized option: version").isEmpty())
        assertTrue(CheckEnvironmentTool.sanitizeVersion("invalid option -- 'v'").isEmpty())
        assertTrue(CheckEnvironmentTool.sanitizeVersion("Unknown option: --version").isEmpty())
        assertTrue(CheckEnvironmentTool.sanitizeVersion("Usage: nc [OPTIONS] HOST PORT").isEmpty())
        assertTrue(CheckEnvironmentTool.sanitizeVersion(
            "BusyBox v1.37.0 multi-call binary.\nUsage: ...").isEmpty())
    }

    @Test
    fun sanitizeVersion_keepsRealVersionStrings() {
        assertEquals("git version 2.43.0", CheckEnvironmentTool.sanitizeVersion("git version 2.43.0"))
        assertEquals("openjdk 17.0.12", CheckEnvironmentTool.sanitizeVersion("  openjdk 17.0.12  "))
        assertEquals("Python 3.12.3", CheckEnvironmentTool.sanitizeVersion("Python 3.12.3"))
        assertTrue(CheckEnvironmentTool.sanitizeVersion("").isEmpty())
    }

    // ---------- inferComponentsFromCommand 非程序名过滤 ----------

    @Test
    fun inferComponentsFromCommand_filtersNumericFlagAndPathTokens() {
        // `head -1 /tmp/install.log | tail -n 4` 的管道片段：
        // head/ tail 是合法程序（tail 虽非构建核心但属开放探测组件）；
        // 而 "1"、"4"、"-n"、"/tmp/install.log" 这些绝不应当组件。
        val components = CheckEnvironmentTool.inferComponentsFromCommand(
            "cat /tmp/install.log | head -1 | tail -n 4"
        )
        assertEquals(listOf("cat", "head", "tail"), components)
        assertFalse("不应把纯数字当组件: $components", components.any { it.all(Char::isDigit) })
        assertFalse("不应把 flag 当组件: $components", components.any { it.startsWith("-") })
        assertFalse("不应把文件路径带后缀当组件: $components",
            components.any { it.endsWith(".log") })
    }

    @Test
    fun inferComponentsFromCommand_normalizesKnownAndSkipsPackageManagers() {
        val components = CheckEnvironmentTool.inferComponentsFromCommand(
            "apk add --no-cache python3 ; python3 -m pip install -r requirements.txt ; git status"
        )
        // apk → 包管理器跳过；python3→Python；pip→Python（去重）；git→Git
        assertEquals(listOf("Python", "Git"), components)
    }

    @Test
    fun inferComponentsFromCommand_keepsBuildScriptNames() {
        val components = CheckEnvironmentTool.inferComponentsFromCommand(
            "./my-custom-build.sh --target release && ./gradlew assembleDebug"
        )
        assertTrue("应保留自定义脚本: $components", "my-custom-build.sh" in components)
        assertTrue("应规范化 gradlew→Gradle: $components", "Gradle" in components)
    }
}
