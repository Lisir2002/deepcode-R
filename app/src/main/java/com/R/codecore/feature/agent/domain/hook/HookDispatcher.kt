package com.R.codecore.feature.agent.domain.hook

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hook 分发器（代码级骨架，模式对齐 SlashCommandRegistry：multibinding 汇集 + 按类型分发）。
 *
 * ### 设计原则
 * - **纯 Kotlin，零 Android 依赖**（可 JUnit）：不依赖 Compose / ViewModel / FileLogger。
 *   失败日志由调用方（StatefulAgentWorkflow）负责，本类只返回 [HookOutcome]。
 * - **异常隔离**：每个 handler 独立 try/catch——单个 hook 抛错不中断后续 hook、不破坏主流程；
 *   错误以 [HookOutcome.error] 上报，调用方决定如何记录。
 * - **协程卫生**：`CancellationException` 一律重抛（不吞取消信号，对齐 workflow 既有约定）。
 *
 * 设计依据：docs/plan-docs/claude-code-study-design.md 第 11 节（11.3）。
 */
@Singleton
class HookDispatcher @Inject constructor(
    handlers: Set<@JvmSuppressWildcards HookHandler>
) {
    private val preToolUseHooks: List<PreToolUseHook>
    private val postToolUseHooks: List<PostToolUseHook>
    private val userPromptSubmitHooks: List<UserPromptSubmitHook>
    private val stopHooks: List<StopHook>
    private val sessionStartHooks: List<SessionStartHook>

    init {
        // 按事件类型分组 + 按 id 去重，保证同一 hook 不重复注册。
        fun <T : HookHandler> collect(clazz: Class<T>): List<T> =
            handlers.filterIsInstance(clazz).distinctBy { it.id }
        preToolUseHooks = collect(PreToolUseHook::class.java)
        postToolUseHooks = collect(PostToolUseHook::class.java)
        userPromptSubmitHooks = collect(UserPromptSubmitHook::class.java)
        stopHooks = collect(StopHook::class.java)
        sessionStartHooks = collect(SessionStartHook::class.java)
    }

    fun dispatchPreToolUse(context: PreToolUseContext): List<HookOutcome> =
        dispatch(preToolUseHooks) { it.onPreToolUse(context) }

    fun dispatchPostToolUse(context: PostToolUseContext): List<HookOutcome> =
        dispatch(postToolUseHooks) { it.onPostToolUse(context) }

    fun dispatchUserPromptSubmit(context: UserPromptSubmitContext): List<HookOutcome> =
        dispatch(userPromptSubmitHooks) { it.onUserPromptSubmit(context) }

    fun dispatchStop(context: StopContext): List<HookOutcome> =
        dispatch(stopHooks) { it.onStop(context) }

    fun dispatchSessionStart(context: SessionStartContext): List<HookOutcome> =
        dispatch(sessionStartHooks) { it.onSessionStart(context) }

    /** 统一分发：跳过未启用处理器；异常隔离并上报为 HookOutcome.error。 */
    private fun <T : HookHandler> dispatch(
        hooks: List<T>,
        invoke: (T) -> Unit
    ): List<HookOutcome> = hooks.mapNotNull { handler ->
        if (!handler.enabled) return@mapNotNull null
        val error = try {
            invoke(handler)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }
        HookOutcome(handler.id, error)
    }
}

/** 单次 hook 分发结果：error 为 null 表示成功，非 null 表示该 hook 抛错（已隔离）。 */
data class HookOutcome(
    val handlerId: String,
    val error: Throwable? = null
)
