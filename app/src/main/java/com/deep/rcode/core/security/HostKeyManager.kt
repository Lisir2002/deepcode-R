package com.deep.rcode.core.security

import android.content.Context
import com.deep.rcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH 主机密钥验证管理器。
 *
 * 使用 sshj 的 [OpenSSHKnownHosts] 读写标准 OpenSSH known_hosts 格式文件，
 * 实现首次连接接受并保存主机密钥、后续连接严格校验的「信任首次使用」(TOFU) 策略。
 *
 * known_hosts 文件存储在应用内部存储，仅本应用可读写。
 * 若主机密钥变更（例如服务器重装），将抛 [HostKeyChangedException] 提醒用户。
 */
@Singleton
class HostKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HostKeyManager"
        const val KNOWN_HOSTS_FILE = "ssh_known_hosts"
    }

    private val knownHostsFile: File
        get() = File(context.filesDir, KNOWN_HOSTS_FILE)

    /**
     * 获取 [HostKeyVerifier] 实例。
     *
     * 验证策略：
     * 1. 如果主机 + 端口 + 密钥类型在 known_hosts 中已有记录，严格比对密钥指纹。
     * 2. 如果是首次连接（无记录），自动接受并持久化密钥。
     * 3. 如果主机已存在但密钥不同，抛 [HostKeyChangedException] 阻止连接。
     */
    fun createVerifier(): HostKeyVerifier {
        return object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                return this@HostKeyManager.verifyKey(hostname, port, key)
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
                val file = knownHostsFile
                if (!file.exists()) return emptyList()
                val entries = try {
                    readKnownHosts(file)
                } catch (e: Exception) {
                    return emptyList()
                }
                val hostKey = if (port == 22 || port <= 0) hostname else "[$hostname]:$port"
                // 从该主机的 saved fingerprint 中提取算法类型（fingerprint 第一段即 KeyType 名称）
                return entries
                    .filter { it.first == hostKey }
                    .mapNotNull { entry ->
                        entry.second.substringBefore(" ").trim().takeIf { it.isNotEmpty() }
                    }
                    .distinct()
            }
        }
    }

    private fun verifyKey(hostname: String, port: Int, key: PublicKey): Boolean {
        val file = knownHostsFile
        // 读取已有的 known_hosts 条目
        val entries = if (file.exists()) {
            try {
                readKnownHosts(file)
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取 known_hosts 失败，将重建: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        // 转为标准 host:port 格式用于匹配
        val hostKey = if (port == 22 || port <= 0) hostname else "[$hostname]:$port"
        val keyType = KeyType.fromKey(key)
        val fingerprint = keyType.toString() + " " + java.util.Base64.getEncoder().encodeToString(key.encoded)

        // 查找该主机已有的密钥记录
        val existing = entries.filter { it.first == hostKey }

        if (existing.isEmpty()) {
            // 首次连接：接受并保存密钥
            FileLogger.i(TAG, "首次连接 $hostKey，接受并保存主机密钥 ($keyType)")
            try {
                // 追加到 known_hosts 文件
                val line = "$hostKey $fingerprint\n"
                file.appendText(line)
            } catch (e: Exception) {
                FileLogger.w(TAG, "保存主机密钥失败: ${e.message}")
            }
            return true
        }

        // 已有记录：验证密钥是否匹配
        val matched = existing.any { (_, savedFingerprint) ->
            savedFingerprint == fingerprint
        }

        if (matched) {
            FileLogger.v(TAG, "主机密钥验证通过: $hostKey")
            return true
        }

        // 密钥不匹配！可能是服务器重装或 MITM 攻击
        val savedType = existing.first().second.substringBefore(" ")
        val savedKey = existing.first().second.substringAfter(" ").take(32) + "..."
        FileLogger.w(TAG, "主机密钥不匹配! host=$hostKey savedType=$savedType savedKey=$savedKey")
        throw HostKeyChangedException(
            host = hostname,
            port = port,
            expectedType = savedType,
            message = "SSH 主机密钥已变更! 主机 $hostname:$port 的密钥与之前记录的不一致。" +
                    "这可能是服务器重新安装系统（正常）或中间人攻击（危险）的迹象。" +
                    "请确认后手动删除 known_hosts 文件重新连接。"
        )
    }

    /**
     * 读取 known_hosts 文件，返回 (hostKey, fingerprint) 列表。
     */
    private fun readKnownHosts(file: File): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            // 格式: hostname key-type base64-key
            val parts = trimmed.split(" ")
            if (parts.size >= 3) {
                // host 部分可能是 [hostname]:port 或 hostname
                val host = parts[0]
                val fingerprint = parts.drop(1).joinToString(" ")
                result.add(host to fingerprint)
            }
        }
        return result
    }

    /**
     * 清除所有已保存的主机密钥。用于"重置 SSH 信任"场景。
     */
    fun clearAllKeys() {
        if (knownHostsFile.exists()) {
            knownHostsFile.delete()
            FileLogger.i(TAG, "已清除所有 saved host keys")
        }
    }

    /**
     * 清除指定主机的密钥记录。
     */
    fun clearHostKey(hostname: String, port: Int = 22) {
        val file = knownHostsFile
        if (!file.exists()) return
        val hostKey = if (port == 22 || port <= 0) hostname else "[$hostname]:$port"
        val entries = readKnownHosts(file)
        val filtered = entries.filter { it.first != hostKey }
        file.writeText(filtered.joinToString("\n") { "${it.first} ${it.second}" } + "\n")
        FileLogger.i(TAG, "已清除主机密钥: $hostKey")
    }
}

/** 主机密钥变更异常：服务器密钥与之前记录的不一致。 */
class HostKeyChangedException(
    val host: String,
    val port: Int,
    val expectedType: String,
    message: String
) : SecurityException(message)