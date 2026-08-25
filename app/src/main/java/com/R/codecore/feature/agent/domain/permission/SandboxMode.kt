package com.R.codecore.feature.agent.domain.permission

import com.R.codecore.feature.agent.domain.model.AgentMode

/**
 * 文件影响三档模式（对齐 DSH SandboxMode，方向 D2）。
 *
 * 在 [ToolPermissionPolicyEngine.evaluate] 的 [AgentMode] 之上叠加一层「文件影响面」约束：
 * - [READ_ONLY]：只读探索，禁止一切修改（等同 PLAN 约束的推广）；
 * - [WORKSPACE_WRITE]：允许工作区内读写，但禁止触碰容器环境/系统/网络/外部动态工具；
 * - [DANGER_FULL_ACCESS]：完全访问（仍保留现有 DangerousCommandGuard + 灾难性 rm 等静态护栏）。
 *
 * 解析优先级（per-call）：显式参数（如 Bash 的 `sandbox`） > 会话覆盖 > 全局默认。
 * [parse] 用于把模型显式传入的 `sandbox` 参数解析为枚举；null 表示「未显式声明，
 * 走会话/全局默认」（当前默认不额外收紧，即 [DANGER_FULL_ACCESS] 语义）。
 */
enum class SandboxMode {
    READ_ONLY,
    WORKSPACE_WRITE,
    DANGER_FULL_ACCESS;

    /** 是否只读：true 时禁止一切修改面工具。 */
    val isReadOnly: Boolean get() = this == READ_ONLY

    /** 是否限制「越出工作区」的能力（系统/容器环境/网络写/外部工具）。 */
    val isWorkspaceRestricted: Boolean get() = this == WORKSPACE_WRITE || this == READ_ONLY

    companion object {
        /** 未知/非法值一律回退 [DANGER_FULL_ACCESS]（宽松优先，防御模型乱传参）。 */
        fun parse(raw: String?): SandboxMode? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().lowercase().replace('-', '_')) {
                "read_only", "readonly", "ro" -> READ_ONLY
                "workspace_write", "workspacewrite", "workspace" -> WORKSPACE_WRITE
                "danger_full_access", "dangerfullaccess", "full", "danger" -> DANGER_FULL_ACCESS
                else -> null
            }
        }
    }
}
