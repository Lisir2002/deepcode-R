package com.core.deepcode.feature.agent.domain.tool.skill

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.skill.SkillExecutionResult
import com.core.deepcode.feature.agent.domain.skill.SkillExecutor
import com.core.deepcode.feature.agent.domain.skill.SkillRuntimeProbe
import com.core.deepcode.feature.agent.domain.skill.SkillType
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 执行一个 SCRIPT 脚本技能（重写「技能调用工具」时新独立出的专用执行入口）。
 *
 * 与 [LoadSkillTool]（读技能正文，PROMPT/SCRIPT 通用）职责分离：本工具在 PRoot 容器内沙箱执行
 * 技能入口脚本，执行前经 [SkillExecutor] 内部的 [com.core.deepcode.feature.agent.domain.tool.ToolPermissionManager]
 * 审批（会话连贯的确认卡），执行后记审计日志。执行参数以 `SKILL_ARG_*` 环境变量注入，
 * 项目路径按 [com.core.deepcode.feature.agent.domain.skill.SkillExecutor] 的容器侧契约注入。
 *
 * **职责边界**：
 * - 仅执行 SCRIPT 技能；PROMPT 技能请用 `loadSkill` 获取指令正文；
 * - MCP 包装技能已降级为别名——直接调用其绑定的 MCP 工具。
 *
 * 共用 [SkillInvocationResolver] 完成定位/版本锁/作用域/依赖校验；S-3 运行时依赖（requires_runtime）
 * 由 [SkillRuntimeProbe] 在容器内受控探测（声明式探针，杜绝 shell 注入），缺失时在执行前明确报错。
 */
class RunSkillScriptTool @Inject constructor(
    private val skillInvocationResolver: SkillInvocationResolver,
    private val skillExecutor: SkillExecutor,
    private val skillRuntimeProbe: SkillRuntimeProbe
) : AgentTool() {
    private companion object {
        const val TAG = "RunSkillScriptTool"
    }

    override val name = "runSkillScript"
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)
    override val description =
        "执行指定脚本技能（SkillType=SCRIPT）：在容器沙箱内运行其入口脚本（执行前需用户确认）。当系统提示「可用技能」清单中的技能为脚本类（SCRIPT）且需要实际执行其入口脚本时调用；指令类（PROMPT）技能请用 loadSkill 获取正文。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要执行的脚本技能名称（与系统提示「可用技能」清单中的名称一致）。",
            required = true
        ),
        "version" to ToolParameter(
            name = "version",
            type = ParameterType.STRING,
            description = "S-1：技能版本锁定（semver，如 \"1.2.0\"）。省略时使用当前安装的最新版本；指定后若安装版本不一致会返回明确错误，避免在不同版本上执行同一技能。",
            required = false
        ),
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.OBJECT,
            description = "传给脚本技能入口脚本的参数（键值对，可选），会注入为 SKILL_ARG_<KEY> 环境变量。",
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
        when (val r = skillInvocationResolver.resolve(skillName, version, args["args"], context)) {
            is SkillInvokeResult.Failed -> return ToolResult.Error(r.message, r.code)
            is SkillInvokeResult.Ready -> {
                val skill = r.skill

                // 类型分流：仅执行 SCRIPT；其它类型给出明确指引（职责拆分后的唯一入口约定）。
                if (skill.type != SkillType.SCRIPT) {
                    return when (skill.type) {
                        SkillType.PROMPT -> {
                            FileLogger.d(TAG, "run_skill_script 命中 PROMPT 技能，指引改用 loadSkill: ${skill.name}")
                            ToolResult.Error(
                                "技能「${skill.name}」为指令类（PROMPT）技能，无入口脚本可执行。" +
                                    "请用 loadSkill 获取其指令正文并按步骤行事。",
                                "SKILL_NOT_SCRIPT"
                            )
                        }
                        SkillType.MCP -> {
                            FileLogger.d(TAG, "run_skill_script 命中 MCP 技能，指引直接调用 MCP 工具: ${skill.name}")
                            ToolResult.Error(
                                "技能「${skill.name}」为 MCP 包装技能（已降级为别名，不再经技能系统执行）。" +
                                    "请直接调用 MCP 工具「${skill.mcpTool ?: "（未绑定）"}」。",
                                "SKILL_NOT_SCRIPT"
                            )
                        }
                        SkillType.SCRIPT -> throw IllegalStateException("unreachable")
                    }
                }

                // S-3：运行时依赖预检查——SCRIPT 技能声明的 requires_runtime 求值树在容器内受控探测，
                // 任一条件不满足都在执行前明确报错（而非执行到一半才失败）。
                val runtimeFailures = skillRuntimeProbe.probe(skill.requiresRuntime)
                if (runtimeFailures.isNotEmpty()) {
                    val details = runtimeFailures.joinToString("；") { it.reason }
                    return ToolResult.Error(
                        "技能「${skill.name}」的运行时依赖未满足：$details。" +
                            "请按上述安装建议（或通过命令工具安装对应运行时）后再执行。",
                        "SKILL_MISSING_RUNTIME"
                    )
                }

                val result = skillExecutor.execute(skill, r.execArgs, r.ctx)
                return when (result) {
                    is SkillExecutionResult.Success -> {
                        // 依赖注入：把 PROMPT 依赖的指令正文拼接到返回结果，供 AI 一并参考。
                        val finalOutput = if (r.dependencyInstructions.isNotEmpty()) {
                            r.dependencyInstructions.joinToString("\n\n--- 依赖指令 ---\n\n") +
                                "\n\n--- 主技能执行输出 ---\n\n" + result.output
                        } else {
                            result.output
                        }
                        FileLogger.d(TAG, "run_skill_script 执行成功: ${skill.name} (${finalOutput.length} 字符)")
                        ToolResult.Success(JsonPrimitive(finalOutput))
                    }
                    is SkillExecutionResult.Error -> {
                        FileLogger.w(TAG, "run_skill_script 执行失败: ${skill.name} - ${result.message}")
                        ToolResult.Error(result.message, result.code)
                    }
                }
            }
        }
    }
}
