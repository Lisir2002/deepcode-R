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

- **注入源统一抽象**：复用 `SystemPromptProvider` 内嵌的 `PromptSource` 接口，新增三类 Source：`GoalHintSource`（当前任务目标）、`PlanPendingHintSource`（待批准计划选择）、`LoopAdvisorySource`（循环提醒）。workflow 在 step 前调 `SystemPromptProvider` 组装注入块。
- **面向模型格式**：**混合标记格式**——自然语言引导行 + 代码块包裹正文（如 `【当前任务目标】\n\`\`\`\n<text>\n\`\`\``），引导行保语义、代码块防格式污染。
- **importance 3 级**：`P0 必须` / `P1 常规` / `P2 可裁剪`。默认归属：goal=**P0 必注入**、plan-pending=**P1 常规**、loop-advisory=**P2 可裁剪**。
- **注入预算**：**完整预算策略**——每轮注入块总字符上限常量（默认值实施时可调，约 400~800 字符），超限按 importance 降级裁剪（先裁 P2 → 再裁 P1），P0 永不裁。
- **注册方式**：接入 `SystemPromptProvider` 现有 Source 聚合链，不新增第二套装配逻辑；每轮由 workflow 触发 `build(ctx)` 取注入块。

**改动面**：新增 3 个 PromptSource；`StatefulAgentWorkflow` step 前统一走 `SystemPromptProvider` 组装注入块；预算常量。
**验收**：JVM 单测——有 goal 时每轮注入块含目标头；注入总长超预算时 P2 先被裁剪、P0 保留；无 goal 时无目标注入块。

#### 3.1.3 六段式工具流水线（深入设计）

**六段契约**：`pre-execute（门）→ guard（护栏）→ execute（执行）→ post-execute（可改写结果）→ finalizeContent（结果定型）→ result（只读观测）`。

**主价值（讨论定稿）**：**guard 段 + 文件观察**。其余段现状已天然覆盖：`runToolSync` 现有「缓存门 → viewImage 守卫 → executeWithContext → 图片/附件剥离 → toolOutputStore.process → 写缓存/事件/增量索引」即为门/执行/处理/定型/观测的自然组合。本批不做破坏性重构，六段作为**契约写进文档与代码注释**，代码层只把缺失的 guard 段做成真实可插桩的链。

**guard 段：统一 Guard 接口 + 链式注册**
- 新增 `ToolGuard` 接口：`suspend fun guard(ctx): ToolGuardResult`，三态 `PASS / BLOCK(error) / ADVISORY(warning)`；首 BLOCK 短路、ADVISORY 进提醒队列（对齐 `HookDispatcher` 的 multibinding 汇集模式）。
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
1. 新增 `feature/agent/domain/sop/SopRegistry.kt`（复用 `AgentAssetCore` 解析，frontmatter 增 `whenToUse` + `order`），资产放 `assets/sop/`，共 6 份：
   `10-release.md`（发版）· `20-migration.md`（迁移）· `30-asset-sync.md`（资产同步）· `40-git-commit.md`（提交）· `50-troubleshooting.md`（排障）· `60-ai-conduct.md`（agent 行为规则步骤化，来源 `prompts/` 行为纪律）。
   90-conduct（Always/Ask/Never）**不进 SOP**——已在 `AGENTS.md` 且运行时被 `SystemPromptProvider` 注入，无步骤性、重复无价值。
2. **触达方式**：摘要注入 + loadSop 按需取。`SystemPromptProvider` 全量常驻注入 SOP 清单摘要（名称 + whenToUse 一句话，**不做 mode 过滤**，6 条摘要很短）；新增轻量 `loadSop` 工具让模型按需取完整正文（复用 skill 的「清单 + loadSkill」模式）。
3. **正文契约**：结构化编号步骤，每步含「操作 + 判定 + 产出/出错处理」，保证模型照做不跳步。
4. **权威源与同步**：双权威源——10-50 对齐 `AGENTS.md`、60-ai-conduct 对齐 `prompts/`；每份 sop 头部注明来源文件；pre-commit 提示（改了 `AGENTS.md` 或 `prompts/` 行为规则但未改对应 sop/ 时提示，不阻断，复用 3.4 批次的 pre-commit 扩展）。

**改动面**：新增 `SopRegistry.kt` + `loadSop` 工具 + 6 份资产 + `SystemPromptProvider` 摘要注入 + `ContainerInstaller` 释放。
**验收**：6 份 SOP 可加载注入摘要；JVM 单测解析 frontmatter（whenToUse/order/步骤编号）；loadSop 返回结构化正文。

### 3.3 Playbook —— 作用在多阶段任务

**作用位置**：一个多阶段大任务（feature-dev / code-review / bug-fix）的编排推进。

**现状**：运行时编排（goal/plan/jobs/schedule）已落地；`AgentAsset` 只有 `component/agent` 两 kind，**无声明式剧本资产与执行器**。

**针对性设计**（深入讨论定稿）：
1. `AgentAsset` 增 `kind` 字段（缺省 `component` 向后兼容）；playbook frontmatter：`kind: playbook / name / stages:[{name, agents[], sop[], gates(approval|auto), guards(timeout)}]`。
2. **推进模型**：模型声明完成 + 显式推进工具。模型每轮可见当前阶段目标/步骤与 gates；阶段工作做完后调 `playbook_advance` 进入下一阶段；`gates=approval` 复用 `planApprovalManager.awaitApproval(reason, sessionId)` 阻塞等用户批准（与模式切换审批同链路）。
3. **持久化**：新增独立 `PlaybookRunEntity` 表（playbookId / currentStage / stageStatuses(JSON) / gates），agent 库新增 v 迁移；`DataRegistry` 同步登记（备份自动覆盖）。
4. **触发入口**：斜杠命令 + 工具双入口——声明式 `/playbook <name> <描述>` 命令（复用 ExtensionCommand）供用户显式启动；`playbook_start` 工具供模型自主识别任务匹配时启动；`playbook_advance` 推进。两入口走同一 `PlaybookExecutor`。
5. **失败处理**：阶段失败（模型声明失败 / 审批拒绝）→ PlaybookRun 置 `ABORTED`，向模型与用户反馈失败阶段 + 原因；模型修复后重新启动或放弃。
6. 内置 3 条剧本（贴项目语义）：`feature-dev`（发现→设计文档[联动Spec]→分支→实施→冒烟→单测→提交→合入）、`code-review`（diff→按类型派专项agent→聚合分级）、`bug-fix`（复现→根因→修复→回归→提交）。

**改动面**：`AgentAsset` + `AgentAssetCore` 解析 kind；新增 `PlaybookExecutor` + `PlaybookRunEntity` + `playbook_start/playbook_advance` 工具 + `/playbook` 命令；新增 `assets/playbooks/*.md` ×3。
**验收**：3 条剧本可触发顺序推进；approval gate 走现有确认弹窗；阶段状态可查询/中断/恢复；失败置 ABORTED。

### 3.4 Spec —— 作用在变更治理

**作用位置**：一次开发周期的变更管理（先出设计文档评审再实施）。

**现状**：`docs/plan-docs/` 评审状态是**约定**，无硬校验。

**针对性设计**（深入讨论定稿）：
1. 状态机沿用 `📝 草案 → ✅ 已评审 → 已实施`，`✅ 已评审` 附一句话评审结论。
2. `.githooks/pre-commit` 扩展（与 docs/modules 校验并列，**提示 + `--no-verify` 逃生口，与现有同策略**）：
   - **配套性触发范围**：仅提交含编译型改动（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提示需配套 `docs/plan-docs/*-design.md`；资产/资源/纯文案改动不触发，低噪音。
   - **状态机校验粒度**：提交含 `*-design.md` → 校验头部评审状态行**存在 + 值合法**（`📝 草案 / ✅ 已评审 / 已实施`），缺失或非法则提示；**不校验**状态机顺序防回退（需读 git 历史、跨多次提交脆弱）。
3. `prompts/40-approach.md` 追加"重大改动先出设计文档并标记状态"（联动 Playbook 的 feature-dev 剧本）。

**改动面**：`.githooks/pre-commit` 扩展（提示项与 SOP 同步提示并列）+ `docs/plan-docs/README.md` 补充 + prompts 更新。
**验收**：编译型改动提交被提示补 design 文档；状态行缺失/非法被提示；纯文案提交不误报。

## 4. 集成与分期

| 批次 | 内容 | 验收 |
|---|---|---|
| 第一批 | 3.1 Agentic Workflow：① step 前上下文纪律（3 个 Source + 预算裁剪）② 六段式流水线 guard 链（ToolGuard 接口 + FileObservationGuard + 文件观察硬拦截）③ 闭环核对（goal 注入 + TOOL_TIMEOUT 提示） | JVM 单测（注入/预算/FS_NOT_OBSERVED/FS_STALE/豁免）、构建绿 |
| 第二批 | 3.2 SOP（`SopRegistry` + `loadSop` 工具 + 6 份资产 + 全量摘要注入） | 摘要注入/loadSop 取正文/单测解析 |
| 第三批 | 3.3 Playbook（kind + `PlaybookExecutor` + `PlaybookRunEntity` + 双入口 + 3 剧本） | 剧本推进/审批/中断/恢复/ABORTED |
| 第四批 | 3.4 Spec（pre-commit 提示扩展：配套性 + 状态行校验 + SOP 同步提示 + README + prompts） | 提示生效、无误报 |

编译型改动按 AGENTS.md 冒烟 `:app:assembleDebug`；push 前 `:app:testReleaseUnitTest`。

## 5. 风险与约束

| 风险 | 对策 |
|---|---|
| goal 注入影响既有对话行为 | 仅注入头部一行、可关闭；单测回归 |
| 注入预算裁剪误伤关键上下文 | importance 分级 + P0 永不裁；裁剪策略单测覆盖 |
| 文件观察硬拦截误伤（如模型已读但 mtime 恰变） | FS_STALE 为可恢复错误（提示重读），不产生永久阻断 |
| 文件观察被 shell 写绕过 | 明确边界：仅 agent 文件工具链，容器写靠 SOP 纪律（文档写明） |
| 新增 guard 链影响既有工具执行 | 链默认 PASS 短路、按工具白名单激活（仅文件类），单测回归 |
| SOP 与 AGENTS.md 双份漂移 | AGENTS.md 唯一权威，sop/ 单向副本 |
| Playbook 编排复杂度 | 复用 goal/plan/approval，先 3 条剧本 |
| pre-commit 强制阻塞 | 提示 + `--no-verify` 逃生口，与 docs/modules 同策略 |
| 新增表需备份覆盖 | `PlaybookRunEntity` 新增后 `DataRegistry` 同步登记（备份自动覆盖） |

## 6. 核心判断

四类规范流程分别作用在**对话循环 / 固定步骤 / 多阶段任务 / 变更治理**四个不同环节，各司其职、可独立落地、互不耦合。经逐机制深入讨论后，各自作用点已收敛为：

- **Agentic Workflow**：① step 前上下文纪律（统一 Source + 混合标记 + 3 级 importance + 完整预算裁剪）② 六段式工具流水线（契约化六段 + 真实 ToolGuard 链，核心是文件观察硬拦截：mtime 版本 CAS + 新建豁免 + 仅 agent 文件工具链）。
- **SOP**：摘要常驻 + loadSop 按需取正文；结构化编号步骤；5 流程 + 60-ai-conduct；双权威源 + pre-commit 同步提示。
- **Playbook**：模型声明完成 + `playbook_advance` 推进；独立 `PlaybookRunEntity` 持久化；斜杠命令 + 工具双入口；复用 `PlanApprovalManager` 审批；失败 ABORTED。
- **Spec**：pre-commit 提示（提示 + 逃生口）；配套性仅编译型改动触发；状态行存在 + 合法值校验。

全部复用现有组件（`SystemPromptProvider` Source / `HookDispatcher` multibinding / `PlanApprovalManager` / `ExtensionCommand` / `DataRegistry`），无重框架、无破坏性重构。
