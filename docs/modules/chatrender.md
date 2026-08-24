# 聊天回复渲染（chatrender）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/chatrender/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

提供**聊天消息回复的外框渲染样式抽象**（RC 周期新增）。目标是把「回复气泡长什么样」从消息链路（agent 展示层）中彻底解耦出来：

- **四款独立样式**：A 纯文字流 / B 细线气泡 / C 终端日志（默认主设计）/ D 时间线，外观语言互不相同；
- **模块化可扩展**：`MessageBubbleStyle` 抽象接口 + 款式工厂 `BubbleStyleProvider`，新增款式只需实现接口 + 注册枚举；
- **透明纪律延续**：所有容器必须透明，只允许色线 / 描边 / 节点 / 前缀标签等线性元素，绝不填充背景色；
- **设置一键切换**：DataStore 持久化（`bubble_style_prefs`），切换实时生效，**不读写任何对话数据**（数据零丢失）。

设计决策过程见 `docs/plan-docs/bubble-style-design.md`（拍板决策记录 D1~D14）。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `BubbleStyle.kt` | 四款样式枚举（`PURE_TEXT / THIN_BUBBLE / TERMINAL_LOG / TIMELINE`）+ 默认值 `TERMINAL_LOG` + `fromPersisted` 持久化解析；每款携带 `labelRes`（设置页名称） |
| `BubbleStyleProvider.kt` | 款式工厂：`BubbleStyle → MessageBubbleStyle` 无状态分发（`when` 全量穷举） |
| `BubbleStyleRepository.kt` | DataStore 持久化（`bubble_style_prefs`）：`bubbleStyleFlow` 流式读取 / `setBubbleStyle` 写入 / `snapshot`（备份快照）/ `restore`（备份还原，null 回退默认） |
| `LocalBubbleStyle.kt` | `staticCompositionLocalOf { BubbleStyle.DEFAULT }`，由 `AIChatPanel` 依据设置注入，切换只触发重组 |
| `MessageBubbleStyle.kt` | 渲染抽象接口：`TaskGroupContainer` / `SubGroupHeader` / `UserContainer` / `AssistantContainer` / `ToolContainer` 五类外框 |
| `BubbleSubGroupType.kt` | 二级片段类型（`USER / REASONING / REPLY / TOOL`），与 agent 层 `TaskSubGroupType` 一一对应但独立定义以保持解耦 |
| `BubblePalette.kt` | 角色色板（用户/助手/工具/思考 + 贯穿竖条，深/浅两档随 `LocalAppDarkMode`）+ `BubbleNodeShape` 节点形状 + `BubbleNode` 节点组件 + `formatBubbleTime` 智能时间格式化 |
| `BubbleChrome.kt` | 各款式共用灰阶元素：`BubbleTaskHeader`（任务头：标题/流式三点脉冲/时间/折叠箭头，可附 leading 节点）、`BubbleSubLabel`（片段标签行）、`StreamingDots`（三点流式脉冲） |
| `PureTextBubbleStyle.kt` | 款式 A · 纯文字流：零容器零描边，靠对齐区分角色（用户右对齐 0.86f，助手/工具左对齐） |
| `ThinBubbleStyle.kt` | 款式 B · 细线气泡：透明底 + 1dp `outlineVariant` 描边圆角框，靠轮廓不靠填色 |
| `TerminalLogBubbleStyle.kt` | 款式 C · 终端日志（默认主设计）：贯穿竖条 + 顶部整行色线，行首色线/色块区分角色，用户行首加粗色块 |
| `TimelineBubbleStyle.kt` | 款式 D · 时间线：左侧角色节点轨道（圆=用户/方=助手/菱=工具）+ 内容右排 + 轨道时间戳 |

## 3. 核心架构与主流程

### 3.1 渲染抽象（`MessageBubbleStyle`）

五种外框职责边界：

| 外框 | 语义 | 接入方 |
| --- | --- | --- |
| `TaskGroupContainer` | 一轮回复（任务组）外框，含任务头（标题/时间/折叠箭头），款式决定贯穿竖条 / 顶部色线 / 细线框 / 时间线轨道 | `TaskAccordion` |
| `SubGroupHeader` | 二级片段头（用户/思考/回复/工具标签行），款式决定短色条 / 节点 / 前缀 | `SubAccordion` |
| `UserContainer` | 用户消息外框 | `AgentMessageItem` |
| `AssistantContainer(isFormal)` | 助手消息外框；`isFormal=true` 为正式回复（款式据此轻微强调，如终端日志 3dp 粗竖条 vs 2dp 细） | `AgentMessageItem` |
| `ToolContainer` | 工具消息外框（内容自带折叠） | `AgentMessageItem` |

正文内容由调用方以 `content` lambda 注入，本模块只负责外框，不感知 Markdown / 工具卡 / 附件预览内部实现——这是接入方「外框分发、内容不变」原则的基础。

### 3.2 数据流（样式持久化 → 全局生效）

```
BubbleStyleRepository(DataStore) ──bubbleStyleFlow──► SettingsViewModel._bubbleStyle
                                                          │  (collectAsStateWithLifecycle)
                                                          ▼
AIChatPanel  CompositionLocalProvider(LocalBubbleStyle provides bubbleStyle) { ... }
                                                          │
                    ┌─────────────────────────────────────┼──────────────────────────────┐
                    ▼                                     ▼                                ▼
          TaskAccordion                          MessageBubbles                    （尾部气泡等其余组件）
    BubbleStyleProvider.provide(LocalBubbleStyle.current)
              │  ── TaskGroupContainer / SubGroupHeader
              ▼
    AgentMessageItem  ── UserContainer / AssistantContainer / ToolContainer
```

- 切换样式 = 写 DataStore 键 → `bubbleStyleFlow` 发射 → `SettingsViewModel` StateFlow 更新 → `AIChatPanel` 重组 → `LocalBubbleStyle` 新值 → 消息链路按新款式重组。
- **数据零丢失**：样式键独立存储于 `bubble_style_prefs`（DataStore），不触碰 Room 消息表；`TaskGroup` / `TaskSubGroup` 结构与展开状态完全不变。

### 3.3 款式工厂（`BubbleStyleProvider`）

```kotlin
object BubbleStyleProvider {
    fun provide(style: BubbleStyle): MessageBubbleStyle = when (style) {
        BubbleStyle.PURE_TEXT -> PureTextBubbleStyle
        BubbleStyle.THIN_BUBBLE -> ThinBubbleStyle
        BubbleStyle.TERMINAL_LOG -> TerminalLogBubbleStyle
        BubbleStyle.TIMELINE -> TimelineBubbleStyle
    }
}
```

四种实现均为无状态单例 object；`when` 全量穷举保证新增枚举值必报编译错，强制实现新款式。

### 3.4 透明纪律（模块级约束）

所有容器（五类外框）一律不填充背景色，只允许：
- **色线**：终端日志的贯穿竖条 / 行首色线 / 顶部整行色线；
- **描边**：细线气泡的 1dp `outlineVariant` 边框；
- **节点**：时间线的圆/方/菱节点与轨道竖线；
- **标签**：纯文字流的灰阶标题 / 标签行。

框内 markdown 组件是否留底（如代码块 + 表格）由接入方内容层自行遵循同一纪律，本模块不干预。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `MessageBubbleStyle`（接口） | 五种外框渲染抽象；接入方通过 `BubbleStyleProvider.provide(LocalBubbleStyle.current)` 获取 |
| `BubbleStyleProvider.provide(style)` | 款式工厂（无状态） |
| `LocalBubbleStyle`（CompositionLocal） | 当前生效款式，由 `AIChatPanel` 注入 |
| `BubbleStyleRepository`（`@Singleton`） | DataStore 持久化；`bubbleStyleFlow` / `setBubbleStyle` / `snapshot` / `restore`（备份兼容） |
| `BubbleStyle`（枚举） | 四款样式 + `labelRes` + `DEFAULT` + `fromPersisted` |
| 共享 UI 资产 | `BubbleChrome`（任务头 / 片段标签 / 三点脉冲）、`BubblePalette`（色板 / 节点 / 时间格式化） |

集成点（接入方）：

| 接入方 | 使用 |
| --- | --- |
| `feature/agent/presentation/component/AIChatPanel.kt` | 读取 `SettingsViewModel.bubbleStyle`，`CompositionLocalProvider(LocalBubbleStyle provides bubbleStyle)` 全局下发 |
| `feature/agent/presentation/component/TaskAccordion.kt` | 任务组外框 → `TaskGroupContainer`；片段头 → `SubGroupHeader`（`TaskSubGroupType.toBubbleSubGroupType()` 映射） |
| `feature/agent/presentation/component/MessageBubbles.kt` | 用户 → `UserContainer`；助手正式/过程 → `AssistantContainer(isFormal=…)`；工具 → `ToolContainer` |
| `feature/settings/presentation/SettingsViewModel.kt` | 注入 `BubbleStyleRepository`，暴露 `bubbleStyle` StateFlow + `setBubbleStyle` |
| `feature/settings/presentation/component/SettingsScreen.kt` | 「外观」分组「回复样式」条目 + `BubbleStyleSelectionSheet` 四款单选 |
| `res/values/strings.xml` | `settings_bubble_style_title` / `settings_bubble_style_subtitle` / `bubble_style_{pure_text,thin_bubble,terminal_log,timeline}` |

依赖：`core.theme`（`Spacing` / `Brand` / `LocalAppDarkMode`）、`core`（Hilt `@Inject`）。

## 5. 关键设计点与约束

- **样式与数据解耦（核心承诺）**：样式切换只写 DataStore 键 + 重组，绝不读写 Room 消息表；这是「切换不影响/不丢失对话数据」的实现根基。
- **与 agent 层解耦**：`BubbleSubGroupType` 独立定义，由接入方 `toBubbleSubGroupType()` 映射，chatrender 不依赖 agent 展示模型。
- **只做外框**：本模块不感知 Markdown / 工具卡 / 附件预览，避免与内容层互相依赖、便于后续样式独立演进。
- **透明纪律**：任何新款式都不得填充容器背景色；如需强调只能使用色线 / 描边 / 节点 / 标签。
- **默认样式为终端日志**：`BubbleStyle.DEFAULT = TERMINAL_LOG`，存量用户升级后视觉从旧「色条流」变为「前缀标记流」属本次设计变更预期。
- **持久化独立**：`bubble_style_prefs` 独立 DataStore，不混入主题/其他设置；`snapshot/restore` 已预留备份兼容接口（当前备份链路未接线，恢复回退默认值）。
- **无数据库 schema 变更**：样式存 DataStore 非 Room，无迁移 SQL。

## 6. 维护与扩展指引

- **新增一款样式**：
  1. 新建 `XxxBubbleStyle.kt` 实现 `MessageBubbleStyle`（五种外框全实现，遵循透明纪律）；
  2. 在 `BubbleStyle.kt` 枚举新增条目（带 `labelRes`），并在 `strings.xml` 补名称；
  3. 在 `BubbleStyleProvider.provide` 的 `when` 中注册（编译期强制穷举）；
  4. 在 `BubbleStyleSelectionSheet.kt` 的 `BubbleStyle.entries.forEach` 自动出现，无需改选择弹窗。
- **调整某款式**：直接改对应 `XxxBubbleStyle.kt`；共用元素（任务头/标签/脉冲）改 `BubbleChrome.kt`；角色配色改 `BubblePalette.kt`。
- **接备份链路**：在 `BackupManagerImpl` 的导出/导入快照中加入 `bubbleStyleRepository.snapshot()/restore()`（当前未接线，restore 缺省回退默认）。
- **文案**：设置条目与四款名称必须走 `strings.xml`；`[agent]` 等终端前缀属语义技术标记，非中文 UI 文案。
- **测试建议**：`BubbleStyleRepository` 的 DataStore 读写/快照/还原（含非法值回退默认）；`BubbleStyleProvider` 全量穷举；各款式外框在不同明暗主题下的对比度（线性元素可见性）。
