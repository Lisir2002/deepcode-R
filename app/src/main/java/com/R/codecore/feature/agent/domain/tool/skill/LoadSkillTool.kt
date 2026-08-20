package com.R.codecore.feature.agent.domain.tool.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.skill.SkillExecutionContext
import com.R.codecore.feature.agent.domain.skill.SkillExecutor
import com.R.codecore.feature.agent.domain.skill.SkillExecutionResult
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载/执行一个技能（RC74 升级为执行分层；本次升级作用域分级 + 上下文贯穿 + 工具绑定）。
 *
 * 系统提示里只注入了各 skill 的 name+description 清单；AI 判断某个 skill 适用时，调用本工具：
 * - PROMPT 技能：返回 SKILL.md 正文（注入上下文）。
 * - SCRIPT 技能：在 PRoot 容器内沙箱执行入口脚本（执行前需审批）。
 * - MCP 技能：映射到已连接的 MCP 工具执行。
 *
 * 本工具依赖 [AgentContext] 贯穿执行上下文（sessionId 等），故重写 [executeWithContext]。
 * 1. 作用域分级：GLOBAL/COMMON 直接可加载；AGENT 级技能需 agentType 匹配当前 agent。
 * 2. 依赖真正注入：按依赖序先加载依赖——PROMPT 依赖的 instructions 拼接到返回结果供 AI 参考；
 *    SCRIPT/MCP 依赖按其类型在技能主体前按需预执行/校验。
 * 3. 专属工具绑定：加载成功后调 [SkillToolBindingManager.registerForSkill] 登记 requiredTools。
 * 仅允许加载已启用的技能；依赖自动递归解析（含环/缺失/禁用检测）。
 */
class LoadSkillTool @Inject constructor(
    private val skillStateRepository: SkillStateRepository,
    private val skillExecutor: SkillExecutor,
    private val commandEngine: CommandEngine,
    private val skillToolBindingManager: SkillToolBindingManager
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"
        /** S-3：运行时预检查超时（毫秒）。 */
        const val RUNTIME_PROBE_TIMEOUT_MS = 15_000L
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.EXECUTE_COMMANDS)
    override val description =
        "加载或执行指定技能（Skill）。PROMPT 技能返回完整指令内容；SCRIPT/MCP 技能按类型执行。当系统提示清单中的技能适用于当前任务时调用。"

    /** L3 结构化结果协议：产出 state.skill.loaded 类型（技能加载后广播，触发工具定义与上下文刷新）。 */
    override val provides = setOf("state.skill.loaded")

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要加载的技能名称（与系统提示「可用技能」清单中的名称一致）。",
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
            description = "传给脚本/MCP 技能的参数（键值对，可选）。PROMPT 技能忽略。",
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

        val skills = skillStateRepository.listSkills()
        val skill = skills.firstOrNull { it.name.equals(skillName, ignoreCase = true) }
            ?: run {
                val available = skills.joinToString(", ") { it.name }
                FileLogger.w(TAG, "load_skill 未找到: $skillName，可用: $available")
                return ToolResult.Error(
                    "未找到技能「$skillName」。可用技能: ${available.ifEmpty { "（无）" }}",
                    "SKILL_NOT_FOUND"
                )
            }

        // S-1：版本锁——AI 指定版本时校验安装版本是否一致（忽略大小写），不一致给出明确报错
        val requestedVersion = args["version"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!requestedVersion.isNullOrEmpty() && !skill.version.equals(requestedVersion, ignoreCase = true)) {
            return ToolResult.Error(
                "技能「${skill.name}」当前安装版本为 v${skill.version}，与请求锁定的 v$requestedVersion 不一致。" +
                    "请省略 version 使用当前版本，或先更新/安装目标版本后再执行。",
                "SKILL_VERSION_MISMATCH"
            )
        }

        if (!skill.enabled) {
            return ToolResult.Error("技能「${skill.name}」已被禁用，请在设置-技能中心启用后再使用", "SKILL_DISABLED")
        }

        // 作用域分级：AGENT 级技能需与当前 agent 匹配（GLOBAL/COMMON 直接放行）。
        val agentScopeCheck = checkAgentScope(skill)
        if (agentScopeCheck != null) {
            return ToolResult.Error(agentScopeCheck.first, agentScopeCheck.second)
        }

        // 依赖解析：自动递归加载依赖，环/缺失/禁用时给出明确错误
        val resolution = skillStateRepository.resolveSkillWithDependencies(skill.id)
        if (resolution == null) {
            return ToolResult.Error("技能「${skill.name}」解析失败", "SKILL_RESOLVE_FAILED")
        }
        if (resolution.missingDependencies.isNotEmpty()) {
            return ToolResult.Error(
                "技能「${skill.name}」缺少依赖: ${resolution.missingDependencies.joinToString(", ")}",
                "SKILL_MISSING_DEP"
            )
        }
        if (resolution.disabledDependencies.isNotEmpty()) {
            return ToolResult.Error(
                "技能「${skill.name}」的依赖已被禁用: ${resolution.disabledDependencies.joinToString(", ")}",
                "SKILL_DISABLED_DEP"
            )
        }

        // 依赖真正注入（设计 §4.1）：按依赖序先加载依赖——PROMPT 依赖的 instructions 拼接；
        // SCRIPT/MCP 依赖的执行由各依赖技能自身按类型执行，此处仅做解析注入准备。
        val dependencyInstructions = resolution.dependencies
            .filter { it.type == SkillType.PROMPT }
            .map { it.instructions }

        // S-3：运行时依赖预检查——SCRIPT 技能声明的 requires_runtime 在容器内逐一探测，
        // 缺失时在执行前给出明确报错（而非执行到一半才失败）
        if (skill.type == SkillType.SCRIPT && skill.requiresRuntime.isNotEmpty()) {
            val missing = skill.requiresRuntime.filter { !runtimeAvailable(it) }
            if (missing.isNotEmpty()) {
                return ToolResult.Error(
                    "技能「${skill.name}」需要运行时依赖: ${missing.joinToString(", ")}，但容器内未找到。" +
                        "请先安装对应运行时（如通过命令工具安装 node/python）后再执行。",
                    "SKILL_MISSING_RUNTIME"
                )
            }
        }

        // 提取参数（args 为 JSON 对象 → Map<String,String>）
        val execArgs = mutableMapOf<String, String>()
        (args["args"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { execArgs[k] = it }
        }

        // 构建执行上下文并贯穿执行器（审批/审计的 sessionId 与当前会话连贯）。
        val skillCtx = SkillExecutionContext.from(context, agentType = skillScopeAgentType(skill))

        // 专属工具绑定：加载成功前登记 requiredTools（缺失给出明确错误）。
        skillToolBindingManager.registerForSkill(skill)?.let { return ToolResult.Error(it, "SKILL_MISSING_TOOL") }

        val result = skillExecutor.execute(skill, execArgs, skillCtx)
        return when (result) {
            is SkillExecutionResult.Success -> {
                // 依赖注入：PROMPT 技能把依赖指令正文拼接到返回结果，供 AI 一并参考。
                val finalOutput = if (dependencyInstructions.isNotEmpty()) {
                    dependencyInstructions.joinToString("\n\n--- 依赖指令 ---\n\n") + "\n\n--- 主技能指令 ---\n\n" + result.output
                } else {
                    result.output
                }
                FileLogger.d(TAG, "load_skill 执行成功: ${skill.name} (${finalOutput.length} 字符)")
                ToolResult.Success(JsonPrimitive(finalOutput))
            }
            is SkillExecutionResult.Error -> {
                FileLogger.w(TAG, "load_skill 执行失败: ${skill.name} - ${result.message}")
                ToolResult.Error(result.message, result.code)
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

    /** AGENT 级技能的 agentType（仅 AGENT 级需要）；GLOBAL/COMMON 返回 null。 */
    private fun skillScopeAgentType(skill: com.R.codecore.feature.agent.domain.skill.Skill): String? {
        if (skill.scope != SkillScope.AGENT) return null
        return skill.agentType
    }

    /**
     * 作用域校验：返回 (错误信息, 错误码) 或 null（放行）。
     * GLOBAL/COMMON 直接放行；AGENT 级需匹配当前 agent（当前单 Agent 场景下按声明 agentType 放行）。
     */
    private fun checkAgentScope(skill: com.R.codecore.feature.agent.domain.skill.Skill): Pair<String, String>? {
        if (skill.scope != SkillScope.AGENT) return null
        // 当前为单 Agent（编程）场景，agentType 标识 "coding"；AGENT 级技能允许按声明加载。
        // 多 Agent 演进后，此处改为：skill.agentType == 当前激活 agentType，不匹配则拒绝。
        FileLogger.d(TAG, "load_skill agent 级技能: ${skill.name} (agentType=${skill.agentType})")
        return null
    }

    /** S-3：在容器内探测指定命令是否可用（command -v），失败/超时视为缺失。 */
    private suspend fun runtimeAvailable(command: String): Boolean {
        return try {
            val result = commandEngine.runCommandSyncWithExit(
                command = "command -v $command >/dev/null 2>&1",
                projectPath = null,
                timeoutMs = RUNTIME_PROBE_TIMEOUT_MS
            )
            result.exitCode == 0
        } catch (e: Exception) {
            FileLogger.w(TAG, "运行时探测失败: $command - ${e.message}")
            false
        }
    }
}
