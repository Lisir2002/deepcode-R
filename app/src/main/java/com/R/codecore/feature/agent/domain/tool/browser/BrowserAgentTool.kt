package com.R.codecore.feature.agent.domain.tool.browser

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.browser.domain.BrowserController
import com.R.codecore.feature.browser.domain.BrowserCredential
import com.R.codecore.feature.browser.domain.BrowserCredentialStore
import com.R.codecore.feature.browser.domain.BrowserElement
import com.R.codecore.feature.browser.domain.BrowserLoginPromptManager
import com.R.codecore.feature.browser.domain.BrowserPageSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import javax.inject.Inject

/**
 * 内置服务浏览器工具：让模型在共享 WebView 会话中浏览网页并操作页面。
 *
 * 与用户共享受同一浏览会话（同一份 Cookie/登录态）：模型能浏览、点击、输入、提交、
 * 截图、执行 JS；用户手动登录后模型自动复用登录态，模型操作在浏览器页实时可见。
 *
 * 支持访问外网 https/http，也支持容器内开发服务（http://localhost:PORT，PRoot 与宿主机
 * 共享网络栈，容器内服务直接绑在 Android 网络栈上）。
 *
 * 动作清单：
 *  - navigate(url)          打开网页
 *  - snapshot()             提取页面元素树 + 文本（模型"看"页面）
 *  - click(element_id)      点击元素
 *  - type(element_id,text)  输入文本
 *  - select_option(id,val)  下拉选择
 *  - submit(element_id?)    提交表单（id 可空→提交页面首个表单）
 *  - scroll(direction)      滚动（top/bottom/up/down）
 *  - screenshot()           页面截图（返回 data URL，多模态模型查看）
 *  - evaluate(js)           执行任意 JS
 *  - wait_for(selector)     等待元素出现
 *  - get_attribute(id,attr) 读元素属性
 *  - handle_dialog(accept)  处理页面 alert/confirm
 *  - login()                登录当前站点（从加密凭据库取账号密码自动代填；无则请用户输入）
 */
class BrowserAgentTool @Inject constructor(
    private val browserController: BrowserController,
    private val credentialStore: BrowserCredentialStore,
    private val loginPromptManager: BrowserLoginPromptManager
) : AgentTool() {

    private companion object {
        const val TAG = "BrowserAgentTool"
        const val MAX_ELEMENTS = 120
        const val MAX_TEXT = 8000
    }

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "browser"
    override val description =
        "操作内置服务浏览器。可打开网页（外网或容器内 http://localhost:PORT）、提取页面内容、点击/输入/提交表单、" +
            "截图、执行 JS、自动登录。与用户共享同一个浏览会话和登录态。" +
            "典型用法：browser.navigate(url) → browser.snapshot() 查看页面 → browser.click/type/submit 操作 → browser.screenshot() 查看效果。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE, ToolCapability.USER_INTERACTION)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "要执行的浏览器动作：navigate / snapshot / click / type / select_option / submit / scroll / screenshot / evaluate / wait_for / get_attribute / handle_dialog / login",
            required = true,
            enum = listOf(
                "navigate", "snapshot", "click", "type", "select_option", "submit",
                "scroll", "screenshot", "evaluate", "wait_for", "get_attribute", "handle_dialog", "login"
            )
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "navigate 时必填：要打开的 URL（可省略协议，自动补 https://；容器服务用 http://localhost:端口）",
            required = false
        ),
        "element_id" to ToolParameter(
            name = "element_id",
            type = ParameterType.STRING,
            description = "click/type/select_option/submit/get_attribute 时必填：snapshot 返回的元素 id",
            required = false
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "type 时必填：要输入到输入框的文本",
            required = false
        ),
        "value" to ToolParameter(
            name = "value",
            type = ParameterType.STRING,
            description = "select_option 时必填：要选中的 option value",
            required = false
        ),
        "direction" to ToolParameter(
            name = "direction",
            type = ParameterType.STRING,
            description = "scroll 时可选：top/bottom/up/down，默认 down",
            required = false,
            enum = listOf("top", "bottom", "up", "down")
        ),
        "attribute" to ToolParameter(
            name = "attribute",
            type = ParameterType.STRING,
            description = "get_attribute 时必填：要读取的元素属性名（如 value/textContent/href/src）",
            required = false
        ),
        "selector" to ToolParameter(
            name = "selector",
            type = ParameterType.STRING,
            description = "wait_for 时必填：CSS 选择器，等待其出现在页面上",
            required = false
        ),
        "accept" to ToolParameter(
            name = "accept",
            type = ParameterType.BOOLEAN,
            description = "handle_dialog 时必填：true=确认，false=取消",
            required = false
        ),
        "js" to ToolParameter(
            name = "js",
            type = ParameterType.STRING,
            description = "evaluate 时必填：要执行的 JavaScript 代码（最后一条表达式的值会作为字符串返回）",
            required = false
        ),
        "timeout_ms" to ToolParameter(
            name = "timeout_ms",
            type = ParameterType.INTEGER,
            description = "wait_for 时可选：等待超时毫秒，默认 10000",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 action 参数", "MISSING_ACTION")
        return try {
            when (action) {
                "navigate" -> doNavigate(args)
                "snapshot" -> ToolResult.Success(snapshotToJson(browserController.snapshot()))
                "click" -> doClick(args)
                "type" -> doType(args)
                "select_option" -> doSelect(args)
                "submit" -> doSubmit(args)
                "scroll" -> doScroll(args)
                "screenshot" -> doScreenshot()
                "evaluate" -> doEvaluate(args)
                "wait_for" -> doWaitFor(args)
                "get_attribute" -> doGetAttribute(args)
                "handle_dialog" -> doHandleDialog(args)
                "login" -> doLogin()
                else -> ToolResult.Error("未知动作: $action", "UNKNOWN_ACTION")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "browser.$action 失败", e)
            ToolResult.Error("浏览器操作失败: ${e.message}")
        }
    }

    // ─────────────────────────── 动作实现 ───────────────────────────

    private suspend fun doNavigate(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("navigate 需要 url 参数", "MISSING_URL")
        val snap = browserController.navigate(url)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doClick(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("click 需要 element_id", "MISSING_ELEMENT_ID")
        val snap = browserController.click(id)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doType(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("type 需要 element_id", "MISSING_ELEMENT_ID")
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("type 需要 text", "MISSING_TEXT")
        val snap = browserController.type(id, text)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doSelect(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("select_option 需要 element_id", "MISSING_ELEMENT_ID")
        val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("select_option 需要 value", "MISSING_VALUE")
        val snap = browserController.selectOption(id, value)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doSubmit(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull
        val snap = browserController.submit(id)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doScroll(args: Map<String, JsonElement>): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val snap = browserController.scroll(direction)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doScreenshot(): ToolResult {
        val dataUrl = browserController.screenshot()
            ?: return ToolResult.Error("截图失败：浏览器页面尚未渲染（请先在浏览器页打开页面）", "SCREENSHOT_EMPTY")
        // 截图非常大，不适合完整塞进上下文；返回一个提示 + 数据 URL 交由上层决定是否喂多模态
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "note" to JsonPrimitive("页面截图（data:image/png;base64），多模态模型可直接查看。"),
                    "image_data_url" to JsonPrimitive(dataUrl)
                )
            )
        )
    }

    private suspend fun doEvaluate(args: Map<String, JsonElement>): ToolResult {
        val js = args["js"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("evaluate 需要 js 参数", "MISSING_JS")
        val result = browserController.evaluate(js)
        return ToolResult.Success(JsonPrimitive(result))
    }

    private suspend fun doWaitFor(args: Map<String, JsonElement>): ToolResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val timeout = runCatching { args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull() ?: 10_000L
        val snap = browserController.waitFor(selector, timeout)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doGetAttribute(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("get_attribute 需要 element_id", "MISSING_ELEMENT_ID")
        val attr = args["attribute"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("get_attribute 需要 attribute", "MISSING_ATTRIBUTE")
        val value = browserController.getAttribute(id, attr)
        return ToolResult.Success(
            JsonObject(mapOf("element_id" to JsonPrimitive(id), "attribute" to JsonPrimitive(attr), "value" to JsonPrimitive(value ?: "")))
        )
    }

    private suspend fun doHandleDialog(args: Map<String, JsonElement>): ToolResult {
        val accept = runCatching { args["accept"]?.jsonPrimitive?.contentOrNull?.toBoolean() }.getOrNull() ?: true
        val handled = browserController.handleDialog(accept)
        return if (handled) {
            ToolResult.Success(JsonPrimitive("对话框已${if (accept) "确认" else "取消"}"))
        } else {
            ToolResult.Success(JsonPrimitive("当前没有待处理的对话框"))
        }
    }

    /** 登录当前站点：从凭据库取账号密码自动代填；无则请求用户输入。 */
    private suspend fun doLogin(): ToolResult {
        val snap = browserController.snapshot()
        if (!snap.hasLoginForm) {
            return ToolResult.Error("当前页面未检测到登录表单（无密码输入框），无需登录", "NO_LOGIN_FORM")
        }
        val host = extractHost(snap.url) ?: "unknown"
        var cred = credentialStore.find(host)
        if (cred == null) {
            FileLogger.i(TAG, "凭据库无 $host 凭据，请求用户输入")
            val answer = loginPromptManager.awaitCredentials(host)
            if (answer.cancelled || answer.username.isNullOrBlank() || answer.password.isNullOrBlank()) {
                return ToolResult.Success(
                    JsonPrimitive("用户未提供 $host 的登录凭据。请提示用户在浏览器页手动登录，或重新调用 login 让用户输入。")
                )
            }
            credentialStore.save(host, answer.username, answer.password)
            cred = BrowserCredential(host = host, username = answer.username, password = answer.password)
        }

        // 定位用户名/密码框并代填 + 提交
        var usernameId: String? = null
        var passwordId: String? = null
        for (e in snap.elements) {
            if (e.tag == "input" && e.type == "password" && passwordId == null) passwordId = e.id
            else if (e.tag == "input" && (e.type == "text" || e.type == "email" || e.type == "tel" || (e.type.isBlank() && e.name.isNotBlank())) && usernameId == null) usernameId = e.id
        }
        if (passwordId == null) {
            return ToolResult.Error("未找到密码输入框，无法自动代填", "NO_PASSWORD_FIELD")
        }
        browserController.type(usernameId ?: "", cred.username)
        browserController.type(passwordId, cred.password)
        // 尝试提交（优先提交登录表单）
        val after = browserController.submit(usernameId)
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "host" to JsonPrimitive(host),
                    "note" to JsonPrimitive("已用 ${cred.username} 自动代填并提交登录表单。若登录成功，后续请求自动携带登录态。"),
                    "page" to snapshotToJson(after)
                )
            )
        )
    }

    // ─────────────────────────── 序列化辅助 ───────────────────────────

    private fun snapshotToJson(snap: BrowserPageSnapshot): JsonObject {
        val elements = snap.elements.take(MAX_ELEMENTS).map { el ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(el.id),
                    "tag" to JsonPrimitive(el.tag),
                    "type" to JsonPrimitive(el.type),
                    "text" to JsonPrimitive(el.text),
                    "disabled" to JsonPrimitive(el.disabled)
                )
            )
        }
        val sb = StringBuilder()
        for (e in elements) {
            sb.append("[#").append((e["id"] as JsonPrimitive).content).append("] ")
            sb.append((e["tag"] as JsonPrimitive).content)
            val t = (e["text"] as JsonPrimitive).content
            if (t.isNotBlank()) sb.append(": ").append(t)
            sb.append('\n')
        }
        val pageText = snap.pageText.take(MAX_TEXT)
        return JsonObject(
            mapOf(
                "title" to JsonPrimitive(snap.title),
                "url" to JsonPrimitive(snap.url),
                "login_page" to JsonPrimitive(snap.hasLoginForm),
                "login_hint" to JsonPrimitive(snap.loginHint),
                "elements_summary" to JsonPrimitive(sb.toString()),
                "elements" to JsonPrimitive(elements.toString()),
                "page_text" to JsonPrimitive(pageText)
            )
        )
    }

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host } .getOrNull()?.lowercase()
    }
}
