package com.R.codecore.feature.agent.domain.guard

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull

/**
 * 工具执行护栏（D1-3，对齐 norm-chain §3.1.3 六段式工具流水线的 guard 段）：
 *
 * 六段契约：pre-execute（门）→ guard（护栏）→ execute（执行）→ post-execute（可改写结果）
 * → finalizeContent（结果定型）→ result（只读观测）。
 *
 * guard 段为统一护栏接口：链上护栏按注册顺序执行，首个 [ToolGuardResult.Block] 短路
 * （工具不执行、直接返回错误）；[ToolGuardResult.Advisory] 不阻断，进提醒（记录日志，
 * 供上层选择注入给模型）。新增护栏仅 `@IntoSet` 注册即可挂载，对齐 [com.R.codecore.feature.agent.domain.hook.HookDispatcher]
 * 的 multibinding 汇集模式。
 *
 * 链上护栏与执行顺序（设计定稿）：权限检查 → 文件观察 → 危险命令 → 超时钳制；
 * 现有护栏归属（权限在 workflow 层、危险命令/超时钳制在 [com.R.codecore.feature.agent.domain.tool.container.ExecuteCommandTool]
 * 工具层）保持原位，通过 guard 链的判定契约对齐，不迁移不重构。
 */
interface ToolGuard {
    /** 护栏唯一标识（用于日志/去重/单测断言）。 */
    val id: String

    /** 对一次工具调用执行护栏判定。 */
    suspend fun guard(ctx: ToolGuardContext): ToolGuardResult
}

/** 一次工具调用的护栏判定上下文（由 workflow 在 execute 前构造）。 */
data class ToolGuardContext(
    val toolName: String,
    val args: Map<String, JsonElement>,
    val sessionId: String? = null,
    val projectRoot: String = ""
) {
    /** 便捷读取 args 中的字符串参数（如 path）。 */
    fun argString(key: String): String? =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/** guard 判定三态结果：PASS 放行 / BLOCK 硬拦截（短路）/ ADVISORY 软提醒（不阻断）。 */
sealed class ToolGuardResult {
    /** 放行，继续执行后续护栏与工具。 */
    data object Pass : ToolGuardResult()

    /** 硬拦截：工具不执行，直接以该错误返回给模型。 */
    data class Block(val code: String, val message: String) : ToolGuardResult()

    /** 软提醒：不阻断执行，提醒信息进提醒队列（当前记录日志，供上层注入给模型）。 */
    data class Advisory(val code: String, val message: String) : ToolGuardResult()
}
