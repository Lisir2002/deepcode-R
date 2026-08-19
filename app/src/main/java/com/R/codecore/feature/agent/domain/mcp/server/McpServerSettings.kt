package com.R.codecore.feature.agent.domain.mcp.server

import kotlinx.serialization.Serializable

/**
 * 内置 MCP 服务器的运行时配置快照（一次性传给 [McpHttpServer]，配置变更走「重启」生效）。
 *
 * 与客户端侧的 [com.R.codecore.feature.agent.domain.mcp.McpServerConfig]（连别人）区分：
 * 本模型是「把自己开放出去」的服务端配置。持久化在 DataStore（见 McpServerManager），
 * 这里只承载一次启动所需的全部参数。
 *
 * @param enabled 总开关（默认关闭，用户显式开启才监听）。
 * @param port 监听端口（默认 3000）。
 * @param token Bearer token，空串表示不鉴权（不建议）。
 * @param requireApproval 远程调用强制审批总开关（默认开）：开启时即使工具 AUTO_APPROVE 也弹审批。
 * @param autoStart App 启动时自动拉起服务。
 */
@Serializable
data class McpServerSettings(
    val enabled: Boolean = false,
    val port: Int = 3000,
    val token: String = "",
    val requireApproval: Boolean = true,
    val autoStart: Boolean = false
)
