package com.R.codecore.feature.agent.domain.tool.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillExecutionContext
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.agent.domain.skill.SkillType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能调用前置解析结果。
 */
sealed class SkillInvokeResult {
    /** 解析通过：技能可直接加载/执行。 */
    data class Ready(
        val skill: Skill,
        val execArgs: Map<String, String>,
        /** PROMPT 依赖的指令正文（依赖序），供调用方拼接注入。 */
        val dependencyInstructions: List<String>,
        val ctx: SkillExecutionContext
    ) : SkillInvokeResult()

    /** 解析失败：返回 (错误信息, 错误码)。 */
    data class Failed(val message: String, val code: String) : SkillInvokeResult()
}

/**
 * 技能调用前置解析器（重写「技能调用工具」时的公共抽离）。
 *
 * 把 [LoadSkillTool] / [RunSkillScriptTool] 共用的「定位技能 + 校验」逻辑收敛到这里，避免
 * 两个工具各自维护一份重复的版本锁 / 作用域 / 依赖校验。**只做解析、不做执行**：
 * 1. 按名称定位技能（忽略大小写）+ S-1 版本锁校验；
 * 2. 启用校验；
 * 3. 作用域严格隐藏 + 对话级双向控制（D7/D8）；
 * 4. 依赖递归解析（环/缺失/禁用检测）；
 * 5. 提取 exec args（JSON 对象 → Map<String,String>）并构建 [SkillExecutionContext]（贯穿执行链路）；
 * 6. 收集 PROMPT 依赖的指令正文。
 */
@Singleton
class SkillInvocationResolver @Inject constructor(
    private val skillStateRepository: SkillStateRepository
) {
    private companion object {
        const val TAG = "SkillInvocationResolver"
    }

    suspend fun resolve(
        skillName: String,
        version: String?,
        rawArgs: JsonElement?,
        context: AgentContext
    ): SkillInvokeResult {
        val skills = skillStateRepository.listSkills()
        val skill = skills.firstOrNull { it.name.equals(skillName, ignoreCase = true) }
            ?: run {
                val available = skills.joinToString(", ") { it.name }
                FileLogger.w(TAG, "技能未找到: $skillName，可用: $available")
                return SkillInvokeResult.Failed(
                    "未找到技能「$skillName」。可用技能: ${available.ifEmpty { "（无）" }}",
                    "SKILL_NOT_FOUND"
                )
            }

        // S-1：版本锁——AI 指定版本时校验安装版本是否一致（忽略大小写）。
        if (!version.isNullOrEmpty() && !skill.version.equals(version, ignoreCase = true)) {
            return SkillInvokeResult.Failed(
                "技能「${skill.name}」当前安装版本为 v${skill.version}，与请求锁定的 v$version 不一致。" +
                    "请省略 version 使用当前版本，或先更新/安装目标版本后再执行。",
                "SKILL_VERSION_MISMATCH"
            )
        }

        if (!skill.enabled) {
            return SkillInvokeResult.Failed(
                "技能「${skill.name}」已被禁用，请在设置-技能中心启用后再使用",
                "SKILL_DISABLED"
            )
        }

        // 作用域严格隐藏 + 对话级控制（D7/D8）：仅「当前 agent + 当前会话」可见的技能才可调用。
        val scopeCheck = checkScopeAndConversation(skill, context.sessionId)
        if (scopeCheck != null) {
            return SkillInvokeResult.Failed(scopeCheck.first, scopeCheck.second)
        }

        // 依赖解析：自动递归加载依赖，环/缺失/禁用时给出明确错误。
        val resolution = skillStateRepository.resolveSkillWithDependencies(skill.id)
            ?: return SkillInvokeResult.Failed("技能「${skill.name}」解析失败", "SKILL_RESOLVE_FAILED")
        if (resolution.missingDependencies.isNotEmpty()) {
            return SkillInvokeResult.Failed(
                "技能「${skill.name}」缺少依赖: ${resolution.missingDependencies.joinToString(", ")}",
                "SKILL_MISSING_DEP"
            )
        }
        if (resolution.disabledDependencies.isNotEmpty()) {
            return SkillInvokeResult.Failed(
                "技能「${skill.name}」的依赖已被禁用: ${resolution.disabledDependencies.joinToString(", ")}",
                "SKILL_DISABLED_DEP"
            )
        }

        // 依赖注入（设计 §4.1）：PROMPT 依赖的 instructions 拼接供 AI 一并参考。
        val dependencyInstructions = resolution.dependencies
            .filter { it.type == SkillType.PROMPT }
            .map { it.instructions }

        // 提取参数（args 为 JSON 对象 → Map<String,String>）。
        val execArgs = mutableMapOf<String, String>()
        (rawArgs as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { execArgs[k] = it }
        }

        // 构建执行上下文并贯穿执行器（审批/审计的 sessionId 与当前会话连贯）。
        val ctx = SkillExecutionContext.from(context, agentType = scopeAgentType(skill))

        return SkillInvokeResult.Ready(skill, execArgs, dependencyInstructions, ctx)
    }

    /** AGENT 级技能的 agentType（仅 AGENT 级需要）；GLOBAL/CONVERSATION 返回 null。 */
    private fun scopeAgentType(skill: Skill): String? {
        if (skill.scope != SkillScope.AGENT) return null
        return skill.agentType
    }

    /**
     * 作用域严格隐藏 + 对话级控制校验：返回 (错误信息, 错误码) 或 null（放行）。
     *
     * 复用 [SkillStateRepository.filterVisibleSkills] 的可见性判定：技能在「当前 agent + 当前会话」
     * 下不可见时，给出明确的原因（AGENT 不匹配 / CONVERSATION 未添加 / 对话内临时禁用），
     * 而不是笼统地报「不可用」，便于用户快速定位。
     */
    private suspend fun checkScopeAndConversation(
        skill: Skill,
        sessionId: String?
    ): Pair<String, String>? {
        val visible = skillStateRepository.filterVisibleSkills(listOf(skill), sessionId)
        if (visible.isNotEmpty()) return null

        // 对话级禁用优先给出明确提示（用户可见、可即时恢复）。
        if (sessionId != null) {
            val conv = runCatching { skillStateRepository.getConversationState(skill.id, sessionId) }.getOrNull()
            if (conv?.enabled == false) {
                return "技能「${skill.name}」在当前对话中已被禁用，请到对话技能面板重新启用后再使用" to "SKILL_CONVERSATION_DISABLED"
            }
        }
        return when (skill.scope) {
            SkillScope.AGENT -> {
                FileLogger.d(TAG, "agent 级技能作用域不匹配: ${skill.name} (agentType=${skill.agentType})")
                "技能「${skill.name}」仅适用于 ${skill.agentType ?: "指定"} Agent，当前 Agent 不可用" to "SKILL_AGENT_SCOPE_MISMATCH"
            }
            SkillScope.CONVERSATION -> "技能「${skill.name}」为对话级技能，需先在对话技能面板添加启用后才能使用" to "SKILL_CONVERSATION_INACTIVE"
            SkillScope.GLOBAL -> "技能「${skill.name}」当前不可用" to "SKILL_NOT_VISIBLE"
        }
    }
}
