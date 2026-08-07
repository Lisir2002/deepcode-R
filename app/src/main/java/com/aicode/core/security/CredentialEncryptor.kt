package com.aicode.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore 加密工具。
 *
 * 使用硬件级 Android Keystore 生成并存储 AES-256 密钥，
 * 通过 AES/GCM/NoPadding 对敏感数据（API Key、Token）进行加密和解密。
 *
 * 特性：
 * - 密钥由 Android Keystore 安全存储，不可导出
 * - AES-256-GCM 认证加密，自带完整性校验
 * - 每次加密生成随机 IV
 * - 输出格式: Base64(IV + ciphertext)，IV 长度 12 字节
 *
 * 使用示例：
 * ```
 * val encrypted = encryptor.encrypt("sk-xxxxx")
 * val decrypted = encryptor.decrypt(encrypted) // "sk-xxxxx"
 * ```
 */
@Singleton
class CredentialEncryptor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "CredentialEncryptor"
        const val KEYSTORE_ALIAS = "aicode_credential_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_LEN = 12
    }

    private var cachedKey: SecretKey? = null

    /**
     * 获取或生成 AES-256 密钥。
     * 密钥存储在 Android Keystore 中，首次调用时自动生成。
     */
    private fun getOrCreateKey(): SecretKey {
        // 先尝试从缓存获取
        cachedKey?.let { return it }

        // 从 Android Keystore 加载密钥
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            cachedKey = existingKey.secretKey
            return cachedKey!!
        }

        // 密钥不存在，生成新的 AES-256 密钥
        FileLogger.i(TAG, "生成新的 AES-256 密钥到 Android Keystore")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        cachedKey = keyGenerator.generateKey()
        return cachedKey!!
    }

    /**
     * 加密明文。
     * @param plaintext 明文字符串（如 API Key）
     * @return Base64 编码的密文（IV + ciphertext + GCM tag）
     * @throws IllegalStateException 如果加密失败
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 格式: IV(12 bytes) + ciphertext
            val combined = iv + ciphertext
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            FileLogger.e(TAG, "加密失败", e)
            throw IllegalStateException("加密凭据失败: ${e.message}", e)
        }
    }

    /**
     * 解密密文。
     * @param encrypted Base64 编码的密文（由 [encrypt] 产生）
     * @return 解密后的明文字符串
     * @throws IllegalStateException 如果解密失败（密钥被重置、数据损坏等）
     */
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val combined = Base64.getDecoder().decode(encrypted)

            if (combined.size < IV_LEN) {
                throw IllegalArgumentException("密文数据长度不足")
            }

            val iv = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            FileLogger.e(TAG, "解密失败", e)
            throw IllegalStateException("解密凭据失败: ${e.message}", e)
        }
    }

    /**
     * 检查密钥是否存在。
     */
    fun isKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.containsAlias(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除密钥。用于"重置凭据加密"场景。
     * 注意：删除后所有已加密的凭据将无法解密！
     */
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
            cachedKey = null
            FileLogger.i(TAG, "已删除 Android Keystore 中的加密密钥")
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除密钥失败", e)
        }
    }
}