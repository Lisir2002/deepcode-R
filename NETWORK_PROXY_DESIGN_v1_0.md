# 网络代理工具（network_proxy）设计文档 v1.0

> 目标：让 AI 在容器内**自助、可控地启用/切换网络代理**，解决「避墙 / 回连」类场景—访问被墙的 API、Git、pip、npm、WebFetch 等外部服务时，选择经上游订阅出去。
>
> **引擎**：mihomo（Clash Meta 内核，https://github.com/MetaCubeX/mihomo），**纯 mixed-port（HTTP+SOCKS5 同端口）模式**运行在容器内，不建 TUN。设计深度借鉴 **ClashMetaForAndroid**（https://github.com/MetaCubeX/ClashMetaForAndroid，下文 CMA）与 mihomo 的核心机制（见 §10 深借对照表）。
>
> 仍是**应用层代理**，非内核级 TUN-VPN。定位详见 §1。

---

## 0. 现状盘点（对齐「已有 vs 缺口」）

| 能力 | 现状 | 缺口 |
|---|---|---|
| 容器命令 / 环境注入 | `LinuxContainerEngine.buildContainerEnv()` 已注入 PATH、HOME、`RCB_BRIDGE_ADDR/TOKEN` 等 | 缺代理 env 并入 |
| 包预取/安装 | `TerminalBundles`（`apk add`）+ `ParallelPrefetchManager` + `GlobalInstallArchiveStore` | 缺 mihomo 二进制下载与启动管理器 |
| 护栏工具 | `StorageTool`（`ASK` + ZTH 确认卡）刚上线；`ToolPermissionPolicy.ASK` / `ToolCapability` 体系成熟 | 缺 `network_proxy` / `MODIFY_NETWORK` |
| 工具注册 | `AgentModule.provideToolRegistry()` 逐工具注册 | 缺 `network_proxy` |
| 凭据/密钥加密 | `core/security/CredentialEncryptor.kt` + DEK 链路已用于 git/浏览器凭据 | 复用给订阅 token / external-controller secret |
| App 网络栈 | OkHttp 驱动的工具多（`WebFetchTool`/`McpManager`/`ModelApiService`/T2I） | 缺统一 `ProxySelector` 注入 |
| 内置浏览器 | 模型经共享 WebView 会话浏览，用户侧内置浏览器同是 WebView | WebView 无公开代理 API（§4.3） |

---

## 1. 形态与硬约束

- 本 App 是 **rootless proot**，容器共享宿主网络命名空间，无 netns、**不能创建 `/dev/net/tun`** → 内核级 WireGuard/TUN-VPN 不可行。
- mihomo 的**一体两面恰好化解**：
  - ❌ **TUN 模式**（CMA 默认整机接管）在 rootless 下不可用；
  - ✅ **纯 mixed-port 模式**：mihomo 开本地 `127.0.0.1:<mixed>` 的 HTTP+SOCKS5 混合入站，不需要 TUN/root/权限，proot 内直接运行。容器进程与 App 共享 loopback，**同一端口同时服务两侧**。
- 因此「VPN」= 容器内常驻 mihomo（应用层代理），`mixed-port` 为统一出口；订阅即 Clash 配置，路由由 mihomo 规则在 rule/global/direct 三种 mode 下完成。

---

## 2. 数据层

### 2.1 `ProxySubscription`（上游来源：订阅 / 手动 / 临时）
```
id            string
name          string
kind          enum      // subscription | manual | temp
url           string?   // 订阅 URL（含自带 token 参数）
rawYaml       string?   // 手动粘贴 / 临时 inline 的 Clash 配置
refreshSecret string    // external-controller secret（随机生成，CredentialEncryptor 加密）
```
- 存储：`ProxySettingsRepository`（DataStore，仿 `ContainerSettingsRepository`）。
- **Token 策略**：订阅 URL 里的 token 仅存本机，**绝不写死进代码/文档**，回显脱敏；接入凭据加密。

### 2.2 配置合成管线（深借 CMA ProfileManager → ProfileProcessor）
订阅/手动 YAML 不是直接被 mihomo 加载，而是**先归一化再与「覆盖块」合并**：
```
订阅/手动YAML
  → 归一化（补默认、校验、把“删掉/夹带”的危险段剥掉）
  → ⊕ 固定覆盖块（我们自己的字段，见下）
  → 落盘 ~/.rcodecore/clash/config.yaml
  → mihomo 启动/`/restart` 加载
```
**固定覆盖块**（合并时「我们优先」，订阅更新不会冲掉，等价 CMA Override）：
- `mixed-port: 7890`、`allow-lan: false`、`bind-address: 127.0.0.1`
- `external-controller: 127.0.0.1:9090`、`secret: <refreshSecret>`
- 兜底规则：`IP-CIDR,<内网/loopback>,DIRECT` 兜底，防止把自己服务代理出去。
- 默认 `mode` 初始为订阅自身，工具可切（§3）。

### 2.3 `ClashProxyManager`（`@Singleton`，生命周期仿 `RcbBridge`）
- `ensureActiveConfig()` / `start()` / `stop()` / `isRunning()`
- `exportContainerEnv()`：`http_proxy/https_proxy/all_proxy → http://127.0.0.1:7890`、`no_proxy` → 供 `buildContainerEnv()` 并入。
- `controller()`：prefab 的 mihomo REST+WS client（§5）。
- 二进制：按容器 profile 取 mihomo `linux-arm64`/`linux-amd64`（后者对应 QEMU x86_64），sha256 校验，缓存 `~/.rcodecore/clash/`，走 `ParallelPrefetchManager` 机制。

---

## 3. 工具层 `network_proxy`（把 CMA 的 UI↔core 交互换成 Agent 工具）

- `name = "network_proxy"`，`permissionPolicy = ASK`，`capabilities = { MODIFY_NETWORK }`（新增 capability）。
- 注册进 `AgentModule.provideToolRegistry()`，与 `device_storage` 同步骤。
- **交互模型（一次播种后模型接管）**：长存 profile 由用户从导入页「播种」（扫码/粘贴 token → 加密 → 可选启用，见 §11/§12）；此后**新增/切换/测速/监控/关停全由模型驱动工具接管**（`status/list_*/latency/on/select/off`），不要求用户进页面。约束：模型**不能凭空新增长存订阅**——`on` 只能引用已播种 profile，或使用**临时 inline YAML**（仅本次会话、不入订阅列表，见 §6）。播种是唯一「用户必须出面的点」，之后工具为主。

| action | 含义 | 后端 | 默认权限 | 记忆性 |
|---|---|---|---|---|
| `status` | 内核/活跃订阅/分组/当前节点/延迟/mode/生效范围 | REST `GET /configs`,`GET /proxies` | AUTO | ✅ |
| `list_subscriptions` | 列出订阅（脱敏 URL） | 本地 repo | AUTO | ✅ |
| `list_proxies` | 列 proxy-groups 及节点（读 mihomo） | REST `GET /proxies` | AUTO | ✅ |
| `latency` | 测单节点/整组延迟 | REST `GET /proxies/:n/delay` | AUTO | ✅ |
| `on` | 启用（选订阅 → 合成 → 启动内核） | 管线+进程 | ASK | 可记忆单订阅 |
| `off` | 停止内核、清代理 env | 进程 | ASK | ✅ |
| `select` | 分组内切节点，或切换 `mode`（rule/global/direct） | REST `PUT /proxies/:g`、`PATCH /configs` | ASK | 可记忆单节点 |
| `config` | 管理订阅/secret/override | 管线 | ASK | 不可记忆 |
| `flow` | 实时流量/连接数（**WS 推流**，非轮询） | WS `/traffic`,`/connections` | AUTO(读) | ✅ |

- **权限分层**：`on`（启动内核/引新出口）最重；`off/config` 重；`select`（已开内核内微调，可记忆单节点）次之；`status/list/latency/flow` 只读自动。
- **mode 语义（深借思考）**：模型要「换出口 IP」时须 `select {mode:global}` 或切到 global 分组；`rule` 模式可能把目标命 DIRECT/固定节点（§10.4）。默认试 `rule`，明确要换 IP 才让模型提 `mode:global`，确认卡会展示。
- 确认卡一律复用 `ZthConfirmationCardManager` + sentinel + SwipeToConfirm；`temporary` 型订阅仅本次会话。

---

## 4. 生效落点（统一出口 = mihomo mixed-port）

### 4.1 容器进程（P0 主链路）
- mihomo 跑容器 `127.0.0.1:7890`；`buildContainerEnv()` 在启用时注入（**新进程生效**，复用「新终端生效」UI 提示）：
  - `http_proxy` / `https_proxy` / `all_proxy` → `http://127.0.0.1:7890`
  - `no_proxy`：loopback、容器内网、工作区、FTP/SFTP 同步本机端、`RCB_BRIDGE_ADDR`。
- 容器内 `git`/`pip`/`npm`/`curl`/WebFetch 自动走 mihomo，由 Clash 规则决定出口；「避墙」路径：`status` → `list_proxies` → `select` →（不达）→ 重试；要换 IP → `select mode:global`。

### 4.2 App 网络层（P1）
- 给共享 OkHttp（`WebFetchTool`/`McpManager`/`ModelApiService`/`ImageGenerator`/T2I）注入 `ProxySelector` → `127.0.0.1:7890`（共享 loopback 直连同一 mihomo），读 `ClashProxyManager`，开关瞬时可逆，NO_PROXY 在 selector 集中维护。

### 4.3 内置浏览器（WebView）
- **硬限制（明示）**：Chromium WebView 无公开 per-WebView 代理 API；`ProxyController` 隐藏 API 被 hidden-API 白名单拦；写 `Settings.Global.HTTP_PROXY` 需 `WRITE_SECURE_SETTINGS`（`targetSdk=28` 非特权）。
- 能力边界：经 App 网络栈的（`WebFetchTool` 等）走 §4.2 全额支持；WebView 直接渲染的网页**暂不代理**，列为已知限制；保留 two workaround（经容器互通 / 未来升级 targetSdk+授权）。

---

## 5. mihomo REST / WebSocket 控制面（model 自助的技术底座）

`external-controller`（`127.0.0.1:9090`，`secret` 鉴权，`Authorization: Bearer <secret>`）：

| 端点 | 方法 | 用途 |
|---|---|---|
| `/configs` | GET / PATCH | 读/改运行配置（切 `mode`） |
| `/proxies` | GET | 全量分组+节点 |
| `/proxies/{group}` | GET / PUT | 切节点（PUT `{"name":…}`） |
| `/proxies/{node}/delay?url=&timeout=` | GET | 测延迟 |
| `/providers/proxies` | GET | proxy-provider 动态列表 |
| `/traffic`、`/connections` | **WS 推流** | 实时流量/连接（零轮询） |
| `/logs`、`/memory` | WS/GET | 诊断 |
| `/restart` | GET/raise | 配置重载 |

实现：`ClashProxyManager.controller` 基于 OkHttp 封 REST + OkHttp-WebSocket 封装 `/traffic`、`/connections`。`flow` action 走 WS 推流（对齐 ha-mihomo-ctrl 的零轮询做法），避免低配机轮询瞬耗。

---

## 6. 上游来源（订阅 / 手动 / 临时 + mode 语义）

- 订阅：设置页粘贴订阅 URL（可多条），经 §2.2 管线合成；模型 `list_subscriptions` 只读选用。测试订阅示例 `https://api2.riolu01.link/RioLU/system/api/v1/client/subscribe?token=…`（token 仅本机）。
- 手动：设置页粘贴 Clash YAML。
- 临时：模型确认后 inline YAML，仅本次会话。
- 冲突：同一时刻运行中的内核对应**一个活跃配置**；切换需 `off` 再 `on` 或 `config` 换活跃。
- **出口 IP 语义**：默认 `rule`；明确要「整机/全局走某节点」→ `select {mode:global}`；完全不用 → `off`(等效 direct)。

---

## 7. 实现清单（按批次）

### P0（容器避墙 + 引擎运行）
- [ ] `ProxySettingsRepository`（订阅 + 活跃 + secret 加密）
- [ ] 配置合成管线 + 固定覆盖块 + 归一化校验（拆危险段）
- [x] `ClashProxyManager`：合成→启动/停止 mihomo（arm64/amd64 预取+sha256）
- [ ] `ToolCapability.MODIFY_NETWORK` + `NetworkProxyTool`（`network_proxy`，ASK）
- [ ] `AgentModule` 注册
- [ ] `buildContainerEnv()` 注入代理 env
- [ ] 独立「配置导入/管理页」（订阅/手动/文件导入向导 + 编辑器 + 覆盖块展示，见 §11）
- [ ] 确认卡接线（`on/off/config`）

### P1（模型自助切节点 + App 出口）
- [ ] mihomo REST 封装（`list_proxies`/`select`/`mode`/`latency`）
- [ ] 共享 OkHttp `ProxySelector` 注入（WebFetch/MCP/模型 API/T2I）
- [ ] `flow`：WS `/traffic`,`/connections` 推流封装
- [ ] geo 资产（geoip/geosite）按需预取（订阅引用时才取）

### P2（体验）
- [ ] agent「目标不可达自动请求开代理并探到可用节点」会话级确认卡模板
- [ ] 多订阅一键切换 + 记忆「始终允许某订阅/节点」

---

## 8. 风险与兼容（明示）

- **`targetSdk=28`**：豁免后台限制——mihomo 常驻无障碍、WebFetch/OkHttp 注入无障碍；但 `WRITE_SECURE_SETTINGS` 拿不到 → WebView 全局代理保持「受限」。
- **mihomo 二进制**：约 15–30MB，冷启动一次性下载；按 profile 取 arm64/amd64；静态 Go，Alpine 可执行。
- **订阅可用性**：上游失效时 `status` 报错并安全回退，保留上次可用配置（不自动清）；合并失败不覆盖用户 override。
- **proot env 生效语义**：proxy env 只对**新进程**生效，旧 shell/既有连接需重开；mihomo 本身是常驻进程，不随 shell 生命周期。
- **临时 YAML/Token 风险**：确认卡展示完整目标；仅本机、加密、脱敏。
- **REST secret 泄漏面**：只绑 loopback + secret；容器内既有进程同 netns、本属同信任域（`RcbBridge` 已注入 token），保持「最小权限 + 关键操作宿主确认」。

---

## 9. 验收标准（含测试订阅）

1. 设置页粘贴测试订阅（`api2.riolu01.link/…`+token）后，容器内新终端正常访问被墙站点；`status` 显示分组/节点/延迟/mode；`no_proxy` 命中本地服务不被代理。
2. `network_proxy on/off/config` 唤起确认卡；`status/list_subscriptions/list_proxies/latency/flow` 自动且 token 脱敏；`flow` 为 WS 推流非轮询。
3. `select` 切节点、`select {mode:global}` 换出口 IP 均确认后生效（容器内 `curl` + WebFetch 一致）。
4. 手动粘贴 Clash YAML 可用；临时 inline 仅本次会话，`off`/切回订阅即回收。
5. 订阅失效时 `status` 报错并安全回退，不清空用户配置；订阅更新不冲掉固定覆盖块。
6. WebView 直接网页代理列入已知限制，不破坏现有浏览行为。

---

## 10. 深度借鉴对照表（CMA / mihomo → 本项目）

| # | mihomo / CMA 机制 | 参考来源 | 本项目映射 | 可行性 |
|---|---|---|---|---|
| 10.1 | Profile 管线 `ProfileManager`→`ProfileProcessor`→激活（订阅下载→归一化→激活） | CMA Profile Management | §2.2 配置合成管线 + `ClashProxyManager` | ✅ 直接移植 |
| 10.2 | Override / 自定义覆盖（订阅更新不冲掉用户字段） | CMA `OverrideSettingsActivity` | §2.2 固定覆盖块（mixed-port/secret/DIRECT 兜底） | ✅ 直接移植 |
| 10.3 | `external-controller` REST + secret 鉴权 | mihomo REST API | §5 `ClashProxyManager.controller`（OkHttp REST+WS） | ✅ 直接移植 |
| 10.4 | 三 mode（rule/global/direct）；换出口 IP 需 global | mihomo 运行模式 | §3 `select {mode}` + 确认卡 | ✅ 语义移植 |
| 10.5 | proxy-groups 五类型 + health（select/url-test/fallback/load-balance/relay；`generate_204`、`expected-status`、`lazy`） | mihomo Proxy-Groups | §3 `latency`/`select` 依据 | ✅ 语义移植 |
| 10.6 | `/traffic` `/connections` WS 推流（零轮询） | ha-mihomo-ctrl | §5 `flow` action（OkHttp-WebSocket） | ✅ 移植 |
| 10.7 | proxy-provider 外部动态节点池 | mihomo proxy-provider | §6 订阅兼容（`use:` + `/providers/proxies`） | ✅ 移植 |
| 10.8 | JNI + gomobile libcore 把 core 嵌进 App 进程 | CMA Clash Core / `core` 模块 | **改**：容器内跑 mihomo 二进制（同引擎不同承载） | ⚠️ 架构替换 |
| 10.9 | `VpnService`/`TunService` 系统整机接管 | CMA Android Integration | **不采用**：rootless 无 TUN；改 mixed-port + env/ProxySelector 收敛 | ⛔ 不可行→替代 |
| 10.10 | geo 资产（geoip.metadb/geosite.dat/ASN.mmdb）与 web dashboard | CMA / mihomo | geo 按需预取；dashboard 不进本期（agent 用 REST） | ⚠️ 裁剪 |

> 引用的外部机制以 mihomo 官方仓库与 CMA 项目为准（见文档头部链接）。设计时对「能直接移植」与「必须改」作了边界划分，避免把 CMA 的宿主级能力误搬进 rootless 容器。

---

## 11. UI 设计：配置导入/管理页（独立页面）

> **交互原则（前置）**：本项目里 `network_proxy` **工具**才是「模型可交互」的第一交互面（§3）；设置/导入页只是**辅助入口**——它只负责两件 UI 独有的事：① 注入只有用户才持有的敏感素材（订阅 URL/token、手动 YAML）；② 给非工具的全局开关/覆盖块一个落脚点。日常启用/切换/测速/监控全部由**模型驱动工具**完成，不要求用户进页面。二者是同一运行链路的两个视图（§11.6），主从关系以工具为准。

对标 CMA 专门 Profile 页（多来源导入 + 编辑器），独立一屏，避免把导入/校验/多配置压扁到设置项里。

### 11.1 入口与导航
- 入口：设置 → 网络代理 → 「配置」卡片（独立 `ProxyConfigActivity`/Compose Screen）。
- 顶栏含「开启代理」主开关 + 当前活跃配置小标签（名/来源 kind/更新时间/校验状态）。
- 底部：`+ 导入配置` 主按钮；列表每条带「启用 / 编辑 / 更新(订阅) / 停用 / 删除 / 切换为活跃」。

### 11.2 列表（多 profile）
| 字段 | 内容 |
|---|---|
| 名称 | 用户可读名；订阅/手动有远端或本地说明 |
| 来源 | `subscription` / `manual` / `temp` 图标与提示 |
| 状态 | 就绪 / 解析失败 / 更新中 / 需确认；占用 ⚠️ 标 |
| 最后更新 | 订阅最后抓取时间 |
| 活跃态 | 高亮「当前」 |

### 11.3 导入向导（复用 CMA 多来源导入）
步骤：来源选择 → 输入 → 预检 → 确认保存。

1. **来源选择**：订阅 URL / 手动粘贴 YAML / 从文件导入（`ActionGetContent` `.yaml`/`.txt`/分享）。
2. **输入**：订阅填 URL；手动贴文本（实时行数与语感）；文件导入回填编辑器。
3. **预检（写卡前必做）**：
   - 远端可拉取（订阅）+ 解析校验；归一化失败给出**具体行级错误**。
   - 概览展示：节点数、proxy-group 数、mode、是否引用 `rule-providers`/geo（决定是否触发 §P1 geo 预取）。
   - **危险段扫描**：夹带/被篡改的 `listen`/`redir-port`/`dns`/`external-ui`/任意 `script` 字段→红色警告并归一化剥离（见 §2.2）。
4. **确认保存**：写本地 + 加密 token；失败/校验不过不落盘。

### 11.4 编辑器（ProfileEditor 类比）
- 文本编辑 YAML + 关键字高亮 + 保存前再校验；仅编辑**订阅自身的可改部分**。
- **固定覆盖块只读展示**（`mixed-port/external-controller/secret/DIRECT 兜底`），用户可开/关某项，但值由系统持有（不在编辑器里写成明文 token）。
- 订阅 **token 不回显明文**；编辑区不出现 `secret`/URL token，仅显示脱敏。

### 11.5 订阅更新
- 手动「更新」（拉最新 → 再次归一化+预检 → 若跑在旧上则提示「更新需重启内核」或热重载）。
- 可选定时自动更新；失败保留上次可用配置并 `status` 报错（§8）。

### 11.6 与工具/护栏的联动
- 「开启」「切换为活跃」「更新」均触发同一套 `ZthConfirmationCardManager` 确认（服务端一致性，UI 只是入口）；agent 的 `network_proxy on/config` 落到同一运行链路，避免「页面开、代码关」两套实现。
- 从页面导入成功后，直接可作为 agent `list_subscriptions` 的内置项。

---

## 12. 导入向导：分步线框（wireframe）

四步向导 + 顶部步骤指示器 `1来源 → 2输入 → 3预检 → 4完成`；左右底部各放"返回 / 下一步"。每步组件、状态、错误处理如下。

### Step 1/4 —— 来源选择
```
┌─────────────────────────────────────────┐
│ 来源（3 选 1，互斥 RadioCard）            │
│  ○ 订阅 URL       [url]      （推荐）      │
│  ○ 手动粘贴 YAML   [keyboard]             │
│  ○ 从文件导入      [folder; 分享]          │
│  ─────────────────────────────          │
│  [返回]                         [下一步]    │  （未选则 [下一步] 禁用）
└─────────────────────────────────────────┘
```
- 选中后不自选进入下一步，仅切 Step2 输入形态；再点一次同项可取消。
- 订阅项右上"（推荐）"角标；文件项提示支持 `.yaml/.txt/任意文本`。
- 辅助说明一行：token/内容仅本机、加密、不回显。

### Step 2/4 —— 输入（按 Step1 来源三分支）

**A. 订阅 URL**
```
┌ 订阅配置 ─────────────────────────────┐
│ 名称      [ 由域名自动填充，可改        ] │
│ 订阅 URL  [ https://…/subscribe?token= ] 〔粘贴〕〔扫码〕
│ 提示: token 加密存储，界面不显示明文     │
│  ────────────────────────             │
│  [测试连接]   → 结果行: ✓ 可拉取(未保存) / ✗ 原因
│  [返回]                    [下一步·预检]  │
└──────────────────────────────────────┘
```
- `订阅 URL` 必填；校验：`http(s)://`、域名合法；非法即时行内红字。
- 〔扫码〕调用相机解析 `clash://` / `surgio://` / 纯 URL（复用既有扫码能力）。
- 「测试连接」只拉取+解析**不落盘**；转瞬忙态，结果只读展示。

**B. 手动粘贴 YAML**
```
┌ 手动配置 ─────────────────────────────┐
│ [ 代码编辑区 ]  行号│YAML高亮│软换行      │
│   proxies:                            │
│     - name: "HK"                      │
│       type: trojan …                  │
│ ──状态条── 解析中… / ✓ 节点12 分组3 /    │
│            ✗ YAML 第N行: <原因>         │
│ [返回]                    [下一步·预检]  │
└──────────────────────────────────────┘
```
- 编辑区行数与光标位置；解析实时（去抖 ~400ms），错误行级提示并可点行跳到编辑区。
- 空/非法 YAML 时 [下一步] 禁用。

**C. 从文件导入**
- 点选 `ActionGetContent`（拦 `.yaml/.txt` 及任意）；选中后回填到 **B 的同一编辑区**，tab 标题显示文件名+大小。
- 与 B 成功路径合并；之后流程与 B 相同。

### Step 3/4 —— 预检（写卡前必做）
```
┌ 预检结果 ─────────────────────────────┐
│ ✓ 解析 OK  概览: 节点12 · 分组3 · mode: rule
│   ⚠ 引用 geo/rule-providers → 「需预取 geo」
│ ── 危险段扫描 ──────────────────      │
│   ⛔ dns / listen / external-ui / script
│      →「将从配置中剥离(含示例: listen 0.0.0.0)」
│ ── 覆盖块(系统持有·只读) ───────────    │
│   mixed-port 7890 · ext-controller 127… │
│   secret •••（掩码）· DIRECT 兜底 3 条    │
│ [返回编辑]          [仅保存] [保存并启用] │
└──────────────────────────────────────┘
```
- 失败态：错误面板（非折叠）列出 `行:列 → 原因`；「仍在编辑器编辑」返回 Step2B，**不落盘**。
- 「保存并启用」= 保存后立即 `on` → 触发 ZTH 确认卡（展示目标摘要）再启动；「仅保存」= 存入成为可选 profile，不启用。
- 危险段扫描命中任一 → [保存并启用] 需二次确认（额外红卡），可走「仍要保存」。
- 保存失败 → inline Snackbar（含原因），**停留在 Step3**，不前进。

### Step 4/4 —— 结果
```
┌ 完成 ────────────────────────────────┐
│ ✓ 已保存「HK · api2.riolu01.link」     │
│   （若”保存并启用”→ 已作为活跃 profile） │
│ [ 开启代理 ]  灰色(已启用) / 主按钮(未启用)
│ [ 查看活跃 ]  [ 回配置列表 ]            │
└──────────────────────────────────────┘
```
- 从 Step4 开启同样走 ZTH（与 §11.6 同链路）；关闭模态回到列表高亮新活跃项。

### 与护栏/一致性 & 安全
- 全流程不出现订阅 URL 明文 token、`secret` 明文；只显示脱敏。
- 预检/保存/启用全部落到「配置合成管线」（§2.2）+ `ClashProxyManager` 同一运行链路，与 agent 的 `network_proxy on/config` 共用确认卡与状态机。
- 一个 profile 导入失败不影响其它；保留上次可用配置（§8）。

---

## 附：与本仓库既有边界的一致性

- **护栏风格**与 `StorageTool`（ASK + ZTH 确认卡 + capability 隔离）一致；`MODIFY_NETWORK` 类比 `ACCESS_DEVICE_STORAGE`。
- **生命周期/承载风格**仿 `RcbBridge`（`@Singleton` + StateFlow + 环境变量注入）。
- **环境注入位**复用 `LinuxContainerEngine.buildContainerEnv()`（与上版存储共享 banner 同一处）。
- **凭据**复用 `core/security/CredentialEncryptor.kt`。