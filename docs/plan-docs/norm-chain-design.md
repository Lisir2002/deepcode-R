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

**总体设计**（深入讨论定稿）：Agentic Workflow 在本批重点落地两块：**step 前上下文纪律**（每轮进入 LLM 前的统一注入规范，3.1.2）与**六段式工具流水线**（工具执行的统一纪律，3.1.3）。单轮闭环三项（3.1.1）为既有能力核对。

#### 3.1.1 单轮循环闭环（既有能力核对）

1. **每轮循环头部注入当前目标**：step 前把 `GoalService` 当前 goal（若存在）注入请求头（"当前任务目标：…"），让模型每轮都锚定目标不跑偏（对齐 DSH goal 注入）。
2. **循环提醒注入**：`LoopGuardTracker.takeAdvisory()` 的提醒在 CallLlm 时追加（已实现，核对接入是否完整）。
3. **结构化超时统一**：`ExecuteCommandTool`/`run_code` 超时统一返回 `TOOL_TIMEOUT`，并在 prompts 中说明"遇 TOOL_TIMEOUT 应重试或换策略"。

#### 3.1.2 step 前上下文纪律（深入设计）

**定位**：把「每轮 CallLlm 前注入哪些上下文」从散落的拼接逻辑收敛为统一规范，防止注入无纪律（格式漂移、无优先级、超预算膨胀）。

- **注入源统一抽象**：复用 `SystemPromptProvider` 内嵌的 `PromptSource` 接口。**Source 全集**（审计定稿，8 类，D1 实现时一次登记，D0 的 4 个 Source 一并纳入）：`GoalHintSource`（当前任务目标）、`IntentAskSource`（意图问判三问，D0）、`BehaviorModeSource`（行为模式纪律行，D0）、`PlanPendingHintSource`（待批准计划选择）、`PlaybookStageSource`（Playbook 当前阶段，3.3）、`GoalAdjustEventSource`（目标状态候选迁移事件，D0）、`GoalStaleSource`（目标失配提醒，D0）、`LoopAdvisorySource`（循环提醒）。workflow 在 step 前调 `SystemPromptProvider` 组装注入块。
- **面向模型格式**：**混合标记格式**——自然语言引导行 + 代码块包裹正文（如 `【当前任务目标】\n\`\`\`\n<text>\n\`\`\``），引导行保语义、代码块防格式污染。
- **importance 3 级**：`P0 必须` / `P1 常规` / `P2 可裁剪`。默认归属（审计定稿，8 源）：goal=**P0 必注入**；问判注入 / 行为模式 / plan-pending / playbook-stage / GoalAdjustEvent=**P1 常规**；GoalStale / loop-advisory=**P2 可裁剪**。
- **注入预算**：**完整预算策略**——每轮注入块总字符上限常量（默认值实施时可调，约 400~800 字符），超限按 importance 降级裁剪（先裁 P2 → 再裁 P1），P0 永不裁。**排序与裁剪**（八源共存定稿，理解优先）：注入顺序 `goal(P0) → 问判注入(P1) → 行为模式(P1) → plan-pending(P1) → playbook-stage(P1) → GoalAdjustEvent(P1) → GoalStale(P2) → loop-advisory(P2)`；同 P1 内固定 问判 → 行为模式 → plan-pending → playbook-stage → GoalAdjustEvent；超预算从尾部 P2 裁起（先裁 loop-advisory → GoalStale），再裁同 P1 按注入顺序倒序（GoalAdjustEvent → playbook-stage → plan-pending → 行为模式 → 问判），P0 永不裁。
- **注册方式**：接入 `SystemPromptProvider` 现有 Source 聚合链，不新增第二套装配逻辑；每轮由 workflow 触发 `build(ctx)` 取注入块。

**改动面**：新增 8 个 PromptSource（含 D0 的问判注入 / 行为模式 / GoalStale / GoalAdjustEvent 4 个）；`StatefulAgentWorkflow` step 前统一走 `SystemPromptProvider` 组装注入块；预算常量。
**验收**：JVM 单测——有 goal 时每轮注入块含目标头；注入总长超预算时 P2 先被裁剪、P0 保留；无 goal 时无目标注入块。

#### 3.1.3 六段式工具流水线（深入设计）

**六段契约**：`pre-execute（门）→ guard（护栏）→ execute（执行）→ post-execute（可改写结果）→ finalizeContent（结果定型）→ result（只读观测）`。

**主价值（讨论定稿）**：**guard 段 + 文件观察**。其余段现状已天然覆盖：`runToolSync` 现有「缓存门 → viewImage 守卫 → executeWithContext → 图片/附件剥离 → toolOutputStore.process → 写缓存/事件/增量索引」即为门/执行/处理/定型/观测的自然组合。本批不做破坏性重构，六段作为**契约写进文档与代码注释**，代码层只把缺失的 guard 段做成真实可插桩的链。

**guard 段：统一 Guard 接口 + 链式注册**
- 新增 `ToolGuard` 接口：`suspend fun guard(ctx): ToolGuardResult`，三态 `PASS / BLOCK(error) / ADVISORY(warning)`；首 BLOCK 短路、ADVISORY 进提醒队列（Dagger multibinding `Set<ToolGuard>` 汇集，新增护栏仅 `@IntoSet` 注册，对齐 `HookDispatcher` 现有模式）。
- 链上护栏与执行顺序：**权限检查 → 文件观察 → 危险命令 → 超时钳制**；循环 guard（`LoopGuardTracker`）保留在结果收集后（既有位置不动）。
- 现有护栏归属：权限（workflow 层）、危险命令/超时钳制（`ExecuteCommandTool` 工具层）保持原位，通过 guard 链的判定契约对齐，不迁移不重构。

**文件观察纪律（guard 段新增核心）**
- **拦截策略**：硬拦截 + 版本 CAS + 新建豁免。
- **版本来源**：**mtime 即版本**，复用 `ToolResultCache.recordFileMtime` 现有记录点（读文件时已记录 mtime，本批仅追加判定）。
- **观察源**：`readFile` 观察（标记 path 已观察）+ `writeFile` 即已知（写入成功即视为已观察，模型天然"知道"内容）；`search`/`list` 不标记（目录级/片段级信息，易误判）。
- **生效边界**：**仅 agent 文件工具链**（readFile/writeFile/editFile）；容器/终端内 shell 写（echo >、sed -i、run_code 脚本内写）不逐条拦截——无法可靠解析任意 shell 意图，靠 SOP/prompt 纪律约束「脚本内先读后写」。
- **错误码**：`FS_NOT_OBSERVED`（未观察就写已存在文件，提示先 readFile）/ `FS_STALE`（观察后文件被外部改动，mtime 不一致，提示重新 readFile）。
- **新建豁免**：目标不存在的 writeFile/editFile 直接放行（新建）；`writeFile` 覆写已存在文件——因「writeFile 即已知」，写入成功后视为已观察，同会话后续 editFile 放行。
- **版本更新闭环**：文件被写后更新该 path 的观察版本（mtime），后续编辑以新版本校验，避免"自己刚改过又被拦"。

**落地程度**：契约化六段（文档 + 代码注释标注六段边界）+ 真实 guard 链挂载在 runToolSync 的 execute 之前；不做显式六阶段函数重构（现有流式/缓存/事件耦合深，重构风险 > 收益）。

**改动面**：新增 `ToolGuard` 接口 + `GuardRegistry`（或 multibinding `Set<ToolGuard>`）+ `FileObservationGuard`（复用 `ToolResultCache.fileMtimes` + 追加 observed 判定）；`StatefulAgentWorkflow.runToolSync` 在 execute 前挂 guard 链；prompts 补 FS_NOT_OBSERVED / FS_STALE 应对说明。
**验收**：JVM 单测——未读即写已存在文件 → `FS_NOT_OBSERVED` BLOCK；读后外部改 mtime 再写 → `FS_STALE` BLOCK；writeFile 新建 → 放行；writeFile 后同会话 editFile → 放行。

### 3.2 SOP —— 作用在重复性步骤

**作用位置**：单步固定操作（发版、迁移、提交、排障），运行时被 Agent 引用。

**现状**：纪律散在 `AGENTS.md` 与 `prompts/15-project-rules.md`，非结构化、不可覆盖。`AgentAssetRegistry` 的 frontmatter+热加载可复用。

**针对性设计**（深入讨论定稿）：
1. 新增 `feature/agent/domain/sop/SopRegistry.kt` + **独立 `SopAsset` 结构**（字段定稿：`name` / `order` / `whenToUse` / `body`，与 `AgentAsset` 解耦，复用 `AgentAssetCore` 的 frontmatter 解析能力），资产放 `assets/sop/`，共 6 份：
   `10-release.md`（发版）· `20-migration.md`（迁移）· `30-asset-sync.md`（资产同步）· `40-git-commit.md`（提交）· `50-troubleshooting.md`（排障）· `60-ai-conduct.md`（agent 行为规则步骤化，来源 `prompts/` 行为纪律）。
   90-conduct（Always/Ask/Never）**不进 SOP**——已在 `AGENTS.md` 且运行时被 `SystemPromptProvider` 注入，无步骤性、重复无价值。
2. **触达方式**：摘要注入 + loadSop 按需取。`SystemPromptProvider` 全量常驻注入 SOP 清单摘要（名称 + whenToUse 一句话，**不做 mode 过滤**，6 条摘要很短）；新增轻量 `loadSop` 工具让模型按需取完整正文（复用 skill 的「清单 + loadSkill」模式）。
3. **正文契约**：结构化编号步骤，每步含「操作 + 判定 + 产出/出错处理」，保证模型照做不跳步。
4. **权威源与同步**：双权威源——10-50 对齐 `AGENTS.md`、60-ai-conduct 对齐 `prompts/`；每份 sop 头部注明来源文件；pre-commit 提示（warning 级、不阻断）：改 `AGENTS.md` → 提示检查 `sop/10-50` 同步；改 `prompts/` 下行为规则文件（精确映射表：15-project-rules、40-approach）→ 提示检查 `sop/60-ai-conduct` 同步，复用 3.4 批次的 spec-check.sh。

**改动面**：新增 `SopRegistry.kt` + `loadSop` 工具 + 6 份资产 + `SystemPromptProvider` 摘要注入 + `ContainerInstaller` 释放。
**验收**：6 份 SOP 可加载注入摘要；JVM 单测解析 frontmatter（whenToUse/order/步骤编号）；loadSop 返回结构化正文。

### 3.3 Playbook —— 作用在多阶段任务

**作用位置**：一个多阶段大任务（feature-dev / code-review / bug-fix）的编排推进。

**现状**：运行时编排（goal/plan/jobs/schedule）已落地；`AgentAsset` 只有 `component/agent` 两 kind，**无声明式剧本资产与执行器**。

**针对性设计**（深入讨论定稿）：
1. **资产契约**：playbook 资产放 `assets/playbooks/`，新增**独立 `PlaybookRegistry`** 扫描（与 `SopRegistry` 同构，复用 `AgentAssetCore` 的 frontmatter 解析 + 热加载；**不并入只扫 `prompts/` + `prompts.custom/` 的 `AgentAssetRegistry`**——审计定稿，避免目录/kind 混用）；frontmatter 用 **YAML 列表 + 精简字段**——`kind: playbook / name / stages:[{name, description, agents[], sop[], gates(approval|auto), guards(timeout)}]`；`description` 即阶段目标（注入模型用）；`agents` 引用 `agent: true` 专项 agent 资产（**执行模型为真子代理隔离上下文，见 3.6**——阶段激活时 `SubAgentRunner` 生成独立上下文的子代理执行，非主模型"切角色"）；`sop` 引用 `assets/sop/` 资产（阶段激活时作为步骤规则注入）；均解析时校验存在性。复用 `SkillParser` SnakeYAML 嵌套解析，资产可读性好。
2. **阶段注入**：新增 `PlaybookStageSource`，step 前把「当前阶段：名称 + description + gates」注入统一注入块（importance 归 **P1 常规**，走预算裁剪），复用 3.1.2 的 step 前上下文纪律，不重复造轮子。**与 GoalService 完全独立**——不自动创建/覆盖 goal，阶段目标仅由本 Source 注入，避免双重目标源冲突与覆盖用户手动 goal；**运行期间挂起 goal 维护双信号**（`GoalStaleSource` 失配提醒 + `GoalAdjustEvent` 事件均不注入，审计定稿），避免 Playbook 阶段目标与 goal 维护信号互相干扰。
3. **推进模型**：模型声明完成 + 显式推进工具。阶段工作做完后调 `playbook_advance` 进入下一阶段；`gates=approval` 复用 `planApprovalManager.awaitApproval(reason, sessionId)` 阻塞等用户批准（与模式切换审批同链路；**`!` 标记可跳过此 approval gate**——审计定稿，`!` 统一跳过流程级确认但永不绕过权限系统）。**完成判定护栏**：advance(done) 前若连续 N 轮无实质工具动作（无文件写/无命令执行）就声明完成，则注入 advisory 提醒「确认阶段产出物」，防模型误报完成/跳步（复用 LoopGuard 思路）。
4. **状态机**：阶段 + 运行双状态机——运行级 `RUNNING/COMPLETED/ABORTED`；阶段级 `PENDING/ACTIVE/DONE/FAILED`。推进时当前 DONE→下一阶段 ACTIVE；失败当前 FAILED→运行 ABORTED；审批阻塞不改变阶段状态（awaitApproval 阻塞执行流）。
5. **工具签名**：`playbook_start(name, context?)` 创建运行并返回首阶段描述；`playbook_advance(action=done|fail)` 默认作用于当前会话最近一次 RUNNING 运行（不传 runId），done 推进、fail 置失败中止；`playbook_resume` 恢复本会话最近一次 `INTERRUPTED` 运行并返回当前阶段；`playbook_retry` 从本会话最近一次 `ABORTED` 运行的 FAILED 阶段恢复（该阶段置回 ACTIVE、已完成阶段保留）；均返回下一阶段描述或完成提示。模型无需管理 runId。
6. **持久化**：新增独立 `PlaybookRunEntity` 表（**结构定稿**：`playbookRunId`(PK) / `sessionId`(索引) / `playbookName` / `currentStageIndex` / `stageStatuses`(JSON) / `status` / `createdAtMs` / `updatedAtMs`，对齐 PlanEntity 风格），agent 库新增 v 迁移；`DataRegistry` 同步登记（备份自动覆盖）。**中断/恢复**：对齐 `JobEntity`——SessionStop hook 把该会话 RUNNING 运行置 `INTERRUPTED`；新会话需显式 `playbook_resume` 继续，`playbook_start` 覆盖旧运行。
7. **触发入口**：斜杠命令 + 工具双入口——声明式 `/playbook <name> <描述>` 命令（复用 ExtensionCommand）供用户显式启动；`playbook_start` 工具供模型自主识别任务匹配时启动；两入口走同一 `PlaybookExecutor`。
8. **失败处理**：阶段失败（模型声明失败 / 审批拒绝）→ PlaybookRun 置 `ABORTED`，向模型与用户反馈失败阶段 + 原因；**恢复双路径**（定稿）：模型可 `playbook_start` 从头重跑（覆盖旧 ABORTED 运行）或 `playbook_retry` 从 FAILED 阶段恢复（该阶段置回 ACTIVE、已完成阶段保留），或放弃。
9. 内置 3 条剧本（贴项目语义）：`feature-dev`（发现→设计文档[联动Spec]→分支→实施→冒烟→单测→提交→合入）、`code-review`（diff→按类型派专项agent→聚合分级）、`bug-fix`（复现→根因→修复→回归→提交）。

**改动面**：新增独立 `PlaybookRegistry`（扫 `assets/playbooks/`，复用 `AgentAssetCore` frontmatter 解析 + 热加载）；新增 `PlaybookExecutor` + `PlaybookRunEntity` + `PlaybookStageSource` + `playbook_start/playbook_advance` 工具 + `/playbook` 命令；新增 `assets/playbooks/*.md` ×3。
**验收**：3 条剧本可触发顺序推进；approval gate 走现有确认弹窗；阶段状态可查询/中断/恢复；失败置 ABORTED。

### 3.4 Spec —— 作用在变更治理

**作用位置**：一次开发周期的变更管理（先出设计文档评审再实施）。

**现状**：`docs/plan-docs/` 评审状态是**约定**，无硬校验。

**针对性设计**（深入讨论定稿）：
1. **状态行格式**：新文档固定文档头部 `> 评审状态：<状态>` 引用行，取值 `📝 草案 / ✅ 已评审 / 已实施`（纯值，不带括号注释/组合态）；`> 评审结论：<一句话>` 另起一行（`✅ 已评审` 时建议必填）。pre-commit 正则匹配两字段。**校验范围：只校验本次提交触碰的 `*-design.md`**（git diff 范围），存量 18 份不强制、不批量迁移——存量值带注释/组合态、两份用章节而非头部行，全量硬校验需先批量规整，噪音高。
2. **实现形态**：新建独立 `spec-check.sh` 封装 Spec 校验（含 SOP 同步提示），`.githooks/pre-commit` 调用；沿用 **提示 + `--no-verify` 逃生口，与 docs/modules 同款**（阻断 + 逃生）。
   - **SOP 同步提示**（warning 级、不阻断）：本次提交改 `AGENTS.md` → 提示检查 `sop/10-50` 同步；改 `prompts/` 行为规则文件（映射表：15-project-rules、40-approach）→ 提示检查 `sop/60-ai-conduct` 同步。
   - **配套性触发规则**（口径定稿）：仅「新增 `feature/<module>` 目录 或 本次提交新增非 test 路径 `.kt`」→ 提示需配套 `docs/plan-docs/*-design.md`；实现：`git diff --cached --name-status --diff-filter=A` 扫描新增文件，`.kt` 且路径不含 `test/`/`androidTest/` 即命中；已有模块普通修改（修 bug、补单测、改资源）不触发，低噪音防「狼来了」。
   - **状态机校验粒度**：提交含 `*-design.md` → 校验头部评审状态行**存在 + 值合法**（`📝 草案 / ✅ 已评审 / 已实施`），缺失或非法则提示；**不校验**状态机顺序防回退（需读 git 历史、跨多次提交脆弱）。
3. **联动 Playbook**：feature-dev 剧本的设计文档阶段产出物 = 新建 `docs/plan-docs/*-design.md` 且头部状态行 `📝 草案`；该阶段 `gates=approval`（用户评审后才进实施）；prompts 引导模型写完设计文档后确认状态行格式。不做事执行器级文件校验（避免执行器感知具体文件结构）。
4. `prompts/40-approach.md` 追加"重大改动先出设计文档并标记状态"（联动 Playbook 的 feature-dev 剧本）。

**改动面**：新增 `spec-check.sh` + `.githooks/pre-commit` 调用 + `docs/plan-docs/README.md` 补充 + prompts 更新。
**验收**：新增模块/新增源文件提交被提示补 design 文档；状态行缺失/非法被提示；纯文案与普通修改不误报。

### 3.5 统一开关控制（跨 App 运行时机制）

**定位**：3.1-3.3 三类运行时机制的启用控制统一收敛到设置页，便于整体/逐项降风险；Spec 预检属仓库治理（`.githooks`），不进 App 设置。

**设计**（深入讨论定稿）：设置页新增「规范流程」分组——
- **总开关** `norm_flow_enabled`（默认开）：关闭即 3.1-3.3 三机制整体停用（step 前注入块、guard 链、SOP 摘要、Playbook 运行均不生效）。
- **子开关**（默认开，逐项可关）：`step_inject`（step 前注入纪律）/ `tool_guard`（guard 链 + 文件观察）/ `sop_summary`（SOP 摘要常驻注入）/ `playbook_auto`（模型自主触发 `playbook_start`；`/playbook` 斜杠命令显式入口不受此开关影响）。
- **读取位置**：workflow 组装注入块/挂 guard 链、`SopRegistry` 摘要注入、`PlaybookExecutor` 入口统一读开关（settings 设置库/DataStore）。

**改动面**：settings 库新增开关字段 + 设置页 UI + 各机制读取点。
**验收**：关闭总开关后注入/guard/SOP/playbook 均不生效；子开关单独关闭仅影响对应机制。

### 3.6 编排流程：多代理子代理机制（深入设计）

**作用位置**：Playbook 阶段内专项 agent 的激活执行模型（对齐 Claude Code 递归自生成代理架构：主代理生成子代理、独立上下文、多代理协调；对齐 DSH run_code 批量执行与 jobs 后台任务）。

**背景**：3.3 Playbook 阶段 `agents[]` 引用专项 agent。经深入讨论，执行模型从"切角色"升级为**真子代理隔离上下文**。

**设计**（深入讨论定稿）：
1. **执行模型：真子代理隔离上下文**——每个子代理拥有独立消息历史、系统提示（= 专项 agent body）、工具集（白名单）、工作目录、中止控制器；跑完只回传结构化结果，不污染主上下文。生成入口：阶段激活时按 `agents[]` 声明自动生成（模型无需手动 spawn），`async` 与权限档位由 frontmatter 声明。
2. **执行循环：独立子循环**——`PlaybookExecutor` 内 `SubAgentRunner`：`buildSubAgentRequest`（systemPrompt=agent body、messages=子代理私有 state、tools=白名单、maxRounds=预算）。**不触发主 workflow 的 goal/plan/loop-guard 注入**——子代理锚定在阶段目标（frontmatter `description` 注入子代理系统提示），避免主会话目标/循环提醒干扰子任务。事件流独立上报（UI 可区分展示"子代理执行中"）。
3. **运行模式：同步 + 可选后台**——默认同步：主代理等待子代理完成拿到结果再继续（与 approval gate、事件流兼容）。阶段 `async: true`：子代理入后台 jobs 队列，`playbook_advance` 前主代理可查询子代理状态；结果就绪后投递回会话（对齐 JobEntity 的 INTERRUPTED 语义，进程回收标记）。
4. **权限作用域：强制三档降权**——复用既有 `SandboxMode` 三档（`READ_ONLY` 默认，只读文件/搜索/只读命令 → `WORKSPACE_WRITE` 允许写工作区文件 → `DANGER_FULL_ACCESS` 含容器/终端/危险工具；命名对齐代码，审计定稿）。由阶段 `gates` 声明，**不继承主会话完整权限**；子代理内工具执行复用 `ToolPermissionManager` 审批链但按档位过滤。
5. **防失控：预算感知 + 自动压缩**——子代理消耗计数对齐主 workflow 注入预算；接近预算时对子代理私有消息做**子代理级压缩**（复用 ContextCompactor 锚定摘要逻辑，适配纯内存上下文，不走 `agentMessageDao` 持久化）；实在不够才截断：强制结束子循环，已做动作摘要 + 截断原因返回主代理。
6. **协调模式：多子代理并行**——阶段 `agents[]` 可声明多个子代理并行（如 code-review 同时派 review-agent + style-agent），各自独立循环，全部完成统一收结果（失败子代理标记 FAILED，不影响其它子代理）。并发调度：每子代理一个协程 `awaitAll` 聚合，结果按 agents 声明顺序稳定返回。
7. **结果契约：结构化 JSON 聚合（canonical output）**——每个子代理结束，`PlaybookExecutor` 把动作摘要/产出物/结论/完成状态序列化为结构化 JSON 字段（对齐 DSH canonical output）；多子代理聚合为一个结果块返回主代理，主代理直接读结构化字段，无需二次解析。
8. **Fork 变体：继承式子代理（深入讨论定稿）**——补齐"需要延续主上下文"的阶段场景。**seed 模式**：playbook frontmatter `agents[]` 每 agent 声明 `seed: spawn|fork`（缺省 spawn）；spawn=全新上下文（上 1-7 点），fork=阶段上下文继承。**Fork 继承范围**：主会话当前 goal/plan + 最近 N 条消息（约 20 条）+ 阶段目标与产物引用（文件路径清单），再叠加 agent 角色指令；避免把主会话全部历史/敏感内容带入。**结束语义**：与 spawn 同走统一回收（结构化 JSON 聚合），并额外把关键结论/产物引用写回 `PlaybookRun.stageStatuses`（如审查结论、修改文件清单），供后续阶段直接引用。
9. **互斥与幂等纪律（深入讨论定稿）**——多子代理并行下的写冲突与重入防护。**互斥粒度**：同一 Playbook 阶段内，所有 `WORKSPACE_WRITE`/`FULL` 档位子代理的写操作（文件写/命令执行）串行执行，读操作保持并行（多子代理并行价值在读/分析类为主，写不频繁）。**幂等纪律**：模型自主判断 + 阶段产物清单辅助——阶段 DONE 时在 `stageStatuses` 记录本阶段已完成的文件操作清单，`playbook_resume`/`playbook_retry` 时把清单注入给模型对照跳过已完成的文件操作；文件写按内容写入天然幂等（同内容重写无害），不额外加锁；不建副作用防重表（避免过度工程，run_code 等外部副作用交由模型按清单判断）。

**改动面**：`PlaybookExecutor` 内 `SubAgentRunner` + 子代理私有会话状态 + 三档权限过滤 + 子代理级压缩适配 + 后台投递 + 阶段内写串行化 + 产物清单注入；`AgentAsset` frontmatter `gates` 增权限档位 + `seed` 字段（spawn|fork）。
**验收**：code-review 剧本 review-agent 在 READ_ONLY 下只能读；多子代理并行结果稳定聚合；async 子代理可后台执行并投递结果；子代理上下文不污染主上下文（压缩/截断后主上下文无残留）。

### 3.7 思维链路：单轮循环推理纪律（深入设计）

**作用位置**：`StatefulAgentWorkflow` 的单轮循环（turn 内多轮工具调用 + 推理过程的纪律）。

**背景**：对齐 Claude Code 查询循环（消息压缩/续轮判断/推理呈现）与 DSH（结构化超时/错误分类/循环 guard）。核实现状：压缩（`ContextCompactor` 锚定摘要 + 预取 + 独立压缩模型）与截断续写上限已成熟，**无需大改**；缺口在"空转检测软收敛"与"推理过程预算与呈现"。

**设计**（深入讨论定稿）：
1. **软性收敛：空转检测强制结束**——新增空转计数器：每轮统计"实质产出"（文件写 / 命令执行 / run_code 任一命中即清零）。连续 N 轮（默认 6）无实质产出 → 不再 advisory（区别于 LoopGuard），**强制结束回合**，把已做动作摘要 + 结束原因返回用户。位置：主循环 reduce 后、下一轮 CallLlm 前检查。与 `MAX_ITERATIONS`（50 硬上限）、`LoopGuardTracker`（3/5/8 advisory）三级防线并存——LoopGuard 提醒最早、空转收敛次之、MAX_ITERATIONS 兜底。与 3.1.3 文件观察联动：文件写动作（Edit/Write 命中）计为实质产出；纯读（readFile/search/list）不计。
2. **推理过程：预算 + 流式呈现**——新增推理预算配置（`reasoning_budget`，默认 off；开启后按 provider 能力传 reasoning 参数）。呈现：模型返回 reasoning 时逐段**流式呈现**给用户（可折叠面板，复用现有 AssistantText reasoningAcc 通道增强），保持思维链可见。上下文：reasoning 累积进后续轮次（保持思维链连续），压缩时随 AssistantMessage 一并处理（`estimateTokens` 已计入 reasoning 长度）。对齐：Claude extended thinking 的 budget 控制；DeepSeek reasoning_content 的流式返回。
3. **续轮判断（核对）**——现有"有工具调用则续轮、无则结束"符合查询循环语义，不新增状态机；仅把空转收敛（上）纳入续轮终止条件。

**改动面**：workflow 空转计数器 + 收敛注入；settings 新增 `reasoning_budget` 开关；provider 调用层传 reasoning 参数；UI 推理面板流式呈现。
**验收**：JVM 单测——连续 6 轮纯读后强制收敛返回摘要；有写动作清零计数；reasoning 开启后模型响应含推理且累积进后续轮；默认 off 不改变现有行为。

### 3.8 步骤结果汇总：Trajectory 运行轨迹 + 用量卡片（深入设计）

**作用位置**：跨会话/任务的数据基建（对齐 DSH 全链路可观测 append-only 轨迹；Claude Code cost-tracker / `/usage` 用量展示）。

**背景**：现有关键信息分散——`AgentMessageEntity`（消息级，含 toolName/toolArgs/isError/tokens，但缺结果摘要/耗时且压缩后 isCompacted 细节丢失）、`FileLogger`（日志级）、`zth_telemetry_events`（append-only 但语义绑定 zth 模块）。缺少一个**统一运行轨迹**作为"步骤结果汇总"的根基。

**设计**（深入讨论定稿）：
1. **独立轨迹表 `TrajectoryEntity`**（append-only）：`trajectoryId`(PK) / `sessionId`(索引) / `taskId` / `turnIndex` / `kind`(tool|turn|compaction|inject|error|timeout) / `toolName` / `argsHash` / `resultSummary` / `isError` / `durationMs` / `tokensIn` / `tokensOut` / `ts`。workflow 在每次工具执行完成时追加 tool 轨迹；在 turn 边界、压缩、goal/plan 注入、错误、超时时追加轻量标记（对齐 DSH 轨迹可回放完整"工作流"而不只"动作"）。**不受上下文压缩影响**（独立于 agent_messages，历史细节不丢失）。agent 库 v 迁移 + `DataRegistry` 登记（备份覆盖）。删除会话时级联清理。
2. **结果摘要：全工具定制提取**——逐工具提取规则表：readFile 留路径+行数、run_code 留 stdout 尾部+exit code、git diff 留统计、Edit/Write 留目标文件+变更概要、其余通用截断（前 N 字符 + truncated 标记）。**规则表挂在 `ToolResultTypeRegistry`（L3 schema）上扩展**（新增"轨迹摘要提取器"），不另建第二套注册。
3. **消费方**：① 3.7 强制收敛的"已做动作摘要"直接由轨迹聚合生成；② Playbook 阶段 DONE 时生成阶段轨迹总结（对齐 Fork 结论回写）；③ 审计回放（按 sessionId/taskId 查询完整轨迹）。
4. **用量卡片（对齐 Claude Code `/usage` + cost-tracker）**——每回合结束在 UI 展示轻量用量卡片：主显**本回合增量**（输入/输出/总 token、耗时、工具调用数）+ 附一行**会话累计**；**仅展示 token 不估成本**（标"依账单为准"，避免本地估算误导）。数据源：轨迹表按 taskId/turnIndex 聚合（本回合）+ 按 sessionId 聚合（累计）。卡片可在设置关闭。
5. **轨迹体积控制**——resultSummary 全工具截断控单条体积；`turn` 标记条数 ≤ 工具条数（轻量）；删除会话级联清理；可设保留条数上限（超出后仅删 turn 标记类）。

**改动面**：新增 `TrajectoryEntity` + DAO + agent 库 v 迁移 + `DataRegistry` 登记；workflow 追加轨迹点（工具执行完成/轮次边界/压缩/注入/错误/超时）；`ToolResultTypeRegistry` 增轨迹摘要提取器；每回合用量卡片 UI + settings 开关。
**验收**：JVM 单测——工具执行后轨迹有条目且 resultSummary 按规则提取；强制收敛摘要由轨迹聚合且含耗时/失败标记；用量卡片本回合+累计数字与轨迹聚合一致；删除会话级联清轨迹。

### 3.9 分层规则纪律（深入设计）

**作用位置**：跨会话的规则装配（对齐 Claude Code 分层 CLAUDE.md：全局/项目/目录级规则 + 显式优先级）。

**背景**：现状有项目级 AGENTS.md（权威纪律，运行时由 SystemPromptProvider 加载）与用户级 prompts.custom（覆盖），缺中间层。经深入讨论，补齐为**四级全量分层**。

**设计**（深入讨论定稿）：
1. **分层级别（四级）**——① **全局**（用户设备级，`~/.rcodecore/global-rules.md`）② **项目**（`AGENTS.md`，已有，权威源）③ **工作区**（工作区根/容器内 `rcodecore` 目录的 `workspace-AGENTS.md`，对特定项目/工作区注入差异化规则）④ **模块**（`feature/<module>/AGENTS.md`，子目录级规则，仅对该模块相关任务生效）。
2. **合并与优先级**——四级规则全量拼接进系统提示词；每份规则 frontmatter 可声明 `priority` 字段（数值大优先，缺省按层级 全局<项目<工作区<模块 递增）。冲突时按 priority 收敛，同 priority 靠后声明者优先。
3. **生效边界（省 token 设计）**——**全局/项目/工作区三级常驻注入**，但走 3.1.2 注入预算裁剪（超预算按 importance/priority 降级裁剪）；**模块级规则仅当本会话/任务涉及该模块时注入**（如工具读写了该模块文件，复用 3.1.3 文件观察的命中路径判断）。**借鉴省 token**：常驻层只注入每份规则的摘要/核心条目（少量 token），完整正文通过显式加载取（`/rules` 斜杠命令或 `load_rule` 工具，对齐 3.2 SOP 的"摘要常驻 + loadSop 按需取正文"模式）。**审计定稿：摘要/正文两级形态 D3 独立自研，不依赖 D4（SOP）实现**，仅共享形态约定，避免 D3↔D4 顺序依赖。
4. **热加载**——复用 `AgentAssetRegistry` 的 mtime/FileObserver 双机制（全局/工作区/模块规则文件增删改即失效缓存）。

**改动面**：`SystemPromptProvider` 增规则 Source（全局/工作区/模块，带 priority 与摘要/正文两级形态）+ 模块命中判断；`AgentAsset` 增 `priority` 字段解析；`/rules` 命令或 `load_rule` 工具（完整正文按需加载）；prompts/ 同步说明。
**验收**：JVM 单测——四级规则拼接顺序/priority 收敛正确；模块规则仅在涉及该模块时注入；常驻只含摘要、正文经显式加载可取全；热加载生效。

### 3.10 用户意图拆解（深入设计）

**作用位置**：workflow 上游——用户输入进入 agent 前的"意图理解与拆解"链路（对齐 Claude Code parseUserInput + plan 模式；DSH 任务编排层 goal/plan/jobs/schedule 的形态判定）。

**背景**：现状输入处理是"完整文本直接进 workflow + 斜杠命令完全相等匹配"（`findExact`），无结构化解析、无意图分类、无形态判定——模型靠直觉决定建 goal / 起 plan / 塞 job / 触 playbook。经深入讨论，补齐为**三层完整链路**。

**设计**（深入讨论定稿）：
1. **语法层：`UserInputParser` 结构化解析 + 意图分类**
   - 新增 `UserInputParser`：把请求解析为 `ParsedInput(command?, args, text, intentLabel)`——首 token 斜杠命令 + 剩余 args/text，纯文本原样；斜杠命令由"完全相等匹配"改为"前缀 + 参数匹配"（如 `/playbook start` 传参），复用现有 `SlashCommandRegistry`。
   - 意图分类（轻量规则）：`task`（任务性，含实现/修复/创建类动词）、`query`（询问性）、`file`（文件操作）、`command`（斜杠/命令）、`unknown`。Parser 输出 `intentLabel` 供形态判定参考（对齐 Claude Code 意图识别）。
2. **自我问判：意图问判清单注入（对齐 3.1.2 注入体系）**
   - step 前注入**意图问判三问**：① 我的理解（用户到底要什么）② 我的拆解思路（打算怎么拆/做）③ 应落哪个形态（goal/plan/jobs/schedule/playbook/普通对话）。
   - 模型每轮开始内省核对；**低置信（理解不确定 / 形态不确定）时主动调 `AskUserQuestion` 澄清**，不猜着做。问判注入作为 3.1.2 的一个 Source（importance=P1 常规，可被预算裁剪）。
3. **语义层：`intent_analyze` 判定平台（五形态路由）**
   - 新增 `intent_analyze` 工具作为**意图判定平台**：模型在拆解前调用，工具内部跑通判定全流程后输出结构化结果（形态 + 参数 + 置信度），模型据其执行下一步。
   - 判定流程（三阶）：① **规则预分类**（代码层关键词/模式，如"每天/每周"→schedule、"后台跑/编译"→jobs、"修复 bug"→playbook 匹配、"多步骤大任务"→plan、"长期目标"→goal）② **判定准则核对**（prompts/ 资产写清五形态判定准则，模型据此确认/修正预分类）③ **模型兜底**（模型最终裁定形态；低置信回退 AskUserQuestion）。
   - 五形态路由输出：`goal`（建/更新 GoalService）、`plan`（起 PlanService + 批准流程）、`jobs`（JobTools 后台）、`schedule`（ScheduleTool 定时）、`playbook`（PlaybookExecutor 剧本，匹配 playbook 资产）、`none`（普通对话）。判定结果注入上下文，模型调对应形态的既有工具执行。
4. **与既有机制关系**——不替代现有 goal/plan/jobs/schedule 工具，只在其上游加统一判定入口；`intent_analyze` 结果与 3.10.2 问判三问呼应（问判确认理解，判定平台定形态）。

**可借鉴增量**（深入讨论定稿，对齐 Claude Code parseUserInput / plan 模式 / `!` 优先级标记 / AskUserQuestion 选项化 + DSH `determine_and_update_goal` / `should_update_goal` 准则 / goal 状态机）：

5. **意图 → 行为模式切换**（对齐 CC plan 模式，深入讨论定稿）
   - **粒度**：四档 `behaviorMode`（与五形态正交）——`design`（设计/评审：只出方案不写文件）/ `execute`（执行：默认，正常工具调用）/ `research`（调研：先搜索后答，不写文件）/ `chat`（问答：普通对话不调工具）。
   - **规则映射**：Parser 意图分类 `task`→execute、`query`+比较/了解/查资料类动词→research、设计/评审/方案类动词→design、无任务纯问答→chat；模型可改判，作为 intent_analyze 输出字段。
   - **生效方式**：提示级纪律行 + guard 软提醒——step 前注入"本轮行为模式"纪律行（如"当前为设计模式：只输出设计方案，不调用写文件类工具；如需写代码请先说明"）；guard 层对 design/research 模式下文件写类工具返回 advisory 软提醒（不阻断，模型可自主覆盖）。复用六段式流水线 guard 段。
   - **触发权**：规则预分类 → 模型改判 → 用户 `?` 标记强制咨询姿态（增量 7 联动）→ 新增 `/mode design|execute|research|chat` 斜杠命令手动切换（复用 `SlashCommandRegistry`）。
   - **持久性**：默认每轮按新输入重判（意图变则模式自然变）；显式指令（`/mode` 切换、`?` 标记）锁定到下次显式解除；**plan 形态强制 behaviorMode=design**（审计定稿——判定为 plan 形态时批准前强制 design、只出方案不改代码，批准后转 execute；design 阶段允许只读调研）。
   - **与 Spec（3.4）轻关联**：design 模式纪律行附带一句"若为新增/复杂改动，先出设计文档到 `docs/plan-docs/` 走 Spec 评审"，不强绑。
6. **持续意图维护闭环**（对齐 DSH `determine_and_update_goal` + `should_update_goal` 准则，深入讨论定稿）
   - 判定不一次性：形态判定落 goal/plan 后，任务执行中**持续核对**"当前目标是否仍匹配用户意图"。
   - **失配检测粒度**：语义相似度——规则层先轻量筛"疑似失配"（goal 关键词不命中且非澄清），仅在疑似时调 LLM 判相关度（输出 0-1 / yes-no），控制成本（复用现有 provider LLM 调用；项目无 embedding，不做向量）。
   - **提醒通道**：新建 `GoalStaleSource`（importance=P2 可裁剪，独立开关），step 前注入"当前目标可能已过期"提醒；连续 N 轮（**默认 2，可配置**）触发，排除澄清轮。
   - **准则载体**：`should_update_goal` 判定准则写入 `assets/prompts/`（"用户输入与当前目标冲突 / 扩展 / 缩小 / 任务完成 → 应更新 / 新建 / 完成目标"），**资产 + 代码兜底**（资产缺失时用代码内置常量）。
   - **反馈深度**：完整事件闭环，详见下文独立小节「持续意图维护闭环（`GoalAdjustEvent`）完整设计」。
**持续意图维护闭环（`GoalAdjustEvent`）完整设计**（增量 6 的闭环展开，深入讨论定稿）：

- **闭环全链路**：`判定 → 执行 → 工具结果 → GoalAdjustEvent 入队 → step 前注入（P1）→ 模型按 should_update_goal 准则 → GoalService 迁移 → 终态/切换清空`。
- **`GoalAdjustEvent` 定义**（丰富字段集）：`eventType`（事件类型，如 GOAL_ADJUST_HINT / GOAL_COMPLETE_HINT）+ `toolName`（来源工具）+ `resultState`（SUCCESS / FAILED / PARTIAL）+ `candidateAction`（CONTINUE / UPDATE / COMPLETE / ABANDON）+ `goalId` + `confidence`（0-1）+ `source`（来源分类：关键工具 / 成功迹象）。
- **六决策点**：
  1. **触发范围**：关键工具白名单（build/test/run_code/execute 等有明确成败的长任务类工具），失败/异常结果优先触发；成功结果仅在"任务完成迹象"时触发。
  2. **注入链路**：事件入队列，step 前统一注入（新事件优先、未消费保留），importance=**P1**（执行反馈高于失配提醒 P2），受 3.1.2 注入预算裁剪。
  3. **双信号协调**：事件提醒（执行反馈，P1）与 `GoalStaleSource` 失配提醒（输入失配，P2）独立并存 + 语义分工（一来自工具结果、一来自用户输入），不冲突；预算裁剪先裁 P2。
  4. **防重复**：队列去重 + 消费标记（同 goalId+eventType+candidateAction 同源事件已注入未消费则不重复注入）+ 模型调 GoalService 变更目标后清空队列。
  5. **终止条件**：目标状态到终态（COMPLETED/ABANDONED）或切换新目标时，清空事件队列并停止产生。
  6. **状态机约束**：保持现状三态（ACTIVE/COMPLETED/ABANDONED，不引入 NEEDS_UPDATE），迁移仍由模型按 `should_update_goal` 准则通过既有 `GoalService`/`PlanService` 完成。
- **与既有机制集成点**：
  - 事件产生挂在六段式工具流水线 post-execute 段（3.1.3），仅对白名单工具生成；
  - 事件注入并入 3.1.2 step 前注入体系（作为 Source，P1）；
  - 与 `GoalStaleSource`（P2，输入失配）互补，共同构成"目标维护双信号"；
  - 目标迁移复用既有 `GoalService`（三态），无新状态、无新表。
- **时序示例**（build 失败 → 目标更新）：
  1. 模型执行 `executeCommand("gradlew :app:assembleDebug")` → 返回失败；
  2. post-execute 段命中白名单 → 生成 `GoalAdjustEvent(eventType=GOAL_ADJUST_HINT, toolName=executeCommand, resultState=FAILED, candidateAction=UPDATE, goalId=…, confidence=0.8, source=build_failure)` 入队；
  3. 下一轮 step 前，注入块含该事件（P1，"编译失败，当前目标是否需调整"）；
  4. 模型按 `should_update_goal` 准则，调 `GoalService` 更新目标（如拆解子目标或修正路径）；
  5. 目标变更 → 清空事件队列；继续执行新目标直至 COMPLETED/ABANDONED → 队列终止。

7. **`!` 优先级 + `?` 咨询标记**（对齐 CC `!` 优先级标记，实现最轻）
   - `UserInputParser` 识别首 token `!`/`?` 标记：`marker ∈ FORCE / CONSULT / NONE`（`!`/`?` 后跟空格或直接接文本，前缀匹配，无歧义；有歧义按普通文本）。
   - `!`（立即执行）：注入"用户要求立即执行"纪律行，跳过**流程级确认**（审计定稿：统一指 plan 批准 + playbook approval gate，见 3.3）；**不绕过权限系统**（危险操作仍走权限审批）。
   - `?`（仅咨询）：注入"仅咨询，不修改文件、不建任务"纪律行，天然对齐 chat/design 模式（与增量 5 触发权联动）。
8. **结构化澄清问题**（对齐 CC AskUserQuestion 选项化，深入讨论定稿）
   - **触发时机**：intent_analyze 输出低置信（理解/形态任一不确定）时 workflow **自动触发**结构化澄清；**模型可跳过**（有足够信息时）。
   - **候选形态**：prompts 资产写"候选格式约定"（编号列表 + 每项一句），模型按约定生成候选理解，不强绑定代码模板。
   - **候选数量**：按歧义维度**动态 1-4 个**（形态不确定 / 目标理解不确定 / 范围不确定各维度对应候选）。
   - **结果回填**：用户选择作为下一轮输入**自然回填**，模型据此继续；intent_analyze 标记"已澄清"避免同一歧义重复问。

**改动面**：新增 `UserInputParser`（解析 + 意图分类 + marker）+ `IntentAnalyzeTool`（判定平台：五形态 + behaviorMode + marker，规则预分类 + 准则引用 + 兜底）；workflow step 前新增问判注入 Source + 行为模式纪律行 + `GoalStaleSource` 失配提醒 + 目标状态候选迁移事件；`/mode` 斜杠命令；prompts/ 资产补五形态判定准则 + `should_update_goal` 准则 + 澄清格式约定（代码兜底常量）；斜杠命令解析改前缀匹配。
**验收**：JVM 单测——Parser 解析 command/args/text/marker/意图分类正确；intent_analyze 预分类命中五形态 + behaviorMode、模型兜底可改判；`/mode` 切换锁定生效；低置信触发结构化澄清（动态 1-4 候选、可跳过、防重复）；失配提醒语义判定 + 连续 N 轮触发 + 排除澄清；`GoalAdjustEvent` 触发范围/字段/注入（P1）/去重/消费标记/目标变更清空/终态终止。

## 4. 集成与分期

| 批次 | 内容 | 验收 |
|---|---|---|
| 第一批 | 3.10 用户意图拆解（`UserInputParser` 解析 + 意图分类 + marker；问判注入；`intent_analyze` 判定平台五形态 + behaviorMode 路由；行为模式纪律行 + `/mode` 命令；`GoalStaleSource` 失配提醒 + `GoalAdjustEvent` 闭环；结构化澄清模板）⑨ 3.1 Agentic Workflow：① step 前上下文纪律（3 个 Source + 预算裁剪 + 四源排序）② 六段式流水线 guard 链（ToolGuard 接口 multibinding + FileObservationGuard + 文件观察硬拦截）③ 闭环核对（goal 注入 + TOOL_TIMEOUT 提示）④ 3.7 思维链路（空转软收敛 + reasoning_budget + 推理面板）⑤ 3.8 Trajectory 轨迹表（工具+轮次标记、全工具定制提取、消耗方聚合）⑥ 3.8 用量卡片（每回合 + 会话累计、仅 token）⑦ 3.9 分层规则纪律（四级拼接 + priority + 模块按需注入 + 摘要常驻/正文显式加载）⑧ 3.5 开关基础（总开关 + step_inject/tool_guard 子开关） | JVM 单测（注入/预算裁剪/排序/FS_NOT_OBSERVED/FS_STALE/豁免/空转收敛/推理参数/轨迹提取与聚合/用量一致性/规则分层与优先级/意图解析与判定路由/marker/行为模式/失配提醒/事件闭环/问判注入）、构建绿 |
| 第二批 | 3.2 SOP（独立 `SopAsset` + `SopRegistry` + `loadSop` 工具 + 6 份资产 + 全量摘要注入 + sop_summary 子开关，与 3.9 摘要/正文两级形态共享） | 摘要注入/loadSop 取正文/单测解析 |
| 第三批 | 3.3 Playbook + 3.6 子代理机制（kind + `PlaybookExecutor` + `SubAgentRunner` + `PlaybookRunEntity` + 4 工具 + 双入口 + 3 剧本 + 三档权限过滤 + spawn/fork 双 seed + 并行聚合 + 结论回写 + 阶段内写串行化 + 产物清单幂等 + async 后台 + playbook_auto 子开关） | 剧本推进/审批/中断/恢复/失败重试/ABORTED/子代理隔离与聚合/Fork 继承与回写/写串行化/重入幂等 |
| 第四批 | 3.4 Spec（pre-commit 提示扩展：配套性 + 状态行校验 + SOP 同步提示 + README + prompts） | 提示生效、无误报 |

编译型改动按 AGENTS.md 冒烟 `:app:assembleDebug`；push 前 `:app:testReleaseUnitTest`。

## 5. 风险与约束

| 风险 | 对策 |
|---|---|
| goal 注入影响既有对话行为 | 仅注入头部一行、可关闭；单测回归 |
| 行为模式纪律行误伤（design 模式误拦写操作） | 提示级约束非强制，模型可自主退出；仅 design/research 生效 |
| 失配提醒误报（用户闲聊被当目标失配） | 连续 N 轮（默认 2）且排除澄清轮；提醒仅提示不阻断 |
| marker 误解析（`!`/`?` 是正文内容） | 仅首 token `!`/`?` + 边界判断；有歧义按普通文本处理 |
| 注入预算裁剪误伤关键上下文 | importance 分级 + P0 永不裁；裁剪策略单测覆盖 |
| 文件观察硬拦截误伤（如模型已读但 mtime 恰变） | FS_STALE 为可恢复错误（提示重读），不产生永久阻断 |
| 文件观察被 shell 写绕过 | 明确边界：仅 agent 文件工具链，容器写靠 SOP 纪律（文档写明） |
| 新增 guard 链影响既有工具执行 | 链默认 PASS 短路、按工具白名单激活（仅文件类），单测回归 |
| SOP 与 AGENTS.md 双份漂移 | AGENTS.md 唯一权威，sop/ 单向副本 |
| Playbook 编排复杂度 | 复用 goal/plan/approval，先 3 条剧本 |
| 子代理隔离上下文增加内存/实现复杂度 | 独立会话状态 + 子代理级压缩适配；先 READ_ONLY 场景验证再开放写档 |
| 多子代理并行结果冲突（同文件写） | 阶段内写串行化：WORKSPACE_WRITE/FULL 档位写操作串行、读并行；单测覆盖 |
| 重入重复副作用（resume/retry） | 模型自主判断 + 阶段产物清单辅助（stageStatuses 注入对照跳过）；文件写天然幂等 |
| 四级规则注入膨胀 | 三级常驻带预算裁剪 + 摘要/正文两级形态 + 模块级按需注入 |
| priority 冲突难调 | 缺省按层级递增（全局<项目<工作区<模块），冲突收敛可评审 |
| 空转收敛误伤（模型在思考但未调工具） | 阈值 6 轮足够宽；收敛返回摘要可让用户续说，不丢已做动作 |
| 推理预算增加 token 成本 | `reasoning_budget` 默认 off；开启时预算可调、可折叠呈现 |
| 轨迹表随会话增长 | resultSummary 全工具截断；turn 标记轻量；删除会话级联清理；可设保留条数上限 |
| 用量卡片每回合展示打扰 | 卡片轻量单行、可设置关闭；仅 token 不估成本无误导 |
| Fork 子代理带入主上下文敏感信息 | 只继承 goal/plan + 最近 N 条 + 产物引用，不继承完整历史；缺省 spawn |
| pre-commit 强制阻塞 | 提示 + `--no-verify` 逃生口，与 docs/modules 同策略 |
| 新增表需备份覆盖 | `PlaybookRunEntity`/`TrajectoryEntity` 新增后 `DataRegistry` 同步登记（备份自动覆盖） |

## 6. 核心判断

四类规范流程分别作用在**对话循环 / 固定步骤 / 多阶段任务 / 变更治理**四个不同环节，各司其职、可独立落地、互不耦合。经逐机制深入讨论后，各自作用点已收敛为：

- **用户意图拆解（上游基座）**：UserInputParser（结构化解析 + 意图分类）→ 自我问判注入（三问 + 低置信澄清）→ `intent_analyze` 判定平台（规则预分类 + 判定准则 + 模型兜底，五形态路由：goal/plan/jobs/schedule/playbook/none）。
- **Agentic Workflow**：① step 前上下文纪律（统一 Source + 混合标记 + 3 级 importance + 完整预算裁剪）② 六段式工具流水线（契约化六段 + 真实 ToolGuard 链，核心是文件观察硬拦截：mtime 版本 CAS + 新建豁免 + 仅 agent 文件工具链）③ 思维链路（空转软收敛三级防线 + 推理预算流式呈现）。
- **步骤结果汇总**：Trajectory 轨迹表（工具+轮次标记、全工具定制提取、append-only 不受压缩影响）作为"已做动作摘要/阶段总结/审计"的单一数据源；每回合用量卡片（本回合增量 + 会话累计、仅 token）。
- **分层规则纪律**：四级全量（全局/项目/工作区/模块）+ 拼接 + 显式 priority；三级常驻（带预算裁剪）+ 模块按需注入 + 摘要常驻/正文显式加载（省 token）。
- **SOP**：摘要常驻 + loadSop 按需取正文；结构化编号步骤；5 流程 + 60-ai-conduct；双权威源 + pre-commit 同步提示。
- **Playbook**：模型声明完成 + `playbook_advance` 推进；独立 `PlaybookRunEntity` 持久化；斜杠命令 + 工具双入口；复用 `PlanApprovalManager` 审批；失败 ABORTED。阶段专项 agent 以**真子代理隔离上下文**执行——独立子循环、三档降权、预算感知压缩、多子代理并行、结构化 JSON 聚合；`seed: spawn|fork` 双模式（fork 阶段上下文继承 + 结论回写 stageStatuses）；互斥纪律（阶段内写串行化）+ 幂等纪律（模型自主 + 产物清单）。
- **Spec**：pre-commit 提示（提示 + 逃生口）；配套性仅编译型改动触发；状态行存在 + 合法值校验。

全部复用现有组件（`SystemPromptProvider` Source / `HookDispatcher` multibinding / `PlanApprovalManager` / `ExtensionCommand` / `DataRegistry` / `ToolResultTypeRegistry`），无重框架、无破坏性重构。
