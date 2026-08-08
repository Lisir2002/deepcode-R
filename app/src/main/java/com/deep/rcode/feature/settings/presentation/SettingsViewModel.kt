package com.deep.rcode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.core.util.LogEntry
import com.deep.rcode.core.util.LogLevel
import com.deep.rcode.feature.agent.domain.container.ConnectionState
import com.deep.rcode.feature.agent.domain.container.ContainerInstaller
import com.deep.rcode.feature.agent.domain.container.ContainerProfile
import com.deep.rcode.feature.agent.domain.container.RemoteSshConnection
import com.deep.rcode.feature.agent.domain.container.RootfsSource
import com.deep.rcode.feature.agent.domain.mcp.McpConfigRepository
import com.deep.rcode.feature.agent.domain.mcp.McpManager
import com.deep.rcode.feature.agent.domain.mcp.McpServerConfig
import com.deep.rcode.feature.agent.domain.mcp.McpServerStatus
import com.deep.rcode.feature.agent.domain.mcp.McpToolDescriptor
import com.deep.rcode.feature.agent.domain.permission.PermissionRule
import com.deep.rcode.feature.agent.domain.permission.PermissionRulesRepository
import com.deep.rcode.feature.settings.data.remote.ModelApiService
import com.deep.rcode.feature.settings.data.remote.ModelMetadataService
import com.deep.rcode.feature.settings.data.remote.ModelTestResult
import com.deep.rcode.feature.settings.data.repository.AppThemeMode
import com.deep.rcode.feature.settings.data.repository.ContainerSettingsRepository
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import com.deep.rcode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder
import com.deep.rcode.feature.settings.data.repository.ExecutionModeRepository
import com.deep.rcode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.deep.rcode.feature.settings.data.repository.LanguageSettingsRepository
import com.deep.rcode.feature.settings.data.repository.LogSettingsRepository
import com.deep.rcode.feature.settings.data.repository.ThemeSettingsRepository
import com.deep.rcode.feature.settings.data.repository.VisionModelSettingsRepository
import com.deep.rcode.feature.workspace.domain.model.RemoteConnection
import com.deep.rcode.feature.workspace.domain.repository.RemoteRepository
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.settings.domain.model.ModelMetadata
import com.deep.rcode.feature.settings.domain.model.ProviderType
import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>) : FetchState()
    data class Error(val message: String) : FetchState()
}

/**
 * 写入等级切换提示类型。
 * - [Tightened] 等级调严（例如 VERBOSE → WARN）：历史还在，建议同步显示过滤。
 * - [Loosened]  等级调松（例如 ERROR → DEBUG）：刷新视图后会看到新产生的低等级日志。
 */
sealed class LogLevelHint {
    data class Tightened(val newLevel: LogLevel) : LogLevelHint()
    data class Loosened(val newLevel: LogLevel) : LogLevelHint()
}

/**
 * 日志查看结构化状态。UI 基于此渲染 LazyColumn + 三维过滤器。
 *
 * @property entries            全部条目（解析文件 + 实时流追加），按时间升序
 * @property searchQuery        关键字搜索
 * @property selectedLevels     等级过滤：空 = 全选
 * @property selectedCategories 分类过滤（App / MCP 服务器名）：空 = 全选
 * @property categories         当前数据源中出现过的所有分类（供 UI Chip 列出）
 * @property filteredEntries    过滤后条目（UI 列表直接用，派生字段）
 * @property levelCounts        按等级统计条数（用于 Chip 右上角徽标）
 */
data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val entries: List<LogEntry> = emptyList(),
    val searchQuery: String = "",
    val selectedLevels: Set<LogLevel> = emptySet(),
    val selectedCategories: Set<String> = emptySet(),
    val categories: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val levelHint: LogLevelHint? = null
) {
    /** 过滤后条目——每次访问即时计算，避免 by lazy 在 data class 新实例间被 Compose 快照系统误缓存。 */
    val filteredEntries: List<LogEntry>
        get() {
            var list = entries
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim()
                list = list.filter { e ->
                    e.message.contains(q, ignoreCase = true) ||
                        e.tag.contains(q, ignoreCase = true) ||
                        e.throwableStack?.contains(q, ignoreCase = true) == true
                }
            }
            if (selectedLevels.isNotEmpty()) {
                val ordinalMax = selectedLevels.maxOf { it.ordinal }
                val ordinalMin = selectedLevels.minOf { it.ordinal }
                // 判断是否连续（覆盖区间 [min, max]），若是走 ordinal 范围匹配更直观
                val continuous = (ordinalMax - ordinalMin + 1) == selectedLevels.size
                list = if (continuous) {
                    list.filter { it.level.ordinal in ordinalMin..ordinalMax }
                } else {
                    list.filter { it.level in selectedLevels }
                }
            }
            if (selectedCategories.isNotEmpty()) {
                list = list.filter { it.category in selectedCategories }
            }
            return list
        }

    /** 按等级统计条数——每次访问即时计算。 */
    val levelCounts: Map<LogLevel, Int>
        get() = entries.groupingBy { it.level }.eachCount()

    val totalCount: Int get() = entries.size
    val shownCount: Int get() = filteredEntries.size
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
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
    private val remoteRepository: RemoteRepository
) : ViewModel() {

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

    /** 实时日志流订阅 Job：只有在日志查看界面才需要订阅。 */
    private var liveEntryJob: Job? = null
    /** 记录上一次写入等级，用于判断调严/调松联动。 */
    private var lastRecordLevel: LogLevel? = null

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

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

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

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** 全部 profile（内置 + 自定义），供 UI 列出。 */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .map { listOf(ContainerProfile.BUILTIN_ALPINE) + it }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, listOf(ContainerProfile.BUILTIN_ALPINE))

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
                    if (lastRecordLevel == null) lastRecordLevel = it
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
        }
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
            val old = lastRecordLevel
            lastRecordLevel = level
            logSettingsRepository.setLevel(level)
            // 联动提示
            if (old != null && old != level) {
                val hint = if (level.ordinal > old.ordinal) {
                    LogLevelHint.Tightened(level)
                } else {
                    LogLevelHint.Loosened(level)
                }
                _logViewerState.update { it.copy(levelHint = hint) }
                // 调松后自动刷新一次，把实时流里可能新增的低等级日志合并
                if (hint is LogLevelHint.Loosened) {
                    refreshLogs()
                }
            }
        }
    }

    /** 清除联动提示（UI 在 Snackbar 显示完毕后调用）。 */
    fun consumeLevelHint() {
        _logViewerState.update { it.copy(levelHint = null) }
    }

    fun setSearchQuery(query: String) {
        _logViewerState.update { it.copy(searchQuery = query) }
    }

    fun toggleLevelFilter(level: LogLevel) {
        _logViewerState.update { s ->
            val next = s.selectedLevels.toMutableSet()
            if (!next.remove(level)) next.add(level)
            s.copy(selectedLevels = next)
        }
    }

    fun clearLevelFilter() {
        _logViewerState.update { it.copy(selectedLevels = emptySet()) }
    }

    /** 一键把显示等级过滤同步为当前写入等级及以上。 */
    fun syncDisplayFilterToRecordLevel() {
        val min = _logLevel.value
        val set = LogLevel.values().filter { it.ordinal >= min.ordinal && it != LogLevel.NONE }.toSet()
        _logViewerState.update { it.copy(selectedLevels = set) }
    }

    fun toggleCategoryFilter(category: String) {
        _logViewerState.update { s ->
            val next = s.selectedCategories.toMutableSet()
            if (!next.remove(category)) next.add(category)
            s.copy(selectedCategories = next)
        }
    }

    fun clearCategoryFilter() {
        _logViewerState.update { it.copy(selectedCategories = emptySet()) }
    }

    fun refreshLogs() {
        loadLogs(preferredFileName = _logViewerState.value.selectedFileName)
    }

    /**
     * 从 MCP 编辑页跳转进入时使用：刷新后自动选中对应分类。
     * 对应老版本的 refreshLogs(filterServerName = x)。
     */
    fun refreshLogsWithCategory(category: String) {
        viewModelScope.launch {
            loadLogs(preferredFileName = _logViewerState.value.selectedFileName)
            // 加载完成后再切筛选（防止被 loadLogs 的 copy 覆盖）
            _logViewerState.update { s ->
                s.copy(
                    selectedCategories = setOf(category),
                    selectedLevels = emptySet(),
                    searchQuery = ""
                )
            }
        }
    }

    fun selectLogFile(fileName: String) {
        loadLogs(preferredFileName = fileName)
    }

    private fun loadLogs(preferredFileName: String?) {
        viewModelScope.launch {
            _logViewerState.update {
                it.copy(loading = true, error = null)
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val files = FileLogger.listLogFiles()
                    val selected = files.firstOrNull { it.name == preferredFileName } ?: files.lastOrNull()
                    // 即使无日志文件，也合并 replay 缓存中的条目，确保实时日志可见
                    val fromFlow = FileLogger.entryFlow.replayCache
                    val entries = if (selected != null) FileLogger.parseLogFile(selected) else emptyList()
                    val merged = mergeEntries(entries, fromFlow)
                    val categories = merged.asSequence()
                        .map { it.category }
                        .distinct()
                        .sorted()
                        .toList()
                    _logViewerState.value.copy(
                        files = files.map { it.name },
                        selectedFileName = selected?.name,
                        entries = merged,
                        categories = categories,
                        loading = false,
                        error = null
                    )
                }.getOrElse { e ->
                    _logViewerState.value.copy(
                        loading = false,
                        error = "读取日志失败: ${e.message}"
                    )
                }
            }
            _logViewerState.value = state
            // 加载后启动实时流订阅（幂等：已有则取消重订）
            startLiveEntrySubscription()
        }
    }

    /** 合并两个按时间升序的列表，基于 timestamp+level+tag+message 去重。 */
    private fun mergeEntries(a: List<LogEntry>, b: List<LogEntry>): List<LogEntry> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val set = LinkedHashSet<LogEntry>(a.size + b.size)
        set.addAll(a)
        set.addAll(b)
        return set.sortedBy { it.timestamp }
    }

    /** 订阅 FileLogger.entryFlow，新条目追加到 entries。 */
    private fun startLiveEntrySubscription() {
        liveEntryJob?.cancel()
        liveEntryJob = viewModelScope.launch {
            var lastSeen: LogEntry? = _logViewerState.value.entries.lastOrNull()
            FileLogger.entryFlow.collect { entry ->
                // 跳过重复：实时流 replay 已经在 loadLogs 时合并过
                if (lastSeen != null && entry.timestamp <= lastSeen!!.timestamp) return@collect
                _logViewerState.update { s ->
                    val newEntries = s.entries + entry
                    val newCategories = if (s.categories.contains(entry.category)) {
                        s.categories
                    } else {
                        (s.categories + entry.category).sorted()
                    }
                    s.copy(entries = newEntries, categories = newCategories)
                }
                lastSeen = entry
            }
        }
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
                ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == id }
                ?: return@launch
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
                    val settings = com.deep.rcode.feature.settings.data.repository.RemoteConnectionSettings(
                        host = conn.host,
                        port = conn.port,
                        username = conn.username,
                        password = conn.password,
                        remoteWorkspacePath = ssh.remoteWorkspacePath.ifBlank { "/home/${conn.username}/workspace" }
                    )
                    executionModeRepository.setRemoteConnection(settings)
                    executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                    executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
                    // 运行时切换需主动连接（启动时由 AIEditorApp 连）；复用 RemoteSshConnection.connect
                    runCatching {
                        remoteSshConnection.connect(
                            com.deep.rcode.feature.agent.domain.container.RemoteConnectionConfig(
                                host = settings.host,
                                port = settings.port,
                                username = settings.username,
                                auth = com.deep.rcode.feature.workspace.domain.remote.RemoteAuth.Password(settings.password),
                                remoteWorkspacePath = settings.remoteWorkspacePath
                            )
                        )
                    }.onFailure { FileLogger.w("SettingsViewModel", "切换到远程镜像时 SSH 连接失败", it) }
                }
            }
        }
    }

    /** 重置内置 Alpine 容器：删除其 rootfs，下次启动重新解压 + provision。 */
    fun resetBuiltinContainer() {
        viewModelScope.launch {
            containerInstaller.resetBuiltinRootfs()
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
            repository.saveProvider(provider)
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
}
