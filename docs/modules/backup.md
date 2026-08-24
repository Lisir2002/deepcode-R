# 备份（Backup）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/backup/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

负责 R-CodeCore 应用数据的**整体导出（备份）与导入（还原）**。支持 AI Provider（含 API Key）、Git 凭据、远程连接/挂载、聊天会话与消息、Todo、MCP 服务器、全局权限规则、应用设置（主题/keepalive/日志/视觉模型/压缩模型/同步设置）等数据段的备份还原。

核心设计目标：

- **全程流式**：大表（会话/消息/Todo）以分页批次流式读写，内存峰值与数据总量解耦。
- **可选口令加密**：导出口令非空时对整体 tar.gz 做 AES-GCM 流式加密；口令不落盘、不记忆。
- **版本兼容**：导出时记录数据库 schema 版本，导入时校验「备份版本 > 当前版本则拒绝」。
- **敏感字段在备份明文与库内密文之间转换**：备份文件里 API Key / Git Token / SSH 密码以明文承载（用户自行保管文件），还原时用当前设备 `CredentialEncryptor` 重新加密入库。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/BackupManagerImpl.kt` | `BackupManager` 的实现：数据采集、tar.gz 打包/解包、加密解密编排、DTO↔Entity 转换、旧格式兼容、DAO 安全访问壳 |
| `domain/BackupManager.kt` | 备份编排接口（`export` / `exportSession` / `import`），以及 `BackupOptions`、`RestoreStats` 数据类 |
| `domain/BackupCrypto.kt` | 对称加解密工具（PBKDF2WithHmacSHA256 + AES/GCM/NoPadding 流式），含 `BackupDecryptionException` |
| `domain/BackupEncryptScope.kt` | 加密范围枚举（`CREDENTIALS_ONLY` / `FULL`）——当前实现采用全量加密语义，枚举保留 |
| `domain/BackupSnapshot.kt` | 备份数据模型：`BackupSnapshot`（旧格式）、`BackupMetadata`（流式格式的 metadata.json）及全套 DTO（ProviderDto、GitCredentialDto、RemoteConnectionDto、RemoteMountDto、ChatSessionDto、AgentMessageDto、TodoItemDto） |
| `presentation/BackupViewModel.kt` | 备份/还原的 UI 状态机（`BackupState`）与流式导入导出编排；数据保全 UI 状态（`DataSafetyUiState`，哨兵 + 本机/外部自动备份） |
| `presentation/BackupSection.kt` | 备份设置页 Compose UI：SAF 文件选择、数据范围勾选、口令输入、进度/结果弹窗、历史数据恢复横幅、数据丢失告警横幅、本机自动备份卡片、外部安全备份卡片 |
| `data/AutoBackupManager.kt` | 双保险自动备份：本机私有目录明文备份（`filesDir/auto-backups/`）+ 外部公共目录签名密钥加密备份，各自轮转保留最近 7 份（纯判定 `excessBackupFiles` / `excessExternalBackups` 可单测） |
| `data/SignatureKeyStore.kt` | 从应用签名证书 SHA-256 派生「跨包名稳定」加密密钥：同一 keystore 签名的包（无论包名）得到相同口令，是外部加密备份可跨包解密找回的密钥基础 |
| `data/ExternalBackupStore.kt` | 外部公共存储备份落点（包名无关安全网）：API 29+ 走 MediaStore.Downloads（`Download/RCodeCore/backups`，免权限），API <29 走 `getExternalStoragePublicDirectory`（需 WRITE_EXTERNAL_STORAGE）；只写调用方加密后的内容 |
| `data/LegacyPackageDetector.kt` | 同签名旧包检测：判断 `com.aicodeeditor` / `com.aicode` / `com.deep.rcode` 旧包是否仍安装且签名一致，供哨兵区分「真全新安装」与「rebrand 升级」 |
| `data/DataSafetyNotifier.kt` | 数据保全通知器：启动检查唯一出口（跑哨兵 + 升级前双保险备份 + 发布判定结果），MainActivity 据此弹启动级全局告警 |
| `data/guard/AppRunMeta.kt` | 应用运行元数据持久化（DataStore `app_run_meta`）：`dataInitialized` / `lastVersionCode` / `lastApplicationId`，哨兵判定依据 |
| `data/guard/DataSentinel.kt` | 数据完整性哨兵：启动检测「全新安装 / 正常升级 / 数据丢失 / 包名被改」并维护运行元数据 |
| `data/guard/SentinelLogic.kt` | 哨兵**纯判定逻辑**（`SentinelVerdict` 枚举 + `SentinelLogic.evaluate`，无 Android 依赖，可单测） |

## 3. 核心架构与主流程

### 3.1 备份文件格式

统一产物为 **tar.gz**（未加密）或 **AES-GCM 加密的 tar.gz**（有口令）。tar 内：

| 条目 | 内容 |
| --- | --- |
| `metadata.json` | `BackupMetadata`（schemaVersion、appVersion、createdAt + 小表数据：providers/gitCredentials/remoteConnections/remoteMounts/mcpServers/globalPermissionRules + 应用设置） |
| `chatSessions.jsonl` | 会话大表（JSONL，逐行一条 DTO） |
| `messages.jsonl` | 消息大表（JSONL） |
| `todoItems.jsonl` | Todo 大表（JSONL） |
| `registry.tar` | **数据注册表全量段**（数据层重构「新写法」新增）：除上述三个已 jsonl 流式导出的表外，其余全部 Room 表（checkpoint/skill/wake/telemetry/t2i/credentials/…）与 DataStore 目录（`files/datastore/`）经 `DataRegistry.snapshotAll()` 打包，恢复时 `restoreAll` 逐域还原 |
| `snapshot.json` | 旧版单文件快照格式（仅导入兼容用，导出不再产生） |

### 3.2 导出主流程（`export`）

1. 建临时文件，`writeTarGz` 内用 `GzipCompressorOutputStream` + `TarArchiveOutputStream` 顺序写入：
   - `metadata.json`：按 `BackupOptions` 开关采集各小表与设置快照（经 `safeDaoSuspend` 兜底）。
   - 大表 JSONL：游标分页（`PAGE_SIZE=500`），`getPageAfter`/`getPageBySessionAfter` 等接口按 `(lastTs/lastId)` 翻页，写入 `JsonlWriter`（64KB 行缓冲）。
2. 临时 tar.gz 文件再经 `BackupCrypto.encryptStream`（有口令）或直接 `copyTo`（无口令）写入调用方提供的 `OutputStream`。
3. 清理临时文件。

`exportSession(sessionId)` 为单会话导出变体：只含该会话 + 关联消息 + 关联 Todo，无密码加密，可直接被 `import` 还原。

### 3.3 导入主流程（`import`）

1. 有口令：先解密到临时文件，再解 gzip+tar；无口令：直接解 gzip+tar。
2. 遍历 tar 条目：
   - `snapshot.json` → `checkVersion` → `restoreLegacy`（旧格式还原）。
   - `metadata.json` → 解析并 `checkVersion`（备份 schema 高于已知最大版本直接拒绝）。
   - 各 `*.jsonl` → `restoreJsonl` 按行解析、每 `PAGE_SIZE` 条回调一次批量插入（upsert/insertAll）。
   - `registry.tar` → `DataRegistry.unpack` + `restoreAll`（逐域还原注册表全量段；DataStore 目录覆盖后由下方 `restoreMeta` 的 repository 级恢复再写一遍，刷新内存缓存）。
3. `restoreMeta` 还原小表与设置（provider/gitCredential/remote/mcp/permission 及各类设置 repository 的 `restore`），并返回 `RestoreStats` 汇总。
4. 异常归类：`BackupDecryptionException`（口令错误）与 `IllegalStateException` 原样抛出；其余包装为带用户可读提示的 `IllegalArgumentException`。

### 3.4 敏感字段加解密（DTO ↔ Entity）

- **AI Provider**：库内 `encryptedApiKey` ↔ 备份 `ProviderDto.apiKey`（明文）。SCHEMA 38 后 Entity 无 `apiKey/selectedModel` 列，`selectedModel` 为旧备份兼容字段，与 `defaultModel` 语义合并。
- **Git 凭据**：库内 `encryptedToken` ↔ 备份 `GitCredentialDto.token`（明文）。
- **远程连接**：`PASSWORD` 类型 `authData` 解密为明文密码导出、导入时重新加密；`PRIVATE_KEY` 类型 `authData` 是私钥路径本身（不加密），`passphrase` 加解密；旧数据解密失败时兜底按明文返回。

### 3.5 DAO 安全访问壳（`safeDao` / `safeDaoSuspend`）

所有 DAO 调用统一经过安全壳：Room 首次 query 触发 onOpen schema 校验，失败会抛 `IllegalStateException` 直接崩进程。安全壳捕获后记 `FileLogger` 并按 `failValue` 兜底返回，保证备份/导入流程失败不外溢到 UI 启动链。

### 3.6 历史数据恢复横幅（包名变更检测）

`BackupSection` 顶部有 `LegacyDataRecoveryBanner`：通过 `PackageManager` 检测历史遗留包名（`com.aicodeeditor`、`com.deep.rcode`）是否仍安装且**签名与当前包一致**（`GET_SIGNING_CERTIFICATES`/`GET_SIGNATURES` 取首个签名比对）。若命中则展示提示卡，引导用户「旧版本导出备份 → 本版本导入备份」找回因包名变更而隔离的历史对话。

> 背景：applicationId 三次变更（`com.aicodeeditor` → `com.deep.rcode` → `com.R.codecore`），每次变更是完全不同的 App，新包名全新安装导致旧包数据不可见。该横幅与 `ApplicationIdStabilityTest`（锁死 release applicationId）共同防止用户数据再次因改包名而丢失。

### 3.7 数据保全（数据完整性哨兵 + 本机自动备份）

针对「历史对话在升级后清空」这一根因（包名变更 = 全新安装、数据被异常清空不可感知、无自动备份），数据保全用**三层防线**覆盖编译期/发布期/运行期：

- **防变更（编译/发布期）**：
  - D1 单测 `ApplicationIdStabilityTest`（release classpath，锁死 `com.R.codecore`，禁回退遗留包名）。
  - D2 CI 发版门禁 `.github/workflows/android-release.yml` 的 `Verify applicationId stability`：比对当前 tag 与上一 tag 的 `applicationId`，不一致则 `::error::` 阻断发版。
  - D3 构建期白名单 `app/build.gradle.kts` 的 `androidComponents.onVariants`：`applicationId` 不在 `ALLOWED_APPLICATION_IDS`（`com.R.codecore` / `com.R.codecore.debug`）内 → 构建直接失败。
  - D3b 系统级云备份兜底（AndroidManifest + `res/xml/`）：`android:allowBackup="true"` + `full_backup_rules.xml`（Android 9-11）+ `data_extraction_rules.xml`（Android 12+），让系统/OEM 换机克隆/Google 云备份保留 Room DB、崩溃备份、ZTH 元数据与 shared_prefs。局限：按包名路由，rebrand 后旧备份不可达，故仅作第三层兜底，主防线仍是应用层双保险备份（D5/D6b）。
- **防丢失（运行时数据安全网）**：
  - D4 数据完整性哨兵（`DataSentinel` + `AppRunMeta` + `SentinelLogic` + `LegacyPackageDetector`）：启动时读运行元数据与 `ChatSessionDao.count()`，判定 `FIRST_RUN / UPGRADED / NORMAL / DATA_LOST / PACKAGE_CHANGED`。判定优先级：未初始化且无同签名旧包→`FIRST_RUN`；未初始化但有同签名旧包→`PACKAGE_CHANGED`（哨兵记忆随包名隔离丢失时，靠 `LegacyPackageDetector` 识别 rebrand 升级，不再静默当全新安装）；包名不一致→`PACKAGE_CHANGED`（优先于 DATA_LOST）；已初始化但会话数=0→`DATA_LOST`；versionCode 增大→`UPGRADED`；其余→`NORMAL`。`DATA_LOST`/`PACKAGE_CHANGED` 不更新 `lastRun`，保留告警态供 UI 持续提示。
  - D5 升级前自动备份（`AutoBackupManager`）：哨兵判定 `UPGRADED` 时后台执行 `backupAll()` 双保险备份——本机私有目录明文（`filesDir/auto-backups/backup-<epochMs>.tar.gz`，仅本应用可读）+ 外部公共目录签名密钥加密（`Download/RCodeCore/backups/`，包名无关）。各自 `pruneLocked` / `pruneExternalLocked` 轮转保留最近 7 份（纯判定 `excessBackupFiles` / `excessExternalBackups` 可单测）。全程 `runCatching`，失败仅记日志不阻断启动。
  - D6 自动备份状态可视化：`BackupSection` 顶部 `AutoBackupCard`（本机：上次备份时间、份数、「立即备份到本机」）。
  - D6b 外部安全备份卡片：`BackupSection` 的 `ExternalBackupCard`（外部安全区：上次备份时间、份数、「立即备份到外部安全区」、有备份时「从外部安全区恢复」）。
- **可找回（迁移与恢复入口）**：
  - D7 同签名旧包检测横幅 `LegacyDataRecoveryBanner`（见 3.6）。
  - D8 数据丢失告警 `DataLossAlertBanner`：哨兵返回 `DATA_LOST`/`PACKAGE_CHANGED` 时展示红色横幅，本机有自动备份则提供「从最近备份恢复」（`AutoBackupManager.latestBackup()` + `BackupManager.import(file, null)` 无口令导入）。
  - D8b 启动级全局告警 `DataSafetyStartupAlert`（MainActivity 顶层）：`DataSafetyNotifier` 发布哨兵判定结果，`DATA_LOST`/`PACKAGE_CHANGED` 时**冷启动即弹全局弹窗**（不再只藏在备份设置页里），一键跳转「设置 → 备份与还原」；「我知道了」仅本次会话去重，恢复后下次启动自然回落不弹。
  - R1 **无感自动迁移**（数据层重构「新写法」新增）：哨兵判定 `PACKAGE_CHANGED` 时，`DataSafetyNotifier` 自动调 `AutoBackupManager.restoreFromLatestExternal()` 从外部加密备份**全量恢复**（经 `DataRegistry` 覆盖全部 Room 表 + DataStore），成功则重置哨兵记忆（`AppRunMeta.updateLastRun`）且**本轮不弹窗**（用户零操作）；失败（无外部备份 / 密钥派生失败 / 解密失败）才回退 D8b 告警弹窗，保留手动恢复入口。
  - D9 About 页变体/包名展示（`VariantPill`，见 settings 模块）。

**挂载点**：`AIEditorApp.onCreate` 中 `appScope.launch { delay(500L); dataSafetyNotifier.run() }`（内部：哨兵 → `UPGRADED` 时 `autoBackupManager.backupAll()` → 发布判定结果），延后首帧且 `runCatching` 兜底，任何失败不影响启动。`MainActivity` 注入 `DataSafetyNotifier` 观察 `verdict` 弹全局告警。`DataSentinel` / `AutoBackupManager` / `DataSafetyNotifier` 等由 Hilt 单例注入。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `BackupManager.export(password, options, output)` | 全量导出；`output` 由调用方打开并负责关闭 |
| `BackupManager.exportSession(sessionId, output)` | 单会话导出（无密码） |
| `BackupManager.import(input, password): Result<RestoreStats>` | 流式还原，返回各段条目统计 |
| `BackupOptions` | 导出数据范围开关（providers / gitCredentials / remoteConnections / chatHistory / mcpServers / permissionRules / appSettings） |
| `BackupViewModel` | Hilt ViewModel，`BackupState`：`Idle / Working / ExportDone / ImportSuccess(stats) / Error(message)`；数据保全 `dataSafety: StateFlow<DataSafetyUiState>`，方法 `refreshDataSafety()` / `backupNow()` / `backupToExternal()` / `restoreFromLatest()` / `restoreFromLatestExternal()` |
| `BackupSection` | Compose 页面，通过 SAF `CreateDocument` / `OpenDocument` 与系统文件选择器交互；顶部聚合数据保全 UI（`LegacyDataRecoveryBanner` + `DataLossAlertBanner` + `AutoBackupCard` + `ExternalBackupCard`） |
| `DataSafetyNotifier.run()` | 启动检查唯一出口：哨兵 → `UPGRADED` 时双保险备份 → 发布 `verdict: StateFlow<SentinelVerdict?>`；`shouldShowStartupAlert` / `dismissStartupAlert()` 供 MainActivity 全局告警去重 |
| `AutoBackupManager` | `backupNow()`（本机）/ `backupToExternal()`（外部加密）/ `backupAll()`（双保险）/ `backups()` / `latestBackup()` / `lastBackupTime()` / `externalBackups()` / `latestExternalBackup()` / `lastExternalBackupTime()` / `restoreFromLatestExternal()`（签名密钥解密导入）；`KEEP_MAX=7` |
| `DataSentinel.check()` | 哨兵检测，返回 `SentinelVerdict`；由 `DataSafetyNotifier.run()` 在启动延后 500ms 调用，`UPGRADED` 时联动 `AutoBackupManager.backupAll()` |
| `SentinelLogic.evaluate(...)` | 哨兵纯判定（无 Android 依赖），`AppRunMeta`/`ChatSessionDao.count()`/`LegacyPackageDetector` 之外的逻辑均可直接单测 |

依赖的外部模块：agent（`AgentMessageDao`/`ChatSessionDao`/`TodoItemDao`/`AgentDatabase`/`McpConfigRepository`/`McpManager`/`PermissionRulesRepository`）、credentials（`GitCredentialDao`）、settings（`AIProviderDao` 及 Theme/Keepalive/Log/Vision/Compaction/Sync 设置仓库）、workspace（`RemoteConnectionDao`/`WorkspaceRepository`）、core.security（`CredentialEncryptor`）、core.util（`FileLogger`）。

## 5. 关键设计点与约束

- **版本门槛**：`checkVersion` 只拒绝「备份 schema > 当前 schema」，允许旧备份导入（新字段取默认值）。
- **流式内存控制**：大表导出用游标分页、导入用 `restoreJsonl` 行缓冲 + 批量插入，不整体载入内存。
- **加密参数**：PBKDF2WithHmacSHA256、210,000 次迭代、256 位密钥、GCM 128 位 tag、随机盐(16B)/IV(12B) 写入文件头。GCM 自带完整性校验，口令错误或篡改在 `doFinal` 抛 `BadPaddingException` → `BackupDecryptionException`。
- **不用 CipherOutputStream/CipherInputStream**：避免 Android GCM 流式下 flush 触发 update 语义、以及 CipherInputStream 吞掉 doFinal 校验异常导致口令错误检测不到。
- **tar 条目 size 前置**：jsonl 先写临时文件拿到准确 `File.length` 再设 `TarArchiveEntry.size`，避免 size 为 0 导致写入越界。
- **工作区路径重绑定**：导入会话时用当前 `workspaceRepository.currentPath()` 覆盖备份中的 `workspacePath`，避免跨设备路径失效。
- **MCP 恢复副作用**：`mcpServers` 恢复后调用 `mcpManager.reload()` 触发运行时重载。
- **`BackupEncryptScope` 枚举**：声明了 `CREDENTIALS_ONLY`/`FULL` 两种范围，当前 `BackupManagerImpl` 按全量加密（FULL）语义实现，加密范围是全局统一行为而非按段选择。

## 6. 维护与扩展指引

- **新增数据段**：
  1. 在 `domain/BackupSnapshot.kt` 增加对应 DTO（`@Serializable`）。
  2. 在 `BackupOptions` 增加开关（如需用户可选）。
  3. 在 `BackupManagerImpl.buildMetadata`/`restoreMeta` 中采集与还原，并补充 Entity↔DTO 转换。
  4. 在 `BackupSection.buildImportSummary` 与 strings.xml 中补充统计展示。
  5. 若含敏感字段，沿用「库内密文 ↔ 备份明文 + 导入重加密」的模式，用 `CredentialEncryptor`。
- **数据库 schema 升级**：`BackupManagerImpl` 的 DTO↔Entity 转换必须与新 Entity 字段对齐；`currentSchemaVersion()` 自动取自 `AgentDatabase.SCHEMA_VERSION`。
- **格式变更**：优先在现有 tar 条目内扩展字段（`ignoreUnknownKeys`/默认值保证兼容），避免破坏旧备份导入；如引入新条目文件，需同步 `restoreFromTar` 的 `when` 分支。
- **测试建议**：构造「导出→导入→对比统计」的往返用例，覆盖加密/未加密、新旧格式、版本过高拒绝、口令错误、单会话导出等路径。
- **数据保全扩展**：
  - 哨兵新增判定维度（如按消息数而非会话数）时，先扩展 `RunMeta` 与 `SentinelLogic.evaluate`，同步更新 `SentinelLogicTest`；`DATA_LOST`/`PACKAGE_CHANGED` 的「不更新 lastRun」语义勿破坏（否则告警态会消失）。
  - 自动备份调整保留份数时，改 `AutoBackupManager.KEEP_MAX`，并同步 `BackupSection.AutoBackupKeepMax`（当前与 UI 文案硬编码一致）与用户文档 `backup-and-restore.md`。
  - 新增数据段接入自动备份：`AutoBackupManager` 走 `BackupManager.export(null, BackupOptions())` 全量语义，无需单独改动。
  - **外部安全备份（包名无关安全网）约束**：外部公共目录仅允许写入**签名密钥加密**内容（`SignatureKeyStore.signaturePassword()` 派生口令 + `BackupCrypto.encryptStream`），绝不允许明文落公共目录；外部落点统一走 `ExternalBackupStore`（API 29+ MediaStore / 更早版本公共目录文件），勿在别处直接写公共目录。若调整外部保留份数，同步改 `AutoBackupManager.KEEP_MAX` 并补 `excessExternalBackups` 单测。
  - **启动级全局告警**：哨兵判定由 `DataSafetyNotifier` 统一发布，MainActivity 的 `DataSafetyStartupAlert` 消费。新增「需要启动提醒」的判定时，改 `shouldShowStartupAlert` 并同步 `SentinelVerdict` 分支。
  - **禁止改 applicationId**：改动即被 D1/D3/CI 门禁拦截，如遇 rebrand 需求只允许改应用名/图标/namespace。
