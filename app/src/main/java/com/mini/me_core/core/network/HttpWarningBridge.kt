package com.mini.me_core.core.network

import com.mini.me_core.core.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 明文 HTTP 请求警告桥：OkHttp 拦截器探测到非回环地址的 `http://` 请求时上报，
 * 全局弹窗宿主（[com.mini.me_core.core.ui.GlobalHttpWarningDialogHost]）订阅本桥在任意页面弹出安全提示。
 *
 * 设计约束（对齐 CredentialRequestBridge 的全局弹窗模式，安全审计 P2-7）：
 * - 每个 App 进程会话只提示首个明文请求，避免模型/工具每轮请求都刷弹窗（进程重启后重新提示）；
 * - 回环地址（localhost / 127.0.0.1 / ::1）不触发——内置 MCP 服务器、mihomo 控制面等本地明文端点
 *   属预期行为，提示反而造成噪音。
 */
@Singleton
class HttpWarningBridge @Inject constructor() {

    data class HttpWarning(
        val host: String,
        val url: String
    )

    private val _warning = MutableStateFlow<HttpWarning?>(null)

    /** 当前待提示的明文请求；null 表示无。会话内首次上报后不再覆盖。 */
    val warning: StateFlow<HttpWarning?> = _warning.asStateFlow()

    /** 会话内是否已提示过（进程常驻期间只提示一次，重启后重新提示）。 */
    @Volatile
    private var reported = false

    fun report(host: String, url: String) {
        if (reported) return
        synchronized(this) {
            if (reported) return
            reported = true
            _warning.value = HttpWarning(host, url)
            FileLogger.w(TAG, "检测到明文 HTTP 请求：$url")
        }
    }

    /** 用户已读（关掉弹窗），清空当前提示。 */
    fun dismiss() {
        _warning.value = null
    }

    private companion object {
        const val TAG = "HttpWarningBridge"
    }
}
