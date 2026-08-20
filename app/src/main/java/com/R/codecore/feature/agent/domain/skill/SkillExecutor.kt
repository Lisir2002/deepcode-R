package com.R.codecore.feature.agent.domain.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.permission.PermissionChoice
import com.R.codecore.feature.agent.domain.tool.PendingToolPermission
import com.R.codecore.feature.agent.domain.tool.ToolPermissionManager
import com.R.codecore.feature.agent.domain.tool.ToolRegistry
import com.R.codecore.feature.workspace.domain.RemoteAuditAction
import com.R.codecore.feature.workspace.domain.RemoteAuditCategory
import com.R.codecore.feature.workspace.domain.repository.RemoteAuditLogRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
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
 * 执行需携带 [SkillExecutionContext]（由 LoadSkillTool 从 [AgentContext] 派生），使脚本技能
 * 审批与审计的 [sessionId] 与当前会话连贯（替代此前传 null 的脱钩问题）。
 *
 * 线程安全：所有方法为 suspend，调用方需保证在协程内调用。
 */
@Singleton
class SkillExecutor @Inject constructor(
    private val commandEngine: CommandEngine,
    private val toolPermissionManager: ToolPermissionManager,
    private val toolRegistryProvider: Provider<ToolRegistry>,
    private val containerInstaller: ContainerInstaller,
    private val auditLogRepo: RemoteAuditLogRepository
) {
    private companion object {
        const val TAG = "SkillExecutor"
        const val SCRIPT_TIMEOUT_MS = 120_000L
    }

    /** 执行一个技能。缺省上下文等价于「无会话上下文」的兼容路径。 */
    suspend fun execute(
        skill: Skill,
        args: Map<String, String> = emptyMap(),
        ctx: SkillExecutionContext = SkillExecutionContext()
    ): SkillExecutionResult {
        return when (skill.type) {
            SkillType.PROMPT -> executePrompt(skill, ctx)
            SkillType.SCRIPT -> executeScript(skill, args, ctx)
            SkillType.MCP -> executeMcp(skill, args)
        }
    }

    private fun executePrompt(skill: Skill, ctx: SkillExecutionContext): SkillExecutionResult {
        val output = if (ctx.sessionId != null) {
            // PROMPT 技能按依赖序注入依赖的指令正文（见 LoadSkillTool），此处单技能返回自身正文；
            // 组合技能的依赖拼接由 LoadSkillTool 负责。
            skill.instructions
        } else {
            skill.instructions
        }
        return SkillExecutionResult.Success(output)
    }

    private suspend fun executeScript(
        skill: Skill,
        args: Map<String, String>,
        ctx: SkillExecutionContext
    ): SkillExecutionResult {
        val entry = skill.entry?.takeIf { it.isNotBlank() }
            ?: return SkillExecutionResult.Error("SCRIPT 技能缺少 entry 入口脚本", "SKILL_MISSING_ENTRY")
        val skillDir = skill.dir
            ?: return SkillExecutionResult.Error("SCRIPT 技能缺少本地目录", "SKILL_MISSING_DIR")

        // 容器内技能目录映射：宿主 rcodecoreDir 绑定到 /root/.rcodecore
        val containerSkillDir = "/root/.rcodecore/skills/${skill.id}"

        // 审批：所有 SCRIPT 技能执行前必须用户确认（决策点 6：全部审批）。
        // 会话 id 来自执行上下文（ctx.sessionId），使确认卡的归属/取消与当前会话连贯，
        // 会话结束/停止时 cancelPending 能按会话精准清理（替代此前传 null 的兜底）。
        val approval = toolPermissionManager.awaitApproval(
            ctx.sessionId,
            PendingToolPermission(
                id = "skill-${skill.id}-${System.currentTimeMillis()}",
                toolName = "loadSkill",
                title = "确认执行脚本技能「${skill.name}」",
                summary = "AI 请求执行脚本技能 ${skill.name}（v${skill.version}）",
                details = "入口脚本: $entry\n目录: $containerSkillDir\n参数: ${args.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "（无）" }}",
                argsPreview = "skill=${skill.id} entry=$entry",
                sessionId = ctx.sessionId
            )
        )
        if (approval != PermissionChoice.ONCE && approval != PermissionChoice.ALWAYS) {
            FileLogger.w(TAG, "脚本技能被用户拒绝: ${skill.id}")
            return SkillExecutionResult.Error("用户拒绝了脚本技能「${skill.name}」的执行", "SKILL_REJECTED")
        }

        // 组装命令：进入技能目录，把参数作为环境变量 SKILL_ARG_* 传入，执行入口脚本。
        // 项目路径契约（SKILL_PROJECT_PATH）：宿主的 projectPath 由 LinuxContainerEngine 经 proot -b
        // 绑定到容器内固定点 /root/workspace，因此此处统一注入容器侧路径 /root/workspace，
        // 供脚本技能入口脚本定位真实项目并在其上执行 git / 文件检查；ctx.projectPath 为 null 时注入空（纯静态检查）。
        val env = buildString {
            val projContainerPath = if (ctx.projectPath != null) "/root/workspace" else ""
            append("SKILL_PROJECT_PATH=${shellQuote(projContainerPath)} ")
            args.forEach { (k, v) ->
                append("SKILL_ARG_${k.uppercase()}=${shellQuote(v)} ")
            }
        }
        val shell = commandEngine.defaultShell()
        val command = "cd $containerSkillDir && $env$shell $entry"

        val result = try {
            commandEngine.runCommandSyncWithExit(command, projectPath = ctx.projectPath, timeoutMs = SCRIPT_TIMEOUT_MS)
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

        val tool = toolRegistryProvider.get().getTool(toolName)
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
                is com.R.codecore.feature.agent.domain.tool.ToolResult.Success ->
                    SkillExecutionResult.Success(result.data.toString())
                is com.R.codecore.feature.agent.domain.tool.ToolResult.Partial ->
                    SkillExecutionResult.Success(result.message)
                is com.R.codecore.feature.agent.domain.tool.ToolResult.Error ->
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
