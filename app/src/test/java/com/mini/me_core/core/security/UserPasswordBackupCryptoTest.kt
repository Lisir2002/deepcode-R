package com.mini.me_core.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserPasswordBackupCrypto] 纯 JVM 单测：PBKDF2 + AES-128-GCM + HMAC 尾的加解密闭环。
 * 覆盖：round-trip / 错误密码 / 密文篡改 / 非备份文件 / 头嗅探。
 */
class UserPasswordBackupCryptoTest {

    private val crypto = UserPasswordBackupCrypto()

    @Test
    fun encrypt_decrypt_roundTrip() {
        val plain = "MiniMe-core 用户密码加密备份 payload（中文 + emoji 🚀）".toByteArray(Charsets.UTF_8)
        val password = "correct horse battery staple".toCharArray()

        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, password).use { it.write(plain) }
        val encrypted = out.toByteArray()

        // 文件结构：64B header + ciphertext(+16B GCM tag) + 32B HMAC 尾
        assertTrue(encrypted.size > 64 + 16 + 32)

        val decrypted = crypto.decryptingInputStream(ByteArrayInputStream(encrypted), password)
            .use { it.readBytes() }
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun encrypt_emptyPayload_roundTrip() {
        val password = "pw".toCharArray()
        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, password).use { /* 空 payload */ }
        val decrypted = crypto.decryptingInputStream(ByteArrayInputStream(out.toByteArray()), password)
            .use { it.readBytes() }
        assertTrue(decrypted.isEmpty())
    }

    @Test
    fun decrypt_wrongPassword_throws() {
        val plain = "encrypted payload".toByteArray(Charsets.UTF_8)
        val password = "right-password".toCharArray()

        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, password).use { it.write(plain) }

        assertThrows(UserPasswordBackupCrypto.BackupWrongPasswordException::class.java) {
            crypto.decryptingInputStream(
                ByteArrayInputStream(out.toByteArray()),
                "wrong-password".toCharArray()
            ).use { it.readBytes() }
        }
    }

    @Test
    fun decrypt_tamperedCiphertext_throws() {
        val password = "pw".toCharArray()
        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, password).use { it.write("payload".toByteArray(Charsets.UTF_8)) }

        val bytes = out.toByteArray()
        // 翻转 64B header 之后的 payload 区一个字节 → HMAC 校验必然失败
        bytes[64] = (bytes[64].toInt() xor 0x01).toByte()

        assertThrows(UserPasswordBackupCrypto.BackupWrongPasswordException::class.java) {
            crypto.decryptingInputStream(ByteArrayInputStream(bytes), password).use { it.readBytes() }
        }
    }

    @Test
    fun decrypt_tamperedHmacTail_throws() {
        val password = "pw".toCharArray()
        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, password).use { it.write("payload".toByteArray(Charsets.UTF_8)) }

        val bytes = out.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()

        assertThrows(UserPasswordBackupCrypto.BackupWrongPasswordException::class.java) {
            crypto.decryptingInputStream(ByteArrayInputStream(bytes), password).use { it.readBytes() }
        }
    }

    @Test
    fun decrypt_notBackupFile_throwsTampered() {
        val garbage = ByteArray(64) { 0x42 }
        assertThrows(UserPasswordBackupCrypto.BackupTamperedException::class.java) {
            crypto.decryptingInputStream(ByteArrayInputStream(garbage), "pw".toCharArray())
        }
    }

    @Test
    fun decrypt_tooShort_throwsTampered() {
        // 不足 64B header
        assertThrows(UserPasswordBackupCrypto.BackupTamperedException::class.java) {
            crypto.decryptingInputStream(ByteArrayInputStream(ByteArray(10)), "pw".toCharArray())
        }
    }

    @Test
    fun sniffHeader_validAndInvalid() {
        val out = ByteArrayOutputStream()
        crypto.encryptingOutputStream(out, "pw".toCharArray()).use { it.write("x".toByteArray(Charsets.UTF_8)) }

        val info = crypto.sniffHeader(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(info)
        assertEquals(1, info!!.version.toInt())
        assertFalse(info.iteration <= 0)
        assertEquals(16, info.salt.size)
        assertEquals(12, info.iv.size)

        assertNull(crypto.sniffHeader(ByteArrayInputStream(ByteArray(64) { 0x42 })))
        // 输入不足 64B 也不抛，返回 null
        assertNull(crypto.sniffHeader(ByteArrayInputStream(ByteArray(3))))
    }
}
