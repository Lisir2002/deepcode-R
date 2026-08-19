package com.R.codecore.feature.agent.domain.tool.search

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

/**
 * 网页抓取工具。注意：必须走**共享 OkHttp**（[AgentModule] 注入 ProxyRouteHolder 的 ProxySelector），
 * 代理启用时流量才会经 mihomo mixed-port 出口，否则直连被墙站点必然失败。
 * 旧实现用 `Jsoup.connect()`（内部 HttpURLConnection）完全绕过共享 OkHttp 与代理，
 * 导致「节点可用但谷歌打不开」；现改为 OkHttp 拉响应体 + Jsoup 解析正文，代理路由与
 * 正文提取能力两者兼得。
 */
class WebFetchTool @Inject constructor(
    private val client: OkHttpClient
) : AgentTool() {

    private companion object {
        const val TAG = "WebFetchTool"
        // 较新的桌面 Chrome 版本，搭配下方 sec-ch-ua / sec-fetch-* 请求头以贴近真实浏览器指纹
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
        const val MAX_LENGTH = 100_000 // 限制最大提取字符数，防止撑爆上下文

        // W-3：块级元素集合，遍历正文时逐块换行拼行
        val BLOCK_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "div",
            "pre", "blockquote", "tr", "td", "th", "section", "article",
            "ul", "ol", "table", "header", "footer", "nav", "main",
            "aside", "figure", "figcaption", "dl", "dt", "dd", "hr", "form"
        )

        // 文本型块级元素：直接取 .text() 作为独立一行（其内容为行内文本，无需再递归子元素）
        val TEXTUAL_BLOCK_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "pre",
            "blockquote", "dt", "dd", "td", "th", "figcaption"
        )
    }

    override val name = "webfetch"
    override val description = "抓取指定 HTTP/HTTPS 网页内容。支持提取网页正文为纯文本或返回原始 HTML 结构。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "需要抓取的网页完整 URL (必须以 http:// 或 https:// 开头)",
            required = true
        ),
        "format" to ToolParameter(
            name = "format",
            type = ParameterType.STRING,
            description = "返回格式: 'text' (默认，去除了广告、脚本和样式，仅保留正文) 或 'html' (原始 HTML 源码)",
            enum = listOf("text", "html"),
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 url 参数")
        val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "text"

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.Error("URL 必须以 http:// 或 https:// 开头")
        }

        return withContext(Dispatchers.IO) {
            try {
                FileLogger.i(TAG, "正在抓取网页: $url, format=$format")

                // 从顶层捕获非 HTTP 的连接异常（DNS、超时、SSL 等），直接把具体原因回传给 AI，不做兜底猜测
                val doc = try {
                    fetchDocument(url)
                } catch (e: FetchException) {
                    return@withContext ToolResult.Error(e.detailedMessage(url))
                }

                // 按要求提取
                val resultText = when (format) {
                    "html" -> doc.outerHtml()
                    else -> extractCleanText(doc)
                }
                
                val finalOutput = if (resultText.length > MAX_LENGTH) {
                    resultText.take(MAX_LENGTH) + "\n\n[网页内容超长，已截断...]"
                } else {
                    resultText
                }

                ToolResult.Success(kotlinx.serialization.json.JsonPrimitive(finalOutput))
            } catch (e: Exception) {
                FileLogger.e(TAG, "抓取网页时发生异常", e)
                ToolResult.Error("抓取失败: ${e.message}")
            }
        }
    }

    private fun fetchDocument(url: String): Document {
        // 共享 OkHttp：代理启用时经 mihomo 出口，未启用直连；请求头保持真实 Chrome 指纹。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            // 真桌面 Chrome 一定会发送的 sec-ch-ua 系列客户端提示
            .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"132\"")
            .header("Sec-Ch-Ua-Mobile", "?0")
            .header("Sec-Ch-Ua-Platform", "\"Windows\"")
            // Fetch Metadata 请求头，现代浏览器发页面请求时必带
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    FileLogger.e(TAG, "HTTP 状态码异常: ${resp.code} - $url")
                    throw FetchException("HTTP ${resp.code}: ${body.take(200).ifBlank { resp.message }}")
                }
                val html = resp.body?.string().orEmpty()
                Jsoup.parse(html, url)
            }
        } catch (e: FetchException) {
            throw e
        } catch (e: SocketTimeoutException) {
            FileLogger.e(TAG, "请求超时: $url", e)
            throw FetchException("请求超时（15 秒内未响应）")
        } catch (e: UnknownHostException) {
            FileLogger.e(TAG, "DNS 解析失败: $url", e)
            throw FetchException("无法解析主机名：${url}")
        } catch (e: SSLException) {
            FileLogger.e(TAG, "SSL 握手失败: $url", e)
            throw FetchException("SSL/TLS 握手失败：${e.message ?: "未知原因"}")
        } catch (e: IOException) {
            FileLogger.e(TAG, "网络 I/O 异常: ${e.message} - $url", e)
            throw FetchException("网络 I/O 异常：${e.message ?: "未知原因"}")
        }
    }

    /** 把抓取过程中的失败包装成携带具体信息的异常，以便回传给 AI 而非模糊提示。 */
    private class FetchException(val detail: String) : RuntimeException(detail) {
        fun detailedMessage(url: String): String = "抓取 $url 失败：$detail"
    }

    /**
     * 提取干净正文，按块级元素逐行拼装，保留标题/段落/列表项各自的换行语义。
     * W-3：改为 Jsoup 遍历块级元素拼行，不再使用字面 "\\n" 替换 hack
     * （避免误替换页面正文中真实的字面 \n）。
     */
    private fun extractCleanText(doc: Document): String {
        // 移除多余的不可见内容
        doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()

        val body = doc.body() ?: return ""
        val sb = StringBuilder()
        appendElement(body, sb)

        return sb.toString()
            .replace(Regex("[ \\t]+\\n"), "\n") // 去掉行尾空白
            .replace(Regex("\\n[ \\t]+"), "\n") // 去掉行首空白（保留行内单词间的单个空格）
            .replace(Regex("\\n{3,}"), "\n\n")  // 合并连续空行为一段空行
            .trim()
    }

    /**
     * 递归遍历 DOM 拼装文本：
     * - <br> 输出换行；
     * - 文本型块级元素（p/h1-h6/li/pre 等）先换行再输出其 .text()，不递归子元素；
     * - 容器块（div/section/table 等）或行内元素先换行（仅块）再递归子节点，
     *   保证同一容器内多个子块分别独立成行。
     */
    private fun appendElement(element: Element, sb: StringBuilder) {
        val tag = element.tagName()

        // <br> 输出换行
        if (tag == "br") {
            sb.append('\n')
            return
        }

        if (tag in BLOCK_TAGS) {
            sb.append('\n')
            if (tag in TEXTUAL_BLOCK_TAGS) {
                val text = element.text().trim()
                if (text.isNotEmpty()) sb.append(text)
                return
            }
        }

        // 容器块或行内元素：追加自身直接文本，再递归子节点
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotBlank()) sb.append(text)
                }
                is Element -> appendElement(node, sb)
                // 注释/数据等其他节点类型忽略
            }
        }
    }
}
