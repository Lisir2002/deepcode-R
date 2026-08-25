---
name: 40-git-commit
order: 40
whenToUse: 需要提交代码（git commit）时
source: AGENTS.md「Git 提交规范」
---

# SOP-40 Git 提交（Commit）

> 权威源：`AGENTS.md`「Git 提交规范」。采用 Conventional Commits，由 `.githooks/commit-msg` 本地校验。

## 1. 检查改动面并确认分支

- **操作**：先确认当前改动类型与所在分支（main 直提或 feature 分支）。
- **判定**：新功能/复杂多文件改动/架构重构 → 分支（`feat/xxx` / `refactor/xxx`）；日常 bug 修复/单测/CI/纯文档/资源文案 → main 直提。
- **产出**：确定提交归属；分支混用则先整理。

## 2. 提交前必跑冒烟

- **操作**：改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 先 `./gradlew :app:assembleDebug`。
- **判定**：编译通过；debug buildType 快、不跑 R8。
- **产出**：可编译；失败则修复后再提交。

## 3. 推送前必跑单元测试

- **操作**：任何 `git push` 到远端前 → 先 `./gradlew :app:testReleaseUnitTest`（release classpath，与 CI 门禁同款）。
- **判定**：全部通过；纯文档/资源文案/纯 `.md` 改动可跳过。
- **产出**：测试全绿；失败则修复后再推送。

## 4. 编写并提交 Commit 信息

- **操作**：按格式 `type(scope): subject` 编写提交信息，subject 一行、句末不加句号。
- **判定**：type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test；scope 建议用功能模块。
- **产出**：`git commit` 通过 commit-msg 校验；被阻断则按 `type(scope): subject` 重写。

## 5. 合并与清理

- **操作**：分支合回 main 并确认无冲突后，清理已合并的本地分支（`git branch -d`，删前用 `git branch --merged main` 确认安全）；已推送过的分支同步删除远端。
- **判定**：分支删除不影响已打的 Tag（Tag 独立引用提交）。
- **产出**：分支清理完成；出错则不删分支并说明。
