package com.deep.rcode.feature.terminal.domain

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY 重建：系统会用 null intent 再次调 onStartCommand。
        // 如果 intent 为空，说明 Service 被杀后系统重建了我们，所有 session/persistent 状态都已丢。
        // 此时若不调 startForeground，Android 12+ 会在 10s 内抛
        // ForegroundServiceDidNotStartInTimeException 并强制杀掉 App。
        // 这里安全兜底：无论 intent 是否为空、能否识别 action，都确保前台通知就位。
        runCatching {
            when (intent?.action) {
                ACTION_START_SESSION -> {
                    sessionCount++
                    ensureForeground()
                    FileLogger.i(TAG, "Session started, count=$sessionCount")
                }
                ACTION_STOP_SESSION -> {
                    sessionCount = (sessionCount - 1).coerceAtLeast(0)
                    if (sessionCount == 0 && !persistent) {
                        stopSelf(startId)
                        FileLogger.i(TAG, "All sessions ended, stopping service")
                    } else {
                        ensureForeground()
                        FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                    }
                }
                ACTION_ENABLE_PERSISTENT -> {
                    persistent = true
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive enabled")
                }
                ACTION_DISABLE_PERSISTENT -> {
                    persistent = false
                    if (sessionCount == 0) {
                        stopSelf(startId)
                        FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                    } else {
                        ensureForeground()
                        FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                    }
                }
                else -> {
                    // RC61b 兜底分支：
                    //   a) null intent → START_STICKY 重建，状态丢失
                    //   b) 未知 action → 不明确意图
                    // 保守处理：立即 enter foreground，避免 DID_NOT_START_IN_TIME 崩溃
                    FileLogger.w(TAG, "onStartCommand intent=$intent 无法识别，保活进入安全前台兜底")
                    ensureForeground()
                }
            }
        }.onFailure { ex ->
            FileLogger.e(TAG, "onStartCommand 分支异常，尝试前台兜底", ex)
            runCatching { ensureForeground() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 进入前台并刷新通知；文案随「常驻保活 / 后台会话」组合变化。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.deep.rcode.MainActivity] 恢复）。
     */
    private fun ensureForeground() {
        val text = when {
            sessionCount > 0 && persistent -> "后台保活 · 终端任务: $sessionCount"
            sessionCount > 0 -> "运行中的终端任务: $sessionCount"
            else -> "后台保活已开启"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        const val ACTION_START_SESSION = "com.deep.rcode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.deep.rcode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.deep.rcode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.deep.rcode.action.DISABLE_PERSISTENT"

        /** 开启常驻保活（幂等）。 */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "enablePersistent startService failed", it) }
        }

        /** 关闭常驻保活（幂等）。仅在确曾开启过时调用，避免为关闭而凭空拉起 Service。 */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }
    }
}
