# Git 版本管理

在项目工作区打开 Git 页面，可视化管理代码版本。页面分三个标签页：**状态**、**分支**、**提交**。非 Git 仓库进入时会提示初始化（`git init`）。

## 1. 状态 (Tab 1)

查看当前仓库的工作区状态，分暂存区与工作区两部分：

*   **当前分支与跟踪信息**：顶部显示当前分支名、领先/落后远程的提交数。
*   **文件改动列表**：
    *   **已暂存**：点击文件可取消暂存（`git reset HEAD`）。
    *   **未暂存**：点击文件可暂存该文件（`git add`）。
    *   **未跟踪**：新文件，点击可暂存。
*   **批量操作**：「全部暂存」（`git add -A`）与「全部取消暂存」（`git reset`）。
*   **提交**：点击「提交」弹出输入框填写提交信息（`git commit -m`）。提交前需已配置署名（user.name / user.email），否则会失败并提示去设置中填写。
*   **拉取 / 推送**：
    *   **拉取**（`git pull`）：需已配置远程仓库。
    *   **推送**（`git push`）：需已配置远程仓库；当前分支无上游时自动 `git push --set-upstream` 首推建关联。
*   **署名与凭据**：页面底部可配置提交署名（user.name / user.email）与仓库地址（remote.origin.url），优先项目级、无则写全局。HTTPS 凭据由容器 `credential.helper` 链自动注入。

## 2. 分支 (Tab 2)

查看与管理分支引用，分本地分支、远程分支、标签三组：

*   **概览卡**：显示当前分支名，以及本地/远程/标签数量。
*   **HEAD**：当前检出的分支。
*   **Local**：本地分支列表，按路径树形展示。分组标题右侧「+」按钮可新建分支。
*   **Remote**：远程分支列表（`origin/xxx`）。
*   **Tags**：标签列表。分组标题右侧「+」按钮可新建标签（从当前 HEAD 创建轻量标签）。
*   **切换分支**：点击任意分支或标签弹出确认框，确认后切换（`git checkout`）。点远程分支会创建本地跟踪分支并切换。切换到标签会进入 detached HEAD 状态。
*   **长按操作菜单**：长按本地分支弹出操作菜单（切换/重命名/删除），长按远程分支弹出操作菜单（切换/删除），长按标签弹出操作菜单（切换/删除）：
    *   **新建分支**：点击 Local 分组标题右侧「+」按钮，弹出底部对话框：
        *   填写分支名（必填）。
        *   选择基准分支（下拉框，默认当前分支）。
        *   「创建并切换」开关（默认开启）：开启则创建并切换（`git checkout -b`），关闭则仅创建不切换（`git branch`）。
    *   **重命名分支**：长按本地分支 → 操作菜单选「重命名」，弹窗输入新名（`git branch -m`）。
    *   **删除分支**：长按分支 → 操作菜单选「删除」，弹出确认框：
        *   **本地分支**：安全删除（`git branch -d`），仅删除已合并的分支；未合并或当前分支会被拒绝并提示原因。
        *   **远程分支**：删除远端分支（`git push <remote> --delete`），该操作不可撤销。当前分支不可删除。
    *   **新建标签**：点击 Tags 分组标题右侧「+」按钮，弹出底部对话框输入标签名，从当前 HEAD 创建轻量标签。
    *   **删除标签**：长按标签 → 操作菜单选「删除」，弹出确认框删除本地标签。

## 3. 提交 (Tab 3)

查看最近 50 条提交记录（`git log`）：

*   每条提交显示短哈希、作者、相对时间、提交信息。
*   点击提交可展开查看该次提交改动的文件清单。
*   再次点击折叠。

## 常见错误提示

操作失败时，Git 的原始报错会被匹配成友好中文提示：

| 场景 | 提示 |
|---|---|
| 删除当前所在分支 | 无法删除当前所在分支，请先切换到其他分支 |
| 删除有未合并提交的分支 | 该分支有未合并的提交，无法安全删除 |
| 切换到不存在的分支 | 分支或引用不存在 |
| 切换时本地改动会被覆盖 | 本地有未提交的改动会被覆盖，请先提交或暂存后再切换 |
| 推送被拒（远程有更新） | 推送被拒绝，远程有更新的提交，请先拉取 |
| 远程鉴权失败 | 远程鉴权失败，请检查凭据配置（用户名/密码/Token） |
| 未配置提交署名 | 尚未配置提交署名，请在设置中填写用户名和邮箱 |
| 未配置远程仓库 | 未配置远程仓库，无法推送/拉取 |

## AI 辅助 Git 工程化（gitops）

对话中调用 `gitops` 工具，或在容器终端运行 `./scripts/gitops/gitops.sh`（需要 `git config core.hooksPath .githooks` 启用 hooks），可把项目 Git 规范、提交纪律、发版流程、版本日志生成等经验自动执行。支持的子命令：

| 场景 | 调用示例 |
|---|---|
| 提交信息规范化 | `gitops check_commit --message "feat(agent): 新增流式工具调用"` |
| 基于改动自动生成提交建议 | `gitops suggest_commit`（AI：`gitops(action="suggest_commit")`） |
| 检查本地 hooks 启用状态 | `gitops hooks-status`（未启用时会给出启用指引） |
| 发版前体检 + RC 判定 | `gitops release-check v1.2.3-rc1` 或 `gitops release-check v1.2.3` |
| 本地打 Tag（推送交给外部） | `gitops release-tag v1.2.3-rc1` |
| 自动生成版本日志草稿 | `gitops changelog v1.2.0`（缺省用最近历史 tag） |

**RC 判定规则**（对齐仓库发版纪律）：
- tag 含 `-rc` / `-beta` / `-alpha` / `-dev` 后缀 → 按 RC 处理
- 改动触及启动 / 容器 / 构建链路（`AndroidManifest.xml`、`AIEditorApp.kt`、`feature/terminal/`、`feature/container/`、`app/build.gradle.kts`、`.github/workflows` 等）→ 建议先发 RC 预览版
- 含功能代码改动 → 建议先发 RC
- 仅 `.md` / `values/strings.xml` 文档或资源文案改动 → 可直接发正式版
- 无提交变更 → 可直接发正式版（需人工确认 tag 语义）

**发版建议工作流**（AI 会在 `release-check` 结果里一并返回 next_steps）：
1. 切到 `main` 分支 + 提交干净
2. `gitops release-check vX.Y.Z-rc1` 取 RC 判定建议
3. 若建议 RC：`gitops release-tag vX.Y.Z-rc1` → Bash 执行 `git push origin vX.Y.Z-rc1` 推送触发 CI → 真机验证 AI 对话 / 终端 / 容器启动三条主线
4. 若无阻塞且直接发正式：`gitops release-tag vX.Y.Z` → Bash 执行 `git push origin vX.Y.Z` → CI 自动构建 Release
5. 每次发版前用 `gitops changelog <prev-tag>` 生成版本日志草稿，润色后写入 `CHANGELOG.md` 与 `docs/modules/<module>.md` 版本演进

**注意**：`release-tag` 仅在本地创建 tag，推送由外部 Bash 完成，凭据由 `credential.helper=store` 自动注入。
