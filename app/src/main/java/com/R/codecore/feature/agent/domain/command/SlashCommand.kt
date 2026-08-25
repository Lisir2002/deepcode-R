package com.R.codecore.feature.agent.domain.command

/**
 * 斜杠命令处理器接口。每条命令（如 /status、/compress）实现一个此类，
 * 通过 Hilt `@Binds @IntoSet` 自动汇集到 [SlashCommandRegistry]。
 *
 * 新增命令只需：新建一个实现类 + 在 SlashCommandContext 上补对应方法 + ViewModel 实现。
 */
interface SlashCommandHandler {

    /** 触发文本，如 "/status"。必须以 '/' 开头。 */
    val trigger: String

    /** 菜单显示名，如 "会话状态"。 */
    val label: String

    /** 菜单描述。 */
    val description: String

    /**
     * 完全匹配判断：仅当用户发送的文本精确等于 [trigger] 时命中。
     * 子类通常无需重写。
     */
    fun matches(input: String): Boolean = input.trim() == trigger

    /** 命中后执行的操作。 */
    fun execute(context: SlashCommandContext)

    /**
     * 带原始输入的执行入口（供 `/agent <name>` 这类带参命令使用）。
     * 默认转发到无参 [execute]；需要解析参数的命令重写本方法。
     */
    fun executeWithInput(context: SlashCommandContext, input: String) {
        execute(context)
    }
}

/**
 * 命令执行上下文：只暴露命令需要的最小能力，避免命令直接持有 ViewModel。
 * 由 AIAgentViewModel 实现，新增命令时在此接口补方法并在 ViewModel 实现。
 */
interface SlashCommandContext {
    fun showSessionStatus()
    fun compactCurrentSession()

    /** 列出可切换的专项 Agent（结果以 AI 气泡输出）。 */
    fun listAgents()

    /** 切换到指定专项 Agent（`/agent <name>`）；空/非法名回退说明。 */
    fun switchAgent(name: String)

    /**
     * 切换/解除行为模式（`/mode <design|execute|research|chat|default>`，D0-6）。
     * `default` 解除会话级锁定，恢复按输入重判；结果以 AI 气泡输出。
     */
    fun switchBehaviorMode(mode: String)

    /**
     * 把一段文本作为用户消息送入 Agent workflow（声明式命令展开用，方向 B1）。
     * 由 [com.R.codecore.feature.agent.domain.ext.ExtensionCommand] 渲染正文后调用：
     * 落库为用户消息 → 驱动一轮 Agent 循环，等价于用户在输入框直接发送该文本。
     */
    fun sendAgentRequest(text: String)
}
