# Credentials（凭据）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/credentials/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

负责 R-CodeCore 的 **Git 远程仓库凭据（host + username + token）管理**与**提交署名/仓库地址配置**，并承担「容器内 git 缺凭据时向 App 请求回填」的文件 IPC 桥。

三件事，一条链：

1. **凭据 CRUD**：Room 表 `git_credentials` 存每 host 每账号一条凭据，token 以 Android Keystore AES-256-GCM 加密；支持同 host 多账号 + `isDefault` 默认条。
2. **落盘共享**：`GitCredentialsFileSync` 把 Room 凭据写成容器 `credential.helper=store` 格式文件，UI / 终端 / AI Bash 三端 git 共用同一份凭据。
3. **缺凭据弹窗**：容器内自定义 credential helper 与 App 之间的文件 IPC 桥（`CredentialRequestBridge`），任意页面收到请求即弹全局对话框回填，回填后存库 + 落盘 + 喂回 git 自动续跑。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/local/dao/GitCredentialDao.kt` | `git_credentials` 表 Room DAO：CRUD、`findByHost`（默认优先）、`clearDefaultForHost`、切换 default |
| `data/local/entity/GitCredentialEntity.kt` | 凭据实体：`host`（小写）、`username`、`encryptedToken`（唯一密文存储）、`label`、`isDefault`、时间戳（Ms 后缀） |
| `data/repository/CredentialRepositoryImpl.kt` | `CredentialRepository` 实现：Entity↔Domain 转换、token 加解密、host 归一小写、default 唯一性 |
| `data/CredentialRequestBridge.kt` | 自定义 git credential helper 与 App 的**文件 IPC 桥**：FileObserver 监听 + 兜底轮询捕获 `cred-req-*`，`respond`/`cancel` 写 `cred-resp-*` |
| `data/GitCredentialsFileSync.kt` | 把 Room 凭据落盘到容器持久挂载 `git-credentials` 文件（git-credential-store 格式，原子写） |
| `domain/model/GitCredential.kt` | 凭据领域模型 + `newCredentialId()` 生成器（时间戳+随机后缀） |
| `domain/repository/CredentialRepository.kt` | 凭据仓储接口：`getAll` / `findForHost`（默认优先）/ `save` / `delete` / `setDefault` |
| `presentation/CredentialViewModel.kt` | 凭据页 UI 编排：凭据 CRUD + 提交署名（user.name/email）与仓库地址配置，Room Flow 与 git config 非反应式状态合并 |
| `presentation/component/CredentialEditorScreen.kt` | `CredentialEditorSheet`（编辑/新增 BottomSheet）+ 全屏 `CredentialEditorScreen` |
| `presentation/component/CredentialListSection.kt` | 凭据列表 + 默认条标记 + 编辑入口，内嵌 `GitUserIdentityCard` |
| `presentation/component/GitUserIdentityCard.kt` | 提交署名（user.name/email）与仓库地址编辑卡片 |
| `presentation/component/CredentialPromptDialog.kt` | 拉取/推送缺凭据的统一登录弹窗（host 只读预填，username/token 双非空才可提交） |
| `presentation/component/GlobalCredentialDialogHost.kt` | 全局弹窗宿主：订阅 `CredentialRequestBridge.request`，非 null 即渲染 `CredentialPromptDialog`（挂在应用根，覆盖所有页面） |

## 3. 核心架构与主流程

### 3.1 凭据 CRUD 主流程

```
CredentialEditorSheet / CredentialListSection / GitScreen 凭据页
  → CredentialViewModel（saveCredential / deleteCredential / setDefault）
    → CredentialRepositoryImpl
      → GitCredential.toEntity()  // token 经 CredentialEncryptor 加密 → encryptedToken
      → GitCredentialDao.upsert(REPLACE)  // isDefault=true 时先 clearDefaultForHost 保证同 host 内唯一
      → GitCredentialsFileSync.syncAll()   // 同步落盘，UI/终端/AI 三端共享
```

- 读取：`getAll()` Flow 解密 token 供 UI 回显；`findForHost(host)` 优先返回该 host 默认凭据，无默认回退任意一条。
- **host 归一小写**：保存与查询都 `trim().lowercase()`，保证匹配一致。
- 提交署名：`saveUserIdentity` 调 `GitRepository.setUserIdentity`（优先项目级、无则全局）与 `setRepoUrl`（仅 local），UI 与终端 `git config` 读到同一份 `.gitconfig`，无两套写入路径竞争。

### 3.2 凭据落盘同步（GitCredentialsFileSync）

- 宿主目录 `filesDir/rcodecore` = 容器内 `/root/.rcodecore`（`LinuxContainerEngine` 的 `-b` 绑定，跨 rootfs 升级不丢）。
- 每 host 取默认条（无默认回退首条）按 `git-credential-store` 格式写 `git-credentials` 文件：`https://<urlencoded user>:<urlencoded token>@<host>` 每行一条；凭据为空时写空文件（不删文件，让 git 知无凭据）。
- 原子写：先 `.tmp` 再 rename，避免容器侧/git 读到半截文件。
- **只写凭据文件，不写 `.gitconfig`**：`.gitconfig` 的 `[credential] helper` 由容器 provision 时 `git config --global` 写、`[user]` 署名由 git 命令维护——真源就是 `.gitconfig` 本身。

### 3.3 缺凭据弹窗链路（CredentialRequestBridge）

```
容器内 git 缺凭据 → credential helper（/root/.rcodecore/git-credential-rcodecore）
  写 cred-req-<id>（含 host=）→ 宿主目录（PRoot -b 同 inode）
→ FileObserver（主线程 startWatching）+ fallbackPollLoop（1s 兜底，seen 去重）
→ handleRequest：解析 host → containerEngine.incPromptInFlight() → request StateFlow
→ GlobalCredentialDialogHost（应用根）弹 CredentialPromptDialog
→ respond(requestId, host, username, token)：
    写 cred-resp-<id> 明文 KV（原子写）→ 存 Room 默认条 → fileSync.syncAll() → decPromptInFlight() → 清 request
  cancel(requestId)：
    写 cancel=1 响应 → decPromptInFlight() → 清 request（helper 退出非零让 git 报认证失败）
→ helper 轮询取走响应喂回 git → git 自动续跑
```

关键机制：

- **在途计数**：`incPromptInFlight`/`decPromptInFlight` 配合 `LinuxContainerEngine.launchKillWatchdog`，用户在途填凭据时 git 命令不被 120s 超时强杀。
- **双通道捕获**：FileObserver 在部分机型绑定目录 inotify 失效，故另起低频兜底轮询；两者按 `requestId` 经带容量上限的 `LinkedHashSetWithCap` 去重（SEEN_CAP=64）。
- **启动清理**：`start()` 时清理上次会话残留的 `cred-req/cred-resp`，避免误触发弹窗。
- **生命周期**：`@Singleton`，由 `AIEditorApp` 主线程调 `start()`（FileObserver 必须主线程创建）。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `CredentialRepository` | 凭据仓储，被 `CredentialViewModel`、`CredentialRequestBridge.respond`、备份模块、`AIEditorApp`（启动兜底）消费 |
| `CredentialRequestBridge` | `request` StateFlow 被 `GlobalCredentialDialogHost` 订阅；`AIEditorApp` 主线程调 `start()`；`LinuxContainerEngine` 用它 inc/dec 在途计数 |
| `GitCredentialsFileSync` | `syncAll()` 被 `AIEditorApp`（启动兜底）、`CredentialViewModel`（增删改后）、`GitViewModel`（弹窗保存后）、`CredentialRequestBridge.respond` 调用 |
| `CredentialViewModel` | 被 `GitScreen` 注入（凭据列表 + 署名卡片）；`saveUserIdentity` 委托给 git 模块的 `GitRepository` |
| `GitRepository` | 依赖（跨模块）：读写署名与仓库地址 |

## 5. 关键设计点与约束

- **token 仅密文存储**：RC68 SCHEMA 38 删除明文 token 列，只存 `encryptedToken`；解密失败回退空串不崩 UI。加密失败（极少数）时 token 落库空串并记日志。
- **同 host 默认唯一**：`save`/`setDefault` 切换前先 `clearDefaultForHost`，保证 `isDefault` 在 host 内最多一条。
- **原子写**：响应文件与凭据文件都先 `.tmp` 后 rename，防止 helper / git 读到半截内容。
- **文件所有权纪律**：app 只写 `cred-resp-*`（响应），请求文件与响应文件的删除由 helper 自理（helper 知道自己何时结束），避免双方抢删。
- **冷启动安全**：凭据文件同步/文件 IPC 都在 IO 协程内，不在冷启动 Flow 上触发加密子系统初始化（与 settings 模块的 SSH 配置同原则）。
- **三端共用单一凭据源**：凭据真源是 Room（`git_credentials` 表），`git-credentials` 文件是其投影；UI/终端/AI 不各存一份，避免漂移。

## 6. 维护与扩展指引

- **新增凭据字段**：同步 `GitCredentialEntity`（注意 `@ColumnInfo(defaultValue)` 与迁移一致）、`GitCredential`、`CredentialRepositoryImpl.toEntity/toDomain`、备份 DTO。
- **数据库 schema 升级**：`git_credentials` 相关迁移须与 Entity/DAO 对齐（历史：RC68 SCHEMA 38 删除明文 token 列、时间戳加 Ms 后缀）。
- **新增 git 协议支持**：若引入 ssh 凭据，需在 `CredentialRequestBridge`（helper 协议字段）与 `GitCredentialsFileSync`（store 格式仅 https）分别适配。
- **helper 脚本变更**：请求/响应文件名前缀（`cred-req-`/`cred-resp-`）与清理/轮询逻辑必须与容器内 helper 脚本保持一致，否则链路断裂。
- **测试建议**：覆盖 FileObserver 失效时兜底轮询、双通道去重、在途计数与 watchdog 联动、响应原子写、同 host 多账号默认切换后落盘文件内容、cancel 路径（git 报认证失败）。
