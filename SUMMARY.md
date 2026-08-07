# R-DeepCode 项目完整总结文档

> **项目名称**: R-DeepCode  
> **仓库地址**: https://github.com/jieapi/aicode  
> **许可证**: GPL-3.0  
> **编程语言**: Kotlin + Java  
> **平台**: Android 8.0+ (API 26+), arm64-v8a / x86_64  
> **UI 框架**: Jetpack Compose + Material 3  
> **依赖注入**: Hilt (Dagger)  
> **数据库**: Room (SQLite)  
> **异步**: Kotlin Coroutines / Flow  
> **网络**: Retrofit + OkHttp  

---

## 一、项目概述

R-DeepCode 是一款**在 Android 手机上运行的 AI 编程助手**，将大语言模型（LLM）与本地 Linux 开发环境深度集成。它内置 Alpine Linux 容器（通过 PRoot 实现）和终端模拟器，让 AI Agent 能直接读写文件、执行 Shell 命令、运行构建工具。同时支持远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

核心灵感来源于 [OpenCode](https://github.com/anomalyco/opencode)（终端 AI 编码工具），终端组件基于 [Termux](https://github.com/termux/termux-app) 开源项目。

---

## 二、项目结构

```
aicode/
├── build.gradle.kts                 # 根 Gradle 构建文件（插件声明）
├── settings.gradle.kts              # 模块配置（含腾讯云镜像、JitPack）
├── gradle.properties                # Gradle JVM 参数与缓存配置
├── CLAUDE.md                        # AI 辅助开发规范（Conventional Commits、发版流程、测试要求）
├── README.md / README.en.md         # 中英文项目说明
├── LICENSE                          # GPL-3.0 协议
│
├── app/                             # 主应用模块（Android Application）
│   ├── build.gradle.kts             # 应用构建配置（flavor、签名、版本号动态推导）
│   ├── proguard-rules.pro           # 代码混淆规则
│   └── src/
│       ├── _armAssets/container/    # ARM 架构的 Alpine rootfs + PRoot 二进制
│       ├── _x86Assets/container/    # x86 架构的 Alpine rootfs + PRoot 二进制
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── api.official.json        # 预置 AI 模型元数据（多提供商）
│       │   │   ├── aicode/git-credential-aicode  # Git 凭据辅助脚本
│       │   │   ├── docs/                    # 内置使用文档（11 篇）
│       │   │   ├── migrations/              # 数据库迁移 SQL（23 个文件）
│       │   │   └── prompts/                 # AI 系统提示词（11 个文件）
│       │   ├── java/com/aicode/
│       │   │   ├── AIEditorApp.kt           # Application 入口
│       │   │   ├── MainActivity.kt          # 主 Activity + 导航
│       │   │   ├── core/                    # 核心基础设施
│       │   │   ├── di/                      # 依赖注入模块
│       │   │   └── feature/                 # 功能模块
│       │   └── res/                         # 资源文件
│       ├── debug/res/values/strings.xml     # Debug 变体字符串
│       └── test/                            # 单元测试
│
├── terminal-emulator/              # 终端仿真器库（基于 Termux）
│   ├── build.gradle.kts
│   └── src/main/java/com/termux/terminal/
│       ├── TerminalEmulator.java    # VT100/ANSI 终端仿真核心
│       ├── TerminalBuffer.java      # 环形缓冲区管理
│       ├── TerminalSession.java     # 终端会话管理
│       ├── ByteQueue.java           # 环形字节队列
│       ├── JNI.java                 # JNI 桥接（PTY 创建）
│       ├── KeyHandler.java          # 键盘映射
│       └── ...（共 15 个 Java 文件）
│
└── terminal-view/                  # 终端视图渲染库（基于 Termux）
    ├── build.gradle.kts
    └── src/main/java/com/termux/view/
        ├── TerminalView.java        # 终端 View 控件
        ├── TerminalRenderer.java    # 终端渲染器
        ├── GestureAndScaleRecognizer.java  # 手势识别
        └── textselection/           # 文本选择功能
```

---

## 三、Gradle 构建系统解析

### 3.1 根构建文件 (`build.gradle.kts`)

声明项目级插件版本：
- `com.android.application` 8.9.3
- `org.jetbrains.kotlin.android` 2.2.21
- `com.google.dagger.hilt.android` 2.56.1
- `com.google.devtools.ksp` 2.2.21-2.0.5

### 3.2 应用构建文件 (`app/build.gradle.kts`)

**核心设计要点**：

1. **动态版本号**：`versionName` 由 `gitVersionName()` 从 Git Tag 动态解析，`versionCode` 由 `gitCommitCount()` 从提交数自动生成，无需手动维护。

2. **targetSdk 锁定 28**：Android 10+（API 29+）的 W^X/SELinux 策略禁止执行 App 可写数据目录里的文件，PRoot 将无法运行。这是与 Termux 相同的取舍，代价是不能上 Google Play。

3. **三 flavor 拆包**：按 CPU/容器镜像拆分为 `universal`（arm64 + x86_64 双架构）、`armsolo`（仅 arm64）、`x86solo`（仅 x86_64），单架构包体积约为通用包的一半。

4. **容器镜像 sourceSet 共享**：`_armAssets` 由 universal + armsolo 共享，`_x86Assets` 由 universal + x86solo 共享，镜像二进制在仓库里只各存一份。

5. **Debug 包名后缀**：debug 变体加 `.debug` 后缀，与 release 可同机共存。

### 3.3 依赖清单

| 类别 | 依赖 |
|------|------|
| Compose | compose-bom 2025.12.01, material3, navigation-compose |
| DI | Hilt 2.56.1, hilt-navigation-compose |
| 数据库 | Room 2.7.1, DataStore Preferences |
| 网络 | Retrofit 2.11.0, OkHttp 4.12.0, Gson |
| AI 通信 | Anthropic API, OpenAI API, Gemini API |
| SSH | sshj 0.38.0 (exec + SFTP + shell) |
| 加密 | BouncyCastle bcprov-jdk18on 1.75 |
| FTP | Commons Net 3.10.0, Apache FtpServer 1.2.0 |
| 容器 | Commons Compress 1.26.2, XZ 1.10 |
| Markdown | multiplatform-markdown-renderer-m3 0.41.0 |
| 终端 | terminal-emulator + terminal-view (本地模块) |
| 序列化 | kotlinx-serialization-json 1.8.1 |
| YAML | SnakeYAML 2.2 |
| HTML 解析 | Jsoup 1.18.1 |

---

## 四、核心基础设施 (`core/`)

### 4.1 `AIEditorApp.kt` — Application 入口

`@HiltAndroidApp` 注解的 Application 类，初始化顺序：

1. **`attachBaseContext()`** — 最早入口：初始化 FileLogger、AILogger、全局崩溃处理器、应用语言
2. **`onCreate()`** — 依次执行：
   - 注册 BouncyCastle 安全 Provider（替换 Android 裁剪版，支持 X25519 密钥交换）
   - 创建通知渠道
   - 启动凭据请求监听（`CredentialRequestBridge`）
   - 提取内置文档和提示词到私有目录
   - 同步 Git 凭据到容器
   - 刷新模型元数据
   - 同步日志等级
   - 初始化执行模式（本地/远程 SSH），远程模式自动连接 SSH
   - 启动 SSH 监督（定期探活、自动重连）
   - 监听后台保活开关
   - 启动 MCP 管理器
   - 语言切换监听

### 4.2 `MainActivity.kt` — 主 Activity

- `@AndroidEntryPoint` 注解的 ComponentActivity
- 使用 `ModalNavigationDrawer` + `NavHost` 构建导航
- 四个页面路由：`chat`（AI 对话）、`settings`（设置）、`terminal`（终端）、`git`（Git 管理）
- 侧边栏（Drawer）：会话列表、新建/删除/重命名/导出会话
- 全局凭据弹窗（`GlobalCredentialDialogHost`）
- Activity 级 ViewModel 共享（AI Agent、设置、工作区）

### 4.3 `core/util/` — 工具类

| 文件 | 功能 |
|------|------|
| `FileLogger.kt` | 通用应用日志落盘（按天分文件，7 天自动清理，可配置等级） |
| `AILogger.kt` | AI 请求/响应日志（按会话分文件，含完整 body、SSE 流） |
| `ErrorUtils.kt` | 异常转用户友好中文提示（DNS 失败、超时、连接拒绝等） |
| `LanguageRegistry.kt` | 应用语言清单（中文、英文） |
| `LineDiff.kt` | LCS 行级文本差异算法（用于代码 diff 展示） |

### 4.4 `core/theme/AIEditorTheme.kt` — 主题

- 自定义深色/浅色配色方案
- 品牌色：蓝色系（Blue, Sky, Ice）
- 间距/圆角常量
- 自定义 Typography

### 4.5 `core/ui/ImeInset.kt` — 键盘内边距

- 处理软键盘弹出时底栏跟随动画
- 兼容 targetSdk=28 下的 IME 动画问题
- 使用 `WindowInsetsAnimationCompat` 实现逐帧同步

### 4.6 `core/db/MigrationLoader.kt` — 数据库迁移

- 自定义文件式迁移系统
- 从 `assets/migrations/` 读取 `{version}_{description}.sql` 文件
- 自动创建 `migration_history` 表记录执行历史
- 支持迁移历史追溯和回滚保护

---

## 五、依赖注入 (`di/`)

### 5.1 `AgentModule.kt`

最大的 DI 模块，提供：
- **Room 数据库**：`AgentDatabase`（含所有 DAO）
- **OkHttpClient**：120 秒超时
- **Retrofit 实例**：OpenAI / Anthropic / Gemini 三个独立 Retrofit
- **命令执行引擎**：`DelegatingCommandEngine`（本地 ↔ 远程 SSH 路由）
- **文件访问**：`DelegatingFileAccess`（本地 ↔ 远程 SFTP 路由）
- **终端会话**：`DelegatingTerminalSessionProvider`
- **ToolRegistry**：注册 17 个工具
- **AgentWorkflow**：`StatefulAgentWorkflow`（核心工作流引擎）

### 5.2 `BackupModule.kt`

绑定 `BackupManager` 接口到 `BackupManagerImpl`。

### 5.3 `RepositoryModule.kt`

绑定 `AIProviderRepository` 和 `CredentialRepository` 接口到实现类。

---

## 六、AI Agent 功能模块 (`feature/agent/`)

这是项目的核心模块，实现了完整的 AI 编程助手功能。

### 6.1 架构模式

采用 **MVI（Model-View-Intent）** 架构：
- **State**：`AgentSessionState` — 不可变状态（消息列表、工具调用、权限、迭代次数等）
- **Action**：`AgentAction` — 状态变更驱动（用户消息、工具执行、模式切换等）
- **Effect**：`AgentSideEffect` — 副作用（网络请求、文件操作、Shell 执行等）

### 6.2 工作流引擎 (`domain/workflow/`)

#### `AgentWorkflow.kt`（接口）
顶层抽象，定义 `executeEvents()` 和 `compactSession()` 核心方法。

#### `StatefulAgentWorkflow.kt`（核心实现）
完整的 MVI 状态机，包含：
- **状态管理**：`AgentSessionState` 维护消息列表、工具调用状态、权限审批、迭代计数
- **事件处理**：`AgentAction` → `reduce()` → 新状态 + 副作用
- **LLM 交互**：支持流式和非流式调用，三种提供商适配器
- **工具执行**：从 `ToolRegistry` 获取工具，按 `AgentMode` 过滤
- **上下文压缩**：自动触发和手动触发（`/compress` 命令）
- **权限审批**：Plan → Build 模式的计划审批流程
- **检查点**：自动创建和恢复代码快照
- **重试机制**：网络错误自动重试（阶梯退避）

#### `ContextCompactor.kt`
上下文压缩器，当对话历史过长时对早期消息进行摘要压缩，控制 Token 消耗。

### 6.3 工具系统 (`domain/tool/`)

共 **17 个注册工具**，分为以下类别：

#### 文件操作工具
| 工具名 | 类 | 功能 |
|--------|------|------|
| `readFile` | `ReadFileTool` | 读取文件内容 |
| `writeFile` | `WriteFileTool` | 创建/写入文件 |
| `editFile` | `EditFileTool` | 字符串匹配编辑（old_string → new_string） |
| `sendFile` | `SendFileTool` | 上传文件到容器 |
| `viewImage` | `ViewImageTool` | 查看图片 |

#### 命令执行工具
| 工具名 | 类 | 功能 |
|--------|------|------|
| `Bash` | `ExecuteCommandTool` | 执行 Shell 命令（流式输出，支持超时截断） |
| `terminal` | `TerminalSessionTool` | 后台终端会话管理（创建/发送/读取） |

#### 浏览搜索工具
| 工具名 | 类 | 功能 |
|--------|------|------|
| `list` | `ListFilesTool` | 文件列表浏览 |
| `search` | `SearchCodeTool` | 代码搜索 |
| `websearch` | `WebSearchTool` | 网络搜索 |
| `webfetch` | `WebFetchTool` | 网页抓取 |

#### 交互工具
| 工具名 | 类 | 功能 |
|--------|------|------|
| `askUserQuestion` | `AskUserQuestionTool` | 向用户提问 |
| `loadSkill` | `LoadSkillTool` | 加载技能指令 |
| `manageMcp` | `ManageMcpTool` | 管理 MCP 服务器 |

#### 模式与辅助工具
| 工具名 | 类 | 功能 |
|--------|------|------|
| `switchMode` | `SwitchModeTool` | 切换工作模式（BUILD/PLAN） |
| `todo` | `TodoTool` | 待办事项管理 |
| `memory` | `MemoryTool` | 记忆管理（全局/项目） |

### 6.4 权限系统 (`domain/permission/`)

#### 权限模型 (`PermissionModels.kt`)
- **`PermissionScope`**：作用域枚举（`PROJECT` / `GLOBAL`）
- **`PermissionDecision`**：判定方向（`ALLOW` / `DENY`）
- **`PermissionChoice`**：用户弹窗选择（`REJECT` / `ONCE` / `ALWAYS`）
- **`PermissionRule`**：一条授权规则，含 `toolName`、`pattern`（命令前缀或通配符 `*`）、`decision`
- **`PermissionFile`**：JSON 文件结构，含 `allow` / `deny` 两个紧凑格式字符串列表（如 `"Bash(git pull)"`、`"writeFile"`）
- 支持 `toCompact()` / `fromCompact()` 双向转换

#### `ToolPermissionPolicyEngine`
工具授权策略引擎，评估顺序：
1. **DENY 规则优先** — 用户明确拒绝的放行
2. **不可静态判定 → ASK** — 含命令替换、管道等复杂结构
3. **内置安全白名单 → ALLOW** — `ls`, `cat`, `grep`, `git status` 等只读命令
4. **已记忆 ALLOW 规则 → ALLOW** — 用户之前允许过
5. **否则 → ASK** — 弹窗询问用户

#### `ShellCommandParser`
Shell 命令安全静态分析器：
- 引用感知分词
- 段首环境赋值跳过
- 子命令分发器识别
- `rm` 命令精细解析（灾难性删除防护）
- 不可静态判定构造降级为"必须弹窗，不可记忆"

#### `BuiltInSafeCommands`
内置安全白名单（只读命令自动放行），不落盘，遵循"规则绝不落盘到容器"的安全原则。

#### `PermissionRulesRepository`
工具授权规则的持久化仓储：
- **全局规则**：存储在 `filesDir/aicode/permissions.json`（app 私有目录，AI 无法篡改）
- **项目级规则**：存储在工作区 `.aicode/permissions.json`（可 Git 追踪）
- 双缓存（`Mutex` 保护文件 IO + `MutableStateFlow` 内存缓存）
- 响应式流：`globalRulesFlow`、`currentProjectRulesFlow`（跟随工作区切换自动更新）
- `loadEffectiveForCurrentProject()` 合并当前项目规则 + 全局规则（项目规则优先）
- 支持 `promoteToGlobal()` 将项目规则提升为全局规则

### 6.5 斜杠命令系统 (`domain/command/`)

#### 架构
通过 Hilt `@Binds @IntoSet` multibinding 自动汇集所有命令处理器，新增命令无需修改注册表。

#### `SlashCommandHandler`（接口）
每条命令实现一个 handler：
- `trigger`：触发文本（如 `/status`）
- `label` / `description`：菜单显示名和描述
- `matches(input)`：完全匹配判断
- `execute(context)`：执行操作

#### `SlashCommandContext`（接口）
命令执行上下文，仅暴露最小能力：
- `showSessionStatus()` — 查看会话状态
- `compactCurrentSession()` — 压缩上下文
- 由 `AIAgentViewModel` 实现

#### 已注册命令
| 命令 | 处理器 | 功能 |
|------|--------|------|
| `/status` | `StatusCommandHandler` | 查看当前会话的 token 用量、模型、模式等信息，结果以 Markdown 表格输出 |
| `/compress` | `CompressCommandHandler` | 手动触发当前会话的上下文压缩，复用 `ContextCompactor` 逻辑 |

#### 注册机制 (`SlashCommandModule.kt`)
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class SlashCommandModule {
    @Binds @IntoSet
    abstract fun bindStatusCommandHandler(handler: StatusCommandHandler): SlashCommandHandler
    @Binds @IntoSet
    abstract fun bindCompressCommandHandler(handler: CompressCommandHandler): SlashCommandHandler
}
```

#### `SlashCommandRegistry`
- 构造时通过 `Set<SlashCommandHandler>` 汇集所有命令
- `filterByPrefix(input)`：输入框前缀过滤，用于实时匹配
- `findExact(input)`：发送时完全匹配

### 6.6 提供商适配 (`domain/provider/`)

| 适配器 | 支持的 API | 特性 |
|--------|-----------|------|
| `OpenAIAdapter` | OpenAI 兼容 API | function calling, reasoning_effort, 图片输入, 流式 |
| `AnthropicAdapter` | Anthropic Claude API | tool use, extended thinking (带签名), 图片/PDF 输入, 流式 |
| `GeminiAdapter` | Google Gemini API | function calling, thinking 配置, 流式 |

### 6.7 容器引擎 (`domain/container/`)

#### `ContainerProfile` — 容器配置模型
可切换的容器配置，支持三种 Rootfs 来源：
- **`RootfsSource.Asset`**：内置 Alpine rootfs（来自 assets）
- **`RootfsSource.LocalFile`**：用户导入的 tar.gz（通过 content URI）
- **`RootfsSource.RemoteSsh`**：远程 SSH 通道（绑定已配置的 SSH 连接 + 远程工作区路径）

内置预置 `BUILTIN_ALPINE` profile，支持自定义：
- `shellPath`：自定义 shell 路径
- `extraBindings`：额外 `-b` 绑定（如 `/sdcard:/mnt/sdcard`）
- `extraArgs`：额外 proot 参数
- `mode`：执行模式（`LOCAL_PROOT` / `REMOTE_SSH`）

#### `ContainerInitState` — 初始化进度状态
密封接口，共 6 种状态：
- `Idle` — 未开始
- `ExtractingRootfs(processed)` — 解压 rootfs
- `DeployingProot` — 部署 proot 二进制
- `InstallingPackages(line)` — 安装基础包
- `Ready` — 就绪
- `Failed(reason)` — 失败

#### `BoundedOutput` — 输出截断器
保留开头 `headLimit`（20k） + 结尾 `tailLimit`（20k）字符，丢弃中间，标注省略字符数。

#### `LinuxContainerEngine`
本地容器命令执行引擎：
- 容器初始化（rootfs 解压、基础包配置）
- 命令执行（ProcessBuilder 启动进程）
- 超时看门狗（防止命令卡死）
- 输出流式读取（`Flow<CommandEvent>`）

#### `RemoteSshEngine`
远程 SSH 命令执行引擎（通过 sshj 实现）：
- SSH exec channel 执行命令
- 流式输出
- 超时控制

#### `DelegatingCommandEngine`
命令路由委托层，根据 `ExecutionMode` 自动选择本地/远程引擎。

#### `ContainerInstaller`
容器安装器：
- 首次使用时解压 Alpine rootfs
- 安装基础工具包
- 提取提示词模板到 `~/.aicode/`
- 管理 `aicodeDir` 应用数据目录

### 6.8 MCP 协议 (`domain/mcp/`)

完整实现 Model Context Protocol 客户端：
- **`McpClient`** — JSON-RPC 通信（列出工具、调用工具）
- **`StdioTransport`** — 本地子进程 stdio 传输
- **`StreamableHttpTransport`** — 远程 HTTP SSE 流式传输
- **`McpTool`** — MCP 工具 → AgentTool 适配器
- **`McpManager`** — 生命周期管理（启动/停止/注册）
- **`McpConfigRepository`** — 配置持久化（`mcp.json`）

### 6.9 记忆系统 (`domain/memory/`)

- **`MemoryRepository`** — 聚合层，汇总全局和项目级记忆
- **`GlobalMemorySource`** — 全局记忆（应用私有目录，跨项目共享）
- **`ProjectMemorySource`** — 项目记忆（工作区目录，可 Git 追踪）
- **`MemoryParser`** — Markdown/YAML frontmatter 解析

### 6.10 技能系统 (`domain/skill/`)

- **`SkillRepository`** — 技能仓库，聚合多个 SkillSource
- **`LocalDirectorySkillSource`** — 从 `aicodeDir/skills/` 扫描 `SKILL.md` / `CLAUDE.md`
- **`SkillParser`** — YAML frontmatter + 正文解析

### 6.11 提示词系统 (`domain/prompt/`)

**`SystemPromptProvider`** — 增量式提示词更新（SystemContext 化）：
- 拆分为多个独立 `PromptSource` 模块（静态规则、PLAN/AUTO 模式提示、技能列表、记忆列表、项目规则、工作区上下文、当前时间）
- 每个 Source 维护缓存和快照
- 高频变化 WorkspaceSource 提供增量 Diff 逻辑
- 加载优先级：`prompts.custom/`（用户覆盖）> `prompts/`（本地副本）> `assets/`（内置兜底）

### 6.12 会话管理 (`domain/session/`)

- **`SessionUseCase`** — 会话 CRUD、标题生成、Token 统计
- **`MessagePersistenceUseCase`** — 消息持久化、内容净化、历史重建、单调递增时间戳

### 6.13 检查点系统 (`domain/checkpoint/`)

- **`CheckpointManager`** — 创建/恢复检查点
- 用户发送消息时自动创建检查点
- 工具修改文件前快照原始文件状态
- 回滚到指定检查点（倒序还原所有受影响文件）

### 6.14 数据层 (`data/`)

#### 远程 API（`data/remote/`）
| 文件 | 内容 |
|------|------|
| `openai/OpenAIApi.kt` | Retrofit 接口（Chat Completions + Responses API） |
| `openai/OpenAIModels.kt` | 请求/响应数据模型（含 reasoning_effort） |
| `anthropic/AnthropicApi.kt` | Retrofit 接口（Messages API） |
| `anthropic/AnthropicModels.kt` | 请求/响应模型（含 extended thinking/signature） |
| `gemini/GeminiApi.kt` | Retrofit 接口（generateContent） |

#### 本地数据库（`data/local/`）
- **`AgentDatabase.kt`** — Room 数据库（版本 30），9 个实体，7 个 DAO
- **Entity**：`AgentMessageEntity`、`ChatSessionEntity`、`TodoItemEntity`、`CheckpointEntity`、`CheckpointFileSnapshotEntity`
- **DAO**：`AgentMessageDao`（分页/搜索/导出）、`ChatSessionDao`（Token 用量累加）、`TodoItemDao`、`CheckpointDao`

### 6.15 表示层 (`presentation/`)

#### `AIAgentViewModel.kt`
核心 ViewModel，管理：
- 会话状态（`AgentUIState` 密封类：Idle/Loading/Streaming/Result/Applied/Error）
- 消息流（分页加载、过滤不可见内容）
- 流式文本/思考过程实时更新
- 工具调用实时输出
- 后台任务完成通知（合并/缓存/触发新请求）
- 请求队列（忙碌时排隊，空闲时自动执行）
- 斜杠命令（`/status`、`/compress`）
- 检查点回滚（Rewind）
- 代码变更追踪与应用

#### 提示词文件（`assets/prompts/`）
11 个 Markdown 文件，按编号排序：
- `00-identity.md` — AI 身份定义
- `10-communication.md` — 沟通风格规范
- `15-project-rules.md` — 项目规则加载
- `20-coding-discipline.md` — 编码纪律
- `30-comments.md` — 注释规范
- `40-approach.md` — 工作方式
- `50-safety.md` — 安全边界
- `60-tools-and-paths.md` — 工具使用说明
- `70-skills-and-mcp.md` — 扩展机制
- `80-plan-mode.md` — PLAN 模式约束
- `81-auto-mode.md` — AUTO 模式约束

---

## 七、其他功能模块

### 7.1 备份模块 (`feature/backup/`)

| 文件 | 功能 |
|------|------|
| `BackupManager.kt` | 备份编排器接口（导出/导入/单会话导出） |
| `BackupSnapshot.kt` | 序列化 DTO 定义（kotlinx.serialization） |
| `BackupCrypto.kt` | AES-256-GCM 对称加密（PBKDF2 派生，21 万次迭代） |
| `BackupManagerImpl.kt` | 实现类：tar.gz 打包 + JSONL 流式写入 |
| `BackupViewModel.kt` | 备份页 UI 状态管理 |
| `BackupSection.kt` | Compose UI（SAF 文件选择器、口令对话框、进度展示） |

### 7.2 凭据模块 (`feature/credentials/`)

| 文件 | 功能 |
|------|------|
| `CredentialRequestBridge.kt` | 容器内 git credential helper ↔ App 的文件 IPC 桥 |
| `GitCredentialsFileSync.kt` | Room 凭据 ↔ 容器文件同步（原子写入） |
| `CredentialViewModel.kt` | 凭据 CRUD + 署名配置 |

**核心设计**：容器内 git 缺凭据时，custom helper 写请求文件到容器目录 → `FileObserver` 捕获 → 全局弹窗回填 → 写入响应文件 → helper 取走喂回 git。三端（UI/终端/AI）共用同一份凭据。

### 7.3 Git 模块 (`feature/git/`)

| 文件 | 功能 |
|------|------|
| `GitRepository.kt` | 完整的 Git 操作封装（复用 CommandEngine） |
| `GitGraphBuilder.kt` | 提交拓扑图解析 + 泳道布局算法 |
| `GitErrorMessage.kt` | Git 错误中文友好提示 |
| `GitViewModel.kt` | Git 页 UI 状态管理（含 diff 视图/语法高亮） |

支持的 Git 操作：`status`、`branches`、`log`、`graph`（拓扑图）、`commit`、`stage`/`unstage`、`pull`/`push`、`checkout`、`branches`（CRUD）、`tags`（CRUD）、`diff` 等。

### 7.4 设置模块 (`feature/settings/`)

36 个源文件，涵盖：
- **AI 提供商管理**：CRUD、启用/禁用、模型列表
- **模型元数据**：内置 `api.official.json` + 网络刷新（24h 缓存）
- **主题设置**：AUTO/DARK/LIGHT
- **语言设置**：中文/英文/跟随系统
- **日志设置**：等级控制（VERBOSE ~ NONE）
- **保活设置**：后台常驻通知开关
- **执行模式**：本地 PRoot / 远程 SSH
- **默认模型**：新会话默认 provider/model
- **视觉模型**：识图专用模型配置
- **压缩模型**：上下文压缩专用模型配置
- **MCP 配置**：服务器列表管理
- **权限规则**：全局/项目级
- **同步设置**：远程工作区同步
- **容器镜像**：本地/远程配置

### 7.5 终端模块 (`feature/terminal/`)

#### 架构设计
终端模块采用 **Provider 接口 + 委托路由** 模式：

```
TerminalSessionTool (AI 工具)
  → DelegatingTerminalSessionProvider
    ├── 本地模式 → TerminalSessionManager (Termux PTY + PRoot)
    └── 远程模式 → RemoteTerminalSessionManager (SSH shell channel)
```

#### `TerminalSessionProvider`（接口）
终端会话后端抽象，定义统一的 AI 终端操作接口：
- `startBackgroundCommand(command, title, notify, sourceSessionId)` — 挂后台运行命令
- `sendInput(id, input, appendNewline)` — 按 id 发送输入
- `writeToTab(id, text)` / `writeBytesToTab(id, bytes)` — 写入文本/控制字符
- `getTabOutput(id)` — 读取终端屏幕缓冲
- `listTabs()` — 列出所有标签摘要
- `closeTab(id)` — 关闭标签
- `tabFinishedEvents: SharedFlow` — 后台任务完成事件流

#### `TerminalTab` — 终端标签模型
一个终端标签包含：会话 + 渲染视图 + 元数据
- `RunState`：`Running` / `Finished(exitCode)`（已结束的标签保留在列表中供回看）
- `isBackground`：是否为后台命令标签
- `notifyOnExit`：结束时是否通知 AI
- `sourceSessionId`：发起该标签的会话 id（回调路由用）
- `view`：TerminalView 引用（由 Compose 回填）

#### `TerminalSessionManager` — 本地终端会话管理器
`@Singleton` 常驻进程的终端会话池：
- 基于 Termux `TerminalSession` + `SubprocessBackend`（PTY 子进程）
- 每个标签有稳定 id（`term-N`），供 AI 持续引用
- **交互标签**：`cd ~/workspace + exec bash` 进入容器交互 shell
- **后台命令**：`startBackgroundCommand()` 把命令挂后台跑（如 `npm run dev`），完成后 `exec /bin/sh` 保活
- 首次启动自动创建交互标签（`ensureInitialTab()`）
- 退出监控：`EXIT_MARKER_REGEX` 检测 `[command exited: N]` 标记，兜底终了回调
- 保活管理：有后台命令时启动 `TerminalKeepaliveService`
- 无视图时也启动进程（`updateSize` 在构造时初始化 emulator）

#### `RemoteTerminalSessionManager` — 远程 SSH 终端会话管理器
API 与本地版对齐，区别在 backend：
- 使用 sshj `Session.startShell()` 分配 PTY
- 通过 `SshShellBackend` 驱动 Termux `TerminalSession`
- 自动 `cd ~/workspace` 到当前工作区
- 支持重连（`reconnect()` 重建 SSH shell channel）

#### `SshShellBackend` — SSH Shell 后端
实现 `SessionBackend` 接口，封装 sshj `Session.Shell`：
- `getInputStream()` / `getOutputStream()` — 委托给 shell channel 的 I/O 流
- `resize(columns, rows)` — 通过 `changeWindowDimensions()` 调整远程 PTY 尺寸（后台线程执行）
- `waitForExit()` — 调用 `shell.join()` 等待远程 shell 结束
- `close()` — 关闭 shell channel（后台线程）

#### `TerminalKeepaliveService` — 前台保活 Service
`@AndroidEntryPoint` 的前台 Service：
- 通过常驻通知防止进程被系统杀死
- 计数管理：`START_SESSION` / `STOP_SESSION` 增减后台会话计数
- 常驻模式：设置页开启后即便无后台会话也保持前台通知
- Android 12+ 后台启动前台服务异常保护（捕获 `ForegroundServiceStartNotAllowedException`）

#### `DelegatingTerminalSessionProvider.kt`
本地/远程路由委托，根据 `ExecutionMode` 自动选择实现。

#### `TerminalViewModel.kt`
终端 UI 状态管理（Compose 侧），管理标签切换、输入、渲染。

### 7.6 工作区模块 (`feature/workspace/`)

#### 文件访问抽象 (`FileAccessProvider` 接口)
统一的文件读写后端抽象，将"在哪读写文件"从硬编码的本地 java.io.File 解耦：

| 方法 | 功能 |
|------|------|
| `readFile(path)` | 读取全文文本 |
| `readLines(path)` | 逐行读取（流式） |
| `writeFile(path, content, overwrite)` | 写入文件（自动创建父目录） |
| `exists(path)` / `isDirectory(path)` / `isFile(path)` | 文件状态查询 |
| `fileSize(path)` / `lastModified(path)` | 文件元数据 |
| `permissions(path)` | 权限位字符串（rwx） |
| `listFiles(path)` | 列出目录条目（`FileEntry`） |
| `readBytes(path)` | 读取原始字节 |
| `copyToLocal(path)` | 复制到本地临时文件 |
| `delete(path)` / `mkdirs(path)` | 文件操作 |
| `parentPath(path)` / `toDisplayPath(path)` | 路径操作 |

**两套实现**：
- **`LocalFileAccess`** — 包一层 `WorkspacePathMapper`，走 java.io.File 直读（本地模式）
- **`RemoteSftpFileAccess`** — 用 SSH exec channel 执行命令读写远程文件（**故意不用 SFTP**：sshj 0.38.0 的 SFTP 有 Buffer 溢出 bug，Android 上必现崩溃）

远程文件访问技术细节：
- 文本文件用 `cat` / `printf` + `base64` 中转
- 二进制文件用 `base64` 编解码
- 路径用单引号转义保证 shell 安全
- `stat` 一次性获取文件元数据（name/type/size/mtime/permissions）

#### `DelegatingFileAccess`
委托路由层，根据 `ExecutionMode` 自动转发到本地或远程实现。

#### `WorkspacePathMapper` — 容器路径映射器
在「容器内路径」与「宿主真实路径」之间互转，让 AI 只使用容器路径：

| 容器路径 | 映射到宿主 |
|---------|-----------|
| `~/workspace/...` （或展开后的 `$HOME/workspace/...`） | 当前工作区目录 |
| `/root/.aicode/...` | AI 配置目录（独立于 rootfs） |
| 其他容器绝对路径 `/etc/...` | 当前 profile 的 rootfs 目录 |
| 相对路径 `src/Main.kt` | 工作区根目录下 |

- **profile 感知**：rootfs 目录随当前选中 profile 变化
- `containerHome` 缓存容器内真实 $HOME 路径
- 用于工具入参转换（`toHostFile`）和回显还原（`toContainerPath`）

#### `WorkspaceRepository` — 工作区管理
管理 App 内的"工作区/项目"：
- **本地模式**：项目放在 `filesDir/projects/<name>`（ext4 真实路径，支持 symlink）
- **远程模式**：工作区 = 远程 SSH 服务器上 `remoteWorkspacePath` 下的子文件夹
- `initialize()`：扫描并恢复上次选中的工作区（首次启动创建默认工作区）
- `selectWorkspace(name)` / `createWorkspace(rawName)` / `deleteWorkspace(name)`
- 当前工作区名持久化在 DataStore 中
- 远程模式自动更新 `~/workspace` 符号链接指向当前工作区
- 名称清洗：仅保留字母数字、下划线、连字符、点和空格

#### 其他组件
- **远程连接**：SFTP + FTP 同步引擎
- **内置 FTP 服务器**：基于 Apache FtpServer（`EmbeddedFtpServer`）
- **SAF DocumentsProvider**：`WorkspaceDocumentsProvider` 供文件管理器访问
- **远程认证模型**：`RemoteAuth`（Password / PrivateKey 两种认证方式）

### 7.7 远程 SSH 连接管理 (`RemoteSshConnection`)

共享的 SSH 连接管理器，持有单个 sshj `SSHClient`，供多模块复用同一连接：

| 组件 | 使用方式 |
|------|---------|
| `RemoteSshEngine` | exec channel 执行命令 |
| `RemoteSftpFileAccess` | SFTP channel 文件操作 |
| `RemoteTerminalSessionManager` | shell channel 交互终端 |
| `WorkspaceRepository` | 远程工作区列表/新建/删除 |

#### 连接管理
- `connect(config)` — 按配置建立连接（支持密码/私钥认证）
- `disconnect()` — 断开连接
- `isConnected()` — 连接状态检测
- `tryReconnectIfDisconnected()` — 前台触发重连

#### 连接状态 (`ConnectionState`)
`DISCONNECTED` → `CONNECTING` → `CONNECTED` / `FAILED`

#### 监督协程 (`startSupervisor`)
- 远程模式下定期探活，断开后自动重连（指数退避：5s→30s）
- 重连成功后触发回调（`onReconnected`，供工作区重新加载）
- DisconnectListener 即时推状态，不等轮询

#### 远程工作区集成
- `updateWorkspaceSymlink(workspacePath)` — 更新远程 `~/workspace` 符号链接
- `uploadDocs(docs)` — 同步内置文档到远程 `~/.aicode/docs/`
- 连接成功后自动查询远程 $HOME 路径（`remoteHome`）

#### 连接配置 (`RemoteConnectionConfig`)
```kotlin
data class RemoteConnectionConfig(
    val host: String,           // 主机地址
    val port: Int = 22,         // 端口
    val username: String,       // 用户名
    val auth: RemoteAuth,       // 认证方式（Password / PrivateKey）
    val remoteWorkspacePath: String  // 远程工作区根路径
)
```

#### 错误处理 (`friendlySshError`)
将 SSH 异常翻译为中文友好提示（连接被拒绝、未知主机、认证失败、网络不可达、超时等）。

---

## 八、终端仿真器 (`terminal-emulator/`)

基于 Termux 开源项目的终端仿真器组件，共 15 个 Java 源文件：

| 文件 | 功能 |
|------|------|
| `TerminalEmulator.java` | **核心**：VT100/ANSI 转义序列状态机 |
| `TerminalBuffer.java` | 屏幕缓冲区（环形缓冲区） |
| `TerminalSession.java` | 终端会话（连接后端和仿真器） |
| `TerminalRow.java` | 终端行（字符 + 样式数组） |
| `TextStyle.java` | 文本样式编码（64 位 long） |
| `TerminalColors.java` | 颜色管理（OSC 4/10/11/12 动态修改） |
| `TerminalColorScheme.java` | 259 色索引颜色方案 |
| `KeyHandler.java` | 键盘码 → 转义序列映射 |
| `ByteQueue.java` | 环形字节缓冲区（线程安全） |
| `JNI.java` | JNI 桥接（PTY 创建、窗口大小调整） |
| `SubprocessBackend.java` | 本地 PTY 子进程后端 |
| `SessionBackend.java` | Session I/O 后端接口 |
| `TerminalOutput.java` | 输出回调抽象基类 |
| `TerminalSessionClient.java` | 客户端回调接口 |
| `WcWidth.java` | Unicode 9 wcwidth 实现 |

**支持的转义序列**：
- 光标控制（上下左右、Home/End、行列定位、保存/恢复）
- 屏幕操作（擦除 ED/EL、插入/删除行 IL/DL、滚动 SU/SD）
- SGR（Select Graphic Rendition）：颜色、粗体、斜体、下划线、闪烁、反转、隐藏、删除线
- DECSET/DECRESET：光标应用模式、自动换行、反转视频、鼠标追踪（1000/1002/1006/SGR）、括号粘贴模式（2004）、备用屏幕缓冲区（47/1047/1049）
- OSC：标题设置（0/1/2）、颜色修改（4/10/11/12）、剪贴板（52）
- 终端查询响应：DA1/DA2、DSR、CPR、DECRQM、DECRQSS
- 主缓冲区和备用缓冲区切换

---

## 九、终端视图 (`terminal-view/`)

| 文件 | 功能 |
|------|------|
| `TerminalView.java` | **核心**：终端 View 控件（输入处理、触摸事件、渲染调度） |
| `TerminalRenderer.java` | 终端渲染器（Canvas 绘制） |
| `GestureAndScaleRecognizer.java` | 手势识别（合并 GestureDetector + ScaleGestureDetector） |
| `TextSelectionCursorController.java` | 文本选择光标控制器 |
| `TextSelectionHandleView.java` | 文本选择手柄视图 |
| `CursorController.java` | 光标控制器接口 |

---

## 十、数据库迁移历史

Room 数据库版本 30，23 个迁移文件：

| 版本 | 文件 | 变更内容 |
|------|------|---------|
| 8 | `8_add_remote_servers.sql` | 远程服务器表 |
| 9 | `9_add_remote_connections_and_mounts.sql` | 远程连接 + 挂载点 |
| 10 | `10_add_auto_connect_to_mounts.sql` | 挂载点自动连接 |
| 11 | `11_add_is_enabled.sql` | 提供商启用开关 |
| 12 | `12_add_chat_session_mode.sql` | 会话模式 |
| 13 | `13_add_api_path.sql` | API 路径 |
| 14 | `14_add_use_response_api.sql` | Response API 开关 |
| 15 | `15_add_todo_items.sql` | 待办事项表 |
| 16 | `16_add_is_compacted.sql` | 压缩标记 |
| 17 | `17_add_context_summary_flag.sql` | 上下文摘要标记 |
| 18 | `18_add_compaction_marker_flag.sql` | 压缩分隔线标记 |
| 19 | `19_add_message_attachments.sql` | 消息附件 JSON |
| 20 | `20_add_session_provider_model.sql` | 会话级提供商/模型 |
| 21 | `21_add_git_credentials.sql` | Git 凭据表 |
| 22 | `22_add_session_token_usage.sql` | 会话 Token 用量 |
| 23 | `23_add_message_token_usage.sql` | 消息 Token 用量 |
| 24 | `24_replace_apiPath_with_useFullUrl.sql` | 完整 URL 模式 |
| 25 | `25_drop_apiPath_column.sql` | 清理废弃列 |
| 26 | `26_add_session_last_input_tokens.sql` | 最近输入 token 数 |
| 27 | `27_trim_oversized_rows.sql` | 截断超长内容 |
| 28 | `28_add_checkpoint_tables.sql` | 检查点 + 文件快照表 |
| 29 | `29_add_session_reasoning_effort.sql` | 思考强度设置 |
| 30 | `30_add_message_signature.sql` | Anthropic 签名 |

---

## 十一、数据流与关键链路

### 11.1 AI 对话主流程

```
用户输入 → AIAgentViewModel.enqueueAgentRequest()
  → 斜杠命令分流
  → 持久化用户消息 → 创建检查点
  → AgentWorkflow.executeEvents()
    → 拼接 SystemPrompt（增量式，含规则/技能/记忆/项目上下文）
    → 调用 AI Provider API（流式，3 种适配器）
    → 接收流式事件（AssistantDelta / ReasoningDelta / ToolCallStarted / ToolCallFinished）
    → 实时更新 UI（流式文本 + 思考过程 + 工具执行输出）
    → 工具执行（读文件/写文件/Shell 命令/搜索/...）
    → 工具结果持久化
    → 循环直到完成
  → 更新会话 Token 统计
  → 处理队列中下一条请求
  → 处理后台任务完成通知
```

### 11.2 命令执行链路

```
命令执行请求 → DelegatingCommandEngine
  → 根据 ExecutionMode 路由：
    ├── 本地模式 → LinuxContainerEngine
    │   → ProcessBuilder 启动 PRoot Alpine 容器
    │   → 在容器内执行命令
    │   → 流式读取 stdout/stderr
    └── 远程 SSH → RemoteSshEngine
        → SSH exec channel 执行命令
        → 流式读取输出
```

### 11.3 文件访问链路

```
文件读写请求 → DelegatingFileAccess
  → 根据 ExecutionMode 路由：
    ├── 本地模式 → LocalFileAccess
    │   → 直接读写本地文件系统
    └── 远程 SSH → RemoteSftpFileAccess
        → SFTP channel 文件操作
```

### 11.4 终端会话链路

```
终端会话请求 → DelegatingTerminalSessionProvider
  → 根据 ExecutionMode 路由：
    ├── 本地模式 → TerminalSessionManager
    │   → Termux TerminalSession + SubprocessBackend
    │   → JNI.createSubprocess() → PTY 子进程
    │   → TerminalEmulator 解析转义序列
    │   → TerminalView 渲染
    └── 远程 SSH → RemoteTerminalSessionManager
        → SSH shell channel
        → SessionBackend 流式接口
        → TerminalEmulator + TerminalView 渲染
```

### 11.5 Git 凭据 IPC 链路

```
容器内 git 执行 → 缺凭据
  → git-credential-aicode helper 写 cred-req-<id> 文件
  → FileObserver 捕获文件变更
  → CredentialRequestBridge 暴露 StateFlow
  → 全局 Compose 弹窗（GlobalCredentialDialogHost）
  → 用户回填凭据
  → 写 cred-resp-<id> 响应文件
  → helper 轮询取走 → 喂回 git → 继续执行
```

---

## 十二、测试覆盖

| 测试文件 | 测试内容 |
|---------|---------|
| `FileMigrationTest.kt` | 迁移加载器版本和 SQL 分割 |
| `ErrorUtilsTest.kt` | 异常链拼接、友好提示 |
| `CodeChangeTrackerTest.kt` | 代码变更追踪逻辑 |
| `HasVisibleContentTest.kt` | 字符串可视内容检测 |
| `BackupCryptoTest.kt` | AES-GCM 加密解密 |

---

## 十三、构建与发布

### 构建命令
```bash
./gradlew :app:assembleUniversalDebug    # 单 flavor 冒烟（日常开发）
./gradlew assembleRelease                 # 三 flavor 全量 Release
./gradlew :app:testUniversalDebugUnitTest # 单 flavor 单元测试
```

### 版本号规范
- `versionName`：由 Git Tag 动态解析（如 `v1.7.0` → `1.7.0`，非 Tag 提交 → `1.7.0-dev.N+hash`）
- `versionCode`：由 Git 提交数自动生成（单调递增）

### 发版流程
1. 在 `main` 分支打 Tag（如 `v1.7.0-rc1`）
2. CI 自动构建 Release
3. 真机测试
4. 有问题 → 从 RC Tag 拉 `hotfix/` 分支修复 → 升 rc 序号 → 合回 main
5. 无问题 → 打正式 Tag（`v1.7.0`）

---

## 十四、已知限制

1. **targetSdk 锁定 28**：绕过 Android 10+ W^X 策略，使 PRoot 可执行，代价是不能上 Google Play
2. **三 flavor 拆包**：按 CPU/容器镜像拆分，错架构安装无法运行 PRoot
3. **迁移脚本限制**：SQL 文件按 `;` 分割，字符串字面量中不能出现 `;`

---

## 十五、致谢与参考

- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考