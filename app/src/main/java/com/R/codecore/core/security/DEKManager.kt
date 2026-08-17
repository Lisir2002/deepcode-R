package com.R.codecore.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.R.codecore.core.util.FileLogger
import java.security.KeyStore
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
 * 2. 用 MasterKey 加密/解密 DEK（DEK 是 256-bit AES 对称密钥，持久化在 Room 的
 *    [CredentialEncryptionStateEntity.dekCiphertext]）。
 * 3. 内存缓存已解密的 DEK（进程级，不持久化）。
 *
 * RC74：DEK 保护改用 AES/GCM ENCRYPT/DECRYPT（而非 WRAP/UNWRAP）。
 * Android Keystore 的 AES/GCM 不支持密钥包装模式，WRAP/UNWRAP 会抛
 * Incompatible purpose → 旧版 DEK 永远无法初始化 → API Key 无法保存。
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
        private const val MASTERKEY_ALIAS = "rcodecore_credential_masterkey"
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
            // RC74 根因修复（DEK 未初始化 / Incompatible purpose）：
            // 旧版用 AES/GCM + WRAP_MODE/UNWRAP_MODE 保护 DEK，但 Android Keystore 的
            // AES/GCM 并不实现密钥包装模式（WRAP/UNWRAP），Cipher.init 会抛
            // UnsupportedOperationException / Incompatible purpose → wrapDek/unwrapDek
            // 永远失败 → ensureInitialized 失败 → DEK 未初始化 → API Key 无法保存。
            // 本版本改为 AES/GCM ENCRYPT/DECRYPT 保护 DEK（标准、普遍支持）。
            // 已存在分支校验 MasterKey 是否支持 ENCRYPT/DECRYPT，不支持则删除重建。
            if (supportsEncryptDecrypt(existingEntry.secretKey)) {
                return existingEntry.secretKey
            }
            FileLogger.w(TAG, "已存在 MasterKey 不支持 ENCRYPT/DECRYPT（用途不兼容），删除并重建")
            keyStore.deleteEntry(MASTERKEY_ALIAS)
            cachedDek = null
        }

        FileLogger.i(TAG, "生成新的 MasterKey（biometricRequired=$biometricRequired）")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        // RC74：MasterKey 授权 ENCRYPT|DECRYPT，用 AES/GCM 加密 DEK 原始字节（标准做法）。
        // 不再使用 WRAP_KEY/UNWRAP_KEY 用途（AES/GCM 不支持密钥包装）。
        val purpose = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
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

    /**
     * RC74：校验 MasterKey 是否支持 ENCRYPT/DECRYPT 用途。
     * 用 Cipher.ENCRYPT_MODE 初始化，若 KeyGenParameterSpec 未授权 ENCRYPT 用途，
     * Android Keystore 会在 init 时抛 KeyStoreException: Incompatible purpose。
     * 必须 IO 线程。
     */
    private fun supportsEncryptDecrypt(key: SecretKey): Boolean {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            true
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
     * 用 MasterKey 加密 DEK → Base64 密文字符串。
     *
     * **RC74 根因修复：** 旧实现用 `Cipher.WRAP_MODE` 包装 DEK，但 Android Keystore 的
     * AES/GCM 并不实现密钥包装模式，`Cipher.init(WRAP_MODE)` 会抛
     * `UnsupportedOperationException / Incompatible purpose`，导致 wrapDek 永远失败 →
     * ensureInitialized 失败 → DEK 未初始化 → API Key 无法保存。
     * 本版本改为标准的 AES/GCM ENCRYPT_MODE：随机 IV + 加密 DEK 原始字节，
     * 输出格式 `Base64(IV(12B) + ciphertext+tag)`。
     *
     * 结果持久化到 [CredentialEncryptionStateEntity.dekCiphertext]。必须 IO 线程。
     */
    fun wrapDek(masterKey: SecretKey, dek: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(dek.encoded)
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /**
     * 用 MasterKey 解密 DEK 密文 → 内存 SecretKey。
     *
     * **RC74：** 与 [wrapDek] 配套，使用 AES/GCM DECRYPT_MODE，从
     * `Base64(IV + ciphertext+tag)` 中解析出 IV 与密文后解密得到 DEK 原始字节。
     * 若 MasterKey 已被外部重置，GCM tag 校验失败会抛异常（即视为被重置）。
     *
     * 结果缓存到 [cachedDek]。必须 IO 线程。
     */
    fun unwrapDek(masterKey: SecretKey, dekCiphertextB64: String): SecretKey {
        val bytes = Base64.getDecoder().decode(dekCiphertextB64)
        val iv = bytes.copyOfRange(0, IV_LEN)
        val ciphertext = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val dekBytes = cipher.doFinal(ciphertext)
        val dek = SecretKeySpec(dekBytes, KeyProperties.KEY_ALGORITHM_AES)
        cachedDek = dek
        return dek
    }

    /** 获取内存缓存的 DEK（如果存在）。volatile 读，任意线程。 */
    fun getCachedDek(): SecretKey? = cachedDek

    /** 清除内存 DEK 缓存。volatile 写，任意线程。 */
    fun clearDekCache() {
        cachedDek = null
    }
}
