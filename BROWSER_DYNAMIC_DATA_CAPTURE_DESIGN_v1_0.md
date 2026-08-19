# 内置浏览器 · 动态数据捕获设计文档 v1.0

> 状态：✅ **已实现**（JS 插桩 + 就绪判定升级 + 动态数据查询接口全部落地，见 §7 实施状态审计）
> 对应代码库：[deepcode-R](/workspace/deepcode-R)
> 核心参考文件：
> - [BrowserController.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt)
> - [BrowserAgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/browser/BrowserAgentTool.kt)
> - [BrowserModels.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserModels.kt)
> - [app/build.gradle.kts](file:///workspace/deepcode-R/app/build.gradle.kts)

---

## 0. 背景与问题

内置浏览器当前的取数链路是：

```
navigate → waitForPageSettled(只等 isLoading=false) → snapshot()
```

`isLoading` 只反映**主文档**是否加载完。但大量网页（SPA、动态列表、实时数据页）的可见数据是由前端脚本通过 `fetch` / `XMLHttpRequest` / `WebSocket` / `EventSource(SSE)` 异步拉取的：

- 主 HTML 早已 `onPageFinished`，业务数据却仍在途；
- 此刻 `snapshot()` 抓到的 DOM 是「骨架屏 / 空列表」；
- 模型据此误判「页面就是空的」，反复重试或给出错误结论。

**根因不是「抓 DOM 不够用力」，而是「没把异步数据通道当成一等公民」。**

---

## 1. 目标与范围（已收敛）

| # | 决策点 | 结论 |
|---|---|---|
| D-1 | 首发范围 | **全量一步到位**：就绪判定升级 + fetch/XHR 插桩 + WebSocket/SSE 插桩 + SPA 路由 hook |
| D-2 | 响应体给模型的粒度 | **脱敏摘要**：每条保留前 ~2000 字符，去掉敏感头/字段 |
| D-3 | 插桩的数据通道 | fetch、XHR、WebSocket/SSE、SPA 路由 **全部纳入** |
| D-4 | 「网络空闲」判定口径 | **只看业务请求**（fetch/XHR/WS/SSE），忽略图片/字体/追踪等静态资源 |
| D-5 | 日志暴露方式 | **按需拉取**：快照只带 `pending_requests` 在途数，详情由模型主动调 `network`/`network_get` 取 |
| D-6 | 记录与脱敏范围 | **全记录 + 自动脱敏**：URL query、`Authorization`/`Cookie`/`Set-Cookie`、响应体敏感字段自动脱敏 |

---

## 2. 总体架构

```
                        ┌─────────────────────────────────────────────┐
  模型 ──snapshot──────▶│        BrowserController (单例 WebView)        │
  模型 ──network───────▶│                                              │
  模型 ──wait_for_req──▶│   ① document-start 注入 hook（每导航生效）      │
                        │   ② waitForPageSettled：业务请求空闲 + DOM 稳定 │
                        │   ③ evaluateJavascript 拉取缓冲               │
                        └──────────────┬──────────────────────────────┘
                                       │ WebView 渲染进程
                        ┌──────────────▼──────────────────────────────┐
                        │  页面内 JS 插桩层                             │
                        │   ├─ fetch / XHR / WebSocket / EventSource   │
                        │   │    └─ 写内存环形缓冲 window.__rcb_net     │
                        │   ├─ window.__rcb_net_pending（在途计数）      │
                        │   └─ history.pushState/replaceState/popstate │
                        │        └─ 触发 routechange                     │
                        └─────────────────────────────────────────────┘
```

要点：插桩层活在**页面 JS 运行时**里，Kotlin 层不主动嗅探网络，只在需要时用 `evaluateJavascript` 把缓冲「拉」出来。这样响应体能被读到（同源 / CORS 放行的请求 `.text()` 可读），又不需要本地 MITM 代理。

---

## 3. 数据模型

### 3.1 新增：`BrowserNetworkRecord`（Kotlin 侧，仅用于反序列化）

```kotlin
/** 单条异步数据请求记录（对应 JS 缓冲里的一条）。 */
@Serializable
data class BrowserNetworkRecord(
    val id: Int = 0,             // 单调递增序号（页面内）
    val op: String = "",         // fetch / xhr / websocket / eventsource
    val method: String = "",     // fetch/xhr 有效；ws/sse 为空
    val url: String = "",        // 已脱敏（query 敏感参数被替换为 ***）
    val status: Int = 0,         // HTTP 状态；ws/sse 用 0
    val durationMs: Long = 0,    // 请求耗时
    val size: Int = 0,           // 响应体字节数（-1 表示未能读取，如 opaque/no-cors）
    val startTs: Long = 0,       // 起播时间戳（用于排序）
    val responseSnippet: String = "", // 脱敏后前 N 字符摘要
    val error: String = ""       // 失败原因（网络错误/中断等）
)
```

`BrowserNetworkRecord` 只作为 Kotlin 反序列化的中间结构，**不持久化**；来源永远是 JS 缓冲。

### 3.2 JS 缓冲结构（页面内，页面级内存）

```js
window.__rcb_net = [];            // 环形缓冲，MAX_NET=100
window.__rcb_net_pending = 0;     // 在途业务请求数
window.__rcb_net_seq = 0;         // 递增序号
```

- 溢出时丢弃最旧并裁剪 `__rcb_net_seq` 对齐（防序号爆炸）。
- `pending` 只统计 `fetch/xhr/websocket(连接中)/eventsource(连接中)`，**不统计**图片、字体、预取、追踪脚本。

---

## 4. 注入时机与依赖

### 4.1 依赖新增

`addDocumentStartJavaScript` 属于 `androidx.webkit`：

```kotlin
// app/build.gradle.kts dependencies 新增
implementation("androidx.webkit:webkit:1.13.0")
```

### 4.2 注入点

在 `createWebView()` 内、首次 `loadUrl` 前调用：

```kotlin
WebViewCompat.addDocumentStartJavaScript(
    webView,
    NET_HOOK_JS,
    setOf("http://", "https://")   // 全站注入，仅 http/https
)
```

特点：脚本在**任何页面脚本运行前**执行，且**每次导航自动重新生效**（新 document → 新作用域），天然解决「SPA 不重载页面导致缓冲不重置」的半数问题。

> 注意事项：
> - 若设备 System WebView 过旧不支持该 API，调用为 no-op（不崩溃），此时自动降级：不采集网络数据，仅保留「就绪判定」里的 DOM 稳定判据兜底。
> - 与 `ALLOWED_SCHEMES` 白名单一致，仅对 http/https 注入。

---

## 5. JS 插桩层设计（`NET_HOOK_JS`）

统一约定：所有 hook 幂等——先判断 `window.__rcb_net` 是否已存在，存在则跳过重复包装。

### 5.1 `fetch`

```js
var __orig_fetch = window.fetch;
window.fetch = function(input, init) {
  var url = typeof input === 'string' ? input : (input && input.url) || '';
  var method = (init && init.method) || 'GET';
  var rec = __rcb_new_rec('fetch', method, __rcb_redact_url(String(url)));
  __rcb_net_pending++;
  var start = Date.now();
  try {
    return __orig_fetch.apply(this, arguments).then(function(resp) {
      __rcb_fill_resp(rec, resp);
      return resp;
    }).catch(function(e) {
      __rcb_fill_err(rec, e);
      throw e;
    });
  } catch (e) { __rcb_fill_err(rec, e); throw e; }
};
```

- `__rcb_fill_resp`：`status = resp.status`；`resp.clone().text()` 异步读 body → 填 `size` 与 `responseSnippet`（脱敏 + 截断），读失败则 `size=-1`；最终 `pending--`、`durationMs`、入缓冲。
- 通过 `.clone()` 读 body，不影响页面自身对原 `resp` 的消费。

### 5.2 `XMLHttpRequest`

- 包装 `prototype.open` 记录 `method`/`url`，`prototype.send` 记录起始。
- 监听 `loadend`（兼容失败/中止）：读 `status`、`responseText`（脱敏截断）→ 入缓冲；`pending--`。
- 对 `responseType='blob'/'arraybuffer'`：`size` 取 `response.size`/`byteLength`，`responseSnippet` 置空（二进制不摘要）。

### 5.3 `WebSocket`

- 包装 `window.WebSocket` 构造器，为每个实例建立连接级记录（`op='websocket'`，url 脱敏）。
- `.send(data)`：登记一条出站记录（`method='send'`，`responseSnippet` 为载体摘要，脱敏截断）。
- 入站：拦截 `onmessage` 与 `addEventListener('message', ...)`，每次消息登记一条（`method='message'`）。
- `onclose`/`onerror`：更新连接记录 `status` 与 `error`，`pending--`。

### 5.4 `EventSource`（SSE）

- 包装 `window.EventSource` 构造器：`op='eventsource'`，连接期间 `pending++`。
- `onmessage` / `addEventListener` 登记的入站事件 → 单条记录（`method='event'`）。
- `onerror` 更新 `error`；`onclose`/关闭时 `pending--`。

### 5.5 SPA 路由 hook

```js
['pushState','replaceState'].forEach(function(m){
  var orig = history[m];
  history[m] = function(){ var r = orig.apply(this, arguments); __rcb_route_changed(); return r; };
});
window.addEventListener('popstate', __rcb_route_changed);
```

`__rcb_route_changed` 做两件事：
1. `window.__rcb_route_seq++`（供 Kotlin 检测路由已变）；
2. 触发一个轻量 `rcb-routechange` DOM 事件（供未来「持续监听」功能复用）。

> 作用定位：**不负责主动抓取**，而是保证——模型一次 `navigate`/`click` 后的 `snapshot()` 里，`url`/`title` 始终是路由变更后的最新值（`JS_SNAPSHOT` 每次读 `location.href` 本就会刷新，此 hook 主要补齐 UI 侧标签栏 URL/标题的同步，以及未来「路由变化即重拍」的能力）。

### 5.6 脱敏规则（`__rcb_redact_url` / body redact）

- URL query：`/([?&](token|access_token|id_token|refresh_token|key|auth|sig|signature|secret|password|credential|session)=)[^&]*/gi → '$1***'`
- 响应体：JSON 风格 key 值脱敏 `/"(access_token|id_token|refresh_token|password|secret|apikey|api_key)"\s*:\s*"[^"]*"/gi → '"$1":"***"'`；随后截断前 `SNIPPET_MAX=2000` 字符。
- 请求头：**一律不采集**（仅记 `method` + 脱敏后的 `url`），从源头避免 `Authorization`/`Cookie` 泄露。

---

## 6. 就绪判定算法（`waitForPageSettled` 重构）

当前只等 `isLoading=false`，重构为三合一：

```
输入：timeoutMs（默认 30s）、debounceMs（默认 400ms）
deadline = now + timeoutMs
stable 计数器 = 0；lastDomMeta = null

循环（步长 100ms）：
  1. pending = evalJs("window.__rcb_net_pending || 0")
  2. domMeta = evalJs("(document.body && document.body.innerText.length) >= 0
       ? document.body.innerText.length + ':' + document.querySelectorAll('*').length : '-1'")
  3. routeSeq = evalJs("window.__rcb_route_seq || 0")

  if (pending == 0 && domMeta == lastDomMeta && routeSeq == lastRouteSeq):
       stable++
       if (stable * 100ms >= debounceMs): return 已就绪
  else:
       stable = 0
  lastDomMeta = domMeta；lastRouteSeq = routeSeq
  if (now > deadline): return 已就绪（超时也要返回，绝不无限等待）

finally: isLoading 兜底 false
```

关键点：
- **只看业务请求**：`pending` 不含静态资源，故追踪脚本/图片不会让判定卡死。
- **DOM 稳定 + 路由稳定**双判据，避免「请求都停了但 DOM 还在被脚本重排」时过早返回。
- 超时仍返回：宁可返回「可能还在加载」的快照（附带 `pending_requests` 提示），也绝不卡死模型。

---

## 7. 工具动作设计

在 `BrowserAgentTool` 的 `action` 枚举新增三项，`parameters` 补充可选参数。

### 7.1 `network`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `limit` | int | 否 | 返回最近 N 条（默认 20，上限 100） |

返回（`ToolResult.Success`）：

```json
{
  "ok": true,
  "total": 42,
  "pending": 3,
  "records": [
    { "id": 41, "op": "fetch", "method": "GET", "url": "https://api.example.com/list?page=2",
      "status": 200, "duration_ms": 180, "size": 12400, "response_snippet": "{\"items\":[...", "error": "" }
  ]
}
```

### 7.2 `network_get`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | 是 | URL 子串（命中最近一条匹配记录） |
| `id` | int | 否 | 精确序号（优先于 `url`） |

返回单条完整记录（`response_snippet` 仍受 2000 字符上限约束）。

### 7.3 `wait_for_request`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `url` | string | 是 | URL 子串，等待匹配的请求完成 |
| `timeout_ms` | int | 否 | 默认 15000 |

语义：轮询 JS 缓冲，直到出现 URL 命中 `url` 且状态已确定（成功/失败）的记录；超时返回 `Error(WAIT_TIMEOUT)` 并附当前 `pending`。用于「点按钮 → 等数据返回 → 再取结果」的确定性交互。

---

## 8. 安全与隐私边界

| 风险 | 策略 |
|---|---|
| 响应体含 token/PII | 默认只留前 2000 字符摘要 + JSON 敏感 key 脱敏；`responseSnippet` 不落盘 |
| URL query 含 token | 统一正则脱敏（见 5.6） |
| 请求/响应头泄露 | 请求头不采集；`Set-Cookie` 不采集 |
| 内存占用 | 环形缓冲 MAX_NET=100，最旧淘汰 |
| 跨页残留 | 新 document 新作用域，页面跳转自然清空；SPA 切换路由不清空但序号与 URL 已更新 |
| 二进制/大响应 | blob/arraybuffer 不摘要只记 size |
| opaque/no-cors 响应 | `.clone().text()` 读不到 → `size=-1`、`snippet` 置空，仍记 URL/状态/耗时 |

---

## 9. 实施步骤（分批）

- **Phase A · 就绪判定升级**：新增 `androidx.webkit` 依赖；注入最小版 `NET_HOOK_JS`（先只做 `pending` 计数，不采集详情）；重构 `waitForPageSettled`。
- **Phase B · fetch/XHR 插桩 + 动作**：补全 fetch/XHR 记录；新增 `BrowserNetworkRecord`；实现 `network`/`network_get`/`wait_for_request` 三个动作与 `pending_requests` 快照字段。
- **Phase C · WebSocket/SSE 插桩**：补全长连接通道记录。
- **Phase D · SPA 路由 hook**：`pushState`/`replaceState`/`popstate` + URL/标题同步。
- **Phase E · 脱敏打磨与边界**：query/body 脱敏正则、opaque/二进制降级、环形缓冲上限、超时策略联调。

> 实施状态：✅ **Phase A~E 全部完成**（对应 `JS_NET_HOOK` 全量落地，见下节）。

---

## 9.1 实施状态审计（对照当前代码）

| 设计点 | 实现状态 | 代码证据 |
|---|---|---|
| `NET_HOOK_JS` 注入（document-start） | ✅ | [BrowserController.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L1361) `WebViewCompat.addDocumentStartJavaScript(wv, JS_NET_HOOK, setOf("*"))` |
| fetch / XHR / WebSocket / EventSource 四通道插桩 | ✅ | [BrowserController.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L427-L619) `JS_NET_HOOK` 内 `__rcb_net_pending` 维护 + 各通道 wrap |
| 在途请求计数 `__rcb_net_pending` | ✅ | 同上；[BrowserController.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L1530) `evalJs("(window.__rcb_net_pending || 0)")` |
| SPA 路由 hook `__rcb_route_seq` | ✅ | [BrowserController.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L619-L622) 包装 `pushState`/`replaceState` + `popstate`；[L1541](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L1541) 读取路由序列 |
| 就绪判定升级（pending + DOM 稳定 + 路由 seq） | ✅ | `waitForPageSettled` 综合三者判定；网络缓冲解析见 [L1588](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/browser/domain/BrowserController.kt#L1588) |
| `network` / `network_get` / `wait_for_request` 动作 | ✅ | [BrowserAgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/browser/BrowserAgentTool.kt) 动作表含动态数据查询接口 |
| 浏览器侧动态数据查询 | ✅ | `pending_requests` 快照 + 按需拉取网络缓冲 |

> 结论：本文档设计目标已全部实现，无需新增实施任务。

---

## 10. 边界情况与风险

| # | 场景 | 处理 |
|---|---|---|
| R-1 | 追踪脚本/图片持续请求 | 「只看业务请求」口径，静态资源不参与空闲判定 |
| R-2 | WebSocket 长连接永不关闭 | 连接建立即 `pending--`（不阻塞就绪）；消息事件只入缓冲不计 pending |
| R-3 | `addDocumentStartJavaScript` 在旧 WebView 无效 | no-op 降级；仅剩 DOM 稳定判据兜底，不崩溃 |
| R-4 | 页面代码覆写 `window.fetch` 后又被 hook | document-start 早于页面脚本，仍能包住最早绑定；若页面后续再覆写，hook 失效但不影响功能 |
| R-5 | SPA 路由变化但无新请求 | 路由 `seq` 变化会重置稳定计数器，仍按 DOM 稳定判定 |
| R-6 | 超大响应体 | 只截前 2000 字符；二进制只记 size |
| R-7 | 隐私敏感站点 | 脱敏正则常驻；后续可扩展 host 白名单开关（本次不做，见 D-6） |

---

## 附：讨论记录

| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-18 | D-1 | 全量一步到位（就绪判定 + fetch/XHR + WebSocket/SSE + SPA 路由） |
| 2026-08-18 | D-2 | 响应体脱敏摘要（前 ~2000 字符） |
| 2026-08-18 | D-3 | 四类通道全部插桩 |
| 2026-08-18 | D-4 | 网络空闲只看业务请求 |
| 2026-08-18 | D-5 | 日志按需拉取（network/network_get），快照仅带在途数 |
| 2026-08-18 | D-6 | 全记录 + 自动脱敏（暂不做 host 白名单开关） |