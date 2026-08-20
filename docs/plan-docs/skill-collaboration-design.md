# 技能三方协作（Skill × Agent × Tool）· 设计文档 v1.0

> 状态：📝 草案
> 目标：在「AI 主动 loadSkill」的前提下，把技能模块、agent 主循环、工具系统三方的协作调度梳理成清晰、可扩展、上下文一致的分层模型
> 对应代码库：[deepcode-R](/workspace)
> 相关入口：`docs/modules/`（模块文档） / `AGENTS.md`

---

## 1. 背景与目标

现状是「AI 靠 `loadSkill` 一个入口工具按需加载技能」，功能可达但存在几处结构性薄弱点：

1. **上下文脱节**：[SkillExecutor.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/skill/SkillExecutor.kt#L77-L87) 审批时 `sessionId = null`，技能执行的权限确认与审计和当前会话脱钩。
2. **事件硬编码**：[StatefulAgentWorkflow.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt#L1063-L1107) 里 `publishToolEvent` 用 `when(toolName)` 逐个硬编码 `loadSkill / writeFile / todo / memory / switchMode / Bash`，新增工具/技能要改主循环。
3. **专属工具不联动**：`Skill.requiredTools` 只是声明，未接入 `ToolRegistry` 的可用性校验 / 动态注册回收，也未与权限策略联动。
4. **依赖解析只校验不注入**：`resolveSkillWithDependencies` 能给出依赖序，但 `LoadSkillTool` 只检查 missing/disabled，不真正注入/预加载依赖。

本设计的目标：在**不改触发模型（维持 AI 驱动）**的前提下，把三方职责和协作调度理顺，做到「技能携带自己的上下文与工具、自己声明产出事件，主循环不再感知具体名字」。

---

## 2. 现状盘点：三方现状与调用链

### 2.1 技能域（`feature/agent/domain/skill/`）
- [Skill.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/skill/Skill.kt)：数据模型，含 `type(PROMPT/SCRIPT/MCP)`、`source(BUILTIN/LOCAL)`、`dependencies`、`requiresRuntime`、`mcpTool`、`requiredTools`、`instructions`、`enabled` 等。
- [SkillExecutor.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/skill/SkillExecutor.kt)：按 `type` 三分派——PROMPT 返回指令正文；SCRIPT 走 `CommandEngine` 容器执行 + `ToolPermissionManager.awaitApproval` + 审计；MCP 经 `ToolRegistry.getTool(mcpTool)` 后 `tool.execute()`。
- [SkillStateRepository.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/skill/SkillStateRepository.kt)：`skillsFlow`(磁盘扫描+Room `enabled` 合并)、`listSkills/listSkillsSync`、`setEnabled/install/uninstall/update`、`resolveSkillWithDependencies`(DFS + 环/缺失/禁用检测)。

### 2.2 工具系统（`feature/agent/domain/tool/`）
- [ToolRegistry.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/tool/ToolRegistry.kt)：`register/getTool/unregister/getToolNames/getAvailableTools/getAllToolDefinitions`，@Singleton 全局注册表（工具/技能/MCP 共用）。
- [AgentTool.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/tool/AgentTool.kt)：基类，含 `name/description/parameters`、`permissionPolicy/capabilities`、`provides/consumes/dependsOn/subscribedEvents`、`execute()` 与 `executeWithContext(args, context)`（默认委托 `execute`）、`buildPermissionRequest`、`toToolDefinition`。
- [LoadSkillTool.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/tool/skill/LoadSkillTool.kt)：桥接工具 `loadSkill`，负责技能查找 → 版本锁 → 禁用检查 → 依赖解析 → run时校验 → `skillExecutor.execute()`。

### 2.3 agent 主循环（`feature/agent/domain/workflow/`）
- [StatefulAgentWorkflow.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt)：
  - reducer 把多个 `toolCalls` 经权限队列、`PermissionEvaluated`、`ExecuteToolBatch` 走并行执行（L356-452）。
  - `runToolSync(tool, toolCall, context: AgentContext)` 统一用 `tool.executeWithContext(toolCall.arguments, context)` 执行（L1024）。
  - L7 事件发布 `publishToolEvent(name, toolCall, processed, context)`：成功时按工具名 `when` 分发到 `ToolEvent`（L1039-1041、L1063-1107）。
- [AgentModule.kt](file:///workspace/app/src/main/java/com/R/codecore/di/AgentModule.kt)：DI 注册 `loadSkillTool` 进 `ToolRegistry`，并注册 `provides=state.skill.loaded`。

### 2.4 现有调用链
```
系统提示注入技能清单(name+desc)
  → AI 调 loadSkill
  → LoadSkillTool.executeWithContext(args, context)   ← 目标：真正用上 context
  → resolveSkillWithDependencies(依赖序/环/缺/禁用)
  → SkillExecutor.execute(skill, args)                ← 目标：贯穿 SkillExecutionContext
  → PROMPT 回指令 / SCRIPT(容器+审批+审计) / MCP(ToolRegistry→execute)
  → ToolResult 回 agent
  → agent 按工具名硬编码发 ToolEvent.loadSkill→StateSkillLoaded  ← 目标：改为自声明
```

---

## 3. 分层职责

| 层 | 负责 | 不负责 |
|---|---|---|
| 工具系统 | 承载原子执行能力 + `loadSkill` 入口；权限 / 缓存 / 事件**自声明** | 不理解技能业务语义 |
| 技能模块 | 技能资产与执行语义（PROMPT/SCRIPT/MCP）；依赖解析、运行时校验、**专属工具注册回收**、审批审计；产出结构化结果 | 不感知 AI 轮次循环 |
| agent 主循环 | 只做「工具名 → AgentTool → `executeWithContext` → 发布工具自声明的 `ToolEvent`」 | 不认识任何工具/技能专名 |

---

## 4. 落地设计（四个已收敛方向）

### 4.1 触发/调度模型：维持 AI 驱动，做三点强化
1. **依赖真正注入**：`resolveSkillWithDependencies` 目前只校验。`LoadSkillTool` 按依赖序先加载依赖——PROMPT 依赖的 `instructions` 拼接到返回结果供 AI 参考，SCRIPT/MCP 依赖按其类型在技能主体前按需预执行/校验，使组合技能可用。
2. **技能内工具可用性**：加载时联动 `requiredTools`（见 4.3），保证技能声称的工具在注册表/容器内真实可用。
3. **结果结构化**：`SkillExecutionResult` 携带技能名/类型/分段输出，对齐现有 `provides=state.skill.loaded`，便于增量索引与审计。

### 4.2 上下文贯穿：新增 `SkillExecutionContext`
- 新增数据类 `SkillExecutionContext(sessionId, mode, projectPath, executionMode)`，由 `AgentContext` 派生（框架已在 `executeWithContext` 传入 `AgentContext`，零改造取用）。
- `LoadSkillTool` 改为重写 `executeWithContext(args, context)`，构建 `SkillExecutionContext` 传给 `skillExecutor.execute(skill, args, ctx)`。
- `SkillExecutor.executeScript`：用 `ctx.sessionId` 构造审批（替换当前 `null`），用 `ctx.projectPath` 做容器目录绑定，审计带 `sessionId` → 权限确认与主链路会话连贯，会话停止时 `cancelPending` 归属正确。

### 4.3 专属工具动态注册/回收：新增 `SkillToolBindingManager`
- `SkillToolBindingManager`（@Singleton）：
  - `registerForSkill(Skill)`：对 `skill.requiredTools` 逐项——
    - 全局已注册（`ToolRegistry.hasTool`）→ 校验存在且启用，缺失/禁用给明确错误；
    - 技能自带工具（skill 目录或 manifest 声明的 `AgentTool` 实例）→ 动态 `ToolRegistry.register`，并记入回收表 `bound: skillId → List<toolName>`。
  - `releaseForSkill(Skill)`：仅回收本次动态注册的工具，**绝不删除内置全局工具**。
- 调用时机：`LoadSkillTool` 加载成功 → `registerForSkill`；技能被禁用/卸载（`SkillsViewModel` / 卸载路径）→ `releaseForSkill`，实现「技能即工具组：加载注入、禁用回收」。

### 4.4 事件自声明：替换主循环硬编码
- 给 `AgentTool` 增加 open 钩子：
  `open fun buildPostExecutionEvent(toolCall, result, context): ToolEvent? = null`
- 删除 [StatefulAgentWorkflow.kt](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt#L1063-L1107) 中 `publishToolEvent` 的整段 `when(toolName){}`，改为：
  `fun publishToolEvent(...) { toolRegistry.getTool(name)?.buildPostExecutionEvent(toolCall, processed, context)?.let { toolEventBus.publish(it) } }`
- 各工具分支迁入各自类：
  - `writeFile` → `FileWritten`（writeFile 工具类）
  - `todo` → `TodoUpdated`（todo 工具类）
  - `memory` → `StateMemoryUpdated`（memory 工具类）
  - `switchMode` → `StateModeChanged`（switchMode 工具类）
  - `Bash` → `FileSystemMutated`（ExecuteCommandTool，或保留 workflow 特判）
  - `loadSkill` → `StateSkillLoaded`（LoadSkillTool 重写）
- 收益：主循环不再认识具体工具名；新工具/技能只需在自身类重写钩子即可发事件，与 `AgentTool.provides`（产出能力元数据）呼应。

### 4.5 技能作用域分级（Skill Scope）

为支撑「多 Agent（后续不止编程）」演进，给 `Skill` 增加**作用域**维度，与 `type`（执行形态）、`modes`（执行模式）正交：

```kotlin
/** 技能作用域：定义「谁能用、能否被用户关闭」。 */
enum class SkillScope {
    GLOBAL, // 全局：系统级强制激活，所有 agent 必有，用户不可关闭
    COMMON, // 通用：所有 agent 默认全局可用，用户在设置里可自定义开关
    AGENT,  // agent 级：仅绑定 agentType 的 agent 可用
}

data class Skill(
    // ...既有字段不变...
    val scope: SkillScope = SkillScope.COMMON,
    val agentType: String? = null, // 当 scope == AGENT 时必填（如 "coding"）
)
```

- 承载于 Frontmatter：新增 `scope: global|common|agent` 与 `agent-type: coding`（仅 agent 级需要）；
- 与既有字段正交：`modes`（BUILD/PLAN/AUTO，执行模式）不变，`scope` 只管适用范围。

**继承/激活规则（自动携带 全局+本 agent）**：
- 某 agent 激活时，`SkillToolBindingManager` 依序注册 `scope=GLOBAL`（**常驻不可关**）→ `scope=COMMON`（且用户开关已开启）→ `scope=AGENT && agentType == 当前 agent`；
- 即：全局技能开箱即用、通用技能默认可用（可关）、agent 级技能仅对应 agent 激活时注入，禁用/卸载时对应 `releaseForSkill` 回收。

**对 R1 的影响**（见 §6）：技能「激活态」语义据此定死为**会话/agent 激活态**——GLOBAL 常驻、COMMON 随「用户开关 × 当前 agent 激活」、AGENT 仅当对应 agent 激活；工具命名空间化（`<skillId>.<name>`）仍用于规避全局注册表竞争。

---

## 5. 影响面 / 改动清单

**新增**
- `feature/agent/domain/skill/SkillExecutionContext.kt`
- `feature/agent/domain/skill/SkillToolBindingManager.kt`

**修改**
- `LoadSkillTool.kt`：改 `executeWithContext` + 依赖真注入 + 调 `registerForSkill` + 重写事件钩子
- `SkillExecutor.kt`：`execute(skill, args, ctx)` 签名带 `SkillExecutionContext`，`sessionId` 贯穿
- `Skill.kt`：新增 `scope: SkillScope`（GLOBAL/COMMON/AGENT）与 `agentType: String?`，Frontmatter 增加 `scope`/`agent-type`
- `AgentTool.kt`：新增 `buildPostExecutionEvent` open 钩子
- `StatefulAgentWorkflow.kt`：删除 `publishToolEvent` 的 `when`，改为按 `ToolRegistry` 查钩子
- 各工具类：`writeFile/todo/memory/switchMode/Bash` 迁移事件分支
- `AgentModule.kt`：DI 注册 `SkillToolBindingManager`

**文档同步（资产同步纪律）**
- 本设计文档：`docs/plan-docs/skill-collaboration-design.md`
- 模块文档：`docs/modules/settings.md`（技能中心相关）或技能模块独立文档（视归属）

---

## 6. 待决策 / 风险

| # | 风险/待定 | 说明 | 建议 |
|---|---|---|---|
| R1 | `ToolRegistry` 全局单例 vs 技能专属工具的注册作用域 | 动态注册进全局表会跨会话可见，多个技能并发激活可能命名冲突 | **已收敛**：技能「激活态」= 会话/agent 激活态（见 §4.5）——GLOBAL 常驻、COMMON 随「用户开关 × 当前 agent 激活」、AGENT 仅当对应 agent 激活；工具名命名空间化（`<skillId>.<name>`）规避冲突 |
| R2 | 审批移到带 `sessionId` 后 UI 交互回归 | 当前以 `loadSkill` 名义、无会话；改后确认卡归属/取消需要回归 | 真机验证脚本技能拒绝/批准/停止三条线 |
| R3 | PROMPT 依赖"注入"的确切语义 | 拼接全部依赖指令 vs 仅声明 | 倾向"按依赖序拼接 + 去重"，但需定上下文膨胀上限 |
| R4 | `provides` 元数据 vs `buildPostExecutionEvent` 二义 | 两者都表示"技能产出"，可能重叠 | 统一为「`provides`=能力/事件类型声明，`buildPostExecutionEvent`=具体实例化」，二者对齐 |

---

## 7. 里程碑（建议）

| 里程碑 | 内容 | 验证 |
|---|---|---|
| M0 | `SkillExecutionContext` + `executeWithContext` 贯穿 + `SkillExecutor` sessionId 打通 | `assembleDebug` + 脚本技能审批归属正确 |
| M1 | `buildPostExecutionEvent` 钩子 + 迁移 loadSkill 事件；其余工具逐步迁移 | `testReleaseUnitTest` 对应用例 |
| M2 | `SkillToolBindingManager` 动态注册/回收 + requiredTools 校验 | 技能加载/禁用卸载的注册表前后一致 |
| M3 | 依赖真注入 + 结果结构化 | 组合技能端到端 |

（本文件为草案，定稿前不对应任何已实施代码。）