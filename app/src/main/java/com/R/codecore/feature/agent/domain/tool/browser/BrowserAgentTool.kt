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
import com.R.codecore.feature.browser.domain.BrowserDownloadInfo
import com.R.codecore.feature.browser.domain.BrowserElement
import com.R.codecore.feature.browser.domain.BrowserLoginPromptManager
import com.R.codecore.feature.browser.domain.BrowserNetworkRecord
import com.R.codecore.feature.browser.domain.BrowserPageSnapshot
import com.R.codecore.feature.browser.domain.BrowserTabInfo
import com.R.codecore.feature.browser.domain.BrowserTakeoverManager
import com.R.codecore.feature.workspace.domain.WorkspacePathMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    private val loginPromptManager: BrowserLoginPromptManager,
    private val takeoverManager: BrowserTakeoverManager,
    private val pathMapper: WorkspacePathMapper
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
            "悬停/拖拽/按键/上传文件、前后退/刷新、截图、执行 JS、自动登录、请求用户接管(takeover)。与用户共享同一个浏览会话和登录态。" +
            "典型用法：browser.navigate(url) → browser.snapshot() 查看页面 → browser.click/type/submit 操作 → browser.screenshot() 查看效果。" +
            "遇到验证码/支付/二次认证等无法自动完成的步骤时，调用 takeover 请求用户亲自接管。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE, ToolCapability.USER_INTERACTION)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "要执行的浏览器动作：navigate / snapshot / extract / click / type / select_option / submit / scroll / hover / drag / press_key / upload_file / back / forward / reload / screenshot / evaluate / wait_for / get_attribute / handle_dialog / login / takeover / new_tab / switch_tab / close_tab / list_tabs / downloads / network / network_get / wait_for_request",
            required = true,
            enum = listOf(
                "navigate", "snapshot", "extract", "click", "type", "select_option", "submit",
                "scroll", "hover", "drag", "press_key", "upload_file", "back", "forward", "reload",
                "screenshot", "evaluate", "wait_for", "get_attribute", "handle_dialog", "login", "takeover",
                "new_tab", "switch_tab", "close_tab", "list_tabs", "downloads",
                "network", "network_get", "wait_for_request"
            )
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "navigate / wait_for_request 时必填；new_tab / network_get 时可选：要打开的 URL 或要匹配的请求 URL 子串（可省略协议，自动补 https://；容器服务用 http://localhost:端口）",
            required = false
        ),
        "element_id" to ToolParameter(
            name = "element_id",
            type = ParameterType.STRING,
            description = "click/type/select_option/submit/get_attribute 时必填：snapshot 返回的元素 id；screenshot 时可选：仅截取该元素区域",
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
            description = "wait_for / wait_for_request 时可选：等待超时毫秒（wait_for 默认 10000，wait_for_request 默认 15000）",
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "network 时可选：返回最近多少条网络请求记录，默认 20（1-100）",
            required = false
        ),
        "network_id" to ToolParameter(
            name = "network_id",
            type = ParameterType.INTEGER,
            description = "network_get 时可选：按记录的 id 精确查询（与 url 二选一，都空则返回最新一条）",
            required = false
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "takeover 时必填：告诉用户需要亲自完成什么（如验证码、支付、二次认证、人工决策）",
            required = false
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "press_key 时必填：要按下的键（如 Enter、Escape、Tab、Backspace 或单字符）",
            required = false
        ),
        "target_element_id" to ToolParameter(
            name = "target_element_id",
            type = ParameterType.STRING,
            description = "drag 时必填：拖拽目标元素的 id（把 element_id 拖到该元素上）",
            required = false
        ),
        "file_path" to ToolParameter(
            name = "file_path",
            type = ParameterType.STRING,
            description = "upload_file 时必填：要上传的本地文件路径（容器内路径，如 ~/workspace/data/input.csv）",
            required = false
        ),
        "mode" to ToolParameter(
            name = "mode",
            type = ParameterType.STRING,
            description = "extract 时可选：text（默认，纯文本）/ links / headings / table / html",
            required = false,
            enum = listOf("text", "links", "headings", "table", "html")
        ),
        "compact" to ToolParameter(
            name = "compact",
            type = ParameterType.BOOLEAN,
            description = "snapshot 时可选：true 时只返回元素摘要，省略页面全文，节省 token（默认 false 返回完整快照）",
            required = false
        ),
        "tab_id" to ToolParameter(
            name = "tab_id",
            type = ParameterType.STRING,
            description = "switch_tab / close_tab 时必填：要切换/关闭的标签 id（从 list_tabs 获取）",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 action 参数", "MISSING_ACTION")
        return try {
            when (action) {
                "navigate" -> doNavigate(args)
                "snapshot" -> doSnapshot(args)
                "extract" -> doExtract(args)
                "click" -> doClick(args)
                "type" -> doType(args)
                "select_option" -> doSelect(args)
                "submit" -> doSubmit(args)
                "scroll" -> doScroll(args)
                "hover" -> doHover(args)
                "drag" -> doDrag(args)
                "press_key" -> doPressKey(args)
                "upload_file" -> doUploadFile(args)
                "back" -> ToolResult.Success(snapshotToJson(browserController.back()))
                "forward" -> ToolResult.Success(snapshotToJson(browserController.forward()))
                "reload" -> ToolResult.Success(snapshotToJson(browserController.reloadPage()))
                "screenshot" -> doScreenshot(args)
                "evaluate" -> doEvaluate(args)
                "wait_for" -> doWaitFor(args)
                "get_attribute" -> doGetAttribute(args)
                "handle_dialog" -> doHandleDialog(args)
                "login" -> doLogin()
                "takeover" -> doTakeover(args)
                "new_tab" -> doNewTab(args)
                "switch_tab" -> doSwitchTab(args)
                "close_tab" -> doCloseTab(args)
                "list_tabs" -> doListTabs()
                "downloads" -> doListDownloads()
                "network" -> doNetwork(args)
                "network_get" -> doNetworkGet(args)
                "wait_for_request" -> doWaitForRequest(args)
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

    private suspend fun doSnapshot(args: Map<String, JsonElement>): ToolResult {
        val compact = args["compact"]?.jsonPrimitive?.booleanOrNull ?: false
        val snap = browserController.snapshot()
        return ToolResult.Success(snapshotToJson(snap, compact))
    }

    private suspend fun doExtract(args: Map<String, JsonElement>): ToolResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "text"
        val result = browserController.extract(selector, mode)
        val parsed = runCatching { json.parseToJsonElement(result) }.getOrNull()
        return if (parsed != null) ToolResult.Success(parsed) else ToolResult.Success(JsonPrimitive(result))
    }

    private suspend fun doNavigate(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("navigate 需要 url 参数", "MISSING_URL")
        browserController.validateUrl(url)?.let { return ToolResult.Error(it, "UNSAFE_URL") }
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

    private suspend fun doHover(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("hover 需要 element_id", "MISSING_ELEMENT_ID")
        val snap = browserController.hover(id)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doDrag(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("drag 需要 element_id", "MISSING_ELEMENT_ID")
        val targetId = args["target_element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("drag 需要 target_element_id", "MISSING_TARGET_ELEMENT_ID")
        val snap = browserController.drag(id, targetId)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doPressKey(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("press_key 需要 element_id", "MISSING_ELEMENT_ID")
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("press_key 需要 key", "MISSING_KEY")
        val snap = browserController.pressKey(id, key)
        return ToolResult.Success(snapshotToJson(snap))
    }

    private suspend fun doUploadFile(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("upload_file 需要 element_id", "MISSING_ELEMENT_ID")
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("upload_file 需要 file_path", "MISSING_FILE_PATH")
        val hostFile = runCatching { pathMapper.toHostFile(filePath) }.getOrNull()
            ?: java.io.File(filePath)
        val error = browserController.uploadFile(id, hostFile)
        return if (error == null) {
            val after = browserController.snapshot()
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(true),
                        "note" to JsonPrimitive("已上传文件 ${pathMapper.toContainerPath(hostFile.absolutePath)} 到元素 #$id"),
                        "page" to snapshotToJson(after)
                    )
                )
            )
        } else {
            ToolResult.Error(error, "UPLOAD_FAILED")
        }
    }

    private suspend fun doScreenshot(args: Map<String, JsonElement>): ToolResult {
        val elementId = args["element_id"]?.jsonPrimitive?.contentOrNull
        val dataUrl = browserController.screenshot(elementId)
            ?: return ToolResult.Error("截图失败：浏览器页面尚未渲染（请先在浏览器页打开页面）", "SCREENSHOT_EMPTY")
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "note" to JsonPrimitive(if (elementId != null) "元素截图（data:image/png;base64），多模态模型可直接查看。" else "页面截图（data:image/png;base64），多模态模型可直接查看。"),
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

    /** 请求用户接管当前页面（验证码/支付/二次认证等），用户完成后返回最新快照。 */
    private suspend fun doTakeover(args: Map<String, JsonElement>): ToolResult {
        val message = args["message"]?.jsonPrimitive?.contentOrNull
            ?: "请用户亲自完成当前页面上的操作（如验证码、支付、二次认证等）。"
        val snap = browserController.snapshot()
        val host = runCatching { URI(snap.url).host }.getOrNull().orEmpty()
        val title = if (host.isNotBlank()) "需要你接管 $host" else "需要你接管浏览器"
        val answer = takeoverManager.awaitTakeover(title, message)
        return if (answer.confirmed) {
            val after = browserController.snapshot()
            ToolResult.Success(snapshotToJson(after))
        } else {
            ToolResult.Success(JsonPrimitive("用户未接管/取消了接管请求。"))
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

    // ─────────────────────────── 多标签页 ───────────────────────────

    private suspend fun doNewTab(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
        url?.let { browserController.validateUrl(it)?.let { e -> return ToolResult.Error(e, "UNSAFE_URL") } }
        val info = browserController.newTab(url)
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tab_id" to JsonPrimitive(info.id),
                    "title" to JsonPrimitive(info.title),
                    "url" to JsonPrimitive(info.url),
                    "tabs" to tabsToJson()
                )
            )
        )
    }

    private suspend fun doSwitchTab(args: Map<String, JsonElement>): ToolResult {
        val id = args["tab_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("switch_tab 需要 tab_id", "MISSING_TAB_ID")
        val err = browserController.switchTab(id)
        return if (err != null) ToolResult.Error(err, "SWITCH_TAB_FAILED")
        else ToolResult.Success(snapshotToJson(browserController.snapshot()))
    }

    private suspend fun doCloseTab(args: Map<String, JsonElement>): ToolResult {
        val id = args["tab_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("close_tab 需要 tab_id", "MISSING_TAB_ID")
        val err = browserController.closeTab(id)
        return if (err != null) ToolResult.Error(err, "CLOSE_TAB_FAILED")
        else ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tabs" to tabsToJson(),
                    "page" to snapshotToJson(browserController.snapshot())
                )
            )
        )
    }

    private fun doListTabs(): ToolResult = ToolResult.Success(tabsToJson())

    private fun doListDownloads(): ToolResult {
        val downloads = browserController.listDownloads()
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "count" to JsonPrimitive(downloads.size),
                    "downloads" to JsonArray(
                        downloads.map { d ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(d.id),
                                    "url" to JsonPrimitive(d.url),
                                    "file_name" to JsonPrimitive(d.fileName),
                                    "path" to JsonPrimitive(d.path),
                                    "status" to JsonPrimitive(d.status),
                                    "error" to JsonPrimitive(d.error)
                                )
                            )
                        }
                    )
                )
            )
        )
    }

    // ─────────────────────────── 动态数据捕获：网络请求查询 ───────────────────────────

    private suspend fun doNetwork(args: Map<String, JsonElement>): ToolResult {
        val limit = runCatching { args["limit"]?.jsonPrimitive?.contentOrNull?.toInt() }.getOrNull() ?: 20
        val records = browserController.listNetwork(limit)
        val total = browserController.networkTotalCount()
        val pending = browserController.networkPendingCount()
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "count" to JsonPrimitive(records.size),
                    "total" to JsonPrimitive(total),
                    "pending" to JsonPrimitive(pending),
                    "note" to JsonPrimitive("已按时间倒序返回最近 ${records.size} 条异步数据请求（URL/响应均脱敏，敏感参数置 ***）。"),
                    "requests" to networkListToJson(records)
                )
            )
        )
    }

    private suspend fun doNetworkGet(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
        val id = runCatching { args["network_id"]?.jsonPrimitive?.contentOrNull?.toInt() }.getOrNull()
        val rec = browserController.getNetwork(url, id)
        return if (rec != null) {
            ToolResult.Success(networkToJson(rec))
        } else {
            ToolResult.Success(
                JsonObject(mapOf("ok" to JsonPrimitive(false), "found" to JsonPrimitive(false), "note" to JsonPrimitive("未找到匹配的网络请求记录。")))
            )
        }
    }

    private suspend fun doWaitForRequest(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("wait_for_request 需要 url 参数（要等待的请求 URL 子串）", "MISSING_URL")
        val timeout = runCatching { args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull() ?: 15_000L
        val rec = browserController.waitForRequest(url, timeout)
        return if (rec != null) {
            ToolResult.Success(networkToJson(rec))
        } else {
            val pending = browserController.networkPendingCount()
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(false),
                        "found" to JsonPrimitive(false),
                        "pending" to JsonPrimitive(pending),
                        "note" to JsonPrimitive("等待超时，未捕获到匹配 $url 的请求。当前在途业务请求 $pending 个。")
                    )
                )
            )
        }
    }

    private fun networkListToJson(records: List<BrowserNetworkRecord>): JsonArray =
        JsonArray(records.map { networkToJson(it) })

    private fun networkToJson(rec: BrowserNetworkRecord): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(rec.id),
                "op" to JsonPrimitive(rec.op),
                "method" to JsonPrimitive(rec.method),
                "url" to JsonPrimitive(rec.url),
                "status" to JsonPrimitive(rec.status),
                "duration_ms" to JsonPrimitive(rec.durationMs),
                "size" to JsonPrimitive(rec.size),
                "response_snippet" to JsonPrimitive(rec.responseSnippet),
                "error" to JsonPrimitive(rec.error)
            )
        )

    private fun tabsToJson(): JsonArray {
        val activeId = browserController.uiState.value.activeTabId
        return JsonArray(
            browserController.listTabs().map { t ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(t.id),
                        "title" to JsonPrimitive(t.title),
                        "url" to JsonPrimitive(t.url),
                        "active" to JsonPrimitive(t.id == activeId)
                    )
                )
            }
        )
    }

    // ─────────────────────────── 序列化辅助 ───────────────────────────

    private fun snapshotToJson(snap: BrowserPageSnapshot, compact: Boolean = false): JsonObject {
        val elements = snap.elements.take(MAX_ELEMENTS)
        val elementsJson = JsonArray(
            elements.map { el ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(el.id),
                        "kind" to JsonPrimitive(el.kind),
                        "tag" to JsonPrimitive(el.tag),
                        "type" to JsonPrimitive(el.type),
                        "label" to JsonPrimitive(el.label),
                        "text" to JsonPrimitive(if (el.sensitive) "" else el.text),
                        "value" to JsonPrimitive(if (el.sensitive) "" else el.value),
                        "href" to JsonPrimitive(el.href),
                        "placeholder" to JsonPrimitive(el.placeholder),
                        "checked" to JsonPrimitive(el.checked),
                        "disabled" to JsonPrimitive(el.disabled),
                        "required" to JsonPrimitive(el.required),
                        "readonly" to JsonPrimitive(el.readonly),
                        "options" to JsonArray(
                            el.options.map { o ->
                                JsonObject(mapOf("value" to JsonPrimitive(o.value), "text" to JsonPrimitive(o.text)))
                            }
                        ),
                        "sensitive" to JsonPrimitive(el.sensitive)
                    )
                )
            }
        )
        // 每控件一行的紧凑清单，模型可快速扫出「编号 + 控件类型 + 名称 + 状态」，据此用 element_id 精确操作
        val sb = StringBuilder()
        for (el in elements) {
            sb.append('[').append(el.id).append("] ").append(el.kind)
            val name = el.label.ifBlank { el.text }.ifBlank { el.value }
            if (name.isNotBlank()) sb.append(" \"").append(name).append('"')
            when {
                el.kind.endsWith(":checkbox") || el.kind.endsWith(":radio") ->
                    sb.append(if (el.checked) " [已勾选]" else " [未勾选]")
                el.kind == "select" && el.value.isNotBlank() ->
                    sb.append(" [当前=").append(el.value).append(']')
            }
            if (el.kind == "select" && el.options.isNotEmpty()) {
                sb.append(" 选项=").append(el.options.joinToString("/") { o -> o.text.ifBlank { o.value } })
            }
            if (el.required) sb.append(" [必填]")
            if (el.disabled) sb.append(" [禁用]")
            if (el.kind == "link" && el.href.isNotBlank()) sb.append(" -> ").append(el.href)
            if (el.placeholder.isNotBlank() && el.label.isBlank()) sb.append(" (placeholder=").append(el.placeholder).append(')')
            sb.append('\n')
        }
        // 标题大纲（缩进展示层级，帮助模型快速理解页面结构）
        val headingSb = StringBuilder()
        for (h in snap.headings) {
            headingSb.append("  ".repeat((h.level - 1).coerceAtLeast(0)))
                .append('H').append(h.level).append(' ').append(h.text).append('\n')
        }
        val headingsJson = JsonArray(
            snap.headings.map { h ->
                JsonObject(mapOf("level" to JsonPrimitive(h.level), "text" to JsonPrimitive(h.text)))
            }
        )
        val pageText = if (compact) "" else snap.pageText.take(MAX_TEXT)
        return JsonObject(
            mapOf(
                "title" to JsonPrimitive(snap.title),
                "url" to JsonPrimitive(snap.url),
                "login_page" to JsonPrimitive(snap.hasLoginForm),
                "login_hint" to JsonPrimitive(snap.loginHint),
                "pending_requests" to JsonPrimitive(snap.pendingRequests),
                "headings_summary" to JsonPrimitive(headingSb.toString()),
                "headings" to headingsJson,
                "controls_total" to JsonPrimitive(snap.elements.size),
                "controls_shown" to JsonPrimitive(elements.size),
                "controls_summary" to JsonPrimitive(sb.toString()),
                "elements" to elementsJson,
                "page_text" to JsonPrimitive(pageText)
            )
        )
    }

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host } .getOrNull()?.lowercase()
    }
}
