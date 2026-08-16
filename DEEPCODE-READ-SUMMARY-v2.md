# R-DeepCode 全量代码深度阅读总结文档（v2）

> 生成时间：2026-08-16
> 代码库：`https://github.com/Lisir2002/deepcode-R`（本地路径 `/workspace/deepcode-R`）
> 说明：本文档基于对整个项目全部代码文件的逐文件精读而成（380 个 Kotlin 文件、22 个 Java 文件，约 7.9 万行 Kotlin + 0.76 万行 Java，另有 37 个 SQL 迁移、34 个 Markdown 文档、11 个提示词、6 个 CI 工作流）。覆盖应用入口、DI、安全加密、数据库迁移、Agent 工作流、工具系统、权限引擎、MCP、Skill、Memory、Checkpoint、Prompt、命令、ZTH 安全子系统、会话管理、数据持久化、展示层、终端系统（模拟器/视图/SSH/保活）、设置/凭据/备份、Git、Workspace、T2I 文生图、资源资产、CI/CD 与测试。

---

## 目录

1. [项目定位与设计哲学](#一项目定位与设计哲学)
2. [技术栈与构建体系](#二技术栈与构建体系)
3. [整体架构与代码结构](#三整体架构与代码结构)
4. [应用入口与启动生命周期](#四应用入口与启动生命周期)
5. [Hilt 依赖注入](#五hilt-依赖注入)
6. [安全与加密体系](#六安全与加密体系)
7. [数据库与迁移机制](#七数据库与迁移机制)
8. [Agent 工作流引擎](#八agent-工作流引擎)
9. [AI 提供商适配](#九ai-提供商适配)
10. [容器引擎与远程 SSH](#十容器引擎与远程-ssh)
11. [工具系统与权限评估](#十一工具系统与权限评估)
12. [MCP / Skill / Memory / Checkpoint / Prompt / 命令](#十二mcp--skill--memory--checkpoint--prompt--命令)
13. [ZTH 零幻觉容忍安全子系统](#十三zth-零幻觉容忍安全子系统)
14. [会话管理与会话级数据持久化](#十四会话管理与会话级数据持久化)
15. [Agent 展示层（UI）](#十五agent-展示层ui)
16. [终端系统](#十六终端系统)
17. [设置 / 凭据 / 备份模块](#十七设置--凭据--备份模块)
18. [Git 特性](#十八git-特性)
19. [Workspace 特性](#十九workspace-特性)
20. [T2I 文生图特性](#二十t2i-文生图特性)
21. [资源资产（prompts/docs/脚本）](#二十一资源资产promptsdocs脚本)
22. [CI/CD 与工程规范](#二十二cicd-与工程规范)
23. [测试体系](#二十三测试体系)
24. [架构模式与设计原则](#二十四架构模式与设计原则)
25. [关键设计决策与潜在风险点](#二十五关键设计决策与潜在风险点)
26. [代码快速索引](#二十六代码快速索引)

---

## 一、项目定位与设计哲学

**R-DeepCode** 是一款在 **Android 手机上运行的 AI 编程工具（AI Coding Agent App）**，把大语言模型与本地 Linux 开发环境深度集成：

- 内置 **Alpine Linux 容器**（PRoot 实现）与 **终端模拟器**（Termux 组件），让 AI 能直接读写文件、执行 Shell 命令、运行构建工具；
- 支持 **远程 SSH 服务器**作为执行后端，把手机变成远程项目的移动工作站；
- 通过 **17+ 内置工具**（文件读写/编辑、Shell 执行、终端管理、网页搜索、MCP 管理等）与开发环境深度交互；
- 内置 **MCP（Model Context Protocol）客户端**、**Git 可视化集成**、**SFTP/FTP 远程同步**、**T2I 文生图**、**AES-256-GCM 加密备份**与 **ZTH（零幻觉容忍）安全子系统**。

### 核心设计哲学（12 项关键决策）

1. **移动优先（真机优先）**：`targetSdk=28` 锁定（PRoot 需在可写目录执行二进制，Android 10+ 的 W^X/SELinux 策略禁止；同 Termux 取舍），只适配 arm64-v8a 真机，牺牲 Google Play 上架换取完整 Linux 环境能力。
2. **双执行模式**：本地（PRoot 容器）与远程（SSH）透明切换，通过 `DelegatingTerminalSessionProvider` / `DelegatingFileAccess` 策略分发，上层无感知。
3. **只存密文原则**：所有凭据（API Key / Git Token / SSH 密码）在 Room 中一律 AES-256-GCM 加密存储。
4. **启动安全优先**：任何初始化失败只记日志不抛异常，绝不阻断启动链（RC61b hotfix3 铁律）；DB 构建采用"保数据最优 → 保启动最差"的四阶段 Funnel 退化链。
5. **强安全兜底**：ZTH 任何"无法判定"的情况一律退化为 NEED_USER_CONFIRM 或拒绝，绝不静默放行（ZTH-0 铁律）。
6. **状态机显式化**：Agent 工作流（MVI Reducer）、确认卡状态机（12×17 显式 when）、失败分类决策矩阵（28 SubClass 显式 switch）全部显式枚举，把"遗漏"变成测试期错误。
7. **增量性能优化**：SystemPromptProvider 增量 Diff + 各 Source 缓存；上下文压缩保留原始数据仅标记；工具结果 mtime 缓存；备份/日志全部流式分页防 OOM。
8. **可回滚**：CheckpointManager 文件快照 + ZTH QGATE postflightDiff，写坏可一键回滚。
9. **文件驱动 SQL 迁移**：Room 迁移用 `assets/migrations/*.sql` 文件驱动 + 程序化迁移兜底，配合 `MigrationLoader` 连续性校验。
10. **版本号全自动**：`versionName` 由 Git Tag 动态推导、`versionCode` 由 commit 计数单调生成，杜绝人为忘记升级。
11. **AI 协同开发纪律**：AGENTS.md 强制资产同步（改代码必须同步 prompts/docs/strings.xml），Conventional Commits + commit-msg hook 校验。
12. **失败兜底文化**：加密失败中止保存防覆盖、DAO 安全壳兜底、备份逐段 `runCatching` 独立还原、`EnumSafe` 容错解析。

---

## 二、技术栈与构建体系

### 2.1 技术栈总览

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.2.21 + Java（Termux 终端组件） |
| 构建 | Android Gradle Plugin 8.9.3 + KSP 2.2.21-2.0.5 + Hilt 2.56.1 |
| UI | Jetpack Compose (BOM 2025.12.01) + Material 3 |
| 依赖注入 | Hilt 2.56.1 (Dagger) |
| 数据库 | Room 2.7.1（文件驱动 SQL 迁移，Schema v45，22 实体） |
| 网络 | Retrofit 2.11.0 + OkHttp 4.12.0 + Gson + SSE 流式 |
| 异步 | Kotlin Coroutines / Flow |
| 终端 | Termux terminal-emulator + terminal-view（JNI libtermux.so） |
| 容器 | PRoot + Alpine Linux rootfs（arm64） |
| 远程 SSH | SSHJ 0.38.0（exec + shell channel）+ BouncyCastle bcprov-jdk18on 1.75 |
| FTP | Apache Commons Net 3.10.0 + ftpserver-core 1.2.0（内置 FTP 服务） |
| 压缩 | Apache Commons Compress 1.26.2（tar.gz / XZ） |
| 序列化 | Gson + kotlinx.serialization |
| Markdown | compose-markdown m3/code 0.41.0 + highlights-jvm |
| 测试 | JUnit 4.13.2 + androidx test + Espresso |

### 2.2 构建配置要点（[app/build.gradle.kts](file:///workspace/deepcode-R/app/build.gradle.kts)）

- **签名回退**：`keystore.properties` 存在用正式签名；否则回退 debug keystore，保证 `assembleRelease` 在 CI/本地零配置也能产出可安装 APK；均强制 `enableV1+V2Signing`。
- **版本号动态推导**：`BASE_VERSION="0.1.0"` 锁定；`gitVersionName()` 仅接受 0.1.0 系列 tag，杜绝从旧 1.x 回跳；`gitCommitCount()` 单调生成 versionCode。
- **ABI 过滤**：`abiFilters = ["arm64-v8a"]`，只打包 arm64 单构建产物。
- **SDK**：compileSdk 36 / minSdk 26 / **targetSdk 28（锁定，功能决策）**。
- **BuildConfig**：`buildConfig = true`（`assertContinuity` 需要 DEBUG 区分）。
- **gradle.properties**：JVM `-Xmx2048m`、KSP in-process、debug 体积用 `useLegacyPackaging=false` 强制 DEFLATE 收敛 APK 体积。

### 2.3 模块划分（[settings.gradle.kts](file:///workspace/deepcode-R/settings.gradle.kts)）

```
rootProject "app"
├── :app                 # 主应用（com.deep.rcode）
├── :terminal-emulator   # Termux 终端仿真核心（Java）
└── :terminal-view       # Termux 终端视图渲染（Java）
```

---

## 三、整体架构与代码结构

采用 **Feature-based + DDD 分层架构**：`feature/<模块>/domain | data | presentation` 三层 + `core/`（安全/db/主题/util/worker）+ `di/`（Hilt 模块）。

```
app/src/main/java/com/deep/rcode/
├── AIEditorApp.kt / MainActivity.kt        # 应用入口
├── di/                                      # AgentModule / RepositoryModule / BackupModule
├── core/
│   ├── db/         # MigrationLoader / LightweightSchemaRescue / RobustMigration44
│   ├── security/   # CredentialEncryptor / DEKManager / HostKeyManager / ZTH 加密
│   ├── theme/      # AIEditorTheme / AppComponents / CyberComponents
│   ├── ui/         # ImeInset
│   ├── util/       # AILogger / FileLogger / EnumSafe / LineDiff ...
│   └── worker/     # V1toV2MigrationWorker / CredentialRotationWorker / AuditPurgeWorker
└── feature/
    ├── agent/      # 核心：workflow / session / provider / model / container / tool / permission
    │               #       / mcp / skill / memory / checkpoint / prompt / command / zth / data / presentation
    ├── terminal/   # 终端（presentation/domain/data）
    ├── settings/   # AI Provider / ModelMetadata / 各类设置
    ├── credentials/# Git 凭据（三端桥）
    ├── backup/     # 备份与恢复
    ├── git/        # Git 可视化（domain/presentation）
    ├── workspace/  # 文件访问抽象 / 远程同步 / SAF
    └── t2i/        # 文生图（provider/model/task + 权限策略）
```

**代码规模统计**：380 个 Kotlin 文件（约 79,462 行）+ 22 个 Java 文件（约 7,610 行）+ 37 个 SQL 迁移 + 34 个 Markdown + 11 个提示词。

---

## 四、应用入口与启动生命周期

### 4.1 [AIEditorApp.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/AIEditorApp.kt)（@HiltAndroidApp）

**attachBaseContext（最早入口）**：
1. `FileLogger.init` + `AILogger.init` + `installCrashHandler()` —— 日志与全局崩溃处理器提到最前，保证 Hilt 注入链早期崩溃也能落盘；
2. 从 SharedPreferences 同步读语言 tag 套 locale。

**onCreate 初始化顺序（16 个 @Inject 服务）**：
1. `registerBouncyCastle()`：用 `addProvider`（非 insert 到第 1 位，避免抢占 OkHttp/Conscrypt 的 SSLContext KeyStore 查找导致 `BKS not found`）；
2. `createNotificationChannels()`：terminal_service 渠道；
3. `credentialRequestBridge.start()`：主线程启动 git 缺凭据文件 IPC 监听；
4. `appScope.launch` 后台任务：`extractDocs` / `extractPrompts`（释放内置提示词到 `~/.rdeepcode/prompts/`，用户自定义在 `prompts.custom/` 不被覆盖）/ `gitCredentialsFileSync.syncAll()` / `modelMetadataService.refreshFromNetworkIfStale()` / 日志等级生效；
5. **RC61b 关键**：首帧优先，延后重活 —— `credentialEncryptor.ensureInitialized()` 后台预热（失败只记日志）→ `delay(500)` → 执行模式读取 → REMOTE_SSH 时 `withTimeout(15s)` 连接并 `syncDocsToRemote()` + 注册重连 supervisor；每段 `runCatching` 隔离；
6. `mcpManager.start()`：连接已配置 MCP server 并注册工具。

**installCrashHandler（三步走）**：① logcat 即时输出；② `FileLogger.flushSync` **同步阻塞落盘**（RC61b hotfix3 关键修复：此前异步排队任务在系统杀进程后丢失）；③ 交给系统原处理器（previous 也炸则 `killProcess` + `System.exit(10)` 兜底强杀）。

### 4.2 [MainActivity.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/MainActivity.kt)

- `enableEdgeToEdge()` 必须在 `super.onCreate()` 之后；
- 语言切换 → `recreate()`；
- API 30+ 全局 `window.setSoftInputMode(ADJUST_NOTHING)`（由 `rememberImeBottomInset()` 接管键盘内边距）；
- `setContent` 根结构：`AIEditorTheme` → `LocalAppDarkMode` → `AppNavigation()` + `GlobalCredentialDialogHost`（全局凭据弹窗宿主）；
- **AppNavigation 根导航容器**：ModalNavigationDrawer 在 NavHost 外面；终端路由用纯 slide 过渡（TerminalView 原生 View 不参与 alpha 动画，fade 会白屏）；ViewModel 提升到导航层共享。

**路由表**：`chat`（AIChatPanel）/ `settings` / `capability_center` / `terminal` / `terminal_settings` / `terminal_bundle_manager` / `git`。

---

## 五、Hilt 依赖注入

### 5.1 [AgentModule.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/di/AgentModule.kt)（最大模块）

**数据库构建 —— DB-SHIELD 四阶段 Funnel**：
- **Funnel 0**：主 DB 不存在 + 有崩溃备份 → 还原最近备份（含 .wal/.shm）；
- **Funnel 1** `FUNNEL1_CONSERVATIVE_MIGRATION`：正常 Room + SQL 迁移，不配 fallback；
- **Funnel 2** `FUNNEL2_AFTER_RESCUE_RETRY`：`LightweightSchemaRescue` 补表补列 → `fallbackToDestructiveMigration(dropAllTables=false)` 重试（只删 schema 对不上的表）；
- **Funnel 3**：同 Funnel 2 更高 warn 级别；
- **Funnel 4** `FUNNEL4_FULL_DESTRUCTIVE_WITH_BACKUP`：先 `snapshotDbFileForDisasterRecovery` 备份（含 WAL checkpoint TRUNCATE）→ 全表重建；
- 最外层第 5 次 destructive 兜底，绝不抛（否则 Hilt 构建失败杀进程）。

**通用配置**：`setJournalMode(WRITE_AHEAD_LOGGING)`、`enableMultiInstanceInvalidation()`、`RoomDatabase.Callback`（onOpen 做 `wal_checkpoint(PASSIVE)`）、**`db.openHelper.writableDatabase` 强制打开**（RC91 关键：Room build 惰性，不强制打开则 Funnel 链 runCatching 接不到迁移失败，Funnel 失效）。

**网络**：OkHttpClient（120s 超时）+ 三个 Named Retrofit（OpenAI/Anthropic/Gemini）+ 对应 API 接口。

**工具体系**：`ToolResultTypeRegistry`、`ToolRegistry`（L3 联动注册，注册 19 个工具）、`CodeChangeTracker`、`ToolDependencyScheduler`、`ToolResultCache`、`ToolEventBus`、`IncrementalIndexStore`。

**AgentWorkflow**：`provideAgentWorkflow` 注入 22 个依赖（toolRegistry、三 Api、promptProvider、permissionManager、policyEngine、contextCompactor、planApprovalManager、toolOutputStore、modelMetadataService、各仓库、sessionUseCase、messagePersistenceUseCase、checkpointManager 等）。

**委派绑定**：`CommandEngine→DelegatingCommandEngine`、`FileAccessProvider→DelegatingFileAccess`、`TerminalSessionProvider→DelegatingTerminalSessionProvider`。

### 5.2 RepositoryModule / BackupModule

- `@Binds`：AIProviderRepository / CredentialRepository / BackupManager 分别绑定实现类。

---

## 六、安全与加密体系

### 6.1 [CredentialEncryptor.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/CredentialEncryptor.kt)（凭据加密门面 2.0）

- **算法**：AES-256-GCM，GCM tag 128bit，IV 12B；
- **双层密钥链**：`MasterKey(Android Keystore) → wrap DEK(256-bit AES) → AES-256-GCM(DEK, data)`；
- **输出格式**：`"V2:" + Base64(IV(12B) + ciphertext + 16B GCM tag)`；
- **解密三路分发**：`V2:` → DEK 解密；空串 → ""；无前缀 → V1 旧版解密（失败回退明文）；
- **ensureInitialized（幂等 + IO）**：查 `credential_encryption_state` 单行；空则生成 MasterKey+DEK 写单行；非空则 unwrap 校验（RC70 修复 Keystore 不可导出导致的 NPE；RC71 增强：unwrap 失败重建 DEK 不永久失败）；**任何失败只记日志不抛异常**；
- **scheduleRotateDek**：生成新 DEK → 重 wrap → 更新 state → 审计；
- **setBiometricRequired**：销毁 MasterKey 重建（注意：实际生成时固定传 `biometricRequired=false`，仅 state 记录标志，与文档描述存在偏差）；
- **emergencyResetMasterKey**：调用方须已通过 SSH 密码验证，销毁重建 MasterKey'' + DEK''。

### 6.2 其余安全组件

| 文件 | 职责 |
|------|------|
| [DEKManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/DEKManager.kt) | MasterKey/DEK 底层实现；**RC74 核心修复**：DEK 保护从 WRAP/UNWRAP 改为标准 AES/GCM ENCRYPT/DECRYPT（Android Keystore 不实现密钥包装）；`getOrCreateMasterKey` 用 `supportsEncryptDecrypt()` 校验旧 key 是否支持 |
| [HostKeyManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/HostKeyManager.kt) | SSH 主机密钥 TOFU 验证；`ssh_known_hosts` 文件；不匹配抛 `HostKeyChangedException` |
| [UserPasswordBackupCrypto.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/UserPasswordBackupCrypto.kt) | 备份文件加密：PBKDF2-HMAC-SHA256(120k) → 256-bit → dataKey+hmacKey 分离；AES-128-GCM + HMAC-SHA256 双校验；64B 文件头；跨设备可用 |
| [ZthSensitiveColumnCrypto.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/ZthSensitiveColumnCrypto.kt) | ZTH `s_` 前缀敏感列加密门面，复用 CredentialEncryptor V2 |
| [ZthSharedSyncKeyStore.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/ZthSharedSyncKeyStore.kt) | 跨设备共享密钥接口 + Phase 1 占位实现（Phase 4 才实现 Argon2id + BIP-39 8 词助记词） |
| [CredentialEncryptionContract.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/CredentialEncryptionContract.kt) | `V2:` 前缀 / V2 长度 / V1 密文启发式判定常量 |

### 6.3 后台 Worker（均不依赖 WorkManager，启动周期调用）

- **V1toV2MigrationWorker**：扫描 `remote_connections / ai_providers / git_credentials` 三表，将 V1 密文/明文迁移为 V2；无法解开的 `isLikelyV1Ciphertext` 清空字段标 UNMIGRATEABLE；完成后置 `migratedFromV1=true`；
- **CredentialRotationWorker**：DEK 轮换 + 三表重加密；
- **AuditPurgeWorker**：审计日志 90 天清理。

---

## 七、数据库与迁移机制

### 7.1 [AgentDatabase.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/data/local/database/AgentDatabase.kt)

Room 数据库，**version = 45**，exportSchema = true，**22 个 Entity + 22 个 DAO**（跨 feature 共享：agent / settings / credentials / workspace / t2i / core 的实体都在此）。

### 7.2 迁移机制

- **[MigrationLoader.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/db/MigrationLoader.kt)**：从 `assets/migrations/*.sql` 加载，按 `";"` 切分语句，包为 `FileMigration`；`MIN_REQUIRED_START_VERSION=8`；`assertContinuity` 连续性闸门（Debug 抛异常 / Release 仅记 FATAL）；每跑一个迁移写 `migration_history` 表审计。
- **[LightweightSchemaRescue.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/db/LightweightSchemaRescue.kt)**（DB-SHIELD Funnel 2）：**RC94 重写**——解析 Room 编译期导出的 schema JSON（`assets/<db>/<version>.json`，KSP 生成打进 assets）而非反射 @Entity 注解（retention 是 BINARY，Release/R8 下恒 null）；按权威 schema 补表/补列/建索引；`snapshotDbFileForDisasterRecovery` 崩溃备份（WAL checkpoint 后保留最近 5 份）。
- **[RobustMigration44.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/db/RobustMigration44.kt)**：程序化 v43→v44，修复线上 Room 崩溃（迁移 42 引用 createdAt 列）；`PRAGMA table_info` 探测 + 幂等四步法（CREATE → INSERT OR IGNORE → DROP IF EXISTS → RENAME），中途 SIGKILL 重跑也不崩。

### 7.3 Schema 演进里程碑（v8→v45，37 个迁移）

| 阶段 | 版本 | 内容 |
|------|------|------|
| 早期 | v08→v21 | 远程服务器/连接/挂载、isEnabled、mode、todo_items、isCompacted、上下文压缩标记、attachments、provider/model、git_credentials |
| 中期 | v22→v32 | token 计数、useFullUrl 替换 apiPath、检查点两张表、reasoningEffort、signature（Anthropic 扩展思考）、encryptedApiKey/encryptedToken、credential_encryption_state + remote_audit_logs |
| 后期 | v33→v45 | model_capability_overrides、ZTH 5 表（sentinels/fuses/audits/l0_logs/telemetry）、**RC68 schema 重构**（去明文列、createdAt→createdAtMs 统一）、T2I 3 表、skill_state、复合主键修复、AUTOINCREMENT 修复、**taskId**（任务手风琴） |

**表结构特点**：敏感列 `encryptedXxx` + `xxxIv` 双列；ZTH `s_` 前缀加密列；时间列统一 `xxxAtMs`；复合主键（model_capability_overrides / zth_hallucination_fuses）；CHECK 布尔约束；AUTOINCREMENT 主键（checkpoint_file_snapshots / remote_audit_logs / zth_telemetry_events）。

---

## 八、Agent 工作流引擎

### 8.1 [AgentWorkflow.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/AgentWorkflow.kt)

- **`AgentEvent`（sealed class）**：向 UI 推送的分步事件——`AssistantText`（含 toolCalls/reasoning/signature/token 计数）、`AssistantDelta`（流式增量，不落库）、`ReasoningDelta`、`ToolCallStarted/Progress/Finished`、`Retrying`、`CompactionStarted/Finished`、`Failed`、`Completed`、`ModeChanged`；
- **`AgentWorkflow` 接口**：`executeEvents()` 运行 Agent 循环（模型回复 → 工具调用 → 工具结果 → 再回复……直至不再调用工具）、`compactSession()` 手动压缩。

### 8.2 [StatefulAgentWorkflow.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt)（MVI 状态机）

- **Model**：`AgentSessionState`（不可变：messages/iterations/isFinished/error/batchToolCalls/pendingPermissionCalls/approvedToolCalls/rejectedToolResults/pendingVisionRound…）；
- **Intent**：`AgentAction`（InitRequest / LlmResponse / LlmError / PermissionEvaluated / ToolBatchFinished）；
- **Side Effect**：`AgentSideEffect`（StreamLlm / StreamLlmVisionFallback / EvaluatePermissions / ExecuteToolBatch / PersistMessages / CompactContext / NotifyError）；
- **reduce 状态转换**：
  - `InitRequest` → 启动 LLM 流式；
  - `LlmResponse` → `stopReason=="max_tokens"` 自动续写；有 toolCalls 进权限评估；否则完成 + 持久化；
  - `LlmError` → 标记错误完成；
  - `PermissionEvaluated` → 按审批结果执行工具或通知用户；
  - `ToolBatchFinished` → 追加工具结果，重启 LLM（下一轮迭代）；
  - **迭代上限 50**，防止无限循环；
- **视觉回退机制**：`pendingVisionRound / visionFallbackRetried` 处理模型不支持图片时的降级。

### 8.3 [ContextCompactor.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/ContextCompactor.kt)

- 触发：`estimateTokens > contextLimit * 0.9` 或 `force=true`；
- 拆分：从后向前累计 token 预算保留最近消息；`adjustSplitIndex()` 避免 tail 第一条是孤立 ToolResultMessage；
- 摘要：LLM 生成结构化 Markdown（Goal/Constraints/Progress/Key Decisions/Next Steps/Critical Context/Relevant Files）；
- **保留原始数据**：head 标记 `isCompacted=true`（不删除）+ 插入 marker + summary 消息；
- 失败时原样返回，由上层承担溢出风险。

---

## 九、AI 提供商适配

### 9.1 统一抽象（[AIProvider.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/provider/AIProvider.kt)）

`AIResponse`（内容/工具调用/停止原因/思考/签名/token）+ `AIStreamChunk`（TextDelta/ReasoningDelta/Final/Retrying）+ `complete()` / `completeStream()` + `joinUrl()`。

### 9.2 三家协议差异

| 特性 | Anthropic | OpenAI | Gemini |
|------|-----------|--------|--------|
| 系统提示 | `system` 字段 | `system`/`developer` 角色（o1/o3 用 developer） | `systemInstruction` 字段 |
| 工具调用 | `tool_use` content block | `tool_calls` 字段 | `functionCall` part |
| 工具结果 | `tool_result`（role=user） | `tool` 角色（tool_call_id 配对吸附） | `functionResponse`（role=user） |
| 思考过程 | `thinking` block + `signature` 回传（否则 400） | `reasoning_content` 字段（DeepSeek 必须） | `thought` 标志（gemini-3 用 thinkingLevel / 2.5 用 thinkingBudget） |
| 流式结束 | `message_stop` 事件 | `[DONE]` 标记 | `finishReason` 非 null |
| 重试 | `retryStaircase` + 60s 首字节 Watchdog | 同左 | 同左 |

**关键实现细节**：
- **AnthropicAdapter**：`buildThinkingConfig()` 将 reasoningEffort 映射 budget_tokens（low=1024/medium=4096/high=8192）；thinking signature 随工具循环原样回传；
- **OpenAIAdapter**：防御性清理——孤立 tool 消息跳过、未配对 tool_calls 裁剪；`[DONE]` 结束；Responses API 支持 `output_text.delta` / `response.completed`；`asTextContent()` 只提取文本不写 base64 图片；
- **GeminiAdapter**：防御性跳过孤立 functionResponse；`inline_data` 图片格式。

---

## 十、容器引擎与远程 SSH

### 10.1 [LinuxContainerEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/container/LinuxContainerEngine.kt)

- **PRoot 调用**：`-r rootfs` + `-b /dev,/proc,/sys,/system` + `-0`（假装 root）+ 项目路径绑定 + 代理环境变量注入；
- **包管理**：`installBundle()` = ParallelPrefetchManager 并行预取 → `apk add` → 进度聚合 → 镜像轮转；
- **容器状态机**：UNINITIALIZED → DOWNLOADING → INSTALLING → READY → ERROR。

### 10.2 容器安装进度（[progress/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/container/progress)）

- **ParallelPrefetchManager**：`apk info --depends` 查依赖图 → 拼 URL 并发下载到 `/var/cache/apk/` → 流式 PrefetchEvent（DependsResolved→SlotUpdate→Finished）→ 失败 fail-open 让 apk 自取兜底；
- **PrefetchConcurrencyPolicy**：按网络带宽估算最优并发槽数；
- **RealProgressAggregator**：五路信号融合（prefetch slot / apk stdout / NetworkCap / TrafficStats 差分 / 依赖总数）+ EMA 平滑 + 阶段权重（DOWNLOAD 0.45 / INSTALL 0.50 / POST 0.05）+ ETA 估算；
- **InstallProgressParsers**：统一 apt/apk/pip/sdkmanager 解析路由。

### 10.3 [RemoteSshConnection.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/container/RemoteSshConnection.kt) / RemoteSshEngine

SSHJ 连接池复用；密码/密钥认证；`execCaptured()` / `streamExec()`；`scpUpload/Download`；`forwardPort()`。

---

## 十一、工具系统与权限评估

### 11.1 工具抽象（[tool/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/tool)）

- **[AgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/tool/AgentTool.kt)**：统一契约（结果类型/错误分类/重试策略/参数 JSON Schema/能力标记 ToolCapability，如 READ_WORKSPACE / WRITE_WORKSPACE / NETWORK_READ / USER_INTERACTION / LOAD_MCP_SERVER / LOAD_SKILL_BUNDLE 等）；
- **AbstractContextualTool**：强制子类走带上下文的 execute；
- **ToolRegistry**：`ConcurrentHashMap` 注册中心；
- **ToolResultCache**：TTL + 文件 mtime 双失效；
- **ToolOutputStore**：大体积输出"截断首尾 + 存完整大文件"；
- **ToolSessionState**：共享会话状态容器；
- **ToolEventBus**：发布-订阅 + 循环保护；
- **ToolDependencyScheduler**：DAG 拓扑序调度 + 重试策略；
- **ToolPermissionManager**：挂起中的权限请求与用户审批桥接。

### 11.2 具体业务工具（19 个注册工具）

| 工具 | 职责 |
|------|------|
| ReadFileTool / WriteFileTool | 文件读写；Write 前回调 CheckpointManager 快照 |
| ViewImageTool / SendFileTool | 查看本地图片 / 发送文件卡片到聊天区 |
| EditFileTool | 精确字符串匹配编辑；多操作原子批量、任一失败整体回滚；编辑前快照 |
| WebSearchTool / WebFetchTool | 网络搜索 / 网页抓取（jsoup 提取文本） |
| ManageMcpTool | MCP 服务器管理（增删改查） |
| ExecuteCommandTool | 容器内执行 shell；流式输出；执行前权限引擎判定 |
| CheckEnvironmentTool | 检查开发/构建组件安装 |
| BackgroundTerminalTools | 后台终端会话管理（长任务） |
| LoadSkillTool | 加载/执行 Skill；SCRIPT 类先权限检查+审计 |
| PlanApprovalManager / SwitchModeTool | PLAN→BUILD 审批桥 / 模式切换（需用户确认） |
| SearchCodeTool / ListFilesTool | ripgrep 检索（静态安全分析防注入）/ ls 列目录 |
| GenerateImageTool | 文生图 Provider 调用（权限检查） |
| TodoTool | AI 任务清单（快照增量更新） |
| AskUserQuestionTool / Manager | 结构化选择题 + park-and-resume |
| MemoryTool | 长期记忆 read/save/edit/delete/list |

### 11.3 权限评估引擎（[permission/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/permission)）

**[ToolPermissionPolicyEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt) —— 七层评估流水线**：
1. **灾难命令拦截**（rm -rf /、磁盘擦除、提权）→ 直接 DENY；
2. **PLAN 只读约束**：仅只读工具，写操作拒绝；
3. **静态分析**：`ShellCommandParser` 判定可静态判定命令 → `BuiltInSafeCommands` 白名单放行 / `BuildCommandClassifier` 分类；
4. **白名单规则**：用户自定义 GLOBAL/PROJECT 规则；
5. **用户审批**：不确定命令挂起等待用户；
6. **规则记忆**：审批结果沉淀为 allow/deny 规则；
7. **兜底拒绝**：无法判定的高风险操作默认拒绝。

**配套模型**：PermissionScope / PermissionDecision / PermissionRule / PermissionFile；ZthFailureModels（6 顶层类 / 28 SubClass / FuseState / 10 列决策结构）。

---

## 十二、MCP / Skill / Memory / Checkpoint / Prompt / 命令

### 12.1 MCP 子系统（[mcp/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/mcp)）

- **McpManager**：多服务器生命周期管理；连接成功后动态注册工具到 ToolRegistry；
- **McpClient**：握手（initialize）、tools/list、tools/call；JSON-RPC ID 匹配；
- **传输层**：`StdioTransport`（本地子进程 stdin/stdout JSON 行）+ `StreamableHttpTransport`（MCP Streamable HTTP，单 JSON + SSE 双模式）；
- **McpTool**：把 MCP 工具适配为 AgentTool，以 `mcp.<server>.<tool>` 命名便于 ZTH 识别第三方来源。

### 12.2 Skill 系统（[skill/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/skill)）

- Skill 模型（PROMPT/SCRIPT/MCP 三类 + 依赖）；SkillParser 提取 frontmatter；
- LocalDirectorySkillSource 管理本地目录；SkillStateRepository 叠加 Room 运行时状态（启用/依赖解析）；
- SkillExecutor 按类型执行：PROMPT 注入上下文；SCRIPT 先权限检查+审计；MCP 走服务调用。

### 12.3 Memory 系统（[memory/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/memory)）

两级记忆：`GlobalMemorySource`（全局偏好，全局目录 memory.yaml）+ `ProjectMemorySource`（项目专属）；MemoryRepository 合并访问，是 MemoryTool 与 SystemPromptProvider 的入口。

### 12.4 Checkpoint 系统（[checkpoint/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/checkpoint/CheckpointManager.kt)）

- 快照根目录 `<filesDir>/checkpoints/<sessionId>/<checkpointId>/`；
- 用户发新消息时创建 Checkpoint（记录 prompt 前 60 字摘要）；
- `beforeFileModified`：同一 checkpoint 内同一文件只保留最原始一次快照；MODIFY 存原内容 / CREATE 写空串占位；
- `restoreCodeToCheckpoint`：倒序还原（先回滚最新）；MODIFY 覆盖写回 / CREATE 安全删除；
- `clearSessionCheckpoints`：清空记录 + 磁盘目录。

### 12.5 System Prompt 系统（[prompt/SystemPromptProvider.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/prompt/SystemPromptProvider.kt)）

**增量式构建（SystemContext 化，提升 KV Cache 命中率）**：
- PromptSource 接口 + 各来源缓存：StaticRuleSource（9 静态片段）/ PlanModeSource / AutoModeSource / ActiveSkillsSource（变化才更新）/ ProjectRuleSource（AGENTS.md，超 32k 截断，mtime 缓存）/ WorkspaceSource / MemoryListSource / CurrentTimeSource；
- **增量 Diff**：Workspace 未变化时仅注入"未发生变化"占位；变化时更新会话级快照；
- 拼接顺序：静态 → 模式 → 技能 → 记忆 → 项目规则 → 工作区 → 时间（稳定重头在前）；
- **resolvePrompt 三优先级**：`prompts.custom/`（用户覆盖）> `prompts/`（本地副本）> `assets/prompts/`（内置兜底）。

### 12.6 斜杠命令（[command/](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/command)）

- SlashCommandRegistry：Hilt multibinding + 按 trigger 排序 + filterByPrefix 实时匹配；
- **/status**（token/模型/模式 Markdown 表格）+ **/compress**（手动触发上下文压缩）。

---

## 十三、ZTH 零幻觉容忍安全子系统

ZTH（Zero Trust Hallucination）是项目最复杂的安全体系，实现 8 大能力：能力审查 + 熔断 + 内容审查 + 输出审查 + 失败分类 + 确认卡状态机 + 计划审批桥接 + 镜像轮换。

### 13.1 域模型（[ZthDomainModels.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthDomainModels.kt)）

7 个子系统间通信的纯数据模型层（禁止互相持有实现，保障 JUnit 隔离）；关键类型：`ZthToolCallPlanItem`、`ZthCapabilityVerdict`（6 判定）、`ZthPresetTier`（0/1/2/3 四档）、`ZthToolOutputVerdict`、`ZthClassifyContext`、`ZthPerformanceClass`（低端机跳过 LLM 终检）、两个 `fun interface` LLM 审查回调。

### 13.2 总入口门面（[ZthGuardAggregateFacade.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthGuardAggregateFacade.kt)）

四方联动总编排：`prepareEnv`（档位+熔断，REMOTE_SSH 短路）、`structuredPlan`（用户可见规划 JSON）、`prePlanAudit`（PLAN→BUILD 前：熔断→PlanApproval→确认卡→预建 Checkpoint）、`preToolAudit`（批量能力审查→确认卡→遥测）、`postToolCompletedAudit`（输出启发扫→失败分类→追加 Checkpoint）、`onThrowableAudit`（统一失败分类→熔断计数→挂卡）、QGATE 3 步（preflightVerify / postflightDiff 回滚）。

### 13.3 能力审查（[ZthCapabilityGuard.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthCapabilityGuard.kt)）

- **9 条零风险规则** → PASS_ZERO_RISK_HEURISTIC；
- **12 条 MCP/Skill 危险规则**（base64 解码执行、curl|sh、chmod 777、rm 递归、写 /etc /proc /sys、sudo 前缀、未过确认卡的 MODIFY_USER_CONFIRMED_STATE 等）→ NEED_USER_CONFIRM；
- 批量 BATCH=20 分片、8s 超时兜底、未判一律退化 NEED_USER_CONFIRM（强安全）。

### 13.4 熔断管理（[ZthCircuitBreakerManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthCircuitBreakerManager.kt)）

双 Scope（GLOBAL+SESSION）；阈值按档位 tier1=5/tier2=3/tier3=2；kill-switch-1 单向置位不可清除；OPEN 冷却自动 HALF_OPEN；LINK-INV CAS 乐观锁事务（3 次重试）；HALF_OPEN 探针成功 1 次回 CLOSED / 失败 1 次回 OPEN。

### 13.5 内容审查 / 输出审查 / 失败分类

- **[ZthContentReviewer.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthContentReviewer.kt)**：20 条危险正则（权重 0.2~2.0）；Step1 light（前 500+后 500 字只查 8 条 HIGH）→ Step2 full 加权置信分 + 档位阈值（STRICT 0.20 / BALANCED 0.50）；
- **[ZthToolOutputGuard.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthToolOutputGuard.kt)**：阶段1 每 64KB 切片 20 条规则（PII + 幻觉特征，命中 HIGH 立即 FAIL，加权分 ÷6.68 与档位阈值比较）；阶段2 聚合 ≤4000 字送 LLM 终检（10s 超时退化 PASS_LOCAL；低端机跳过）；
- **[ZthFailureClassifier.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthFailureClassifier.kt)**：4 步——离线大兜底 → Throwable→28 SubClass（forcedOverride 最高优先、pkg_mgr_mismatch 前置、HTTP 状态码、类型映射）→ SubClass→6 FailureClass → 按 (subClass, tier) 二维显式 switch 填 10 列（未覆盖走异常兜底）。

### 13.6 确认卡状态机与业务管理

- **[ZthConfirmationCardStateMachine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthConfirmationCardStateMachine.kt)**：12 状态 × 17 事件显式 when（204 分支无 else）；STATE-INV-1 必须 SwipeToConfirm（≥92%）；STATE-INV-2 tier≥2 禁止直接取消；
- **[ZthConfirmationCardManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthConfirmationCardManager.kt)**：`commitUserDecision` 的 **LINK-INV 4 写事务**（顺序锁死：fuse CAS linkageVersion+1 → 加密 3 个 s_* 列写 sentinel 主表 → REJECT 时写 rejection audit → resolve 唤醒 workflow）。

### 13.7 其余

- **ZthPlanApprovalManagerWrapper**：APPROVE_DIRECT→APPROVE / REFUSE→REFINE / MODIFY_AND_REQUIRE_REFINE→强制 REFINE 映射；
- **ZthWorkflowHooks**：4 个接入点（hookPrePlanAudit / hookPreToolAudit / hookPostToolAudit / hookOnThrowable），薄包装零侵入核心循环；
- **TerminalBundleMirrorRotator**：镜像 OFFICIAL→ALIYUN→TSINGHUA→USTC 严格顺序轮转（最多 4 次），失败细分 8 SubClass。

---

## 十四、会话管理与会话级数据持久化

### 14.1 会话管理（[SessionUseCase.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/session/SessionUseCase.kt)）

- `initColdStartCleanup()`：冷启动清理残留"执行中"工具占位行（标记已中断）；
- `deriveTitle()`：从请求提取标题（≤20 字符）；`deleteSession()` 级联删除消息。

### 14.2 消息持久化（[MessagePersistenceUseCase.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/session/MessagePersistenceUseCase.kt)）

- 单调递增时间戳（同毫秒顺序稳定：assistant 恒在其 tool 结果前）；
- 内容净化（剥离 base64 图片、截断 200k）；
- `buildHistory()` 只回放 `isCompacted=false` + `validIds` 过滤无法配对的工具调用/结果（防 API 400）。

### 14.3 DAO / Entity（13 组核心 + 跨 feature 共享）

核心表：`agent_messages`（含 taskId/isCompacted/isContextSummary/isCompactionMarker/token）、`chat_sessions`（mode/reasoningEffort/providerId/model/token 累计）、`checkpoints` + `checkpoint_file_snapshots`、`todo_items`、`model_capability_overrides`、ZTH 六表（fuses/sentinels/rejection_audits/hard_constraint_delete_audits/l0_soft_compact_restore_logs/telemetry_events）、`skill_state`。

### 14.4 ZTH Repository 层

- **ZthCapabilityAuditRepository**：审计写遥测 + L0 压缩日志管理；
- **ZthCheckpointRepository**：幂等创建检查点 + 关联文件快照（CKPT-INV：存相对路径+hash，不存内容）；
- **ZthCircuitBreakerRepository**：熔断薄封装 + `mergeFromRemote` 跨设备同步（KILL-1 过滤）；
- **ZthConfirmationCardRepository**：sentinel + rejection audit 查询、时间线观察、崩溃恢复扫描、一键回滚；
- **ZthPlanApprovalRepository**：硬约束删除审计（写前加密）；
- **ZthTelemetryRepository**：14 指标 5 大类（FUSE/CARD/CAPABILITY/OFFLINE/SYNC），BURIED-INV：只存 sessionId SHA-256 前 16 字符，只追加不更新。

---

## 十五、Agent 展示层（UI）

### 15.1 [AIAgentViewModel.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/presentation/AIAgentViewModel.kt)（中央调度中枢）

- 状态：`Map<String, AgentUIState>` 多会话隔离、`_streamingText/_streamingReasoning`、`_runningTools`、`_pendingToolPermission/_pendingUserQuestion/_pendingPlanApproval`、`_environmentSnapshots`、`_taskGroups` 手风琴；
- **executeAgentRequestStream 主流程**：ensureSession → slashCommand 分流 → taskId 生成 → buildHistory → 用户消息落库 + 建 checkpoint → `agentWorkflow.executeEvents()` 收集事件 → 环境探测联动 → 队列处理；
- `editAndResend`：截断编辑消息之后的对话重新执行；`flushMergedNotifications` 合并通知。

### 15.2 [AIChatPanel.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/presentation/component/AIChatPanel.kt)

布局：ChatHeader → 主内容区（WelcomeState / LazyColumn：TaskAccordion + 思考气泡 + 尾部状态气泡）→ 底部面板区（ChangePreviewPanel / StatusBanner / ToolPermissionPanel / AskUserQuestionPanel / PlanApprovalPanel / EditingMessageBanner）→ ChatInputBar → FileDiffSheet。

**自动滚动策略**：`followBottom` 标志 + `snapshotFlow` 监测 + `autoScrollSignal`（含各流式长度）平滑滚动；思考阶段跟随目标改为倒数第二项避免空 Box 预跳抖动。

**附件处理**：文件/图片/拍照上传，URI 复制到工作区转 PendingUploadAttachment；图片发送规则（supportsVision 或 INFERRED）。

### 15.3 核心 UI 组件

MarkdownContent（compose-markdown + 代码高亮 + 行号 + 复制）、StatusBubbles（Thinking/Streaming/CompactionProgress/Retrying/Reasoning 可折叠）、TaskAccordion（按 taskId 分组可折叠）、ChatInputBar（模型选择/模式切换/附件/斜杠命令/token 进度条）、FileDiffSheet、ZthConfirmationCardSheet、SwipeToConfirm 等。

---

## 十六、终端系统

### 16.1 终端分层

```
TerminalViewModel → TerminalScreen（Compose）
        ↓ 委托
DelegatingTerminalSessionProvider（按执行模式路由）
        ├── TerminalSessionManager（本地 PRoot）
        └── RemoteTerminalSessionManager（SSH）
        └── SshShellBackend / TerminalKeepaliveService
Terminal Emulator（Termux Java） + Terminal View（Termux Java）
```

### 16.2 会话管理

- **TerminalSessionManager**：`tabOpLock: Mutex` 并发保护；`closeTab` 三步走安全关闭（移除列表 → 解绑 View → 延后 finish）；`ensureAtLeastOneTab` 合并式 pendingJob 避免竞态；后台命令 `notifyOnExit` + `sourceSessionId`；
- **RemoteTerminalSessionManager**：SSH 重连后 `reconnectAllInteractiveRunningTabs()` 自动重建标签（保留 id/title）；
- **SshShellBackend**：sshj Shell 通道 + `resizeExecutor` 独立线程池 + PipedInputStream/OutputStream 桥接；
- **TerminalKeepaliveService**：START_STICKY 前台服务；sessionCount 计数 + persistent 常驻标记；兜底分支防 `ForegroundServiceDidNotStartInTimeException`。

### 16.3 终端 Bundles（20 个可安装功能包）

AI 推荐组合 `{PYTHON, RIPGREP, GIT, BASH, NET}`；含 Python(45MB)/Node(55MB)/GCC(120MB)/Go(80MB)/OpenJDK(180MB)/.NET(250MB)/PHP/MySQL/PG/SQLite/FFmpeg/Docker CLI 等；每个 Bundle 带 `postInstallHook` 安装后配置；TerminalBundleRepository 用 marker 文件（bundleId+version 命名）持久化状态。

### 16.4 Terminal Emulator（[terminal-emulator](file:///workspace/deepcode-R/terminal-emulator) 15 Java 文件）

- **TerminalEmulator**：仿真核心（2000+ 行）——完整 ANSI/Xterm 转义序列（CSI/OSC/DEC 私有模式/SGR）、主/alt 屏幕切换、滚动区域、自动换行、制表符管理、光标样式；
- **TerminalSession**：会话生命周期 + SessionBackend 管理 + 窗口大小报告；
- **TerminalBuffer/TerminalRow**：行缓冲区 + 字符/样式/宽度（全角半角）；
- **TerminalColors/TextStyle**：256 色调色板 + 样式编码（前景/背景/7 效果位）；
- **KeyHandler**：Android 按键 → 转义序列（功能键/方向键/修饰键）；
- **SessionBackend/SubprocessBackend**：JNI 子进程 + PTY 管理。

### 16.5 Terminal View（[terminal-view](file:///workspace/deepcode-R/terminal-view) 7 Java 文件）

- **TerminalView**：自定义视图（1000+ 行）——onDraw 委托 TerminalRenderer、onKeyDown/onTouchEvent、attachSession、文本选择模式；
- **TerminalRenderer**：逐行逐字符渲染 + 反向视频 + 光标（块/下划线/竖线）+ 选中高亮；
- **GestureAndScaleRecognizer**：GestureDetector + ScaleGestureDetector 组合（滚动/捏合缩放/单击/双击/长按/甩动）；
- **TextSelection**：手柄 + 光标控制器。

---

## 十七、设置 / 凭据 / 备份模块

### 17.1 设置模块（[feature/settings](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings)）

- **AI Provider 配置**：`AIProviderConfig`（id/name/type/apiKey/baseUrl/defaultModel/isActive/models/selectedModel/isEnabled/useFullUrl/useResponseApi + effectiveModel）；Room 存储 `encryptedApiKey`（**加密失败中止保存防覆盖**）；激活全局互斥；AddProviderSheet（内置 StepFun 3 步向导 + 自定义 2 步向导）；
- **ModelMetadataService**：models.dev 目录 + 本地缓存 + **名称启发式推断**（视觉/工具/推理）+ 兼容策略修正 + 用户覆盖；产出 ModelMetadata（含 source 与 InferenceReason）；
- **ModelApiService**：fetchModels（按类型路径/鉴权、GEMINI v1beta/models、T2I 404 兜底）+ testModel（useFullUrl/useResponseApi 分叉）；
- **SettingsViewModel**：管理面极广（供应商/日志/主题/语言/Keepalive/MCP/权限/视觉与压缩模型/容器/执行模式/远程/审计/兼容策略）；
- **安全设置面**：SecuritySettingsViewModel（生物识别/DEK 轮换/紧急通道/ZTH 档位）+ SecurityEmergencyChannelSection（凭 SSH 密码验证身份后重置主密钥）+ RemoteAuditLogsScreen。

### 17.2 凭据模块（[feature/credentials](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/credentials)）

**三端桥 = Room（持久）↔ 内存/UI ↔ Git 凭据文件（.git-credentials）**：
- CredentialRepositoryImpl：CRUD + 加解密，变更后 `GitCredentialsFileSync.sync()`；
- GitCredentialsFileSync：写系统 git 凭据文件并维护默认；
- CredentialRequestBridge + GlobalCredentialDialogHost + CredentialPromptDialog：业务请求 → UI 弹窗应答 → 回填；
- GitCredentialEntity 只存 `encryptedToken`。

### 17.3 备份模块（[feature/backup](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/backup)）

- **接口**：BackupManager.export / exportSession / import（全流式，内存与数据总量解耦）；
- **格式**：gzip + tar；`metadata.json`（小表 + 设置）+ 三个 `*.jsonl`（会话/消息/待办）+ 兼容旧 `snapshot.json`；schemaVersion 校验（备份高于当前拒绝）；
- **加密**：BackupCrypto —— PBKDF2WithHmacSHA256(210k) + AES-GCM 流式手动分块（规避 Android GCM 流式 flush 坑）；随机 salt/IV 入文件头；
- **安全壳**：`safeDao/safeDaoSuspend` 防 Room schema 校验异常崩溃波及备份流程；
- **分页**：keyset 分页（PAGE_SIZE=500）；**重定位**：会话还原强制当前 workspacePath；
- 展示：BackupViewModel 状态机 + BackupSection（范围选择/口令/SAF 导出导入/结果统计）。

---

## 十八、Git 特性

### 18.1 [GitRepository.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/git/domain/GitRepository.kt)

全部底层 Git 操作唯一实现（经 CommandEngine 执行系统 git 并解析输出）：
- `status()`：porcelain v1 + `-b` 逐行解析（当前分支/ahead/behind、XY 双字符、重命名 `->` 路径 unquote）；
- `log()`：自定义 --format 输出提交元信息 + 按需 `git show` 完整 body + 分页；
- `diff()`：`git diff` + LineDiff 行类型 + 预计算新旧行号与语法高亮；
- 失败包装 `GitCommandFailureException` → `GitErrorMessage` 友好文案。

### 18.2 [GitGraphBuilder.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/git/domain/GitGraphBuilder.kt)（泳道布局算法）

维护 `active: MutableList<String?>` 泳道；延续已有泳道 → 复用空闲 → 新建；**首父连线**（跨泳道边 or 自连边）；**合并边**优先复用已存在泳道（`reuse` 逻辑减少交叉）；记录 activeTopLanes/activeBottomLanes 供 UI 画线。

### 18.3 展示层

GitScreen（Tab：Status/Branches/Log）+ GitStatusTab（staged/unstaged/untracked 分组、逐文件 stage）+ GitBranchesTab（检出/新建/删除 + ModalBottomSheet 表单）+ GitLogTab（Canvas 泳道图 + 滚动加载更多）+ DiffViewer（行号/高亮/增删着色，ViewModel 后台预计算，UI 零计算）。

---

## 十九、Workspace 特性

### 19.1 文件访问抽象

**[FileAccessProvider.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/workspace/domain/FileAccessProvider.kt)**（统一接口：readFile/readLines/writeFile/exists/listFiles/copyToLocal/delete/mkdirs/permissions…）+ 三实现：
- **LocalFileAccess**（java.io.File，readLines 惰性 Sequence）；
- **RemoteSftpFileAccess**（SFTP，`sftp://host:port` 显示路径，POSIX 权限位格式化）；
- **DelegatingFileAccess**（按执行模式自动分发，上层零改动）。

### 19.2 远程同步引擎

- **[SyncEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/workspace/domain/remote/SyncEngine.kt)**：本地↔远程双向差分同步；`isIgnored` 过滤（customIgnores + gitignore）；`FileObserver` 实时监听 → syncChannel 增量上传；失败重连 + 重试队列；`delay(50)` 限速；`maxSyncBatchSize` 分批；
- **RemoteSyncClient** 接口 + Local/SFTP/FTP 三实现；
- **[FtpServerManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/workspace/domain/remote/ftp/FtpServerManager.kt)**：内置 FTP 服务器（反向共享 workspace，事件回调写审计）；
- **WorkspacePathMapper**：本地/远程路径双向换算；
- 审计：RemoteAuditCategory 分类 + RemoteAuditLogDao 落库。

### 19.3 其余

RemoteConnection/RemoteMount/RemoteAuditLog 领域模型 + Room DAO；RemoteServerScreen/ViewModel（连接与挂载 CRUD、同步状态机、FTP 服务）；WorkspacePicker + WorkspaceViewModel + WorkspaceDocumentsProvider（SAF 辅助）。

---

## 二十、T2I 文生图特性

- **3 张新表**：`t2i_providers`（isActive+priority 激活互斥、endpointMode SYNC/ASYNC/AUTO、extraHeadersJson）、`t2i_provider_models`（supportsHd/Inpaint、costPerImageTokens 额度）、`t2i_tasks`（状态机、imagePath/thumbnailPath、permissionDecision、quotaDeductedTokens）；
- **[ImageGenerator.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/t2i/data/remote/ImageGenerator.kt)**：OpenAI 兼容实现（`POST {baseUrl}/v1/images/generations`，b64_json 解码存盘 + 缩略图）；EndpointMode：SYNC 同步返回 / ASYNC 轮询远端任务 / AUTO 自动探测；非 2xx 解析 error.code 抛 ProviderException（refundable 标记）；
- **[T2IModelProbeService.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/t2i/data/remote/T2IModelProbeService.kt)**：连通性测试 + 端点模式探测 + 默认模型建议（`classify` 归因 401/404/网络错误）；
- **[T2IPermissionPolicyEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/t2i/domain/permission/T2IPermissionPolicyEngine.kt)**：P1–P6 多级评估（强制确认 / 日额度耗尽 `sumDeductedTokensSince` / 单次上限 / 高风险动作 / 余额预警 / 放行），DENY 带机器可读 denyCode + 用户可读 denyMessage，决策与扣减全程落库可追溯。

---

## 二十一、资源资产（prompts/docs/脚本）

### 21.1 Prompt 提示词系统（11 个文件，[assets/prompts](file:///workspace/deepcode-R/app/src/main/assets/prompts)）

| 文件 | 主题 |
|------|------|
| 00-identity.md | AI 身份定义（Android 内运行、PRoot 完整 Linux 环境、本地/远程双模式） |
| 10-communication.md | 沟通风格（中文、专业、具体示例） |
| 15-project-rules.md | 加载 AGENTS.md、代码修改纪律、Conventional Commits、资产同步 |
| 20-coding-discipline.md | 编码规范（Kotlin 最佳实践、类型安全） |
| 30-comments.md | 注释规范（解释 why 而非 what） |
| 40-approach.md | 工作方法论（分析→计划→执行→验证） |
| 50-safety.md | 安全准则（凭据脱敏 `{REDACTED}`、灾难性 rm 拦截） |
| 60-tools-and-paths.md | 17 个工具说明 + 工作区路径约定 |
| 70-skills-and-mcp.md | Skill / MCP 使用指南（stdio + HTTP） |
| 80-plan-mode.md | PLAN 模式（只读约束、计划质量标准） |
| 81-auto-mode.md | AUTO 模式（全放行但保留灾难性 rm 拦截） |

### 21.2 Docs 使用文档（11 个，[assets/docs](file:///workspace/deepcode-R/app/src/main/assets/docs)）

app-settings-guide / backup-and-restore / checkpointing / container-image / custom-prompts / environment-guides / git-page / logs-and-private-dir / mcp-and-skills / providers-and-models / remote-servers。

### 21.3 git-credential-rdeepcode 脚本（[assets/rdeepcode](file:///workspace/deepcode-R/app/src/main/assets/rdeepcode/git-credential-rdeepcode)）

Git credential helper：只处理 `get`；自检 `~/.rdeepcode/git-credentials`；未命中写 `cred-req-<RID>` 触发 App FileObserver；阻塞轮询 `cred-resp-<RID>`（200ms × 7500 次 ≈ 25 分钟）；原子写（先 .tmp 再 mv）；经 PRoot -b 绑定共享；仅 HTTPS 生效。

### 21.4 api.official.json

预置模型元数据：anthropic(15) / deepseek(4) / google(30+) / xai(10) / minimax(7) / moonshot(10) / openai(40+) / alibaba(40+) / zhipuai(16) 等，每个模型含 id/name/tool_call/reasoning/limit/modalities。

---

## 二十二、CI/CD 与工程规范

### 22.1 CI/CD 工作流（[.github/workflows](file:///workspace/deepcode-R/.github/workflows)，6 个）

| 工作流 | 触发 | 内容 |
|--------|------|------|
| ci.yml | push/PR 到 main | fetch-depth 0、JDK17+SDK36、生成 debug keystore、assembleRelease + testReleaseUnitTest |
| android-release.yml | `v*` Tag 或手动 | 固定 debug keystore（base64 硬编码保跨 run 签名一致）、versionCode 单调校验、尝试还原正式 keystore、构建 Release APK → `rdeepcode-arm64-<ver>.apk`、changelog、GitHub Release + SHA256 |
| auto-cleanup.yml | 每周日 20:00 UTC | 清理 30 天前 workflow runs / 过期 artifacts |
| ci-failure-alert.yml | CI 失败 workflow_run | 自动创建 Issue 追踪（ci-failure/bug 标签） |
| dependency-audit.yml | 每周三 00:00 UTC | 依赖更新检查、Gradle wrapper 版本、审计摘要 |
| weekly-health-check.yml | 每周一 00:00 UTC | Lint + 双变体单测 + 全量构建 + APK 大小/DEX 数统计 |

### 22.2 Tools 工具脚本（[tools](file:///workspace/deepcode-R/tools)，3 个）

- **ci_monitor.py**：实时轮询 GitHub Actions（ANSI 彩色、状态图标、产物信息）；
- **generate_launcher_icons.py**：master 图 → 5 档密度 launcher 图标（LANCZOS 缩放 + 圆形 mask + PlayStore 512）；
- **mvn_proxy.py**：本地 Maven 缓存反向代理（解决沙箱 443 被拦截；localhost:18080 → curl 拉取 + SHA256 缓存 + 多上游兜底）。

### 22.3 commit-msg Hook（[.githooks/commit-msg](file:///workspace/deepcode-R/.githooks/commit-msg)）

校验 Conventional Commits：`<type>(<scope>): <subject>`；type ∈ 9 种；scope ∈ 12 个模块；merge/revert/fixup/squash 跳过。

### 22.4 工程文档（[docs/engineering](file:///workspace/deepcode-R/docs/engineering)）

**startup-crash-lessons-RC61.md**：RC60→RC61 闪退复盘——根因链（主线程 Hilt 构建 + 后台 CredentialRequestBridge 抢 Room 单例锁 → 伪死锁 → 5s 超时杀进程 → CrashHandler 异步写日志未落盘）；9 大工程铁律 + 反模式红黑榜 + 3 套 SOP + 发布 Checklist。

---

## 二十三、测试体系

单元测试位于 [app/src/test](file:///workspace/deepcode-R/app/src/test)（JVM 层）：

| 文件 | 覆盖 |
|------|------|
| DbSCHIELDPreflightTest.kt | DB-SHIELD 预检（ENTITY-COUNT-TEST / GAP-TEST 豁免） |
| FileMigrationTest.kt | SQL 文件迁移加载/切分/连续性 |
| MigrationSchemaConsistencyTest.kt | 迁移与 schema JSON 一致性 |
| ErrorUtilsTest.kt | 错误消息工具 |

CI 门禁：`testReleaseUnitTest`（release classpath，与 AGENTS.md 推送前必跑单测一致）。

---

## 二十四、架构模式与设计原则

1. **Feature-based + DDD 三层**：`feature/<模块>/domain | data | presentation`，纯 Kotlin domain 无 UI/数据库依赖；
2. **MVI 状态机**：StatefulAgentWorkflow 的 Model/Intent/SideEffect + 纯函数 reduce，可测试可预测；
3. **策略模式**：DelegatingTerminalSessionProvider / DelegatingFileAccess / DelegatingCommandEngine 按执行模式路由；
4. **适配器模式**：SessionBackend 统一本地子进程与 SSH；AIProvider 统一三家协议；RemoteSyncClient 统一 Local/SFTP/FTP；
5. **事件驱动**：Flow<AgentEvent> 驱动 UI；ToolEventBus 发布-订阅（含循环保护）；
6. **观察者模式**：TerminalSessionClient 回调；Room Flow 响应式；
7. **协程并发**：Mutex（tabOpLock）、CoroutineExceptionHandler、SupervisorJob 隔离；
8. **多会话隔离**：AIAgentViewModel 所有状态 `Map<sessionId, T>` 索引；
9. **双阶段 DB 兜底 + 崩溃备份**：DB-SHIELD 四阶段 Funnel + Funnel 0 崩溃还原。

---

## 二十五、关键设计决策与潜在风险点

### 关键设计决策

1. **targetSdk=28 锁定**：PRoot 需在可写目录执行二进制（Android 10+ W^X/SELinux 禁止）；配套 lint 关闭 ExpiredTargetSdkVersion、`usesCleartextTraffic=true`、ImeInset 自管理键盘（ADJUST_NOTHING + WindowInsetsAnimationCompat）。
2. **BouncyCastle 注册**用 `addProvider` 放列表末尾（不抢 OkHttp/Conscrypt SSLContext KeyStore 查找），供 sshj 按名查找。
3. **版本号全自动**：仅 0.1.0 系列 tag 有效，versionCode 由 commit 计数单调生成，杜绝回跳。
4. **签名回退**：无 keystore.properties 时用 debug keystore 签 release，保证 CI/本地零配置可产出 APK。
5. **资产同步纪律**：改代码必须同步 prompts/docs/strings.xml（AGENTS.md 强制）。
6. **RC91 强制 writableDatabase 打开**：让 DB-SHIELD Funnel 链真正生效（Room build 惰性，不打开接不到迁移失败）。
7. **RC74 DEK 保护改 AES/GCM ENCRYPT/DECRYPT**：Android Keystore 的 AES/GCM 不实现密钥包装模式，WRAP 会抛 Incompatible purpose。
8. **RC94 LightweightSchemaRescue 改解析 schema JSON**：@Entity 注解 retention 是 BINARY，Release/R8 下反射恒 null。

### 潜在风险点（代码审读发现）

1. **CredentialEncryptor.setBiometricRequired**：实际生成 MasterKey 时固定传 `biometricRequired=false`，仅 state 记录标志，Keystore 层并不真的启用生物识别——与"开启生物识别保护"文档描述不一致。
2. **UserPasswordBackupCrypto.encryptingOutputStream**：注释明确 close() 未实现流式 HMAC 计算，而 decryptingInputStream 会校验 HMAC 尾——用该路径加密的产物可能无正确 HMAC 尾，解密时会抛 BackupWrongPasswordException（实际备份可能走别的加密路径，需关注）。
3. **AuditPurgeWorker**：文档写"10000 条/90 天"，代码仅实现 90 天时间维度（10k 条数上限在 Repository 层，需确认）。
4. **SQL 迁移切分**：按 `";"` 直接 split，SQL 内容不能含分号字符串（已知简化）。
5. **ZthSharedSyncKeyStore**：Phase 1 仅接口占位，跨设备同步密钥能力未实现（Phase 4 才实现）。
6. **MasterKeyTamperedException** 已不再被 ensureInitialized 抛出（仅保留类型），紧急解锁走 emergencyResetMasterKey。

---

## 二十六、代码快速索引

| 关注点 | 文件 |
|--------|------|
| 应用入口 | [AIEditorApp.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/AIEditorApp.kt) / [MainActivity.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/MainActivity.kt) |
| DI | [AgentModule.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/di/AgentModule.kt) |
| 加密 | [CredentialEncryptor.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/CredentialEncryptor.kt) / [DEKManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/security/DEKManager.kt) |
| 数据库 | [AgentDatabase.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/data/local/database/AgentDatabase.kt) / [MigrationLoader.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/db/MigrationLoader.kt) / [LightweightSchemaRescue.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/core/db/LightweightSchemaRescue.kt) |
| Agent 工作流 | [StatefulAgentWorkflow.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt) / [ContextCompactor.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/ContextCompactor.kt) |
| 提供商 | [AnthropicAdapter.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/provider/AnthropicAdapter.kt) / [OpenAIAdapter.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/provider/OpenAIAdapter.kt) / [GeminiAdapter.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/provider/GeminiAdapter.kt) |
| 工具 | [ToolRegistry.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/tool/ToolRegistry.kt) / [ToolPermissionPolicyEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt) |
| ZTH | [ZthGuardAggregateFacade.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthGuardAggregateFacade.kt) / [ZthConfirmationCardStateMachine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthConfirmationCardStateMachine.kt) / [ZthCapabilityGuard.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/zth/ZthCapabilityGuard.kt) |
| 容器 | [LinuxContainerEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/container/LinuxContainerEngine.kt) / [ContainerInstaller.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/container/ContainerInstaller.kt) |
| 终端 | [TerminalSessionManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/terminal/domain/TerminalSessionManager.kt) / [TerminalEmulator.java](file:///workspace/deepcode-R/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java) / [TerminalView.java](file:///workspace/deepcode-R/terminal-view/src/main/java/com/termux/view/TerminalView.java) |
| 备份 | [BackupManagerImpl.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/backup/data/BackupManagerImpl.kt) |
| Git | [GitRepository.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/git/domain/GitRepository.kt) / [GitGraphBuilder.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/git/domain/GitGraphBuilder.kt) |
| 同步 | [SyncEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/workspace/domain/remote/SyncEngine.kt) / [FtpServerManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/workspace/domain/remote/ftp/FtpServerManager.kt) |
| T2I | [ImageGenerator.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/t2i/data/remote/ImageGenerator.kt) / [T2IPermissionPolicyEngine.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/t2i/domain/permission/T2IPermissionPolicyEngine.kt) |
| 提示词 | [SystemPromptProvider.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/prompt/SystemPromptProvider.kt) |

---

*文档完 · 基于 380 个 Kotlin 文件 + 22 个 Java 文件 + 37 个 SQL 迁移逐文件精读整合*
