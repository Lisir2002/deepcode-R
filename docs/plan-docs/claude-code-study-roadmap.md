# R-CodeCore Claude Code 借鉴 — 落地优先级规划（Roadmap）

> 📝 草案（执行清单）
>
> 设计依据：[claude-code-study-design.md](./claude-code-study-design.md)（12 节设计定稿 + 13 条决策记录，含 10 个借鉴方向 + 2 个延伸方向）
> 执行纪律：对齐 AGENTS.md——编译型代码改动先 `./gradlew :app:assembleDebug`；push 前 `./gradlew :app:testReleaseUnitTest`；资产同步纪律（prompts / docs / strings.xml / 模块文档）
> 使用方式：按 P0 → P4 顺序**逐条执行**；每条完成后在「完成记录」勾选并简述验证结果；新发现的问题在「变更记录」追加，不跳级

## 1. 规划总览

- **目标**：把 10 个借鉴方向 + 2 个延伸方向（插件五件套复用、13 插件移植）按依赖与价值落地到 R-CodeCore。
- **排序原则**：依赖驱动（容器 → 安全 → 智能 → 网络后台 → 插件化）+ 每条可独立验证（编译 / 单测 / 冒烟）+ 最小改动。
- **阶段划分**：

| 阶段 | 主题 | 任务 | 核心方向 |
|---|---|---|---|
| P0 | 容器与地基 | R01–R04 | #4 Hook 骨架、#1 agent 资产系统 |
| P1 | 安全底线 | R05–R10 | #6 危险命令、#5 规则引擎、#7 权限分级 |
| P2 | 编排与智能 | R11–R14 | #2 多 Agent 编排、#3 置信度、#9 子代理 |
| P3 | 网络与后台 | R15–R17 | #8 网络限制、#10 后台任务 |
| P4 | 插件化与移植 | R18–R25 | #17 插件化 + 13 插件移植 |

## 2. 详细任务清单

### P0 容器与地基

#### R01 Hook 分发骨架（#4 代码级）

- **内容**：新增 `HookDispatcher`（multibinding，同 `SlashCommandHandler` 模式）+ 5 事件接口（PreToolUse / PostToolUse / UserPromptSubmit / Stop / SessionStart，各带上下文参数）；复用 ZTH preTool/postTool 挂点挂载。
- **依赖**：无。
- **完成标准**：`./gradlew :app:assembleDebug` 通过；`HookDispatcher` 单测（事件分发 / 异常隔离）；挂 1 个示例 hook（提交前纪律检查）。
- **关联**：design 第 11 节（11.3/11.5）。

#### R02 WakeQueue 骨架（#4 唤醒，供 #10 复用）

- **内容**：WakeQueue Room 表 + DAO（wake_id/source/type/content/status/createdAt）；Stop/PostToolUse 后台审查结果持久化；下一轮开始时注入 system-reminder + 原子化消费确认（防重复/防丢失）。
- **依赖**：R01。
- **完成标准**：assembleDebug 通过；WakeQueue 单测（写入/注入/消费/失败保留）；真机会话冒烟（后台审查下轮唤醒）。
- **关联**：design 第 11 节（11.3 asyncRewake 下轮注入）、第 16 节（16.2 统一队列）。

#### R03 AgentAssetRegistry + frontmatter 解析（#1 容器核心）

- **内容**：新增 `AgentAssetRegistry`——扫描 `prompts/` + `prompts.custom/`，解析 frontmatter（name/description/order/enabled/agent/mode/tools/model/includes），按 order 排序；includes 组合引用；热加载双机制（mtime 懒刷新 + FileObserver 兜底）；区分主 agent 组件（agent:false）与专项 agent（agent:true）。
- **依赖**：无（frontmatter 解析复用 `SkillParser` 风格 + SnakeYAML 2.2，依赖已在）。
- **完成标准**：assembleDebug 通过；解析/排序/includes/热加载失效单测；`StaticRuleSource` 改读 registry。
- **关联**：design 第 8 节（8.2/8.3/8.4）。

#### R04 prompt 资产迁移（#1 落地）

- **内容**：现有 11 个 prompt 文件加 frontmatter；`StaticRuleSource` 从 registry 读取（删硬编码片段列表）；`PlanModeSource`/`AutoModeSource` 由 `mode` 字段统一并删除两个特殊 Source；`AgentContext` 加 `currentAgentId`；`SlashCommandRegistry` 新增 `/agent <name>` 命令 + UI 切换入口（tools/model 仅建议/提示）。
- **依赖**：R03。
- **完成标准**：assembleDebug 通过；会话冒烟（默认主 agent 正常、`/agent` 切换专项 agent 生效、切换后系统提示词替换）；`docs/modules/`（prompt 装配）与 `assets/docs/` 同步。
- **关联**：design 第 8 节（8.5/8.6/8.7）。

### P1 安全底线

#### R05 DangerousCommandGuard（#6 核心）

- **内容**：新增 `feature/agent/domain/permission/DangerousCommandGuard.kt`——`isBlocked(command)`（B1 RCE 管道 / B2 系统目录权限 / B3 设备磁盘 / B4 关机杀进程，优先用 `ShellCommandParser` 段级 token 判定）+ `warnMessage(command)`（W1–W11 合并提示块）；`parseChmodInfo` 辅助（复用 sysDirs）；B3 三条子判定误报防护。
- **依赖**：无。
- **完成标准**：assembleDebug 通过；单元测试覆盖 4 类 Block + 4 类 Warn + 误报样本（`chmod 777 工作区文件`、`curl -O`、`ls /dev/`、`pkill nginx`、`cat /dev/sda`）。
- **关联**：design 第 7 节（7.2/7.3/7.5）。

#### R06 #6 双层接入（#6 落地）

- **内容**：`ToolPermissionPolicyEngine.evaluateShell` 在灾难 rm 后插入 Block（顺序 = Block → 灾难 rm → DENY → 白名单 → 记忆 ALLOW → ASK；AUTO 分支同步加）；`ExecuteCommandTool` 开头加 Block 硬拦截兜底；Warn 统一合并提示通道（权限卡 details + 输出末尾，与 BusyBox 合并，一条命令一次展示）；terminal(start) 双层覆盖。
- **依赖**：R05。
- **完成标准**：assembleDebug 通过；单测（AUTO 拦截 / 合并提示 / 用户规则不能放宽）；容器冒烟（`curl | sh` 被拦、`pkill -9 nginx` 放行+提示）。
- **关联**：design 第 7 节（7.2 接入点 / 7.5 第 2-6 条）。

#### R07 #6 资产同步

- **内容**：`assets/prompts/` 命令纪律同步（Bash 工具描述标注危险命令行为）；`assets/docs/` Bash 行为变化说明；`docs/modules/agent` 记录守卫体系。
- **依赖**：R05/R06。
- **完成标准**：文档与实际行为核对一致；无硬编码中文文案（字符串走 strings.xml）。
- **关联**：AGENTS.md 资产同步纪律。

#### R08 RuleEngine + MD 规则层（#5 核心）

- **内容**：`Rule(name, enabled, event, tool_matcher, conditions[], action(allow|deny|warn), message)` + `Condition(field, operator, pattern)`（regex_match/contains/equals/not_contains/starts_with/ends_with）；MD 规则层（项目 `.rcodecore/rules/*.md` + 全局，frontmatter + 正文，可 git 追踪）+ mtime 热加载；frontmatter 解析公共工具（复用 R03）。
- **依赖**：R03（frontmatter 工具复用）。
- **完成标准**：assembleDebug 通过；RuleEngine 单测（六运算符 / deny 优先 warn / matcher 过滤）；示例规则加载生效。
- **关联**：design 第 12 节（12.3/12.5）。

#### R09 #5 接入权限引擎 + Hook 双消费点（#5 落地）

- **内容**：接入 `ToolPermissionPolicyEngine.evaluateShell/evaluateGeneric`（deny 最高优先、warn 附加提示）；接入 R01 `HookDispatcher`（事件规则消费，条件/动作声明式化）；优先级链 = 内置安全底线 > 用户 deny/block > 记忆 ALLOW > 白名单 > warn > ASK。
- **依赖**：R08/R01。
- **完成标准**：assembleDebug 通过；单测（deny 拦记忆 ALLOW、warn 不拦只提示、Hook 事件规则生效）。
- **关联**：design 第 12 节（12.3 双消费点 / 5.7 优先级）。

#### R10 deny.json + 禁绕过开关（#7 落地）

- **内容**：`.rcodecore/deny.json`（项目 + 全局）管理级强制 deny 最高优先；settings 安全设置页「禁绕过」开关（禁切 AUTO + 禁新「始终允许」记忆，DataStore 持久化）；AUTO 入口禁用 + 存量会话降级提示。
- **依赖**：R09（优先级链）。
- **完成标准**：assembleDebug 通过；单测（deny 文件拦已记忆 ALLOW）；UI 冒烟（开关生效、AUTO 禁用、弹窗无「始终允许」）。
- **关联**：design 第 13 节（13.2/13.4）。

### P2 编排与智能

#### R11 workflow 资产类型 + 编排执行器（#2 核心）

- **内容**：`#1` 资产体系新增 `workflow` 类型（阶段列表/触发条件/引用专项 agent）；编排执行器解析 workflow 按阶段顺序执行（单 agent 顺序扮演）；变更类型分类器（git diff → 路径/后缀分类：test/注释/错误处理/新类型/通用）。
- **依赖**：R03/R04。
- **完成标准**：assembleDebug 通过；分类器单测（各类型识别）；workflow 解析单测。
- **关联**：design 第 9 节（9.3/9.5）。

#### R12 专项 agent + 工作流命令（#2 落地）

- **内容**：`code-reviewer` / `code-architect` / `code-explorer` 专项 agent 资产（#1 的 agent:true）；`/code-review`、`/feature-dev` 命令（#1 资产形式）；输出分级聚合（Critical/Important/Suggestions + 行动方案）。
- **依赖**：R11。
- **完成标准**：assembleDebug 通过；会话冒烟（/code-review 按变更类型派发、输出分级）；`assets/prompts/` 与 `assets/docs/` 同步。
- **关联**：design 第 9 节（9.3/9.5）。

#### R13 Confidence Scoring 纪律（#3 落地）

- **内容**：code-reviewer 等专项 agent 正文内嵌 Confidence Scoring 规范（五档锚点 0/25/50/75/100 + 只报 ≥80 + Critical/Important 分组标注置信度），随 R12 资产走。
- **依赖**：R12。
- **完成标准**：会话冒烟（审查输出分级 + 置信度标注，低置信不报）；`assets/prompts/` 同步。
- **关联**：design 第 10 节（10.3/10.5）。

#### R14 fork 工具 + 预算控制（#9 骨架）

- **内容**：fork 工具（新建 subagent 会话 + 派发任务简报，type 标记）；预算控制（最大并发 + 单 subagent 轮数/API 次数/超时，settings 可配，默认并发 1）；subagent 结果回收与摘要返回主会话（衔接 #3）。
- **依赖**：R01/R03（会话落库 + 资产）。
- **完成标准**：assembleDebug 通过；单测（预算硬上限 / 并发限制）；会话冒烟（fork 子代理跑独立会话并返回摘要）。
- **关联**：design 第 15 节（15.2/15.4）。

### P3 网络与后台

#### R15 allowedDomains 网络限制（#8 落地）

- **内容**：allowedDomains 两级配置（全局 DataStore + 项目 `.rcodecore/network.json`）；mihomo 规则注入（白名单放行 + MATCH→REJECT，mode=rule）；settings 网络代理页编辑入口；默认关。
- **依赖**：无（mihomo 内核已有）。
- **完成标准**：assembleDebug 通过；容器冒烟（白名单域放行、域外 REJECT）；`assets/docs/`（网络限制说明，诚实标注 HTTP(S) 范围）与 `docs/modules/` 同步。
- **关联**：design 第 14 节（14.2/14.4）。

#### R16 system-reminder 注入机制（#10 核心）

- **内容**：下轮会话开始前拼接 system-reminder（复用 R02 WakeQueue 队列承载耗时任务结果）+ 消费确认；Room 持久化 + App 被杀后重扫待注入队列。
- **依赖**：R02。
- **完成标准**：assembleDebug 通过；单测（队列重扫 / 注入消费）。
- **关联**：design 第 16 节（16.2/16.4）。

#### R17 终端/容器/MCP 后台化（#10 落地）

- **内容**：终端长命令 detach 到会话后台（TerminalKeepaliveService 保活）+ 完成写唤醒队列；容器下载/安装完成唤醒；MCP server 处理完成通知；后台并发预算用户可配置（默认 1，对齐 #9）。
- **依赖**：R16。
- **完成标准**：assembleDebug 通过；冒烟（终端长命令后台跑 + 完成唤醒提示）；`docs/modules/`（agent/terminal）同步。
- **关联**：design 第 16 节（16.2/16.4）。

### P4 插件化与移植

#### R18 插件化基建（#17 落地）

- **内容**：plugin.json 元数据模型 + 解析；zip/目录导入 + 结构校验；复用 ZthContentReviewer 导入内容审查（有风险标记警告）；hooks → #5 规则翻译器；插件资产 vs 内置资产覆盖关系；marketplace 协议预留扩展点（本期不实现）。
- **依赖**：R01/R03/R08。
- **完成标准**：assembleDebug 通过；单测（导入校验 / Zth 审查拦截 / hooks 翻译）；冒烟（导入一个测试插件资产生效）。
- **关联**：design 第 17 节（17.2/17.4）。

#### R19 阶段 A：纯资产插件移植（5 个）

- **内容**：commit-commands、frontend-design、claude-opus-4-5-migration、explanatory-output-style、learning-output-style——frontmatter 格式转换 + 工具映射（A 类轻改）。
- **依赖**：R18。
- **完成标准**：各插件资产可加载生效；会话冒烟（/commit、frontend-design skill 触发）；`assets/prompts/` 与 `assets/docs/` 同步。
- **关联**：design 第 18 节（18.2 阶段 A）。

#### R20 阶段 B：编排型插件移植（4 个）

- **内容**：feature-dev、pr-review-toolkit、code-review、plugin-dev——`gh`→本地 git、`CLAUDE.md`→项目规则、tools 映射（B 类适配）。
- **依赖**：R12/R18。
- **完成标准**：各编排命令/专项 agent 生效；会话冒烟（/feature-dev、/review-pr 派发）；`assets/prompts/` 与 `assets/docs/` 同步。
- **关联**：design 第 18 节（18.2 阶段 B）。

#### R21 阶段 C：hook 型插件翻译移植（2 个）

- **内容**：hookify、security-guidance——Python hook（4+11 个 py）翻译为 #5 规则引擎 MD 规则（C 类翻译）。
- **依赖**：R08/R09/R18。
- **完成标准**：翻译后规则加载生效；冒烟（security-guidance PreToolUse 模式告警、Stop diff 审查触发）；`assets/docs/` 同步。
- **关联**：design 第 18 节（18.2 阶段 C）。

#### R22 阶段 D：行为型插件按需实现（2 个）

- **内容**：ralph-wiggum（Stop hook 循环控制）、agent-sdk-dev（对 R-CodeCore 低价值，按需取舍）。
- **依赖**：R01/R09。
- **完成标准**：按取舍实现或标记「不移植」并说明理由。
- **关联**：design 第 18 节（18.2 阶段 D）。

## 3. 执行纪律（对齐 AGENTS.md）

1. 编译型代码（`.kt`/`.gradle.kts`/`AndroidManifest.xml`）改动，提交前先 `./gradlew :app:assembleDebug` 验证。
2. 任何 `git push` 前先 `./gradlew :app:testReleaseUnitTest` 且全部通过。
3. 资产同步纪律：prompts / docs / strings.xml（中文 + 英文）/ 模块文档四类变更必须同步。
4. UI 中文文案一律走 strings.xml，禁止 .kt 硬编码。
5. 涉及数据库 schema 变更（如 R02/R16 WakeQueue 表）按迁移纪律执行（递增版本 + `assets/migrations/{V}_*.sql`，字符串字面量勿用 `;`）。
6. 新功能/复杂多文件改动按需拉 `feat/*` 分支，验证后合回 main。
7. 每条完成后在「完成记录」勾选；发现设计缺口的先补设计再实施，不跳级。

## 4. 完成记录

| 任务 | 状态 | 完成日期 | 验证结果 |
|---|---|---|---|
| R01 Hook 分发骨架 | ⬜ | — | — |
| R02 WakeQueue 骨架 | ⬜ | — | — |
| R03 AgentAssetRegistry | ⬜ | — | — |
| R04 prompt 资产迁移 | ⬜ | — | — |
| R05 DangerousCommandGuard | ⬜ | — | — |
| R06 #6 双层接入 | ⬜ | — | — |
| R07 #6 资产同步 | ⬜ | — | — |
| R08 RuleEngine + MD 规则 | ⬜ | — | — |
| R09 #5 双消费点 | ⬜ | — | — |
| R10 deny.json + 禁绕过 | ⬜ | — | — |
| R11 workflow 资产 + 编排执行器 | ⬜ | — | — |
| R12 专项 agent + 工作流命令 | ⬜ | — | — |
| R13 Confidence Scoring 纪律 | ⬜ | — | — |
| R14 fork 工具 + 预算控制 | ⬜ | — | — |
| R15 allowedDomains 网络限制 | ⬜ | — | — |
| R16 system-reminder 注入机制 | ⬜ | — | — |
| R17 终端/容器/MCP 后台化 | ⬜ | — | — |
| R18 插件化基建 | ⬜ | — | — |
| R19 阶段 A 插件移植 | ⬜ | — | — |
| R20 阶段 B 插件移植 | ⬜ | — | — |
| R21 阶段 C 插件翻译 | ⬜ | — | — |
| R22 阶段 D 插件按需实现 | ⬜ | — | — |

## 5. 变更记录

- （2026-08-23）规划文档创建：P0–P4 五阶段、R01–R22 任务清单，依据 design 12 节定稿。
