# core 模块文档（公共基础层）

> 模块路径：`app/src/main/java/com/R/codecore/core/` + `di/` + 应用入口
> 维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

core 不是业务功能模块，而是**跨模块共享的基础设施层**：数据库迁移、安全加密、主题、日志/工具类、后台 Worker，以及 Hilt 依赖注入与应用启动入口。所有 feature 模块都依赖它，但它不依赖任何 feature 模块（单向依赖）。

## 2. 目录结构与职责

| 路径 | 职责 |
|---|---|
| `core/data/` | **数据注册表**（数据层重构「新写法」核心）：`DataRegistry`（备份/恢复/自动迁移的单一事实源）、`DataProvider`/`DataBlob`/`DataCategory`（数据域接口与载荷）、`TableDataProvider`（通用 Room 表转储）、`DataStoreDataProvider`（DataStore 目录转储）、`DataRegistryModule`（注册 5 域库 26 表 + DataStore） |
| `core/db/` | 数据库基础：`DbSplitMigrator`（旧单库 → 新 5 域库一次性移植）、`MigrationLoader`（老库文件驱动迁移，仅 LegacyAgentDatabase 读取旧数据用）、`RobustMigration44`、`LightweightSchemaRescue`、`CredentialEncryptionStateEntity` |
| `core/environment/` | **运行环境抽象**：`ExecutionEnvironment`（真机 / arm64 模拟器 / x86_64 模拟器 / 其它）+ `EnvironmentDetector`（宿主 ABI、模拟器探测、默认容器 profile 选择）——所有「真机绑定」适配的唯一入口（见 [emulator-support-design](../plan-docs/emulator-support-design.md)） |
| `core/security/` | 安全加密：`CredentialEncryptor`/`DEKManager`（数据加密密钥）、`HostKeyManager`（SSH host key）、`UserPasswordBackupCrypto`、`ZthSensitiveColumnCrypto`/`ZthSharedSyncKeyStore`、`CredentialEncryptionContract` |
| `core/theme/` | 主题与组件：`AIEditorTheme`、`AppComponents`、`CyberComponents` |
| `core/ui/` | UI 基础：`ImeInset`（软键盘 inset 处理） |
| `core/util/` | 工具类：`AILogger`/`FileLogger`（日志）、`ErrorUtils`、`EnumSafe`、`LanguageRegistry`、`LineDiff`、`LogLineParser` |
| `core/worker/` | WorkManager 任务：`AuditPurgeWorker`（审计清理）、`CredentialRotationWorker`（凭据轮换）、`V1toV2MigrationWorker` |
| `di/` | Hilt 模块：`AgentModule`、`BackupModule`、`RepositoryModule` |
| `AIEditorApp.kt` / `MainActivity.kt` | 应用入口：初始化核心服务、启动导航 |

## 3. 核心架构与主流程

- **数据库迁移**：数据层已按域拆库（新写法），5 个 v1 全新域库无迁移链；旧单巨库（`LegacyAgentDatabase` v49）由 `DbSplitMigrator` 启动时一次性逐表移植（幂等、只跑一次、旧文件改名留底）。`MigrationLoader` 文件驱动迁移仅保留用于 `LegacyAgentDatabase` 读取旧数据场景，迁移 SQL 放 `app/src/main/assets/migrations/{VERSION}_description.sql`。⚠️ 迁移文件按 `;` 切分语句，SQL 字面量中不得出现 `;`（用 `char(59)`）。
- **数据注册表**：`DataRegistry` 枚举全应用数据域（Room 表 + DataStore 目录），提供 `snapshotAll/restoreAll/pack/unpack`；`TableDataProvider` 用 `PRAGMA table_info` 动态转储任意表（JSONL，BLOB 走 base64），新增表无需专用 DTO。备份/恢复/自动迁移统一经注册表全量覆盖（见 backup 模块文档）。
- **运行环境抽象**：`EnvironmentDetector` 在启动/首次使用时探测宿主 ABI（`arm64-v8a` / `x86_64`）与模拟器信号（fingerprint/product/qemu），导出 `hostIsArm64` / `hostIsX86_64` / `containerRunnable` / `defaultProfileId()`；被 `ContainerSettingsRepository`（默认容器 profile）、`ContainerInstaller`（proot 架构）、`LinuxContainerEngine`（容器启动）统一消费。探测仅用于**适配与降级**，绝不用于授权/安全判断。
- **安全体系**：凭据/敏感字段经 `CredentialEncryptor` 加密落库，密钥由 `DEKManager` 管理；ZTH 敏感列走 `ZthSensitiveColumnCrypto`。`HostKeyManager` 管理 SSH host key 校验。
- **启动链路**：`AIEditorApp` 初始化 `FileLogger`、`TerminalKeepaliveService`、`McpManager` 等核心服务；`MainActivity` 承载 Compose 导航。
- **后台任务**：`core/worker` 的 WorkManager 任务负责审计日志清理、凭据轮换、V1→V2 迁移等周期/一次性工作。

## 4. 对外接口与集成点

- `MigrationLoader` / `DbSplitMigrator`：前者被 `LegacyAgentDatabase`（旧数据读取）调用；后者被 `di/DatabaseModule` 各域库 provider 在构建前调用（幂等一次性移植）。
- `DataRegistry`：被 `feature/backup` 的 `BackupManagerImpl`（全量备份/恢复）与 `DataSafetyNotifier`（无感自动迁移）调用。
- 加密组件：被 `credentials`、`settings`、`backup`、`agent`（ZTH）等模块调用，是敏感数据落库的唯一加解密入口。
- `FileLogger`/`AILogger`：全 App 日志统一出口。
- `di/`：各 feature 模块的 Hilt `@Module` 在此汇总绑定 Repository/UseCase。
- 主题组件：被所有 feature 的 UI 复用。

## 5. 关键设计点与约束

- **单向依赖**：core 不依赖 feature，保证基础设施可独立演进与测试。
- **密钥管理**：`DEKManager` 统一管理数据加密密钥，避免散落；凭据轮换由 `CredentialRotationWorker` 周期执行。
- **迁移纪律**：新库 v1 起无历史迁移链，改表无需迁移 SQL；仅 `LegacyAgentDatabase`（旧数据读取）继续走 `MigrationLoader` 文件迁移，禁止在代码里直接改表。
- **注册表纪律**：新增 Room 表必须登记进 `core/data/DataRegistryModule`，否则备份/恢复/自动迁移不覆盖；DataStore 走目录级转储，无需逐项登记。

## 6. 维护与扩展指引

- 新增跨模块公共能力（加密、日志、工具类、Worker）→ 放入对应 `core/` 子包，并在本文档登记。
- 新增 Hilt 绑定 → 放入 `di/` 对应 Module。
- 数据库变更 → 遵循「数据库迁移」纪律（见 §5）。
