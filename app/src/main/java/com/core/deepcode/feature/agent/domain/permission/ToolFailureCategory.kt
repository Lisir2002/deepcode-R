package com.core.deepcode.feature.agent.domain.permission

/**
 * 统一工具失败分类（对齐 DSH denial/runner-failure 分类，方向 D2）。
 *
 * 把 `Bash` / `run_code` 等执行类工具的结构化失败码归一为三类，供模型精准应对：
 * - [DENIED]：沙箱/权限拒绝（危险命令静态拦截、PLAN/只读模式拦截、用户拒绝等）；
 * - [SANDBOX_UNAVAILABLE]：执行环境（容器/SSH）不可用或底层异常；
 * - [COMMAND_FAILED]：命令/脚本本身执行失败（非零退出码）。
 *
 * 工具在 [ToolResult.Error.code] 中直接产出对应错误码（如 `COMMAND_FAILED`、
 * `SANDBOX_UNAVAILABLE`、`DENIED`、`TOOL_TIMEOUT`），消费方可用 [fromErrorCode]
 * 一键归类；[TOOL_TIMEOUT] 属瞬时类，单独保留（对应结构化超时护栏）。
 */
enum class ToolFailureCategory {
    DENIED,
    SANDBOX_UNAVAILABLE,
    COMMAND_FAILED;

    companion object {
        /** 按错误码归类；未知码返回 null（由调用方决定兜底）。 */
        fun fromErrorCode(code: String?): ToolFailureCategory? {
            if (code.isNullOrBlank()) return null
            return when (code.uppercase()) {
                "DENIED", "SYSTEM_DENIED", "PLAN_MODE_REJECTED", "USER_REJECTED",
                "DANGEROUS_SCRIPT", "DANGEROUS_COMMAND", "FORK_BOMB", "READ_ONLY_DENIED",
                "WORKSPACE_RESTRICTED" -> DENIED
                "SANDBOX_UNAVAILABLE", "CONTAINER_UNAVAILABLE", "TOOL_EXECUTION_FAILED",
                "EXECUTION_FAILED" -> SANDBOX_UNAVAILABLE
                "COMMAND_FAILED", "SCRIPT_FAILED" -> COMMAND_FAILED
                else -> null
            }
        }
    }
}
