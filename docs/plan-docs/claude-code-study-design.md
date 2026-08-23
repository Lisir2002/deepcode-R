# Claude Code 调研借鉴 — Design

> 📝 草案
>
> 关联模块：`feature/agent`（工具系统 / 权限治理 / MCP）、`feature/terminal`、`feature/git`、`feature/settings`
> 触发场景：以 anthropics/claude-code 公开仓库为范本，系统性挖掘对 R-CodeCore 可复用的产品/架构设计，形成分步落地方案。
> 调研源：`/workspace/_research/claude-code/`（已克隆，深读 README / CHANGELOG / plugins / examples）

## 1. 背景与目的

R-CodeCore 是运行在 Android 真机/虚拟环境上的 AI 编程工具，已具备：Agent 多 Provider、工具系统（`ToolRegistry` + File/Shell/MCP 工具）、权限治理（`ToolPermissionManager` / `ToolPermissionPolicyEngine`）、内置 MCP 客户端+服务器、PRoot 容器终端、git 集成。

Claude Code 是业界 agentic coding 工具的标杆，其公开仓库（官方设计范式库，非 CLI 闭源码）覆盖：插件五件套、子代理声明式定义、Hook 事件模型、声明式规则引擎、权限/Sandbox 模型、MDM 企业管控、Gateway 部署。本文档沉淀调研结论，并把可借鉴点映射到 R-CodeCore 现有模块，作为后续分步实施与逐步讨论的基础。

## 2. Claude Code 公开仓库调研结论

### 2.1 仓库性质澄清

- 该公开仓库**不包含 CLI 本体源码**（`@anthropic-ai/claude-code` 是闭源 npm 包）。
- 价值集中在**官方示例与设计范式**：`plugins/`（插件五件套）、`examples/hooks|settings|mdm|gateway/`、`CHANGELOG.md`（产品演进暴露的设计）。
- 调研结论以「范式」为单位沉淀，落地到 R-CodeCore 时按本项目架构裁剪，不做机械照搬。

### 2.2 插件五件套结构（plugins/README.md）

```
plugin/
├── .claude-plugin/plugin.json   # 插件元数据（marketplace 分发）
├── commands/    # 斜杠命令（= 小型工作流编排器）
├── agents/      # 专项子代理（声明式 Markdown）
├── skills/      # Agent Skills（可自动触发）
├── hooks/       # 事件处理器（PreToolUse 等）
└── .mcp.json    # 外部工具配置
```

### 2.3 子代理声明式定义（agents/*.md，以 code-reviewer 为代表）

- **frontmatter**：`name` / `description`（含触发场景）/ `tools`（工具白名单）/ `model` / `color`。
- **正文**：角色设定 → Review Scope → 核心职责 → Confidence Scoring → 输出格式。
- 亮点设计：**Confidence Scoring（0–100，只报 ≥80 的问题）**——用置信度主动过滤误报，是审查质量的关键；每个 agent 只做一个专项，工具白名单严格限定（Glob/Grep/LS/Read/WebFetch/TodoWrite/KillShell/BashOutput）。

### 2.4 命令 = 多 Agent 编排器（review-pr.md / feature-dev.md）

- frontmatter：`description` / `argument-hint` / `allowed-tools`。
- 一个命令即一个小型编排器：确定范围 → 识别变更（git diff）→ **按变更类型动态决定派哪些 agent** → 串行/并行调度 → 聚合为 Critical/Important/Suggestions 分级输出 → 行动方案。
- feature-dev 使用 7 阶段流程（Discovery/Exploration/Clarifying/Architecture/Implementation/Review/Summary）。

### 2.5 Hook 事件模型（hookify / security-guidance hooks.json）

- 事件：`PreToolUse / PostToolUse / UserPromptSubmit / Stop / SessionStart / SessionEnd / Setup / DirectoryAdded / PreCompact / PostCompact`。
- **matcher 精确控制**：`"if": "Bash(git commit:*)"` 只对 git 提交生效。
- **asyncRewake 后台审查**：安全审查后台跑，发现问题才用 `rewakeMessage` 唤醒 Agent 插入对话——审查不阻塞主流程。
- **PreToolUse 拦截协议**：hook 读 stdin JSON（tool_name/tool_input），`exit 0` 放行 / `1` 提示 / `2` 拦截并把 stderr 喂给模型。

### 2.6 声明式规则引擎（hookify core/rule_engine.py）

- `Condition`（字段 + 运算符）+ `Rule`（事件 + 条件 + action + tool_matcher + 消息）。
- 运算符：`regex_match / contains / equals / not_contains / starts_with / ends_with`。
- 规则存 `.claude/hookify.*.local.md` 的 YAML frontmatter，**用户可写**。
- 区分 blocking/warning，返回 `permissionDecision: "deny"` + `systemMessage`。

### 2.7 Bash 命令静态预校验（bash_command_validator_example.py）

- PreToolUse + Bash matcher；正则规则表（`^grep\b`、`find -name`）在执行前拦截并提示改用 rg；exit 2 拦截。

### 2.8 权限与 Sandbox 模型（settings-strict.json 等）

- `ask / deny`（按工具名）、`disableBypassPermissionsMode`（禁绕过）。
- `allowManagedPermissionRulesOnly / allowManagedHooksOnly`（只认托管规则，企业锁）。
- `strictKnownMarketplaces`（只信已知插件市场）。
- `sandbox`：`autoAllowBashIfSandboxed`、`excludedCommands`、`network.allowedDomains / unixSockets / proxy`。

### 2.9 企业管控（MDM / Gateway）

- `managed-settings.json` 企业强制策略，经 macOS `mobileconfig` / Windows `ADMX` 系统策略通道下发，**最高优先级不可覆盖**。
- Gateway：代理后真实 IP、OIDC 身份、多 upstream 模型路由（failover）、按 group/email_domain 的 RBAC 权限下发。

### 2.10 CHANGELOG 暴露的产品级设计

- **Subagent forking**：`subagent_type:"fork"` 继承完整对话与 prompt cache。
- **background tasks**：后台任务用 `<system-reminder>` 交付结果；`/goal` 长任务 check-in。
- **worktree isolation**（隔离 git/Bash 操作）、**credential masking**（凭证掩码）。
- **MCP elicitation/forms**：工具参数的交互式补全。
- **资源控制**：嵌套 subagent 默认关、并发上限、`--max-budget-usd` 阻止后台 subagent、subagent 结果后释放内存。

## 3. 对 R-CodeCore 的可借鉴映射矩阵

| # | 借鉴点 | 落地到 R-CodeCore 现有模块 | 改动面 | 收益 |
|---|--------|--------------------------|--------|------|
| 1 | Agent 声明式定义（frontmatter + 正文） | `feature/agent` 提示词资产：把 `assets/prompts/` 硬编码提示词升级为「元数据 + 正文」的 agent 定义，可热加载/复用 | 中 | 高（体系升级） |
| 2 | 多 Agent 编排工作流 | `feature/agent`：新增「代码审查 / 功能开发」多阶段 skill/命令，按变更动态派专项 agent | 大 | 高 |
| 3 | Confidence Scoring 过滤误报 | `feature/agent`：审查/修复建议按 0–100 置信度分级上报，只报高置信问题 | 小 | 高 |
| 4 | Hook 事件模型（PreToolUse 拦截 + asyncRewake） | `feature/agent` 权限治理：`ToolPermissionManager` 扩展「工具执行前后事件钩子 + 后台安全审查唤醒」 | 中 | 高 |
| 5 | 声明式规则引擎 | `feature/agent` `ToolPermissionPolicyEngine`：升级为用户可配置规则（条件/运算符/动作，存 Markdown frontmatter） | 中 | 高 |
| 6 | Bash 命令静态预校验 | `feature/agent` `ExecuteCommandTool`：执行前加危险命令正则拦截表（`rm -rf /`、`curl \| sh` 等） | 小 | 高 |
| 7 | 权限分级 ask/deny + 禁绕过 | `feature/settings`：加 deny 白名单策略文件与「禁绕过」开关 | 小 | 中 |
| 8 | Sandbox 网络限制 | `feature/terminal` 容器网络：allowedDomains / proxy 限制 | 中 | 中 |
| 9 | 子代理 fork 继承上下文 + 并发/预算上限 | `feature/agent`：若引入 subagent，需带资源控制与上下文继承 | 大 | 中 |
| 10 | 后台任务 + system-reminder 唤醒 | `feature/terminal` / MCP server：耗时任务「后台跑 + 结果唤醒」 | 中 | 中 |

## 4. 候选方向与深入讨论清单

全部 10 个方向均进入深入讨论范围（用户 2026-08-23 确认「都值得深入讨论」）。
以下「批次」仅表示**讨论/实施顺序**，不表示取舍——每个方向都会深入讨论并产出设计。

- **批次 1**：#6 Bash 命令静态预校验（设计定稿 + 深化，见第 7 节；Block 守恒 4 条 + Warn 扩容 11 条）
- **批次 2**：#3 Confidence Scoring 分级上报（设计定稿，见第 10 节；随 #1/#2 实施）
- **批次 3**：#4 Hook 事件模型（设计定稿，见第 11 节；代码后声明）→ #5 声明式规则引擎（设计定稿，见第 12 节；MD+JSON 共存、引入 warn、权限+Hook 双消费点）
- **批次 4**：#1 Agent 声明式定义（设计定稿，见第 8 节；用户 2026-08-23 提前拍板）→ #2 多 Agent 编排（设计定稿，见第 9 节；依赖 #1 落地）
- **批次 5**：#7 权限分级（设计定稿，见第 13 节；禁 AUTO+记忆、独立 deny 文件）/ #8 Sandbox 网络限制（设计定稿，见第 14 节；L1 HTTP 白名单基于 mihomo）/ #10 后台任务 + system-reminder 唤醒（设计定稿，见第 16 节；统一 WakeQueue）
- **批次 6**：#9 子代理 fork（设计定稿，见第 15 节；独立会话落库、继承环境+简报、并发/预算用户可配置、骨架先行）
- **延伸批次 7**：插件五件套复用（设计定稿，见第 17 节；资产容器化 + hooks→#5 规则 + Zth 审查 + 预留市场）

## 5. 决策记录（逐步讨论结论）

### 5.1 整体优先级（2026-08-23 已确认）

实施顺序定为 **A → B → C**：

- **阶段 A（快速见效）**：#6 Bash 命令静态预校验 → #3 Confidence Scoring 分级上报。
- **阶段 B（体系升级，一个专题）**：#4+#5 Hook 事件模型 + 声明式规则引擎（强耦合，成对做）。
- **阶段 C（按需/专题）**：#1/#2 Agent 声明式定义 + 多 Agent 编排；#7/#8/#10 按需推进。

### 5.2 方向 #6 设计决策（2026-08-23 已确认）

- **Block（硬拦截）覆盖**：RCE 管道（curl/wget|sh/bash）、系统目录权限破坏（chmod/chown 到系统根目录）、设备/磁盘破坏（dd/mkfs/fdisk 写 /dev、覆盖关键文件）、关机/杀进程（shutdown/reboot/halt、kill -9 -1、无目标 pkill -9）。
- **规则配置**：本阶段内置表、不可用户自定义；后续 #5 规则引擎落地时再开放加严（用户只能加严不能放宽，安全优先）。
- **判定引擎**：复用 `ShellCommandParser` 段级 token 判定（非纯正则），降低误报。

### 5.3 方向 #1 设计决策（2026-08-23 已确认）

- **对象粒度**：完整 agent 化——多 agent 定义系统（主 agent + 可触发专项 agent），非仅升级主 agent 提示词。
- **模式片段**：80-plan-mode.md / 81-auto-mode.md 统一进声明式资产（`mode` 字段），删除 `PlanModeSource`/`AutoModeSource` 两个特殊 Source。
- **热加载**：mtime 懒刷新 + FileObserver 监听**双机制并行**（优劣兼具，mtime 兜底）。
- **组合复用**：支持 `includes: [other-asset]` 引用机制。
- **触发方式**：斜杠命令（复用现有 `SlashCommandRegistry`）+ 会话内切换。
- **tools 白名单语义**：**仅建议**（不硬拦截，权限引擎仍管审批）——与 Claude Code 硬限制不同，避免与现有权限体系重复设墙。
- **model 字段语义**：**仅提示**（实际仍用用户选定 provider/model，不跨 provider 强切）。
- **#1/#2 边界**：#1 只做「定义/加载/触发/切换」基建；多阶段编排、按变更类型动态派发留给 #2。

### 5.4 方向 #2 设计决策（2026-08-23 已确认）

- **执行机制（混合 C）**：先做「多阶段流程 + 按变更类型动态分支」（单 agent 顺序执行，阶段正文由声明式资产承载）；物理 subagent 派发等 #9 落地后再接入。
- **变更类型识别**：自动识别——git diff 识别变更文件，按路径/后缀分类（test/注释/错误处理/新类型/通用）自动选专项审查，对齐 Claude Code review-pr。
- **落地形态**：**声明式资产**（工作流命令 + 专项 agent 均做成 #1 资产体系，非代码级命令）→ 依赖 #1 先落地。
- **输出聚合**：Critical/Important/Suggestions 分级 + 行动方案，与 #3 Confidence Scoring 衔接。
- **依赖**：#2 依赖 #1（资产承载）；专项 agent 物理派发依赖 #9（远期）。

### 5.5 方向 #3 设计决策（2026-08-23 已确认）

- **约束方式**：纯 prompt 纪律（非代码机制）——五档评分锚点 + 只报 ≥80，写在专项 agent 正文。
- **评分粒度**：五档锚点 0/25/50/75/100（每档配描述，对齐 Claude Code code-reviewer）。
- **低置信处置**：直接过滤（只报 ≥80，质量优先）。
- **落地时机**：随 #1/#2 实施（作为 code-reviewer 等专项 agent 的正文纪律）。
- **角色区分**：与 ZthToolOutputGuard 确定性置信（防幻觉）不合并——#3 是生成式置信（防误报）。

### 5.6 方向 #4 设计决策（2026-08-23 已确认）

- **落地形态**：**代码后声明**——先代码级 Hook 分发骨架（multibinding 同 SlashCommandHandler 模式）跑通，后续 #5 把条件/动作声明式化。
- **事件范围**：**完整事件集**——PreToolUse / PostToolUse / UserPromptSubmit / Stop / SessionStart。
- **asyncRewake 表达**：**下轮注入**——Stop 后台审查结果**持久化**（防会话结束/App 被杀丢失），下一轮开始前注入为 system-reminder，注入后**消费确认**（防重复/防丢失，确保注入成功且有效）。
- **与 #5 分工**：**骨架 + 规则分离**——#4 事件分发骨架代码级；#5 把条件/动作声明式化。
- **对齐 AGENTS.md 纪律**：hook 承载纪律检查（提交前检查、危险命令、规则合规等）。

### 5.7 方向 #5 设计决策（2026-08-23 已确认）

- **规则载体**：**MD + JSON 共存**——新增 MD 规则文件层（项目 `.rcodecore/rules/*.md` + 全局，frontmatter + 正文）；现有 JSON（`permissions.json`）保留为「记忆授权」产物，职责分离。
- **动作扩展**：**引入 warn**（allow/deny/warn 三态）——warn 不拦截只提示（正文喂模型/弹窗附注），对齐 hookify + #6 Warn。
- **作用域**：**权限 + Hook 全上**——统一规则引擎双消费点（ToolPermissionPolicyEngine + #4 HookDispatcher），实施可分层（先权限后 Hook）。
- **优先级**：内置安全底线（灾难 rm / #6 Block）> 用户 deny/block > 已记忆 ALLOW > 内置白名单 > warn 提示 > ASK；用户规则只增不减（沿用 #6 原则）。

### 5.8 方向 #6 深化决策（2026-08-23 已确认）

- **清单范围**：**Block 守恒 + Warn 扩容**——Block 保持 4 条核心（误报可控优先），Warn 扩容吸收 ZthContentReviewer 规则池。
- **分级边界**：**严格分级**——Block 只收「必然灾难 + 误报可控」；其余一律 Warn（提示不拦截，误报成本低）。
- **协同**：**分层 + 联动**——内置表固定（安全底线）+ 用户规则只能加严；危险命令审查可作 PostToolUse hook（如 git commit 提交前检查）。
- **反向 shell**：归 **Warn + 提示**（提示语注明「若为合法网络调试可忽略」），不入 Block。
- **收编范围**：**高优先六条**（W5 sudo 敏感 / W6 敏感文件读取 / W7 SSH 密钥覆盖 / W8 base64 解码执行 / W9 明文密码入脚本 / W10 git force push）+ 反向 shell（W11）。
- **去重**：**统一合并**——Warn 提示块统一合并（#6 + BusyBox + 多条 Warn 一条命令一次展示），避免重复弹窗/重复提示。

### 5.9 方向 #7 + #8 设计决策（2026-08-23 已确认）

**#7 权限分级 + 禁绕过**
- **禁绕过范围**：**禁 AUTO + 禁新记忆「始终允许」**（全封闭，最接近 Claude Code `disableBypassPermissionsMode`）。
- **deny 白名单**：**独立文件**（管理级强制 deny 策略，区别于 #5 用户可配规则）。

**#8 Sandbox 网络限制**
- **层级**：**L1 仅 HTTP 白名单**（allowedDomains → mihomo 规则，白名单放行 + 其余 REJECT），基于已有 mihomo 代理内核。
- **配置**：**两级（全局 + 项目级）+ 默认关**（启用即白名单）。

### 5.10 方向 #9 设计决策（2026-08-23 已确认）

- **subagent 形态**：**独立会话落库**——复用现有 ChatSession + StatefulAgentWorkflow 实例，type 标记 subagent，可持久化、可追溯、结果可恢复。
- **上下文继承**：**继承环境 + 简报**——继承 workspace/skills/权限/MCP 环境 + 主 agent 生成的任务简报，**不继承对话历史**（token 成本低，符合 Android 资源现实）。
- **并发/预算上限**：**用户可配置**（settings 项：最大并发数 + 单 subagent 轮数上限 + API 次数上限 + 超时；默认并发 1 + 硬上限）。
- **落地依赖**：**骨架先行**——独立实现 fork 工具 + 预算控制骨架，agent_type 动态化后续接入（#1 落地后启用 agent_type 专用 subagent）。

### 5.11 方向 #10 设计决策（2026-08-23 已确认）

- **唤醒机制**：**统一 WakeQueue**——单一 Room 队列承载 #4 hook 审查结果 + #10 耗时任务结果，下轮注入 system-reminder + 消费确认，一套机制两处消费。
- **后台化范围**：**终端长命令（detach）+ 容器下载/安装完成 + MCP server 处理完成**；Agent Bash 工具超时转后台本期不纳入。
- **生命周期**：**Room 持久化 + 重扫**——唤醒队列落库，App 被杀后下次启动重扫待注入队列继续唤醒。
- **并发/预算**：**用户可配置 + 默认并发 1**（对齐 #9 预算思想）。

### 5.12 插件五件套复用决策（2026-08-23 已确认）

- **复用范围**：**资产容器化**——引入 plugin.json 元数据 + 本地导入（zip/目录）+ 校验 + 安全审查，把现有 skills/commands/prompts 统一为可导入导出资产。
- **hooks 桥接**：**翻译为 #5 规则**——规则型 hook 导入时翻译为规则引擎 MD 规则（声明式、零外部进程、安全）。
- **安全审查**：**复用 Zth 审查**——导入时复用 ZthContentReviewer 2 步内容审查（Skill 正文/规则/MCP server.json），有风险资产标记警告。
- **分发形态**：**预留市场**——marketplace 协议预留扩展点，本期只做本地导入。

### 5.13 插件移植决策（2026-08-23 已确认）

- **推进方式**：**先建容器再移植**——先实施 #4 Hook 代码级骨架 + #1 agent 资产系统（容器），再按 A→B→C→D 分类逐个移植 13 个插件。
- **移植范围**：**全部 13 个插件**（含翻译与自定义实现）。

## 6. 待深入讨论的问题清单（逐步讨论用）

1. ~~**范围确认**~~（已定：A→B→C，见 5.1）
2. **Hook 事件模型**：R-CodeCore 是否需要完整 Hook 事件（PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart）？还是先做「工具执行前后」两事件？asyncRewake 后台唤醒在无后台会话的 Android 端如何表达（是否借用终端会话/通知）？
3. ~~**Bash 预校验**~~（已定：内置表 + block/warn 分级 + 复用现有权限引擎/守卫体系，见 5.2 与 7）
4. **规则引擎**：规则格式（YAML frontmatter 的 .local.md）在 Android 端是否合适？规则文件放哪（assets / 工作区 / 设置目录）？是否支持「仓库级规则」随项目走？
5. **权限分级**：`ask/deny/allow` 三分模型是否引入 `deny` 白名单策略文件？`disableBypassPermissionsMode` 对应什么 UI/入口？
6. **Agent 声明式定义**：现有 `assets/prompts/` 与「元数据 + 正文」如何平滑迁移？是否影响 SystemPromptProvider 加载链？
7. **文档同步纪律**：实施后需同步 `docs/modules/`（agent/terminal/settings）与 `assets/docs/` 使用说明。

## 7. 方向 #6 设计定稿（草案）

### 7.1 现状与缺口

现有防护已覆盖：灾难 rm 拦截（含 AUTO）、fork bomb 拦截、无界循环超时钳制、BusyBox 兼容提示。缺口：

1. 除灾难 rm 外**无危险命令静态拦截表**（RCE 管道 / 系统目录权限破坏 / 设备磁盘破坏 / 关机杀进程）。
2. **AUTO 模式**只查灾难 rm，其余危险模式直接放行。
3. 缺统一的**危险命令守卫层**（防护分散在权限引擎 + 工具入口两处）。

### 7.2 设计

新增 `feature/agent/domain/permission/DangerousCommandGuard.kt`：

- **两级判定**：
  - `Block`：命中即拒绝执行（AUTO/NORMAL 均生效），返回 `denyReason` 喂给模型（与 fork bomb 拦截一致）。
  - `Warn`：放行，在权限卡 `details` + 命令输出末尾提示（复用 BusyBox 提示路径）。
- **判定引擎**：优先用 `ShellCommandParser` 段级 token 判定（`chmod 777 /` vs `chmod 777 file` 用 token 序列区分；`curl|sh` 用「前段 curl/wget + 下段 sh/bash」跨段组合）。
- **接入点（双层）**：
  1. `ToolPermissionPolicyEngine.evaluateShell` 在灾难 rm 判定后插入 Block；AUTO 分支同步加。
  2. `ExecuteCommandTool.execute/executeStream` 开头加 Block 硬拦截兜底（绕过权限引擎时仍安全）。

### 7.3 规则清单定稿

**Block（高置信、必然灾难性）**

| # | 类别 | 判定要点 | 提示语 |
|---|------|---------|--------|
| B1 | RCE 管道 | **仅拦截「管道直接执行」**：前段 curl/wget + 后段 sh/bash/zsh/dash/ash（相邻段，`\|` 连接）；`&&` 断开（`curl -O && sh`）不拦；正则兜底 `<(curl|wget)` | 禁止下载后直接执行远程脚本（供应链/代码执行风险）；先 `curl -O` 检查或用包管理器 |
| B2 | 系统目录权限破坏 | chmod/chown 目标含系统根目录（复用灾难 rm 的 sysDirs）且（递归或 777 权限）；工作区路径不拦 | 禁止修改系统目录权限（破坏容器稳定性/安全隐患） |
| B3 | 设备/磁盘破坏 | dd 带 `of=/dev/`、mkfs.*、fdisk、`> /dev/sd*`、`: > /etc/...` 等关键文件覆盖 | 禁止对 /dev/ 设备写操作/格式化（损坏存储） |
| B4 | 关机/杀进程 | **仅拦无目标杀进程**：shutdown/reboot/halt/poweroff、`kill -9 -1`、无目标 `pkill -9`/`killall`；有具体进程名（如 `pkill -9 nginx`）不拦 | 禁止关机/重启/杀掉所有进程（导致容器/会话崩溃） |

**Warn（中置信、提示不拦截）**

| # | 判定要点 | 提示语 |
|---|---------|--------|
| W1 | chmod 777/666 到具体文件（非系统目录） | 权限过宽，建议收紧为 755/644 |
| W2 | `: > file` 截断、`> file` 覆盖工作区文件 | 该命令会清空/覆盖目标文件 |
| W3 | curl/wget 下载到工作区外绝对路径（`-o /...`） | 正在下载到工作区外路径，请确认目标位置 |
| W4 | curl 不带 `-o`/`-O` 直接输出到终端（防刷屏） | 建议加 `-o <文件>` 或 `-s` 避免大段输出刷屏 |
| W5 | sudo 高危操作：`sudo (chmod\|chown\|rm\|shutdown\|mkfs\|dd\|iptables\|useradd\|usermod)` | 正在以 sudo 执行高危操作，确认无提权风险 |
| W6 | 敏感文件读取：`cat/head/tail/base64 + /etc/(shadow\|passwd\|gshadow\|sudoers)` | 读取凭据类文件，注意泄露风险 |
| W7 | SSH 密钥覆盖：`> /~/.ssh/(authorized_keys\|id_rsa\|id_ed25519)` | 覆盖 SSH 授权/密钥文件 |
| W8 | base64 解码执行：`base64 -d \| sh`、`python -c '...base64...'` | 解码后执行，确认来源可信 |
| W9 | 明文密码入脚本：`passwd\|password\|pwd : "..."` | 避免明文凭据写入脚本/提交 |
| W10 | git force push：`git push --force(-with-lease)` | 强制推送可能覆盖远端历史 |
| W11 | 反向 shell 模式：`nc/socat/bash -i + /dev/tcp 或远程 IP` | 若为合法网络调试可忽略；否则疑似反向连接 |

### 7.4 待办

- [ ] 实施 DangerousCommandGuard + 双层接入（详见 7.2）
- [ ] 新增/补充单元测试（覆盖 4 类 Block + 4 类 Warn + 误报样本如 `chmod 777 工作区文件`、`curl -O`、`ls /dev/`、`pkill nginx`）
- [ ] 按资产同步纪律更新 `assets/prompts/`（命令纪律）与 `assets/docs/`（Bash 行为变化说明）
- [ ] 同步 `docs/modules/`（agent 模块文档记录守卫体系）

### 7.5 实现细节定稿（2026-08-23 确认）

1. **B3 误报防护（三条子判定）**：dd 用参数 `of=/dev/...` 判定；mkfs.\* / fdisk 用程序名判定；重定向到设备用原始命令正则 `>[[:space:]]*/dev/` 兜底；覆盖关键文件用 `> /etc/(passwd|shadow|group|hosts|sudoers)` 正则。`ls /dev/`、`cat /dev/sda`（读设备）不拦。
2. **B2 用 `parseChmodInfo`**：仿照 `ShellCommandParser.parseRmInfo` 解析 chmod/chown 的递归（-R/-r）、权限位（777/666）、目标路径，再与 sysDirs 比对；工作区路径不在 sysDirs，自动放行。
3. **Block 优先级 = 安全底线最前**：`evaluateShell` 顺序 = Block → 灾难 rm → DENY 规则 → 内置白名单 → 已记忆 ALLOW → ASK。用户规则只能加严、不能放宽（即使配了 `allow: ["Bash(curl|sh)"]` 仍拦截）。
4. **AUTO 模式**：Block 在 AUTO 分支返回 `Verdict.DENY` + `denyReason`（与现有灾难 rm 路径一致，无需新机制）。
5. **Warn 统一合并提示通道（含 W5-W11 扩容）**：`DangerousCommandGuard.warnMessage(command)` 返回所有命中 Warn 的合并提示块，与 `BusyBoxCompatibilityGuard.warningMessage` 合并成同一提示块；在 `buildPermissionRequest`（权限卡 details）与 `appendHint`（输出末尾）两处统一调用，**一条命令一次展示**，避免重复提示。
6. **terminal(start) 双层覆盖**：权限引擎层覆盖全部 shell 工具（含 `terminal action=start` 常驻会话）；工具入口硬拦截（`ExecuteCommandTool`）只兜 Bash。
7. **与 Zth 内容审查去重**：命令执行路径走 #6（execute 静态判定），内容审查走 Zth（Skill/MCP/输入文本）——作用域不同、重叠低；但 Warn 提示块设计为可合并通道，避免同一条命令弹多条提示。

### 7.6 设计状态

✅ #6 设计定稿（规则清单 + 实现细节已确认），待实施。

## 8. 方向 #1 设计定稿（草案）

### 8.1 现状与缺口

现有提示词装配（`SystemPromptProvider`）：
- **硬编码顺序**：`StaticRuleSource` 写死 9 片段 join 顺序。
- **无元数据**：文件名（`00-identity.md`）承载顺序 + 语义，无 name/description/tools/model。
- **静态不热**：`StaticRuleSource` 一次性缓存永不失效；仅 `ProjectRuleSource`（mtime）/ `ActiveSkillsSource`（每轮）热。
- **无组合**：无 includes 引用机制。

已有可复用基建：
- **三级优先级解析**：`prompts.custom/ > prompts/ > assets`（`resolvePrompt`）。
- **斜杠命令系统**：`SlashCommandRegistry` / `SlashCommandHandler`（`ChatInputBar` 已有 `/` 菜单）。
- **frontmatter 解析**：`SkillParser.splitAndParseFrontmatter` + SnakeYAML 2.2（依赖已在）。
- **多 agent 雏形**：`SkillScope.AGENT` 已含 `agent_type` 字段。

### 8.2 设计

新增 `feature/agent/domain/prompt/AgentAssetRegistry.kt`：
- 扫描 `prompts/` + `prompts.custom/`，解析 frontmatter，按 `order` 排序组装。
- 区分主 agent 组件（`agent: false`）与专项 agent（`agent: true`）。
- 支持 `includes` 引用组合。
- 热加载双机制（mtime + FileObserver）。
- 会话内 agent 切换：`AgentContext` 增加 `currentAgentId`，切换后系统提示词正文替换为该 agent。

### 8.3 frontmatter 格式（YAML）

```yaml
---
name: identity          # 资产唯一标识
description: 角色与自我认知  # 用途/触发场景
order: 0                # 加载顺序（替代硬编码列表）
enabled: true           # 可禁用
agent: false            # false=主 agent 组件；true=可触发专项 agent
mode: [default]         # default/plan/auto（统一 mode 片段，替代 Plan/Auto Source）
tools: []               # 仅建议语义（不硬拦截；权限引擎仍管审批）
model: ""               # 仅提示语义（仍用用户选定 provider/model）
includes: []            # 引用其它资产（组合复用）
---
正文
```

### 8.4 热加载双机制（已确认：mtime + FileObserver 并行）

- **主机制（mtime 懒刷新）**：build 时比对 `prompts/` 目录与各文件 mtime，变化才重扫（对齐 `ProjectRuleSource`）。
- **辅机制（FileObserver）**：监听 `prompts/` + `prompts.custom/` 增删改 → 失效 registry 缓存 + 触发 `ToolEvent` 增量刷新。
- **兜底**：FileObserver 被系统回收 / inotify 上限超限时，mtime 懒刷新仍兜底生效。

### 8.5 触发与切换

- **命令触发**：复用 `SlashCommandRegistry`，新增 `/agent <name>` 命令（列出/切换专项 agent）。
- **会话内切换**：新增「当前 agent」会话状态，切换后系统提示词正文替换；主 agent 为默认。
- **tools/model 仅建议/提示**：不强制切换 provider，不拦截白名单外工具。

### 8.6 接入改造

- `StaticRuleSource` → 从 `AgentAssetRegistry` 读取（order 排序），删除硬编码片段列表。
- `PlanModeSource` / `AutoModeSource` → 由 `mode` 字段统一，删除两个特殊 Source。
- `resolvePrompt` 三级优先级保留（frontmatter 随文件覆盖）。
- `AgentContext` 增加 `currentAgentId`；`SkillScope.AGENT` 的 `agent_type` 与之对齐。

### 8.7 待办

- [ ] `AgentAssetRegistry` + frontmatter 解析（复用 `SkillParser.splitAndParseFrontmatter` 风格 + SnakeYAML）
- [ ] 现有 11 个 prompt 文件加 frontmatter（迁移）
- [ ] `SlashCommandRegistry` 新增 `/agent` 命令
- [ ] `AgentContext` / 会话状态加 `currentAgentId` + UI 切换入口
- [ ] 单元测试（解析/排序/includes/热加载失效）
- [ ] 按资产同步纪律更新 `assets/prompts/`、`assets/docs/` 与 `docs/modules/`

### 8.8 设计状态

✅ #1 设计定稿（8 个决策点已确认），待实施。

## 9. 方向 #2 设计定稿（草案）

### 9.1 Claude Code 范式提取（调研源）

- **代码审查**（code-review.md）：预检 → 找规则文件 → 并行多 agent 独立审查 → 再并行 agent 逐条验证 issue → 只留 HIGH SIGNAL。
- **功能开发**（feature-dev.md）：7 阶段（Discovery → 探索 → 澄清 → 架构 → 实施 → 质检 → 总结），关键节点等用户拍板。
- **按变更类型派 agent**（review-pr.md）：`git diff --name-only` 识别变更 → 按文件类型派专项 agent → 聚合 Critical/Important/Suggestions + 行动方案。

### 9.2 现状与缺口

- 已有：`SlashCommandRegistry`（代码级命令）、`feature/git`（`GitRepository` 等）、#1 声明式 agent 资产体系（已定稿）。
- 缺口：无多阶段工作流、无按变更类型动态分支、无输出分级聚合。

### 9.3 设计

- **两个工作流**：`/code-review`、`/feature-dev`，做成 **#1 声明式资产**（新增 `workflow` 资产类型，含阶段列表/触发条件/引用专项 agent）。
- **专项 agent**：`code-reviewer` / `code-architect` / `code-explorer` 等为 #1 的 `agent: true` 资产。
- **编排执行器**：解析 workflow 资产 → 按阶段顺序执行，每阶段可选「切换 agent 定义」（单 agent 顺序扮演）或将来「派 subagent」（#9）。
- **变更类型分类器**：git diff 变更文件 → 路径/后缀分类（test/注释/错误处理/新类型/通用）→ 自动选专项审查。
- **输出聚合**：Critical/Important/Suggestions 分级 + 行动方案，只报高置信（衔接 #3）。

### 9.4 与其它方向的依赖

| 依赖 | 说明 |
|---|---|
| #1（先落地） | 工作流命令 + 专项 agent 均靠 #1 资产体系承载 |
| #3（衔接） | 输出分级只报高置信问题 |
| #9（远期） | 物理 subagent 派发、并发/预算上限 |

### 9.5 待办（依赖 #1 落地后启动）

- [ ] #1 落地后：`workflow` 资产类型（阶段列表/触发条件/引用 agent）
- [ ] `code-reviewer` / `code-architect` / `code-explorer` 专项 agent 资产
- [ ] 变更类型分类器（git diff → 路径/后缀分类）
- [ ] 编排执行器（阶段顺序执行 + 动态分支）
- [ ] 输出分级聚合（Critical/Important/Suggestions + 行动方案）
- [ ] 按资产同步纪律更新 `assets/prompts/`、`assets/docs/` 与 `docs/modules/`

### 9.6 设计状态

✅ #2 设计定稿（4 个决策点已确认），依赖 #1 落地后实施。

## 10. 方向 #3 设计定稿（草案）

### 10.1 范式提取（Claude Code code-reviewer）

Confidence Scoring 本质是 **prompt 纪律，非代码机制**：
- 五档评分锚点 0/25/50/75/100，每档配描述帮模型对齐（非裸连续分）。
- 硬规则「只报 ≥80」，低置信直接不报。
- 写在 agent 正文，随 agent 定义资产走。

### 10.2 现状与角色区分

| 体系 | 类型 | 角色 |
|---|---|---|
| `ZthToolOutputGuard.hallucinationConfidence` | 确定性置信 | 防幻觉（拦截错误工具输出） |
| `ZthContentReviewer` | 确定性置信 | 防注入（审查 Skill/MCP/输入） |
| **#3 生成式置信** | 提示词纪律 | **防误报**（过滤低质量审查/修复建议） |

确定性置信与 #3 角色不同，**不合并**。

### 10.3 设计（已确认：纯 prompt 纪律）

- code-reviewer 等专项 agent（#1 资产）正文内嵌 Confidence Scoring 规范。
- **五档锚点**：0（误报/存量）/ 25（可能误报）/ 50（真问题但轻）/ 75（已核实、影响功能）/ 100（确凿、高频）。
- **只报 ≥80**，低置信直接过滤。
- **输出分级**：Critical / Important 分组 + 置信度标注，衔接 #2 输出聚合（Critical/Important/Suggestions + 行动方案）。

### 10.4 决策记录（4 决策点）

- 约束方式：**纯 prompt 纪律**（无 tool use / 无结构化强制）。
- 评分粒度：**五档锚点**。
- 低置信处置：**直接过滤**。
- 落地时机：**随 #1/#2 实施**。

### 10.5 待办（随 #1/#2）

- [ ] code-reviewer 等专项 agent 正文含 Confidence Scoring 规范（五档锚点 + 只报 ≥80）
- [ ] 输出格式规范（Critical/Important 分组 + 置信度标注）
- [ ] 按资产同步纪律更新 `assets/prompts/` 与 `assets/docs/`

### 10.6 设计状态

✅ #3 设计定稿（4 个决策点已确认），随 #1/#2 实施。

## 11. 方向 #4 设计定稿（草案）

### 11.1 范式提取（Claude Code security-guidance / hookify）

- **事件**：PreToolUse / PostToolUse / UserPromptSubmit / Stop / SessionStart。
- **matcher**：按工具名匹配（`"Edit|Write|MultiEdit"`）；**if** 精确匹配（`"Bash(git commit:*)"`）。
- **asyncRewake**：后台审查 + `rewakeMessage`/`rewakeSummary` 唤醒插入对话，不阻塞主流程。
- **timeout**：hook 执行超时。
- 典型：PostToolUse 挂 `Bash(git commit/push)` 后台安全审查；Stop 后台审查、下轮反馈。

### 11.2 现状与机会

- `ToolPermissionManager` 只管权限弹窗挂起；判定在 `ToolPermissionPolicyEngine`。
- ZTH 已有 preTool/postTool 接入点雏形（`StatefulAgentWorkflow` 接入点 B/C）——hook 框架可复用这些挂点。
- 无后台会话机制，「后台唤醒」需用「下轮注入」表达。

### 11.3 设计

- **HookDispatcher**：代码级 multibinding（同 `SlashCommandHandler` 模式），注册 Hook 处理器。
- **完整事件集**：PreToolUse / PostToolUse / UserPromptSubmit / Stop / SessionStart，各事件定义回调接口，携带上下文（工具名/参数/输出/会话）。
- **matcher / if**：事件过滤（工具名 + 精确匹配，对齐 `Bash(git commit:*)` 语法）。
- **asyncRewake 下轮注入（关键：确保注入成功且有效）**：
  1. Stop / PostToolUse 触发后台审查 → 结果**持久化**到 Room（key = sessionId，防会话结束 / App 被杀丢失）；
  2. 下一轮 `executeEvents` 开始时检查未消费唤醒 → 在系统提示词后、用户消息前注入为 system-reminder；
  3. 注入成功即**标记已消费**（防重复注入）；注入动作原子化，失败则保留待下次；
  4. 唤醒内容结构化（`rewakeMessage` + `rewakeSummary`），说明「补充审查发现，处理完继续原任务」。
- **对齐 AGENTS.md 纪律**：hook 承载纪律检查（提交前检查、危险命令静态拦截补强、规则合规、资产同步提醒等）。

### 11.4 决策记录（4 决策点）

- 落地形态：**代码后声明**（先代码级骨架，后续 #5 声明式化条件/动作）。
- 事件范围：**完整事件集**。
- 唤醒表达：**下轮注入 + 持久化 + 消费确认**。
- 与 #5：**骨架 + 规则分离**。

### 11.5 待办

- [ ] `HookDispatcher` + 5 事件接口（PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart）
- [ ] 复用 ZTH preTool/postTool 挂点挂载
- [ ] 唤醒持久化存储（Room）+ 下轮注入 + 消费确认（原子化）
- [ ] 示例 hook（提交前纪律检查 / 危险命令补强）
- [ ] 按资产同步纪律更新 `docs/modules/` 与 `assets/docs/`

### 11.6 设计状态

✅ #4 设计定稿（4 个决策点已确认），代码级骨架可先行落地。

## 12. 方向 #5 设计定稿（草案）

### 12.1 范式提取（Claude Code hookify rule_engine + config_loader）

- **Condition**：`{field, operator, pattern}`；运算符 = `regex_match / contains / equals / not_contains / starts_with / ends_with`。
- **Rule**：`{name, enabled, event, tool_matcher, conditions[], action(warn|block), message(正文)}`。
- **评估**：tool_matcher 过滤 → 全部 conditions 匹配 → **block 优先 warn** → 输出 decision / systemMessage；PreToolUse 的 block → `permissionDecision: deny`。
- **存储**：每规则一个 `.local.md`，frontmatter + 正文（正文即提示语）。

### 12.2 现状与缺口

- 现有 [PermissionRule](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/permission/PermissionModels.kt) 二元（toolName + pattern + ALLOW/DENY），**无条件表达式、无 warn、无事件维度**。
- JSON（`permissions.json`）=「始终允许/拒绝」的**记忆授权**产物。
- [evaluateShell](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt#L167-L229) 顺序：灾难 rm → DENY → 不可判定 → 内置白名单 → 已记忆 ALLOW → ASK。
- 已有 frontmatter 解析（SkillParser + #1 定稿 SnakeYAML）可复用。

### 12.3 设计（已确认：MD+JSON 共存、引入 warn、权限+Hook 双消费点）

- **规则模型升级**：
  - `Condition(field, operator, pattern)`，field ∈ command / file_path / content 等，operator 六种（对齐 hookify）。
  - `Rule(name, enabled, event, tool_matcher, conditions[], action(allow|deny|warn), message)`。
- **存储**：
  - MD 规则层：项目 `.rcodecore/rules/*.md` + 全局 `filesDir/rcodecore/rules/*.md`（frontmatter + 正文，可 git 追踪/回滚）。
  - JSON 保留：记忆授权产物，职责分离。
- **统一规则引擎 RuleEngine**：解析 MD → 评估（tool_matcher + conditions → action），**双消费点**：
  1. **权限评估**：接入 `ToolPermissionPolicyEngine.evaluateShell/evaluateGeneric`；deny 优先级最高，warn 附加提示（弹窗附注/注入对话）。
  2. **Hook 事件**（#4）：`HookDispatcher` 事件消费规则，条件/动作声明式化。
- **优先级**：内置安全底线（灾难 rm / #6 Block）> 用户 deny/block > 已记忆 ALLOW > 内置白名单 > warn 提示 > ASK；用户规则只增不减。
- **热加载**：MD 规则 mtime 懒刷新（对齐 #1 `AgentAssetRegistry` / `ProjectRuleSource` 做法）。
- **frontmatter 解析复用**：SnakeYAML（#1 定稿依赖）或现有 `SkillParser` 抽公共工具。

### 12.4 决策记录（3 决策点）

- 规则载体：**MD + JSON 共存**。
- 动作扩展：**allow/deny/warn 三态**。
- 作用域：**权限 + Hook 全上**（统一引擎双消费点，实施可分层）。

### 12.5 待办

- [ ] RuleEngine（Condition/六运算符/评估，对齐 hookify rule_engine）
- [ ] MD 规则层（`.rcodecore/rules/` + 全局）+ mtime 热加载
- [ ] 接入 ToolPermissionPolicyEngine（deny 优先、warn 附加）
- [ ] 接入 #4 HookDispatcher（事件规则消费）
- [ ] frontmatter 解析公共工具（SnakeYAML / SkillParser 抽取）
- [ ] 按资产同步纪律更新 `docs/modules/` 与 `assets/docs/`

### 12.6 设计状态

✅ #5 设计定稿（3 个决策点已确认），实施与 #4 骨架协同。

## 13. 方向 #7 设计定稿（草案）

### 13.1 现状

- Verdict{ALLOW/DENY/ASK} 三分已有；`PermissionsSettingsSection` 已列规则（项目/全局、删除、提升全局）。
- **AUTO 模式 = 放行所有权限**（只查灾难 rm + #6 Block）——即「绕过」形态；「始终允许」记忆也是授权放宽。
- #5 已定稿 MD 规则层（allow/deny/warn + deny 优先 + 只增不减）。

### 13.2 设计（已确认：禁 AUTO + 禁记忆、独立 deny 文件）

- **deny 白名单策略文件（独立）**：`.rcodecore/deny.json`（项目级）+ 全局 `filesDir/rcodecore/deny.json`，**管理级强制 deny**，最高优先（连已记忆 ALLOW 也拦）；区别于 #5 用户可配规则（#5 = 用户配置，deny 文件 = 管理/团队策略）。
- **「禁绕过」开关**（settings 安全设置页，对齐 Claude Code `disableBypassPermissionsMode`）：
  - **禁切 AUTO**：入口禁用 + 已有 AUTO 会话降级提示；
  - **禁新增「始终允许」记忆**：权限弹窗不再提供「始终允许」。
- **优先级链**（禁绕过开启）：deny 白名单文件 > 内置安全底线（灾难 rm / #6 Block）> #5 用户 deny/block > 已记忆 ALLOW（禁新记忆后仅存量生效）> 内置白名单 > warn > ASK。

### 13.3 决策记录（2 决策点）

- 禁绕过范围：**禁 AUTO + 禁新记忆「始终允许」**（全封闭）。
- deny 白名单：**独立文件**（管理级强制 deny）。

### 13.4 待办

- [ ] `.rcodecore/deny.json`（项目 + 全局）加载与评估（最高优先）
- [ ] settings 安全设置页「禁绕过」开关 + DataStore
- [ ] AUTO 入口禁用 + 存量会话降级
- [ ] 权限弹窗「始终允许」按开关隐藏
- [ ] 按资产同步纪律更新 `assets/docs/`（权限使用说明）与 `docs/modules/`（agent/settings）

### 13.5 设计状态

✅ #7 设计定稿（2 个决策点已确认），与 #5 deny 分层协同。

## 14. 方向 #8 设计定稿（草案）

### 14.1 现状

- 容器 PRoot **共享宿主网络**（无隔离），DNS 写阿里云。
- **已有 mihomo 代理内核**（feature/proxy `ClashProxyManager`）+ 容器启动注入 `http/https/all_proxy → 127.0.0.1:7890`（`exportContainerEnv`）。
- 无 allowedDomains / 域名白名单。

### 14.2 设计（已确认：L1 仅 HTTP 白名单、两级配置 + 默认关）

- **allowedDomains 两级配置**：全局（DataStore）+ 项目级（`.rcodecore/network.json`）。
- **mihomo 规则注入**：启用时生成/追加白名单规则——allowedDomains → DIRECT（或走代理），**其余 → REJECT**（MATCH → REJECT），mode=rule 白名单策略。
- **默认关闭**：默认不启用（软限制），启用即严格白名单。
- **覆盖范围（诚实标注）**：HTTP(S) 代理流量受控；DNS / git ssh / 非标准端口 TCP 不受控（mihomo 只代理 HTTP(S)，非透明代理）。

### 14.3 决策记录（2 决策点）

- 层级：**L1 仅 HTTP 白名单**（基于 mihomo）。
- 配置：**两级 + 默认关**。

### 14.4 待办

- [ ] allowedDomains 两级配置（DataStore + `.rcodecore/network.json`）
- [ ] mihomo 规则生成/注入 + reload（白名单放行 + REJECT）
- [ ] settings 网络代理页加 allowedDomains 编辑入口
- [ ] 按资产同步纪律更新 `assets/docs/`（网络限制说明）与 `docs/modules/`（proxy/agent）

### 14.5 设计状态

✅ #8 设计定稿（2 个决策点已确认），基于现有 mihomo 代理基础设施。

## 15. 方向 #9 设计定稿（草案）

### 15.1 现状（三个既有支撑）

- **agent_type 雏形已存在**：[SkillScope.AGENT](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/skill/Skill.kt#L41) + `Skill.agentType`（默认 `"coding"`），`SkillStateRepository` 注释「多 Agent 演进后由调用方传入动态值」——#1 的 agent_type 动态化即 #9 基础。
- **上下文预算体系成熟**：[ContextCompactor](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/workflow/ContextCompactor.kt#L211) + `ModelContextPolicy.preserveRecentTokens` 可被 subagent 复用。
- **工具并行安全已就绪**：[ToolSessionState](file:///workspace/app/src/main/java/com/R/codecore/feature/agent/domain/tool/ToolSessionState.kt#L17)「并行工具同 key 串行安全」；provider 多 tool_use 已支持。

### 15.2 设计（已确认：独立会话落库、继承环境+简报、并发/预算可配置、骨架先行）

- **形态**：subagent = 复用现有 `StatefulAgentWorkflow` 实例 + 独立 ChatSession（type 标记 subagent），落库可追溯、结果可恢复。
- **上下文继承**：继承环境（workspace/skills/权限/MCP）+ 主 agent 生成的**任务简报**；不继承对话历史（token 成本低）。
- **并发/预算上限（用户可配置）**：settings 项 = 最大并发数 + 单 subagent 轮数上限 + API 次数上限 + 超时；默认并发 1 + 硬上限。
- **落地**：骨架先行——独立实现 fork 工具 + 预算控制；agent_type 动态化后续接入（#1 落地后启用）。
- **与 #2 衔接**：#9 落地 = #2 的物理 subagent 派发能力。

### 15.3 决策记录（4 决策点）

- 形态：**独立会话落库**。
- 上下文继承：**环境 + 简报，不继承历史**。
- 并发/预算：**用户可配置**（默认并发 1 + 硬上限）。
- 落地：**骨架先行**。

### 15.4 待办

- [ ] fork 工具（新建 subagent 会话 + 派发任务简报）
- [ ] 预算控制（并发/轮数/API 次数/超时，settings 可配）
- [ ] subagent 结果回收与摘要返回主会话（衔接 #3 置信度）
- [ ] agent_type 动态化接入（#1 落地后）
- [ ] 按资产同步纪律更新 `docs/modules/`（agent）与 `assets/docs/`

### 15.5 设计状态

✅ #9 设计定稿（4 个决策点已确认），10 个方向全部定稿。

## 16. 方向 #10 设计定稿（草案）

### 16.1 现状（三个事实）

- **无现成 system-reminder 机制**——全新能力；「下轮注入」概念 #4 asyncRewake 已定稿。
- **无 WorkManager**——项目风格为 Service + 协程 + App 启动周期调用（AuditPurgeWorker/CredentialRotationWorker），不引入新框架。
- **已有两个常驻后台**：`TerminalKeepaliveService`（终端保活）+ `McpServerManager`（MCP server 常驻）。

### 16.2 设计（已确认：统一 WakeQueue、终端/容器/MCP、Room 持久化+重扫、可配置默认 1）

- **统一 WakeQueue（Room 表）**：wake_id / source / type / content / status(待注入/已注入) / createdAt。
  - #4 hook 审查结果 + #10 耗时任务结果**共用一套队列**，下轮注入 system-reminder + 消费确认（防重复）。
- **后台化范围**：
  - 终端长命令：detach 到会话后台跑（TerminalKeepaliveService 已保活），完成写唤醒队列；
  - 容器下载/安装：完成写唤醒队列；
  - MCP server：处理完成通知写唤醒队列。
  - Agent Bash 工具超时转后台**本期不纳入**。
- **注入时机**：下一轮会话开始前拼入 system-reminder（需在 SystemPromptProvider 或 workflow 前处理，全新机制）。
- **生命周期**：WakeQueue Room 持久化 + 会话落库；App 被杀后下次启动**重扫待注入队列**继续唤醒。
- **并发/预算**：后台任务并发上限**用户可配置**（对齐 #9），默认并发 1 + 超时上限。
- **与 #4 关系**：#4 先做骨架（HookDispatcher + WakeQueue），#10 复用队列承载耗时任务。

### 16.3 决策记录（4 决策点）

- 唤醒机制：**统一 WakeQueue**（一套机制两处消费）。
- 后台化范围：**终端 + 容器 + MCP**（Bash 超时转后台不纳入）。
- 生命周期：**Room 持久化 + 重扫**。
- 并发/预算：**可配置 + 默认 1**。

### 16.4 待办

- [ ] WakeQueue 表 + DAO（Room 迁移）
- [ ] system-reminder 注入机制（下轮会话开始前拼接 + 消费确认）
- [ ] 终端长命令 detach 后台化 + 完成唤醒
- [ ] 容器下载/安装完成唤醒
- [ ] MCP server 完成通知唤醒
- [ ] 后台并发预算用户可配置（对齐 #9）
- [ ] 按资产同步纪律更新 `docs/modules/`（agent/terminal）与 `assets/docs/`

### 16.5 设计状态

✅ #10 设计定稿（4 个决策点已确认）——**10 个方向全部定稿完成**。

## 17. 插件五件套复用设计定稿（草案，延伸方向）

### 17.1 现状映射（五件套 → R-CodeCore）

| Claude Code 五件套 | R-CodeCore 对应物 | 状态 |
|---|---|---|
| `skills/` | Skill 系统（内置 `BuiltinSkillSeeder` + 本地目录扩展 `LocalDirectorySkillSource`） | ✅ 已有 |
| `commands/` | `SlashCommandRegistry` | ✅ 已有 |
| `agents/` | #1 agent 定义资产（`agent: true`） | 📝 设计定稿 |
| `hooks/` | #4 HookDispatcher + #5 规则引擎 | 📝 设计定稿 |
| `.claude-plugin/plugin.json` | **无** | ❌ **唯一缺口** |

**核心结论**：五件套的资产部分（skills/commands/agents/hooks）已有或设计已覆盖；真正缺的是**插件化这一层**——plugin.json 元数据 + 导入/导出 + 校验 + 安全审查。

### 17.2 设计（已确认：资产容器化、hooks→#5 规则、Zth 审查、预留市场）

- **plugin.json 元数据**：name / description / version / author / entry（五件套入口）+ assets 清单。
- **本地导入**：zip/目录 → 用户插件目录，与内置资产共存（对齐 `prompts.custom/ > prompts/ > assets` 覆盖关系）。
- **校验**：目录结构 + frontmatter 合法性。
- **安全审查**：复用 `ZthContentReviewer` 2 步内容审查，有风险资产标记警告。
- **hooks 桥接**：规则型 hook 导入时翻译为 #5 规则引擎 MD 规则（声明式、零外部进程、安全）。
- **marketplace 预留**：插件清单协议预留扩展点，本期不实现。

### 17.3 决策记录（4 决策点）

- 复用范围：**资产容器化**。
- hooks 桥接：**翻译为 #5 规则**。
- 安全审查：**复用 Zth 审查**。
- 分发形态：**预留市场**。

### 17.4 待办

- [ ] plugin.json 元数据模型 + 解析
- [ ] zip/目录导入 + 结构校验
- [ ] Zth 导入内容审查（复用 ZthContentReviewer）
- [ ] hooks → #5 规则翻译器
- [ ] 插件资产 vs 内置资产覆盖关系/优先级
- [ ] marketplace 协议预留扩展点
- [ ] 按资产同步纪律更新 `docs/modules/` 与 `assets/docs/`

### 17.5 设计状态

✅ 插件五件套复用设计定稿（4 个决策点已确认），与 #1/#4/#5 基建协同。

## 18. 插件移植计划定稿（延伸方向）

### 18.1 可行性结论（三种硬差异）

- **语言差异（最硬）**：hook 是 Python/shell 脚本（security-guidance 11 个 py、hookify 4 个 py、ralph-wiggum stop-hook.sh），R-CodeCore 运行时无 Python——**hooks 必须翻译**为 Kotlin 或 #5 规则引擎 MD 规则。
- **格式差异**：插件是 Claude Code 格式（YAML frontmatter + plugin.json + hooks.json），需**格式转换层**（#17 插件化）。
- **环境差异**：提示词含 `CLAUDE.md`/`gh pr`/`npm` 等 Claude Code 环境引用；agent tools 白名单需映射到 R-CodeCore ToolRegistry。

### 18.2 移植分类（13 个）

| 类别 | 插件 | 工作量 | 说明 |
|---|---|---|---|
| **A 轻改移植**（纯 Markdown，格式转换） | commit-commands、frontend-design、claude-opus-4-5-migration、explanatory-output-style、learning-output-style | 小 | 提示词为主，frontmatter 适配 + 工具映射 |
| **B 适配移植**（多 agent 编排，改环境引用） | feature-dev、pr-review-toolkit、code-review、plugin-dev | 中 | `gh`→本地 git、`CLAUDE.md`→项目规则、tools 映射 |
| **C 翻译移植**（Python hook → #5 规则） | hookify、security-guidance | 大 | 4+11 个 py 翻译为 Kotlin 规则/规则引擎 MD |
| **D 自定义实现**（行为型） | ralph-wiggum、agent-sdk-dev | 中/低价值 | ralph 需 workflow 循环控制；SDK 开发对 R-CodeCore 低价值 |

### 18.3 决策记录

- 推进方式：**先建容器再移植**（#4 Hook 骨架 + #1 agent 资产系统先行）。
- 移植范围：**全部 13 个**。

### 18.4 实施路线图（阶段 0 → D）

- **阶段 0（容器）**：#4 Hook 代码级骨架（HookDispatcher + WakeQueue）+ #1 agent 资产系统（AgentAssetRegistry + frontmatter）→ 容器就绪。
- **阶段 A**：4-5 个纯资产插件轻改移植（commit-commands / frontend-design / claude-opus-4-5-migration / 两个 output-style）。
- **阶段 B**：4 个编排型插件适配移植（feature-dev / pr-review-toolkit / code-review / plugin-dev）。
- **阶段 C**：2 个 hook 型插件翻译移植（hookify / security-guidance → #5 规则）。
- **阶段 D**：2 个行为型自定义实现（ralph-wiggum / agent-sdk-dev，按需取舍）。

### 18.5 待办

- [ ] 阶段 0：#4 HookDispatcher + WakeQueue（代码级骨架）
- [ ] 阶段 0：#1 AgentAssetRegistry + frontmatter 解析 + 热加载
- [ ] 阶段 A：A 类 5 个插件格式转换 + 工具映射
- [ ] 阶段 B：B 类 4 个插件 `gh`/`CLAUDE.md` 适配 + tools 映射
- [ ] 阶段 C：hookify / security-guidance 的 py → #5 规则翻译
- [ ] 阶段 D：ralph-wiggum / agent-sdk-dev 按需实现
- [ ] 按资产同步纪律更新 `docs/modules/` 与 `assets/docs/`

### 18.6 设计状态

✅ 插件移植计划定稿（2 个决策点已确认），实施依赖阶段 0 容器。

## 19. 实施记录

- （待定，按讨论结论逐项补充）
