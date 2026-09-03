package com.core.deepcode.core.network

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp 异步 DNS 缓存（网络层优化设计 C3）。
 *
 * 包装系统解析 [Dns.SYSTEM]，以短 TTL 缓存 host → IP 的解析结果，避免流式首字节前
 * 每次都走系统 DNS（弱网下几十~几百 ms，直接抬升 TTFT）。
 *
 * 安全约束（设计 §5.4 弱网回退）：
 * - TTL 设短（默认 60s），IP 变化最多滞后一个 TTL，可接受；
 * - 解析失败时立即清除该条目并原样抛出（回退到系统解析语义，由 OkHttp/上层重试接管），
 *   绝不把脏条目喂回连接层；
 * - 单 host 失败不影响其他 host（[ConcurrentHashMap] 分 key 隔离）。
 */
class CachingDns(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val delegate: Dns = Dns.SYSTEM
) : Dns {

    private class Entry(val addresses: List<InetAddress>, val expireAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { entry ->
            if (now < entry.expireAtMillis) return entry.addresses
        }
        return try {
            val addresses = delegate.lookup(hostname)
            cache[hostname] = Entry(addresses, now + ttlMillis)
            addresses
        } catch (e: Exception) {
            cache.remove(hostname)
            throw e
        }
    }

    companion object {
        private const val DEFAULT_TTL_MILLIS = 60_000L
    }
}
