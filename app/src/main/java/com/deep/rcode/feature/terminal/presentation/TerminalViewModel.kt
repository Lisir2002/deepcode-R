package com.deep.rcode.feature.terminal.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.agent.domain.container.LinuxContainerEngine
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder
import com.deep.rcode.feature.terminal.data.repository.TerminalFontSizes
import com.deep.rcode.feature.terminal.data.repository.TerminalSettingsRepository
import com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager
import com.deep.rcode.feature.terminal.domain.TerminalSessionManager
import com.deep.rcode.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 终端页的薄观察层。
 *
 * 会话的所有权在 [TerminalSessionManager]（Singleton），本 ViewModel 只转发 UI 操作并暴露其状态流，
 * **不持有也不销毁任何会话**——这正是「常驻后台」的关键：离开终端页导致本 VM onCleared 时，
 * 会话仍由管理器持有、继续在后台运行，下次回到终端页直接复用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val localManager: TerminalSessionManager,
    private val remoteManager: RemoteTerminalSessionManager,
    private val modeHolder: ExecutionModeHolder,
    private val containerEngine: LinuxContainerEngine,
    private val settingsRepo: TerminalSettingsRepository
) : ViewModel() {

    private companion object { const val TAG = "TerminalViewModel" }

    private fun isRemote() = modeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    /** 容器准备阶段的整体状态：仅用于首个标签创建前的 Loading/Error 提示。 */
    sealed interface PrepareState {
        data object Loading : PrepareState
        data object Ready : PrepareState
        data class Error(val message: String) : PrepareState
    }

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Loading)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    /** 容器初始化实时进度（解压/部署/装包），Loading 阶段用它展示细粒度文案。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    val tabs get() = if (isRemote()) remoteManager.tabs else localManager.tabs
    val activeTabId get() = if (isRemote()) remoteManager.activeTabId else localManager.activeTabId
    val revision get() = if (isRemote()) remoteManager.revision else localManager.revision

    /** 额外按键行驱动的虚拟修饰键，供 TerminalView 读取。 */
    val modifiers = TerminalKeyModifiers()

    /** 字号（SP）持久化。 */
    val fontSizeSp: StateFlow<Int> = settingsRepo.fontSizeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalFontSizes.DEFAULT)

    /** true = 完整扩展键盘，false = 简洁档。 */
    val fullExtraKeys: StateFlow<Boolean> = settingsRepo.fullExtraKeysFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Ctrl 首次提示是否已展示。 */
    val ctrlHintShown: StateFlow<Boolean> = settingsRepo.ctrlHintShownFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 用户错误提示流（Toast/Snackbar 消费）。 */
    private val _errorToast = MutableStateFlow<String?>(null)
    val errorToast: StateFlow<String?> = _errorToast.asStateFlow()

    fun consumeErrorToast() { _errorToast.value = null }

    /** 后台标签的「新输出」指示：tabId -> (已读 revision)。用户切到该标签即清零。 */
    private val _lastSeenRevision = MutableStateFlow<Map<String, Int>>(emptyMap())
    val lastSeenRevision: StateFlow<Map<String, Int>> = _lastSeenRevision.asStateFlow()

    /** 每个 tab 是否有新输出红点（仅当启用红点提示时）。 */
    val hasNewOutputFlow: Flow<Map<String, Boolean>> = combine(
        tabs, revision, _lastSeenRevision, settingsRepo.newOutputIndicatorFlow
    ) { tabsList, rev, seen, enabled ->
        if (!enabled) return@combine emptyMap()
        tabsList.associate {
            it.id to ((seen[it.id] ?: 0) < rev)
        }
    }

    init {
        // 切活动标签：清零该标签「新输出」计数
        viewModelScope.launch {
            activeTabId.collect { id ->
                id ?: return@collect
                val curRev = revision.value
                _lastSeenRevision.value = _lastSeenRevision.value + (id to curRev)
            }
        }
        prepare()
    }

    /** 进入终端页：确保至少有一个标签（首次会解压容器或连 SSH）。 */
    fun prepare() {
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            try {
                if (isRemote()) remoteManager.ensureInitialTab() else localManager.ensureInitialTab()
                _prepareState.value = PrepareState.Ready
            } catch (e: Exception) {
                FileLogger.e(TAG, "终端准备失败", e)
                _prepareState.value = PrepareState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun newTab(autoActivate: Boolean = true) {
        viewModelScope.launch {
            try {
                val id = if (isRemote()) remoteManager.createInteractiveTab() else localManager.createInteractiveTab()
                if (!autoActivate) return@launch
                // autoActivate = true 默认激活（本地/远端 createInteractiveTab 内部已激活）
                @Suppress("UNUSED_EXPRESSION") id
            } catch (e: Exception) {
                FileLogger.e(TAG, "新建标签失败", e)
                _errorToast.value = "新建标签失败：${e.message}"
            }
        }
    }

    fun activate(id: String) {
        if (isRemote()) remoteManager.activate(id) else localManager.activate(id)
        // 切过去即清「新输出」红点
        viewModelScope.launch {
            val curRev = revision.value
            _lastSeenRevision.value = _lastSeenRevision.value + (id to curRev)
        }
    }

    fun closeTab(id: String) {
        val hadOthers = tabs.value.size > 1
        val result = if (isRemote()) remoteManager.closeTab(id) else localManager.closeTab(id)
        if (!result) return
        // 关闭最后一个标签：自动新建一个空 shell，避免用户落入「无标签」死角
        viewModelScope.launch {
            // 等 closeTab 的状态流更新结束再查
            kotlinx.coroutines.delay(50)
            val remaining = tabs.value
            if (remaining.isEmpty()) {
                runCatching {
                    if (isRemote()) remoteManager.createInteractiveTab() else localManager.createInteractiveTab()
                }.onFailure { e ->
                    FileLogger.e(TAG, "自动新建兜底标签失败", e)
                    _errorToast.value = "创建新标签失败：${e.message}"
                }
            } else if (!hadOthers) {
                // 理论上不会命中，但防御性保持 activate
                remaining.firstOrNull()?.let { activate(it.id) }
            }
        }
    }

    /** 关闭除指定 id 以外的全部标签。 */
    fun closeOtherTabs(keepId: String) {
        tabs.value
            .map { it.id }
            .filter { it != keepId }
            .forEach { closeTab(it) }
    }

    /** 关闭所有终端标签（切换工作区前调用）。 */
    fun closeAllTabs() {
        tabs.value.map { it.id }.forEach { closeTab(it) }
    }

    fun reconnectActive() {
        val id = activeTabId.value ?: run {
            _errorToast.value = "没有活动标签"
            return
        }
        viewModelScope.launch {
            runCatching { if (isRemote()) remoteManager.reconnect(id) else localManager.reconnect(id) }
                .onFailure { e ->
                    FileLogger.e(TAG, "重连标签失败", e)
                    _errorToast.value = "重连失败：${e.message}"
                }
        }
    }

    /** 重连所有标签（长按菜单用）。 */
    fun reconnectAll() {
        viewModelScope.launch {
            val ids = tabs.value.map { it.id }
            ids.forEach { id ->
                runCatching { if (isRemote()) remoteManager.reconnect(id) else localManager.reconnect(id) }
            }
        }
    }

    /** 重启容器（清空所有本地标签、重新 ensureInstalled；远端不做任何事）。 */
    fun restartContainer() {
        if (isRemote()) {
            _errorToast.value = "远端模式下不可用"
            return
        }
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            runCatching {
                // 先关闭所有会话（让其进程退出），再触发重新安装
                closeAllTabs()
                kotlinx.coroutines.delay(300)
                containerEngine.ensureInstalled()
                localManager.ensureInitialTab()
            }.onSuccess {
                _prepareState.value = PrepareState.Ready
            }.onFailure { e ->
                FileLogger.e(TAG, "重启容器失败", e)
                _prepareState.value = PrepareState.Error(e.message ?: "未知错误")
            }
        }
    }

    /** 重命名标签。 */
    fun renameTab(id: String, title: String) {
        if (isRemote()) {
            // RemoteTerminalSessionManager 若没有 rename，静默忽略（远端标题在服务端维护）
            return
        }
        localManager.rename(id, title)
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun write(text: String) = if (isRemote()) remoteManager.writeToActive(text) else localManager.writeToActive(text)

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03、Ctrl-D=0x04）。 */
    fun writeBytes(vararg bytes: Int) = if (isRemote()) remoteManager.writeBytesToActive(*bytes) else localManager.writeBytesToActive(*bytes)

    // ── 设置偏好持久化 ──────────────────────────────────────
    fun setFontSizeSp(sp: Int) {
        viewModelScope.launch { settingsRepo.saveFontSize(sp) }
    }

    /** 双指缩放：按当前档位向下/向上卡一个档位，避免任意 SP。 */
    fun stepFontSize(scale: Float) {
        val cur = fontSizeSp.value
        val steps = TerminalFontSizes.STEPS
        val currentIdx = steps.indexOfFirst { it == cur }.takeIf { it >= 0 }
            ?: steps.binarySearch(cur).let { if (it < 0) -it - 1 else it }
        val targetIdx = when {
            scale > 1.05f -> (currentIdx + 1).coerceAtMost(steps.lastIndex)
            scale < 0.95f -> (currentIdx - 1).coerceAtLeast(0)
            else -> return
        }
        setFontSizeSp(steps[targetIdx])
    }

    fun setFullExtraKeys(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.saveFullExtraKeys(enabled) }
    }

    fun toggleFullExtraKeys() = setFullExtraKeys(!fullExtraKeys.value)

    fun markCtrlHintShown() {
        viewModelScope.launch { settingsRepo.markCtrlHintShown() }
    }

    fun setNewOutputIndicator(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.saveNewOutputIndicator(enabled) }
    }

    // 注意：故意不在 onCleared 里销毁会话——会话归 Singleton 管理器所有，需常驻后台。
}

