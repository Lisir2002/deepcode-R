package com.R.codecore.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.core.util.LogLevel
import com.R.codecore.feature.agent.domain.container.ConnectionState
import com.R.codecore.feature.agent.domain.container.ContainerArch
import com.R.codecore.feature.agent.domain.container.ContainerInstaller
import com.R.codecore.feature.agent.domain.container.ContainerProfile
import com.R.codecore.feature.agent.domain.container.RemoteSshConnection
import com.R.codecore.feature.agent.domain.container.RootfsSource
import com.R.codecore.feature.agent.domain.mcp.McpConfigRepository
import com.R.codecore.feature.agent.domain.mcp.McpManager
import com.R.codecore.feature.agent.domain.mcp.McpServerConfig
import com.R.codecore.feature.agent.domain.mcp.McpServerStatus
import com.R.codecore.feature.agent.domain.mcp.McpToolDescriptor
import com.R.codecore.feature.agent.domain.permission.PermissionRule
import com.R.codecore.feature.agent.domain.permission.PermissionRulesRepository
import com.R.codecore.feature.settings.data.remote.ModelApiService
import com.R.codecore.feature.settings.data.remote.ModelMetadataService
import com.R.codecore.feature.settings.data.remote.ModelTestResult
import com.R.codecore.feature.settings.data.repository.AppThemeMode
import com.R.codecore.feature.settings.data.repository.CompatibilityPolicyRepository
import com.R.codecore.feature.settings.data.repository.DefaultPolicy
import com.R.codecore.feature.settings.data.repository.ViewImageUnknownGuardPolicy
import com.R.codecore.feature.settings.data.repository.ContainerSettingsRepository
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.data.repository.CompactionModelSettingsRepository
import com.R.codecore.feature.settings.data.repository.ExecutionModeHolder
import com.R.codecore.feature.settings.data.repository.ExecutionModeRepository
import com.R.codecore.feature.settings.data.repository.KeepaliveSettingsRepository
import com.R.codecore.feature.settings.data.repository.LanguageSettingsRepository
import com.R.codecore.core.util.LogLineParser
import com.R.codecore.feature.settings.data.repository.LogFilterSettingsRepository
import com.R.codecore.feature.settings.data.repository.LogSettingsRepository
import com.R.codecore.feature.settings.data.repository.ThemeSettingsRepository
import com.R.codecore.feature.settings.data.repository.VisionModelSettingsRepository
import com.R.codecore.feature.workspace.domain.model.RemoteConnection
import com.R.codecore.feature.workspace.domain.repository.RemoteAuditLogRepository
import com.R.codecore.feature.workspace.domain.repository.RemoteRepository
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.settings.domain.model.ModelMetadata
import com.R.codecore.feature.settings.domain.model.ProviderType
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.R.codecore.feature.settings.presentation.component.SettingsSection

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>) : FetchState()
    data class Error(val message: String) : FetchState()
}

data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val content: String = "",
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,

    // 筛选面板：默认收起，只通过点击筛选图标打开，通过 X 关闭
    val filterPanelExpanded: Boolean = false,
    val selectedDates: Set<String> = emptySet(),
    val selectedLevels: Set<LogLevel> = emptySet(),
    val selectedTags: Set<String> = emptySet(),
    val allAvailableTags: List<String> = emptyList(),
    val dateRangeMode: Boolean = false,
    val dateRangeStart: String? = null,
    val dateRangeEnd: String? = null,

    // 搜索
    val searchQuery: String = "",

    // 实时尾随
    val liveTailEnabled: Boolean = false,
    val hasNewLogs: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
    private val logFilterSettingsRepository: LogFilterSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerInstaller: ContainerInstaller,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    val auditLogRepository: RemoteAuditLogRepository,
    /** RC63 备选方案③：兼容端点全局策略（STRICT/HEURISTIC/LAX/MANUAL + 自动降级 + viewImage 守卫）。 */
    private val compatibilityPolicyRepository: CompatibilityPolicyRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200
    }

    // RC62：跨屏幕设置页跳转的「待打开分区」信号。
    // - 背景：MainActivity 有两个地方会触发「跳转到 SSH 主机配置」：
    //     1) SettingsScreen 内部 onNavigateToSshHosts lambda（本就在 Settings 路由栈内）
    //     2) TerminalSettingsScreen 的 SSH 菜单项（从 terminal_settings 路由先 pop 回 settings，
    //        再把 SettingsScreen 内部的 section 切到 RemoteServers —— 但 SettingsScreen 内部
    //        section 是它自己的 remember mutableState，外部调不到）
    // - 解决方案：复用 Activity 级单例 SettingsViewModel（本来 MainActivity L388 就复用了），
    //   用 tick + lastRequestedSection 对比：外部 openSection(Section) 后 tick+1，
    //   SettingsScreen LaunchedEffect(pendingTick, consumedTick) 看到有新 tick 就跳 section 并标记 consumed。
    // - 为什么不用 SharedFlow replay=0：路由创建之前就发 openSection 的场景会丢事件，
    //   tick 对比是纯状态机，100% 不漏也不重入。
    private val _pendingOpenSectionTick = MutableStateFlow(0L)
    private val _lastConsumedSectionTick = MutableStateFlow(-1L)
    private val _lastRequestedSection: MutableStateFlow<SettingsSection?> = MutableStateFlow(null)
    val lastRequestedSection: StateFlow<SettingsSection?> = _lastRequestedSection.asStateFlow()
    val pendingOpenSectionTick: StateFlow<Long> = _pendingOpenSectionTick.asStateFlow()
    val lastConsumedSectionTick: StateFlow<Long> = _lastConsumedSectionTick.asStateFlow()

    /**
     * 请求 SettingsScreen 切到指定二级分区。
     * 通常用在：TerminalSettingsScreen 里点「管理 SSH 主机配置」时，需要 terminal_settings
     * 先 popBackStack 回 settings，然后 SettingsScreen 自己在进入时收到信号 → 内部 section = Section。
     */
    fun openSection(section: SettingsSection) {
        _lastRequestedSection.value = section
        _pendingOpenSectionTick.value = (_pendingOpenSectionTick.value + 1) and Long.MAX_VALUE
    }

    /** 消费一次 openSection 请求（由 SettingsScreen 调），避免 LaunchedEffect 重入时反复跳同一个 section。 */
    fun markPendingSectionConsumed(tick: Long) {
        _lastConsumedSectionTick.value = tick
    }

    // ── 缓存：所有已读入的行（供局部过滤使用，避免重复读文件） ──
    private var _cachedRawLines: List<String> = emptyList()

    // ── 筛选防抖触发器：每次筛选变化递增，外层 debounce 300ms 后消费 ──
    private val _filterTrigger = MutableStateFlow(0L)

    // ── 实时尾随文件观察器 ──
    private var _liveTailFileObserver: android.os.FileObserver? = null

    private val _providers = MutableStateFlow<List<AIProviderConfig>>(emptyList())
    val providers: StateFlow<List<AIProviderConfig>> = _providers.asStateFlow()

    private val _activeProvider = MutableStateFlow<AIProviderConfig?>(null)
    val activeProvider: StateFlow<AIProviderConfig?> = _activeProvider.asStateFlow()

    /** 识图专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _visionProviderId = MutableStateFlow("")
    val visionProviderId: StateFlow<String> = _visionProviderId.asStateFlow()

    private val _visionModel = MutableStateFlow("")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    /** 压缩专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _compactionProviderId = MutableStateFlow("")
    val compactionProviderId: StateFlow<String> = _compactionProviderId.asStateFlow()

    private val _compactionModel = MutableStateFlow("")
    val compactionModel: StateFlow<String> = _compactionModel.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    /** 用户选择的应用语言 tag（null 表示跟随系统）。 */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val mcpServers: StateFlow<List<McpServerConfig>> = _mcpServers.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    /** 内置供应商向导「选择模型」步骤的拉取状态（与编辑页 [_fetchState] 隔离，避免互相干扰）。 */
    private val _builtInFetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val builtInFetchState: StateFlow<FetchState> = _builtInFetchState.asStateFlow()

    /**
     * RC72：保存提供商时的错误提示通道（一次性，消费后自动清空）。
     *
     * 背景：RC71 把 AIProviderRepositoryImpl.toEntity 的「加密失败」从静默落空串改成抛异常，
     * 以杜绝密钥被空串覆盖。但 ViewModel 的 saveProvider 用 viewModelScope.launch 无 try-catch，
     * 未捕获的协程异常会直接导致 App 崩溃（闪退）。这里接管异常：不崩溃、不静默丢密钥，
     * 而是把错误暴露给 UI 弹提示。
     */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /** 消费一次保存错误提示（UI 展示后调用），避免重复弹。 */
    fun consumeSaveError() {
        _saveError.value = null
    }

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    /** RC63 备选方案③：兼容端点「默认策略」流 + 快照（ProviderEditorScreen Tab0 下拉选择 + 选项卡小角标用）。 */
    val compatibilityDefaultPolicyFlow: StateFlow<DefaultPolicy>
        get() = compatibilityPolicyRepository.defaultPolicyFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, DefaultPolicy.STRICT
        )

    /** RC63 备选方案②「发送失败自动降级」总开关。 */
    val autoDowngradeOnSendFailureFlow: StateFlow<Boolean>
        get() = compatibilityPolicyRepository.autoDowngradeOnSendFailureFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, true
        )

    /** RC63 viewImage 守卫策略（FALLBACK_VISION_MODEL vs FAIL_FAST）。 */
    val viewImageUnknownGuardPolicyFlow: StateFlow<ViewImageUnknownGuardPolicy>
        get() = compatibilityPolicyRepository.viewImageUnknownGuardPolicyFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL
        )

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _activeProfileId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /** 「共享设备存储」：把设备外存绑定进容器 /root/storage/shared（默认关）。 */
    private val _storageShareEnabled = MutableStateFlow(false)
    val storageShareEnabled: StateFlow<Boolean> = _storageShareEnabled.asStateFlow()

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** 全部 profile（内置 arm64 + 内置 x86_64 + 自定义），供 UI 列出。 */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .map { listOf(ContainerProfile.BUILTIN_ALPINE, ContainerProfile.BUILTIN_ALPINE_X86) + it }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            listOf(ContainerProfile.BUILTIN_ALPINE, ContainerProfile.BUILTIN_ALPINE_X86)
        )

    /** 当前执行模式（本地 PRoot / 远程 SSH），供 UI 判断是否显示远程连接指示器。 */
    val executionMode: StateFlow<ExecutionMode> = executionModeHolder.mode

    /** 远程 SSH 连接状态，供 UI 显示指示器。 */
    val connectionState: StateFlow<ConnectionState> = remoteSshConnection.connectionState

    /** 工作区已配置的远程连接通道，供容器镜像 SSH 模式下拉复用。 */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            // 启动即保证有激活提供商（若库中存在却无激活项），避免主页模型胶囊因 activeProvider=null 消失。
            repository.ensureActiveProvider()

            launch {
                repository.getAllProviders().collectLatest {
                    _providers.value = it
                    // 运行期兜底：提供商列表变化后若仍无激活项且有提供商，自动激活首个。
                    if (_activeProvider.value == null && it.isNotEmpty()) {
                        repository.ensureActiveProvider()
                    }
                }
            }

            launch {
                repository.getActiveProvider().collectLatest {
                    _activeProvider.value = it
                }
            }

            launch {
                visionModelSettingsRepository.providerIdFlow.collectLatest {
                    _visionProviderId.value = it
                }
            }

            launch {
                visionModelSettingsRepository.modelFlow.collectLatest {
                    _visionModel.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.providerIdFlow.collectLatest {
                    _compactionProviderId.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.modelFlow.collectLatest {
                    _compactionModel.value = it
                }
            }

            launch {
                logSettingsRepository.levelFlow.collectLatest {
                    _logLevel.value = it
                }
            }

            // 恢复持久化的日志筛选偏好
            launch {
                val levels = logFilterSettingsRepository.readSelectedLevels()
                val tags = logFilterSettingsRepository.readSelectedTags()
                val rangeMode = logFilterSettingsRepository.readDateRangeMode()
                _logViewerState.update {
                    it.copy(
                        selectedLevels = levels,
                        selectedTags = tags,
                        dateRangeMode = rangeMode
                    )
                }
            }

            launch {
                keepaliveSettingsRepository.enabledFlow.collectLatest {
                    _keepaliveEnabled.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                languageSettingsRepository.languageFlow.collectLatest {
                    _languageTag.value = it
                }
            }

            launch {
                containerSettingsRepository.activeProfileIdFlow.collectLatest {
                    _activeProfileId.value = it
                }
            }

            launch {
                containerSettingsRepository.customProfilesFlow.collectLatest {
                    _customProfiles.value = it
                }
            }

            launch {
                containerSettingsRepository.storageShareEnabledFlow.collectLatest {
                    _storageShareEnabled.value = it
                }
            }

            launch {
                mcpConfigRepository.serversFlow.collectLatest {
                    _mcpServers.value = it
                }
            }

            launch {
                permissionRulesRepository.globalRulesFlow.collectLatest {
                    _globalRules.value = it
                }
            }

            launch {
                permissionRulesRepository.currentProjectRulesFlow.collectLatest {
                    _projectRules.value = it
                }
            }

            // ── 筛选防抖消费者：筛选变化 300ms 后重新应用过滤 ──
            launch {
                _filterTrigger.debounce(300).collectLatest { _ ->
                    applyLocalFilters()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveTail()
    }

    fun upsertMcpServer(originalName: String?, config: McpServerConfig) {
        viewModelScope.launch {
            val ordered = LinkedHashMap<String, McpServerConfig>()
            _mcpServers.value.forEach { ordered[it.name] = it }
            if (originalName != null && originalName != config.name) {
                ordered.remove(originalName)
            }
            ordered[config.name] = config
            persistMcpServers(ordered.values.toList())
        }
    }

    fun deleteMcpServer(name: String) {
        viewModelScope.launch {
            persistMcpServers(_mcpServers.value.filterNot { it.name == name })
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            persistMcpServers(_mcpServers.value.map { if (it.name == name) it.copy(enabled = enabled) else it })
        }
    }

    fun reloadMcp() {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }

    private suspend fun persistMcpServers(servers: List<McpServerConfig>) {
        mcpConfigRepository.setServers(servers)
        _mcpReloading.value = true
        try {
            mcpManager.reload()
        } finally {
            _mcpReloading.value = false
        }
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch {
            logSettingsRepository.setLevel(level)
        }
    }

    fun refreshLogs(filterServerName: String? = _logViewerState.value.filterServerName) {
        loadLogs(
            filterServerName = filterServerName?.takeIf { it.isNotBlank() },
            preferredFileName = _logViewerState.value.selectedFileName
        )
    }

    fun selectLogFile(fileName: String) {
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = fileName
        )
    }

    // ── 筛选控制方法 ──

    fun toggleFilterPanel() {
        // 只打开，不关闭；关闭由 closeFilterPanel() 负责
        _logViewerState.update { it.copy(filterPanelExpanded = true) }
    }

    fun closeFilterPanel() {
        _logViewerState.update { it.copy(filterPanelExpanded = false) }
    }

    fun setSelectedDates(dates: Set<String>) {
        _logViewerState.update { it.copy(selectedDates = dates) }
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = _logViewerState.value.selectedFileName
        )
    }

    fun setDateRangeMode(rangeMode: Boolean) {
        _logViewerState.update { it.copy(dateRangeMode = rangeMode) }
        viewModelScope.launch { logFilterSettingsRepository.saveDateRangeMode(rangeMode) }
    }

    fun setDateRange(start: String?, end: String?) {
        _logViewerState.update { it.copy(dateRangeStart = start, dateRangeEnd = end) }
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = _logViewerState.value.selectedFileName
        )
    }

    fun toggleLevel(level: LogLevel) {
        _logViewerState.update { current ->
            val newLevels = if (level in current.selectedLevels) {
                current.selectedLevels - level
            } else {
                current.selectedLevels + level
            }
            current.copy(selectedLevels = newLevels)
        }
        viewModelScope.launch {
            logFilterSettingsRepository.saveSelectedLevels(_logViewerState.value.selectedLevels)
        }
        // 局部过滤：不重新读文件
        triggerDebouncedFilter()
    }

    fun toggleTag(tag: String) {
        _logViewerState.update { current ->
            val newTags = if (tag in current.selectedTags) {
                current.selectedTags - tag
            } else {
                current.selectedTags + tag
            }
            current.copy(selectedTags = newTags)
        }
        viewModelScope.launch {
            logFilterSettingsRepository.saveSelectedTags(_logViewerState.value.selectedTags)
        }
        // 局部过滤：不重新读文件
        triggerDebouncedFilter()
    }

    fun setSearchQuery(query: String) {
        _logViewerState.update { it.copy(searchQuery = query) }
        // 局部过滤：不重新读文件
        triggerDebouncedFilter()
    }

    fun resetFilters() {
        _logViewerState.update {
            it.copy(
                selectedDates = emptySet(),
                selectedLevels = emptySet(),
                selectedTags = emptySet(),
                searchQuery = "",
                dateRangeStart = null,
                dateRangeEnd = null
            )
        }
        viewModelScope.launch {
            logFilterSettingsRepository.saveSelectedLevels(emptySet())
            logFilterSettingsRepository.saveSelectedTags(emptySet())
        }
        // 局部过滤：不重新读文件
        applyLocalFilters()
    }

    // ── 实时尾随 ──

    fun toggleLiveTail() {
        val current = _logViewerState.value
        if (current.liveTailEnabled) {
            stopLiveTail()
        } else {
            startLiveTail()
        }
    }

    private fun startLiveTail() {
        _logViewerState.update { it.copy(liveTailEnabled = true, hasNewLogs = false) }
        // 启动 FileObserver 监听日志目录变化
        val logDir = FileLogger.getLogDir() ?: return
        _liveTailFileObserver = object : android.os.FileObserver(logDir, android.os.FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.startsWith("log-")) {
                    _logViewerState.update { it.copy(hasNewLogs = true) }
                }
            }
        }.apply { startWatching() }
        // 立即刷新一次
        refreshLogs()
    }

    private fun stopLiveTail() {
        _liveTailFileObserver?.stopWatching()
        _liveTailFileObserver = null
        _logViewerState.update { it.copy(liveTailEnabled = false, hasNewLogs = false) }
    }

    fun dismissNewLogs() {
        _logViewerState.update { it.copy(hasNewLogs = false) }
        refreshLogs()
    }

    // ── 防抖触发 ──

    private fun triggerDebouncedFilter() {
        _filterTrigger.update { it + 1 }
    }

    /** 局部过滤：仅对已缓存的行做内存过滤，不重新读文件。 */
    private fun applyLocalFilters() {
        val snapshot = _logViewerState.value
        val allLines = _cachedRawLines
        if (allLines.isEmpty()) return

        val filteredLines = allLines.filter { line ->
            matchesFilters(line, snapshot.filterServerName, snapshot)
        }
        val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

        val allTags = LogLineParser.extractTags(allLines)
        _logViewerState.update {
            it.copy(
                content = visibleLines.joinToString("\n"),
                totalLines = filteredLines.size,
                shownLines = visibleLines.size,
                allAvailableTags = allTags,
                loading = false
            )
        }
    }

    // ── 核心加载逻辑 ──

    /** 流式读取日志：从文件尾部向前扫描，只读最后 N 行。 */
    private fun loadLogs(filterServerName: String?, preferredFileName: String?) {
        viewModelScope.launch {
            val snapshot = _logViewerState.value
            _logViewerState.update {
                it.copy(
                    loading = true,
                    filterServerName = filterServerName,
                    error = null
                )
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val allFiles = FileLogger.listLogFiles()
                    if (allFiles.isEmpty()) {
                        _cachedRawLines = emptyList()
                        return@runCatching LogViewerUiState(
                            filterServerName = filterServerName,
                            error = "还没有日志文件"
                        )
                    }

                    // 1. 按日期筛选文件列表
                    val matchedFiles = filterFilesByDate(allFiles, snapshot)

                    // 2. 流式读取：从每个文件尾部向前扫描，只读最后 MAX_LOG_LINES 行
                    val allLines = readTailLines(matchedFiles, MAX_LOG_LINES * 2)

                    // 3. 缓存原始行（供局部过滤使用）
                    _cachedRawLines = allLines

                    // 4. 提取所有可用 Tag
                    val allTags = LogLineParser.extractTags(allLines)

                    // 5. 多维度过滤
                    val filteredLines = allLines.filter { line ->
                        matchesFilters(line, filterServerName, snapshot)
                    }

                    // 6. 尾部截断
                    val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

                    // 7. 确定选中文件名
                    val selectedName = preferredFileName
                        ?: allFiles.firstOrNull { it.name == preferredFileName }?.name
                        ?: matchedFiles.lastOrNull()?.name
                        ?: allFiles.lastOrNull()?.name

                    LogViewerUiState(
                        files = allFiles.map { it.name },
                        selectedFileName = selectedName,
                        filterServerName = filterServerName,
                        content = visibleLines.joinToString("\n"),
                        totalLines = filteredLines.size,
                        shownLines = visibleLines.size,
                        selectedDates = snapshot.selectedDates,
                        selectedLevels = snapshot.selectedLevels,
                        selectedTags = snapshot.selectedTags,
                        allAvailableTags = allTags,
                        dateRangeMode = snapshot.dateRangeMode,
                        dateRangeStart = snapshot.dateRangeStart,
                        dateRangeEnd = snapshot.dateRangeEnd,
                        searchQuery = snapshot.searchQuery,
                        liveTailEnabled = snapshot.liveTailEnabled
                    )
                }.getOrElse { e ->
                    LogViewerUiState(
                        filterServerName = filterServerName,
                        error = "读取日志失败: ${e.message}"
                    )
                }
            }
            _logViewerState.value = state
        }
    }

    /** 从多个文件尾部向前读取，收集最多 maxLines 行。 */
    private fun readTailLines(files: List<java.io.File>, maxLines: Int): List<String> {
        if (files.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        // 从最新文件开始读（文件列表按日期排序，最后一个是最近）
        for (file in files.reversed()) {
            if (result.size >= maxLines) break
            val lines = readFileTail(file, maxLines - result.size)
            result.addAll(0, lines) // 插到前面保持时间顺序
        }
        return result
    }

    /** 从单个文件尾部读取最后 n 行（UTF-8 安全）。 */
    private fun readFileTail(file: java.io.File, n: Int): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val raf = java.io.RandomAccessFile(file, "r")
            try {
                val fileLength = raf.length()
                // 估算读取字节数：每行按 200 字节估算，至少 8KB
                val bytesToRead = minOf(fileLength, (n * 200L).coerceAtLeast(8192L))
                val startPos = fileLength - bytesToRead
                raf.seek(startPos)
                val bytes = ByteArray(bytesToRead.toInt())
                raf.readFully(bytes)
                // 整块 UTF-8 解码（开头可能截断在多字节字符中间，但 String 解码器会正确处理）
                String(bytes, Charsets.UTF_8)
                    .lines()
                    .takeLast(n)
                    .map { it.trimEnd('\r') }
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            // 降级：使用 readLines
            file.readLines(Charsets.UTF_8).takeLast(n)
        }
    }

    /** 按日期筛选条件匹配文件列表。 */
    private fun filterFilesByDate(
        allFiles: List<java.io.File>,
        snapshot: LogViewerUiState
    ): List<java.io.File> {
        val dates = snapshot.selectedDates
        if (dates.isNotEmpty()) {
            // 列表模式：按选中日期精确匹配
            return allFiles.filter { f ->
                val fileDate = f.name.removePrefix("log-").removeSuffix(".txt")
                fileDate in dates
            }
        }
        val rangeStart = snapshot.dateRangeStart
        val rangeEnd = snapshot.dateRangeEnd
        if (rangeStart != null || rangeEnd != null) {
            // 范围模式：按日期范围筛选
            return allFiles.filter { f ->
                val fileDate = f.name.removePrefix("log-").removeSuffix(".txt")
                val inRange = (rangeStart == null || fileDate >= rangeStart) &&
                    (rangeEnd == null || fileDate <= rangeEnd)
                inRange
            }
        }
        // 无日期筛选：返回所有文件
        return allFiles
    }

    /** 判断单行是否通过所有筛选条件。 */
    private fun matchesFilters(
        line: String,
        filterServerName: String?,
        snapshot: LogViewerUiState
    ): Boolean {
        val parsed = LogLineParser.parse(line)
        if (parsed == null) {
            // 附属行（堆栈等）始终保留
            return true
        }

        // filterServerName 过滤（MCP 对话框跳转）
        if (!filterServerName.isNullOrBlank()) {
            if (!parsed.raw.contains("[$filterServerName]", ignoreCase = true) &&
                !parsed.raw.contains(filterServerName, ignoreCase = true)
            ) {
                return false
            }
        }

        // 等级过滤
        if (snapshot.selectedLevels.isNotEmpty()) {
            if (parsed.level == null || parsed.level !in snapshot.selectedLevels) {
                return false
            }
        }

        // Tag 来源过滤
        if (snapshot.selectedTags.isNotEmpty()) {
            if (parsed.tag !in snapshot.selectedTags) {
                return false
            }
        }

        // 搜索关键词过滤
        val query = snapshot.searchQuery.trim()
        if (query.isNotEmpty()) {
            if (!parsed.raw.contains(query, ignoreCase = true)) {
                return false
            }
        }

        return true
    }

    // 仅持久化标志位——启停 Service 由 AIEditorApp 监听 enabledFlow 统一完成。
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keepaliveSettingsRepository.setEnabled(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    /** 设置应用语言；tag 为空字符串或 null 表示跟随系统。 */
    fun setLanguage(tag: String?) {
        viewModelScope.launch {
            languageSettingsRepository.setLanguage(tag?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * 切换当前选中的容器 profile，并按其 [ContainerProfile.mode] 同步切全局执行模式。
     *
     * 本地镜像 → [ExecutionMode.LOCAL_PROOT]；远程 SSH 镜像 → [ExecutionMode.REMOTE_SSH]，
     * 并据其 [RootfsSource.RemoteSsh] 绑定的工作区通道构造 [RemoteConnectionSettings] 持久化 + 触发 SSH 连接。
     * 委托层每次调用读 holder，切换即时生效，无需重启。
     */
    fun setActiveContainerProfile(id: String) {
        viewModelScope.launch {
            val profile = _customProfiles.value.firstOrNull { it.id == id }
                ?: when (id) {
                    ContainerProfile.BUILTIN_ID -> ContainerProfile.BUILTIN_ALPINE
                    ContainerProfile.BUILTIN_X86_ID -> ContainerProfile.BUILTIN_ALPINE_X86
                    else -> null
                } ?: return@launch
            containerSettingsRepository.setActiveProfile(id)
            when (profile.mode) {
                ExecutionMode.LOCAL_PROOT -> {
                    executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                    executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
                }

                ExecutionMode.REMOTE_SSH -> {
                    val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return@launch
                    val conn = remoteConnections.value.firstOrNull { it.id == ssh.connectionId }
                        ?: return@launch
                    val auth = runCatching { remoteRepository.getAuthById(ssh.connectionId) }.getOrNull()
                        ?: com.R.codecore.feature.workspace.domain.remote.RemoteAuth.Password(conn.password)
                    val workspacePath = ssh.remoteWorkspacePath.ifBlank { "/home/${conn.username}/workspace" }
                    // v2 统一数据源：只存 activeConnectionId + workspacePath + profileId，
                    // 不再重复 host/port/username/password 到 DataStore，避免与 Room 漂移。
                    val settings = com.R.codecore.feature.settings.data.repository.RemoteConnectionSettings(
                        host = "",
                        port = 22,
                        username = "",
                        password = "",
                        remoteWorkspacePath = workspacePath,
                        activeConnectionId = ssh.connectionId,
                    )
                    executionModeRepository.setRemoteConnection(settings, activeProfileId = id)
                    executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                    executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
                    // 运行时切换需主动连接（启动时由 AIEditorApp 连）；复用 RemoteSshConnection.connect
                    runCatching {
                        remoteSshConnection.connect(
                            com.R.codecore.feature.agent.domain.container.RemoteConnectionConfig(
                                host = conn.host,
                                port = conn.port,
                                username = conn.username,
                                auth = auth,
                                remoteWorkspacePath = workspacePath
                            )
                        )
                    }.onFailure { FileLogger.w("SettingsViewModel", "切换到远程镜像时 SSH 连接失败", it) }
                }
            }
        }
    }

    /** 切换「共享设备存储」开关。生效对象为后续启动的容器进程，已运行的 shell 需重开。 */
    fun setStorageShareEnabled(enabled: Boolean) {
        viewModelScope.launch { containerSettingsRepository.setStorageShareEnabled(enabled) }
    }

    /** 重置内置容器（arm64 或 x86_64）：删除对应架构 rootfs，下次初始化重新解压 + provision。 */
    fun resetBuiltinContainer(profile: ContainerProfile) {
        viewModelScope.launch {
            when (profile.arch) {
                ContainerArch.X86_64 ->
                    containerInstaller.resetBuiltinX86Rootfs()
                else -> containerInstaller.resetBuiltinRootfs()
            }
        }
    }

    /** 保存（新增/覆盖）自定义容器 profile。 */
    fun saveCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 编辑自定义 profile：覆盖配置；若镜像来源变了则删旧 rootfs 触发重新解压。 */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                containerInstaller.deleteCustomRootfs(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 删除自定义 profile，连带清理其 rootfs 目录。 */
    fun deleteCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            containerInstaller.deleteCustomRootfs(profile)
            if (_activeProfileId.value == profile.id) {
                containerSettingsRepository.setActiveProfile(ContainerProfile.BUILTIN_ID)
                // 删的是当前激活的远程镜像：回退到内置本地镜像，同步切回本地模式
                if (profile.mode == ExecutionMode.REMOTE_SSH) {
                    executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                    executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
                }
            }
        }
    }

    /** 设置识图专用模型；providerId 留空等同 [clearVisionModel]（跟随聊天模型）。 */
    fun setVisionModel(providerId: String, model: String) {
        viewModelScope.launch {
            visionModelSettingsRepository.setVisionModel(providerId, model)
        }
    }

    /** 清空识图专用模型——回退到跟随当前聊天模型。 */
    fun clearVisionModel() {
        viewModelScope.launch {
            visionModelSettingsRepository.clear()
        }
    }

    /** 设置压缩专用模型；providerId 留空等同 [clearCompactionModel]（跟随聊天模型）。 */
    fun setCompactionModel(providerId: String, model: String) {
        viewModelScope.launch {
            compactionModelSettingsRepository.setCompactionModel(providerId, model)
        }
    }

    /** 清空压缩专用模型——回退到跟随当前聊天模型。 */
    fun clearCompactionModel() {
        viewModelScope.launch {
            compactionModelSettingsRepository.clear()
        }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch {
            repository.setActiveProvider(id)
        }
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            // RC72：接管保存异常，避免未捕获协程异常导致崩溃（闪退）。
            // 底层 toEntity 在加密失败时会抛异常（防止密钥被空串覆盖），必须在这里捕获，
            // 否则 viewModelScope.launch 的未捕获异常会直接闪退。
            runCatching { repository.saveProvider(provider) }
                .onFailure { e ->
                    FileLogger.e("SettingsVM", "保存提供商失败: ${e.message}", e)
                    _saveError.value = e.message ?: "保存失败，请重试"
                }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    fun fetchModels(provider: AIProviderConfig) {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            modelApiService.fetchModels(provider.baseUrl, provider.apiKey, provider.type)
                .onSuccess {
                    _fetchState.value = FetchState.Success(it)
                    resolveModelMetadata(provider.type, it)
                }
                .onFailure { _fetchState.value = FetchState.Error(it.message ?: "拉取失败") }
        }
    }

    fun resolveModelMetadata(type: ProviderType, modelIds: List<String>) {
        val normalizedIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(type, normalizedIds)
            _modelMetadata.update { current -> current + metadata }
        }
    }

    /**
     * 加载所有已启用 provider 的全部模型元数据，合并进 [modelMetadata]。
     * 供「识图模型」等需要展示跨 provider 模型能力标签的页面在进入时调用--
     * 这些页面不像 ProviderEditor 那样会在编辑单个 provider 时顺带 resolve，
     * 不主动加载则 map 为空、所有模型都被误判为不支持图片。
     *
     * 实现要点（避免设置页卡顿）：
     * - 单协程顺序处理各 provider：首个 resolveAll 触发 catalog 加载（内存/磁盘 24h 缓存/内置 assets）并写入内存缓存，
     *   后续 provider 命中缓存。resolve 链路不发网络请求，models.dev 刷新统一由 App 启动时异步触发。
     * - 全部解析完一次性 update，避免多次 emit 导致设置页反复重组。
     */
    fun loadAllModelMetadata() {
        val enabled = _providers.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, ModelMetadata>()
            for (provider in enabled) {
                val ids = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                resolved += modelMetadataService.resolveAll(provider.type, ids)
            }
            if (resolved.isNotEmpty()) {
                _modelMetadata.update { it + resolved }
            }
        }
    }

    fun resetFetchState() {
        _fetchState.value = FetchState.Idle
    }

    /**
     * 内置供应商向导：实时拉取模型列表（步骤 3 使用）。
     *
     * 复用 [ModelApiService.fetchModels] 的接口探测与 T2I Failover；状态独立于编辑页，
     * 避免在向导里拉取模型时污染编辑页的 [_fetchState]。
     */
    fun fetchBuiltInModels(baseUrl: String, apiKey: String, type: ProviderType) {
        viewModelScope.launch {
            _builtInFetchState.value = FetchState.Loading
            modelApiService.fetchModels(baseUrl, apiKey, type)
                .onSuccess {
                    _builtInFetchState.value = FetchState.Success(it)
                    // 与编辑页一致：解析模型元数据，向导第 3 步才能展示真实能力标签（识图/工具/思考）
                    resolveModelMetadata(type, it)
                }
                .onFailure { _builtInFetchState.value = FetchState.Error(it.message ?: "拉取失败") }
        }
    }

    fun testModel(provider: AIProviderConfig, model: String) {
        viewModelScope.launch {
            _testing.update { it + model }
            val result = modelApiService.testModel(provider.baseUrl, provider.apiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            repository.setSelectedModel(providerId, model)
        }
    }

    // 主页模型选择：同步更新全局 active provider 的选中模型，使新建会话回退全局时落到用户最近选的模型。
    fun applyModelGlobally(providerId: String, model: String) {
        viewModelScope.launch {
            val activeId = repository.getActiveProviderSync()?.id
            if (activeId != providerId) {
                repository.setActiveProvider(providerId)
            }
            repository.setSelectedModel(providerId, model)
        }
    }

    fun deleteGlobalRule(rule: PermissionRule) {
        viewModelScope.launch { permissionRulesRepository.removeGlobalRule(rule) }
    }

    fun deleteProjectRule(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.removeProjectRule(name, rule) }
    }

    fun promoteRuleToGlobal(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.promoteToGlobal(name, rule) }
    }

    // ────────────────────────────────────────────────────────────────
    // RC63 备选方案③：兼容端点全局策略设置（ProviderEditorScreen Tab0 调用）
    // ────────────────────────────────────────────────────────────────

    fun setCompatibilityDefaultPolicy(policy: DefaultPolicy) {
        viewModelScope.launch { compatibilityPolicyRepository.setDefaultPolicy(policy) }
    }

    fun setAutoDowngradeOnSendFailure(enabled: Boolean) {
        viewModelScope.launch { compatibilityPolicyRepository.setAutoDowngradeOnSendFailure(enabled) }
    }

    fun setViewImageUnknownGuardPolicy(policy: ViewImageUnknownGuardPolicy) {
        viewModelScope.launch { compatibilityPolicyRepository.setViewImageUnknownGuardPolicy(policy) }
    }

    // ────────────────────────────────────────────────────────────────
    // RC63 备选方案④：单模型能力复选框覆盖（ProviderEditorScreen Tab1 调用）
    // ────────────────────────────────────────────────────────────────

    /** 观察某模型的手动覆盖（用于 CapabilityOverrideSheet 实时回显勾选状态）。 */
    fun observeCapabilityOverride(type: ProviderType, modelId: String) =
        modelMetadataService.observeOverride(type, modelId)

    /** 保存某模型的手动覆盖；不想覆盖的字段传 null（保持自动决策）。 */
    fun saveCapabilityOverride(
        type: ProviderType,
        modelId: String,
        vision: Boolean?,
        tools: Boolean?,
        reasoning: Boolean?
    ) {
        viewModelScope.launch {
            modelMetadataService.saveOverride(type, modelId, vision, tools, reasoning)
            // 立即重算该 provider 全部模型元数据（以便 CapabilityOverrideSheet 返回后
            // ProviderModelRow 的「识图/工具/思考」徽章立即刷新，用户看不到过期值）。
            val typeProviderModels = _providers.value
                .filter { it.type == type && it.models.isNotEmpty() }
                .flatMap { it.models.map(String::trim).filter(String::isNotEmpty).distinct() }
                .plus(modelId)
                .distinct()
            if (typeProviderModels.isEmpty()) return@launch
            val refreshed = modelMetadataService.resolveAll(type, typeProviderModels)
            _modelMetadata.update { it + refreshed }
        }
    }

    /** 清除某模型覆盖（恢复自动推荐）。 */
    fun clearCapabilityOverride(type: ProviderType, modelId: String) {
        viewModelScope.launch {
            modelMetadataService.clearOverride(type, modelId)
            val refreshed = modelMetadataService.resolveAll(type, listOf(modelId))
            _modelMetadata.update { it + refreshed }
        }
    }
}
