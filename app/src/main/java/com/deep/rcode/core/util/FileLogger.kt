package com.deep.rcode.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
        Log.v(tag, message)
        write("VERBOSE", tag, message, null)
    }

    fun d(tag: String, message: String) {
        if (!shouldLog(LogLevel.DEBUG)) return
        Log.d(tag, message)
        write("DEBUG", tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (!shouldLog(LogLevel.INFO)) return
        Log.i(tag, message)
        write("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.WARN)) return
        Log.w(tag, message, throwable)
        write("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.ERROR)) return
        Log.e(tag, message, throwable)
        write("ERROR", tag, message, throwable)
    }

    /** 返回当前日志目录，供日志查看器使用。 */
    fun getLogDir(): File? = logDir

    /**
     * 紧急同步落盘：不进 ioExecutor 队列，阻塞当前线程直接把一行写到今天的日志文件。
     * 仅用于「线程即将崩溃、进程马上被系统杀」这种最后一刻。ioExecutor 的排队任务
     * 会因进程被杀而全部丢失（这正是用户说「闪退拿不到日志」的根因），因此 CrashHandler
     * 必须绕过异步，保证 CRASH 记录在 return 前已经 fsync-ish 落盘。
     */
    fun flushSync(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        val now = java.time.Instant.now()
        val line = buildString {
            append(timestampFormat.format(now))
            append(" ").append(level)
            append(" [").append(tag).append("] ")
            append(message)
            if (throwable != null) {
                append("\n").append(stackTraceToString(throwable))
            }
            append("\n")
        }
        runCatching {
            val file = File(dir, "log-${fileNameFormat.format(now)}.txt")
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText("--- 日志文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 已重置 ---\n")
            }
            file.appendText(line)
            // 再尝试 flush 到 OS（不保证 fsync，但对 Java IO 已尽力），
            // 避免后续立即杀进程导致缓冲行丢失。
            runCatching { java.io.FileOutputStream(file, true).use { it.channel?.force(false) } }
        }.onFailure {
            // 紧急日志本身再失败，就只 logcat——此时 IO 基本挂了，也没法再兜
            android.util.Log.e(TAG, "紧急同步落盘失败", it)
        }
    }

    /** 返回当前所有日志文件，按文件名（即日期）排序，供"查看日志"等界面使用。 */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return // 未初始化则只走 logcat，不落盘
        val now = java.time.Instant.now()
        val line = buildString {
            append(timestampFormat.format(now))
            append(" ").append(level)
            append(" [").append(tag).append("] ")
            append(message)
            if (throwable != null) {
                append("\n").append(stackTraceToString(throwable))
            }
            append("\n")
        }
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "log-${fileNameFormat.format(now)}.txt")
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
