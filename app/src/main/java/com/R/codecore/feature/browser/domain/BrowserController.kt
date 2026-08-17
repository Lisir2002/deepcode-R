package com.R.codecore.feature.browser.domain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置服务浏览器核心控制器（单例）。
 *
 * 职责：持有唯一的 [WebView] 实例，同时服务于
 *  - 用户侧：[ServiceBrowserScreen] 通过 [bind]/[unbind] 挂载/卸载同一 WebView；
 *  - 模型侧：[BrowserAgentTool] 通过 suspend 操作（navigate/snapshot/click/type/...）驱动页面。
 *
 * 线程模型：WebView 所有操作必须在主线程执行。本类把每个操作封成 suspend 函数，
 * 内部统一调度到主线程（[withContext](Dispatchers.Main) 或主 Handler post），
 * 工具侧在 IO 协程调用即可。
 *
 * 关键设计：模型与用户共享同一个浏览会话 —— 同一份 Cookie、同一个页面，
 * 用户登录后模型自动复用登录态。
 */
@Singleton
class BrowserController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BrowserController"

        /** 页面快照 JS：给可交互元素打 data-rcb-id 并返回元素树。 */
        const val JS_SNAPSHOT = """
            (function() {
              var els = [];
              var seen = new WeakSet();
              var counter = 1;
              var sel = 'a,button,input,select,textarea,[role="button"],[role="link"],[role="menuitem"],[contenteditable="true"],summary';
              function collect(root) {
                if (!root || seen.has(root)) return;
                seen.add(root);
                var nodes = root.querySelectorAll ? root.querySelectorAll(sel) : [];
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (el.closest('[data-rcb-skip]')) continue;
                  if (el.tagName === 'INPUT' && el.type === 'hidden') continue;
                  var rect = el.getBoundingClientRect();
                  if (rect.width < 1 || rect.height < 1) continue;
                  var id = el.getAttribute('data-rcb-id');
                  if (!id) { id = String(counter++); el.setAttribute('data-rcb-id', id); }
                  var text = (el.innerText || '').trim().slice(0, 100);
                  if (!text) text = (el.value || '').trim().slice(0, 100);
                  if (!text) text = (el.getAttribute('placeholder') || '').trim().slice(0, 60);
                  els.push({
                    id: id,
                    tag: el.tagName.toLowerCase(),
                    role: el.getAttribute('role') || '',
                    type: el.getAttribute('type') || '',
                    name: el.getAttribute('name') || '',
                    text: text,
                    disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                    placeholder: el.getAttribute('placeholder') || ''
                  });
                }
              }
              collect(document);
              return JSON.stringify({ title: document.title || '', url: location.href, elements: els });
            })();
        """

        /** 页面纯文本 JS。 */
        const val JS_PAGE_TEXT = """
            (function() {
              var t = document.body ? document.body.innerText : '';
              return t.slice(0, 12000);
            })();
        """

        /** 滚动 JS。 */
        const val JS_SCROLL = """
            (function() {
              var d = arguments[0];
              var h = window.innerHeight || 600;
              if (d === 'top') window.scrollTo(0, 0);
              else if (d === 'bottom') window.scrollTo(0, document.body.scrollHeight);
              else if (d === 'up') window.scrollBy(0, -Math.floor(h * 0.8));
              else window.scrollBy(0, Math.floor(h * 0.8));
              return window.scrollY;
            })();
        """

        /** 点击元素 JS。 */
        const val JS_CLICK = """
            (function() {
              var id = arguments[0];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.scrollIntoView({block:'center', behavior:'smooth'});
              var r = el.getBoundingClientRect();
              var x = r.left + r.width / 2, y = r.top + r.height / 2;
              ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t) {
                el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window, clientX:x, clientY:y}));
              });
              return JSON.stringify({ok:true});
            })();
        """

        /** 输入 JS（React 兼容 value setter）。 */
        const val JS_TYPE = """
            (function() {
              var id = arguments[0], text = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.focus();
              var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, text);
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});
            })();
        """

        /** 下拉选择 JS。 */
        const val JS_SELECT = """
            (function() {
              var id = arguments[0], value = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.value = value;
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});
            })();
        """

        /** 提交表单 JS。 */
        const val JS_SUBMIT = """
            (function() {
              var id = arguments[0];
              var el = id ? document.querySelector('[data-rcb-id="' + id + '"]') : null;
              var form = el ? (el.closest('form') || null) : (document.querySelector('form') || null);
              if (el && !form) {
                el.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                return JSON.stringify({ok:true, note:'clicked-element'});
              }
              if (!form) return JSON.stringify({ok:false, reason:'NO_FORM'});
              if (typeof form.requestSubmit === 'function') {
                try { form.requestSubmit(); return JSON.stringify({ok:true, note:'requestSubmit'}); } catch(e){}
              }
              form.dispatchEvent(new Event('submit', {bubbles:true, cancelable:true}));
              return JSON.stringify({ok:true, note:'submit-event'});
            })();
        """

        /** 读属性 JS。 */
        const val JS_ATTRIBUTE = """
            (function() {
              var id = arguments[0], attr = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              return JSON.stringify({ok:true, value: el[attr] != null ? String(el[attr]) : (el.getAttribute(attr) || '')});
            })();
        """
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var webView: WebView? = null

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentBrowserStatus())
    val agentStatus: StateFlow<AgentBrowserStatus> = _agentStatus.asStateFlow()

    private val _pendingDialog = MutableStateFlow<PendingBrowserDialog?>(null)
    val pendingDialog: StateFlow<PendingBrowserDialog?> = _pendingDialog.asStateFlow()

    private var dialogResult: JsResult? = null
    private var dialogDeferred: CompletableDeferred<Boolean>? = null

    /** 最近一次快照，供 get_attribute 等操作参考。 */
    @Volatile
    private var lastSnapshot: BrowserPageSnapshot = BrowserPageSnapshot()

    private val json = Json { ignoreUnknownKeys = true }

    // ─────────────────────────── UI 绑定 ───────────────────────────

    /**
     * 浏览器页挂载 WebView。创建（首次）或复用已有实例，配置好后返回给 Compose AndroidView。
     * 必须在主线程调用（Compose AndroidView factory 即主线程）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun bind(): WebView {
        val existing = webView
        if (existing != null) {
            _uiState.value = _uiState.value.copy(screenVisible = true)
            return existing
        }
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replaceFirst("; wv", "")
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                _uiState.value = _uiState.value.copy(isLoading = true, progress = 10)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    progress = 100,
                    currentUrl = url ?: _uiState.value.currentUrl,
                    canGoBack = view?.canGoBack() ?: false,
                    canGoForward = view?.canGoForward() ?: false
                )
                view?.title?.let { _uiState.value = _uiState.value.copy(title = it) }
            }
            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                FileLogger.w(TAG, "页面加载错误 code=$errorCode url=$failingUrl: $description")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                FileLogger.w(TAG, "页面加载错误: ${error?.errorCode} ${error?.description} ${request?.url}")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _uiState.value = _uiState.value.copy(progress = newProgress)
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                _uiState.value = _uiState.value.copy(title = title ?: "")
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                handleDialog("alert", message ?: "", result)
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                handleDialog("confirm", message ?: "", result)
                return true
            }
        }
        webView = wv
        _uiState.value = _uiState.value.copy(screenVisible = true)
        FileLogger.i(TAG, "WebView 已创建")
        return wv
    }

    /** 浏览器页卸载时调用（不销毁 WebView，保留会话/Cookie）。 */
    fun unbind() {
        _uiState.value = _uiState.value.copy(screenVisible = false)
    }

    /** 后退（用户侧 UI）。 */
    fun goBack() {
        mainHandler.post { webView?.let { if (it.canGoBack()) it.goBack() } }
    }

    /** 前进（用户侧 UI）。 */
    fun goForward() {
        mainHandler.post { webView?.let { if (it.canGoForward()) it.goForward() } }
    }

    /** 刷新当前页（用户侧 UI）。 */
    fun reload() {
        mainHandler.post { webView?.reload() }
    }

    /** 停止加载（用户侧 UI）。 */
    fun stopLoading() {
        mainHandler.post { webView?.stopLoading() }
    }

    /** 应用退出/不再需要时销毁 WebView。 */
    fun destroy() {
        mainHandler.post {
            webView?.let {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.destroy()
            }
            webView = null
        }
    }

    // ─────────────────────── 模型/工具操作（suspend） ───────────────────────

    /** 打开 URL 并返回页面快照。 */
    suspend fun navigate(url: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在导航到 $url", true)
        try {
            val normalized = if (url.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))) url else "https://$url"
            withContext(Dispatchers.Main) { ensureWebView().loadUrl(normalized) }
            waitForPageSettled(30_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 获取当前页面快照（元素树 + 文本）。 */
    suspend fun snapshot(): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在提取页面结构", true)
        try {
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 点击元素，返回点击后的快照。 */
    suspend fun click(elementId: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在点击元素 #$elementId", true)
        try {
            evalJs("($JS_CLICK)(${quote(elementId)})")
            waitForPageSettled(10_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 在元素中输入文本。 */
    suspend fun type(elementId: String, text: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在向 #$elementId 输入内容", true)
        try {
            evalJs("($JS_TYPE)(${quote(elementId)}, ${quote(text)})")
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 下拉选择。 */
    suspend fun selectOption(elementId: String, value: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在选择 #$elementId", true)
        try {
            evalJs("($JS_SELECT)(${quote(elementId)}, ${quote(value)})")
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 提交表单（elementId 可为空 → 提交页面首个表单）。 */
    suspend fun submit(elementId: String?): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在提交表单", true)
        try {
            evalJs("($JS_SUBMIT)(${if (elementId == null) "null" else quote(elementId)})")
            waitForPageSettled(15_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 滚动页面。 */
    suspend fun scroll(direction: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在滚动页面", true)
        try {
            evalJs("($JS_SCROLL)(${quote(direction)})")
            delay(300)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 读取元素属性。 */
    suspend fun getAttribute(elementId: String, attribute: String): String? {
        val out = evalJs("($JS_ATTRIBUTE)(${quote(elementId)}, ${quote(attribute)})")
        val parsed = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull()
        return parsed?.get("value")?.let { if (it is JsonPrimitive) it.content else null }
    }

    /** 执行任意 JS，返回结果字符串。 */
    suspend fun evaluate(js: String): String {
        _agentStatus.value = AgentBrowserStatus("正在执行页面脚本", true)
        return try {
            evalJs(js)
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 页面截图，返回 base64 PNG data URL（供多模态模型查看）。 */
    suspend fun screenshot(): String? = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在截取页面", true)
        try {
            val bmp = withContext(Dispatchers.Main) {
                val wv = ensureWebView()
                if (wv.width <= 0 || wv.height <= 0) return@withContext null
                val b = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(b))
                b
            } ?: return@mutex.withLock null
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 处理浏览器对话框（alert/confirm）。返回是否成功处理。 */
    suspend fun handleDialog(accept: Boolean): Boolean = mutex.withLock {
        val deferred = dialogDeferred ?: return@withLock false
        dialogDeferred = null
        val resolved = deferred.complete(accept)
        // 结果确认动作由监听 dialogDeferred 完成的协程在主线程执行
        withContext(Dispatchers.Main) { delay(50) }
        _pendingDialog.value = null
        resolved
    }

    /** 等待元素出现或加载完成。selector 为 CSS 选择器（匹配则视为满足）。 */
    suspend fun waitFor(selector: String?, timeoutMs: Long): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("等待页面元素", true)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val snap = snapshotInternal()
                if (selector.isNullOrBlank()) {
                    if (snap.url.isNotBlank()) return@mutex.withLock snap
                } else {
                    val found = evalJs("(function(){ return !!document.querySelector(${quote(selector)}); })()")
                    if (found.trim() == "true") return@mutex.withLock snap
                }
                delay(500)
            }
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /** 当前页面是否存在登录表单（密码框等）。 */
    fun detectLoginForm(snapshot: BrowserPageSnapshot): Pair<Boolean, String> {
        var hasPassword = false
        var usernameId: String? = null
        var passwordId: String? = null
        for (e in snapshot.elements) {
            if (e.tag == "input" && e.type == "password") {
                hasPassword = true
                passwordId = e.id
            } else if (e.tag == "input" && (e.type == "text" || e.type == "email" || e.type == "tel" || (e.type.isBlank() && e.name.isNotBlank()))) {
                if (usernameId == null) usernameId = e.id
            }
        }
        return if (hasPassword && passwordId != null) {
            true to "检测到登录表单（用户名框=${usernameId ?: "未知"}，密码框=$passwordId）。请调用 login 工具自动代填，或提示用户手动填写。"
        } else if (snapshot.pageText.contains(Regex("(?i)sign in|log in|login|登录|登陆|登入"))) {
            true to "页面可能要求登录（检测到登录相关文案）。请调用 login 工具处理。"
        } else {
            false to ""
        }
    }

    private suspend fun snapshotInternal(): BrowserPageSnapshot {
        val elJson = evalJs(JS_SNAPSHOT)
        val parsed = runCatching { json.parseToJsonElement(elJson).jsonObject }.getOrNull()
        val elements = runCatching {
            parsed?.get("elements")?.let { el ->
                Json { ignoreUnknownKeys = true }.decodeFromString<List<BrowserElement>>(el.toString())
            }
        }.getOrNull() ?: emptyList()
        val title = parsed?.get("title")?.let { if (it is JsonPrimitive) it.content else "" } ?: ""
        val url = parsed?.get("url")?.let { if (it is JsonPrimitive) it.content else "" } ?: ""
        val pageText = evalJs(JS_PAGE_TEXT)
        val (hasLogin, hint) = detectLoginForm(
            BrowserPageSnapshot(title = title, url = url, elements = elements, pageText = pageText.take(12000))
        )
        val snap = BrowserPageSnapshot(
            title = title,
            url = url,
            elements = elements,
            pageText = pageText.take(12000),
            hasLoginForm = hasLogin,
            loginHint = hint
        )
        lastSnapshot = snap
        return snap
    }

    /** 主线程执行 JS（evaluateJavascript 回调转 suspend）。 */
    private suspend fun evalJs(js: String): String {
        return withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            val deferred = CompletableDeferred<String>()
            wv.evaluateJavascript(js) { result -> deferred.complete(result ?: "null") }
            try {
                withTimeoutOrNull(15_000) { deferred.await() } ?: "null"
            } catch (e: Exception) {
                FileLogger.e(TAG, "evaluateJavascript 失败", e)
                "null"
            }
        }
    }

    /** 在主线程获取 WebView 实例（懒创建）。 */
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView ?: bind()
    }

    /** WebChromeClient 对话框回调：登记待处理对话框，等待工具侧处理。 */
    private fun handleDialog(type: String, message: String, result: JsResult?) {
        dialogResult?.cancel()
        val id = UUID.randomUUID().toString()
        _pendingDialog.value = PendingBrowserDialog(id, type, message)
        dialogResult = result
        val deferred = CompletableDeferred<Boolean>()
        dialogDeferred = deferred
        scope.launch {
            val accept = try { deferred.await() } catch (e: Exception) { false }
            mainHandler.post {
                val r = dialogResult
                if (r != null) {
                    if (accept) r.confirm() else r.cancel()
                }
                if (_pendingDialog.value?.id == id) _pendingDialog.value = null
                if (dialogResult === r) dialogResult = null
            }
        }
    }

    private suspend fun waitForPageSettled(timeoutMs: Long) {
        var waited = 0L
        while (waited < timeoutMs) {
            val loading = withContext(Dispatchers.Main) { _uiState.value.isLoading }
            if (!loading) break
            delay(100)
            waited += 100
        }
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }

    /** 当前快照（模型读属性等用途）。 */
    fun currentSnapshot(): BrowserPageSnapshot = lastSnapshot
}
