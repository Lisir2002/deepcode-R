package com.mini.me_core.core.security

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用户密码加密备份：PBKDF2-HMAC-SHA256 + AES-128-GCM + HMAC-SHA256 完整性校验。
 *
 * 文件格式：
 *   - 头 64B：magic(2) = 0x5243 'RC' | version(1)=0x01 | salt(16) | iter(4, BE) | iv(12) | reserved(29)
 *   - 中间：AES-128-GCM(dataKey, payload tar.gz bytes) + 16B GCM tag (由 CipherOutputStream close 自动写出)
 *   - 尾 32B：HMAC-SHA256(hmacKey, header || entireCiphertext || gcmTag)
 *
 * 密钥派生：password → PBKDF2-HMAC-SHA256(120k iter) → 256-bit keyMaterial
 *    → split: dataKey(前 128bit) + hmacKey(后 128bit)
 *
 * 跨设备可用：不依赖 Android Keystore。
 */
class UserPasswordBackupCrypto {

    private companion object {
        const val MAGIC: Short = 0x5243.toShort() // 'RC'
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 64
        const val PBKDF2_ITER = 120_000
        const val TAG_LEN_BITS = 128
        const val HMAC_LEN = 32
        const val KEY_LEN_BITS = 256
        const val DATA_KEY_LEN = 16 // 128 bit
        const val HMAC_KEY_LEN = 16 // 128 bit
        const val IV_LEN = 12
        const val GCM_TAG_LEN = 16
    }

    /**
     * 密码错误的异常。
     */
    class BackupWrongPasswordException : Exception("密码不正确或备份文件已损坏")
    class BackupTamperedException : Exception("不是有效的 MiniMe-core 加密备份文件")

    /**
     * 加密写入：返回的 OutputStream 关流时自动写 GCM tag + HMAC 尾。
     * 使用时需要确保外层 OutputStream 在 finally 中 close。
     */
    fun encryptingOutputStream(
        rawOut: OutputStream,
        password: CharArray,
        explicitIter: Int = PBKDF2_ITER
    ): OutputStream {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(IV_LEN).apply { SecureRandom().nextBytes(this) }
        val (dataKey, hmacKey) = deriveKeys(password, salt, explicitIter)

        // 写 64B header
        val header = buildHeader(salt, explicitIter, iv)
        rawOut.write(header)

        // AES-128-GCM 加密层
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))

        // 流式 tee：边写 rawOut 边对 (header || ciphertext || gcmTag) 累加 HMAC，
        // 不把整个密文缓冲在内存（备份包可能很大）。
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(header)
        val teeOut = object : OutputStream() {
            override fun write(b: Int) {
                mac.update(b.toByte())
                rawOut.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                mac.update(b, off, len)
                rawOut.write(b, off, len)
            }
        }

        val cipherOut = CipherOutputStream(teeOut, cipher)
        return object : FilterOutputStream(cipherOut) {
            override fun close() {
                // CipherOutputStream.close() 会写出 GCM tag（tee 已同步累加进 HMAC）
                cipherOut.close()
                // 写 32B HMAC 尾：HMAC(header || ciphertext || gcmTag)
                rawOut.write(mac.doFinal())
                rawOut.flush()
            }
        }
    }

    /**
     * 解密读取：返回的 InputStream 自动验证 HMAC + GCM 解密。
     * @throws BackupWrongPasswordException 密码错或文件损坏
     * @throws BackupTamperedException 文件头不是 RC v1 格式
     */
    fun decryptingInputStream(rawIn: InputStream, password: CharArray): InputStream {
        val header = ByteArray(HEADER_SIZE).also { readFully(rawIn, it) }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.short
        if (magic != MAGIC) throw BackupTamperedException()
        val version = buf.get()
        if (version != VERSION) throw BackupTamperedException()
        val salt = ByteArray(16).also { buf.get(it) }
        val iter = buf.int
        val iv = ByteArray(IV_LEN).also { buf.get(it) }

        val (dataKey, hmacKey) = deriveKeys(password, salt, iter)

        // 读取 payload（含 GCM tag）+ HMAC 尾
        val payloadWithTag = rawIn.readBytes()
        if (payloadWithTag.size < GCM_TAG_LEN + HMAC_LEN) {
            throw BackupWrongPasswordException()
        }
        val ciphertextWithTag = payloadWithTag.copyOfRange(0, payloadWithTag.size - HMAC_LEN)
        val storedHmac = payloadWithTag.copyOfRange(payloadWithTag.size - HMAC_LEN, payloadWithTag.size)

        // 验证 HMAC
        val computedHmac = computeHmac(hmacKey, header, ciphertextWithTag)
        if (!MessageDigest.isEqual(computedHmac, storedHmac)) {
            throw BackupWrongPasswordException()
        }

        // GCM 解密
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_LEN_BITS, iv))
        val plaintext = cipher.doFinal(ciphertextWithTag)
        return plaintext.inputStream()
    }

    /**
     * 嗅探备份文件头：从 [rawIn] 前 64B 解析元信息（不消费 payload）。
     * 返回 null 说明不是 RC v1 文件。
     */
    fun sniffHeader(rawIn: InputStream): BackupHeaderInfo? {
        return try {
            rawIn.mark(HEADER_SIZE)
            val header = ByteArray(HEADER_SIZE).also { readFully(rawIn, it) }
            rawIn.reset()
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            if (buf.short != MAGIC) return null
            val version = buf.get()
            if (version != VERSION) return null
            val salt = ByteArray(16).also { buf.get(it) }
            val iter = buf.int
            val iv = ByteArray(IV_LEN).also { buf.get(it) }
            BackupHeaderInfo(version, iter, salt, iv)
        } catch (e: Exception) {
            null
        }
    }

    data class BackupHeaderInfo(
        val version: Byte,
        val iteration: Int,
        val salt: ByteArray,
        val iv: ByteArray
    )

    // ============== 内部辅助 ==============

    private fun deriveKeys(password: CharArray, salt: ByteArray, iteration: Int): Pair<ByteArray, ByteArray> {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iteration, KEY_LEN_BITS)
        val keyMaterial = factory.generateSecret(spec).encoded
        return keyMaterial.copyOf(DATA_KEY_LEN) to keyMaterial.copyOfRange(DATA_KEY_LEN, DATA_KEY_LEN + HMAC_KEY_LEN)
    }

    private fun buildHeader(salt: ByteArray, iter: Int, iv: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(MAGIC)
            put(VERSION)
            put(salt)
            putInt(iter)
            put(iv)
            // 剩余 29 bytes 保留为 0
        }
        return header
    }

    private fun computeHmac(hmacKey: ByteArray, header: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(header)
        mac.update(ciphertextWithTag)
        return mac.doFinal()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw BackupTamperedException()
            offset += read
        }
    }
}