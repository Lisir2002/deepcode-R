# Agent 任务编排与扩展能力加强设计

> 评审状态：📝 草案
> 关联模块：agent（workflow / tools / permission / session / workspace / terminal）
> 参考来源：
> - DeepSeek Harness（DSH）：https://github.com/deepseek-ai/deepseek-harness
> - Anthropic Claude Code：https://github.com/anthropics/claude-code
> - 前置设计：docs/plan-docs/deepseek-harness-borrowing-design.md（护栏部分与本设计衔接）

## 1. 背景与目标

DeepCore-Code 已具备 Agent 循环（`StatefulAgentWorkflow`）、工具注册与权限引擎（`ToolPermissionManager` / `ToolPermissionPolicyEngine`，`ALLOW/DENY/ASK` + AgentMode PLAN/AUTO）、PRoot 容器 + 远程 SSH 执行、MCP 客户端/服务器、`assets/prompts` 提示词资产、Room 分库持久化、上下文压缩等能力。

本设计吸收 DSH 的**任务编排运行时**（goal/jobs/schedule/workflow/run_code）与 Claude Code 的**声明式扩展生态**（agents/commands/skills/hooks/plugins 全为 Markdown+JSON），并结合现有护栏体系，为 DeepCore-Code 规划四层加强。目标：

1. **任务编排层**：让 Agent 具备可追溯的目标状态机、可恢复的后台任务与定时提醒。
2. **声明式扩展生态**：把"提示词/子代理/命令/技能"全部改为目录 + frontmatter 声明式，支持用户目录热加载与插件分发。
3. **工程编程**：给模型提供容器内脚本化批量执行入口，压缩多轮往返、省 token。
4. **统一护栏 + 沙箱预设**：衔接已落地的流式归一化护栏，补循环级护栏与文件影响三档模式。

### 1.1 设计原则

1. **从实际出发**：只做对 Android 端真实有增量价值的能力；不引入 Cordis 式运行时热插拔框架。
2. **声明式 > 过程式**：扩展面（子代理/命令/技能/钩子）优先用 Markdown/JSON 声明，可版本化、可分发。
3. **复用现有**：权限/持久化/后台调度优先扩展现有组件（权限引擎、Room 表、App 启动周期任务），不重复造轮子。
4. **渐进落地**：分三批，每批独立可验证、可回滚。

## 2. 现状盘点

### 2.1 已有能力（设计基线）

| 能力 | 现状 |
|---|---|
| Agent 循环 | `StatefulAgentWorkflow`：turn/step 驱动、事件流（`AgentEvent`）、重试/续写/压缩 |
| 工具与权限 | `ToolRegistry` 注册；`ToolPermissionManager`（弹窗审批队列）+ `ToolPermissionPolicyEngine`（ALLOW/DENY/ASK、rememberablePatterns、Shell 指令级匹配、DangerousCommandGuard、灾难性 rm 防护）；AgentMode：PLAN / AUTO |
| 消息与会话 | Room agent 库 17 表（含 `TodoItemEntity` / `CheckpointEntity` / `SkillStateEntity` / `WakeItemEntity` / `ModeSwitchHistoryEntity`）；`MessagePersistenceUseCase` 分块落库 |
| 提示词资产 | `assets/prompts/` 编号 Markdown（00-identity … 81-auto-mode），含 plan-mode / auto-mode |
| 执行环境 | PRoot 容器（Alpine）+ 远程 SSH；`ExecuteCommandTool`（bash）；`FileTools` / `RemoteSftpFileAccess` |
| 后台调度 | 明确**不依赖 WorkManager**：`core/worker/` 由 App 启动周期调用（CredentialRotationWorker / AuditPurgeWorker） |
| 流式护栏 | `core/network/DeltaAccumulator`（AUTO_DETECT 去重 + base64 折叠 + 200k 截断），已接入 workflow 与 provider |

### 2.2 缺口

- 无 goal（目标状态机）与 plan 的持久化协作（现有 PLAN 模式仅做只读约束）。
- 无后台任务（jobs）抽象：长编译/长测试只能前台阻塞。
- 无定时提醒（schedule）。
- 扩展面全在打包资产：无用户自定义目录、无 frontmatter 元数据、无 hooks 生命周期。
- 无脚本化批量执行入口（run_code）；工具调用为逐轮往返。
- 护栏只在流式层，缺循环层（重复调用提醒、结构化超时）。

## 3. 方案总览（四层架构）

```
┌─────────────────────────────────────────────────────────────┐
│  声明式扩展生态：agents/ commands/ skills/ hooks/ plugins      │  ← Claude Code 形态
│  （目录 + frontmatter + 用户目录热加载 + 插件 manifest）        │
├─────────────────────────────────────────────────────────────┤
│  任务编排层：goal(状态机) + jobs(后台任务) + schedule(定时)      │  ← DSH 形态
│              + plan 持久化协作                                  │
├─────────────────────────────────────────────────────────────┤
│  工程编程：run_code(容器内脚本批量执行 + 并发调度 + 结构化输出)    │  ← DSH run_code
├─────────────────────────────────────────────────────────────┤
│  统一护栏：工具链事件化(pre-execute→guard→execute→post)          │  ← DSH + CC hooks
│            + 文件影响三档模式 + 流式护栏(已落地)                  │
└─────────────────────────────────────────────────────────────┘
          底层：现有 Agent 循环 / 权限引擎 / Room / PRoot / MCP
```

## 4. 分方向设计

### 4.1 方向 A：任务编排层

#### A1. Goal（任务目标状态机）

目标：会话中"当前任务目标"可追溯、可修订、可归因，写入会话日志供压缩/恢复/审计。

- **持久化**：agent 库新增 `GoalEntity`（复用 `TodoItemEntity` 同类风格），字段：`goalId`、`sessionId`、`text`、`status`（PROPOSED / ACTIVE / DONE / ABANDONED）、`revision`（单调递增，CAS 变更）、`parentGoalId`（支持子目标）、`roundSeq`（归因到哪一轮）、`createdAt`/`updatedAt`。
- **语义**：每会话**唯一**当前 goal（对齐 DSH `GoalService` 契约：activation 不持久化、mutation 用 compare-and-set）。
- **变更走事件**：goal 变更写入 `AgentEvent.GoalChanged`（对齐 DSH `goal/change`），与消息同日志，压缩后可从日志折叠出目标快照。
- **与 workflow 联动**：在每轮 step 前把当前 goal 注入 system prompt（对齐 DSH "当前 goal 注入模型请求"）。

#### A2. Plan（计划协作状态）

- 现状：PLAN 模式仅做只读约束（权限引擎 DENY 写操作）。
- 增量：`PlanEntity` 持久化当前计划（`planId`、`sessionId`、`title`、`steps`(JSON)、`status`、`pendingSelection`），**pending selection** 在每轮 pre-step 追加到模型请求（对齐 DSH plan mode）；用户批准后写入一条 `user/message` 替换为 approved 计划。
- 映射：与项目现有 Plan/Spec 模式（plan-docs）语义对齐，UI 侧复用。

#### A3. Jobs（后台任务）

目标：长时间任务（容器内编译/测试/构建、远程同步）可后台运行、可 `kill`/`wait`/查状态，App 切后台不丢。

- **契约**（对齐 DSH `JobStart`/`JobRegistry`）：`JobSpec`（jobId、sessionId、kind、title）+ producer 声明 + runtime 预检 + `run(suspend)` 生命周期；`JobRegistry` 提供 `start/get/list/read/kill/wait`，owner/session 隔离，first-wins settlement（重复 start 幂等）。
- **执行载体**（从实际出发）：**不引入 WorkManager**，延续项目现状——容器/SSH 命令经前台 `TerminalKeepaliveService` 存活，jobs 状态写 Room（`JobEntity`：jobId/sessionId/kind/status(RUNNING/DONE/FAILED/KILLED)/exitCode/outputLocator/createdAt/finishedAt），进程句柄放内存注册表（进程被回收即状态置 INTERRUPTED，恢复时提示重跑）。
- **工具面**：新增 `job_start` / `job_status` / `job_kill` / `job_log` 四个工具（对齐 DSH jobs 消费），模型可自启动后台构建。

#### A4. Schedule（定时提醒）

- **规则**：`after`(延迟) / `at`(定点) / `every`(周期) 三种（对齐 DSH schedule），`ScheduleEntity`（scheduleId/sessionId/rule/args/status/enabled）。
- **调度载体**（从实际出发）：`every` 周期任务由 App 启动时的统一调度循环（沿用 `core/worker/` 模式）检查；`at`/`after` 短延迟用协程延时；跨重启恢复靠 Room 持久化 + 启动扫描未投递项（对齐 DSH "persistence barrier + idle-phase 维护"）。
- **投递**：到点向会话注入 `AgentEvent.ScheduleFired`，作为一条 `user/message`（带 "scheduled" 标记）进入 workflow。

### 4.2 方向 B：声明式扩展生态（Claude Code 形态）

核心思想：**扩展 = 目录 + 文件 + frontmatter**，无运行时 API 复杂度。

#### B1. 声明式目录结构

```
assets/ext/                       # 内置扩展（打包）
  agents/*.md                     # 子代理：frontmatter(name/description/tools/model)
  commands/*.md                   # 斜杠命令：frontmatter(description/argument-hint)
  skills/<name>/SKILL.md          # 技能：frontmatter(description/whenToUse/disable-model-invocation)
  hooks/<event>.json              # 生命周期钩子
  plugins/*/plugin.json           # 插件 manifest(name/version/description)
<workspace>/.codecore/            # 用户扩展目录（热加载、优先级高于内置）
  agents/ commands/ skills/ hooks/
```

- **frontmatter 元数据**（对齐 Claude Code 与 DSH SKILL.md）：
  - agent：`name / description / tools(白名单) / model`
  - command：`name / description / argument-hint`
  - skill：`name / description / whenToUse / disable-model-invocation / user-invocable`
- **解析与注册**：新增 `ExtensionLoader`（扫描内置 + 用户目录，解析 frontmatter → 注册到 `ToolRegistry` / 命令表 / skill 目录 / agent 目录）；用户目录用 `FileObserver` 监听变更热加载（fallback：每次会话启动全量重扫）。
- **与现有 prompts 的演进**：`assets/prompts/*.md` 保留（系统提示词基线），新增的 agent/command/skill 走 frontmatter 化声明；plan-mode/auto-mode 已具备，扩展为可被声明式覆盖。

#### B2. Hooks（生命周期钩子）

事件挂点（对齐 Claude Code hooks + DSH 工具流水线），复用现有工具链：

| Hook 事件 | 挂点 | 语义 |
|---|---|---|
| `PreToolUse` | `ToolRegistry.execute` 前、权限引擎之后 | 可拒绝/改写调用（对齐 security-guidance 插件） |
| `PostToolUse` | 工具返回后 | 可附加结果/记录审计 |
| `UserPromptSubmit` | 用户消息进入 workflow 前 | 可改写/拦截用户输入 |
| `Stop` | workflow 判定回合结束前 | 可阻止提前结束（对齐 ralph-loop 插件） |
| `SessionStart` | 会话初始化 | 注入会话上下文 |

- **声明方式**：`hooks/<event>.json`（`{"hooks":[{"type":"command","command":"...","timeout":...}]}`，对齐 Claude Code hooks.json）；执行由宿主（App 进程）解析，脚本经容器/SSH 跑（复用 `ExecuteCommandTool` 链路）。
- **策略**：fail-closed（钩子执行失败按拒绝处理）；可配置降级（`off / on / warn`）。

#### B3. Plugins（插件分发）

- `plugin.json` 极简 manifest（对齐 Claude Code：name/version/description/author）+ 约定目录。
- 支持从用户目录导入（zip → 解压到 `.codecore/plugins/<name>/`），经 `ExtensionLoader` 注册。
- **边界**：Android 端插件**只能声明** agents/commands/skills/hooks，**不能**注册任意原生代码（安全边界，对齐"无运行时插件 API"的克制）。

### 4.3 方向 C：工程编程（run_code 轻量版）

目标：给模型容器内"脚本化批量执行"入口，把多轮工具往返压成单次，省 token（移动端价值高）。

- **轻量形态**（从实际出发，不移植类型化 SDK 全套）：
  - 新增 `run_code` 工具：模型提交一段可执行脚本（`sh` / `python3`，容器内已有 Alpine 环境）+ 可选说明；宿主在容器/SSH 执行并返回 `{value, stdout, stderr, exitCode}`（对齐 DSH `CodeRunResult`）。
  - **并发调度**：`run_code` 内部对 `file_read / file_write / bash` 等**只读操作并发池化**（默认并发 4，对齐 DSH concurrency-safe），**写操作串行屏障**（对齐 DSH exclusive），由现有执行层保证顺序。
  - **结构化输出契约**：脚本内约定用 JSON 输出（`echo '{"ok":true}'`）→ 宿主解析为 `canonical output`（对齐 DSH 强制 canonical output），减少模型二次解析。
  - **护栏**：`run_code` 命令经现有 `ToolPermissionPolicyEngine`（DangerousCommandGuard + 灾难性 rm 防护）评估；执行挂 `timeout` 结构化超时（见 4.4）。
- **收益**：批量文件操作/多步 shell 一步完成；中间值不进对话历史（对齐 DSH "中间值不落日志"），进一步省 token。
- **配套**：系统提示词（prompts/60-tools-and-paths.md）补充 `run_code` 使用规范。

### 4.4 方向 D：统一护栏 + 沙箱预设

衔接前置设计（deepseek-harness-borrowing-design 第一批），此处合并为方向 D，避免重复文档：

#### D1. 循环级护栏（guard）

- **结构化超时**：`ExecuteCommandTool` / `run_code` 声明 `timeoutMs`，wrapper 超时返回结构化 `TOOL_TIMEOUT`（区分于正常失败），模型可据信号重试/换策略。
- **重复调用提醒**：在 workflow 循环层统计**连续相同工具 + 相同参数**调用（对齐 DSH repeat-tool-reminder 阈值 3/5/8），达阈值在下一轮注入 advisory 提醒（不阻塞、不改写、决策留给模型）。实现可复用 `DeltaAccumulator` 的观测思路（`duplicateCount`）。

#### D2. 文件影响三档模式 + 失败分类

- **三档文件影响模式**（对齐 DSH SandboxMode）：`read-only` / `workspace-write` / `danger-full-access`，per-call 解析（会话覆盖 > 全局默认 > 显式覆盖），映射到 `ToolPermissionPolicyEngine.evaluate` 的 `AgentMode` 之上（如 `read-only` = 现有 PLAN 约束的推广）。
- **失败分类**：统一工具失败枚举为 `DENIED(沙箱拒绝) / SANDBOX_UNAVAILABLE(容器不可用) / COMMAND_FAILED(命令本身失败)`（对齐 DSH denial/runner-failure 分类），`ExecuteCommandTool` 与 `run_code` 返回结构化分类，模型可精准应对。

## 5. 与现有架构的集成点

| 方向 | 集成点 |
|---|---|
| A 任务编排 | agent 库新增 `Goal/Plan/Job/Schedule` 4 表（`AgentDatabase` v2 迁移）；`AgentEvent` 新增 GoalChanged/JobState/ScheduleFired；`StatefulAgentWorkflow` step 前注入 goal/plan；`core/worker/` 统一调度循环扩展 schedule；`TerminalKeepaliveService` 承载 jobs 进程 |
| B 扩展生态 | 新增 `feature/agent/domain/ext/`（`ExtensionLoader`/`HookDispatcher`/`PluginManager`）；`ToolRegistry` 扩展 pre/post 钩子；`assets/ext/` + `.codecore/` 目录；`FileObserver` 热加载 |
| C run_code | 新增 `feature/agent/domain/tool/RunCodeTool.kt`；复用 `ExecuteCommandTool` 执行链与权限引擎；`run_code` 结果走 canonical output 解析 |
| D 护栏 | `ToolPermissionPolicyEngine` 扩展三档模式与失败分类；workflow 循环层加 guard 统计；复用 `DeltaAccumulator` |
| 数据注册表 | `core/data/DataRegistry` 登记新增 4 表，保证备份/恢复/自动迁移覆盖（遵守数据注册表单一声源纪律） |

## 6. 分期落地路线

### 第一批：纯增量、低风险（建议先行）

1. `run_code` 轻量工具 + 结构化超时（方向 C + D1 一部分）
2. 循环级 guard：重复调用提醒（方向 D1）
3. Goal 状态机 + `AgentEvent.GoalChanged` + step 前注入（方向 A1）

### 第二批：中等重构

1. Jobs 后台任务 + 4 个 job 工具（方向 A3）
2. 扩展生态骨架：`ExtensionLoader` + agent/command/skill frontmatter + 用户目录热加载（方向 B1）
3. 文件影响三档模式 + 失败分类（方向 D2）

### 第三批：能力扩展

1. Schedule 定时提醒（方向 A4）
2. Hooks 生命周期（PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart）（方向 B2）
3. Plan 持久化协作（方向 A2）
4. Plugins 分发（方向 B3）

## 7. 风险与约束

| 风险/约束 | 对策 |
|---|---|
| 数据库 schema 变更（新增 4 表） | agent 库 v1→v2 走 Room 迁移；按迁移纪律说明改动面；`DataRegistry` 同步登记 |
| Android 进程回收导致 jobs 中断 | jobs 状态落库 + 恢复提示；`TerminalKeepaliveService` 保活；不引入 WorkManager（维持项目现状） |
| run_code 安全风险（容器内任意脚本） | 复用现有权限引擎（DangerousCommandGuard + rm 防护）+ fail-closed；`run_code` 默认受限模式 |
| 热加载文件监听在 Android 的兼容性 | `FileObserver` 主路径 + 启动全量重扫 fallback；监听目录上限控制 |
| hooks 脚本执行开销 | 超时 + 失败 fail-closed；默认 off，用户按需开启 |
| 扩展生态与现有 prompts 的职责重叠 | prompts 保留为系统基线；ext 负责用户/插件扩展，优先级覆盖 |

## 8. 核心判断

DSH 提供的是**过程化运行时**（goal/jobs/schedule/run_code 的调度与恢复），Claude Code 提供的是**声明式生态**（agent/command/skill/hook/plugin 的文件化与分发）——两者互补：编排层保证"能跑完、可恢复、可调度"，声明层保证"易扩展、可分发、低门槛"。DeepCore-Code 已有不错的 Agent 循环与权限底座，本次加强以"声明式生态 + 任务编排 + run_code + 循环护栏"四层补齐，全部建立在对现有组件（权限引擎/Room/worker/工具链）的复用之上，无引入重框架。

## 9. 参考来源

- DSH：`docs/subsystems/{goal,plan,jobs,workflow,schedule,code-runtime}.md`、`packages/{goal,todo,jobs,workflow,schedule,code-runtime}/`
- Claude Code：`plugins/*`（plugin.json / agents/*.md / hooks/hooks.json / commands / skills）、`examples/settings/settings-*.json`
- 前置设计：docs/plan-docs/deepseek-harness-borrowing-design.md
