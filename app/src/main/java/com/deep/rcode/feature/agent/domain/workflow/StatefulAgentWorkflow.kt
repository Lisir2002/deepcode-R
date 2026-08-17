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
import com.deep.rcode.feature.agent.domain.tool.ToolDependencyScheduler
import com.deep.rcode.feature.agent.domain.tool.ToolResultCache
import com.deep.rcode.feature.agent.domain.tool.ToolEventBus
import com.deep.rcode.feature.agent.domain.tool.ToolEvent
import com.deep.rcode.feature.agent.domain.tool.IncrementalIndexStore
import com.deep.rcode.feature.agent.domain.tool.ActionIndex
import com.deep.rcode.feature.agent.domain.tool.RoundSnapshot
import com.deep.rcode.feature.agent.domain.tool.ToolSessionState
import com.deep.rcode.feature.agent.domain.tool.ToolOutputRecord
import com.deep.rcode.feature.agent.domain.tool.ToolErrorClass
import com.deep.rcode.feature.agent.domain.tool.RetryPolicy
import com.deep.rcode.feature.agent.domain.tool.classifyError
import com.deep.rcode.feature.agent.domain.tool.ToolStreamEvent
import com.deep.rcode.feature.agent.domain.tool.toTransportString
import com.deep.rcode.feature.agent.presentation.AgentAttachment
import com.deep.rcode.feature.settings.data.remote.ModelMetadataService
import com.deep.rcode.feature.settings.data.repository.CompatibilityPolicyRepository
import com.deep.rcode.feature.settings.data.repository.ViewImageUnknownGuardPolicy
import com.deep.rcode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.deep.rcode.feature.settings.data.repository.VisionModelSettingsRepository
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.agent.data.remote.anthropic.AnthropicApi
import com.deep.rcode.feature.agent.data.remote.gemini.GeminiApi
import com.deep.rcode.feature.agent.data.remote.openai.OpenAIApi
import com.deep.rcode.feature.agent.domain.provider.AnthropicAdapter
import com.deep.rcode.feature.agent.domain.provider.GeminiAdapter
import com.deep.rcode.feature.agent.domain.provider.OpenAIAdapter
import com.deep.rcode.feature.settings.domain.model.ModelMetadata
import com.deep.rcode.feature.settings.domain.model.ProviderType
import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
    /**
     * RC63 备选方案③兼容端点策略 & ②自动降级总开关。
     * 读两个字段：(a) viewImage 未收录模型守卫策略（FALLBACK_VISION_MODEL vs FAIL_FAST）；
     * (b) 备选方案②「发送失败自动降级」总开关（用户关掉则即使触发也不自动降级）。
     */
    private val compatibilityPolicyRepository: CompatibilityPolicyRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    /** L4 依赖感知调度：构建依赖图、拓扑分层、指数退避重试。 */
    private val dependencyScheduler: ToolDependencyScheduler,
    /** L5 结果缓存：纯读工具按 (toolName, argsHash) 键控复用，TTL + mtime 双机制失效。 */
    private val toolResultCache: ToolResultCache,
    /** L7 事件总线：工具间事件驱动协作，文件变更事件联动缓存失效。 */
    private val toolEventBus: ToolEventBus,
    /** L6 上下文增量发布：工具动作与轮次快照的增量索引。 */
    private val incrementalIndexStore: IncrementalIndexStore
) : AgentWorkflow {

    init {
        // L5 + L7 联动：文件变更事件 → 失效相关缓存（写后失效，保证缓存新鲜度）。
        toolEventBus.subscribe { event ->
            when (event) {
                is ToolEvent.FileEdited -> toolResultCache.invalidateByEvent("file.edited", event.path)
                is ToolEvent.FileWritten -> toolResultCache.invalidateByEvent("file.written", event.path)
                is ToolEvent.FileDeleted -> toolResultCache.invalidateByEvent("file.deleted", event.path)
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        /** L6 增量索引：动作摘要截断长度，避免大结果全量写入内存索引。 */
        const val SUMMARY_CHARS = 512

        /**
         * 上游模型 API 返回「不支持多模态（image / image_url）」的特征关键字列表。
         * 由 HttpErrorEnricher 把响应体里的 error.message 拼到异常 message 后是这种形式：
         *   "HTTP 400: Invalid parameter: messages with type=image_url are not supported by this model"
         * 捕获阶段 2 备选方案②的自动降级条件：用这些关键字做匹配。
         */
        val VISION_UNSUPPORTED_HINTS: List<String> = listOf(
            "image_url",
            "image",
            "not supported",
            "unsupported",
            "invalid parameter",
            "multimodal"
        )
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
        val pendingVisionRound: Boolean = false,
        /**
         * 备选方案②的防死循环 flag：本会话是否已经因为「上游报错不支持 vision」而自动降级过重试一次？
         * 是则不再自动重试，直接给用户展示可操作的错误提示（去设置识图模型 / 去改模型覆盖）。
         * 新用户请求（AgentAction.InitRequest）下发时会被清 false。
         */
        val visionFallbackRetried: Boolean = false
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
        val attachments: List<com.deep.rcode.feature.agent.presentation.AgentAttachment> = emptyList(),
        /** L3 错误分类：成功为 null，失败按 [classifyError] 推断，供 L4 调度器判定是否重试。 */
        val errorClass: ToolErrorClass? = null
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
                // 新用户请求：清零备选方案②的降级重试标志，让兼容端点新请求有机会自动兜底
                newState = state.copy(
                    messages = action.initialMessages,
                    visionFallbackRetried = false
                )
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
                        // ════════════════════════════════════════════════════════════
                        // ZTH 接入点 B（hookPreToolAudit）：Phase 5 C.5 四方联动第 2 站
                        // 插入位置：PermissionEvaluated reducer 所有 toolCall 已批准（approved
                        //   集合已满 + remaining.isEmpty），effects.add(ExecuteToolBatch) 之前。
                        // 接入代码（示例，不改状态树只做挂起审查）：
                        //   val auditBundle = zthWorkflowHooks.hookPreToolAudit(
                        //       sessionId = currentContext.sessionId,
                        //       toolCalls = approved,
                        //       capabilityResolver = { tc -> resolveCapabilities(tc.name) },
                        //       mode = AgentMode.BUILD,
                        //       executionMode = ExecutionMode.LOCAL_PROOT,
                        //       onlineValidated = true
                        //   )
                        //   // auditBundle.anyBlockedByGlobalDeny → 整批拒绝写 ToolBatchResult
                        // ════════════════════════════════════════════════════════════
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
                        // ════════════════════════════════════════════════════════════
                        // ZTH 接入点 B 分支 2（hookPreToolAudit）：PermissionEvaluated deny
                        //   分支，remaining 清空后也要跑 preTool（不挂 CC 但写遥测）。
                        // ════════════════════════════════════════════════════════════
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // ════════════════════════════════════════════════════════════
                // ZTH 接入点 C（hookPostToolAudit）：Phase 5 C.5 四方联动第 3 站
                // 插入位置：ToolBatchFinished reducer 入口（已进入 reducer，action.results
                //   可用），在组装 appendedMessages 之前。
                // 接入代码（示例，每个 tool result 逐一挂 postTool 幻觉扫）：
                //   action.results.forEach { r ->
                //       zthWorkflowHooks.hookPostToolAudit(
                //           sessionId = currentContext.sessionId,
                //           callId = r.id, toolName = r.toolName,
                //           outputText = r.result,
                //           modifiedFilesHint = extractModifiedFiles(r.toolName, argsMap)
                //       )
                //       // hallucinationFlag=true → FailureClassifier(E7) + 弹卡
                //   }
                // ════════════════════════════════════════════════════════════
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
        // 即时反馈：workflow 一启动立即推送一个空 reasoning 占位，
        // 让 UI 立即进入"思考中"渲染态（ThinkingBubble），
        // 避免首轮准备阶段（prompt 构建 / provider 解析 / 上下文压缩 / 网络建连）长时间无反馈。
        send(AgentEvent.ReasoningDelta(""))

        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        // L2 共享会话状态：随会话创建，注入 currentContext 供所有工具共享读写。
        val sessionState = context.sessionId?.let { ToolSessionState(it) }
        if (sessionState != null) {
            currentContext = currentContext.copy(sessionState = sessionState)
        }
        val actionQueue = ArrayDeque<AgentAction>()
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    content = userRequest,
                    images = currentContext.inputImages
                )
            )
        )

        // 并行化首轮准备：prompt 构建（含文件 IO / Room 同步查询）与 provider 解析（含多次 DB 查询）
        // 互不依赖，放到 IO 线程并行执行，避免在收集线程上串行阻塞、拖慢首字节反馈。
        val prepared = coroutineScope {
            val systemPromptDeferred = async(Dispatchers.IO) { promptProvider.build(currentContext) }
            val providerDeferred = async(Dispatchers.IO) { getActiveProvider(currentContext.sessionId) }
            systemPromptDeferred.await() to providerDeferred.await()
        }
        var systemPrompt = prepared.first
        val aiProvider = prepared.second

        // 预压缩任务：在工具执行间隙于后台启动，供下一轮 CallLlm 复用，
        // 避免上下文压缩阻塞下一轮 LLM 的首字节。null 表示无待消费的预压缩结果。
        var pendingCompaction: Deferred<List<AgentMessage>>? = null
        var pendingCompactionBaseCount = 0

        // 主循环包进 try/finally：无论正常结束、协程被取消（用户点停止）还是异常退出，
        // 都兜底清理本会话残留的「未决工具权限请求」，避免对话结束后确认卡一直挂着。
        // 正常路径下 awaitApproval 的 finally 已清 _pendingRequest，此处为空操作；取消/异常
        // 路径下把挂起的 awaitApproval 以 REJECT 唤醒，工具收到「用户拒绝执行」而非永久挂起。
        try { while (!state.isFinished && actionQueue.isNotEmpty()) {
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
                        // 优先消费工具执行间隙的预压缩结果；没有（首轮）则按原逻辑同步压缩。
                        val preCompacted = try {
                            pendingCompaction?.await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "预压缩任务异常，回退同步压缩", e)
                            null
                        }
                        pendingCompaction = null
                        val compactedMessages = if (preCompacted != null) {
                            // 预压缩基于「本批工具结果之前」的消息，需把本批新增结果追加回末尾
                            preCompacted + state.messages.drop(pendingCompactionBaseCount)
                        } else {
                            contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId) { send(it) }
                        }
                        if (compactedMessages !== state.messages) {
                            state = state.copy(messages = compactedMessages)
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // sendImages 提级到 try 外面：catch 块（备选方案②自动降级）需要访问它判断
                        // "本轮原本就是带图发送"，否则 try 里定义的局部变量 catch 不可见，
                        // 会出现 Unresolved reference 'sendImages' 编译错。
                        val sendImages = state.pendingVisionRound || shouldSendImages(currentContext.sessionId)

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            // 注意：这里用 shouldSendImages 而不是 activeModelSupportsVision，
                            // 是为了避免把 source=INFERRED 的自定义多模态模型误当成纯文本模型，
                            // 发送前剥离图片导致模型「图都收不到还怎么识别」。
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, sendImages)
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
                            val errorMessage = e.message.orEmpty()

                            // —— 备选方案②：自动降级兜底（上游明确拒绝 image / 多模态时触发） ——
                            // 条件：(a) 本来就是带图在发的（sendImages=true）；(b) 还没自动降级重试过
                            // （visionFallbackRetried=false）；(c) 错误文本符合 VISION_UNSUPPORTED_HINTS
                            // 关键字特征。此时走两条兜底：
                            //   路径 1（优先，若已配置识图模型）：把 messages 中的图片先调用
                            //     runVisionFallback 翻译成文本描述，替换掉 images 字段后重发纯文本
                            //     （重发过程中置 visionFallbackRetried=true 防止死循环）
                            //   路径 2（没配识图模型）：直接剥 images 后重发一次纯文本，
                            //     同时在 prompt 最前面追加一句「原请求含图片，当前模型不支持
                            //     多模态识图（Vision），已自动转为纯文本发送，若需看图请去设置
                            //     识图模型或修改当前模型覆盖配置」提示。
                            val visionUnsupported = errorMessage.length >= 8 && run {
                                val lower = errorMessage.lowercase()
                                val containsVision = VISION_UNSUPPORTED_HINTS.count { lower.contains(it) }
                                // 至少命中 image* 类 + not supported / unsupported / invalid parameter 类各一条
                                (lower.contains("image") || lower.contains("image_url") || lower.contains("multimodal")) &&
                                    (lower.contains("not support") || lower.contains("unsupported") || lower.contains("invalid parameter") || lower.contains("not allowed") || lower.contains("invalid request"))
                            }
                            if (sendImages && !state.visionFallbackRetried && visionUnsupported &&
                                compatibilityPolicyRepository.isAutoDowngradeOnSendFailure()
                            ) {
                                // 先标记降级已尝试，避免死循环
                                state = state.copy(visionFallbackRetried = true)
                                val fallbackReady = visionFallbackReady()
                                val fallbackDescription = buildString {
                                    append("【系统提示】原请求包含图片，检测到当前模型不支持「多模态识图（Vision）」，")
                                    if (fallbackReady) append("已启用「识图专用模型」自动识别图片内容后，以纯文本形式重新发送。")
                                    else append("未配置「识图专用模型」，已自动去掉图片并转为纯文本发送；若需要识别图片内容，请在设置中指定一个支持「多模态识图（Vision）」能力的模型，或在当前模型的能力覆盖中手动开启「多模态识图（Vision）」。")
                                }
                                // 构造去 images 的消息 + 前置系统提示 UserMessage
                                val textOnlyMessages = sanitizeImagesForModel(compactedMessages, supportsVision = false).toMutableList()
                                val firstUserIndex = textOnlyMessages.indexOfFirst { it is AgentMessage.UserMessage }
                                if (firstUserIndex >= 0) {
                                    val origin = textOnlyMessages[firstUserIndex] as AgentMessage.UserMessage
                                    val newContent = buildString {
                                        appendLine(fallbackDescription)
                                        append("【图片内容摘要】")
                                        appendLine("（注：以下图片摘要是系统兜底生成，如果图片内容对你不重要可忽略）")
                                        // 如果有独立识图模型就用它去识别第一张图（runVisionFallback 的接口是传 ToolResult），
                                        // 为复用代码这里先组装一个假的 ToolResult.Success 喂给 runVisionFallback；
                                        // 没配识图模型就写"无"。
                                        if (fallbackReady) {
                                            val originImage = compactedMessages
                                                .filterIsInstance<AgentMessage.UserMessage>()
                                                .flatMap { it.images }
                                                .firstOrNull()
                                            if (originImage != null) {
                                                val fake = ToolResult.Success(
                                                    JsonObject(
                                                        mapOf(
                                                            "image" to JsonObject(
                                                                mapOf(
                                                                    "mime_type" to JsonPrimitive(originImage.mimeType),
                                                                    "base64_data" to JsonPrimitive(originImage.base64Data),
                                                                    "path" to JsonPrimitive(originImage.path.orEmpty())
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                                runCatching {
                                                    val summary = runVisionFallback(fake, currentContext.sessionId, null)
                                                    append(summary)
                                                }.onFailure { append("摘要失败：${it.message}") }
                                            } else {
                                                append("（未在历史消息中找到图片附件）")
                                            }
                                        } else {
                                            append("（未配置识图专用模型，跳过图片识别）")
                                        }
                                        appendLine()
                                        appendLine("—— 用户原始问题 ——")
                                        append(origin.content)
                                    }
                                    textOnlyMessages[firstUserIndex] = origin.copy(content = newContent)
                                }
                                // 正式走一次纯文本请求（不再包 try，失败就按原逻辑给 AgentEvent.Failed）
                                val acc2 = StringBuilder()
                                val reasoning2 = StringBuilder()
                                var final2: AIResponse? = null
                                providerInUse.completeStream(systemPrompt, textOnlyMessages, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                    when (chunk) {
                                        is AIStreamChunk.TextDelta -> {
                                            acc2.append(chunk.text)
                                            send(AgentEvent.AssistantDelta(acc2.toString()))
                                        }
                                        is AIStreamChunk.ReasoningDelta -> {
                                            reasoning2.append(chunk.text)
                                            send(AgentEvent.ReasoningDelta(reasoning2.toString()))
                                        }
                                        is AIStreamChunk.Retrying -> {
                                            acc2.setLength(0)
                                            reasoning2.setLength(0)
                                            send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries))
                                        }
                                        is AIStreamChunk.Final -> final2 = chunk.response
                                    }
                                }
                                val aiResp2 = (final2 ?: AIResponse(content = acc2.toString())).let {
                                    if (reasoning2.isNotEmpty()) it.copy(reasoning = reasoning2.toString()) else it
                                }
                                if (aiResp2.content.isNotBlank() || aiResp2.toolCalls.isNotEmpty()) {
                                    send(AgentEvent.AssistantText(aiResp2.content, aiResp2.toolCalls, reasoning2.toString(), aiResp2.signature ?: "", aiResp2.inputTokens, aiResp2.outputTokens))
                                }
                                actionQueue.addLast(AgentAction.LlmResponse(aiResp2))
                            } else {
                                // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                                // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                                // 有正文或有思考其一即落库；两者皆空则不写空消息。
                                // ════════════════════════════════════════════════════════════
                                // ZTH 接入点 D（hookOnThrowable）：Phase 5 C.5 四方联动第 4 站
                                // 插入位置：CallLlm catch(e: Exception) 分支，备选方案② visionFallback
                                //   已尝试完毕但未命中（进入本 else 分支，准备走 LlmError 原逻辑）之前。
                                // 接入代码（示例，不改 state 结构，把 hook 返回 banner 注入 errorMessage）：
                                //   val classifyBundle = zthWorkflowHooks.hookOnThrowable(
                                //       sessionId = currentContext.sessionId,
                                //       throwable = e,
                                //       mode = AgentMode.BUILD,
                                //       executionMode = ExecutionMode.LOCAL_PROOT,
                                //       onlineValidated = true,
                                //       httpStatusCode = extractHttpStatusCode(e)
                                //   )
                                //   // classifyBundle.suspendForCard=true → 挂 CC 等用户确认再继续
                                //   // classifyBundle.autoRecoveryHint → 拼到 LlmError message 显示
                                // ════════════════════════════════════════════════════════════
                                if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                    send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                                }
                                actionQueue.addLast(AgentAction.LlmError("LLM 调用失败: ${e.message}"))
                            }
                        } finally {
                            if (state.pendingVisionRound) state = state.copy(pendingVisionRound = false)
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode, currentContext.sessionId)

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

                        // 工具执行间隙预压缩：在后台启动基于当前上下文的压缩（与工具执行并行），
                        // 下一轮 CallLlm 直接复用结果，避免压缩阻塞下一轮 LLM 的首字节。
                        // 注意：压缩基于「本批工具结果之前」的消息快照，本批结果由 CallLlm 侧追加回末尾；
                        // 快照末尾必为上一轮 assistant（含 toolCalls），tail 至少保留它，追加 toolResult 不会孤立。
                        if (pendingCompaction == null) {
                            pendingCompactionBaseCount = state.messages.size
                            val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: aiProvider
                            val messagesSnapshot = state.messages
                            val producer = this
                            pendingCompaction = async(Dispatchers.IO) {
                                contextCompactor.compactIfNeeded(messagesSnapshot, compactionProvider, context.sessionId) { producer.send(it) }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            // L4 依赖感知调度：按工具声明的 dependsOn 构建依赖图并拓扑分层，
                            // 无依赖的工具并行、有依赖的自动串行；可重试错误按指数退避自动重试。
                            val plan = dependencyScheduler.buildSchedule(toolCalls, toolRegistry)
                            if (plan.hasCycle) {
                                toolCalls.map { call ->
                                    ToolRunResult(
                                        ToolResult.Error("调度失败: ${plan.cycleMessage}", "DEPENDENCY_CYCLE").toTransportString(),
                                        true,
                                        errorClass = ToolErrorClass.FATAL
                                    )
                                }
                            } else {
                                val resultsById = HashMap<String, ToolRunResult>()
                                for (layer in plan.layers) {
                                    coroutineScope {
                                        layer.map { call ->
                                            async {
                                                val tool = toolRegistry.getTool(call.name)
                                                val policy = tool?.retryPolicy ?: RetryPolicy()
                                                val result = dependencyScheduler.runWithRetry(
                                                    tool, call,
                                                    execute = { t, c ->
                                                        if (t is StreamingAgentTool) {
                                                            runToolStream(t, c, currentContext) { send(it) }
                                                        } else {
                                                            runToolSync(t, c, currentContext)
                                                        }
                                                    },
                                                    isRetryable = { r ->
                                                        r.isError && r.errorClass?.let { it in policy.retryable } == true
                                                    }
                                                )
                                                resultsById[call.id] = result
                                            }
                                        }.awaitAll()
                                    }
                                }
                                // 按原始顺序返回，保证与 toolCalls.forEachIndexed 组装结果一致。
                                toolCalls.map {
                                    resultsById[it.id] ?: ToolRunResult(
                                        ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(),
                                        true
                                    )
                                }
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
                                    // ════════════════════════════════════════════════════════════
                                    // ZTH 接入点 A（hookPrePlanAudit）：Phase 5 C.5 四方联动第 1 站
                                    // 插入位置：SwitchModeTool(PLAN→BUILD) 完成上下文切换计算后，
                                    //           planApprovalManager.awaitApproval() 调用之前。
                                    // 接入代码（示例，保持 HOOK-INV 不破坏 reducer 不变性）：
                                    //   val (zthChoice, structuredPlan) = zthWorkflowHooks.hookPrePlanAudit(
                                    //       sessionId = currentContext.sessionId,
                                    //       originalPlanReason = reason,
                                    //       planText = reason,
                                    //       affectedFiles = emptyList(),  // 后续从 SwitchModeTool 参数传
                                    //       estimatedToolCalls = 0,
                                    //       mode = AgentMode.PLAN,
                                    //       executionMode = ExecutionMode.LOCAL_PROOT,
                                    //       onlineValidated = true
                                    //   )
                                    //   // 若 zthChoice == REFINE → 直接走 REFINE 分支（不调原生 awaitApproval）
                                    // ════════════════════════════════════════════════════════════
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

                        // L6 增量索引：记录本批轮次快照并持久化。
                        recordIncrementalRound(currentContext, batchResults)

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError, attachments = br.attachments))
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        } finally {
            // 兜底：本会话 workflow 退出（正常 / 用户停止取消 / 异常）时，清理残留的
            // 未决工具权限请求，以 REJECT 唤醒挂起的 awaitApproval。正常路径下该请求已被
            // awaitApproval 的 finally 清掉，这里是空操作；取消/异常路径避免确认卡残留。
            permissionManager.cancelPending(currentContext.sessionId)
        }
        
        state.error?.let { send(AgentEvent.Failed(it)) }
        send(AgentEvent.Completed)
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            val error = ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND")
            recordSessionOutput(context, toolCall.id, name, error)
            return ToolRunResult(error.toTransportString(), true, errorClass = classifyError(error.code))
        }
        return try {
            // L5 结果缓存：纯读工具（readFile/searchCode/listFiles/grep）按 (toolName, argsHash) 键控，
            // 命中则直接复用结果，避免同参数重复执行。文件类工具由 mtime + TTL 双机制失效。
            val cacheKey = if (name in ToolResultCache.FILE_TOOLS) {
                context.sessionId?.let { sid -> toolResultCache.buildKey(name, toolCall.arguments, sid) }
            } else {
                null
            }
            if (cacheKey != null) {
                val cached = toolResultCache.get(cacheKey)
                if (cached != null) {
                    val cachedTransport = cached.toTransportString()
                    recordSessionOutput(context, toolCall.id, name, cached)
                    recordIncrementalAction(name, cachedTransport, context)
                    val errorClass = (cached as? ToolResult.Error)?.let { classifyError(it.code) }
                    return ToolRunResult(cachedTransport, cached is ToolResult.Error, errorClass = errorClass)
                }
            }
            if (name == "viewImage" && !activeModelSupportsVision(context.sessionId)) {
                // RC63 备选方案③：viewImage 守卫策略（默认自动回退识图模型，FAIL_FAST 则直接报错提示用户）
                val guardPolicy = compatibilityPolicyRepository.getViewImageUnknownGuardPolicy()
                val fallbackReady = visionFallbackReady()
                if (guardPolicy == ViewImageUnknownGuardPolicy.FAIL_FAST || !fallbackReady) {
                    val error = ToolResult.Error(
                        "当前聊天模型不支持「多模态识图（Vision）」能力，且未配置专用的「多模态识图（Vision）」兜底模型。请在【设置 → 默认模型 → 识图模型】中指定一个支持「多模态识图（Vision）」能力的模型后再查看图片。\n" +
                            "💡 你也可以在【设置 → AI 提供商 → 该模型 → 能力覆盖】中手动开启「多模态识图（Vision）」复选框，一步修复。",
                        "MODEL_VISION_UNSUPPORTED"
                    )
                    recordSessionOutput(context, toolCall.id, name, error)
                    return ToolRunResult(
                        error.toTransportString(),
                        true,
                        errorClass = classifyError(error.code)
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
                recordSessionOutput(context, toolCall.id, name, processed)
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
            recordSessionOutput(context, toolCall.id, name, processed)
            // L5 写缓存：仅成功结果入缓存，失败不缓存（避免缓存错误）。
            if (cacheKey != null && processed is ToolResult.Success) {
                toolResultCache.put(cacheKey, processed)
            }
            // L7 事件发布：工具成功后广播对应事件，驱动缓存失效、上下文增量刷新等联动。
            if (processed is ToolResult.Success) {
                publishToolEvent(name, toolCall, processed, context)
            }
            // L6 增量索引：记录工具动作摘要（成功与失败均记录，作为状态变化）。
            // 复用下方 transportString，避免对完整结果重复序列化。
            val transportString = processed.toTransportString()
            recordIncrementalAction(name, transportString, context)
            val errorClass = (processed as? ToolResult.Error)?.let { classifyError(it.code) }
            ToolRunResult(transportString, processed is ToolResult.Error, images, attachments, errorClass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED")
            recordSessionOutput(context, toolCall.id, name, error)
            ToolRunResult(error.toTransportString(), true, errorClass = classifyError(error.code))
        }
    }

    /**
     * L7 事件发布：工具成功后广播对应事件，驱动缓存失效、上下文增量刷新等联动。
     *
     * 事件类型按工具名映射：文件写（file.edited / file.written）、待办（todo.updated）、
     * 记忆（state.memory.updated）、技能（state.skill.loaded）、模式（state.mode.changed）。
     * hash/diff 等字段级详情由工具自身发布，此处先建立机制。
     */
    private suspend fun publishToolEvent(name: String, toolCall: ToolCall, result: ToolResult, context: AgentContext) {
        val sessionId = context.sessionId
        val event: ToolEvent? = when (name) {
            "editFile" -> {
                val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull ?: return
                ToolEvent.FileEdited(path = path, oldHash = null, newHash = "", diffSummary = "", sessionId = sessionId)
            }
            "writeFile" -> {
                val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull ?: return
                ToolEvent.FileWritten(path = path, size = 0, hash = "", sessionId = sessionId)
            }
            "todo" -> {
                // 快照式接口：每次提交完整列表，统一按 todo.updated 广播，负载含完整待办列表。
                val fullList = context.sessionState?.todoSnapshot.orEmpty()
                ToolEvent.TodoUpdated(todoId = "", changedFields = listOf("items"), fullList = fullList, sessionId = sessionId)
            }
            "memory" -> {
                // 仅写操作（save/edit/delete）广播记忆变更，read/list 不触发。
                val action = (toolCall.arguments["action"] as? JsonPrimitive)?.contentOrNull
                if (action !in setOf("save", "edit", "delete")) return
                val memoryKey = (toolCall.arguments["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                val summary = (result as? ToolResult.Success)?.data
                    ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
                ToolEvent.StateMemoryUpdated(memoryKey = memoryKey, summary = summary, sessionId = sessionId)
            }
            "loadSkill" -> {
                val skillName = (toolCall.arguments["skill_name"] as? JsonPrimitive)?.contentOrNull ?: ""
                ToolEvent.StateSkillLoaded(skillName = skillName, toolCount = 0, sessionId = sessionId)
            }
            "switchMode" -> {
                val to = (toolCall.arguments["mode"] as? JsonPrimitive)?.contentOrNull ?: ""
                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
                ToolEvent.StateModeChanged(from = context.mode.name, to = to, reason = reason, sessionId = sessionId)
            }
            else -> null
        }
        event?.let { toolEventBus.publish(it) }
    }

    /**
     * L6 增量索引：记录工具动作摘要。
     *
     * 性能关键：接收已序列化的 [transportString]（调用方 [runToolSync] 已序列化一次，
     * 此处复用避免对完整结果重复 toTransportString）；data 只存截断摘要，避免内存膨胀。
     */
    private suspend fun recordIncrementalAction(name: String, transportString: String, context: AgentContext) {
        val sessionId = context.sessionId ?: return
        val summary = transportString.take(SUMMARY_CHARS)
        incrementalIndexStore.recordAction(
            sessionId,
            ActionIndex(
                actionType = name,
                data = JsonPrimitive(summary),
                dataHash = transportString.hashCode().toString()
            )
        )
    }

    /**
     * L6 增量索引：记录本批轮次快照并触发持久化（轮次级索引）。
     */
    private suspend fun recordIncrementalRound(context: AgentContext, batchResults: List<ToolBatchResult>) {
        val sessionId = context.sessionId ?: return
        if (batchResults.isEmpty()) return
        val actions = batchResults.map { br ->
            ActionIndex(
                actionType = br.toolName,
                data = JsonPrimitive(br.result.take(SUMMARY_CHARS)),
                dataHash = br.result.hashCode().toString()
            )
        }
        // O(1) 读取输出计数作为轮次号，避免每次全量遍历 outputs。
        val round = context.sessionState?.outputCount ?: 0
        incrementalIndexStore.recordRound(
            sessionId,
            RoundSnapshot(round, actions, "本批 ${actions.size} 个工具动作")
        )
        incrementalIndexStore.persist(sessionId)
    }

    /**
     * L2 共享会话状态：把单次工具执行结果写入 [ToolSessionState.outputs]（callId 索引），
     * 供后续工具按 callId 直连引用前序产物。成功记录 errorClass=null，失败按 [classifyError] 分类。
     */
    private fun recordSessionOutput(context: AgentContext, callId: String, toolName: String, result: ToolResult) {
        val state = context.sessionState ?: return
        val errorClass = when (result) {
            is ToolResult.Error -> classifyError(result.code)
            else -> null
        }
        state.recordOutput(
            ToolOutputRecord(
                callId = callId,
                toolName = toolName,
                result = result,
                errorClass = errorClass
            )
        )
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
            ?: return "「多模态识图（Vision）」兜底模型不可用，请先在设置中启用。"

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

    /**
     * 当前聊天模型是否「有原生能力支持多模态」。
     *
     * 影响两条关键链路：
     *  (1) `runToolSync(name=="viewImage")` L586 的守卫：
     *      if (!activeModelSupportsVision(...)) → 直接抛「当前聊天模型不支持图片输入」
     *      这条就是用户接入 step-3.7-flash 时截图里看到的错误文案。
     *
     *  (2) `pendingVisionRound` 是否启用独立识图模型 fallback 的判定入口。
     *
     *  RC63 判定链优先级（从高到低）：
     *  - ④ 单模型复选框手动覆盖（`ModelCapabilityOverrideDao`，在 resolve 内处理）；
     *  - catalog 明确 supportsVision=true → 支持；
     *  - catalog 明确 supportsVision=false（MODELS_DEV 收录的纯文本模型）→ 不支持；
     *  - ③ 兼容端点默认策略 Repository（STRICT/HEURISTIC/LAX/MANUAL，resolve 内处理）；
     *  - probablyVision 启发式（step- 家族白名单在 ModelMetadataService.default() 命中即为 true）。
     *
     *  RC63 关键决策（按用户要求「针对性修复独立出来」）：
     *  - **撤销 RC62e 的 `source==INFERRED → 一律 true` 全局放宽**；
     *  - 这里只返回最终 `metadata.supportsVision` 布尔值；
     *  - step-3.7-flash 修复仅由 probablyVision step- 家族白名单单独命中，
     *    不影响其他未收录模型（它们恢复 RC62d 之前的严格语义）。
     */
    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前是否应该把图片带给模型。
     * RC63 判定链与 [activeModelSupportsVision] 严格一致；
     * 2026-08-10 RC63 按用户要求**撤销 RC62e 的 `source==INFERRED → 一律 true` 全局放宽**，
     * 恢复 RC62d 之前的严格语义。未收录兼容端点模型仅当 probablyVision 启发式
     * （或③兼容端点策略 / ④单模型覆盖）命中时才会带图发送，避免全局放宽的副作用。
     */
    private suspend fun shouldSendImages(sessionId: String?): Boolean {
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
            val errorClass = (processed as? ToolResult.Error)?.let { classifyError(it.code) }
            return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, errorClass = errorClass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED")
            return ToolRunResult(error.toTransportString(), true, errorClass = classifyError(error.code))
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
        mode: com.deep.rcode.feature.agent.domain.model.AgentMode,
        sessionId: String?
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
                when (permissionManager.awaitApproval(sessionId, request)) {
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
