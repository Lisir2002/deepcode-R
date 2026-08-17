package com.R.codecore.feature.terminal.domain

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.RemoteSshConnection
import com.R.codecore.feature.settings.data.repository.ExecutionMode
import com.R.codecore.feature.settings.data.repository.ExecutionModeHolder
import com.R.codecore.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.R.codecore.feature.terminal.presentation.component.TextInputTracker
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {

    init {
        // SSH 断线自动重连 → 重建所有 Running 的交互 tab（后台命令 tab 不碰，避免重复副作用）。
        // 监听在构造期注册，RemoteSshConnection 用 ConcurrentHashMap 保存监听器；
        // 如果之后 connection 生命周期长于本类（目前双方都是 @Singleton，等同），
        // 也无需 unregister——进程生命周期一致。
        connection.registerOnReconnectedListener {
            runCatching { reconnectAllInteractiveRunningTabs() }
                .onFailure { FileLogger.w(TAG, "重连后重建交互 tab 失败", it) }
        }
    }

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val tabOpLock = ReentrantLock()

    @Volatile
    private var ensureTabJob: Job? = null

    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 仅当当前模式是 REMOTE_SSH 且已连接时才可使用。 */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?,
        workdir: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(command, isBackground = true, notify = notify, title = title, sourceSessionId = sourceSessionId, workdir = workdir)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?,
        workdir: String? = null
    ): String {
        val id = nextId()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = withContext(Dispatchers.IO) {
            connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
        }
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // shell 登录后默认在 home，先 cd 到工作区，与命令执行链路（RemoteSshEngine.buildCdCommand）保持一致：
        // 优先 ~/workspace 符号链接，失败回退到真实工作区路径；工具传入 workdir（T-5）时优先用它。
        val wsPath = workdir?.takeIf { it.isNotBlank() }
            ?: workspaceRepository.currentPath()
        if (wsPath.isNotBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd '${wsPath.trimEnd('/')}' 2>/dev/null\n")
        }
        if (command != null) {
            val init = command + (if (notify) "" else "; exec /bin/sh")
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        tab.session.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        tab.session.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    override fun closeTab(id: String): Boolean = tabOpLock.withLock {
        val snapshot = _tabs.value
        val idx = snapshot.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val tab = snapshot[idx]

        // ① 先移除 tab + 修正 activeId，避免 UI 再渲染即将销毁的 View
        val remaining = snapshot.toMutableList().apply { removeAt(idx) }
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        _tabs.value = remaining
        bumpRevision()

        // ② 解绑定 View → 置空引用
        runCatching {
            val v = tab.view
            if (v is TerminalView) {
                v.setTerminalViewClient(null)
                v.attachSession(null)
            }
        }
        tab.view = null

        // ③ 下一帧主线程空闲时再 finish 会话（对齐本地实现）
        remoteScope.launch(Dispatchers.Main.immediate) {
            runCatching { tab.session.finishIfRunning() }
                .onFailure { e -> FileLogger.w(TAG, "finish 远程会话 $id 异常（忽略）", e) }
        }

        FileLogger.i(TAG, "关闭远程终端标签 $id · 剩余 ${remaining.size} 个")
        true
    }

    /** 对应 [TerminalSessionManager.ensureAtLeastOneTab]，语义一致。 */
    fun ensureAtLeastOneTab(scope: CoroutineScope) {
        if (_tabs.value.isNotEmpty()) {
            ensureTabJob?.cancel()
            ensureTabJob = null
            return
        }
        if (ensureTabJob?.isActive == true) return
        ensureTabJob = scope.launch(Dispatchers.Main) {
            var waited = 0L
            val stepMs = 30L
            val totalMs = 150L
            while (waited < totalMs) {
                if (_tabs.value.isNotEmpty()) return@launch
                delay(stepMs); waited += stepMs
            }
            // suspend 函数不可持锁 → 先锁内判空再锁外执行
            val shouldCreate = tabOpLock.withLock {
                _tabs.value.isEmpty() && ensureRemote()
            }
            if (shouldCreate) {
                runCatching { createInteractiveTab() }
                    .onFailure { e -> FileLogger.e(TAG, "自动新建兜底远程标签失败", e) }
            }
        }
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** 重连：关闭旧会话并重建交互 shell。 */
    suspend fun reconnect(id: String) {
        val old = tab(id) ?: return
        runCatching { old.session.finishIfRunning() }
        recreateInteractiveShellForTab(old)
    }

    /**
     * 自动重连所有「仍处于 Running 状态的交互 shell tab」。
     * 由 RemoteSshConnection.registerOnReconnectedListener 在 SSH 断线重连成功后调用。
     *
     * 故意忽略：
     *  - 后台命令 tab（!isBackground=false）：断连后命令已经死了，自动重启可能导致
     *    重复执行（比如 `rm`、`git push` 这类副作用命令），安全上不能自动重启。
     *  - Finished 状态的 tab：用户自己 exit 的，不需要复活。
     */
    suspend fun reconnectAllInteractiveRunningTabs() {
        if (!ensureRemote()) return
        val snapshot = tabOpLock.withLock { _tabs.value.toList() }
        val targets = snapshot.filter { !it.isBackground && it.runState is RunState.Running }
        if (targets.isEmpty()) return
        FileLogger.i(TAG, "SSH 重连成功，自动重建 ${targets.size} 个交互 shell tab: ${targets.joinToString { it.id }}")
        for (old in targets) {
            runCatching { old.session.finishIfRunning() }
            runCatching { recreateInteractiveShellForTab(old) }
                .onFailure { FileLogger.e(TAG, "重建交互 tab ${old.id} 失败", it) }
        }
    }

    /** 按旧 tab 元数据重建一个 shell session（保持 id/title/运行状态）。 */
    private suspend fun recreateInteractiveShellForTab(old: TerminalTab) {
        val session = connection.startShellSession().also { it.allocateDefaultPTY() }
        val shell = session.startShell()
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // 与 openShellTab 对齐：先切到 ~/workspace 符号链接（由 updateWorkspaceSymlink 创建），
        // 失败回退到 connection.config.remoteWorkspacePath 的真实路径，保证用户重连后仍在工作区内。
        val wsPath = connection.config?.remoteWorkspacePath
        if (!wsPath.isNullOrBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd '${wsPath.trimEnd('/')}' 2>/dev/null\n")
        }
        val newTab = TerminalTab(
            id = old.id,
            title = old.title,
            session = termSession,
            isBackground = old.isBackground,
            command = old.command,
            notifyOnExit = old.notifyOnExit,
            sourceSessionId = old.sourceSessionId,
            runState = RunState.Running
        )
        _tabs.value = _tabs.value.map { if (it.id == old.id) newTab else it }
        bumpRevision()
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            tab.session.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
        }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {

        private var lastCursorRow = -1
        private var lastCursorCol = -1

        override fun onTextChanged(changedSession: TerminalSession) {
            val tab = _tabs.value.firstOrNull { it.session === changedSession }
            val view = tab?.view
            val tracker = tab?.let(TextInputTracker::forTab)
            val emu = view?.mEmulator
            if (emu != null && tracker != null) {
                val newRow = emu.cursorRow
                val newCol = emu.cursorCol
                val changed = (newRow != lastCursorRow) || (newCol != lastCursorCol)
                tracker.syncCursor(newRow, newCol, changed)
                lastCursorRow = newRow
                lastCursorCol = newCol
            }
            view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                target.runState = RunState.Finished(0)
                bumpRevision()
                if (target.notifyOnExit) {
                    _tabFinishedEvents.tryEmit(
                        TabFinishedEvent(
                            target.id, target.title, target.command, 0, target.sourceSessionId,
                            tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                        )
                    )
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
