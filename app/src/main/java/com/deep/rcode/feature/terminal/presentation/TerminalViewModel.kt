package com.deep.rcode.feature.terminal.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.ContainerInitState
import com.deep.rcode.feature.agent.domain.container.LinuxContainerEngine
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import com.deep.rcode.feature.settings.data.repository.ExecutionModeHolder
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundles
import com.deep.rcode.feature.terminal.data.repository.TerminalBundleRepository
import com.deep.rcode.feature.terminal.data.repository.TerminalFontSizes
import com.deep.rcode.feature.terminal.data.repository.TerminalSettingsRepository
import com.deep.rcode.feature.terminal.domain.RemoteTerminalSessionManager
import com.deep.rcode.feature.terminal.domain.RunState
import com.deep.rcode.feature.terminal.domain.TabColorMarker
import com.deep.rcode.feature.terminal.domain.TerminalSessionManager
import com.deep.rcode.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val settingsRepo: TerminalSettingsRepository,
    private val bundleRepo: TerminalBundleRepository
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

    /** 首屏 Banner 是否已由用户点过「暂不提醒」关闭过。 */
    val firstRunBannerDismissed: StateFlow<Boolean> = settingsRepo.firstRunBannerDismissedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Bundle 安装状态：由 TerminalSettingsScreen 与本页 Banner 共用。 */
    val bundleStates: StateFlow<Map<TerminalBundleId, BundleInstallState>> = bundleRepo.states

    /** 容器是否已安装好（rootfs + proot），基于 containerInit 派生。 */
    val containerInstalled: StateFlow<Boolean> = containerInit
        .map { state ->
            when (state) {
                is ContainerInitState.Ready,
                is ContainerInitState.BundleInstalling,
                is ContainerInitState.BundleUninstalling -> true
                else -> false
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Python bundle 是否已安装（AI Run 前置检查用 & Banner 用）。 */
    val pythonInstalled: StateFlow<Boolean> = bundleStates
        .map { states -> states[TerminalBundleId.PYTHON] is BundleInstallState.Installed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 终端页 Banner 类型：null 表示不显示 Banner。与 UI 同包，便于 UI 直接 when。 */
    sealed interface BannerType {
        /** 容器未初始化 → 提示去终端设置页初始化。 */
        data object ContainerNotInstalled : BannerType
        /** 容器就绪但 Python 未装 → 提示 AI Run 需要它；可以「立即安装」或「暂不提醒」。 */
        data object PythonMissing : BannerType
    }

    /** Banner（容器未装 / Python 未装）是否应当显示：null = 不显示。 */
    val currentBanner: StateFlow<BannerType?> = combine(
        containerInstalled,
        pythonInstalled,
        firstRunBannerDismissed,
        modeHolder.mode
    ) { installed, pythonOk, bannerDismissed, mode ->
        // 远程 SSH 下不展示本地容器相关 Banner。
        if (mode == ExecutionMode.REMOTE_SSH) return@combine null
        when {
            !installed -> BannerType.ContainerNotInstalled
            !pythonOk && !bannerDismissed -> BannerType.PythonMissing
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 用户 Banner 上「暂不提醒」。 */
    fun dismissFirstRunBanner() {
        viewModelScope.launch { settingsRepo.saveFirstRunBannerDismissed(true) }
    }

    /**
     * 从终端页一键「立即装 AI 推荐组合」（Python + rg + Git + Bash + Net）。
     * 容器未初始化时先 ensureInstalled。完成后 currentBanner 自动变为 null。
     */
    fun installAiRecommended() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (!containerInstalled.value) containerEngine.ensureInstalled()
                containerEngine.installBundlesOrdered(TerminalBundles.AI_RECOMMENDED_IDS)
            }.onFailure { _errorToast.value = (it.message ?: "安装 AI 推荐组合失败") }
        }
    }

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

    // ── 标签 Pin 住 / 颜色标记 ──────────────────────────────

    /** 切换标签 Pin 状态 */
    fun togglePin(tabId: String) {
        val tab = tabs.value.firstOrNull { it.id == tabId } ?: return
        tab.isPinned = !tab.isPinned
        // 触发重组
        _refreshTabs()
    }

    /** 设置标签颜色标记 */
    fun setColorMarker(tabId: String, marker: TabColorMarker) {
        val tab = tabs.value.firstOrNull { it.id == tabId } ?: return
        tab.colorMarker = marker
        _refreshTabs()
    }

    private fun _refreshTabs() {
        // 利用 revision 触发重组
        viewModelScope.launch {
            val cur = revision.value
            // 触发 tabs StateFlow 的 collect 感知变化
            @Suppress("UNUSED_EXPRESSION")
            cur
        }
    }

    // ── 二次确认状态 ────────────────────────────────────────

    private val _confirmAction = MutableStateFlow<ConfirmAction?>(null)
    val confirmAction: StateFlow<ConfirmAction?> = _confirmAction.asStateFlow()

    sealed interface ConfirmAction {
        data class CloseTab(val tabId: String, val title: String) : ConfirmAction
        data class CloseOtherTabs(val keepId: String) : ConfirmAction
        data object RestartContainer : ConfirmAction
        data object ReconnectAll : ConfirmAction
    }

    fun consumeConfirmAction() { _confirmAction.value = null }

    // ── 长按浮动菜单（复制/粘贴/剪切/选择/浏览器打开） ────────────

    /** 长按菜单锚点：tabId + 像素坐标（px）+ 当前选中文字（如果已经选中）。
     *  Compose 层根据 showFloatingMenu 时的 anchorPx 在屏幕上定位 FloatingCard。 */
    data class FloatingMenuState(
        val tabId: String,
        val anchorXPx: Float,
        val anchorYPx: Float,
        val hasSelection: Boolean,
        val selectionText: String,
        val selectionStartCol: Int,
        val selectionStartRow: Int,
        val selectionEndCol: Int,
        val selectionEndRow: Int,
        val cutEligibleBytes: Int,
        val detectedUrl: String?
    )

    private val _floatingMenu = MutableStateFlow<FloatingMenuState?>(null)
    val floatingMenu: StateFlow<FloatingMenuState?> = _floatingMenu.asStateFlow()

    /** 触发浮动菜单（由 TerminalView 的 onLongPress 回调调用，
     *  参数包含已通过 Termux API 计算的选中/选区信息。 */
    fun showFloatingMenu(state: FloatingMenuState) { _floatingMenu.value = state }

    fun dismissFloatingMenu() { _floatingMenu.value = null }

    /** 用户点击"选择" → 开始拖动选区（通过 startTextSelectionMode）。 */
    var onRequestStartSelection: ((tabId: String) -> Unit)? = null
    var onRequestStopSelection: ((tabId: String) -> Unit)? = null
    fun startSelection(tabId: String) { onRequestStartSelection?.invoke(tabId) }
    fun stopSelection(tabId: String) { onRequestStopSelection?.invoke(tabId) }

    /** "剪切"请求：先复制选中文本，再向 session 写入 N 个 \x7f (DEL) 完成删除。
     *  执行权交给 Compose 层（它持有 view/session），ViewModel 只负责关闭菜单。 */
    var onPerformCut: ((tabId: String, bytesBack: Int, selectedText: String) -> Unit)? = null
    fun performCut(tabId: String, bytesBack: Int, selectedText: String) {
        onPerformCut?.invoke(tabId, bytesBack, selectedText)
        _floatingMenu.value = null
    }

    /** "复制"请求（同上，仅复制不删）。 */
    var onPerformCopy: ((text: String) -> Unit)? = null
    fun performCopy(text: String) {
        onPerformCopy?.invoke(text)
        _floatingMenu.value = null
    }

    /** "粘贴"。 */
    var onPerformPaste: ((tabId: String) -> Unit)? = null
    fun performPaste(tabId: String) {
        onPerformPaste?.invoke(tabId)
        _floatingMenu.value = null
    }

    /** "浏览器打开"：把检测到的 URL 通过 Intent 扔给系统。 */
    var onOpenUrl: ((String) -> Unit)? = null
    fun openUrl(url: String) {
        onOpenUrl?.invoke(url)
        _floatingMenu.value = null
    }

    /** 请求关闭标签（有运行中进程时弹确认） */
    fun requestCloseTab(tabId: String) {
        val tab = tabs.value.firstOrNull { it.id == tabId } ?: return
        if (tab.runState is RunState.Running && !tab.isBackground) {
            _confirmAction.value = ConfirmAction.CloseTab(tabId, tab.title)
        } else {
            closeTab(tabId)
        }
    }

    /** 请求关闭其他标签 */
    fun requestCloseOtherTabs(keepId: String) {
        _confirmAction.value = ConfirmAction.CloseOtherTabs(keepId)
    }

    /** 请求重启容器 */
    fun requestRestartContainer() {
        _confirmAction.value = ConfirmAction.RestartContainer
    }

    /** 请求重连全部标签 */
    fun requestReconnectAll() {
        _confirmAction.value = ConfirmAction.ReconnectAll
    }

    /** 执行确认后的操作 */
    fun executeConfirmedAction(action: ConfirmAction) {
        when (action) {
            is ConfirmAction.CloseTab -> closeTab(action.tabId)
            is ConfirmAction.CloseOtherTabs -> closeOtherTabs(action.keepId)
            is ConfirmAction.RestartContainer -> restartContainer()
            is ConfirmAction.ReconnectAll -> reconnectAll()
        }
        _confirmAction.value = null
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

