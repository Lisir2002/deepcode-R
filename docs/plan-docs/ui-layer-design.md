# 新版 UI 层统一架构设计（DeepCore-Code）

> 评审状态：📝 草案
> 关联模块：全部 feature（agent / settings / git / terminal / proxy / workspace / credentials / capability / browser / backup）+ 核心主题（core/theme）+ 仓库治理（AGENTS.md / docs / githooks）
> 前置：无（本设计为 UI 层总纲，后续各专题设计在其下展开）

## 1. 背景与目标

DeepCore-Code 的 UI 层经过多轮迭代，已形成可用的功能矩阵，但随着 feature 模块增长，UI 侧出现两类问题：
1. **结构漂移**：目录命名 `component`/`components` 并存、整屏塞进 `component/`、导航魔法串、命名口径不一、巨型 ViewModel；—— 已有骨架治理，见 §2/§4。
2. **视觉原子失管**（本轮重点）：圆角 / 阴影 / 高度 / 间距 / 颜色 / 排版 / 动效缺少统一令牌，魔法值满天飞、两套并行色板不同源，导致各模块视觉不统一、无法"照模板"一致落地。

**项目工作目标进入"规范项目结构"阶段，第一阶段就是 UI 层规范化。**

**本设计的定位**：制定一套**新版 UI 层统一架构（v2）**，覆盖两类上层——
- **结构骨架**（§2，已拍板）：今后所有新增页面的标准模板；
- **设计令牌体系**（§3，本轮补全）：视觉原子统一管理的地基。

然后**按批次逐步接管（迁移）存量旧版 UI 层**。增量新页面优先、存量渐进收口。

**硬性前提（负责人已拍板）**：
1. 先定规范，不动代码——本设计只产出规范与路线图，不进入任何代码/资源改动；
2. 严格四件套骨架——新页面一律采用 §2，不允许例外。

**遵循的存量纪律**（规范化不得破坏）：
- UI 文案纪律已落地：用户可见中文一律走 `strings.xml`（中/英双语）+ `stringResource()` 引用（见 AGENTS.md「资产同步纪律」）。
- ViewModel 提升到 Activity 级共享是既有正确模式，作为显式约定保留。
- 现有一批设计精良的 token（`Spacing`/`Elevation`）**纳入新令牌体系统管，不断其用法**。

---

## 2. 统一骨架（严格四件套）

每个 feature 的 `presentation/` 统一为固定四件套，**新页面一律按此落位**：

```
feature/<module>/presentation/
├── nav/                NavGraph 段 + Route 常量      （导航收敛到这）
│   └── <Module>Routes.kt
├── state/              UiState + UiEvent + ViewModel  （状态与 UI 控件分离）
│   ├── <Feature>Contract.kt    （UiState / UiEvent / UiSideEffect）
│   └── <Feature>ViewModel.kt
├── component/          仅可复用原子组件（无整屏）       （统一单数，消灭复数漂移）
│   └── ...原子组件...
└── <Feature>Screen.kt  整屏入口，只做组装，不放业务    （整屏命名统一 *Screen）
```

### 2.1 四件套职责边界

| 件套 | 目录 | 职责 | 禁止 |
|---|---|---|---|
| **Routes** | `nav/` | 定义本模块 route 常量 + `NavGraphBuilder.<module>NavGraph(...)` | 不放业务、不放魔法字符串 |
| **Contract** | `state/` | 屏幕级 `UiState` / `UiEvent` / `UiSideEffect`，一屏一组 | 不放可执行逻辑（至多纯推导） |
| **ViewModel** | `state/` | 一屏一 Holder，只消费 Contract 收发事件 | 不聚合多屏状态、不直接持有 UI |
| **原子组件** | `component/` | 大写的可复用原子组件（`*Card`/`*Item`/`*Field`） | 不放整屏、不放业务 |
| **整屏** | `presentation/` 根 | `*Screen` 只做依赖注入式组装，回调下沉 | 不放业务状态机 |

### 2.2 关键规则

1. **component 统一单数**：`component/` 为唯一写法，消灭 `components/`（复数）漂移源。
2. **整屏上提**：`*Screen` 一律放 `presentation/` 根，禁止再塞进 `component/` 或嵌套子包。
3. **Route 收敛**：route 字符串进入 `nav/` 常量，`MainActivity` 不再出现魔法串。
4. **ViewModel 职责收敛**：一屏一 Holder，只持本屏 `Contract`；跨屏共享（会话/工作区/设置）经显式注入共享 `ViewModel`，不靠巨型聚合。
5. **视觉一律引用令牌**：任何 modifier/组件出现的圆角、阴影、高度、间距、颜色、字号、动效，必须引用 §3 令牌，禁止 magic value（pre-commit 可选校验见 §7）。

---

## 3. 设计令牌体系（Design Tokens）——视觉原子统一管理

### 3.1 分层模型（采 Material 3 标准三层，贴合项目）

```
第 3 层  组件令牌    Card/Button/ListRow/TextField...  组件私有，映射语义令牌
第 2 层  语义令牌    primary/surface/border/onSurface  表达"意图"，不写死数值
第 1 层  原始令牌    色值 #2563EB / 4.dp / tween(250)     真实常量的唯一出处
```

- 代码中只允许引用**语义令牌**（第 2 层）与**组件令牌**（第 3 层）；原始数值只在 `AppTokens` 门面内出现一次。
- **禁止跨模块拷贝色值/圆角/高度字面量**。

### 3.2 统一令牌门面（`core/theme/` → 收敛为一套 `AppTokens` 门面）

现有散件收编为成套 object（统一前缀，全部放 `core/theme/token/`）：

| Token 门面 | 内容 | 现状来源 |
|---|---|---|
| `AppColor` | 单一语义色板（合并 Brand + CyberColors，去第二套色板） | `Brand` / `ChatAccent` / `CyberColors` 三处 → 合 |
| `AppType` | 完整 Typography scale | `AppTypography`（现有局部魔改）→ 补全 |
| `AppSpacing` | 间距刻度（补 2/3 间隙态） | `Spacing`（现有）→ 纳入 |
| `AppRadius` | 圆角刻度 + 组件形变映射 | `Radius`（现有）→ 增强 |
| `AppElevation` | 阴影层级 + 每层伴随 shadow 定义 | `Elevation`（现有）→ 增强 |
| `AppSizing` | **高度/尺寸**：栏高/图标/触控目标/分割线/最小尺寸 | 无（现为魔法值，本设计补全） |
| `AppMotion` | 动效时长 + 缓动 | 无（现为 `tween(250)` 魔法值，补全） |

### 3.3 你点名的三大令牌——指定设计

#### 3.3.a 圆角令牌 `AppRadius`
现有 `Radius{xs=4, sm=8, md=10, lg=14, pill=999}` **建议重构为对齐 M3 的五档**：

| 语义令牌 | 取值 | 典型用途（组件令牌第 3 层） |
|---|---|---|
| `AppRadius.xs` | 4.dp | 小分隔条、进度条端 |
| `AppRadius.sm` | 8.dp | Chip、小型图标块 |
| `AppRadius.md` | 12.dp | 卡片/输入框/搜索栏 |
| `AppRadius.lg` | 16.dp | 大卡片、Dialog、Sheet |
| `AppRadius.pill` | 999.dp | 按钮、标签、胶囊切换 |

> ✅ **已定档（T1）**：圆角迁到 M3 标准档（md=12 / lg=16）。实施时批量改存量 `RoundedCornerShape(14/12/10)` 为引用 `AppRadius`，观感轻微变化属预期。

#### 3.3.b 阴影令牌 `AppElevation`
现有 `Elevation{z0..z4}` 已是正确雏形，**补全为"层级 + 伴随阴影"二元定义**（项目同时用 `shadowElevation` 浮起与 `tonalElevation` 分层）：

| 语义层级 | shadow | tonal | 用途 |
|---|---|---|---|
| `z0` | 0.dp | 0 | 页面背景 |
| `z1` | 1.dp | 0 | 列表项、卡片（无浮起） |
| `z2` | 3.dp | 1.dp | 输入栏、按钮、可交互卡片 |
| `z3` | 8.dp | 2.dp | 弹窗、Sheet、面板 |
| `z4` | 12.dp | 3.dp | 全局 Toast/横幅 |

> ✅ **已定档（T2）**：阴影细到 **M3 全 shadow 规格**——每层除 elevation 数值外，定义 shadow 颜色 / x偏移 / y偏移 / 模糊半径（`core/theme/token/AppElevation` 内置完整 shadow spec，`shadowElevation`+`tonalElevation` 二元下表仅列数值；全规格待实施时补全）。
> - 备注：`CyberCard` 现用 `shadowElevation=0.5dp` 等零散值，统一后一律取 `z1`。

#### 3.3.c 高度/尺寸令牌 `AppSizing`（新增，现行空白）
统一控件高度与尺寸，终结 `44dp`/`40dp`/`20dp` 满天飞：

| 语义令牌 | 取值(建议) | 用途 |
|---|---|---|
| `TouchTarget` | 44.dp（紧凑，跟随 AppTopAppBar）| 栏/行/可点项最小高度 |
| `AppTopBarHeight` | 44.dp | 统一 TopBar |
| `IconButtonSize` | 40.dp | 图标钮可触点 |
| `Icon*`（`IconXs/S/M`） | 18 / 20 / 24.dp | 图标尺寸唯一刻度 |
| `DividerThickness` | 1.dp（Border）/ 0.7~1.dp | 分割线（统一为 1 档） |
| `ProgressHeight` / `CardMinHeight` | 依组件 | 进度条、卡片最小高 |

> ✅ **已定档（T3）**：全项目统一 **44dp 紧凑触控规范**（`AppSizing.TouchTarget=44.dp`），保持 AppTopBar/ChatHeader 现有紧凑观感。

### 3.4 颜色令牌 `AppColor`——消灭两套并行色板

现状 `Brand.Blue=#2563EB` 与 `CyberColors.Cyan=#00B894`、`LineBlue=#0984E3` **不同源**，是视觉撕裂主因。设计为单一语义色板：

- **品牌/语义主色**：并入 `Brand`（Blue/Sky/Ice/PageBg/IconGray）+ `StatusGreen`（状态绿，保留）。
- **功能色**：`ChatAccent`（Build/Plan/Auto/Reasoning/Skill 双态 Tone）保留，明确为"功能语义色"。
- **Cyber 色板并入**：`CyberColors.Cyan/Blue` 收敛为语义色（`AppColor.accent` 系），`CardBg/CardStroke/Divider/HeaderText` 收敛为对应 `surface/outline/onSurface`，删除重复定义。
- 日/夜仍由 `LocalAppDarkMode` 统一下发（不变）。

> ✅ **已定档（T4）**：`CyberColors` 全部语义化**并入单一语义色板 `AppColor`**，删除第二套色值定义（涉及设置/关于页观感微变属预期）。`Cyan/Blue→AppColor.accent` 系，`CardBg/CardStroke/Divider/HeaderText→surface/outline/onSurface`。

### 3.5 排版与动效令牌

- **`AppType`**（排版）：补全 Material3 完整 TypeScale（现仅魔改 6 档），统一字重/字距/行高；禁止在手写 `fontWeight=SemiBold` 时覆盖全局 style（局部覆盖收敛为组件令牌）。
- **`AppMotion`**（动效）：统一时长刻度（`fast=150ms / med=250ms / slow=400ms`）+ 缓动（`FastOutSlowInEasing` 等），页面过渡 `tween(250/200)`、进度 `tween(1500/300)` 全部走令牌。

---

## 4. 命名规范全集

| 类别 | 规范 | 当前反例 |
|---|---|---|
| 整屏入口 | `*Screen`（presentation 根） | `AIChatPanel`、`BackupSection` |
| 原子组件 | `*` + `*Card`/`*Item`/`*Field`（component/） | — |
| 全局弹窗 | `*DialogHost`（保留，合理） | — |
| 设置分区块 | `*Section`（component/ 内，块非屏） | `BackupSection` 实为整屏 |
| ViewModel | `*ViewModel`，一屏一 Holder | `AIAgentViewModel`（巨型） |
| UI 状态 | `<Feature>Contract.UiState/UiEvent/UiSideEffect` | 平铺散落 |
| 路由 | `<Module>Routes.{CHAT,...}` 常量 | `"chat"` 魔法串 |
| 组件目录 | `component/`（单数）统一 | `components/` 残留 |
| **令牌** | **`AppColor`/`AppType`/`AppSpacing`/`AppRadius`/`AppElevation`/`AppSizing`/`AppMotion`** | 散件 `Brand`/`Spacing`/`CyberColors` 各叫各的 |
| 全限定名 import | 跨模块经统一门面，禁散落全路径 | `MainActivity`/各 component 全限定引用 |

---

## 5. 导航治理

- 新建 `core/nav/` 存 Route 常量基座 + 共享过渡动画（从 `MainActivity` 迁出 `pageEnter/pageExit/...` 与 `terminal*`）。
- 每 feature 提供 `fun NavGraphBuilder.<module>NavGraph(...)`，`MainActivity` 只组装，从 ~900 行瘦到 ~50 行。
- Activity 级共享 ViewModel 模式作为显式约定保留；Drawer 生命周期隔离、退终端后白屏规避等既有修复以注释保留勿动。

---

## 6. 共享组件库与主题收敛

- `AppComponents.kt` / `CyberComponents.kt` 两套"总组件库"**合并为单一 `core/ui/` 组件库**，为跨模块复用唯一归属。
- 跨模块组件（富文本 segment、`DiffViewer`、`SwipeToConfirm`、`AppTopBar`、`AppEmptyState` 等）迁入 `core/ui/`。
- 组件一律引用 `AppTokens`（§3），删除自身魔法值（如 `CyberSearchBar` 的硬编码 `44dp/12dp/0xFFF2F3F5`）。
- `Spacing/Radius/Brand/ChatAccent/LocalAppDarkMode` 收编进 `AppTokens`，不断现有用法。

---

## 7. 存量旧 UI 接管路线图

**分批收敛标准（每批通用）**：不破坏现有功能、每批 `./gradlew :app:assembleDebug` 通过、文案走 `strings.xml`、每批同步 `docs/modules/<module>.md` 与索引。

- **P0 先立地基（本轮，零功能变更）**：
  - 本文档进入 `docs/plan-docs/` 并登记索引；评审通过后 §8 待拍板项定稿。
  - 命名规范 + 四件套 + **令牌纪律**（禁 magic value，统一走 `AppTokens`）写入 `AGENTS.md` 边界纪律。
  - 建 `core/theme/token/` 令牌门面 + `core/ui/` 空组件库骨架。
  - **此后所有新增页面与视觉一律按 v2 走**。

- **P1 接最大模块（agent 聊天 + settings）**：拆 `AIAgentViewModel`→`ChatContract`+`ChatViewModel`；抽 `AgentRoutes`/`SettingsRoutes`；`AIChatPanel`→`ChatScreen`；settings 复数组件归并 `component/`（单数）；**两处魔法圆角/高度/色值先迁到令牌**（样板工程验证）。

- **P2 其余 feature**（git / terminal / proxy / workspace / credentials / capability / browser / backup）：按 P1 模板逐屏套用，各抽 Route + Contract + Screen，视觉引用令牌。

- **P3 收口**：`AppComponents`/`CyberComponents` 并入 `core/ui/`；`CyberColors` 语义化并色（去第二套色板）；`MainActivity` 瘦身到图表组装；全量模块文档与索引核对。

---

## 8. 拍板结论（已敲定，待负责人最终确认后转正）

| # | 事项 | 结论 |
|---|---|---|
| T1 | 圆角刻度 | ✅ **迁到 M3 标准**（md=12 / lg=16） |
| T2 | 阴影规格 | ✅ **M3 全 shadow 规格**（含颜色/xy/模糊半径） |
| T3 | 触控高度 | ✅ **统一 44dp 紧凑** |
| T4 | CyberColors | ✅ **并入单一语义色板 AppColor** |
| T5 | 令牌精度 | ⏳ 未单独敲定——默认"精简够用为基 + shadow 取 T2 全规格"；实施如需全量 M3 再扩 |

## 9. 门禁与配套

- 沿用既有 `.githooks/pre-commit` + `commit-msg`；后继可选扩展"component 单数、*Screen 位置、新增 `.kt` 含魔法色值/圆角/高度"轻量校验（后续增强，非本轮）。
- 本设计定稿后由各 `docs/modules/<module>.md` 反映落地实现，两处不重复维护（依 `docs/plan-docs/README.md`）。

## 10. 评审记录

> T1–T4 已由负责人拍板并回填 §8（M3 圆角 / M3 全阴影规格 / 44dp 紧凑 / 并入单色板）；T5 默认精简够用。
> ⚠️ 本文档仍为 `📝 草案`：待负责人对整份设计（骨架 + 令牌 + 路线图）做最终确认后，将状态转正为 `✅ 已评审` 再进入 P0 实施。