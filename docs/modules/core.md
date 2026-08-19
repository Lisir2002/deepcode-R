# core 模块文档（公共基础层）

> 模块路径：`app/src/main/java/com/R/codecore/core/` + `di/` + 应用入口
> 维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

core 不是业务功能模块，而是**跨模块共享的基础设施层**：数据库迁移、安全加密、主题、日志/工具类、后台 Worker，以及 Hilt 依赖注入与应用启动入口。所有 feature 模块都依赖它，但它不依赖任何 feature 模块（单向依赖）。

## 2. 目录结构与职责

| 路径 | 职责 |
|---|---|
| `core/db/` | 数据库基础：`MigrationLoader`（自定义文件驱动迁移）、`RobustMigration44`、`LightweightSchemaRescue`、`CredentialEncryptionStateEntity` |
| `core/security/` | 安全加密：`CredentialEncryptor`/`DEKManager`（数据加密密钥）、`HostKeyManager`（SSH host key）、`UserPasswordBackupCrypto`、`ZthSensitiveColumnCrypto`/`ZthSharedSyncKeyStore`、`CredentialEncryptionContract` |
| `core/theme/` | 主题与组件：`AIEditorTheme`、`AppComponents`、`CyberComponents` |
| `core/ui/` | UI 基础：`ImeInset`（软键盘 inset 处理） |
| `core/util/` | 工具类：`AILogger`/`FileLogger`（日志）、`ErrorUtils`、`EnumSafe`、`LanguageRegistry`、`LineDiff`、`LogLineParser` |
| `core/worker/` | WorkManager 任务：`AuditPurgeWorker`（审计清理）、`CredentialRotationWorker`（凭据轮换）、`V1toV2MigrationWorker` |
| `di/` | Hilt 模块：`AgentModule`、`BackupModule`、`RepositoryModule` |
| `AIEditorApp.kt` / `MainActivity.kt` | 应用入口：初始化核心服务、启动导航 |

## 3. 核心架构与主流程

- **数据库迁移**：采用自定义轻量级文件驱动迁移（`MigrationLoader`），迁移 SQL 放 `app/src/main/assets/migrations/{VERSION}_description.sql`，启动时自动按版本执行并记录 `migration_history`。⚠️ 迁移文件按 `;` 切分语句，SQL 字面量中不得出现 `;`（用 `char(59)`）。
- **安全体系**：凭据/敏感字段经 `CredentialEncryptor` 加密落库，密钥由 `DEKManager` 管理；ZTH 敏感列走 `ZthSensitiveColumnCrypto`。`HostKeyManager` 管理 SSH host key 校验。
- **启动链路**：`AIEditorApp` 初始化 `FileLogger`、`TerminalKeepaliveService`、`McpManager` 等核心服务；`MainActivity` 承载 Compose 导航。
- **后台任务**：`core/worker` 的 WorkManager 任务负责审计日志清理、凭据轮换、V1→V2 迁移等周期/一次性工作。

## 4. 对外接口与集成点

- `MigrationLoader`：被数据库层（`AgentDatabase` 及 feature 各 DAO 迁移）调用。
- 加密组件：被 `credentials`、`settings`、`backup`、`agent`（ZTH）等模块调用，是敏感数据落库的唯一加解密入口。
- `FileLogger`/`AILogger`：全 App 日志统一出口。
- `di/`：各 feature 模块的 Hilt `@Module` 在此汇总绑定 Repository/UseCase。
- 主题组件：被所有 feature 的 UI 复用。

## 5. 关键设计点与约束

- **单向依赖**：core 不依赖 feature，保证基础设施可独立演进与测试。
- **密钥管理**：`DEKManager` 统一管理数据加密密钥，避免散落；凭据轮换由 `CredentialRotationWorker` 周期执行。
- **迁移纪律**：任何数据库 schema 变更必须走 `MigrationLoader` 文件迁移，禁止在代码里直接改表。

## 6. 维护与扩展指引

- 新增跨模块公共能力（加密、日志、工具类、Worker）→ 放入对应 `core/` 子包，并在本文档登记。
- 新增 Hilt 绑定 → 放入 `di/` 对应 Module。
- 数据库变更 → 遵循「数据库迁移」纪律（见 §5）。
