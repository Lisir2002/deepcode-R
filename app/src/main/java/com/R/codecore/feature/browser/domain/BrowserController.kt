package com.R.codecore.feature.browser.domain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.workspace.domain.WorkspacePathMapper
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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
    @param:ApplicationContext private val context: Context,
    private val pathMapper: WorkspacePathMapper
) {
    private companion object {
        const val TAG = "BrowserController"

        /** 导航协议白名单：拦截 file://、content://、intent://、javascript: 等危险 scheme。 */
        val ALLOWED_SCHEMES = setOf("http", "https")

        /** 页面快照 JS：给可交互控件打 data-rcb-id，解析控件类型/标签/取值/状态，返回元素树。 */
        const val JS_SNAPSHOT = """
            (function() {
              function txt(n) { return (n && (n.innerText || n.textContent || '') || '').replace(/\s+/g, ' ').trim(); }
              function resolveLabel(el) {
                var al = (el.getAttribute('aria-label') || '').trim();
                if (al) return al;
                var lb = el.getAttribute('aria-labelledby');
                if (lb) {
                  var parts = [], ns = lb.split(/\s+/);
                  for (var i = 0; i < ns.length; i++) {
                    var ref = document.getElementById(ns[i]);
                    if (ref) { var t = txt(ref); if (t) parts.push(t); }
                  }
                  if (parts.length) return parts.join(' ');
                }
                var fid = el.id || el.name;
                if (fid) {
                  try {
                    var lab = document.querySelector('label[for="' + fid + '"]');
                    if (lab) { var t = txt(lab); if (t) return t; }
                  } catch (e) {}
                }
                if (el.closest) {
                  var parent = el.closest('label');
                  if (parent) { var t2 = txt(parent); if (t2) return t2; }
                }
                return '';
              }
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
                  var tag = el.tagName.toLowerCase();
                  var type = (el.getAttribute('type') || '').toLowerCase();
                  var role = el.getAttribute('role') || '';
                  var sensitive = tag === 'input' && type === 'password';
                  var kind = tag;
                  if (tag === 'a' && el.getAttribute('href')) kind = 'link';
                  else if (tag === 'button' || role === 'button') kind = 'button';
                  else if (tag === 'input') kind = 'input:' + (type || 'text');
                  else if (tag === 'select') kind = 'select';
                  else if (tag === 'textarea') kind = 'textarea';
                  var label = resolveLabel(el);
                  var visibleText = (tag === 'input' || tag === 'textarea') ? '' : txt(el);
                  var value = (tag === 'input' || tag === 'textarea') ? (sensitive ? '' : (el.value || '')) : '';
                  var options = [];
                  if (tag === 'select') {
                    var so = el.options && el.options[el.selectedIndex];
                    if (so) value = so.text || so.value || '';
                    for (var o = 0; o < el.options.length && o < 30; o++) {
                      options.push({ value: el.options[o].value || '', text: txt(el.options[o]) || el.options[o].value || '' });
                    }
                  }
                  var checked = (tag === 'input' && (type === 'checkbox' || type === 'radio')) ? !!el.checked : false;
                  els.push({
                    id: id,
                    tag: tag,
                    kind: kind,
                    role: role,
                    type: type,
                    name: el.getAttribute('name') || '',
                    label: label,
                    text: visibleText.slice(0, 200),
                    value: value.slice(0, 200),
                    href: el.getAttribute('href') || '',
                    ariaLabel: el.getAttribute('aria-label') || '',
                    ariaExpanded: el.getAttribute('aria-expanded') || '',
                    heading: /^H[1-6]$/.test(el.tagName) ? tag : '',
                    contenteditable: el.getAttribute('contenteditable') === 'true',
                    disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                    required: !!el.required,
                    readonly: !!el.readOnly,
                    checked: checked,
                    options: options,
                    placeholder: el.getAttribute('placeholder') || '',
                    sensitive: sensitive
                  });
                }
              }
              collect(document);
              var headings = [];
              var hnodes = document.querySelectorAll('h1,h2,h3,h4,h5,h6');
              for (var i = 0; i < hnodes.length && i < 100; i++) {
                headings.push({ level: parseInt(hnodes[i].tagName.charAt(1)), text: txt(hnodes[i]).slice(0, 200) });
              }
              return JSON.stringify({ title: document.title || '', url: location.href, headings: headings, elements: els });
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
              var sensitive = el.tagName === 'INPUT' && el.type === 'password';
              var val;
              if (sensitive && (attr === 'value' || attr === 'textContent')) {
                val = '[redacted]';
              } else {
                val = el[attr] != null ? String(el[attr]) : (el.getAttribute(attr) || '');
              }
              return JSON.stringify({ok:true, value: val});
            })();
        """

        /** 悬停元素 JS（触发 hover 相关 UI，如下拉菜单/提示）。 */
        const val JS_HOVER = """
            (function() {
              var id = arguments[0];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.scrollIntoView({block:'center'});
              var r = el.getBoundingClientRect();
              var x = r.left + r.width / 2, y = r.top + r.height / 2;
              ['pointerover','mouseover','mouseenter','mousemove'].forEach(function(t) {
                el.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window, clientX:x, clientY:y}));
              });
              return JSON.stringify({ok:true});
            })();
        """

        /** 按键 JS（向元素派发 keydown/keyup）。 */
        const val JS_PRESS_KEY = """
            (function() {
              var id = arguments[0], key = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.focus();
              var code = key;
              if (key.length === 1) code = 'Key' + key.toUpperCase();
              else if (key === ' ') code = 'Space';
              var init = {bubbles:true, cancelable:true, key:key, code:code};
              el.dispatchEvent(new KeyboardEvent('keydown', init));
              el.dispatchEvent(new KeyboardEvent('keyup', init));
              return JSON.stringify({ok:true});
            })();
        """

        /** 拖拽 JS：从源元素拖到目标元素（鼠标/指针事件模拟，覆盖滑块、排序等场景）。 */
        const val JS_DRAG = """
            (function() {
              var srcId = arguments[0], dstId = arguments[1];
              var src = document.querySelector('[data-rcb-id="' + srcId + '"]');
              var dst = dstId ? document.querySelector('[data-rcb-id="' + dstId + '"]') : null;
              if (!src) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              var sr = src.getBoundingClientRect();
              var dr = dst ? dst.getBoundingClientRect() : sr;
              var sx = sr.left + sr.width / 2, sy = sr.top + sr.height / 2;
              var dx = dr.left + dr.width / 2, dy = dr.top + dr.height / 2;
              function me(t, x, y) { return new MouseEvent(t, {bubbles:true, cancelable:true, view:window, clientX:x, clientY:y, button:0}); }
              src.dispatchEvent(me('pointerdown', sx, sy));
              src.dispatchEvent(me('mousedown', sx, sy));
              src.dispatchEvent(me('mousemove', dx, dy));
              (dst || src).dispatchEvent(me('mousemove', dx, dy));
              (dst || src).dispatchEvent(me('mouseup', dx, dy));
              src.dispatchEvent(me('pointerup', dx, dy));
              return JSON.stringify({ok:true});
            })();
        """

        /** 触发文件输入点击（配合 onShowFileChooser 自动回填）。 */
        const val JS_UPLOAD_CLICK = """
            (function() {
              var id = arguments[0];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el || el.tagName !== 'INPUT' || el.type !== 'file') {
                return JSON.stringify({ok:false, reason: el ? 'NOT_FILE_INPUT' : 'NOT_FOUND'});
              }
              el.click();
              return JSON.stringify({ok:true});
            })();
        """

        /** 元素截图 JS：滚动元素到视口中央并返回其相对视口矩形。 */
        const val JS_ELEMENT_RECT = """
            (function() {
              var id = arguments[0];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.scrollIntoView({block:'center'});
              var r = el.getBoundingClientRect();
              return JSON.stringify({ok:true, x:r.left, y:r.top, width:r.width, height:r.height});
            })();
        """

        /** 结构化取数 JS：按 selector + mode 抽取链接/标题/表格/文本/HTML。 */
        const val JS_EXTRACT = """
            (function() {
              var selector = arguments[0], mode = arguments[1] || 'text';
              var el = selector ? document.querySelector(selector) : document;
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              function txt(n) { return (n.innerText || n.textContent || '').trim(); }
              if (mode === 'links') {
                var links = el.querySelectorAll ? el.querySelectorAll('a') : [];
                var out = [];
                for (var i=0;i<links.length && i<200;i++) {
                  var h = links[i].getAttribute('href') || '';
                  var t = txt(links[i]);
                  if (h || t) out.push({text: t.slice(0,120), href: h});
                }
                return JSON.stringify({ok:true, mode:mode, data: out});
              }
              if (mode === 'headings') {
                var heads = el.querySelectorAll ? el.querySelectorAll('h1,h2,h3,h4,h5,h6') : [];
                var hs = [];
                for (var j=0;j<heads.length && j<200;j++) {
                  hs.push({level: parseInt(heads[j].tagName.charAt(1)), text: txt(heads[j]).slice(0,200)});
                }
                return JSON.stringify({ok:true, mode:mode, data: hs});
              }
              if (mode === 'table') {
                var table = el.tagName === 'TABLE' ? el : (el.querySelector ? el.querySelector('table') : null);
                if (!table) return JSON.stringify({ok:false, reason:'NO_TABLE'});
                var trs = table.querySelectorAll('tr');
                var rows = [];
                for (var k=0;k<trs.length && k<500;k++) {
                  var tds = trs[k].querySelectorAll('th,td');
                  var cells = [];
                  for (var m=0;m<tds.length;m++) cells.push(txt(tds[m]).slice(0,200));
                  rows.push(cells);
                }
                return JSON.stringify({ok:true, mode:mode, data: rows});
              }
              if (mode === 'html') {
                return JSON.stringify({ok:true, mode:mode, data: (el.innerHTML || '').slice(0, 20000)});
              }
              return JSON.stringify({ok:true, mode:mode, data: txt(el).slice(0, 20000)});
            })();
        """

        /**
         * 动态数据捕获插桩 JS：在 document-start 注入（比任何页面脚本早），
         * 包装 fetch / XMLHttpRequest / WebSocket / EventSource，把异步数据请求写入
         * 页面内内存环形缓冲 window.__rcb_net，并维护在途计数 window.__rcb_net_pending。
         *
         * 关键约定：
         *  - 幂等：window.__rcb_net 已存在则跳过（防止重复包装）；
         *  - 幂等范围：全 http/https 注入，与 ALLOWED_SCHEMES 白名单一致；
         *  - 脱敏：URL query 敏感参数、响应体敏感 JSON key 值均替换为 ***；
         *  - 只统计业务请求（fetch/XHR/WS/SSE），图片/字体/追踪脚本不参与空闲判定。
         */
        const val JS_NET_HOOK = """
            (function() {
              if (window.__rcb_net) return;
              var MAX_NET = 100;
              var SNIPPET_MAX = 2000;
              var URL_KEY_RE = /([?&](?:token|access_token|id_token|refresh_token|apikey|api_key|secret|password|credential|session|sig|signature|key|auth)=)[^&#]*/gi;
              var BODY_KEY_RE = /"((?:access_token|id_token|refresh_token|password|secret|apikey|api_key))"(\s*:\s*)"[^"]*"/gi;

              window.__rcb_net = [];
              window.__rcb_net_pending = 0;
              window.__rcb_net_seq = 0;
              window.__rcb_route_seq = 0;

              function redactUrl(u) {
                return String(u || '').replace(URL_KEY_RE, function(m, p) { return p + '***'; });
              }
              function redactBody(s) {
                s = String(s || '');
                return s.replace(BODY_KEY_RE, function(m, key, sp) { return '"' + key + '"' + sp + '"***"'; });
              }
              function snippet(s) { return redactBody(s).slice(0, SNIPPET_MAX); }
              function pushRec(rec) {
                if (window.__rcb_net.length >= MAX_NET) window.__rcb_net.shift();
                window.__rcb_net.push(rec);
              }
              function newRec(op, method, url) {
                return { id: ++window.__rcb_net_seq, op: op, method: method, url: redactUrl(url), status: 0, duration_ms: 0, size: 0, start_ts: Date.now(), response_snippet: '', error: '' };
              }
              function settleRec(rec, status, bodyText, error) {
                rec.end_ts = Date.now();
                rec.duration_ms = rec.end_ts - rec.start_ts;
                if (typeof bodyText === 'string') { rec.size = bodyText.length; rec.response_snippet = snippet(bodyText); }
                if (status !== undefined && status !== null) rec.status = status;
                if (error) rec.error = String(error).slice(0, 200);
                window.__rcb_net_pending = Math.max(0, window.__rcb_net_pending - 1);
                pushRec(rec);
              }

              if (window.fetch) {
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                  var url = '';
                  try {
                    if (typeof input === 'string') url = input;
                    else if (input && input.url) url = input.url;
                    else if (input && input.href) url = input.href;
                  } catch (e) {}
                  var method = (init && init.method) || 'GET';
                  var rec = newRec('fetch', method, url);
                  window.__rcb_net_pending++;
                  var p;
                  try { p = origFetch.apply(this, arguments); }
                  catch (e) { settleRec(rec, 0, null, e && e.message); throw e; }
                  return p.then(function(resp) {
                    try {
                      rec.status = resp.status;
                      var c = resp.clone();
                      if (c && c.text) {
                        c.text().then(function(t) { rec.size = t.length; rec.response_snippet = snippet(t); }).catch(function() { rec.size = -1; });
                      }
                    } catch (e) { rec.size = -1; }
                    rec.end_ts = Date.now(); rec.duration_ms = rec.end_ts - rec.start_ts;
                    window.__rcb_net_pending = Math.max(0, window.__rcb_net_pending - 1);
                    pushRec(rec);
                    return resp;
                  }).catch(function(e) {
                    rec.error = String((e && e.message) || e).slice(0, 200);
                    rec.end_ts = Date.now(); rec.duration_ms = rec.end_ts - rec.start_ts;
                    window.__rcb_net_pending = Math.max(0, window.__rcb_net_pending - 1);
                    pushRec(rec);
                    throw e;
                  });
                };
              }

              if (window.XMLHttpRequest) {
                var XHR = window.XMLHttpRequest;
                var origOpen = XHR.prototype.open;
                var origSend = XHR.prototype.send;
                XHR.prototype.open = function(method, url) {
                  try { this.__rcb = { method: method, url: url }; } catch (e) {}
                  return origOpen.apply(this, arguments);
                };
                XHR.prototype.send = function() {
                  var self = this;
                  var rec = null;
                  if (self.__rcb) {
                    rec = newRec('xhr', self.__rcb.method || 'GET', self.__rcb.url || '');
                    window.__rcb_net_pending++;
                  }
                  if (rec && self.addEventListener) {
                    self.addEventListener('loadend', function() {
                      var bodyText = null;
                      try {
                        if (self.responseType === '' || self.responseType === 'text') bodyText = self.responseText;
                        else {
                          if (self.response && (self.response.size != null)) rec.size = self.response.size;
                          else if (self.response && (self.response.byteLength != null)) rec.size = self.response.byteLength;
                          else rec.size = -1;
                        }
                      } catch (e) { rec.size = -1; }
                      settleRec(rec, self.status, bodyText, (self.status === 0) ? 'network-error-or-aborted' : '');
                    }, { once: true });
                  }
                  return origSend.apply(this, arguments);
                };
              }

              if (window.WebSocket) {
                var WS = window.WebSocket;
                function wsPatch(ws) {
                  try {
                    var url = '';
                    try { url = ws.url || ''; } catch (e) {}
                    var connRec = newRec('websocket', 'connect', url);
                    window.__rcb_net_pending++;
                    ws.addEventListener('open', function() {
                      connRec.status = 101; connRec.duration_ms = Date.now() - connRec.start_ts;
                      window.__rcb_net_pending = Math.max(0, window.__rcb_net_pending - 1); pushRec(connRec);
                    });
                    ws.addEventListener('close', function(e) {
                      connRec.status = (e && e.code) ? e.code : 0; connRec.duration_ms = Date.now() - connRec.start_ts; pushRec(connRec);
                    });
                    ws.addEventListener('error', function() { connRec.error = 'websocket-error'; });
                    ws.addEventListener('message', function(e) {
                      var m = newRec('websocket', 'message', url);
                      var data = e && e.data;
                      if (typeof data === 'string') { m.size = data.length; m.response_snippet = snippet(data); }
                      else if (data && (data.size != null)) m.size = data.size;
                      else if (data && (data.byteLength != null)) m.size = data.byteLength;
                      else if (data instanceof ArrayBuffer) m.size = data.byteLength;
                      else if (data) m.size = -1;
                      m.status = 101; m.duration_ms = 0; pushRec(m);
                    });
                    try {
                      var origSend = ws.send;
                      ws.send = function(data) {
                        var m = newRec('websocket', 'send', url);
                        try {
                          if (typeof data === 'string') { m.size = data.length; m.response_snippet = snippet(data); }
                          else if (data instanceof ArrayBuffer) m.size = data.byteLength;
                          else if (data && (data.byteLength != null)) m.size = data.byteLength;
                          else if (data && (data.size != null)) m.size = data.size;
                          else if (data) m.size = -1;
                        } catch (e2) { m.size = -1; }
                        pushRec(m);
                        return origSend.apply(this, arguments);
                      };
                    } catch (e) {}
                  } catch (e) {}
                }
                window.WebSocket = function(url, protocols) {
                  var ws;
                  try { ws = (arguments.length > 1) ? new WS(url, protocols) : new WS(url); } catch (e) { throw e; }
                  wsPatch(ws);
                  return ws;
                };
                try { window.WebSocket.prototype = WS.prototype; } catch (e) {}
              }

              if (window.EventSource) {
                var ES = window.EventSource;
                function esPatch(es) {
                  try {
                    var url = '';
                    try { url = es.url || ''; } catch (e) {}
                    var connRec = newRec('eventsource', 'connect', url);
                    window.__rcb_net_pending++;
                    es.addEventListener('open', function() {
                      connRec.status = 200; connRec.duration_ms = Date.now() - connRec.start_ts;
                      window.__rcb_net_pending = Math.max(0, window.__rcb_net_pending - 1); pushRec(connRec);
                    });
                    es.addEventListener('error', function() { connRec.error = 'eventsource-error'; });
                    es.addEventListener('message', function(e) {
                      var m = newRec('eventsource', 'event', url);
                      var data = e && e.data;
                      if (typeof data === 'string') { m.size = data.length; m.response_snippet = snippet(data); }
                      m.status = 200; m.duration_ms = 0; pushRec(m);
                    });
                  } catch (e) {}
                }
                window.EventSource = function(url, config) {
                  var es;
                  try { es = (arguments.length > 1) ? new ES(url, config) : new ES(url); } catch (e) { throw e; }
                  esPatch(es);
                  return es;
                };
                try { window.EventSource.prototype = ES.prototype; } catch (e) {}
              }

              try {
                function routeChanged() {
                  window.__rcb_route_seq++;
                  try { window.dispatchEvent(new Event('rcb-routechange')); } catch (e2) {}
                }
                ['pushState', 'replaceState'].forEach(function(m) {
                  var orig = history[m];
                  history[m] = function() {
                    var r = orig.apply(this, arguments);
                    routeChanged();
                    return r;
                  };
                });
                window.addEventListener('popstate', routeChanged);
              } catch (e) {}
            })();
        """
    }

    /** 内部标签：id + WebView + 标题/URL 缓存。 */
    private class BrowserTab(
        val id: String,
        val webView: WebView,
        var title: String = "",
        var url: String = ""
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** 全部标签页（每个标签独占一个 WebView，保留各自历史栈/会话/Cookie）。 */
    private val tabs = mutableListOf<BrowserTab>()

    /** 当前激活标签 id。 */
    private var activeTabId: String? = null

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _tabsState = MutableStateFlow<List<BrowserTabInfo>>(emptyList())
    /** 标签列表（UI 标签栏与 list_tabs 工具共用）。 */
    val tabsState: StateFlow<List<BrowserTabInfo>> = _tabsState.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentBrowserStatus())
    val agentStatus: StateFlow<AgentBrowserStatus> = _agentStatus.asStateFlow()

    private val _downloads = MutableStateFlow<List<BrowserDownloadInfo>>(emptyList())
    /** 最近下载任务（供 downloads 工具 / 模型查询）。 */
    val downloads: StateFlow<List<BrowserDownloadInfo>> = _downloads.asStateFlow()

    private val _pendingDialog = MutableStateFlow<PendingBrowserDialog?>(null)
    val pendingDialog: StateFlow<PendingBrowserDialog?> = _pendingDialog.asStateFlow()

    private var dialogResult: JsResult? = null
    private var dialogDeferred: CompletableDeferred<Boolean>? = null

    /** upload_file：模型请求上传时暂存目标文件；onShowFileChooser 触发时用 FileProvider Uri 回填。 */
    @Volatile
    private var pendingUploadFile: File? = null

    @Volatile
    private var pendingUploadDone: CompletableDeferred<Boolean>? = null

    /** 最近一次快照，供 get_attribute 等操作参考。 */
    @Volatile
    private var lastSnapshot: BrowserPageSnapshot = BrowserPageSnapshot()

    private val json = Json { ignoreUnknownKeys = true }

    // ─────────────────────────── UI 绑定 ───────────────────────────

    /**
     * 浏览器页挂载 WebView。创建（首次）或复用已有实例，配置好后返回给 Compose AndroidView。
     * 必须在主线程调用（Compose AndroidView factory 即主线程）。
     */
    /** 确保存在一个激活标签（浏览器页组合前调用；避免 composition 期间创建标签引发 key 跳变崩溃）。 */
    fun ensureActiveTab() {
        if (tabs.isEmpty()) {
            val tab = createTab(null)
            activeTabId = tab.id
            applyActiveTab(tab)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun bind(): WebView {
        ensureActiveTab()
        val active = activeTab() ?: createTab(null).also {
            activeTabId = it.id
            applyActiveTab(it)
        }
        _uiState.value = _uiState.value.copy(screenVisible = true)
        return active.webView
    }

    /** 浏览器页卸载时调用（不销毁 WebView，保留会话/Cookie）。 */
    fun unbind() {
        _uiState.value = _uiState.value.copy(screenVisible = false)
    }

    /** 后退（用户侧 UI）。 */
    fun goBack() {
        mainHandler.post { activeWebView()?.let { if (it.canGoBack()) it.goBack() } }
    }

    /** 前进（用户侧 UI）。 */
    fun goForward() {
        mainHandler.post { activeWebView()?.let { if (it.canGoForward()) it.goForward() } }
    }

    /** 刷新当前页（用户侧 UI）。 */
    fun reload() {
        mainHandler.post { activeWebView()?.reload() }
    }

    /** 停止加载（用户侧 UI）。 */
    fun stopLoading() {
        mainHandler.post { activeWebView()?.stopLoading() }
    }

    /** 应用退出/不再需要时销毁所有 WebView。 */
    fun destroy() {
        mainHandler.post {
            tabs.forEach { detachAndDestroy(it.webView) }
            tabs.clear()
            activeTabId = null
            _tabsState.value = emptyList()
        }
    }

    // ─────────────────────── 模型/工具操作（suspend） ───────────────────────

    /** 规范化 URL：无协议时自动补 https://。 */
    fun normalizeUrl(url: String): String =
        if (url.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))) url else "https://$url"

    /** 校验导航目标：返回 null 表示安全，否则返回拦截原因（供工具侧返回错误）。 */
    fun validateUrl(url: String): String? {
        val normalized = normalizeUrl(url)
        val scheme = runCatching { URI(normalized).scheme }.getOrNull()?.lowercase()
            ?: return "无法解析 URL：$url"
        if (scheme !in ALLOWED_SCHEMES) {
            return "已拦截导航：不允许的协议 '$scheme'（仅允许 http/https）。"
        }
        return null
    }

    /** 打开 URL 并返回页面快照。危险协议会被拒绝加载。 */
    suspend fun navigate(url: String): BrowserPageSnapshot = mutex.withLock {
        val normalized = normalizeUrl(url)
        val blocked = validateUrl(normalized)
        if (blocked != null) {
            FileLogger.w(TAG, blocked)
            return@withLock BrowserPageSnapshot(url = "", pageText = blocked)
        }
        _agentStatus.value = AgentBrowserStatus("正在导航到 $url", true)
        try {
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

    /** 结构化取数：按 CSS selector + mode 抽取文本/链接/标题/表格/HTML，返回原始 JSON 字符串。 */
    suspend fun extract(selector: String?, mode: String): String {
        _agentStatus.value = AgentBrowserStatus("正在抽取结构化数据", true)
        return try {
            evalJs("($JS_EXTRACT)(${if (selector == null) "null" else quote(selector)}, ${quote(mode)})")
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 页面截图（未指定元素时截视口），返回 base64 PNG data URL（供多模态模型查看）。 */
    suspend fun screenshot(elementId: String? = null): String? = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus(if (elementId != null) "正在截取元素 #$elementId" else "正在截取页面", true)
        try {
            // 元素截图：先滚动元素到视口中央并取相对视口矩形
            var crop: Rect? = null
            if (elementId != null) {
                val raw = evalJs("($JS_ELEMENT_RECT)(${quote(elementId)})")
                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val ok = runCatching { (obj?.get("ok") as? JsonPrimitive)?.content?.toBoolean() }.getOrNull() ?: false
                if (!ok) return@withLock null
                fun num(key: String): Float? = (obj?.get(key) as? JsonPrimitive)?.content?.toFloatOrNull()
                val x = num("x") ?: return@withLock null
                val y = num("y") ?: return@withLock null
                val w = num("width") ?: return@withLock null
                val h = num("height") ?: return@withLock null
                crop = Rect(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
            }
            val bmp = withContext(Dispatchers.Main) {
                val wv = ensureWebView()
                if (wv.width <= 0 || wv.height <= 0) return@withContext null
                val full = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(full))
                val rect = crop
                if (rect != null) {
                    val left = rect.left.coerceIn(0, full.width - 1)
                    val top = rect.top.coerceIn(0, full.height - 1)
                    val right = rect.right.coerceIn(left + 1, full.width)
                    val bottom = rect.bottom.coerceIn(top + 1, full.height)
                    val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                    if (cropped !== full) full.recycle()
                    cropped
                } else full
            } ?: return@withLock null
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
                    if (snap.url.isNotBlank()) return@withLock snap
                } else {
                    val found = evalJs("(function(){ return !!document.querySelector(${quote(selector)}); })()")
                    if (found.trim() == "true") return@withLock snap
                }
                delay(500)
            }
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    // ─────────────────── 补充动作（hover/drag/press_key/upload/导航） ───────────────────

    /** 悬停元素，返回悬停后快照。 */
    suspend fun hover(elementId: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在悬停元素 #$elementId", true)
        try {
            evalJs("($JS_HOVER)(${quote(elementId)})")
            delay(200)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 按键（向元素派发 keydown/keyup）。 */
    suspend fun pressKey(elementId: String, key: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在按键 #$elementId", true)
        try {
            evalJs("($JS_PRESS_KEY)(${quote(elementId)}, ${quote(key)})")
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 拖拽：从源元素拖到目标元素（targetElementId 可空，表示原地拖拽）。 */
    suspend fun drag(elementId: String, targetElementId: String?): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在拖拽 #$elementId", true)
        try {
            evalJs("($JS_DRAG)(${quote(elementId)}, ${if (targetElementId == null) "null" else quote(targetElementId)})")
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /**
     * 向页面中 file 输入框自动回填本地文件。
     *
     * @return null 表示成功；否则返回错误说明（供工具侧透传给模型）。
     */
    suspend fun uploadFile(elementId: String, hostFile: File): String? = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在上传文件", true)
        try {
            if (!hostFile.exists() || !hostFile.isFile) {
                return@withLock "上传失败：文件不存在 ${hostFile.absolutePath}"
            }
            val done = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                pendingUploadFile = hostFile
                pendingUploadDone = done
            }
            try {
                evalJs("($JS_UPLOAD_CLICK)(${quote(elementId)})")
                val ok = withTimeoutOrNull(3_000) { done.await() } ?: false
                if (ok) null
                else "未能自动上传：文件选择器未被触发（可能被页面脚本拦截）。可改用 takeover 让用户手动选择文件。"
            } finally {
                withContext(Dispatchers.Main) {
                    pendingUploadFile = null
                    pendingUploadDone = null
                }
            }
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 后退一页，返回快照。 */
    suspend fun back(): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在后退", true)
        try {
            withContext(Dispatchers.Main) { activeWebView()?.takeIf { it.canGoBack() }?.goBack() }
            waitForPageSettled(15_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 前进一页，返回快照。 */
    suspend fun forward(): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在前进", true)
        try {
            withContext(Dispatchers.Main) { activeWebView()?.takeIf { it.canGoForward() }?.goForward() }
            waitForPageSettled(15_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 刷新当前页，返回快照。 */
    suspend fun reloadPage(): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在刷新页面", true)
        try {
            withContext(Dispatchers.Main) { activeWebView()?.reload() }
            waitForPageSettled(30_000)
            snapshotInternal()
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    // ─────────────────── 多标签页（new/switch/close/list） ───────────────────

    /** 新建标签页（可带初始 URL），并切换过去。返回新标签信息。 */
    suspend fun newTab(url: String?): BrowserTabInfo = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在新建标签页", true)
        try {
            withContext(Dispatchers.Main) {
                val tab = createTab(url)
                switchToTab(tab.id)
                BrowserTabInfo(tab.id, tab.title, tab.url)
            }
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 切换到指定标签。返回 null 表示成功，否则返回错误说明。 */
    suspend fun switchTab(tabId: String): String? = mutex.withLock {
        withContext(Dispatchers.Main) {
            if (findTab(tabId) == null) return@withContext "标签不存在：$tabId"
            switchToTab(tabId)
            null
        }
    }

    /** 关闭指定标签。返回 null 表示成功，否则返回错误说明。 */
    suspend fun closeTab(tabId: String): String? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val tab = findTab(tabId) ?: return@withContext "标签不存在：$tabId"
            if (tabs.size <= 1) return@withContext "至少保留一个标签页，无法关闭"
            val idx = tabs.indexOf(tab)
            tabs.removeAt(idx)
            detachAndDestroy(tab.webView)
            if (activeTabId == tabId) {
                val next = tabs.getOrNull(idx) ?: tabs.lastOrNull()
                activeTabId = next?.id
                next?.let(::applyActiveTab)
            }
            publishTabs()
            null
        }
    }

    /** 列出所有标签页（供 list_tabs 工具与 UI 使用）。 */
    fun listTabs(): List<BrowserTabInfo> = tabsState.value

    // ─────────────────────────── 下载 ───────────────────────────

    /** 列出最近下载任务（供 downloads 工具查询）。 */
    fun listDownloads(): List<BrowserDownloadInfo> = _downloads.value

    /**
     * 下载到工作区 downloads 目录（在 IO 协程执行，携带当前会话 Cookie）。
     *
     * @param url              下载地址
     * @param userAgent        页面 UA（用于请求头）
     * @param contentDisposition 响应 Content-Disposition（用于猜文件名）
     * @param mimetype         响应 MIME 类型（用于猜文件名）
     */
    private suspend fun downloadToWorkspace(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val id = UUID.randomUUID().toString()
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val info = BrowserDownloadInfo(id = id, url = url, fileName = fileName, status = "downloading")
        upsertDownload(info)

        try {
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
            val downloadsDir = pathMapper.toHostFile("~/workspace/downloads")
            downloadsDir.mkdirs()
            val outFile = File(downloadsDir, fileName)

            val conn = (URL(url).openConnection() as? HttpURLConnection)
                ?: throw IllegalStateException("非 HTTP 下载地址：$url")
            conn.apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent ?: "R-CodeCore-Browser")
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败（HTTP $code）")
            }
            conn.inputStream.use { input -> outFile.outputStream().use { input.copyTo(it) } }
            conn.disconnect()

            upsertDownload(
                info.copy(
                    status = "done",
                    path = pathMapper.toContainerPath(outFile.absolutePath)
                )
            )
            FileLogger.i(TAG, "下载完成: ${outFile.absolutePath}")
        } catch (e: Exception) {
            FileLogger.w(TAG, "下载失败: $url", e)
            upsertDownload(info.copy(status = "error", error = e.message ?: "下载失败"))
        }
    }

    /** 插入或更新下载任务（保留最多 50 条）。 */
    private fun upsertDownload(info: BrowserDownloadInfo) {
        val list = _downloads.value.toMutableList()
        val idx = list.indexOfFirst { it.id == info.id }
        if (idx >= 0) list[idx] = info else list.add(info)
        if (list.size > 50) list.removeAt(0)
        _downloads.value = list
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

    private fun activeTab(): BrowserTab? = tabs.firstOrNull { it.id == activeTabId }

    private fun activeWebView(): WebView? = activeTab()?.webView

    private fun findTab(id: String): BrowserTab? = tabs.firstOrNull { it.id == id }

    /** 切换到指定标签（主线程）。 */
    private fun switchToTab(tabId: String) {
        val tab = findTab(tabId) ?: return
        activeTabId = tabId
        applyActiveTab(tab)
        publishTabs()
    }

    /** 用指定标签刷新 UI 状态（主线程）。 */
    private fun applyActiveTab(tab: BrowserTab) {
        _uiState.value = _uiState.value.copy(
            currentUrl = tab.url,
            title = tab.title,
            canGoBack = tab.webView.canGoBack(),
            canGoForward = tab.webView.canGoForward(),
            isLoading = false,
            progress = 0,
            activeTabId = tab.id
        )
    }

    /** 将标签列表同步到 [tabsState]。 */
    private fun publishTabs() {
        _tabsState.value = tabs.map { BrowserTabInfo(it.id, it.title, it.url) }
    }

    /** 新建一个标签（主线程），可选加载初始 URL；不自动切换。 */
    private fun createTab(url: String?): BrowserTab {
        val id = UUID.randomUUID().toString()
        val wv = createWebView(id)
        val tab = BrowserTab(id = id, webView = wv)
        tabs.add(tab)
        if (url != null) {
            val normalized = normalizeUrl(url)
            tab.url = normalized
            wv.loadUrl(normalized)
        }
        publishTabs()
        FileLogger.i(TAG, "新建标签 $id${if (url != null) " -> $url" else ""}")
        return tab
    }

    /** 配置一个全新的 WebView（主线程），回调按标签 id 分派。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tabId: String): WebView {
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
                onTabLoading(tabId, true)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                onTabFinished(tabId, view, url)
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
                onTabProgress(tabId, newProgress)
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                onTabTitle(tabId, title)
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                handleDialog("alert", message ?: "", result)
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                handleDialog("confirm", message ?: "", result)
                return true
            }
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                val file = pendingUploadFile
                if (file != null && filePathCallback != null && file.exists() && file.isFile) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    filePathCallback.onReceiveValue(arrayOf(uri))
                    pendingUploadFile = null
                    pendingUploadDone?.complete(true)
                    pendingUploadDone = null
                    FileLogger.i(TAG, "已自动回填上传文件: ${file.absolutePath}")
                    return true
                }
                // 无自动上传目标：走系统默认文件选择器（配合 takeover，用户手动选择）
                return false
            }
        }
        // 下载监听：把下载任务异步落到工作区 downloads 目录（携带 Cookie 保持登录态）
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            scope.launch { downloadToWorkspace(url, userAgent, contentDisposition, mimetype) }
        }
        // 动态数据捕获插桩：在 document-start 注入（比任何页面脚本早），抓取 fetch/XHR/WS/SSE 请求。
        try {
            WebViewCompat.addDocumentStartJavaScript(wv, JS_NET_HOOK, setOf("*"))
        } catch (e: Exception) {
            FileLogger.w(TAG, "addDocumentStartJavaScript 注入失败（旧 WebView 降级：不采集网络数据）", e)
        }
        return wv
    }

    private fun onTabLoading(tabId: String, isLoading: Boolean) {
        if (activeTabId != tabId) return
        _uiState.value = _uiState.value.copy(isLoading = isLoading, progress = if (isLoading) 10 else _uiState.value.progress)
    }

    private fun onTabFinished(tabId: String, view: WebView?, url: String?) {
        val tab = findTab(tabId)
        if (tab != null) {
            tab.url = url ?: tab.url
            view?.title?.let { tab.title = it }
        }
        publishTabs()
        if (activeTabId == tabId) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                progress = 100,
                currentUrl = url ?: _uiState.value.currentUrl,
                canGoBack = view?.canGoBack() ?: false,
                canGoForward = view?.canGoForward() ?: false,
                title = tab?.title ?: _uiState.value.title
            )
        }
    }

    private fun onTabProgress(tabId: String, progress: Int) {
        if (activeTabId != tabId) return
        _uiState.value = _uiState.value.copy(progress = progress)
    }

    private fun onTabTitle(tabId: String, title: String?) {
        val tab = findTab(tabId)
        if (tab != null && title != null) tab.title = title
        publishTabs()
        if (activeTabId == tabId && title != null) {
            _uiState.value = _uiState.value.copy(title = title)
        }
    }

    private fun detachAndDestroy(wv: WebView) {
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    private suspend fun snapshotInternal(): BrowserPageSnapshot {
        val elJson = evalJs(JS_SNAPSHOT)
        val parsed = runCatching { json.parseToJsonElement(elJson).jsonObject }.getOrNull()
        val elements = runCatching {
            parsed?.get("elements")?.let { el ->
                Json { ignoreUnknownKeys = true }.decodeFromString<List<BrowserElement>>(el.toString())
            }
        }.getOrNull() ?: emptyList()
        val headings = runCatching {
            parsed?.get("headings")?.let { h ->
                Json { ignoreUnknownKeys = true }.decodeFromString<List<BrowserHeading>>(h.toString())
            }
        }.getOrNull() ?: emptyList()
        val title = parsed?.get("title")?.let { if (it is JsonPrimitive) it.content else "" } ?: ""
        val url = parsed?.get("url")?.let { if (it is JsonPrimitive) it.content else "" } ?: ""
        val pageText = evalJs(JS_PAGE_TEXT)
        val (hasLogin, hint) = detectLoginForm(
            BrowserPageSnapshot(title = title, url = url, headings = headings, elements = elements, pageText = pageText.take(12000))
        )
        val snap = BrowserPageSnapshot(
            title = title,
            url = url,
            headings = headings,
            elements = elements,
            pageText = pageText.take(12000),
            hasLoginForm = hasLogin,
            loginHint = hint,
            pendingRequests = readPendingCount()
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

    /** 在主线程获取当前激活标签的 WebView（懒创建首个标签）。 */
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        activeWebView() ?: run {
            val tab = createTab(null)
            activeTabId = tab.id
            applyActiveTab(tab)
            tab.webView
        }
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
        val debounceMs = 400L
        val deadline = System.currentTimeMillis() + timeoutMs
        var stableCount = 0
        var lastDom: String? = null
        var lastRoute: Long = -1L
        var firstPass = true
        while (System.currentTimeMillis() < deadline) {
            val loading = withContext(Dispatchers.Main) { _uiState.value.isLoading }
            val pending = readPendingCount()
            val dom = readDomMeta()
            val route = readRouteSeq()
            if (loading || pending > 0) {
                // 主文档还在加载、或有在途业务请求（fetch/XHR/WS/SSE），重置稳定计数
                stableCount = 0
                lastDom = null
            } else if (!firstPass && dom == lastDom && route == lastRoute) {
                // 业务请求已停 + DOM 稳定 + 路由稳定，累计到 debounce 即视为就绪
                stableCount++
                if (stableCount * 100L >= debounceMs) break
            } else {
                stableCount = 0
            }
            lastDom = dom
            lastRoute = route
            firstPass = false
            delay(100)
        }
        withContext(Dispatchers.Main) { _uiState.value = _uiState.value.copy(isLoading = false, progress = 100) }
    }

    /** 读取页面内在途业务请求数（fetch/XHR/WS/SSE）；注入失效时为 0，不影响判定。 */
    private suspend fun readPendingCount(): Int {
        val raw = evalJs("(window.__rcb_net_pending || 0)")
        return raw.trim().toIntOrNull() ?: 0
    }

    /** 读取页面 DOM 稳定性指纹（正文长度 : 元素总数）。 */
    private suspend fun readDomMeta(): String = evalJs(
        "(function(){ var n = document.body ? document.body.innerText.length : -1; var c = document.querySelectorAll('*').length; return n + ':' + c; })()"
    )

    /** 读取 SPA 路由变更计数（pushState/replaceState/popstate 触发）。 */
    private suspend fun readRouteSeq(): Long {
        val raw = evalJs("(window.__rcb_route_seq || 0)")
        return raw.trim().toLongOrNull() ?: 0L
    }

    // ─────────────────── 动态数据捕获：网络请求日志查询 ───────────────────

    /** 页面内在途业务请求数（fetch/XHR/WS/SSE），供 wait_for_request 超时、network 汇总报告。 */
    suspend fun networkPendingCount(): Int = mutex.withLock { readPendingCount() }

    /** 页面内网络缓冲总记录数（含已完成与在途）。 */
    suspend fun networkTotalCount(): Int = mutex.withLock {
        evalJs("(window.__rcb_net || []).length").trim().toIntOrNull() ?: 0
    }

    /** 列出页面内已记录的异步数据请求（fetch/XHR/WS/SSE），按时间倒序返回最近 limit 条。 */
    suspend fun listNetwork(limit: Int = 20): List<BrowserNetworkRecord> = mutex.withLock {
        val n = limit.coerceIn(1, 100)
        decodeNetworkList(evalJs("JSON.stringify((window.__rcb_net || []).slice(-$n).reverse())"))
    }

    /** 按记录 id 或 URL 子串查询单条网络请求记录（两者都空则返回最新一条）。 */
    suspend fun getNetwork(url: String? = null, id: Int? = null): BrowserNetworkRecord? = mutex.withLock {
        val all = decodeNetworkList(evalJs("JSON.stringify(window.__rcb_net || [])"))
        when {
            id != null -> all.firstOrNull { it.id == id }
            !url.isNullOrBlank() -> all.lastOrNull { it.url.contains(url) }
            else -> all.lastOrNull()
        }
    }

    /** 轮询等待匹配 URL 子串的请求完成并返回该记录（超时返回 null）。 */
    suspend fun waitForRequest(url: String, timeoutMs: Long = 15_000): BrowserNetworkRecord? = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("等待数据请求：$url", true)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val hit = decodeNetworkList(evalJs("JSON.stringify(window.__rcb_net || [])"))
                    .lastOrNull { it.url.contains(url) }
                if (hit != null) return@withLock hit
                delay(300)
            }
            null
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 解析网络缓冲 JSON（兼容 WebView evaluateJavascript 对字符串结果再包裹一层的形态）。 */
    private fun decodeNetworkList(raw: String): List<BrowserNetworkRecord> {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return emptyList()
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val arr = when (element) {
            is JsonArray -> element
            is JsonPrimitive -> if (element.isString) {
                runCatching { json.parseToJsonElement(element.content) }.getOrNull() as? JsonArray ?: return emptyList()
            } else return emptyList()
            else -> return emptyList()
        }
        return runCatching { json.decodeFromString<List<BrowserNetworkRecord>>(arr.toString()) }.getOrNull() ?: emptyList()
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }

    /** 当前快照（模型读属性等用途）。 */
    fun currentSnapshot(): BrowserPageSnapshot = lastSnapshot
}
