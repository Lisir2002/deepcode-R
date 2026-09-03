package com.core.deepcode.feature.browser.domain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.core.deepcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器站点登录凭据存储（加密）。
 *
 * 用 Android Keystore 生成 AES-GCM 密钥（不可导出），凭据经加密后存入 SharedPreferences，
 * 按 host 维度存取。模型需要登录时读取对应站点的账号密码自动代填。
 *
 * 安全性说明：模型（大模型 API）在代填登录时会读到明文凭据并作为上下文发给云端。
 * 本存储只负责「本地加密保管」，无法阻止模型把内容发给云端；如在意该风险，
 * 可在 [BrowserLoginPromptManager] 增加代填前确认开关。
 */
@Singleton
class BrowserCredentialStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BrowserCredentialStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "deepcode_browser_cred"
        const val PREFS = "browser_credentials"
        const val IV_LENGTH_BYTES = 12
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key by lazy { getOrCreateKey() }

    /** 读取指定 host 的凭据；未找到返回 null。 */
    fun find(host: String): BrowserCredential? {
        val normalized = host.trim().lowercase()
        val ciphertext = prefs.getString("cred_$normalized", null) ?: return null
        return try {
            decrypt(ciphertext)
        } catch (e: Exception) {
            FileLogger.e(TAG, "解密凭据失败 host=$normalized", e)
            null
        }
    }

    /** 保存（或覆盖）指定 host 的凭据。 */
    fun save(host: String, username: String, password: String) {
        val normalized = host.trim().lowercase()
        if (normalized.isBlank() || username.isBlank()) return
        val cred = BrowserCredential(host = normalized, username = username.trim(), password = password)
        try {
            val ciphertext = encrypt(cred)
            prefs.edit().putString("cred_$normalized", ciphertext).apply()
            FileLogger.i(TAG, "凭据已加密保存 host=$normalized")
        } catch (e: Exception) {
            FileLogger.e(TAG, "加密保存凭据失败 host=$normalized", e)
        }
    }

    /** 删除指定 host 的凭据。 */
    fun delete(host: String) {
        val normalized = host.trim().lowercase()
        prefs.edit().remove("cred_$normalized").apply()
    }

    /** 已保存凭据的 host 列表（UI 管理用）。 */
    fun hosts(): List<String> = prefs.all.keys
        .filter { it.startsWith("cred_") }
        .map { it.removePrefix("cred_") }
        .sorted()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(cred: BrowserCredential): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // 明文格式："username\u0001password"（host 由 prefs key 索引，不入载荷）
        val plain = cred.username + "\u0001" + cred.password
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String): BrowserCredential? {
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH_BYTES) return null
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(encrypted), Charsets.UTF_8)
        val sep = plain.indexOf('\u0001')
        if (sep <= 0) return null
        return BrowserCredential(
            host = "",
            username = plain.substring(0, sep),
            password = plain.substring(sep + 1)
        )
    }
}
