# skill-collaboration-design

> 评审状态：✅ 已评审（R1 已实施，作用域分级 + 上下文贯穿 + 工具绑定落地）｜📝 剩余里程碑见 §6
>
> 主题：Skill 模块规划，以及 Skill 与 Agent（工作流）、Tool（工具系统）三方的协作调度设计。

## 1. 背景与问题

技能（Skill）为 AI 提供可复用的规则/脚本/MCP 编排单元，但早期实现存在四方面协作断点：

- **上下文脱节**：`SkillExecutor` 审批时 `sessionId` 传 null，导致确认卡归属、会话停止时的待决请求清理与当前会话脱钩。
- **事件硬编码**：`StatefulAgentWorkflow.publishToolEvent` 以 `when(toolName)` 硬编码各工具事件（writeFile/todo/memory/switchMode/Bash/loadSkill），工具自身不声明事件，新增工具需改工作流。
- **工具不联动**：技能声明的 `requiredTools` 未接入 `ToolRegistry`，AI 加载技能后其专属工具不可见/缺失无提示。
- **依赖未注入**：技能依赖仅解析，未真正把依赖技能内容注入执行结果。

## 2. 设计目标

1. 执行契约化：技能执行统一携带上下文，审批/审计归属与当前会话连贯。
2. 事件自声明：工具自行声明是否/如何产出事件，工作流只查钩子、不硬编码。
3. 技能即工具组：加载注入、禁用回收（`SkillToolBindingManager`）。
4. 依赖真正注入：PROMPT 依赖正文拼接进返回结果，供 AI 一并参考。
5. 作用域分级：为多 Agent 演进定义「谁能用、能否被用户关闭」。

## 3. 名词与正交维度

| 维度 | 取值 | 含义 |
| --- | --- | --- |
| `SkillType`（执行形态） | PROMPT / SCRIPT / MCP | 决定怎么执行 |
| `Skill.modes`（执行模式） | BUILD / PLAN / AUTO | 决定何模式适用 |
| `SkillScope`（作用域） | GLOBAL / COMMON / AGENT | 决定谁能用、可否关闭 |

三者正交，互不干扰。本设计聚焦 `SkillScope` 与执行链路联动的实现。

## 4. 方案

### 4.1 依赖真正注入

`LoadSkillTool` 在执行前经 `SkillStateRepository.resolveSkillWithDependencies` 递归解析依赖（环/缺失/禁用检测）。对 PROMPT 类型依赖，把其 `instructions` 拼接进返回结果：

依赖指令正文 → `--- 依赖指令 ---` → 主技能指令，供 AI 一并参考。SCRIPT/MCP 依赖由各依赖技能按其自身类型执行。

### 4.2 执行上下文贯穿（SkillExecutionContext）

新增 `SkillExecutionContext`（sessionId / mode / projectPath / agentType），由 `LoadSkillTool` 从 `AgentContext.from(...)` 派生，贯穿 `SkillExecutor` 全链路。脚本技能审批 `awaitApproval(ctx.sessionId, ...)`、审计写入均使用上下文中的 sessionId，替代此前传 null 的兜底；会话停止时 `cancelPending` 可按会话精准清理。

### 4.3 技能专属工具绑定（SkillToolBindingManager）

技能加载成功时 `registerForSkill(skill)` 校验并登记 `Skill.requiredTools`：
- 缺失/不可用 → 返回明确错误（`SKILL_MISSING_TOOL`），阻断加载；
- 已存在 → 不重复注册、不回收；
- 技能自带动态工具（未来）→ 注册进 `ToolRegistry` 并记入回收表。

技能禁用/卸载时 `releaseForSkill(skillId)` 仅回收本管理器动态注册的工具，绝不删除内置全局工具（保护 `AgentModule` 内建工具）。

### 4.4 安全与校验（SCRIPT 技能）

- 所有 SCRIPT 技能执行前必须用户确认（决策点 6：全部审批）。
- **S-3 运行时预检查（`SkillRuntimeProbe`）**：`requiresRuntime` 声明为**布尔求值树**（`RuntimeProbeExpr`：`Leaf` / `And` / `Or` / `Not`），由 `SkillProbeExprParser` 从 `expr` 字符串解析（如 `cmd:node>=18<=22 && (mod:numpy || cmd:python3)`）。预检在容器内受控探测：命令（`command -v`）/ Python 模块 / npm 全局包 / deb 包 / 文件存在性，叶子可带版本区间（`min_version`=下界 `>=`，`max_version`=上界 `<=`）；目标名与版本一律字符白名单校验、探测命令参数化执行，杜绝 shell 注入；组合逻辑只做纯逻辑求值、**绝不 eval**。任一条件不满足在**执行前**报错（`SKILL_MISSING_RUNTIME`），失败原因沿树汇总并可附带 `install_hint` 安装建议。
- **双路径统一**：手动 `runSkillScript` 与自动触发路径共用同一 `SkillRuntimeProbe` 预检，避免「触发即静默失败」。
- **旧声明兼容**：YAML 对象列表 / 字符串列表 / 逗号串由 parser 归一为 `And`，存量技能不受影响。

### 4.5 技能作用域分级（SkillScope）

为支撑多 Agent 演进，给 `Skill` 增加与 `type`/`modes` 正交的作用域维度：

```kotlin
enum class SkillScope {
    GLOBAL, // 全局：系统级强制激活，所有 agent 必有，用户不可关闭
    COMMON, // 通用：所有 agent 默认全局可用，用户在设置里可自定义开关
    AGENT,  // agent 级：仅绑定 agentType 的 agent 可用
}
// Skill 新增：scope: SkillScope = COMMON，agentType: String? = null（scope==AGENT 时必填，如 "coding"）
```

- Frontmatter 承载 `scope: global|common|agent` + `agent-type`，缺省按 COMMON。
- **继承/激活规则（自动携带 全局 + 本 agent）**：agent 激活时 `SkillToolBindingManager` 依序注册 `GLOBAL`（常驻不可关）→ `COMMON`（用户开关已开启）→ `AGENT && agentType == 当前 agent`；禁用/卸载走 `releaseForSkill` 回收。
- `LoadSkillTool`：GLOBAL/COMMON 直接放行；AGENT 级校验 agentType（当前单 Agent 场景按声明放行，多 Agent 后改为匹配当前激活 agent）。
- 技能「激活态」据此定死为**会话/agent 激活态**：GLOBAL 常驻、COMMON 随「用户开关 × 当前 agent 激活」、AGENT 仅当对应 agent 激活；工具命名空间化仍用于规避全局注册表竞争。

**设置侧体现**：GLOBAL 不出开关、COMMON 出用户开关、AGENT 仅当前 agentType 匹配时可见/可开关。

## 5. 改动面核对（R1 已实施）

- `Skill.kt`：新增 `SkillScope` 枚举 + `scope`/`agentType` 字段。
- `SkillParser.kt`：解析 `scope` / `agent_type`。
- `SkillExecutionContext.kt`（新增）：执行上下文，`from(AgentContext)` 派生。
- `SkillToolBindingManager.kt`（新增）：按 requiredTools 注册校验 / 回收动态工具。
- `SkillExecutor.kt`：`execute` 携带 `SkillExecutionContext`，审批/审计贯穿 sessionId。
- `LoadSkillTool.kt`：重写 `executeWithContext`，作用域校验 + 依赖注入 + 工具绑定 + 上下文贯穿。
- `docs/modules/agent.md` §3.6.1、`docs/modules/settings.md` §3.6：模块文档同步。

## 6. 里程碑（R1 已完成素，剩余规划）

- [x] 技能作用域分级（GLOBAL/COMMON/AGENT）+ 上下文贯穿 + 工具绑定。
- [x] M1：`AgentTool.buildPostExecutionEvent` 钩子 + 迁移全部既有事件分支（writeFile/todo/memory/switchMode/Bash/loadSkill）；工作流 `publishToolEvent` 由硬编码 `when` 改为查钩子（`toolRegistry.getTool(name)` → `buildPostExecutionEvent`），不再感知具体工具名。
- [ ] M2：多 Agent 激活时按作用域动态注册/回收（`SkillToolBindingManager` 在 agent 激活处接入）。

> 注：R1 中的事件解耦（M1）为纯架构清洁，不改变任何可观测行为，独立里程碑推进，避免挤压发布窗口。