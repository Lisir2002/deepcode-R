---
# 协同 AI 版本日志（面向 AI 模型的版本演进记录）
# 结构化、可解析；供 AI 在任务编排时快速掌握每个版本的工具/schema/接口/行为变化
# 权威规范见 docs/versioning.md 第 2.3 节
---

# Version Log (AI)

- 规则：工具 / prompt / schema 等 AI 工作流相关变更，发版时必须在本文件追加一条结构化记录。
- 版本号规则：四段式 x.x.x.x，见 `docs/versioning.md`。本文件最新条目置顶。

## 0.0.0.1-rc10 (2026-09-03)

### TYPE
prerelease-rc / fix (restore pre-migration snapshot safety net; harden fileName data-contract)

### IMPACT
- datalayer / migration engine；无 schema 版本变化、无表结构变化、无库文件名变化、无 API 语义变化。

### DATA / SCHEMA
- **无数据迁移**。`schema.version` 不变，不需新增 `.sqm`。
- 根因：原 `MigrationEngine.ensureSchema` 第一步 `currentVersion(driver)` 经 SQLDelight driver 读 `user_version`，而 `AndroidSqliteDriver` 构造即打开并执行 `schema.create`/`schema.migrate`、写入目标版本号 → `current == 0` 与 `current < target` 分支永远不可达，**迁移前快照安全网从未执行**（迁移中途失败则无快照可回滚）。
- 修复：新增平台无关接口 `VersionProbe.readVersion(lib)`（Android 实现 `AndroidVersionProbe` 用 `android.database.sqlite.SQLiteDatabase` 只读打开，不触发 onCreate/onUpgrade）；`MigrationEngine.preOpen(lib, schema)` 在 `ConnectionPool.driver` 的 `factory.create` **之前**调用，仅对 `0 < current < target` 的旧版本库先 `snapshot()` 再交驱动迁移；版本回退拒绝前移。
- `decidePreOpen(current, target)` 纯函数决策：FRESH(0) / UPGRADE_SNAPSHOT(0<cur<tgt) / ALIGNED_NOOP(==) / DOWNGRADE(>)。

### AI ACTION
- 排查「迁移/升级后数据损坏、无快照」类问题时，先确认版本探测是否在 driver 打开**之前**完成；凡「driver 构造即打开并迁移」的 SQLDelight 驱动，绝不能经 driver 读 `user_version` 来判断「是否需要迁移前快照」——读到的总是迁移后的版本。
- `LibName.fileName` 是数据契约：**改文件名后缀 = 清空该库历史**。缺列/结构演进一律走 `SchemaSelfHealer` 无损重建或 `.sqm` 迁移，禁止改文件名换库规避；仅确属不可自愈的损坏才换名且需评审记录（rc7 改 `rcodecore_agent_v3.db` 清空历史会话，属过度反应，已注释固化防复发）。

## 0.0.0.1-rc9 (2026-09-03)

### TYPE
prerelease-rc / fix (sql shape defect: anonymous subquery in generated paged query)

### IMPACT
- datalayer / agent 消息读取路径；**无 schema 版本变化、无表结构变化、无库文件变化、无 API 语义变化**。

### DATA / SCHEMA
- **无数据迁移**。`agent.sq` 的 `selectMessagesBySessionPaged` 内层子查询补 `AS agent_message` 别名（仅 .sq 文本变更，`schema.version` 仍为 3，不需新增 .sqm）。
- 根因：SQLDelight 会把外层 `SELECT *` 展开为带表名前缀的列（`agent_message.id, ...`）；子查询**匿名**时，SQLite 外层作用域内不存在名为 `agent_message` 的表或别名，展开后的列无处绑定，**prepare 阶段**即报 `no such column: agent_message.id`。
- 关键判据：**表结构 100% 正确（22 列含 `id`）时同样必崩**（SQLite 3.45 实测）。故 rc1~rc7 围绕「表缺列 / 自愈重建 / 换库文件名」的七轮修复从机制上不可能生效。此前崩溃快照的 `agentPreheatRan=true` 正是「自愈已成功、问题不在数据」的指纹。

### AI ACTION
- **AI 新增或修改 `.sq` 查询时：凡出现 `FROM ( 子查询 )` 且外层使用 `SELECT *`，子查询必须显式别名**（如 `AS agent_message`），否则外层展开的带前缀列会解析失败。
- 排查 `no such column: <table>.<col>` 时，**先区分「表真的缺列」还是「SQL 作用域解析失败」**：若错误文本带表名前缀、且 `PRAGMA table_info` 确认该列存在 → 必属后者，应审计 SQL 形态而非数据库。这是 rc1~rc8 七轮误判的总教训。
- 启动冒烟**禁止手写近似 SQL**（rc8 就因手写 `SELECT agent_message.id FROM agent_message LIMIT 1` 形态不同而打出假阳性绿灯），必须直接调用 SQLDelight 生成的查询方法，保证与线上路径同构、永不漂移。

## 0.0.0.1-rc7 (2026-09-03)

### TYPE
prerelease-rc / fix (abandon corrupt legacy agent db file)

### IMPACT
- datalayer / agent 数据落点变化；`LibName.AGENT.fileName` 由 `rcodecore_agent_v2.db` 改为 `rcodecore_agent_v3.db`。

### DATA / SCHEMA
- AGENT 库改全新文件名 `rcodecore_agent_v3.db`（全仓仅 `datalayer/engine/DatabasePathProvider.kt` 一处引用，备份/恢复/自动迁移均按 `LibName.fileName` 映射，透明适配）。
- 新文件首次打开 `user_version=0` → `schema.create` 全新建表，`agent_message` 必带 `id` 主键，从机制上根治反复出现的 `no such column: agent_message.id`。
- 受影响设备旧的 `rcodecore_agent_v2.db`（很早期 rc 创建、缺 `id`、覆盖安装未清除的坏库）不再被读取，历史 AI 会话随之弃置（新库为空）。

### AI ACTION
- AI 若需访问/迁移旧 agent 数据，注意旧文件 `rcodecore_agent_v2.db` 已被弃用；后续一律以 `LibName.AGENT.fileName` 为准，勿硬编码旧库名。

## 0.0.0.1-rc3 (2026-09-03)

### TYPE
prerelease-rc / fix (structural self-heal idempotency)

### IMPACT
- datalayer only；无 API/schema 语义变化。

### DATA / SCHEMA
- `SchemaSelfHealer.healTable` 在 `ALTER TABLE ... RENAME TO ${table}_legacy` 前新增 `DROP TABLE IF EXISTS ${table}_legacy`（`try/catch` 包裹、失败不阻断）：清理上次自愈在 RENAME 后未 DROP 就中断所遗留的临时表，避免本次 RENAME 撞名失败、永久锁死后续自愈。

### AI ACTION
- 无行为迁移；AI 演进 agent 域表结构时仍需同步 SchemaSelfHealer 的 target 列与 DDL（与 .sq 一致）。

## 0.0.0.1-rc2 (2026-09-03)

### TYPE
prerelease-rc / fix (structural self-heal hardening)

### IMPACT
- startup / datalayer only；无 API/schema 语义变化。

### DATA / SCHEMA
- `datalayer/migration/SchemaSelfHealer` 新增保证性复核：
  - `hasColumn`（PRAGMA 命中 true/false）；
  - `ensureAgentMessageUsable` / `ensureAgentSessionUsable`：heal 后强制验证 `id` 列存在，仍缺立即重试无损重建，二次失败抛明确异常（不再落入 confusing 的 `no such column` 崩溃）。
- `DataLayerModule.provideAgentDb` 打开链路：healAgentSession → healAgentMessage → ensureAgentMessageUsable → ensureAgentSessionUsable（全部幂等）。

### AI ACTION
- 若 AI 新增/演进 agent 域表结构，确保同步更新 SchemaSelfHealer 的 target 列与 DDL（与 .sq 一致）。

## 0.0.0.1-rc1 (2026-09-03)

### TYPE
prerelease-rc / feat+fix+docs

### IMPACT
- startup / datalayer / network / versioning / docs

### TOOLS & PROMPTS
- 无新增 AgentTool；prompt 资产新增本文件（90-version-log.md）作为 AI 协同版本日志。

### DATA / SCHEMA
- 数据层已完成 Room/DataStore → SQLDelight V2 全接管（核心链路）。
- 新增 `datalayer/migration/SchemaSelfHealer`：AgentDb 打开时对 `agent_session` / `agent_message` 做幂等无损结构自愈（缺列重建），规避历史库「版本号相同但表结构漂移」导致的 `no such column: agent_message.id` 崩溃。AI 若新增/演进表结构，需在该自愈器登记目标列与建表 DDL（与 .sq 保持一致）。

### NETWORK / BEHAVIOR
- 新增 `core/network/PublicDnsFallback`：系统 DNS 解析失败时自动回退公共 DNS（223.5.5.5 等 UDP 直连），解决「Unable to resolve host」类模型接口连接失败。共享 OkHttp 已挂载，AI 侧无需干预。

### VERSIONING
- 版本号切换为四段式 x.x.x.x（从 0.0.0.1 迭代）：
  - Bug 修复再发版 → 仅迭代 rc 后缀（v0.0.0.1-rcN）；
  - 新增/删除功能正式发版 → D 段 +10（v0.0.0.10）；
  - 框架重构正式发版 → C 段 +1 且 D 段归零（v0.0.1.0）。
- 三份版本日志落地：用户=CHANGELOG.md、开发者=docs/modules/<module>.md「版本演进记录」、AI=本文件。发版前三者都需同步。

### COMPATIBILITY / MIGRATION
- 历史旧三段 tag（v0.5.x / v0.6.x 等）不再用于版本号推导，构建侧对无四段 tag 时回退 dev 基线 0.0.1-dev，防止版本号回跳。
- 数据库结构自愈幂等、可重复执行；失败仅记日志，不阻断启动（release 语义）。