package com.deep.rcode.core.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * 结构化日志条目。写入/读取/过滤共用同一数据模型。
 *
 * @property timestamp 发生时间（Instant）
 * @property level     日志等级
 * @property tag       日志标签（通常是类名/模块名）
 * @property message   日志消息（不含堆栈）。MCP 相关的消息惯例以「[服务器名]」开头。
 * @property throwableStack 异常堆栈字符串（可空）
 */
data class LogEntry(
    val timestamp: Instant,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableStack: String? = null
) {
    /**
     * 派生分类：从 [message] 前缀尝试识别「[MCP 服务器名]」，否则用 [tag] 的简洁版。
     * 例如：
     * - tag=McpManager, message="[my-server] 连接成功" → category = "my-server"
     * - tag=SettingsViewModel, message="..." → category = "SettingsViewModel"（即显示为 "App" 组：在 UI 里统一归类）
     */
    val category: String get() = messagePrefixServerName() ?: tag

    private fun messagePrefixServerName(): String? {
        if (message.length < 3 || message[0] != '[') return null
        val end = message.indexOf(']', startIndex = 1)
        if (end <= 1) return null
        // 中间必须非空且不含换行，避免匹配到 message 中间随机的 [xxx]
        val inner = message.substring(1, end)
        if (inner.isBlank() || inner.contains('\n')) return null
        return inner
    }

    companion object {
        /** 日志行前缀的时间戳 + 等级 + tag 格式（与 write() 保持严格一致）。 */
        private val LINE_HEADER_REGEX = Regex(
            """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(VERBOSE|DEBUG|INFO|WARN|ERROR)\s+\[([^\]]+)\]\s*(.*)$"""
        )
        private val HEADER_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /**
         * 从日志文件的一行解析出 LogEntry。解析失败返回 null（多行堆栈或空行由调用方处理）。
         */
        fun parseLine(line: String): LogEntry? {
            val match = LINE_HEADER_REGEX.matchEntire(line) ?: return null
            val (tsStr, levelStr, tag, message) = match.destructured
            val instant = runCatching {
                LocalDateTime.parse(tsStr, HEADER_TIMESTAMP_FORMAT).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull() ?: Instant.now()
            val level = runCatching { LogLevel.valueOf(levelStr) }.getOrNull() ?: LogLevel.INFO
            return LogEntry(
                timestamp = instant,
                level = level,
                tag = tag,
                message = message
            )
        }
    }
}

/**
 * 日志等级，由低到高。[NONE] 用作阈值时关闭一切输出（没有任何等级 ≥ NONE）。
 *
 * 顺序即严重程度：阈值 [FileLogger.minLevel] 之下的日志（logcat 与落盘）都会被丢弃。
 */
enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
}

/**
 * 把日志落盘到 App 的内部存储，方便在没有连接 adb 的情况下调试。
 *
 * 日志写到外部私有目录 `getExternalFilesDir/logs/` 下（不可用时回退到内部 `filesDir/logs/`），
 * 按天分文件（log-yyyy-MM-dd.txt）。外部私有目录可通过文件管理器在
 * `/storage/emulated/0/Android/data/<包名>/files/logs/` 直接查看，无需 root。
 * 所有写入都串行化到单线程后台执行，避免阻塞主线程与多线程交错。同时镜像一份到 [android.util.Log]。
 *
 * 支持按等级过滤：低于 [minLevel] 的日志（含 logcat 镜像与落盘）一律跳过。等级由
 * `LogSettingsRepository` 持久化、`AIEditorApp` 在启动与设置变更时通过 [setMinLevel] 同步。
 * 开发期默认 [LogLevel.VERBOSE]（全量记录）。
 *
 * 使用前需在 [Application.onCreate] 调用一次 [init]。
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // 单个日志文件上限 5MB（VERBOSE 下增长较快）

    /** 实时日志流 replay 容量：进入查看界面可立即看到最近一批日志。 */
    private const val REPLAY_ENTRIES = 500

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "file-logger").apply { isDaemon = true }
    }
    private val fileNameFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault())
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())

    @Volatile
    private var logDir: File? = null

    /** 当前最低记录等级；低于它的日志一律跳过。默认 VERBOSE（开发期全量）。 */
    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE
        private set

    /**
     * 实时结构化日志流（只读）。写入成功即 emit，replay 最近 [REPLAY_ENTRIES] 条，
     * 供查看界面订阅实现"进入即显示 + 新日志滚动追加"。
     */
    val entryFlow: SharedFlow<LogEntry> get() = _entryFlow.asSharedFlow()
    private val _entryFlow = MutableSharedFlow<LogEntry>(
        replay = REPLAY_ENTRIES,
        extraBufferCapacity = 2000
    )

    /** 设置最低记录等级（线程安全）。由设置项/启动同步调用。 */
    fun setMinLevel(level: LogLevel) {
        if (minLevel != level) {
            minLevel = level
            // 等级变更本身用 logcat 记录，避免被新阈值过滤掉
            Log.i(TAG, "日志等级切换为 $level")
        }
    }

    private fun shouldLog(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /** 初始化日志目录。重复调用安全。 */
    fun init(context: Context) {
        if (logDir != null) return
        // 优先外部私有目录，便于用户用文件管理器查看；不可用时回退内部存储。
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "logs").apply { mkdirs() }
        logDir = dir
        ioExecutor.execute { cleanupOldLogs(dir) }
        i(TAG, "FileLogger 初始化完成，日志目录: ${dir.absolutePath}")
    }

    fun v(tag: String, message: String) {
        if (!shouldLog(LogLevel.VERBOSE)) return
        val entry = LogEntry(Instant.now(), LogLevel.VERBOSE, tag, message)
        Log.v(tag, message)
        emitEntry(entry)
        write(entry, null)
    }

    fun d(tag: String, message: String) {
        if (!shouldLog(LogLevel.DEBUG)) return
        val entry = LogEntry(Instant.now(), LogLevel.DEBUG, tag, message)
        Log.d(tag, message)
        emitEntry(entry)
        write(entry, null)
    }

    fun i(tag: String, message: String) {
        if (!shouldLog(LogLevel.INFO)) return
        val entry = LogEntry(Instant.now(), LogLevel.INFO, tag, message)
        Log.i(tag, message)
        emitEntry(entry)
        write(entry, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.WARN)) return
        val stack = throwable?.let { stackTraceToString(it) }
        val entry = LogEntry(Instant.now(), LogLevel.WARN, tag, message, stack)
        Log.w(tag, message, throwable)
        emitEntry(entry)
        write(entry, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.ERROR)) return
        val stack = throwable?.let { stackTraceToString(it) }
        val entry = LogEntry(Instant.now(), LogLevel.ERROR, tag, message, stack)
        Log.e(tag, message, throwable)
        emitEntry(entry)
        write(entry, throwable)
    }

    private fun emitEntry(entry: LogEntry) {
        runCatching { _entryFlow.tryEmit(entry) }
    }

    /** 返回当前所有日志文件，按文件名（即日期）排序，供"查看日志"等界面使用。 */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 读取指定日志文件并解析为结构化 [LogEntry] 列表（按时间升序，最旧在前）。
     * 连续的非头部行自动合并为上一条日志的 [LogEntry.throwableStack]。
     */
    fun parseLogFile(file: File): List<LogEntry> {
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrElse { return emptyList() }
        val result = ArrayList<LogEntry>(lines.size)
        var current: LogEntry? = null
        val stackBuf = StringBuilder()
        fun flushCurrent() {
            val c = current ?: return
            result += if (stackBuf.isNotEmpty()) {
                c.copy(throwableStack = stackBuf.toString().trimEnd())
            } else c
            current = null
            stackBuf.clear()
        }
        for (line in lines) {
            if (line.isBlank()) continue
            val parsed = LogEntry.parseLine(line)
            if (parsed != null) {
                flushCurrent()
                current = parsed
            } else if (current != null) {
                if (stackBuf.isNotEmpty()) stackBuf.append('\n')
                stackBuf.append(line)
            }
        }
        flushCurrent()
        return result
    }

    private fun write(entry: LogEntry, throwable: Throwable?) {
        val dir = logDir ?: return // 未初始化则只走 logcat，不落盘
        val line = buildString {
            append(timestampFormat.format(entry.timestamp))
            append(" ").append(entry.level.name)
            append(" [").append(entry.tag).append("] ")
            append(entry.message)
            if (throwable != null) {
                append("\n").append(stackTraceToString(throwable))
            }
            append("\n")
        }
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "log-${fileNameFormat.format(entry.timestamp)}.txt")
                if (file.length() > MAX_FILE_BYTES) {
                    // 超过上限则截断重开，避免单文件无限增长
                    file.writeText("--- 日志文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 已重置 ---\n")
                }
                file.appendText(line)
            }.onFailure {
                Log.e(TAG, "写入日志失败", it)
            }
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    /** 删除超过 [MAX_AGE_DAYS] 天的日志文件。 */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }?.forEach { file ->
            if (file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
