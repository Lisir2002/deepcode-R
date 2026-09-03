package com.core.deepcode.feature.proxy.domain

import kotlinx.serialization.Serializable

/**
 * 一条网络代理 profile（mihomo 配置）。
 *
 * 设计（《网络代理设计 v1.0》§11「播种后模型接管」）：长存 profile 由用户经导入页
 * 「播种」写入，之后模型只能 `on/select/config/off`，不能凭空新建长存订阅。
 * 因此本模型只承载已播种的配置，新建入口在 UI；模型侧仅可临时 inline（见 NetworkProxyTool）。
 *
 * 敏感内容（订阅 URL 中的 token / 手动 YAML 里的凭据）在仓库层统一用
 * [com.core.deepcode.core.security.CredentialEncryptor] 加密后存于 [secretCipher]，
 * 界面/工具输出一律不取回明文，只回显脱敏信息。
 */
@Serializable
data class ProxySubscription(
    val id: String,
    val name: String,
    /** "subscription"（订阅 URL，token 在 URL 里）/ "manual"（手动粘贴 YAML）。 */
    val kind: String,
    /** 加密后的敏感内容：subscription=订阅 URL；manual=完整 YAML。 */
    val secretCipher: String,
    val createdAt: Long,
) {
    companion object {
        const val KIND_SUBSCRIPTION = "subscription"
        const val KIND_MANUAL = "manual"
    }
}