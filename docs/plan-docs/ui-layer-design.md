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

### 2.3 页面布局槽位模型（Slot Model）

> **定位**：§2.1/§2.2 是"静态四件套"（页面的文件组织模板）；本节是同一 `*Screen` 的**运行时布局组装模型**——把页面拆成一组可插拔、可嵌套、自适应的**槽位（Slot）**，代替每页手写 `Scaffold`/`TopAppBar`/tab 布局。静态简单页仍可用 §2.1 直接写；需要多区/自适应/tab/收纳能力的页面**一律按本节槽位模型装配**。
>
> ✅ **已拍板**：槽位模型**落地为框架 API**（`core/ui/` 提供 `SlotContent`/`BlockSlot`/`SlotSet`），非仅协议。

#### 2.3.1 槽位类型与层级（递归树）

槽位分**两级类型**，且**块级可收纳块级**，构成递归的槽位树：

- **块级槽位（Block Slot）**：占据一块布局区域的容器。五类标准块级槽位——
  `TopAppBar`（顶栏） / `TopTabs`（顶栏 tab 菜单） / `Content`（内容区） / `BottomTabs`（底栏 tab 菜单） / `SideRail`（侧边栏悬浮窗）。
  
  块级槽位既可以是**叶子**（如 `Content`），也可以是**容器**：容器内可再挂子级槽位，或**收纳其它块级槽位**（如 `SideRail` 把 `TopTabs` 收纳为自身子级）。
- **子级槽位（Sub Slot）**：挂在块级槽位内的具体内容插槽。如——
  - `TopAppBar` 下的 `ActionButton` / `Title`（顶栏按钮与文字）；
  - `TopTabs`/`BottomTabs` 下的 `TabItem`（单个 tab）；
  - `SideRail` 收纳块级槽位时，被收纳块作为其子级。

> 规则：**块级可收纳块级、子级只在块级之下**；任一槽位可"空置"（不挂插件时就回退默认/隐藏）。→ 这就是"块级槽位插件 + 子级槽位插件"的递归含义。

#### 2.3.2 标准页面槽位树（默认装配）

```
Screen (整屏)
├─ TopAppBar            [块级，容器]
│   ├─ Title            [子级] label | actions
│   ├─ ActionButton*    [子级，顶栏按钮]  ← AppIcon + 可点区
│   │     └─ 图标/文字（子槽位：图标块 IconContainer 或 文字）
├─ TopTabs              [块级，可选，自适应宽度]  ← 容器
│   └─ TabItem*         [子级，tab]  ← 每个 tab 是"按钮+文字"
├─ Content              [块级，叶子，weight(1f)]
│   └─ 页面内容（state/ViewModel 渲染）
├─ BottomTabs           [块级，可选，自适应宽度]  ← 同 TopTabs
│   └─ TabItem*
└─ SideRail             [块级，可选，悬浮窗/抽屉]
    ├─ 收纳: TopTabs / 其它块级槽位   ← 收纳为子级
    └─ 子级插件（顶栏会长、其它块）
```

槽位树由 `Screen` 用 **`SlotSet` 声明式装配**（§2.3.5），哪些块显示/哪些 tab 挂载由该屏配置决定，未挂载槽位自然隐藏。

#### 2.3.3 自适应 Tab 菜单协议（按个数 × 可用宽度自适应）

`TopTabs`/`BottomTabs` 接收 **tabs 列表 + 可用宽度**，布局策略自适应：

| 策略 | 行为 | 适用 |
|---|---|---|
| `EqualWeight` | 均分 `availableWidth / tabCount`，居中文字 | 2–4 个 tab 均匀铺满 |
| `FitContent` | 按各 tab 文字/图标内容自然宽，`spacedBy(ItemGap)` | 文字长短不一、个数少 |
| `Scrollable` | 超宽时可横向滚动 | tab 很多（>4）或超窄屏 |
| `OverflowCollapse` | 超出可视宽时收敛进 `更多`（`ExpandMore`）二级收纳 | ⏳ 后置（非本轮默认） |

**选择算法（默认，三策略）**：先 `FitContent` 估宽；若总宽 ≤ 可用宽 → `FitContent`；若超宽且 tab ≤ 4 → `EqualWeight` 均分；若 tab > 4 或仍超宽 → `Scrollable`。→ **个数字宽自适应，杜绝 tab 挤压换行。**（`OverflowCollapse` 作为后续可选增强，不纳入本轮选路）

宽度由父槽位测量给出：`Content(contentPadding={ TopTabs 吸到该宽 })`、`BoxWithConstraints`/`onSizeChanged` 提供 `availableWidth`。

#### 2.3.4 槽位插件接口（可插拔）

为"块级槽位插件"与"子槽位插件"定义统一协议，任何插件（页面内、甚至收纳进 SideRail）都能挂接：

```kotlin
// 子级槽位插件：一块可渲染内容
sealed interface SlotContent {
    data class Text(val text: String, val style: SlotText) : SlotContent   // 顶栏/底栏文字
    data class Button(val icon: ImageVector, val tint: SlotTint, val onClick: () -> Unit, val description: String?) : SlotContent
    object Divider : SlotContent
    // …（可扩展：徽标、下拉、数据展示）
}

// 块级槽位插件：一块可挂子级的区域
interface BlockSlot {
    val slot: BlockSlotKind                 // TopAppBar / TopTabs / Content / BottomTabs / SideRail / 自定义
    val subSlots: Map<SubSlotKey, SlotContent>? // 子级槽位（按钮/文字/tab）
    @Composable fun Blocks(): @Composable () -> Unit   // 块区域渲染（内部装配 subSlots）
}
```

- **子级 RESIZABLE**：`Title`/`ActionButton` 都实现为 `SlotContent`，插到 `TopAppBar.actions` 或收纳处，同一组件装着跑（复用 §3.12 的 `Icon`/`IconContainer`/`AppHaptics`）。
- **收纳即嵌套**：`SideRail` 的 `subSlots` 可插入一个 `BlockSlot`（如 `TopTabs`），块级即成为子级 —— 实现"侧边栏把顶栏 tab 收纳进来"。✅ **本轮先作静态挂载**（收纳进/出为一次布局切换，不追求展开/收起动画，动画后置）。

#### 2.3.4b ★ 与官方 Slot API 的深度融合（Composable 插槽惯例）

> **学习结论**：官方 Compose 的"插槽"就是 **命名参数 + `@Composable () -> Unit` lambda**（`Scaffold(topBar=…, bottomBar=…)`、`NavigationSuiteScaffold(navigationSuiteItems=…, content=…)`、`NavigableListDetailPaneScaffold(listPane/detailPane…)` 都是同一语言）。因此我们**以官方 scaffold 为基底、在其上加一层"装配定义"，而不是另起一套 data-class 框架** —— 这样 recomposition、keep alive、背压等全部交给官方。

**双轨融合**（关键）：
- **渲染轨（Composable 插槽 lambda）**：子级用什么由插槽 lambda 决定，能放任意 Composable、正确处理状态与重组。**这是页面的真渲染源。**
- **描述轨（数据化 SlotContent /装配图）**：只描述"哪个 slot 装了哪个插件"（顶栏按钮、tab 项、被收纳块），**用于可收纳搬移、无状态持久化、导航参数传递**，渲染时按 `SlotKey` 从插槽 lambda 取。两轨靠 **`SlotKey` 对齐**。

```kotlin
// 子级 = 命令为 @Composable 插槽（渲染轨） + 可选 SlotContent 装配码（描述轨）
@Composable
fun AppTopBarBlock(
    slotScope: SlotScope,                   // 装配上下文（当前 Screen 的 SlotKey 空间）
    nav: (@Composable () -> Unit)? = null,  // 返回钮插槽
    title: @Composable () -> Unit,          // 标题插槽
    actions: List<SlotKey> = emptyList(),   // 操作按钮装配码（可收纳/可持久化）
    actionSlots: Map<SlotKey, @Composable () -> Unit> = emptyMap(), // 对应插槽 lambda
)

// 块级 = 组合"命名插槽"的编排器（对标官方 Scaffold 插槽语言）；收纳 = 把某个 SlotKey 插到它的作用域
interface BlockSlot {
    val slot: BlockSlotKind
    val subKeys: List<SlotKey>              // 一块挂哪些子级（顶栏 tabs / 侧边栏收纳的块）
    @Composable fun Expose(scope: SlotScope)
}
```

- **收纳不重写组件**：`TopTabs` 从顶栏搬到侧边栏，只需把它的 `SlotKey` 从 `TopAppBar.subKeys` 移到 `SideRail.subKeys`（其插槽 lambda 原样复用），这就是"同一子级处处可插"的官方化实现。
- **与官方 scaffold 的映射**：`SlotScope` 即官方 scaffold 的 slot 参数空间；`AppAdaptiveNav`/普通页面的 `Scaffold`/`NavigableListDetailPaneScaffold` 是"编排基底"，`SlotKey` 装配图是"内容定价"。—— P0 落地时，**优先用官方 Scaffold/NavigationSuiteScaffold/NavigableListDetailPaneScaffold 组装，我们的 SlotKey 仅描述"谁放哪"，不重写布局骨架**。

#### 2.3.5 Screen 装配 `SlotSet`（声明式）

`*Screen` 只需声明挂载，不写布局：

```kotlin
currentScreen.SlotSet(
    topBar = TopAppBar.Block(           // 块级：顶栏
        title = SlotContent.Text("设置"),
        actions = [ SlotContent.Button(icon = Icons.Rounded.Add, tint=…, onClick={…}),
                    SlotContent.Button(icon = Icons.Rounded.MoreVert, tint=…) ],
        nav = IconButton(Back),          // 返回钮
    ),
    tabs = TopTabs.Block( items = [Tab("工具", Tool), Tab("Agent", Agent), Tab("技能", Skill)] ),  // 自适应
    content = Content.Block { ChatScaffold(...) },   // 叶子
    bottomTabs = BottomTabs.Block( items = […] ),    // 可选
    sideRail = SideRail.Block { /* 可收纳 topTabs / 其它块 */ },  // 可选悬浮窗
)
```

> **装配只做挂载**：标题文案/按钮/参数构造放 Screen，业务回调下沉到 ViewModel，遵循 §2.2 职责。
> **槽位树是设计级协议**：§2.3.4 的接口与 §2.3.5 `SlotSet` 为**待开发框架**；本轮 `docs/modules/ui.md`（若建）记录协议，落地列为 P1 首批（与 `AppIcon`/`AppLayout` 同批）。

#### 2.3.6 与静态四件套的关系

| 页面复杂度 | 采用 |
|---|---|
| 简单单区页（对话框/详情 toast/静态面板） | §2.1/§2.2 直接写（无需槽位树） |
| 多区 / 含 tab / 需收纳 / 需悬浮抽屉的页 | **§2.3 槽位模型**装配 |

> 两者**不冲突**：槽位模型是 `*Screen` 内部布局的一种实现，文件仍落 `presentation/` 四件套；`SlotSet` 可视为 `*Screen` 的骨架装配入口，不强加给所有页。

### 2.4 各槽位针对性细化

> 五个块级槽位各承担一个明确的布局职责。下表是**每个槽位的完整规格**：职责、子级构成（子槽位）、尺寸/令牌、自适应、交互、收纳、空置行为。框架接口 `BlockSlot.Slot` 落在 `core/ui/`，各槽位均为 `BlockSlotKind` 的标准化实现，Screen 经 §2.3.5 装配。

#### 2.4.1 顶栏槽位 `TopAppBar`

| 面 | 规格 |
|---|---|
| **职责** | 页面标题 + 主导航返回 + 一组操作按钮；顶部全局信息承载（在线态点、同步状态）。 |
| **子级构成** | `nav`（返回钮，二级页必填）；`title`（`SlotContent.Text`，`titleLarge`/`onSurface`）；`actions`（`SlotContent.Button*`，右端操作）；可选 `statusDot`（`AppStatusDot` 在线态）。 |
| **尺寸/高度** | `AppSizing.TouchTarget`(44dp) 定高；左右 padding `AppLayout.PageHorizontal`；进 remote 态吸到顶不预留 statusBars（全屏模式）。 |
| **阴影/分层** | 静止 `Elevation.z0`（无阴影，靠内容色）；**滚动抬升** `z2`（内容滚动过栏时给 1dp shadow，需父槽位上报 `scrolled` 布尔）。 |
| **交互** | `nav` 优先级 > `actions`；破坏性操作（删除/重置）前缀 `AppHaptics` + 二次确认（§3.11）。 |
| **收纳** | 可整体被 `SideRail` 收纳为子级（自定义 Siderail 收纳页）。|
| **空置** | 无 `title`/`nav` 的极简页（全屏终端）可不挂顶栏；缺省返回钮隐藏。 |
| **令牌对齐** | `AppTopBar`(§3.12) + `AppIcon` 尺寸 + `AppColor.onSurfaceVariant` tint。 |

#### 2.4.2 顶栏 Tab 槽位 `TopTabs`

| 面 | 规格 |
|---|---|
| **职责** | 页面内次级导航（同域 tab 切换）；窗口标题下方，与 `Content` 顶对齐。 |
| **子级构成** | `TabItem*`（子槽位：`Text`(icon+text) / `Text`(纯文) / `Icon+X`）；每项 = "按钮+文字"，选中态可加角标 `Badge`。 |
| **自适应** | §2.3.3 三策略：`FitContent`→`EqualWeight`→`Scrollable`；`availableWidth` 由父槽位 `onSizeChanged` 提供。 |
| **指示条** | 选中项下 2dp 指示器 `AppColor.primary`（`TabRow` indicator），圆角 `AppRadius.pill`。 |
| **选中态** | 文字 `onSurface`（选）/`onSurfaceVariant`（未选）；tint 同步（§3.6.5）。 |
| **交互** | 切换即替换 `Content`；可 `DisposableEffect` 重置内容状态；tab 单击反馈 `AppHaptics.click`。 |
| **收纳** | **可被 `SideRail` 收纳**：整组 `TopTabs.Block` 作为块级插入 `SideRail.subSlots`（T15 静态）。 |
| **空置** | 单 tab 或缺省 → 整体隐藏，`Content` 直达顶栏。 |
| **令牌对齐** | 高度 `TouchTarget`；tab 间距 `ItemGap`；指示色 `primary`；图标 `AppIcon.S/M`。 |

#### 2.4.3 内容槽位 `Content`

| 面 | 规格 |
|---|---|
| **职责** | 唯一"叶子"业务区，渲染 §2.1 的页面正文（`state`/`ViewModel` 内容）。 |
| **尺寸** | `weight(1f)` 吸满父槽位高；`contentPadding` 提供 `TopTabs` 同宽基准，宽屏 `widthIn(max=PageMaxWidth)` 居中。 |
| **滚动卫生** | 可滚动区必须包 `verticalScroll`/`LazyColumn`；禁整页 `fillMaxHeight` 平铺（§3.7.10）。 |
| **三态** | 页内走 §3.8 `AppState`：`Loading/Empty/Error/Content` 统一分支，禁自造。 |
| **insets** | `WindowInsets` 统一由 `AppLayout` 注入（§3.10）；终端/WebView 原生区旁路尺寸保留。 |
| **空置** | 无内容时展示 `AppEmptyState`（不保留空白粘堆）。 |

#### 2.4.4 底栏 Tab 槽位 `BottomTabs`

| 面 | 规格 |
|---|---|
| **职责** | 应用级主导航（跨域跳转），替换原"扩展脚手架"底部 map；与 `TopTabs` 一个协议。 |
| **子级构成** | `TabItem*`：常显 `Icon+Text`（`AppIcon.M`）；Badge（角标/红点）放图标右上。 |
| **与 TopTabs 差异** | 顶栏 tab 是域内切换，**底部 tab 是跨域**；选中持久（`LocalModNavigationState`/NavHost backStack）；高度 56dp（含 label）+ `navigationBars` inset；禁纵向压缩。 |
| **自适应** | 同 §3.7 三策略；应用级常 2–5 个 → 默认 `EqualWeight`。 |
| **选中态** | `primary` 色 icon+text / 灰 `onSurfaceVariant`；切换即导航，`NavHost` 维护 backStack。 |
| **收纳** | 一般**不收纳**（应用级定位移动到底/侧，桌面宽屏可收纳进 `SideRail` 垂直排列，作为增强）。 |
| **空置** | 单 Tab/无 → 隐藏；WebView 全屏等沉浸页收起。 |

#### 2.4.5 侧边栏悬浮窗槽位 `SideRail`

| 面 | 规格 |
|---|---|
| **职责** | 悬浮/抽屉式辅助区：收纳其它块级槽位（`TopTabs` 等）与子级插件，提供二级入口、快速跳转、密度摆放。 |
| **形态** | 采纳 M3 **navigation-rail 双态**：`Collapsed`（收叠窄栏，仅图标，3–7 项，不隐藏）/ `Expanded`（展开，图标+文字，可 modal 悬浮可 `Permanent` 常驻）。默认 `Collapsed`（中等屏），`SideRail.Layout`（`Drawer|Floating|Permanent`）切换形态。与 `BottomTabs` 同属 `AppAdaptiveNav`（§3.10），由断点自动换型。 |
| **子级构成** | 收纳的 `BlockSlot*`（如 `TopTabs`，T15 静态）+ `SlotContent*`（顶栏会长、快捷项、其它块）。 |
| **收纳交互** | 开启收纳 == 把目标块 `.subSlots[]` 插入自己；**先静态**（进/出一次布局切换，展开/收起动画后置 T15）。 |
| **开合状态** | `open` 由 Screen `SideRailState` 持有；`scrim` 点击/返回键关闭（`BackHandler`）；状态不跨屏泄漏（每屏 `rememberSaveable`）。 |
| **停靠边** | `SideRail.Edge`(`Start|End`) 令牌化，单屏固定一边，禁运行时左右横跳。 |
| **内容宽** | `widthIn(max=320dp)`（`Layout` 令牌）自适应；内容 `verticalScroll`。 |
| **a11y** | 打开时焦点迁入抽屉、关闭返回触发器；scrim 关闭有 `onDismiss`（§3.13）。 |
| **空置** | 未挂任何块/子级 → 整个 `SideRail` 不渲染（不占位）。 |

#### 2.4.6 子级槽位规格

统一「按钮/文字/tab」三级子槽位，全部复用 §3.6/§3.12 组件，可插到上述任一块级 `subSlots`：

| 子级槽位 | 渲染 | 尺寸 | tint | 备注 |
|---|---|---|---|---|
| `Title`(`Text`) | `AppType.titleLarge` | 高度随栏 | `onSurface` | 顶栏标题；可 `SlotText.iconless` |
| `ActionButton`(`Button`) | `IconButton`(40dp) 内 `Icon(Rounded)` | `AppSizing.IconButton` | 见 §3.6.4 | `contentDescription` 语义，禁装饰位 |
| `TabItem`(`Text/Icon`) | `Tab` = 按钮 + label | `TouchTarget` | 选中`primary`/未选灰 | 指示条见 §2.4.2 |
| `statusDot` | `AppStatusDot` | 8dp | `Brand.StatusGreen` | 顶栏在线态 |
| `Badge` | `BadgedBox` + 数字/红点 | 16dp | `error` | 底部/顶栏 tab 角标 |

> 子级是**最小可复用手**（`SlotContent`），块级是**承载区**（`BlockSlot`）；同一 `SlotContent` 可出现在顶栏或收纳处，实现"一套子级、处处可插"。

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
>
> 📌 **对标业界（令牌分层 + W3C DTCG 2025.10）**：
> - 三层模型已对齐业界共识 **Primitive → Semantic → Component**（与 M3 reference/system/component、SLDS base/alias/component 同构）；本文档序列表即"component 层之上"的命名事实源。
> - **落地建议升级**：value 定义采用 **W3C DTCG 2025.10 稳定版 JSON** 单一源（`$value/$type/$description/$deprecated` + 别名引用 `{color.brand.primary}`），用 **Terrazzo / Style Dictionary** 生成 Kotlin-Compose 常量，**替代手写 `object AppXxx`** → 改色/改间距只改 tokens 文件，一套定义驱动全部。
> - **命名规范借鉴**：token 名用 **scoped prefix**（`color-`/`spacing-`/`elevation-`/`motion-`），**禁止仅靠大小写区分**（如 `Primary`/`primary`）；分组按**功能**（text/bg/border/surface）而非纯按类型平铺；别名要校验**无循环/无缺失引用**。
> - **Resolver 思路**：Light/Dark（及潜在品牌变体）用**组合规则**切换 `semantic` 层（value 指向哪个 primitive），**不复制**整份色板——这正是序列表"只改表层"的价值放大。
>
> **★ 落地样例（DTCG 2025.10 + 生成器通路，深度化）**：序列表（文档）为人工事实源，**转一份 `tokens/*.tokens.json`**（DTCG 格式），由生成器产出 Compose 与预览船型，改色只改 JSON：
>
> ```jsonc
> // tokens/color/primitive.json —— 基元
> { "color": { "brand": {
>     "primary": { "$value": "#6C5CE7", "$type": "color", "$description": "品牌主色" },
>     "surface": { "$value": "#F7F8FA", "$type": "color" } } } }
> // tokens/color/semantic.json —— 语义（引用基元）
> { "color": { "text": {
>     "primary":   { "$value": "{color.brand.primary}", "$type": "color" },
>     "onSurface": { "$value": "{color.ink.d900}",       "$type": "color" } } } }
> // tokens/color/mode.json —— Light/Dark 组合规则（Resolver）
> { "color": { "scheme": {
>     "dark": { "$value": { "source": "{color.brand.primary}" }, "$type": "colorScheme", "$description": "dark 方案下语义层如何解析" } } } }
> // tokens/component/button.json —— 组件令牌（三层之顶）
> { "button": { "background": { "default": { "$value": "{color.text.primary}", "$type": "color" } } } }
> ```
>
> ```kotlin
> // Style Dictionary / Terrazzo 生成到 core/theme/token/generated/：
> @Immutable object AppColors { val Primary = Color(0xFF6C5CE7); val OnSurface = Color(...) }
> ```

通路：`tokens/*.json`(源) → 生成器(Dictionary/Terrazzo) → `core/theme/token/generated/*.kt` + Compose `ColorScheme`/Typography 装配 → 组件引用 Component 层。**校验**：生成器加"别名无循环/无缺失、无重复 uri"插件（Style Dictionary 有现成 transforms）；`generated/` 与 tokens 逐一 CR 同步。

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

### 3.6 图标体系 `AppIcon` —— 完整规范

**现状**：345 处 `material.icons` import + 244 处 `Icons.*`，`Rounded/Outlined/Filled` 三态混用、尺寸 20/18/24 混用、图标块背景全散落。以下为完整图标规范。

#### 3.6.1 来源与方法
- 全局统一 Material Icons **`Rounded`**（拟合圆角调性）；`Outlined` 仅用于"空/呼起"层级、`Filled` 仅用于"已选中/实心状态"，二者另作有意区分，**默认一律 Rounded**。
- 业务专属图形（如 Provider logo、容器标识）走 `ProviderLogo`/自定义 ImageVector，**不占 Material 图库命名**，统一放 `core/ui/icon/`。
- 新增图标先查组件集目录，禁止各页面手绘 `ImageVector` 堆 `ImageVector.Builder`（除非有统一收录）。

#### 3.6.2 图标尺寸刻度（唯一刻度，禁中间值）
| 令牌 | 值 | 用途 |
|---|---|---|
| `AppIcon.Xs` | 16.dp | 角标/极小装饰 |
| `AppIcon.S` | 18.dp | 搜索/输入区、进度旁、次级行内 |
| `AppIcon.M` | 20.dp | 顶栏图标、行内图标（默认档） |
| `AppIcon.L` | 24.dp | 首页/大按钮/空态主图标 |

图标实际渲染文字对齐：`Modifier.size(AppIcon.X)` 固高，`Icon` 内部自动居中。

#### 3.6.3 图标块 `IconContainer`（彩底白图标，Material You 设置页风格）
| 属性 | 值 | 说明 |
|---|---|---|
| 尺寸 | `AppSizing.IconButton`(40.dp) | 外围可触点在此之上 |
| 圆角 | `AppRadius.sm`(8.dp) | 图标块方角 |
| 背景 | `AppColor.iconBlock`（语义） | 供 `iconBg` 传入的彩色块 |
| 图标色 | 白 `Color.White` | 与彩色块成对（§3.4） |

**两种图标展示**：
- **纯线条图标**（无块）：tint 走 §3.6.4，尺寸 `AppIcon.M`。
- **图标块图标**（`AppMenuRow`/设置行）：`Box(40dp, clip sm, bg iconBlock){ Icon(White) }`。`iconBg` 覆盖时 → 彩色块白图标；否则 → 灰块灰图标（兼容 About 等复用方）。

#### 3.6.4 图标色彩 `tint` 规则
| 场景 | tint |
|---|---|
| 可点击/行内图标 | `AppColor.onSurfaceVariant`（默认） |
| 主色强调（选中等） | `AppColor.primary` |
| 功能语义 | `ChatAccent.<Tone>.resolve()`（Build/Plan/Auto/…） |
| 状态点 | `Brand.StatusGreen.Light/Dark` |
| 禁用 | `AppColor.disabled` |
| 装饰（非交互） | `onSurfaceVariant.copy(alpha=0.4-0.6)` 统一档 |

#### 3.6.5 图标交互状态
- pressed/selected/hover/disabled 跟随 §3.11；可点图标外圈放 `AppSizing.IconButton` 点击区（防 44dp 触控违规）。
- 选中图标（如筛选 Chip）tint 升为 `primary`，未选中 `onSurfaceVariant`——对比满足 §3.13。

#### 3.6.6 语义图标映射表（同义词优先引用，防一人一图标）
| 语义 | 首选图标 | 备注 |
|---|---|---|
| 返回 | `ArrowBack`(AutoMirrored) | 顶栏统一 |
| 设置 | `Settings` | 全局唯一 |
| 搜索 | `Search` | 搜索栏唯一 |
| 终端/命令 | `Terminal` | 终端入口 |
| 工作区/文件夹 | `Folder` | 统一 |
| Git/分支 | `GitBranch` | 统一 |
| 文件 | `Description` / `Article` | 阅读页 |
| 新增 | `Add` | 统一 |
| 删除 | `DeleteOutline` | 破坏性前置确认 |
| 导出/分享 | `Upload`/`Share` | 会话导出 |
| 关闭 | `Close` | 弹窗/Sheet |
| 提示/信息 | `InfoOutline` | Toast/提示 |
| 警告/错误 | `WarningAmber`/`ErrorOutline` | 告警态 |
| 播放/检查(状态点) | 圆点 `Box` 自绘 | 不用图标 |

#### 3.6.7 内容描述
- 语义图标：`contentDescription` 必填，走 `strings.xml`。
- 纯装饰/冗余（旁有文字重复）：`contentDescription = null`；禁 `""`（TalkBack 会当作语义空）。
- 图标块内图标视为语义需要时补描述；否则 `null`。

#### 3.6.8 对齐与文字图标
- 行内图标（`AppIcon.M`=20dp）与 `bodyMedium` 文字垂直对齐：`Row(verticalAlignment = Alignment.CenterVertically)`，行高由 `AppSizing.TouchTarget`(44dp) 定，勿用 `line height` 图配。
- 图标 + 文本间距 `AppSpacing.sm`(8dp)；图标块 + 文本 `AppSpacing.md`(12dp)。
- 禁用 `.size(Dp.Hairline)` 缩放图标到文字（破坏字形节奏）。

#### 3.6.9 自定义/业务图标唯一规则
- 新业务图标必须进 `core/ui/icon/` 并登记本节映射表；禁止散落页面内联。
- 动态/渐变图标（如 `CyberStatCard` 刷子文字）仅用于深色彩效点缀，统一协议 `gradientTextStyle`（收 `core/ui`）。

> 迁移：`CyberSearchBar`(18dp/`#F2F3F5`)、`CyberMenuRow`(灰块灰图标)、`AppTopBar`(`AppIcon.M`=20dp 已对齐) 等 P1 起迁到本节规范。

### 3.7 布局体系 `AppLayout` —— 完整规范

**现状**：`padding(horizontal=16/14/12)`、`Arrangement.spacedBy(...)`、`weight` 散落。以下为完整布局规范。

#### 3.7.1 页面栅格与边距
| 令牌 | 值 | 用途 |
|---|---|---|
| `PageHorizontal` | `AppSpacing.lg`(16.dp) | 标准页面左右留白（默认） |
| `PageTop` | `AppSpacing.lg` + `statusBars` inset | 内容页首留白 |
| `PageMaxWidth` | 720.dp | 宽屏内容上界（居中），防排版拉爆 |
| `ContentTop/Bottom` | `AppSpacing.lg` | 页尾留白 |

两列窄分区（如"项目/描述"小屏自适应）：紧凑用 `PageHorizontal=16`，图标块场景侧边 `=AppSpacing.lg`。**禁止页面同时出现两种水平留白**（对齐参考线唯一）。

#### 3.7.2 间距刻度层级（值都来自 `AppSpacing`）
| 层级 | 值 | 语义 |
|---|---|---|
| `StackGap` | `xl`(24dp) | 逻辑区块之间 |
| `BlockGap` | `lg`(16dp) | 同区块内分组之间 |
| `RowGap` | `sm`(8dp) | 行内元素/标题与副标题 |
| `ItemGap` | `md`(12dp) | 列表项、字段间距 |

每行铺满时用 `Arrangement.spacedBy(对应 Gap)`，**禁手写 3/6/10/14 等表外值**（§3.2 序列表）。

#### 3.7.3 三区块层级（页面 → 区块 → 行）
```
页面  | Surface(page bg)
  └─Block  | 逻辑区块（SectionHeader + AppSectionGroup）
       └─Row | 列表行/字段行（可点用 AppMenuRow/ListRow）
```
- **区块（Block）**：语义标题（`AppSectionHeader`）+ 统一分组容器（`AppSectionGroup`/`AppCard`）。
- **分组（Group）**：内短行自间距 `RowGap`，组间 `BlockGap`。
- **行（Row）**：高定 `Sizing.TouchTarget`，可点 + ripple（§3.11）。

#### 3.7.4 标准页面模板（新页面唯一骨架）
```
Surface(fillMaxSize, page bg) {
 Column(PageTop, weight(1f), bottom nav 可选) {
   AppTopBar(title="…")            // §3.12，非必选
   LazyColumn / Column(verticalScroll)(PageHorizontal) {
     SectionHeader + AppSectionGroup { … }   // 区块1
     StackGap
     SectionHeader + AppSectionGroup { … }   // 区块2
     ItemGap …
   }
 }
}
```
- **可滚动区块必包 `verticalScroll`/`LazyColumn`**（防大屏/旋转溢出）。
- 页面自身**禁用整页 `fillMaxHeight` 平铺**（留系统 inset，保持内容流式）。

#### 3.7.5 表单布局模板（AddProviderSheet/Mcp 等）
```
每字段组:
  Label (labelMedium, onSurfaceVariant)
  OutlinedTextField / 控单   // 统一 TextFieldToken(§3.12)
  校验错误 (error, bodySmall)
字段间: ItemGap=12
版面: 一列为主；两字段并排仅当短值(host/port)用 Row+weight
保存条: 底部 Button 全宽 (FilledTonal / Button)
```
- 字段标签一律 `labelMedium` + `onSurfaceVariant`，错误文案走 `error`（§3.4）。
- 校验错误紧贴字段下，业务文案走 `strings.xml`。

#### 3.7.6 列表布局模板（设置/技能/凭据列表）
```
LazyColumn(PageHorizontal verticalScroll) {
  ListRow/AppMenuRow {
    IconContainer | 标题(weight1f) | 副标题(可选) | 尾部(chevron/开关/数值)
  }
  HorizontalDivider(padding start=68 dp 对齐行 icon 右侧)  // 分割线左缩进与 row 对齐
}
```
- 行高 `TouchTarget`；分割线 `AppLayout.DividerThickness`（1dp）并 `padding(start)` 对齐图标块右缘（沿用 `CyberMenuRow` 既有 68dp 起点）。
- 尾部控件（开关/chevron）保持 `ItemGap` 距，禁挤贴行尾。

#### 3.7.7 对齐规则
- 行内：图标与 `bodyMedium` `CenterVertically`；名称/主文本 `weight(1f)` 左对齐；副文本 `bodyMedium/onSurfaceVariant`。
- 数字/状态：右对齐尾列（`Alignment.End`）。
- 标题/区块：左对齐 + `AppSectionHeader` 装饰条（§3.12）。
- 按钮条：底部操作全宽；靠右主操作 `Arrangement.End`。
- **统一骨架 `Row`**，禁交叉用 `Box` + 绝对 `padding` 堆对齐。

#### 3.7.8 尺寸与约束
- 宽屏：内容 `widthIn(max=PageMaxWidth)` 居中（§3.10）；卡片宽自适应。
- 元素高：按 `AppSizing`；文本行高走 `AppType`，勿改 Dp 硬对齐。
- 行 icon 文本：图标 `AppIcon.M` + 文本 `bodyLarge`，行高 `TouchTarget`=44。

#### 3.7.9 弹窗 / Sheet 布局
- `AlertDialog`：宽度 `PageMaxWidth` 内；内 `pad=AppSpacing.lg`；按钮排底部 `ActionRow(End)`。
- `ModalBottomSheet`：内容 `Column(verticalScroll)`，最大高 87% 屏，超出滚动；顶部拖拽 bar + 可选 `AppTopBar`。
- 陷阱：`fillMaxWidth`+固定 `px` 双写成因屏不同价 → 统一 `Dp` 令牌 + 断点（§3.10）。

#### 3.7.10 布局卫生禁止项
- ❌ 页面同时两种水平留白（对齐基线唯一）。
- ❌ 整页 `fillMaxHeight` 平铺（应滚）。
- ❌ 分工消耗 `spacedBy(3/6/10/14)` 表外值。
- ❌ 图标 `.size(文字高度)` 缩放；文字 `Dp` 硬对齐替代行高。
- ❌ `weight(1f)` 滥用（顶栏 trailing/行尾按钮除外）导致空白失衡。
- ✅ 一律 `AppLayout` + `AppSpacing`，新页面入模板（§3.7.4）。

> 对齐：`AppLayout` 只引用 `AppSpacing`/`AppSizing`，不新造数值（§3.2 序列表纪律）。

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

> 📌 **对标业界（Sealed-State 模式）**：`UiState` 用 `sealed interface { Initial / Loading / Error / Empty / Content }` 定义，让 `when` 编译器强穷尽（漏分支即编译报错），杜绝"忘了错误态"。三个补充约定采纳：
> 1. **Operation 与页面态分离**：表单"提交中/提交成功/提交失败"等**独立子状态**用单独 `sealed class OperationState { Idle/Submitting/Success/Error }` 挂到主 `UiState`，**不要塞进整屏四态**（避免整屏进 loading）。
> 2. **单次事件（OneTimeEvent）隔离**：Toast/导航/Snackbar 属一次性事件，用 `Event<T>`（或 `Channel`）单独投递，**不落进持久 `UiState`**（否则旋转屏幕会重放 Toast）。
> 3. **容器化 StatusBox**：加载/空/错可用统一容器包内容（loading 居中 spinner、error 中心图标+文案+重试、底部加载用 tick 指示），对齐 `AppLoadingState`/`AppErrorState` 插到 `Content` 内部任何区块（列表底部、局部卡片），不强制整屏。

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
| **断点** | 对齐 M3 最新 **五档 breakpoints**（取代三档 window size class）：紧凑 <600 / 中等 600–839 / 展开 840–1199 / 大 1200–1599 / 特大 ≥1600dp。移动为主页默认 Compact，宽屏按 840 升档启用多窗。 |
| **导航自适应（关键借鉴）** | 采纳 M3 **`NavigationSuiteScaffold`** 思想：`BottomTabs ↔ SideRail` 由断点自动切换——Compact 用底栏 tab，中等用收叠 `SideRail`(rail)，展开用展开 rail。→ 落地为 §2.4 `BottomTabs` 与 §2.4.5 `SideRail` 的**统一 `AppAdaptiveNav` 槽位**，一处换形态。 |
| **可滚动区块** | 长内容一律收进 `verticalScroll`/`LazyColumn`；禁用整页 `fillMaxHeight` 平铺（段用户平板/折叠屏顶部溢出） |
| **最大内容宽** | 宽屏下内容区 `widthIn(max=PageMaxWidth)`（建议 720dp）居中，避免排版拉爆 |
| **抽屉/弹窗** | Sheet/Dialog 宽度用 `Dp` 令牌 + 断点控制，不用固定像素 + `fillMaxWidth` 双写 |
| **状态栏/insets** | 一律 `WindowInsets` + `AppLayout` 令牌，禁 `statusBarsPadding()` 与 `WindowInsets` 混用（收口到 `AppLayout`） |
| **终端/WebView** | 原生 View 区高度用旁路尺寸（`Modifier.weight`/`Box` 约束），不做 breakpoint 走势（已有 MTerminal 逻辑保留） |

> **对"reveal/divide/resize/reposition/swap"的回应**（M3 逐断点决策法）：每档只回答"隐藏/展开哪块、面板如何划分/重排/互换"，不做整个页面重写。响应式不改变现有手机端布局，只在新页面与大屏检测时应用；折叠屏中隔断仅作 diff 级收口，不重构既有页。

**`AppAdaptiveNav` 具体机制（对标 `NavigationSuiteScaffold` 深度化）**

`BottomTabs` 与 `SideRail` 不再分别写，统一定义为 `AppAdaptiveNav`：由**断点决策函数**在三种 `NavSuiteType` 间切换（对齐官方的 `NavigationSuiteType.NavigationBar/Rail/Drawer`）：

| 断点 | `NavSuiteType` | 形态 |
|---|---|---|
| <600dp | `BAR` | `BottomTabs` 底栏 |
| 600–839dp | `RAIL_COLLAPSED` | `SideRail` 收叠窄栏（仅图标） |
| 840–1599dp | `RAIL_EXPANDED` | `SideRail` 展开（图标+文字，`Permanent` 常驻） |
| ≥1600dp | `DRAWER`(可选) | 展开侧栏/`PermanentModal` 大抽屉 |

```kotlin
@Composable
fun WindowWidthSizeClassToNavSuiteType(width: Int): NavSuiteType {
    return when {
        width < 600 -> NavSuiteType.BAR
        width < 840 -> NavSuiteType.RAIL_COLLAPSED
        width < 1600 -> NavSuiteType.RAIL_EXPANDED
        else -> NavSuiteType.DRAWER
    }
}

@Composable
fun AppAdaptiveNav(
    navSuiteType: NavSuiteType,           // 由断点决策函数给出
    items: List<AppNavItem>,              // 同一组搭配项（icon+label+route）
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navSuiteType.items.forEach { item ->
                item(item.icon, item.label, selected = item.route == currentRoute) {
                    onNavigate(item.route)
                }
            }
        },
        layoutType = navSuiteType,        // 委托官方换形
        content = { /* 页面正文 */ },
    )
}
```

- **状态保持**：切换类型时**不重建选中项**——`currentRoute` 取自 `NavHost` backStack/`LocalModNavigationState`，`onNavigate` 只改 route；跨断点换 shape 不丢当前页。
- **切换动画**：`layoutType` 变化用 `AnimatedContent`（或 `MutableTransitionState`）平滑过渡（可后置，T15 同批）。
- **懒加载对齐**：`RAIL` 用 `NavigationRailItem`，`BAR` 用 `NavigationBarItem`，`DRAWER` 用 `ModalNavDrawer`+rail —— 全套直接复用官方组件，不手写 tab 布局。

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
| **区块标题** | `AppSectionHeader`（左竖条装饰 + label） | 页面区块统一标题样式（§3.7.3/3.7.7） |
| **图标** | `AppIcon`（Rounded / 尺寸刻度）| 语义映射见 §3.6.6；禁止裸手绘 ImageVector |
| **图标块** | `IconContainer`（40dp 色块 + 白图标） | `AppMenuRow`/设置行统一形态（§3.6.3） |
| **分组容器** | `AppSectionGroup` / `AppCard`（收 `CyberCard`） | 页内区块统一形态 |
| **触觉反馈** | `AppHaptics.click`（封装 `LocalHapticFeedback`） | 破坏性操作二次确认（§3.11） |
| **富文本/工具消息** | `MarkdownContent`/segment 体系（跨模块） | 聊天独有，收敛 `core/ui` |

> 组件集目录为**新增页面默认首选**；确需 M3 原生替代时标注理由。图标/布局两套完整规范见 §3.6 / §3.7（组件需对齐其令牌与禁止项）。

#### 3.12.a 分子组建族（高级动效扩展，`:newui` 已落地其一）

在基础组件之上，扩展一支**高动效分子组建族**，统一对齐 Design Tokens（`AppMotion` 时长/缓动）、`AppRadius`/`AppSizing`/`AppColor`，动效参照 Linear/Vercel 的 Precision Micro-interactions（150–300ms、FastOutSlowIn/弹簧缓动、骨架 shimmer、列表 stagger）。已实现组件以 `✅` 标注：

| 域 | 组件 | 动效/样式要点 |
|---|---|---|
| **反馈** | `AppShimmerBox` ✅ | 骨架微光：`surfaceVariant` 底 + 周期扫光（`LinearEasing` 无限过渡），加载占位替代灰色块 |
| | `AppToast` ✅ | 底部滑入+淡出卡片（`slideInVertically`+`fadeIn`），icon+文案，宿主驱动自动消失 |
| | `AppProgressBar` ✅ | determinate，`animateFloat`+`FastOutSlowIn` 平滑过渡，pill 圆角 `StrokeCap.Round` |
| | `AppBadge` / `AppBadgeDot` ✅ | 数字徽标（N+ 折叠）弹簧回弹缩放 / 8dp 红点，覆盖图标右上角 |
| | `AppInlineAlert` ✅ | 四色调低饱和底（Info/Success/Warning/Danger）+ 品牌图标 + 可选关闭，顶入滑出+淡出 |
| **表单** | `AppSwitch` / `AppSwitchRow` ✅ | 迷你弹簧开关：knob 弹簧回弹 + 轨道色过渡；行组件带标题/副标题 |
| | `AppSegmentedToggle` ✅ | pill 分段：surface 高亮块随选择滑动（`animateDpAsState`），选中加粗浮起 |
| | `AppStepper` ✅ | 步进器：加减按钮 + 数值 `AnimatedContent` 数字过渡，min/max/step 受控 |
| | `AppSearchBar` ✅ | pill 搜索栏：前置放大镜 + 占位浮字 + 可清空，行高 `TouchTarget(44dp)` |
| | `AppTextField` ✅ | 统一输入：标签浮起/下沉、聚焦色、可选 error/消息，对齐 baseline |
| | `AppSlider` ✅ | 高级滑杆：thumb 上方浮动态数值气泡（`animateDpAsState` 跟随），平滑取值 |
| | `AppRatingBar` ✅ | 星级评分：点选星星弹簧放大回弹（`spring`+`graphicsLayer` scale），容量 max/色可配 |
| | `AppCheckRow` / `AppCheckbox` ✅ | 复选行：勾选态弹性缩放+颜色过渡（`animateColorAsState`+spring），带副标题 |
| | `AppTagInput` ✅ | 标签输入：回车添加 chip（`scaleIn`+`expandHorizontally` 回弹进入、可删），FlowRow 自适应换行 |
| **数据展示** | `AppStatCard` ✅ | 统计卡：数字 count-up（`animateFloat`）、趋势徽标、可选图标块、描边+阴影 |
| | `AppAvatar` ✅ | 品牌渐变圆 + 首字母，可选右下在线/离线状态环 |
| | `AppBreadcrumb` ✅ | 面包屑：ChevronRight 分隔，末级加粗高亮，超长用 `…` 折叠 |
| | `AppRingProgress` ✅ | 环形仪表：品牌 `sweepGradient` 渐变扫环 + 中心百分比 count-up，替代朴素进度环 |
| | `AppSparkline` ✅ | 迷你趋势线：折线自左向右生长（`PathMeasure`）+ 面积渐隐渐变，结尾高亮圆点 |
| | `AppMiniBarChart` ✅ | 迷你柱状图：柱体自下而上交错生长（smoothstep）+ 纵向渐变，highlight 高亮柱 |
| | `AppStatusDot` ✅ | 状态圆点 + 可选文本标签（Success/Warning/Danger…） |
| **操作** | `AppFAB` ✅ | 扩展式 FAB：pill 品牌胶囊，标签横向展开（`expandHorizontally`）+ 内边距动画 |
| | `AppSwipeAction` ✅ | 滑扫操作：右滑露出底部动作区（`detectHorizontalDragGesures`+`animateDpAsState` 回中/展开） |
| | `AppFileCard` ✅ | 文件卡：状态着色图标 + 上传动态进度条 + 终态图标（Check/Error） |
| | `AppButton` ✅ | 统一按钮：Filled/Tonal/Outlined/Text 四变体 + 禁用，触感回馈（`AppHaptics`） |
| **导航** | `AppBreadcrumb`（同上） | 深层路径定位，比 Tab 更轻量 |
| | `AppTabs` ✅ | 滑动指示器 Tab：指示条 `animateDpAsState` 平滑游动，文字色/粗细跟随 |
| | `AppFilterChips` ✅ | 过滤芯片组：横向可滚动多选，选中勾号回弹缩放+填充色过渡 |
| | `AppAccordion` ✅ | 折叠面板：箭头 90° 旋转 + `expandVertically/shrinkVertically` 内容展开收起 |
| | `AppPagination` ✅ | 分页：页码+省略号+前后翻页，活跃页码弹簧放大浮起 |
| | `AppKeyCap` / `AppKeyCombo` ✅ | 拟物键帽（底部暗边+轻投影）及快捷键组合（⌘K / Ctrl+⇧P 串联） |
| | `AppMenuRow` ✅ | 列表行：图标块/纯色前置/尾随插槽，分组内结构统一 |
| | `AppSectionHeader` / `AppSectionGroup` ✅ | 区块标题（装饰条）+ 分组容器（圆角描边内聚同类项） |
| | `AppTimeline` ✅ | 垂直时间线：节点回弹色点 + 连接线 + 条目交错浮现，色调/图标/时间可配 |
| | `AppProgressSteps` ✅ | 步骤进度：已完成对勾回弹、当前节点脉冲光环、连线从左向右填充 |
| **通知** | `AppNotificationItem` ✅ | 通知项：左滑浮现 + 未读色点 + 圆角图标底，标题/正文/时间 |
| **AI 对话** | `AppChatBubble` ✅ | 会话气泡：入场上浮+淡入（spring），用户/assistant 双色异形圆角 |
| | `AppTypingIndicator` ✅ | 正在输入：三点错峰波浪弹跳（`rememberInfiniteTransition`+正弦），呼吸透明度 |
| **流程占位** | `AppSkeletonList` ✅ | 骨架列表：行内条块 shimmer，行间 stagger 淡入（`delay`+`expandVertically`） |
| | `AppDialog` ✅ | 弹窗：Confirm/Danger 双主色，遮罩滑入，破坏性提醒 |

> **族集命名统一 `App*`**（atom/molecule 同源），全部走 `component/molecule/`。当前已覆盖反馈 / 表单 / 数据展示 / 操作 / 导航 / 通知 / AI 对话 / 流程占位八大域的 **60+ 组件**，动效全部对齐 `AppMotion` 时长与缓动（FastOutSlowIn / 弹簧 / 交错）。候选后续：命令面板 `AppCommandPalette`、树形节点 `AppTreeItem`、对比热力图 `AppHeatmap`。

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

## 7. 路线图（并行双子塔：独立新版 → 样板验证 → 逐步迁移）

> **策略（负责人已拍板）**：独立起一套全新的 `:newui` Gradle 模块承载全新 UI 层，**不动旧版 `:app` UI**（旧版继续可用、可发版）；在新的独立 UI 层内**完整落地本设计**；落地后用**样板页**验证并逐步敲定；敲定后**再逐步迁移**旧页面；全部迁移完才收口旧版。

**分批收敛标准（每批通用）**：不破坏现有功能（`:app` 零依赖 `:newui` 前旧版不受影响）、每批 `./gradlew :app:assembleDebug` 通过、文案走 `strings.xml`、每批同步 `docs/modules/<module>.md` 与索引、编译型改动提交前过单元测试。

- **S0 起新版地基（本轮，对 `:app` 零改动）**：
  - 本文档经评审转正 `✅`，§8 待拍板项定稿。
  - 新建独立 module `:newui`（Compose + Material3 + adaptive），`settings.gradle.kts` 注册；`:app` **暂不依赖**（强隔离）。
  - 令牌：`tokens/*.tokens.json`(DTCG) + 生成器 → `:newui` 内 `generated/App*`；**完整独立 M3 主题**（AppColorScheme/AppTypography/AppShape，含暗色，不碰旧 theme）。
  - 骨架：原子包 + 四件套约定 + `AppTopBar`/`AppLayout`/三态 `AppState` + 槽位框架 `SlotSet`/`BlockSlot`/`SlotKey`（§2.3.4b）。
  - **样板页 `DesignGallery`**：在一个演示页内把令牌/原子组件/骨架/三态/槽位/`AppTopBar` 全部陈列演示，供负责人检验并敲定「基本完整落地」。
- **S1 补齐新版能力**：`AppAdaptiveNav`(BAR/RAIL/DRAWER) + 全部原子/分子组件 + §3.12 组件集目录 + 暗色完整 + 无障碍（§3.13）。新版在独立预览/测试路由可用。
- **S2 逐步迁移**：优先高复用/高价值页（settings → agent-chat …），按 `:app` 页面逐个接入 `:newui` 产出，用 **路由替换/feature toggle** 切换默认并支持回退；每迁一页验证 + 模块文档同步。
- **S3 收口**：旧版原子/`CyberComponents` 与 `:newui` 去重后移除；`:app` 依赖收口为"只引 `:newui` 产出"；`MainActivity` 瘦身到槽位组装；全量 `/docs/modules` 与索引核对；tokens 单一源最后确认。

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
| T13 | 槽位模型 | ✅ 落地为框架 API（`core/ui/` 提供 `SlotContent`/`BlockSlot`/`SlotSet`） |
| T14 | Tab 自适应 | ✅ 三策略默认（`FitContent`/`EqualWeight`/`Scrollable`）；舍弃 `OverflowCollapse` 本轮 |
| T15 | 侧边栏收纳 | ✅ 本轮先静态挂载（收纳/切一次布局，展开动画后置） |
| T16 | 令牌落地用 DTCG 2025.10 + 生成器 | ✅ 已采纳（§3.2 附带 JSON 样例与生成通路） |
| T17 | 底栏/侧边栏合一 `AppAdaptiveNav` | ✅ 已采纳（§3.10 附机制与代码） |
| T18 | 新版 UI 载体 | ✅ 独立 Gradle 模块 `:newui`，`:app` 暂不依赖 |
| T19 | 新版主题 | ✅ 完整独立 M3 主题（AppColorScheme/AppType/AppShape，含暗色，不碰旧 theme） |
| T20 | 首段落地范围 | ✅ 最小可自证集：令牌全部 + 原子组件 + 四件套 + `AppTopBar`/`AppLayout`/三态 + 槽位框架 `SlotSet` |
| T21 | 迁移策略 | ✅ 先建样板页 `DesignGallery` 验证敲定，再逐步迁移（§7 S0→S3） |

## 9. 门禁与配套

- 沿用既有 `.githooks/pre-commit` + `commit-msg`；后继可选扩展"component 单数、*Screen 位置、新增 `.kt` 含魔法色值/圆角/高度"轻量校验（后续增强，非本轮）。
- 本设计定稿后由各 `docs/modules/<module>.md` 反映落地实现，两处不重复维护（依 `docs/plan-docs/README.md`）。

## 10. 评审记录

> T1–T4 已由负责人拍板并回填 §8（M3 圆角 / M3 全阴影规格 / 44dp 紧凑 / 并入单色板）；T5 默认精简够用；T13–T15 槽位模型已拍板（框架 API / 三策略 / 静态收纳）；T16–T21 并行双子塔实施策略已拍板（独立 `:newui` 模块 / 完整独立主题 / 最小自证集 / 样板页先行）。
> ⚠️ 本文档仍为 `📝 草案`：待负责人对整份设计（骨架 + 令牌 + 槽位 + 路线图）做最终确认后，将状态转正为 `✅ 已评审` 再进入 S0 实施。

## 11. 业界高光设计对标（学习借鉴，2026-09）

依负责人要求，对本设计方案逐主题全网检索对标，以下为**采纳结论**（对应正文已标注 `📌`/`✅`），供优化思路复盘。

| 我们的设计 | 业界高光参照 | 采纳动作 |
|---|---|---|
| 页面槽位模型 §2.3 | **Compose Slot API**（`HomeSection(title, content…)` 插槽约定）+ 官方 **`NavigableListDetailPaneScaffold`**(list/detail/extra pane) + `ThreePaneScaffoldNavigator` | 印证"插槽/面板 = 组装元"，槽位模型是官方 slot 约定的高阶封装；落地框架时**复用官方 pane scaffold/slot 语义**，不重复造轮子 |
| 槽位/导航自适应 §2.3/§3.10 | M3 **`NavigationSuiteScaffold`**：按 WindowSizeClass 在 bottom bar / rail / 展开 rail 间自动切换 | 采纳：`BottomTabs↔SideRail` 合一为 **`AppAdaptiveNav`** 槽位，断点换形（§3.10） |
| 侧边栏悬浮窗 §2.4.5 | M3 **navigation-rail**：collapsed(3–7项不隐藏)/expanded、可 `Permanent`/modal | 采纳：`SideRail` 改 **Collapsed/Expanded 双态**（§2.4.5） |
| 令牌序列表 §3.2 | **W3C DTCG 2025.10 稳定版**（2025-10-28）+ Style Dictionary / Terrazzo / Tokens Studio | 采纳：落地用 DTCG JSON + 生成器产出 Compose，替代手写 object；命名 scoped prefix、禁大小写-only（§3.2） |
| 响应式 §3.10 | M3 **五档 breakpoints**(600/840/1200/1600) + "reveal/divide/resize/reposition/swap"决策法 | 采纳：三档升**五档**（§3.10），每档只做增删/重排 |
| 状态组件 §3.8 | **sealed UiState** 强穷尽 + `OperationState` 独立 + `OneTimeEvent` + **Compose-StatusBox** 容器 | 采纳：三者约定补入 §3.8 |
| 总组件库分层 §3.9 | M3 reference/system/component + IBM Carbon layer | 印证 atom/molecule/screen 与 token 同构，无新增改动 |

**新增待拍板（待负责人确认后回填 §8）**：
| # | 事项 | 现状 |
|---|---|---|
| T16 | 令牌落地采用 DTCG 2025.10 + 生成器（Terrazzo/Dictionary） | ⏳ 提议（对应 §3.2，改"手写 object"为"生成"） |
| T17 | 底栏与侧边栏合一为 `AppAdaptiveNav`（断点自动换形） | ⏳ 提议（对应 §3.10，若采纳则将 BottomTabs/SideRail 统一） |

> **Sources**：W3C DTCG 2025.10 稳定版(www.designtokens.org/TR/2025.10)、Style Dictionary、Compose Slot API / `NavigableListDetailPaneScaffold`(developer.android.com)、M3 breakpoints / navigation-rail / `NavigationSuiteScaffold`(m3.material.io / developer.android.com)、Compose-StatusBox(GitHub OCNYang)。