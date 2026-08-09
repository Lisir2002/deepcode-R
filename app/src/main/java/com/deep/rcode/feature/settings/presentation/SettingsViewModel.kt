package com.deep.rcode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.core.util.FileLogger
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
import com.deep.rcode.core.util.LogLineParser
import com.deep.rcode.feature.settings.data.repository.LogFilterSettingsRepository
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
    private val remoteRepository: RemoteRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200
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
                    val auth = runCatching { remoteRepository.getAuthById(ssh.connectionId) }.getOrNull()
                        ?: com.deep.rcode.feature.workspace.domain.remote.RemoteAuth.Password(conn.password)
                    val workspacePath = ssh.remoteWorkspacePath.ifBlank { "/home/${conn.username}/workspace" }
                    // v2 统一数据源：只存 activeConnectionId + workspacePath + profileId，
                    // 不再重复 host/port/username/password 到 DataStore，避免与 Room 漂移。
                    val settings = com.deep.rcode.feature.settings.data.repository.RemoteConnectionSettings(
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
                            com.deep.rcode.feature.agent.domain.container.RemoteConnectionConfig(
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
