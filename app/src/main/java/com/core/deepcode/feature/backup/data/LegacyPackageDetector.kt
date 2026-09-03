package com.core.deepcode.feature.backup.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.core.deepcode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 历史遗留包名（applicationId 曾三次变更）。禁止修改 applicationId 回退到这些包名，否则用户数据再次隔离。
 *
 * 变更史：com.aicodeeditor → com.aicode → com.deep.rcode → com.core.deepcode（当前）。
 * 包名变更 = 全新安装，旧包私有目录（含历史对话）随 UID 隔离，本包进程无权读取。
 */
val LEGACY_APPLICATION_IDS: List<String> = listOf("com.aicodeeditor", "com.aicode", "com.deep.rcode")

/**
 * 同签名旧包检测：判断是否存在「与本应用同签名但包名不同」的旧版本应用仍安装在本机。
 *
 * 背景：applicationId 三次变更后，老版本应用若仍安装（用户并排安装/未卸载），其私有目录里的
 * 历史数据仍可经「旧包导出 → 新包导入」找回。本检测器供数据完整性哨兵（DataSentinel）在
 * 「本包名下无记忆」时判断：究竟是真正的全新安装，还是 rebrand 升级导致的包名变更。
 */
@Singleton
class LegacyPackageDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "LegacyPackageDetector"
    }

    /**
     * 是否存在同签名旧包。成功探测到返回 true；任何异常按无旧包处理（不阻断启动）。
     * 本包自身包名在 [LEGACY_APPLICATION_IDS] 内时跳过（避免自匹配）。
     */
    fun hasSameSignatureLegacyPackage(): Boolean {
        val currentSig = runCatching {
            packageSignature(context, context.packageName)
        }.getOrNull() ?: return false
        for (pkg in LEGACY_APPLICATION_IDS) {
            if (pkg == context.packageName) continue
            val sig = runCatching { packageSignature(context, pkg) }.getOrNull() ?: continue
            if (sig.contentEquals(currentSig)) {
                FileLogger.i(TAG, "检测到同签名旧包: $pkg")
                return true
            }
        }
        return false
    }

    private fun packageSignature(context: Context, packageName: String): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    ?.signatures?.firstOrNull()?.toByteArray()
            }.getOrNull()
        }
}
