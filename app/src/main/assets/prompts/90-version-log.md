---
# 协同 AI 版本日志（面向 AI 模型的版本演进记录）
# 结构化、可解析；供 AI 在任务编排时快速掌握每个版本的工具/schema/接口/行为变化
# 权威规范见 docs/versioning.md 第 2.3 节
---

# Version Log (AI)

- 规则：工具 / prompt / schema 等 AI 工作流相关变更，发版时必须在本文件追加一条结构化记录。
- 版本号规则：四段式 x.x.x.x，见 `docs/versioning.md`。本文件最新条目置顶。

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