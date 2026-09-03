package com.core.deepcode.feature.agent.domain.tool.git

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitOpsTool 的 Conventional Commits 提交规范校验规则测试。
 *
 * 规则来源：GitOpsTool.COMMIT_REGEX（对齐 .githooks/commit-msg 的 type/scope 枚举）：
 *   ^<type>(<scope>)?[!]?: <subject>，type ∈ feat|fix|refactor|docs|style|chore|ci|build|perf|test，
 *   : 后至少一个非空字符（subject 不能为空）。
 *
 * 直接内联同一份正则做纯 JVM 断言，避免实例化 Hilt 依赖链（GitRepository/CommandEngine 等），
 * 保证规则演进时此测试与实现同步可维护。
 */
class GitOpsCommitRuleTest {

    /** 与 GitOpsTool.COMMIT_REGEX 同源的规则定义（改动必须同步）。 */
    private companion object {
        val TYPES = listOf(
            "feat", "fix", "refactor", "docs", "style",
            "chore", "ci", "build", "perf", "test"
        )
        val COMMIT_REGEX = Regex("^(${TYPES.joinToString("|")})(\\([A-Za-z0-9._-]+\\))?!?: .+")
    }

    private fun isValid(message: String): Boolean = COMMIT_REGEX.matches(message)

    // ── 合法提交信息 ──

    @Test
    fun `type with colon and subject is valid`() {
        assertTrue(isValid("feat: 新增能力"))
        assertTrue(isValid("fix: 修复崩溃"))
    }

    @Test
    fun `type with scope is valid`() {
        assertTrue(isValid("feat(agent): 新增流式工具调用"))
        assertTrue(isValid("fix(settings): 修复设置页闪退"))
    }

    @Test
    fun `type with scope and breaking change marker is valid`() {
        assertTrue(isValid("feat(agent)!: 破坏性变更"))
        assertTrue(isValid("refactor(core)!: 重构数据层"))
    }

    @Test
    fun `all registered types are valid`() {
        TYPES.forEach { type ->
            assertTrue("type=$type 应合法", isValid("$type: 提交信息"))
        }
    }

    @Test
    fun `scope allows letters digits dot underscore dash`() {
        assertTrue(isValid("feat(git.ops_v2-rc): 作用域字符集"))
    }

    @Test
    fun `english subject is valid`() {
        assertTrue(isValid("docs: update README"))
        assertTrue(isValid("ci: fix workflow"))
    }

    // ── 非法提交信息 ──

    @Test
    fun `empty message is invalid`() {
        assertFalse(isValid(""))
    }

    @Test
    fun `missing colon is invalid`() {
        assertFalse(isValid("feat 新增能力"))
        assertFalse(isValid("feat(agent) 新增能力"))
    }

    @Test
    fun `empty subject after colon is invalid`() {
        assertFalse(isValid("feat: "))
        assertFalse(isValid("feat:"))
    }

    @Test
    fun `unknown type is invalid`() {
        assertFalse(isValid("add: 新增文件"))
        assertFalse(isValid("update: 更新内容"))
        assertFalse(isValid("bugfix: 修复"))
    }

    @Test
    fun `uppercase type is invalid`() {
        assertFalse(isValid("Feat: 大写 type 不合规"))
        assertFalse(isValid("FIX: 大写 type 不合规"))
    }

    @Test
    fun `whitespace before type is invalid`() {
        assertFalse(isValid("  feat: 前导空格"))
    }

    @Test
    fun `non standard scope characters are invalid`() {
        assertFalse(isValid("feat(中文): 作用域需 ASCII"))
        assertFalse(isValid("feat(agent 2): 作用域含空格"))
    }

    @Test
    fun `breaking marker without scope is valid but bang only is not`() {
        // 允许 type!（无 scope 的 breaking 标记）
        assertTrue(isValid("feat!: 直接 breaking"))
        // 单独一个 ! 不是合法提交
        assertFalse(isValid("!"))
        // 冒号前只有 ! 无 type 不合法
        assertFalse(isValid("!: 没有 type"))
    }

    @Test
    fun `validation only checks first line of multi line message`() {
        // 实现只对 firstLine 做完整匹配（COMMIT_REGEX.matches(firstLine)）：
        // 首行合规 → 合法；首行违规 → 非法（即使后续行看起来合规）。
        val firstLine = "feat: 首行合规"
        val multiLine = "$firstLine\n第二行正文"
        // 内联实现语义：完整消息的 matches() 会因 $ 锚定被 \n 阻断而失败，
        // 因此这里显式模拟实现只校验首行（与 GitOpsTool 保持一致）。
        assertTrue(COMMIT_REGEX.matches(firstLine))
        assertFalse(COMMIT_REGEX.matches(multiLine))
        assertFalse(COMMIT_REGEX.matches("不是合法type: 首行\nfeat: 看起来像合法"))
    }
}
