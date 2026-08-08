package com.deep.rcode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.LinuxContainerEngine
import com.deep.rcode.feature.terminal.presentation.component.AppTerminalSessionClient
import com.deep.rcode.feature.terminal.presentation.component.TextInputTracker
import com.deep.rcode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内常驻的终端会话池：所有 [TerminalSession] 的唯一所有者。
 *
 * 之所以放在 [Singleton] 而非 ViewModel：终端要「常驻后台」，离开终端页/切到聊天页都不能杀会话。
 * ViewModel 绑定在导航路由上、出栈即 onCleared，会连带 finishIfRunning 杀掉 proot——故把会话
 * 所有权上移到本管理器，ViewModel 退化为只读观察层。只要 App 进程还活着，会话就一直在跑。
 *
 * 每个标签有稳定且对 AI 友好的唯一 id（`term-N`）。AI 可凭 id：
 *  - [startBackgroundCommand] 把 `npm run dev` 之类挂后台并拿到 id；
 *  - [sendInput] 按 id 持续发命令；
 *  - [writeBytesToTab] 按 id 发送控制字符（如 Ctrl-C=0x03）；
 *  - [closeTab] 按 id 关闭并销毁会话；
 *  - [getTabOutput] 按 id 读终端内容（emulator 屏幕缓冲）。
 *
 * 所有可变状态读写都在主线程（UI 事件、AI 工作流派发到主线程的调用），不额外加锁。
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {
    private companion object {
        const val TAG = "TerminalSessionManager"
        const val TRANSCRIPT_ROWS = 2000
        // 无视图挂载时用于「就地启动」会话的默认终端尺寸；视图挂载后会按真实尺寸 resize。
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        /** 命令打印 `[command exited: N]` 后等待正常 onFinished 回调的缓冲（毫秒）。 */
        const val EXIT_MARKER_GRACE_MS = 1_500L
        /** 完成兜底监控轮询屏幕缓冲的间隔（毫秒）。 */
        const val EXIT_MARKER_POLL_MS = 200L
        /** 匹配命令退出标记 `[command exited: N]`。 */
        val EXIT_MARKER_REGEX = Regex("\\[command exited: (\\d+)]")
    }

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** 触发任一标签输出/状态变化时自增，供 Compose 重组拉取最新屏幕内容。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /**
     * 新建一个交互 shell 标签并设为当前。返回新标签 id。
     *
     * 容器未就绪（rootfs/proot 没解压或解压失败）时，fallback 到手机原生 /system/bin/sh：
     * 保证首次进入终端不会白屏或一直卡在 Loading；用户可见 Banner 提示去安装完整环境。
     */
    suspend fun createInteractiveTab(): String {
        val installed = runCatching { ensureContainer(); containerEngine.isContainerInstalled() }
            .getOrDefault(false)
        val id = nextId()
        val shellCommand = if (installed) {
            "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; exec ${containerEngine.defaultShell()}"
        } else {
            // 原生 sh：把 cwd 设到 app 私有 files/workspace（WorkspaceRepository 保证目录存在）；
            // 写一个 MOTD 告诉用户此为 fallback shell、仅基础命令、apk/包管理不可用。
            val cwd = workspaceRepository.currentPath()
            val motd = listOf(
                "╔══════════════════════════════════════════════════╗",
                "║  ⚠️  本地容器未初始化，当前使用原生 Android sh     ║",
                "║                                                   ║",
                "║  • 可用基础命令：ls/cd/cat/echo/grep/ps/toybox   ║",
                "║  • apk/pip/npm/git/curl 等工具不可用              ║",
                "║  • 点顶栏「齿轮 → 初始化环境」解锁 Alpine Linux    ║",
                "╚══════════════════════════════════════════════════╝",
                ""
            ).joinToString("\\n")
            "cd \"$cwd\" 2>/dev/null; printf '%b\\n' \"$motd\"; exec /system/bin/sh -i"
        }
        val session = if (installed) buildSession(shellCommand) else buildNativeFallbackSession(shellCommand)
        addTab(
            TerminalTab(
                id = id,
                title = id,
                session = session,
                isBackground = false,
                command = null,
                runState = RunState.Running
            )
        )
        _activeTabId.value = id
        FileLogger.i(TAG, "新建交互终端标签 $id（${if (installed) "容器模式" else "原生 sh fallback"}）")
        return id
    }

    /**
     * 供 AI 预留接口：把一条命令挂后台跑（如 `npm run dev`），返回唯一 tabId。
     *
     * 命令跑完后 `exec /bin/sh` 保活，使该标签仍是一个可继续输入的会话（dev server 退出后也能复用），
     * 且输出全程留在 emulator 缓冲里，用户切过去或 AI 用 [getTabOutput] 都能看到累计输出。
     */
    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        val installed = runCatching { ensureContainer(); containerEngine.isContainerInstalled() }
            .getOrDefault(false)
        val id = nextId()
        val shellCommand: String
        if (installed) {
            val afterCommand = if (notify) "; exit \$ec" else "; exec ${containerEngine.defaultShell()}"
            shellCommand = "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        } else {
            // 容器未安装时，AI 发起的后台命令不真正执行：直接 echo 错误 + exit，提醒用户先初始化容器。
            val err = "容器未初始化，本地后台命令暂不支持。请先在终端页点齿轮→初始化环境。"
            shellCommand = buildString {
                append("echo \"$err\" 1>&2;")
                append(" echo \"[command exited: 100]\";")
                append(if (notify) " exit 100" else " exec /system/bin/sh -i")
            }
        }
        val session = if (installed) buildSession(shellCommand) else buildNativeFallbackSession(shellCommand)
        addTab(
            TerminalTab(
                id = id,
                title = title ?: id,
                session = session,
                isBackground = true,
                command = command,
                notifyOnExit = notify,
                sourceSessionId = sourceSessionId,
                runState = RunState.Running
            )
        )
        // 后台命令不抢占当前标签焦点：仅当没有活动标签时才设为当前。
        if (_activeTabId.value == null) _activeTabId.value = id

        if (notify) monitorBackgroundExit(id)

        startKeepaliveService()
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /** 按 id 向标签发送输入并回车执行（AI 持续发命令的入口）。返回是否命中标签且仍活跃。 */
    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始文本，不自动追加回车。 */
    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { writeToSession(it.session, text) }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    /**
     * 按 id 读取终端内容（emulator 屏幕缓冲的完整 transcript），供 AI 拉取。
     * 返回 null 表示无此标签。
     */
    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    /** 列出全部标签的摘要（id/标题/是否后台/运行状态/命令），供 AI 选目标。 */
    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    /** 切换当前标签。 */
    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** 关闭并销毁标签（用户主动关 / AI close）。从列表移除并杀会话。 */
    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()

        if (tab.isBackground && remaining.none { it.isBackground && it.runState is RunState.Running }) {
            stopKeepaliveService()
        }
        FileLogger.i(TAG, "关闭终端标签 $id")
        return true
    }

    /** 重连：重建该标签的交互会话（仅对交互标签有意义）。 */
    suspend fun reconnect(id: String) {
        val old = tab(id) ?: return
        runCatching { old.session.finishIfRunning() }
        val installed = runCatching { ensureContainer(); containerEngine.isContainerInstalled() }
            .getOrDefault(false)
        val session = if (installed) {
            buildSession("cd ~/workspace 2>/dev/null; export ENV=/etc/profile; exec ${containerEngine.defaultShell()}")
        } else {
            val cwd = workspaceRepository.currentPath()
            buildNativeFallbackSession("cd \"$cwd\" 2>/dev/null; exec /system/bin/sh -i")
        }
        val newTab = TerminalTab(
            id = old.id,
            title = old.title,
            session = session,
            isBackground = old.isBackground,
            command = old.command,
            runState = RunState.Running
        )
        _tabs.value = _tabs.value.map { if (it.id == id) newTab else it }
        bumpRevision()
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    private suspend fun ensureContainer() {
        runCatching { containerEngine.ensureInstalled() }
    }

    /**
     * 构造一个原生 shell 会话（不依赖 proot/rootfs）：仅使用 Android 系统自带的
     * /system/bin/sh 作为可执行文件。容器未装好时的 fallback，保证终端页面永远可用。
     */
    private fun buildNativeFallbackSession(shellCommand: String): TerminalSession {
        val cwd = appContext.filesDir.absolutePath
        // 以 /system/bin/sh -c '...' 启动；env 保留基础 HOME/TMPDIR/PATH。
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    target.runState = RunState.Finished(finished.exitStatus)
                    bumpRevision()
                    FileLogger.i(TAG, "（原生 fallback）标签 ${target.id} 会话结束 exit=${finished.exitStatus}")
                    if (target.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
                        stopKeepaliveService()
                    }
                    if (target.notifyOnExit && !target.finishedNotified) {
                        target.finishedNotified = true
                        _tabFinishedEvents.tryEmit(
                            TabFinishedEvent(
                                target.id, target.title, target.command, finished.exitStatus, target.sourceSessionId,
                                tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                            )
                        )
                    }
                }
            },
            inputTrackerProvider = {
                val tab = _tabs.value.firstOrNull { it.session === session }
                    ?: return@AppTerminalSessionClient null
                TextInputTracker.forTab(tab)
            }
        )
        val envArray = arrayOf(
            "HOME=$cwd",
            "TMPDIR=${appContext.cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "SHELL=/system/bin/sh",
            "TERM=xterm-256color",
            "PS1=> \\u@\\h:\\w\\$ "
        )
        session = TerminalSession(
            "/system/bin/sh",
            cwd,
            arrayOf("sh", "-c", shellCommand),
            envArray,
            TRANSCRIPT_ROWS,
            client
        )
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        return session
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /**
     * 完成兜底监控：termux 的会话结束依赖 PTY 读到 EOF，而 proot 宿主进程在 Android 上可能
     * 概率性不退出（即使其 bash 已执行 exit，proot 仍攥着 /dev/pts 不释放），导致 onFinished
     * 永不回调、notify=true 的任务不触发通知。bash 在真正退出前必打印 `[command exited: N]`，
     * 以此作为命令结束的可靠信号：监控到后短缓冲（给正常回调留时间），仍 Running 则强制收尾。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    if (EXIT_MARKER_REGEX.containsMatchIn(output)) seenMarker = true
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // 缓冲后再查：正常 onFinished 回调若已触发，状态不再 Running，此处直接退出。
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = EXIT_MARKER_REGEX.find(getTabOutput(tabId) ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        current.runState = RunState.Finished(exitCode)
                        bumpRevision()
                        FileLogger.i(TAG, "兜底：标签 $tabId 检测到退出标记，强制收尾 exit=$exitCode")
                        if (current.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
                            stopKeepaliveService()
                        }
                        if (current.notifyOnExit && !current.finishedNotified) {
                            current.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                                )
                            )
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun writeToSession(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * 构造一个进入容器的 PTY 会话，并接好输出/结束回调。
     *
     * client 的 viewProvider/onFinished 都以 session 为键回查 [_tabs]：会话与标签一一对应，
     * 故无需把 tab 引用提前注入 client（避免「构造 client 时 tab 还不存在」的先有鸡先有蛋）。
     */
    private fun buildSession(shellCommand: String): TerminalSession {
        val workspace = workspaceRepository.currentPath()
        val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    target.runState = RunState.Finished(finished.exitStatus)
                    bumpRevision()
                    FileLogger.i(TAG, "终端标签 ${target.id} 会话结束 exit=${finished.exitStatus}")
                    if (target.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
                        stopKeepaliveService()
                    }
                    if (target.notifyOnExit && !target.finishedNotified) {
                        target.finishedNotified = true
                        _tabFinishedEvents.tryEmit(
                            TabFinishedEvent(
                                target.id, target.title, target.command, finished.exitStatus, target.sourceSessionId,
                                tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                            )
                        )
                    }
                }
            },
            inputTrackerProvider = {
                val tab = _tabs.value.firstOrNull { it.session === session }
                    ?: return@AppTerminalSessionClient null
                TextInputTracker.forTab(tab)
            }
        )
        session = TerminalSession(
            invocation.executable,
            appContext.filesDir.absolutePath,
            invocation.argv.toTypedArray(),
            invocation.ptyEnvArray,
            TRANSCRIPT_ROWS,
            client
        )
        // 立刻用默认尺寸初始化 emulator——这一步才会真正 fork 出 proot 子进程并起 I/O 读写线程。
        // 否则进程只会在 TerminalView 挂载（其 updateSize）时才启动：后台命令（如 npm run dev）
        // 在用户打开终端页之前永远不会真正运行。视图之后挂载只是按真实尺寸 resize，已累积输出仍保留。
        // 注意：buildSession 始终在主线程被调用（AI 工具走 Dispatchers.Main、ViewModel 走 viewModelScope），
        // 故 session 的 MainThreadHandler 绑定到主 Looper，这里同线程调用 updateSize 是安全的。
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        return session
    }

    private fun startKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_START_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已启动")
    }

    private fun stopKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_STOP_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已停止")
    }
}
