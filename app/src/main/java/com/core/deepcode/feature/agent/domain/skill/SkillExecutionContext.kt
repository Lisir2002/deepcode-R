package com.core.deepcode.feature.agent.domain.skill

import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.model.AgentMode

/**
 * 技能执行上下文（设计文档 skill-collaboration-design §4.2）。
 *
 * 由 [AgentContext] 派生，贯穿技能执行的全链路，解决此前技能审批/审计与当前会话脱钩
 * （[SkillExecutor] 审批时 sessionId 传 null）的问题：
 * - [sessionId]：当前会话 id，用于审批归属与审计关联；
 * - [mode]：当前 Agent 模式（BUILD/PLAN/AUTO）；
 * - [projectPath]：当前项目根目录，供容器目录绑定等使用；
 * - [executionMode]：执行模式（本地 PRoot / 远程 SSH），由调用方显式传入（不在本领域层解析，
 *   避免依赖 settings 层 ExecutionModeHolder 造成环依赖）；缺省为 null。
 * - [agentType]：当前 Agent 类型标识（如 "coding"），用于作用域过滤：仅 [SkillScope.AGENT] 级
 *   技能需与之匹配。多 Agent 演进时为必填；当前单 Agent 场景可由调用方传入或置 null。
 * - [autoTrigger]：是否由「技能自动触发」机制发起（区别于用户手动 loadSkill）。用于审批卡标题等
 *   提示用户「这是系统自动触发的技能执行」，避免用户不明就里、以为 App 卡住。
 */
data class SkillExecutionContext(
    val sessionId: String? = null,
    val mode: AgentMode = AgentMode.BUILD,
    val projectPath: String? = null,
    val executionMode: String? = null,
    val agentType: String? = null,
    val autoTrigger: Boolean = false
) {
    companion object {
        /** 从 [AgentContext] 派生（零改造取用工作流已传入的上下文）。 */
        fun from(context: AgentContext, agentType: String? = null, autoTrigger: Boolean = false): SkillExecutionContext {
            return SkillExecutionContext(
                sessionId = context.sessionId,
                mode = context.mode,
                projectPath = context.projectRoot,
                agentType = agentType,
                autoTrigger = autoTrigger
            )
        }
    }
}