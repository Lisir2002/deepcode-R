package com.deep.rcode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.deep.rcode.core.util.FileLogger
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 管理 MasterKey（Android Keystore）与 DEK（Data Encryption Key）的双层密钥体系。
 *
 * 职责：
 * 1. 生成/加载 MasterKey（AES-256，Android Keystore 存储，不可导出）。
 * 2. 用 MasterKey wrap/unwrap DEK（DEK 是 256-bit AES 对称密钥，持久化在 Room 的
 *    [CredentialEncryptionStateEntity.dekCiphertext]）。
 * 3. 内存缓存已 unwrap 的 DEK（进程级，不持久化）。
 *
 * MasterKey 支持可选的 [setUserAuthenticationRequired(true)] ，开启后 30 秒内
 * 首次解密需要指纹/面容验证。
 */
class DEKManager private constructor() {
    private companion object {
        const val TAG = "DEKManager"
        const val MASTERKEY_ALIAS = "rdeepcode_credential_masterkey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_LEN = 12
        const val DEK_LEN_BITS = 256
    }

    /** 内存缓存的 DEK。进程期有效，App 被杀后丢失。 */
    @Volatile
    private var cachedDek: SecretKey? = null

    // ============== MasterKey 管理 ==============

    /**
     * 获取或生成 MasterKey。
     * @param biometricRequired 是否要求生物识别才能使用该密钥。
     */
    fun getOrCreateMasterKey(biometricRequired: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existingEntry = keyStore.getEntry(MASTERKEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        FileLogger.i(TAG, "生成新的 MasterKey（biometricRequired=$biometricRequired）")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            MASTERKEY_ALIAS,
            KeyProperties.PURPOSE_WRAP_KEY or KeyProperties.PURPOSE_UNWRAP_KEY
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (biometricRequired) {
            specBuilder.setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(30)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    /** 获取已存在的 MasterKey 指纹（SHA-256 of its encoded bytes）。 */
    fun getMasterKeyFingerprint(masterKey: SecretKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(masterKey.encoded))
    }

    /** 检查 MasterKey 是否在 Keystore 中存在。 */
    fun isMasterKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.containsAlias(MASTERKEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    /** 删除 MasterKey（Keystore 中的密钥）。 */
    fun deleteMasterKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(MASTERKEY_ALIAS)
            cachedDek = null
            FileLogger.i(TAG, "已删除 MasterKey")
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除 MasterKey 失败", e)
        }
    }

    // ============== DEK 管理 ==============

    /** 生成新的随机 DEK（256-bit AES）。 */
    fun generateDek(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        keyGenerator.init(DEK_LEN_BITS)
        return keyGenerator.generateKey()
    }

    /**
     * 用 MasterKey wrap DEK → Base64 密文字符串。
     * 结果持久化到 [CredentialEncryptionStateEntity.dekCiphertext]。
     */
    fun wrapDek(masterKey: SecretKey, dek: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.WRAP_MODE, masterKey)
        val wrapped = cipher.wrap(dek)
        return Base64.getEncoder().encodeToString(wrapped)
    }

    /**
     * 用 MasterKey unwrap DEK 密文 → 内存 SecretKey。
     * 结果缓存到 [cachedDek]。
     */
    fun unwrapDek(masterKey: SecretKey, dekCiphertextB64: String): SecretKey {
        val wrapped = Base64.getDecoder().decode(dekCiphertextB64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.UNWRAP_MODE, masterKey)
        val dek = cipher.unwrap(wrapped, KeyProperties.KEY_ALGORITHM_AES, Cipher.SECRET_KEY)
        cachedDek = dek as SecretKey
        return cachedDek!!
    }

    /** 获取内存缓存的 DEK（如果存在）。 */
    fun getCachedDek(): SecretKey? = cachedDek

    /** 清除内存 DEK 缓存。 */
    fun clearDekCache() {
        cachedDek = null
    }

    companion object {
        @Volatile
        private var instance: DEKManager? = null

        fun getInstance(): DEKManager {
            return instance ?: synchronized(this) {
                instance ?: DEKManager().also { instance = it }
            }
        }
    }
}