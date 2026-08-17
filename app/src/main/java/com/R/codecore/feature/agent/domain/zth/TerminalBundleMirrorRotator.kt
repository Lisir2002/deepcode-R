package com.R.codecore.feature.agent.domain.zth

import com.R.codecore.feature.agent.domain.permission.FailureSubClass
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.13 Terminal Bundle 下载镜像自动切换 + 失败细分 8 SubClass。
 *
 * 严格镜像顺序（Phase 4 替换为真 OkHttp 下载）：
 *   OFFICIAL → ALIYUN → TSINGHUA → USTC（BundleMirror 枚举 ordinal 顺序锁死）。
 *   每个镜像最多下载 1 次；合计最多切 4 次。
 *   单次下载超时 = 90s（可通过构造参数 override，单测短点）。
 *
 * 失败细分严格映射 8 SubClass（C.4.13）：
 *   DNS 解析失败 → INFRA_PACKAGE_DNS_FAILURE
 *   TLS 握手失败 → INFRA_PACKAGE_TLS_HANDSHAKE_FAIL
 *   代理失败(407) → INFRA_PACKAGE_PROXY_ERROR
 *   404 → INFRA_PACKAGE_INDEX_404
 *   SHA256 校验失败 → INFRA_PACKAGE_SIGNATURE_MISMATCH
 *   文件损坏(truncated) → INFRA_PACKAGE_DOWNLOAD_CORRUPT
 *   磁盘满(ENOSPC) → INFRA_PACKAGE_DISK_FULL
 *   其他网络错误 → INFRA_HTTP_SERVER_OR_NETWORK
 *
 * 接口 + 占位实现：Phase 1.5 Preflight P12（项目当前无 OkHttp wrapper 直接注入），
 * 故把「下载」抽成 Downloader 接口，Phase 4 AgentModule.kt @Provides 才写 RealOkHttpDownloader。
 */
@Singleton
class TerminalBundleMirrorRotator @Inject constructor(
    /** 90s 可覆盖（JUnit 传 1ms 快速失败）。 */
    private val perMirrorTimeoutMs: Long = 90_000L,
    /** 默认 4 次重试，每个镜像一次（C.4.13）。 */
    private val maxAttempts: Int = BundleMirror.entries.size,
    /** 默认镜像顺序 = OFFICIAL→ALIYUN→TSINGHUA→USTC，测试可传子集。 */
    private val mirrorOrder: List<BundleMirror> = BundleMirror.entries,
    /** 默认占位实现；Phase 4 替换为真。 */
    private val downloader: Downloader = PlaceholderDownloader
) {

    /** 下载器：Phase 4 注入真 OkHttp；单测提供 FakeDownloader。 */
    fun interface Downloader {
        suspend fun download(bundleKey: String, mirror: BundleMirror, timeoutMs: Long): DownloadAttempt
    }

    data class DownloadAttempt(
        val mirror: BundleMirror,
        val success: Boolean,
        val bytes: Long = 0L,
        val sha256Hex: String? = null,
        /** 失败时非空，填 8 种 FailureSubClass 之一。 */
        val failure: FailureSubClass? = null,
        val errorMessage: String? = null
    )

    /**
     * 核心下载：按镜像顺序切 4 次，每次超时 perMirrorTimeoutMs。
     *
     * @param bundleKey TerminalBundleId.stableKey（python/node/...）
     * @param expectedSha256 不为空时，下载完成必须对比；不符 → SIGNATURE_MISMATCH
     * @return BundleDownloadResult（Success / Failure(subClass)）
     */
    suspend fun download(bundleKey: String, expectedSha256: String? = null): BundleDownloadResult {
        require(bundleKey.isNotBlank())
        var latestFailure: FailureSubClass? = null
        var attemptCount = 0
        for (mirror in mirrorOrder.take(maxAttempts)) {
            attemptCount++
            val att = withTimeoutOrNull(perMirrorTimeoutMs) {
                runCatching { downloader.download(bundleKey, mirror, perMirrorTimeoutMs) }
                    .getOrElse { t ->
                        // 超时/抛异常 → 细分失败映射
                        DownloadAttempt(mirror, false, failure = mapThrowableToSub(t), errorMessage = t.message)
                    }
            } ?: DownloadAttempt(
                mirror, false,
                failure = FailureSubClass.INFRA_TIMEOUT,
                errorMessage = "perMirror(${mirror.stableKey}) timeout ${perMirrorTimeoutMs}ms"
            )
            if (att.success) {
                // 成功 → 若期望 sha256，再校验签名（失败 SIGNATURE_MISMATCH，不会直接返回成功）
                if (expectedSha256 != null && att.sha256Hex?.lowercase() != expectedSha256.lowercase()) {
                    latestFailure = FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH
                    continue  // 签名失败 → 下一镜像重试
                }
                return BundleDownloadResult.Success(bundleKey, mirror, att.bytes, expectedSha256 != null, attemptCount)
            } else {
                latestFailure = att.failure
                    ?: FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK // 兜底：其余 HTTP 服务端错
            }
        }
        // 所有镜像都失败
        val sub = latestFailure ?: FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
        return BundleDownloadResult.Failure(
            bundleKey, sub,
            failedMirror = mirrorOrder.lastOrNull(),
            retryCount = attemptCount,
            message = "所有镜像失败；最终 ${sub.name}（尝试 $attemptCount 次）"
        )
    }

    private fun mapThrowableToSub(t: Throwable): FailureSubClass = when (t) {
        is java.net.UnknownHostException -> FailureSubClass.INFRA_PACKAGE_DNS_FAILURE
        is javax.net.ssl.SSLHandshakeException -> FailureSubClass.INFRA_PACKAGE_TLS_HANDSHAKE_FAIL
        is java.net.ProtocolException -> when {
            (t.message ?: "").contains("407") -> FailureSubClass.INFRA_PACKAGE_PROXY_ERROR
            else -> FailureSubClass.INFRA_PACKAGE_INDEX_404
        }
        is java.io.FileNotFoundException -> FailureSubClass.INFRA_PACKAGE_INDEX_404
        is java.io.IOException -> when {
            (t.message ?: "").lowercase().let { m ->
                m.contains("no space left") || m.contains("enospc")
            } -> FailureSubClass.INFRA_PACKAGE_DISK_FULL
            (t.message ?: "").lowercase().contains("checksum") -> FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH
            (t.message ?: "").lowercase().contains("truncated") -> FailureSubClass.INFRA_PACKAGE_DOWNLOAD_CORRUPT
            (t.message ?: "").lowercase().contains("407") -> FailureSubClass.INFRA_PACKAGE_PROXY_ERROR
            else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
        }
        else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
    }

    /** Phase 1~3 默认下载实现（占位，永远失败；Phase 4 AgentModule 注入 RealOkHttpDownloader）。 */
    object PlaceholderDownloader : Downloader {
        override suspend fun download(bundleKey: String, mirror: BundleMirror, timeoutMs: Long): DownloadAttempt =
            DownloadAttempt(
                mirror = mirror,
                success = false,
                failure = FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH, // 随便一个非致命 SubClass（供 JUnit 分支 cover）
                errorMessage = "Phase 4 AgentModule 需注入 RealOkHttpDownloader；当前 Placeholder 永远返回失败。"
            )
    }
}
