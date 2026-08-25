package com.R.codecore.feature.agent.domain.rule

/**
 * 规则层级（D3-1，对齐 norm-chain-design.md §3.9）：四级全量分层。
 */
enum class RuleLayer(val defaultPriority: Int) {
    /** 全局（用户设备级，`~/.rcodecore/global-rules.md`）。 */
    GLOBAL(10),
    /** 项目（`AGENTS.md`，已有，权威源）。 */
    PROJECT(20),
    /** 工作区（工作区根 `workspace-AGENTS.md`，对特定项目/工作区注入差异化规则）。 */
    WORKSPACE(30),
    /** 模块（`feature/<module>/AGENTS.md`，子目录级规则，仅对该模块相关任务生效）。 */
    MODULE(40)
}
