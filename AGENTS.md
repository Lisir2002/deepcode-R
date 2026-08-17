# AGENTS.md

本文件为 R-CodeCore 项目的 **AI 协同开发规范**，是任意 AI Agent（Claude Code / Trae / Cursor / 自研 Agent 等）在本仓库工作时的唯一权威纪律源。App 运行时由 `SystemPromptProvider` 自动加载本项目规则（优先 `AGENTS.md`），拼入 System Prompt。

## 总则
- 永远使用中文回复。
- 优先使用已有工具进行文件操作（读取、修改、搜索等），不要用 shell 命令替代专用工具。

## 资产同步纪律
项目中的 `app/src/main/assets/prompts/` 和 `app/src/main/assets/docs/` 是 AI Agent 的核心知识来源，必须与代码保持同步：

- **AI 工作流相关改动 → 检查 prompts**：任何与 AI 工作流相关的改动（工具新增/删除/重命名/参数签名变化、agent 行为变化、提示词逻辑调整等），都必须检查 `app/src/main/assets/prompts/` 下的提示词是否需要同步更新，确保模型看到的工具定义与行为说明与实际一致。AI 应自行在 `prompts/` 目录中查找对应的提示词文件；若不存在则新建。
- **功能、工具变化 → 检查 docs**：任何功能新增/删除/行为变化或工具变更，还要检查 `app/src/main/assets/docs/` 下是否有对应使用文档需要更新（如新功能的使用说明、工具行为变化的提示）。
- **UI 变化 → 必须更新对应使用文档**：任何 UI 变化（新增页面、改交互、调布局、改文案）**必须**同步更新 `app/src/main/assets/docs/` 下对应的使用文档，确保用户可见的说明与实际界面一致。AI 应自行在 `docs/` 目录中查找对应的文档；若不存在则新建。
- **UI 文案 → 必须同步 strings.xml**：任何新增或修改用户可见的中文文案（按钮、标题、提示、Toast 等），**必须**将其提取为 string resource 写入 `app/src/main/res/values/strings.xml`（中文）和 `app/src/main/res/values-en/strings.xml`（英文翻译），并在 `.kt` 代码中用 `stringResource(R.string.xxx)` 或 `context.getString(R.string.xxx)` 引用。**禁止在 .kt 文件中硬编码中文 UI 文案。** 命名规范：语义化英文全小写下划线分隔，通用文案用 `common_` 前缀跨页面复用。

## Git 提交规范

项目采用 **Conventional Commits**，由 `.githooks/commit-msg` 在本地校验（启用见仓库根 `.githooks/`）。格式：

```
<type>(<scope>): <subject>

<可选正文，空行隔开>
```

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** 可选，建议用功能模块：`agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- **subject** 一行简述，中英文均可，句末不加句号。
- 跳过校验（仅紧急）：`git commit --no-verify ...`

示例：`feat(agent): 支持流式工具调用` / `fix(settings): 修复 provider 保存时校验失败` / `ci: 删除签名校验步骤`

## 分支与改动工作流

**原则：大功能/复杂改动拉分支，轻量修改/单测/修 Bug 直接在 `main` 操作。** 本仓库已全面采用 Tag 驱动发版，平时在 `main` 上的提交不会影响发布包，仅打 Tag 时才触发 GitHub Release。

- **改动分档**：
  - **新功能 / 复杂多文件改动 / 架构重构**：新建分支 `feat/xxx` 或 `refactor/xxx`，改完验证通过后合回 `main` 并清理分支。
  - **日常 Bug 修复 / 补单元测试 / CI与构建配置 / 纯文档 / 资源文案**：直接在 `main` 分支提交，无需新建分支，避免分支过滥。
  - **预览版（RC）热修复**：已发 RC Tag 后发现问题，必须从**该 RC Tag** 拉 `hotfix/xxx` 分支修复（**勿从最新 `main` 或功能分支拉**，否则会把已合入的未发版功能带进修复包），修复验证后升 rc 序号打 Tag 发修复版，再合回 `main` 并清理分支（详见「发版流程」）。
- **改动前先定分支**：涉及新功能开发时，先确认分支命名（如 `feat/session-model`），避免不同主题混在同一分支。
- **提交前必跑冒烟**：改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提交前默认 `./gradlew :app:assembleDebug` 验证可编译（debug buildType 快，不跑 R8）。验证 release 链路用 `:app:assembleRelease`，**项目已无 flavor 概念，不要使用 assembleUniversalDebug/assembleArmsolo 等旧命令**。
- **推送到远端前必跑单元测试**：任何 `git push` 到远端之前，必须先跑一次单元测试 `./gradlew :app:testReleaseUnitTest`（release classpath，与 CI 门禁同款），确认测试全部通过后再推送。改动不涉及逻辑（纯文档 / 资源文案 / 纯 `.md`）时可跳过。
- **合并入 main**：本地合并并确认无冲突后，及时清理已被合并的本地分支（`git branch -d <branch_name>`，删前用 `git branch --merged main` 确认安全）；已推送过的分支同步删除远端（`git push origin --delete <branch_name>`），避免本地删了远端残留。分支删除不影响已打的 Tag，Tag 独立引用提交，可随时 `git show <tag>` 追溯。

## 版本号规范

- **唯一事实源**：由 Git Tag / Commit 动态推导解析，**彻底无需手写 `app/build.gradle.kts` 中的 `versionName`**。
  - **`versionName`**：由 `gitVersionName()` 在构建时动态解析（如 tag 为 `v1.7.0` 则为 `1.7.0`；tag 为 `v1.7.0-rc1` 则为 `1.7.0-rc1`；非 Tag 的平时提交为 `1.7.0-dev.N+<hash>`）。
  - **`versionCode`**：由 `gitCommitCount()` 从 git 提交数自动生成，随提交单调递增，无需手动维护。
- **与 Tag 绑定**：发版时只需直接在 `main` 节点上打 git tag，例如 `v1.7.0-rc1` 或 `v1.7.0`，CI 捕获后会自动将生成的 APK 与该版本进行匹配并发布 Release。**严禁在功能分支（`feat/*` / `refactor/*`）上打 Tag 发版**，必须先合入 `main` 再打 Tag，确保发版的代码在 `main` 主线上可追溯。**唯一例外：预览版热修复**——RC 已发出后发现问题时，允许在基于该 RC Tag 的 `hotfix/*` 分支上打 rc 序号 +1 的 Tag 发修复版，修复必须随后合回 `main`（见「发版流程」）。

## 发版流程（RC 判定）

本项目靠 GitHub Release 分发且无灰度，发出去即终态，RC 是主要兜底。发版前按改动面判断是否先发 RC：

- **必须先发 RC**：本发版周期含新功能 / 行为变化（定档 `x.Y.0`）；或构建链路 / 签名 / ABI 打包策略 / CI 改动；或容器镜像、PRoot 相关改动。
- **可直接发正式**：本发版周期仅纯文档 / typo / 资源文案（定档 `x.y.Z`，无行为变化）。
- **看改动面**：本发版周期仅纯 bug 修复（定档 `x.y.Z`）——小改直接正式，触碰启动/容器的仍先 RC。

### 操作步骤

1. **零代码修改发版（必须在 `main` 分支）**：无需在代码或配置中修改版本号。所有功能/修补必须先合并到 `main` 分支，在 `main` 最新的提交节点上直接打 Tag（例如 `git tag v1.7.0-rc1`）并推送：`git push origin v1.7.0-rc1`。
2. CI 接收到 `v*` Tag 后，自动捕获 Tag 版本推导生成 APK，构建 Release 发出。
3. **真机装 rc 包**，至少跑通 AI 对话 + 终端 + 容器启动三条主线。
4. 有问题 -> 从该 RC Tag 拉 `hotfix/xxx` 分支修复（**勿从最新 `main` 拉**，否则会把已合入的未发版功能带进修复包）-> 升 rc 序号打 Tag（`v1.7.0-rc2`）推送重发 -> 将修复合回 `main` 并推送 -> 删除 hotfix 分支；无问题 -> 直接打正式 Tag（`v1.7.0`）推远端转正。

## 云端构建与实时监控自动化

Tag 推送到 GitHub 后，由 `.github/workflows/android-release.yml` 自动接管构建、签名、发布全流程。AI / 维护者必须能通过 GitHub API **实时监控构建进度**并**自动校验产物**。

### 触发方式

- **自动触发**：`git push origin v0.1.0-rcN` / `git push origin v0.1.0`，CI 接收 `v*` tag push 事件后自动启动。
- **手动触发**（仅测试用）：GitHub Actions 页面 → `android-release.yml` → Run workflow（workflow_dispatch），versionName = `manual-<run_number>`，**不作为正式发版**。

### CI 全流程（单 job `build`，6 个逻辑阶段）

> workflow 实际是**单 job 多 step**结构（jobs.build），下述 6 个阶段是按职责划分的逻辑阶段，对应 step 序列。

1. **variables** → `Display release tag info` + `Verify versionCode monotonic`（versionCode 单调递增校验）+ `Determine release name`（手动触发用 `manual-<run_number>`）+ `Determine prerelease flag`（tag 含 `-rc/-dev/-beta/-alpha` 后缀自动标记 prerelease）
2. **build** → `:app:testReleaseUnitTest`（发版质量门禁）→ `:app:assembleRelease` → `Restore release keystore`（还原 `AICODE_KEYSTORE_BASE64` 到 `app/rcodecore.jks`）→ `Generate keystore.properties`（用 4 个签名 secrets 生成临时 `keystore.properties`）→ **正式签名**构建 APK 到 `app/build/outputs/apk/release/app-release.apk` → `Rename APK` 重命名为 `dist/rcodecore-arm64-<tag>.apk`
3. **upload-mapping** → `Upload R8 mapping`（`actions/upload-artifact@v4`，artifact 名 `r8-mapping-<tag>`，90 天保留，`if-no-files-found: ignore` 不阻塞）
4. **create-release** → `Generate changelog from git log` + `Create GitHub Release & Upload assets`（`softprops/action-gh-release@v2`，prerelease 取决于 tag 是否含预发布后缀）
5. **upload-apk** → 与 create-release 同 step 完成（`files: dist/rcodecore-arm64-*.apk` 挂到 Release Assets）
6. **summary** → `Write download URLs to Run Summary`（写入 Tag / Prerelease / APK 文件名 / SHA256 / Release 页面 / mapping artifact 名到 `$GITHUB_STEP_SUMMARY`）

### 实时监控命令（GitHub API）

```bash
# 1. 查询最新 run 状态（tag 触发）
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/workflows/android-release.yml/runs?per_page=5" \
  | python3 -c "import sys,json;[print(r['id'],r['status'],r.get('conclusion','-'),r['head_branch']) for r in json.load(sys.stdin)['workflow_runs']]"

# 2. 查询指定 run 的每个 job 进度
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/runs/<run_id>/jobs" \
  | python3 -c "import sys,json;[print(j['name'],j['status'],j.get('conclusion','-')) for j in json.load(sys.stdin)['jobs']]"

# 3. 查询 Release 是否已创建 + APK asset
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/releases/tags/<tag>" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('tag_name'),d.get('prerelease'));[print(a['name'],a['size'],a['browser_download_url']) for a in d.get('assets',[])]"
```

### 产物校验清单（构建完成后必跑）

1. **下载 APK** → `curl -sL -u "<owner>:<token>" -o rcodecore-arm64-<tag>.apk "<browser_download_url>"`
2. **ABI 校验** → `unzip -l <apk> | grep lib/` 必须只有 `lib/arm64-v8a/*.so`，无 x86_64
3. **签名校验** → `keytool -printcert -jarfile <apk>` → Owner 必须为正式签名（非 `CN=Android Debug`）
4. **SHA256** → `sha256sum <apk>` 记录指纹
5. **Release 页面** → https://github.com/Lisir2002/deepcode-R/releases/tag/<tag>

### 签名 Secrets 前置条件（构建正式签名 APK 必须配置）

仓库 `Settings → Secrets and variables → Actions` 必须配置以下 4 个 secrets：

| Secret 名称 | 取值 |
|---|---|
| `AICODE_KEYSTORE_BASE64` | `app/rcodecore.jks` 文件的 base64 编码 |
| `AICODE_KEYSTORE_PASSWORD` | keystore 的 storePassword |
| `AICODE_KEY_ALIAS` | 签名 key 的 keyAlias |
| `AICODE_KEY_PASSWORD` | key 的 keyPassword |

**验证 secrets 是否存在**：
```bash
curl -s -u "<owner>:<token>" \
  "https://api.github.com/repos/<owner>/<repo>/actions/secrets?per_page=30" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('secrets 总数:',d.get('total_count',0));[print(' -',s['name']) for s in d.get('secrets',[])]"
```

> **⚠️ 若 secrets 总数 = 0 或缺少任一**：CI 构建的 APK 会**回退到项目级固定 debug keystore 签名**（`CN=Android Debug, O=Android, C=US`，alias=`androiddebugkey`，密码均为 `android`，APK 签名证书 SHA256 固定 = `7A:D5:EA:0E:3F:A9:6F:10:26:29:21:0C:9C:DB:AA:81:E3:CE:D4:9B:32:20:A5:21:7B:64:EC:1A:95:D2:FA:C8`）。
> 该方案**保证所有未配置正式签名的 Tag 构建输出同一份证书指纹的 APK**：RC24 / RC25 / … / RC∞ 之间覆盖安装不会再报「软件包与现有软件包冲突」。
> 但它**仍不可上架**（证书 Owner 必须为开发者主体，而非 Android Debug），若需上架请配置上面 4 个 secrets。


## 构建与运行

本项目是 Android 应用，使用 Kotlin + Jetpack Compose + Hilt 构建，Gradle 为构建系统。

- **完整构建**：`./gradlew build` —— 含 debug/release 两 buildType 全量编译 + lint + 测试，耗时极长，日常开发不用。
- **单 buildType 冒烟（AI 改完代码默认跑这个）**：`./gradlew :app:assembleDebug` —— debug buildType 快，不跑 R8。验证 release 链路跑 `./gradlew :app:assembleRelease`。**项目已无 flavor 概念，不要使用 assembleUniversal/assembleArmsolo/assembleX86solo 等旧命令**。
- **Release APK**：`./gradlew assembleRelease` —— 真机 arm64-v8a 单架构 APK，输出到 `app/build/outputs/apk/release/app-release.apk`
- **Release AAB**：`./gradlew bundleRelease` —— 输出到 `app/build/outputs/bundle/release/app-release.aab`
- **单元测试**：`./gradlew :app:testReleaseUnitTest`（release classpath，日常 / push 前推荐）；`./gradlew :app:testDebugUnitTest`（debug classpath）。

### Release 签名配置

签名配置在 `app/build.gradle.kts` 中自动处理：
- **Keystore 文件**：路径由 `app/keystore.properties` 的 `storeFile` 字段指定（文件名可自定义，不固定为 `aicode.jks`）。本地通常不存放签名文件，CI 从 GitHub secret 还原到 `app/rcodecore.jks`。
- **凭据**：从 `app/keystore.properties` 加载（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。
- **目标 ABI**：单架构 `arm64-v8a` 真机；x86_64 模拟器不做正式支持。

*注：项目锁定 `targetSdk = 28` 以绕过 Android 10+ W^X 策略，使 PRoot 可执行。*

## 架构概览

应用采用基于功能的架构（Feature-based Architecture）与领域驱动设计（DDD）原则，重度依赖 Jetpack Compose（UI）、Hilt（依赖注入）、Kotlin Coroutines/Flow（异步）。

### 关键组件

- **App 入口**：`AIEditorApp` 初始化核心服务（`FileLogger`、`TerminalKeepaliveService`、`McpManager` 等）。
- **Core 模块**：`app/src/main/java/com/deep/rcode/core/` 承载跨功能基础设施：`FileLogger`、`db/MigrationLoader.kt`、`CredentialEncryptor`、主题等。
- **Feature 模块**：代码按功能组织在 `app/src/main/java/com/deep/rcode/feature/`：
    - `agent`：核心 AI Agent 系统。含提示词管理、MCP（Model Context Protocol）集成、工具注册（文件工具、Shell 执行等）、权限处理、多 Provider 适配（Anthropic、OpenAI、Gemini）。
    - `git`：Git 集成与可视化操作。
    - `settings`：应用配置（AI Provider、容器、MCP、远程、日志等）。
    - `terminal`：终端模拟与会话管理。本地模式用 Termux 组件（`terminal-emulator`、`terminal-view`）+ PRoot（`LinuxContainerEngine`）；远程 SSH 模式用 sshj（`SshShellBackend`、`RemoteTerminalSessionManager`）。
    - `workspace`：工作区与文档管理。远程 SSH 文件访问经 `RemoteSftpFileAccess`。
    - `credentials`：Git 凭据统一管理（三端共用：UI Git / AI Bash / 终端 git）。
    - `backup`：AES 加密备份与恢复。
- **远程 SSH 链路**：`RemoteSshConnection`（共享 sshj `SSHClient`）+ `RemoteSshEngine`（exec channel 执行命令）+ `RemoteSftpFileAccess`（文件操作）+ `RemoteTerminalSessionManager`（终端会话），构成远程模式下的执行链路。

### 数据库

应用使用 Room 做本地数据库存储，核心定义在 `feature/agent/data/local/database/AgentDatabase.kt` 及相关 DAO（如 `ChatSessionDao`、`AgentMessageDao`）。

**数据库迁移**：
采用自定义的轻量级文件驱动迁移系统（`MigrationLoader.kt`）。更新数据库 schema 步骤：
1. 在 `AgentDatabase.kt` 中递增数据库版本号。
2. 在 `app/src/main/assets/migrations/` 下新建 SQL 文件，命名为 `{VERSION}_description.sql`（如 `8_add_remote_servers.sql`、`26_add_session_last_input_tokens.sql`）。
3. 在该文件中写入必要的 DDL/SQL 语句。系统会在启动时自动执行并记录到 `migration_history` 表。
   - ⚠️ **注意**：迁移文件按 `;` 切分语句（见 `MigrationLoader`），因此 **SQL 字符串字面量里不能出现 `;`**（例如不要写 `';base64,'`）——会被切分器误切导致整个迁移失败。需要字面量分号时用 `char(59)`。

### AI Agent 与工具

AI Agent 通过工具系统（`feature/agent/domain/tool/`）与环境交互。可用工具包括文件操作（`FileTools.kt`）、Shell 执行（`ExecuteCommandTool.kt`）、终端管理、网页搜索、询问用户等。工具经 `ToolRegistry` 注册管理。工具执行权限（如 Shell 命令）由 `ToolPermissionManager` 和 `ToolPermissionPolicyEngine` 治理。

### MCP（Model Context Protocol）

应用实现了 MCP 客户端（`feature/agent/domain/mcp/`），可连接远程服务器并动态注册其提供的工具。

### 依赖注入

Hilt 被广泛使用。各 Feature 模块定义自己的 DI 模块（如 `AgentModule.kt`、`RepositoryModule.kt`、`BackupModule.kt`）向实现提供接口。
