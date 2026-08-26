# agent 模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/agent/`
> 维护规则：本模块代码变更必须同步更新本文档。新增/重命名/删除类、接口、工具、命令、Provider、DAO 或调整关键流程后，请一并修订对应小节。

## 1. 模块定位

一句话：agent 模块是 App 的 AI 编程助手核心，负责接收用户请求、驱动大模型（OpenAI / Anthropic / Gemini 兼容协议）多轮推理与工具调用，在本地 Linux 容器或远程 SSH 服务器上执行命令、读写文件、联网搜索等操作，并配合记忆、技能、MCP、权限引擎、检查点与 ZTH 零信任防护完成端到端的编码任务。

简述：模块以 `StatefulAgentWorkflow` 为核心循环（模型回复 → 工具调用 → 工具结果 → 再回复，直至结束），`ToolRegistry` 汇集全部工具，`AIAgentViewModel` 对接 UI 并负责会话、权限弹窗与消息落库；命令执行由 `CommandEngine` 抽象本地（PRoot 容器）/远程（SSH）两种后端；上层通过 `ZthGuardAggregateFacade` 对计划、工具调用、输出做多层安全审查与降级。

## 2. 目录结构与职责

### 2.1 data 层（持久化与远程数据）

| 路径 | 职责 |
| --- | --- |
| `data/local/dao/` | Room DAO：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`SkillStateDao`、`SkillConversationStateDao`、`ModeSwitchHistoryDao`、`ModelCapabilityOverrideDao`、Checkpoint 系列（`CheckpointDao`、`CheckpointFileSnapshotDao`、`FileEditHunkDao`）、ZTH 审计系列（`ZthTelemetryEventDao`、`UserConfirmedSentinelDao`、`HallucinationFuseDao`、`SentinelPlanRejectionAuditDao`、`HardConstraintDeleteAuditDao`、`L0SoftCompactRestoreLogDao`）、任务编排层（`GoalDao`、`PlanDao`、`JobDao`、`ScheduleDao`）、`WakeQueueDao` |
| `data/local/database/AgentDatabase.kt` | **agent 域独立库**（v4，`exportSchema=true`），仅承载 agent 域 23 张表（消息/会话/todo/checkpoint/skill/wake/zth + 任务编排层 Goal/Plan/Job/Schedule 4 表 + 运行轨迹 `agent_trajectories` + 剧本运行 `agent_playbook_runs`）；迁移链见 `AgentDatabaseMigrations`（`MIGRATION_1_2` 任务编排层 / `MIGRATION_2_3` 运行轨迹 / `MIGRATION_3_4` 剧本运行，DatabaseModule 注册）；`LegacyAgentDatabase.kt` 为旧单巨库（v49）只读副本，供一次性移植 |
| `data/local/entity/` | 与 DAO 一一对应的实体类（`AgentMessageEntity`、`ChatSessionEntity`、`CheckpointEntity`、`SkillStateEntity`、`SkillConversationStateEntity`、`TodoItemEntity` 等） |
| `data/remote/anthropic/` | Anthropic API 客户端（`AnthropicApi`、`AnthropicModels`），含 `@Streaming` SSE 流式接口 |
| `data/remote/openai/` | OpenAI 兼容 API 客户端（`OpenAIApi`、`OpenAIModels`） |
| `data/remote/gemini/` | Gemini API 客户端（`GeminiApi`） |
| `data/remote/zth/` | ZTH 遥测/审计的 Firestore 同步（`ZthFirestoreSyncManager`、`ZthFirestoreDtos`、`ZthEntityMapper`） |
| `data/repository/` | ZTH 专属仓库：`ZthCapabilityAuditRepository`、`ZthCheckpointRepository`、`ZthCircuitBreakerRepository`、`ZthConfirmationCardRepository`、`ZthPlanApprovalRepository`、`ZthTelemetryRepository` |
| `data/CodeChangeTracker.kt` | 从 writeFile/editFile 工具调用与结果中提取 `CodeChange`（CREATE/REPLACE），供 diff 展示与检查点使用 |

### 2.2 domain 层（核心领域逻辑）

| 路径 | 职责 |
| --- | --- |
| `domain/bridge/RcbBridge.kt` | 容器 ⇄ 宿主 loopback TCP 桥：让容器内 `rcb-*` 命令把剪贴板/URL/通知操作交给 App；握手令牌鉴权，`open_url` 默认仅记日志 |
| `domain/checkpoint/CheckpointManager.kt` | 文件检查点：每条用户消息建一个 checkpoint，文件被修改前保存原始快照（`<filesDir>/checkpoints/...`），支持回滚 |
| `domain/command/` | 斜杠命令系统：`SlashCommand`（handler 接口）、`SlashCommandRegistry`（Hilt multibinding 汇集）、`SlashCommandModule`（注入绑定）、`CompressCommandHandler`、`StatusCommandHandler`、`RulesCommandHandler`（`/rules` 列出/加载分层规则，D3-3） |
| `domain/container/` | 命令执行后端：`CommandEngine`（接口）、`LinuxContainerEngine`（PRoot 本地容器）、`RemoteSshEngine`（SSH exec）、`RemoteSshConnection`（共享 sshj 连接/SFTP）、`DelegatingCommandEngine`（按模式委派本地/远程）、`ContainerInstaller`、`ContainerProfile`、`ContainerInitState`、`BoundedOutput`、`GlobalInstallArchiveStore` |
| `domain/container/progress/` | 安装进度聚合：`RealProgressAggregator`、`InstallProgressParser`、`ApkStdoutParser`、`ParallelPrefetchManager`、`PrefetchConcurrencyPolicy`、`ProgressModels` |
| `domain/mcp/` | MCP 集成：`McpManager`（连接/工具注册/状态流）、`McpClient`、`McpTool`（MCP 工具适配 `AgentTool`）、`McpJsonRpc`、`McpServerConfig`、`McpConfigRepository`、`McpTransport` + `StdioTransport` / `StreamableHttpTransport`。**`server/` 子包（已实施）**：内置 MCP 服务器（`McpServerManager`/`McpHttpServer`/`McpServerSession`/`AgentToolMcpAdapter`/`McpServerSecurity`/`McpServerSettings`），把 App 能力开放给外部 MCP 客户端，见 [builtin-mcp-server-design](../plan-docs/builtin-mcp-server-design.md) |
| `domain/memory/` | 记忆系统：`Memory` 模型（GLOBAL/PROJECT 作用域）、`MemoryParser`、`MemorySource` + `GlobalMemorySource`/`ProjectMemorySource`、`MemoryRepository` |
| `domain/model/` | 领域模型：`AgentMessage`（含 `AgentContext`：currentFile/selectedCode/projectRoot 等）、`ChatSession`（含 `AgentMode`：BUILD/PLAN/AUTO）、`CodeChange`、`ReasoningEffort`、`TodoItem` |
| `domain/permission/` | 权限引擎：`ToolPermissionPolicyEngine`（ALLOW/DENY/ASK 判定）、`PermissionRulesRepository`、`ShellCommandParser`、`BuiltInSafeCommands`、`BuildCommandClassifier`、`PermissionModels`、`ZthFailureModels`、`DangerousCommandGuard`（危险命令静态守卫） |
| `domain/prompt/SystemPromptProvider.kt` | 增量式系统提示词：按 `PromptSource` 组装静态规则（R04 起由 `AgentAssetRegistry` 按 mode 注入）、工作区上下文等片段，维护缓存与快照；**step 前注入（D1）**：`stepSources` 一次登记 8 个 `PromptSource`（含 D0 问判/行为模式/GoalStale/GoalAdjustEvent 4 源），经 `StepInjectionAssembler` 八源排序 + 预算裁剪后由 `buildStepInjections(ctx)` 产出注入块，工作流每轮 step 前拼进 system prompt |
| `domain/provider/` | AI Provider 抽象：`AIProvider`（complete / completeStream，含 reasoning、signature、token 统计）、`OpenAIAdapter`、`AnthropicAdapter`、`GeminiAdapter`、`RetryPolicy`、`HttpErrorEnricher`（把 HTTP 错误体拼进 message）。**流式 SSE 解析（网络层优化 P1）**：三家 adapter 改用 Okio `readUtf8Line()` 逐行读取 + `core/network/SseFieldExtractor` 定点字段抽取（Gson `JsonReader` 流式不建整树），替换原 `JsonParser.parseString().asJsonObject` 每行整树解析 |
| `domain/session/` | 会话用例：`SessionUseCase`（**删除事务化** `withTransaction` + 级联清理 9 张关联表[todo/hunk/模式切换历史/技能会话态/wake/任务编排 4 表]、重命名长度截断 `TITLE_MAX`、删当前会话后重选兜底 `getFirstSessionOfWorkspace`→`getFirstUnboundSession`→`getMostRecentSession`）、`MessagePersistenceUseCase` |
| `domain/skill/` | 技能系统：`Skill` 模型（PROMPT/SCRIPT/MCP 三形态、BUILTIN/LOCAL 来源）、`SkillParser`、`SkillRepository`、`SkillExecutor`、`SkillSource` + `LocalDirectorySkillSource`、`BuiltinSkillSeeder`（首启引导内置技能）、`SkillStateRepository`（Room 持久化启用状态） |
| `domain/tool/` | 工具系统（详见 2.3） |
| `domain/trajectory/` | **运行轨迹**（D2-3/D2-5）：`TrajectoryEntity`（agent_trajectories 表，append-only：tool/turn/compaction/inject/error/timeout 六类 kind）、`TrajectoryService`（记录 tool 轨迹与轻量标记、`buildActionSummary` 已做动作摘要、`turnUsage`/`sessionUsage` 用量聚合、`getTrajectory` 审计回放）；workflow 每次工具执行完成追加 tool 轨迹、turn 边界/压缩/注入/错误/超时追加标记，独立于 agent_messages 不受压缩影响 |
| `domain/workflow/` | Agent 工作流：`AgentWorkflow`（接口 + `AgentEvent` 事件集）、`StatefulAgentWorkflow`（MVI 状态机实现）、`ContextCompactor`（上下文压缩）。**D1 六段式工具流水线契约**：runToolSync 内 pre-execute（门，L5 缓存/视图守卫）→ guard（护栏链）→ execute（执行）→ post-execute（可改写结果/媒体剥离/文件观察版本更新）→ finalizeContent（结果定型）→ result（只读观测）；每轮 step 前经 `NormFlowSettingsRepository.isStepInjectActive()` 判定注入 step 前注入块，guard 段经 `isToolGuardActive()` 判定挂载护栏链 |
| `domain/zth/` | ZTH 零信任防护：`ZthGuardAggregateFacade`（聚合门面）、`ZthCircuitBreakerManager`、`ZthConfirmationCardManager` + `ZthConfirmationCardStateMachine`、`ZthPlanApprovalManagerWrapper`、`ZthContentReviewer`、`ZthToolOutputGuard`、`ZthCapabilityGuard`、`ZthFailureClassifier`、`ZthWorkflowHooks`、`ZthDomainModels`、`TerminalBundleMirrorRotator` |
| `domain/ext/` | **声明式扩展生态**（Claude Code 形态）：`ExtensionLoader`（统一扫描内置 `assets/ext/` + 用户 `<rcodecore>/ext/` 的声明式命令，FileObserver 热加载）、`ExtensionCommand`（frontmatter 命令模型）、`ExtensionCommandCore`（无 Android 依赖的命令扫描核心，mtime 懒刷新）、`PluginManifest`/`PluginManager`（插件分发：内置/用户两级，zip 导入 + Zip Slip 防护，聚合插件命令与 hooks 内容源） |
| `domain/goal/` | **会话任务目标状态机**（DSH goal）：`GoalService` 管理每会话唯一 ACTIVE 目标（activate/updateText/setStatus），`GoalEntity` 持久化；workflow 每轮 step 前把当前目标注入 system prompt |
| `domain/plan/` | **计划协作状态**（DSH plan + Claude Code Plan/Spec）：`PlanService` 管理会话单计划（propose/getById/update/approve/abandon/setPendingSelection），`PlanEntity` 持久化；workflow 每轮 step 前把未获批的 `pendingSelection` 注入 system prompt |
| `domain/job/` | **后台任务**（DSH jobs）：`JobService`/`JobExecutor` 管理长任务（编译/测试/构建）后台执行，状态落库（pending/running/success/failed/interrupted），支持 start/status/kill/log |
| `domain/schedule/` | **定时提醒**（DSH schedule）：`ScheduleService`（AFTER/AT/EVERY 三规则 + isDue 判定）、`ScheduleScheduler`（轮询调度，到点经 WakeQueueManager 注入会话唤醒 Agent），跨重启持久化 |
| `domain/playbook/` | **剧本编排 Playbook**（D5）：`PlaybookAsset`（frontmatter 剧本资产：stages/agents/sop/gates/guards + `PlaybookGate` 审批门 + `PlaybookSeed` spawn/fork 双 seed）、`PlaybookRegistry`（扫 `assets/playbooks/`，复用 frontmatter 解析 + mtime 懒刷新）、`PlaybookExecutor`（双状态机执行：运行级 RUNNING/COMPLETED/ABORTED/INTERRUPTED + 阶段级 PENDING/ACTIVE/DONE/FAILED，支持 start/advance/resume/retry/abort/interrupt，产物清单幂等）、`SubAgentRunner`（阶段子代理执行：spawn/fork 双 seed + 三档 SandboxMode 降权 + 并行聚合 + 阶段内写串行化） |
| `domain/hook/` | **声明式 hook 事件**（Claude Code hooks + DSH 工具流水线）：`HookDispatcher`（PreToolUse/PostToolUse/UserPromptSubmit/Stop/SessionStart 挂点）、`HookConfigLoader`（合并内置/插件/用户 hooks.json）、`CommitDisciplineHook`（git commit/push 纪律检查示例） |
| `domain/input/` | **用户意图拆解与持续意图维护源**（D0 + D1 step 前注入源）：`UserInputParser`（结构化解析 command?/args/text + 意图分类 + `!`/`?` marker）、`IntentAskSource`（意图问判三问，P1）、`BehaviorModeManager`/`BehaviorModeSource`（四档行为模式，P1）、`GoalStaleDetector`/`GoalStaleSource`（语义失配检测，P2）、`GoalAdjustEvent`/`GoalAdjustEventSource`（目标调整事件闭环，P1）、`GoalHintSource`（goal 注入，P0）、`PlanPendingHintSource`（plan pending 提示，P1）、`PlaybookStageSource`（剧本阶段注入，P1，D5 预留）、`LoopAdvisorySource`（空转循环提醒，P2） |
| `domain/rule/` | **分层规则纪律**（D3）：`RuleLayer`（全局/项目/工作区/模块四级 + 显式 priority）、`RuleAsset`（frontmatter 元数据 + 摘要/正文两级）、`RuleRegistry`（四级注册表：全局 `~/.rcodecore/global-rules.md` / 项目 `AGENTS.md` / 工作区 `workspace-AGENTS.md` / 模块 `feature/<module>/AGENTS.md`，复用 frontmatter 解析 + mtime 懒刷新；`resident` 三级常驻 + `moduleRules` 按需命中），`RuleAssetCore`（无 Android 依赖的解析核心，JVM 可测） |
| `domain/sop/` | **SOP 标准作业**（D4）：`SopAsset`（独立结构：`name`/`order`/`whenToUse`/`body`，与 `AgentAsset` 解耦，body 为编号步骤「操作 + 判定 + 产出/出错处理」）、`SopRegistry`（扫 `~/.rcodecore/sop/`，复用 `SkillParser` frontmatter 解析 + mtime 懒刷新；`SopAssetCore` 无 Android 依赖解析核心，JVM 可测）；摘要（名称 + whenToUse）经 `SystemPromptProvider.SopSource` 常驻注入，完整正文经 `loadSop` 工具按需取用 |
| `domain/guard/` | **工具执行护栏链**（D1）：`ToolGuard` 接口（guard 三态 PASS/BLOCK/ADVISORY）+ `ToolGuardContext`（toolName/args/sessionId/projectRoot）+ `ToolGuardResult`（Pass/Block/Advisory）；`FileObservationGuard`（文件观察纪律：编辑前必须先读否则 `FS_NOT_OBSERVED`、mtime 版本 CAS 否则 `FS_STALE`，新建豁免、writeFile 即已知）；`GuardModule`（Dagger `@IntoSet` 汇集注册护栏到 `Set<ToolGuard>`，挂入六段式 guard 段，首个 BLOCK 短路） |

### 2.3 domain/tool 工具系统

| 路径 | 职责 |
| --- | --- |
| `tool/AgentTool.kt` | 工具基类：`ToolResult`（Success/Error/Partial）、`ToolParameter`、`ToolCapability`、`ToolPermissionPolicy`、`RetryPolicy`/`ToolErrorClass`（L3 错误分类）、`provides/consumes`（L3 结果协议）、`dependsOn`（L4 依赖）、`subscribedEvents`（L7 事件）、`buildPostExecutionEvent`（L7 事件自声明钩子，事件由工具自声取代工作流硬编码 mapping）、`StreamingAgentTool` 流式接口、`ToolCall` |
| `tool/ToolRegistry.kt` | 单例工具注册表（`ConcurrentHashMap`），注册/查找/列出可用工具 |
| `tool/ToolResultCache.kt` | L5 结果缓存：会话级 + TTL（默认 60s），文件类工具按 mtime 失效；**文件观察版本（D1-4）**：`recordFileMtime(path, mtime)` 记录观察版本、`fileMtime(path)` 供 `FileObservationGuard` 做 mtime CAS 判定 |
| `tool/ToolResultTypeRegistry.kt` | L3 结构化结果类型登记；**轨迹摘要提取器（D2-3）**：`registerTrajectorySummarizer` 按工具名登记定制提取器（readFile 路径+行数 / writeFile 目标文件 / editFile 状态 / run_code exit+stdout 尾 / Bash 命令），无定制走通用截断（前 200 字符 + truncated） |
| `tool/ToolDependencyScheduler.kt` | L4 依赖感知调度：按工具 `dependsOn` 构建依赖图调度执行 |
| `tool/ToolEventBus.kt` + `ToolEvent.kt` | L7 事件总线：工具间声明式事件发布/订阅，驱动缓存失效等 |
| `tool/IncrementalIndexStore.kt` | 工具动作与轮次快照的增量索引 |
| `tool/ToolOutputStore.kt` / `ToolSessionState.kt` / `ToolPermissionManager.kt` | 工具输出存储、会话级工具状态、权限请求管理 |
| `tool/browser/BrowserAgentTool.kt` | 网页浏览能力 |
| `tool/container/` | 容器命令工具：`ExecuteCommandTool`（Bash，流式 + 输出限长 + 环境同步）、`EnsureAndroidEnvTool`、`CheckEnvironmentTool`、`BackgroundTerminalTools`、`SwitchContainerArchTool` |
| `tool/editor/EditFileTool.kt` | 按 old/new_string 编辑文件（支持多编辑点） |
| `tool/explorer/` | `ListFilesTool`（ls 风格只读遍历，隐藏噪音目录）、`SearchCodeTool`（ripgrep 代码搜索） |
| `tool/file/` | `FileTools`（read/write）、`ImageTools`、`SendFileTool` |
| `tool/image/GenerateImageTool.kt` | 通过 T2I Provider 生成图片 |
| `tool/mcp/ManageMcpTool.kt` | MCP 服务器管理（含动态 install） |
| `tool/memory/MemoryTool.kt` | 记忆读写（save/list/read） |
| `tool/mode/` | `SwitchModeTool`（BUILD/PLAN/AUTO 切换）、`PlanApprovalManager` |
| `tool/proxy/NetworkProxyTool.kt` | 网络代理（mihomo mixed-proxy）管理 |
| `tool/question/` | `AskUserQuestionTool` + `AskUserQuestionManager` + `UserQuestionModels`（向用户提问并等待回答） |
| `tool/rule/LoadRuleTool.kt` | 按需加载分层规则完整正文（`load_rule`，D3-3）：系统提示只注入规则摘要，判断适用时按名称精确查找取 body |
| `tool/search/` | `WebSearchTool`、`WebFetchTool` |
| `tool/skill/LoadSkillTool.kt` | 加载技能指令正文（PROMPT/SCRIPT 通用，仅返回 SKILL.md、不执行；含依赖注入、作用域校验、工具绑定） |
| `tool/skill/RunSkillScriptTool.kt` | 执行 SCRIPT 脚本技能（容器沙箱 + 审批 + 审计，专用执行入口） |
| `tool/skill/SkillInvocationResolver.kt` | loadSkill / runSkillScript 共用的技能定位与校验解析器 |
| `tool/storage/StorageTool.kt` | 设备（外部共享）存储读写，带安全护栏 |
| `tool/todo/TodoTool.kt` | 管理 TODO 列表 |
| `tool/AbstractContextualTool.kt` / `CodeSelection.kt` | 上下文工具基类与代码选区模型 |
| `tool/goal/GoalTool.kt` | 会话任务目标工具（`goal`：set/get/update/done/abandon，变更经 `AgentEvent.GoalChanged` 联动） |
| `tool/plan/PlanTool.kt` | 计划协作工具（`plan`：propose/get/update_steps/set_pending_selection/approve/abandon） |
| `tool/job/JobTools.kt` | 后台任务工具（`job_start`/`job_status`/`job_kill`/`job_log`） |
| `tool/schedule/ScheduleTool.kt` | 定时提醒工具（`schedule`：create(after/at/every)/list/cancel） |

### 2.4 presentation 层（UI 状态与组件）

| 路径 | 职责 |
| --- | --- |
| `presentation/AIAgentViewModel.kt` | Agent 主 ViewModel：`executeAgentRequestStream` 启动工作流、管理会话/工具权限/消息落库、实现 `SlashCommandContext`；**会话列表数据源 `sessionsWithCount`**（会话+消息条数聚合，保留工作台过滤）；**删除撤销**（缓存最近删除会话+消息，`undoDeleteSession` re-insert 恢复） |
| `presentation/ZthConfirmationCardViewModel.kt` | ZTH 确认卡片 UI 状态 |
| `presentation/AgentUiModels.kt` | UI 层模型（`AgentAttachment`、`AgentImage` 等） |
| `presentation/EnvironmentSnapshotStore.kt` | 环境快照存储 |
| `presentation/component/` | Compose 组件：`AIChatPanel`、`ChatInputBar`（编排入口，胶囊浮动条）、`ChatInputField`（输入框+附件预览）、`ChatInputToolbar`（工具栏+收纳菜单）、`ChatPanels`（权限审批/状态横幅/变更预览/计划审批面板）、`MessageBubbles`、`ToolMessageComponents`、`AskUserQuestionPanel`、`ZthConfirmationCardSheet`、`TaskAccordion`、`TodoCardComponents`、`WebSearchResultComponents`、`FileDiffSheet`、`ChatModelSheet`、`ChatSessionPicker`（`ChatSessionRow` 两行增强：标题+「时间·N 条消息」、选中高亮、执行中呼吸点）、`ChatDrawer`（对话列表四档吸顶分组 + `SwipeToDismissBox` 左滑删除 + Snackbar 撤销）、`MarkdownContent`、`RichSegmenter`（`component/richsegment/` 富文本分段）等 |
| `presentation/component/SessionListFormat.kt` | 会话列表时间分档与格式化：`SessionBucket`（TODAY/YESTERDAY/WITHIN_7D/EARLIER 四档）、`sessionBucket`/`formatSessionClock`（今日时钟）/`formatSessionDate`（更早日期）/`sessionDaysAgo`（N 天前） |

## 3. 核心架构与主流程

### 3.1 Agent 工作流（AgentWorkflow / StatefulAgentWorkflow）

1. UI 层 `AIAgentViewModel.executeAgentRequestStream(...)` 组装用户请求、上下文（currentFile/selectedCode/projectRoot/输入图片/附件）、目标会话与可用工具集。
2. 调用 `StatefulAgentWorkflow.executeEvents(...)`，以 `channelFlow` 向外推送 `AgentEvent`（`AssistantText`/`AssistantDelta`/`ReasoningDelta`/`ToolCallStarted`/`ToolCallProgress`/`ToolCallFinished`/`Retrying`/`CompactionStarted`/`Failed`/`Completed`/`ModeChanged` 等）。
3. 循环体：用 `AIProvider.completeStream`（SSE 流式）把 systemPrompt + 历史消息 + 工具定义发给模型 → 收到文字增量实时推送、收到 `tool_calls` 进入工具阶段 → 逐工具做权限评估 → 执行（流式工具走 `executeStream`，逐行 `ToolCallProgress`）→ 把 `ToolResult` 序列化回填上下文 → 再请求模型，直到模型不再调用工具或达到迭代上限。
4. 工作流以不可变 `AgentSessionState` + reducer 的 MVI 方式管理状态，覆盖：批量工具调用（`batchToolCalls`）、待审批权限调用（`pendingPermissionCalls`）、被拒工具结果（`rejectedToolResults`）、视觉输入轮（`pendingVisionRound`/`visionFallbackRetried`）、上下文压缩等。
5. 错误处理：网络首字节前失败自动重试（`Retrying` 事件）；`max_tokens`/`length` 截断自动续写；超过阈值自动 `ContextCompactor` 压缩；`/compress` 命令可手动触发 `compactSession`。
6. **流式累积归一化**：正文（`acc`）与思考（`reasoningAcc`）的流式累积统一走 `core/network/DeltaAccumulator`（`AUTO_DETECT` 语义），不再裸 `StringBuilder.append`——兼容网关全量重发 `reasoning_content`/`content` 时自动去重（本次 base64 重复 bug 根因），并内置裸 base64 折叠与 200k 长度护栏；工具参数增量累积（OpenAI `tool_calls.arguments`、Anthropic `input_json_delta`）在 adapter 侧接入 `DeltaAccumulator(INCREMENTAL)`。护栏触发（全量重发/截断/折叠）经 `logNormalizerGuardrails` 打 warn（含 sessionId、model、放大比率）。详见 [docs/plan-docs/streaming-delta-normalizer-design.md](../../docs/plan-docs/streaming-delta-normalizer-design.md)。

### 3.2 工具系统（AgentTool / ToolRegistry）

- 所有能力以 `AgentTool` 子类表达：声明 `name`、`description`、`parameters`、`capabilities`、`permissionPolicy`，实现 `execute`；需要过程输出的工具同时实现 `StreamingAgentTool.executeStream`。
- `ToolRegistry`（单例）统一注册与按名查找；`AIAgentViewModel` 从注册表取全部工具传给工作流，再转换为各 Provider 的 function-calling schema（`toToolDefinition`/`toJsonSchema`，MCP 工具可透传服务端原始 inputSchema）。
- 工具间协作维度：`provides/consumes`（L3 结果类型直连）、`dependsOn`（L4 依赖调度）、`subscribedEvents`（L7 事件总线）、`ToolResultCache`（L5 结果去重）。
- **L7 事件自声明（事件解耦）**：工具成功产出事件由工具自身经 `AgentTool.buildPostExecutionEvent(toolCall, result, context)` 钩子声明（file.edited / file.written / file.mutated / todo.updated / state.memory.updated / state.skill.loaded / state.mode.changed）；`StatefulAgentWorkflow.publishToolEvent` 只做 `toolRegistry.getTool(name)` → 统一查询钩子并 `toolEventBus.publish`，不再硬编码 `when(toolName)`，新增工具无需改动工作流。

### 3.3 容器 / 命令执行（CommandEngine）

- `CommandEngine` 抽象同步/流式命令执行、容器状态与初始化进度（`initProgress: StateFlow<ContainerInitState>`）。
- `LinuxContainerEngine`：本地 PRoot 容器实现；`RemoteSshEngine`：SSH exec（基于 `RemoteSshConnection` 共享 sshj 连接，与 SFTP 文件访问复用同一连接）；`DelegatingCommandEngine` 按当前模式/配置把调用委派给本地或远程后端。
- `ExecuteCommandTool`（`Bash`）把 shell 命令交给 `CommandEngine`，流式输出、限长截断、环境同步，并按计划/自动/常规模式受权限引擎约束。
- **命令执行护栏（`CommandLoopGuard` + `BusyBoxCompatibilityGuard`）**：`tool/container/` 两个静态检测器在命令执行前预检，预防 AI 写出「永不结束 / BusyBox 不兼容 / 危险爆炸」的命令。
  - `CommandLoopGuard`：检测无界循环（`while true`/`while :`/`while [ 1 ]`/`until false`/`for ((;;))`，刻意不命中 `while read`/条件循环/`for i in ...` 等有界用法）与经典 fork bomb（`:(){ :|:& };:`）。命中无界循环时：
    - `Bash`（`ExecuteCommandTool`）：把超时钳制为 30 秒（默认 120s 提前强制终止），权限卡 `details` 附加警告。
    - `terminal(action="start")`（`TerminalSessionTool`）：常驻终端无超时，**直接拒绝启动**并提示改用 `Bash`，防止 AI 把循环塞进常驻终端无限刷屏/空耗。
    - fork bomb 两个工具都**直接拒绝**（`&` 后台自复制，超时无法可靠终止）。
  - `BusyBoxCompatibilityGuard`：识别 BusyBox 不支持的 GNU 专属参数（`nc -q`/`grep -P`/grep 模式 `\d\s\w`/`find -printf`/`xargs -d`/`cp --parents`）与无限 `ping`（未带 `-c|-w`）。**只预警不拦截**：权限卡 `details` 附加提示，并把提示拼进工具结果末尾，让 AI 看到并改用兼容写法。
  - `DangerousCommandGuard`（危险命令静态守卫，R05-R07 落地）：在 **权限引擎层（`ToolPermissionPolicyEngine.evaluateShell`/AUTO 分支）与工具入口层（`ExecuteCommandTool`）双层接入**，即使绕过权限引擎（如 AUTO 模式）也能拦截。两级判定：
    - **Block（命中即 DENY，拒绝原因喂给模型）**：B1 RCE 管道（`curl|wget` + `sh|bash` 跨段相邻 `|` 连接、进程替换 `<(curl ...)`，`&&` 断开不拦）；B2 系统目录权限破坏（`chmod`/`chown` 目标在系统目录集且递归或宽松权限位，`parseChmodInfo` 解析段 token）；B3 设备/磁盘破坏（`dd of=/dev/sd*` 写设备、`mkfs.*`、`fdisk` 写操作、重定向写存储设备、覆盖 `/etc/passwd|shadow|group|hosts|sudoers`）；B4 关机/杀进程（`shutdown/reboot/halt/poweroff`、`kill -9 -1`、无目标 `pkill`/`killall`）。
    - **Warn（放行，合并提示块）**：W1 权限过宽、W2 文件覆盖/清空（`> file`/`: > file`）、W3 下载到工作区外绝对路径、W4 `curl` 未带 `-o/-s` 刷屏、W5 sudo 高危、W6 读取凭据文件、W7 覆盖 SSH 密钥、W8 base64 解码执行、W9 明文密码、W10 `git push --force`、W11 疑似反向 shell（`nc -e`/`socat exec`/`/dev/tcp`）。
    - **误报防护**：伪设备白名单（`/dev/null|zero|random|tty|pts/|fd/` 等）排除在 B3 之外；系统目录集合不覆盖工作区，正常开发命令不误拦。Warn 提示经 `mergedWarnBlock` 与 `BusyBoxCompatibilityGuard` 提示合并为统一提示块，在权限卡 `details` 与命令输出末尾两处展示，避免重复。
  - 背景：容器为 Alpine（BusyBox），AI 曾写出 `while true; do nc -q ...; done`（`nc -q` 为 GNU netcat 语法，BusyBox 不支持，每次调用失败又重试）塞进常驻终端导致无限刷屏，此护栏 + 提示词约束（`prompts/60-tools-and-paths.md`「容器命令兼容性与执行纪律」）双管齐下。
- `ContainerInstaller` + `progress/` 子包负责环境安装与进度聚合解析（`RealProgressAggregator`/`InstallProgressParser`/`ApkStdoutParser`）。
- **双架构容器（真机 + 模拟器）**：`ContainerInstaller` 支持 `container/arm`（arm64）与 `container/x86_64`（x86_64 rootfs + x86_64 原生 proot + qemu 转译器）双 rootfs 资产，`prootBinFor/rootfsDirFor` 按 `ContainerProfile.arch + EnvironmentDetector` 选择；`LinuxContainerEngine` 首次启动按宿主架构自动落到对应内置 profile（x86_64 宿主 → `BUILTIN_ALPINE_X86`），`buildBaseProotArgv` 仅「x86_64 容器 + 非 x86_64 宿主」时注入 `-q qemu`；容器不可用环境走明确降级报错，AI 核心（文件/对话/远程 SSH）不受影响（见 [emulator-support-design](../plan-docs/emulator-support-design.md)）。

### 3.4 MCP（McpManager）

- `McpManager`（单例）读取 `McpConfigRepository` 的服务器配置，通过 `StreamableHttpTransport`（HTTP）或 `StdioTransport`（stdio）连接 `McpClient`，把服务器暴露的工具包装为 `McpTool` 注册进 `ToolRegistry`，并维护 `statuses: StateFlow<List<McpServerStatus>>` 供 UI 展示；支持动态 install 新服务器（`ManageMcpTool`）。

**内置 MCP 服务器（规划中，未实施）**：当前 `domain/mcp/` 只有客户端（连别人）。设计已评审（[builtin-mcp-server-design](../plan-docs/builtin-mcp-server-design.md)）：新增 `server/` 子包做「客户端 + 服务器」双角色，用 Ktor CIO 起 Streamable HTTP 端点，把 `ToolRegistry` 中的 `AgentTool` 映射为 MCP 工具（复用 `toToolDefinition()`/`execute()`），权限复用 `ToolPermissionManager` + 远程强制审批总开关，服务管理对标 `FtpServerManager`（开关/端口/token/自启/URL 展示）。实施按 M0 只读子集 → M1 全量工具 → M2 SSE+保活 → M3 上下文工具渐进推进；落地后更新本节。

### 3.5 记忆（Memory）

- `Memory` 模型带 `MemoryScope`（GLOBAL/PROJECT）；`GlobalMemorySource`/`ProjectMemorySource` 从对应目录解析，`MemoryRepository` 汇总。
- `SystemPromptProvider` 把记忆摘要注入系统提示词；`MemoryTool` 提供 save/list/read，维护访问计数与标签。

### 3.6 技能（Skill）

- `Skill` 支持三种执行形态：PROMPT（注入指令，无执行）、SCRIPT（容器内沙箱执行入口脚本，需 ZTH 审批）、MCP（**已降级为别名**：不再经技能系统执行，AI 直接调用其绑定的 MCP 工具）。
- `SkillParser` 解析带 Frontmatter 的技能文件（版本/作者/标签/适用模式/依赖/requiredTools/requiresRuntime 等），`SkillRepository` 管理来源，`SkillStateRepository` 用 Room 持久化启用状态。
- **技能调用工具职责拆分（重写后的唯一入口约定）**：`LoadSkillTool`（`loadSkill`）负责**读正文**——PROMPT/SCRIPT 技能均返回 SKILL.md 指令正文（SCRIPT 正文末尾附执行指引），绝不执行任何脚本/工具；`RunSkillScriptTool`（`runSkillScript`）是 SCRIPT 脚本技能的专用执行入口（容器沙箱 + 审批 + 审计 + 运行时预检）；两者共用 `SkillInvocationResolver` 完成定位/版本锁/作用域/依赖校验，`SkillExecutor` 负责实际运行。
- `SkillExecutor` 执行链路携带 `SkillExecutionContext`（由调用方从 `AgentContext` 派生，含 sessionId/mode/projectPath/agentType），使脚本技能审批与审计的 sessionId 与当前会话连贯（替代此前传 null 的脱钩问题）。
- **S-3 运行时预检（`SkillRuntimeProbe`）**：SCRIPT 技能可在 frontmatter 声明 `requires_runtime`——布尔求值树（`RuntimeProbeExpr`：Leaf/And/Or/Not），由 `SkillProbeExprParser` 从 `expr` 字符串解析（如 `cmd:node>=18<=22 && (mod:numpy || cmd:python3)`；旧 YAML 对象/字符串列表/逗号串兼容归一为 And）。执行前在容器内受控探测：命令 / Python 模块 / npm 全局包 / deb 包 / 文件，叶子支持版本区间（`min_version`/`max_version`）与 `install_hint` 安装建议；目标名与版本白名单校验、探测命令参数化执行，杜绝 shell 注入，组合逻辑只做纯逻辑求值不 eval。任一条件不满足即返回 `SKILL_MISSING_RUNTIME` 并列出缺什么与安装建议。`RunSkillScriptTool`（手动）与自动触发路径共用同一预检（双路径一致，避免「触发即静默失败」）。

#### 3.6.1 技能作用域分级（SkillScope v2，多 Agent 演进 + 对话级控制）

给 `Skill` 增加与 `type`/`modes` **正交**的作用域维度，定义「技能在哪些上下文生效、能否被用户关闭」，v47 起为三档：

- `SkillScope` 三档：`GLOBAL` 全局（所有 Agent、所有对话默认生效，用户可设置级开关，也可在某个对话内临时禁用）／`AGENT` 指定 Agent 级（仅绑定 `agentType` 的 agent 生效，如 `"coding"`）／`CONVERSATION` 对话级（默认休眠，不进系统提示词、不可调用、不自动触发，仅当用户显式「添加」到某个对话后才在该对话内全面生效）。
- `Skill` 新增字段：`scope: SkillScope = GLOBAL`、`agentType: String? = null`（scope==AGENT 时必填，如 `"coding"`）。
- Frontmatter 承载 `scope: global|agent|conversation` + `agent-type`（仅 agent 级需要）；旧 `scope: common` 由 `SkillParser` 兼容映射为 `GLOBAL`（旧 COMMON 语义并入 GLOBAL）。
- **作用域用户覆盖**：`skill_state` 表新增 `scope_override` / `agent_type_override`（可空），`SkillStateRepository.mergeWithState` 在非空且可解析时覆盖 frontmatter 声明（仅 LOCAL 技能可改），NULL=跟随声明。
- **对话级双向控制**：`skill_conversation_state(skill_id, session_id, enabled)` 表记录技能在某对话内的生效状态。
  - `enabled=true`：添加对话级技能（CONVERSATION 技能激活）或对话内恢复；
  - `enabled=false`：对 GLOBAL/AGENT 技能做对话内临时禁用；
  - 无绑定记录 = 跟随声明。
- **作用域严格隐藏（filterVisibleSkills/filterVisibleSkillsSync）**：仅「全局启用 + 作用域匹配 + 未被本对话临时禁用」的技能对模型可见/可调用/可自动触发：
  - AGENT → 仅 `skill.agentType == 当前 agentType`（无 agentType 上下文不可见）；
  - CONVERSATION → 需本会话存在 `enabled=true` 绑定；
  - GLOBAL/AGENT → 本会话存在 `enabled=false` 绑定则排除。
- `SkillToolBindingManager`：技能加载成功时校验并登记 `requiredTools`（缺失给明确错误 `SKILL_MISSING_TOOL`），技能禁用/卸载时回收本管理器动态注册的工具（绝不删除内置全局工具）。
- `LoadSkillTool.executeWithContext`：GLOBAL 直接放行；AGENT 级按声明 agentType 校验；加载成功前登记专属工具。当前为单 Agent 场景（`DEFAULT_AGENT_TYPE="coding"`），多 Agent 演进后改为 `<skill.agentType> == 当前激活 agentType` 才放行。作用域/版本/依赖校验统一由 `SkillInvocationResolver` 提供（loadSkill 与 runSkillScript 共用，避免重复实现）。

#### 3.6.2 内置技能（BuiltinSkillSeeder）与脚本项目路径契约

- `BuiltinSkillSeeder`：首启把 `assets/skills/*`（每个子目录一个内置技能）幂等引导（copy）进技能根目录 `skillsRoot`，并写入 `.builtin` 只读标记；`LocalDirectorySkillSource.listSkills()` 每次扫描前调用 `seedIfNeeded()` 补齐缺失项，扫描时据此把已落地目录识别为 `SkillSourceType.BUILTIN`。**内置技能升级覆盖**：对已落地带 `.builtin` 标记的技能，按 `SKILL.md` frontmatter `version` 与 assets 侧比对，不一致时 `deleteRecursively` 干净重建为新版（官方内容升级随新包自动生效），一致则不动；无 `.builtin` 标记的同名用户技能仍不覆盖。
- **内置技能只读保护**：`LocalDirectorySkillSource.uninstall/install/update` 检测到 `.builtin` 标记即拒绝卸载/覆盖/更新（内置技能随版本升级，不可被用户改删）。
- **脚本项目路径契约（SKILL_PROJECT_PATH）**：`SkillExecutor.executeScript` 组装入口脚本环境时注入 `SKILL_PROJECT_PATH`。宿主导入 `projectPath` 由 `LinuxContainerEngine` 经 proot `-b` 绑定到容器内固定点 `/root/workspace`，故统一注入容器侧 `/root/workspace`；`projectPath` 为空则注入空（纯静态检查）。供脚本技能在内定位真实项目并执行 git / 文件检查。
- 首个内置技能：**pre-commit-health**（提交前规范体检，`assets/skills/pre-commit-health/`），scope=COMMON、type=SCRIPT、当前版本 v1.7.0；依据 `SKILL_PROJECT_PATH` 圈定待提交改动，输出「阻断项/建议项」报告（C-1~C-13 阻断 + W-1~W-26 建议，覆盖模块文档同步、strings.xml、版本号、敏感信息与高熵密钥、targetSdk、prompts/docs 资产、迁移 SQL、Git 中间状态、技能资产 frontmatter、二进制文件、提交信息格式、分支纪律、diff 预算、文件卫生含 CRLF、超长行、游离 HEAD、依赖锁定、.gitignore 缺口、大小写冲突、损坏符号链接、AI 引用残留、子模块嵌套仓库、硬编码绝对路径、shebang 一致性、编码与结构化文件雷区、依赖版本未锁定、大删除确认、.gitattributes 归一化、工作流供应链安全、内网私有 IP 等），有阻断项时退出码非 0。

#### 3.6.3 pre-commit-health 分层检查 / busybox 兼容 / CI 护栏

- **B1 分层检查**：脚本先按 `app/build.gradle.kts` / `app/build.gradle` 是否存在于项目根判定类型并打印 `[类型]` 行。
  - 通用检查（任意 git 项目，v1.7.0 共 10 阻断 + 23 建议）：C-4 敏感信息、C-6 合并冲突标记、C-7 构建产物/超大文件、C-8 敏感文件类型、C-9 调试残留、C-10 Git 中间状态、C-11 技能资产 frontmatter、C-12 二进制文件、C-13 高熵密钥（正则 + Shannon 熵双层，借鉴 gitleaks/detect-secrets）；W-4 提交信息格式、W-5 分支纪律、W-6 diff 预算、W-7 待办标记、W-8 原子性、W-9 文件卫生、W-10 超长行、W-11 游离 HEAD、W-12 依赖锁定、W-13 .gitignore 缺口、W-14 CRLF 混用、W-15 大小写冲突、W-16 损坏符号链接、W-17 AI 引用残留、W-18 子模块/嵌套仓库、W-19 硬编码绝对路径、W-20 shebang 一致性、W-21 编码/结构化文件雷区、W-22 依赖版本未锁定、W-23 大删除/大改面确认、W-24 .gitattributes 归一化、W-25 工作流供应链安全、W-26 内网私有 IP。
  - Android 专属检查（仅识别为 Android 项目时执行）：C-1 模块文档同步、C-2 `.kt` 硬编码中文、C-3 手写版本号、C-5 targetSdk 锁定、W-1 prompts/docs 资产同步、W-2 模块文档行为变化、W-3 迁移 SQL 字面量。
  - 非 Android 项目自动跳过 Android 专属项，避免误报。
  - 技能资产目录（`app/src/main/assets/skills/*`）在 C-4/C-13/W-7/W-19/W-26 中整体跳过，避免安全正则字面量对技能自身造成自引用误报。
- **busybox 兼容约束**：目标运行环境为 Alpine（busybox ash/grep/sed）。脚本禁用 gawk 专属正则（`\x{...}`、`\s`、`\d`）；字符检测改用 `LC_ALL=C grep -n '[^ -~]'` 按字节判非可打印 ASCII；`has_pref` 统一用 `grep -E`（ERE），避免 BRE 中 `|` 为字面量、`\|` 依赖 GNU 扩展导致 alternation 失效的坑。新增检查项须经本地 `sh -n` 验证。
- **效率优化**（v1.7.0）：C-12 二进制判定采样前 8KB（避免整读超大文件）；W-9/W-14 合并为单次文件遍历减少重复 IO；C-13 先 grep 预筛长串再跑 awk 熵计算；CHANGED 收集改用 `git diff --name-only HEAD` + `git diff --cached --name-only` + `git ls-files --others` 三源合并，并全局 `-c core.quotepath=false` 防路径转义。
- **降噪优化**（v1.7.0）：C-13 跳过锁文件/压缩产物/测试目录；C-4 移除 "test" 豁免词（防 `sk_test_` 等真实测试密钥漏检）并新增 `changeme|xxxx` 占位符豁免；C-8 不用 `*secret*` 通配拦截文件名（防误伤 SecretService.kt 等合法源码），新增 `.env.local`/`.env.production`/`.aws/credentials` 等敏感变体。
- **CI 护栏**：`.github/workflows/ci.yml` 在 JDK 之后新增「Check skill script syntax (sh -n)」步骤，对 `assets/skills/**/*.sh` 逐个 `sh -n` 校验，防止不合 POSIX 语法的脚本合入后容器内执行即崩。
- **故障口径（只读不修）**：脚本运行时若报语法错误/无法完成，AI 不就地修改内置资产（`entry/run.sh`/`SKILL.md`），应如实报告原因（脚本 bug / 环境缺依赖）；确为脚本 bug 由维护者修复后经 `sh -n` + CI 护栏双验证再发版。
- **SKILL.md 触发词**：description 含通用触发词（提交前检查 / 规范体检 / pre-commit / commit 前体检），并写明输出解读、修复口径与分层说明。

#### 3.6.4 第二款内置技能 coding-preflight（编程前准备）

- 第二款内置技能：**coding-preflight**（编程前准备，`assets/skills/coding-preflight/`），scope=COMMON、type=SCRIPT、当前版本 v1.0.0；与 pre-commit-health 互补，构成「开工前 → 编程 → 提交前」闭环。
- **执行契约**：沿用 `SKILL_PROJECT_PATH`；任务描述经 `runSkillScript` 的 args 传入，`SkillExecutor` 统一注入为 `SKILL_ARG_TASK` 环境变量（机制已存在，无需新增注入逻辑）。
- **SCRIPT 自动采集现状快照**：`entry/run.sh` 按 `SKILL_PROJECT_PATH` 定位仓库根后输出：
  - `[项目]/[类型]`：仓库根、Android/非 Android 分层（存在 `app/build.gradle.kts` 或 `app/build.gradle` 判定）；
  - `[任务]`：`SKILL_ARG_TASK`（缺省提示先澄清需求）；
  - `[环境]`：按项目栈推断关键构建组件并探测（Android→Java/Gradle/AndroidSDK；非 Android 按 package.json/go.mod/Cargo.toml/pom.xml/requirements.txt 推断 Node/Go/Cargo/Maven/Python，Git 始终探测），输出 `NAME=installed(版本)/missing`；
  - `[仓库]`：分支、游离 HEAD、中间操作、未提交改动（已暂存/未暂存/未跟踪）、最近提交；
  - `[结构]`：feature 模块清单 + `docs/modules/*.md` 存在性、关键目录、`[文件]` AGENTS.md/.gitignore/.gitattributes/README.md 存在性。
- **就绪判定（输出「阻断项 ❌ / 建议项 ⚠️」，有阻断项退出码非 0）**：
  - 阻断项：R-1 环境缺关键组件（按项目栈判定）、R-2 仓库中间操作、R-3 游离 HEAD、R-4 有未提交改动（与用户确认同主题或先 stash/提交）、R-5 错误分支（功能/重构在 main/master 时建议建分支）；
  - 建议项：W-1 记忆未加载、W-2 模块文档缺失/未读、W-3 资产同步面预判（strings.xml/prompts/docs/docs-modules）、W-4 危险/敏感操作前置询问（Ask First）、W-5 未提供任务描述。
- **PROMPT 引导**：SKILL.md 正文引导 AI 完成「任务理解（只读核实不凭记忆）→ 计划拆解（可执行步骤+验证方式）→ 验收标准 → 纪律自检（AGENTS.md 边界 + 资产同步项）」，末尾输出 `[计划] 建议步骤` 供 AI 落地。
- **busybox/dash 兼容**：命令替换内不直接嵌套带引号的 `"$2"/"$3"`（部分 sh 解析会崩），先转存局部变量再拼命令；统一 `grep -E`/`sed`/`awk`；全部 git 命令 `-c core.quotepath=false` 防路径转义；`sh -n` + CI 护栏（`assets/skills/**/*.sh`）双验证。
- **故障口径**：与 pre-commit-health 一致（只读不修 / 先复核再报错 / 降级不硬崩 / 如实报告）。

#### 3.6.5 技能自动触发（autoTrigger，自动化流程一环）

把技能从「等模型自觉调用 loadSkill」升级为**工作流程序化自动触发**，无需关键词、不依赖模型自觉性，作为自动化流程的一环在新任务到来时智能识别并注入。

- **声明方式**：`Skill` 新增 `autoTrigger: Boolean = false` + `triggerConditions: String? = null` + `triggerKeywords: List<String> = emptyList()` 字段；`SkillParser` 解析 SKILL.md frontmatter 的 `auto_trigger: true`、`trigger_conditions: <自然语言触发条件>` 与 `trigger_keywords: [典型信号词, ...]`（支持 YAML list 或逗号分隔），缺省均不参与自动触发。
- **触发决策铁律（唯一设计原则，后续所有声明 `auto_trigger` 的技能都必须遵循）：模型决策 > 关键词**：
  1. **模型主导（唯一主路径）**：`decideAutoTriggerSkills` 用 LLM 触发决策器（`AIProvider.complete`，reasoningEffort=low）基于 `triggerConditions`/`description` 判断任务意图是否高度匹配，输出技能 name JSON 数组，**宁可少触发、不可误触发**；`trigger_keywords` 仅是「典型触发信号词」喂给模型聚焦，**绝不直接参与触发判定、永不高于模型判断**；
  2. **关键词兜底（降级路径，仅极端保底）**：仅当模型链路完全不可用（异常）时，才回退用 `trigger_keywords` 做关键词匹配触发，避免明确任务极端落空。关键词永远不高于模型判断。
- **规则快筛（前置过滤，非决策）**：候选 = 「启用 + `autoTrigger` + 通过作用域过滤（`filterVisibleSkillsSync`，即对当前会话可见，含对话级激活/禁用的双向过滤）+ 本会话未触发过」，最多 `MAX_AUTO_TRIGGER_SKILLS`（2）个。
- **触发调度（D12）**：命中技能按作用域优先级 `GLOBAL > AGENT > CONVERSATION` 排序后取前 ≤2 个，避免同轮多审批卡与上下文膨胀；`scopePriority` 实现于 `StatefulAgentWorkflow`。
- **执行注入（双段注入）**：命中则走 `SkillExecutor.execute`（PROMPT 注入正文、SCRIPT 走既有 ZTH 审批；**MCP 包装技能不参与自动触发**——已降级为别名，由模型按需直接调用绑定 MCP 工具）。注入内容为**双段结构**——`【技能规则】`（SKILL.md 正文 `skill.instructions`，SCRIPT 技能自动触发时一并纳入，含检查项/修复口径/计划引导/纪律）+ `【执行报告】`（本次执行 stdout/正文），合并进最后一条用户消息（技能在前、用户请求在后），保证模型必然以该消息为当前任务并读取其中规则；`AUTO_TRIGGER_OUTPUT_MAX` 拉高到 24KB 确保 coding-preflight 报告末尾的【文档模板】段（4 份完整最小模板，约 10~18KB）不被截断；同时向 UI 推送 `AutoTriggered` 事件落库工具卡片，用户可直观看到「某技能被自动触发并加载」的结果。SCRIPT 自动触发执行前仍弹确认卡（审批卡标题带【自动触发】标记，安全审批链路不变），用户拒绝/超时降级为文本注入不阻塞主流程；**仅成功执行后才标记会话级去重**，失败/被拒可重试。
- **注入权威性加固 + 通用化项目类型**：三重保障（从物理送到→语义约束→内容来源完全闭环）：
  (a) **合并进最后一条用户消息**（`技能规则+执行报告\n\n【本次用户请求】\n用户请求`），模型必然读到；也避免「连续多条 User 消息」触发 Anthropic 等 API 报错；
  (b) **SCRIPT 技能注入其 SKILL.md 规则正文 + 脚本报告末尾附带【文档模板】段**：对 4 份缺失基础资产（AGENTS.md/README.md/.gitignore/.gitattributes）分别给出「通用最小可用模板」——**解决模型"想补全却没有内容可写"的根因**（之前模型只看到"创建 AGENTS.md 含边界规则…"却不知道"边界规则具体写什么"，直接用熟悉的 PHP/HTML 跳过）；.gitignore/.gitattributes 从「仅快照展示」升级为 W-8/W-9 建议项，与 W-6/W-7 一同计入建议计数；
  (c) **系统提示词末尾追加【系统】硬性指令，严格规定执行顺序**：① 先完整阅读技能规则 + W-*/R-* + 【文档模板】 ② 写业务代码前必须先补所有缺失项（AGENTS.md/README.md/.gitignore/.gitattributes 一律直接 writeFile 写入模板、W-1 先 git init） ③ 再加载记忆/读模块文档/拆步骤/验收标准/纪律自检 ④ 最后才处理用户请求业务开发。
  同时 **项目类型识别通用化**：去掉「非 Android 项目」二分，改为按「标志文件 + 目录指纹」识别 Android/Web前端/Node后端/PHP/Python/Go/Rust/Java/C/C++/空工作区/脚本工程/通用工程，可多标签并存。README 技术栈/运行方式、.gitignore 语言忽略项、PHP 环境探测等均按识别类型精确生成，先入为主"非Android"偏见完全消除。
  coding-preflight 脚本还新增「软阻断」：建议项>0 输出 🛑 说明并以 exit 3 非 0 退出，让调用方感知基础资产未就绪；就绪判定段直接声明"未补全前不要开始用户请求代码改动"，在全链路施压让模型先补资产再写业务代码。
- **会话级去重**：`ToolSessionState` 新增 `autoTriggeredSkills` 集合，同一技能在同一会话内最多自动触发一次。
- **已启用声明**：`coding-preflight`（编程前）与 `pre-commit-health`（提交前）两个内置 SCRIPT 技能已在 frontmatter 声明 `auto_trigger: true` + `trigger_conditions` + `trigger_keywords`。
- **内容升级机制（根治：保证改动到达老设备）**：`BuiltinSkillSeeder` 的内置技能升级判定为「**version 或内容 hash 任一不一致即覆盖**」双保险——(a) frontmatter `version` 显式 bump 时覆盖；(b) 即便忘记 bump version，只要技能内容（SKILL.md / entry / 其它资产）与 assets 侧不一致（如新增 `auto_trigger` 字段），也会按「相对技能目录路径 + 内容」的 SHA-256 组合 hash 比对触发覆盖。从根上避免「只改 frontmatter 却因 version 未变导致改动永不生效」的问题。
- **决策点**：仅通过作用域过滤（对当前会话可见）的技能参与自动触发；AGENT 级需当前 agent 匹配、CONVERSATION 级需本对话已添加。

### 3.7 ZTH 零信任防护（ZthGuardAggregateFacade）

- 聚合门面串联四方联动：熔断器（`ZthCircuitBreakerManager`）、计划审批（`ZthPlanApprovalManagerWrapper`）、确认卡片（`ZthConfirmationCardManager` + `ZthConfirmationCardStateMachine`）、检查点（`ZthCheckpointRepository`）。
- 在计划前、工具调用前、工具完成后、异常抛出四处挂审计钩子（`ZthWorkflowHooks`），配合 `ZthContentReviewer`（幻觉内容审查）、`ZthToolOutputGuard`（工具输出守卫）、`ZthCapabilityGuard`（能力降级，如 `LOAD_MCP_SERVER`/`LOAD_SKILL_BUNDLE`/`MODIFY_USER_CONFIRMED_STATE`）、`ZthFailureClassifier`（异常分类）。
- 审计与遥测经 `data/repository/` 落 Room，并经 `data/remote/zth/ZthFirestoreSyncManager` 同步 Firestore。

### 3.8 Hook 事件模型（HookDispatcher）

- **定位**：工具执行前后 / 用户提交 / 工作流结束等节点的扩展事件钩子（对齐 Claude Code Hook 模型，R01 为代码级骨架，见 `docs/plan-docs/claude-code-study-design.md` 第 11 节）。
- **事件集（5 种）**：`PreToolUse`（工具执行前）、`PostToolUse`（工具执行后）、`UserPromptSubmit`（用户消息提交）、`Stop`（工作流结束）、`SessionStart`（会话创建）。每种事件有独立接口与上下文类（如 `PreToolUseContext` 携带 `sessionId/toolCalls/mode`）。
- **分发器**（`domain/hook/HookDispatcher`）：Hilt 注入 `Set<HookHandler>`，构造时按事件类型分组并按 `id` 去重；每次分发对每个 handler 独立 try/catch——**异常隔离**（单个 hook 抛错不阻断后续 hook、不中断主流程），`CancellationException` 一律重抛（不吞协程取消信号），错误以 `HookOutcome.error` 上报，由调用方 `logHookFailures` 记日志。
- **声明式注册**：实现类实现对应事件接口 + 在 `domain/hook/HookModule` 以 `@Binds @IntoSet` 绑定，即自动被 `HookDispatcher` 汇集（模式对齐 `SlashCommandHandler`）。
- **挂点位置**（`StatefulAgentWorkflow`）：`UserPromptSubmit` 在 `executeEvents` 入口；`PreToolUse` 在 `ExecuteToolBatch` 起始（复用 ZTH preTool 挂点）；`PostToolUse` 在 `batchResults` 组装后逐条（复用 ZTH postTool 挂点）；`Stop` 在 `executeEvents` 的 `finally` 块。`SessionStart` **仅定义接口与注册点**，真实挂点属「会话创建处」，随会话生命周期阶段（roadmap R04）落地——不接入 `executeEvents` 入口以避免每轮用户消息冒名触发。
- **示例 hook**：`CommitDisciplineHook`（PostToolUse）静态检查 Bash 中 `git commit`/`git push` 命令文本是否符合 AGENTS.md 纪律（Conventional Commits 格式、push 前跑单测），产出结构化报告。R02 起审查发现经 `WakeQueueManager.enqueueAsync` 写入统一唤醒队列，下轮会话开始前注入用户消息前（见 3.9）；完整 system-reminder 注入机制（Stop 事件重扫、会话生命周期管理）见 roadmap R16。

### 3.9 WakeQueue 统一唤醒队列（WakeQueueManager）

- **定位**：统一唤醒队列（R02 骨架，对齐 Claude Code asyncRewake 下轮注入模型，见 `docs/plan-docs/claude-code-study-design.md` 第 11.3 / 16.2 节）。承载两类后台产出：**hook 后台审查结果**（如 CommitDisciplineHook 的纪律发现）与 **耗时任务结果**（roadmap R17 终端/MCP 后台化复用），一套机制两处消费。
- **数据结构**（Room `wake_queue` 表）：`WakeItemEntity` 字段 `wake_id`（UUID 主键）/`session_id`（归属会话，null 存空串）/`source`（来源标识，如 `hook.commit-discipline`，注入时按 source 分组）/`type`/`content`/`status`（`PENDING`→`CONSUMED`）/`created_at_ms`。持久化保证 **App 被杀不丢**，下次启动重扫待注入队列继续唤醒（重扫入口随 R16 落地）。
- **管理器**（`domain/wake/WakeQueueManager`，Hilt 单例，`@Inject constructor` 注入 `WakeQueueDao`）：
  - `enqueue(sessionId, source, type, content)`：挂起写入，content 空白跳过；
  - `enqueueAsync(...)`：非阻塞异步入队（独立 `SupervisorJob + Dispatchers.IO` scope），供同步 Hook 回调安全调用，单条失败隔离并记日志不抛到调用方；
  - `pendingForSession(sessionId)`：读取会话全部待注入唤醒（升序）；
  - `markConsumed(ids)`：注入成功后原子化标记 `CONSUMED`（防重复注入；失败保留待下轮重扫）；
  - `allPending()`：启动重扫用。
- **工作流注入挂点**（`StatefulAgentWorkflow`）：用户消息处理前读取 `pendingForSession(sessionId)`，非空则把 `buildWakeReminder` 生成的「【系统·补充审查发现】」文本拼到 `userRequestContent` 最前，随后 `markConsumed` 消费确认；读取/消费失败仅记日志跳过本轮（不阻断主流程）。

### 3.10 Agent 资产注册表（AgentAssetRegistry / AgentAssetCore）

- **定位**：方向 #1 Agent 声明式定义（R03，对齐 Claude Code agent 声明式范式，见 `docs/plan-docs/claude-code-study-design.md` 第 8 节）。把 `prompts/` 硬编码提示词升级为「frontmatter 元数据 + 正文」的 agent 资产，可热加载、可组合复用。
- **资产模型**（`AgentAsset`）：`fileName`/`name`/`description`/`order`/`enabled`/`agent`/`modes`/`tools`/`model`/`includes`/`body`。无 frontmatter 的存量文件自动回退：name 取文件名去后缀、order 取文件名数字前缀（`00-identity.md` → 0）、enabled=true、agent=false、modes=default——保证迁移前装配结果与硬编码顺序一致。
- **注册表**（`domain/prompt/AgentAssetRegistry`，Hilt 单例）：
  - 扫描 `~/.rcodecore/prompts/`（内置默认副本，启动由 `ContainerInstaller.extractPrompts` 全量释放）+ `prompts.custom/`（用户覆盖，同名即覆盖元数据与正文）；
  - `components()`：主 agent 组件（enabled 且 `agent:false`）按 order 排序；`agents()`：专项 agent（`agent:true`）；`all()`：全部（含 disabled）；`findByName(name)`：按 name 精确查找；
  - **includes 组合引用**：按 name 递归展开正文（被引用方正文先序拼接），循环引用跳过防无限递归；
  - **热加载双机制**：mtime 懒刷新（主，读取时比对目录指纹（mtime,size），对齐 `ProjectRuleSource`）+ FileObserver（辅，监听两目录增删改 → `invalidate()` 失效缓存）；FileObserver 被回收/inotify 超限时 mtime 仍兜底。
- **纯解析核心**（`AgentAssetCore`，同文件，无 Android 依赖可 JVM 单测）：目录指纹 = 两目录 `.md` 文件的 (mtime,size) 映射（custom 同名覆盖），指纹未变复用缓存；三级优先级读正文 `prompts.custom/ > prompts/ > assets`；本地目录为空时兜底 assets 清单。
- **接入**：R04 已落地——`StaticRuleSource` 改为从 registry 读取（删硬编码片段列表），`PlanModeSource`/`AutoModeSource` 删除并由 `mode` 字段统一（见 3.11）。

### 3.11 Prompt 资产装配与 `/agent` 切换（R04 落地）

- **装配改造**（`SystemPromptProvider`）：注入 `AgentAssetRegistry`，`StaticRuleSource.build(ctx)` 每次从 `registry.all()` 取全量资产：
  - 主 agent（`ctx.currentAgentId == null`）：过滤 `enabled && !agent`，按 `modes` 注入——`modes` 含 `"default"` 恒注入；含当前模式关键字（`plan`/`auto`，由 `ctx.mode` 映射）时在对应模式注入；按 `order` 排序、includes 已展开。旧 `PlanModeSource`/`AutoModeSource` 及其字段删除，`resolvePrompt`/`containerInstaller`/`context` 依赖一并清理。
  - 专项 agent（`ctx.currentAgentId` 命中 `enabled && agent` 资产）：正文整体替换为主 agent 装配结果。
- **会话级切换**（`AgentContext.currentAgentId`，默认 null = 主 agent；`AIAgentViewModel` 以会话级内存 map `currentAgentIds` 承载，重启回退主 agent，不引入 DB 迁移）。
- **`/agent` 命令**：新增 `domain/command/AgentCommandHandler`（trigger=`/agent`，`matches` 支持带参前缀），经 `SlashCommandModule` multibinding 注册；`SlashCommandHandler` 增加 `executeWithInput(context, input)` 默认方法（转发到无参 `execute`），`runSlashCommand` 改调带输入入口，现有 `/status`/`/compress` 不受影响。`SlashCommandContext` 新增 `listAgents()`/`switchAgent(name)`：
  - `/agent` 无参 → 列出 `registry.agents()`（表格气泡，含当前 agent 与用法提示）；
  - `/agent <name>` → `switchAgent`：非法/不存在回「未找到」；与当前相同 → 恢复主 agent；否则切换并回「已切换」。结果经 `MessagePersistenceUseCase` 以 AI 气泡落库。
  - `tools`/`model` 仅建议语义（不切换 provider、不拦截工具），对齐 design 8.5。
- **资产迁移**：11 个内置 `assets/prompts/*.md` 均添加 frontmatter（`00-identity`~`70-skills-and-mcp` 为 `mode:[default]` 组件；`80-plan-mode` 为 `mode:[plan]`；`81-auto-mode` 为 `mode:[auto]`），装配结果与原硬编码顺序一致。

### 3.12 规范流程基座（norm-chain D1，见 `docs/plan-docs/norm-chain-design.md`）

- **step 前注入纪律**（§3.1.2）：`SystemPromptProvider.stepSources` 一次登记 8 个 `PromptSource`（八源），每轮 step 前由 `buildStepInjections(ctx)` 走 `StepInjectionAssembler` 装配：
  - **八源排序（理解优先）**：goal（P0 order 0）→ 问判 `IntentAskSource`（P1,1）→ 行为模式 `BehaviorModeSource`（P1,2）→ plan-pending `PlanPendingHintSource`（P1,3）→ playbook-stage `PlaybookStageSource`（P1,4，D5 预留）→ `GoalAdjustEventSource`（P1,5）→ `GoalStaleSource`（P2,6）→ `LoopAdvisorySource`（P2,7）。
  - **预算裁剪**：注入块总字符默认上限 800，超限从注入顺序倒序迭代裁剪，先裁 P2（loop→stale）再裁 P1（adjust→playbook→plan-pending→行为→问判），**P0 永不裁**；全部被裁或空输入返回 null（不注入）。
- **六段式工具流水线**（§3.1.3）：`StatefulAgentWorkflow.runToolSync` 按契约注释实现 pre-execute（门：L5 结果缓存短路、viewImage 守卫）→ guard（护栏链）→ execute（执行）→ post-execute（可改写结果：媒体剥离、文件观察版本更新）→ finalizeContent（结果定型：`toolOutputStore.process` canonical 化）→ result（只读观测）。
- **guard 护栏链**（D1-3/4）：`Set<ToolGuard>` 经 `GuardModule` 的 Dagger `@IntoSet` 汇集注入，首个 `BLOCK` 短路（工具不执行直接返回错误）；`FileObservationGuard` 仅拦截 `editFile`：未观察已存在文件 → `FS_NOT_OBSERVED`（提示先 readFile）、观察后 mtime 变化 → `FS_STALE`（提示重读）、新建豁免、readFile 观察/writeFile 即已知（post-execute 段 `markObserved` 更新版本）；容器/终端内 shell 写不逐条拦截（靠 SOP/prompt 纪律约束）。
- **统一开关基础**（§3.5）：总开关 `norm_flow_enabled` + 子开关 `step_inject_enabled`/`tool_guard_enabled`（`settings` 模块 `NormFlowSettingsRepository` 经 DataStore 持久化）；workflow 分别经 `isStepInjectActive()`/`isToolGuardActive()` 判定是否启用 step 前注入与 guard 链，设置页「规范流程」分区（`NormFlowSection`）提供开关 UI。
- **闭环核对**（§3.1.1）：prompts 资产 `60-tools-and-paths.md` 补充文件观察纪律（`FS_NOT_OBSERVED`/`FS_STALE` 错误码）与 `TOOL_TIMEOUT` 结构化超时说明，与既有 goal 注入/循环提醒能力对齐接入。

### 3.13 Playbook 剧本编排（D5，见 `docs/plan-docs/norm-chain-design.md` §3.3 / §3.6）

- **剧本资产与注册**（D5-1/2）：`PlaybookRegistry` 扫 `assets/playbooks/` 加载 3 份剧本（bug-fix/code-review/feature-dev），复用 `AgentAssetCore` frontmatter 解析 + mtime 懒刷新；`PlaybookAsset` 由多阶段 `stages[]` 组成，每阶段含 `description`/`agents[]`（子代理配置 + `PlaybookSeed` spawn/fork）/`sop` 引用/`gates`（`PlaybookGate.APPROVAL/AUTO`）/`guards`。
- **双状态机执行**（D5-3/8）：`PlaybookRunEntity`（agent_playbook_runs 表）持久化双状态机——运行级 RUNNING/COMPLETED/ABORTED/INTERRUPTED + 阶段级 PENDING/ACTIVE/DONE/FAILED；`PlaybookExecutor` 提供 start/advance/status/abort/resume/retry/interrupt；**产物清单幂等**：阶段 DONE 记录 artifacts，resume/retry 时注入对照跳过已完成操作；**完成判定护栏**：连续 N 轮无实质工具动作声明完成 → advisory 提醒确认阶段产出物；会话停止置 INTERRUPTED、可 resume，失败阶段可 retry。
- **阶段注入与挂起 goal 双信号**（D5-5）：`PlaybookStageSource`（P1，八源第 4 位）把当前剧本名/阶段/目标注入 step 前块；运行期把当前阶段目标挂到 goal，暂停双信号（`GoalStale`/`GoalAdjustEvent` 不注入）避免阶段内误判语义失配。
- **子代理执行**（D5-6/7）：`SubAgentRunner` 在阶段激活时执行 `agents[]`——spawn/fork 双 seed（上下文隔离：独立消息/工具/工作目录），三档 `SandboxMode` 权限降权（READ_ONLY/WORKSPACE_WRITE/DANGER_FULL_ACCESS），每子代理一协程 `async` + `awaitAll` 并行聚合（按声明顺序返回），阶段内共享 `Mutex` 把写档位工具调用串行化（读保持并行）；产出按内容写入天然幂等。
- **工具与命令**（D5-4）：4 工具 `playbook_start/advance/status/abort`（`domain/tool/playbook/`）+ `/playbook <name>` 斜杠命令（`domain/command/PlaybookCommandHandler`）；**清单可见 + 精确匹配**，未命中回退 plan/goal。
- **审批与开关**（D5-9 / D5-pa）：阶段 `gates=approval` 时推进需用户批准，用户消息首 token 为 `!` 时跳过当次流程级 approval gate（`markForceApproval`/`consumeForceApproval`，不绕权限系统）；总开关 `norm_flow_enabled` 关闭则 Playbook 整体不可用；子开关 `playbook_auto_enabled`（`playbook_auto`，默认开）关闭后模型不能自主 `playbook_start`，`/playbook` 命令不受影响。

### 3.14 分层规则纪律（D3，见 `docs/plan-docs/norm-chain-design.md` §3.9）

- **四级规则资产**（D3-1）：`RuleLayer` 定义全局（`~/.rcodecore/global-rules.md`）/ 项目（`AGENTS.md`，权威源）/ 工作区（工作区根 `workspace-AGENTS.md`）/ 模块（`feature/<module>/AGENTS.md`）四级，frontmatter 可声明 `priority`（数值大优先，缺省按层级 10/20/30/40 递增）；`RuleRegistry`（装配 `RuleAssetCore` 纯解析核心，复用 `AgentAssetCore` frontmatter 解析 + mtime 懒刷新）按 priority 降序拼接合并，同 priority 靠后声明者优先。
- **三级常驻 + 模块级按需注入**（D3-2）：`resident` 只注入全局/项目/工作区三级；模块级规则按**文件观察命中路径**判断——`RuleRegistry.touchedModulePaths(projectRoot)` 遍历 `ToolResultCache.touchedPaths()`（readFile 观察 / writeFile 即已知的路径集合），命中 `/feature/<module>/` 段即取模块目录名，`moduleRules` 只注入本会话触碰过的模块规则；`SystemPromptProvider.RulesSource`（step 前注入）把 `resident + moduleRules` 合并后按 priority 注入摘要。
- **摘要/正文两级**（D3-3）：常驻只注入 `summary`（frontmatter `summary` 优先，否则正文首段，默认 120 字符），完整正文经 `/rules` 命令（`RulesCommandHandler`，列出四级规则清单或加载指定规则正文）或 `load_rule` 工具（`LoadRuleTool`，按名称精确查找，不存在返回 `RULE_NOT_FOUND`）显式加载。

### 3.15 思维链路 + 步骤结果汇总（D2，见 `docs/plan-docs/norm-chain-design.md` §3.7 / §3.8）

- **空转软收敛**（D2-1，§3.7.1）：`StatefulAgentWorkflow` 维护 `idleRounds` 计数器——每轮统计实质产出（文件写 / 命令执行 / run_code 命中 `SUBSTANTIAL_TOOLS` 即清零；`readFile` 读到**新文件路径**视为产出性读动作也清零，仅反复重读同类文件才累计空转）；连续 `IDLE_CONVERGE_ROUNDS`（6）轮无实质产出 → 在 CallLlm 前**强制结束回合**（区别于 LoopGuard 的 advisory），把 `TrajectoryService.buildActionSummary` 生成的已做动作摘要 + 结束原因返回用户；与 `MAX_ITERATIONS`（50）硬上限、`LoopGuardTracker`（3/5/8 advisory）构成三级防线。**空转收敛子开关**（`NormFlowSettingsRepository.idleConvergeEnabledFlow`，默认关）：`isIdleConvergeActive()`（总开关 && 子开关）每轮 CallLlm 前判定，关闭时即使累计达标也不收敛——因 `SUBSTANTIAL_TOOLS` 未覆盖 `websearch/browser` 等研究/浏览动作，默认关避免此类请求被误伤结束。
- **推理预算**（D2-2，§3.7.2）：子开关 `reasoning_budget`（`NormFlowSettingsRepository`，默认开）+ 总开关开启时，workflow 每轮取 `currentContext.reasoningEffort` 透传给 `provider.completeStream`（关闭则 null 禁用推理参数）；模型返回 reasoning 时经 `reasoningAcc` 累积逐段发 `AgentEvent.ReasoningDelta` 流式呈现（`AIChatPanel` 折叠面板，复用 AssistantText reasoningAcc 通道），DeepSeek `reasoning_content` / Anthropic / OpenAI reasoning 均支持；reasoning 随 AssistantMessage 进入后续轮次并计入压缩 token。
- **运行轨迹表**（D2-3，§3.8.1/2）：`agent_trajectories`（`TrajectoryEntity`，append-only，v2→v3 迁移 + `DataRegistry` 登记 + `LightweightSchemaRescue`）字段 trajectoryId/sessionId/taskId/turnIndex/kind/toolName/argsHash/resultSummary/isError/durationMs/tokensIn/tokensOut/ts；workflow 每次工具执行完成追加 tool 轨迹，turn 边界/压缩/注入/错误/超时追加轻量标记；`resultSummary` 经 `ToolResultTypeRegistry.registerTrajectorySummarizer` 定制提取（readFile 路径+行数 / writeFile 目标 / editFile 状态 / run_code exit+stdout 尾 / Bash 命令），其余通用截断。
- **用量卡片**（D2-4，§3.8.4）：回合结束 `TrajectoryService.turnUsage`（本回合增量：输入/输出/总 token、耗时、工具数）+ `sessionUsage`（会话累计，仅 token 不估成本）经 `AgentEvent.TurnUsage` 发送，`AIAgentViewModel` 落库为 usage 工具卡片（Room Flow 驱动渲染）；子开关 `usage_card` 关闭则不发（均默认开，受总开关管控）。
- **轨迹消费**（D2-5，§3.8.3）：`buildActionSummary` 生成已做动作摘要（空转收敛返回 / Playbook 阶段总结复用）；`getTrajectory`/`getByTask` 供审计回放；删除会话时 `TrajectoryDao.deleteBySession` 级联清理。

### 3.16 SOP 标准作业（D4，见 `docs/plan-docs/norm-chain-design.md` §3.2）

- **资产与注册**（D4-1/2）：`SopRegistry`（装配 `SopAssetCore` 纯解析核心，复用 `SkillParser.splitAndParseFrontmatter` frontmatter 解析 + mtime 懒刷新）扫 `~/.rcodecore/sop/`（内置默认副本经 `ContainerInstaller.extractSop` 启动全量释放）；`SopAsset` 独立结构 `name`/`order`/`whenToUse`/`body`（与 `AgentAsset` 解耦），frontmatter 缺省回退文件名/数字前缀/正文首段；`assets/sop/` 共 6 份：10-release（发版）/20-migration（迁移）/30-asset-sync（资产同步）/40-git-commit（提交）/50-troubleshooting（排障）/60-ai-conduct（行为纪律步骤化），正文均为编号步骤「操作 + 判定 + 产出/出错处理」，头部注明权威源（10-50 对齐 `AGENTS.md`、60 对齐 `prompts/`）。
- **摘要常驻 + 按需取正文**（D4-3/4）：`SystemPromptProvider.SopSource`（step 前注入，importance=P1，八源第 7 位 order 7，走预算裁剪）全量常驻注入 SOP 清单摘要（名称 + whenToUse 一句话，不做 mode 过滤）；完整正文经 `loadSop` 工具（`domain/tool/sop/LoadSopTool`，参数 `sop_name`，按名称精确查找，不存在返回 `SOP_NOT_FOUND`）按需加载。**sop_summary 子开关**（`NormFlowSettingsRepository`，默认开）：`isSopSummaryActive()` 每轮控制摘要注入开合，`loadSop` 取正文不受影响。
- **SOP/Skill 双判据边界**（D4-4）：主判据按适用范围——SOP = 仓库内固定操作流程（绑项目语义，摘要常驻注入）；Skill = 通用可复用技能（用户可增删的技能中心）。辅助判据按步骤化程度——SOP 严格编号步骤；Skill 可非步骤化。双判据同时满足才归 SOP；区分指引写入 `prompts/70-skills-and-mcp.md`（§技能）。
- **权威源同步提示**（D4-5）：`.githooks/spec-check.sh` 第 3 段（warning 级、不阻断）——本次提交改 `AGENTS.md` → 提示检查 `sop/10-50` 同步；改 `prompts/15-project-rules.md` / `40-approach.md` → 提示检查 `sop/60-ai-conduct` 同步。

## 4. 对外接口与集成点

- **被谁调用**：`presentation/AIAgentViewModel` 是 UI 唯一入口（被 Compose 聊天界面使用）；`StatefulAgentWorkflow` 由 ViewModel 驱动；其余领域服务（ToolRegistry、McpManager、SystemPromptProvider、CheckpointManager 等）均为 Hilt 单例，可被本模块内或外部注入。
- **Room 数据库**：agent 域独立库 `AgentDatabase`（v1）只承载 agent 自身 17 张表（消息/会话/Todo/检查点/技能状态/模式切换/模型能力覆盖/ZTH 审计/wake_queue）。settings（`AIProviderEntity`）、workspace（`RemoteConnectionEntity`/`RemoteMountEntity`/`RemoteAuditLogEntity`）、credentials（`GitCredentialEntity`）、t2i（`T2IProviderEntity` 等）已随数据层重构（新写法）拆到各自 feature 域的独立库（见 `di/DatabaseModule`）；旧单巨库数据由 `DbSplitMigrator` 一次性移植。
- **AI Provider**：`AIProvider` 接口 + `OpenAIAdapter`/`AnthropicAdapter`/`GeminiAdapter`；支持 reasoning（回传签名/思考文本）、token 统计、`stopReason` 截断续写；底层走 `data/remote/*` 的 Retrofit API。**网络层优化**：共享 OkHttp 连接池/DNS 缓存/连接预热与代理分流见 `docs/modules/core.md` 与 `docs/modules/proxy.md`；流式 SSE 解析为定点字段抽取（P1），工具调用路径（Anthropic `partial_json`、OpenAI/Gemini `functionCall`）保留完整 JSON 累积。
- **Bridge 能力**：`RcbBridge` 在 loopback 随机端口起 TCP 服务，`RCB_BRIDGE_TOKEN` 注入容器，容器内 `rcb-*` helper 经 base64 行协议调用宿主侧剪贴板/URL/通知能力；`openUrlHandler` 由宿主注入（默认仅记日志）。
- **外部模块依赖**：workspace（`FileAccessProvider`、`WorkspacePathMapper`、`WorkspaceRepository`、远程连接/挂载）、settings（AI Provider 配置）、t2i（图片生成）、core（`FileLogger`、`HostKeyManager`、加密凭据）。
- **DAOs（本模块）**：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`SkillStateDao`、`ModeSwitchHistoryDao`、`ModelCapabilityOverrideDao`、`CheckpointDao`、`CheckpointFileSnapshotDao`、`FileEditHunkDao`、`ZthTelemetryEventDao`、`UserConfirmedSentinelDao`、`HallucinationFuseDao`、`SentinelPlanRejectionAuditDao`、`HardConstraintDeleteAuditDao`、`L0SoftCompactRestoreLogDao`、`WakeQueueDao`。

## 5. 关键设计点与约束

- **权限引擎**（`ToolPermissionPolicyEngine`）：判定 `ALLOW/DENY/ASK`。shell 命令（Bash/terminal.start）按指令级前缀匹配；评估顺序为 DENY 优先 → 不可静态判定必须 ASK 且不可记忆 → 内置安全白名单（`BuiltInSafeCommands`，ls/git status 等）→ 已记忆 ALLOW → 否则 ASK。`PLAN` 模式物理沙盒拒绝一切写/执行类工具；`AUTO` 模式放行全部权限但仍保留灾难性 rm 防护（禁止根目录/系统目录/工作区根/通配删除）。修改 Agent 配置、容器环境、外部动态工具等能力不可记忆授权，仅单次放行。
- **工具结果缓存**（`ToolResultCache`）：`(toolName, argsHash, sessionId)` 键控，TTL 默认 60s；文件类工具（`readFile`/`search`/`list`）叠加文件 mtime 失效；L7 事件总线在文件被写/删后广播强制失效。
- **重试策略**：`RetryPolicy` 默认对 `NETWORK`/`TRANSIENT` 类错误指数退避 + 抖动自动重试（上限 3 次）；错误码按前缀推断分类，工具可显式声明 `errorClass` 覆盖。
- **持久化**：消息、会话、Todo、技能启用状态、模式切换、模型能力覆盖、检查点快照与全套 ZTH 审计均落 Room；检查点文件快照存 `<filesDir>/checkpoints/<sessionId>/<checkpointId>/`，同一 checkpoint 内对同一文件只保留最原始一份，支持回滚。
- **上下文压缩**：`ContextCompactor` 在历史超过阈值时自动压缩，也可经 `/compress` 手动触发；压缩结果内部持久化。
- **安全**：RcbBridge 绑定 127.0.0.1 随机端口（外网不可达）+ 首行令牌鉴权；`share` 能力默认不支持（防数据渗出）；`open_url` 默认不真正打开。`PLAN` 模式只读、工具权限最小化、MCP/Skill 加载能力受 ZTH 能力守卫管控。
- **会话与工作区一对一绑定**：会话创建时把当时的当前工作区路径写入 `ChatSession.workspacePath`，之后**不可中途切换**（会话列表按工作区路径过滤，UI 无改绑入口）；一个工作区可被多个会话绑定。工作区重命名时由 `WorkspaceRepository.renameWorkspace` 经 `ChatSessionDao.updateWorkspacePath` 批量迁移绑定路径。侧边栏「所有工作台 → 查看对话绑定」经 `AIAgentViewModel.sessionsBoundToWorkspace(path)` 查询某工作区绑定的会话。
- **侧边栏（ChatDrawer）**：顶部 tab 导航高 44dp 与全局标题栏一致；「对话列表」tab 不含新建按钮（新建入口在聊天页顶栏）；「工作目录」tab 文件树点击文件跳转独立阅读页（`MainActivity` 记录 `reopenDrawerAfterFileReader`，退出阅读页自动重开侧边栏并保留所在 tab）；工作区列表行点击弹出下拉菜单（切换/重命名/删除/查看对话绑定-手风琴）；底部按钮栏左侧为设置（图标+文字）、右侧为主题切换纯图标，两侧留边距。
- **对话列表（会话列表）**：数据源 `AIAgentViewModel.sessionsWithCount`（`ChatSessionDao.getAllWithCount` LEFT JOIN 计数投影 `ChatSessionWithCount`，保留工作台过滤）。列表按 `updatedAtMs` 四档分组吸顶（今天/昨天/7天内/更早，`SessionListFormat.sessionBucket`），组头吸顶显示分档名+计数（`LazyColumn` stickyHeader）；列表项两行增强（标题 + 「时间 · N 条消息」：今日时钟/昨日「昨天」/7天内「N 天前」/更早日期），选中高亮（primaryContainer 0.45 alpha + 字色/字重）、执行中绿色呼吸点。**删除**：左滑（`SwipeToDismissBox` 仅 EndToStart、始终回弹上抛确认框）→ 确认框（执行中会话额外提示「删除将中断其任务」）→ `SessionUseCase.deleteSession` 事务化删除（级联清理 9 张关联表）→ Snackbar「已删除 · 撤销」（ViewModel 缓存最近删除会话+消息，`undoDeleteSession` re-insert 恢复）；长按菜单（重命名/导出/删除）路径保留。**重命名**：上限 20 字（`SessionUseCase.updateTitle` 截断对齐 `TITLE_MAX`），不更新 updatedAt、列表顺序不变。**删当前会话重选**：同工作台最近 → 最近未绑定 → 全局最近 → 置空。
- **终端输出**：`BoundedOutput` 限制命令输出长度，避免超长回填污染上下文；进度类输出由 `progress/` 解析器聚合。

## 6. 维护与扩展指引

- **新增工具**：在 `domain/tool/<分类>/` 下实现 `AgentTool` 子类（流式输出再实现 `StreamingAgentTool`），声明 `name/description/parameters/capabilities/permissionPolicy`（按需 `provides/consumes/dependsOn/subscribedEvents/retryPolicy`）；在 `di/AgentModule`（Hilt `@Binds @IntoSet`）注册；若工具结果应被缓存，需同步登记进 `ToolResultCache.FILE_TOOLS` 相关集合，否则缓存静默失效。
- **新增斜杠命令**：实现 `SlashCommandHandler` + `@Binds @IntoSet` 即自动纳入 `SlashCommandRegistry`（无需改注册表），建议同时提供 `trigger`/`matches`/`filterByPrefix` 语义。
- **新增 Hook**：实现对应事件接口（如 `PostToolUseHook`）+ 在 `domain/hook/HookModule` 以 `@Binds @IntoSet` 绑定，即自动被 `HookDispatcher` 汇集；若事件逻辑需同步到模型可见行为，需同步检查 `assets/prompts/` 提示词与 `assets/docs/` 使用文档。hook 内不得吞 `CancellationException`，不得阻塞主流程（骨架阶段仅同步计算，耗时逻辑应内部异步化）；后台审查/耗时任务的产出走 `WakeQueueManager.enqueueAsync` 写入唤醒队列，下轮会话自动注入。
- **新增 AI Provider**：实现 `AIProvider` 接口，新增 Adapter（参考 `OpenAIAdapter`/`AnthropicAdapter`/`GeminiAdapter`），在 `data/remote/` 加 Retrofit API，并在 Provider 选择处注册。
- **新增 DAO/实体**：agent 域新增 DAO/Entity 时，在 `data/local/` 新增并在 `AgentDatabase` 的 `entities` 列表与抽象访问器中登记，同时把新表名加进 `core/data/DataRegistryModule`（数据注册表，保证备份/恢复全量覆盖）与 `core/db/DbSplitMigrator` 的表映射（如仍需从旧库移植）。agent 库已独立演进至 v4（v1→v2 任务编排层 / v2→v3 运行轨迹 / v3→v4 剧本运行），新增表需在 `AgentDatabaseMigrations` 提供程序化 Migration（新增表走 `CREATE TABLE IF NOT EXISTS`，无数据搬迁）并在 `DatabaseModule` 注册。
- **新增能力/权限维度**：扩展 `ToolCapability` 枚举，并在 `ToolPermissionPolicyEngine` 的危险能力集、`ZthCapabilityGuard` 能力降级表、`ToolPermissionManager` 中同步处理。
- **新增 ZTH 审计维度**：新增 DAO/Entity 与 `data/repository/` 仓库，接入 `ZthGuardAggregateFacade` 对应审计阶段，如需云端同步则在 `data/remote/zth/` 补 DTO 与 Firestore 映射。
- **新增容器能力**：扩展 `CommandEngine` 实现（本地/远程），或新增 `domain/tool/container/` 下的环境类工具；安装进度解析在 `domain/container/progress/` 扩展 `InstallProgressParser`。

## 7. 版本演进记录

> 本模块开发维度演进；用户可见变更见仓库根 [CHANGELOG.md](../../CHANGELOG.md)。

- **v0.3.0-rc3（2026-08-26）**：空转软收敛（D2-1）新增「规范流程 → 空转收敛」子开关（`NormFlowSettingsRepository.idleConvergeEnabledFlow`，默认关）：workflow 每轮 CallLlm 前经 `isIdleConvergeActive()` 判定，关闭时即使连续 6 轮无实质产出也不强制收敛，避免研究/浏览类请求（websearch/browser 不在实质产出集合）被误伤结束；设置页「规范流程」二级页新增开关行。
- **v0.3.0-rc2（2026-08-26）**：AI 工作流规范体系 D2~D6 落地——D2 思维链路 + 步骤结果汇总（空转软收敛、推理预算、运行轨迹表、用量卡片）；D3 分层规则纪律（全局/项目/工作区/模块四级规则资产 + 显式 priority + 模块级按需注入，基于文件观察命中判断）；D4 SOP 标准作业（6 份 SOP + `loadSop` 工具）；D5 Playbook + 子代理（剧本资产与执行引擎、4 个剧本工具 + `/playbook`、spawn/fork 双模式、`playbook_auto` 子开关）；D6 Spec 规范驱动（`spec-check.sh` 预检）。
- **v0.3.0-rc1（2026-08-25）**：D0 用户意图拆解基座（问判模式、行为模式、GoalStale/GoalAdjustEvent 注入）；D1 Agentic Workflow 基座（`step` 前注入链、`ToolGuard` 链式护栏、统一开关）。
- **v0.2.0（2026-08-25）**：任务编排协作（Goal/Plan/Job/Schedule 声明式扩展生态）；流式累积归一化；网络层性能优化（连接预热、SSE 定点解析、模型 host 直连分流）；危险命令静态守卫双层拦截；`/agent` 专项切换。
- **v0.1.0（2026-08-22）**：AI Agent 核心作为首个正式版落地（多 Provider、20+ 工具、流式输出、上下文压缩、多会话、PLAN/BUILD/AUTO、检查点回滚、MCP 双角色、ZTH、技能中心）。
