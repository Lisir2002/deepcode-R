package com.R.codecore.core.network

import com.R.codecore.core.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接预热（网络层优化设计 C1）。
 *
 * 对目标 AI host 后台发起一个最小请求（GET /），预建 DNS + TCP + TLS + HTTP/2 握手，
 * 连接留在共享 OkHttp 连接池，正式请求复用后省掉 1~3 RTT 的首字节延迟（TTFT）。
 * 预热走同一个共享 [OkHttpClient]，因此代理开启时天然走代理链路，行为与正式请求一致。
 *
 * 安全约束：
 * - 每个 host 只预热一次（[warmed] 去重），后续调用直接返回；
 * - 失败静默（可能误预热 / 网络不通），仅记 debug 日志，绝不影响任何主链路；
 * - 在独立 [CoroutineScope]（IO + SupervisorJob）中执行，不阻塞启动与首帧。
 */
@Singleton
class ConnectionPrewarmer @Inject constructor(
    private val client: OkHttpClient
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmed = ConcurrentHashMap.newKeySet<String>()

    /** 三家默认 AI host（用户自定义 baseUrl 的 host 会在首次正式请求时自然建连）。 */
    fun warmDefaults() {
        DEFAULT_HOSTS.forEach { warm(it) }
    }

    /** 预热指定 URL 的 host；已预热过则忽略。 */
    fun warm(url: String) {
        val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull() ?: url
        if (!warmed.add(host)) return
        scope.launch {
            runCatching {
                val request = Request.Builder().url(url).method("GET", null).build()
                client.newCall(request).execute().use { }
            }.onFailure {
                FileLogger.d(TAG, "连接预热失败（忽略，不影响主链路）: $url ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ConnectionPrewarmer"

        private val DEFAULT_HOSTS = listOf(
            "https://api.openai.com/",
            "https://api.anthropic.com/",
            "https://generativelanguage.googleapis.com/"
        )
    }
}
