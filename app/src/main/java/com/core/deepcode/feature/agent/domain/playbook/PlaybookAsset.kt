package com.core.deepcode.feature.agent.domain.playbook

import com.core.deepcode.feature.agent.domain.permission.SandboxMode

/**
 * Playbook 阶段审批门（D5-1，对齐 norm-chain-design.md §3.3.1 `gates`）：
 *
 * - [APPROVAL]：阶段推进前需用户批准（复用 `PlanApprovalManager.awaitApproval` 阻塞等用户决策；
 *   用户消息首 token `!` 标记可跳过此流程级确认，永不绕过权限系统）。
 * - [AUTO]：无需确认，模型声明完成即推进。
 */
enum class PlaybookGate {
    APPROVAL,
    AUTO;

    companion object {
        /** 解析 frontmatter 的 gates 值（如 `approval` / `auto`）；未知/缺省回退 [AUTO]。 */
        fun parse(raw: String?): PlaybookGate = when (raw?.trim()?.lowercase()) {
            "approval", "approve", "confirm" -> APPROVAL
            else -> AUTO
        }
    }
}

/**
 * Playbook 子代理 seed 模式（D5-6，对齐 norm-chain-design.md §3.6.8）：
 *
 * - [SPAWN]：全新上下文——独立消息历史 + 独立系统提示（= 专项 agent body），只回传结构化结果，不污染主上下文（缺省）。
 * - [FORK]：继承式子代理——继承主会话当前 goal/plan + 最近 N 条消息 + 阶段目标与产物引用（文件路径清单），
 *   再叠加 agent 角色指令；结束额外把关键结论/产物引用写回 `PlaybookRun.stageStatuses`。
 */
enum class PlaybookSeed {
    SPAWN,
    FORK;

    companion object {
        fun parse(raw: String?): PlaybookSeed = when (raw?.trim()?.lowercase()) {
            "fork", "inherit" -> FORK
            else -> SPAWN
        }
    }
}

/**
 * 一个 Playbook 阶段（D5-1，对齐 norm-chain-design.md §3.3.1 / §3.6）：
 *
 * - [name]：阶段名（推进日志/注入展示用）。
 * - [description]：阶段目标（注入模型用；子代理锚定在阶段目标，注入其系统提示）。
 * - [agents]：引用 `agent: true` 专项 agent 资产名列表；阶段激活时经 [com.core.deepcode.feature.agent.domain.playbook.SubAgentRunner]
 *   生成独立上下文的子代理执行（非主模型"切角色"）。
 * - [sop]：引用 `assets/sop/` 资产名列表；阶段激活时作为步骤规则注入。
 * - [gates]：审批门（[PlaybookGate.APPROVAL] 需用户批准，`!` 可跳过）。
 * - [async]：是否后台执行（阶段 `async: true` 子代理入后台 jobs 队列，结果就绪投递回会话）。
 * - [sandbox]：子代理三档权限降权（[SandboxMode.READ_ONLY] 默认，只读；不继承主会话完整权限）。
 * - [seed]：子代理 [PlaybookSeed.SPAWN] / [PlaybookSeed.FORK]（缺省 spawn）。
 * - [guardsTimeoutMs]：阶段超时钳制（毫秒，null 表示不强制超时）。
 */
data class PlaybookStage(
    val name: String,
    val description: String,
    val agents: List<String> = emptyList(),
    val sop: List<String> = emptyList(),
    val gates: PlaybookGate = PlaybookGate.AUTO,
    val async: Boolean = false,
    val sandbox: SandboxMode = SandboxMode.READ_ONLY,
    val seed: PlaybookSeed = PlaybookSeed.SPAWN,
    val guardsTimeoutMs: Long? = null
)

/**
 * 一份已解析的 Playbook 剧本资产（D5-1，对齐 norm-chain-design.md §3.3）。
 *
 * - [name]：剧本名（frontmatter `name` 优先，否则文件名去后缀，如 `feature-dev`）。
 * - [description]：剧本简介（清单注入 / `/playbook` 命令展示用；模型按名称精确匹配）。
 * - [stages]：多阶段列表（顺序编排推进）。
 * - [path]：资产源路径。
 *
 * 与 [com.core.deepcode.feature.agent.domain.sop.SopAsset] 同构（复用 frontmatter 解析 + mtime 懒刷新），
 * 但 stages 为 **YAML 列表**（复用 SkillParser SnakeYAML 嵌套解析），资产可读性好。
 */
data class PlaybookAsset(
    val name: String,
    val description: String,
    val stages: List<PlaybookStage>,
    val path: String
)
