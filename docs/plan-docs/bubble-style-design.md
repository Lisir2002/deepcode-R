# bubble-style-design

> 评审状态：已实施（四款样式 + 设置切换已落地，落地实现见 `docs/modules/chatrender.md`）
>
> 主题：消息回复气泡四款独立样式——纯文字流 / 细线气泡 / 终端日志（默认主设计）/ 时间线，模块化实现 + 设置页一键切换（切换不读写对话数据，数据零丢失）。

## 1. 背景与问题

此前多轮修复「回复区块背景色」——把消息链路里所有容器背景改为透明、仅保留功能性色线与文字，问题收敛。但视觉语言仍单一：TaskAccordion 硬编码了「贯穿竖条 + 整行色线 + 灰阶日志行」这一种形态，无法满足不同用户对「极简 / 结构清晰 / 终端感 / 时间线」的偏好。

决策：推翻单一样式，将回复渲染抽为**独立模块 `feature/chatrender/`**，实现四款完全独立的外观，用户可在设置中选择切换；切换只影响渲染层，不触碰任何对话数据。

## 2. 设计目标

1. **四款独立样式**：A 纯文字流 / B 细线气泡 / C 终端日志（默认主设计）/ D 时间线，外观语言互不相同。
2. **模块化可扩展**：`MessageBubbleStyle` 抽象接口 + 款式工厂，后续新增款式只需实现接口 + 注册枚举。
3. **透明纪律延续**：所有容器透明，只允许色线 / 描边 / 节点 / 标签等线性元素。
4. **设置一键切换**：DataStore 持久化，切换实时生效，不读写对话数据。
5. **接入真实链路**：任务组外框 / 片段头 / 用户 / 助手 / 工具五类容器全部走样式分发。

## 3. 拍板决策记录（提问式逐条讨论收敛）

| # | 决策点 | 结论 |
|---|--------|------|
| D1 | 款式 A 结构元素标识 | **加粗灰阶标题**：任务组头 / 工具片段 / 思考片段用加粗灰阶标题区分正文 |
| D2 | 款式 A 时间戳 | **不显示** |
| D3 | 款式 A 流式状态 | **标题后三点脉冲** |
| D4 | 款式 B 描边 | **统一灰描边**（outlineVariant 1dp），靠框内角色色线区分角色 |
| D5 | 款式 B 任务组 | **任务组套框**（内外嵌套：任务组框 + 片段框） |
| D6 | 款式 B 框内 markdown | **代码块 + 表格留底**（保留可读性），其余组件透明 |
| D7 | 款式 B 折叠任务头 | **框顶标题行 + 箭头**（点击整框展开/收起） |
| D8 | 款式 C 角色区分 | **终端前缀标记** `[agent]` / `[tool]` / `[user]` + 色点 |
| D9 | 款式 C 任务头 | **贯穿竖条 + 顶部色线**（拐角钉纵向锚点） |
| D10 | 款式 C 用户消息 | **顶格左对齐 + `[user]` 前缀**（保持日志流统一，不右对齐） |
| D11 | 款式 C 时间戳 | **不显示** |
| D12 | 款式 D 节点 | **形状绑定角色**：圆=用户、方=助手、菱=工具（+ 颜色双维度） |
| D13 | 款式 D 时间戳 | **轨道左侧常显**（小号灰字，智能带日期） |
| D14 | 款式 D 任务组 | **粗节点 + 横向连接线**作为分组锚点 |

## 4. 方案

### 4.1 模块架构（feature/chatrender）

```
feature/chatrender/
├── BubbleStyle.kt            枚举四款 + 默认 TERMINAL_LOG + 持久化解析
├── BubbleStyleProvider.kt    款式工厂：BubbleStyle → MessageBubbleStyle
├── BubbleStyleRepository.kt  DataStore 持久化（read / write / snapshot / restore）
├── LocalBubbleStyle.kt       CompositionLocal（由 AIChatPanel 依据设置注入）
├── MessageBubbleStyle.kt     渲染抽象接口（五类容器）
├── BubbleSubGroupType.kt     片段类型（与 agent 层 TaskSubGroupType 映射解耦）
├── BubblePalette.kt          角色色板（深/浅两档）+ 节点形状 + 时间格式化
├── BubbleChrome.kt           共用灰阶任务头 / 片段标签行 / 三点脉冲
├── PureTextBubbleStyle.kt    款式 A
├── ThinBubbleStyle.kt        款式 B
├── TerminalLogBubbleStyle.kt 款式 C（默认）
└── TimelineBubbleStyle.kt    款式 D
```

**抽象接口**（`MessageBubbleStyle`）五类容器：

- `TaskGroupContainer(...)`：任务组外框（任务头 + 贯穿竖条/顶部色线/细线框/轨道等由款式决定），`content` 注入用户消息 + 过程内容 + 正式回复；
- `SubGroupHeader(type, label, isExpanded, isUser, onToggle)`：二级片段头（用户 / 思考 / 回复 / 工具标签行）；
- `UserContainer(timestamp, content)`：用户消息外框；
- `AssistantContainer(isFormal, timestamp, content)`：助手消息外框（正式回复轻微强调）；
- `ToolContainer(timestamp, content)`：工具消息外框。

**透明纪律**：五类容器全部不填充背景色，只允许色线 / 描边 / 节点 / 前缀标签。框内 markdown 组件的留底（D6：代码块 + 表格）由接入方内容层遵循，模块只负责外框。

### 4.2 四款样式要点

| 款式 | 任务组外框 | 片段头 | 用户消息 | 助手消息 | 工具消息 |
|------|-----------|--------|---------|---------|---------|
| A 纯文字 | 无容器无描边，任务头=加粗灰阶标题 | 加粗灰阶标签 | 右对齐 0.86f | 左对齐纯文字 | 左对齐纯文字 |
| B 细线气泡 | 1dp 灰描边圆角框，框顶标题行+箭头 | 灰标签 + 角色色短条 | 右对齐细线框 | 左对齐细线框 | 左对齐细线框 |
| C 终端日志 | 贯穿竖条 + 顶部整行色线 | `[xxx]` 前缀 + 色点 | 顶格左对齐 + `[user]` | `[agent]` 前缀 | `[tool]` 前缀 |
| D 时间线 | 左侧轨道 + 粗节点 + 横向连接线 | 形状节点（圆/方/菱）+ 时间 | 圆节点 + 轨道时间 | 方节点 + 轨道时间 | 菱节点 + 轨道时间 |

**流式状态**（D3）：`BubbleTaskHeader` 在标题后追加三点脉冲动画（复用现有 `rememberInfiniteTransition` 呼吸思路）。

**款式 C 前缀标记**：行首等宽字体小号前缀 `[user]` / `[agent]` / `[tool]` / `[think]` + 角色色点。前缀为终端语义技术标记（非中文 UI 文案），直接硬编码；标签正文（如「工具执行」）继续走 `strings.xml`。

**款式 D 节点**：`BubbleNodeShape`（CIRCLE / SQUARE / DIAMOND，菱形 = 45° 旋转圆角方块）；`BubbleNode` 组件按形状渲染；任务组头 = 大尺寸方节点 + 横向短连接线。

### 4.3 接入点（真实链路）

- **TaskAccordion**（一级任务手风琴）：外层「贯穿竖条 + 整行色线 + 任务头 + 过程区 + 正式回复」整块替换为 `style.TaskGroupContainer(...) { content }`；`SubAccordion` 的片段头 Row 替换为 `style.SubGroupHeader(...)`。任务折叠状态仍由 ViewModel 的 `_expandedTasks` / `_expandedSubGroups` 维护，UI 只读。
- **AgentMessageItem**（单条消息）：用户分支 → `style.UserContainer`；助手正式回复（formalMode）→ `style.AssistantContainer(isFormal=true)`；助手过程内容 → `style.AssistantContainer(isFormal=false)`；工具消息 → `style.ToolContainer`。
- **AIChatPanel**：`CompositionLocalProvider(LocalBubbleStyle provides bubbleStyle) { ... }`，`bubbleStyle` 来自 `SettingsViewModel.bubbleStyle`。

### 4.4 设置切换

- `BubbleStyleRepository`（DataStore `bubble_style_prefs`）已建：`bubbleStyleFlow` / `setBubbleStyle` / `snapshot` / `restore`，与 `ThemeSettingsRepository` 同构，备份恢复已兼容。
- `SettingsViewModel` 注入 `BubbleStyleRepository`，暴露 `bubbleStyle: StateFlow<BubbleStyle>` + `setBubbleStyle(style)`（init 中 collectLatest）。
- 设置「外观」区新增「回复样式」条目 → BottomSheet 四款单选（复用 ThemeSelectionSheet 样式），选中即持久化 + 全局重组。
- **数据零丢失保证**：样式切换只改 CompositionLocal / DataStore 键值，不触碰 Room 消息表；切换时 `TaskGroup` / `TaskSubGroup` 结构与展开状态完全不变。

### 4.5 文案与文档同步

- `strings.xml`（本项目仅单一 `values/strings.xml`，无 values-en）：新增 `bubble_style_*` 四款名称 + 设置条目「回复样式」。
- `docs/modules/chatrender.md`（新建）+ `docs/modules/README.md` 索引登记（pre-commit 校验）。
- `app/src/main/assets/docs/` 使用文档：设置 → 外观 → 回复样式说明。

## 5. 改动面核对

| 模块 | 文件 | 改动 |
|------|------|------|
| chatrender（新） | `feature/chatrender/*` | 12 个文件：抽象接口 + 款式工厂 + 四款实现 + 持久化 + CompositionLocal |
| 接入 | `feature/agent/presentation/component/TaskAccordion.kt` | TaskGroupContainer / SubGroupHeader 分发 |
| 接入 | `feature/agent/presentation/component/MessageBubbles.kt` | UserContainer / AssistantContainer / ToolContainer 分发 |
| 接入 | `feature/agent/presentation/component/AIChatPanel.kt` | 注入 LocalBubbleStyle |
| 设置 | `feature/settings/presentation/SettingsViewModel.kt` | bubbleStyle StateFlow + setBubbleStyle |
| 设置 | `feature/settings/presentation/component/SettingsScreen.kt` | 「回复样式」条目 + BottomSheet 选择 |
| 资源 | `res/values/strings.xml` | 四款名称 + 设置条目文案 |
| 文档 | `docs/modules/chatrender.md`（新）、`docs/modules/README.md`、`docs/plan-docs/bubble-style-design.md`（本文件） | 模块文档 / 索引 / 设计文档 |
| 使用文档 | `app/src/main/assets/docs/` | 设置 → 外观 → 回复样式说明 |

## 6. 风险与注意

- **TaskAccordion 重构面大**：1000+ 行手风琴逻辑，接入时保持「外框分发、内容不变」原则——markdown / 工具卡 / 附件预览等内部实现不动，只替换外框与片段头，降低回归风险。
- **嵌套框视觉**：款式 B 任务组框内嵌片段框属有意设计（D5），注意内边距避免双框贴太近。
- **前缀硬编码**：`[agent]` 等为终端语义标记（非中文文案），按 AGENTS.md「禁止硬编码中文 UI 文案」不违反；若未来需国际化可在 strings 集中管理。
- **默认样式变更**：默认 TERMINAL_LOG（D8-D11），存量用户首次升级后视觉从旧「色条流」变为「前缀标记流」，属本次设计变更预期。
- **迁移 SQL**：无数据库 schema 变更（样式存 DataStore 非 Room）。
