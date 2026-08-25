# 对话列表布局重构与删除/重命名检修设计

> 评审状态：✅ 已评审 · 已实施
> 关联模块：agent（presentation/component · presentation/AIAgentViewModel · data/local/dao · domain/session）
> 参考来源：Material3 `SwipeToDismissBox` / `LazyColumn` stickyHeader 规范；项目现有 `docs/plan-docs/` 设计惯例
> 说明：本文先落设计，评审通过后按「实施计划」分步实现（A1/A2/A3/B/C1-C5 已全部落地，`assembleDebug` + `testReleaseUnitTest` 通过）。

## 1. 背景与目标

对话列表（侧边栏「对话列表」tab）是会话导航主入口，负责历史会话的浏览、选择与增删改。现状：

- **信息密度低**：列表项只有标题单行 + 可选绿色呼吸点，无时间、无消息量、无工作区标识，长列表难以定位目标会话。
- **无分组**：全部会话按 `updatedAtMs` 平铺，跨天/跨周的历史无层级感。
- **删除路径深**：长按 → 底部弹窗 → 确认框，共三步；无滑动快捷路径、无撤销兜底。
- **数据残留**：删除会话仅清理消息/checkpoint/环境快照，关联的任务编排层与历史功能表（todo / hunk / 技能会话态 / wake 等 9 张表）存在孤儿数据。
- **约束缺失**：重命名无长度上限（与自动标题 `TITLE_MAX=20` 不一致）；删除当前会话后的重选逻辑可能跨工作台跳变。

目标（已与用户逐项确认）：

1. **布局重构**：列表项信息增强（时间 + 消息条数 + 选中高亮）+ 四档日期分组吸顶。
2. **删除交互**：左滑删除 + 确认框 + Snackbar 撤销，长按菜单路径保留。
3. **数据检修**：删除事务化 + 级联清理盘点补齐；重命名约束；删当前会话重选优化；执行中删除提示。

## 2. 现状盘点

### 2.1 布局与数据源

| 项 | 现状 |
|---|---|
| 列表容器 | `feature/agent/presentation/component/ChatDrawer.kt#ChatSessionListPanel`：`LazyColumn` + `items(sessions, key={it.id})`，`Arrangement.spacedBy(xs)` |
| 列表项 | `feature/agent/presentation/component/ChatSessionPicker.kt#ChatSessionRow`：`Row`（呼吸点 + 标题单行 Ellipsis），`combinedClickable(onClick, onLongClick)`；选中仅字色 primary + 字重 SemiBold |
| 数据源 | `AIAgentViewModel.sessions`：`_currentWorkspace.flatMapLatest { chatSessionDao.getAll() → filter { it.workspacePath.isBlank() \|\| it.workspacePath == path } }`（全量 + 内存过滤：未绑定会话 + 当前工作台会话） |
| 模型 | `ChatSession`（id/title/createdAt/updatedAt/workspacePath/mode/tokens），**无 messageCount / 最后消息预览** |
| 滚动 | `LaunchedEffect(currentSessionId, sessions)` 进入 tab 自动滚到当前会话 |

### 2.2 删除与重命名链路

| 层 | 现状 |
|---|---|
| UI 触发 | 长按 `ChatSessionRow` → `menuSession` → `SessionActionSheet`（ModalBottomSheet：重命名/导出/删除）→ `pendingDelete` → `AlertDialog` 确认 |
| ViewModel | `deleteSession(id)`：`checkpointManager.clearSessionCheckpoints(id)`（已含 snapshot+checkpoint+磁盘目录）→ `sessionUseCase.deleteSession(id)`（`agentMessageDao.deleteBySession` + `chatSessionDao.delete`）→ `envSnapshotStore.clear(id)` → 取消 job / 清理各状态 map → 若删当前会话则重选 |
| ViewModel | `renameSession(id, newTitle)`：`trim()` 非空校验 → `sessionUseCase.updateTitle`（不改 `updatedAt`，列表顺序不变） |
| DAO | `ChatSessionDao.updateTitle/delete`；`AgentMessageDao.deleteBySession`；`CheckpointDao.deleteCheckpointsForSession`、`CheckpointFileSnapshotDao.deleteFileSnapshotsForSession` |

### 2.3 已确认的缺口（问题清单）

**布局侧**
1. 列表项无时间、无消息量、无工作区信息。
2. 无分组，长列表平铺。
3. 选中态弱（仅字色/字重），无背景高亮。

**删除侧**
4. 三步操作、无滑动路径、无撤销兜底。
5. `deleteSession` 非事务：删消息 + 删会话两次独立写，中途崩溃留孤儿消息。
6. **级联清理缺口**：带 `sessionId` 但未清理的表——`file_edit_hunks`、`todo_items`、`mode_switch_history`、`skill_conversation_state`、`wake_queue`(该会话行)、`agent_goals`、`agent_plans`、`agent_jobs`、`agent_schedules`（任务编排 4 表为近期新增，删除会话必然遗留）。
7. 删除正在执行的会话无提示。
8. 删当前会话后重选逻辑：同工作台最近 → **全局最近未绑定**，未绑定会话可能属其他工作台 → 跨台跳变。

**重命名侧**
9. 长度无上限（`deriveTitle` 有 `TITLE_MAX=20`，重命名反而没有）；无 IME 回车提交。

## 3. 方案总览（四块）

```
┌──────────────────────────────────────────────────────────────┐
│ A 布局重构：列表项两行增强 + 今天/昨天/7天内/更早 四档吸顶分组     │
├──────────────────────────────────────────────────────────────┤
│ B 删除交互：左滑(SwipeToDismissBox) → 确认框(含执行中提示)       │
│              → 删除 → Snackbar「已删除 · 撤销」(re-insert)      │
├──────────────────────────────────────────────────────────────┤
│ C 数据/逻辑检修：①删除事务化 ②级联清理盘点补齐 9 表              │
│                  ③重命名约束(TITLE_MAX=20+IME回车)             │
│                  ④删当前会话重选优化 ⑤执行中删除提示             │
└──────────────────────────────────────────────────────────────┘
   底层：现有 ChatSessionDao / SessionUseCase / AIAgentViewModel / ChatDrawer
```

## 4. 分方向设计

### A. 布局重构：列表项增强 + 四档吸顶分组

#### A1. 数据层：消息条数聚合

- 新增 DTO `ChatSessionWithCount`（`data/local/model/` 或与 DAO 同包）：
  `id, title, createdAtMs, updatedAtMs, workspacePath, mode, messageCount: Int`。
- `ChatSessionDao` 新增查询（保留现有 `getAll` 不动）：

```sql
SELECT cs.id, cs.title, cs.createdAtMs, cs.updatedAtMs, cs.workspacePath, cs.mode,
       COUNT(am.id) AS messageCount
FROM chat_sessions cs
LEFT JOIN agent_messages am ON am.sessionId = cs.id
GROUP BY cs.id
ORDER BY cs.updatedAtMs DESC
```

- `AIAgentViewModel.sessions` 数据源改用该聚合查询 + 现有过滤逻辑不变（未绑定 + 当前工作台），`agentStates` 继续用于执行中判定。列表项需要 `isExecuting` 依旧由 `agentStates[session.id] is Loading/Streaming` 计算。

> 取舍：侧边栏会话量通常有限，全量 + LEFT JOIN COUNT 可接受；若未来会话量级增长，再考虑 keyset 分页（数据源改造独立、不影响 UI）。

#### A2. 列表项：两行布局

`ChatSessionRow` 由单行改为两行（保持 `combinedClickable` 与长按语义）：

- 第一行：标题（单行 Ellipsis）。
- 第二行（`bodySmall` / `onSurfaceVariant`）：「智能分档时间 · N 条消息」。

**智能分档时间**（按 `updatedAtMs`）：
| 条件 | 显示 |
|---|---|
| 今天（同日） | `HH:mm`（如 `14:30`） |
| 昨天 | `昨天` |
| 7 天内 | `x天前` |
| 更早 | `M月d日`（跨年再加 `yyyy年`，可选） |

**消息条数**：恒显「· N 条消息」；`N=0` 显示「· 暂无消息」（未发首条的新会话）。

**选中态**：整行背景高亮 `MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.45f)` + 圆角 `RoundedCornerShape(Radius.sm)`，标题字色/字重跟随。取代原先仅字色变化。

**执行中**：保留绿色呼吸点（放标题前），第二行时间/条数照常。

#### A3. 分组：四档吸顶

- 按 `updatedAtMs` 分四档：**今天 / 昨天 / 7天内 / 更早**（今天=自然日零点起；昨天=昨日零点至今日零点；7天内=7 个自然日前零点起）。
- `ChatSessionListPanel` 内 `LazyColumn` 改用 `items(grouped, key)` + **`stickyHeader`**（`ExperimentalFoundationApi`），分组头吸顶。
- 分组头样式：小号 `labelLarge` 文字 + 右侧 `onSurfaceVariant` 会话计数（如「今天  12」），吸顶时同款显示。
- 组内顺序：`updatedAtMs` 降序（沿用现有排序）。
- 组间顺序：今天 → 昨天 → 7天内 → 更早。
- 空态：无会话时维持现有 `chat_no_sessions_hint`。
- 自动滚动逻辑（`scrollToItem(currentSessionId)`）需按「分组后全局索引」换算：进入 tab 滚到当前会话所在组的对应 item 下标。

### B. 删除交互：左滑 + 确认 + 撤销

#### B1. 左滑

- 每行外包 `SwipeToDismissBox`（`ExperimentalMaterial3Api`），`enableDismissFromStartToEnd = false`，仅左滑。
- 背景：红色（`MaterialTheme.colorScheme.errorContainer`）+ 白色 `Delete` 图标，`Modifier.fillMaxSize().background(...)` + `Arrangement.Center` 对齐。
- 关键点：`SwipeToDismissBox` 的 `confirmValueChange` 中，**当 `targetValue == EndToStart`（滑出）时不真正移除行**，而是先触发确认流程，再返回 `false`（回弹）。确认通过后才从列表移除并执行删除。

#### B2. 确认框

- 复用现有 `AlertDialog`（`chat_delete_session` / `chat_delete_session_confirm`）。
- 执行中提示：若该会话 `isExecuting`，确认文案追加提示（如 `chat_delete_session_running`：「该会话正在执行，删除将中断其任务」），确认按钮文字仍为「删除」。

#### B3. Snackbar 撤销

- 删除确认后：`deleteSession` 执行 → 弹出 Snackbar「已删除「title」」+ 动作「撤销」。
- 撤销 = 恢复数据：ViewModel 在删除前缓存 `(ChatSessionEntity, List<AgentMessageEntity>)`（消息按 `createdAtMs`/`order` 原样 re-insert），撤销时 `chatSessionDao.upsert(entity)` + `agentMessageDao.upsertAll(messages)`。
- 撤销缓存仅保留最近一次删除（单槽覆盖即可）；进程被杀/会话切走后缓存失效（低风险，撤销窗口短）。
- 撤销窗口时长：`SnackbarDuration.Short`（约 4s，含「撤销」动作按钮）。
- **长按菜单路径的删除同样接确认 + 撤销**（统一行为）。

> 取舍说明：撤销只恢复「会话 + 消息」；checkpoint 磁盘快照、环境快照、任务编排数据不随撤销恢复（删除即视为放弃，4 秒窗口内误删主要损失聊天记录，可接受）。若后续需要完整撤销，可扩展为软删除 + 回收站（改动面大，本期不做）。

### C. 数据/逻辑检修

#### C1. 删除事务化

- `SessionUseCase.deleteSession` 的 DB 写（删消息 + 删关联表 + 删会话）用 `androidx.room.withTransaction`（`room-ktx` 已依赖）包裹，原子生效，中途失败整体回滚。
- 注：`withTransaction` 需要 `AgentDatabase` 实例；`SessionUseCase` 现注入两个 DAO，改造为再注入 `AgentDatabase`（或改为注入 `ChatSessionDao` 级联事务方法）。倾向注入 `AgentDatabase` 在 UseCase 内组织事务，保持级联清单在业务层可见、可维护。

#### C2. 级联清理盘点补齐

删除会话时，除现有（消息/checkpoint/snapshot/环境快照/内存状态）外，**补齐清理以下带 `sessionId` 的关联表**（均按 `sessionId` 精确删除）：

| 表 | 实体 | 说明 |
|---|---|---|
| `file_edit_hunks` | FileEditHunkEntity | 代码变更 hunk 记录 |
| `todo_items` | TodoItemEntity | 会话 todo |
| `mode_switch_history` | ModeSwitchHistoryEntity | 模式切换历史 |
| `skill_conversation_state` | SkillConversationStateEntity | 技能会话态（按 session_id） |
| `wake_queue` | WakeItemEntity | 该会话的唤醒项（空串=全局，不删） |
| `agent_goals` | GoalEntity | 任务编排：目标状态机 |
| `agent_plans` | PlanEntity | 任务编排：计划协作 |
| `agent_jobs` | JobEntity | 任务编排：后台任务 |
| `agent_schedules` | ScheduleEntity | 任务编排：定时提醒 |

各表 DAO 需补 `deleteBySession(sessionId)` 方法（无则新增，均为一句话 `DELETE FROM ... WHERE sessionId = :sessionId`）。

**明确保留不删**（审计/全局语义）：
- `zth_*`（`zth_telemetry_events` 存 sessionId 哈希、`zth_user_confirmed_sentinels`、`zth_sentinel_plan_rejection_audits`、`zth_hard_constraint_delete_audits`、`zth_l0_soft_compact_restore_logs`）——审计流水保留。
- `hallucination_fuses`（`composeSessionId` 语义，保留）。
- `skill_state`（全局技能状态，无 sessionId，不删）。
- `wake_queue` 中 `sessionId=""` 的全局项（不删）。

#### C3. 重命名约束

- 长度上限对齐 `SessionUseCase.TITLE_MAX = 20`：`renameSession` 内 `trim()` 后 `take(20)` 截断（或 UI `enabled` 同步校验 + 后端兜底截断）。
- 空标题：维持现有 `trim()` 空串拦截。
- IME 回车提交：重命名 `OutlinedTextField` 的 `KeyboardActions(onDone = 提交)`，与确认按钮等效。
- 去重：**不做强制**（会话允许同名，项目现状允许），仅长度约束。

#### C4. 删当前会话重选优化

`deleteSession` 内删除当前会话后的重选逻辑调整为（消除跨工作台跳变）：

1. 当前工作台最近会话（`getAllSessionsByWorkspaceOnce(path).firstOrNull()`）；
2. 当前工作台**未绑定**会话（按工作台过滤未绑定，需补一个查询或复用现有数据）；
3. 全局最近会话（`getAllOnce().firstOrNull()`）；
4. 以上皆无 → `null`（空列表态）。

（现逻辑为「同工作台最近 → 全局未绑定」，第 2 步会取到跨工作台的未绑定会话；改为「同工作台未绑定优先，再回退全局」。）

#### C5. 执行中删除提示

- 删除确认框根据 `isExecuting`（`agentStates[id] is Loading/Streaming`）追加提示文案。
- 该状态信息需从 `ChatSessionListPanel` 上浮到确认框：由 `ChatDrawerContent` 持有的 `agentStates` 直接查 `session.id` 即可（确认框本就在 `ChatDrawerContent` 层，可读 `agentStates`）。

## 5. 数据层改动清单

| 文件 | 改动 |
|---|---|
| `data/local/dao/ChatSessionDao.kt` | 新增聚合查询（含 COUNT）+ 可选 `ChatSessionWithCount` DTO 映射 |
| `data/local/dao/AgentMessageDao.kt` | 新增 `getBySessionOnce(sessionId)`（撤销缓存用，若已存在则复用） |
| `data/local/dao/FileEditHunkDao.kt` 等 8 个关联 DAO | 新增 `deleteBySession(sessionId)`（各关联表） |
| `domain/session/SessionUseCase.kt` | `deleteSession` 事务化 + 级联清理；`TITLE_MAX` 重命名截断；重选逻辑补充未绑定按工作台过滤 |
| `data/local/entity/` | 不改 schema（无新增列/表，无需 Room 迁移） |

> 数据库 schema 无变化，`agent` 库维持 v2，无迁移链。

## 6. 交互细节规格

- 相对时间分档：见 A2 表格。
- 分组头：`labelLarge` + 右侧计数，吸顶。
- 选中：背景 `primaryContainer.copy(alpha=0.45f)`。
- 滑删：仅左滑，背景 `errorContainer` + 白色 Delete 图标，滑出先确认后删。
- Snackbar：`已删除「title」` + 「撤销」，`Short` 时长。
- 执行中删除：确认框文案追加「该会话正在执行，删除将中断其任务」。

## 7. 实施计划（分步实现，每步独立可验证）

1. **C 数据检修先行**（事务化 + 级联补齐 + 重命名约束 + 重选优化）——纯逻辑层，`testReleaseUnitTest` 覆盖。
2. **A1 数据层聚合**：DTO + DAO 查询 + ViewModel 数据源切换。
3. **A2/A3 布局**：列表项两行 + 分组吸顶 + 选中高亮 + 相对时间工具。
4. **B 删除交互**：SwipeToDismissBox + 确认 + Snackbar 撤销 + 长按路径统一。
5. **资产同步**：`strings.xml` 新增文案、`assets/prompts/`（若涉及会话管理工具描述）、`docs/modules/agent.md`（模块文档）、`docs/plan-docs/` 本文档状态更新为「已实施」。

## 8. 风险与取舍

| 项 | 取舍 |
|---|---|
| 撤销范围 | 仅会话+消息；checkpoint/环境快照/任务编排数据不随撤销恢复（低风险，窗口短） |
| 聚合性能 | 全量 + LEFT JOIN COUNT，会话量大时可后续加分页（数据源独立） |
| 事务粒度 | 级联清理与删会话同事务，审计类保留 |
| 重名 | 不强制去重（现状允许同名） |
| `SwipeToDismissBox` 状态 | `LazyColumn` 内需按 `key=session.id` 管理，滑出后行移除需同步 `SwipeToDismissBox` state，避免复用串态 |

## 9. 验证方式

- `./gradlew :app:assembleDebug`（编译冒烟）。
- `./gradlew :app:testReleaseUnitTest`（单测，含新增 DAO/UseCase 用例：级联清理、事务、重选、TITLE_MAX 截断）。
- 手工：真机/模拟器走查——列表分组吸顶、滑删确认、撤销恢复、执行中删除提示、删当前会话重选。
