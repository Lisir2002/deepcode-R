package com.R.codecore.feature.agent.domain.tool.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.skill.SkillToolBindingManager
import com.R.codecore.feature.agent.domain.skill.SkillType
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCall
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolEvent
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载一个 PROMPT 技能（重写：职责拆分后仅处理 PROMPT 类型，不再执行任何脚本/工具）。
 *
 * 系统提示里只注入了各 skill 的 name+description 清单；AI 判断某个 PROMPT 技能适用时，
 * 调用本工具把 SKILL.md 正文（含依赖指令）注入上下文，供 AI 严格按正文行事。
 *
 * **职责边界（适配当前 agent 与 skill 行为逻辑的关键拆分）**：
 * - 仅加载 PROMPT 技能：返回指令正文，无执行、无安全风险。
 * - SCRIPT 脚本技能：指引改用 `runSkillScript` 工具执行（如需阅读其 SKILL.md 可自行 readFile）。
 * - MCP 包装技能：已降级为别名——直接调用其绑定的 MCP 工具，不再经技能系统包装执行。
 *
 * 共用 [SkillInvocationResolver] 完成定位/版本锁/作用域/依赖校验；加载成功后登记专属工具
 * （[SkillToolBindingManager]，技能即工具组）并广播 [ToolEvent.StateSkillLoaded]。
 */
class LoadSkillTool @Inject constructor(
    private val skillInvocationResolver: SkillInvocationResolver,
    private val skillToolBindingManager: SkillToolBindingManager
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定 PROMPT 技能：返回该技能的完整指令正文（含依赖指令）供 AI 按步骤行事，不执行任何脚本。当系统提示「可用技能」清单中的技能为指令类（PROMPT）且适用于当前任务时调用；脚本类（SCRIPT）技能请用 runSkillScript 执行。"

    /** L3 结构化结果协议：产出 state.skill.loaded 类型（技能加载后广播，触发工具定义与上下文刷新）。 */
    override val provides = setOf("state.skill.loaded")

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要加载的 PROMPT 技能名称（与系统提示「可用技能」清单中的名称一致）。",
            required = true
        ),
        "version" to ToolParameter(
            name = "version",
            type = ParameterType.STRING,
            description = "S-1：技能版本锁定（semver，如 \"1.2.0\"）。省略时使用当前安装的最新版本；指定后若安装版本不一致会返回明确错误，避免在不同版本上执行同一技能。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        // 缺省路径：无 AgentContext 时以「无会话上下文」执行（兼容无上下文调用场景）。
        return executeWithContext(
            args,
            AgentContext(currentFile = null, selectedCode = null, projectRoot = "", language = null)
        )
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (skillName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: skill_name", "MISSING_SKILL_NAME")
        }

        val version = args["version"]?.jsonPrimitive?.contentOrNull?.trim()
        return when (val r = skillInvocationResolver.resolve(skillName, version, null, context)) {
            is SkillInvokeResult.Failed -> ToolResult.Error(r.message, r.code)
            is SkillInvokeResult.Ready -> {
                val skill = r.skill

                // 类型分流：仅加载 PROMPT；SCRIPT/MCP 给出明确指引（职责拆分后的唯一入口约定）。
                if (skill.type != SkillType.PROMPT) {
                    return when (skill.type) {
                        SkillType.SCRIPT -> {
                            FileLogger.d(TAG, "load_skill 命中 SCRIPT 技能，指引改用 runSkillScript: ${skill.name}")
                            ToolResult.Error(
                                "技能「${skill.name}」为脚本类（SCRIPT）技能，loadSkill 仅加载指令正文。" +
                                    "请改用 runSkillScript 执行其入口脚本（执行前需用户确认）。",
                                "SKILL_NOT_PROMPT"
                            )
                        }
                        SkillType.MCP -> {
                            // MCP 包装技能降级为别名：返回绑定工具指引，不执行。
                            FileLogger.d(TAG, "load_skill MCP 别名解析: ${skill.name} -> ${skill.mcpTool}")
                            ToolResult.Success(
                                JsonPrimitive(
                                    "技能「${skill.name}」为 MCP 包装技能（已降级为别名，不再经技能系统执行）。" +
                                        "请直接调用 MCP 工具「${skill.mcpTool ?: "（未绑定）"}」，该工具的说明即本技能的用途描述。" +
                                        "若工具未连接，请先用 manageMcp 连接对应服务。"
                                )
                            )
                        }
                        SkillType.PROMPT -> throw IllegalStateException("unreachable")
                    }
                }

                // 专属工具绑定：加载成功前登记 requiredTools（缺失给出明确错误）。
                skillToolBindingManager.registerForSkill(skill)?.let { return ToolResult.Error(it, "SKILL_MISSING_TOOL") }

                // 依赖注入：把 PROMPT 依赖的指令正文拼接到返回结果，供 AI 一并参考。
                val finalOutput = if (r.dependencyInstructions.isNotEmpty()) {
                    r.dependencyInstructions.joinToString("\n\n--- 依赖指令 ---\n\n") + "\n\n--- 主技能指令 ---\n\n" + skill.instructions
                } else {
                    skill.instructions
                }
                FileLogger.d(TAG, "load_skill 加载成功: ${skill.name} (${finalOutput.length} 字符)")
                ToolResult.Success(JsonPrimitive(finalOutput))
            }
        }
    }

    /** L7 事件自声明：技能加载成功后广播 state.skill.loaded，触发工具定义与上下文刷新。 */
    override fun buildPostExecutionEvent(
        toolCall: ToolCall,
        result: ToolResult,
        context: AgentContext
    ): ToolEvent? {
        val skillName = (toolCall.arguments["skill_name"] as? JsonPrimitive)?.contentOrNull ?: ""
        return ToolEvent.StateSkillLoaded(skillName = skillName, toolCount = 0, sessionId = context.sessionId)
    }
}
