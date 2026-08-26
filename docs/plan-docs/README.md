# docs/plan-docs 设计文档目录说明

本目录存放 R-CodeCore 的架构/功能**设计文档**（方案、评审、决策记录）。它是 Spec 规范驱动（norm-chain-design §3.4）与 pre-commit 预检（`.githooks/spec-check.sh`）的作用对象。

## 命名与状态规范

- **命名**：`<名称>-design.md`（全小写 snake_case），禁止散放仓库根目录或其他位置。
- **头部状态行**：文档开头必须有引用行 `> 评审状态：<状态>`，取值三选一：
  - `📝 草案` —— 设计稿，待评审
  - `✅ 已评审` —— 评审通过，待实施
  - `已实施` —— 已按设计落地（由对应 `docs/modules/<module>.md` 反映实现）
  - 纯值，不带括号注释/组合态。
- **评审结论**：`✅ 已评审` 时建议另起一行 `> 评审结论：<一句话>`。

## 与模块文档的关系

| 目录 | 面向 | 作用 |
|---|---|---|
| `docs/plan-docs/`（本目录） | 方案/评审/决策 | 记录「打算怎么做」，实施后由模块文档反映落地 |
| `docs/modules/` | 开发与维护 | 记录「现状长什么样」，一个 feature 模块一份 |

设计定稿并实施后，由 `docs/modules/` 对应模块文档反映落地实现；两处不重复维护同一份内容。

## 预检规则（.githooks/spec-check.sh）

- 本次提交新增非 test 路径 `.kt` 或新增 feature 模块 → 提示需配套 `docs/plan-docs/*-design.md`（阻断 + `--no-verify` 逃生口）。
- 本次提交含 `*-design.md` → 校验头部 `> 评审状态：` 行存在且值合法（阻断 + 逃生口）；状态为 `✅ 已评审` 时建议另起一行 `> 评审结论：<一句话>`，缺失仅 warning 不阻断。
- 改 `AGENTS.md` / `prompts/` 行为规则文件 → 提示检查 `assets/sop/` 同步（warning 级、不阻断）。

## 索引

- [design-master-plan-design.md](./design-master-plan-design.md) —— 主设计域总纲（D0–D6 分层）
- [progress-tracker.md](./progress-tracker.md) —— 实施进度追踪（子任务级状态）
- 其余为各专题设计文档，按需查阅。
