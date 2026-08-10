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
 *
 * 线程安全约定：
 *  - [getInstance]：无阻塞，纯 volatile+CAS，主线程/Hilt 注入安全。
 *  - 其他所有方法均含 Keystore IO / 密码运算，必须在 Dispatchers.IO 调用。
 *    调用方 CredentialEncryptor 通过 withContext(Dispatchers.IO) 保证。
 */
class DEKManager private constructor() {
    companion object {
        private const val TAG = "DEKManager"
        private const val MASTERKEY_ALIAS = "rdeepcode_credential_masterkey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LEN = 12
        private const val DEK_LEN_BITS = 256

        @Volatile
        private var instance: DEKManager? = null

        /** 轻量无阻塞：仅 volatile + 双检锁，对象本身无任何 IO，构造函数体为空。 */
        fun getInstance(): DEKManager {
            val cur = instance
            if (cur != null) return cur
            return synchronized(this) {
                val cur2 = instance
                if (cur2 != null) cur2 else DEKManager().also { instance = it }
            }
        }
    }

    /** 内存缓存的 DEK。进程期有效，App 被杀后丢失。 */
    @Volatile
    private var cachedDek: SecretKey? = null

    // ============== MasterKey 管理 ==============

    /**
     * 获取或生成 MasterKey（含 Keystore.load 与 KeyGenerator.generateKey IO）。
     * 必须在 Dispatchers.IO 调用，否则会阻塞主线程导致 ANR。
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
            KeyProperties.PURPOSE_WRAP_KEY or 8 // PURPOSE_UNWRAP_KEY (API 兼容常量)
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

    /** 获取已存在的 MasterKey 指纹（SHA-256 of its encoded bytes）。必须 IO 线程。 */
    fun getMasterKeyFingerprint(masterKey: SecretKey): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(masterKey.encoded))
    }

    /** 检查 MasterKey 是否在 Keystore 中存在。必须 IO 线程。 */
    fun isMasterKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.containsAlias(MASTERKEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    /** 删除 MasterKey（Keystore 中的密钥）。必须 IO 线程。 */
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

    /** 重新生成并覆盖 MasterKey（紧急重置通道：生物识别锁死后使用）。必须 IO 线程。 */
    fun regenerateMasterKey(biometricRequired: Boolean = false): SecretKey {
        deleteMasterKey()
        return getOrCreateMasterKey(biometricRequired)
    }

    // ============== DEK 管理 ==============

    /** 生成新的随机 DEK（256-bit AES）。CPU-only，无 IO，任何线程都 OK。 */
    fun generateDek(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        keyGenerator.init(DEK_LEN_BITS)
        return keyGenerator.generateKey()
    }

    /**
     * 用 MasterKey wrap DEK → Base64 密文字符串。
     * 结果持久化到 [CredentialEncryptionStateEntity.dekCiphertext]。必须 IO 线程。
     */
    fun wrapDek(masterKey: SecretKey, dek: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.WRAP_MODE, masterKey)
        val wrapped = cipher.wrap(dek)
        return Base64.getEncoder().encodeToString(wrapped)
    }

    /**
     * 用 MasterKey unwrap DEK 密文 → 内存 SecretKey。
     * 结果缓存到 [cachedDek]。必须 IO 线程。
     */
    fun unwrapDek(masterKey: SecretKey, dekCiphertextB64: String): SecretKey {
        val wrapped = Base64.getDecoder().decode(dekCiphertextB64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.UNWRAP_MODE, masterKey)
        val iv = ByteArray(IV_LEN)
        // GCM UNWRAP 不需要 IV（wrap 时用的随机 IV 已经在 wrapped 里由 cipher 解析）
        val dek = cipher.unwrap(wrapped, KeyProperties.KEY_ALGORITHM_AES, Cipher.SECRET_KEY)
        cachedDek = dek as SecretKey
        return cachedDek!!
    }

    /** 获取内存缓存的 DEK（如果存在）。volatile 读，任意线程。 */
    fun getCachedDek(): SecretKey? = cachedDek

    /** 清除内存 DEK 缓存。volatile 写，任意线程。 */
    fun clearDekCache() {
        cachedDek = null
    }
}
