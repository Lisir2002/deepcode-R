package com.core.deepcode.feature.browser.domain

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
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.proxy.domain.ClashProxyManager
import com.core.deepcode.feature.workspace.domain.WorkspacePathMapper
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executor
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
    private val pathMapper: WorkspacePathMapper,
    private val proxyManager: ClashProxyManager,
    private val okHttp: OkHttpClient,
    private val historyStore: BrowserHistoryStore,
    private val bookmarkStore: BrowserBookmarkStore
) {
    private companion object {
        const val TAG = "BrowserController"

        /** 页面纯文本截断上限（与 JS_PAGE_TEXT 内 12000 一致）。 */
        const val MAX_PAGE_TEXT = 12000

        /**
         * 快照元素数量上限（R2.1 快照分级的内存保护）：超大页面只取前 N 个可交互控件。
         * JS_SNAPSHOT 未设上限时，一个 500+ 控件页面会生成超大 JSON（每个元素 25+ 字段），
         * 是快照内存/耗时峰值、进而触发低内存闪退的直接来源之一（headings 已有 100 上限先例）。
         * 模型可通过滚动加载后续元素；summary 级快照本就只给控件摘要，上限不影响可用性。
         */
        const val MAX_SNAPSHOT_ELEMENTS = 300

        /** 导航协议白名单：拦截 file://、content://、intent://、javascript: 等危险 scheme。 */
        val ALLOWED_SCHEMES = setOf("http", "https")

        /** 桌面版网页 UA（R1.3 桌面版切换）：切换后按桌面 Chrome UA 渲染（有些站点对移动 UA 返回移动版页面）。 */
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 页面快照 JS：给可交互控件打持久 data-rcb-id（跨快照调用单调递增，SPA 重渲染节点
         * 被替换后旧 id 失效 → 靠 locator/semantic 重定位），解析控件类型/标签/取值/状态，
         * 并生成三级定位信息（CSS 绝对路径 locator + 语义描述符 semantic）与可操作性标注
         * （in_viewport / visible / needs_scroll / overlapped），返回元素树。
         */
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
              // CSS 绝对路径：html > body > div#main > form > div:nth-child(2) > input
              function cssPath(el) {
                if (!el || el.nodeType !== 1) return '';
                var parts = [], node = el;
                while (node && node.nodeType === 1 && node !== document.documentElement) {
                  var part = node.tagName.toLowerCase();
                  if (node.id) { part += '#' + node.id; parts.unshift(part); break; }
                  if (node.classList && node.classList.length) part += '.' + node.classList[0];
                  var parent = node.parentElement;
                  if (parent && parent.children.length > 1) {
                    var idx = 1, sib = parent.firstElementChild;
                    while (sib && sib !== node) { if (sib.tagName === node.tagName) idx++; sib = sib.nextElementSibling; }
                    part += ':nth-child(' + idx + ')';
                  }
                  parts.unshift(part);
                  node = parent;
                }
                parts.unshift('html');
                return parts.join(' > ');
              }
              function isVisible(el) {
                var rect = el.getBoundingClientRect();
                if (rect.width < 1 || rect.height < 1) return false;
                var cs = window.getComputedStyle(el);
                if (cs.display === 'none' || cs.visibility === 'hidden') return false;
                var o = parseFloat(cs.opacity);
                if (!isNaN(o) && o === 0) return false;
                return true;
              }
              function isInViewport(el) {
                var r = el.getBoundingClientRect();
                var vh = window.innerHeight || document.documentElement.clientHeight;
                var vw = window.innerWidth || document.documentElement.clientWidth;
                return r.top >= 0 && r.left >= 0 && r.bottom <= vh && r.right <= vw;
              }
              function isOverlapped(el) {
                var r = el.getBoundingClientRect();
                var x = r.left + r.width / 2, y = r.top + r.height / 2;
                var top;
                try { top = document.elementFromPoint(x, y); } catch (e) { return false; }
                if (!top) return false;
                return !(top === el || el.contains(top));
              }
              if (!window.__rcb_seq) window.__rcb_seq = 0;
              var els = [];
              var seen = new WeakSet();
              var sel = 'a,button,input,select,textarea,[role="button"],[role="link"],[role="menuitem"],[contenteditable="true"],summary';
              var semIndex = {};
              function semKey(el, kind) {
                var role = (el.getAttribute('role') || (kind === 'link' ? 'link' : (kind.indexOf('input') === 0 ? 'input' : el.tagName.toLowerCase()))).toLowerCase();
                var type = (el.getAttribute('type') || '').toLowerCase();
                return role + '|' + type;
              }
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
                  if (!id) { id = String(++window.__rcb_seq); el.setAttribute('data-rcb-id', id); }
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
                  var loc = cssPath(el);
                  var skey = semKey(el, kind);
                  semIndex[skey] = (semIndex[skey] || 0) + 1;
                  var semRole = role || (kind === 'link' ? 'link' : (kind.indexOf('input') === 0 ? 'input' : tag));
                  var semantic = 'role=' + semRole + ' name="' + (label || visibleText).slice(0, 80) + '" index=' + semIndex[skey];
                  var visible = isVisible(el);
                  var inViewport = visible && isInViewport(el);
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
                    sensitive: sensitive,
                    locator: loc,
                    semantic: semantic,
                    inViewport: inViewport,
                    visible: visible,
                    needsScroll: visible && !inViewport,
                    overlapped: inViewport && isOverlapped(el)
                  });
                  // 元素上限保护：超大页面（长列表/表格/导航）只取前 MAX_SNAPSHOT_ELEMENTS 个，
                  // 防止一次快照生成超大 JSON（内存/耗时峰值，低内存时易触发 LMKD 静默杀进程）。
                  if (els.length >= MAX_SNAPSHOT_ELEMENTS) break;
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

        /** 输入 JS（React 兼容 value setter；先滚动到视口中央消除"点了没反应"）。 */
        const val JS_TYPE = """
            (function() {
              var id = arguments[0], text = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.scrollIntoView({block:'center', behavior:'smooth'});
              el.focus();
              var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
              setter.call(el, text);
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});
            })();
        """

        /** 下拉选择 JS（先滚动到视口中央）。 */
        const val JS_SELECT = """
            (function() {
              var id = arguments[0], value = arguments[1];
              var el = document.querySelector('[data-rcb-id="' + id + '"]');
              if (!el) return JSON.stringify({ok:false, reason:'NOT_FOUND'});
              el.scrollIntoView({block:'center', behavior:'smooth'});
              el.value = value;
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});
            })();
        """

        /**
         * 三级定位 JS（R2.2）：把模型传入的 element_id（data-rcb-id / CSS 绝对路径 / 语义描述符
         * 三者任一）解析成元素并返回其 data-rcb-id。
         * 解析顺序：`[data-rcb-id]` 直查 → CSS 路径 querySelector → 语义（role=… name=… index=…）匹配。
         * 命中且元素尚无 id 时补打新 data-rcb-id（单调递增，保证后续操作可用同一 id）。
         */
        const val JS_LOCATE = """
            (function() {
              var loc = arguments[0];
              if (!loc) return JSON.stringify({ok:false, reason:'EMPTY'});
              function nextId() { if (!window.__rcb_seq) window.__rcb_seq = 0; return String(++window.__rcb_seq); }
              // 1) data-rcb-id 直查
              try {
                var byId = document.querySelector('[data-rcb-id="' + loc + '"]');
                if (byId) return JSON.stringify({ok:true, id: byId.getAttribute('data-rcb-id'), method:'id'});
              } catch (e) {}
              // 2) CSS 绝对路径 / 选择器
              try {
                var byCss = document.querySelector(loc);
                if (byCss && byCss.getAttribute('data-rcb-skip') === null) {
                  var cid = byCss.getAttribute('data-rcb-id');
                  if (!cid) { cid = nextId(); byCss.setAttribute('data-rcb-id', cid); }
                  return JSON.stringify({ok:true, id: cid, method:'css'});
                }
              } catch (e) {}
              // 3) 语义描述符 role=… name=… index=…
              var m = loc.match(/^role=([\w-]+)\s+name="?([^"]*?)"?\s+index=(\d+)$/i);
              if (m) {
                var role = m[1].toLowerCase(), name = m[2].trim(), index = parseInt(m[3], 10);
                var nodes = document.querySelectorAll('a,button,input,select,textarea,[role]');
                var n = 0;
                for (var i = 0; i < nodes.length; i++) {
                  var e = nodes[i];
                  if (e.closest && e.closest('[data-rcb-skip]')) continue;
                  var tag = e.tagName.toLowerCase();
                  if (tag === 'INPUT' && e.type === 'hidden') continue;
                  var r = (e.getAttribute('role') || (tag === 'a' ? 'link' : (tag === 'input' ? 'input' : tag))).toLowerCase();
                  if (r !== role) continue;
                  var nm = (e.getAttribute('aria-label') || (tag === 'a' || tag === 'button' ? (e.innerText || '') : (e.getAttribute('name') || ''))).trim();
                  if (nm !== name) continue;
                  n++;
                  if (n === index) {
                    var sid = e.getAttribute('data-rcb-id');
                    if (!sid) { sid = nextId(); e.setAttribute('data-rcb-id', sid); }
                    return JSON.stringify({ok:true, id: sid, method:'semantic'});
                  }
                }
                return JSON.stringify({ok:false, reason:'SEMANTIC_NOT_FOUND'});
              }
              return JSON.stringify({ok:false, reason:'NOT_FOUND'});
            })();
        """

        /**
         * DOM 变化订阅 JS（R2.4）：document-start 注入，用 MutationObserver 监听元素新增/移除/
         * 属性变化，写入页面环形缓冲 window.__rcb_mut（上限 50 条摘要），并维护单调版本号
         * window.__rcb_mut_version。wait_for_change 挂起等待版本号变化即返回变化摘要。
         * 幂等：window.__rcb_mut 已存在则跳过。
         */
        const val JS_CHANGE_OBSERVER = """
            (function() {
              if (window.__rcb_mut) return;
              window.__rcb_mut = [];
              window.__rcb_mut_version = 0;
              var MAX_MUT = 50;
              function summarize(records) {
                for (var i = 0; i < records.length && window.__rcb_mut.length < MAX_MUT; i++) {
                  var r = records[i];
                  if (r.type === 'childList') {
                    var t = r.target;
                    if (t && t.nodeType === 1 && t.closest && t.closest('[data-rcb-skip]')) continue;
                    if (r.addedNodes.length) window.__rcb_mut.push({type:'added', tag: r.addedNodes[0].nodeName || '', target: t && t.tagName ? t.tagName.toLowerCase() : ''});
                    if (r.removedNodes.length) window.__rcb_mut.push({type:'removed', tag: r.removedNodes[0].nodeName || '', target: t && t.tagName ? t.tagName.toLowerCase() : ''});
                  } else if (r.type === 'attributes') {
                    if (r.target && r.target.nodeType === 1 && r.target.closest && r.target.closest('[data-rcb-skip]')) continue;
                    window.__rcb_mut.push({type:'attr', name: r.attributeName || '', target: r.target && r.target.tagName ? r.target.tagName.toLowerCase() : ''});
                  }
                }
                window.__rcb_mut_version++;
              }
              var obs = new MutationObserver(function(records) { summarize(records); });
              if (document.documentElement) {
                obs.observe(document.documentElement, {childList: true, subtree: true, attributes: true});
              }
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

    /** 原始移动 UA（R1.3 桌面版切换）：首个 WebView 创建时记录，桌面版关闭后恢复。 */
    @Volatile
    private var originalUserAgent: String? = null

    // 代理开关变化时，让已存在的 WebView 也跟上 mihomo 代理（新标签/导航天然生效）。
    init {
        scope.launch {
            proxyManager.state.collect { applyWebViewProxy(null) }
        }
    }

    // ─────────────────────── WebView 代理接管 ───────────────────────

    /**
     * 把 WebView 的网络流量接入 mihomo 代理。WebView 不认 Java ProxySelector，只能靠
     * [ProxyController.setProxyOverride] 做进程级代理覆盖。
     * 代理开启时指向 mihomo mixed-port 并放行 loopback/内网（容器开发服务直连），
     * 关闭时清除覆盖回退系统默认网络。内部统一 post 到主线程执行。
     */
    private fun applyWebViewProxy(webView: WebView?) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            FileLogger.w(TAG, "当前 WebView 不支持 PROXY_OVERRIDE，外网访问将走系统默认网络")
            return
        }
        // ProxyController 内部与 WebView 提供方通信，统一调度到主线程避免线程问题。
        val syncExecutor = Executor { it.run() }
        mainHandler.post {
            runCatching {
                if (proxyManager.isEnabled()) {
                    val config = ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:${ClashProxyManager.MIXED_PORT}")
                        .addBypassRule("localhost")
                        .addBypassRule("127.0.0.1")
                        .addBypassRule("10.*")
                        .addBypassRule("192.168.*")
                        .addBypassRule("172.16.*")
                        .build()
                    ProxyController.getInstance().setProxyOverride(config, syncExecutor, Runnable {})
                } else {
                    ProxyController.getInstance().clearProxyOverride(syncExecutor, Runnable {})
                }
            }.onFailure {
                FileLogger.w(TAG, "设置 WebView 代理失败: ${it.message}")
            }
        }
    }

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

    /**
     * 增量快照基线（R2.1）：最近一次写操作后的快照，供 computeDelta 计算前后差异。
     * 导航后重置（新页面与旧页面不可比）。
     */
    @Volatile
    private var deltaBaseline: BrowserPageSnapshot? = null

    /**
     * 最近一次写操作后的增量结果（R2.1/R2.3 写操作自动验证）：
     * 写操作内部计算「基线 → 操作后」差异后填充，工具侧读取并随 envelope 返回给模型。
     */
    @Volatile
    private var lastDelta: BrowserSnapshotDelta? = null

    /** 最近动作日志（R2.3 动作历史）：操作 + 结果摘要，最多保留 30 条。 */
    private val actionLog = ArrayDeque<String>()

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
        // 共享 WebView 复用前必须先摘除旧父容器，否则 Compose AndroidView 二次 addView 会抛
        // IllegalStateException("The specified child already has a parent ...")。
        detachFromParent(active.webView)
        return active.webView
    }

    /** 浏览器页卸载时调用（不销毁 WebView，保留会话/Cookie）。 */
    fun unbind() {
        // 摘除激活 WebView，避免离开页面后仍挂在旧 AndroidViewHolder 上，下次返回时重复挂载崩溃。
        activeWebView()?.let(::detachFromParent)
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

    // ─────────────────────── 用户侧 API（R1 更像浏览器） ───────────────────────

    /** 访问历史（R1.1 历史记录）：时间倒序。 */
    fun history(): List<BrowserHistoryEntry> = historyStore.entries()

    /** 清空访问历史（R1.1）。 */
    fun clearHistory() = historyStore.clear()

    /** 收藏夹（R1.1 收藏夹）：时间倒序。 */
    fun bookmarks(): List<BrowserBookmark> = bookmarkStore.bookmarks()

    /** 当前 URL 是否已收藏（R1.1）。 */
    fun isBookmarked(url: String): Boolean = bookmarkStore.contains(url)

    /** 收藏当前页面（R1.1）；返回是否新增成功（同 URL 已收藏返回 false）。 */
    fun addBookmark(): Boolean = bookmarkStore.add(_uiState.value.title, _uiState.value.currentUrl)

    /** 取消收藏指定 URL（R1.1）。 */
    fun removeBookmark(url: String) = bookmarkStore.remove(url)

    /** 清除下载列表（R1.2 下载管理 UI）。 */
    fun clearDownloads() {
        mainHandler.post { _downloads.value = emptyList() }
    }

    /** 下载任务的宿主文件（供 UI「打开」下载文件用）；未完成或路径无效返回 null。 */
    fun downloadHostFile(info: BrowserDownloadInfo): File? {
        if (info.status != "done" || info.path.isBlank()) return null
        return runCatching { pathMapper.toHostFile(info.path) }.getOrNull()?.takeIf { it.exists() }
    }

    /** 重试下载（R1.2 下载管理 UI）：按原 URL 重新发起下载任务。 */
    suspend fun retryDownload(info: BrowserDownloadInfo) {
        scope.launch {
            downloadToWorkspace(info.url, null, null, null)
        }.join()
    }

    /**
     * 无痕模式（R1.3 无痕模式）：会话级开关。
     * 开启/关闭时都清空 Cookie 与缓存（无痕会话不落痕；关闭后不残留本次会话的 cookie），
     * 无痕期间不记录访问历史。
     */
    fun setIncognito(on: Boolean) {
        mainHandler.post {
            val cm = CookieManager.getInstance()
            runCatching { cm.removeAllCookies(null) }
            runCatching { activeWebView()?.clearCache(true) }
            _uiState.value = _uiState.value.copy(incognito = on)
        }
    }

    /**
     * 桌面版网页切换（R1.3 桌面版切换）：切到桌面 UA 后重载当前页。
     * 存储桌面 UA（[DESKTOP_UA]），非桌面态使用 WebView 默认移动 UA。
     */
    fun toggleDesktopMode() {
        val next = !_uiState.value.desktopMode
        mainHandler.post {
            activeWebView()?.let { wv ->
                if (next) {
                    wv.settings.userAgentString = DESKTOP_UA
                } else {
                    // 恢复移动 UA：默认 UA（去 wv 标记）或保存的原始 UA
                    wv.settings.userAgentString = originalUserAgent ?: wv.settings.userAgentString.replace(DESKTOP_UA, "")
                }
                _uiState.value = _uiState.value.copy(desktopMode = next)
                wv.reload()
            }
        }
    }

    /** 页面缩放（R1.3 缩放控制）：textZoom 百分比（100 默认，范围 50–200）。 */
    fun setTextZoom(percent: Int) {
        val clamped = percent.coerceIn(50, 200)
        mainHandler.post {
            activeWebView()?.settings?.textZoom = clamped
            _uiState.value = _uiState.value.copy(textZoom = clamped)
        }
    }

    /** 页内查找（R1.1 页内查找）：findAllAsync 高亮全部匹配。 */
    fun findOnPage(text: String) {
        if (text.isBlank()) {
            clearFindOnPage()
            return
        }
        mainHandler.post { activeWebView()?.findAllAsync(text) }
    }

    /** 页内查找（R1.1）：在匹配结果中上/下切换高亮。 */
    fun findNextOnPage(forward: Boolean) {
        mainHandler.post { activeWebView()?.findNext(forward) }
    }

    /** 页内查找（R1.1）：清除高亮。 */
    fun clearFindOnPage() {
        mainHandler.post { activeWebView()?.clearMatches() }
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
            // 导航到新页面：重置增量基线与上次增量（新旧页面不可比），下次写操作后重新建立。
            deltaBaseline = null
            lastDelta = null
            snapshotInternal(SnapshotLevel.SUMMARY)
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 获取当前页面快照（元素树 + 文本）。 */
    suspend fun snapshot(level: SnapshotLevel = SnapshotLevel.FULL): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在提取页面结构", true)
        try {
            snapshotInternal(level)
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 单独取页面纯文本（R2.1 按需取文）：模型需要正文时再取，不再随快照默认返回。 */
    suspend fun pageText(): String = mutex.withLock {
        evalJs(JS_PAGE_TEXT).take(MAX_PAGE_TEXT)
    }

    /**
     * 三级定位解析（R2.2）：把模型传入的 element_id（data-rcb-id / CSS 绝对路径 / 语义描述符
     * 三者任一）解析成元素的 data-rcb-id。
     * @return null 表示全部解析失败；否则返回（id, 命中方式）。
     */
    suspend fun resolveElementId(locator: String): ResolvedElement? = mutex.withLock {
        val raw = evalJs("($JS_LOCATE)(${quote(locator)})")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@withLock null
        val ok = runCatching { (obj["ok"] as? JsonPrimitive)?.content?.toBoolean() }.getOrNull() ?: false
        if (!ok) return@withLock null
        val id = (obj["id"] as? JsonPrimitive)?.content ?: return@withLock null
        val method = (obj["method"] as? JsonPrimitive)?.content ?: "id"
        ResolvedElement(id, method)
    }

    /**
     * 增量快照（R2.1）：相对 [deltaBaseline] 计算元素级差异。
     * 基线为空（首次/导航后）返回 null，调用方应回退到全量快照。
     */
    fun computeDelta(after: BrowserPageSnapshot): BrowserSnapshotDelta? {
        val before = deltaBaseline ?: return null
        val beforeMap = before.elements.associateBy { it.id }
        val afterMap = after.elements.associateBy { it.id }
        val added = after.elements.filter { it.id !in beforeMap }
        val removed = before.elements.map { it.id }.filter { it !in afterMap }
        val changed = after.elements.filter { e ->
            val b = beforeMap[e.id] ?: return@filter false
            elementSignature(b) != elementSignature(e)
        }
        val textNote = when {
            after.pageText.isBlank() || before.pageText.isBlank() -> ""
            after.pageText.length > before.pageText.length * 2 -> "页面正文明显变长（${before.pageText.length} → ${after.pageText.length} 字）"
            after.pageText.length * 2 < before.pageText.length -> "页面正文明显变短（${before.pageText.length} → ${after.pageText.length} 字）"
            else -> ""
        }
        return BrowserSnapshotDelta(added = added, removed = removed, changed = changed, textNote = textNote)
    }

    /** 元素可感知签名（R2.1 diff 依据）：文本/取值/状态/可操作性/选项集合任一变化即视为变化。 */
    private fun elementSignature(e: BrowserElement): String =
        listOf(
            e.kind, e.label, e.text, e.value, e.href, e.placeholder,
            e.checked, e.disabled, e.required, e.readonly, e.sensitive,
            e.inViewport, e.visible, e.needsScroll, e.overlapped,
            e.options.joinToString("|") { "${it.value}=${it.text}" }
        ).joinToString("∷")

    /** 记录一次动作到 action_log（R2.3 动作历史）。 */
    fun recordAction(action: String, summary: String) {
        synchronized(actionLog) {
            actionLog.addLast("[$action] $summary")
            while (actionLog.size > 30) actionLog.removeFirst()
        }
    }

    /** 动作历史（R2.3）：最近 30 条操作 + 结果摘要，供 history 动作查询。 */
    fun actionHistory(): List<String> = synchronized(actionLog) { actionLog.toList() }

    /** 最近一次写操作后的增量（R2.1/R2.3 写操作自动验证），供工具侧随 envelope 返回。 */
    fun lastDelta(): BrowserSnapshotDelta? = lastDelta

    /**
     * 系统内存压力回调（由 AIEditorApp.onTrimMemory 在 RUNNING_CRITICAL/lowMemory 时转发）：
     * 释放本进程持有的页面快照大对象缓存（全量元素树 / 增量基线 / 增量结果），
     * 降低被 LMKD 静默杀进程的概率——LMKD 杀进程绕过 Java CrashHandler，
     * 正是「模型输出时闪退却无日志」的高概率根因之一（见 AIEditorApp.onTrimMemory）。
     * 释放后模型下一次 snapshot 会重新生成快照，属可接受的降级。
     */
    fun onMemoryPressure() {
        lastSnapshot = BrowserPageSnapshot()
        deltaBaseline = null
        lastDelta = null
    }

    /**
     * 写操作后统一处理（R2.1/R2.3 写操作自动验证）：计算「基线 → 操作后」增量，
     * 把基线推进到操作后快照（下次写操作以此为对照），并记录动作日志。
     */
    private fun afterWrite(action: String, after: BrowserPageSnapshot): BrowserPageSnapshot {
        val delta = computeDelta(after)
        lastDelta = delta
        deltaBaseline = after
        val summary = buildString {
            append(action)
            if (delta != null) {
                append(": 新增 ${delta.added.size}，变化 ${delta.changed.size}，消失 ${delta.removed.size}")
                if (delta.textNote.isNotBlank()) append("，${delta.textNote}")
            }
        }
        recordAction(action, summary)
        return after
    }

    /** 点击元素，返回点击后的快照。 */
    suspend fun click(elementId: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在点击元素 $elementId", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("click", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            evalJs("($JS_CLICK)(${quote(resolved.id)})")
            waitForPageSettled(10_000)
            afterWrite("click", snapshotInternal(SnapshotLevel.SUMMARY))
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 在元素中输入文本。 */
    suspend fun type(elementId: String, text: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在向 $elementId 输入内容", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("type", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            evalJs("($JS_TYPE)(${quote(resolved.id)}, ${quote(text)})")
            afterWrite("type", snapshotInternal(SnapshotLevel.SUMMARY))
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 下拉选择。 */
    suspend fun selectOption(elementId: String, value: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在选择 $elementId", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("select_option", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            evalJs("($JS_SELECT)(${quote(resolved.id)}, ${quote(value)})")
            afterWrite("select_option", snapshotInternal(SnapshotLevel.SUMMARY))
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 提交表单（elementId 可为空 → 提交页面首个表单）。 */
    suspend fun submit(elementId: String?): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在提交表单", true)
        try {
            val id = elementId?.let { resolveElementId(it)?.id }
            evalJs("($JS_SUBMIT)(${if (id == null) "null" else quote(id)})")
            waitForPageSettled(15_000)
            afterWrite("submit", snapshotInternal(SnapshotLevel.SUMMARY))
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
            afterWrite("scroll", snapshotInternal(SnapshotLevel.SUMMARY))
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
                val snap = snapshotInternal(SnapshotLevel.STANDARD)
                if (selector.isNullOrBlank()) {
                    if (snap.url.isNotBlank()) return@withLock snap
                } else {
                    val found = evalJs("(function(){ return !!document.querySelector(${quote(selector)}); })()")
                    if (found.trim() == "true") return@withLock snap
                }
                delay(500)
            }
            snapshotInternal(SnapshotLevel.STANDARD)
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /**
     * 事件驱动等待（R2.4）：挂起直到页面 DOM 发生变化（MutationObserver 版本号前进）或超时。
     * @param timeoutMs    最长等待毫秒
     * @param baselineVersion 变化基线版本号；不传则取当前版本号作为起点
     * @return 等待结果：是否发生变化 + 变化摘要文本（从页面环形缓冲读取，取最近变化）
     */
    suspend fun waitForChange(timeoutMs: Long, baselineVersion: Long? = null): WaitChangeResult = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("等待页面变化", true)
        try {
            val startVersion = baselineVersion ?: readMutVersion()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val version = readMutVersion()
                if (version > startVersion) {
                    val summary = decodeJsString(evalJs("JSON.stringify((window.__rcb_mut || []).slice(-10))"))
                    return@withLock WaitChangeResult(changed = true, version = version, summary = summary)
                }
                // 网络事件也唤醒（wait_for_change 同时监听网络动静）
                if (networkPendingCount() > 0) {
                    val recs = listNetwork(1)
                    if (recs.isNotEmpty() && recs.first().startTs > System.currentTimeMillis() - 2000) {
                        return@withLock WaitChangeResult(changed = true, version = version, summary = "检测到新的网络请求：${recs.first().url.take(120)}")
                    }
                }
                delay(300)
            }
            WaitChangeResult(changed = false, version = readMutVersion(), summary = "")
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    // ─────────────────── 补充动作（hover/drag/press_key/upload/导航） ───────────────────

    /** 读取页面 DOM 变化版本号（R2.4，MutationObserver 维护）；注入失效时为 0。 */
    private suspend fun readMutVersion(): Long {
        val raw = evalJs("(window.__rcb_mut_version || 0)")
        return raw.trim().toLongOrNull() ?: 0L
    }

    /** 悬停元素，返回悬停后快照。 */
    suspend fun hover(elementId: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在悬停元素 $elementId", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("hover", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            evalJs("($JS_HOVER)(${quote(resolved.id)})")
            delay(200)
            afterWrite("hover", snapshotInternal(SnapshotLevel.SUMMARY))
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 按键（向元素派发 keydown/keyup）。 */
    suspend fun pressKey(elementId: String, key: String): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在按键 $elementId", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("press_key", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            evalJs("($JS_PRESS_KEY)(${quote(resolved.id)}, ${quote(key)})")
            afterWrite("press_key", snapshotInternal(SnapshotLevel.SUMMARY))
        } finally {
            _agentStatus.value = AgentBrowserStatus()
        }
    }

    /** 拖拽：从源元素拖到目标元素（targetElementId 可空，表示原地拖拽）。 */
    suspend fun drag(elementId: String, targetElementId: String?): BrowserPageSnapshot = mutex.withLock {
        _agentStatus.value = AgentBrowserStatus("正在拖拽 $elementId", true)
        try {
            val resolved = resolveElementId(elementId)
            if (resolved == null) {
                recordAction("drag", "失败：元素未找到（$elementId）")
                return@withLock BrowserPageSnapshot(url = lastSnapshot.url, pageText = "元素 $elementId 未找到：data-rcb-id / CSS 路径 / 语义均未命中")
            }
            val targetResolved = targetElementId?.let { resolveElementId(it)?.id }
            evalJs("($JS_DRAG)(${quote(resolved.id)}, ${if (targetResolved == null) "null" else quote(targetResolved)})")
            afterWrite("drag", snapshotInternal(SnapshotLevel.SUMMARY))
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

            // 共享 OkHttp 下载（代理启用时经 mihomo 出口）；旧实现 HttpURLConnection 直连会绕过代理。
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent ?: "DeepCore-Code-Browser")
                .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    throw IllegalStateException("下载失败（HTTP ${resp.code}）")
                }
                resp.body!!.byteStream().use { input -> outFile.outputStream().use { input.copyTo(it) } }
            }

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
            // 首次创建时记录原始移动 UA（去 wv 标记），供桌面版切换关闭后恢复
            if (originalUserAgent == null) originalUserAgent = userAgentString.replaceFirst("; wv", "")
            userAgentString = if (_uiState.value.desktopMode) DESKTOP_UA else (originalUserAgent ?: userAgentString.replaceFirst("; wv", ""))
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
        // DOM 变化订阅（R2.4 事件驱动感知）：document-start 注入 MutationObserver，
        // 供 wait_for_change 挂起等待页面变化，替代轮询 snapshot。
        try {
            WebViewCompat.addDocumentStartJavaScript(wv, JS_CHANGE_OBSERVER, setOf("*"))
        } catch (e: Exception) {
            FileLogger.w(TAG, "addDocumentStartJavaScript 注入失败（旧 WebView 降级：wait_for_change 不可用）", e)
        }
        // 新 WebView 创建即按当前代理开关接管网络出口（WebView 不认 Java ProxySelector）。
        applyWebViewProxy(wv)
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
        // R1.1 历史记录：页面加载完成且非无痕模式时写入访问历史（含 http/https 页面）
        if (!_uiState.value.incognito && !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
            historyStore.record(tab?.title ?: "", url)
        }
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

    /** 将 WebView 从当前父容器摘除（不销毁），供复用前的重新挂载与页面卸载时调用。 */
    private fun detachFromParent(wv: WebView) {
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
    }

    private fun detachAndDestroy(wv: WebView) {
        detachFromParent(wv)
        wv.destroy()
    }

    /**
     * 快照分级提取（R2.1）：总是收集元素树与标题大纲（供 delta 计算与元素定位），
     * 仅 [SnapshotLevel.FULL] 时才额外取 page_text（最贵的部分），
     * summary/standard 不取正文，由工具侧按分级裁剪元素 JSON 后返回。
     */
    private suspend fun snapshotInternal(level: SnapshotLevel = SnapshotLevel.FULL): BrowserPageSnapshot {
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
        // 分级：page_text 只在 FULL 级提取；登录表单信号基于元素（密码框）始终检测
        val pageText = if (level == SnapshotLevel.FULL) evalJs(JS_PAGE_TEXT) else ""
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

    /**
     * 解码 WebView evaluateJavascript 返回的 JS 字符串结果：JS 表达式结果为字符串时，
     * WebView 会再包一层引号并转义（如 `"[\"a\"]"`），此处还原为原始 JSON 文本。
     */
    private fun decodeJsString(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("\"") && t.endsWith("\"")) {
            return runCatching { json.parseToJsonElement(t).jsonPrimitive.content }.getOrNull() ?: t
        }
        return t
    }

    /** 当前快照（模型读属性等用途）。 */
    fun currentSnapshot(): BrowserPageSnapshot = lastSnapshot
}
