package com.R.codecore.core.network

import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * 系统解析失败的公共 DNS 兜底（对应 PRoot 容器内 resolv.conf 的阿里公共 DNS 策略，见 ContainerInstaller#728）。
 *
 * 背景：App 进程走的是 Android 系统 resolver（[Dns.SYSTEM] → InetAddress），在部分被网络分流 /
 * 私人 DNS(DoT/DoH) 劫持的环境下，系统可能解析不出某些域名（典型报错 "Unable to resolve host"），
 * 但同一域名通过公共 DNS 却能解析。本项目容器内即用 223.5.5.5 兜底，这里给 App 进程网络栈同样的兜底。
 *
 * 行为：
 *  - 优先用 [primary]（通常是带短 TTL 缓存的 [CachingDns]）解析，成功则直接返回；
 *  - 仅当 primary 抛异常 / 返回空时才回退：用 UDP 直连 [nameservers] 查 A 记录，绕过系统 resolver；
 *  - 兜底查询各 nameserver 依次尝试，单次超时 [timeoutMs]，全失败才把 primary 的原始异常重新抛出，
 *    绝不吞掉真实解析失败、也不给上层喂脏地址。
 */
class PublicDnsFallback(
    private val primary: Dns = Dns.SYSTEM,
    private val nameservers: List<String> = listOf("223.5.5.5", "223.6.6.6", "8.8.8.8"),
    private val timeoutMillis: Long = 2000L,
) : Dns {

    private val random = Random.Default

    override fun lookup(hostname: String): List<InetAddress> {
        val primaryFailure = try {
            val addresses = primary.lookup(hostname)
            if (addresses.isNotEmpty()) return addresses
            null
        } catch (e: Exception) {
            e
        }

        if (hostname.isBlank()) throw UnknownHostException("empty host")

        var lastFailure: Exception = primaryFailure ?: UnknownHostException(hostname)
        for (ns in nameservers) {
            try {
                return udpQueryA(hostname, InetAddress.getByName(ns))
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        // 兜底耗尽：抛 primary 原始异常（保持调用方对真实 DNS 失败的语义理解一致性）
        throw (primaryFailure ?: lastFailure)
    }

    /** 直接向指定 nameserver 发标准 DNS A 记录查询（RFC 1035），返回解析到的 IPv4 地址。 */
    private fun udpQueryA(hostname: String, server: InetAddress): List<InetAddress> {
        val message = buildQuery(hostname)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis.toInt()
            val request = DatagramPacket(message, message.size, server, 53)
            val responseBuf = ByteArray(512)
            val response = DatagramPacket(responseBuf, responseBuf.size)
            socket.send(request)
            socket.receive(response)
            return parseResponse(responseBuf, response.length, hostname)
        }
    }

    /** 构造 DNS 查询报文：header(12B) + QDCOUNT=1 的 A 记录查询。 */
    private fun buildQuery(hostname: String): ByteArray {
        val id = random.nextInt(0xFFFF).toShort().toInt() and 0xFFFF
        var size = 12 + hostname.length + 2 + 4
        val buf = ByteArray(size)
        var pos = 0
        // Header
        put16(buf, pos, id); pos += 2          // ID
        put16(buf, pos, 0x0100); pos += 2      // flags: RD=1
        put16(buf, pos, 1); pos += 2           // QDCOUNT
        put16(buf, pos, 0); pos += 2           // ANCOUNT
        put16(buf, pos, 0); pos += 2           // NSCOUNT
        put16(buf, pos, 0); pos += 2           // ARCOUNT
        // Question: QNAME (labels)
        for (label in hostname.split('.')) {
            buf[pos++] = label.length.toByte()
            for (c in label) buf[pos++] = c.code.toByte()
        }
        buf[pos++] = 0                          // root label
        put16(buf, pos, 1); pos += 2           // QTYPE = A
        put16(buf, pos, 1); pos += 2           // QCLASS = IN
        return buf
    }

    /** 解析响应。跳过 header + question，遍历 answer 提取 A 类型 RDATA。 */
    private fun parseResponse(buf: ByteArray, length: Int, hostname: String): List<InetAddress> {
        if (length < 12) throw UnknownHostException("short dns response for $hostname")
        val rcode = (buf[3].toInt() and 0x0F)
        if (rcode != 0) throw UnknownHostException("dns rcode=$rcode for $hostname")
        val ancount = getU16(buf, 6)

        var pos = 12
        // 跳过 Question（QDCOUNT=1）
        pos = skipName(buf, pos) + 4

        val answers = ArrayList<InetAddress>(ancount)
        for (i in 0 until ancount) {
            pos = skipName(buf, pos)
            if (pos + 10 > length) break
            val type = getU16(buf, pos); pos += 2
            pos += 2                             // CLASS
            pos += 4                             // TTL
            val rdlen = getU16(buf, pos); pos += 2
            if (type == 1 && rdlen == 4 && pos + 4 <= length) {
                val bytes = byteArrayOf(buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3])
                answers += InetAddress.getByAddress(hostname, bytes)
            }
            pos += rdlen
        }
        return answers
    }

    /** 读取一个域名（可能含 CNAME 指针 0xC0），返回下一字段偏移。 */
    private fun skipName(buf: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            if (pos >= buf.size) return start
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2  // 压缩指针：占 2 字节
            pos += len + 1
        }
    }

    private fun getU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun put16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}