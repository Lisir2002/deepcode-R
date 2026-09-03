# data-layer-refactor-design

> 评审状态：📝 草案（方向已定：全部一次性 / 每模块一个 DataStore / 按域拆库）
>
> 主题：数据层重构（「新写法」）——以现有数据层（「旧版写法」）为参照，收敛碎片化存储、统一持久化注册与备份/恢复链路，实现「数据库不被任何外部因素影响 + 包变更/升级无感自动迁移」。
>
> 关联问题：日志显示 `PACKAGE_CHANGED`（`com.deep.rcode` → `com.core.deepcode`）后需手动从备份恢复；且现有备份为手工白名单，大量数据不参与迁移。
> 关联模块：`feature/backup`、`feature/settings`、`feature/agent`、`feature/credentials`、`feature/workspace`、`feature/t2i`。

## 0. 决策记录（评审确认）

| # | 决策点 | 结论 |
|---|--------|------|
| C1 | 实施节奏 | **全部一次性**：S1（注册表+全量备份+自动迁移）+ S2（DataStore 收敛）+ S3（Room 拆库）同批落地 |
| C2 | DataStore 形态 | **每模块一个**：settings 11→1，其他模块各自一个 |
| C3 | Room 处理 | **按域拆库**：AgentDatabase 单巨库拆为 agent / settings / credentials / workspace / t2i 五个库，含一次性数据移植 |

## 1. 背景与目标

### 1.1 目标（新写法要达成的效果）

1. **数据库不被任何外部因素影响**：同名升级天然保留（Room 迁移已覆盖）；包名变更被 build 白名单锁死（已实施）；残余的唯一场景——历史旧包（`com.deep.rcode`）一次性迁移——实现**无感自动迁移**，用户零手动操作。
2. **备份/恢复覆盖全量数据**：从「手工白名单」变为「注册表驱动全量」，让任何一次迁移都迁得完整、不丢数据。
3. **存储结构收敛**：消除碎片化 DataStore 与跨模块耦合的单巨库，形成统一、类型安全的持久化层。
4. **不破坏现有功能**：重构只收敛结构与链路，不改变业务行为；Room 按域拆库（决策 C3），实体类定义不变（列 1:1），仅拆「@Database 聚合器 + DI + 一次性移植」。

### 1.2 旧版写法（现状）三大痛点（均为代码实测）

| # | 痛点 | 实据 |
|---|------|------|
| P1 | **单巨库跨域耦合** | `AgentDatabase` 26 实体 / 26 DAO / schema **v49**，横跨 agent / settings / workspace / credentials / t2i 七个域，且归属 `feature/agent` 模块；任何 feature 改表都动 agent 模块、挤进同一条迁移链 |
| P2 | **DataStore 碎片化** | 全应用 **17 个独立 `preferencesDataStore` 文件**，其中 settings 模块独占 **11 个**（theme/container/log/proxy/vision/…），每个都是裸 string key，类型安全缺失，备份/恢复无法枚举全量 |
| P3 | **备份为手工白名单，覆盖不全** | `BackupSnapshot` 仅覆盖 providers/gitCredentials/remoteConnections/remoteMounts/chatSessions/agentMessages/todoItems/mcpServers/globalPermissionRules + 4-5 项设置；**skills、checkpoints、t2i 任务、telemetry、wakeQueue、大部分设置（17 个 DataStore 只捞 4-5 个字段）均不参与备份恢复** → 包变更迁移后数据仍大量丢失 |

> 附带发现：AGENTS.md 记载 "Schema v46"，实际代码为 **v49**（文档漂移，需同步）。

## 2. 需求清单（一系列需求）

重构后的数据层必须覆盖：

- R1 **无感自动迁移**：`PACKAGE_CHANGED` 时自动从外部加密备份全量恢复，无弹窗、无手动步骤；失败才回退告警。
- R2 **升级自动备份**：versionCode 增加时自动备份（现有 D5 保留）。
- R3 **全量可恢复**：备份覆盖所有 Room 表 + 所有 DataStore + 关键文件（通过注册表枚举，不再手工白名单）。
- R4 **类型安全存储**：设置类持久化提供类型化 Key / 类型安全读写，消除裸 string。
- R5 **单一事实源**：备份/恢复/哨兵/迁移共用同一份「数据注册表」，不再各自维护一份清单。
- R6 **Room 按域拆库（决策 C3）**：单巨库拆为 agent / settings / credentials / workspace / t2i 五个独立库，实体类定义不变（列 1:1），通过 LegacyAgentDatabase 一次性移植旧数据，避免跨库高频改动与耦合。
- R7 **文档同步**：`docs/modules/` 与 AGENTS.md 中过时信息（如 Schema v49）同步修正。

## 3. 目标架构（新写法）

```
┌─────────────────────────────────────────────────────────────┐
│  DataRegistry（数据注册表 · 单一事实源）                       │
│  - 枚举全应用数据域：Room 26 表 + DataStore 17 + 文件          │
│  - 每项提供：id / snapshot() / restore() / 序列化             │
│  - 供 备份 / 恢复 / 哨兵 / 自动迁移 统一调用                    │
└─────────────────────────────────────────────────────────────┘
        ▲ 读取/枚举                ▲ 导出/导入
┌───────┴────────┐      ┌──────────┴──────────┐
│ PersistenceFacade │      │  BackupFacade        │
│ (类型安全 DataStore │      │  (原 BackupManager +  │
│  收敛 + Room 访问)  │      │   AutoBackup + 外部存储)│
└──────────────────┘      └─────────────────────┘
        │                                 ▲
        ▼                                 │
┌─────────────────────────────────────────┴──────────┐
│  MigrationOrchestrator（自动迁移编排）                 │
│  哨兵判定 → 全量导出外部加密备份 → 自动恢复 → 标记已初始化 │
│  全程无感；失败回退告警                                 │
└────────────────────────────────────────────────────┘
```

### 3.1 新增：`DataRegistry`（数据注册表，核心）

- 位置：`core/data/`（公共基础层）或独立 `feature/dataregistry/`（评审后定，倾向 core）。
- 结构：
  - `DataDomain` 枚举：每个数据域一项（如 `CHAT_SESSIONS`、`AGENT_MESSAGES`、`PROVIDERS`、`SKILLS`、`T2I_TASKS`、`SETTINGS_THEME`、`SETTINGS_CONTAINER`、…），携带 `key`、`category`（Room/DataStore/File）。
  - `DataProvider` 接口：`suspend fun snapshot(): DataBlob` / `suspend fun restore(blob: DataBlob)`。
  - `DataRegistry`：`providers: List<DataProvider>`（DI 注入），提供 `snapshotAll()/restoreAll()`。
- 收益：备份从「手工白名单」变「注册表全量」；新增数据域只需注册一个 Provider，备份/恢复/迁移自动覆盖。

### 3.2 重构：DataStore 收敛（类型安全）

- 现状：settings 模块 11 个独立 `preferencesDataStore`。
- 目标（两种形态，评审后二选一）：
  - **A. 每模块一个 store**：settings 收敛为 `settings_prefs` 一个 DataStore，内部按 `SettingsKey` 类型化定义（enum + Flow 映射），其余模块各自一个。
  - **B. 全应用一个 store**：`app_prefs` 一个 DataStore + 全量 `AppSettingsKey`。
- 倾向 A（改动面可控、保留模块边界）。每个 store 注册进 `DataRegistry`，实现类型安全读写 + 全量备份。

### 3.3 Room 按域拆库（核心决策 C3）

**现状**：单巨库 `AgentDatabase`（26 实体 / 26 DAO / v49）落在 `feature/agent`，跨 7 域耦合；迁移链 44 个 SQL 单一巨串。

**目标**：拆为 5 个独立 Room 库，各归属其 feature 模块（实体/DAO 已在各 feature 包内，主要拆「@Database 聚合器 + DI + 移植」）：

| 新库 | 文件 | 实体 | 版本 |
|------|------|------|------|
| `AgentDatabase`（瘦身） | `feature/agent/data/local/database/AgentDatabase.kt` | agent 域（消息/会话/todo/checkpoint/skill/wake/zth 等 17 实体） | 1（全新） |
| `SettingsDatabase` | `feature/settings/data/local/database/SettingsDatabase.kt` | AIProvider | 1 |
| `CredentialsDatabase` | `feature/credentials/data/local/database/CredentialsDatabase.kt` | GitCredential | 1 |
| `WorkspaceDatabase` | `feature/workspace/data/local/database/WorkspaceDatabase.kt` | RemoteConnection、RemoteMount、RemoteAuditLog、CredentialEncryptionState（后者的实体在 core、DAO 在 workspace，随 DAO 物理位置归 workspace） | 1 |
| `T2IDatabase` | `feature/t2i/data/local/database/T2IDatabase.kt` | T2IProvider、T2IProviderModel、T2ITask | 1 |

**一次性数据移植（升级路径，防数据丢失）**：
1. 现 `AgentDatabase` 重命名为 `LegacyAgentDatabase`（保留 v49 + 全迁移链 + 漏斗救援），仅用于读取旧单库文件 `deepcode_agent_db`。
2. 启动时检测：旧文件存在且移植标记未置位 → 打开 Legacy 库 → 逐表拷贝到新 5 库 → 关库并把旧文件改名 `deepcode_agent_db.migrated.v49` 留底 → 置位移植标记（幂等，只跑一次）。
3. 新库为全新 v1，无需迁移链；表结构 = 现 v49 表定义（实体类不变，列 1:1，天然无损）。
4. 移植失败：保留旧文件 + 告警，不删除、不覆盖，可重试。
5. 后续版本：新库各自独立演进，互不影响；`LightweightSchemaRescue` 改为按库维护实体清单。

### 3.4 无感自动迁移（核心落地）

改造 `DataSafetyNotifier` / `AutoBackupManager`：
1. `PACKAGE_CHANGED` 判定后：`DataRegistry.snapshotAll()` → 走现有 `ExternalBackupStore`（签名派生密钥加密，写公共目录）→ `restoreAll()` 自动恢复。
2. 成功后：标记哨兵已初始化（防下次启动重复导入）→ 本轮不弹窗。
3. 失败（无外部备份 / 解密失败）：回退现有告警弹窗，保留手动恢复入口。
4. `UPGRADED`：维持现有自动备份（R2），但改为 `DataRegistry.snapshotAll()` 全量。

## 4. 实施计划（一次性，决策 C1）

实施顺序（每步编译验证，按依赖排序）：

| 步骤 | 内容 | 主要文件 | 风险 |
|------|------|---------|------|
| T1 | **Room 按域拆库**：新建 5 库（agent/settings/credentials/workspace/t2i，均 v1）；现 `AgentDatabase`→`LegacyAgentDatabase`；一次性移植 + 标记；DI 改 5 个 provider + DAO 分发 | `feature/*/data/local/database/*`、`di/AgentModule.kt`、`MigrationLoader.kt`、`LightweightSchemaRescue.kt`、`AIEditorApp.kt` | 高（拆库+移植，防数据丢失） |
| T2 | **DataStore 每模块一个**：settings 11→1（类型安全 Key + 旧值搬迁），其余模块各自一个 | `feature/settings/data/repository/*` | 中（旧值迁移兼容） |
| T3 | **DataRegistry + 备份全量化 + 无感自动迁移**：注册表驱动全量备份/恢复；`PACKAGE_CHANGED` 自动恢复 | `core/data/*`、`BackupSnapshot.kt`、`DataSafetyNotifier.kt`、`AutoBackupManager.kt` | 中（备份 DTO 兼容旧备份） |
| T4 | **文档同步 + 单测**：模块文档、AGENTS.md（Schema v49）、移植/哨兵/注册表单测 | `docs/modules/*`、AGENTS.md、测试 | 低 |

> 关键：T1 是最高风险与最大改动面，必须在独立分支完成并充分验证（含「旧单库 → 新 5 库」移植路径）；T2/T3 建立在 T1 之上。

## 5. 风险与权衡

| 风险 | 权衡/缓解 |
|------|----------|
| **拆库移植导致数据丢失**（最高风险） | 移植只读旧库→写新库；旧文件改名留底不删除；幂等标记；失败保留旧文件可重试；实体类不变列 1:1 |
| 拆库后 26 表分散，schema 校验/救援复杂化 | `LightweightSchemaRescue` 改为按库维护实体清单；新库 v1 无迁移链，天然简单 |
| 备份 DTO 扩展破坏旧备份兼容 | 新字段带默认值；导入兼容旧 schemaVersion |
| DataStore 收敛导致旧值丢失 | 迁移读取旧 store 文件并映射到新 store，一次性搬迁逻辑 |
| 自动迁移误恢复（用户主动清空） | 仅在 `PACKAGE_CHANGED`（首次安装新包、本包无用户数据）触发；`DATA_LOST` 仍告警不自动恢复 |
| 全量备份体积增大（含大表） | 聊天大表走现有 jsonl 分片流式；轮转仅留 7 份 |

## 6. 评审状态

- [x] 现状盘点与痛点核实（P1-P3，代码实测）
- [x] 目标架构与实施计划（决策 C1/C2/C3 已确认）
- [ ] 新建分支 `refactor/data-layer`，按 T1→T4 实施（每步编译验证）
- [ ] 验证：`./gradlew :app:assembleDebug :app:testReleaseUnitTest`；CI 全流程；Tag 发版
