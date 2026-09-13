package com.mini.me_core.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 明文 HTTP 探测拦截器：挂在主 OkHttpClient 上，探测非回环地址的 `http://` 请求并上报
 * [HttpWarningBridge]，由全局弹窗提示用户改用 HTTPS（安全审计 P2-7）。
 *
 * 仅上报、不拦截：应用需兼容本地明文端点（内网 LLM、本地 MCP 等），提示是安全指引而非强阻断。
 * 回环地址（localhost / 127.0.0.1 / ::1）跳过，避免内置 MCP 服务器、mihomo 控制面等本地端点误报。
 */
class HttpWarningInterceptor(
    private val bridge: HttpWarningBridge
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.isHttps || url.host.isLoopback()) {
            return chain.proceed(request)
        }
        bridge.report(url.host, url.toString())
        return chain.proceed(request)
    }

    private fun String.isLoopback(): Boolean =
        this == "localhost" || this == "127.0.0.1" || this == "::1"
}
