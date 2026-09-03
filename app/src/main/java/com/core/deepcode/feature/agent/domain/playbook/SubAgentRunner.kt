package com.core.deepcode.feature.agent.domain.playbook

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.data.remote.anthropic.AnthropicApi
import com.core.deepcode.feature.agent.data.remote.gemini.GeminiApi
import com.core.deepcode.feature.agent.data.remote.openai.OpenAIApi
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.model.AgentMessage
import com.core.deepcode.feature.agent.domain.model.AgentMode
import com.core.deepcode.feature.agent.domain.permission.SandboxMode
import com.core.deepcode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.core.deepcode.feature.agent.domain.prompt.AgentAssetRegistry
import com.core.deepcode.feature.agent.domain.provider.AIProvider
import com.core.deepcode.feature.agent.domain.provider.AnthropicAdapter
import com.core.deepcode.feature.agent.domain.provider.GeminiAdapter
import com.core.deepcode.feature.agent.domain.provider.OpenAIAdapter
import com.core.deepcode.feature.agent.domain.session.MessagePersistenceUseCase
import com.core.deepcode.feature.agent.domain.tool.ToolCall
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolRegistry
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import com.core.deepcode.feature.settings.domain.model.ProviderType
import com.core.deepcode.feature.settings.domain.repository.AIProviderRepository
import dagger.Lazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playbook 阶段子代理执行器（D5-6/7，对齐 norm-chain-design.md §3.6 多代理子代理机制）：
 *
 * 阶段激活时按 `stage.agents[]` 声明生成**真子代理隔离上下文**（非主模型"切角色"）：
 * - **独立子循环**：每子代理独立消息历史 + 系统提示（= 专项 agent body + 阶段目标）+ 工具集 +
 *   maxRounds 预算；复用 [AIProvider.complete] 非流式单轮补全循环（对齐 §3.6.2 buildSubAgentRequest）。
 * - **双 seed（§3.6.8）**：[PlaybookSeed.SPAWN] 全新上下文（缺省）；[PlaybookSeed.FORK] 继承主会话
 *   最近 N 条消息 + 阶段目标，再叠加 agent 角色指令。
 * - **三档权限降权（§3.6.4）**：复用 [ToolPermissionPolicyEngine.evaluate] 的既有 [SandboxMode] 过滤
 *   （READ_ONLY 拦修改类、WORKSPACE_WRITE 拦越出工作区能力），不继承主会话完整权限；白名单 = 注册工具
 *   按档位过滤。
 * - **并行聚合（§3.6.6/7）**：每子代理一个协程 [async] + [awaitAll] 聚合，结果按 agents 声明顺序稳定返回；
 *   结束回传**结构化 JSON**（结论/动作摘要/产出物/完成状态），不污染主上下文。
 * - **阶段内写串行化（§3.6.9）**：同一阶段所有子代理共享一个 [Mutex]，写档位（WRITE_WORKSPACE /
 *   EXECUTE_COMMANDS）工具调用串行执行，读操作保持并行。
 *
 * 本类不做后台投递（§3.6.3 async 可选）：阶段子代理默认同步执行（主代理等结果再继续），与
 * approval gate / 事件流兼容；async 阶段的子代理当前同样同步执行（降级，不做过度设计）。
 *
 * **Lazy 注入 [ToolRegistry]**：子代理只在执行时读取工具白名单/解析工具（运行时才 [Lazy.get]），
 * 而 ToolRegistry 又构造了依赖本类的 playbook 工具，构成 DI 构造环；用 [dagger.Lazy] 延迟取用打破环
 * （对齐 Dagger 对 Provider/Lazy 边界允许环的语义，运行时取用时注册表已就绪）。
 */
@Singleton
class SubAgentRunner @Inject constructor(
    private val toolRegistry: Lazy<ToolRegistry>,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val agentAssetRegistry: AgentAssetRegistry,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val messagePersistenceUseCase: MessagePersistenceUseCase
) {
    private companion object {
        const val TAG = "SubAgentRunner"

        /** 子代理最大 LLM 轮次预算（防失控，§3.6.2 maxRounds）。 */
        const val MAX_ROUNDS = 8

        /** fork 继承主会话最近消息条数（§3.6.8，避免带入全部历史/敏感内容）。 */
        const val FORK_INHERIT_MESSAGES = 20

        /** 写档位能力：命中即视为写操作，需串行执行（§3.6.9）。 */
        val WRITE_CAPABILITIES = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.MODIFY_SESSION_STATE,
            ToolCapability.MODIFY_TODO_STATE,
            ToolCapability.MODIFY_NETWORK
        )
    }

    /** 阶段子代理运行聚合结果（结构化 JSON 聚合，§3.6.7）。 */
    data class StageSubAgentResult(
        val agents: List<SubAgentResult>,
        /** 聚合摘要（人类可读，供工具/命令返回正文）。 */
        val summary: String
    )

    /** 单个子代理的结构化结果（§3.6.7 canonical output 字段）。 */
    data class SubAgentResult(
        val agentName: String,
        val seed: PlaybookSeed,
        val sandbox: SandboxMode,
        /** COMPLETED / FAILED。 */
        val status: String,
        /** 子代理最终结论（正文）。 */
        val conclusion: String,
        /** 动作摘要（工具名 + 简述）。 */
        val toolActions: List<String>,
        /** 产出物文件路径清单（D5-8 阶段产物清单来源之一）。 */
        val producedFiles: List<String>,
        val error: String? = null
    )

    /**
     * 运行阶段声明的全部子代理（并行 + 阶段内写串行化），结果按 [stage.agents] 声明顺序聚合。
     *
     * @param stage 当前阶段（agents / sandbox / seed / gates 决定子代理装配）。
     * @param stageGoal 阶段目标（frontmatter description，注入子代理系统提示，§3.6.2）。
     * @param mainContext 主会话上下文（fork 继承消息 / 工具执行工作目录）。
     * @return 聚合结果；无子代理时返回空列表结果。
     */
    suspend fun runStage(
        sessionId: String?,
        stage: PlaybookStage,
        stageGoal: String,
        mainContext: AgentContext? = null
    ): StageSubAgentResult {
        if (stage.agents.isEmpty()) {
            return StageSubAgentResult(agents = emptyList(), summary = "")
        }
        val provider = resolveStandaloneProvider(sessionId)
        val writeMutex = Mutex()
        val perAgent = coroutineScope {
            stage.agents.map { agentName ->
                async {
                    runSubAgent(agentName, stage, stageGoal, sessionId, mainContext, provider, writeMutex)
                }
            }.awaitAll()
        }
        val summary = buildSummary(stage, perAgent)
        return StageSubAgentResult(agents = perAgent, summary = summary)
    }

    // ── 单个子代理执行循环 ──

    private suspend fun runSubAgent(
        agentName: String,
        stage: PlaybookStage,
        stageGoal: String,
        sessionId: String?,
        mainContext: AgentContext?,
        provider: AIProvider?,
        writeMutex: Mutex
    ): SubAgentResult {
        val sandbox = stage.sandbox
        val seed = stage.seed
        val systemPrompt = buildSystemPrompt(agentName, stageGoal, stage, seed)
        val messages = buildInitialMessages(agentName, stageGoal, sessionId, seed).toMutableList()
        val whitelist = toolRegistry.get().getAvailableTools(AgentMode.AUTO)
        val context = buildAgentContext(mainContext, sessionId, agentName, messages)

        val toolActions = mutableListOf<String>()
        val producedFiles = mutableListOf<String>()

        if (provider == null) {
            return SubAgentResult(
                agentName = agentName, seed = seed, sandbox = sandbox,
                status = "FAILED",
                conclusion = "", toolActions = emptyList(), producedFiles = emptyList(),
                error = "未配置可用的 AI 提供商（无法启动子代理）"
            )
        }

        FileLogger.i(TAG, "runSubAgent: agent=$agentName seed=$seed sandbox=$sandbox rounds预算=$MAX_ROUNDS")
        var conclusion = ""
        for (round in 1..MAX_ROUNDS) {
            val response = try {
                provider.complete(systemPrompt, messages, whitelist)
            } catch (e: Exception) {
                return SubAgentResult(
                    agentName = agentName, seed = seed, sandbox = sandbox,
                    status = "FAILED", conclusion = "",
                    toolActions = toolActions, producedFiles = producedFiles.distinct(),
                    error = "子代理调用失败: ${e.message}"
                )
            }
            if (response.toolCalls.isEmpty()) {
                conclusion = response.content
                break
            }
            // 先入队本轮 assistant（含 toolCalls），再执行工具、结果紧随其后（对齐主 workflow 消息序）。
            messages += AgentMessage.AssistantMessage(
                content = response.content,
                toolCalls = response.toolCalls,
                reasoning = response.reasoning.orEmpty(),
                signature = response.signature.orEmpty()
            )
            // 执行本批工具调用（按阶段 sandbox 档位过滤；写操作串行化）。
            val isWriteBatch = response.toolCalls.any { isWriteTool(it) }
            if (isWriteBatch) {
                writeMutex.withLock { executeToolCalls(response.toolCalls, messages, context, sandbox, toolActions, producedFiles) }
            } else {
                executeToolCalls(response.toolCalls, messages, context, sandbox, toolActions, producedFiles)
            }
        }
        return SubAgentResult(
            agentName = agentName, seed = seed, sandbox = sandbox,
            status = "COMPLETED", conclusion = conclusion,
            toolActions = toolActions, producedFiles = producedFiles.distinct()
        )
    }

    /** 批量执行子代理工具调用：按档位过滤 + 结果写回子代理私有消息。 */
    private suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        messages: MutableList<AgentMessage>,
        context: AgentContext,
        sandbox: SandboxMode,
        toolActions: MutableList<String>,
        producedFiles: MutableList<String>
    ) {
        toolCalls.forEach { toolCall ->
            val tool = toolRegistry.get().getTool(toolCall.name)
            val verdict = if (tool != null) {
                runCatching {
                    policyEngine.evaluate(tool, toolCall.name, toolCall.arguments, AgentMode.AUTO, sandbox)
                }.getOrNull()?.verdict ?: ToolPermissionPolicyEngine.Verdict.DENY
            } else {
                ToolPermissionPolicyEngine.Verdict.DENY
            }
            if (tool == null || verdict != ToolPermissionPolicyEngine.Verdict.ALLOW) {
                val reason = if (tool == null) {
                    "工具 ${toolCall.name} 不存在"
                } else {
                    "工具 ${toolCall.name} 在当前沙箱模式（${sandbox.name.lowercase()}）下被拒绝"
                }
                messages += AgentMessage.ToolResultMessage(
                    toolName = toolCall.name,
                    result = "{\"status\":\"error\",\"code\":\"SANDBOX_DENIED\",\"message\":\"$reason\"}"
                )
                return@forEach
            }
            val result = try {
                tool.executeWithContext(toolCall.arguments, context)
            } catch (e: Exception) {
                ToolResult.Error("子代理工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED")
            }
            // 写工具记录产出物路径（D5-8 阶段产物清单）。
            if (isWriteTool(toolCall)) {
                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { producedFiles.add(it) }
            }
            toolActions.add(describeAction(toolCall, result))
            messages += AgentMessage.ToolResultMessage(
                toolName = toolCall.name,
                result = toolResultToText(result)
            )
        }
    }

    // ── 装配 ──

    private fun buildSystemPrompt(agentName: String, stageGoal: String, stage: PlaybookStage, seed: PlaybookSeed): String {
        val agentBody = runCatching {
            agentAssetRegistry.agents().firstOrNull { it.name == agentName }?.body
        }.getOrNull().orEmpty()
        return buildString {
            append("你是专项子代理「$agentName」。")
            if (seed == PlaybookSeed.FORK) append("你继承了主会话的上下文（最近对话 + 本阶段目标），请在此基础上继续推进。")
            appendLine()
            appendLine()
            appendLine("【本阶段目标】")
            appendLine(stageGoal.ifBlank { "（未提供阶段目标）" })
            if (stage.sop.isNotEmpty()) {
                appendLine()
                appendLine("【参考 SOP】")
                stage.sop.forEach { appendLine("- $it（用 loadSop 工具加载编号步骤执行）") }
            }
            if (stage.gates == PlaybookGate.APPROVAL) {
                appendLine()
                appendLine("【审批门】本阶段推进需用户批准，完成后请汇总结论与产出物清单，等待批准。")
            }
            appendLine()
            appendLine("【工作方式】")
            appendLine("- 用允许的工具完成阶段目标；只读探索优先，必要时才写文件/执行命令（受沙箱档位约束）。")
            appendLine("- 工作完成后，在最终回复中给出：结论 / 关键动作摘要 / 产出文件路径清单（每行一个）。")
            append("- 不要重复已完成的动作；产出物按内容写入天然幂等（同内容重写无害）。")
            if (agentBody.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("【专项 agent 指令】")
                append(agentBody)
            }
        }
    }

    private suspend fun buildInitialMessages(
        agentName: String,
        stageGoal: String,
        sessionId: String?,
        seed: PlaybookSeed
    ): List<AgentMessage> {
        val stagePrompt = "请完成本阶段目标（阶段：$stageGoal），使用可用工具开展工作，完成后给出结论、动作摘要与产出文件清单。"
        return if (seed == PlaybookSeed.SPAWN) {
            listOf(AgentMessage.UserMessage(content = stagePrompt))
        } else {
            // fork：继承主会话最近 N 条消息 + 阶段目标（§3.6.8）。
            val inherited = if (sessionId != null) {
                runCatching { messagePersistenceUseCase.buildHistory(sessionId, "__playbook_fork__") }
                    .getOrElse { emptyList() }
                    .takeLast(FORK_INHERIT_MESSAGES)
            } else {
                emptyList()
            }
            val inheritPrompt = "以下为主会话最近的对话上下文（继承），请在此基础上推进子代理任务「$agentName」。\n" +
                "【本阶段目标】$stageGoal"
            buildList {
                if (inherited.isNotEmpty()) {
                    add(AgentMessage.UserMessage(content = inheritPrompt))
                    addAll(inherited)
                    add(AgentMessage.UserMessage(content = stagePrompt))
                } else {
                    add(AgentMessage.UserMessage(content = "$inheritPrompt\n$stagePrompt"))
                }
            }
        }
    }

    private fun buildAgentContext(
        mainContext: AgentContext?,
        sessionId: String?,
        agentName: String,
        messages: List<AgentMessage>
    ): AgentContext = AgentContext(
        currentFile = mainContext?.currentFile,
        selectedCode = null,
        projectRoot = mainContext?.projectRoot.orEmpty(),
        language = mainContext?.language,
        history = messages,
        inputImages = emptyList(),
        sessionId = sessionId,
        mode = AgentMode.AUTO,
        currentAgentId = agentName,
        reasoningEffort = mainContext?.reasoningEffort,
        sessionState = mainContext?.sessionState
    )

    // ── 依赖解析 ──

    /** 创建独立 provider（对齐 workflow createStandaloneProvider 的适配器工厂逻辑）。 */
    private suspend fun resolveStandaloneProvider(sessionId: String?): AIProvider? {
        val config = aiProviderRepository.getActiveProviderSync() ?: return null
        if (config.apiKey.isBlank() || config.effectiveModel.isBlank()) return null
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi)
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi)
        }
        provider.apiKey = config.apiKey
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.logSessionId = sessionId
        return provider
    }

    // ── 辅助 ──

    private fun isWriteTool(toolCall: ToolCall): Boolean {
        val caps = toolRegistry.get().getTool(toolCall.name)?.effectiveCapabilities(toolCall.arguments).orEmpty()
        return caps.any { it in WRITE_CAPABILITIES }
    }

    private fun describeAction(toolCall: ToolCall, result: ToolResult): String {
        val ok = result is ToolResult.Success
        return if (ok) "${toolCall.name} ✓" else "${toolCall.name} ✗(${(result as? ToolResult.Error)?.code ?: "ERR"})"
    }

    private fun toolResultToText(result: ToolResult): String = when (result) {
        is ToolResult.Success -> result.data.toString()
        is ToolResult.Partial -> result.message
        is ToolResult.Error -> "错误(${result.code}): ${result.message}"
    }

    private fun buildSummary(stage: PlaybookStage, results: List<SubAgentResult>): String {
        if (results.isEmpty()) return ""
        val failed = results.filter { it.status == "FAILED" }
        return buildString {
            appendLine("【子代理执行结果】")
            results.forEach { r ->
                append("- ${r.agentName}（${r.seed.name.lowercase()} / ${r.sandbox.name.lowercase()}）: ")
                append(if (r.status == "COMPLETED") "完成" else "失败")
                appendLine()
                if (r.conclusion.isNotBlank()) {
                    r.conclusion.lineSequence().forEach { appendLine("    $it") }
                }
                if (r.producedFiles.isNotEmpty()) {
                    appendLine("    产出物:")
                    r.producedFiles.forEach { appendLine("      - $it") }
                }
                if (r.error != null) appendLine("    错误: ${r.error}")
            }
            if (failed.isNotEmpty()) {
                appendLine("注意：${failed.joinToString { it.agentName }} 执行失败，请检查后决定是否重试/推进。")
            }
        }.trimEnd()
    }
}
