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
| `presentation/BackupViewModel.kt` | 备份/还原的 UI 状态机（`BackupState`）与流式导入导出编排 |
| `presentation/BackupSection.kt` | 备份设置页 Compose UI：SAF 文件选择、数据范围勾选、口令输入、进度/结果弹窗 |

## 3. 核心架构与主流程

### 3.1 备份文件格式

统一产物为 **tar.gz**（未加密）或 **AES-GCM 加密的 tar.gz**（有口令）。tar 内：

| 条目 | 内容 |
| --- | --- |
| `metadata.json` | `BackupMetadata`（schemaVersion、appVersion、createdAt + 小表数据：providers/gitCredentials/remoteConnections/remoteMounts/mcpServers/globalPermissionRules + 应用设置） |
| `chatSessions.jsonl` | 会话大表（JSONL，逐行一条 DTO） |
| `messages.jsonl` | 消息大表（JSONL） |
| `todoItems.jsonl` | Todo 大表（JSONL） |
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
   - `metadata.json` → 解析并 `checkVersion`（备份 schema 高于当前版本直接拒绝）。
   - 各 `*.jsonl` → `restoreJsonl` 按行解析、每 `PAGE_SIZE` 条回调一次批量插入（upsert/insertAll）。
3. `restoreMeta` 还原小表与设置（provider/gitCredential/remote/mcp/permission 及各类设置 repository 的 `restore`），并返回 `RestoreStats` 汇总。
4. 异常归类：`BackupDecryptionException`（口令错误）与 `IllegalStateException` 原样抛出；其余包装为带用户可读提示的 `IllegalArgumentException`。

### 3.4 敏感字段加解密（DTO ↔ Entity）

- **AI Provider**：库内 `encryptedApiKey` ↔ 备份 `ProviderDto.apiKey`（明文）。SCHEMA 38 后 Entity 无 `apiKey/selectedModel` 列，`selectedModel` 为旧备份兼容字段，与 `defaultModel` 语义合并。
- **Git 凭据**：库内 `encryptedToken` ↔ 备份 `GitCredentialDto.token`（明文）。
- **远程连接**：`PASSWORD` 类型 `authData` 解密为明文密码导出、导入时重新加密；`PRIVATE_KEY` 类型 `authData` 是私钥路径本身（不加密），`passphrase` 加解密；旧数据解密失败时兜底按明文返回。

### 3.5 DAO 安全访问壳（`safeDao` / `safeDaoSuspend`）

所有 DAO 调用统一经过安全壳：Room 首次 query 触发 onOpen schema 校验，失败会抛 `IllegalStateException` 直接崩进程。安全壳捕获后记 `FileLogger` 并按 `failValue` 兜底返回，保证备份/导入流程失败不外溢到 UI 启动链。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `BackupManager.export(password, options, output)` | 全量导出；`output` 由调用方打开并负责关闭 |
| `BackupManager.exportSession(sessionId, output)` | 单会话导出（无密码） |
| `BackupManager.import(input, password): Result<RestoreStats>` | 流式还原，返回各段条目统计 |
| `BackupOptions` | 导出数据范围开关（providers / gitCredentials / remoteConnections / chatHistory / mcpServers / permissionRules / appSettings） |
| `BackupViewModel` | Hilt ViewModel，`BackupState`：`Idle / Working / ExportDone / ImportSuccess(stats) / Error(message)` |
| `BackupSection` | Compose 页面，通过 SAF `CreateDocument` / `OpenDocument` 与系统文件选择器交互 |

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
