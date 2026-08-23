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

- **批次 1**：#6 Bash 命令静态预校验（设计定稿，见第 7 节）
- **批次 2**：#3 Confidence Scoring 分级上报
- **批次 3**：#4 Hook 事件模型 + #5 声明式规则引擎（强耦合，成对讨论）
- **批次 4**：#1 Agent 声明式定义（设计定稿，见第 8 节；用户 2026-08-23 提前拍板）→ #2 多 Agent 编排
- **批次 5**：#7 权限分级（ask/deny + 禁绕过）/ #8 Sandbox 网络限制 / #10 后台任务 + system-reminder 唤醒
- **批次 6**：#9 子代理 fork 继承上下文 + 并发/预算上限（重点讨论 Android 资源与上下文约束）

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
5. **Warn 展示整合**：`DangerousCommandGuard.warnMessage(command)` 与 `BusyBoxCompatibilityGuard.warningMessage` 合并成同一提示块，在 `buildPermissionRequest`（权限卡 details）与 `appendHint`（输出末尾）两处合并调用。
6. **terminal(start) 双层覆盖**：权限引擎层覆盖全部 shell 工具（含 `terminal action=start` 常驻会话）；工具入口硬拦截（`ExecuteCommandTool`）只兜 Bash。

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

## 9. 实施记录

- （待定，按讨论结论逐项补充）
