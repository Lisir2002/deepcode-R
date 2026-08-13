package com.deep.rcode.feature.agent.domain.skill

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.CommandEngine
import com.deep.rcode.feature.agent.domain.container.ContainerInstaller
import com.deep.rcode.feature.agent.domain.permission.PermissionChoice
import com.deep.rcode.feature.agent.domain.tool.PendingToolPermission
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionManager
import com.deep.rcode.feature.agent.domain.tool.ToolRegistry
import com.deep.rcode.feature.workspace.domain.RemoteAuditAction
import com.deep.rcode.feature.workspace.domain.RemoteAuditCategory
import com.deep.rcode.feature.workspace.domain.repository.RemoteAuditLogRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能执行结果（RC74 新增）。
 */
sealed class SkillExecutionResult {
    data class Success(val output: String) : SkillExecutionResult()
    data class Error(val message: String, val code: String = "SKILL_EXEC_ERROR") : SkillExecutionResult()
}

/**
 * 技能执行器（RC74 新增）：按 [Skill.type] 分派执行。
 *
 * - [SkillType.PROMPT]：返回指令正文（注入上下文），无执行、无安全风险。
 * - [SkillType.SCRIPT]：在 PRoot 容器内沙箱执行入口脚本，执行前经 [ToolPermissionManager] 审批，
 *   执行后记审计日志（复用 [RemoteAuditLogRepository]）。
 * - [SkillType.MCP]：把技能调用映射到 [ToolRegistry] 中已注册的 MCP 工具（命名空间化名）。
 *
 * 线程安全：所有方法为 suspend，调用方需保证在协程内调用。
 */
@Singleton
class SkillExecutor @Inject constructor(
    private val commandEngine: CommandEngine,
    private val toolPermissionManager: ToolPermissionManager,
    private val toolRegistry: ToolRegistry,
    private val containerInstaller: ContainerInstaller,
    private val auditLogRepo: RemoteAuditLogRepository
) {
    private companion object {
        const val TAG = "SkillExecutor"
        const val SCRIPT_TIMEOUT_MS = 120_000L
    }

    /** 执行一个技能。 */
    suspend fun execute(skill: Skill, args: Map<String, String> = emptyMap()): SkillExecutionResult {
        return when (skill.type) {
            SkillType.PROMPT -> executePrompt(skill)
            SkillType.SCRIPT -> executeScript(skill, args)
            SkillType.MCP -> executeMcp(skill, args)
        }
    }

    private fun executePrompt(skill: Skill): SkillExecutionResult {
        return SkillExecutionResult.Success(skill.instructions)
    }

    private suspend fun executeScript(skill: Skill, args: Map<String, String>): SkillExecutionResult {
        val entry = skill.entry?.takeIf { it.isNotBlank() }
            ?: return SkillExecutionResult.Error("SCRIPT 技能缺少 entry 入口脚本", "SKILL_MISSING_ENTRY")
        val skillDir = skill.dir
            ?: return SkillExecutionResult.Error("SCRIPT 技能缺少本地目录", "SKILL_MISSING_DIR")

        // 容器内技能目录映射：宿主 rdeepcodeDir 绑定到 /root/.rdeepcode
        val containerSkillDir = "/root/.rdeepcode/skills/${skill.id}"

        // 审批：所有 SCRIPT 技能执行前必须用户确认（决策点 6：全部审批）
        val approval = toolPermissionManager.awaitApproval(
            PendingToolPermission(
                id = "skill-${skill.id}-${System.currentTimeMillis()}",
                toolName = "loadSkill",
                title = "确认执行脚本技能「${skill.name}」",
                summary = "AI 请求执行脚本技能 ${skill.name}（v${skill.version}）",
                details = "入口脚本: $entry\n目录: $containerSkillDir\n参数: ${args.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "（无）" }}",
                argsPreview = "skill=${skill.id} entry=$entry"
            )
        )
        if (approval != PermissionChoice.ONCE && approval != PermissionChoice.ALWAYS) {
            FileLogger.w(TAG, "脚本技能被用户拒绝: ${skill.id}")
            return SkillExecutionResult.Error("用户拒绝了脚本技能「${skill.name}」的执行", "SKILL_REJECTED")
        }

        // 组装命令：进入技能目录，把参数作为环境变量 SKILL_ARG_* 传入，执行入口脚本
        val env = buildString {
            args.forEach { (k, v) ->
                append("SKILL_ARG_${k.uppercase()}=${shellQuote(v)} ")
            }
        }
        val shell = commandEngine.defaultShell()
        val command = "cd $containerSkillDir && $env$shell $entry"

        val result = try {
            commandEngine.runCommandSyncWithExit(command, projectPath = null, timeoutMs = SCRIPT_TIMEOUT_MS)
        } catch (e: Exception) {
            FileLogger.e(TAG, "脚本技能执行异常: ${skill.id}", e)
            auditLogRepo.append(
                category = RemoteAuditCategory.SECURITY,
                action = RemoteAuditAction.SKILL_EXEC_FAIL,
                success = false,
                message = "脚本技能执行异常 ${skill.id}: ${e.message}"
            )
            return SkillExecutionResult.Error("脚本技能执行异常: ${e.message}", "SKILL_EXEC_EXCEPTION")
        }

        val success = result.exitCode == 0
        auditLogRepo.append(
            category = RemoteAuditCategory.SECURITY,
            action = if (success) RemoteAuditAction.SKILL_EXEC_OK else RemoteAuditAction.SKILL_EXEC_FAIL,
            success = success,
            message = "脚本技能 ${skill.id} 执行完成，退出码=${result.exitCode}"
        )

        return if (success) {
            SkillExecutionResult.Success(result.output)
        } else {
            SkillExecutionResult.Error(
                "脚本技能「${skill.name}」执行失败（退出码=${result.exitCode}）:\n${result.output.take(2000)}",
                "SKILL_EXEC_NONZERO"
            )
        }
    }

    private suspend fun executeMcp(skill: Skill, args: Map<String, String>): SkillExecutionResult {
        val toolName = skill.mcpTool?.takeIf { it.isNotBlank() }
            ?: return SkillExecutionResult.Error("MCP 技能缺少 mcp_tool 绑定", "SKILL_MISSING_MCP_TOOL")

        val tool = toolRegistry.getTool(toolName)
            ?: return SkillExecutionResult.Error(
                "MCP 工具「$toolName」未连接或未注册，请先在 MCP 设置中连接对应服务",
                "SKILL_MCP_NOT_CONNECTED"
            )

        val jsonArgs: JsonObject = buildJsonObject {
            args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return try {
            val result = tool.execute(jsonArgs)
            when (result) {
                is com.deep.rcode.feature.agent.domain.tool.ToolResult.Success ->
                    SkillExecutionResult.Success(result.data.toString())
                is com.deep.rcode.feature.agent.domain.tool.ToolResult.Partial ->
                    SkillExecutionResult.Success(result.message)
                is com.deep.rcode.feature.agent.domain.tool.ToolResult.Error ->
                    SkillExecutionResult.Error(result.message, result.code)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "MCP 技能执行异常: ${skill.id}", e)
            SkillExecutionResult.Error("MCP 技能执行异常: ${e.message}", "SKILL_MCP_EXEC_EXCEPTION")
        }
    }

    /** shell 单引号转义，防止参数注入。 */
    private fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
