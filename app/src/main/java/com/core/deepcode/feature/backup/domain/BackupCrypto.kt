package com.core.deepcode.feature.backup.domain

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件的对称加密：PBKDF2WithHmacSHA256 派生密钥 + AES/GCM/NoPadding 加密。
 *
 * 口令不落盘、不记忆；盐与 IV 随每次加密随机生成并写入文件头。GCM 自带完整性校验，
 * 口令错误或文件被篡改时解密抛 [BackupDecryptionException]。
 *
 * 流式实现手动分块 [Cipher.update]/[Cipher.doFinal]，不用 CipherOutputStream/CipherInputStream：
 * 避免 Android GCM 流式下 flush 触发 update 语义、以及 CipherInputStream 吞掉 doFinal 校验异常
 * 导致口令错误检测不到的坑。
 */
class BackupDecryptionException(cause: Throwable? = null) : IllegalArgumentException(
    "备份口令错误，或加密备份文件已损坏；若备份未加密，请留空口令",
    cause
)

object BackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val CHUNK_SIZE = 64 * 1024

    const val SALT_LEN = 16
    const val IV_LEN = 12

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { random.nextBytes(it) }
    fun newIv(): ByteArray = ByteArray(IV_LEN).also { random.nextBytes(it) }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LEN_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /** 流式加密：先写 salt+IV 头，再分块加密 [input] 全部内容写入 [output]，末尾追加 GCM tag。 */
    fun encryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val salt = newSalt()
        val iv = newIv()
        output.write(salt)
        output.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) output.write(cipher.update(buffer, 0, n))
        }
        output.write(cipher.doFinal())
        output.flush()
    }

    /** 流式解密：读 salt+IV 头，分块解密 [input] 写入 [output]，末尾 doFinal 校验 GCM tag。口令错误抛 [BackupDecryptionException]。 */
    fun decryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val salt = readFully(input, SALT_LEN)
        val iv = readFully(input, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) output.write(cipher.update(buffer, 0, n))
        }
        try {
            output.write(cipher.doFinal())
        } catch (e: BadPaddingException) {
            throw BackupDecryptionException(e)
        }
        output.flush()
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val result = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(result, offset, len - offset)
            if (n < 0) throw IllegalArgumentException("不是有效的加密 DeepCore-Code 备份文件")
            offset += n
        }
        return result
    }
}
