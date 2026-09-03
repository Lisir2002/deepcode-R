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

### 3.2 统一令牌门面（序列表式，单一事实源）

现有散件收编为成套 object（`core/theme/token/`），但**数值定义统一进一张"令牌全景序列表"**（本文档即序列表，实施时转为代码常量表），各 token object 只引用该表，**改版只改表一处**：

| 序 | 语义令牌 | 值 | 用途 | 现来源 |
|---|---|---|---|---|
| C | **色彩族** | — |（§3.4）| `Brand`/`ChatAccent`/`CyberColors` |
| T | **排版族** | — |（§3.5）| `AppTypography` |
| S | `Spacing.xs/sm/md/lg/xl/xxl` | 4/8/12/16/24/32dp | 唯一间距刻度 | `Spacing` |
| R1 | `Radius.xs` | 4.dp | 小分隔/进度端 | `Radius` |
| R2 | `Radius.sm` | 8.dp | Chip/小图标块 | `Radius` |
| R3 | `Radius.md` | **12.dp**(M3) | 卡片/输入框/搜索栏 | 迁 |
| R4 | `Radius.lg` | **16.dp**(M3) | 大卡/Dialog/Sheet | 迁 |
| R5 | `Radius.pill` | 999.dp | 按钮/标签/胶囊 | `Radius` |
| E1–E5 | `Elevation.z0..z4` | 0/1/3/8/12dp(+shadow) | 阴影层级(全规格T2) | `Elevation` |
| Z1 | `Sizing.TouchTarget` | 44.dp | 触控/栏高(T3) | 魔法值 |
| Z2 | `Sizing.IconButton` | 40.dp | 图标钮可触点 | 魔法值 |
| Z3 | `Sizing.Icon{Xs/S/M/L}` | 16/18/20/24.dp | 图标尺寸唯一刻度 | 魔法值 |
| I1 | `Icon.Library` | `Rounded` | 图标库统一基调(T6) | 345处混用 |
| I2 | `Icon.BlockSize` | 40.dp | 图标块规格 | 魔法值 |
| L1 | `Layout.PageHorizontal` | 16.dp | 页面左右留白 | 魔法值 |
| L2 | `Layout.BlockGap/RowGap` | 16/8dp | 区块/行内间距 | 魔法值 |
| M1–M3 | `Motion.fast/med/slow` | 150/250/400ms | 动效时长刻度 | 魔法值 |

> **序列表纪律**：表内 `序` 稳定不删改（新增追加序）；各 `AppXxx` object 由表生成；改值只改表。禁止在组件内出现表外数值。

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

### 3.4 颜色令牌 `AppColor`——完整语义色板（消灭两套并行色板）

现状 `Brand.Blue=#2563EB` 与 `CyberColors.Cyan=#00B894`、`LineBlue=#0984E3` **不同源**，是视觉撕裂主因。设计为**一套完整语义色板**，并按 Material 3 **Color Roles** 映射进 `MaterialTheme`：

**色板族（唯一出处 `AppColor`）**：

| 色族 | 内容 | 来源 |
|---|---|---|
| **品牌/主色** | `primary` / `primaryContainer` / `onPrimary(Container)` | `Brand.Blue/Sky/Ice` 归入 |
| **中性/背景** | `background` / `surface` / `surfaceVariant` / `onSurface(Variant)` | `Light/DarkColorScheme` 建立 |
| **边框/分割** | `outline` / `outlineVariant` | 并入 `CyberColors.CardStroke/Divider` |
| **状态绿** | `StatusGreen.Light/Dark`（成功/就绪/在线） | `Brand.StatusGreen` 保留 |
| **功能语义色** | `ChatAccent`（Build/Plan/Auto/Reasoning/Skill 双态 Tone） | `ChatAccent` 保留 |
| **风险色** | `error` / `errorContainer` / 警告 `warning` / 禁用 `disabled` | 现有 `error` 移植 + 补 warning/disabled |

**规则**：
- 组件一律用语义色名（`AppColor.primary`/`surfaceVariant`），`MaterialTheme.colorScheme` 由 `AppColor` 映射生成（日/夜仍由 `LocalAppDarkMode` 统一下发）。
- `CyberColors` 全部并入并删除重复定义（T4）；禁止在组件内新建色值字面量。

> ✅ **已定档（T4）**：`CyberColors` 全部语义化**并入单一语义色板 `AppColor`**，删除第二套色值定义（涉及设置/关于页观感微变属预期）。`Cyan/Blue→AppColor.accent` 系，`CardBg/CardStroke/Divider/HeaderText→surface/outline/onSurface`。

### 3.5 排版与动效令牌

- **`AppType`**（排版）：补全 Material3 完整 TypeScale（现仅魔改 6 档），统一字重/字距/行高；禁止在手写 `fontWeight=SemiBold` 时覆盖全局 style（局部覆盖收敛为组件令牌）。
- **`AppMotion`**（动效）：统一时长刻度（`fast=150ms / med=250ms / slow=400ms`）+ 缓动（`FastOutSlowInEasing` 等），页面过渡 `tween(250/200)`、进度 `tween(1500/300)` 全部走令牌。

### 3.6 图标令牌 `AppIcon`（新增，覆盖 345+ 处乱象）

现状 345 处 `material.icons` import + 244 处 `Icons.*`，`Rounded/Outlined/Filled` 三态混用、尺寸 20/18/24 混用、图标块背景全散落。统一为图标体系：

| 令牌 | 内容 |
|---|---|
| **库统一** | 全局统一使用 Material Icons **`Rounded`**（拟合圆角调性）；`Outlined/Filled` 仅作有意的层级区分 |
| **图标尺寸刻度** | `AppIcon.Xs=16 / S=18 / M=20 / L=24`（对齐 `AppSizing.Icon*`），禁止用中间值 |
| **图标块规格** | `AppIcon.BlockSize=40.dp`（触点在 `AppSizing.IconButtonSize`）、圆角走 `AppRadius.sm`、背景走语义色 `AppColor.iconBlock`（统一 iconBg 规则：彩色块背景 + 白色图标，见 M3 设置页风格） |
| **tint 规则** | 可点图标 `onSurfaceVariant`；功能图标走 `ChatAccent.resolve()`；禁用走 `disabled`；装饰图标 `onSurfaceVariant.copy(alpha)` 透明档统一 |
| **内容描述** | 纯装饰 `contentDescription=null`；语义图标必填（已有纪律，补为图标规范） |

> `CyberSearchBar`/`CyberMenuRow` 等现有硬编码图标尺寸/背景，P1 起迁到 `AppIcon`。

### 3.7 布局令牌 `AppLayout`（新增，统一页面栅格与留白）

现状 `padding(horizontal=16/14/12)`、`Arrangement.spacedBy(...)` 散落。统一为标准页面模板：

| 令牌 | 取值 | 用途 |
|---|---|---|
| `PageHorizontal` | `Spacing.lg`(16.dp) | 标准页面左右留白 |
| `PageTop` | `Spacing.lg` + statusBars | 页首留白 |
| `BlockGap` | `Spacing.lg` | 区块间垂直间距 |
| `RowGap` / `ItemGap` | `Spacing.sm/md` | 行内/列表项间距 |
| `ListRowPaddingVertical` | `Sizing.RowPadV`(14.dp) | 菜单行垂直内边距 |
| `AlignJustify` 约定 | 分组卡靠左 + `weight(1f)` | 文案/控件对齐口径 |

**标准页面模板**（新页面唯一布局骨架）：

```
Surface(page bg) > Column(vert pad) {
    SectionHeader(*Section)
    AppSectionGroup { 每行: Row(padding PageHorizontal) }
    BlockGap 分隔
}
```

> `Spacing` 现有的 xs/sm/md/lg/xl/xxl 为唯一间距刻度，`AppLayout` 只引用它、不新造数值。

### 3.8 状态组件 `AppState`（Loading / Empty / Error 统一）

现状 `AppLoadingState` / `AppEmptyState` 已各自存在但未成体系。统一为**三态契约**，每个可加载区块必须明确三态：

| 状态 | 统一组件 | 契约 |
|---|---|---|
| **Loading** | `AppLoadingState`（已有，补全） | 加载图标流、spinner + `loadingText`（可选） |
| **Empty** | `AppEmptyState`（已有，补全） | 图标 + 标题 + 可选副标题/操作按钮 |
| **Error** | `AppErrorState`（新增） | 错误图标 + 主文案 + 原因副文案 + 重试按钮（回调 `onRetry`） |

**统一规则**：
- 三态组件都走 `core/ui/` 共享，引用 `AppColor`/`AppType`/`AppSpacing`/`AppIcon`。
- `AppEmptyState` 现有 `Icon` 尺寸 48dp 收口到 `AppIcon` 刻度；文案走 `strings.xml`。
- 页面级统一 `when(state) { Loading -> empty; Empty -> empty; Error -> error; Content -> screen }` 模板，杜绝各页各自造三态。
- **禁止**新页面自造 loading/empty/error（除非有明确差异化需求，须标注）。

### 3.9 组件库分级体系

把"组件"分成**三级职责**，回答"组件该放哪、有多复杂"：

| 级 | 归属 | 定义 | 示例 | 禁止 |
|---|---|---|---|---|
| **原子 Primitives** | `core/ui/` | 无业务、纯视觉，跨模块复用 | `AppTopBar`/`AppEmptyState`/`AppState`/`SwipeToConfirm`/`DiffViewer` | 含业务回调 |
| **分子 Molecule** | `feature/*/presentation/component/` | 本模块内组合，可带轻业务 | `ChatInputBar`/`GitStatusTab`/`ProviderEditorScreen 块` | 整屏状态机 |
| **整屏 Screen** | `feature/*/presentation/` 根 | 页面级组装，连接 ViewModel | `*Screen` | 业务逻辑 |

**分层规则**：
- 组件能否跨模块复用 → 能：`core/ui/`（原子）；不能：留在本模块 `component/`（分子）。
- 分子组件禁止再出现在 `core/ui/`；原子组件禁止被 feature 复制拷贝。
- 三级之上即 §2 四件套：原子/分子 供 `Screen` 组装，`Screen` 由 `ViewModel` 喂数据。

### 3.10 响应式与多尺寸适配

当前以手机纵向为主，但需明确多窗口/大屏卫生（深色已由 defer，尺寸适配统一）：

| 面 | 规范 |
|---|---|
| **断点** | 紧凑(<600dp) / 中等(600–840dp) / 展开(≥840dp) 三档（Material 3 window size class 约定） |
| **可滚动区块** | 长内容一律收进 `verticalScroll`/`LazyColumn`；禁用整页 `fillMaxHeight` 平铺（段用户平板/折叠屏顶部溢出） |
| **最大内容宽** | 宽屏下内容区 `widthIn(max=PageMaxWidth)`（建议 720dp）居中，避免排版拉爆 |
| **抽屉/弹窗** | Sheet/Dialog 宽度用 `Dp` 令牌 + 断点控制，不用固定像素 + `fillMaxWidth` 双写 |
| **状态栏/insets** | 一律 `WindowInsets` + `AppLayout` 令牌，禁 `statusBarsPadding()` 与 `WindowInsets` 混用（收口到 `AppLayout`） |
| **终端/WebView** | 原生 View 区高度用旁路尺寸（`Modifier.weight`/`Box` 约束），不做 breakpoint 走势（已有 MTerminal 逻辑保留） |

> 响应式不改变现有手机端布局，只在新页面与大屏检测时应用；折叠屏中隔断仅作 diff 级收口，不重构既有页。

### 3.11 交互反馈与状态

定义组件在交互下的**五种状态**，杜绝各页乱用 `clickable`/`hoverable`：

| 状态 | 统一实现 | 规则 |
|---|---|---|
| **pressed** | M3 ripple `clickable`（`Indication` 跟随 `MaterialTheme`） | 默认均有触控波纹；禁 `indication = null`（除 WebView/Terminal 原生区） |
| **hover** | `hoverable` + 轻背景 | 触宽屏/鼠标生效，开关可控；手机忽略 |
| **focus** | 系统 focusIndicator | 保留 TalkBack/键盘可达性 |
| **disabled** | `AppColor.disabled`（前景+背景降饱和） | 统一透明度档，禁各页乱 `.copy(alpha)` |
| **selected** | 选中态容器/描边（`AppColor.primaryContainer` or `outlineVariant`） | 与 unselected 对比 ≥ 色彩差异标准 |

**触觉反馈（Haptics）**：
- 轻量点击（按钮/行/开关）→ 无/轻触感（`LocalHapticFeedback` 统一封装 `AppHaptics.click`）。
- 关键破坏性操作（删除/重置/危险确认）→ 明确触感 + 二次确认（沿用既有 `SwipeToConfirm`/受保护操作纪律），禁裸点击一把梭。
- 震动走统一 `AppHaptics`，禁在组件内直接 `Vibrator`。

### 3.12 组件集目录（Component Catalog）——新页面搭什么

给新页面一份**统一的"可用组件清单"**，答复"搭这个页面该用什么"，避免各页自造轮子：

| 类别 | 统一组件（core/ui 原子 + M3 原生） | 备注 |
|---|---|---|
| **按钮** | M3 `Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton`（或 `AppButton` 封装） | 禁混用裸 `Box.clickable` 当按钮 |
| **文本输入** | M3 `OutlinedTextField` / `BasicTextField`(紧凑) | 统一 `TextFieldToken`（填充/描边/占位） |
| **筛选** | `Chip` / `FilterChip`（或 `AppChip`，收 `CyberChip`） | 选中态走 §3.11 |
| **卡片/分组** | `AppSectionGroup` / `AppCard`（收 `CyberCard`） | 页内区块统一形态 |
| **列表行** | `AppMenuRow`（收 `CyberMenuRow`）/ `ListRow` | 行高 `Sizing.TouchTarget`、icon 块 `AppIcon` |
| **分割** | `HorizontalDivider`（`AppLayout.DividerThickness`） | 统一厚度 |
| **反馈** | `AppLoadingState`/`AppEmptyState`/`AppErrorState`（§3.8） | 三态统一 |
| **弹窗/Sheet** | M3 `AlertDialog`/`ModalBottomSheet` + `AppTokens` 圆角/边距 | 尺寸走断点（§3.10） |
| **导航顶栏** | `AppTopBar`（紧凑 44dp） | 全项目唯一顶栏 |
| **状态圆点/徽标** | `AppStatusDot`（`Brand.StatusGreen`） | 状态点统一 |
| **富文本/工具消息** | `MarkdownContent`/segment 体系（跨模块） | 聊天独有，收敛 `core/ui` |

> 组件集目录为**新增页面默认首选**；确需 M3 原生替代时标注理由。

### 3.13 无障碍（a11y）

延续既有正确做法的同时补齐规范：

| 面 | 规范 |
|---|---|
| **对比度** | 前景/背景对比 ≥ 4.5:1（正文）/ 3:1（大文本）；`Brand.StatusGreen` 已满足，新色须过审 |
| **触控目标** | ≥ `AppSizing.TouchTarget`(44dp)，图标钮 ≥ `IconButtonSize`(40dp)；字号可点文字放宽 |
| **语义标签** | 语义图标 `contentDescription` 必填（`strings.xml`）；装饰图标 `null`，禁反用 |
| **TalkBack 导航** | 可聚焦组件按"标题→控件→操作"次序；分组用 `mergeDescendants`，避免复读 |
| **缩减动效** | 尊重 `Settings.Global.ANIMATOR_DURATION_SCALE=0`，`AppMotion` 时长据此降档（可选增强，后置） |

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
| **令牌** | **`AppColor`/`AppType`/`AppSpacing`/`AppRadius`/`AppElevation`/`AppSizing`/`AppIcon`/`AppLayout`/`AppMotion`** | 散件 `Brand`/`Spacing`/`CyberColors` 各叫各的 |
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

- **P1 接最大模块（agent 聊天 + settings）**：拆 `AIAgentViewModel`→`ChatContract`+`ChatViewModel`；抽 `AgentRoutes`/`SettingsRoutes`；`AIChatPanel`→`ChatScreen`；settings 复数组件归并 `component/`（单数）；**魔法圆角/高度/色值/图标尺寸/页面留白先迁到令牌**（`AppRadius`/`AppSizing`/`AppColor`/`AppIcon`/`AppLayout`，样板工程验证）。

- **P2 其余 feature**（git / terminal / proxy / workspace / credentials / capability / browser / backup）：按 P1 模板逐屏套用，各抽 Route + Contract + Screen，视觉引用令牌。

- **P3 收口**：`AppComponents`/`CyberComponents` 并入 `core/ui/`；`CyberColors` 语义化并色（去第二套色板）；**组件集目录落地**（§3.12 收 `CyberCard/CyberMenuRow/CyberChip` 到 `App*`）；`MainActivity` 瘦身到图表组装；全量模块文档与索引核对。

---

## 8. 拍板结论（已敲定，待负责人最终确认后转正）

| # | 事项 | 结论 |
|---|---|---|
| T1 | 圆角刻度 | ✅ **迁到 M3 标准**（md=12 / lg=16） |
| T2 | 阴影规格 | ✅ **M3 全 shadow 规格**（含颜色/xy/模糊半径） |
| T3 | 触控高度 | ✅ **统一 44dp 紧凑** |
| T4 | CyberColors | ✅ **并入单一语义色板 AppColor** |
| T5 | 令牌精度 | ⏳ 未单独敲定——默认"精简够用为基 + shadow 取 T2 全规格"；实施如需全量 M3 再扩 |
| T6 | 图标库基调 | ⏳ 待拍板——默认**统一 `Rounded`** 单态基调（拟合圆角调性）；如同意沿用即可 |
| T7 | 语义色板族 | ⏳ 待拍板——默认按 §3.4 **六色族**（主/中性/边框/状态绿/功能/风险）建立 `AppColor`；同意沿用即可 |
| T8 | 三态组件 | ✅ 采纳 §3.8：统一 `AppState` 三态（Loading/Empty/Error），禁自造三态 |
| T9 | 组件分级 | ✅ 采纳 §3.9：原子(`core/ui`)/分子(`component`)/整屏(`*Screen`) 三级 |
| T10 | 响应式 | ⏳ 待拍板——默认按 §3.10 三断点(600/840) + 最大内容宽 720dp，仅新页与大屏生效 |
| T11 | 组件集目录 | ✅ 采纳 §3.12：新增页面统一走 Component Catalog，收 `Cyber*` 到 `App*` |
| T12 | 无障碍 | ⏳ 待拍板——默认按 §3.13（对比度/触控/语义标签/TalkBack）；动效降档后置 |

## 9. 门禁与配套

- 沿用既有 `.githooks/pre-commit` + `commit-msg`；后继可选扩展"component 单数、*Screen 位置、新增 `.kt` 含魔法色值/圆角/高度"轻量校验（后续增强，非本轮）。
- 本设计定稿后由各 `docs/modules/<module>.md` 反映落地实现，两处不重复维护（依 `docs/plan-docs/README.md`）。

## 10. 评审记录

> T1–T4 已由负责人拍板并回填 §8（M3 圆角 / M3 全阴影规格 / 44dp 紧凑 / 并入单色板）；T5 默认精简够用。
> ⚠️ 本文档仍为 `📝 草案`：待负责人对整份设计（骨架 + 令牌 + 路线图）做最终确认后，将状态转正为 `✅ 已评审` 再进入 P0 实施。