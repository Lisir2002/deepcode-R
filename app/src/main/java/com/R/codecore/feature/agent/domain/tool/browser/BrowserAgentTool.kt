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
import com.R.codecore.feature.browser.domain.BrowserSnapshotDelta
import com.R.codecore.feature.browser.domain.BrowserTabInfo
import com.R.codecore.feature.browser.domain.BrowserTakeoverManager
import com.R.codecore.feature.browser.domain.SnapshotLevel
import com.R.codecore.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.CancellationException
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
 *  - snapshot()             提取页面（快照分级：summary/standard/full，见 snapshot_level）
 *  - page_text()            单独取页面正文（按需取文，省 token）
 *  - click(element_id)      点击元素
 *  - type(element_id,text)  输入文本
 *  - select_option(id,val)  下拉选择
 *  - submit(element_id?)    提交表单（id 可空→提交页面首个表单）
 *  - scroll(direction)      滚动（top/bottom/up/down）
 *  - screenshot()           页面截图（返回 data URL，多模态模型查看）
 *  - evaluate(js)           执行任意 JS
 *  - wait_for(selector)     等待元素出现
 *  - wait_for_change()      事件驱动等待页面变化（替代轮询 snapshot）
 *  - history()              查询最近动作历史
 *  - get_attribute(id,attr) 读元素属性
 *  - handle_dialog(accept)  处理页面 alert/confirm
 *  - login()                登录当前站点（从加密凭据库取账号密码自动代填；无则请用户输入）
 *
 * 统一返回 envelope（R2.3 干净替换）：所有动作返回
 * `{ok, action, changed, summary, note|error, recoverable, snapshot?, delta?}`，
 * 写操作自动返回增量 delta 让模型无需自行轮询。
 * `element_id` 接受 data-rcb-id / CSS 绝对路径 / 语义描述符三者任一（三级定位）。
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
            "典型用法：browser.navigate(url) → browser.snapshot() 查看页面 → browser.click/type/submit 操作 → browser.wait_for_change() 等待变化 → browser.screenshot() 查看效果。" +
            "所有动作返回统一 envelope：{ok, action, changed, summary, note|error, recoverable, snapshot?, delta?}，写操作自带 delta 增量对比，无需反复 snapshot。" +
            "快照分级 snapshot_level：summary（默认，控件摘要，最省 token）/ standard（含完整元素）/ full（含页面正文）。" +
            "element_id 可传 data-rcb-id / CSS 绝对路径 / 语义描述符（role=… name=… index=…）三者任一。" +
            "遇到验证码/支付/二次认证等无法自动完成的步骤时，调用 takeover 请求用户亲自接管。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE, ToolCapability.USER_INTERACTION)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "要执行的浏览器动作：navigate / snapshot / page_text / extract / click / type / select_option / submit / scroll / hover / drag / press_key / upload_file / back / forward / reload / screenshot / evaluate / wait_for / wait_for_change / history / get_attribute / handle_dialog / login / takeover / new_tab / switch_tab / close_tab / list_tabs / downloads / network / network_get / wait_for_request",
            required = true,
            enum = listOf(
                "navigate", "snapshot", "page_text", "extract", "click", "type", "select_option", "submit",
                "scroll", "hover", "drag", "press_key", "upload_file", "back", "forward", "reload",
                "screenshot", "evaluate", "wait_for", "wait_for_change", "history", "get_attribute",
                "handle_dialog", "login", "takeover",
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
            description = "click/type/select_option/submit/get_attribute 时必填：snapshot 返回的元素 data-rcb-id，也接受 CSS 绝对路径或语义描述符（role=… name=… index=…）三者任一；screenshot 时可选：仅截取该元素区域",
            required = false
        ),
        "snapshot_level" to ToolParameter(
            name = "snapshot_level",
            type = ParameterType.STRING,
            description = "snapshot / 各写操作返回时可选：快照粒度 summary（默认，仅控件摘要，最省 token）/ standard（含完整元素 JSON）/ full（含页面正文）。同一值也作为结果分级：summary 只回一行结论+摘要",
            required = false,
            enum = listOf("summary", "standard", "full")
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
            description = "wait_for / wait_for_change / wait_for_request 时可选：等待超时毫秒（wait_for 默认 10000，wait_for_change 默认 15000，wait_for_request 默认 15000）",
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
                "page_text" -> doPageText()
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
                "back" -> doNavigation("back", browserController.back())
                "forward" -> doNavigation("forward", browserController.forward())
                "reload" -> doNavigation("reload", browserController.reloadPage())
                "screenshot" -> doScreenshot(args)
                "evaluate" -> doEvaluate(args)
                "wait_for" -> doWaitFor(args)
                "wait_for_change" -> doWaitForChange(args)
                "history" -> doHistory()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "browser.$action 失败", e)
            ToolResult.Error("浏览器操作失败: ${e.message}")
        }
    }

    // ─────────────────────────── 动作实现 ───────────────────────────

    private suspend fun doNavigate(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("navigate 需要 url 参数", "MISSING_URL")
        browserController.validateUrl(url)?.let {
            return envelope("navigate", ok = false, error = it, recoverable = true, summary = "导航被拦截：$it")
        }
        val snap = browserController.navigate(url)
        return envelope(
            action = "navigate",
            ok = true,
            changed = true,
            summary = "已打开 ${snap.url.ifBlank { url }}（${snap.title.ifBlank { "(无标题)" }}）",
            snapshot = snapshotToJson(snap, navLevelOf(args))
        )
    }

    private suspend fun doSnapshot(args: Map<String, JsonElement>): ToolResult {
        val level = snapshotLevelOf(args)
        val snap = browserController.snapshot(level)
        return envelope("snapshot", ok = true, summary = snapshotSummary(snap), snapshot = snapshotToJson(snap, level))
    }

    /** 按需取正文（R2.1）：模型需要页面文本时单独取，不随快照默认返回。 */
    private suspend fun doPageText(): ToolResult {
        val text = browserController.pageText().take(MAX_TEXT)
        return envelope("page_text", ok = true, summary = "页面正文共 ${text.length} 字（超过 ${MAX_TEXT} 字已截断）", note = text)
    }

    private suspend fun doExtract(args: Map<String, JsonElement>): ToolResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "text"
        val result = browserController.extract(selector, mode)
        val parsed = runCatching { json.parseToJsonElement(result) }.getOrNull()
        val note = if (parsed != null) result.take(MAX_TEXT) else result.take(MAX_TEXT)
        return envelope("extract", ok = true, summary = "已按 mode=$mode 抽取数据", note = note)
    }

    // ─────────────────── 写操作（统一 envelope + 增量 delta） ───────────────────

    /**
     * 写操作统一入口（R2.3 写操作自动验证）：执行动作 → 检测"元素未找到"类失败 →
     * 取 controller 计算好的增量 delta 一并返回，模型无需自行轮询。
     */
    private suspend fun writeEnvelope(action: String, args: Map<String, JsonElement>, run: suspend () -> BrowserPageSnapshot): ToolResult {
        val snap = run()
        val notFound = snap.pageText.takeIf { it.startsWith("元素 ") && it.contains("未找到") }
        if (notFound != null) {
            return envelope(
                action = action,
                ok = false,
                error = notFound,
                recoverable = true,
                summary = "$action 失败：$notFound",
                note = "元素可能因页面刷新/重渲染失效，请重新 snapshot 获取最新元素标识"
            )
        }
        val delta = browserController.lastDelta()
        return envelope(
            action = action,
            ok = true,
            changed = delta != null &&
                (delta.added.isNotEmpty() || delta.changed.isNotEmpty() || delta.removed.isNotEmpty() || delta.textNote.isNotBlank()),
            summary = delta?.let { writeDeltaSummary(action, it) } ?: "$action 完成",
            snapshot = snapshotToJson(snap, snapshotLevelOf(args)),
            delta = delta
        )
    }

    private suspend fun doClick(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("click 需要 element_id", "MISSING_ELEMENT_ID")
        return writeEnvelope("click", args) { browserController.click(id) }
    }

    private suspend fun doType(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("type 需要 element_id", "MISSING_ELEMENT_ID")
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("type 需要 text", "MISSING_TEXT")
        return writeEnvelope("type", args) { browserController.type(id, text) }
    }

    private suspend fun doSelect(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("select_option 需要 element_id", "MISSING_ELEMENT_ID")
        val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("select_option 需要 value", "MISSING_VALUE")
        return writeEnvelope("select_option", args) { browserController.selectOption(id, value) }
    }

    private suspend fun doSubmit(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull
        return writeEnvelope("submit", args) { browserController.submit(id) }
    }

    private suspend fun doScroll(args: Map<String, JsonElement>): ToolResult {
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        return writeEnvelope("scroll", args) { browserController.scroll(direction) }
    }

    private suspend fun doHover(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("hover 需要 element_id", "MISSING_ELEMENT_ID")
        return writeEnvelope("hover", args) { browserController.hover(id) }
    }

    private suspend fun doDrag(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("drag 需要 element_id", "MISSING_ELEMENT_ID")
        val targetId = args["target_element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("drag 需要 target_element_id", "MISSING_TARGET_ELEMENT_ID")
        return writeEnvelope("drag", args) { browserController.drag(id, targetId) }
    }

    private suspend fun doPressKey(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("press_key 需要 element_id", "MISSING_ELEMENT_ID")
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("press_key 需要 key", "MISSING_KEY")
        return writeEnvelope("press_key", args) { browserController.pressKey(id, key) }
    }

    private suspend fun doUploadFile(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("upload_file 需要 element_id", "MISSING_ELEMENT_ID")
        val filePath = args["file_path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("upload_file 需要 file_path", "MISSING_FILE_PATH")
        val hostFile = runCatching { pathMapper.toHostFile(filePath) }.getOrNull()
            ?: java.io.File(filePath)
        val error = browserController.uploadFile(id, hostFile)
        return if (error == null) {
            val after = browserController.snapshot(snapshotLevelOf(args))
            envelope(
                action = "upload_file",
                ok = true,
                summary = "已上传文件 ${pathMapper.toContainerPath(hostFile.absolutePath)} 到元素 #$id",
                snapshot = snapshotToJson(after, snapshotLevelOf(args))
            )
        } else {
            envelope("upload_file", ok = false, error = error, recoverable = true, summary = "上传失败：$error")
        }
    }

    /** 导航类动作统一处理（back/forward/reload）：页面完全变化，默认给 standard 快照。 */
    private suspend fun doNavigation(action: String, snap: BrowserPageSnapshot): ToolResult {
        val verb = when (action) {
            "back" -> "已后退"
            "forward" -> "已前进"
            else -> "已刷新"
        }
        return envelope(
            action = action,
            ok = true,
            changed = true,
            summary = "${verb}到 ${snap.title.ifBlank { "(无标题)" }}（${snap.url}）",
            snapshot = snapshotToJson(snap, navLevelOf(emptyMap()))
        )
    }

    private suspend fun doScreenshot(args: Map<String, JsonElement>): ToolResult {
        val elementId = args["element_id"]?.jsonPrimitive?.contentOrNull
        val dataUrl = browserController.screenshot(elementId)
            ?: return envelope(
                "screenshot", ok = false, recoverable = true,
                summary = "截图失败", error = "截图失败：浏览器页面尚未渲染（请先在浏览器页打开页面）"
            )
        return envelope(
            action = "screenshot",
            ok = true,
            summary = "已截取${if (elementId != null) "元素 #$elementId" else "页面"}截图",
            note = "data:image/png;base64，多模态模型可直接查看",
            extra = JsonObject(mapOf("image_data_url" to JsonPrimitive(dataUrl)))
        )
    }

    private suspend fun doEvaluate(args: Map<String, JsonElement>): ToolResult {
        val js = args["js"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("evaluate 需要 js 参数", "MISSING_JS")
        val result = browserController.evaluate(js)
        return envelope("evaluate", ok = true, summary = "JS 执行完成", note = result.take(MAX_TEXT))
    }

    private suspend fun doWaitFor(args: Map<String, JsonElement>): ToolResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val timeout = runCatching { args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull() ?: 10_000L
        val snap = browserController.waitFor(selector, timeout)
        return envelope(
            action = "wait_for",
            ok = true,
            changed = true,
            summary = "等待完成：${if (selector.isNullOrBlank()) "页面已就绪" else "元素 $selector 已出现"}",
            snapshot = snapshotToJson(snap, snapshotLevelOf(args))
        )
    }

    /** 事件驱动等待页面变化（R2.4）：替代轮询 snapshot，页面 DOM/网络变化即返回摘要。 */
    private suspend fun doWaitForChange(args: Map<String, JsonElement>): ToolResult {
        val timeout = runCatching { args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull() ?: 15_000L
        val result = browserController.waitForChange(timeout)
        return if (result.changed) {
            envelope(
                action = "wait_for_change",
                ok = true,
                changed = true,
                summary = "页面发生变化：${result.summary.take(200)}"
            )
        } else {
            envelope(
                action = "wait_for_change",
                ok = true,
                changed = false,
                summary = "等待 ${timeout}ms 超时，页面无变化",
                note = "可调用 snapshot 查看当前状态，或继续其他操作"
            )
        }
    }

    /** 动作历史查询（R2.3）：返回最近 30 条操作 + 结果摘要，避免重复操作。 */
    private suspend fun doHistory(): ToolResult {
        val history = browserController.actionHistory()
        return envelope(
            action = "history",
            ok = true,
            summary = "最近 ${history.size} 条动作",
            note = history.joinToString("\n")
        )
    }

    private suspend fun doGetAttribute(args: Map<String, JsonElement>): ToolResult {
        val id = args["element_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("get_attribute 需要 element_id", "MISSING_ELEMENT_ID")
        val attr = args["attribute"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("get_attribute 需要 attribute", "MISSING_ATTRIBUTE")
        val value = browserController.getAttribute(id, attr)
        return envelope(
            action = "get_attribute",
            ok = true,
            summary = "属性 $attr 读取完成",
            note = "$id.$attr = ${value ?: "(空)"}"
        )
    }

    private suspend fun doHandleDialog(args: Map<String, JsonElement>): ToolResult {
        val accept = runCatching { args["accept"]?.jsonPrimitive?.contentOrNull?.toBoolean() }.getOrNull() ?: true
        val handled = browserController.handleDialog(accept)
        return envelope(
            action = "handle_dialog",
            ok = handled,
            summary = if (handled) "对话框已${if (accept) "确认" else "取消"}" else "当前没有待处理的对话框"
        )
    }

    /** 请求用户接管当前页面（验证码/支付/二次认证等），用户完成后返回最新快照。 */
    private suspend fun doTakeover(args: Map<String, JsonElement>): ToolResult {
        val message = args["message"]?.jsonPrimitive?.contentOrNull
            ?: "请用户亲自完成当前页面上的操作（如验证码、支付、二次认证等）。"
        val snap = browserController.snapshot(SnapshotLevel.SUMMARY)
        val host = runCatching { URI(snap.url).host }.getOrNull().orEmpty()
        val title = if (host.isNotBlank()) "需要你接管 $host" else "需要你接管浏览器"
        val answer = takeoverManager.awaitTakeover(title, message)
        return if (answer.confirmed) {
            val after = browserController.snapshot(SnapshotLevel.SUMMARY)
            envelope(
                action = "takeover",
                ok = true,
                changed = true,
                summary = "用户已完成接管，页面状态已更新",
                snapshot = snapshotToJson(after, navLevelOf(emptyMap()))
            )
        } else {
            envelope("takeover", ok = false, summary = "用户未接管/取消了接管请求。")
        }
    }

    /** 登录当前站点：从凭据库取账号密码自动代填；无则请求用户输入。 */
    private suspend fun doLogin(): ToolResult {
        val snap = browserController.snapshot(SnapshotLevel.SUMMARY)
        if (!snap.hasLoginForm) {
            return envelope("login", ok = false, summary = "无需登录", error = "当前页面未检测到登录表单（无密码输入框）")
        }
        val host = extractHost(snap.url) ?: "unknown"
        var cred = credentialStore.find(host)
        if (cred == null) {
            FileLogger.i(TAG, "凭据库无 $host 凭据，请求用户输入")
            val answer = loginPromptManager.awaitCredentials(host)
            if (answer.cancelled || answer.username.isNullOrBlank() || answer.password.isNullOrBlank()) {
                return envelope(
                    "login", ok = false, summary = "未获得凭据",
                    note = "用户未提供 $host 的登录凭据。请提示用户在浏览器页手动登录，或重新调用 login 让用户输入。"
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
            return envelope("login", ok = false, recoverable = true, summary = "登录失败", error = "未找到密码输入框，无法自动代填")
        }
        if (!usernameId.isNullOrBlank()) browserController.type(usernameId, cred.username)
        browserController.type(passwordId, cred.password)
        // 尝试提交（优先提交登录表单）
        val after = browserController.submit(usernameId)
        return envelope(
            action = "login",
            ok = true,
            summary = "已用 ${cred.username} 自动代填并提交登录表单",
            note = "若登录成功，后续请求自动携带登录态。",
            snapshot = snapshotToJson(after, navLevelOf(emptyMap()))
        )
    }

    // ─────────────────────────── 多标签页 ───────────────────────────

    private suspend fun doNewTab(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
        url?.let { browserController.validateUrl(it)?.let { e -> return envelope("new_tab", ok = false, error = e, recoverable = true, summary = "已拦截：$e") } }
        val info = browserController.newTab(url)
        return envelope(
            action = "new_tab",
            ok = true,
            changed = true,
            summary = "已新建标签${if (url != null) "并打开 $url" else ""}",
            extra = JsonObject(
                mapOf(
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
        return if (err != null) {
            envelope("switch_tab", ok = false, error = err, recoverable = true, summary = "切换失败：$err")
        } else {
            val snap = browserController.snapshot(navLevelOf(args))
            envelope(
                action = "switch_tab",
                ok = true,
                changed = true,
                summary = "已切换到标签 ${snap.title.ifBlank { id }}",
                snapshot = snapshotToJson(snap, navLevelOf(args))
            )
        }
    }

    private suspend fun doCloseTab(args: Map<String, JsonElement>): ToolResult {
        val id = args["tab_id"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("close_tab 需要 tab_id", "MISSING_TAB_ID")
        val err = browserController.closeTab(id)
        return if (err != null) {
            envelope("close_tab", ok = false, error = err, recoverable = true, summary = "关闭失败：$err")
        } else {
            val snap = browserController.snapshot(navLevelOf(args))
            envelope(
                action = "close_tab",
                ok = true,
                changed = true,
                summary = "已关闭标签 $id",
                snapshot = snapshotToJson(snap, navLevelOf(args)),
                extra = JsonObject(mapOf("tabs" to tabsToJson()))
            )
        }
    }

    private fun doListTabs(): ToolResult = envelope(
        action = "list_tabs",
        ok = true,
        summary = "当前共 ${browserController.listTabs().size} 个标签",
        extra = JsonObject(mapOf("tabs" to tabsToJson()))
    )

    private fun doListDownloads(): ToolResult {
        val downloads = browserController.listDownloads()
        return envelope(
            action = "downloads",
            ok = true,
            summary = "最近 ${downloads.size} 个下载任务",
            extra = JsonObject(
                mapOf(
                    "count" to JsonPrimitive(downloads.size),
                    "downloads" to JsonArray(downloads.map { downloadToJson(it) })
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
        return envelope(
            action = "network",
            ok = true,
            summary = "已按时间倒序返回最近 ${records.size} 条异步数据请求",
            note = "URL/响应均脱敏，敏感参数置 ***。",
            extra = JsonObject(
                mapOf(
                    "count" to JsonPrimitive(records.size),
                    "total" to JsonPrimitive(total),
                    "pending" to JsonPrimitive(pending),
                    "requests" to JsonArray(records.map { networkToJson(it) })
                )
            )
        )
    }

    private suspend fun doNetworkGet(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
        val id = runCatching { args["network_id"]?.jsonPrimitive?.contentOrNull?.toInt() }.getOrNull()
        val rec = browserController.getNetwork(url, id)
        return if (rec != null) {
            envelope(
                action = "network_get",
                ok = true,
                summary = "已找到请求记录 #${rec.id}",
                extra = JsonObject(mapOf("request" to networkToJson(rec)))
            )
        } else {
            envelope("network_get", ok = false, summary = "未找到匹配的网络请求记录")
        }
    }

    private suspend fun doWaitForRequest(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("wait_for_request 需要 url 参数（要等待的请求 URL 子串）", "MISSING_URL")
        val timeout = runCatching { args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull() ?: 15_000L
        val rec = browserController.waitForRequest(url, timeout)
        return if (rec != null) {
            envelope(
                action = "wait_for_request",
                ok = true,
                changed = true,
                summary = "已捕获到匹配 $url 的请求",
                extra = JsonObject(mapOf("request" to networkToJson(rec)))
            )
        } else {
            val pending = browserController.networkPendingCount()
            envelope(
                action = "wait_for_request",
                ok = false,
                summary = "等待超时，未捕获到匹配 $url 的请求",
                note = "当前在途业务请求 $pending 个。"
            )
        }
    }

    private fun downloadToJson(d: BrowserDownloadInfo): JsonObject =
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

    // ─────────────────────────── 统一 envelope ───────────────────────────

    /**
     * 统一动作 envelope（R2.3 干净替换）：所有动作返回 `{ok, action, changed, summary, note|error,
     * recoverable, snapshot?, delta?}`，[extra] 用于追加结构化的动作专属数据（tabs/requests 等）。
     */
    private fun envelope(
        action: String,
        ok: Boolean = true,
        changed: Boolean = false,
        summary: String = "",
        note: String = "",
        error: String = "",
        recoverable: Boolean = false,
        snapshot: JsonObject? = null,
        delta: BrowserSnapshotDelta? = null,
        extra: JsonObject? = null
    ): ToolResult {
        val fields = linkedMapOf<String, JsonElement>(
            "ok" to JsonPrimitive(ok),
            "action" to JsonPrimitive(action),
            "changed" to JsonPrimitive(changed),
            "summary" to JsonPrimitive(summary)
        )
        if (note.isNotBlank()) fields["note"] = JsonPrimitive(note)
        if (error.isNotBlank()) fields["error"] = JsonPrimitive(error)
        if (recoverable) fields["recoverable"] = JsonPrimitive(true)
        snapshot?.let { fields["snapshot"] = it }
        delta?.let { fields["delta"] = deltaToJson(it) }
        extra?.let { fields.putAll(it) }
        return ToolResult.Success(JsonObject(fields))
    }

    /** 增量 delta 序列化（R2.1）：新增/变化元素以紧凑摘要返回，消失元素只给 id。 */
    private fun deltaToJson(delta: BrowserSnapshotDelta): JsonObject = JsonObject(
        mapOf(
            "added" to JsonArray(delta.added.map { elementBriefJson(it) }),
            "removed" to JsonArray(delta.removed.map { JsonPrimitive(it) }),
            "changed" to JsonArray(delta.changed.map { elementBriefJson(it) }),
            "text_note" to JsonPrimitive(delta.textNote)
        )
    )

    private fun writeDeltaSummary(action: String, delta: BrowserSnapshotDelta): String = buildString {
        append(action).append("完成")
        val parts = mutableListOf<String>()
        if (delta.added.isNotEmpty()) parts.add("新增 ${delta.added.size}")
        if (delta.changed.isNotEmpty()) parts.add("变化 ${delta.changed.size}")
        if (delta.removed.isNotEmpty()) parts.add("消失 ${delta.removed.size}")
        if (parts.isNotEmpty()) append("：").append(parts.joinToString("，"))
        if (delta.textNote.isNotBlank()) append("；").append(delta.textNote)
    }

    private fun snapshotSummary(snap: BrowserPageSnapshot): String = buildString {
        append("页面「").append(snap.title.ifBlank { "(无标题)" }).append("」")
        if (snap.url.isNotBlank()) append(" URL=").append(snap.url)
        val visible = snap.elements.count { it.visible && it.inViewport }
        append(" 控件 ").append(snap.elements.size).append(" 个（视口内 ").append(visible).append("）")
        if (snap.hasLoginForm) append("，检测到登录表单")
        if (snap.pendingRequests > 0) append("，在途请求 ").append(snap.pendingRequests)
    }

    private fun snapshotLevelOf(args: Map<String, JsonElement>): SnapshotLevel =
        SnapshotLevel.fromName(args["snapshot_level"]?.jsonPrimitive?.contentOrNull)

    /** 导航/标签切换类动作默认 standard（页面完全变化，给完整元素便于继续操作），可用 snapshot_level 覆盖。 */
    private fun navLevelOf(args: Map<String, JsonElement>): SnapshotLevel {
        val explicit = args["snapshot_level"]?.jsonPrimitive?.contentOrNull
        return if (explicit != null) SnapshotLevel.fromName(explicit) else SnapshotLevel.STANDARD
    }

    // ─────────────────────────── 序列化辅助 ───────────────────────────

    /**
     * 快照分级序列化（R2.1）：
     *  - summary：标题/URL/登录信号 + 每控件一行的紧凑摘要（含可操作性标注），不含完整元素与 page_text；
     *  - standard：summary + 完整元素 JSON；
     *  - full：standard + page_text。
     */
    private fun snapshotToJson(snap: BrowserPageSnapshot, level: SnapshotLevel = SnapshotLevel.SUMMARY): JsonObject {
        val elements = snap.elements.take(MAX_ELEMENTS)
        // 每控件一行的紧凑清单 + 可操作性标注（R2.2）：模型可快速扫出「编号 + 控件类型 + 名称 + 状态 + 可操作性」
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
            // 可操作性标注（R2.2）：不可见/视口外/被遮挡
            if (!el.visible) sb.append(" [不可见]")
            else if (el.needsScroll) sb.append(" [视口外]")
            if (el.overlapped) sb.append(" [被遮挡]")
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
        val fields = linkedMapOf<String, JsonElement>(
            "title" to JsonPrimitive(snap.title),
            "url" to JsonPrimitive(snap.url),
            "login_page" to JsonPrimitive(snap.hasLoginForm),
            "login_hint" to JsonPrimitive(snap.loginHint),
            "pending_requests" to JsonPrimitive(snap.pendingRequests),
            "controls_total" to JsonPrimitive(snap.elements.size),
            "controls_shown" to JsonPrimitive(elements.size),
            "controls_summary" to JsonPrimitive(sb.toString()),
            "headings_summary" to JsonPrimitive(headingSb.toString())
        )
        if (level != SnapshotLevel.SUMMARY) {
            fields["headings"] = JsonArray(
                snap.headings.map { h ->
                    JsonObject(mapOf("level" to JsonPrimitive(h.level), "text" to JsonPrimitive(h.text)))
                }
            )
            fields["elements"] = JsonArray(elements.map { elementJson(it) })
        }
        if (level == SnapshotLevel.FULL) {
            fields["page_text"] = JsonPrimitive(snap.pageText.take(MAX_TEXT))
        }
        return JsonObject(fields)
    }

    /** 完整元素 JSON（standard/full 级）：含三级定位字段与可操作性标注（R2.2）。 */
    private fun elementJson(el: BrowserElement): JsonObject = JsonObject(
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
            "sensitive" to JsonPrimitive(el.sensitive),
            // R2.2 三级定位 + 可操作性标注
            "locator" to JsonPrimitive(el.locator),
            "semantic" to JsonPrimitive(el.semantic),
            "in_viewport" to JsonPrimitive(el.inViewport),
            "visible" to JsonPrimitive(el.visible),
            "needs_scroll" to JsonPrimitive(el.needsScroll),
            "overlapped" to JsonPrimitive(el.overlapped)
        )
    )

    /** 增量/变化元素紧凑摘要（delta 用）。 */
    private fun elementBriefJson(el: BrowserElement): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(el.id),
            "kind" to JsonPrimitive(el.kind),
            "label" to JsonPrimitive(el.label),
            "text" to JsonPrimitive(if (el.sensitive) "" else el.text),
            "in_viewport" to JsonPrimitive(el.inViewport),
            "visible" to JsonPrimitive(el.visible),
            "needs_scroll" to JsonPrimitive(el.needsScroll),
            "overlapped" to JsonPrimitive(el.overlapped)
        )
    )

    private fun extractHost(url: String): String? {
        return runCatching { URI(url).host } .getOrNull()?.lowercase()
    }
}
