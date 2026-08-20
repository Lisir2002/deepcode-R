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
| `data/local/dao/` | Room DAO：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`SkillStateDao`、`ModeSwitchHistoryDao`、`ModelCapabilityOverrideDao`、Checkpoint 系列（`CheckpointDao`、`CheckpointFileSnapshotDao`、`FileEditHunkDao`）、ZTH 审计系列（`ZthTelemetryEventDao`、`UserConfirmedSentinelDao`、`HallucinationFuseDao`、`SentinelPlanRejectionAuditDao`、`HardConstraintDeleteAuditDao`、`L0SoftCompactRestoreLogDao`） |
| `data/local/database/AgentDatabase.kt` | 全局 Room 数据库（schema v46，`exportSchema=true`），跨模块聚合 agent/settings/workspace/credentials/t2i 的表 |
| `data/local/entity/` | 与 DAO 一一对应的实体类（`AgentMessageEntity`、`ChatSessionEntity`、`CheckpointEntity`、`SkillStateEntity`、`TodoItemEntity` 等） |
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
| `domain/skill/` | 技能系统：`Skill` 模型（PROMPT/SCRIPT/MCP 三形态、BUILTIN/LOCAL 来源）、`SkillParser`、`SkillRepository`、`SkillExecutor`、`SkillSource` + `LocalDirectorySkillSource`、`SkillStateRepository`（Room 持久化启用状态） |
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
| `tool/skill/LoadSkillTool.kt` | 加载并执行技能 |
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
- `ContainerInstaller` + `progress/` 子包负责环境安装与进度聚合解析（`RealProgressAggregator`/`InstallProgressParser`/`ApkStdoutParser`）。
- **双架构容器（真机 + 模拟器）**：`ContainerInstaller` 支持 `container/arm`（arm64）与 `container/x86_64`（x86_64 rootfs + x86_64 原生 proot + qemu 转译器）双 rootfs 资产，`prootBinFor/rootfsDirFor` 按 `ContainerProfile.arch + EnvironmentDetector` 选择；`LinuxContainerEngine` 首次启动按宿主架构自动落到对应内置 profile（x86_64 宿主 → `BUILTIN_ALPINE_X86`），`buildBaseProotArgv` 仅「x86_64 容器 + 非 x86_64 宿主」时注入 `-q qemu`；容器不可用环境走明确降级报错，AI 核心（文件/对话/远程 SSH）不受影响（见 [emulator-support-design](../plan-docs/emulator-support-design.md)）。

### 3.4 MCP（McpManager）

- `McpManager`（单例）读取 `McpConfigRepository` 的服务器配置，通过 `StreamableHttpTransport`（HTTP）或 `StdioTransport`（stdio）连接 `McpClient`，把服务器暴露的工具包装为 `McpTool` 注册进 `ToolRegistry`，并维护 `statuses: StateFlow<List<McpServerStatus>>` 供 UI 展示；支持动态 install 新服务器（`ManageMcpTool`）。

**内置 MCP 服务器（规划中，未实施）**：当前 `domain/mcp/` 只有客户端（连别人）。设计已评审（[builtin-mcp-server-design](../plan-docs/builtin-mcp-server-design.md)）：新增 `server/` 子包做「客户端 + 服务器」双角色，用 Ktor CIO 起 Streamable HTTP 端点，把 `ToolRegistry` 中的 `AgentTool` 映射为 MCP 工具（复用 `toToolDefinition()`/`execute()`），权限复用 `ToolPermissionManager` + 远程强制审批总开关，服务管理对标 `FtpServerManager`（开关/端口/token/自启/URL 展示）。实施按 M0 只读子集 → M1 全量工具 → M2 SSE+保活 → M3 上下文工具渐进推进；落地后更新本节。

### 3.5 记忆（Memory）

- `Memory` 模型带 `MemoryScope`（GLOBAL/PROJECT）；`GlobalMemorySource`/`ProjectMemorySource` 从对应目录解析，`MemoryRepository` 汇总。
- `SystemPromptProvider` 把记忆摘要注入系统提示词；`MemoryTool` 提供 save/list/read，维护访问计数与标签。

### 3.6 技能（Skill）

- `Skill` 支持三种执行形态：PROMPT（注入指令，无执行）、SCRIPT（容器内沙箱执行入口脚本，需 ZTH 审批）、MCP（映射到已连接 MCP 工具）。
- `SkillParser` 解析带 Frontmatter 的技能文件（版本/作者/标签/适用模式/依赖/requiredTools/requiresRuntime 等），`SkillRepository` 管理来源，`SkillStateRepository` 用 Room 持久化启用状态，`LoadSkillTool` 加载执行，`SkillExecutor` 负责运行。
- `SkillExecutor` 执行链路携带 `SkillExecutionContext`（由 `LoadSkillTool` 从 `AgentContext` 派生，含 sessionId/mode/projectPath/agentType），使脚本技能审批与审计的 sessionId 与当前会话连贯（替代此前传 null 的脱钩问题）。

#### 3.6.1 技能作用域分级（SkillScope，多 Agent 演进）

支撑「后续不止编程 agent」：给 `Skill` 增加与 `type`/`modes` **正交**的作用域维度，定义「谁能用、能否被用户关闭」：

- `SkillScope` 三档：`GLOBAL` 全局（系统级强制激活，所有 agent 必有，用户不可关闭）／`COMMON` 通用（所有 agent 默认可用，用户可开关）／`AGENT` agent 级（仅绑定 `agentType` 的 agent 可用）。
- `Skill` 新增字段：`scope: SkillScope = COMMON`、`agentType: String? = null`（scope==AGENT 时必填，如 `"coding"`）。
- Frontmatter 承载 `scope: global|common|agent` + `agent-type`（仅 agent 级需要），缺省按 COMMON 解析。
- `SkillToolBindingManager`：技能加载成功时校验并登记 `requiredTools`（缺失给明确错误 `SKILL_MISSING_TOOL`），技能禁用/卸载时回收本管理器动态注册的工具（绝不删除内置全局工具）。
- `LoadSkillTool.executeWithContext`：GLOBAL/COMMON 直接放行加载；AGENT 级按声明 agentType 校验；加载前登记专属工具、构建 `SkillExecutionContext` 贯穿执行。当前为单 Agent 场景，AGENT 级按声明放行，多 Agent 演进后改为 `<skill.agentType> == 当前激活 agentType` 才放行。
- 技能「激活态」语义（R1）：GLOBAL 常驻、COMMON 随「用户开关 × agent 激活」、AGENT 仅当对应 agent 激活——实际按作用域动态注册/回收由 `SkillToolBindingManager` 在 agent 激活时执行。

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
