# 极难爬取站点的数据获取方法 · 全网调研总结（深度版）

> 版本：v1.1
> 定位：方法论 + 代码思路 + 工程架构深度调研，供内置浏览器「动态数据捕获」能力演进参考。
> 范围声明：本文仅覆盖**公开、正当**的爬虫技术（动态渲染、接口逆向、反检测、限速合规、工程架构）。
> 破解验证码、逆向风控签名、绕过登录墙/付费墙、破解滑块等越界或违法手段**不收录、不展开**。

---

## 目录

第一部分 · 总览
1. [核心思维模型：分层与一致性](#1-核心思维模型)
2. [动态数据获取的三大路径](#2-动态数据获取的三大路径)
3. [实时数据通道：WebSocket / SSE / CDP](#3-实时数据通道)
4. [TLS 与 HTTP/2 指纹对抗](#4-tls-与-http2-指纹对抗)
5. [无头浏览器反检测与指纹底层](#5-无头浏览器反检测与指纹底层)
6. [隐藏接口的自动化发现（CDP 高级）](#6-隐藏接口的自动化发现)

第二部分 · 深度篇
7. [JS 逆向技术体系](#7-js-逆向技术体系)
8. [采集框架与系统架构设计](#8-采集框架与系统架构设计)
9. [代理池架构设计](#9-代理池架构设计)
10. [工程化关键点](#10-工程化关键点)
11. [合规红线](#11-合规红线)
12. [与本项目的对接与落地建议](#12-与本项目的对接与落地建议)
13. [参考来源](#13-参考来源)

---

# 第一部分 · 总览

## 1. 核心思维模型

新手和资深爬虫工程师最本质的差别：新手被拦只会无脑堆 `User-Agent`；资深的先问一句——**我卡在哪一层？**。

现代反爬是分层流水线，任何一层露馅都会被秒杀。更重要的是：**各层必须互相一致**，任何一处不一致都会被风控系统判负。

| 层次 | 检测什么 | 典型手段 | 对应思路 |
|---|---|---|---|
| 1. 网络层 | IP 信誉 / ASN、请求频率 | 封 IP、429、蜜罐链接 | 住宅代理池 + 轮换 + 限速 |
| 2. TLS 层 | `ClientHello` 的加密套件 / 扩展顺序 | Cloudflare/Akamai 的 JA3/JA4 | `curl_cffi` 伪造浏览器握手 |
| 3. HTTP/2 层 | SETTINGS 帧、WINDOW_UPDATE、伪头顺序 | Akamai h2 指纹 / JA4H | 与 TLS 一起伪造（同一库搞定） |
| 4. 浏览器层 | `navigator.webdriver`、Canvas/WebGL/Audio 指纹、JS 挑战 | 极验/数美/Turnstile | 反检测浏览器 + 指纹伪装 |
| 5. 行为层 | 鼠标轨迹、滚动、请求节奏、会话时长 | PerimeterX/HUMAN | 随机化延迟 + 拟人轨迹 |

关键结论：

- **爬虫即使代理完美、header 完美，仍可能在 TLS 层就出局**——握手发生在任何 HTTP 字节之前。
- **一致性是第一原则**：UA 说「Chrome on Windows」，但 TLS 握手是 Python urllib3、TCP 是 Linux 服务器、HTTP/2 SETTINGS 是 httpx——风控不查任何单点，只查「这些对得上吗」。

排查被拦的顺序：

```
先查 TLS/HTTP2 → 再查浏览器指纹 → 最后才查 IP
```

---

## 2. 动态数据获取的三大路径

现代站点多为 React/Vue SPA，初始 HTML 只有 `<div id="root"></div>`，数据靠后续 JS 异步拉取。按优先级分三档：

### 2.1 路径一：接口逆向（首选，效率最高，快 5–10 倍）

核心认知：**数据一定有源头接口，跳过页面渲染直接打接口**。

步骤：

1. DevTools → Network → 筛选 `XHR/Fetch` → 勾选 `Preserve log`
2. 触发滚动/搜索/翻页，定位返回 JSON 的请求
3. `右键 → Copy as cURL` → 用 curlconverter 转成代码

```python
import httpx

headers = {
    "Accept": "application/json",
    "X-Requested-With": "XMLHttpRequest",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120.0.0.0 Safari/537.36",
    "Referer": "https://target.com/list",
    "Origin": "https://target.com",
}

# 直接打数据接口，绕过整个页面渲染
r = httpx.get("https://target.com/api/products?page=1&pageSize=50", headers=headers)
data = r.json()
```

要点：把 `pageSize` 拉到 UI 上限之外（界面每页 20 条，接口常允许 100 条）；识别分页规律（`page/limit` 或 `cursor`）。若接口带签名/加密，进阶方法见 [§7 JS 逆向技术体系](#7-js-逆向技术体系)。

### 2.2 路径二：无头浏览器（兜底，接口加密太重时才用）

当接口有动态签名、设备指纹、JS 挑战时，退回真实浏览器执行。主力工具 **Playwright**（自动等待、支持 CDP、跨语言）。

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, args=[
        "--disable-blink-features=AutomationControlled",
    ])
    page = browser.new_page()
    # 拦掉图片/字体，只留数据流量，降低内存与风控面
    page.route("**/*.{png,jpg,jpeg,woff,woff2}", lambda r: r.abort())
    page.goto("https://target-spa.com", wait_until="networkidle")
    # 很多 SPA 会把数据塞进全局变量
    data = page.evaluate("window.__INITIAL_STATE__")
```

### 2.3 路径三：掏 SSR「尸体」数据（最容易被忽视）

服务端渲染（Next.js/Nuxt）会在 HTML 里内嵌一段 JSON，无需等 JS 执行：

```python
import re, json, requests

html = requests.get("https://nextjs-site.com/products").text
m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html)
data = json.loads(m.group(1))  # 直接拿到整页结构化数据
```

---

## 3. 实时数据通道

行情、弹幕、实时榜单等数据走长连接推送，「一问一答」的 `requests` 会永远卡住。

### 3.1 SSE（单向流）

SSE 是 WebSocket 的轻量替代，基于普通 HTTP，服务器持续单向推送。用最朴素的流式读即可抓：

```python
from http.client import HTTPConnection

conn = HTTPConnection("97.push2.eastmoney.com", 80)
conn.request("GET", "/api/qt/stock/details/sse?fields1=f1,f2,f3&secid=1.600807")
resp = conn.getresponse()
while not resp.closed:
    for line in resp:
        print(line.decode("utf-8"))   # 逐行读到最新推送
```

### 3.2 WebSocket（全双工）

两种思路：

1. **直接连**：用 `websocket-client` / `websockets` 库建立长连接、发订阅、处理推送。
2. **靠 CDP 抓帧**：用 CDP 的 `Network.webSocketFrameReceived` / `Network.webSocketFrameSent` 事件，无需理解对方协议，直接截获帧内容。

工程关注点：心跳保活、`on_close` 退避重连、`trade_id`/序号幂等去重、客户端流速控制。

---

## 4. TLS 与 HTTP/2 指纹对抗

### 4.1 TLS 指纹（JA3 / JA4）

2026 年被拦的第一大元凶：`requests`(OpenSSL) 的 TLS 握手与真 Chrome 的握手对不上，被 JA4 在握手阶段就判定为脚本。

- **JA3**（2017）：`MD5(TLSVersion, Ciphers, Extensions, EllipticCurves, ECPointFormats)`
- **JA4**（2023）：排序后分段的可读指纹（如 `t13d1516h2`），Chrome 扩展随机化不再影响结果

### 4.2 HTTP/2 指纹（JA4H / Akamai h2）

TLS 层之上还有一层 HTTP/2。即使 TLS 仿得再像，SETTINGS 帧暴露真实客户端。连接建立后第一个 HTTP/2 报文就是 SETTINGS 帧，各实现默认值差异极大：

| 参数 | Chrome | Python httpx | Go net/http |
|---|---|---|---|
| `HEADER_TABLE_SIZE` | 65536 | 4096 | 4096 |
| `ENABLE_PUSH` | 0 | 1 | 1 |
| `MAX_CONCURRENT_STREAMS` | 1000 | 100 | 250 |
| `INITIAL_WINDOW_SIZE` | 6291456（6MB）| 65535 | 65535 |
| `MAX_FRAME_SIZE` | 16384 | 正确 | 正确 |
| `MAX_HEADER_LIST_SIZE` | 262144 | 不发 | 不发 |

除 SETTINGS 外还有三个信号：Chrome 紧跟一个 **WINDOW_UPDATE** 帧（delta 15663105 = 15MB − 65535）；伪头顺序 Chrome 为 `:method, :authority, :scheme, :path`（Firefox 是 `:method, :path, :authority, :scheme`）；Chrome 会发 **PRIORITY** 帧而多数库直接跳过。

### 4.3 统一解法

```python
from curl_cffi import requests  # 只换个 import

resp = requests.get("https://target.com/", impersonate="chrome")
print(resp.http_version)  # HTTP/2，与真浏览器一致
```

`impersonate="chrome"` 一行同时伪造四层：TLS 指纹（JA3/JA4）、HTTP/2 SETTINGS/帧、header 顺序、ALPN。这正是它与「只换 UA」的本质差别。

要点：**用 `"chrome"` 别名而非写死的 `"chrome124"`**，否则 Chrome 每 4 周一更新，旧 profile 反而变成异常特征。

---

## 5. 无头浏览器反检测与指纹底层

### 5.1 检测信号 vs 对抗档次

检测信号：`navigator.webdriver === true`、Canvas/WebGL GPU 指纹、`chrome.runtime` 缺失、插件为空、媒体编解码器缺失等。

三个对抗档次：

1. **轻量**：`--disable-blink-features=AutomationControlled` + 注入脚本把 `navigator.webdriver` 抹成 `undefined`
2. **插件级**：`puppeteer-extra` + `puppeteer-extra-plugin-stealth`（内置 18+ 项伪装，Firecrawl 靠它过 Cloudflare）
3. **二进制级**：直接改 Chromium C++ 源码擦指纹（如 CloakBrowser），反爬系统把它当正常浏览器

### 5.2 stealth 插件的关键设计（源码级）

插件核心是 `evaluateOnNewDocument`——**在页面所有脚本执行前**注入伪装代码，让检测脚本读不到原始值。

```javascript
// navigator.webdriver 伪装：删除原型链属性 + Proxy 双重防御
if (navigator.webdriver !== undefined) {
    delete Object.getPrototypeOf(navigator).webdriver;
}
window.navigator = new Proxy(window.navigator, {
    get: (t, p) => p === 'webdriver' ? undefined : Reflect.get(t, p),
    has: (t, p) => p === 'webdriver' ? false : Reflect.has(t, p),
});
```

Canvas/WebGL 靠扰乱 `getImageData` / `getParameter` 返回值；`chrome.runtime`、`media.codecs`、WebGL `vendor`/`renderer` 用预置静态数据模拟真实签名。

### 5.3 浏览器指纹底层原理（12 大向量）

指纹是「哈希化」的多信号组合，单个信号熵值低，20–30 个组合起来的唯一率可达 99.5%：

| 熵值 | 向量 | 说明 |
|---|---|---|
| 极高 | Canvas | GPU/驱动/字体渲染的像素级差异 |
| 极高 | WebGL | 直接暴露 GPU 型号（`WEBGL_debug_renderer_info`）|
| 极高 | 字体列表 | 测宽度推断已装字体集合 |
| 高 | Audio | `OscillatorNode`+`DynamicsCompressorNode` 波形差异 |
| 中 | screen/navigator | 分辨率、DPR、`hardwareConcurrency`、时区 |

风控的杀手锏是**一致性校验**：UA 说 iPhone，但 WebGL 报桌面 NVIDIA GPU、时区说东京但 IP 在俄亥俄、language 是俄语——这种组合现实中不存在，比任何单一信号都更能定罪。裸 HTTP 客户端跑不了 Canvas/WebGL 的 JS，所以**只要目标是浏览器指纹，任何 HTTP client（无论 TLS 多像）都过不了**。这是「HTTP 客户端 → 无头浏览器 → 反检测浏览器」升级阶梯的边界所在。

---

## 6. 隐藏接口的自动化发现

人工翻 Network 太慢，工程化做法是**用无头浏览器挂 `response` 事件钩子，自动枚举所有返回 JSON 的接口**：

```python
page.on("response", lambda resp:
    collect(resp.url, resp.request.method,
            resp.headers.get("content-type"), try_json(resp)))
await page.goto(url, wait_until="networkidle")
# 自动得到 [path, method, status, response_keys, item_keys] 清单
```

这与本项目 `BrowserController` 的 `listNetwork/getNetwork` 是同一件事。

---

# 第二部分 · 深度篇

## 7. JS 逆向技术体系

接口带 sign/token/加密参数时，需要逆向 JS 里的加密逻辑。这是「极难爬取」里技术含量最高的一环。

### 7.1 定位加密入口

| 方法 | 操作 |
|---|---|
| XHR 断点 | Sources → XHR/fetch Breakpoints → 加 URL 片段，命中断点回溯调用栈 |
| 全局搜索 | `Ctrl+Shift+F` 搜参数名（`sign`、`nonce`、`encrypt`）|
| Initiator 标签 | Network 里点请求 → Initiator → 看触发链 |
| Scope 面板 | 断点处查看闭包里的密钥、盐值 |

### 7.2 Hook 拦截

```javascript
// Hook btoa（Base64 定位）
const orig = window.btoa;
window.btoa = function (data) {
    console.log("btoa input:", data);
    return orig(data);
};

// Hook XHR.send（拦请求体里的加密参数）
const origSend = XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.send = function (...args) {
    console.log("XHR body:", args[0]);
    return origSend.apply(this, args);
};
```

### 7.3 补环境（跑通抠出来的 JS）

加密函数抠到 Node.js 里跑，会因缺 `window`/`document`/`navigator` 报错。用 `vm` + `Proxy` 搭隔离环境，顺带过反调试：

```javascript
const vm = require('vm');
const cleanEnv = new Proxy({}, {
    get(t, p) {
        if (p === 'debugger') return () => {};      // 阻断反调试
        if (p === 'console') return { log: () => {} };
        return Reflect.get(t, p);
    }
});
const safeEval = (code) => vm.runInContext(code, vm.createContext(cleanEnv));
```

### 7.4 三方案选型

| 方案 | 适用场景 | 代表 |
|---|---|---|
| 扣 JS 代码 | 逻辑简单、无环境依赖 | `PyExecJS` / `execjs` |
| RPC 通信 | 复杂混淆、强浏览器环境依赖 | `Sekiro`（浏览器里跑真实 JS，Python 走 RPC 调）|
| 无头浏览器 | 需完整渲染流程 | Playwright / Selenium |

### 7.5 AST 反混淆与内存漫游

- **AST 还原**：`@babel/core` 做控制流平坦化还原、字符串解密、变量重命名。
- **内存漫游**（`ast-hook-for-js-RE`）：变量级抓包——把页面所有变量存库，拿到加密参数后反查「哪个变量存了它、在哪个代码位置」，再往前打断点追溯加密逻辑。这是追加密逻辑位置的「通杀方案」。

---

## 8. 采集框架与系统架构设计

大佬不自己重写队列/重试/去重，而是复用或借鉴成熟框架的架构设计。

### 8.1 Scrapy：管道-过滤器模式

五大组件单向流动，中间件拦截：`Spider → Engine → Scheduler → Downloader → Spider → Item Pipeline`。核心设计：

- **去重**：`RFPDupeFilter` 基于请求指纹（URL+method+body 哈希）
- **分布式**：`scrapy-redis` 把 Scheduler 与 DupeFilter 换成 Redis 队列 + Redis Set 去重，多机共享一个队列
- **扩展点**：信号（Signals）+ 下载中间件 + Spider 中间件

### 8.2 Crawlee（Apify 开源内核）：采集运行时

Crawlee 不是 Playwright 的薄封装，而是完整采集运行时，抽象了队列/重试/持久化/代理/会话：

- `RequestQueue`：持久化 + 去重 + 状态机（pending/processing/done/failed）
- `AutoscaledPool`：按 CPU/内存/事件循环动态调整并发
- `SessionPool`：Cookie + 身份 + 代理 IP 绑定到同一会话，失败即退役
- `Router`：按 label 把列表页/详情页/附件页分发到不同 handler
- `Storage`：Dataset（追加表）/ KeyValueStore / RequestQueue 三种存储
- 抓取引擎三选一：`CheerioCrawler`（纯 HTTP，吞吐最高）/ `PlaywrightCrawler` / `PuppeteerCrawler`

### 8.3 系统架构四级演进

| 层级 | 结构 | 适用规模 | 关键新增 |
|---|---|---|---|
| L1 单脚本 | 进程内 + 文件输出 | <1K URL | 无（原型验证）|
| L2 队列化 | Redis/BullMQ/SQS + 数据库 + 重试 | 1K–500K | 队列、指数退避、死信队列 |
| L3 分布式 | N worker + 共享队列 + 集中代理池 | 500K+ | 监控、分布式追踪 |
| L4 托管平台 | Apify / Bright Data | 任意 | 只管抽取逻辑，基础设施外包 |

决策：需要可靠性和周期运行 → 上 L2；单机吞吐不足 → 上 L3；基础设施负担超过业务价值 → 上 L4。

---

## 9. 代理池架构设计

「池」和「列表」的区别：**池有生命周期管理**——进来要验证、跑着要检测、不行要淘汰、好的要优先。

### 9.1 四模块闭环

```
Fetcher（获取）→ Validator（入池验证）→ Scheduler（评分调度）→ HealthChecker（健康检测/淘汰）
```

### 9.2 Redis 数据结构设计

| 结构 | Key | 用途 |
|---|---|---|
| Sorted Set | `proxy:pool:{task}` | member=ip:port，score=质量分，`ZREVRANGEBYSCORE` 取高分 |
| Hash | `proxy:meta:{ip:port}` | 延迟/成功数/失败数/连续失败/地域 |
| Set | `proxy:blacklist` | 淘汰黑名单，设 TTL 给「复活」机会 |

### 9.3 三个关键工程细节

1. **带权随机而非恒取最高分**：1000 个 IP 里只反复用评分最高的 10 个，那 10 个会先触发目标站频率控制；应 `ZRANGEBYSCORE` 取 top-N 后按 score 权重随机。
2. **评分淘汰**：初始 50，成功 +10（封顶 100），失败 −30，跌破 0 移除（三态：success → recheck → 淘汰）。
3. **分布式借出锁**：`SET proxy:lock:{ip}:{domain} worker_id EX 30 NX`，锁粒度是 ip+domain（同一 IP 同时访问不同站点是允许的），30 秒兜底防止 Worker 崩溃不释放。

---

## 10. 工程化关键点

- **代理**：住宅 IP > 移动 IP > 数据中心 IP；数据中心 ASN 在高防站点默认拉黑；按「会话固定 IP + 15–30 分钟轮换」而非每请求换。
- **限速**：随机延迟（2–10s），遵守 `Crawl-Delay` / `Retry-After`，429 时指数退避：`sleep = min(2 ** retry, 60)`。
- **会话卫生**：同一 IP 内保持 session 一致性，别跳着抓深层页；Cookie + IP + 状态绑定成一体会话。
- **熔断监控**：盯 403/429/503 占比、平均延迟、队列深度，超过阈值降级或停爬。

---

## 11. 合规红线

调研中涉及的下列手段**属于越界甚至违法，本文不收录**：

- 打码平台/OCR/模型破解验证码（reCAPTCHA、滑块、点选、空间推理）
- 逆向风控签名、绕过访问控制
- 绕过登录墙/付费墙获取受保护内容
- 破解滑块轨迹、对抗「无感验证」设备指纹（用于伪装身份）

> 注：§7 JS 逆向技术体系面向「公开数据接口的参数生成逻辑还原」，是正当的数据采集手段；但若目标明确用于绕过风控/登录墙/验证码保护的内容，则越界。

正当爬虫底线：

1. 查 `robots.txt` 和 ToS
2. 只抓**公开数据**
3. 主动声明爬虫身份（UA 带联系方式）
4. 控制频率
5. 不碰个人隐私数据

优先级：**官方 API > 接口逆向 > 无头浏览器**。

---

## 12. 与本项目的对接与落地建议

deepcode-R 内置浏览器已经走在「无头浏览器 + 网络插桩」路线上（`addDocumentStartJavaScript` hook fetch/XHR/WS/SSE + `network` 动作 + 三合一就绪判定）。对照本文框架，可增强的方向：

1. **SSE/WebSocket 帧内容捕获**：当前只记「在途数」，未记「帧内容」。参考 CDP `webSocketFrameReceived` 思路，给长连接做带脱敏的消息摘要，行情/弹幕类站点即可抓到数据本体。
2. **SSR 数据直读**：就绪判定里加一步，优先探测 `<script id="__NEXT_DATA__">` / `window.__INITIAL_STATE__`，直接拿结构化 JSON，比等 DOM 稳定更省 token。
3. **接口发现去重 + 摘要**：`listNetwork` 当前返回原文列表，可参考 §6 按 path 聚合、输出 `response_keys` 摘要，帮模型更快定位「哪个接口里有我要的数据」。
4. **一致性指纹附件**：快照里附带 `navigator.webdriver`、Canvas/WebGL 哈希等一致性信号，让模型在「被验证码/风控拦截」时能自行判断是否暴露了自动化特征（对齐 §5）。

---

## 13. 参考来源

**总览 / 反爬体系**
- [主流反爬虫、反作弊防护与风控对抗手段](https://jishuzhan.net/article/1967453650852823042)
- [Web Scraping Without Getting Blocked (Decodo 四层模型)](https://decodo.com/blog/web-scraping-without-getting-blocked)
- [12 Proven Techniques to Scrape Without Getting Blocked (BrightData)](https://brightdata.com/blog/web-data/web-scraping-without-getting-blocked)
- [How to Scrape SPAs Without Losing Your Mind (MrScraper)](https://web.dev.mrscraper.com/blog/scraping-spas)

**JS 逆向**
- [JavaScript逆向工程核心技术解密（AST/反调试/加密破解）](https://blog.csdn.net/KE17RS/article/details/148505000)
- [Web JS 逆向全体系详细解释](https://jishuzhan.net/article/2033709401350144002)
- [JS逆向爬虫教程与实战技巧](https://juejin.cn/post/7512006380427264039)
- [ast-hook-for-js-RE（浏览器内存漫游）](https://github.com/tlq-github/ast-hook-for-js-RE)

**TLS / HTTP/2 指纹**
- [TLS Fingerprinting 101（四层模型 + JA3/JA4/HTTP2/TCP）](https://github.com/Nika-Proxy/tls-fingerprint-101)
- [HTTP/2 Fingerprinting Explained (ProxyHat)](https://proxyhat.com/blog/http2-fingerprinting-explained-2026-1)
- [浏览器指纹与反爬虫：TLS JA3、HTTP/2指纹原理及绕过](https://blog.axiaoxin.com/post/browser-fingerprint-anti-crawler-bypass/)
- [How to Bypass TLS/JA4 Fingerprinting with curl_cffi](https://proxycove.com/en/blog/how-to-bypass-tls-ja4-fingerprint-curl-cffi-2026)
- [Passive Fingerprinting of HTTP/2 Clients (Akamai, BlackHat)](https://blackhat.com/docs/eu-17/materials/eu-17-Shuster-Passive-Fingerprinting-Of-HTTP2-Clients.pdf)

**反检测 / 指纹**
- [puppeteer-extra无头模式增强：stealth插件原理](https://blog.csdn.net/gitblog_00181/article/details/151857992)
- [The Complete Guide to Puppeteer-Extra Stealth with Proxies](https://proxyhat.com/blog/puppeteer-extra-stealth-proxy-guide)
- [Browser Fingerprinting in 2026（12 向量）](https://empirium.io/blog/browser-fingerprinting-2026)
- [Browser Fingerprinting Explained: Canvas, WebGL, Fonts](https://link.sc/blog/browser-fingerprinting-explained)
- [手把手教你做指纹浏览器（内核定制）](https://openeuler.csdn.net/6a4b3c8e662f9a54cb8a32ff.html)

**实时通道 / CDP**
- [Capture Live WebSocket Data Over CDP (Scrapeless)](https://www.scrapeless.com/en/blog/websocket-scraping-scrapeless)
- [CDP vs BiDi: Browser Automation Protocol Internals for Scrapers](https://evomi.com/blog/cdp-vs.-bidi-browser-automation-protocol-internals-for-scrapers)
- [Playwright mock.md（route/fulfill/routeWebSocket）](https://github.com/microsoft/playwright/blob/main/docs/src/mock.md)
- [Intercept the Hidden JSON API (Scrapeless)](https://www.scrapeless.com/en/blog/network-request-interception-scraping-browser)

**框架 / 架构**
- [Scrapy Architecture overview（官方）](https://doc.scrapy.org/en/master/topics/architecture.html)
- [Scrapy 框架深度解析：五大组件与中间件](https://blog.csdn.net/weixin_44781464/article/details/163323014)
- [爬虫教程：scrapy-redis、集群、aio-scrapy、反检测浏览器](https://blog.csdn.net/freeking101/article/details/107969507)
- [Crawlee 核心架构解析](https://blog.csdn.net/gitblog_01047/article/details/148375089)
- [Crawlee 用 Node.js 构建可扩展采集系统](https://blog.csdn.net/weixin_43114209/article/details/163362016)
- [Web Scraping Architecture Patterns: From Prototype to Production](https://use-apify.com/blog/web-scraping-architecture-patterns)

**代理池**
- [代理IP池搭建：Redis + Golang 四模块](https://blog.csdn.net/Spiderzhaoyi/article/details/163467937)
- [代理池全生命周期维护指南](https://blog.csdn.net/KE17RS/article/details/148381229)
- [分布式爬虫代理IP调度：Scrapy-Redis 集群](http://m.toutiao.com/group/7670074141835411977/)
- [Xproxypool（评分淘汰 + 借出锁）](https://github.com/yehx6/Xproxypool)