# Changelog

本文件记录 R-CodeCore 各版本的用户可见变更，采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格。版本号采用四段式 `x.x.x.x(-rcN)`（从 `0.0.0.1` 迭代），完整规则见 [docs/versioning.md](./docs/versioning.md)。

- **用户日志**：本文档面向用户，只记录用户可感知的变化；内部实现细节（重构、DB 迁移、测试）不在此展开。
- **开发者日志**：各模块内部演进见 `docs/modules/<module>.md` 的「版本演进记录」章节。
- **AI 日志**：面向协同 AI 的结构化版本日志见 `app/src/main/assets/prompts/90-version-log.md`。
- **生成机制**：发版时从 `git log`（Conventional Commits）汇总初稿并人工润色后写入，见 `AGENTS.md`「发版流程」。版本号由 Git Tag 动态推导，代码中不手写。

## 分类约定（六类）

| 分类 | 英文 | 含义 | 典型判断 |
| --- | --- | --- | --- |
| 新增 | `Added` | 全新功能、能力、页面、工具 | 以前没有，现在有 |
| 改进 | `Improved` | 在现有能力上优化体验 / 性能 / 质量 | 行为不变，更好用 |
| 修复 | `Fixed` | 纠正错误行为、Bug、崩溃 | 把不正确的改成正确的 |
| 变更 | `Changed` | 行为 / 语义变化（含结构调整、不兼容变更） | 现有行为被改变 |
| 删除 | `Removed` | 移除功能、能力、兼容支持 | 以前有，现在没了 |
| 调整 | `Adjusted` | UI / 文案 / 参数等细微调整 | 外观措辞微调，不改核心行为 |

> 同一处变更可能同时命中多类时，按「新增 > 变更 > 调整」就近归入最显著的一类；模块文档「版本演进记录」保持叙述式，不强制分类。

格式约定：

- `[Unreleased]`：尚未发布的变更。
- 每个版本条目含版本号、发布日期；变更按上述六类分组，顺序固定为 新增 → 改进 → 修复 → 变更 → 删除 → 调整。

---

## [v0.0.0.1-rc6] - 2026-09-03

上溯审计「新版数据层结构本身是否正常」：SQLDelight DDL 中 `agent_message` 明确含 `id` 主键（22 列与插入语句完全对应），全代码库仅 `agent.sq` 与自愈器两处建表且结构一致，「全新库 → schema.create」必然带 `id` 列。因此反复出现 `no such column: agent_message.id`，根因指向**历史坏库「user_version 已对齐 target」被 MigrationEngine 判为 no-op，跳过了修复路径**。rc6 把它强制拉回迁移路径，并给崩溃快照加指纹以在当场定案。

### Added（新增）

- **崩溃日志指纹（一锤定音定位）**：崩溃快照头部新增三行标记 `version=`（当前安装版本）、`schemaVersion=AGENT-v3`（本次 schema 目标版本）、`agentPreheatRan=true/false`（本进程是否已执行过 AGENT 库预热自愈）。复现后仅凭这三行即可判定「跑的是不是含修复代码的包、自愈有没有执行」，终结此前的版本扯皮。

### Fixed（修复）

- **schema 升至 v3 强制迁移，堵死「版本对齐即当表结构正确」的根因**：AGENT 域新增 `2.sqm` 迁移标记，把历史中「user_version 已对齐 target → MigrationEngine no-op」的坏库强制拉进迁移路径（schema.version 由 2 升 3）。坏库在进入迁移后由既有 `SchemaSelfHealer` 按列交集无损重建 `agent_message`（缺 `id` 主键即 UUID 回填重建），保证打开即不再缺失关键列。迁移文件只放置幂等 marker 表、不依赖任何业务表列，确保迁移恒可成功、不因缺列中途崩溃。

---

## [v0.0.0.1-rc5] - 2026-09-03

rc4 修复了 `ConnectionPool` 打开配套的 schema 对齐 + 自愈时机，但仍依赖「首个查询方触发」。若某条启动路径同时读取了 AGENT 库结构但跳过了自愈，或自愈执行的连接与报错查询不一致，仍可能在上一个 rc（rc4，安装后添加模型发送第一条消息）复现 `no such column: agent_message.id`。rc5 改为**彻底消除时机不确定性**。

### Fixed（修复）

- **启动即无条件预热 AGENT 数据层，结构自愈铁定先于任何 UI 查询**：在应用 `onCreate` 的最早同步阶段，无条件触发一次 AGENT 库的连接池打开。无论哪个 ViewModel / 数据门面何时查询，`ConnectionPool.onOpened` 都会在返回前完成 `ensureSchema` + `SchemaSelfHealer` 幂等自愈（缺 `id` 列即无损重建）。正常库仅毫秒级幂等检查，缺失才重建，开启会话页即不再因表结构缺失而崩溃。
- **自愈异常改为语义明确的崩溃日志**：若自愈仍失败，进程以「自愈自定义错误」崩溃，崩溃快照直接指向缺列与表结构现场，不再只是毫无信息的 `no such column`，便于继续定位。

---

## [v0.0.0.1-rc4] - 2026-09-03

彻底堵死结构自愈竞态窗口（bug 修复，仅迭代 rc 后缀）。rc3 版本自愈代码虽已存在，但因 `DataRegistryModule.provideDataProviders` 可能先于 `provideAgentDb` 触发 `ConnectionPool.driver(AGENT)`，导致 AndroidSqliteDriver 的 SQLiteOpenHelper 自动跑完 `schema.create/schema.migrate` 把 user_version 对齐后，provideAgentDb 的 ensureSchema 走 no-op 分支，自愈代码可能来不及在业务查询前修复缺列表，最终在查询 `agent_message.id` 时崩溃。

### Fixed（修复）

- **ConnectionPool 打开即对齐 schema + 自愈**：在 `ConnectionPool.driver()` 首次创建 SqlDriver 后、返回给任何调用者之前，立刻注入的 `onOpened` 回调会对该库跑 `MigrationEngine.ensureSchema`，对 AGENT 库额外跑完整的 `SchemaSelfHealer` 自愈 + 保证性复核。`DataRegistryModule`、`provideAgentDb` 等任何先拿到 driver 的调用方，拿到的都已是表结构完整的库。
- **自愈/迁移全节点 FileLogger 诊断加固**：`SchemaSelfHealer`（healAgentSession / healAgentMessage / healTable 每一步：existing 列、missing 列、RENAME / CREATE / INSERT / DROP、自愈后列清单）与 `MigrationEngine.ensureSchema`（每库 current / target 版本号与分支决策）均输出结构化日志。rc4 安装后首次运行即可在崩溃快照里看到自愈完整执行轨迹，彻底消除「自愈跑没跑 / 跑了没修好」的盲区。

---

## [v0.0.0.1-rc3] - 2026-09-03

进一步加固历史库升级自愈的健壮性（bug 修复，仅迭代 rc 后缀）。

### Fixed（修复）

- **自愈过程防中断残留**：若上次结构自愈在重命名旧表后意外中断，本次启动会自动清理遗留的临时表并继续完成重建，避免因残留表撞名导致自愈永久失败、再次触发 `no such column: agent_message.id` 崩溃。

---

## [v0.0.0.1-rc2] - 2026-09-03

修复 rc1 未能完全兜底的历史库崩溃问题（bug 修复，仅迭代 rc 后缀）。

### Fixed（修复）

- **数据库自愈加强复核**：数据层在打开时对 `agent_session` / `agent_message` 的结构自愈增加**强制校验**——自愈完成后再次确认主键列存在，若仍未就位立即重试重建，彻底杜绝 `no such column: agent_message.id` 类崩溃；极端异常也会给出明确错误而非难懂的 SQL 报错。

### Changed（变更）

- **升级建议**：从旧版本升级的 Android 设备若已装过 rc1 或早期包，请**先卸载再安装** rc2，确保走到新的自愈逻辑。

---

## [v0.0.0.1-rc1] - 2026-09-03

首个预发行版，承接数据层全量重构 #4（数据层迁移等工作）并刷新版本号体系（四段式，从 `0.0.0.1` 重新迭代）。

### Added（新增）

- **DNS 解析兜底**：模型接口因系统 DNS 解析失败而连接不上时，自动回退到公共 DNS（223.5.5.5 等）完成解析，规避「Unable to resolve host」导致的无法添加 / 拉取模型问题。
- **数据库结构自愈**：数据层增加幂等自愈能力，自动修复历史库「版本号相同但表结构不一致」导致的崩溃（如 `no such column: agent_message.id`），升级整个过程无感、可重复执行。

### Improved（改进）

- **数据层全面接管 SQLite**：核心数据统一由 SQLDelight 持久化，构建更稳、运行更可预期。

### Fixed（修复）

- **修复崩溃**：`no such column: agent_message.id` 引发的启动崩溃。
- **修复模型连接失败**：DeepSeek 等自定义模型接口因 DNS 解析失败无法添加 / 拉取。

### Changed（变更）

- **版本号体系升级为四段式**：`x.x.x.x(-rcN)`，从 `0.0.0.1` 开始迭代（Bug 修复仅迭代 rc 后缀；新增 / 删除功能正式版 D 段 +10；框架重构正式版 C 段 +1 且 D 段归零）。

---

## [v0.3.1] - 2026-08-26

### Adjusted（调整）

- **错误提示横幅折叠展示**：对话页红色错误提示默认仅显示一行（超长省略号截断），点击横幅即可展开查看完整详情、再次点击收起，避免长错误信息占据聊天区空间。

## [v0.3.0] - 2026-08-26

### Added（新增）

- **空转收敛开关**：「规范流程」二级页新增「空转收敛」子开关（默认关闭）。开启后 AI 连续多轮无实质产出（未写文件/未执行命令/未读到新信息）会自动结束当前回合；默认关闭避免 AI 在做搜索/浏览类任务（无代码产出）时被误判为"空转"而提前结束。
- **崩溃与内存自愈防线（预防闸门）**：内存临界时自动释放浏览器页面快照缓存 + 对所有域库执行 WAL checkpoint（缩小低内存杀进程后的数据库损坏窗口）；下次启动自动诊断上次是否因内存压力被系统静默回收并留痕，让「模型输出时闪退却无日志」这类问题自动留下排查证据。

## [v0.3.0-rc3] - 2026-08-26

### Added（新增）

- **内置浏览器升级（需求一「更像浏览器」用户侧 + 需求二「适配大模型」模型侧）**：
  - 用户侧（R1）：
    - **历史记录**：访问历史持久化（最近 200 条、同 URL 去重），「更多 → 历史记录」列表回跳 + 清空；无痕模式不记录。
    - **收藏夹**：书签增删查，「更多 → 收藏本页/取消收藏」与「更多 → 收藏夹」管理，新标签页主页快捷入口。
    - **新标签页/主页**：空标签展示搜索框 + 最近访问 + 收藏夹快捷入口。
    - **页内查找**：查找条实时高亮 + 上一个/下一个切换 + 关闭清除。
    - **下载管理 UI**：下载任务面板（列表/打开（FileProvider）/重试/清除）。
    - **凭据管理 UI**：已保存登录凭据列表，密码默认打码、点击眼睛显示明文、删除需确认。
    - **分享/复制链接**：「更多」菜单动作。
    - **无痕模式 / 桌面版切换 / 缩放控制**：会话级开关，桌面 UA 重载，textZoom 50–200% 步进调整 + 重置。
  - 模型侧（R2）：
    - **快照分级**：`summary`（默认，最省 token）/ `standard` / `full` 三级，由 `snapshot_level` 参数控制。
    - **增量 diff**：写操作自动返回 `delta`（新增/变化/消失元素 + 文本变化摘要）做前后对比，无需反复 snapshot 轮询。
    - **按需取文**：新增 `page_text` 动作单独取正文。
    - **三级元素定位**：`element_id` 接受 `data-rcb-id` / CSS 绝对路径 / 语义描述符三者任一，SPA 重渲染后 id 失效自动按 CSS 路径/语义兜底。
    - **事件驱动等待**：新增 `wait_for_change`（MutationObserver + 网络事件驱动，替代轮询）；新增 `history` 动作历史查询。

### Improved（改进）

- **统一动作 envelope**：所有浏览器动作返回 `{ok, action, changed, summary, note|error, recoverable, snapshot?, delta?}`，写操作自动验证变化，错误带可恢复建议，模型操作心智负担显著降低。
- **可操作性标注 + 自动滚动**：快照元素标注 `in_viewport/visible/needs_scroll/overlapped`，点击/输入前自动 `scrollIntoView` 居中，消除「点了没反应」。

### Changed（变更）

- 浏览器工具返回结构统一为 envelope（干净替换，无旧结构兼容层），模型 prompt / 工具描述 / 模块文档已同步更新。

### Fixed（修复）

- **修复版本号漂移**：`BASE_VERSION` 0.2.0 → 0.3.0。此前版本基线停留在 0.2.0 但已发布 v0.3.0-rc1/rc2，导致产物 versionName 错误回退为 `0.2.0-dev`（实测 v0.3.0-rc2 APK versionName=`0.2.0-dev`）。

## [v0.3.0-rc2] - 2026-08-26

### Added（新增）

- **AI 工作流规范体系 D0-D6 全量落地**（相对 v0.2.0 的完整功能增量，主体经占位提交并入，进度见 `docs/plan-docs/progress-tracker.md`）：
  - D0 用户意图拆解：问判模式、行为模式、GoalStale/GoalAdjustEvent 注入（承上，v0.3.0-rc1 已含）。
  - D1 Agentic Workflow 基座：step 前注入、ToolGuard 链式护栏、统一开关（承上，v0.3.0-rc1 已含）。
  - D2 思维链路 + 步骤结果汇总：空转软收敛（连续 N 轮无实质产出强制结束）、推理预算（流式呈现 + 消耗追踪）、运行轨迹表、用量卡片（本回合增量 + 会话累计）。
  - D3 分层规则纪律：全局/项目/工作区/模块四级规则资产 + 显式 priority + 模块级按需注入（基于文件观察命中判断）。
  - D4 SOP 标准作业：6 份 SOP 资产（发版/迁移/资产同步/提交/排障/行为规范）+ `loadSop` 工具 + 摘要常驻注入。
  - D5 Playbook + 子代理：剧本资产与执行引擎、4 个剧本工具 + `/playbook` 命令、子代理 spawn/fork 双模式、`playbook_auto` 自动触发子开关。
  - D6 Spec 规范驱动：设计文档评审状态行 pre-commit 预检（`spec-check.sh`）、配套性触发、SOP 同步提示。
- 新增内置技能 `coding-preflight`（编程前准备，与 pre-commit-health 形成「开工前 → 编程 → 提交前」闭环）。

### Improved（改进）

- 文档审计修缮：AGENTS.md / README / 模块文档 / SOP 与代码对齐（agent 库 v4、32 表、迁移链等）。

### Fixed（修复）

- 修复 playbook / SOP / 轨迹等子开关在设置页「规范流程」二级页的说明缺失。

### Removed（删除）

- 仓库整理：移除迁移模拟脚本、演示站点、调研临时目录与调试数据库等杂项，仓库仅保留源文件与必要文档。

## [v0.3.0-rc1] - 2026-08-25

### Added（新增）

- **D0 用户意图拆解基座**：问判模式（Ask/Plan/Build/Auto）与行为模式注入。
- **D1 Agentic Workflow 基座**：`step` 前注入链、`ToolGuard` 链式护栏（PASS/BLOCK/ADVISORY）、六段式工具流水线契约、统一开关基座。

> 说明：v0.3.0 系列为「AI 工作流规范体系」主线，RC 期间功能持续演进，正式转正版本建议在真机验证 AI 对话 / 终端 / 容器启动三条主线后由 `v0.3.0` 承接。

## [v0.2.0] - 2026-08-25

### Added（新增）

- **任务编排协作**：Goal/Plan/Job/Schedule 任务编排层与声明式扩展生态。
- **回复气泡样式**：四款气泡样式与设置切换；消息流淡彩色线分层、用户消息右侧气泡。
- **危险命令静态守卫双层拦截**：Shell 静态分析 + 内置只读白名单。
- **工作区入口迁移到侧边栏**：支持文件浏览与独立阅读页。
- `/agent` 专项切换：prompt 资产迁移后新增。

### Improved（改进）

- **网络层性能优化**：连接预热（DNS+TCP+TLS 预建）、SSE 定点解析（不建树）、模型 host 直连分流。
- **流式累积归一化**：防止流式全量重发放大。
- **长输出分块存储**：杜绝超长输出导致的无声闪退。

### Fixed（修复）

- 终端启动字段初始化顺序导致的 NPE 闪退。

### Adjusted（调整）

- 对话列表左滑删除背景缩进、消息层级视觉细节。

## [v0.1.7] - 2026-08-23

### Fixed（修复）

- 设置页与关于页深色模式白底改主题语义色。

### Adjusted（调整）

- 侧边栏底部改两图标贴右，能力中心入口移入设置页。

## [v0.1.6] - 2026-08-23

### Changed（变更）

- 侧边栏重构为 tab 结构（对话列表 / 工作目录 / 更多配置）。

## [v0.1.5] - 2026-08-23

### Adjusted（调整）

- 侧边栏底部改为图标按钮，浏览器入口换成主题循环切换。

## [v0.1.1 - v0.1.4] - 2026-08-22 ~ 2026-08-23

### Fixed（修复）

- 设置页与关于页深色模式白底改主题语义色。

### Adjusted（调整）

- 设置页保活图标 Pulse 改 Favorite。

## [v0.1.0] - 2026-08-22

首个正式版（含 v0.1.0-rc20 ~ rc196 高频迭代的收敛成果）。

### Added（新增）

- **AI 编程核心**：支持 Anthropic（Claude）/ OpenAI（GPT）/ Gemini 多 Provider；20+ 内置工具（文件读写编辑、Shell 执行、终端管理、网页搜索/抓取、图片生成、MCP 管理等）；流式输出、上下文压缩、多会话管理、PLAN/BUILD/AUTO 执行模式；文件修改前自动检查点快照与回滚。
- **内置终端与容器**：Termux 组件 + PRoot Alpine Linux 容器，完整命令行环境；后台常驻、多标签管理、7 个内置功能包（Python/Node/Git/Bash/rg/网络工具/QEMU x86 转译）。
- **远程 SSH 执行后端**：exec channel 执行命令、文件读写、shell channel 终端，自动重连与状态指示。
- **网络代理**：mihomo 内核接入、订阅解析、独立节点管理页（分组/节点/状态/测速/切换）、实时流量视图、代理感知模型元数据拉取。
- **MCP 协议**：客户端（本地 stdio / 远程 HTTP 动态扩展工具）+ 内置 MCP 服务器（Streamable HTTP，客户端+服务器双角色，Bearer 鉴权 + SSE）。
- **技能中心 v2**：作用域分级、自动触发、导入导出、查看/编辑、对话级控制；内置技能 pre-commit-health（提交前规范体检）。
- **备份与恢复**：AES-256-GCM 加密、数据保全三层防线（防历史对话丢失）、外部备份轮转。
- **虚拟环境支持**：模拟器/虚拟机单包通用（arm64-v8a + x86_64 双 ABI 按宿主选择容器）。
- **Git 集成**：可视化状态/分支/提交/标签/拓扑图，三端凭据统一管理。
- **ZTH 零信任防护**：七层权限评估、DB-SHIELD 持久化护盾（含历史对话空修复）、多模态模型兼容策略。
- **对话体验**：Markdown 渲染、文件修改聚合弹窗（TOOL 片段折叠 + diff 查看）、连续相同工具调用聚合面板、消息流分层着色。
- 内置浏览器（WebView 会话、登录接管、动态数据捕获）。

### Changed（变更）

- 全量硬编码中文字符串国际化（中英双语适配）。

### Removed（删除）

- 移除中英双语切换，仅保留中文（v0.1.0 后续 RC 收敛）。

---

> 早期迭代版本（v0.1.0-rc20 之前）无 Tag 记录，变更并入首个正式版 v0.1.0。各版本完整提交明细见 Git 历史（`git log <prev-tag>..<tag>`）。
