# 新版 UI 层统一架构设计（DeepCore-Code）

> 评审状态：📝 草案
> 关联模块：全部 feature（agent / settings / git / terminal / proxy / workspace / credentials / capability / browser / backup）+ 仓库治理（AGENTS.md / docs / githooks）
> 前置：无（本设计为 UI 层总纲，后续各专题设计在其下展开）

## 1. 背景与目标

DeepCore-Code 的 UI 层经过多轮迭代，已形成可用的功能矩阵，但随着 feature 模块增长，UI 侧出现了一批**结构漂移**问题，导致新页面无法被稳定地"照模板落位"、跨模块复用组件无法被清晰识别。本项目工作目标进入"规范项目结构"阶段，**第一阶段就是 UI 层规范化**。

**本设计的定位**：制定一套**新版 UI 层统一骨架（v2）**，作为<b>今后所有新增页面的标准模板</b>，然后**按批次逐步接管（迁移）存量旧版 UI 层**。增量新页面优先、存量渐进收口。

**两个硬性前提（本轮已由负责人拍板）**：
1. **先定规范，不动代码**——本设计只产出规范与路线图，不进入任何代码/资源改动；
2. **严格四件套骨架**——新页面一律采用下文 §2 的统一骨架，不允许例外。

**遵循的存量纪律**（规范化不得破坏）：
- UI 文案纪律已落地：用户可见中文一律走 `strings.xml`（中/英双语）+ `stringResource()` 引用，`.kt` 无硬编码中文（详见 AGENTS.md「资产同步纪律」）。
- 主题体系已凝结（`core/theme/` 的 `Spacing` / `Radius` / `Brand` / `ChatAccent` / `LocalAppDarkMode`），这套设计精良，**保持不动**。
- ViewModel 提升到 Activity 级共享（避免设置页重复 init、侧边栏卡顿）是既有正确模式，**作为显式约定保留**。

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
| **Routes** | `nav/` | 定义本模块 route 常量 + `NavGraphBuilder.<module>NavGraph(...)` 扩展 | 不放业务逻辑、不放魔法字符串 |
| **Contract** | `state/` | 屏幕级 `UiState` / `UiEvent` / `UiSideEffect`，一屏一组 | 不放可执行逻辑（至多纯推导） |
| **ViewModel** | `state/` | 一屏一 Holder，只消费 Contract 收发事件 | 不聚合多屏状态、不直接持有 UI |
| **原子组件** | `component/` | 大写的可复用原子组件（`*Card` / `*Item` / `*Field`） | 不放整屏、不放业务 |
| **整屏** | `presentation/` 根 | `*Screen` 只做依赖注入式组装，回调下沉 | 不放业务状态机 |

### 2.2 关键规则

1. **component 统一单数**：`component/` 为唯一写法，消灭存量 `components/`（复数）漂移源。
2. **整屏上提**：`*Screen` 一律放 `presentation/` 根，禁止再塞进 `component/` 或嵌套子包；消灭"整屏 Component/Section 混放"问题。
3. **Route 收敛**：route 字符串进入 `nav/` 的常量 + 路由对象，`MainActivity` 不再出现魔法串。
4. **ViewModel 职责收敛**：一屏一 Holder，只持本屏 `Contract`；跨屏共享如会话/工作区等 Activity 级状态，通过显式注入共享 `ViewModel`，不靠聚合巨型 `ViewModel`。
5. **`<Feature>Contract` 命名**：`UiState`（不可变数据）+ `UiEvent`（用户/系统输入)+ `UiSideEffect`(一次性副作用) 三合一。

---

## 3. 命名规范全集

| 类别 | 规范 | 当前反例（存量漂移） |
|---|---|---|
| 整屏入口 | `*Screen`（presentation 根） | `AIChatPanel`、`BackupSection`（命名歧义） |
| 原子组件 | `*` + `*Card` / `*Item` / `*Field`（component/） | — |
| 全局弹窗 | `*DialogHost`（保留，命名已合理） | — |
| 设置分区块 | `*Section`（component/ 内，明确"块"非"屏"） | `BackupSection` 实为整屏 |
| ViewModel | `*ViewModel`，一屏一 Holder | `AIAgentViewModel`（巨型聚合） |
| UI 状态 | `<Feature>Contract.UiState/UiEvent/UiSideEffect` | 现状平铺散落 |
| 路由 | `<Module>Routes.{CHAT, SETTINGS, ...}` 常量 + `*Routes` 对象 | `MainActivity` 裸字符串 `"chat"` |
| 组件目录 | `component/`（单数）统一 | `components/`（复数）残留 |
| 全限定名 import | 跨模块引用经统一门面，禁止到处散落 `com.core.deepcode.feature.*.*` 全路径 | `MainActivity` / 各 component 大量全限定引用 |

---

## 4. 导航治理

- 新建 `core/nav/` 存 Route 常量基座 + 共享过渡动画（从 `MainActivity` 迁出 `pageEnter/pageExit/...` 与 `terminal*` 过渡）。
- 每个 feature 提供 `fun NavGraphBuilder.<module>NavGraph(...)`，`MainActivity` 只按顺序组装，从 ~900 行瘦身到 ~50 行图表组装。
- **Activity 级共享 ViewModel 模式**作为显式约定写入规范：跨页面共享的状态（会话/工作区/设置）由 `MainActivity` 顶层 `hiltViewModel()` 获取并注入，避免每屏重复创建；`AppNavigation` 的 Drawer 生命周期隔离、`ModalNavigationDrawer` 外层包裹、退终端后 Drawer 白屏规避等既有修复，全部作为注释保留勿动。

---

## 5. 共享组件与主题收敛

- `core/theme/` 中 `AppComponents.kt` / `CyberComponents.kt` 两套平行"总组件库"**合并为单一 `core/ui/` 组件库**，作为跨模块复用的唯一归属。
- 跨模块复用的组件（如聊天富文本 segment、`DiffViewer`、`SwipeToConfirm` 等）迁入 `core/ui/`。
- 订阅统一走 `LocalAppDarkMode` + `MaterialTheme`，沿用既有 `AIEditorTheme` 出口。
- `Spacing` / `Radius` / `Brand` / `ChatAccent` / `LocalAppDarkMode` 保持不动（已有纪律）。

---

## 6. 存量旧 UI 接管路线图

**分批收敛标准（每批通用）**：不破坏现有功能、每批结束必须 `./gradlew :app:assembleDebug` 通过、UI 文案一律走 `strings.xml`、每批同步 `docs/modules/<module>.md` 文档与索引。

- **P0 先立地基（本轮，零功能变更）**：
  - 本设计文档进入 `docs/plan-docs/` 并登记索引；
  - 命名规范与四件套纪律写入 `AGENTS.md`「边界规则」与「资产同步纪律」；
  - 新建 `core/nav/` 空骨架 + `core/ui/` 组件库（仅放已共享原子组件，不含业务）。
  - **此后所有新增页面一律按 v2 骨架落位**。

- **P1 接最大模块（agent 聊天 + settings）**：
  - 拆 `AIAgentViewModel` → `ChatContract` + `ChatViewModel`（一屏一 Holder）；
  - 抽 `AgentRoutes` / `SettingsRoutes`，`MainActivity` 首度瘦身；
  - `AIChatPanel` → `ChatScreen` 落 `presentation/` 根；settings `components/`（复数）归并为 `component/`（单数）、`*Screen` 上提。

- **P2 其余 feature**（git / terminal / proxy / workspace / credentials / capability / browser / backup）：按 P1 模板逐屏套用，各自抽 Route + Contract + Screen 收敛。

- **P3 收口**：`AppComponents` / `CyberComponents` 合并入 `core/ui/`；跨模块组件上提；`MainActivity` 瘦身到图表组装；全量 `docs/modules/` 与索引核对。

---

## 7. 门禁与配套

- **命名**：沿用既有 `.githooks/pre-commit` + `commit-msg`。后继可对此扩展"component/components 复数归一、*Screen 位置"类轻量校验（作为后续可选增强，不在本轮）。
- **文档同步**：本设计定稿后由各 `docs/modules/<module>.md` 反映落地实现，两处不重复维护同一份内容（依 `docs/plan-docs/README.md`）。

---

## 8. 待定 / 随实现确认

- `core/ui/` 组件库的初始收录清单（哪些组件属跨模块、哪些留在各 feature `component/`）——P0 收口时盘点定稿。
- Contract 三件（UiState/UiEvent/UiSideEffect）在 chat 这种超大屏上的粒度切分——P1 首次实战定稿后回填本设计。
- 是否引入逐模块"迁移完成"检查清单（复用 `progress-tracker.md` 机制）——随 P0–P3 推进补充。

## 9. 评审记录

> 待评审结果回填（评审通过建议另起一行 `> 评审结论：<一句话>`）。