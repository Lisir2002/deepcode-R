# R-CodeCore 功能模块文档索引

> 本目录为**功能模块级文档**（一个模块一份文档），对应源码 `app/src/main/java/com/R/codecore/feature/<module>/`。
> 与 `app/src/main/assets/docs/`（用户可见使用说明）不同：本目录面向**开发与维护**，记录模块定位、目录职责、核心架构、对外接口与维护指引。

## 维护规则（强制）

- **代码变更必须同步本目录**：任何功能新增 / 删除 / 行为变化 / 目录结构调整，都必须同步更新对应模块文档。
- **新增模块必须实时新增文档**：在 `feature/` 下新增模块时，必须同时新建 `docs/modules/<module>.md`（参考下方统一结构），并在本文档索引中登记。
- **结构统一**：每份文档固定六段式 + 可选第七节（见 `_template` 说明）：
  1. `模块定位` —— 该模块干什么、解决什么问题
  2. `目录结构与职责` —— 路径 | 职责 表格
  3. `核心架构与主流程` —— 关键类职责、数据流、主链路
  4. `对外接口与集成点` —— 被谁调用 / 调用谁 / DAO / Provider / Bridge
  5. `关键设计点与约束` —— 权限、安全、性能、注意事项
  6. `维护与扩展指引` —— 新增功能的落点位置
  7. `版本演进记录`（可选）—— 模块开发维度演进（版本 + 日期 + 变更），与仓库根 `CHANGELOG.md`（发布视角）互补，详见 [AGENTS.md 发版流程](../../AGENTS.md)

## 命名规范（强制）

- 每份模块文档的**文件名必须与 `feature/<module>` 目录名完全一致**：全小写（snake_case）+ `.md` 后缀，例如 `feature/t2i/` → `docs/modules/t2i.md`。
- 两个约定外文件名：`core.md`（公共基础层）与 `README.md`（本索引）。
- 该一一对应关系由 `.githooks/pre-commit` 在提交时自动校验（缺失或孤儿文档都会阻断），启用 hooks 后无需人工检查。

## 模块清单

| 模块 | 源码路径 | 文档 | 一句话职责 |
|---|---|---|---|
| agent | `feature/agent/` | [agent.md](./agent.md) | 核心 AI Agent：工作流、工具系统、容器/命令执行、MCP、记忆、技能、权限、ZTH 防护 |
| terminal | `feature/terminal/` | [terminal.md](./terminal.md) | 终端模拟：本地 PTY + 远程 SSH 双后端会话、后台保活、Bundle 管理 |
| workspace | `feature/workspace/` | [workspace.md](./workspace.md) | 工作区：本地/远程文件访问、同步引擎、FTP、挂载 |
| settings | `feature/settings/` | [settings.md](./settings.md) | 应用设置：AI Provider、模型、MCP、容器、日志、执行模式、安全 |
| git | `feature/git/` | [git.md](./git.md) | Git 集成：仓库操作、提交图、分支/日志、Diff 查看 |
| credentials | `feature/credentials/` | [credentials.md](./credentials.md) | Git 凭据：三端（UI/AI/终端）共用凭据管理与 IPC 请求链路 |
| backup | `feature/backup/` | [backup.md](./backup.md) | 备份与还原：流式导出/导入、AES-GCM 可选加密 |
| proxy | `feature/proxy/` | [proxy.md](./proxy.md) | 网络代理：mihomo 内核管理、订阅、路由注入 |
| browser | `feature/browser/` | [browser.md](./browser.md) | 内置浏览器：WebView 会话、登录接管、动态数据捕获 |
| capability | `feature/capability/` | [capability.md](./capability.md) | 能力中心：工具/Agent/技能聚合视图 |
| t2i | `feature/t2i/` | [t2i.md](./t2i.md) | 文生图：Provider 抽象、SYNC/ASYNC/AUTO 端点、权限策略 |
| core（公共基础层） | `core/` + `di/` + 应用入口 | [core.md](./core.md) | 跨模块基础设施：数据库迁移、安全加密、主题、日志、Worker、Hilt DI、App 入口 |

## 设计文档（docs/plan-docs/）

- 本目录只收录**功能模块文档**（一个模块一份）；**架构/功能设计文档**（方案、评审、决策记录）统一放置 `docs/plan-docs/`，命名 `<名称>-design.md`，并在文档头部标注评审状态（`📝 草案` / `✅ 已评审` / `已实施`）。
- 二者关系：设计文档定稿 → 实施落地 → 由本目录对应模块文档反映实现（规则权威来源见 [AGENTS.md 资产同步纪律](../../AGENTS.md)）。

## 相关文档

- [AGENTS.md](../../AGENTS.md) —— AI 协同开发规范（本文档同步规则权威来源）
- [README.md](../../README.md) —— 项目总览
- [plan-docs/](../plan-docs/) —— 设计文档目录（架构/功能设计方案）
