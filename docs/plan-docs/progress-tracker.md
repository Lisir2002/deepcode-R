# R-CodeCore 任务进度追踪（Progress Tracker）

> 定位：全项目设计/实施任务的**进度单一事实源**。本文档**只记录任务进度与前后依赖**，不重复详细设计内容（详细设计见 `design-master-plan-design.md` 与各设计文档）。
> 用法：模型在设计或编程前，**先读本文档掌握前后任务进度**——当前任务的前置是否完成、后置是谁、处于哪个阶段，避免重复或跳级。
> 状态标记：`✅ 已实施` / `🔄 实施中` / `📝 已设计待实施` / `⬜ 待设计（草案）`；子任务状态 `⬜ 未开始 / 🟡 进行中 / ✅ 完成`。

## 1. 当前主线

- **主设计域（D0–D6）设计已全部收敛**（含深度审计 13 项修订），任务已细化到**子任务级**（共 41 项，见 §2）。
- 当前整体处于 ✅ 实施完成，**D0（基座）、D1（Agentic Workflow 基座）、D2（思维链路+步骤结果汇总）、D3（分层规则纪律）、D4（SOP 标准作业）、D5（Playbook + 子代理）与 D6（Spec 规范驱动）已实施完成**，主设计域 D0–D6 全部落地。
- 实施顺序：`D0 → D1 → D2 → D3 → D4 → D5 → D6`（D3 与 D4 互不依赖；D5 依赖 D2/D4 完成）。

## 2. 主设计域进度（子任务级）

### D0 用户意图拆解（✅ 已实施 | 前置：无（基座） | 后置：D1）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D0-1 | `UserInputParser`：结构化解析（command?/args/text）+ 意图分类（task/query/file/command/unknown）+ `!`/`?` marker 解析（FORCE/CONSULT/NONE） | §3.10 | ✅ |
| D0-2 | 斜杠命令改**前缀 + 参数匹配**（`/playbook start` 传参，复用 SlashCommandRegistry） | §3.10 | ✅ |
| D0-3 | `IntentAskSource`：意图问判三问注入（step 前 Source，P1，纳入 3.1.2 八源排序） | §3.10 / §3.1 | ✅ |
| D0-4 | 低置信**结构化澄清**：AskUserQuestion 选项化（自动触发+可跳过、动态 1-4 候选、已澄清防重复标记） | §3.10 | ✅ |
| D0-5 | `IntentAnalyzeTool` 判定平台：规则预分类 + 判定准则 + 模型兜底，输出五形态 + behaviorMode + 参数；**Parser 门控 + 频控**（task/command 才调、结果缓存） | §3.10 | ✅ |
| D0-6 | 行为模式切换：四档 behaviorMode + `BehaviorModeSource`（P1）+ guard 软提醒 + `/mode` 命令 + **plan 形态强制 design** | §3.10 | ✅ |
| D0-7 | 持续意图维护闭环：语义失配检测（规则疑似 + LLM 确认，同 goal 每 10 轮 ≤1 次）+ `GoalStaleSource`（P2）+ `should_update_goal` 准则 + `GoalAdjustEvent`（白名单+失败优先、丰富字段、step 前 P1 注入、去重消费、终态/切换清空） | §3.10 | ✅ |
| D0-8 | prompts/ 资产同步：五形态判定准则 + should_update_goal 准则 + 行为模式纪律 + `!`/`?` 说明 | §3.10 | ✅ |

### D1 Agentic Workflow 基座（✅ 已实施 | 前置：D0 | 后置：D2, D3, D4）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D1-1 | PromptSource 统一注入：8 Source 一次登记（含 D0 的问判/行为模式/GoalStale/GoalAdjustEvent 4 源）走 `SystemPromptProvider` 聚合链 | §3.1.2 | ✅ |
| D1-2 | 注入预算裁剪：预算常量 + importance 降级裁剪 + **八源排序（理解优先）**（P0 永不裁） | §3.1.2 | ✅ |
| D1-3 | `ToolGuard` 接口（PASS/BLOCK/ADVISORY）+ 链式注册，挂入六段式 guard 段 | §3.1.3 | ✅ |
| D1-4 | `FileObservationGuard`：编辑前必须先读（否则 FS_NOT_OBSERVED）+ mtime 版本 CAS 防并发覆盖 | §3.1.3 | ✅ |
| D1-5 | 六段式契约对齐：pre-execute → guard → execute → post-execute → finalizeContent → result 写进文档/注释（非破坏性重构） | §3.1.3 | ✅ |
| D1-6 | 闭环核对（3.1.1）：goal 注入 / 循环提醒 / TOOL_TIMEOUT 统一（既有能力核对接入完整性） | §3.1.1 | ✅ |
| D1-7 | 统一开关基础（3.5）：单轮闭环三项 + 收敛/轨迹消费的可控开关 | §3.5 | ✅ |

### D2 思维链路 + 步骤结果汇总（✅ 已实施 | 前置：D1 | 后置：D5）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D2-1 | 空转软收敛：空转计数器（实质产出清零 + **产出性读动作清零**）+ 连续 N 轮（默认 6）强制结束回合 | §3.7 | ✅ |
| D2-2 | 推理预算：流式呈现 + 预算消耗追踪 | §3.7 | ✅ |
| D2-3 | `TrajectoryEntity` 轨迹表 + DAO（append-only）+ 全工具定制提取 | §3.8 | ✅ |
| D2-4 | 用量卡片：本回合增量（输入/输出/总 token、耗时、工具数）+ 一行会话累计 | §3.8 | ✅ |
| D2-5 | 轨迹消费：已做动作摘要 / 阶段总结 / 审计（append-only 单一数据源） | §3.8 | ✅ |

### D3 分层规则纪律（✅ 已实施 | 前置：D1 | 后置：—）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D3-1 | 四级规则资产：全局/项目/工作区/模块 + 显式 priority + 拼接合并 | §3.9 | ✅ |
| D3-2 | 三级常驻注入（带预算裁剪）+ 模块级按需注入（文件观察命中路径判断） | §3.9 | ✅ |
| D3-3 | 摘要/正文两级（**独立自研，不依赖 D4**）+ `/rules` 命令 / `load_rule` 工具取正文 | §3.9 | ✅ |

### D4 SOP 标准作业（✅ 已实施 | 前置：D1 | 后置：D5）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D4-1 | `SopAsset` + `SopRegistry`（复用 AgentAssetCore frontmatter 解析 + 热加载，扫 `assets/sop/`）+ `ContainerInstaller.extractSop` 释放 | §3.2 | ✅ |
| D4-2 | 6 份 SOP 资产：10-release / 20-migration / 30-asset-sync / 40-git-commit / 50-troubleshooting / 60-ai-conduct（编号步骤：操作 + 判定 + 产出/出错处理，头部注明权威源） | §3.2 | ✅ |
| D4-3 | 摘要常驻注入 Source（importance P1，走预算裁剪，八源第 7 位）+ `sop_summary` 子开关 + 双权威源头注（AGENTS.md / prompts） | §3.2 | ✅ |
| D4-4 | `loadSop` 工具：按需取正文 + SOP/Skill **双判据边界**（适用范围主判 + 步骤化辅助）+ prompts 区分指引（70-skills-and-mcp） | §3.2 | ✅ |
| D4-5 | 权威源同步提示：改 AGENTS.md / prompts 行为规则 → pre-commit 提示检查 sop 同步（spec-check.sh 第 3 段，warning 级不阻断） | §3.2 | ✅ |

### D5 Playbook + 子代理（✅ 已实施 | 前置：D1, D2, D4 | 后置：—）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D5-1 | `PlaybookRegistry`（独立，扫 `assets/playbooks/`）+ PlaybookAsset frontmatter 解析（stages/agents/sop/gates/guards） | §3.3 | ✅ |
| D5-2 | 3 份 playbook 资产（多阶段任务剧本） | §3.3 | ✅ |
| D5-3 | `PlaybookExecutor` + `PlaybookRunEntity`（双状态机：运行级 RUNNING/COMPLETED/ABORTED/INTERRUPTED + 阶段级 PENDING/ACTIVE/DONE/FAILED） | §3.3 | ✅ |
| D5-4 | 4 工具 + 命令：`playbook_start/advance/status/abort` + `/playbook <name>`；**清单可见 + 精确匹配**，未命中回退 plan/goal | §3.3 | ✅ |
| D5-5 | `PlaybookStageSource`（P1 注入）+ 运行期**挂起 goal 维护双信号**（GoalStale/GoalAdjustEvent 不注入） | §3.3 | ✅ |
| D5-6 | `SubAgentRunner`：spawn/fork 双 seed + 上下文隔离（独立消息/工具/工作目录）+ **三档 SandboxMode 权限**降权 | §3.6 | ✅ |
| D5-7 | 并行聚合（子代理结果合并）+ 阶段内写串行化 | §3.6 | ✅ |
| D5-8 | 产物清单幂等 + 完成判定护栏（连续无实质动作声明完成 → advisory）+ 中断/恢复（INTERRUPTED + resume/retry） | §3.3 | ✅ |
| D5-9 | `!` 标记跳过 approval gate（统一流程级确认，不绕权限系统） | §3.3 | ✅ |
| D5-pa | `playbook_auto` 子开关（设置页 + NormFlowSettingsRepository 持久化）：关闭后模型不能自主 `playbook_start`，`/playbook` 命令与总开关不受影响 | §3.5 | ✅ |

### D6 Spec 规范驱动（✅ 已实施 | 前置：无（独立治理） | 后置：—）

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| D6-1 | spec-check.sh 配套性触发：新模块 + 新增 .kt 判定（只校验本次触碰，`git diff --cached` 扫描，test/androidTest 路径豁免） | §3.4 | ✅ |
| D6-2 | 评审状态行校验（`> 评审状态：` 存在 + 值合法：📝 草案 / ✅ 已评审 / 已实施，纯值不带括号注释/组合态；只校验触碰的 *-design.md，存量不迁移）+ `✅ 已评审` 时 `> 评审结论：` 行建议必填（缺失 warning 不阻断，两字段） | §3.4 | ✅ |
| D6-3 | SOP 同步提示：精确映射表（AGENTS.md ↔ sop/10-50、prompts 行为规则 ↔ sop/60-ai-conduct） | §3.4 / §3.2 | ✅ |
| D6-4 | githooks 接入（pre-commit 调用 spec-check.sh，阻断 + --no-verify 逃生口）+ 无误报验证（纯文档/test 路径 .kt 不误报；新增 main .kt / 状态行非法正确阻断） | §3.4 | ✅ |

## 3. 草案队列（D7，待细化，⬜）

| 任务 | 内容摘要（一行） | 前置 | 状态 |
|---|---|---|---|
| D7-1 chunked-message | 长输出分块消息方案细化（细化时再拆子任务） | 独立 | ⬜ |
| D7-2 data-layer-refactor | 数据层重构方案细化 | 独立 | ✅ 已实施（V1 拆库已落地，见 §4） |
| D8 V2 全面接管（v2-full-takeover） | V2 SQLDelight 接管全部业务读写并彻底剔除旧 Room 层，P0–P5 六阶段 | D7-2（V1 拆库） | 🔄 实施中 |

### D8 V2 全面接管与旧层剔除（🔄 实施中 | 前置：D7-2 | 后置：—）

> 详细设计与缺口实测见 [v2-full-takeover-design.md](./v2-full-takeover-design.md)。状态标记同 §2 用法。

| 子任务 | 内容（一行） | 章节 | 状态 |
|---|---|---|---|
| P0-1 | V2 五个 Repository 补 `Flow` 响应式读（对齐旧 DAO 34 个 Flow 查询） | 设计 §4 P0 | 🟡 |
| P0-2 | 各 `.sq` 补 update / 列级 setter / `INSERT OR REPLACE`，排序键与 `LIMIT 1` 对齐旧层 | 设计 §4 P0 | 🟡 |
| P0-3 | 修 `MigrationEngine.restoreSnapshot` 拷贝方向反转缺陷 + 快照回滚单测 | 设计 §2.4 | 🟡 |
| P0-4 | 设计文档入库 + 进度登记（本表） | 设计 §4 P0 | ✅ |
| P1-1 | 切换前强制全量备份钩子（失败阻断 V2 读） | 设计 §4 P1 | ✅ |
| P1-2 | `DataReadMode` 读源开关（ROOM / V2，可一键回退） | 设计 §4 P1 | ✅ |
| P1-3 | `V2ParityChecker` 逐表行数 + 抽样哈希校验，不一致自动回落 ROOM | 设计 §4 P1 | ✅ |
| P1-4 | `DataRegistryModule` 双登记保持（过渡期资产，非清理项） | 设计 §4 P1 | ✅ |
| P2-1 | 批 1 单表外围切换（settings / credentials / t2i / remote_audit_logs） | 设计 §4 P2 | 🟡 |
| P2-2 | 批 2 多表 Repository + Zth 域切换 | 设计 §4 P2 | 🟡 |
| P2-3 | 批 3 会话/消息热表切换（最高风险） | 设计 §4 P2 | ⬜ |
| P2-4 | 批 4 事务服务 + 跨域写 + 3 处 ViewModel 直注收敛 | 设计 §4 P2 | ⬜ |
| P2-5 | 收口：`DataLayerModule` 唯一 DI 入口 + `DatabaseModule` 标 Deprecated + 灰度 | 设计 §4 P2 | ⬜ |
| P3-1..5 | 剔除旧层：注册表旧条目 → DAO/entity → 域库/DatabaseModule → Room 依赖 → Legacy 迁移链 | 设计 §4 P3 | ⬜ |
| P4 | 旧库物理文件留底 7 天到期删除 | 设计 §4 P4 | ⬜ |
| P5 | 文档与纪律同步（模块文档 / AGENTS.md / README / CHANGELOG / sop） | 设计 §4 P5 | ⬜ |
| D7-3 network-layer-optimization | 网络层性能优化方案细化 | 独立 | ⬜ |
| D7-4 deepseek-harness 剩余借鉴 | DSH 剩余能力按需取舍后纳入 | 主设计域完成 | ⬜ |

## 4. 已实施任务（前置进度，✅）

| 设计项 | 说明 |
|---|---|
| 流式增量语义归一化 | 预防 base64 重复显示类 bug |
| 对话列表布局重构 + 删除/重命名检修 + 主题色 | 布局、左滑删除/重命名、主题色适配 |
| 输入栏编排入口重构 | ChatInputField/Toolbar/Panels |
| Skill 管理 / Skill 协作 | 技能中心 + 作用域 + 工具绑定 |
| pre-commit 健康检查 / 编码预检 | 首款内置技能 + 预检组合 |
| 数据保全 | DataRegistry 备份/恢复单一事实源 |
| 虚拟环境支持 | 模拟器/虚拟机 |
| 内置 MCP 服务器 | 客户端 + 服务器双角色 |
| 命令防死循环 | 防死循环 + BusyBox 护栏 |
| Claude Code 借鉴 R01–R07 | Hook/WakeQueue/Agent 资产/危险命令守卫 |
| Agent 任务编排扩展（运行时） | goal/plan/jobs/schedule 已落地（文档草案） |

## 5. 前后依赖链（子任务级速览）

```
D0（基座，8 子任务）
 → D1（注入+guard 基座，7 子任务）
     ├→ D2（思维链路+轨迹，5 子任务） ──┐
     ├→ D3（分层规则，3 子任务）独立     ├→ D5（Playbook+子代理，9 子任务）
     └→ D4（SOP，5 子任务）──────────────┘
D6（Spec 治理，4 子任务，独立）
D7 草案队列（细化时拆子任务）
```

## 6. 推进规则

1. **只记录进度**：本文档不写详细设计（见各设计文档），只维护子任务状态、前后依赖、归属章节。
2. **每完成一个子任务**：更新本文档状态（⬜ → ✅）+ 在 `design-master-plan-design.md` §6 完成记录勾选对应主任务（全部子任务完成才勾主任务）。
3. **新设计任务**：先在 `design-master-plan-design.md` §4 登记，再补充本文档子任务行。
4. **跳级禁止**：前置未完成（⬜）不启动后置任务；主任务内子任务按编号顺序推进。
5. **模型工作前**：先读本文档 §2/§3/§4 定位前后进度与当前子任务，再读对应设计文档章节。
