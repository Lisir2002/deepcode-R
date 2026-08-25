# 四类规范流程针对性落地设计（R-CodeCore）

> 评审状态：📝 草案
> 关联模块：agent（workflow / tool / prompt / sop / playbook）+ 仓库治理（docs / githooks）
> 参考来源：DSH https://github.com/deepseek-ai/deepseek-harness · Claude Code https://github.com/anthropics/claude-code（仅作背景，不展开）
> 前置：docs/plan-docs/agent-orchestration-extension-design.md（运行时能力已实施）

## 1. 背景与结论

学习 deepseek-harness 与 claude-code 后，提炼出四类"规范流程"，它们**不是四层叠加，而是分别作用在 Agent 工作链路的不同环节**，各司其职：

| 规范流程 | 作用环节 | 作用粒度 | 解决的问题 |
|---|---|---|---|
| **Spec / RFC** 规范驱动 | 变更前 | 一次开发周期 | 先设计评审再动手，防方向性返工 |
| **Playbook** 剧本编排 | 任务中 | 一个多阶段任务 | 大任务按剧本推进，不跳步 |
| **SOP** 标准作业 | 步骤中 | 一个固定操作 | 重复操作有固定章法 |
| **Agentic Workflow** 智能体工作流 | 单轮循环 | 一轮对话 | 多步推理+工具调用有纪律 |

链路关系：`用户提需求 → Spec(先评审) → Playbook(按剧本编排) → SOP(按步骤执行) → Agentic Workflow(单轮循环)→ 工具(信封结果)`。四个机制作用在不同层级，互不替代。

## 2. 现状基线（已核实代码）

- **Agentic Workflow**：`StatefulAgentWorkflow`（turn/step 驱动、事件流、重试/压缩）；`LoopGuardTracker`（循环提醒 3/5/8）；`GoalService`/`PlanService`/`JobTools`/`ScheduleTool` 已落地。
- **工具链**：`ToolRegistry` 注册分发；`HookDispatcher`（PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart）；`ToolResultTypeRegistry`（L3 类型 schema）；`RunCodeTool.buildSuccess` 已做 canonical JSON 解析。
- **资产**：`AgentAssetRegistry`（prompts + prompts.custom，frontmatter 解析 + mtime/FileObserver 热加载）；`SystemPromptProvider`（StaticRuleSource 按 mode 装配）。
- **治理**：`docs/plan-docs/` 18 份设计文档带评审状态标记；`.githooks/pre-commit` + `commit-msg` 已校验 docs/modules 与提交规范。

## 3. 针对性落地设计

### 3.1 Agentic Workflow —— 作用在单轮对话循环

**作用位置**：`StatefulAgentWorkflow` 的 turn/step 循环（每轮对话的推理+工具调用纪律）。

**现状**：循环护栏 `LoopGuardTracker`、任务编排 goal/plan/jobs/schedule 均已落地；缺"单轮循环的统一执行纪律"：目标注入、循环提醒、结构化超时三个信号未串成一个闭环规范。

**针对性设计**：
1. **每轮循环头部注入当前目标**：step 前把 `GoalService` 当前 goal（若存在）注入请求头（"当前任务目标：…"），让模型每轮都锚定目标不跑偏（对齐 DSH goal 注入）。
2. **循环提醒注入**：`LoopGuardTracker.takeAdvisory()` 的提醒在 CallLlm 时追加（已实现，核对接入是否完整）。
3. **结构化超时统一**：`ExecuteCommandTool`/`run_code` 超时统一返回 `TOOL_TIMEOUT`，并在 prompts 中说明"遇 TOOL_TIMEOUT 应重试或换策略"。

**改动面**：`StatefulAgentWorkflow` step 前注入 goal；核对 `LoopGuardTracker` 接入；prompts/60-tools-and-paths.md 补超时应对说明。
**验收**：JVM 单测断言"有 goal 时每轮请求含目标头；重复调用达阈值注入提醒；TOOL_TIMEOUT 信号可被模型识别"。

### 3.2 SOP —— 作用在重复性步骤

**作用位置**：单步固定操作（发版、迁移、提交、排障），运行时被 Agent 引用。

**现状**：纪律散在 `AGENTS.md` 与 `prompts/15-project-rules.md`，非结构化、不可覆盖。`AgentAssetRegistry` 的 frontmatter+热加载可复用。

**针对性设计**：
1. 新增 `feature/agent/domain/sop/SopRegistry.kt`（复用 `AgentAssetCore` 解析，frontmatter 增 `whenToUse`），资产放 `assets/sop/`：
   `10-release.md`（发版）· `20-migration.md`（迁移）· `30-asset-sync.md`（资产同步）· `40-git-commit.md`（提交）· `50-troubleshooting.md`（排障）· `90-conduct.md`（Always/Ask/Never）。
2. `SystemPromptProvider` 装配后追加"相关 SOP 摘要"（按 mode 注入：如 AUTO 模式注入 40-git/30-asset-sync）。
3. `AGENTS.md` 保持唯一权威源，sop/ 为单向同步副本（资产同步纪律同步更新）。

**改动面**：新增 `SopRegistry.kt` + 6 份资产 + `SystemPromptProvider` 注入 + `ContainerInstaller` 释放。
**验收**：≥5 份 SOP 可加载注入；JVM 单测解析 frontmatter。

### 3.3 Playbook —— 作用在多阶段任务

**作用位置**：一个多阶段大任务（feature-dev / code-review / bug-fix）的编排推进。

**现状**：运行时编排（goal/plan/jobs/schedule）已落地；`AgentAsset` 只有 `component/agent` 两 kind，**无声明式剧本资产与执行器**。

**针对性设计**：
1. `AgentAsset` 增 `kind` 字段（缺省 `component` 向后兼容）；playbook frontmatter：`kind: playbook / name / stages:[{name, agents[], sop[], gates(approval|auto), guards(timeout)}]`。
2. 新增 `feature/agent/domain/playbook/PlaybookExecutor.kt` + 触发入口（复用 `LoadSkillTool` 语义）：按 stages 顺序推进，agents/sop 注入上下文，gates=approval 阻塞等用户确认，guards 走现有 `LoopGuardTracker`/`HookDispatcher`；阶段状态并入 plan 或新增 `PlaybookRunEntity`（实施时按改动面最小化定，若加表则 `DataRegistry` 登记）。
3. 内置 3 条剧本（贴项目语义）：`feature-dev`（发现→设计文档[联动Spec]→分支→实施→冒烟→单测→提交→合入）、`code-review`（diff→按类型派专项agent→聚合分级）、`bug-fix`（复现→根因→修复→回归→提交）。

**改动面**：`AgentAsset` + `AgentAssetCore` 解析 kind；新增 `PlaybookExecutor`；新增 `assets/playbooks/*.md` ×3。
**验收**：3 条剧本可触发顺序推进；阶段状态可查询/中断/恢复。

### 3.4 Spec —— 作用在变更治理

**作用位置**：一次开发周期的变更管理（先出设计文档评审再实施）。

**现状**：`docs/plan-docs/` 评审状态是**约定**，无硬校验。

**针对性设计**：
1. 状态机沿用 `📝 草案 → ✅ 已评审 → 已实施`，`✅ 已评审` 附一句话评审结论。
2. `.githooks/pre-commit` 扩展（与 docs/modules 校验并列，提示 + `--no-verify` 逃生口）：
   - 提交含 `feature/`/`core/` 编译型改动 → 提示需配套 `docs/plan-docs/*-design.md`；
   - 提交含 `*-design.md` → 校验头部评审状态行。
3. `prompts/40-approach.md` 追加"重大改动先出设计文档并标记状态"（联动 Playbook 的 feature-dev 剧本）。

**改动面**：`.githooks/pre-commit` 扩展 + `docs/plan-docs/README.md` 补充 + prompts 更新。
**验收**：新功能分支提交被提示补 design 文档；状态行缺失被提示。

## 4. 集成与分期

| 批次 | 内容 | 验收 |
|---|---|---|
| 第一批 | 3.1 Agentic Workflow 闭环（goal 注入核对 + TOOL_TIMEOUT 提示） | 单测、构建绿 |
| 第二批 | 3.2 SOP（`SopRegistry` + 6 资产 + 注入） | 加载/注入验证 |
| 第三批 | 3.3 Playbook（kind + `PlaybookExecutor` + 3 剧本） | 剧本推进/中断/恢复 |
| 第四批 | 3.4 Spec（pre-commit 扩展 + README + prompts） | 提交提示生效 |

编译型改动按 AGENTS.md 冒烟 `:app:assembleDebug`；push 前 `:app:testReleaseUnitTest`。

## 5. 风险与约束

| 风险 | 对策 |
|---|---|
| goal 注入影响既有对话行为 | 仅注入头部一行、可关闭；单测回归 |
| SOP 与 AGENTS.md 双份漂移 | AGENTS.md 唯一权威，sop/ 单向副本 |
| Playbook 编排复杂度 | 复用 goal/plan/approval，先 3 条剧本 |
| pre-commit 强制阻塞 | 提示 + `--no-verify` 逃生口，与 docs/modules 同策略 |
| 新增表需备份覆盖 | 若加 PlaybookRun 表，`DataRegistry` 同步登记 |

## 6. 核心判断

四类规范流程分别作用在**对话循环 / 固定步骤 / 多阶段任务 / 变更治理**四个不同环节，各司其职、可独立落地、互不耦合。R-CodeCore 运行时底座已齐，本设计是**针对性补齐四类机制各自的作用点**：Agentic Workflow 侧重闭环核对、SOP 侧重资产化注入、Playbook 侧重声明式编排执行、Spec 侧重硬校验。全部复用现有组件，无重框架。
