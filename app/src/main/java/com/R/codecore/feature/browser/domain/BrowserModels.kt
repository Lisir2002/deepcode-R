package com.R.codecore.feature.browser.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 页面可交互元素（给模型"看"的元素树条目）。 */
@Serializable
data class BrowserElement(
    val id: String,
    val tag: String = "",
    val role: String = "",
    val type: String = "",
    val name: String = "",
    val text: String = "",
    val disabled: Boolean = false,
    val placeholder: String = ""
)

/** 页面快照：标题 + URL + 可交互元素树 + 页面纯文本（供模型决策）。 */
@Serializable
data class BrowserPageSnapshot(
    val title: String = "",
    val url: String = "",
    val elements: List<BrowserElement> = emptyList(),
    val pageText: String = "",
    @SerialName("isLoginPage")
    val hasLoginForm: Boolean = false,
    /** 探测到需要登录的信号（有密码框、401 文案、跳转登录页等）。 */
    val loginHint: String = ""
)

/** 浏览器当前状态（UI 观察）。 */
data class BrowserUiState(
    val currentUrl: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    /** 屏幕当前是否已绑定 WebView（浏览器页打开时 true）。 */
    val screenVisible: Boolean = false
)

/** 模型操作状态（浏览器页底部状态条）。 */
data class AgentBrowserStatus(
    val text: String = "",
    val active: Boolean = false
)

/** 浏览器对话框（alert/confirm/prompt）等待处理。 */
data class PendingBrowserDialog(
    val id: String,
    val type: String,       // alert / confirm / prompt
    val message: String
)

/** 登录凭据提示（模型需要用户提供某站点凭据）。 */
data class PendingLoginPrompt(
    val requestId: String,
    val host: String,
    val title: String
)

/** 用户输入的站点凭据。 */
data class BrowserCredential(
    val host: String,
    val username: String,
    val password: String
)
