package com.R.codecore.feature.agent.presentation.component

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.tool.question.UserQuestionAnswer
import com.R.codecore.feature.agent.presentation.AgentUIMessage
import com.R.codecore.feature.agent.presentation.AgentUIState
import com.R.codecore.feature.agent.presentation.AIAgentViewModel
import com.R.codecore.feature.agent.presentation.ConversationSkillsViewModel
import com.R.codecore.feature.agent.presentation.MessageRole
import com.R.codecore.feature.agent.presentation.hasVisibleContent
import com.R.codecore.feature.chatrender.BubbleStyle
import com.R.codecore.feature.chatrender.LocalBubbleStyle
import com.R.codecore.feature.settings.presentation.SettingsViewModel
import com.R.codecore.feature.workspace.presentation.WorkspaceViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal val brandGradient = Brush.linearGradient(listOf(Brand.Blue, Brand.Sky))

/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, NONE }

private data class AutoScrollSignal(
    val streamingTextLength: Int,
    val streamingReasoningLength: Int,
    val runningToolMessageId: String?,
    val runningToolTextLength: Int,
    val isCompacting: Boolean,
    val messageCount: Int,
    val thinkingTail: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    drawerState: DrawerState,
    currentFile: String? = null,
    selectedCode: String? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val taskGroups by viewModel.taskGroups.collectAsStateWithLifecycle()
    val changes by viewModel.changes.collectAsStateWithLifecycle()

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSession = sessions.find { it.id == currentSessionId }
    val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_session_btn)
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val environmentSnapshots by viewModel.environmentSnapshots.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()

    val globalActiveProvider = settingsViewModel?.activeProvider?.collectAsStateWithLifecycle()?.value
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // 与 workflow.resolveProviderConfig 保持一致：绑定 provider 须启用且已填 apiKey，否则回退全局
            providers.find { it.id == boundProviderId }?.takeIf { it.apiKey.isNotBlank() }?.let {
                if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
            } ?: globalActiveProvider
        } else {
            globalActiveProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    LaunchedEffect(inputDraft) {
        if (inputText != inputDraft) inputText = inputDraft
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    // 编辑态：正在编辑的用户消息 id。发送时若非空则走「截断重发」而非普通发送。
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var fileDiffsForSheet by remember { mutableStateOf<TaskChangesSheetData?>(null) }
    // 对话技能面板（D5/D10）：输入栏「技能」按钮唤出，管理对话级添加/禁用。
    var showConversationSkills by remember { mutableStateOf(false) }
    val conversationSkillsViewModel: ConversationSkillsViewModel = hiltViewModel()
    val listState = rememberLazyListState()
    val markdownCache = remember { MarkdownRenderCache() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = modelMetadata[activeModel]
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.type, listOf(activeModel))
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    /** 编辑用户消息：把内容填入输入框，让用户修改后发送（发送时截断该消息之后的对话）。 */
    fun startEditMessage(message: AgentUIMessage) {
        if (message.role != MessageRole.USER) return
        editingMessageId = message.id
        inputText = message.content
        viewModel.updateInputDraft(message.content)
        focusManager.clearFocus()
    }

    /** 取消编辑态：清空编辑标记与输入框草稿。 */
    fun cancelEditMessage() {
        editingMessageId = null
        inputText = ""
        viewModel.clearInputDraft()
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            selected.forEach { uri ->
                runCatching {
                    copyUriToWorkspace(context, uri, projectRoot, includeImageData = images)
                }.onSuccess { uploaded ->
                    pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                    successCount += 1
                }.onFailure { error ->
                    failures += (error.message ?: uploadFallbackError(context))
                }
            }

            when {
                successCount > 0 && failures.isEmpty() && uris.size <= remainingAttachmentSlots(pendingAttachments.size - successCount) ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
                successCount > 0 ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // 拍照：输出到 cache 临时文件（FileProvider 授权 uri），拍完按图片附件处理。
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, unreadableFileMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    // 自动滚动跟随
    var positionedSession by remember { mutableStateOf<String?>(null) }
    var followBottom by remember { mutableStateOf(true) }

    val isAtBottom by remember {
        derivedStateOf {
            if (!listState.canScrollForward) return@derivedStateOf true
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            val viewportBottom = layout.viewportEndOffset
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= viewportBottom + 4
        }
    }

    val autoScrollSignal by rememberUpdatedState(
        AutoScrollSignal(
            streamingTextLength = streamingText?.length ?: 0,
            streamingReasoningLength = streamingReasoning?.length ?: 0,
            runningToolMessageId = runningTool.firstOrNull()?.messageId,
            runningToolTextLength = runningTool.firstOrNull()?.text?.length ?: 0,
            isCompacting = isCompacting,
            messageCount = messages.size,
            // 思考阶段：__reasoning__ 气泡存在且无正文流式时，尾部 __active__ 是空 Box。
            thinkingTail = streamingReasoning?.isNotEmpty() == true && streamingText?.hasVisibleContent() != true
        )
    )

    // 用户开始拖拽：停止跟随。松手时若已到底则恢复跟随（旧逻辑）。
    // 额外：流式输出时内容持续增长，用户可能松手后又被「顶」离底部——
    // 用 snapshotFlow { isAtBottom } 持续监测，只要滑到底部就恢复跟随，
    // 满足「流式中滚到底部自动继续跟随」。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> followBottom = isAtBottom
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    fun lastItemBottomOffset(index: Int): Int {
        val layout = listState.layoutInfo
        val viewportH = layout.viewportEndOffset - layout.viewportStartOffset
        val size = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
        return size - viewportH
    }

    suspend fun ensureLastItemMeasured(index: Int) {
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
            withFrameNanos { }
        }
    }

    // 贴底跟随：与正文流式一致，用 animateScrollToItem 平滑滚动，collectLatest 自动取消
    // 上一个未跑完的动画、新动画从当前位置平滑接管，视觉连续。
    // 末条流式消息可能高于一屏，必须滚到末项底部；只 animateScrollToItem(lastIndex)
    // 会把末项顶部对齐到视口顶部。
    val snapToBottom: suspend (Int) -> Unit = { index ->
        if (index >= 0) {
            ensureLastItemMeasured(index)
            listState.animateScrollToItem(index, lastItemBottomOffset(index))
        }
    }

    val sendMessage: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val editingId = editingMessageId
            val attachments = pendingAttachments
            val modelRequest = appendAttachmentsToRequest(context, text, attachments)
            // 判断是否把 pendingAttachments 中的图片作为 vision 输入传给模型。
            // 规则（多层防线，避免误杀用户配置的自定义多模态模型）：
            //   - metadata == null：还没解析过，信任用户 → 发图片；
            //   - metadata.supportsVision=true → 发；
            //   - metadata.source=INFERRED：模型不在 models.dev catalog 内（自建/兼容端点/新模型），
            //     信任用户的意图 → 发图片；即便真的是文本模型 API 报 400，错误也会正常显示，
            //     远比「静默把图片置空，模型回复文本，用户以为模型不识字」要好。
            //   - 仅当 metadata 明确来自 MODELS_DEV 且 supportsVision=false（收录的纯文本模型）→ 不发
            val sendImages = when (val m = activeModelMetadata) {
                null -> true
                else -> m.supportsVision || m.source == com.R.codecore.feature.settings.domain.model.ModelMetadata.Source.INFERRED
            }
            val images = if (sendImages) attachments.toAgentImages() else emptyList()
            if (editingId != null) {
                // 编辑重发：截断该消息之后的对话，以新内容重新执行（上下文干净）
                viewModel.editAndResend(editingId, text)
            } else {
                // 统一走队列：AI 忙时入队（等本轮结束后自动发送下一条），空闲时直接发送。
                // 斜杠命令在 ViewModel 内（agent workflow 之前）分流执行，无需在此区分。
                viewModel.enqueueAgentRequest(
                    request = text,
                    modelRequest = modelRequest,
                    currentFile = currentFile,
                    selectedCode = selectedCode,
                    projectRoot = projectRoot,
                    inputImages = images,
                    inputAttachments = attachments.toAgentAttachments()
                )
            }
            editingMessageId = null
            inputText = ""
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom(listState.layoutInfo.totalItemsCount - 1)
            }
        }
    }

    // 切换会话：把列表定位到最新一条，并恢复跟随。
    LaunchedEffect(currentSessionId, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        val target = messages.size - 1
        if (target < 0) {
            positionedSession = currentSessionId
            followBottom = true
            return@LaunchedEffect
        }
        if (positionedSession != currentSessionId) {
            snapToBottom(listState.layoutInfo.totalItemsCount - 1)
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // 流式贴底跟随（聊天标准做法）。
    // 监听 (流式文本长度/思考长度, 消息条数) 元组：每个吐字 delta（length 变）和每次落库
    // （size 变）都触发一次瞬时贴底（scrollToItem）。思考与正文都按字符粒度触发，
    // 内容增长多少立即滚多少，气泡始终贴底，无动画滞后与周期感。
    // 注意：不能用 distinctUntilChanged() 包布尔谓词，只触发一次后去重，不再跟随（旧根因）。
    // 注意：不能删 __active__ item（让 totalItemsCount 突减），anchor clamp 上跳（旧根因）。
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { autoScrollSignal }.collectLatest { signal ->
            if (!followBottom) return@collectLatest
            // 等一帧让新文本/新落库消息完成测量，scrollToItem 读到正确布局。
            kotlinx.coroutines.delay(0)
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            // 思考阶段：尾部 __active__ 是空 Box，跟随目标改为思考内容（倒数第二），
            // 让持续增长的思考文本始终贴底可见，避免跟随被顶出视口的空 Box 反复预跳抖动。
            val target = if (lastIndex > 0 && signal.thinkingTail) lastIndex - 1 else lastIndex
            snapToBottom(target)
        }
    }

    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, messagesReady, messagesState.hasMore, messagesState.isLoadingMore) {
        if (messagesReady && firstVisibleItemIndex <= 3 && messagesState.hasMore && !messagesState.isLoadingMore) {
            viewModel.loadMoreMessages()
        }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.R.codecore.feature.settings.data.repository.ExecutionMode.REMOTE_SSH
    // 回复气泡款式：由设置页 DataStore 持久化，注入 CompositionLocal 全局生效（切换只影响渲染层）
    val bubbleStyle = settingsViewModel?.bubbleStyle?.collectAsStateWithLifecycle()?.value ?: BubbleStyle.DEFAULT

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatHeader(
                sessionTitle = sessionTitle,
                modelName = activeProvider?.effectiveModel,
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    scope.launch { drawerState.open() }
                },
                onNewChat = { viewModel.newSession() },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                onNavigateToBrowser = onNavigateToBrowser,
                connectionState = connectionState?.takeIf { isRemote }
            )
        }
    ) { padding ->
        CompositionLocalProvider(LocalBubbleStyle provides bubbleStyle) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (!messagesReady) {
                    // 远程模式连接未就绪时显示连接状态占位，避免空白或旧工作区记录闪烁
                    if (isRemote && connectionState != null && connectionState != com.R.codecore.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = Spacing.lg,
                            vertical = Spacing.xs
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        items(taskGroups, key = { it.taskId }, contentType = { "task" }) { group ->
                            TaskAccordion(
                                group = group,
                                markdownCache = markdownCache,
                                onToggleTask = { viewModel.toggleTask(it) },
                                onToggleSubGroup = { taskId, subGroupId -> viewModel.toggleSubGroup(taskId, subGroupId) },
                                onEditClick = { message -> startEditMessage(message) },
                                onNewChatClick = { message -> viewModel.newChatAndSend(message.content) },
                                onViewChanges = { fileDiffsForSheet = it },
                                runningTool = runningTool,
                                environmentSnapshots = environmentSnapshots
                            )
                            // 日志流细分割线：任务（回合）之间用极淡灰线分隔
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = Spacing.sm),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                thickness = 0.5.dp
                            )
                        }
                        val reasoning = streamingReasoning
                        val showReasoning = reasoning != null && reasoning.isNotEmpty()
                        if (showReasoning) {
                            item(key = "__reasoning__", contentType = "tail") {
                                // 流式实时：短文本默认展开边想边看，过长（超 REASONING_COLLAPSE_LINE_LIMIT）时由气泡内部自动折叠，不刷屏
                                ReasoningBubble(text = reasoning.orEmpty(), initiallyExpanded = true, cache = markdownCache)
                            }
                        }
                        val streaming = streamingText
                        val showStreaming = streaming != null && streaming.hasVisibleContent()
                        val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && runningTool.isEmpty() && pendingPermission == null && pendingQuestion == null
                        val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val tailKind = when {
                            showStreaming -> TailKind.STREAMING
                            isCompacting -> TailKind.COMPACTING
                            showRetrying -> TailKind.RETRYING
                            showThinking -> TailKind.THINKING
                            else -> TailKind.NONE
                        }
                        // 尾巴气泡：永远挂载 item，NONE 时为空 Box（0 高度）。
                        // 注意：不能按 tailKind 增删 item；流结束时 __active__ 移除会让 totalItemsCount
                        // 突减，LazyColumn 把 firstVisibleItemIndex 向下 clamp → 视口上跳（旧症状2根因）。
                        // 永远挂载则 item 数量稳定，只在 StreamingBubble 和空 Box 间切换，
                        // anchor 不会被 clamp。流结束落库后跟随 effect 会把新消息贴底。
                        item(key = "__active__", contentType = "tail") {
                            when (tailKind) {
                                TailKind.THINKING -> ThinkingBubble()
                                TailKind.STREAMING -> StreamingBubble(text = streaming ?: "")
                                TailKind.COMPACTING -> CompactionProgressBubble()
                                TailKind.RETRYING -> {
                                    val rs = retryState
                                    if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries) else Box(Modifier)
                                }
                                TailKind.NONE -> Box(Modifier)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = changes.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ChangePreviewPanel(
                    changes = changes,
                    onApply = { viewModel.applyChanges(changes) },
                    onReject = { viewModel.rejectChanges() }
                )
            }

            StatusBanner(state = agentState)

            AnimatedVisibility(
                visible = pendingPermission != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                pendingPermission?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) }
                    )
                }
            }

            AnimatedVisibility(
                visible = pendingQuestion != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                pendingQuestion?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) }
                    )
                }
            }

            val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
            AnimatedVisibility(
                visible = planApproval != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                planApproval?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() }
                    )
                }
            }

            // 编辑态提示条：显示正在编辑哪条消息，支持取消编辑
            editingMessageId?.let { editingId ->
                val editingMsg = messages.find { it.id == editingId }
                if (editingMsg != null) {
                    EditingMessageBanner(
                        snippet = editingMsg.content.take(80),
                        onCancel = { cancelEditMessage() }
                    )
                }
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it; viewModel.updateInputDraft(it) },
                onSend = sendMessage,
                onStop = { viewModel.stopAgent() },
                isBusy = isBusy,
                activeProvider = activeProvider,
                providers = providers,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                onNavigateToSettings = onNavigateToSettings,
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = { imagePicker.launch(arrayOf("image/*")) },
                onTakePhoto = ::takePhoto,
                slashCommands = viewModel.slashCommands,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.contextTokens ?: 0
                    if (contextLimit > 0) {
                        sessionLastInputTokens.toFloat() / contextLimit
                    } else 0f
                },
                onOpenSkills = { showConversationSkills = true }
            )

            val skillsSessionId = currentSessionId
            if (showConversationSkills && skillsSessionId != null) {
                ConversationSkillsSheet(
                    viewModel = conversationSkillsViewModel,
                    sessionId = skillsSessionId,
                    onDismiss = { showConversationSkills = false }
                )
            }

            fileDiffsForSheet?.let { sheetData ->
                FileDiffSheet(
                    fileDiffs = sheetData.fileDiffs,
                    logs = sheetData.logs,
                    onDismiss = { fileDiffsForSheet = null }
                )
            }
        }
        }
    }
}

/**
 * 编辑态提示条：展示正在编辑的消息摘要，提供取消编辑入口。
 * 出现在输入框上方，提示用户当前发送将「截断重发」。
 */
@Composable
private fun EditingMessageBanner(
    snippet: String,
    onCancel: () -> Unit
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.R.codecore.core.theme.Radius.md),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.chat_editing_banner, snippet),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.sm)
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_edit_cancel),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

