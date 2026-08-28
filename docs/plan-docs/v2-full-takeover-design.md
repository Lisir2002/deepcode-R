# v2-full-takeover-design

> 评审状态：✅ 已评审
>
> 评审结论：批准按 P0→P5 六阶段推进 V2（SQLDelight）全面接管并彻底剔除旧 Room 数据层；migrator 采用「永久保留两个轻量移植器 + 仅删 LegacyAgentDatabase 全迁移链」的取舍。
>
> 主题：新版数据层（V2）完全接管、旧版数据层（V0 单巨库 + V1 五域 Room 库）完全剔除。
>
> 关联设计：[data-layer-refactor-design](./data-layer-refactor-design.md)（V1 拆库方案，已实施）、[data-preservation-design](./data-preservation-design.md)（DataRegistry 备份事实源，已实施）。
>
> 关联模块：`datalayer/`（V2 全部）、`di/DatabaseModule.kt`、`feature/{agent,settings,credentials,workspace,t2i}`、`core/{data,db,worker}`、`feature/backup`。

## 0. 决策记录

| # | 决策点 | 结论 |
|---|--------|------|
| T1 | 实施节奏 | **分六阶段 P0→P5**，每阶段独立提交、独立可回滚；禁止跳级 |
| T2 | 是否引入双写窗口 | **不引入**。以「读模式开关 + 逐表 parity 校验 + 切换前强制全量备份」三件套替代，避免双写的复杂度与不一致风险 |
| T3 | migrator 去留 | **永久保留** `DbSplitMigrator`（V0→V1）与 `V1toV2FullMigrator`（V1→V2）两个轻量移植器（启动期标记命中即早退，成本可忽略）；**仅删** `LegacyAgentDatabase` + v49 全迁移链（44 个 SQL + 26 旧实体聚合器） |
| T4 | 旧库物理文件删除 | 与代码剔除**分阶段**：先删代码、文件改名留底 7 天、到期再删；删前必须已有外部加密备份 |
| T5 | 业务切换顺序 | 按「单表外围 → 多表 Repository → 会话/消息热表 → 事务与散点」四批，风险递增 |

## 1. 现状：三层数据层并存

| 代际 | 形态 | 状态 |
|---|---|---|
| **V0** | `LegacyAgentDatabase` 单巨库，26 实体 / schema v49 / 44 条迁移 SQL | 已退役为只读，仅 `DbSplitMigrator` 救援读取 |
| **V1** | 5 个 Room 域库（agent v4 / settings / credentials / workspace / t2i），32 表 | **当前线上生效的主存储** |
| **V2** | SQLDelight 6 库（5 域 + infra）、47 张 CREATE TABLE、5 个 Store、`MigrationEngine` | 已建完地基，仅接受一份历史数据拷贝，无业务读取 |

「V2 全面接管」已完成的三个阶段（`9b7c52b` / `845cfa2` / `1001b30`）：补齐 14 张缺失表、5 个 V2 Repository 补全方法、`V1toV2FullMigrator` 全量移植并接入 `AIEditorApp.onCreate`。

**尚未开始的是阶段 4（业务切换）与阶段 5（剔除）**，即本文档主题。

## 2. 缺口实测（代码盘点，非估算）

### 2.1 方法覆盖缺口

| 域 | V1 Room DAO 方法 | 其中 Flow | V2 Repository 方法 | V2 Flow | 缺口 |
|---|---|---|---|---|---|
| agent | 203（24 个 DAO） | 22 | 68 | 0 | **方法缺 ~135、Flow 全缺** |
| workspace | 33（4 个 DAO） | 5 | 20 | 0 | 缺 13、Flow 全缺 |
| t2i | 44（3 个 DAO） | 4 | 16 | 0 | 缺 28、Flow 全缺 |
| settings | 16 | 2 | 13 | 0 | 缺 3、Flow 全缺 |
| credentials | 10 | 1 | 12 | 0 | 缺列级 setter、Flow 全缺 |

> 阶段 2 提交信息称「接口同构」，实测不成立：V2 普遍只有 insert/get/list/delete 骨架，**缺 update 系与列级 setter**，且 **0 个 Flow 读方法**。

### 2.2 两处硬缺口（阻塞业务切换）

1. **响应式读缺失**：V1 有 34 个 `Flow<...>` 查询驱动 UI 刷新（Provider 列表、会话列表、用量卡片、Goal/Plan/Job/Schedule 流）。V2 全为 suspend 一次性调用，未使用已在依赖中的 `app.cash.sqldelight:coroutines-extensions`。不补齐则切换后 UI 退化为手动轮询。
2. **schema 语义偏差**：以 settings 域 `ai_providers` 为例，V2 相比 Room 缺 `activateProvider` / `setDefaultModel` / `setModels` / `setProviderEnabled` / `updateEncryptedApiKey` 五个查询；`selectAllProviders` 排序键为 `name`（Room 为 `id`）；`selectActiveProvider` 缺 `LIMIT 1`；`insertProvider` 为纯 `INSERT`（Room 为 `OnConflictStrategy.REPLACE`），**保存已存在 Provider 会直接抛主键冲突**。

> **事务 API 不缺**：SQLDelight 生成的 `db.transaction { }` 已在 `SettingsRepository.setActiveProfile` 中实际使用，V1 的 5 处 `withTransaction`（Goal/Plan/Job/Todo/SessionUseCase）可直接平移。仅需为跨物理库写（workspace 改 agent 会话路径）显式定义顺序写语义（V1 现状亦为跨库，无更强保证）。

### 2.3 业务不变量必须原样搬迁

`AIProviderRepositoryImpl` 等处沉淀了多轮线上修复，切换时**不得降级**：

- **RC68 P0-1**：`saveProvider(isActive=true)` 必须先 `deactivateAll` 再写，保证全库 `is_active=1` 最多一行（曾导致模型下拉回跳旧 provider）。
- **RC71**：API Key 加密失败**必须抛异常中止保存**，绝不写空串覆盖已有密文；编辑时未改 key 则保留旧密文。
- **RC68 SCHEMA 38**：明文 `apiKey` 列已 DROP，只从密文列解密；解密失败返回空串 + 日志，不崩 UI。

### 2.4 既有缺陷（本次一并修复）

`MigrationEngine.restoreSnapshot()`（`datalayer/migration/MigrationEngine.kt`）中 `main.copyTo(bak, overwrite = true)` **拷贝方向写反**——把主库覆盖到快照，而非用快照还原主库。一旦真触发回滚会立即销毁安全网快照。注释还自称「此处为把快照写回主库路径」，属带注释的 bug。

### 2.5 消费方分布（切换工作面）

- 引用 `androidx.room` 的文件：**83 个**（feature 75 / core 7 / di 1）。
- Repository 封装层 13 个；`ChatSessionDao` 被 9 个文件直用、`AgentMessageDao` 7 个、`CredentialEncryptionStateDao` 4 个。
- **ViewModel 直注 DAO 3 处**（`AIAgentViewModel` / `SecuritySettingsViewModel` / `AboutStatsViewModel`）——违反分层，本次一并收敛进 Repository。
- 测试代码引用 Room：**0**（剔除后无测试面返工）。

## 3. 目标架构

```
业务层（feature/*/domain, presentation）
        │  仅依赖 domain.repository 接口
        ▼
V1 兼容门面（feature/*/data/repository/*Impl）──┐
        │  读模式开关 DataReadMode（ROOM | V2）    │ 切换期存在，P3 后消失
        ├──────────────► Room DAO（V1）           │
        └──────────────► datalayer/repository（V2）│ ← 最终唯一路径
                             ▼
                    SQLDelight 6 库 + 5 Store
                             ▲
              DataRegistry（备份/恢复/迁移单一事实源，双登记过渡 → V2 单登记）
```

单一事实源不变：备份/恢复/自动迁移仍走 `DataRegistry`，仅把 Provider 从「V1 32 表 + V2 18 表」双登记收敛为 V2 单登记。

## 4. 实施编排（六阶段）

### P0 地基补缺（阻塞后续一切）

| # | 任务 | 判据 |
|---|------|------|
| P0-1 | V2 补 `Flow` 响应式读：5 个 Repository 按 §2.1 缺口清单补 `asFlow().asXxx()` 查询 | V2 Flow 方法数 ≥ V1 对应 34 个；agent 域逐 DAO 对齐 |
| P0-2 | V2 补 update/列级 setter 与 REPLACE 语义：各 `.sq` 补查询 + `insertOrReplace` | §2.2 settings 五项缺口闭合；保存已存在 Provider 不抛冲突 |
| P0-3 | 修 `restoreSnapshot` 方向 bug + 排序键/`LIMIT 1` 对齐 V1 | JVM 单测：篡改主库→restore→主库等于快照 |
| P0-4 | 本设计文档入库 + `progress-tracker.md` D7-2 状态与子任务登记 | `spec-check.sh` 通过；README 索引可查 |

### P1 安全网（先建逃生通道，再动业务）

| # | 任务 | 判据 |
|---|------|------|
| P1-1 | 切换前强制全量备份钩子（`DataRegistry.snapshotAll()` → `ExternalBackupStore`）；失败则阻断 V2 读、回落 ROOM | 备份可解密校验；人为制造失败时开关不生效 |
| P1-2 | `DataReadMode` 持久化单开关（ROOM \| V2），控制各域读源 | 切回 ROOM 后功能全可用 |
| P1-3 | `V2ParityChecker`：启动后台逐表比行数 + 关键字段抽样哈希，不一致即告警并自动回落 ROOM | 输出全表 parity 报告；注入不一致能被捕获 |
| P1-4 | `DataRegistryModule` 双登记**保持不动**（它是 P1/P2 的资产，非待清理项） | 一致性测试 50 项全绿 |

### P2 业务切换（四批，每批一个可回滚提交）

| 批次 | 范围 | 判据 |
|---|------|------|
| 批 1 | 单表外围：`AIProviderRepositoryImpl`、`CredentialRepositoryImpl`、t2i 三表、`remote_audit_logs` | 4 个 Impl 不再注入 DAO；§2.3 三条不变量单测全绿 |
| 批 2 | 多表 Repository + Zth 域：6 个 `Zth*Repository`、`SkillStateRepository`、`CredentialEncryptionStateDao` 4 个消费方、`RemoteRepository` | feature 层仅剩批 3 目标注入 DAO；密文列 `s_` 原名读写一致 |
| 批 3 | **热表（最高风险，独立成批）**：`ChatSessionDao` + `AgentMessageDao` + `MessagePersistenceUseCase` + `SessionUseCase` 9 表级联删除 | 长对话滚动/流式输出/删除级联/按工作区过滤真机冒烟通过；parity 100% |
| 批 4 | 事务服务 + 跨域写 + 散点：`Goal/Plan/Job/Todo` 的 `withTransaction`、`renameWorkspace`、3 处 ViewModel 直注收敛 | `grep androidx.room feature/*/presentation` = 0 |
| 收口 | `DataLayerModule` 成为唯一 DB DI 入口；`DatabaseModule` 仅服务 migrator/parity/旧表备份三条遗留通路，标 `@Deprecated` | 灰度一个完整版本周期，无 V2 读侧 P0 反馈 |

> `V1toV2MigrationWorker` 的 V1/V2 指**凭据加密代际**，与数据层代际无关，本批重命名为 `CredentialCryptoUpgradeWorker` 消除歧义。

### P3 剔除旧层（依赖倒序删除）

| # | 删除项 | 门槛 |
|---|--------|------|
| P3-1 | `DataRegistryModule` 旧 32 表登记、UI/DI Room 残留绑定；一致性测试 50 → 18 + DataStore | 灰度期无 ROOM 回退记录 |
| P3-2 | 32 个 DAO + 全部 entity / `toDomainModel` mapper（共 64 文件） | 编译 0 引用 |
| P3-3 | 5 个 Room 域库类 + `di/DatabaseModule.kt` + `LightweightSchemaRescue` Room 清单 | 同上 |
| P3-4 | Room 依赖（runtime/ktx/compiler KSP/`schemaLocation`）、`schemas/` 归档 | DoD 判据 1/2 |
| P3-5 | `LegacyAgentDatabase` + `MigrationLoader` + `RobustMigration44` + `assets/migrations/*.sql` | 按 T3：`DbSplitMigrator` 已消费且留底 `.migrated.v49` 文件 |

### P4 旧库物理清理

V2 标记置位 **且** 最近一次 parity 为绿 **且** 外部备份存在 → 旧 5 个 Room 文件改名 `*.v1_room.removed_<date>` 留底 → 7 天后下次启动到期删除。与 P3 **绝不同批**（代码先删、文件留底，保留重装+备份恢复路径）。

### P5 文档与纪律（每提交同步，不末尾补做）

`docs/modules/core.md` §3 数据库迁移段重写为 V2 单层；agent/settings/credentials/workspace/t2i 五份模块文档持久化章节；AGENTS.md 迁移纪律改 `.sq`/`.sqm`；README 技术栈行改 SQLDelight；`progress-tracker.md` D7-2 ⬜→✅；`CHANGELOG.md` 用户可见条目；`assets/sop/20-migration.md` 按新机制更新。本地启用 `git config core.hooksPath .githooks`。

## 5. 完成判据（Definition of Done）

1. `grep -r "androidx.room" app/src/main/java` = **0 命中**（基线 83 文件）。
2. `build.gradle.kts` 无 Room 依赖与 KSP schemaLocation；`schemas/` 归档。
3. 全部业务读写经 `datalayer/repository`；34 个 Flow 查询在 V2 侧一一对应。
4. 存量升级链不破：V0→V1→V2 数据完整到达；旧库文件删除前已有强制全量备份。
5. `restoreSnapshot` 回滚路径有单测覆盖并通过。
6. 文档债清零：本文档存在、`progress-tracker.md` D7-2 为 ✅、README 技术栈为 SQLDelight。

## 6. 风险与权衡

| 风险 | 缓解 |
|------|------|
| 热表切换致会话/消息丢失（最高） | 批 3 独立成批 + 切换前强制备份 + parity 100% 才放量 + 旧库文件留底不删 |
| Flow 语义与 Room 不一致（触发时机、初始值） | P0-1 补对照单测：同一数据集下 ROOM/V2 两条流发射序列比对 |
| 业务不变量在搬迁中丢失 | §2.3 三条逐条写成回归单测，作为批 1 放行门禁 |
| `DataReadMode` 开关被遗忘成长期技术债 | P3-1 为强制删除项，DoD 无开关残留 |
| 老包名用户（V0 未升级）升级后丢数据 | 按 T3 永久保留两个轻量 migrator，不追求绝对零残留 |
| 剔除后无法回滚 | P3 与 P4 分阶段；P3 全程可重装 + 外部备份恢复；`S` 分支保留删除前 HEAD |

## 7. 评审状态

- [x] V2 接管阶段 0–3 现状与缺口实测（§2，代码盘点）
- [x] 六阶段编排与门禁判据（§4）、决策 T1–T5（§0）
- [ ] P0 实施中：P0-1 / P0-2 / P0-3 / P0-4
- [ ] P1–P5 待实施
