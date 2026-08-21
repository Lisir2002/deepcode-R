---
name: pre-commit-health
description: 提交前规范体检。在准备执行 git commit 前调用，对工作区待提交改动做仓库纪律体检并输出「阻断项/建议项」报告：模块文档同步、strings.xml 同步、版本号纪律、targetSdk 锁定、敏感信息与密钥文件（含高熵密钥/二进制文件）、合并冲突标记、Git 中间状态、技能资产 frontmatter、构建产物与超大文件、调试残留、prompts|docs 资产同步、迁移 SQL、提交信息格式、分支纪律、diff 预算与原子性、文件卫生（含 CRLF）、超长行、游离 HEAD、依赖锁定、.gitignore 缺口、子模块嵌套仓库、硬编码绝对路径、shebang 一致性、编码与结构化文件雷区、依赖版本未锁定、大删除确认、.gitattributes 归一化、工作流供应链安全、内网私有 IP；按报告修复后重跑至无阻断项再提交，减少 CI 返工。触发词：提交前检查、规范体检、pre-commit、commit 前体检。
version: 1.7.0
author: R-CodeCore
tags:
  - git
  - discipline
  - pre-commit
  - lint
  - security
type: script
scope: common
entry: entry/run.sh
license: Apache-2.0
auto_trigger: true
trigger_conditions: 用户请求执行 git 提交（commit）、打 Tag、合并分支、暂存/撤销等版本控制写操作，或准备提交代码之前（首次提交、多文件/新增模块/重构提交时最适用）。纯代码编写、代码审查、答疑等与提交动作无关的任务不触发。
compatibility: 需要容器内具备 git / grep / sed / awk / tail（内置 Alpine 已含），缺失时自动降级跳过对应检查。
metadata:
  repo: R-CodeCore
---

# 提交前规范体检（pre-commit-health）

## 何时使用（激活触发词）
在你即将执行 `git commit`（尤其是第一次提交、涉及多文件/新增模块/重构、或 CI 曾红过）之前，**主动调用本技能**做一次体检。它是一道「提交前用力关一道门」，把本该由 CI 或 `.githooks` 才暴露的问题提前到提交前拦截。
触发场景：用户说"提交前检查 / 规范体检 / 跑一下体检 / commit 前检查"，或你准备执行 git commit / 打 Tag 之前。

## 调用方式
- 直接请求执行本技能即可，传入参数：
  - `project_path`（可选）：项目目录。缺省时工具会自动以容器内 `/root/workspace` 作为项目路径。
- 执行后得到一份 UTF-8 报告，分 **阻断项（❌）** 与 **建议项（⚠️）**，末尾给出汇总与结论。

## 分层检查（B1）
本技能按项目类型分层执行，**非 Android 项目不会误报 Android 专属项**：

- **通用检查（对任意 git 项目生效）**：C-4 敏感信息、C-6 合并冲突标记、C-7 构建产物/超大文件、C-8 敏感文件类型、C-9 调试残留、C-10 Git 中间状态、C-11 技能资产 frontmatter、C-12 二进制文件、C-13 高熵密钥、W-4 提交信息格式、W-5 分支纪律、W-6 diff 预算、W-7 待办标记、W-8 原子性、W-9 文件卫生、W-10 超长行、W-11 游离 HEAD、W-12 依赖锁定、W-13 .gitignore 缺口、W-25 工作流供应链安全、W-26 内网私有 IP。
- **Android 专属检查（仅当识别为 Android 项目，即存在 `app/build.gradle.kts` 或 `app/build.gradle`）**：
  C-1 模块文档同步、C-2 `.kt` 硬编码中文、C-3 手写版本号、C-5 targetSdk 锁定、W-1 prompts/docs 资产同步、W-2 模块文档行为变化、W-3 迁移 SQL 字面量。
- 报告开头会打印 `[类型]` 一行说明本次按哪种项目识别执行，方便对照检查范围。

## 输出解读与修复口径
- **阻断项（❌）**：有任一存在即**不要提交**，逐条修复后再重跑本技能直到绿灯：
  - `C-1 模块文档同步`：改动了 `feature/<模块>/` 代码，但缺少 `docs/modules/<模块>.md`，或存在孤儿文档。→ 新建/删除对应模块文档（六段式）。
  - `C-2 strings.xml 同步`：改动的 `.kt` 中出现未走 `R.string.*` 的用户可见中文文案。→ 提取为 `values/strings.xml` 里的 string resource 并用 `stringResource(R.string.xxx)` / `context.getString(R.string.xxx)` 引用，**禁止硬编码中文到 .kt**。
  - `C-3 版本号纪律`：`app/build.gradle.kts` 出现手写 `versionName`/`versionCode`。→ 移除手写，版本号由 Git Tag 动态推导。
  - `C-4 敏感信息`：改动文件中出现 token / 密钥 / 私钥 / AWS 凭据特征。→ 立即移除，切勿 commit 进仓库。
  - `C-5 targetSdk 锁定`：`targetSdk` 被提高到 28 以上。→ 保持 28，勿"顺手修复"（破坏 PRoot W^X 绕过）。
  - `C-6 合并冲突标记`：文件残留 `<<<<<<<` / `>>>>>>>`。→ 先解决冲突再提交。
  - `C-7 构建产物/超大文件`：提交了 `build/`、`*.apk`、`*.aab` 等产物或 >5MB 文件。→ 加入 `.gitignore`，体积过大改用 LFS。
  - `C-8 敏感文件类型`：提交了 `.env`（含 `.env.local`/`.env.production` 等变体）、`.aws/credentials`、`.credentials`、`.secrets`、`*.pem`、`*.key`、`*.keystore` 等凭据/私钥/认证配置文件。→ 移除并加入 `.gitignore`。注意：源码内硬编码凭据由 C-13 兜底，不按文件名拦源码。
  - `C-9 调试残留`：主源码出现 `Log.d/v/i`、`println(`、`debugger;`（Kotlin）或 `console.log`/`debugger`（JS/TS）。→ 使用项目标准日志（FileLogger）或移除。
  - `C-10 Git 中间状态`：仓库正处于 merge/rebase/cherry-pick/revert 进行中。→ 先完成（`git merge --continue` / `rebase --continue`）或中止（`--abort`）再提交，否则会破坏合并/变基历史。
  - `C-11 技能资产 frontmatter`：新增/修改的 `SKILL.md`/`CLAUDE.md` 缺 `---` 分隔符或 `name`/`description` 字段。→ 补全 frontmatter，否则技能无法被 AI 识别与触发。
  - `C-12 二进制文件`：提交了二进制/不可 diff 文件（编译产物/资源）。→ 产物加入 `.gitignore`，必要资源改用 LFS 或文本化；媒体/字体等合法二进制资产不拦。
  - `C-13 高熵密钥/敏感赋值`：变量名含 `KEY/SECRET/TOKEN/PASSWORD` 被赋长值，或出现高熵长串（疑似随机密钥）。→ 若为真实凭据移入环境变量/密钥管理，示例/占位值忽略。已跳过锁文件/压缩产物/测试目录以降低误报。
- **建议项（⚠️）**：提醒项，按需处理，不强制阻断：
  - `W-1 prompts/docs 资产同步`：改动涉及 AI 工作流/工具签名/UI 变化时，检查 `assets/prompts/` 与 `assets/docs/` 是否需同步。
  - `W-2 模块文档是否记录本次行为变化`：功能行为变化应体现在对应 `docs/modules/<模块>.md`。
  - `W-3 迁移 SQL 字面量`：`assets/migrations/*.sql` 字符串字面量含 `;` 时应用 `char(59)`。
  - `W-4 提交信息格式`：报告末尾给出建议的 Conventional Commits 首行（`<type>(<scope>): <subject>`），type ∈ feat/fix/refactor/docs/style/chore/ci/build/perf/test，subject ≤72 字符、句末不加句号。
  - `W-5 分支纪律`：当前在 `feat/*`/`refactor/*`/`hotfix/*` 分支时，提示不要在功能分支上打 Tag 发版。
  - `W-6 diff 预算`：改动 >400 行或 >40 文件时，建议拆分为多个原子提交以保 review 质量。
  - `W-7 待办标记`：源码残留 `TODO/FIXME/HACK/XXX`，提交前确认是否已处理。
  - `W-8 原子性`：改动横跨 ≥3 个 feature 模块时，建议按主题拆分提交。
  - `W-9 文件卫生`：存在行尾空白或文件末尾缺换行符。→ 清理行尾空格、补 EOF 换行（POSIX 文本约定）。
  - `W-10 超长行`：存在 >240 字符的纯 ASCII 超长行。→ 拆分以保可读性/工具兼容。
  - `W-11 游离 HEAD`：当前处于 detached HEAD。→ 先 `git checkout <branch>` 再提交，避免提交丢失。
  - `W-12 依赖锁定文件`：提交含 lockfile（package-lock.json/yarn.lock 等）。→ 确认依赖清单与锁定文件同步并一并提交，避免版本漂移。
  - `W-13 .gitignore 缺口`：易误提交的产物/敏感文件未被 `.gitignore` 忽略。→ 补充对应规则（`git check-ignore` 判定）。
  - `W-25 工作流供应链安全`：`.github/workflows/*` 中 action 未固定到完整 SHA（`@main`/`@v1`）、`pull_request_target` + `actions/checkout` 未用 `ref:` 锁定、`curl|sh` 管道执行远程脚本。→ 固定完整 40 位 SHA、用 `ref:` 固定分支或改用 `pull_request`、下载后校验哈希再执行（借鉴 zizmor/actionlint）。
  - `W-26 内网私有 IP`：源码硬编码 `10./192.168./172.16-31./127.0.0.1/169.254.` 等内网地址。→ 改为配置/环境变量，防拓扑泄漏与环境迁移失效。

## 纪律
- 本技能**只读**：只生成报告，不修改、不提交任何文件；修复动作由你依据报告执行。
- 报告基于「待提交改动」而非全仓静态扫描，速度快、聚焦；已跟踪/未跟踪改动均覆盖。
- 修复后再跑一次，直至报告无阻断项（❌=0）再 commit。

## 故障口径（脚本报错时）
本技能入口脚本面向 Alpine/busybox 环境（`sh` + `grep` + `sed` + `awk`），已做兼容处理；
但若执行时出现「脚本语法错误 / 无法完成体检」类输出，遵循以下口径：

1. **只读不修**：体检报告或脚本本身发现问题时，**不要**在调用侧修改技能资产（`entry/run.sh`、
   `SKILL.md`）。技能资产属于内置只读资产，应由维护者修完重新发版，而不是由 AI 在会话里就地改。
2. **先复核再报错**：先确认是「脚本 bug」（可在本机用 `sh -n` 验证语法）还是「环境缺依赖」
   （git/grep/sed/awk 缺失）。确为脚本 bug 时，按兼容性约束修复后仍需 CI 护栏 + 本地 `sh -n` 双重验证。
3. **降级不硬崩**：若个别检查因缺工具无法执行，脚本应跳过该项并提示「部分检查降级」，而不是整体失败。
4. **遇到无法完成的情况**：如实向用户报告「无法完成 + 原因」，不编造体检结论；若为环境问题，
   可尝试在具备依赖的容器内重跑。
