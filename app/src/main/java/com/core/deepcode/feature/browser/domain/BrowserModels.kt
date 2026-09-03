package com.core.deepcode.feature.browser.domain

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
    val placeholder: String = "",
    /**
     * 三级定位之一：CSS 绝对路径（如 `html > body > div#main > form > div:nth-child(2) > input`）。
     * SPA 重渲染替换节点后只要 DOM 结构位置不变仍可命中，是主定位方式。
     */
    val locator: String = "",
    /**
     * 三级定位之一：语义描述符（如 `role=link name=Read more index=2`），
     * CSS 路径与 data-rcb-id 都失效时作最终兜底，同时便于模型理解元素。
     */
    val semantic: String = "",
    /** 是否在视口内（getBoundingClientRect 相对 viewport 判定）。 */
    val inViewport: Boolean = false,
    /** 是否可见（非 display:none / visibility:hidden / opacity:0 且有非零尺寸）。 */
    val visible: Boolean = false,
    /** 元素可见但不在视口内，需要先滚动才能操作。 */
    val needsScroll: Boolean = false,
    /** 元素被其他元素遮挡（elementFromPoint 中心点不是自己/后代）。 */
    val overlapped: Boolean = false
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
    val activeTabId: String = "",
    /** 无痕模式（R1.3）：会话级开关，开启时清空 Cookie/缓存，不记录历史。 */
    val incognito: Boolean = false,
    /** 桌面版网页（R1.3）：UA override 切换，桌面 UA 渲染。 */
    val desktopMode: Boolean = false,
    /** 页面缩放（R1.3）：WebSettings.textZoom 百分比，100 为默认。 */
    val textZoom: Int = 100
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

/**
 * 快照分级（R2.1）：控制 snapshot 动作返回内容的粒度，同时作为统一 envelope 的结果分级。
 *  - [SUMMARY]（默认）：标题/URL/标题大纲 + 每控件一行的紧凑摘要，不含 page_text 与完整元素 JSON；
 *  - [STANDARD]：summary + 完整元素 JSON（不含 page_text）；
 *  - [FULL]：standard + page_text（截断上限保留）。
 */
enum class SnapshotLevel {
    SUMMARY,
    STANDARD,
    FULL;

    companion object {
        fun fromName(name: String?): SnapshotLevel = when (name?.lowercase()) {
            "summary" -> SUMMARY
            "standard" -> STANDARD
            "full" -> FULL
            else -> SUMMARY
        }
    }
}

/**
 * 增量快照（R2.1）：写操作后返回的新增/消失/变化元素清单 + 文本变化摘要，
 * 模型默认只读 delta 即可感知页面变化，无需再取全量快照。
 */
data class BrowserSnapshotDelta(
    /** 相对上次快照新增的元素（id → 摘要行）。 */
    val added: List<BrowserElement> = emptyList(),
    /** 相对上次快照消失的元素（id）。 */
    val removed: List<String> = emptyList(),
    /** 相对上次快照发生变化（同 id 但文本/取值/可操作性不同）的元素。 */
    val changed: List<BrowserElement> = emptyList(),
    /** 页面纯文本变化摘要：只有文本长度变化时给出简短说明。 */
    val textNote: String = ""
)

/**
 * 统一动作 envelope（R2.3 干净替换）：所有 browser 动作返回同一结构，
 * `snapshot`（按分级）为可选项，写操作额外携带 `delta` 让模型无需自行轮询。
 */
data class BrowserActionResult(
    val ok: Boolean = false,
    val action: String = "",
    /** 页面是否有可感知变化（写操作自动验证后填充）。 */
    val changed: Boolean = false,
    /** 一行结论（给模型读的摘要）。 */
    val summary: String = "",
    val note: String = "",
    val error: String = "",
    /** 错误是否可恢复 + 具体建议（R2.3 错误可恢复）。 */
    val recoverable: Boolean = false,
    val snapshot: BrowserPageSnapshot? = null,
    val delta: BrowserSnapshotDelta? = null
)

/** 访问历史记录（R1.1 持久化）。 */
@Serializable
data class BrowserHistoryEntry(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    /** 访问时间（epoch millis）。 */
    val timestamp: Long = 0
)

/** 收藏夹书签（R1.1 持久化）。 */
@Serializable
data class BrowserBookmark(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val createdAt: Long = 0
)

/**
 * 三级定位解析结果（R2.2）：把模型传入的 element_id（data-rcb-id / CSS 绝对路径 / 语义描述符
 * 三者任一）解析成元素的 data-rcb-id，并记录命中方式（id / css / semantic）。
 */
data class ResolvedElement(
    val id: String,
    val method: String
)

/**
 * 事件驱动等待结果（R2.4）：wait_for_change 挂起等待页面 DOM/网络变化的返回。
 */
data class WaitChangeResult(
    val changed: Boolean = false,
    /** 变化后的 mutation 版本号。 */
    val version: Long = 0,
    /** 变化摘要（最近变化项 / 新网络请求说明）。 */
    val summary: String = ""
)
