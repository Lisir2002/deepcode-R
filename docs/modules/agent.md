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
| `data/local/dao/` | Room DAO：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`SkillStateDao`、`SkillConversationStateDao`、`ModeSwitchHistoryDao`、`ModelCapabilityOverrideDao`、Checkpoint 系列（`CheckpointDao`、`CheckpointFileSnapshotDao`、`FileEditHunkDao`）、ZTH 审计系列（`ZthTelemetryEventDao`、`UserConfirmedSentinelDao`、`HallucinationFuseDao`、`SentinelPlanRejectionAuditDao`、`HardConstraintDeleteAuditDao`、`L0SoftCompactRestoreLogDao`） |
| `data/local/database/AgentDatabase.kt` | 全局 Room 数据库（schema v47，`exportSchema=true`），跨模块聚合 agent/settings/workspace/credentials/t2i 的表 |
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
| `domain/command/` | 斜杠命令系统：`SlashCommand`（handler 接口）、`SlashCommandRegistry`（Hilt multibinding 汇集）、`SlashCommandModule`（注入绑定）、`CompressCommandHandler`、`StatusCommandHandler` |
| `domain/container/` | 命令执行后端：`CommandEngine`（接口）、`LinuxContainerEngine`（PRoot 本地容器）、`RemoteSshEngine`（SSH exec）、`RemoteSshConnection`（共享 sshj 连接/SFTP）、`DelegatingCommandEngine`（按模式委派本地/远程）、`ContainerInstaller`、`ContainerProfile`、`ContainerInitState`、`BoundedOutput`、`GlobalInstallArchiveStore` |
| `domain/container/progress/` | 安装进度聚合：`RealProgressAggregator`、`InstallProgressParser`、`ApkStdoutParser`、`ParallelPrefetchManager`、`PrefetchConcurrencyPolicy`、`ProgressModels` |
| `domain/mcp/` | MCP 集成：`McpManager`（连接/工具注册/状态流）、`McpClient`、`McpTool`（MCP 工具适配 `AgentTool`）、`McpJsonRpc`、`McpServerConfig`、`McpConfigRepository`、`McpTransport` + `StdioTransport` / `StreamableHttpTransport`。**`server/` 子包（已实施）**：内置 MCP 服务器（`McpServerManager`/`McpHttpServer`/`McpServerSession`/`AgentToolMcpAdapter`/`McpServerSecurity`/`McpServerSettings`），把 App 能力开放给外部 MCP 客户端，见 [builtin-mcp-server-design](../plan-docs/builtin-mcp-server-design.md) |
| `domain/memory/` | 记忆系统：`Memory` 模型（GLOBAL/PROJECT 作用域）、`MemoryParser`、`MemorySource` + `GlobalMemorySource`/`ProjectMemorySource`、`MemoryRepository` |
| `domain/model/` | 领域模型：`AgentMessage`（含 `AgentContext`：currentFile/selectedCode/projectRoot 等）、`ChatSession`（含 `AgentMode`：BUILD/PLAN/AUTO）、`CodeChange`、`ReasoningEffort`、`TodoItem` |
| `domain/permission/` | 权限引擎：`ToolPermissionPolicyEngine`（ALLOW/DENY/ASK 判定）、`PermissionRulesRepository`、`ShellCommandParser`、`BuiltInSafeCommands`、`BuildCommandClassifier`、`PermissionModels`、`ZthFailureModels` |
| `domain/prompt/SystemPromptProvider.kt` | 增量式系统提示词：按 `PromptSource` 组装静态规则/计划模式/工作区上下文等片段，维护缓存与快照 |
| `domain/provider/` | AI Provider 抽象：`AIProvider`（complete / completeStream，含 reasoning、signature、token 统计）、`OpenAIAdapter`、`AnthropicAdapter`、`GeminiAdapter`、`RetryPolicy`、`HttpErrorEnricher`（把 HTTP 错误体拼进 message） |
| `domain/session/` | 会话用例：`SessionUseCase`、`MessagePersistenceUseCase` |
| `domain/skill/` | 技能系统：`Skill` 模型（PROMPT/SCRIPT/MCP 三形态、BUILTIN/LOCAL 来源）、`SkillParser`、`SkillRepository`、`SkillExecutor`、`SkillSource` + `LocalDirectorySkillSource`、`BuiltinSkillSeeder`（首启引导内置技能）、`SkillStateRepository`（Room 持久化启用状态） |
| `domain/tool/` | 工具系统（详见 2.3） |
| `domain/workflow/` | Agent 工作流：`AgentWorkflow`（接口 + `AgentEvent` 事件集）、`StatefulAgentWorkflow`（MVI 状态机实现）、`ContextCompactor`（上下文压缩） |
| `domain/zth/` | ZTH 零信任防护：`ZthGuardAggregateFacade`（聚合门面）、`ZthCircuitBreakerManager`、`ZthConfirmationCardManager` + `ZthConfirmationCardStateMachine`、`ZthPlanApprovalManagerWrapper`、`ZthContentReviewer`、`ZthToolOutputGuard`、`ZthCapabilityGuard`、`ZthFailureClassifier`、`ZthWorkflowHooks`、`ZthDomainModels`、`TerminalBundleMirrorRotator` |

### 2.3 domain/tool 工具系统

| 路径 | 职责 |
| --- | --- |
| `tool/AgentTool.kt` | 工具基类：`ToolResult`（Success/Error/Partial）、`ToolParameter`、`ToolCapability`、`ToolPermissionPolicy`、`RetryPolicy`/`ToolErrorClass`（L3 错误分类）、`provides/consumes`（L3 结果协议）、`dependsOn`（L4 依赖）、`subscribedEvents`（L7 事件）、`buildPostExecutionEvent`（L7 事件自声明钩子，事件由工具自声取代工作流硬编码 mapping）、`StreamingAgentTool` 流式接口、`ToolCall` |
| `tool/ToolRegistry.kt` | 单例工具注册表（`ConcurrentHashMap`），注册/查找/列出可用工具 |
| `tool/ToolResultCache.kt` | L5 结果缓存：会话级 + TTL（默认 60s），文件类工具按 mtime 失效 |
| `tool/ToolResultTypeRegistry.kt` | L3 结构化结果类型登记 |
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
| `tool/search/` | `WebSearchTool`、`WebFetchTool` |
| `tool/skill/LoadSkillTool.kt` | 加载技能指令正文（PROMPT/SCRIPT 通用，仅返回 SKILL.md、不执行；含依赖注入、作用域校验、工具绑定） |
| `tool/skill/RunSkillScriptTool.kt` | 执行 SCRIPT 脚本技能（容器沙箱 + 审批 + 审计，专用执行入口） |
| `tool/skill/SkillInvocationResolver.kt` | loadSkill / runSkillScript 共用的技能定位与校验解析器 |
| `tool/storage/StorageTool.kt` | 设备（外部共享）存储读写，带安全护栏 |
| `tool/todo/TodoTool.kt` | 管理 TODO 列表 |
| `tool/AbstractContextualTool.kt` / `CodeSelection.kt` | 上下文工具基类与代码选区模型 |

### 2.4 presentation 层（UI 状态与组件）

| 路径 | 职责 |
| --- | --- |
| `presentation/AIAgentViewModel.kt` | Agent 主 ViewModel：`executeAgentRequestStream` 启动工作流、管理会话/工具权限/消息落库、实现 `SlashCommandContext` |
| `presentation/ZthConfirmationCardViewModel.kt` | ZTH 确认卡片 UI 状态 |
| `presentation/AgentUiModels.kt` | UI 层模型（`AgentAttachment`、`AgentImage` 等） |
| `presentation/EnvironmentSnapshotStore.kt` | 环境快照存储 |
| `presentation/component/` | Compose 组件：`AIChatPanel`、`ChatInputBar`、`MessageBubbles`、`ToolMessageComponents`、`AskUserQuestionPanel`、`ZthConfirmationCardSheet`、`TaskAccordion`、`TodoCardComponents`、`WebSearchResultComponents`、`FileDiffSheet`、`ChatModelSheet`、`ChatSessionPicker`、`ChatDrawer`、`MarkdownContent`、`RichSegmenter`（`component/richsegment/` 富文本分段）等 |

## 3. 核心架构与主流程

### 3.1 Agent 工作流（AgentWorkflow / StatefulAgentWorkflow）

1. UI 层 `AIAgentViewModel.executeAgentRequestStream(...)` 组装用户请求、上下文（currentFile/selectedCode/projectRoot/输入图片/附件）、目标会话与可用工具集。
2. 调用 `StatefulAgentWorkflow.executeEvents(...)`，以 `channelFlow` 向外推送 `AgentEvent`（`AssistantText`/`AssistantDelta`/`ReasoningDelta`/`ToolCallStarted`/`ToolCallProgress`/`ToolCallFinished`/`Retrying`/`CompactionStarted`/`Failed`/`Completed`/`ModeChanged` 等）。
3. 循环体：用 `AIProvider.completeStream`（SSE 流式）把 systemPrompt + 历史消息 + 工具定义发给模型 → 收到文字增量实时推送、收到 `tool_calls` 进入工具阶段 → 逐工具做权限评估 → 执行（流式工具走 `executeStream`，逐行 `ToolCallProgress`）→ 把 `ToolResult` 序列化回填上下文 → 再请求模型，直到模型不再调用工具或达到迭代上限。
4. 工作流以不可变 `AgentSessionState` + reducer 的 MVI 方式管理状态，覆盖：批量工具调用（`batchToolCalls`）、待审批权限调用（`pendingPermissionCalls`）、被拒工具结果（`rejectedToolResults`）、视觉输入轮（`pendingVisionRound`/`visionFallbackRetried`）、上下文压缩等。
5. 错误处理：网络首字节前失败自动重试（`Retrying` 事件）；`max_tokens`/`length` 截断自动续写；超过阈值自动 `ContextCompactor` 压缩；`/compress` 命令可手动触发 `compactSession`。

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
- **执行注入（双段注入）**：命中则走 `SkillExecutor.execute`（PROMPT 注入正文、SCRIPT 走既有 ZTH 审批；**MCP 包装技能不参与自动触发**——已降级为别名，由模型按需直接调用绑定 MCP 工具）。注入内容为**双段结构**——`【技能规则】`（SKILL.md 正文 `skill.instructions`，SCRIPT 技能自动触发时一并纳入，含检查项/修复口径/计划引导/纪律）+ `【执行报告】`（本次执行 stdout/正文），合并进最后一条用户消息（技能在前、用户请求在后），保证模型必然以该消息为当前任务并读取其中规则；同时向 UI 推送 `AutoTriggered` 事件落库工具卡片，用户可直观看到「某技能被自动触发并加载」的结果。SCRIPT 自动触发执行前仍弹确认卡（审批卡标题带【自动触发】标记，安全审批链路不变），用户拒绝/超时降级为文本注入不阻塞主流程；**仅成功执行后才标记会话级去重**，失败/被拒可重试。
- **注入权威性加固**：自动触发技能的结果仅以「独立 UserMessage」注入时，轻量/事实问答类模型会把它当作「无关历史」忽略，只按最后一条用户消息作答。三重保障：(a) **合并进最后一条用户消息**（`技能规则+执行报告\n\n【本次用户请求】\n用户请求`），模型必然读到；(b) **SCRIPT 技能注入其 SKILL.md 规则正文**（此前只注入脚本 stdout 摘要，模型看不到规则细节、也没有 AGENTS.md 等文档的内容来源，导致"技能要求补全的规则文档不会补全"）；(c) **系统提示词末尾追加【系统】硬性指令**，把技能输出从「建议/参考」升格为「本任务必须逐条落实的前置条件」，并明确「技能报告指出的缺失项（AGENTS.md/README.md/模块文档/环境组件/记忆）必须在执行中补全，不确定时用只读工具核实或向用户澄清」。合并方式也避免了「连续多条 User 消息」触发 Anthropic 等要求 user/assistant 交替的 API 报错。
- **会话级去重**：`ToolSessionState` 新增 `autoTriggeredSkills` 集合，同一技能在同一会话内最多自动触发一次。
- **已启用声明**：`coding-preflight`（编程前）与 `pre-commit-health`（提交前）两个内置 SCRIPT 技能已在 frontmatter 声明 `auto_trigger: true` + `trigger_conditions` + `trigger_keywords`。
- **内容升级机制（根治：保证改动到达老设备）**：`BuiltinSkillSeeder` 的内置技能升级判定为「**version 或内容 hash 任一不一致即覆盖**」双保险——(a) frontmatter `version` 显式 bump 时覆盖；(b) 即便忘记 bump version，只要技能内容（SKILL.md / entry / 其它资产）与 assets 侧不一致（如新增 `auto_trigger` 字段），也会按「相对技能目录路径 + 内容」的 SHA-256 组合 hash 比对触发覆盖。从根上避免「只改 frontmatter 却因 version 未变导致改动永不生效」的问题。
- **决策点**：仅通过作用域过滤（对当前会话可见）的技能参与自动触发；AGENT 级需当前 agent 匹配、CONVERSATION 级需本对话已添加。

### 3.7 ZTH 零信任防护（ZthGuardAggregateFacade）

- 聚合门面串联四方联动：熔断器（`ZthCircuitBreakerManager`）、计划审批（`ZthPlanApprovalManagerWrapper`）、确认卡片（`ZthConfirmationCardManager` + `ZthConfirmationCardStateMachine`）、检查点（`ZthCheckpointRepository`）。
- 在计划前、工具调用前、工具完成后、异常抛出四处挂审计钩子（`ZthWorkflowHooks`），配合 `ZthContentReviewer`（幻觉内容审查）、`ZthToolOutputGuard`（工具输出守卫）、`ZthCapabilityGuard`（能力降级，如 `LOAD_MCP_SERVER`/`LOAD_SKILL_BUNDLE`/`MODIFY_USER_CONFIRMED_STATE`）、`ZthFailureClassifier`（异常分类）。
- 审计与遥测经 `data/repository/` 落 Room，并经 `data/remote/zth/ZthFirestoreSyncManager` 同步 Firestore。

## 4. 对外接口与集成点

- **被谁调用**：`presentation/AIAgentViewModel` 是 UI 唯一入口（被 Compose 聊天界面使用）；`StatefulAgentWorkflow` 由 ViewModel 驱动；其余领域服务（ToolRegistry、McpManager、SystemPromptProvider、CheckpointManager 等）均为 Hilt 单例，可被本模块内或外部注入。
- **Room 数据库**：`AgentDatabase`（v46）聚合跨模块表——agent 自身（消息/会话/Todo/检查点/技能状态/模式切换/模型能力覆盖/ZTH 审计）、settings（`AIProviderEntity`）、workspace（`RemoteConnectionEntity`/`RemoteMountEntity`/`RemoteAuditLogEntity`）、credentials（`GitCredentialEntity`）、t2i（`T2IProviderEntity` 等）。
- **AI Provider**：`AIProvider` 接口 + `OpenAIAdapter`/`AnthropicAdapter`/`GeminiAdapter`；支持 reasoning（回传签名/思考文本）、token 统计、`stopReason` 截断续写；底层走 `data/remote/*` 的 Retrofit API。
- **Bridge 能力**：`RcbBridge` 在 loopback 随机端口起 TCP 服务，`RCB_BRIDGE_TOKEN` 注入容器，容器内 `rcb-*` helper 经 base64 行协议调用宿主侧剪贴板/URL/通知能力；`openUrlHandler` 由宿主注入（默认仅记日志）。
- **外部模块依赖**：workspace（`FileAccessProvider`、`WorkspacePathMapper`、`WorkspaceRepository`、远程连接/挂载）、settings（AI Provider 配置）、t2i（图片生成）、core（`FileLogger`、`HostKeyManager`、加密凭据）。
- **DAOs（本模块）**：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`SkillStateDao`、`ModeSwitchHistoryDao`、`ModelCapabilityOverrideDao`、`CheckpointDao`、`CheckpointFileSnapshotDao`、`FileEditHunkDao`、`ZthTelemetryEventDao`、`UserConfirmedSentinelDao`、`HallucinationFuseDao`、`SentinelPlanRejectionAuditDao`、`HardConstraintDeleteAuditDao`、`L0SoftCompactRestoreLogDao`。

## 5. 关键设计点与约束

- **权限引擎**（`ToolPermissionPolicyEngine`）：判定 `ALLOW/DENY/ASK`。shell 命令（Bash/terminal.start）按指令级前缀匹配；评估顺序为 DENY 优先 → 不可静态判定必须 ASK 且不可记忆 → 内置安全白名单（`BuiltInSafeCommands`，ls/git status 等）→ 已记忆 ALLOW → 否则 ASK。`PLAN` 模式物理沙盒拒绝一切写/执行类工具；`AUTO` 模式放行全部权限但仍保留灾难性 rm 防护（禁止根目录/系统目录/工作区根/通配删除）。修改 Agent 配置、容器环境、外部动态工具等能力不可记忆授权，仅单次放行。
- **工具结果缓存**（`ToolResultCache`）：`(toolName, argsHash, sessionId)` 键控，TTL 默认 60s；文件类工具（`readFile`/`search`/`list`）叠加文件 mtime 失效；L7 事件总线在文件被写/删后广播强制失效。
- **重试策略**：`RetryPolicy` 默认对 `NETWORK`/`TRANSIENT` 类错误指数退避 + 抖动自动重试（上限 3 次）；错误码按前缀推断分类，工具可显式声明 `errorClass` 覆盖。
- **持久化**：消息、会话、Todo、技能启用状态、模式切换、模型能力覆盖、检查点快照与全套 ZTH 审计均落 Room；检查点文件快照存 `<filesDir>/checkpoints/<sessionId>/<checkpointId>/`，同一 checkpoint 内对同一文件只保留最原始一份，支持回滚。
- **上下文压缩**：`ContextCompactor` 在历史超过阈值时自动压缩，也可经 `/compress` 手动触发；压缩结果内部持久化。
- **安全**：RcbBridge 绑定 127.0.0.1 随机端口（外网不可达）+ 首行令牌鉴权；`share` 能力默认不支持（防数据渗出）；`open_url` 默认不真正打开。`PLAN` 模式只读、工具权限最小化、MCP/Skill 加载能力受 ZTH 能力守卫管控。
- **终端输出**：`BoundedOutput` 限制命令输出长度，避免超长回填污染上下文；进度类输出由 `progress/` 解析器聚合。

## 6. 维护与扩展指引

- **新增工具**：在 `domain/tool/<分类>/` 下实现 `AgentTool` 子类（流式输出再实现 `StreamingAgentTool`），声明 `name/description/parameters/capabilities/permissionPolicy`（按需 `provides/consumes/dependsOn/subscribedEvents/retryPolicy`）；在 `di/AgentModule`（Hilt `@Binds @IntoSet`）注册；若工具结果应被缓存，需同步登记进 `ToolResultCache.FILE_TOOLS` 相关集合，否则缓存静默失效。
- **新增斜杠命令**：实现 `SlashCommandHandler` + `@Binds @IntoSet` 即自动纳入 `SlashCommandRegistry`（无需改注册表），建议同时提供 `trigger`/`matches`/`filterByPrefix` 语义。
- **新增 AI Provider**：实现 `AIProvider` 接口，新增 Adapter（参考 `OpenAIAdapter`/`AnthropicAdapter`/`GeminiAdapter`），在 `data/remote/` 加 Retrofit API，并在 Provider 选择处注册。
- **新增 DAO/实体**：在 `data/local/` 新增 DAO/Entity，并在 `AgentDatabase` 的 `entities` 列表与抽象访问器中登记，提升 `SCHEMA_VERSION`（同时提供迁移）。
- **新增能力/权限维度**：扩展 `ToolCapability` 枚举，并在 `ToolPermissionPolicyEngine` 的危险能力集、`ZthCapabilityGuard` 能力降级表、`ToolPermissionManager` 中同步处理。
- **新增 ZTH 审计维度**：新增 DAO/Entity 与 `data/repository/` 仓库，接入 `ZthGuardAggregateFacade` 对应审计阶段，如需云端同步则在 `data/remote/zth/` 补 DTO 与 Firestore 映射。
- **新增容器能力**：扩展 `CommandEngine` 实现（本地/远程），或新增 `domain/tool/container/` 下的环境类工具；安装进度解析在 `domain/container/progress/` 扩展 `InstallProgressParser`。
