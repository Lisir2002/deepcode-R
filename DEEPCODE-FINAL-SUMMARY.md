# R-CodeCore 项目深度总结文档（经源码核验的最终版 2026-08-09）

> **文档权威性声明**：本文档是 R-CodeCore 项目的**唯一官方深度总结文档**，全部声明均以实际源码逐行核验为依据；不存在其他"旧版总结"作为并行来源。  
> 每一次代码改动涉及的同步范围见 §十六，请在后续提交前回查并更新对应章节。

---

## 一、项目设计哲学与定位

### 1.1 产品定位
R-CodeCore 是**原生 Android 上的 AI 编程 IDE**，核心是让 **AI Agent 拥有真实的执行环境**——直接读写项目文件、执行 Shell、跑构建/测试、管理 Git、操作交互式终端，且所有动作都发生在**同一个工作区**里（本地 PRoot Alpine 容器或同一台远程 SSH 服务器）。

### 1.2 核心设计决策与权衡（实际核验确认）

| 决策 | 实际做法 | Why | 代价 |
|------|---------|-----|------|
| **targetSdk 锁定 28** | `build.gradle.kts` targetSdk = 28 | Android 10+ W^X 禁止 App 私有可写目录执行二进制，PRoot 需执行 rootfs 内 `/bin/sh`/`/usr/bin/git` | 无法上架 Google Play（与 Termux 同一取舍） |
| **arm64-v8a 真机单产物策略** | `app/build.gradle.kts` abiFilters = [arm64-v8a]，sourceSets 仅挂 _armAssets（ContainerInstaller.ASSET_DIR 固定 container/arm） | x86_64 构建资源省 0，PRoot/rootfs/so 单一架构 APK 体积净省 3~5 MB | 不支持 x86_64 模拟器安装（安装阶段因缺 so 失败，无运行时崩溃） |
| **SOFT_INPUT_ADJUST_NOTHING 全局** | MainActivity `window.setSoftInputMode(ADJUST_NOTHING)`，各页面 `rememberImeBottomInset()` 自绘跟随 | 切页时旧页面 dispose 会恢复默认 softInputMode 造成白屏闪烁 | 每个页面必须手动处理键盘边距，漏处理则输入栏被键盘遮挡 |
| **BouncyCastle 注册到 Provider 末尾** | `Security.removeProvider("BC")` → `Security.addProvider(...)` → `SecurityUtils.setSecurityProvider("BC")` 还要告诉 sshj 用 BC | sshj 0.38.0 X25519 需要完整版 BC；放首位会抢占 BKS 实现导致 OkHttp "BKS not found" | - |
| **RemoteSftpFileAccess 不用真 SFTP** | exec channel + `cat`/`printf` + `base64` | sshj 0.38.0 SFTP 在 Android Dalvik 上 Buffer 溢出必崩 | 读写性能略低，但稳定 |
| **SQL 文件驱动迁移** | `assets/migrations/{VERSION}_{desc}.sql` | DBA 直接编辑 SQL，不需改 Kotlin；升级只加新文件（共 24 个 SQL，v8→v31） | SQL 字符串字面量里不能出现 `;`（用 `char(59)`） |
| **凭据/配置独立于 rootfs 挂载** | `filesDir/rcodecore/`（宿主）↔ `~/.rcodecore`（容器内 PRoot `-b`） | 升级 rootfs/切 profile 不丢 prompts、docs、git 凭据、权限规则、记忆 | - |
| **Git 页 UI 直连 CommandEngine、不经工具链** | GitRepository 直接注入 `CommandEngine` 跑 `git` CLI | 用户主动点 UI 不应再经 AI 工具权限审批；与 AI Bash/终端 git 共用同 credential helper | - |
| **Git 凭据不走环境变量、走 `credential.helper=store`** | `git-credentials` 文件 + `git-credential-rcodecore` helper 文件 IPC 桥 | 三端（UI Git / AI Bash / 终端 git）共用同一份凭据；`GIT_ASKPASS` 在 AI 后台子进程无 tty 时不可用 | 容器初始化和 UI 启动时需 sync |
| **System Prompt 三优先级加载** | `prompts.custom`（用户）> `rcodecore/prompts`（释放副本）> `assets/prompts`（内置兜底） | 用户自定义 system prompt 能跨 rootfs 升级保留；用 `ContainerInstaller.extractPrompts(context)` 释放 | - |

---

## 二、完整仓库与代码结构

### 2.1 关键目录树（Glob 扫描确认）

```
deepcode-R/
├── AGENTS.md                          # AI 协同开发规范（资产同步纪律，项目规则唯一权威源）
├── DEEPCODE-FINAL-SUMMARY.md          # 本文档 —— 项目唯一官方深度总结
├── README.md / README.en.md           # 项目首页（中英文）
├── LICENSE (GPL-3.0)
├── .githooks/commit-msg                # Conventional Commits 校验钩子
├── .github/workflows/
│   ├── ci.yml                          # main 推送/PR：assembleRelease + testReleaseUnitTest（release classpath 做门禁）
│   └── android-release.yml             # Tag 打 v*：assembleRelease → 单 APK rcodecore-arm64-<ver>.apk → GitHub Release
│
├── build.gradle.kts                    # AGP 8.9.3 / Kotlin 2.2.21 / Hilt 2.56.1 / KSP 2.2.21-2.0.5
├── settings.gradle.kts                 # 含腾讯云/阿里镜像 + JitPack + MavenCentral
├── gradle.properties                   # JVM 堆 -Xmx4g、Kotlin 增量编译
│   └── app/
│       ├── build.gradle.kts            # arm64-v8a 单 ABI 真机单产物、动态版本号、签名回退策略（debug keystore 兜底）
│   ├── proguard-rules.pro              # 实际 104 行 keep 规则
│   └── src/
│       ├── _armAssets/container/arm/   # arm64 rootfs + proot/bin/loader/.so（真机单架构，无 x86 资产）
│       │
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── api.official.json           # 预置模型元数据
│       │   │   ├── rcodecore/git-credential-rcodecore  # Git 凭据 helper 脚本
│       │   │   ├── migrations/8_add_remote_servers.sql … 31_add_encrypted_fields.sql
│       │   │   │                             # 24 个 SQL（v8 → v31）
│       │   │   ├── prompts/                   # 11 段系统提示词
│       │   │   │   00-identity.md / 10-communication.md / 15-project-rules.md
│       │   │   │   20-coding-discipline.md / 30-comments.md / 40-approach.md
│       │   │   │   50-safety.md / 60-tools-and-paths.md / 70-skills-and-mcp.md
│       │   │   │   80-plan-mode.md / 81-auto-mode.md
│       │   │   └── docs/                        # 11 篇使用指南
│       │   ├── java/com/deep/rcode/             # 约 220+ .kt 文件
│       │   ├── res/values & values-en           # 中/英 strings.xml
│       │   └── res/xml/                         # network_security_config, locales_config, file_paths
│       ├── debug/res/values                     # .debug 包名变体
│       └── test/java                            # 14 个 *Test.kt
│
├── terminal-emulator/  (15 Java files)  # Termux 终端仿真 + JNI libtermux.so
└── terminal-view/      (8 Java files)   # Canvas 渲染 + 手势/选择/光标
```

### 2.2 Kotlin 模块分布（全盘 Glob + Grep 确认）

| 模块 | 文件数 | 核心职责 |
|------|--------|---------|
| `di/` (AgentModule, RepositoryModule, BackupModule) | 3 | Hilt 注入定义 |
| `core/`（FileLogger, AILogger, LineDiff, ErrorUtils, MigrationLoader, CredentialEncryptor, ImeInset, Theme） | ~25 | 基础设施 |
| `feature/agent/`（工作流/工具/权限/MCP/技能/记忆/会话/检查点/提示词/Provider 适配/斜杠命令/ViewModel/30 Composable） | ~100 | AI Agent 全部逻辑 |
| `feature/git/`（GitRepository, GitErrorMessage, GitGraphBuilder, 3 Tab UI, DiffViewer, GitViewModel） | 8 | Git 可视化 |
| `feature/workspace/`（FileAccessProvider 三实现、SyncEngine 三客户端、FtpServerManager、WorkspaceRepository、DocumentsProvider、4 ViewModel/UI） | ~30 | 工作区/文件/同步 |
| `feature/terminal/`（SessionManager 本地/远程、SshShellBackend、KeepaliveService、Bundle 管理、TerminalSettingsRepository、3 ViewModel/UI、6 个内置 Bundle） | ~16 | 终端系统 |
| `feature/settings/`（AIProvider Dao/Entity/Repository/Impl、15 DataStore Settings Repositories、ModelMetadataService、SettingsViewModel、20 设置页） | 36 | 设置 & Provider 管理 |
| `feature/credentials/`（CredentialRequestBridge、GitCredentialsFileSync、GitCredential Dao/Entity/Repository、CredentialViewModel、5 UI） | ~12 | Git 凭据统一弹窗 |
| `feature/backup/`（BackupManagerImpl、BackupCrypto、BackupSnapshot、BackupViewModel、BackupSection） | 6 | AES 加密备份恢复 |
| AIEditorApp + MainActivity | 2 | App 入口与主导航 |

---

## 三、启动生命周期（逐行源码核验 AIEditorApp / MainActivity）

### 3.1 `AIEditorApp.kt` 位置：`app/src/main/java/com/deep/rcode/AIEditorApp.kt`

**Phase A：`attachBaseContext(base)`（Hilt 注入前，最早期）**
1. **FileLogger.init(base)** 传 Context 而非 `filesDir/"logs"`
2. **AILogger.init(base)**
3. **installCrashHandler()**：全局未捕获异常先 FileLogger 落盘 → 交回原系统 handler（保留崩溃对话框）
4. 同步读 `SharedPreferences("language_prefs_sync", "language_tag")` → `setLocale()`

**Phase B：`onCreate()`（Hilt 注入完成，所有 `@Inject lateinit var` 已赋值）**
1. **registerBouncyCastle()**：`Security.removeProvider("BC")` → `Security.addProvider(BouncyCastleProvider())` → **`SecurityUtils.setSecurityProvider("BC")`** 必须告诉 sshj 用它
2. **createNotificationChannels()**：只有 1 个 channel = `"terminal_service"`（IMPORTANCE_LOW，"Terminal Services"，showBadge=false）
3. **credentialRequestBridge.start()**：FileObserver（监听 CREATE/CLOSE_WRITE/MOVED_TO）+ `fallbackPollLoop`（1s 兜底扫 cred-req-*）+ cleanupStale（删上次残留）
4. 并行后台任务（均走 `appScope.launch`）：
   - **ContainerInstaller.extractDocs(this)**
   - **ContainerInstaller.extractPrompts(this)**
   - **gitCredentialsFileSync.syncAll()**：写 `~/.rcodecore/git-credentials`（store 格式）+ sync 署名
   - **modelMetadataService.refreshFromNetworkIfStale()**（24h 缓存；失败静默，兜底 assets/api.official.json）
   - **logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }**：持久化等级实时同步
   - 执行模式初始化：`executionModeRepository.executionModeFlow.first()` → `executionModeHolder.setMode(mode)`；若 REMOTE_SSH → `remoteSshConnection.connect(Password(settings.password))`（目前密码路径为实际唯一场景）→ **syncDocsToRemote()**（SSH 连接成功同步 assets/docs 到远程）；失败静默首命令自动重试
   - **remoteSshConnection.startSupervisor(appScope) { workspaceRepository.initialize(); syncDocsToRemote() }**：30s 心跳 + 5s→30s 指数退避重连；重连后重新加载工作区并同步 docs
   - **keepaliveSettings.enabledFlow.distinctUntilChanged**：开→`TerminalKeepaliveService.enablePersistent`；**由开变关才 disable**（避免凭空拉起 Service）
5. **mcpManager.start()**：并行连接所有启用的 MCP server → 注册工具 → 发状态 StateFlow

### 3.2 `MainActivity.kt` 位置：`app/src/main/java/com/deep/rcode/MainActivity.kt`

1. `attachBaseContext` 同步 SharedPreferences locale
2. `onCreate`：
   - `enableEdgeToEdge()`
   - `lifecycleScope` 订阅语言变化 → `applicationContext.resources.updateConfiguration(...)` + recreate()
   - 申请存储权限（READ/WRITE_EXTERNAL_STORAGE）
   - API 30+：`window.setSoftInputMode(SOFT_INPUT_ADJUST_NOTHING)` 全局
   - `setContent { AppScaffold → AppNavigation() + GlobalCredentialDialogHost }`
     - **外层** ModalNavigationDrawer（在 NavHost **外面**，状态不随切页重置；仅 chat 路由允许手势打开）：
       - ChatDrawerContent：会话列表（切/新建/删除/重命名/导出 .tar.gz）、工作区选择、导航到 settings
     - **内层 NavHost**（有过渡动画，terminal 页单独 terminalEnter/Exit）：
       - `chat` → AIChatPanel；`settings` → SettingsScreen
       - `terminal` / `terminal_settings` / `terminal_bundle_manager` / `terminal_custom_packages`
       - `git` → GitScreen
     - **全局浮层**：GlobalCredentialDialogHost（任意页面都能弹 Git 凭据）
3. **onResume**：远程模式下立即 `remoteSshConnection.tryReconnectIfDisconnected()`（前台保证不被系统 FGS 限制挡掉）

---

## 四、AI Agent 引擎（完整内部运作）

### 4.1 工具抽象体系

文件：`app/src/main/java/com/deep/rcode/feature/agent/domain/tool/AgentTool.kt`

```kotlin
abstract class AgentTool {
    abstract val name, description, parameters
    open val permissionPolicy: ToolPermissionPolicy = AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()
    open fun effectiveCapabilities(args): Set<ToolCapability> // 可覆写按参数细化
    open fun buildPermissionRequest(callId, args, argsPreview) -> PendingToolPermission
    abstract suspend fun execute(args): ToolResult
    open suspend fun executeWithContext(args, context): ToolResult
    open fun toJsonSchema(): Map<String, Any>      // 传给 LLM 的 JSON Schema
}
interface StreamingAgentTool {  // 执行中可 emit Progress 行
    fun executeStream(args, context): Flow<ToolStreamEvent.Progress | Completed>
}
// 12 个 ToolCapability 枚举：
READ_WORKSPACE, WRITE_WORKSPACE, EXECUTE_COMMANDS, NETWORK_READ, NETWORK_WRITE,
READ_AGENT_CONFIG, MODIFY_AGENT_CONFIG, MODIFY_CONTAINER_ENV, USER_INTERACTION,
MODIFY_SESSION_STATE, MODIFY_TODO_STATE, EXTERNAL_TOOL
```

### 4.2 17 个内置工具（AgentModule.provideToolRegistry 216-252 行逐一核对）

**注册顺序与精确配置（按实际 register 顺序）**：

| # | 工具注册名 | 文件 | permissionPolicy | capabilities | 备注 |
|---|-----------|------|-----------------|--------------|------|
| 1 | `readFile` | file/FileTools.kt ReadFileTool | AUTO_APPROVE | READ_WORKSPACE | 2000 行/段，另限 200KB；返回 structured JSON（content/total_lines/truncated 等） |
| 2 | `sendFile` | file/SendFileTool.kt | AUTO_APPROVE | USER_INTERACTION | ≤10 文件 ≤100MB；文件卡片 UI 渲染，**内容不回模型上下文**（不耗 token） |
| 3 | `viewImage` | file/ImageTools.kt ViewImageTool | AUTO_APPROVE | READ_WORKSPACE | 当前模型原生 vision 优先；否则自动换 VisionModel 识图（vision_fallback） |
| 4 | `writeFile` | file/FileTools.kt WriteFileTool | **ASK** | WRITE_WORKSPACE | overwrite=true/false；返回统一 hunks JSON（旧→新 LCS diff；>2000 行文件退化为整体新增） |
| 5 | `editFile` | editor/EditFileTool.kt | **ASK** | WRITE_WORKSPACE | 原子字符串编辑 edits[]（old_string/new_string/replace_all）；任一失败整批回滚；返回 hunks diff |
| 6 | `Bash` | container/ExecuteCommandTool.kt | **ASK** | EXECUTE_COMMANDS | **StreamingAgentTool**；timeout 默认 120s / 上限 1800s；超大输出 BoundedOutput（头 20k + 尾 20k）；异常时保留此前 Progress |
| 7 | `terminal` | container/BackgroundTerminalTools.kt | **按 action** | 混合 | `action=start/send` = ASK（SHELL）；`read/listTabs/close` = AUTO_APPROVE |
| 8 | `list` | explorer/ListFilesTool.kt | AUTO_APPROVE | READ_WORKSPACE | `ls` 风格，支持 `-la -R -t -S -v -a` 等 ripgrep 风格参数 |
| 9 | `search` | explorer/SearchCodeTool.kt | AUTO_APPROVE | READ_WORKSPACE | ripgrep CLI；只接受 ripgrep 参数，禁止 shell pipe |
| 10 | `loadSkill` | skill/LoadSkillTool.kt | AUTO_APPROVE | READ_AGENT_CONFIG | YAML frontmatter + Markdown 正文 |
| 11 | `askUserQuestion` | question/AskUserQuestionTool.kt | AUTO_APPROVE | USER_INTERACTION | 结构化选择题；StateFlow 阻塞等用户选；每问 1-4 题 × 2-4 选项 |
| 12 | `manageMcp` | mcp/ManageMcpTool.kt | **ASK** | MODIFY_AGENT_CONFIG | add_stdio/add_http/remove/list；单工具不可记忆 always（NON_REMEMBERABLE_CAPABILITIES） |
| 13 | `websearch` | search/WebSearchTool.kt | AUTO_APPROVE | NETWORK_READ | 互联网搜索 |
| 14 | `webfetch` | search/WebFetchTool.kt | AUTO_APPROVE | NETWORK_READ | 网页抓取（Jsoup 清洗正文） |
| 15 | `switchMode` | mode/SwitchModeTool.kt | AUTO_APPROVE | USER_INTERACTION | PLAN↔BUILD 申请；BUILD→AUTO 不允许；AUTO→PLAN 是唯一退出 AUTO 路径 |
| 16 | `todo` | todo/TodoTool.kt | **AUTO_APPROVE** | MODIFY_TODO_STATE | 快照式 items[] 完整替换；按 subject 归一化复用旧实体的 id 与 createdAt |
| 17 | `memory` | memory/MemoryTool.kt | **AUTO_APPROVE** | READ_AGENT_CONFIG + MODIFY_AGENT_CONFIG（默认）；**effectiveCapabilities: action=read/list → 仅 READ_AGENT_CONFIG**（PLAN 模式也放行） | project/global 双 scope；5 种 action：read/save/edit/delete/list；edit 走 edits[] old→new 局部编辑（同 editFile） |

**动态 MCP 工具**：名称 = `mcp__<server>__<tool>`，capability = `EXTERNAL_TOOL`（NON_REMEMBERABLE_CAPABILITIES，不可记忆 always），toJsonSchema() 透传服务端原始 inputSchema。

### 4.3 ToolPermissionPolicyEngine 完整评估链路（源码逐分支核对）

文件：`app/src/main/java/com/deep/rcode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt`

**总入口 evaluate(tool, toolName, args, mode) 执行顺序（从外层到内层）**：

```
evaluate()
│
├─ 0) PLAN 模式 + 危险 tool → DENY
│     危险 capabilities = {WRITE_WORKSPACE, EXECUTE_COMMANDS, NETWORK_WRITE,
│       MODIFY_AGENT_CONFIG, MODIFY_CONTAINER_ENV, EXTERNAL_TOOL}
│     另有 safePlanModeTools 白名单 = {list, search}（即使 capability 命中也放行）
│     terminal tool 仅 action=read 安全；writeFile/editFile/Bash 额外 dangerousTools 名单兜底
│
├─ 1) AUTO 模式
│     └─ shell 命令 → checkCatastrophicRm()
│        └─ 命中 → DENY
│        其他 → ALLOW（AUTO 下灾难性 rm 仍被拦截 其他全放行）
│
├─ 2) capabilities == {READ_AGENT_CONFIG}（纯读）→ ALLOW（memory read/list）
│
└─ 分两条路径：shell 工具走 evaluateShell()，其他走 evaluateGeneric()

evaluateShell(rules, args) —— Bash / terminal(action=start|send)
│
├─ 0) rm 高危防护 checkCatastrophicRm() —— 根目录/工作区/~/tmp/系统 16 目录
│     实际匹配：/、/*、//*、*、.*、.、./*、..、../.*、~、~/*、
│       ~/workspace(及其变体 8 条)/tmp、/tmp/*、/bin/etc/usr/proc/sys... 及子目录前缀
│     命中 → DENY（带中文原因）
│
├─ 1) DENY 规则优先（可覆盖内置白名单）—— 任一段命中 DENY → DENY
│
├─ 2) analyzable=false → ASK，rememberablePatterns=空
│     不可静态判定触发条件：
│        命令替换 $(...) / 反引号（双引号内仍生效）、分组 ()、进程替换 <( >( )、
│        重定向到绝对路径 > /xxx、未闭合引号
│
├─ 3) BuiltInSafeCommands 每段全命中 → ALLOW（不落盘，符合"规则绝不落盘容器"原则）
│     （ls/cat/head/tail/echo/pwd/whoami/env/grep -n 只读版/find -name 只读版/
│      git status/log/diff/branch 等）
│
├─ 4) 已记忆 ALLOW 规则 + 每段命中
│     对 rm 有精细二次校验：存量规则仅 "rm"/"rm -rf"（无目标）不会放行递归
│
└─ 5) 否则 → ASK + 计算 rememberablePatterns
     ├── rm 段若 空目标/递归/通配 → rememberablePatterns=空（"高风险删除不可记忆"原因）
     └── 其余 → 段 programPrefix 或 "程序+子命令"（子命令分发器 45 个程序）
         SUBCOMMAND_DISPATCHERS = git/gh、npm/npx/yarn/pnpm/pnpx、
            cargo/rustup、go、pip/pip3/poetry/uv/conda、gradle/mvn/sbt、
            docker/kubectl/helm、adb/fastlane、brew/apt/apt-get/gem/bundle、
            az/aws/gcloud/terraform/ansible

evaluateGeneric(rules, capabilities)
├── 任一条 WHOLE_TOOL DENY → DENY
├── 任一条 WHOLE_TOOL ALLOW → ALLOW
├── 含 NON_REMEMBERABLE_CAPABILITIES = {MODIFY_AGENT_CONFIG, MODIFY_CONTAINER_ENV, EXTERNAL_TOOL}
│   → ASK，rememberablePatterns=空（"仅支持单次放行"原因）
└── 否则 → ASK，rememberablePatterns=["*"]（整工具记忆）
```

**ShellCommandParser 补充细节**：
- **段首环境赋值跳过**：`FOO=bar BAR=2 git pull` 仍按 `git pull` 匹配
- **匹配按字面首 token**：`/usr/bin/node` 不会被 `node` 规则命中（宁可多问，不误放）
- **rm 解析 RmInfo**：支持 `--` 双破折号、`-r -R --recursive` 合并 flag、`*`/`?` 通配识别、targetPaths 精确列表

---

## 五、StatefulAgentWorkflow（MVI Reducer）

文件：`app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt`

**AgentSessionState**（实际字段确认）：
```kotlin
messages: List<AgentMessage>, iterations: Int, isFinished: Boolean, error: String?
batchToolCalls: List<ToolCall>              // 本批模型原始 tool_calls（保持顺序）
pendingPermissionCalls: List<ToolCall>      // 待请求权限
approvedToolCalls: List<ToolCall>           // 已批准，待并行执行
rejectedToolResults: Map<String, ToolBatchResult>  // 被策略/系统拒绝（非用户拒绝）key=toolCallId
pendingVisionRound: Boolean                 // 当前模型不支持 vision 时，下一轮切 vision 模型识图
```

**Action → Reducer → (newState, SideEffect) 链路**：
- `InitRequest(initialMessages)` → + SideEffect.CallLlm（第 1 轮）
- `LlmResponse(AIResponse 含 tool_calls[])` → tool_calls 非空 → SideEffect.RequestPermission 逐个；空 → finished
- `LlmError(reason)` → RetryPolicy：429/5xx + retries < max → CallLlm(指数退避)；否则 finished
- `PermissionEvaluated(toolCall, approved, ...)`：
  - approved → 加入 approvedToolCalls；pending 全评完 → SideEffect.ExecuteToolBatch（所有批准的并行）
  - rejected → **SideEffect.CancelToolBatch(剩余未评的全部 toolCalls)**：为**所有 N 个原始 tool_calls** 都补发 rejection_reason → 对齐 OpenAI 工具响应数量约束（防 400）→ CallLlm(下一轮)
- `ToolBatchFinished(results[])` → 所有 tool_result 追加消息 → CallLlm(下一轮)

**SideEffect 处理器内部细节**：
- CallLlm：sanitizeImagesForModel(剥离模型不支持的图片) → compactIfNeeded(70% 窗口阈值) → providerAdapter.completeStream()
- RequestPermission：并行 policyEngine.evaluate → ToolPermissionManager.awaitApproval（StateFlow 阻塞 UI 弹窗）→ 用户选 ALWAYS → policyEngine.remember → PermissionEvaluated
- ExecuteToolBatch：`coroutineScope { approved.map { async { checkpointManager.beforeFileModified → tool.executeWithContext() } }.awaitAll() }` checkpoint 在 execute 前捕获快照 → ToolBatchFinished

### 5.1 Provider 适配器（7 个实际文件核对）

```
feature/agent/domain/provider/
├── AIProvider.kt (接口: completeStream / complete / sanitizeImages)
├── OpenAIAdapter.kt   (两个 Retrofit: ChatCompletions + Responses API useResponseApi 切换)
├── AnthropicAdapter.kt (Messages API SSE: content_block_delta + start/stop; extended_thinking signature)
├── GeminiAdapter.kt    (generateContent; responseModalities=["THINKING","TEXT"] 思考配置)
├── HttpErrorEnricher.kt (401/403/429 等 → 中文友好信息)
└── RetryPolicy.kt (429/5xx 退避重试)
```

**Anthropic signature**：`messageStartEvent.message.metadata.signature` → AgentMessageEntity.signature（v30 迁移加列）  
**OpenAI extended thinking**：传 reasoning_effort（model_extra）  
**PDF 输入**：Anthropic 转 base64 source

### 5.2 SystemPromptProvider 11 段（实际 assets/prompts 11 文件确认）

00-identity → 10-communication → 15-project-rules(加载 AGENTS.md，代码回退查 CLAUDE.md 但项目只提供 AGENTS.md) → 20-coding-discipline → 30-comments → 40-approach → 50-safety(凭据脱敏) → 60-tools-and-paths → 70-skills-and-mcp(manageMcp 自动装 npx/pip 前置环境，绝不手改 mcp.json) → 80-plan-mode(只读约束 + PLAN 质量标准 + 末尾 switchMode 触发审查) → 81-auto-mode(全放行但灾难性 rm 仍拦截)

---

## 六、数据库与持久化（10 个 Entity 文件 + 24 个 SQL 迁移核对）

### 6.1 Room Schema Version 31（AgentDatabase）

**9 张 Entity 表 + 对应 Dao**（实际 10 个 Entity 类名确认）：

| 表（Entity） | 路径（相对包根 feature/.../data/local/entity/） | 关键列（含 v31 新增加密字段） |
|-------------|------|------------------------------|
| chat_sessions ChatSessionEntity | agent/.../entity | id, title, createdAt, updatedAt, workspacePath, providerId, model, mode, reasoningEffort, lastInputTokens, totalInputTokens, totalOutputTokens, isCompacted |
| agent_messages AgentMessageEntity | agent/.../entity | id(会话内雪花id), sessionId, timestamp, role(USER/ASSISTANT/TOOL/SYSTEM), content(JSON), reasoning(JSON), attachments(JSON), toolCallId, toolName, **signature(Anthropic v30)**, inputTokens, outputTokens, hasVisibleContent, isContextSummary, isCompacted, compactedFromHash, isCompactionMarker(v18) |
| todo_items TodoItemEntity | agent/.../entity | id, sessionId, subject, description, status, priority, order, createdAt, updatedAt |
| checkpoints CheckpointEntity | agent/.../entity | id, sessionId, messageCount, description, createdAt |
| checkpoint_file_snapshots CheckpointFileSnapshotEntity | agent/.../entity | id, checkpointId, path, originalContent |
| ai_providers AIProviderEntity | settings/.../entity | id, name, type(OPENAI/ANTHROPIC/GEMINI/CUSTOM), baseUrl, **apiKeyEncrypted**, **apiKeyIv**, useFullUrl(v24), useResponseApi(v14), models(JSON), defaultModel, selectedModel, isEnabled(v11), sortKey |
| git_credentials GitCredentialEntity | credentials/.../entity | id, host, username, **tokenEncrypted**, **tokenIv**, isDefault, createdAt(v21) |
| remote_connections RemoteConnectionEntity | workspace/.../entity | id, name, protocol(SFTP/FTP/LOCAL), host, port, username, **passwordEncrypted/IV**, **privateKeyEncrypted/IV**, **passphraseEncrypted/IV**, authType, localTargetDir, isAutoConnect, privateKeyName(v9 基础 + v10 auto_connect) |
| remote_mounts RemoteMountEntity | workspace/.../entity | id, connectionId, remoteDir, localMountPath, isAutoConnect, isConnected, lastSyncAt |

**v31 加密模式**：每条记录独立随机 12 字节 IV → `*_iv` 列；AndroidKeyStore AES-256-GCM 加密 → `*_encrypted` 列（Base64）

### 6.2 文件驱动迁移（24 个 SQL 文件，8→31 里程碑）

`assets/migrations/{N}_{desc}.sql` 被 MigrationLoader 扫描 → FileMigration(start=N-1, end=N)

**里程碑版本（按时间顺序）**：
- v8 远程服务器表
- v9 connections + mounts；v10 isAutoConnect 加列
- v11 ai_providers.isEnabled；v12 chat_sessions.mode；v13 apiPath；v14 useResponseApi
- v15 add_todo_items；v16 sessions/messages isCompacted
- v17 messages isContextSummary；v18 messages isCompactionMarker
- v19 message attachments；v20 session providerId + model
- v21 git_credentials；v22 session tokens 计数；v23 message tokens 计数
- v24 replace apiPath with useFullUrl；v25 drop apiPath 列
- v26 session.lastInputTokens；v27 trim_oversized_rows（清理）
- v28 checkpoints + checkpoint_file_snapshots 两张表
- v29 sessions.reasoning_effort；**v30 messages.signature**（Anthropic 扩展思考签名）
- **v31 全部加密列（apiKey/token/password/privateKey/passphrase + IV 列）**

---

## 七、Git 三端凭据统一（CredentialRequestBridge + GitRepository 源码核对）

### 7.1 完整链路（UI Git / AI Bash / 终端 git 共用同一份）

```
Room GitCredentialEntity (App 私有 DB)
   └── GitCredentialsFileSync.syncAll() ──┐
                                           ▼
        filesDir/rcodecore/git-credentials  ←── PRoot -b 绑定 → 容器内 ~/.rcodecore/git-credentials
                                           │
        容器 ~/.gitconfig [credential] helper=store --file=<上者路径>
                                           │ (git 自带 store helper 先查，命中直接用，无弹窗)
                                           │
                    未命中 → fallback 用 /root/.rcodecore/git-credential-rcodecore
                                           │
               helper 写 cred-req-<pid> (host=xxx)  ← 原子写 .tmp → rename
                     │
                     ▼
         CredentialRequestBridge（双向捕获）
            ├── FileObserver(CREATE / CLOSE_WRITE / MOVED_TO)
            └── fallbackPollLoop(1000ms 兜底扫)
                     │ seen = LinkedHashSetWithCap(64) 去重
                     │ LinuxContainerEngine.incPromptInFlight()
                     │   → 暂停 120s 超时看门狗（用户填凭据几分钟不杀 git）
                     ▼
         GlobalCredentialDialogHost（任意 Compose 页面）
             用户填 host/username/token →
                 credentialRepository.save() + fileSync.syncAll()（下次直接用 store）
                 respond(cred-resp-<pid>.tmp → rename 原子) →
                     LinuxContainerEngine.decPromptInFlight()（恢复看门狗）
                     helper 读到 → stdout 吐 username=/password= → 喂回 git → 自动续跑
                 用户取消 → cancel=1 响应 → helper 非零退出 → git 认证失败（如实显示）
```

### 7.2 GitRepository 命令封装（GitRepository.kt 源码核对）

```kotlin
class GitRepository @Inject constructor(engine: CommandEngine, workspaceRepo: WorkspaceRepository) {
    // 只读（不判退出码，解析得空即空态）
    private suspend fun git(vararg args): String
    // 写命令（非零退出 → GitCommandFailureException 如实抛）
    private suspend fun gitChecked(vararg args): String
    
    // 关键方法（按实际存在）：
    isRepo() = git rev-parse --is-inside-work-tree == "true"
    status() = git status --porcelain=v1 -b → 分支 ahead/behind + staged/unstaged/untracked（含 rename）
    localRefsOnly() = 仅 refs/heads 轻量快照（首屏 <1s）
    loadAllRefs() = for-each-ref refs/heads refs/tags（全量，BRANCHES tab 用）
    log(limit=50) = git log --pretty=format: Hash|ShortHash|Author|RelativeTime|Subject
    graph(limit=100, refs) + graphAppend(prev, refs, limit) = 分页拓扑图，GitGraphBuilder 纯 Kotlin 泳道分配
    diff(file, staged) = unified diff → DiffViewer
    initRepo() / stage / unstage / restoreWorkspace / deleteUntracked
    commit(message, signOff) / stageAllAndCommit
    checkout(branch) / checkoutNew(name, from) / deleteBranch / renameBranch
    tag(create/list/delete)
    pull(rebase) / push(setUpstream)
}
```

**注意**：凭据缺失时 credential.helper=store → helper IPC → GlobalCredentialDialogHost 自动介入（与 AI Bash/终端 git 同一链）

---

## 八、容器引擎 & 远程 SSH

### 8.1 LinuxContainerEngine（PRoot 本地）

- **ContainerInstaller 四步**：Mutex + initScope(SupervisorJob+IO) 防并发与页面取消 → 查 `provisioned.flag` → 复制 `_armAssets/container/{arch}/` 到 `app.getDir("container")` → 解压 rootfs（Commons Compress 1.26.2 + XZ 1.10）→ Deploy proot 二进制 chmod 755 → apk add 基础工具 → 写 credential helper → 建 ~/workspace → 写 flag
- **buildPRootCommand 拼装**：`proot --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -b workspace -b ~/.rcodecore <extraBindings> -w <cwd> /bin/sh -c "<cmd>"`
- **执行模式**：`runCommandSync`（去 ANSI，BoundedOutput 头 20k+尾 20k）+ `runCommandStream`（Flow<Line|Exit>；完整输出另存 `~/.rcodecore/tool-output/<ts>.log`）
- **看门狗**：超时 kill -9；promptInFlight>0 时临时延长

### 8.2 RemoteSshConnection 共享单连接（sshj 0.38.0）

- **认证**：目前实际使用路径 = `RemoteAuth.Password(settings.password)`（PrivateKey 底层有接口未暴露设置入口）
- **四条消费复用**：exec 通道（命令）、exec cat/printf+base64（文件，真 SFTP 有 bug）、shell PTY（终端）、ls 列工作区
- **Supervisor**：30s 心跳 + 5s→30s 指数退避重连；onReconnected 回调 workspaceRepo.initialize()+syncDocsToRemote()
- **MainActivity.onResume**：前台立即 tryReconnectIfDisconnected()
- **friendlySshError 映射**：UnknownHost → 无法解析主机；ConnectException → 连接被拒绝；SocketTimeout → 连接超时；AuthException → 认证失败

---

## 九、终端系统（本地 + 远程）

### 9.1 6 个内置 TerminalBundle（TerminalBundles.kt 88-150+ 行核对）

| BundleId | displayName | packages | version | size MB | AI推荐 | postInstallHook |
|----------|-------------|----------|---------|---------|--------|-----------------|
| PYTHON | Python 运行时 | python3 py3-pip | 1 | 45 | ✅ | — |
| NODE | Node.js 运行时 | nodejs npm | 1 | 28 | ❌ | — |
| RIPGREP | 高速搜索 rg | ripgrep | 1 | 4 | ✅ | — |
| GIT | Git | git | **2** | 22 | ✅ | `git config --global credential.helper store` |
| BASH | Bash | bash less ncurses | 1 | 5 | ✅ | 切 passwd root shell /etc/shells → bash；彩色 PS1 + bash_completion |
| NET | 网络工具 | curl wget ca-certificates openssh | 1 | 3 | ✅ | — |

**AI_RECOMMENDED_IDS = {PYTHON, RIPGREP, GIT, BASH, NET}（不含 NODE）**

### 9.2 本地 TerminalSessionManager

- Tab 稳定 id：`term-0`、`term-1`…
- 交互 Tab：`cd ~/workspace && exec bash`（exec 替换 PID 1，bash 结束即 Exit）
- 后台命令 Tab：`start(cmd, notify)` → 命令跑完后 `exec /bin/sh` 保活
- EXIT_MARKER_REGEX：后台命令打印 `[command exited: N]` → TabFinishedEvent → `AIAgentViewModel.pendingMergedNotifications`：AI 忙碌时排队，空闲时合并一条 `<task-notification>` 注入新一轮对话（附 TAIL_LINES=10 行输出）
- 有后台任务 → start TerminalKeepaliveService(START_SESSION) 前台服务计数

### 9.3 TerminalKeepaliveService（Manifest 声明确认）

```xml
foregroundServiceType = "dataSync"
```
- 前台低优先级通知（"后台任务运行中"，点击回 MainActivity）
- START_SESSION → ++count；STOP_SESSION → --count；count=0 && !permanentMode → stopForeground(true)
- 永久模式：设置页 keepalive enabled=true → enablePersistent 常驻
- Android 12+ 捕获 ForegroundServiceStartNotAllowedException → 降级（不崩）
- 冷启动恢复：MainActivity.onCreate 若 keepaliveSettings.isEnabled() → enablePersistent（前台保证不被挡）

---

## 十、备份与恢复（AES-GCM 加密 + PBKDF2 口令派生）

- **导出 tar.gz 内容**：meta.json(schemaVersion=31)、sessions.jsonl、messages.jsonl、todos.jsonl、providers.jsonl、git_credentials.jsonl、remote_connections.jsonl、remote_mounts.jsonl、datastore_preferences.xml、permissions.json、mcp.json、memory.md、skills/、docs/、可选 workspace/
- **加密**：password 非空 → "RDCB1" magic + salt(16) + iv(12) + AES-256-GCM ciphertext + tag(16)  
  密钥派生：PBKDF2(password, salt, 210,000 iterations, SHA-256) → 256-bit key
- **单会话导出**：.tar.gz（SAF CreateDocument 保存）

---

## 十一、AndroidManifest（逐行核对）

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<!-- 注意：没有 FOREGROUND_SERVICE_DATA_SYNC、没有 RECEIVE_BOOT_COMPLETED、
     没有 SYSTEM_ALERT_WINDOW、没有 ACCESS_FINE_LOCATION -->

<application android:name=".AIEditorApp"
    android:allowBackup="false" android:fullBackupContent="false"
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="true"          明文 HTTP 允许
    android:enableOnBackInvokedCallback="true"   预测返回
    android:localeConfig="@xml/locales_config">  系统语言表
  <activity .MainActivity android:windowSoftInputMode="adjustResize"
    android:exported="true"> <!-- 被 Activity 代码覆盖为 ADJUST_NOTHING -->
  <provider .WorkspaceDocumentsProvider authorities="${applicationId}.documents"
    permission="MANAGE_DOCUMENTS" exported="true" DOCUMENTS_PROVIDER intent-filter />
  <provider androidx.core.content.FileProvider
    authorities="${applicationId}.fileprovider" exported="false" />
  <service .TerminalKeepaliveService
    android:foregroundServiceType="dataSync" exported="false" />
</application>
```

---

## 十二、ProGuard（proguard-rules.pro 逐段核对）

```
1. 通用：keepattributes Signature/*Annotation*/SourceFile,LineNumberTable/InnerClasses/EnclosingMethod
2. Retrofit+OkHttp：@retrofit2.http.* <methods> 保留；Call/Response 保留；dontwarn retrofit2/okhttp3/okio/conscrypt/openjsse
3. BouncyCastle：BouncyCastleProvider、jce.provider.**、jcajce.provider.** 全保留；
              crypto.engines/digests/macs/modes/paddings 子包全保留（SPI 反射加载）；
              PQC/OpenPGP 等不用，靠 R8 shrinker 自动删；dontwarn org.bouncycastle.**
4. Gson：整个 feature.agent.data.remote.** { *; }（所有 DTO）+ @SerializedName 字段
5. kotlinx.serialization：**$$serializer { *; } 以及 serializer(...) 反射查找
6. Hilt：@HiltAndroidApp、@HiltViewModel 类；dagger.hilt.** / androidx.hilt.**
7. Room：* extends RoomDatabase；**/entity/** { *; }（Entity 列名不混淆）；@androidx.room.*
8. Termux：com.termux.terminal.** { *; } 和 com.termux.view.** { *; }（JNI 反射）
```

---

## 十三、测试清单（14 个 *Test.kt 实际 glob 一一核对）

运行：`./gradlew :app:testReleaseUnitTest`（AGENTS.md 规定 push 前必跑，release classpath 与 CI 门禁同款）

1. FileMigrationTest — SQL 分号切分
2. ShellCommandParserTest — 引号感知 / 段拆分 / 命令替换 / rm 解析
3. BuiltInSafeCommandsTest — 白名单边界
4. ToolRegistryTest — 注册唯一性 / schema 正确
5. MessagePersistenceUseCaseTest — 单调时间戳 / content 净化
6. MemoryParserTest — Markdown frontmatter 解析 / edit 替换
7. HasVisibleContentTest — 空内容判定（消息列表过滤依据）
8. CodeChangeTrackerTest — writeFile/editFile 前后 LCS
9. ErrorUtilsTest — DNS/超时/连接拒绝/认证失败友好中文
10. BackupCryptoTest — AES-GCM 往返 + 错误口令失败
11. FormatTokenCountTest — tokens → "1.2K"/"1.5M"
12. WebSearchResultComponentsTest — 卡片 snippet 长度
13. AboutSectionVersionTest — About 页版本号逻辑
14. CompressedFormatDetectTest — tar.gz/tar.xz/tgz/txz 格式识别

---

## 十四、AGENTS.md 必须遵守的纪律（资产同步纪律最重要，反复强调）

文件：`AGENTS.md`（原 `CLAUDE.md` 已迁移至此，项目规则唯一权威源；App 运行时 SystemPromptProvider 优先加载 AGENTS.md，代码回退查 CLAUDE.md 仅为兼容用户自定义场景）

1. **永远中文回复**
2. **资产同步纪律（改代码必须同步文档，最容易漏）**：
   - 工具改签名/新增/重命名 → **必须**同步 `assets/prompts/60-tools-and-paths.md` 系统提示词
   - 功能/工具行为变化 → **必须**同步 `assets/docs/` 使用文档
   - UI 变化 → **必须**同步对应 docs 文档
   - 新 UI 文案 → **必须**写 `values/strings.xml`（中文）+ `values-en/strings.xml`（英文），禁止 .kt 硬编码中文；命名 = 语义化 `common_` 前缀
3. **Conventional Commits**：type(scope): subject（type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test；scope ∈ agent/settings/terminal/workspace/git/ui/mcp/db/core/docs/build/deps）
4. **提交前**：`./gradlew :app:assembleDebug`（debug buildType 冒烟，快）；验证 release 链路用 `:app:assembleRelease`（项目已无 flavor 概念，禁止使用 assembleUniversal/assembleArmsolo 等旧命令）
5. **push 前**：`./gradlew :app:testReleaseUnitTest`（14 个单测，release classpath 与 CI 同款）
6. **RC 发版判定**：新功能/行为变化/构建链路/容器镜像 → 必发 RC；纯文档/typo → 可直接正式
7. **发版步骤**：main 打 tag v0.1.0-rc1 → CI 出 APK → 真机测 → 有问题从 RC tag 拉 hotfix（不从 main 防止夹带未发版功能）→ 升 rc 序号 → 合回 main → 打正式 tag
8. **云端构建监控与产物校验**（详见 AGENTS.md §云端构建与实时监控自动化）：
   - Tag 推送后通过 GitHub API 实时轮询 `workflow_runs` + `jobs` 直到 `conclusion=success/failure`
   - 构建完成后必须校验：① 下载 APK → ② `unzip -l` 确认只有 `lib/arm64-v8a/*.so` → ③ `keytool -printcert` 确认正式签名（非 `CN=Android Debug`）→ ④ `sha256sum` 记录指纹
   - **签名 Secrets 前置条件**：仓库必须配置 4 个 secrets（`AICODE_KEYSTORE_BASE64` / `AICODE_KEYSTORE_PASSWORD` / `AICODE_KEY_ALIAS` / `AICODE_KEY_PASSWORD`）；缺失任一 → **回退到项目级固定 debug keystore**（`CN=Android Debug, O=Android, C=US`，alias=`androiddebugkey`，密码 `android`， APK 证书 SHA256 固定 `7A:D5:EA:0E:3F:A9:6F:10:26:29:21:0C:9C:DB:AA:81:E3:CE:D4:9B:32:20:A5:21:7B:64:EC:1A:95:D2:FA:C8`），所有 RC Tag 构建输出同指纹 APK，可互相覆盖升级，但**仍不可上架**
   - **验证 secrets 存在性**：`GET /repos/{owner}/{repo}/actions/secrets` 返回 `total_count` 必须 ≥ 4

---

## 十五、代码快速索引（全部带相对路径，可在 Android Studio 跳转）

| 功能 | 入口相对路径 |
|------|---------|
| App 启动 | `app/src/main/java/com/deep/rcode/AIEditorApp.kt` |
| 主导航 / NavHost / Drawer | `app/src/main/java/com/deep/rcode/MainActivity.kt` |
| Agent MVI 工作流 Reducer | `app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt` |
| 工具注册（17 个顺序） | `app/src/main/java/com/deep/rcode/di/AgentModule.kt` 第 216-252 行 |
| 工具抽象 | `app/src/main/java/com/deep/rcode/feature/agent/domain/tool/AgentTool.kt` |
| Bash（流式+同步） | `app/src/main/java/com/deep/rcode/feature/agent/domain/tool/container/ExecuteCommandTool.kt` |
| Permission 评估总入口 | `app/src/main/java/com/deep/rcode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt` |
| Shell 静态解析 | `app/src/main/java/com/deep/rcode/feature/agent/domain/permission/ShellCommandParser.kt` |
| Hilt 注入 | `di/AgentModule.kt` / `di/RepositoryModule.kt` / `di/BackupModule.kt` |
| Room DB 定义 + MigrationLoader 加载 | `app/src/main/java/com/deep/rcode/feature/agent/data/local/database/AgentDatabase.kt` |
| 字段加密（AES-GCM + AndroidKeyStore） | `app/src/main/java/com/deep/rcode/core/security/CredentialEncryptor.kt` |
| PRoot 容器 | `app/src/main/java/com/deep/rcode/feature/agent/domain/container/LinuxContainerEngine.kt` |
| 凭据文件 IPC 桥（双捕获+看门狗暂停） | `app/src/main/java/com/deep/rcode/feature/credentials/data/CredentialRequestBridge.kt` |
| Git 凭据文件同步 | `app/src/main/java/com/deep/rcode/feature/credentials/data/GitCredentialsFileSync.kt` |
| Git 命令封装 | `app/src/main/java/com/deep/rcode/feature/git/domain/GitRepository.kt` |
| MCP 总管 | `app/src/main/java/com/deep/rcode/feature/agent/domain/mcp/McpManager.kt` |
| 文件同步引擎 | `app/src/main/java/com/deep/rcode/feature/workspace/domain/remote/SyncEngine.kt` |
| 6 个内置 Bundle | `app/src/main/java/com/deep/rcode/feature/terminal/data/bundle/TerminalBundles.kt` |
| 前台保活服务 | `app/src/main/java/com/deep/rcode/feature/terminal/domain/TerminalKeepaliveService.kt` |
| 备份实现 | `app/src/main/java/com/deep/rcode/feature/backup/data/BackupManagerImpl.kt` |
| 备份加密（RDCB1 magic + PBKDF2） | `app/src/main/java/com/deep/rcode/feature/backup/domain/BackupCrypto.kt` |
| ProGuard keep 规则 | `app/proguard-rules.pro` |
| Manifest | `app/src/main/AndroidManifest.xml` |
| AI 协同规范（资产同步纪律） | `AGENTS.md` |

---

## 十六、文档维护机制（后续每次代码改动实时同步的明确规则）

### 16.1 触发同步更新的 8 类代码改动范围

后续每一次代码改动，如果涉及下列任一范围，**必须同步更新本总结文档**：

| 改动类别 | 需要同步的文档章节 |
|---------|-----------------|
| 改工具签名 / 新增工具 / 删工具 / 改 permissionPolicy / capabilities | 第 4.2 节工具矩阵、第 4.3 节 Permission 评估链路（如有影响） |
| 改 Permission Policy Engine 评估顺序 / ShellCommandParser 解析规则 / BuiltInSafeCommands / checkCatastrophicRm | 第 4.3 节完整评估链路 |
| 加 DB Entity / 删列 / 加列 / 新增 SQL 迁移里程碑 | 第 6.1 节 Entity 表 + 第 6.2 节里程碑列表 |
| 改启动流程（AIEditorApp onCreate 顺序 / MainActivity 导航结构 / onResume 行为） | 第 3 节启动生命周期 |
| 改 Git 凭据链路 / CredentialRequestBridge / GitCredentialsFileSync / GitRepository 关键方法签名 | 第 7 节 Git 三端凭据统一 |
| 改 PRoot 初始化流程 / RemoteSshConnection 认证通道 / SyncEngine 三客户端 | 第 8 节容器引擎 & 远程 SSH |
| 改 Terminal Bundle 清单 / KeepaliveService 行为 / 前台服务类型 / 后台命令通知策略 | 第 9 节终端系统 |
| 加 Manifest 权限 / 加 ProGuard keep 规则 / 加或删单元测试文件 | 第 11、12、13 节 |
| 变更任何设计决策 / 引入新的技术权衡 / 调整 targetSdk / ABI 打包策略 / 构建配置 | 第 1.2 节决策矩阵 |
| 变更 CI workflow / 签名 secrets / 监控命令 / 产物校验流程 / Release 命名规则 | 第 14 节第 8 条 + AGENTS.md §云端构建与实时监控自动化 |

### 16.2 每次提交代码前的"同步文档"检查清单

每次在执行 `git add` 之前，手动回答下面的问题。如果任一回答为「是」，请先编辑本文档对应章节再提交：

1. [ ] 我有没有新增 / 删除 / 重命名任何 AgentTool？→ 第 4.2 节工具矩阵 + 第 4.3 节（若改策略）
2. [ ] 我有没有改 Permission 规则或 Shell 解析逻辑？→ 第 4.3 节
3. [ ] 我有没有改动 Room Entity 字段或加 SQL 迁移？→ 第 6 节
4. [ ] 我有没有改 AIEditorApp / MainActivity 的核心启动步骤？→ 第 3 节
5. [ ] 我有没有改 Git 凭据、命令封装？→ 第 7 节
6. [ ] 我有没有改容器 / 远程 SSH / 同步引擎？→ 第 8 节
7. [ ] 我有没有改 Terminal Bundle、前台保活、后台通知？→ 第 9 节
8. [ ] 我有没有改 Manifest / ProGuard / 测试清单？→ 第 11/12/13 节
9. [ ] 我有没有引入新的技术 trade-off / 策略？→ 第 1.2 节

### 16.3 文档变更的 Git 提交规范

- **本文件的变更必须与引起它变更的代码提交放在同一次 commit 内**（不要单独"补文档"提交，避免代码与文档版本错位）。
- 文档变更的 commit scope 按 AGENTS.md 填写为 `docs` 或对应模块 + docs 说明：
  - 例如：修改 Bash 工具参数 + 更新 §4.2 → commit message = `feat(agent): 为 Bash 增加 env 参数，并同步更新 DEEPCODE-FINAL-SUMMARY 工具矩阵`
  - 例如：新加 SQL 迁移 v32 + 更新 §6 → `feat(db): 增加会话书签（migration 32），同步更新 SUMMARY 的里程碑列表`
