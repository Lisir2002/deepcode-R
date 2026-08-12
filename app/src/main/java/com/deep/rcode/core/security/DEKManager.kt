package com.deep.rcode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.deep.rcode.core.util.FileLogger
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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

        /**
         * RC61b：Android Keystore 官方 `KeyProperties` 仅提供 `PURPOSE_WRAP_KEY=32`，
         * 并未公开 `PURPOSE_UNWRAP_KEY` 常量，但 Cipher.UNWRAP_MODE 需要该权限位
         * （实际值 = 0x8 = 8，自 API 23 引入 wrap/unwrap 即存在且稳定，与 RC61a 硬编码一致）。
         * 用局部常量替代硬编码：既避免魔法数字，又规避「引用不存在官方符号导致编译失败」。
         */
        private const val PURPOSE_UNWRAP_KEY_COMPAT = 8

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
        // PURPOSE_WRAP_KEY | PURPOSE_UNWRAP_KEY_COMPAT = 允许该 MasterKey 执行 wrap/unwrap DEK。
        // 说明：KeyProperties 仅公开 PURPOSE_WRAP_KEY；PURPOSE_UNWRAP_KEY_COMPAT(8) 为兼容常量，
        //       与 RC61a 已验证的硬编码一致，编译期不受 compileSdk 常量存在性影响。
        val purpose = KeyProperties.PURPOSE_WRAP_KEY or PURPOSE_UNWRAP_KEY_COMPAT
        val specBuilder = KeyGenParameterSpec.Builder(MASTERKEY_ALIAS, purpose)
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

    /**
     * 获取已存在的 MasterKey 指纹。必须 IO 线程。
     *
     * **RC70 修复（密钥无法保存的根因）：**
     * MasterKey 由 Android Keystore 生成，密钥材料不可导出，`SecretKey.getEncoded()` 恒返回 `null`。
     * 旧实现 `digest.digest(masterKey.encoded)` 会因传入 null 抛 NPE，导致
     * `CredentialEncryptor.ensureInitialized()` 永远初始化失败 → DEK 未加载 →
     * `encrypt()` 里 `requireDek()` 抛 IllegalStateException →
     * `AIProviderRepositoryImpl.toEntity()` 捕获后将 API Key 静默落成空串（密钥无法保存）。
     *
     * 修复：不再取不可导出的 key 字节。返回一个基于 algorithm 的稳定标记，
     * 真正的「MasterKey 是否被外部重置」校验交由 `CredentialEncryptor.ensureInitialized()`
     * 通过 `unwrapDek()` 的成功性完成（GCM tag 不匹配即视为被重置）。
     */
    fun getMasterKeyFingerprint(masterKey: SecretKey): String {
        return "V2-kstore-" + masterKey.algorithm
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
        // WRAP/UNWRAP 模式的 IV 由 Cipher 在 wrap 时内部生成并随 wrapped bytes 一起编码，
        // unwrap 时 cipher 自行从 wrapped 字节流解析，不需要外部 IV。
        cipher.init(Cipher.UNWRAP_MODE, masterKey)
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
