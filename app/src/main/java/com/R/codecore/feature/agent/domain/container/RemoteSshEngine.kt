package com.R.codecore.feature.agent.domain.container

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.container.CommandEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * [CommandEngine] 的远程 SSH 实现：用 sshj exec channel 在远程服务器上执行命令。
 *
 * 共享一个 [RemoteSshConnection]（持有 sshj [SSHClient]），与 [RemoteSftpFileAccess]
 * 复用同一 SSH 连接——命令执行用 exec channel，文件读写用 SFTP channel。
 *
 * 与 [LinuxContainerEngine] 的语义对应：
 * - [ensureInstalled]：建立/维持 SSH 连接（对应本地解压 rootfs）；
 * - [isContainerInstalled]：SSH 连接是否存活（对应本地 rootfs 是否就绪）；
 * - [isProvisioned]：恒 true（远程工具由用户自行保证，对应本地 apk 装包完成）；
 * - [defaultShell]：/bin/bash（远程服务器通常有 bash）。
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程流式) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            if (session.isOpen) {
                timedOut.set(true)
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                runCatching { session.close() }
            }
        }
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && session.isOpen) {
                FileLogger.i(TAG, "命令被取消，关闭 session: $command")
                runCatching { session.close() }
            }
        }
        val reader = BufferedReader(InputStreamReader(session.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(CommandEvent.Line(line!!))
            }
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.TimedOut)
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: $command")
                else FileLogger.v(TAG, "命令完成(退出码 0): $command")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.TimedOut)
                emit(CommandEvent.Line("[命令执行超时：超过 ${effectiveTimeout}ms 已被强制终止]"))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): $command", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!isContainerInstalled()) return null
        return runCatching { execCaptured(command, projectPath, timeoutMs) }
            .getOrElse {
                FileLogger.w(TAG, "远程命令执行失败(连接可能已断): $command", it)
                null
            }
    }

    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(远程同步) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val output = BoundedOutput()
        var exitCode: Int? = null
        // 结构化超时标记：看门狗触发（超时强关 session）时置位，供上层返回 TOOL_TIMEOUT。
        val timedOut = AtomicBoolean(false)
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (session.isOpen) {
                        FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                        timedOut.set(true)
                        runCatching { session.close() }
                    }
                }
                val reader = BufferedReader(InputStreamReader(session.inputStream))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line!!)
                        output.append("\n")
                    }
                    exitCode = session.exitStatus
                } finally {
                    watchdog.cancel()
                    runCatching { reader.close() }
                }
            }
        } finally {
            runCatching { session.close() }
        }
        FileLogger.v(TAG, "命令完成(远程, 退出码 $exitCode，输出 ${output.totalChars} 字符): $command")
        CommandResult(output.build(), exitCode, timedOut.get())
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        if (connection.isConnected()) {
            _initProgress.value = ContainerInitState.Ready()
            return@withLock
        }
        _initProgress.value = ContainerInitState.BundleInstalling(bundleId = null, line = "正在连接 SSH 服务器…")
        try {
            connection.connect()
            _initProgress.value = ContainerInitState.Ready()
        } catch (e: Exception) {
            FileLogger.e(TAG, "SSH 连接失败", e)
            val friendly = friendlySshError(e)
            _initProgress.value = ContainerInitState.Failed(friendly)
            throw RuntimeException(friendly, e)
        }
    }

    /** 拼接 cd 到 projectPath 再执行 command 的完整命令；projectPath 为 null 则直接执行。
     *  优先 cd 到 ~/workspace（符号链接），让 AI 执行 pwd 时看到 ~/workspace 而非真实路径。
     *  ~/workspace 不存在（符号链接未建成）时 fallback 到 projectPath。 */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        if (projectPath == null) return command
        return "cd ~/workspace 2>/dev/null || cd '$projectPath' 2>/dev/null; $command"
    }
}
