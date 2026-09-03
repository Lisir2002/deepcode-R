package com.core.deepcode.feature.agent.domain.mcp.server

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 内置 MCP 服务器安全助手：token 生成/校验 + 远程暴露黑名单。
 *
 * 设计原则（见 docs/plan-docs/builtin-mcp-server-design.md §5.4）：
 * - 默认关闭、token 鉴权、远程强制审批、局域网限制、审计日志。
 * - 黑名单「宁缺毋滥」：强依赖 UI/本机会话、或修改 MCP 配置本身的工具不暴露。
 */
object McpServerSecurity {
    /** token 长度（字节），hex 化后 64 字符。 */
    private const val TOKEN_BYTES = 32

    /**
     * 不纳入 MCP 暴露的工具黑名单：
     * - askUserQuestion：强依赖 App 内 UI 交互，远程无意义；
     * - switchMode：会修改本机会话模式状态；
     * - terminal：终端会话控制强绑定本机 PTY/UI，后续按只读子集评估；
     * - browser：依赖共享 WebView 会话与用户登录态；
     * - manageMcp：管理 MCP 配置本身，远程开放等于自改配置。
     */
    private val REMOTE_BLACKLIST = setOf(
        "askUserQuestion",
        "switchMode",
        "terminal",
        "browser",
        "manageMcp"
    )

    /** 工具是否允许对外暴露。 */
    fun isExposed(name: String): Boolean = name !in REMOTE_BLACKLIST

    /** 生成 64 位 hex 随机 token（SecureRandom，不可预测）。 */
    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 常量时间比较 Bearer token，避免时序侧信道。空 token/空输入一律拒绝。 */
    fun isValidToken(expected: String, presented: String?): Boolean {
        if (expected.isBlank() || presented.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.trim().toByteArray(Charsets.UTF_8)
        )
    }
}
