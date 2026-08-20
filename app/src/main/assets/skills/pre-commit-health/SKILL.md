---
name: pre-commit-health
description: 提交前规范体检。AI 在准备 git commit 前调用本技能，对工作区待提交改动做一次仓库纪律体检（模块文档同步 / strings.xml 同步 / 版本号纪律 / 敏感信息 / targetSdk / prompts|docs 资产同步 / 迁移 SQL / 提交信息格式 / 分支纪律），输出阻断项与建议项报告并按报告修复，确保提交符合 AGENTS.md 纪律、减少 CI 返工。
version: 1.0.0
author: R-CodeCore
tags:
  - git
  - discipline
  - pre-commit
  - lint
type: script
scope: common
entry: entry/run.sh
---

# 提交前规范体检（pre-commit-health）

## 何时使用
在你即将执行 `git commit`（尤其是第一次提交、涉及多文件/新增模块/重构、或 CI 曾红过）之前，**主动调用本技能**做一次体检。它是一道「提交前用力关一道门」，把本该由 CI 或 `.githooks` 才暴露的问题提前到提交前拦截。

## 调用方式
- 直接请求执行本技能即可，传入参数：
  - `project_path`（可选）：项目目录。缺省时工具会自动以容器内 `/root/workspace` 作为项目路径。
- 执行后得到一份 UTF-8 报告，分 **阻断项（❌）** 与 **建议项（⚠️）**。

## 输出解读与修复口径
- **阻断项（❌）**：有任一存在即**不要提交**，逐条修复后再重跑本技能直到绿灯：
  - `C-1 模块文档同步`：改动了 `feature/<模块>/` 代码，但缺少 `docs/modules/<模块>.md`，或存在孤儿文档。→ 新建/删除对应模块文档（六段式），并在提交信息中使用 `docs` type 或标注。
  - `C-2 strings.xml 同步`：改动的 `.kt` 中出现未走 `R.string.*` 的用户可见中文文案。→ 提取为 `values/strings.xml` 里的 string resource，用 `stringResource(R.string.xxx)` 或 `context.getString(R.string.xxx)` 引用，**禁止硬编码中文到 .kt**。
  - `C-3 版本号纪律`：`app/build.gradle.kts` 出现手写 `versionName`/`versionCode`。→ 移除手写，版本号由 Git Tag 动态推导，勿自行维护。
  - `C-4 敏感信息`：改动文件中出现 token / 密钥 / 私钥特征。→ 立即移除，切勿 commit 进仓库。
  - `C-5 targetSdk 锁定`：`targetSdk` 被提高到 28 以上。→ 保持 28，勿"顺手修复"（破坏 PRoot W^X 绕过）。
- **建议项（⚠️）**：提醒项，按需处理，不强制阻断：
  - `W-1 prompts/docs 资产同步`：改动涉及 AI 工作流/工具签名/UI 变化时，检查 `assets/prompts/` 与 `assets/docs/` 是否需同步。
  - `W-2 模块文档是否记录本次行为变化`：功能行为变化应体现在对应 `docs/modules/<模块>.md`。
  - `W-3 迁移 SQL 字面量`：`assets/migrations/*.sql` 字符串字面量含 `;` 时应用 `char(59)`。
  - `W-4 提交信息格式`：报告末尾给出建议的 Conventional Commits 首行（`<type>(<scope>): <subject>`），type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test，句末不加句号。
  - `W-5 分支纪律`：当前在 `feat/*`/`refactor/*`/`hotfix/*` 分支时，提示不要在功能分支上打 Tag 发版。

## 纪律
- 本技能**只读**：只生成报告，不修改、不提交任何文件；修复动作由你依据报告执行。
- 报告基于「待提交改动」而非全仓静态扫描，速度快、聚焦。
- 修复后再跑一次，直至报告无阻断项（❌=0）再 commit。