# chat-inputbar-redesign-design

> 评审状态：✅ 已实施（ChatInputField / ChatInputToolbar / ChatPanels 已落地，ChatInputBar 已瘦身为编排入口）
>
> 主题：聊天页输入框与输入框工具栏的完整重构——胶囊浮动条布局 + 组件拆分 + 工具栏低频按钮收纳。

## 1. 背景与问题

聊天页底部输入区（`ChatInputBar.kt`）当前为「一体式贴底容器」，存在以下问题：

- **结构耦合**：输入框、附件预览、工具栏、斜杠命令菜单、队列面板全部塞进同一个圆角 Surface 容器，多行输入 + 附件 + 工具栏叠加时视觉偏重、层次不清。
- **文件臃肿**：`ChatInputBar.kt` 单文件 700+ 行，混合了输入框、工具栏、权限面板（`ToolPermissionPanel`）、状态横幅（`StatusBanner`/`InfoBanner`）、变更预览（`ChangePreviewPanel`/`ChangeItem`）、计划审批（`PlanApprovalPanel`）等 10+ 组件，不利于维护与扩展。
- **工具栏密度高**：一行常驻 7 个控件（模式 pill / 模型 / 工作区 / 思考强度 / 技能 / 附件＋ / 发送），小屏易拥挤；思考强度、技能属低频操作却常驻占位。
- **视觉单调**：模式 pill 为纯文字标签（BUILD/PLAN/AUTO 仅靠颜色区分），无图标、无按压反馈；输入条贴底无悬浮层次。

## 2. 设计目标

1. **胶囊浮动条**：输入条改为圆角全满 + 阴影的悬浮胶囊，浮于消息列表底部之上（带边距），键盘弹出时上浮，与消息列表视觉解耦。
2. **组件拆分**：`ChatInputBar.kt` 拆分为编排入口 + 独立子组件，职责单一、便于维护。
3. **工具栏降密度**：7 个控件全部保留但**功能零损失**——思考强度、技能两个低频按钮收纳进「＋」旁的展开菜单。
4. **对外契约不变**：`AIChatPanel` / `AIAgentViewModel` 调用点零改动，输入文本、isBusy、队列、token 进度仍由外部状态驱动；工具栏内部 UI 状态（展开菜单、附件弹层）下沉到子组件 `remember` 内部。

## 3. 目录结构与职责

| 文件 | 职责 | 变更 |
| --- | --- | --- |
| `presentation/component/ChatInputBar.kt` | 输入条编排入口：胶囊容器 + 输入框 + 工具栏组合 | 瘦身重构 |
| `presentation/component/ChatInputField.kt` | `TextField`（多行 44~160dp）+ 附件预览区独立 | 新增 |
| `presentation/component/ChatInputToolbar.kt` | 工具栏 Row + `SendButton`/`UploadIconButton`/模式 pill/收纳「更多」菜单 | 新增 |
| `presentation/component/ChatInputAttachments.kt` | 附件弹层（`AttachmentSheet`）、队列面板（`QueuedRequestPanel`） | 保留 |
| `presentation/component/ChatModelSheet.kt` | `ModelIconButton`/`ReasoningEffortSelector` | 保留 |
| `presentation/component/ChatPanels.kt` | `ToolPermissionPanel`/`StatusBanner`/`InfoBanner`/`ChangePreviewPanel`/`ChangeItem`/`PlanApprovalPanel` | 新增（平移归位） |

> `ChatPanels.kt` 仅做平移归位（不改逻辑），`ToolPermissionPanel` 等仍在 `ChatInputBar.kt` 内被引用时需调整 import。

## 4. 布局设计（胶囊浮动条）

```
┌──────── 消息列表（LazyColumn） ────────┐
│  ...                                  │
├───────────────────────────────────────┤
│          （浮动边距 ~8dp）              │
│   ╭──────────────────────────────────╮ │
│   │  [附件预览 · 可横滚 76dp]   ← 内嵌 │ │
│   │  [ 输入框 多行 44~160dp ]          │ │
│   │  ┌──────────────────────────────┐ │ │
│   │  │ 模式│模型│工作区│ ··· │ ＋ │发送 │ │ │  ← 工具栏
│   │  └──────────────────────────────┘ │ │
│   ╰──────────────────────────────────╯ │  ← 圆角全满(~28dp) + 阴影 + 悬浮
└───────────────────────────────────────┘
```

- 圆角 `RoundedCornerShape(28.dp)`（胶囊）、`shadowElevation` 柔和阴影、与消息列表之间留浮动边距。
- 斜杠命令菜单 / 队列面板从「容器内嵌」改为「胶囊上方独立浮层」，避免拉高输入条。
- 键盘 inset 继续由 `rememberImeBottomInset()` 兜底，胶囊上浮动画用 `animateDpAsState`。

## 5. 工具栏布局（7 控件全保留 + 收纳）

- **常驻组（左）**：模式 pill → 模型 → 工作区。
- **收纳组（「＋」旁展开菜单）**：思考强度（`ReasoningEffortSelector`）、技能对话入口（Zap 按钮）。
- **常驻组（右）**：附件「＋」→ 发送按钮（保留 token 圆弧进度）。
- 模式 pill 增加图标 + 按压反馈（现状为纯文字）。

## 6. 关键设计点与约束

1. **对外回调签名不变**：`ChatInputBar` 的参数列表保持现状，`AIChatPanel` 调用点零改动。
2. **内部状态下沉**：收纳菜单展开态、附件弹层态在子组件 `remember` 内管理；`value`/`isBusy`/`queuedRequests`/`tokenProgress` 等仍由外部传入。
3. **零逻辑迁移**：`ChatPanels.kt` 中的 6 个组件逐字平移，不改变任何行为。
4. **附件预览**：`PendingAttachmentPreviewList` 迁移至 `ChatInputField.kt`，仅在附件非空时展示。

## 7. 维护与扩展指引

- 新增工具栏按钮：在 `ChatInputToolbar.kt` 的常驻组或收纳组中登记。
- 新增输入区浮层面板：优先放 `ChatPanels.kt`，避免回流入 `ChatInputBar.kt`。
- 涉及用户可见 UI 变化时同步更新 `app/src/main/assets/docs/` 使用文档与本模块文档 `docs/modules/agent.md`。
