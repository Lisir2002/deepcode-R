package com.deep.rcode.feature.agent.domain.workflow

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.model.AgentContext
import com.deep.rcode.feature.agent.domain.model.AgentImage
import com.deep.rcode.feature.agent.domain.model.AgentMessage
import com.deep.rcode.feature.agent.domain.model.AgentMode
import com.deep.rcode.feature.agent.domain.session.SessionUseCase
import com.deep.rcode.feature.agent.domain.session.MessagePersistenceUseCase
import com.deep.rcode.feature.agent.domain.checkpoint.CheckpointManager
import com.deep.rcode.feature.agent.domain.permission.PermissionChoice
import com.deep.rcode.feature.agent.domain.permission.PermissionScope
import com.deep.rcode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.deep.rcode.feature.agent.domain.prompt.SystemPromptProvider
import com.deep.rcode.feature.agent.domain.provider.AIProvider
import com.deep.rcode.feature.agent.domain.provider.AIResponse
import com.deep.rcode.feature.agent.domain.provider.AIStreamChunk
import com.deep.rcode.feature.agent.domain.tool.AgentTool
import com.deep.rcode.feature.agent.domain.tool.StreamingAgentTool
import com.deep.rcode.feature.agent.domain.tool.ToolCall
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionManager
import com.deep.rcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.deep.rcode.feature.agent.domain.tool.ToolRegistry
import com.deep.rcode.feature.agent.domain.tool.ToolResult
import com.deep.rcode.feature.agent.domain.tool.ToolOutputStore
import com.deep.rcode.feature.agent.domain.tool.ToolStreamEvent
import com.deep.rcode.feature.agent.domain.tool.toTransportString
import com.deep.rcode.feature.agent.presentation.AgentAttachment
import com.deep.rcode.feature.settings.data.remote.ModelMetadataService
import com.deep.rcode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.deep.rcode.feature.settings.data.repository.VisionModelSettingsRepository
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.agent.data.remote.anthropic.AnthropicApi
import com.deep.rcode.feature.agent.data.remote.gemini.GeminiApi
import com.deep.rcode.feature.agent.data.remote.openai.OpenAIApi
import com.deep.rcode.feature.agent.domain.provider.AnthropicAdapter
import com.deep.rcode.feature.agent.domain.provider.GeminiAdapter
import com.deep.rcode.feature.agent.domain.provider.OpenAIAdapter
import com.deep.rcode.feature.settings.domain.model.ProviderType
import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
    }

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap(),
        /** 标记下一轮 CallLlm 用于识图——若当前聊天模型不支持 vision 则临时切到识图专用模型发送。 */
        val pendingVisionRound: Boolean = false
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse) : AgentAction
        data class LlmError(val error: String) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED"
    )

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        val images: List<AgentImage> = emptyList(),
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.deep.rcode.feature.agent.presentation.AgentAttachment> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        val images: List<AgentImage> = emptyList(),
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.deep.rcode.feature.agent.presentation.AgentAttachment> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getActiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 提供商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank()) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                    return if (!boundModel.isNullOrBlank()) config.copy(selectedModel = boundModel) else config
                }
            }
        }
        return aiProviderRepository.getActiveProviderSync()
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 提供商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank()) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider() ?: provider
        val compacted = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, onEvent = onEvent)
        return compacted.size != history.size
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于识图回退和上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     */
    private fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
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

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: ""
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isTruncated) {
                        newState = newState.copy(
                            messages = newState.messages + AgentMessage.UserMessage(content = "你的回复因长度限制被截断了，请从截断处继续。")
                        )
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                var hasImages = false
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images
                        )
                    )
                    if (batchResult.images.isNotEmpty()) {
                        hasImages = true
                        appendedMessages.add(
                            AgentMessage.UserMessage(
                                content = "已附加 ${batchResult.toolName} 读取的图片，供下一轮视觉分析使用。",
                                images = batchResult.images
                            )
                        )
                    }
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                // 本批 viewImage 产出了图片，标记下一轮为识图轮。
                if (hasImages) {
                    newState = newState.copy(pendingVisionRound = true)
                }
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    content = userRequest,
                    images = currentContext.inputImages
                )
            )
        )

        var systemPrompt = promptProvider.build(currentContext)
        val aiProvider = getActiveProvider(currentContext.sessionId)

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.CallLlm -> {
                        // 识图轮：若当前聊天模型无 vision，使用独立识图模型发送
                        val visionProvider = if (state.pendingVisionRound) resolveVisionFallbackProvider(currentContext.sessionId) else null
                        val providerInUse = visionProvider ?: aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        val compactedMessages = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId) { send(it) }
                        if (compactedMessages !== state.messages) {
                            state = state.copy(messages = compactedMessages)
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = state.pendingVisionRound || activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, supportsVision)
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        acc.append(chunk.text)
                                        send(AgentEvent.AssistantDelta(acc.toString()))
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        reasoningAcc.append(chunk.text)
                                        send(AgentEvent.ReasoningDelta(reasoningAcc.toString()))
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries))
                                    }
                                    is AIStreamChunk.Final -> finalResponse = chunk.response
                                }
                            }
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty()) {
                                send(AgentEvent.AssistantText(aiResponse.content, aiResponse.toolCalls, reasoningAcc.toString(), aiResponse.signature ?: "", aiResponse.inputTokens, aiResponse.outputTokens))
                            }
                            actionQueue.addLast(AgentAction.LlmResponse(responseWithReasoning))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            actionQueue.addLast(AgentAction.LlmError("LLM 调用失败: ${e.message}"))
                        } finally {
                            if (state.pendingVisionRound) state = state.copy(pendingVisionRound = false)
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
                        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                toolCalls.map { toolCall ->
                                    async {
                                        val tool = toolRegistry.getTool(toolCall.name)
                                        if (tool is StreamingAgentTool) {
                                            runToolStream(tool, toolCall, currentContext) { send(it) }
                                        } else {
                                            runToolSync(tool, toolCall, currentContext)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // PLAN→BUILD 时挂起 workflow，等待用户在计划审查面板批准后才继续
                                if (newCtx.mode == AgentMode.BUILD) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        systemPrompt = promptProvider.build(currentContext)
                                    } else {
                                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        systemPrompt = promptProvider.build(currentContext)
                                        rawResult = ToolResult.Error("用户拒绝了模式切换请求，请继续在 PLAN 模式下完善方案，待用户认可后再次申请切换。", "MODE_SWITCH_REJECTED").toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    systemPrompt = promptProvider.build(currentContext)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.images, runResult.attachments))
                        }

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError, attachments = br.attachments))
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it)) }
        send(AgentEvent.Completed)
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            if (name == "viewImage" && !activeModelSupportsVision(context.sessionId)) {
                if (!visionFallbackReady()) {
                    return ToolRunResult(
                        ToolResult.Error(
                            "当前聊天模型不支持图片输入，且未配置支持 Vision 的识图专用模型。请在「设置 → 默认模型 → 识图模型」中指定一个支持 Vision 的模型后再查看图片。",
                            "MODEL_VISION_UNSUPPORTED"
                        ).toTransportString(),
                        true
                    )
                }
                // 非多模态模型：同步用识图模型理解图片，文本结果直接作为工具返回
                val result = tool.executeWithContext(toolCall.arguments, context)
                val userPrompt = (toolCall.arguments["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
                val textResult = runVisionFallback(result, context.sessionId, userPrompt)
                val processed = toolOutputStore.process(name, toolCall.id, ToolResult.Success(JsonObject(mapOf(
                    "content" to JsonPrimitive(textResult),
                    "model" to JsonPrimitive("vision-fallback")
                ))))
                return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, emptyList())
            }
            val result = tool.executeWithContext(toolCall.arguments, context)
            val images = if (name == "viewImage") extractInlineImages(result) else emptyList()
            val attachments = if (name == "sendFile") extractAttachments(result) else emptyList()
            val transportResult = when {
                images.isNotEmpty() -> stripInlineImages(result)
                attachments.isNotEmpty() -> stripAttachments(result)
                else -> result
            }
            val processed = toolOutputStore.process(name, toolCall.id, transportResult)
            ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images, attachments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 非多模态模型的 viewImage 回退：从工具结果中提取 base64 图片，
     * 同步发给识图专用模型理解内容，返回文本结果。
     */
    private suspend fun runVisionFallback(result: ToolResult, sessionId: String?, customPrompt: String? = null): String {
        val data = (result as? ToolResult.Success)?.data as? JsonObject
            ?: return "无法解析图片数据"
        val image = data["image"] as? JsonObject
            ?: return "无法提取图片数据"
        val mimeType = image["mime_type"]?.jsonPrimitive?.contentOrNull
            ?: return "无法识别图片格式"
        val base64Data = image["base64_data"]?.jsonPrimitive?.contentOrNull
            ?: return "无法读取图片数据"
        val path = image["path"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val agentImage = AgentImage(mimeType = mimeType, base64Data = base64Data, path = path)
        val visionProvider = resolveVisionFallbackProvider(sessionId)
            ?: return "识图模型不可用"

        val promptText = if (!customPrompt.isNullOrBlank()) {
            "请针对用户/模型的如下关注重点，详细分析并描述这张图片：\n$customPrompt"
        } else {
            "请详细描述这张图片的内容，包括其中出现的文字、元素、布局、颜色等关键信息。"
        }

        try {
            val messages = listOf(
                AgentMessage.UserMessage(
                    content = promptText,
                    images = listOf(agentImage)
                )
            )
            val response = visionProvider.complete("", messages, emptyList())
            return response.content.ifBlank { "（识图模型未返回内容）" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "识图回退失败", e)
            return "识图失败: ${e.message}"
        }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 识图专用兜底模型是否可用：已配置 providerId 且指向的 provider/model 存在、有 apiKey、且其
     * ModelMetadata.supportsVision 为真。识图轮仅当 [activeModelSupportsVision] 为 false 时才回退到它。
     * 未配置（providerId 空）即视为「跟随聊天模型」，不构成兜底 → 返回 false。
     */
    private suspend fun visionFallbackReady(): Boolean {
        val providerId = visionModelSettingsRepository.getVisionProviderId().trim()
        if (providerId.isEmpty()) return false
        val model = visionModelSettingsRepository.getVisionModel().trim()
        if (model.isEmpty()) return false
        val config = aiProviderRepository.getProviderById(providerId) ?: return false
        if (!config.isEnabled) return false
        if (config.apiKey.isBlank()) return false
        val metadata = modelMetadataService.resolve(config.type, model)
        return metadata.supportsVision
    }

    /**
     * 识图轮专用 provider 解析。仅当当前聊天模型不支持 vision、且专用模型已配置且可用时返回
     * 全新的独立 AIProvider 实例；否则返回 null（表示无需切换、沿用 aiProvider）。
     */
    private suspend fun resolveVisionFallbackProvider(sessionId: String?): AIProvider? {
        if (activeModelSupportsVision(sessionId)) return null // 当前聊天模型就有原生能力，直接用之
        if (!visionFallbackReady()) return null       // 无可用兜底，仍沿用 aiProvider（守卫已先行拦截并报错）
        val providerId = visionModelSettingsRepository.getVisionProviderId().trim()
        val model = visionModelSettingsRepository.getVisionModel().trim()
        val config = aiProviderRepository.getProviderById(providerId)
            ?: error("识图专用模型配置丢失")
        if (config.apiKey.isBlank()) error("识图专用模型「${config.name}」未填写 API Key")
        if (model.isBlank()) error("识图专用模型未指定模型")
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || config.apiKey.isBlank()) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        try {
            tool.executeStream(toolCall.arguments, context).collect { ev ->
                when (ev) {
                    is ToolStreamEvent.Progress -> {
                        live.append(ev.chunk).append('\n')
                        if (live.length > LIVE_TAIL_CHARS) {
                            live.delete(0, live.length - LIVE_TAIL_CHARS)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                            lastEmitMs = now
                            onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                        }
                    }
                    is ToolStreamEvent.Completed -> finalResult = ev.result
                }
            }
            val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
            val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
            return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "switchMode" && !isError) {
            val targetModeStr = (toolCall.arguments["mode"] as? JsonPrimitive)?.content?.trim()?.uppercase()
                ?: toolCall.arguments["mode"]?.toString()?.replace("\"", "")?.trim()?.uppercase()
            if (targetModeStr != null) {
                runCatching { AgentMode.valueOf(targetModeStr) }.getOrNull()?.let { newMode ->
                    if (currentContext.mode != newMode) {
                        return currentContext.copy(mode = newMode) to true
                    }
                }
            }
        }
        return currentContext to false
    }

    private fun extractInlineImages(result: ToolResult): List<AgentImage> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val image = data["image"] as? JsonObject ?: return emptyList()
        val mimeType = image["mime_type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val base64Data = image["base64_data"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val path = image["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!mimeType.startsWith("image/") || base64Data.isBlank()) return emptyList()
        return listOf(AgentImage(mimeType = mimeType, base64Data = base64Data, path = path))
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private fun stripInlineImages(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val image = data["image"] as? JsonObject ?: return result
        val strippedImage = image.toMutableMap().apply {
            remove("base64_data")
            this["base64_omitted"] = JsonPrimitive(true)
            this["note"] = JsonPrimitive("图片数据已作为视觉输入附加，未写入文本工具结果。")
        }
        val strippedData = data.toMutableMap().apply {
            this["image"] = JsonObject(strippedImage)
            this["image_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, kotlinx.serialization.json.JsonElement>,
        argsPreview: String,
        mode: com.deep.rcode.feature.agent.domain.model.AgentMode
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // switchMode 从 PLAN 切到 BUILD 时，后续会有计划审查面板兜底用户决策，
        // 此处权限弹窗冗余，直接放行；BUILD→PLAN 方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "switchMode" && mode == AgentMode.PLAN) {
            val targetModeStr = (arguments["mode"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
            if (targetModeStr == AgentMode.BUILD.name) {
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == com.deep.rcode.feature.agent.domain.model.AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val request = tool.buildPermissionRequest(callId, arguments, argsPreview)
                    .copy(
                        rememberablePatterns = eval.rememberablePatterns,
                        rememberDisabledReason = eval.rememberDisabledReason
                    )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }

    private fun extractFinalContent(state: AgentSessionState): String {
        for (i in state.messages.indices.reversed()) {
            val msg = state.messages[i]
            if (msg is AgentMessage.AssistantMessage && msg.content.isNotBlank()) {
                return msg.content.trim()
            }
        }
        return ""
    }
}
