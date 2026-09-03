package com.core.deepcode.feature.agent.domain.tool.skill

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.skill.SkillToolBindingManager
import com.core.deepcode.feature.agent.domain.skill.SkillType
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCall
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolEvent
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载一个技能的指令正文（重写：职责拆分后只负责「读正文」，不执行任何脚本/工具）。
 *
 * 系统提示里只注入了各 skill 的 name+description 清单；AI 判断某技能适用时，
 * 调用本工具把 SKILL.md 正文（含依赖指令）注入上下文，供 AI 严格按正文行事。
 *
 * **职责边界（按行为而非类型二分，与 runSkillScript 互补）**：
 * - 读正文（本工具）：PROMPT/SCRIPT 技能均可返回 SKILL.md 完整正文，**绝不执行**；
 *   SCRIPT 技能正文末尾附一行指引，提醒实际执行请用 `runSkillScript`。
 * - 执行（runSkillScript）：仅 SCRIPT 技能，容器沙箱执行入口脚本（审批 + 审计）。
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
        "加载指定技能的 SKILL.md 指令正文（PROMPT/SCRIPT 均可，仅返回正文供阅读参考、绝不执行任何脚本），含 PROMPT 依赖的指令拼接。当系统提示「可用技能」清单中的技能适用于当前任务、需要了解其完整指令或使用说明时调用；其中 SCRIPT 脚本技能如需实际执行，请改用 runSkillScript。"

    /** L3 结构化结果协议：产出 state.skill.loaded 类型（技能加载后广播，触发工具定义与上下文刷新）。 */
    override val provides = setOf("state.skill.loaded")

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要加载的技能名称（PROMPT/SCRIPT 均可，与系统提示「可用技能」清单中的名称一致）。",
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

                // MCP 包装技能降级为别名：无正文可读，返回绑定工具指引（不执行）。
                if (skill.type == SkillType.MCP) {
                    FileLogger.d(TAG, "load_skill MCP 别名解析: ${skill.name} -> ${skill.mcpTool}")
                    return ToolResult.Success(
                        JsonPrimitive(
                            "技能「${skill.name}」为 MCP 包装技能（已降级为别名，不再经技能系统执行）。" +
                                "请直接调用 MCP 工具「${skill.mcpTool ?: "（未绑定）"}」，该工具的说明即本技能的用途描述。" +
                                "若工具未连接，请先用 manageMcp 连接对应服务。"
                        )
                    )
                }

                // 专属工具绑定：加载成功前登记 requiredTools（缺失给出明确错误）。
                skillToolBindingManager.registerForSkill(skill)?.let { return ToolResult.Error(it, "SKILL_MISSING_TOOL") }

                // 依赖注入：把 PROMPT 依赖的指令正文拼接到返回结果（SCRIPT 依赖无正文，不拼），
                // 供 AI 一并参考；SCRIPT 技能正文末尾附执行指引（本工具只读不执行）。
                val baseOutput = if (r.dependencyInstructions.isNotEmpty()) {
                    r.dependencyInstructions.joinToString("\n\n--- 依赖指令 ---\n\n") + "\n\n--- 主技能指令 ---\n\n" + skill.instructions
                } else {
                    skill.instructions
                }
                val finalOutput = if (skill.type == SkillType.SCRIPT) {
                    baseOutput + "\n\n（本技能为 SCRIPT 脚本技能，以上为 SKILL.md 正文，仅作阅读参考；" +
                        "如需实际执行其入口脚本，请改用 runSkillScript，执行前会征求你的确认。）"
                } else {
                    baseOutput
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
