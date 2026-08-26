# 内置浏览器升级设计（browser-upgrade-design）

> 状态：✅ 已评审（refactor/browser-upgrade 分支已全量落地实施，落地细节见 `docs/modules/browser.md`）
> 日期：2026-08-26
> 关联模块：`feature/browser`、`feature/agent/domain/tool/browser`
> 关联文档：`docs/modules/browser.md`、`app/src/main/assets/docs/*`

## 1. 背景与目标

内置服务浏览器同时服务**用户侧**（`ServiceBrowserScreen`）与**模型侧**（`BrowserAgentTool` 驱动同一共享 WebView）。本次升级两条需求：

- **需求一「更像浏览器」**（副线，用户侧体验补齐）
- **需求二「适配大模型」**（主线，模型操作更流畅便捷、交互更统一）

决策：需求二为主线优先，需求一为副线同批实施；落地形态为**一次性大改造**（`refactor/browser-upgrade` 分支，分阶段验证后合回 main）。

## 2. 现状与差距

已有底座（本轮不动）：共享会话、动态数据捕获（fetch/XHR/WS/SSE 插桩）、协议白名单、敏感脱敏、WebView 代理接管、多标签、下载、登录代填、用户接管（park-and-resume）。

### 2.1 模型侧摩擦（R2 主线）

| 差距 | 现状 | 后果 |
| --- | --- | --- |
| 快照 token 重 | 每个动作都返回全量快照（≤120 元素 JSON + ≤8000 字 page_text） | 长任务 token 成本高、响应慢 |
| 无视口/可操作性信息 | 快照不标记元素是否在视口、可见性、遮挡 | 模型常去点不可见/被遮挡元素 |
| element_id 稳定性 | `data-rcb-id` 计数器打标，SPA 重渲染替换节点后失效 | 模型拿到 stale id 操作报错 |
| 交互不统一 | 各动作返回结构不一，无统一「观察→行动→验证」循环 | 模型心智负担高、容易来回试错 |
| 无增量/事件感知 | 页面变化靠反复 snapshot 轮询 | 响应慢、占用上下文 |

### 2.2 用户侧缺失（R1 副线）

- 浏览增强：历史记录、收藏夹、新标签页/主页、页内查找
- 内容管理：下载管理 UI（现仅模型可列下载）、凭据管理 UI、分享/复制链接
- 隐私与适配：无痕模式、桌面版切换、缩放控制

## 3. 设计方案

### R2 模型侧（主线）

#### R2.1 快照 token 瘦身

- **快照分级**：`summary`（默认）/ `standard` / `full` 三级，由工具参数 `snapshot_level` 控制：
  - `summary`：只含标题/URL/标题大纲 + 每控件一行的紧凑摘要（当前 `controls_summary`），**不含** page_text 与完整元素 JSON；
  - `standard`：`summary` + 完整元素 JSON（不含 page_text）；
  - `full`：`standard` + page_text（截断上限保留）。
- **增量快照**：controller 缓存上次快照（URL + DOM 指纹 + 元素 id 集合），写操作后返回 `delta`（新增/消失/变化的元素清单 + 文本变化摘要），模型默认只读 delta；首次或导航后返回全量。
- **按需取文**：新增 `page_text` 动作单独取正文，模型不需要时不再随快照返回。

#### R2.2 元素稳定性 + 可操作性

**三级定位策略（主：CSS 绝对路径；兜底：data-rcb-id + 语义回退；兼顾：语义定位）**

- **主定位 = CSS 绝对路径**：快照为每个元素生成稳定路径（如 `html > body > div#main > form > div:nth-child(2) > input`）。SPA 重渲染即使替换了节点，只要 DOM 结构位置不变路径仍可命中，比仅靠打标 id 持久；
- **兜底 = data-rcb-id + 语义回退**：`[data-rcb-id]` 保留做快速精确定位；id 失效时回退按 `kind + label/name + type` 语义模糊匹配一次；
- **兼顾语义定位**：参考 Playwright 生成 `role=<role> name=<可访问名称> index=<n>` 语义描述符，CSS 路径与 id 都失效时作最终兜底，同时便于模型理解元素。
- **定位解析**：controller 维护 id → CSS 路径/语义描述 的 locator 映射（随快照刷新）；快照每元素返回 `id` + `locator`（CSS 路径）+ `semantic` 三字段；工具 `element_id` 参数接受三者任一，解析顺序：`[data-rcb-id]` 直查 → locator 映射按 CSS 路径解析 → 语义匹配 → 返回可恢复错误。

**可操作性标注**

- 快照每个元素补字段——`in_viewport`、`visible`、`needs_scroll`、`overlapped`（基于 `getBoundingClientRect` + `IntersectionObserver` + `elementFromPoint` 判定）。
- **自动滚动**：`click/type/select_option/hover` 前若元素不在视口则先 `scrollIntoView({block:'center'})` 再执行，消除"点了没反应"。

#### R2.3 统一动作协议

- **统一返回 envelope**：所有动作返回 `{ok, action, changed, summary, note|error}`，`snapshot`（按分级）作为可选项；
- **结果分级**：`summary`（默认，一行结论）/ `standard`（含精简快照）/ `full`（全量），由同一 `snapshot_level` 控制；
- **写操作自动验证**：每个写操作（click/type/select/submit 等）后自动取 `summary` 快照判断状态变化，把"前后对比"直接给模型，减少模型自行轮询；
- **错误可恢复**：失败返回 `recoverable: true` + 具体建议（如"元素不可见，先 scroll down"、"id 失效，已按语义重定位"）；
- **动作历史**：controller 维护最近 N 条 `action_log`（操作 + 结果摘要），新增 `history` 动作查询，避免重复操作。

> **兼容性决策（已定）**：envelope 统一为**干净替换**——不留旧返回结构兼容层，模型 prompt / 工具描述 / `docs/modules/browser.md` 同步更新，一次到位。

#### R2.4 事件驱动感知

- **DOM 变化订阅**：document-start 注入 `MutationObserver`，元素新增/移除/属性变化写入页面环形缓冲并维护版本号；`wait_for_change(timeout)` 挂起等待版本号变化后返回变化摘要，替代轮询 snapshot；
- **可见性订阅**：`IntersectionObserver` 上报进入/离开视口的元素，供模型判断"滚动后看到了什么"；
- **网络事件复用**：现有 `JS_NET_HOOK` + `wait_for_request` 保留，`wait_for_change` 同时监听网络事件，页面任何动静都唤醒；
- **后台感知**：模型挂起等待期间 UI 状态条显示"等待页面变化"，变化后自动唤醒并回传摘要。

### R1 用户侧（副线）

#### R1.1 浏览增强

- **历史记录**：持久化访问历史（host/title/url/时间），浏览器页入口列表，支持回跳；
- **收藏夹**：书签增删查（持久化），新标签页展示 + 快捷入口；
- **新标签页/主页**：空标签显示搜索/收藏/最近访问快捷入口；
- **页内查找**：`WebView.findAllAsync` + 高亮 + 上下切换。

#### R1.2 内容管理

- **下载管理 UI**：基于现有 `downloads` StateFlow 补 UI 面板（列表/打开/重试/清除）；
- **凭据管理 UI**：基于 `BrowserCredentialStore` 补管理页（增删/明文查看受保护）；
- **分享/复制链接**：地址栏菜单动作。

#### R1.3 隐私与适配

- **无痕模式**：独立 WebView 实例（不共享 cookie），会话级开关；
- **桌面版切换**：UA override 切换（`WebSettings.userAgentString`）；
- **缩放控制**：`WebSettings.textZoom` / `setSupportZoom`。

## 4. 对外接口变更

- `BrowserAgentTool` 动作不变（新增 `page_text` / `wait_for_change` / `history`），返回结构统一为 envelope（**干净替换，无兼容层**：模型 prompt / 工具描述 / `docs/modules/browser.md` 同步更新，一次到位）；`element_id` 参数扩展为可传 id / CSS 路径 / 语义描述符三者任一；
- `BrowserController` 新增快照分级、增量 diff、locator 映射与三级定位解析、可操作性标注、事件订阅接口；
- UI 侧新增历史/收藏/下载/凭据/无痕等入口与页面（string 资源需入 `strings.xml` + `values-en`）。

## 5. 实施计划（refactor/browser-upgrade 分支，分阶段）

- **阶段 A（R2 协议与快照）**：快照分级（summary/standard/full）+ 增量 diff + 统一 envelope（干净替换）+ 结果分级 + 写操作自动验证 + `snapshot_level` 参数；同步 prompts。
- **阶段 B（R2 元素与事件）**：三级定位（CSS 绝对路径为主 + data-rcb-id 兜底 + 语义定位）+ 可操作性标注 + 自动滚动 + `wait_for_change`/`history`。
- **阶段 C（R1 用户侧）**：历史/收藏/新标签页/页内查找/下载 UI/凭据 UI/分享/无痕/桌面版/缩放。
- **阶段 D（收尾）**：文档同步（`docs/modules/browser.md`、`assets/docs/*`）、`./gradlew :app:assembleDebug` 冒烟、合回 main。

## 6. 验证方案

- 每阶段 `./gradlew :app:assembleDebug` 冒烟；
- 真机/模拟器：模型驱动复杂页面（SPA、懒加载、表单）的 token 消耗与操作成功率对比；
- 单元测试：快照 diff、id 自愈、可操作性判定、wait_for_change 触发。
