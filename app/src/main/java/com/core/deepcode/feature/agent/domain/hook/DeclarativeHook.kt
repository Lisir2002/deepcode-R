package com.core.deepcode.feature.agent.domain.hook

import com.core.deepcode.core.util.FileLogger
import kotlinx.serialization.Serializable

/**
 * 声明式 hooks.json 配置模型（对齐 Claude Code hooks 形态 + 本项目 [HookHandler] 5 类事件）。
 *
 * hooks.json 结构：
 * ```
 * {
 *   "hooks": {
 *     "PreToolUse":       [{ "id": "...", "enabled": true, "action": "log", "message": "..." }],
 *     "PostToolUse":      [...],
 *     "UserPromptSubmit": [...],
 *     "Stop":             [...],
 *     "SessionStart":     [...]
 *   }
 * }
 * ```
 *
 * [DeclarativeHookEntry.action] 当前支持 `log`（事件触发时按 [message] 记日志），
 * 这是声明式 hook 的通用最小能力：用户/插件无需写代码即可给指定事件挂审计日志。
 * 代码级 hook（[PreToolUseHook] 等）保留 multibinding 注册，两者并存。
 */
@Serializable
data class DeclarativeHookConfig(
    val hooks: Map<String, List<DeclarativeHookEntry>> = emptyMap()
)

@Serializable
data class DeclarativeHookEntry(
    val id: String,
    val enabled: Boolean = true,
    /** 动作类型：当前支持 "log"（记日志）。未知动作忽略（防御未知配置）。 */
    val action: String = "log",
    /** 事件触发时记录的提示/审计文案。 */
    val message: String = ""
)

/** 事件名 → 对应 [HookHandler] 事件接口（hooks.json 的 key）。 */
internal object DeclarativeHookEvents {
    const val PRE_TOOL_USE = "PreToolUse"
    const val POST_TOOL_USE = "PostToolUse"
    const val USER_PROMPT_SUBMIT = "UserPromptSubmit"
    const val STOP = "Stop"
    const val SESSION_START = "SessionStart"

    val KNOWN = setOf(PRE_TOOL_USE, POST_TOOL_USE, USER_PROMPT_SUBMIT, STOP, SESSION_START)
}

/**
 * 把解析后的声明式配置转换为 [HookHandler] 实例列表。
 * 单实例实现 5 类事件接口，运行时按 [DeclarativeHookEntry] 归属事件过滤：
 * 只有与自身事件匹配的分发才记日志，其余静默（filterIsInstance 会命中所有分组，
 * 故必须在 handler 内做事件归属判断，避免无关事件也产生日志）。
 */
internal fun DeclarativeHookConfig.toHandlers(): List<HookHandler> =
    hooks.flatMap { (eventName, entries) ->
        if (eventName !in DeclarativeHookEvents.KNOWN) return@flatMap emptyList()
        entries.filter { it.enabled && it.action == "log" }.map { entry ->
            DeclarativeLogHook(eventName, entry)
        }
    }

/** 声明式日志 hook：事件触发时按配置的 message 记日志。 */
internal class DeclarativeLogHook(
    private val event: String,
    private val entry: DeclarativeHookEntry
) : PreToolUseHook, PostToolUseHook, UserPromptSubmitHook, StopHook, SessionStartHook {

    override val id: String = entry.id

    override fun onPreToolUse(context: PreToolUseContext) =
        logIfEvent(DeclarativeHookEvents.PRE_TOOL_USE, "PreToolUse", context.sessionId)

    override fun onPostToolUse(context: PostToolUseContext) =
        logIfEvent(DeclarativeHookEvents.POST_TOOL_USE, "PostToolUse", context.sessionId)

    override fun onUserPromptSubmit(context: UserPromptSubmitContext) =
        logIfEvent(DeclarativeHookEvents.USER_PROMPT_SUBMIT, "UserPromptSubmit", context.sessionId)

    override fun onStop(context: StopContext) =
        logIfEvent(DeclarativeHookEvents.STOP, "Stop", context.sessionId)

    override fun onSessionStart(context: SessionStartContext) =
        logIfEvent(DeclarativeHookEvents.SESSION_START, "SessionStart", context.sessionId)

    private fun logIfEvent(mine: String, label: String, sessionId: String?) {
        if (event != mine) return
        val detail = if (entry.message.isBlank()) "声明式 hook 触发（${label}）" else entry.message
        FileLogger.d("Hook[$label]", "[${entry.id}] session=${sessionId ?: "-"} $detail")
    }
}
