package com.R.codecore.feature.agent.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.R
import com.R.codecore.core.util.FileLogger
import com.R.codecore.core.util.toUserMessage
import com.R.codecore.feature.agent.data.local.dao.AgentMessageDao
import com.R.codecore.feature.agent.domain.checkpoint.CheckpointManager
import com.R.codecore.feature.agent.data.local.dao.CheckpointDao
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.agent.data.local.entity.ChatSessionEntity
import com.R.codecore.feature.agent.data.CodeChangeTracker
import com.R.codecore.feature.agent.domain.container.ContainerInitState
import com.R.codecore.feature.agent.domain.container.LinuxContainerEngine
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import com.R.codecore.feature.settings.data.repository.DefaultModelSettingsRepository
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.model.AgentImage
import com.R.codecore.feature.agent.domain.model.AgentMessage
import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.model.ChangeType
import com.R.codecore.feature.agent.domain.model.ChatSession
import com.R.codecore.feature.agent.domain.model.CodeChange
import com.R.codecore.feature.agent.domain.model.ReasoningEffort
import com.R.codecore.feature.agent.domain.model.WorkflowStatus
import com.R.codecore.feature.agent.domain.permission.PermissionChoice
import com.R.codecore.feature.agent.domain.permission.BuildCommandClassifier
import com.R.codecore.feature.agent.domain.workflow.AgentWorkflow
import com.R.codecore.feature.terminal.domain.TabFinishedEvent
import com.R.codecore.feature.terminal.domain.TAIL_LINES
import com.R.codecore.feature.terminal.domain.TerminalSessionManager
import com.R.codecore.feature.terminal.domain.takeTailLines
import com.R.codecore.feature.agent.domain.workflow.AgentEvent
import com.R.codecore.feature.agent.domain.tool.ToolPermissionManager
import com.R.codecore.feature.agent.domain.tool.ToolRegistry
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.agent.domain.tool.container.CheckEnvironmentTool
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalManager
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.R.codecore.feature.agent.domain.tool.question.AskUserQuestionManager
import com.R.codecore.feature.agent.domain.tool.question.UserQuestionAnswer
import com.R.codecore.feature.agent.domain.tool.toTransportString
import com.R.codecore.feature.agent.domain.session.SessionUseCase
import com.R.codecore.feature.agent.domain.session.MessagePersistenceUseCase
import com.R.codecore.feature.backup.domain.BackupManager
import com.R.codecore.feature.agent.domain.command.SlashCommandContext
import com.R.codecore.feature.agent.domain.command.SlashCommandRegistry
import com.R.codecore.feature.agent.domain.command.SlashCommandHandler
import com.R.codecore.feature.agent.domain.prompt.AgentAssetRegistry
import com.R.codecore.feature.agent.presentation.AgentAttachment
import com.R.codecore.feature.agent.presentation.component.formatTokenCount
import com.R.codecore.feature.agent.presentation.component.parseEnvironmentComponents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val codeChangeTracker: CodeChangeTracker,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val backupManager: BackupManager,
    private val checkEnvironmentTool: CheckEnvironmentTool,
    private val agentAssetRegistry: AgentAssetRegistry,
    /** 定时提醒调度循环：会话创建后注册到点投递回调（DSH schedule）。 */
    private val scheduleScheduler: com.R.codecore.feature.agent.domain.schedule.ScheduleScheduler,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()

    /** 已向调度循环注册过到点投递回调的会话 id 集合（去重 + onCleared 注销用）。 */
    private val registeredScheduleSessions = mutableSetOf<String>()

    /**
     * 为会话注册「定时提醒到点投递」回调：到点时把提醒正文作为一条带 "scheduled"
     * 标记的 user/message 经 [enqueueAgentRequest] 注入该会话（忙碌时排队、空闲直发），
     * 唤醒 Agent 执行提醒任务（DSH schedule fired）。会话首次进入请求流程时注册一次。
     */
    private fun registerScheduleListener(sessionId: String) {
        if (!registeredScheduleSessions.add(sessionId)) return
        scheduleScheduler.register(sessionId) { _, prompt ->
            val msg = context.getString(R.string.agent_schedule_fired_prefix) + prompt
            enqueueAgentRequest(
                request = msg,
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    override fun onCleared() {
        registeredScheduleSessions.forEach { scheduleScheduler.unregister(it) }
        registeredScheduleSessions.clear()
        super.onCleared()
    }

    /**
     * 会话级「当前专项 agent」（#1 会话内切换，design 8.5）。
     * null = 主 agent（默认）。内存态即可：重启后回退主 agent，不引入数据库迁移。
     */
    private val currentAgentIds = mutableMapOf<String, String?>()

    /**
     * AI 忙碌期间到达的后台任务完成事件缓冲区（按会话累积）。
     * 会话空闲时到达的事件不缓存、立即发送；忙碌期间到达的缓存下来，
     * 等该会话本轮结束（finally）时合并成一条通知发送，只触发一轮 AI 回复。
     */
    private val pendingMergedNotifications = mutableMapOf<String, MutableList<TabFinishedEvent>>()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /**
     * 旁路环境探测快照（key = 触发它的工具气泡 messageId）。
     * 内存 + SharedPreferences 双写：冷启动/进程被杀后能恢复气泡，避免重新进入 App 后消失。
     * 探测中状态不落盘；仅已完成结果持久化。
     */
    private val _environmentSnapshots = MutableStateFlow<Map<String, EnvironmentSnapshot>>(emptyMap())
    val environmentSnapshots: StateFlow<Map<String, EnvironmentSnapshot>> = _environmentSnapshots.asStateFlow()

    private val envSnapshotStore by lazy { EnvironmentSnapshotStore(context) }

    /** 旁路探测节流：记录每个触发消息最近一次探测时间（elapsedRealtime）。 */
    private val lastProbeAt = mutableMapOf<String, Long>()

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30

    private val _inputDraft = MutableStateFlow("")
    val inputDraft: StateFlow<String> = _inputDraft.asStateFlow()
    fun updateInputDraft(text: String) { _inputDraft.value = text }
    fun clearInputDraft() { _inputDraft.value = "" }

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
    }

    // 会话列表 = 当前工作台的已绑定会话 + 所有「未绑定工作台」的会话。
    // 工作台绑定在用户首条消息时自动发生（此前会话处于未绑定态），未绑定会话需始终可见。
    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getAll().map { list ->
                list.filter { it.workspacePath.isBlank() || it.workspacePath == path }
                    .map { it.toDomain() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentSessionMode: StateFlow<AgentMode> = combine(
        _currentSessionId, sessions
    ) { id, list ->
        list.find { it.id == id }?.mode ?: AgentMode.BUILD
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        combine(
            _currentSessionId, sessions
        ) { id, list ->
            list.find { it.id == id }?.reasoningEffort ?: ReasoningEffort.MEDIUM
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> = combine(
        _currentSessionId, sessions
    ) { id, list ->
        val s = list.find { it.id == id }
        (s?.providerId ?: "") to (s?.model ?: "")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null as String? to null as String?)

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    // 先拼接分块消息（chunk 行按 chunk_index 序合并为单条完整消息），再走既有过滤/映射。
                    messages = MessagePersistenceUseCase.mergeChunks(list).asSequence()
                        .filterNot { it.isContextSummary }
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))

    // 任务手风琴展开状态（按会话）：taskId -> 是否展开一级手风琴。
    private val _expandedTasks = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    // 二级片段展开状态（按会话）：subGroupId -> 是否展开。片段 id 唯一标识时间顺序中的一段连续同类型消息。
    private val _expandedSubGroups = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    // 当前正在流式生成的任务（按会话）：sessionId -> taskId。用于任务手风琴头部脉冲指示。
    private val _streamingTaskBySession = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 任务分组列表：把扁平消息列表按 taskId 分组为一级手风琴（任务），
     * 组内按时间顺序扫描、仅合并「连续同类型」消息为二级片段（用户消息 / 助手回复 / 工具调用）。
     * 历史消息（taskId 为空串）归入顶部「历史对话」组，默认折叠以节省空间。
     */
    val taskGroups: StateFlow<List<TaskGroup>> = combine(
        messagesState,
        _expandedTasks,
        _expandedSubGroups,
        _streamingTaskBySession
    ) { state, expandedTasks, expandedSubGroups, streamingTasks ->
        buildTaskGroups(
            state.messages,
            expandedTasks,
            expandedSubGroups,
            streamingTaskId = streamingTasks[state.sessionId]
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 切换一级任务手风琴的展开/折叠。 */
    fun toggleTask(taskId: String) {
        val current = _expandedTasks.value
        _expandedTasks.value = current + (taskId to !(current[taskId] ?: taskId.isNotBlank()))
    }

    /** 切换任务内二级片段手风琴的展开/折叠。 */
    fun toggleSubGroup(taskId: String, subGroupId: String) {
        val current = _expandedSubGroups.value
        _expandedSubGroups.value = current + (subGroupId to !(current[subGroupId] ?: true))
    }

    private fun buildTaskGroups(
        messages: List<AgentUIMessage>,
        expandedTasks: Map<String, Boolean>,
        expandedSubGroups: Map<String, Boolean>,
        streamingTaskId: String?
    ): List<TaskGroup> {
        val historical = messages.filter { it.taskId.isBlank() }
        val tasked = messages.filter { it.taskId.isNotBlank() }
        val result = mutableListOf<TaskGroup>()

        // 历史对话（升级前 / 斜杠命令 / 压缩锚点等无 taskId 的消息）：顶部扁平组，默认折叠。
        if (historical.isNotEmpty()) {
            result += TaskGroup(
                taskId = "",
                title = context.getString(R.string.chat_task_history),
                timestamp = historical.first().timestamp,
                subGroups = buildSubGroups("", historical, expandedSubGroups),
                isExpanded = expandedTasks[""] ?: false
            )
        }

        // 按 taskId 分组，保持首次出现顺序（时间序）。
        val taskOrder = tasked.map { it.taskId }.distinct()
        val grouped = tasked.groupBy { it.taskId }
        for (taskId in taskOrder) {
            val taskMessages = grouped[taskId] ?: continue
            val firstUser = taskMessages.firstOrNull { it.role == MessageRole.USER }
            val title = firstUser?.content?.let { deriveTaskTitle(it) } ?: context.getString(R.string.chat_task_untitled)
            val timestamp = firstUser?.timestamp ?: taskMessages.first().timestamp
            result += TaskGroup(
                taskId = taskId,
                title = title,
                timestamp = timestamp,
                subGroups = buildSubGroups(taskId, taskMessages, expandedSubGroups),
                isExpanded = expandedTasks[taskId] ?: true,
                isStreaming = taskId == streamingTaskId
            )
        }
        return result
    }

    /**
     * 按时间顺序把消息切分为「连续同类型」片段：仅合并相邻同类型消息，绝不跨类型重排，
     * 从而完整保留任务内真实的执行时间线（如：用户 → 思考 → 工具 → 思考 → 工具 → 回复）。
     * 片段 id = "$taskId-$seq"，跨重组稳定，用于维护二级手风琴展开状态。
     */
    private fun buildSubGroups(
        taskId: String,
        messages: List<AgentUIMessage>,
        expandedMap: Map<String, Boolean>
    ): List<TaskSubGroup> {
        val result = mutableListOf<TaskSubGroup>()
        var currentType: TaskSubGroupType? = null
        var currentMessages = mutableListOf<AgentUIMessage>()
        var seq = 0
        for (msg in messages) {
            val type = msg.subGroupType() ?: continue
            if (currentType != null && type != currentType) {
                val id = "$taskId-$seq"
                result += TaskSubGroup(
                    id = id,
                    type = currentType,
                    messages = currentMessages,
                    isExpanded = expandedMap[id] ?: true
                )
                seq++
                currentMessages = mutableListOf()
            }
            currentType = type
            currentMessages += msg
        }
        if (currentType != null && currentMessages.isNotEmpty()) {
            val id = "$taskId-$seq"
            result += TaskSubGroup(
                id = id,
                type = currentType,
                messages = currentMessages,
                isExpanded = expandedMap[id] ?: true
            )
        }
        return result
    }

    /** 消息归属的二级片段类型：ASSISTANT 消息（含内嵌思考）统一归 REPLY，保持 reasoning 与正文同处时间线。 */
    private fun AgentUIMessage.subGroupType(): TaskSubGroupType? = when {
        role == MessageRole.USER && !isBackgroundNotification -> TaskSubGroupType.USER
        role == MessageRole.ASSISTANT &&
            (reasoning?.hasVisibleContent() == true || content.hasVisibleContent()) -> TaskSubGroupType.REPLY
        role == MessageRole.TOOL -> TaskSubGroupType.TOOL
        else -> null
    }

    /** 从用户消息内容派生任务标题：取首个非空行，截断到 24 字符。 */
    private fun deriveTaskTitle(content: String): String {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (firstLine.length <= 24) firstLine else firstLine.take(24) + "…"
    }

    private val _changesMap = MutableStateFlow<Map<String, List<CodeChange>>>(emptyMap())
    val changes: StateFlow<List<CodeChange>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _changesMap.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun setChanges(sessionId: String, changes: List<CodeChange>) {
        _changesMap.value = if (changes.isEmpty()) _changesMap.value - sessionId else _changesMap.value + (sessionId to changes)
    }

    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 流式正文/思考的内存护栏：单条流式文本显示上限。模型输出超长内容（如一次性吐出
     * 超大 diff / 内嵌 base64）时，若不做截断，整段文本会同时存在 workflow 累加器、
     * 本 StateFlow 与 Compose 重组三份拷贝，长流式下内存持续攀升，极易触发 LMKD
     * 静默杀进程 → 「模型输出时突然闪退且无 Java 日志」。这里只截断**流式显示**，
     * 最终落库仍由 MessagePersistenceUseCase.MAX_CONTENT_CHARS（200k）兜底。
     */
    private fun capStreamingText(text: String, isReasoning: Boolean): String {
        val max = if (isReasoning) STREAMING_REASONING_MAX_CHARS else STREAMING_TEXT_MAX_CHARS
        if (text.length <= max) return text
        FileLogger.w(
            "AgentVM",
            "流式${if (isReasoning) "思考" else "正文"}超长已截断显示: len=${text.length} > $max（仅截断流式显示，落库走 MAX_CONTENT_CHARS 兜底）"
        )
        return text.take(max)
    }

    private fun setStreamingText(sessionId: String, text: String?) {
        val capped = text?.let { capStreamingText(it, isReasoning = false) }
        _streamingTexts.value = if (capped == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to capped)
        updateStreamingTask(sessionId)
    }

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        val capped = text?.let { capStreamingText(it, isReasoning = true) }
        _streamingReasonings.value = if (capped == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to capped)
        updateStreamingTask(sessionId)
    }

    /** 同步流式任务指示：会话正在流式（正文或思考）时标记当前 taskId，否则清除。 */
    private fun updateStreamingTask(sessionId: String) {
        val isStreaming = _streamingTexts.value[sessionId] != null || _streamingReasonings.value[sessionId] != null
        val taskId = currentTaskIdBySession[sessionId]
        _streamingTaskBySession.value = if (isStreaming && taskId != null) {
            _streamingTaskBySession.value + (sessionId to taskId)
        } else {
            _streamingTaskBySession.value - sessionId
        }
    }

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    // 只暴露「当前会话」的权限确认请求：避免对话已结束/切换会话后，旧会话残留的确认卡仍弹出。
    val pendingToolPermission = toolPermissionManager.pendingRequest
        .combine(_currentSessionId) { pending, currentSid ->
            if (pending?.sessionId == null || pending.sessionId == currentSid) pending else null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    // 当前请求的 taskId（按会话）：同一轮用户请求（executeAgentRequestStream）生成的所有消息
    // （用户消息 / 助手回复 / 工具调用 / 思考过程）都归入同一任务分组。
    private val currentTaskIdBySession = mutableMapOf<String, String>()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        return sessions.value.any { sessionJobs[it.id]?.isActive == true }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"

        /** 旁路探测节流窗口：同一触发消息 30s 内不重复探测。 */
        const val PROBE_THROTTLE_MS = 30_000L

        /** 流式正文单条显示上限（字符）：防御超长模型输出撑爆流式显示内存（落库另有 200k 兜底）。 */
        const val STREAMING_TEXT_MAX_CHARS = 400_000

        /** 流式思考单条显示上限（字符）。 */
        const val STREAMING_REASONING_MAX_CHARS = 100_000
    }

    init {
        viewModelScope.launch {
            // 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。
            // 这些工具不可能还在跑，统一回填为「已中断」。放在设置会话之前完成，使首帧不再闪转圈。
            sessionUseCase.initColdStartCleanup()

            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                // 切到某工作台：优先选中其最近会话；否则选中最近一条未绑定工作台的会话；
                // 都没有则置空（进入欢迎页），等用户新建/首条消息时再绑定，避免空会话堆积。
                val existing = sessionUseCase.getFirstSessionOfWorkspace(path)
                    ?: sessionUseCase.getFirstUnboundSession()
                _currentSessionId.value = existing?.id
            }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }
    }

    /**
     * 后台命令（notify=true）结束后的回调：触发 Agent 新一轮，以一条后台任务完成通知（user 消息）
     * 作为本轮输入。
     *
     * 用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走 enqueueAgentRequest 正常流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        // 按事件携带的来源会话路由，而非用户当前所在会话：后台命令可能在用户已切到别的会话后才结束。
        val sessionId = event.sourceSessionId ?: return
        // 该会话正忙（AI 正在工作）：缓存事件，等本轮结束后合并成一条发送，只触发一轮 AI 回复。
        if (sessionJobs[sessionId]?.isActive == true) {
            pendingMergedNotifications.getOrPut(sessionId) { mutableListOf() }.add(event)
            return
        }
        // 会话空闲：立即发送，保持及时响应
        val notification = buildBackgroundNotification(listOf(event))
        viewModelScope.launch {
            enqueueAgentRequest(
                request = notification,
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    /** 本轮结束后把忙碌期间缓存的后台任务完成通知合并成一条发送。 */
    private fun flushMergedNotifications(sessionId: String) {
        val events = pendingMergedNotifications.remove(sessionId) ?: return
        if (events.isEmpty()) return
        val notification = buildBackgroundNotification(events)
        viewModelScope.launch {
            enqueueAgentRequest(
                request = notification,
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    /** 构建后台任务完成通知文本；多条时合并为一条，含多个 <task-notification> 块。 */
    private fun buildBackgroundNotification(events: List<TabFinishedEvent>): String {
        if (events.size == 1) return buildBackgroundNotification(events.first())
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            appendLine("共有 ${events.size} 个后台任务已完成，这是合并后的通知。")
            appendLine("这些是后台任务完成事件，不是来自用户的消息。")
            appendLine("不要将它们视为用户的确认、同意或对任何待处理问题的回答。")
            appendLine()
            events.forEach { event ->
                val status = if (event.exitCode == 0) "completed" else "failed"
                appendLine("<task-notification>")
                appendLine("  <task-id>${event.tabId}</task-id>")
                appendLine("  <title>${event.title}</title>")
                appendLine("  <command>${event.command ?: ""}</command>")
                appendLine("  <exit-code>${event.exitCode}</exit-code>")
                appendLine("  <status>$status</status>")
                appendLine("  <summary>后台任务「${event.title}」已结束（退出码 ${event.exitCode}）</summary>")
                appendTailOutput(event)
                appendLine("</task-notification>")
                appendLine()
            }
            append("通知已携带各终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"...\") 读取对应任务。")
        }
    }

    /** 构建单条后台任务完成通知文本（与历史格式一致）。 */
    private fun buildBackgroundNotification(event: TabFinishedEvent): String {
        val status = if (event.exitCode == 0) "completed" else "failed"
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            appendLine("这是一条后台任务完成事件，不是来自用户的消息。")
            appendLine("不要将其视为用户的确认、同意或对任何待处理问题的回答。")
            appendLine()
            appendLine("<task-notification>")
            appendLine("  <task-id>${event.tabId}</task-id>")
            appendLine("  <title>${event.title}</title>")
            appendLine("  <command>${event.command ?: ""}</command>")
            appendLine("  <exit-code>${event.exitCode}</exit-code>")
            appendLine("  <status>$status</status>")
            appendLine("  <summary>后台任务「${event.title}」已结束（退出码 ${event.exitCode}）</summary>")
            appendTailOutput(event)
            appendLine("</task-notification>")
            appendLine()
            append("通知已携带该终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"${event.tabId}\") 读取。")
        }
    }

    /** 追加 <tail-output> 块；空白输出跳过。转义尖括号防止 <status>/<summary> 等字样污染提示条的正则提取。 */
    private fun StringBuilder.appendTailOutput(event: TabFinishedEvent) {
        event.tailOutput?.takeIf { it.isNotBlank() }?.let { tail ->
            appendLine("  <tail-output>${escapeXml(tail)}</tail-output>")
        }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    private fun processNextInQueue(sessionId: String) {
        // 进入下一条前，清理可能残留的权限确认请求，避免旧确认卡阻塞/残留。
        toolPermissionManager.cancelPending(sessionId)
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再执行 handler。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runSlashCommand(command: SlashCommandHandler, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                command.executeWithInput(this@AIAgentViewModel, input)
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        skipCommandDispatch: Boolean = false
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        // 定时提醒投递注册：会话首次进入请求流程即挂接，到点把提醒正文注入本会话。
        registerScheduleListener(sessionId)
        // 命令分流：完全相等匹配到斜杠命令时，不走 agent workflow，直接执行命令操作
        // （命令文本已作为用户消息落库，进入对话上下文）。不注册 sessionJobs，
        // 因此 isRunning 保持 false，/compress 等命令内部的自检可以正常工作。
        // skipCommandDispatch：声明式命令展开（sendAgentRequest）复用本方法时置位，
        // 跳过命令分流，避免展开正文再次命中命令导致递归。
        if (!skipCommandDispatch) {
            slashCommandRegistry.findExact(request)?.let { command ->
                runSlashCommand(command, request, sessionId)
                return@launch
            }
        }
        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        setAgentState(sessionId, AgentUIState.Streaming)

        try {
            var failed = false
            // 本轮请求的 taskId：该请求产出的所有消息归入同一任务分组（任务手风琴）。
            val taskId = UUID.randomUUID().toString()
            currentTaskIdBySession[sessionId] = taskId
            // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            val isFirst = history.isEmpty()

            if (!isAutoTrigger) {
                val userMsgId = UUID.randomUUID().toString()
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, request, id = userMsgId, taskId = taskId, attachments = inputAttachments)
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // 首条消息自动绑定当前工作台（仅未绑定会话生效；bindWorkspace 内部保证一次性绑定）
                    sessionUseCase.bindWorkspace(sessionId, projectRoot)
                }
            }
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                mode = mode,
                currentAgentId = currentAgentIds[sessionId],
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue
            )

            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = toolRegistry.getAvailableTools(mode)
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        setRetryState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.Retrying -> {
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries))
                    }
                    is AgentEvent.CompactionStarted -> {
                        setRetryState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                    }
                    is AgentEvent.AssistantText -> {
                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            taskId = taskId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            viewModelScope.launch {
                                runCatching {
                                    chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                    if (event.inputTokens > 0) {
                                        chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                    }
                                }
                            }
                        }
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            taskId = taskId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        val toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId]
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            taskId = taskId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = toolArgs,
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        // 联动检测：构建/环境变更命令执行完成后，旁路探测环境（不落库、不进上下文），
                        // 供触发它的 Bash 工具气泡底部渲染环境状态条。
                        // 开放推断：从命令本身提取所需构建环境（如 php、python3、node），不写死特定环境。
                        if (!event.isError && (event.toolName == "Bash" || event.toolName == "execute_command")) {
                            val command = extractCommandFromArgs(toolArgs)
                            val inferred = CheckEnvironmentTool.inferComponentsFromCommand(command)
                            when (BuildCommandClassifier.classify(command)) {
                                BuildCommandClassifier.CommandClass.BUILD ->
                                    // 构建命令：优先探测命令推断出的组件；推断不出时回退到构建核心组件
                                    probeEnvironment(taskId = taskId, triggerMsgId = msgId, components = inferred.ifEmpty { CheckEnvironmentTool.BUILD_CORE_COMPONENTS })
                                BuildCommandClassifier.CommandClass.ENV_MUTATION ->
                                    // 环境变更命令：仅探测能明确从命令推断出的组件；推断不出时不做无意义全量探测
                                    probeEnvironment(taskId = taskId, triggerMsgId = msgId, components = inferred.ifEmpty { emptyList() })
                                BuildCommandClassifier.CommandClass.OTHER ->
                                    // 其他命令：只要推断出所需构建环境就探测（开放检测，不写死）
                                    if (inferred.isNotEmpty()) {
                                        probeEnvironment(taskId = taskId, triggerMsgId = msgId, components = inferred)
                                    }
                            }
                        }
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(event.error))
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setCompacting(sessionId, false)
                    }
                    is AgentEvent.ModeChanged -> {
                        // 模式切换事件：PlanApprovalManager 已在 workflow 层面挂起等待用户批准
                        // 这里只更新 streamingText 显示
                    }
                    is AgentEvent.GoalChanged -> {
                        // 会话任务目标变更：与消息同日志，落库为工具卡片，让用户「看见」目标被设定/更新/完成。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        val msgId = "goal_${event.goalId}_${System.nanoTime()}"
                        val statusLabel = when (event.status) {
                            "done" -> context.getString(R.string.agent_goal_status_done)
                            "abandoned" -> context.getString(R.string.agent_goal_status_abandoned)
                            else -> context.getString(R.string.agent_goal_status_active)
                        }
                        val summary = if (event.text.isBlank()) statusLabel
                        else "${context.getString(R.string.agent_goal_status_active)}：${event.text}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            summary,
                            id = msgId,
                            taskId = taskId,
                            toolName = "goal",
                            toolArgs = "[$statusLabel]",
                            isError = false
                        )
                    }
                    is AgentEvent.AutoTriggered -> {
                        // 自动触发技能已完成：落库为工具卡片，让用户「看见」自动触发的实际效果（含失败也展示）。
                        // 技能输出已随首轮模型上下文以【系统·自动触发技能…】注入，这里仅用于 UI 渲染，不重复进模型上下文。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        val msgId = "skill_auto_${System.nanoTime()}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.output,
                            id = msgId,
                            taskId = taskId,
                            toolName = event.skillName,
                            toolArgs = "[auto_trigger]",
                            isError = event.isError
                        )
                    }
                    is AgentEvent.ScheduleFired -> {
                        // 定时提醒到点由调度循环经 enqueueAgentRequest 直接注入会话（不走 workflow 事件流），
                        // 本分支仅作穷举兜底（workflow 不产出该事件）。
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            if (!failed) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            setAgentState(sessionId, AgentUIState.Idle)
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
        } finally {
            if (sessionJobs[sessionId] == coroutineContext[Job]) {
                sessionJobs.remove(sessionId)
            }
            currentTaskIdBySession.remove(sessionId)
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)

            // 忙碌期间缓存的后台任务完成通知：本轮结束且 job 已移除后，合并成一条发送
            flushMergedNotifications(sessionId)

            // 正常完成时先回到 Idle，再处理队列；队列若有下一轮会重新设 Streaming
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /**
     * 手动刷新环境探测：重新执行 check_environment 并落库为 TOOL 消息，
     * 使环境总览卡片立即展示最新状态（Room Flow 自动触发 UI 刷新）。
     * 仅当容器已就绪时执行；失败时静默跳过（不打扰用户）。
     *
     * [taskId] 指定新探测消息归属的任务分组；缺省时取当前会话的 taskId。
     */
    fun refreshEnvironment(taskId: String? = null, components: List<String>? = null) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            if (!containerEngine.isProvisioned()) return@launch
            val args = if (components.isNullOrEmpty()) emptyMap() else mapOf(
                "components" to kotlinx.serialization.json.JsonArray(components.map { kotlinx.serialization.json.JsonPrimitive(it) })
            )
            val result = runCatching { checkEnvironmentTool.execute(args) }.getOrNull()
            if (result is ToolResult.Success) {
                messagePersistenceUseCase.persist(
                    sid,
                    MessageRole.TOOL,
                    result.toTransportString(),
                    taskId = taskId ?: currentTaskIdBySession[sid] ?: "",
                    toolName = "check_environment",
                    toolArgs = null,
                    isError = false
                )
            }
        }
    }

    /**
     * 旁路环境探测：构建/环境变更命令执行完成后自动触发，结果写入 [environmentSnapshots]，
     * 供触发它的 Bash 工具气泡底部渲染环境状态条。
     *
     * 与 [refreshEnvironment] 的区别：**不落库、不进模型上下文**，避免污染 LLM 上下文。
     * 带节流：同一触发消息 [triggerMsgId] 在 [PROBE_THROTTLE_MS] 内不重复探测。
     * 仅当容器已就绪时执行；失败时静默跳过（不打扰用户）。
     */
    private fun probeEnvironment(taskId: String, triggerMsgId: String, components: List<String>?) {
        val sid = _currentSessionId.value ?: return
        // 关键修复：明确传入空集合时不做任何探测，避免空组件集合 → 触发默认全量探测
        if (components != null && components.isEmpty()) {
            _environmentSnapshots.value = _environmentSnapshots.value - triggerMsgId
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastProbeAt[triggerMsgId] ?: 0L
        if (now - last < PROBE_THROTTLE_MS) return
        lastProbeAt[triggerMsgId] = now
        // 先标记「探测中」，让状态条立即显示转圈
        _environmentSnapshots.value = _environmentSnapshots.value + (
            triggerMsgId to EnvironmentSnapshot(key = triggerMsgId, components = emptyList(), probedAt = now, probing = true)
        )
        viewModelScope.launch {
            if (!containerEngine.isProvisioned()) {
                _environmentSnapshots.value = _environmentSnapshots.value - triggerMsgId
                return@launch
            }
            // components==null 走默认组件探测（项目栈启发式）；components 非空且非空列表 → 精准探测指定组件
            val args = if (components.isNullOrEmpty()) emptyMap() else mapOf(
                "components" to kotlinx.serialization.json.JsonArray(components.map { kotlinx.serialization.json.JsonPrimitive(it) })
            )
            val result = runCatching { checkEnvironmentTool.execute(args) }.getOrNull()
            val snapshot = if (result is ToolResult.Success) {
                EnvironmentSnapshot(
                    key = triggerMsgId,
                    components = parseEnvironmentComponents(result.toTransportString()),
                    probedAt = android.os.SystemClock.elapsedRealtime(),
                    probing = false
                )
            } else {
                // 探测失败：移除占位，避免残留「探测中」状态
                _environmentSnapshots.value = _environmentSnapshots.value - triggerMsgId
                return@launch
            }
            _environmentSnapshots.value = _environmentSnapshots.value + (triggerMsgId to snapshot)
            // 探测完成后持久化到磁盘，冷启动可恢复
            val sid = _currentSessionId.value
            if (sid != null) {
                envSnapshotStore.save(sid, _environmentSnapshots.value)
            }
        }
    }

    /** 从工具参数 JSON 预览中提取 command 字段。 */
    private fun extractCommandFromArgs(argsPreview: String?): String {
        if (argsPreview.isNullOrBlank()) return ""
        return runCatching {
            Json.parseToJsonElement(argsPreview).jsonObject["command"]?.jsonPrimitive?.contentOrNull ?: ""
        }.getOrDefault("")
    }

    /**
     * 主动打断正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        // 全停时清理任意会话的残留权限确认请求（传 null = 任意会话）。
        toolPermissionManager.cancelPending(null)
        pendingMergedNotifications.clear()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        currentTaskIdBySession.clear()
    }

    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        // 同步捕获本轮 taskId：job.cancel() 后 finally 会清理 map，这里先取走避免竞态。
        val stoppedTaskId = currentTaskIdBySession[sessionId] ?: ""
        job.cancel()
        // 停止会话时清理其残留的权限确认请求，避免确认卡继续弹出。
        toolPermissionManager.cancelPending(sessionId)
        pendingMergedNotifications.remove(sessionId)
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (runningTools.isNotEmpty()) {
                // 并行执行被中止：所有未完成的工具都落库为「已停止」
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        taskId = stoppedTaskId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()) {
                val partial = (streamingText ?: "").trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    taskId = stoppedTaskId,
                    reasoning = reasoning
                )
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // 点「停止」= 跳过当前轮，立即执行队列下一条
            processNextInQueue(sessionId)
        }
    }

    // region 会话管理

    /** 新建会话；若当前会话还是空的则直接复用，避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            setChanges(curId, emptyList())
            return@launch
        }
        val s = createSession()
        sessionUseCase.upsertSession(s)
        _currentSessionId.value = s.id
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
        // 切换会话时从磁盘恢复环境快照（冷启动/进程被杀后重建气泡）
        _environmentSnapshots.value = envSnapshotStore.load(id)
        lastProbeAt.clear()
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 暴露给 UI：输入框下拉菜单展示的命令列表。 */
    val slashCommands: List<SlashCommandHandler> get() = slashCommandRegistry.all

    /** /status —— 以 Markdown 表格作为 AI 气泡输出当前会话状态。 */
    override fun showSessionStatus() {
        val sid = _currentSessionId.value ?: return
        val session = sessions.value.find { it.id == sid } ?: return
        val msgCount = messagesState.value.messages.size
        val model = session.model ?: sessionProviderModelDisplay(sid)
        val table = buildString {
            appendLine("| 项目 | 值 |")
            appendLine("|---|---|")
            appendLine("| 会话 | ${escapeMd(session.title)} |")
            appendLine("| 模型 | ${escapeMd(model)} |")
            appendLine("| 模式 | ${session.mode.name} |")
            appendLine("| 工作区 | ${escapeMd(session.workspacePath)} |")
            appendLine("| 消息数 | $msgCount |")
            appendLine("| 输入 tokens | ${formatTokenCount(session.totalInputTokens)} |")
            appendLine("| 输出 tokens | ${formatTokenCount(session.totalOutputTokens)} |")
        }
        viewModelScope.launch {
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isCompacted = true)
        }
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> setCompacting(sid, false)
                        else -> {}
                    }
                }
                val resultText = if (changed) context.getString(R.string.agent_context_compacted) else context.getString(R.string.agent_context_no_compaction)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = resultText
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // 压缩是异步流程，结束后接续队列中排队的下一条
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    /** /agent（无参）—— 列出可切换的专项 Agent（结果以 AI 气泡输出）。 */
    override fun listAgents() {
        val sid = _currentSessionId.value ?: return
        val agents = agentAssetRegistry.agents()
        val headerAgent = context.getString(R.string.agent_list_header_col_agent)
        val headerDesc = context.getString(R.string.agent_list_header_col_desc)
        val table = buildString {
            appendLine("| $headerAgent | $headerDesc |")
            appendLine("|---|---|")
            if (agents.isEmpty()) {
                appendLine(context.getString(R.string.agent_list_empty))
            } else {
                agents.forEach { a ->
                    appendLine("| ${escapeMd(a.name)} | ${escapeMd(a.description.ifBlank { context.getString(R.string.agent_list_none_desc) })} |")
                }
            }
            appendLine("")
            appendLine(context.getString(
                R.string.agent_list_current,
                currentAgentIds[sid] ?: context.getString(R.string.agent_list_current_main)
            ))
            appendLine(context.getString(R.string.agent_list_usage))
        }
        viewModelScope.launch {
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isCompacted = true)
        }
    }

    /** /agent <name> —— 切换专项 Agent；tools/model 仅建议（不切换 provider、不拦截工具）。 */
    override fun switchAgent(name: String) {
        val sid = _currentSessionId.value ?: return
        val asset = agentAssetRegistry.findByName(name)
        viewModelScope.launch {
            val msg = when {
                asset == null || !asset.agent || !asset.enabled ->
                    context.getString(R.string.agent_switch_not_found, name)
                currentAgentIds[sid] == name -> {
                    currentAgentIds.remove(sid)
                    context.getString(R.string.agent_switch_reset)
                }
                else -> {
                    currentAgentIds[sid] = name
                    context.getString(R.string.agent_switch_ok, asset.name, asset.description)
                }
            }
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, msg, isCompacted = true)
        }
    }

    /**
     * 声明式命令展开（方向 B1）：把渲染后的正文作为用户消息送入 workflow。
     * 复用 [executeAgentRequestStream] 完整链路（落库/checkpoint/历史/事件流），
     * skipCommandDispatch 置位防止展开正文再次命中命令分流导致递归。
     * 原始命令文本（`/name args`）已由 [runSlashCommand] 落库，故对话同时保留命令与展开正文。
     */
    override fun sendAgentRequest(text: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = text,
            modelRequest = text,
            targetSessionId = sid,
            skipCommandDispatch = true
        )
    }

    private fun sessionProviderModelDisplay(sid: String): String {
        val pair = currentSessionProviderModel.value ?: return context.getString(R.string.agent_model_not_selected)
        val (_, model) = pair
        return model?.takeIf { it.isNotBlank() } ?: context.getString(R.string.agent_model_not_selected)
    }

    private fun escapeMd(text: String): String = text.replace("|", "\\|").replace("\n", " ")


    /** 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。 */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。 */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
        // 切换会话时从磁盘恢复环境快照（冷启动/进程被杀后重建气泡）
        _environmentSnapshots.value = envSnapshotStore.load(id)
        lastProbeAt.clear()
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        checkpointManager.clearSessionCheckpoints(id)
        sessionUseCase.deleteSession(id)
        // 删除会话时同步清理环境快照持久化
        envSnapshotStore.clear(id)

        sessionJobs[id]?.cancel()
        sessionJobs.remove(id)
        _agentStates.value = _agentStates.value - id
        _streamingTexts.value = _streamingTexts.value - id
        _streamingReasonings.value = _streamingReasonings.value - id
        _runningTools.value = _runningTools.value - id
        _retryStates.value = _retryStates.value - id
        _changesMap.value = _changesMap.value - id
        _queuedRequests.value = _queuedRequests.value - id

        if (_currentSessionId.value == id) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                // 删除后选中该工作台最近会话；否则选最近未绑定会话；都没有则置空。
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                    ?: sessionUseCase.getFirstUnboundSession()
                _currentSessionId.value = remaining?.id
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /**
     * 手动绑定当前会话到指定工作台（「更多配置 → 工作台绑定」）。
     * 绑定即一次性的：仅未绑定会话生效，已绑定会话忽略（bindWorkspace 内部保证）。
     */
    fun bindSessionWorkspace(workspacePath: String) = viewModelScope.launch {
        val sid = _currentSessionId.value ?: return@launch
        sessionUseCase.bindWorkspace(sid, workspacePath)
    }

    /**
     * 查询某个工作区绑定的所有会话（按更新时间倒序），供侧边栏「所有工作台 → 查看对话绑定」展示。
     * 会话与工作区一对一绑定、不可中途切换；一个工作区可绑定多个会话。
     */
    suspend fun sessionsBoundToWorkspace(workspacePath: String): List<ChatSession> =
        chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath).map { it.toDomain() }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val existing = sessionUseCase.getFirstSessionOfWorkspace(ws)
        val id = if (existing != null) existing.id else {
            val s = createSession()
            sessionUseCase.upsertSession(s)
            s.id
        }
        _currentSessionId.value = id
        return id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     *
     * 新会话初始**不绑定工作台**（workspacePath 为空）：工作台绑定发生在用户发第一条消息时自动绑定，
     * 或由用户在「更多配置 → 工作台绑定」手动绑定。
     */
    private suspend fun createSession(): ChatSessionEntity {
        val s = sessionUseCase.newSessionEntity("")
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        if (providerId.isNotBlank() && model.isNotBlank()) {
            sessionUseCase.updateProviderModel(s.id, providerId, model)
        }
        return s
    }

    // endregion

    fun applyChanges(changes: List<CodeChange>) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                for (change in changes) {
                    when (change.type) {
                        ChangeType.CREATE -> {
                            val file = File(change.filePath)
                            file.parentFile?.mkdirs()
                            file.writeText(change.newCode)
                        }

                        ChangeType.REPLACE -> {
                            val file = File(change.filePath)
                            val lines = file.readLines().toMutableList()
                            val start = (change.startLine - 1).coerceIn(0, lines.size)
                            val end = (change.endLine - 1).coerceIn(0, lines.size - 1)

                            if (start <= end && start < lines.size) {
                                repeat(end - start + 1) {
                                    if (start < lines.size) lines.removeAt(start)
                                }
                                change.newCode.lines().reversed().forEach { line ->
                                    lines.add(start, line)
                                }
                                file.writeText(lines.joinToString("\n"))
                            }
                        }

                        ChangeType.INSERT -> {
                            val file = File(change.filePath)
                            val lines = file.readLines().toMutableList()
                            val insertLine = (change.startLine - 1).coerceIn(0, lines.size)
                            change.newCode.lines().reversed().forEach { line ->
                                lines.add(insertLine, line)
                            }
                            file.writeText(lines.joinToString("\n"))
                        }

                        ChangeType.DELETE -> {
                            val file = File(change.filePath)
                            val lines = file.readLines().toMutableList()
                            val start = (change.startLine - 1).coerceIn(0, lines.size)
                            val end = (change.endLine - 1).coerceIn(0, lines.size - 1)

                            for (i in end downTo start) {
                                if (i < lines.size) lines.removeAt(i)
                            }
                            file.writeText(lines.joinToString("\n"))
                        }

                        else -> {}
                    }
                }
            }
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                setAgentState(sessionId, AgentUIState.Applied)
                setChanges(sessionId, emptyList())
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyChanges 失败", e)
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                setAgentState(sessionId, AgentUIState.Error(context.getString(R.string.agent_apply_changes_failed, e.message)))
            }
        }
    }

    fun rejectChanges() {
        val sessionId = _currentSessionId.value ?: return
        setAgentState(sessionId, AgentUIState.Idle)
        setChanges(sessionId, emptyList())
    }

    /**
     * 编辑并重发：保留原对话（该消息及其之后的消息标记为已截断，UI 仍可见、不再参与新一轮上下文），
     * 然后以新内容作为新一轮对话重新执行。
     * 语义：不删除已生成的回复与终端输出，截断后开启新一轮对话。
     */
    fun editAndResend(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId) ?: return@launch
            if (msg.role != MessageRole.USER.name) return@launch
            // 1) 保留原对话：把该消息及其之后的所有消息标记为已截断（isCompacted=1），
            //    不删除，UI 仍可见；仅不再参与新一轮上下文回放。
            agentMessageDao.markMessagesCompactedInclusiveFromTimestamp(msg.sessionId, msg.timestamp)
            // 2) 以新内容作为新一轮对话重新执行（enqueueAgentRequest 会插入新的用户消息并开启新任务分组）
            enqueueAgentRequest(
                request = newContent,
                modelRequest = newContent,
                projectRoot = _currentWorkspace.value,
                targetSessionId = msg.sessionId
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "编辑并重发失败", e)
        }
    }

    /**
     * 创建新聊天并发送：新建一个会话，然后自动发送该消息内容。
     * 用于用户消息的「创建新聊天」快捷操作。
     */
    fun newChatAndSend(content: String) = viewModelScope.launch {
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return@launch
        val s = createSession()
        sessionUseCase.upsertSession(s)
        _currentSessionId.value = s.id
        enqueueAgentRequest(
            request = content,
            modelRequest = content,
            projectRoot = ws,
            targetSessionId = s.id
        )
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
