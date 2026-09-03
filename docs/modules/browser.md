# 服务浏览器（Browser）模块文档

> 模块路径：`app/src/main/java/com/core/deepcode/feature/browser/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

提供内置**服务浏览器**能力：单个 `BrowserController` 单例持有一个（或多个标签的）WebView 实例，同时服务于**用户侧**（`ServiceBrowserScreen` 地址栏/标签栏/WebView 容器）与**模型侧**（`BrowserAgentTool` 通过 suspend 操作 navigate/snapshot/click/type/… 驱动页面）。

关键设计：

- **用户与模型共享同一浏览会话**：同一份 Cookie、同一个页面，用户登录后模型自动复用登录态。
- **可代理浏览器控制（Browser Control）**：模型把浏览器当作工具操作，包括点击、输入、选择、提交、滚动、悬停、拖拽、按键、上传文件、截图、抽取结构化数据、多标签页、下载等。
- **动态数据捕获**：document-start 注入 JS 插桩包装 fetch/XHR/WS/SSE，供模型查询页面网络请求、判定页面就绪与等待请求。
- **安全护栏**：协议白名单（仅 http/https）、密码等敏感字段快照脱敏、网络记录 URL/响应体脱敏。
- **异步人机协作**：登录凭据输入（`BrowserLoginPromptManager`）与用户接管（`BrowserTakeoverManager`）均采用 park-and-resume 挂起/唤醒模式。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `domain/BrowserController.kt` | 浏览器核心控制器（`@Singleton`）：持有 WebView 单例/多标签、模型操作全集（suspend）、快照分级/增量 diff/三级定位/事件驱动等待、网络捕获、WebView 代理接管、下载、对话框处理 |
| `domain/BrowserModels.kt` | 数据模型：`BrowserElement`/`BrowserOption`/`BrowserHeading`/`BrowserPageSnapshot`/`BrowserNetworkRecord`/`BrowserTabInfo`/`BrowserDownloadInfo`/`BrowserUiState`/`AgentBrowserStatus`/`PendingBrowserDialog`/`PendingLoginPrompt`/`BrowserCredential`；R2 新增 `SnapshotLevel`/`BrowserSnapshotDelta`/`BrowserActionResult`（统一 envelope）/`ResolvedElement`/`WaitChangeResult`；R1 新增 `BrowserHistoryEntry`/`BrowserBookmark` |
| `domain/BrowserHistoryStore.kt` | 访问历史持久化（R1.1）：SharedPreferences + JSON 按时间倒序保存最近 200 条，同 URL 去重上移，无痕模式不写入 |
| `domain/BrowserBookmarkStore.kt` | 收藏夹持久化（R1.1）：SharedPreferences + JSON 书签增删查（title/url） |
| `domain/BrowserCredentialStore.kt` | 站点登录凭据加密存储：Android Keystore AES-GCM 密钥 + SharedPreferences 按 host 维度存取 |
| `domain/BrowserLoginPromptManager.kt` | 登录凭据 park-and-resume 流程：工具侧 `awaitCredentials` 挂起，UI 侧 `resolve`/`cancel` 唤醒（超时 3 分钟） |
| `domain/BrowserTakeoverManager.kt` | 用户接管 park-and-resume 流程：模型遇到验证码/支付/二次认证等调用 `awaitTakeover`，用户完成/取消唤醒（超时 5 分钟） |
| `presentation/ServiceBrowserScreen.kt` | 浏览器页 Compose UI：地址栏、前进/后退/刷新、「更多」菜单（历史/收藏/下载/凭据/分享/复制链接/无痕/桌面版/缩放/页内查找）、新标签页主页、标签栏、WebView 容器、模型操作状态条、alert/confirm 对话框、登录凭据输入、用户接管提示 |

## 3. 核心架构与主流程

### 3.1 线程模型

WebView 所有操作必须在主线程。`BrowserController` 把每个模型操作封装成 suspend 函数，内部统一调度到 `Dispatchers.Main`（`withContext(Dispatchers.Main)` 或主 Handler post），工具侧在 IO 协程调用即可。全部操作经 `Mutex` 串行化，避免并发操作同一页面。

### 3.2 多标签与 UI 绑定

- 每个标签一个 `BrowserTab`（独立 WebView，保留各自历史栈/会话）。
- `bind()`/`unbind()`：页面挂载/卸载时复用同一 WebView（摘除旧父容器避免 AndroidView 二次 addView 崩溃）；`ensureActiveTab()` 在组合前预创建首个标签，避免组合期间改 activeTabId 引发崩溃。
- 状态流：`uiState`（URL/标题/能否前进后退/加载进度/screenVisible/activeTabId）、`tabsState`、`agentStatus`（模型操作状态条）、`downloads`、`pendingDialog`。

### 3.3 模型操作（suspend API）

> **统一动作 envelope（R2.3，干净替换）**：所有动作返回 `BrowserActionResult{ok, action, changed, summary, note|error, recoverable, snapshot?, delta?}`，`snapshot` 按 `snapshot_level` 分级可选；写操作自动计算 `delta`（新增/变化/消失元素清单 + 文本变化摘要）做前后对比，模型无需反复 snapshot 轮询。

| 操作 | 说明 |
| --- | --- |
| `navigate(url)` | 校验协议白名单后 `loadUrl`，`waitForPageSettled` 等页面就绪并返回快照（写操作，自动验证变化） |
| `snapshot(level=SUMMARY/STANDARD/FULL)` | 页面快照：`summary` 仅标题/URL/标题大纲/每控件一行紧凑摘要（最省 token）；`standard` 加完整元素 JSON；`full` 再加 page_text |
| `pageText()` | 单独取页面正文（R2.1 按需取文，不随快照返回） |
| `computeDelta(after)` | 相对基线快照计算增量（新增/消失/变化元素 + 文本变化摘要） |
| `resolveElementId(locator)` | 三级定位解析（R2.2）：`data-rcb-id` 直查 → CSS 绝对路径 locator 映射 → 语义匹配，返回命中方式（id/css/semantic） |
| `click/type/selectOption/submit/scroll/hover/pressKey/drag` | 通过三级定位解析 `element_id` 后执行对应 JS，操作前自动 `scrollIntoView({block:'center'})` 消除"点了没反应"；返回 envelope（写操作，自动验证变化） |
| `getAttribute/evaluate/extract` | 读属性、执行任意 JS、结构化取数（links/headings/table/text/html） |
| `screenshot(elementId?)` | 视口/元素截图，返回 base64 PNG data URL 供多模态模型查看 |
| `handleDialog(accept)` | 处理页面 alert/confirm |
| `waitFor(selector, timeout)` | 等待元素出现或页面加载完成 |
| `waitForChange(timeout, baselineVersion)` | 事件驱动等待（R2.4）：挂起等待 DOM 版本号（MutationObserver）/网络变化后返回 `WaitChangeResult` 变化摘要，替代轮询 snapshot |
| `history()` / `recordAction` / `actionHistory()` | 最近 N 条动作日志（R2.3 动作历史），避免重复操作 |
| `uploadFile(elementId, hostFile)` | 向 file 输入自动回填本地文件（暂存 `pendingUploadFile`，`onShowFileChooser` 时用 FileProvider Uri 回填） |
| `back/forward/reloadPage` | 导航历史操作 |
| `newTab/switchTab/closeTab/listTabs` | 多标签管理 |
| `listNetwork/getNetwork/waitForRequest/networkPendingCount` | 查询页面网络请求记录 |

### 3.4 快照分级与元素定位（R2）

- **快照分级**：`snapshot(level)` 控制粒度——`summary`（默认，仅控件紧凑摘要）/ `standard`（+ 完整元素 JSON）/ `full`（+ page_text 截断上限），模型按需提级省 token。
- **增量 diff**：controller 缓存上次写操作后的快照作为基线，`computeDelta` 计算新增/消失/变化元素（按 `elementSignature` 比对 id/文本/取值/可操作性）与文本变化摘要，写操作自动返回 delta。
- **三级定位**：快照每元素返回 `id`（data-rcb-id）+ `locator`（CSS 绝对路径）+ `semantic`（role+可访问名称+index）三字段；`element_id` 参数三者任一皆可，解析顺序 `[data-rcb-id]` 直查 → locator 映射按 CSS 路径解析 → 语义模糊匹配 → 返回可恢复错误（`recoverable=true` + 建议）。
- **可操作性标注**：快照元素补 `in_viewport/visible/needs_scroll/overlapped`（基于 `getBoundingClientRect` + `elementFromPoint`），操作前自动滚动到视口居中。

### 3.5 事件驱动感知（R2.4）

- document-start 注入 `MutationObserver` 维护页面环形缓冲 + 版本号 `__rcb_mut_version`（`readMutVersion` 读取），`waitForChange` 挂起轮询版本号变化后返回变化摘要；
- 同时复用 `JS_NET_HOOK` 网络事件（在途请求变化也会唤醒）；
- 替代模型反复 snapshot 轮询，减少 token 消耗与响应延迟。

### 3.6 页面快照与 JS 注入

- `JS_SNAPSHOT`：给可交互控件打 `data-rcb-id`，解析控件类型/标签/取值/状态，返回元素树；密码等敏感字段 `value` 置空、标 `sensitive=true`。
- `JS_PAGE_TEXT`：正文纯文本（截 12000）。
- `JS_TYPE`：React 兼容的 value setter（走原型描述符 set + input/change 事件）。
- `JS_NET_HOOK`：`addDocumentStartJavaScript` 在 document-start 注入，包装 fetch/XHR/WebSocket/EventSource，写入页面内环形缓冲 `window.__rcb_net`（上限 100 条）并维护在途计数 `__rcb_net_pending`；URL query 敏感参数与响应体敏感 JSON key 均替换为 `***`；同时 hook pushState/replaceState/popstate 维护 `__rcb_route_seq`。
- `waitForPageSettled`：以「主文档加载完成 + 在途业务请求为 0 + DOM 指纹稳定 + 路由稳定」三重条件判定页面就绪（400ms debounce）。

### 3.7 WebView 代理接管

WebView 不认 Java `ProxySelector`，只能用 `ProxyController.setProxyOverride` 做进程级代理覆盖：代理开启时指向 mihomo mixed-port `127.0.0.1:7890` 并放行 loopback/内网（容器开发服务直连），关闭时 `clearProxyOverride` 回退系统默认网络。`init` 中订阅 `proxyManager.state` 让已存在 WebView 跟随代理开关变化。

### 3.8 下载与会话

`setDownloadListener` → `downloadToWorkspace`：用共享 OkHttp 下载到 `~/workspace/downloads`（携带当前页面 Cookie 保持登录态，代理启用时经 mihomo 出口），任务状态经 `_downloads` 暴露，保留最多 50 条。

### 3.9 用户侧能力（R1）

- **历史记录**（R1.1）：`BrowserHistoryStore` 持久化（SharedPreferences+JSON，最近 200 条，同 URL 去重），浏览器页「更多 → 历史记录」列表回跳 + 清空；无痕模式不记录。
- **收藏夹**（R1.1）：`BrowserBookmarkStore` 持久化书签增删查，「更多 → 收藏本页/取消收藏」与「更多 → 收藏夹」管理，新标签页主页快捷入口。
- **新标签页/主页**（R1.1）：当前标签无 URL 时展示搜索框 + 最近访问 + 收藏夹快捷入口。
- **页内查找**（R1.1）：`WebView.findAllAsync/findNext/clearMatches`，查找条输入实时高亮、上/下一个切换、关闭清除。
- **下载管理 UI**（R1.2）：基于 `downloads` StateFlow 面板化（列表/打开（FileProvider）/重试/清除列表）。
- **凭据管理 UI**（R1.2）：基于 `BrowserCredentialStore` 列出现有 host + 用户名，明文查看受保护（点击眼睛才显示密码）、删除（需确认）。
- **分享/复制链接**（R1.2）：地址栏「更多」菜单，`Intent.ACTION_SEND` 分享 / 剪贴板复制（Toast 提示）。
- **无痕模式**（R1.3）：会话级开关，开启/关闭时清空 Cookie 与缓存、无痕期间不记录历史（`uiState.incognito`）。
- **桌面版切换**（R1.3）：`WebSettings.userAgentString` 桌面 UA（`DESKTOP_UA`）切换后重载当前页（`uiState.desktopMode`）。
- **缩放控制**（R1.3）：`WebSettings.textZoom` 50–200% 步进调整 + 重置（`uiState.textZoom`）。

### 3.10 登录与接管协作

- `detectLoginForm`：根据密码框/登录文案探测登录页。
- `BrowserLoginPromptManager.awaitCredentials(host)`：工具侧挂起 → `pendingPrompt` 状态触发 UI 凭据输入弹窗 → `resolve/cancel` 唤醒，超时 3 分钟自动放行。
- `BrowserTakeoverManager.awaitTakeover(title, message)`：挂起 → UI 提示用户亲自操作 → `resolve(TakeoverAnswer(confirmed=true))` 让模型继续，超时 5 分钟取消。
- `BrowserCredentialStore`：Keystore AES-GCM 密钥（不可导出），凭据 `"username\u0001password"` 加密后存入 SharedPreferences（IV 前置 Base64），按 host 维度存取；模型代填时会读明文发给云端，文档注释明确提示该风险可由登录代填前确认开关缓解。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `BrowserController` | `@Singleton`，注入 `WorkspacePathMapper`（下载路径映射）、`ClashProxyManager`（代理接管）、`OkHttpClient`（下载）、`BrowserHistoryStore`、`BrowserBookmarkStore` |
| `BrowserAgentTool` | 模型侧工具：通过上述 suspend 操作驱动页面（不在此目录，消费方），动作统一返回 envelope |
| `BrowserHistoryStore.entries/record/clear` | 访问历史持久化（R1.1） |
| `BrowserBookmarkStore.bookmarks/contains/add/remove` | 收藏夹持久化（R1.1） |
| `BrowserCredentialStore.find/save/delete/hosts` | 凭据读写（模型登录代填与 UI 管理） |
| `BrowserLoginPromptManager.awaitCredentials/pendingPrompt/resolve/cancel` | 登录凭据 park-and-resume |
| `BrowserTakeoverManager.awaitTakeover/pending/resolve/cancel` | 用户接管 park-and-resume |
| `ServiceBrowserScreen` | Compose 页面，参数注入 `browserController`/`loginPromptManager`/`takeoverManager`/`credentialStore`/`initialUrl`/`onNavigateBack` |

## 5. 关键设计点与约束

- **协议白名单**：`ALLOWED_SCHEMES = {http, https}`，拦截 `file://`/`content://`/`intent://`/`javascript:` 等危险 scheme。
- **敏感数据脱敏**：密码框 value 不回传、`JS_ATTRIBUTE` 对 value/textContent 返回 `[redacted]`、网络记录 URL/响应体正则脱敏。
- **共享会话**：模型与用户共用同一 WebView/Cookie；`unbind()` 不销毁 WebView，保留会话与登录态。
- **WebView 代理**：必须走 `ProxyController` 进程级覆盖，且统一 post 主线程；不支持 `PROXY_OVERRIDE` 时降级走系统默认网络并告警。
- **上传回填**：仅当 `pendingUploadFile` 已暂存时才拦截 `onShowFileChooser` 自动回填，否则走系统默认文件选择器配合 takeover。
- **至少保留一个标签**：`closeTab` 在 `tabs.size <= 1` 时拒绝关闭。

## 6. 维护与扩展指引

- **新增模型操作**：在 `BrowserController` 加对应 suspend 方法（`mutex.withLock` + 主线程调度），需页面 JS 时在 companion 增加 `JS_*` 常量；`BrowserAgentTool` 侧同步暴露；动作统一返回 `BrowserActionResult` envelope（写操作需调用 `afterWrite` 自动验证变化）。
- **扩展网络捕获**：`JS_NET_HOOK` 中新增捕获目标（如 beacon、iframe）时，同步扩展 `BrowserNetworkRecord` 字段与 `decodeNetworkList` 反序列化。
- **敏感字段扩展**：新增密码类字段需在快照/属性读取/网络脱敏三处保持一致。
- **升级 AndroidX WebView 依赖**：`ProxyController` 依赖 `WebViewFeature.PROXY_OVERRIDE`、`addDocumentStartJavaScript` 依赖 `WebViewFeature.DOCUMENT_START_SCRIPT`，升级时需校验特性开关，注入失败已按降级处理。
- **快照/定位扩展**：新增元素可感知字段时同步 `elementSignature`、`JS_SNAPSHOT` 与三级定位解析（`resolveElementId`）；新增页面 JS 事件时同步 MutationObserver 版本号维护。
- **测试建议**：覆盖多标签切换复用、模型驱动交互后快照、快照 diff、三级定位 id 自愈、可操作性判定、wait_for_change 触发、登录探测与凭据代填、接管超时、上传回填、下载落盘路径、WebView 代理开关切换。

## 7. 版本演进记录

> 本模块开发维度演进；用户可见变更见仓库根 [CHANGELOG.md](../../CHANGELOG.md)。

- **v0.3.0（2026-08-26）**：浏览器升级两批落地——
  - **R2 模型侧**：快照分级（summary/standard/full）+ 客户端增量 diff（`BrowserSnapshotDelta`）+ 统一动作 envelope（`BrowserActionResult`，干净替换无兼容层）+ 写操作自动验证 + 三级定位（CSS 绝对路径为主 + data-rcb-id 兜底 + 语义回退）+ 可操作性标注 + 自动滚动 + `wait_for_change`（MutationObserver 版本号）与动作 `history`；新增 `BrowserHistoryStore`/`BrowserBookmarkStore` 依赖注入。
  - **R1 用户侧**：历史记录/收藏夹持久化与面板、新标签页主页（搜索+最近访问+收藏）、页内查找条、下载管理 UI（打开/重试/清除）、凭据管理 UI（明文受保护+删除）、分享/复制链接、「更多」菜单（无痕/桌面版/缩放开关）；`ServiceBrowserScreen` 新增 `credentialStore` 参数。
  - **内存自愈（预防闸门）**：新增 `onMemoryPressure()`——由 `AIEditorApp.onTrimMemory` 在 `RUNNING_CRITICAL`/`lowMemory` 时转发调用，释放 `lastSnapshot`/`deltaBaseline`/`lastDelta` 页面快照大对象缓存，降低 LMKD 静默杀进程概率（LMKD 杀绕过 Java CrashHandler，是「模型输出时闪退却无日志」高概率根因）。
  - **快照元素上限**：`JS_SNAPSHOT` 新增 `MAX_SNAPSHOT_ELEMENTS = 300` 保护——超大页面（长列表/表格/导航）只取前 300 个可交互控件，防止一次快照生成超大 JSON（内存/耗时峰值，低内存时易触发 LMKD 静默杀进程）；模型可滚动加载后续元素，summary 级快照本就只给控件摘要，上限不影响可用性。
- **v0.1.0（2026-08-17 ~ 08-18）**：内置服务浏览器核心功能落地；动态数据捕获（异步请求插桩 + 网络日志查询 + 就绪判定升级，随后补齐 WS 出站记录 + SPA routechange 事件 + network 汇总字段）；修复进入/切换浏览器页 WebView 重复挂载与复用前未摘除旧父容器崩溃。
