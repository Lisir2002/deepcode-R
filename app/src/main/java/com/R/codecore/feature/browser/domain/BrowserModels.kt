package com.R.codecore.feature.browser.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 页面可交互元素（给模型"看"的元素树条目）。 */
@Serializable
data class BrowserElement(
    val id: String,
    val tag: String = "",
    /** 归一化控件类型：link / button / input:text / input:password / input:checkbox / select / textarea 等。 */
    val kind: String = "",
    val role: String = "",
    val type: String = "",
    val name: String = "",
    /** 元素人类可读名称（优先 aria-label / <label for> / 包裹 label / name）。 */
    val label: String = "",
    /** 可见文本（链接/按钮文案等；input/textarea 为空）。 */
    val text: String = "",
    /** 当前取值（input/textarea 的 value、select 的选中项文本；敏感字段已清空）。 */
    val value: String = "",
    /** a11y/链接语义：链接目标、辅助标签、展开态、标题层级。 */
    val href: String = "",
    val ariaLabel: String = "",
    val ariaExpanded: String = "",
    val heading: String = "",
    val contenteditable: Boolean = false,
    val disabled: Boolean = false,
    val required: Boolean = false,
    val readonly: Boolean = false,
    /** checkbox/radio 是否勾选（其余控件恒为 false）。 */
    val checked: Boolean = false,
    /** select 的可选项列表。 */
    val options: List<BrowserOption> = emptyList(),
    /** 是否为密码等敏感字段：快照不得回传其 value。 */
    val sensitive: Boolean = false,
    val placeholder: String = ""
)

/** select 的可选项。 */
@Serializable
data class BrowserOption(
    val value: String = "",
    val text: String = ""
)

/** 页面标题层级（h1–h6 文档大纲，供模型理解页面结构）。 */
@Serializable
data class BrowserHeading(
    val level: Int = 0,
    val text: String = ""
)

/** 页面快照：标题 + URL + 标题大纲 + 可交互控件树 + 页面纯文本（供模型决策）。 */
@Serializable
data class BrowserPageSnapshot(
    val title: String = "",
    val url: String = "",
    val headings: List<BrowserHeading> = emptyList(),
    val elements: List<BrowserElement> = emptyList(),
    val pageText: String = "",
    @SerialName("isLoginPage")
    val hasLoginForm: Boolean = false,
    /** 探测到需要登录的信号（有密码框、401 文案、跳转登录页等）。 */
    val loginHint: String = "",
    /** 在途业务请求数（fetch/XHR/WS/SSE），>0 表示页面数据可能尚未加载完。 */
    val pendingRequests: Int = 0
)

/** 单条异步数据请求记录（对应页面内 JS 插桩层缓冲，仅用于反序列化，不持久化）。 */
@Serializable
data class BrowserNetworkRecord(
    val id: Int = 0,
    val op: String = "",           // fetch / xhr / websocket / eventsource
    val method: String = "",
    val url: String = "",          // 已脱敏（query 敏感参数置为 ***）
    val status: Int = 0,           // HTTP 状态；ws/sse 用连接码
    @SerialName("duration_ms")
    val durationMs: Long = 0,
    val size: Int = 0,             // 响应体字节数；-1 表示未能读取（opaque/no-cors）
    @SerialName("start_ts")
    val startTs: Long = 0,
    @SerialName("response_snippet")
    val responseSnippet: String = "", // 脱敏后截断摘要
    val error: String = ""
)

/** 标签页信息（供 UI 标签栏 / list_tabs 工具使用）。 */
data class BrowserTabInfo(
    val id: String,
    val title: String = "",
    val url: String = ""
)

/** 下载任务状态（供 downloads 工具 / 模型查询）。 */
data class BrowserDownloadInfo(
    val id: String,
    val url: String,
    val fileName: String = "",
    val path: String = "",
    val status: String = "downloading", // downloading / done / error
    val error: String = ""
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
    val screenVisible: Boolean = false,
    /** 当前激活标签 id（驱动 UI 用 key 切换 WebView）。 */
    val activeTabId: String = ""
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
