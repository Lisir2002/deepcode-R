# 工作区（Workspace）模块文档

> 模块路径：`app/src/main/java/com/core/deepcode/feature/workspace/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

工作区模块管理 App 的「工作区/项目」概念及其配套能力，覆盖四块功能：

1. **工作区管理**：本地模式下所有项目位于内部私有 ext4 目录 `filesDir/projects/<name>`；远程模式下工作区为 SSH 服务器 `remoteWorkspacePath` 下的子文件夹。支持列列表/新建/删除/**重命名**/切换，当前选中工作区持久化于 DataStore。工作区是 AI 文件工具与命令执行的根范围，切换即切换 AI 操作范围。侧边栏「工作目录」页内嵌「当前工作台 / 所有工作台」两个子 tab：前者浏览当前工作区文件树（点击文件跳转独立阅读页，退出阅读页自动重开侧边栏），后者做工作区列表管理——**点击工作区弹出下拉菜单**（切换 / 重命名 / 删除 / 查看对话绑定，其中「查看对话绑定」以手风琴展开该工作区绑定的会话）。
2. **文件访问抽象**：`FileAccessProvider` 把「在哪读写文件」从硬编码的 `java.io.File` 解耦，本地（`LocalFileAccess` + `WorkspacePathMapper`）与远程（`RemoteSftpFileAccess`）两套实现由 `DelegatingFileAccess` 按执行模式转发，AI 的文件类工具统一走容器路径（`~/workspace/...`）。
3. **路径映射**：`WorkspacePathMapper` 在「容器内路径」与「宿主真实路径」之间互转（`~/workspace` ↔ 工作区、`/root/.deepcode` ↔ AI 配置目录、其它容器绝对路径 ↔ rootfs），对 AI 只暴露容器路径。
4. **远程连接/挂载与文件同步**：Room 持久化远程连接（SFTP/FTP/LOCAL）与挂载点；`RemoteRepository` 编排 `SyncEngine` + 三种 `RemoteSyncClient`，对挂载目录做增量监听同步/全量上传下载；内置 `FtpServerManager`（Apache FtpServer）把工作区共享为 FTP 服务；`RemoteAuditLogRepository` 记录连接/凭据/同步等审计事件。另通过 SAF `WorkspaceDocumentsProvider` 把私有目录暴露给系统文件管理器。

**核心架构原则**：以「执行模式（本地/远程 SSH）」为路由键，文件访问与终端会话采用一致的委托模式；`~/workspace` 容器路径作为 AI 侧唯一可见路径，物理位置与外部可见性（SAF）解耦。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/repository/WorkspaceRepository.kt` | 工作区核心仓库（@Singleton）：扫描/新建/删除/**重命名**/切换，DataStore 持久化当前选中；本地扫 `filesDir/projects`，远程走 SSH exec（`ls -d */` / `mkdir -p` / `rm -rf` / `mv`）；**重命名成功后经 `ChatSessionDao.updateWorkspacePath` 把绑定该工作区的会话路径批量迁移到新路径** |
| `data/local/dao/RemoteConnectionDao.kt` | Room DAO：远程连接 + 挂载两张表（含凭据更新、按连接查挂载、批量插入） |
| `data/local/dao/RemoteMountDao.kt` | 独立挂载表 DAO（从 ConnectionDao 拆分，职责单一） |
| `data/local/dao/RemoteAuditLogDao.kt` | 审计日志 DAO：分页/按连接/多维筛选/清理/计数/distinct 分类 |
| `data/local/dao/CredentialEncryptionStateDao.kt` | 凭据加密状态单行 upsert（id=1），支撑加密方案迁移 |
| `data/local/entity/RemoteConnectionEntity.kt` | `remote_connections` 表：协议/主机/端口/用户名/`authType`（PASSWORD/PRIVATE_KEY）/加密后的 `authData`/`passphrase` |
| `data/local/entity/RemoteMountEntity.kt` | `remote_mounts` 表：挂载点，外键 CASCADE 关联连接，`connectionId` 索引 |
| `data/local/entity/RemoteAuditLogEntity.kt` | `remote_audit_logs` 表：分类/动作/连接快照/成功标志/脱敏主机/时间 |
| `data/provider/WorkspaceDocumentsProvider.kt` | SAF DocumentsProvider：单一根暴露 `projects/` 与 `deepcode/`，全链路沙箱校验，异常统一转 `FileNotFoundException` |
| `domain/model/Workspace.kt` | `Workspace(name, path)` 工作区模型 |
| `domain/model/RemoteModels.kt` | `RemoteProtocol`（SFTP/FTP/LOCAL）、`RemoteConnection`、`RemoteMount` 领域模型 |
| `domain/FileAccessProvider.kt` | 文件读写抽象接口 + `FileEntry`（目录条目含权限/本地 File 引用） |
| `domain/DelegatingFileAccess.kt` | `FileAccessProvider` 委托层：按执行模式转发到本地/远程实现 |
| `domain/LocalFileAccess.kt` | 本地实现：包一层 `WorkspacePathMapper` 走 `java.io.File` 直读 |
| `domain/RemoteSftpFileAccess.kt` | 远程实现：**用 SSH exec 而非 SFTP**（规避 sshj 0.38.0 SFTP Buffer 溢出 bug #461），`cat`/重定向读文本、`base64` 中转二进制 |
| `domain/WorkspacePathMapper.kt` | 容器路径 ↔ 宿主路径互转（profile 感知 rootfs），统一文件工具入参/回显 |
| `domain/remote/RemoteSyncClient.kt` | 同步协议抽象 + `RemoteFileInfo` + `RemoteAuth`（Password/PrivateKey 密封类） |
| `domain/remote/ftp/FtpServerManager.kt` | 内置 FTP 服务端（Apache FtpServer）：端口/账号/匿名/自启配置持久化，状态 StateFlow |
| `domain/remote/ftp/FtpSyncClient.kt` | FTP 协议的 `RemoteSyncClient` 实现（Apache Commons Net） |
| `domain/remote/sftp/SftpSyncClient.kt` | SFTP 协议的 `RemoteSyncClient` 实现（sshj SFTPClient，支持私钥） |
| `domain/remote/local/LocalSyncClient.kt` | 把远程同步适配为本地目录镜像（host 字段作镜像根，单向复制） |
| `domain/remote/SyncEngine.kt` | 同步引擎：FileObserver 增量监听 + Channel 防抖批量、全量上传/下载、忽略规则（自定义 + .gitignore）、失败重试/强制重连 |
| `domain/repository/RemoteRepository.kt` | 远程连接/挂载仓库：凭据加解密（CredentialEncryptor）、协议分发创建 `SyncEngine`、连接/断开/强制上传下载/测试连接/列远端目录 |
| `domain/repository/RemoteAuditLogRepository.kt` | 审计日志写入/查询/导出 JSON/保留策略（10000 条 / 90 天） |
| `domain/RemoteAuditCategory.kt` | 审计分类与动作常量（CONNECT/CREDENTIAL/BACKUP/SYNC/SECURITY 等） |
| `presentation/WorkspaceViewModel.kt` | 工作区选择页 VM：列表/当前状态，初始化与模式切换重载 |
| `presentation/WorkspaceFileViewModel.kt` | 侧边栏「工作目录 → 当前工作台」文件浏览器 VM：目录导航栈（相对容器路径）+ 经 `DelegatingFileAccess` 列文件（本地/远程一致），切换工作区自动复位到根目录 |
| `presentation/FileReaderViewModel.kt` | 独立文件阅读页 VM：经 `DelegatingFileAccess` 读取容器路径文件文本，携带加载/错误状态 |
| `presentation/component/FileReaderScreen.kt` | 独立文件阅读页 UI：`AppTopAppBar` 返回栏 + 等宽字体内容，加载/错误/空态处理 |
| `presentation/remote/RemoteServerViewModel.kt` | 远程服务器页 VM：连接/挂载列表、自动连接、同步配置、FTP 服务 |
| `presentation/remote/RemoteServerScreen.kt` / `RemoteDialogs.kt` / `RemoteFtpSection.kt` / `RemoteSyncAndCards.kt` | 远程服务器页 UI、连接/挂载弹窗、FTP 配置区、同步设置与挂载卡片 |

## 3. 核心架构与主流程

### 3.1 工作区生命周期（`WorkspaceRepository`）

- `initialize()`：远程模式先等 SSH 连接就绪（CONNECTED/FAILED，最多 5s）；刷新列表；本地模式首次启动自动建 `default` 工作区；从 DataStore 恢复当前选中；远程模式切换后 `updateWorkspaceSymlink` 让 Bash 的 `~/workspace` 指向当前工作区。
- 本地：`filesDir/projects/<name>`（**必须是 ext4**——emulated/FUSE 存储拒绝 `symlink()`，npm/pnpm/git 建软链会 `EACCES`），对外可见性交给 SAF Provider 补回。
- 远程：列表用 SSH exec `ls -d .../*/ | xargs basename`（刻意不用 SFTP，规避 sshj Buffer bug）；新建/删除走 exec `mkdir -p` / `rm -rf`（`shellQuote` 单引号转义防注入）；**重命名走 exec `mv`，成功后把绑定该工作区的会话路径批量迁移（`ChatSessionDao.updateWorkspacePath`），并同步更新持久化的当前选中名**。
- `currentPath()` 是本地/远程统一的「当前工作区绝对路径」出口，供终端会话与文件工具取根。

### 3.2 文件访问委托（`DelegatingFileAccess`）

AI 的文件工具（`FileTools`/`EditFileTool`/`ListFilesTool`/`ImageTools`/`SendFileTool` 等）依赖 `FileAccessProvider` 接口，由 DI 注入 `DelegatingFileAccess`，每次方法调用按 `ExecutionModeHolder.currentMode()` 实时转发（与注入时机解耦）：
- 本地 → `LocalFileAccess`：`WorkspacePathMapper.toHostFile(path)` → `java.io.File`。
- 远程 → `RemoteSftpFileAccess`：`toRemotePath` 把 `~/workspace`/`$HOME/workspace` 映射到当前工作区根、其它绝对路径直用；文本 `cat` 读写、二进制 `base64` 中转、目录 `stat -c '%n|%F|%s|%Y|%A'` 批量列出；失败抛 `NoSuchFileException`/`FileAlreadyExistsException` 对齐本地语义。

### 3.3 路径映射（`WorkspacePathMapper`）

`toHostFile`（AI 路径 → 宿主文件）按优先级匹配：
1. `~/workspace` / `$HOME/workspace` → 宿主工作区根（bind mount）；
2. `/root/.deepcode` → 宿主 AI 配置目录（skills/mcp.json，独立于 rootfs，**必须先于通用 `/` 规则匹配**，否则落到 rootfs 临时副本升级即丢）；
3. 其它 `/xxx` → 当前 profile 的 rootfs 对应文件；
4. 相对路径 → 挂到工作区根下。

`toContainerPath` 反向还原。rootfs 目录随 `ContainerSettingsRepository.activeProfileIdFlow` 感知当前 profile（内置 Alpine / x86 / 自定义）。

### 3.4 远程连接、挂载与同步

- 连接/挂载存 Room；凭据用 Android Keystore AES-256-GCM 加密（`CredentialEncryptor`），解密失败回退旧明文兼容老数据；`authType` 大小写新旧兼容（rc60 前小写 `password`/`key`，rc60+ 大写）。
- `RemoteRepository.connectMount`：按协议实例化 `SftpSyncClient`（带 HostKeyVerifier）/`FtpSyncClient`/`LocalSyncClient` → `connect` → 构造 `SyncEngine` → `startWatching()`（增量监听）；LOCAL 协议额外 `uploadWorkspace()`。
- `SyncEngine`：
  - `startWatching`：FileObserver 递归监听本地镜像目录（CREATE/MODIFY/DELETE/MOVED），事件经 `syncChannel`（UNLIMITED）防抖批量（`maxSyncBatchSize`），`Set` 去重；子目录动态增监听。
  - `handleLocalChange`：存在→建目录/上传，不存在→删除；失败 `forceReconnect()` + 最多重试 3 次。
  - `downloadWorkspace`/`uploadWorkspace`：递归全量拉取/推送，忽略规则（自定义 + `.gitignore` 适度匹配：`*.ext`/`**`/多段/非锚定保守处理）。
  - `shutdown`：停监听 + 取消协程。

### 3.5 内置 FTP 服务（`FtpServerManager`）

用 Apache FtpServer 在 App 内起 FTP 服务，共享目录为 `filesDir/projects`；支持普通用户 + 匿名、写权限 + 并发限制、端口/账号/密码/自启配置持久化；暴露 `serverUrl`（`ftp://<本机IP>:<port>`）与 `isRunning` StateFlow；保存配置时若在运行先停再启。

### 3.6 SAF 外部可见性（`WorkspaceDocumentsProvider`）

单一根 = app 私有 `filesDir`，根下仅暴露 `projects/` 与 `deepcode/`（其余 rootfs/DB 刻意隐藏）。三条铁律：
1. `onCreate` 先独立初始化 `FileLogger`（Provider 生命周期早于 Application.onCreate）；
2. 所有 SAF 入口过 `providerSafe{}`，非 SAF 契约异常统一转 `FileNotFoundException`（Provider 抛 Runtime 直接杀进程）；
3. `exposedChildren` 热路径绝不做 asset IO（docs 由 Application 后台协程异步提取）。

所有以绝对路径作 docId 的访问过 `sandboxedFile` 全链路沙箱：canonicalize + 限定 filesDir 内 + 白名单子目录 + 写意图二次校验；`createDocument` 防路径穿越（displayName 禁 `/`、`.`、`..`），`deleteDocument` 禁止删根与白名单顶层。

### 3.7 审计日志（`RemoteAuditLogRepository`）

统一 `append` 入口（message ≤500 截断），覆盖连接/凭据/备份/同步/安全/技能执行等事件；分页/按连接/多维筛选查询；JSON 脱敏导出；每 50 条检查一次保留上限（10000 条 / 90 天，`enforceRetentionIfNeeded`）。

## 4. 对外接口与集成点

| 接口 / 类 | 消费者 | 说明 |
| --- | --- | --- |
| `FileAccessProvider`（实现：`DelegatingFileAccess`） | `agent/domain/tool/file/FileTools.kt`、`editor/EditFileTool.kt`、`explorer/ListFilesTool.kt`、`file/ImageTools.kt`、`file/SendFileTool.kt`、`container/CheckEnvironmentTool.kt`、`checkpoint/CheckpointManager.kt`、`di/AgentModule.kt` | AI 读写远程/本地文件统一入口 |
| `WorkspaceRepository` | `TerminalSessionManager`、`RemoteTerminalSessionManager`、`RemoteSftpFileAccess`、`WorkspacePathMapper`、两个 ViewModel | 当前工作区路径 / 生命周期 |
| `RemoteRepository` | `RemoteServerViewModel`、冷启动（AIEditorApp）、`SettingsViewModel` | 连接/挂载 CRUD、同步编排、凭据解析 |
| `SyncEngine` | `RemoteRepository` | 挂载同步 |
| `FtpServerManager` | `RemoteServerViewModel` | 内置 FTP 服务 |
| `WorkspaceDocumentsProvider` | Android 系统文件管理器 / SAF 选择器（Manifest 注册的 ContentProvider） | 私有目录对外可见 |
| `ExecutionModeHolder` | 委托层 | 本地 vs 远程路由 |
| `WorkspacePathMapper` | `LocalFileAccess`、工具层 | 容器路径 ↔ 宿主路径 |
| `WorkspaceViewModel` / `WorkspaceFileViewModel` | `agent/presentation/component/ChatDrawer.kt`（侧边栏「工作目录」子 tab） | 工作区列表管理（所有工作台：下拉菜单切换/重命名/删除/查看对话绑定）+ 当前工作区文件树浏览（当前工作台）；「查看对话绑定」经 `AIAgentViewModel.sessionsBoundToWorkspace(path)` 查询 |
| `FileReaderScreen` + `FileReaderViewModel` | `MainActivity` 路由 `file_reader/{filePath}`（路径 `Uri.encode` 传入） | 侧边栏点击文件跳转独立阅读页；退出后自动重开侧边栏并保留所在 tab |

## 5. 关键设计点与约束

- **容器路径对 AI 可见**：统一用 `~/workspace`，物理位置（ext4 私有目录）与外部可见性（SAF）解耦；`filesDir` 必须保持 ext4 以获得 symlink 支持。
- **远程文件访问不用 SFTP**：sshj 0.38.0 的 SFTP 实现有 Buffer 溢出 bug（issue #461，Android 必现 `ArrayIndexOutOfBoundsException`），`RemoteSftpFileAccess` 与 `WorkspaceRepository` 的远程列表/新建/删除统一走 SSH exec；仅 `SftpSyncClient`（独立连接）用 SFTP 同步。
- **凭据安全**：存库一律 Keystore AES-256-GCM 加密；`RemoteConnection.password` 仅在 `RemoteAuth` 构造处解密，Domain 层不暴露私钥 passphrase；解密失败回退旧明文保证老用户升级不失效。
- **同步稳健性**：Channel 防抖批量 + Set 去重 + 失败强制重连 + 3 次重试 + 批量上限后 300ms 缓冲；上传/下载文件间 50ms 延时防压垮服务器。
- **Provider 稳定性**：SAF Provider 所有异常转 `FileNotFoundException`，热路径禁 asset IO，避免 Provider 崩溃杀进程。
- **路径安全**：`shellQuote` 单引号转义远程命令参数；`LocalSyncClient.resolve` 与 `sandboxedFile` 都做越界/越权校验。

## 6. 维护与扩展指引

- **新增工作区存储后端**：改 `WorkspaceRepository` 的 `refreshLocalWorkspaces`/`refreshRemoteWorkspaces` 与 `createWorkspace`/`deleteWorkspace` 分支；`currentPath()` 是统一出口，勿破坏其语义。
- **新增文件工具能力**：在 `FileAccessProvider` 接口加方法 → 必须同步实现 `LocalFileAccess` 与 `RemoteSftpFileAccess` 两处，委托层自动转发；二进制走 `base64` 中转要保持对称。
- **新增同步协议**：实现 `RemoteSyncClient` 并在 `RemoteRepository.connectMount`/`testConnection`/`listRemoteDirectories` 三处分发点按 `RemoteProtocol` 注册。
- **审计埋点**：优先复用 `RemoteAuditCategory`/`RemoteAuditAction` 常量，经 `RemoteAuditLogRepository.append` 写入，勿硬编码字符串。
- **修改暴露目录**：同步更新 `WorkspaceDocumentsProvider.exposedChildren()` 白名单与 `sandboxedFile` 规则（改根下子目录名是安全敏感变更，需谨慎）。
- **涉及本模块的行为变更**：更新 `docs/modules/workspace.md` 的对应小节。

## 7. 版本演进记录

> 本模块开发维度演进；用户可见变更见仓库根 [CHANGELOG.md](../../CHANGELOG.md)。

- **v0.2.0（2026-08-25）**：工作区入口迁移到侧边栏（「工作目录」tab，支持文件浏览与独立阅读页）。
- **v0.1.0（早期）**：工作区与文档管理核心落地（本地/远程工作区、SAF 暴露、内置 FTP 服务、远程 SSH exec/SFTP 文件访问、同步引擎、审计日志）。
