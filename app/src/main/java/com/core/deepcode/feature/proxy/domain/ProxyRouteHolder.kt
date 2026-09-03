package com.core.deepcode.feature.proxy.domain

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 网络层（共享 OkHttp）的代理路由开关托底。
 *
 * 设计（《网络代理 v1.0》§4.2 / §10.9）：rootless proot 下内核级 TUN 不可行，App 进程与容器共享
 * loopback，因此把经 App 网络栈的流量（WebFetch / MCP / 模型 API / T2I 等）也收敛到同一个
 * mihomo mixed-port —— 通过给共享 OkHttpClient 注入 [selector] 实现。
 *
 * **为什么用这个独立的 Holder 而不是直接读 DataStore**：`ProxySelector.select` 是每个连接阻塞回调，
 * 不能挂起读 Flow；且 `ClashProxyManager` 与共享 `OkHttpClient` 存在依赖关系，若让 OkHttp 直接依赖
 * 管理器会成环。此 Holder 无任何依赖、只存两个 [Volatile] 值，由 [ClashProxyManager] 在开关变化时写入，
 * OkHttp 侧只读它。单例、零环。
 */
@Singleton
class ProxyRouteHolder @Inject constructor() {

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var proxyAddress: String = "127.0.0.1:${ClashProxyManager.MIXED_PORT}"
        private set

    /** 由 [ClashProxyManager] 在代理开关切换时写入。 */
    fun update(enabled: Boolean, address: String) {
        this.enabled = enabled
        this.proxyAddress = address
    }

    /**
     * 网络层优化 C5：AI 接口直连/代理分流开关（可配置策略，默认关 = 保持全走代理）。
     * 由 [ClashProxyManager] 收集 DataStore 设置写入；开启后已知 AI host 跳过代理直连，
     * 去掉代理一跳延迟；若所在网络直连不通（依赖代理才能访问模型接口），关闭即可回退代理。
     */
    @Volatile
    var aiHostsDirect: Boolean = false
        private set

    fun setAiHostsDirect(value: Boolean) {
        this.aiHostsDirect = value
    }

    /**
     * 供共享 OkHttp 注入的 [ProxySelector]：未启用直连；启用时走 mihomo mixed-port，
     * 但 loopback 与内网保持直连（[isNoProxy]），避免把自己服务代理出去；
     * 若开启分流（[aiHostsDirect]），已知 AI host（[isKnownAiHost]）同样直连，其余走代理。
     */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            if (!this@ProxyRouteHolder.enabled) return listOf(Proxy.NO_PROXY)
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (host.isBlank() || isNoProxy(host)) return listOf(Proxy.NO_PROXY)
            // C5：分流开启且命中已知 AI host → 直连（省代理一跳）
            if (this@ProxyRouteHolder.aiHostsDirect && isKnownAiHost(host)) {
                return listOf(Proxy.NO_PROXY)
            }
            return listOf(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(hostPart(), portPart())
                )
            )
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 关代理即直连，connectFailed 无需处理
        }

        private fun hostPart(): String {
            val addr = this@ProxyRouteHolder.proxyAddress
            val i = addr.lastIndexOf(':')
            return if (i > 0) addr.substring(0, i) else "127.0.0.1"
        }

        private fun portPart(): Int {
            val addr = this@ProxyRouteHolder.proxyAddress
            val i = addr.lastIndexOf(':')
            return if (i in 0 until addr.length - 1) {
                addr.substring(i + 1).toIntOrNull() ?: ClashProxyManager.MIXED_PORT
            } else ClashProxyManager.MIXED_PORT
        }

        private fun isNoProxy(host: String): Boolean = run {
            if (host == "localhost") return true
            if (host.endsWith(".local") || host.endsWith(".localhost")) return true
            if (host.startsWith("127.")) return true
            if (host.startsWith("10.")) return true
            if (host.startsWith("192.168.")) return true
            if (host.startsWith("172.16.") || host.startsWith("172.17.") ||
                host.startsWith("172.18.") || host.startsWith("172.19.") ||
                host.startsWith("172.20.") || host.startsWith("172.21.") ||
                host.startsWith("172.22.") || host.startsWith("172.23.") ||
                host.startsWith("172.24.") || host.startsWith("172.25.") ||
                host.startsWith("172.26.") || host.startsWith("172.27.") ||
                host.startsWith("172.28.") || host.startsWith("172.29.") ||
                host.startsWith("172.30.") || host.startsWith("172.31.")
            ) {
                return true
            }
            false
        }
    }

    companion object {
        /**
         * 已知模型接口 host（与 ConnectionPrewarmer 的默认预热列表保持一致）。
         * 分流（[aiHostsDirect]）开启时这些 host 直连、跳过代理；用户自定义 base URL 的 host
         * 不在列表内，仍走代理。做精确匹配避免误伤同域其它服务。
         */
        val KNOWN_AI_HOSTS = setOf(
            "api.openai.com",
            "api.anthropic.com",
            "generativelanguage.googleapis.com"
        )
    }
}

/** C5：host 是否命中已知 AI host 列表。 */
private fun isKnownAiHost(host: String): Boolean =
    ProxyRouteHolder.KNOWN_AI_HOSTS.contains(host)