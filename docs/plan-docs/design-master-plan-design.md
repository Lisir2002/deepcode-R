# R-CodeCore 设计主计划（Design Master Plan）

> 评审状态：📝 草案
> 定位：全项目设计工作的**总纲与顺序控制器**。后续所有设计任务从本文档登记、按序推进；已完成/在研/待设计在此登记状态。
> 使用方式：新增设计 → 在 §4 登记（附详细设计文档）→ 按 §3 顺序推进 → 更新状态。互有依赖的项不并行推进、不跳级。
> 关联：详细设计文档（norm-chain-design.md 等）是各设计项的实现细节；本文档只管**顺序与状态**；任务**进度追踪（含前后依赖）**见 [progress-tracker.md](./progress-tracker.md)（进度单一事实源）。

## 1. 设计域全景（现状登记）

### 1.1 已实施（✅）

| 设计项 | 文档 | 说明 |
|---|---|---|
| 流式增量语义归一化 | [streaming-delta-normalizer-design.md](./streaming-delta-normalizer-design.md) | 预防"字段语义假设"类 bug（base64 重复显示） |
| 对话列表布局重构 + 删除/重命名检修 + 主题色 | [chat-session-list-refactor-design.md](./chat-session-list-refactor-design.md) | 布局重构、左滑删除/重命名、主题色适配 |
| 输入栏编排入口重构 | [chat-inputbar-redesign-design.md](./chat-inputbar-redesign-design.md) | ChatInputField/Toolbar/Panels 落地 |
| Skill 管理 | [skill-management-design.md](./skill-management-design.md) | 技能中心 + 技能作用域 v2 |
| Skill 协作 | [skill-collaboration-design.md](./skill-collaboration-design.md) | 作用域分级 + 上下文贯穿 + 工具绑定 |
| pre-commit 健康检查 | [precommit-health-design.md](./precommit-health-design.md) | 首款内置技能落地 |
| 编码预检 | [coding-preflight-design.md](./coding-preflight-design.md) | M2/M3 完成，与 pre-commit-health 组合 |
| 数据保全 | [data-preservation-design.md](./data-preservation-design.md) | DataRegistry 备份/恢复单一事实源 |
| 虚拟环境支持 | [emulator-support-design.md](./emulator-support-design.md) | 模拟器/虚拟机支持 |
| 内置 MCP 服务器 | [builtin-mcp-server-design.md](./builtin-mcp-server-design.md) | 客户端+服务器双角色 |
| 命令防死循环 | [command-loop-guard-design.md](./command-loop-guard-design.md) | 防死循环 + BusyBox 兼容护栏 |
| Claude Code 借鉴（部分） | [claude-code-study-roadmap.md](./claude-code-study-roadmap.md) | R01–R07 已落地（Hook/WakeQueue/Agent 资产/危险命令守卫） |

### 1.2 已设计待实施（📝 当前主设计域：规范流程与编排）

> 详细设计见 [norm-chain-design.md](./norm-chain-design.md)（3.1–3.10 十章，逐机制讨论定稿）。

| 批次 | 设计项 | 详细章节 | 状态 |
|---|---|---|---|
| D0 | 用户意图拆解：输入解析管线 + 自我问判 + 意图形态判定平台 | §3.10 | 📝 已设计待实施 |
| B1 | Agentic Workflow 基座：step 前上下文纪律 + 六段式工具流水线（guard 链 + 文件观察）+ 闭环核对 + 统一开关 | §3.1 / §3.5 | 📝 已设计待实施 |
| B1 | 思维链路：空转软收敛 + 推理预算流式呈现 | §3.7 | 📝 已设计待实施 |
| B1 | 步骤结果汇总：Trajectory 轨迹表 + 用量卡片 | §3.8 | 📝 已设计待实施 |
| B1 | 分层规则纪律：四级规则 + priority + 摘要/正文两级 | §3.9 | 📝 已设计待实施 |
| B2 | SOP 标准作业流程 | §3.2 | 📝 已设计待实施 |
| B3 | Playbook 剧本编排 + 子代理机制（spawn/fork、三档权限、并行聚合、互斥/幂等） | §3.3 / §3.6 | 📝 已设计待实施 |
| B4 | Spec 规范驱动（pre-commit 治理） | §3.4 | 📝 已设计待实施 |

### 1.3 草案待细化（📝 未进入主设计域）

| 设计项 | 文档 | 说明 |
|---|---|---|
| Agent 任务编排与扩展能力加强 | [agent-orchestration-extension-design.md](./agent-orchestration-extension-design.md) | norm-chain 前置（运行时能力已实施，文档草案） |
| DeepSeek Harness 能力调研与借鉴 | [deepseek-harness-borrowing-design.md](./deepseek-harness-borrowing-design.md) | 借鉴评估，剩余能力待纳入主计划 |
| Claude Code 调研借鉴（设计） | [claude-code-study-design.md](./claude-code-study-design.md) | 12 节设计 + 决策记录（roadmap 已部分实施） |
| 长输出分块消息 | [chunked-message-design.md](./chunked-message-design.md) | 长输出分块方案 |
| 数据层重构 | [data-layer-refactor-design.md](./data-layer-refactor-design.md) | 方向已定：全部一次性/每模块 DataStore/按域拆库 |
| 网络层性能优化 | [network-layer-optimization-design.md](./network-layer-optimization-design.md) | 网络层优化 |

## 2. 设计推进原则

1. **顺序驱动**：被依赖项先于依赖项；主设计域先 **D0（用户意图拆解，基座）** → 再 B1 → B2 → B3 → B4（B1 承接 D0 的注入体系并作为后续基座，B2/B3 复用其注入预算；摘要/正文两级形态 D3 与 D4 各自独立自研、仅共享形态约定）。
2. **单设计可独立评审**：每项设计在详细文档中自成章节（含改动面 + 验收），可独立评审与实施。
3. **不跳级**：设计缺口先补设计再实施；互有依赖的项不并行推进。
4. **落地纪律**：设计定稿并实施后，由对应 `docs/modules/` 模块文档反映落地实现。

## 3. 后续设计顺序（走序）

> 主设计域（norm-chain）为当前主线；各批次的内容/验收以详细文档章节为准。

| 序 | 设计任务 | 详细文档 | 前置依赖 | 完成标准 | 状态 |
|---|---|---|---|---|---|
| D0 | **用户意图拆解**：UserInputParser（结构化解析 + 意图分类 + `!`/`?` marker）+ 自我问判注入（三问 + 低置信结构化澄清：自动触发+可跳过、动态 1-4 候选、防重复）+ intent_analyze 判定平台（规则预分类 + 判定准则 + 模型兜底，输出五形态 + behaviorMode + 参数）+ 行为模式切换（四档 + `/mode` 命令 + guard 软提醒）+ 持续意图维护（语义失配检测 + GoalStaleSource + should_update_goal 准则 + `GoalAdjustEvent` 闭环：白名单+失败优先、丰富字段、P1 注入、去重消费、终态清空） | norm-chain-design.md §3.10 | 无（基座，被 D1–D5 依赖） | 解析/预分类/判定路由/行为模式/marker/失配提醒/结构化澄清/事件闭环 JVM 单测绿 + 问判注入生效 | 📝 |
| D1 | **B1 Agentic Workflow 基座**：step 前上下文纪律（8 Source 一次登记 + 预算裁剪 + 八源排序，含 D0 的问判/行为模式/GoalStale/GoalAdjustEvent 4 源）、六段式流水线 guard 链（ToolGuard + FileObservationGuard）、闭环核对、统一开关基础 | norm-chain-design.md §3.1 / §3.5 | D0（问判注入并入 step 前注入体系） | 注入/预算/排序/文件观察 JVM 单测绿 + assembleDebug | 📝 |
| D2 | **B1 思维链路 + 步骤结果汇总**：空转软收敛、推理预算、Trajectory 轨迹表（全工具定制提取）、用量卡片 | §3.7 / §3.8 | D1（收敛注入与轨迹消费走 workflow 循环） | 轨迹提取/聚合/用量一致性单测绿 | 📝 |
| D3 | **B1 分层规则纪律**：四级规则 + priority + 模块按需注入 + 摘要/正文两级（独立自研，不依赖 D4） | §3.9 | D1（注入预算体系） | 规则分层/优先级/按需注入单测绿 | 📝 |
| D4 | **B2 SOP**：SopAsset + SopRegistry + loadSop 工具 + 6 资产 + 全量摘要注入 | §3.2 | D1（注入体系） | 摘要注入/loadSop 取正文单测绿 | 📝 |
| D5 | **B3 Playbook + 子代理**：PlaybookExecutor + SubAgentRunner + PlaybookRunEntity + 4 工具 + spawn/fork 双 seed + 并行聚合 + 阶段内写串行化 + 产物清单幂等 | §3.3 / §3.6 | D1/D2/D4（注入/轨迹/摘要形态） | 剧本推进/审批/中断/恢复/失败重试/子代理隔离/写串行化/幂等单测绿 | 📝 |
| D6 | **B4 Spec**：pre-commit 提示扩展（配套性 + 状态行校验 + SOP 同步提示） | §3.4 | 无（仓库治理 .githooks） | 提示生效、无误报 | 📝 |
| D7 | **草案细化队列**：chunked-message → data-layer-refactor → network-layer-optimization → deepseek-harness 剩余借鉴（按需取舍） | 各草案文档 | 独立于主设计域 | 各草案细化后评审 | 📝 |

> 主设计域（D1–D6）完成后，D7 队列按业务优先级逐项细化。

## 4. 设计任务登记表（新增设计在此登记）

| 登记日期 | 设计项 | 类别 | 详细文档 | 目标批次/顺序 | 状态 |
|---|---|---|---|---|---|
| — | （示例） | — | — | — | — |

> 新设计任务先在此登记，再补详细设计文档；顺序由 §3 决定。

## 5. 执行纪律（对齐 AGENTS.md）

1. 设计文档统一放 `docs/plan-docs/`，命名 `<名称>-design.md`（全小写 snake_case），头部标注评审状态（📝 草案 / ✅ 已评审 / 已实施）；禁止散放根目录。
2. 设计定稿并实施后，由对应 `docs/modules/` 模块文档反映落地实现。
3. 落地实现遵循 AGENTS.md：编译型代码改动先 `./gradlew :app:assembleDebug`；push 前 `./gradlew :app:testReleaseUnitTest`；资产同步纪律（prompts / docs / strings.xml / 模块文档）。
4. 涉及数据库 schema 变更按迁移纪律执行；新表须在 `DataRegistry` 登记（备份自动覆盖）。
5. 每项完成后在本文档 §6 勾选并简述验证结果，并同步更新 [progress-tracker.md](./progress-tracker.md) 的任务状态与前后依赖；发现设计缺口的先补设计再实施，不跳级。

## 6. 完成记录

| 任务 | 状态 | 完成日期 | 验证结果 |
|---|---|---|---|
| D0 用户意图拆解 | ✅ 已实施 | 2026-08-25 | Parser 解析/意图分类/门控缓存/行为模式/marker/失配提醒/结构化澄清/事件闭环 JVM 单测 50 例全绿；`assembleDebug` + `testReleaseUnitTest`（343 例）全绿 |
| D1 Agentic Workflow 基座 | ✅ 已实施 | 2026-08-25 | 8 Source 一次登记 + 八源排序 + 预算裁剪（P0 永不裁）、ToolGuard 链 + FileObservationGuard（FS_NOT_OBSERVED/FS_STALE）、六段式契约对齐、闭环核对（prompts 补充 TOOL_TIMEOUT）、统一开关基础（NormFlowSettingsRepository + 设置页 UI）JVM 单测 15 例全绿；`assembleDebug` + `testReleaseUnitTest`（358 例）全绿 |
| D2 思维链路 + 步骤结果汇总 | ✅ 已实施 | 2026-08-26 | 空转软收敛（idleRounds 计数器 + 实质产出/产出性读清零 + 连续 6 轮强制收敛返回已做动作摘要）、推理预算（reasoning_budget 子开关 + reasoningEffortForRound 透传 provider + ReasoningDelta 流式呈现）、TrajectoryEntity + DAO + v2→v3 迁移 + DataRegistry 登记 + 全工具定制提取（readFile/writeFile/editFile/run_code/Bash）、用量卡片（usageCardEnabled 子开关 + TurnUsage 事件落库渲染，本回合增量 + 会话累计）、轨迹消费（buildActionSummary 已做动作摘要 / getTrajectory 审计回放）；`assembleDebug` + `testReleaseUnitTest` 全绿 |
| D3 分层规则纪律 | ✅ 已实施 | 2026-08-26 | 四级规则资产（RuleLayer/RuleAsset/RuleRegistry + RuleAssetCore 纯解析核心）、三级常驻注入 + 模块级按需注入（touchedModulePaths 从 ToolResultCache 文件观察路径命中 `/feature/<module>/` 推导）、摘要/正文两级（RulesSource 注入 + `/rules` 命令 + `load_rule` 工具）；`assembleDebug` + `testReleaseUnitTest` 全绿；模块文档 agent.md 同步 §3.14 |
| D4 SOP 标准作业 | ✅ 已实施 | 2026-08-26 | SopAsset + SopRegistry（SopAssetCore 纯解析核心复用 SkillParser frontmatter + mtime 热加载）+ ContainerInstaller.extractSop 释放；6 份资产（10-release/20-migration/30-asset-sync/40-git-commit/50-troubleshooting/60-ai-conduct，编号步骤「操作+判定+产出」头部注明权威源）；SopSource 摘要常驻注入（P1，八源第 7 位，sop_summary 子开关管控）+ loadSop 工具按需取正文（双判据边界指引入 prompts/70-skills-and-mcp）；spec-check.sh 第 3 段 SOP 同步提示（warning 不阻断）；JVM 单测（SopAssetCoreTest：frontmatter 解析/order 回退/whenToUse 回退/排序/热加载/结构化正文取回）全绿；`assembleDebug` + `testReleaseUnitTest` 全绿；模块文档 agent.md 同步 §3.16 |
| D6 Spec 规范驱动 | ✅ 已实施 | 2026-08-26 | `spec-check.sh` 独立预检（配套性触发：新增非 test 路径 .kt/新模块阻断提示补 design 文档，`git diff --cached` 只校验本次触碰；`*-design.md` 评审状态行存在+值合法校验：📝 草案/✅ 已评审/已实施，纯值不带括号注释/组合态，存量不迁移；SOP 同步提示：AGENTS.md ↔ sop/10-50、prompts 行为规则 ↔ sop/60-ai-conduct，warning 级不阻断）+ `.githooks/pre-commit` 调用 + `docs/plan-docs/README.md` 规范补充 + `prompts/40-approach.md` 追加「重大改动先出设计文档」+ feature-dev 剧本「设计文档」阶段联动（gates=approval）；无误报验证 5 用例通过（纯文档/test 路径 .kt 不误报；新增 main .kt/状态行非法正确阻断）；`assembleDebug` + `testReleaseUnitTest` 全绿 |
| D7 | ⬜ | — | — |

## 7. 变更记录

- （2026-08-25）创建设计主计划：盘点 19 份 plan-docs，登记主设计域（norm-chain B1–B4，含 3.6–3.9 新增）与草案队列，规定 D1–D7 后续设计顺序。
- （2026-08-25）新增 D0 用户意图拆解设计域（§3.10）：语法层 UserInputParser + 自我问判注入 + intent_analyze 判定平台（五形态路由）；置于 D1 前作为基座。
- （2026-08-25）深度审计修订：① D3 去掉对 D4 依赖（摘要/正文两级独立自研）；② §2 基座原则对齐 D0；③ D1 注入体系扩为 8 Source 八源排序（纳入 D0 四源，理解优先）。
- （2026-08-25）深度审计修订（成本与细节）：① intent_analyze Parser 门控 + 频控（task/command 才调、结果缓存；失配确认同 goal 每 10 轮 1 次）；② SOP/Skill 边界双判据（适用范围主判 + 步骤化辅助）；③ 空转收敛产出性读动作清零（避免误伤只读调研）；④ playbook 清单可见 + 精确匹配，未命中回退 plan/goal。
- （2026-08-25）登记进度追踪文档 [progress-tracker.md](./progress-tracker.md)：只记录任务进度与前后依赖，作为模型工作前的前后进度上下文；本文档头部关联、§5 纪律同步更新。
