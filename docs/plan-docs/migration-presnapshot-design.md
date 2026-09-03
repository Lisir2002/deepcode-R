# 迁移前版本探测与快照安全网设计（preOpen / VersionProbe）

> 评审状态：📝 草案

## 背景与问题

原 `MigrationEngine.ensureSchema` 第一步 `currentVersion(driver)` 经 SQLDelight driver 读取
`user_version`。但 `app.cash.sqldelight.driver.android.AndroidSqliteDriver` 在**构造时**就打开
数据库，并经由 `SQLiteOpenHelper.onCreate` / `onUpgrade` 回调同步执行 `schema.create` /
`schema.migrate`、写入**目标** `user_version`。因此 `ensureSchema` 读到的永远是迁移后的 target 版本：

- 全新库：`create` 已将 `user_version` 设为 target → 读到 target，`current == 0` 分支不可达；
- 旧版本库：`onUpgrade` 已设为 target → 读到 target，`current < target` 分支不可达。

后果：本应在「迁移前」执行的文件级快照安全网（§5.3）**从未执行**——迁移若中途失败，无快照可
回滚。这是 rc 演进中长期被忽略的隐性数据风险（修复见 v0.0.0.1-rc10）。

## 方案（把版本探测与快照前移到 driver 构造之前）

1. 引入平台无关接口 `VersionProbe.readVersion(lib): Int`：在 driver 打开**之前**用原生只读连接
   读真实 `user_version`，隔离「物理 SQLite 探测」与「决策/快照逻辑」。
2. Android 实现 `AndroidVersionProbe`：用 `android.database.sqlite.SQLiteDatabase` 只读打开库文件
   （不触发 `onCreate`/`onUpgrade`），读到迁移前的真实版本。
3. `MigrationEngine.preOpen(lib, schema)` 在 `factory.create(lib)`（即 `AndroidSqliteDriver` 构造）
   **之前**调用：用 `probe` 读 `current`，`decidePreOpen(current, target)` 决策；仅当
   `0 < current < target` 时对「迁移前状态」的文件先 `snapshot()`。
4. `ConnectionPool.driver` 新增 `onPreOpen` 回调，在 `factory.create(lib)` 之前触发。
5. `DataLayerModule` 注入 `AndroidVersionProbe` 与 `onPreOpen` hook（`preOpen` 失败不阻断启动，
   由 `ensureSchema` 兜底）。
6. `ensureSchema` 职责收敛为「打开后兜底校验 + codeMigrations + 日志」，删除不可达的
   `snapshot` 死代码与 `current == 0` 分支。

## 决策纯函数 `decidePreOpen(current, target)`

| 条件 | PreOpenAction | 行为 |
| --- | --- | --- |
| `current == 0` | `FRESH` | 全新库，`create` 会建表，无需快照 |
| `0 < current < target` | `UPGRADE_SNAPSHOT` | 旧版本待迁移，此刻文件仍是迁移前状态，先快照保命 |
| `current == target` | `ALIGNED_NOOP` | 已对齐，无需快照（结构完整性由 SchemaSelfHealer 保证） |
| `current > target` | `DOWNGRADE` | 版本回退，拒绝打开以防数据损坏 |

## 风险①（rc7 换库代价）固化

`LibName.fileName` 是**数据契约**：改后缀（v2→v3）= 放弃旧文件 = 清空该库全部历史数据。
rc7 曾因误判 `no such column: agent_message.id` 根因（实为 SQL 形态不合法，非库损坏）而换名
`rcodecore_agent_v3.db`，导致历史会话被静默清空——过度反应，不可逆。

纪律（已固化到 `DatabasePathProvider` 注释）：缺列/结构演进一律走 `SchemaSelfHealer` 无损重建或
`.sqm` 迁移，**禁止用「改文件名换库」规避**；仅当库确属不可自愈的损坏才考虑换名重建且需评审记录。

## 测试

`MigrationEnginePreOpenTest`（JVM，用可控 `FakeProbe` 隔离物理探测）：
- `decidePreOpen` 覆盖全部 5 个分支；
- `preOpen` 对旧版本库在「迁移前」做快照并保住旧内容（核心回归）；
- 全新库 / 已对齐库不快照；版本回退抛异常。
