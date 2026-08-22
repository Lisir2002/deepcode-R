package com.R.codecore.feature.backup.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从应用签名证书派生「跨包名稳定」的加密密钥（口令）。
 *
 * 背景：外部公共存储安全网（ExternalBackupStore）写入的备份含 API Key / Git Token 等敏感明文，
 * 公共目录其他应用可读，必须加密。但口令不能是随机值——applicationId（包名）变更后，
 * 新包必须能用同一把密钥解密旧包在公共目录写入的历史备份，才能完成数据找回。
 *
 * 方案：以「应用签名证书」为种子取 SHA-256。同一 keystore 签名的所有包（无论包名怎么变）
 * 都能派生出完全相同的结果；换 keystore（不同开发者/重新签名）则无法解密。这与 Android
 * 「包名变更但签名不变 = 同一开发者升级」的语义天然一致，是包名无关数据找回的密钥基础。
 *
 * 安全说明：密钥本身只存在于内存（不落盘）；泄露该密钥等价于泄露「应用签名」，第三方即便拿到
 * 也无法在未持有签名 keystore 的机器上复现。
 */
@Singleton
class SignatureKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "SignatureKeyStore"
    }

    @Volatile
    private var cached: CharArray? = null

    /**
     * 签名派生口令：SHA-256(签名证书) 的十六进制串，作为 AES-GCM 加密的「口令」。
     * 同 keystore 签名的应用（无论包名）返回相同结果；证书读取失败返回 null（调用方应降级处理）。
     */
    fun signaturePassword(): CharArray? {
        cached?.let { return it.copyOf() }
        val hex = runCatching {
            val bytes = signingCertificateBytes() ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        }.onFailure {
            FileLogger.w(TAG, "派生签名口令失败", it)
        }.getOrNull() ?: return null
        cached = hex.toCharArray()
        return hex.toCharArray()
    }

    private fun signingCertificateBytes(): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    ?.signatures?.firstOrNull()?.toByteArray()
            }.getOrNull()
        }
}
