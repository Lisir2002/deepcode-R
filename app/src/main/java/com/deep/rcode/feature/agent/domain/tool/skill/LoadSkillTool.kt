package com.deep.rcode.feature.agent.domain.tool.skill

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.CommandEngine
import com.deep.rcode.feature.agent.domain.skill.SkillExecutor
import com.deep.rcode.feature.agent.domain.skill.SkillExecutionResult
import com.deep.rcode.feature.agent.domain.skill.SkillStateRepository
import com.deep.rcode.feature.agent.domain.skill.SkillType
import com.deep.rcode.feature.agent.domain.tool.AgentTool
import com.deep.rcode.feature.agent.domain.tool.ParameterType
import com.deep.rcode.feature.agent.domain.tool.ToolParameter
import com.deep.rcode.feature.agent.domain.tool.ToolCapability
import com.deep.rcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载/执行一个技能（RC74 升级为执行分层）。
 *
 * 系统提示里只注入了各 skill 的 name+description 清单；AI 判断某个 skill 适用时，调用本工具：
 * - PROMPT 技能：返回 SKILL.md 正文（注入上下文）。
 * - SCRIPT 技能：在 PRoot 容器内沙箱执行入口脚本（执行前需审批）。
 * - MCP 技能：映射到已连接的 MCP 工具执行。
 *
 * 仅允许加载已启用的技能；依赖自动递归解析（含环/缺失/禁用检测）。
 */
class LoadSkillTool @Inject constructor(
    private val skillStateRepository: SkillStateRepository,
    private val skillExecutor: SkillExecutor,
    private val commandEngine: CommandEngine
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
        (args["args"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { execArgs[k] = it }
        }

        val result = skillExecutor.execute(skill, execArgs)
        return when (result) {
            is SkillExecutionResult.Success -> {
                FileLogger.d(TAG, "load_skill 执行成功: ${skill.name} (${result.output.length} 字符)")
                ToolResult.Success(JsonPrimitive(result.output))
            }
            is SkillExecutionResult.Error -> {
                FileLogger.w(TAG, "load_skill 执行失败: ${skill.name} - ${result.message}")
                ToolResult.Error(result.message, result.code)
            }
        }
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
