# DeepSeek Harness 能力调研与借鉴评估

> 评审状态：📝 草案
> 关联模块：agent（workflow / tools / permission / workspace / terminal）
> 调研对象：https://github.com/deepseek-ai/deepseek-harness（MIT，2026-08 开源）

## 1. 背景与目标

### 1.1 背景

R-CodeCore 是 Android 端 AI 编程工具，已具备 Agent 循环、工具注册与权限、PRoot 容器 + 远程 SSH 执行、MCP 客户端/服务器、消息分库持久化、上下文压缩等能力。DeepSeek Harness（下称 DSH）是 DeepSeek 开源的 Agent 运行时框架，沉淀了大量 Agent 工程化护栏与能力抽象。

### 1.2 目标

深度阅读 DSH 源码与子系统文档，盘点其优势能力与架构，逐项评估能否借鉴到 R-CodeCore，输出分优先级的落地路线，避免"无中生有"式照搬。

### 1.3 借鉴原则

1. **从实际出发**：只借鉴对 Android 端真实有增量价值的机制，不引入运行时插件框架等重架构。
2. **护栏优先**：DSH 最值得吸收的是工具链路上的工程化护栏（输出契约、读前必改 + CAS、结构化超时、重复调用提醒），与我们已落地的流式归一化（`DeltaAccumulator`）同源同向。
3. **渐进落地**：按"纯增量 → 中等重构 → 能力扩展"分三批推进，每批独立可验证。

## 2. DSH 概览

- **定位**：`Model + Harness = Agent` 的 Agent 运行时框架，对标 OpenAI Codex / Claude Code。
- **实现**：Node.js / TypeScript，底层基于 Cordis 元框架（时空可组合性：插件卸载自动回收副作用、依赖运行时解析）。
- **预置模式**：标准（完整编码 Agent）/ 极简（仅两工具，供基准测试）/ PTC（模型写 TS 脚本把多步工具调用压缩为单次交互）/ 创造（AI 可检查修改自身运行时配置）。
- **兼容性**：40+ 模型提供商、内置 MCP 客户端、多层沙箱后端（bwrap / Landlock / Seatbelt / Windows ACL / E2B 远程沙箱）。

## 3. 优势能力与架构全景

| # | 能力 | 机制要点 |
|---|---|---|
| 1 | 工具执行流水线 | 六段式：`pre-execute`(允许/拒绝/询问门) → `guard`(单调策略) → `execute`(超时/重试/指标包装) → `post-execute`(可替换结果/附加上下文) → `finalizeContent` → `result`(只读观测)。强制 canonical output 输出契约，`presentCall/presentResult` 返回工具自有 UI 卡片 |
| 2 | 沙箱分层与权限 | `SandboxMode = read-only / workspace-write / danger-full-access` 三档；per-call 解析策略（会话覆盖 > 部署默认 > 显式覆盖），fail-closed；失败三分：沙箱拒绝(`denialSignatures`) / runner 故障(`runnerFailureRules`) / 命令失败 |
| 3 | 文件观察策略 | 强制"编辑前必须先读"（未读 → `FS_NOT_OBSERVED`）；观察后版本 CAS 守卫（`FS_STALE_VERSION`）防并发覆盖；观察态走 `fs/*` 事件门可整体插拔 |
| 4 | 护栏 guard | `timeout-policy`：工具声明 timeoutMs → wrapper 合作式超时 → 结构化 `TOOL_TIMEOUT`；`repeat-tool-reminder`：统计连续相同工具 + 相同 canonical 参数，阈值 3/5/8 注入 escalating advisory 提醒（不阻塞、不改写） |
| 5 | 事件溯源会话 + 投影 | session 为 append-only 事件日志，LLM 历史由日志推导；`session-projection` 纯函数 `apply(state,event)` 折叠出会话列表/标题/token/UI 视图；持久化带 flush checkpoint + crash recovery |
| 6 | 上下文管理 | `compaction` 事件化 + 锁（`start→summary→end`，崩溃孤儿锁可检测）；`tool-result-pruner` 确定性头/中/尾裁剪（Unicode code point 度量）保留 rich-block 顺序；`spill` 超大输出溢出为 preview+locator；`token-meter` 估算与重放 |
| 7 | Code Mode / run_code | 工具以类型化 SDK（TS/Python）暴露，模型写程序批量调用；`concurrency-safe` 调用并行池化（默认 10），`exclusive` 串行屏障；中间值不落日志 |
| 8 | Skill 系统 | `SKILL.md` + frontmatter（name/description/whenToUse/metadata/disable-model-invocation/user-invocable）；多 root 分级扫描 + 文件 watch 热更新；invocation 策略 fail-closed |
| 9 | 子代理体系 | `tool-subagent`（foreground/background/continuable）+ `tool-subagent-control`（send_message/interrupt_agent/list_agents）+ `tool-subagent-report`；深度限制、工具过滤、persona |
| 10 | Agent 循环 steer | `ReactLoopAgent` inbox 驱动（send/followup/steer/inject 写入 next-turn/next-step），`cancel` 清空+中止；kick/turn/step 三级驱动 + wake latch |
| 11 | 任务编排 | `goal`（持久化目标+生命周期+快照修订）、`plan`、`jobs`（后台任务 JobStart 契约）、`workflow`（模型写编排脚本）、`schedule`（定时） |
| 12 | LSP 集成 | `tool-lsp` 给模型四种语义导航（跳转定义/引用等） |
| 13 | 反馈与遥测 | message-feedback（点赞/踩进会话日志）+ session-telemetry |
| 14 | 会话回放测试 | 用录制 session.jsonl 做 e2e 回放回归（replay-round-trip）；文档由 `gen-cordis-catalog` 自动生成并 type-equiv 校验 |

## 4. 与 R-CodeCore 现状对比

**已有能力**（借鉴基线）：`StatefulAgentWorkflow`（Agent 循环）、`ToolRegistry` + `ToolPermissionManager`（工具与权限）、PRoot 容器 + 远程 SSH（执行沙箱）、MCP 客户端/服务器、assets/prompts 提示词资产、Room 消息分块落库、`ContextCompactor`（上下文压缩）、凭据加密、AES 备份。

**缺口 / 可补**：工具输出契约与执行流水线事件化、文件"读前必改 + 版本 CAS"、护栏体系（超时结构化 + 重复调用提醒）、子代理工具化、skill 元数据规范化、会话回放回归测试、goal/jobs 任务抽象、三档沙箱显式化。

## 5. 借鉴评估矩阵

### 5.1 🟢 强烈建议借鉴（高价值 / 低-中成本 / 契合度极高）

| DSH 能力 | 借鉴点 | 理由与落地路径 |
|---|---|---|
| 文件观察策略 | **最高优先** | 远程 SSH 场景多客户端并发改文件，CAS 守卫直接防覆盖；强制先读减少幻觉编辑。改动集中在 `FileTools.kt` 编辑类工具 + `RemoteSftpFileAccess`，纯增量 |
| 工具 canonical output + 流水线事件化 | 高 | 工具返回格式不统一；把 `ToolRegistry` 扩展为 `pre-execute(门)→guard→execute→post-execute→result` 事件链，并在 Compose 侧按工具类型渲染专用卡片（terminal/diff/search/read），渐进式重构 |
| guard 护栏 | 高 | 与已落地的 `DeltaAccumulator`（流式护栏）是同一护栏理念的下一环——循环级护栏。给 `ExecuteCommandTool` 加结构化超时（`TOOL_TIMEOUT`），加"连续相同调用提醒"注入下一轮，直击 token 浪费与死循环 |
| 会话回放回归测试 | 高 | 用录制会话（含流式 chunk）跑 `StatefulAgentWorkflow` 回归，验证 `DeltaAccumulator` 去重不破坏正常流；项目已有 testReleaseUnitTest 门禁，自然延伸 |

### 5.2 🟡 值得借鉴（中价值 / 中成本 / 择机做）

| DSH 能力 | 借鉴点 | 理由与落地路径 |
|---|---|---|
| 三档沙箱显式化 + 失败三分 | 中高 | 已有 PRoot 容器 + 权限引擎，把"只读/工作区可写/完全访问"三档显式化、per-call 解析，并把"沙箱拒绝 vs 沙箱不可用 vs 命令失败"分类，对移动端安全与排查价值大 |
| 子代理工具化 | 中高 | 复杂任务委派 + 可中断/可继续，对移动端长任务体验提升明显；工作量较大，先做 in-process 版 |
| Skill 元数据规范化 + 用户自定义热加载 | 中 | prompts 资产是打包死的；借鉴 SKILL.md frontmatter + 用户目录扫描热更新，让技能生态化 |
| compaction 事件化 + tool-result 裁剪 | 中 | 现有 `ContextCompactor` 是对整段压缩；借鉴"确定性头/中/尾裁剪超大工具结果"更精细，且压缩加锁防崩溃半截 |
| Agent 循环 steer/inject | 中 | 对话中动态注入方向/上下文的事件化机制 |
| goal 持久化 + jobs 后台任务抽象 | 中 | 移动端适合后台任务（长时间编译/测试）；agent 库已有 todo/checkpoint 表，可扩展 goal/jobs 语义 |

### 5.3 🔴 谨慎 / 不宜直接借鉴

| DSH 能力 | 结论 | 原因 |
|---|---|---|
| Cordis 热插拔"一切皆插件" | 不引入运行时，借鉴设计思维 | Android 进程内无此类 JVM 框架，热插拔收益低、工程成本极高；"能力 seam + 事件门"的分层思维可映射到 Hilt 模块边界 |
| LSP 集成 | 可选，低优先 | 容器内跑 LSP server 可行但链路重，非核心体验 |
| ACP / API Gateway | 已有替代 | 已走 MCP server 路线（手机当开发后端），不必再引入外部协议 |
| Code Mode / run_code 完整版 | 部分借鉴 | 已有 `ExecuteCommandTool`（shell 批量）；类型化 SDK 全量移植成本高，先做轻量版 |
| 多代理团队（agent-team） | 低优先 | experimental 阶段，需求不明确 |

## 6. 落地路线

### 6.1 第一批：低风险纯增量（建议尽快）

1. **fs 读前必改 + 版本 CAS**（`FileTools.kt` + `RemoteSftpFileAccess`）
2. **guard 重复调用提醒 + 结构化超时**（workflow 循环层）
3. **工具 canonical output 契约**（工具返回统一 schema）
4. **会话回放回归测试**（录制会话跑 workflow）

### 6.2 第二批：中等重构

1. **工具执行流水线事件化 + 工具专用 UI 卡片**（`ToolRegistry` + Compose）
2. **三档沙箱显式化 + 失败分类**（`ToolPermissionPolicyEngine` + `ExecuteCommandTool`）

### 6.3 第三批：能力扩展

1. 子代理工具化
2. skill 用户目录热加载
3. goal / jobs 抽象
4. compaction tool-result 裁剪

## 7. 核心判断

DSH 最值得 R-CodeCore 吸收的**不是"插件框架"这一层**（Android 端不适用），而是它沉淀在工具链路上的**工程化护栏**——canonical 输出契约、读前必改 + CAS、结构化超时、重复调用提醒、事件化压缩。这些与已落地的流式归一化（`DeltaAccumulator`）同源同向，是本项目最该补齐的短板。

## 8. 参考来源

- DSH 仓库：https://github.com/deepseek-ai/deepseek-harness
- 关键子系统文档：`docs/subsystems/{tools,sandbox,credentials,approval,skills,subagent,compaction,session,jobs,workflow}.md`
- 关键实现：`packages/core/tools/`、`packages/sandbox/sandbox-policy/`、`packages/fs/fs-observation-policy/`、`packages/guard/{timeout-policy,repeat-tool-reminder}/`、`packages/core/agent-loop/src/agent.ts`、`packages/skill/skill-filesystem/`、`packages/subagent/tool-subagent/`
