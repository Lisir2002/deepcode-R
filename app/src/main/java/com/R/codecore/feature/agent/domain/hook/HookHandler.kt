package com.R.codecore.feature.agent.domain.hook

import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.tool.ToolCall

/**
 * Hook 基接口：所有事件处理器的最少公共契约。
 *
 * 实现类通过 Hilt `@Binds @IntoSet` 注册到 [HookDispatcher]（模式对齐
 * [SlashCommandHandler]：接口 + multibinding + Registry 汇集）。
 *
 * 设计依据：docs/plan-docs/claude-code-study-design.md 第 11 节（11.3 完整事件集）。
 */
interface HookHandler {
    /** 唯一标识：日志与去重。 */
    val id: String

    /** 是否启用。默认启用；返回 false 时 [HookDispatcher] 跳过该处理器。 */
    val enabled: Boolean get() = true
}

/**
 * PreToolUse 事件：工具执行前（可做静态预校验 / 纪律检查）。
 *
 * 挂点 B：StatefulAgentWorkflow `ExecuteToolBatch` 起始处（复用 ZTH preTool 挂点）。
 */
interface PreToolUseHook : HookHandler {
    fun onPreToolUse(context: PreToolUseContext)
}

data class PreToolUseContext(
    val sessionId: String?,
    val toolCalls: List<ToolCall>,
    val mode: AgentMode
)

/**
 * PostToolUse 事件：工具执行后（可做后台安全审查 / 提交纪律检查）。
 *
 * 挂点 C：StatefulAgentWorkflow `batchResults` 组装后（复用 ZTH postTool 挂点）。
 */
interface PostToolUseHook : HookHandler {
    fun onPostToolUse(context: PostToolUseContext)
}

data class PostToolUseContext(
    val sessionId: String?,
    /** 原始工具调用（含参数，供按命令文本匹配）。 */
    val toolCall: ToolCall,
    /** 工具执行结果（transport 字符串）。 */
    val result: String,
    val isError: Boolean,
    val mode: AgentMode
)

/** UserPromptSubmit 事件：用户消息提交（StatefulAgentWorkflow.executeEvents 入口）。 */
interface UserPromptSubmitHook : HookHandler {
    fun onUserPromptSubmit(context: UserPromptSubmitContext)
}

data class UserPromptSubmitContext(
    val sessionId: String?,
    val userRequest: String,
    val mode: AgentMode
)

/** Stop 事件：工作流结束（正常 / 用户取消 / 异常，StatefulAgentWorkflow.executeEvents finally 块）。 */
interface StopHook : HookHandler {
    fun onStop(context: StopContext)
}

data class StopContext(
    val sessionId: String?,
    val isFinished: Boolean,
    val iterations: Int
)

/**
 * SessionStart 事件：会话创建。
 *
 * ⚠️ 真实挂点是「会话创建处」（AIAgentViewModel.newSession / SessionUseCase.newSessionEntity），
 * **不是** executeEvents 入口——executeEvents 每轮用户消息都会触发一次，在那里触发会冒名
 * SessionStart（产生假痕迹）。R01 仅定义接口与注册点，接线随会话生命周期阶段（R04）落地。
 */
interface SessionStartHook : HookHandler {
    fun onSessionStart(context: SessionStartContext)
}

data class SessionStartContext(
    val sessionId: String?,
    val mode: AgentMode
)
