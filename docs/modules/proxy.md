# 网络代理（Proxy）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/proxy/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

为 R-CodeCore 提供基于 **mihomo（Clash Meta）内核**的全局代理能力，涵盖：

- **Profile 播种与管理**：用户经 UI 导入「订阅 URL / 手动 YAML / 文件」形成长存 profile（订阅列表），敏感内容加密存储。
- **内核生命周期**：自举下载并校验 mihomo 二进制 → 合成 Clash 配置 → 拉启/停止内核子进程（App 子进程，绑定 `127.0.0.1:7890`）。
- **控制面**：通过 mihomo external-controller REST/WS（secret 鉴权）实现 `status / list_proxies / select / latency / flow`，UI 与模型工具（`network_proxy`）共用同一数据源。
- **路由注入**：把经 App 网络栈的流量（WebFetch/MCP/模型 API/T2I 等）与容器内进程流量统一收敛到同一个 mihomo mixed-port。

设计约束（《网络代理设计 v1.0》）：rootless proot 下内核级 TUN 不可行，App 进程与容器共享 loopback，因此 App 侧用 `ProxyRouteHolder` 注入共享 OkHttp 的 `ProxySelector`，容器侧用 env 注入，两侧都指向同一实例。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/ProxySettingsRepository.kt` | Preferences DataStore 持久化：全局开关、活跃 profile id、已播种 profile 列表（JSON 序列化，敏感内容加密）；**AI 直连分流开关 `ai_hosts_direct`**（`aiHostsDirectFlow` / `setAiHostsDirect`）；含 `revealSecret` 按需解密 |
| `domain/ProxySubscription.kt` | profile 模型：`id/name/kind(subscription|manual)/secretCipher/createdAt`；`secretCipher` 为加密后的订阅 URL 或完整 YAML |
| `domain/ClashProxyManager.kt` | 核心管理器（`@Singleton`）：配置合成、订阅拉取、内核生命周期、REST/WS 控制面、env 注入、运行时状态机；**collect `aiHostsDirectFlow` 同步写路由 holder** |
| `domain/ProxyRouteHolder.kt` | 无依赖的代理路由开关托底：只存 `enabled/proxyAddress` + **`aiHostsDirect`（C5 分流）** 三个 volatile 值，向共享 OkHttp 暴露 `ProxySelector`，避免 OkHttp ↔ 管理器成环 |
| `presentation/ProxyViewModel.kt` | 代理页 ViewModel：播种/预检/开关/展开节点/测速/分组·流量；**暴露 `aiHostsDirect` 状态与 `toggleAiHostsDirect`**；定义 `ProxyPreview`、`ProfileNodesView`、`ProxyGroupsView` 视图模型 |
| `presentation/component/ProxyConfigScreen.kt` | 网络代理配置/导入页：总开关、导入向导（订阅/手动/文件）、预检、profile 列表、展开区（节点列表 + 分组·流量）、**AI 直连分流开关（`AiHostsDirectToggle`）** |
| `presentation/component/ProxyNodesScreen.kt` | 独立「节点管理」整页：状态 Hero、分组/节点双 Tab、搜索、全部测速、点选切换节点 |

## 3. 核心架构与主流程

### 3.1 状态机与数据流

- `ClashProxyManager.state: StateFlow<ProxyRuntimeState>`：`enabled / mode / activeProfileId / mixedHost+Port(7890) / controllerHost+Port(9090) / controllerReachable`。
- 启动（`init`）时：生成/加载 secret → 若上次为启用态先兜底重建 config.yaml 并 `ensureKernelRunning`（内核先起，再置 enabled，避免流量打进无人监听的 7890）→ 之后由 `repository.proxyEnabledFlow.drop(1)` 驱动开关。
- `ProxyRouteHolder` 由 manager 在开关变化时同步写位（`update(true, "127.0.0.1:7890")`），消除「on() 返回后立即发请求仍走直连」的竞态窗口。

### 3.1.1 AI 直连分流（网络层优化 C5）

- **开关**：`ProxySettingsRepository` 持久化 `ai_hosts_direct`（默认关）；`ClashProxyManager` 在初始化时 collect `aiHostsDirectFlow` 同步写 `routeHolder.setAiHostsDirect(direct)`，`ProxyConfigScreen` 的 `AiHostsDirectToggle` 经 `ProxyViewModel.toggleAiHostsDirect` 切换。
- **路由**：`ProxyRouteHolder.selector.select` 在代理启用且分流开启时，对 `KNOWN_AI_HOSTS`（`api.openai.com` / `api.anthropic.com` / `generativelanguage.googleapis.com`，与 `ConnectionPrewarmer` 预热列表一致）做**精确匹配**返回直连，其余 host 仍走 mihomo mixed-port。
- **回退**：所在网络依赖代理才能访问模型接口时，关闭开关即恢复全走代理；用户自定义 base URL 的 host 不在列表内，天然仍走代理。

### 3.2 配置合成（`synthesizeConfig`）

- 用 **SnakeYAML 真实解析**源配置为 Map，剥掉 `OVERRIDDEN_KEYS`（mixed-port/port/socks-port/redir-port/tproxy-port/external-controller/external-ui/secret/allow-lan/bind-address/mode）顶层键，再 dump 回 YAML——避免旧「正则删行」误删块式节点嵌套键导致 mihomo FATAL 秒退。
- 叠加**固定覆盖块**：`mixed-port: 7890`、`allow-lan: false`、`mode: rule`、`log-level: info`（保证内核错误落到 mihomo.log）、`external-controller: 127.0.0.1:9090`、随机 secret。
- 源不是 YAML 映射（订阅回 HTML/裸文本）时退化为仅 `MATCH,DIRECT` 兜底。
- 产物落盘 `filesDir/rcodecore/proxy/config.yaml`。

### 3.3 内核生命周期

1. **二进制自举**（`downloadMihomoIfNeeded`）：按宿主 ABI 挑选 android 构建资产（arm64 / amd64），固定版本 `v1.19.13`，走**直连**（`Proxy.NO_PROXY` 的 `directClient`）从 GitHub 下载 .gz，流式计算 SHA-256 与官方校验和比对，通过后解压到 `configDir()/mihomo/mihomo` 并 `setExecutable`。
2. **启动**（`startKernelProcess`）：`ProcessBuilder` 以 `-d <dir> -f config.yaml` 拉起，stdout/stderr 重定向到 `mihomo.log`；启前校验 config 存在且可解析为 YAML 映射；另起协程 `waitFor()` 监视退出，进程意外退出时清句柄、告警并回读日志尾部。
3. **就绪确认**（`ensureKernelRunning`，在 `kernelMutex` 内串行）：启动后 delay 300ms 检测秒退（读日志定位原因），再轮询 `/configs` 至多 20 次 × 500ms 确认控制面可达。
4. **停止**（`stopKernel`）：`destroy()` → 1.5s 宽限 → `destroyForcibly()`。

### 3.4 控制面（REST / WS）

- `controllerRequest(method, path, body)`：携带 `Authorization: Bearer <secret>` 打 `http://127.0.0.1:9090`；失败返回 null 并视为未运行，成功/失败均置 `controllerReachable`。
- `/proxies` → `fetchProxiesSnapshot`：按 `GROUP_TYPES`（Selector/URLTest/Fallback/LoadBalance/Relay/Direct/Reject 等）过滤出分组树（type/now/all/delay）。
- `PUT /proxies/{group}` → `selectProxyNode`：切分组内选中节点。
- `GET /proxies/{node}/delay?url=generate_204&timeout=5000` → `testNodeLatency`：节点测速（只走内核真实出口，不做 App 直连近似）。
- `WS /traffic` → `trafficFlow()`：callbackFlow 建一条 WebSocket 推流 `ProxyTraffic(up, down)`，collect 取消即关闭。

### 3.5 路由与 env 注入

- **App 侧**：共享 OkHttp 注入 `ProxyRouteHolder.selector`（ProxySelector）——未启用走 `Proxy.NO_PROXY`；启用时除 loopback/内网（`isNoProxy`：127.*/10.*/172.16-31.*/192.168.*/*.local）外走 `127.0.0.1:7890`。
- **容器侧**：`exportContainerEnv()` 返回 `http_proxy/https_proxy/all_proxy=127.0.0.1:7890` + `no_proxy` 保护（loopback/内网/RCB_BRIDGE/工作区同步）+ `CLASH_MIXED_PORT/CLASH_CONTROLLER_ADDR/CLASH_CONTROLLER_TOKEN`，供容器构建 env 并入。

### 3.6 订阅拉取

`fetchSubscriptionYaml` 用 `User-Agent: clash.meta`（该订阅商按 Clash 系 UA 白名单放行，非 Clash UA 回 406）拉取远端 YAML。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `ClashProxyManager.on(profileId, inlineYaml): String` | 拉起代理：从 profile 或 inline YAML 合成配置 → 落盘 → 启内核 → 置开关 → 写位；返回 "ok" 或错误描述 |
| `ClashProxyManager.off()` | 停内核 → 关开关 → 写位 |
| `ClashProxyManager.isEnabled()` / `exportContainerEnv()` / `controllerAddress()` / `controllerSecret()` | 供容器构建与上层读取的同步接口 |
| `ProxySettingsRepository` | `subscriptionsFlow / proxyEnabledFlow / activeProfileIdFlow / aiHostsDirectFlow / upsertSubscription / deleteSubscription / revealSecret / setAiHostsDirect` |
| `ProxyRouteHolder.selector` | 共享 OkHttp 的 ProxySelector 注入点；`aiHostsDirect` 分流开关位（`setAiHostsDirect` 由 manager 写入） |
| `ProxyViewModel` | `toggleEnabled / activate / delete / runPreview / inspectProfile / testProfileLatency / openGroups / closeGroups / selectGroupNode / commitProfile / toggleAiHostsDirect` |
| `ProxyConfigScreen` / `ProxyNodesScreen` | Compose 页面（配置/导入页 + 节点管理页） |

依赖的外部模块：core.security（`CredentialEncryptor`）、core.util（`FileLogger`）、容器引擎（`exportContainerEnv` 供 `LinuxContainerEngine.buildContainerEnv` 并入）、模型工具 `network_proxy`（与 UI 共用 manager/repository 同一链路）。

## 5. 关键设计点与约束

- **固定的端口与 secret**：mixed-port `7890`、external-controller `127.0.0.1:9090`，secret 运行时生成落盘 `secret` 文件（24 位小写字母+数字），跨进程一致。
- **必须内核先起再置 enabled**：否则共享 OkHttp 会把流量打进没人监听的 7890（`Failed to connect to /127.0.0.1:7890`）。
- **直连自举**：内核二进制属基础设施，下载强制绕过代理，避免「代理未起/代理本身被墙」影响下载。
- **独立 Holder 避免依赖环**：`ProxyRouteHolder` 无任何依赖，由 manager 写入、OkHttp 只读，单例零环。
- **no_proxy 保护**：loopback 与内网（含 172.16/12 容器网段）保持直连，避免把自己服务代理出去。
- **AI 直连分流（C5）**：仅对 `KNOWN_AI_HOSTS` 精确匹配直连（避免误伤同域其它服务），开关持久化且默认关（保持全走代理）；直连不通的网络关闭开关即回退代理。与 `core/network/ConnectionPrewarmer` 的预热 host 列表保持一致。
- **测速语义对齐 Clash**：只走内核真实出口的 generate_204 延迟；被测配置非当前运行态时临时启用、测完 `restoreAfterTest` 恢复原状态。
- **内核版本固定**：`MIHOMO_VERSION=v1.19.13` + 官方 SHA256 硬编码，避免运行时探测 GitHub API 引入不确定性；旧版本资产长期保留故固定版本安全。

## 6. 维护与扩展指引

- **升级内核版本**：改 `MIHOMO_VERSION`，同步替换 `MIHOMO_ARM64_ASSET/AMD64_ASSET` 及其 SHA256（可从发布页 sha256sums 获取）。
- **新增 profile 类型**：在 `ProxySubscription.KIND_*` 常量与 `ProxyConfigScreen.ImportEditor` 的导入模式中扩展；保存逻辑在 `commitProfile` 按 kind 决定 `secret` 内容（订阅存 URL、手动存 YAML）。
- **新增控制面能力**：在 `ClashProxyManager` 加 `controllerRequest` 封装并暴露到 `ProxyViewModel`；UI 与模型工具复用同一方法即可。
- **配置安全**：凡写入 `secretCipher` 的内容必须经 `CredentialEncryptor.encrypt`；界面/工具输出一律不回显明文，只展示脱敏信息。
- **规则变更**：修改 `OVERRIDDEN_KEYS` / `GROUP_TYPES` / `isNoProxy` 网段时，需同步更新 `ProxyConfigScreen` 的 `FIXED_OVERRIDE_HINT` 文案与 `ProxyViewModel.dangerScan` 提示，保持用户可见行为一致。
- **测试建议**：覆盖「启动自动恢复（config 缺失重建）」、`synthesizeConfig` 对块式/流式 YAML 与 HTML 回退、口令场景、内核秒退日志定位、WS /traffic 推流启停。
