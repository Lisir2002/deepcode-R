# precommit-health-design

> 评审状态：✅ 已实施（首款内置技能 pre-commit-health 已落地；v0.1.0-rc175 首包，
> v0.1.0-rc176 携带 B1 分层 + busybox 兼容修复 + CI 语法护栏）
>
> 主题：首款内置 Skill「提交前规范体检」（pre-commit-health）的设计。定义体检范围、执行契约
> （SCRIPT 脚本 + PROMPT 修复指引双形态）与工程化落地清单。

## 1. 背景与问题

仓库维护了大量硬性纪律（见 [AGENTS.md](../../AGENTS.md)）：Conventional Commits、模块文档同步、
`strings.xml` 文案同步、prompts/docs 资产同步、版本号由 Git 动态推导、targetSdk 锁定 28、敏感信息禁入、
迁移 SQL 字面量禁用 `;`、设计文档归入 `docs/plan-docs/`、不在功能分支打 Tag 等。

这些纪律目前仅由 `.githooks/pre-commit` 与 `.githooks/commit-msg` 做**单点**机械校验（覆盖范围有限），
且更多规则靠 AI/人自我约束。常见的漏检或违规包括：改 `feature/` 新增模块未同步 `docs/modules/`、
`.kt` 里写死中文、改了工作流没更 prompts、手动改 versionName、提交信息不合规——最终表现为 CI 红、
合入冲突、PR 被打回。这些在提交前如果能被「体检」主动拦截并给出修复指引，能显著降低返工。

## 2. 设计目标

1. **提交前主动拦截**：在 AI 即将 commit 时，一键对工作区改动做一次规范体检，输出「阻断项 / 建议项」。
2. **补全机械钩子盲区**：在 `.githooks` 单点检查之外，覆盖 strings.xml 同步、版本号纪律、敏感信息、
   迁移 SQL、设计文档归位等靠人自觉的规则。
3. **双形态承载**：SCRIPT（跑检查脚本产报告）+ PROMPT（SKILL.md 指令正文引导 AI 理解报告并修复），
   一次技能同时给「体检能力」与「修复口径」。
4. **项目路径契约化**：脚本技能通过 `SKILL_PROJECT_PATH` 环境变量拿到宿主真实项目路径，在容器内
   对项目目录执行 git / 文件检查。
5. **零侵入规范**：技能只读检测 + 输出报告，不改任何文件，不写库、不建表，纯资产 + 少量注入改动。

## 3. 技能定位

| 维度 | 取值 | 说明 |
| --- | --- | --- |
| 名称 | `pre-commit-health` | 大模型调用的唯一标识 |
| 类型 | SCRIPT | 由入口脚本在容器内跑检查织出 UTF-8 报告 |
| 来源 | BUILTIN | 随 App 预置，只读 |
| 作用域 | COMMON | 默认所有 agent 可用，用户在设置可关（非强制常驻，体现作用域分级） |
| 适用模式 | 全模式（不限定 `modes`） | 提交前任意模式都可用 |
| 运行态 | 容器内 `bash` | 复用既有 SCRIPT 执行链 + 审批 + 审计 |

## 4. 体检范围（检查项）

报告按「阻断项（❌，退出码非 0）/ 建议项（⚠️）」两级分类，全部基于工作区的**待提交改动**
（`git diff HEAD` / 未跟踪文件）而非全仓静态扫描，保证聚焦、快、可复现：

### 4.1 阻断项（有任一即建议先修再提交）

- **C-1 模块文档同步**：改动涉及 `feature/<module>/*` 时，必须有对应 `docs/modules/<module>.md`；
  `docs/modules/` 下不得出现无对应 feature 目录的孤儿文档（与 `.githooks/pre-commit` 同口径，但更早暴露）。
- **C-2 `strings.xml` 同步**：新增/修改 `.kt` 后，若改动含用户可见中文/UI 文案，`values/strings.xml`
  与 `values-en/strings.xml` 中应能检索到对应 resource（项目已切中文单语言，`values-en` 不再维护，
  仅校验 `values/strings.xml`，并提示「若误新增 UI 文案需提取为 `R.string.*`」）。
- **C-3 versionName/versionCode 稳定性**：不允许 `app/build.gradle.kts` 出现手写 `versionName`/`versionCode`
  赋值（版本号必须由 Git Tag 动态推导）。
- **C-4 敏感信息**：改动文件不应出现明显 token/secret 特征（`ghp_`、`api_key =`、私钥头等），
  防止 commit 到仓库。
- **C-5 targetSdk 锁定**：禁止把 `targetSdk` 相关配置从 `28` 改高（破坏 PRoot W^X 绕过）。
- **C-6 合并冲突标记**：改动文件残留 `<<<<<<<` / `>>>>>>>`，需先解决冲突再提交。
- **C-7 构建产物/超大文件/临时备份**：`build/`、`*.apk`/`*.aab`、`*.orig`/`*.rej`/`*.tmp`/`*.bak`/`*~`/`*.swp`、
  `.idea/`、`node_modules/`、`local.properties`、`*.log` 等产物/临时/IDE/日志文件，或 >5MB 文件（借鉴
  pre-commit-hooks 的 check-added-large-files）。
- **C-8 敏感文件类型**：`.env` 及其变体（`.env.local`/`.env.production`）、`.aws/credentials`、`.credentials`、
  `.secrets`、`*.pem`/`*.key`/`*.keystore`/`*.jks`/`*.p12`/`*.pfx`、`*id_rsa*`/`*id_ed25519*`、`.npmrc`/`.pypirc`/
  `.htpasswd`/`.netrc` 等凭据/私钥/密钥库/认证配置。注意**不用** `*secret*`/`*credentials*` 通配拦文件名
  （防误伤 `SecretService.kt` 等合法源码），源码内硬编码凭据由 C-13 兜底。
- **C-9 调试残留**：Kotlin 主源码 `Log.d/v/i`、`println(`、`debugger;`（排除测试目录与 FileLogger），
  JS/TS `console.log`/`debugger`/`alert`。
- **C-10 Git 仓库中间状态**：merge/rebase/cherry-pick/revert 进行中（`.git/MERGE_HEAD` 等存在）时提交
  会破坏合并/变基历史（借鉴 claude-commit-skill 的 safety gates）。
- **C-11 技能资产 frontmatter**：新增/修改的 `SKILL.md`/`CLAUDE.md` 缺 `---` 分隔符或 `name`/`description`
  字段（借鉴 lint-skills），技能将无法被正确识别与触发。
- **C-12 二进制/不可 diff 文件**：`git diff --numstat` 两列均为 `-` 或未跟踪文件 `grep -I` 判二进制；
  媒体/字体等合法二进制资产白名单放行（借鉴 pre-commit-hooks）。v1.7.0 提速：采样前 8KB 判定，避免整读超大文件。
- **C-13 高熵密钥/敏感赋值**：借鉴 gitleaks/detect-secrets 的「正则 + 熵」双层——正则只防已知前缀（C-4），
  随机形态的密钥靠 Shannon 熵检测（>=28 字符且熵 >=4.6）兜底，仅警告不阻断。v1.7.0 降噪：跳过锁文件/
  压缩产物/测试目录，先 grep 预筛长串再跑熵计算（提效）。

### 4.2 建议项（提醒，不强阻断）

- **W-1 prompts/docs 资产同步**：改动涉及 AI 工作流/工具签名变化时，提示检查
  `app/src/main/assets/prompts/` 与 `assets/docs/` 是否需同步。
- **W-2 模块文档是否已记录本次行为变化**：功能行为变化应体现在 `docs/modules/<module>.md` 六段式文档中。
- **W-3 迁移 SQL 字面量**：`assets/migrations/*.sql` 内字符串字面量若含 `;` 需用 `char(59)`。
- **W-4 提交信息格式**：给出建议的 Conventional Commits 首行（`<type>(<scope>): <subject>`），
  并校验 `git log` 上一条是否用了非规范格式。
- **W-5 分支纪律提醒**：若当前分支为 `feat/*`/`refactor/*`/`hotfix/*` 且本次要打 Tag，提示「禁在功能分支发版」。

> 后续 v1.0.1/v1.7.0 已把建议项扩展至 W-26（见 4.4 与第 9 节演进记录）。

> C-1 与 `.githooks/pre-commit`、W-4/C-1 与 `.githooks/commit-msg` 口径一致；本技能是在 hook 之外
> 的**更早、更全**的一次体检，把「提交失败」前移到「提交前主动把关」。

### 4.3 B1 分层检查（非 Android 项目不误报）

脚本入口先判定项目类型并打印 `[类型]` 行：

- 识别为 **Android 项目**（项目根存在 `app/build.gradle.kts` 或 `app/build.gradle`）→ 执行全部
  Android 专属检查（C-1/C-2/C-3/C-5/W-1/W-2/W-3）+ 通用检查（C-4/W-4/W-5）。
- 识别为 **非 Android 项目**（纯前端/后端/脚本仓库等）→ 自动跳过 Android 专属项，仅执行
  C-4 敏感信息、W-4 提交信息格式、W-5 分支纪律；W-4 会按前端扩展名（js/ts/css/html/vue/jsx/tsx）
  建议 `feat`/`fix`，避免"纯前端仓库被 Android 规则误报"。

### 4.4 建议项扩展（v1.7.0，W-6 ~ W-26，均通用检查）

- **W-6 diff 预算**：改动 >400 行或 >40 文件，建议拆分原子提交（review 质量）。
- **W-7 待办标记**：源码残留 `TODO/FIXME/HACK/XXX`，提交前确认是否已处理。
- **W-8 原子性**：改动横跨 ≥3 个 feature 模块，建议按主题拆分提交。
- **W-9 文件卫生**：行尾空白 / 文件末尾缺换行符（借鉴 pre-commit-hooks trailing-whitespace / end-of-file-fixer）。
- **W-10 超长行**：>240 字符纯 ASCII 长行，建议拆分（借鉴 flake8 max-line-length；中文多字节行不误报）。
- **W-11 游离 HEAD**：detached HEAD 下提交易丢失（claude-commit-skill safety gate）。
- **W-12 依赖锁定文件同步**：锁文件须随 manifest 一并提交，避免版本漂移；双向查（锁文件在改动中 / 清单改但锁文件未同步）。
- **W-13 .gitignore 缺口**：易误提交的产物/敏感文件未被忽略（`git check-ignore` 精确判定）。
- **W-14 CRLF 混用**：Windows 行尾混入会导致 shell 脚本/构建诡异 bug，建议统一 LF（与 W-9 合并为一次遍历）。
- **W-15 大小写冲突**：同路径仅大小写不同的文件对，在大小写不敏感文件系统互相覆盖（check-case-conflict）。
- **W-16 损坏符号链接**：指向不存在目标的 symlink 破坏构建/打包（check-symlinks）。
- **W-17 AI 引用残留**：`[oaicite:]`/`[filecite:]`/`:contentReference` 等剪贴引用标记，提交前删除（借鉴 IBM mcp-context-forge）。
- **W-18 子模块/嵌套仓库**：`.gitmodules` 变更或改动位于嵌套 git 仓库内（forbid-new-submodules）。
- **W-19 硬编码绝对路径**：`/home/`、`/Users/`、`C:\Users\` 等本机路径泄漏用户名且不可移植（commit-audit）。
- **W-20 shebang 一致性**：可执行脚本缺 shebang / 带 shebang 未设执行位（check-executables-have-shebangs）。
- **W-21 编码/结构化文件雷区**：UTF-8 BOM、JSON 尾逗号（`,}`/`,]`）、YAML Tab 缩进（fix-encoding-pragma / check-json / check-yaml）。
- **W-22 依赖版本未锁定**：package.json 的 `^`/`~`/`*`、Gradle 的 `+`/`latest.*`、Python `>=` 等动态版本，建议锁定精确版（可复现构建）。
- **W-23 大删除/大改面确认**：删除行 >300 或删除文件 ≥10，提示确认有意重构/迁移而非误删。
- **W-24 .gitattributes 归一化缺失**：有 `.gitignore` 但缺 `.gitattributes`，建议 `* text=auto eol=lf` 与二进制声明。
- **W-25 工作流供应链安全**（.github/workflows/*）：action 未固定完整 SHA、`pull_request_target` + `actions/checkout` 未用 `ref:` 锁定、`curl|sh` 管道执行远程脚本（借鉴 zizmor / actionlint）。
- **W-26 内网私有 IP**：源码硬编码 `10.`/`192.168.`/`172.16-31.`/`127.0.0.1`/`169.254.`，建议改配置/环境变量。

## 5. 执行契约（SKILL_PROJECT_PATH）

复用 [SkillExecutor](../modules/agent.md) 既有 SCRIPT 链路：`cd $containerSkillDir && env ... $shell $entry`。
为让入口脚本在容器内定位宿主项目，需在组装环境时注入项目路径环境变量（小改动）：

```bash
# SkillExecutor.executeScript 组装 env 时追加：
SKILL_PROJECT_PATH=<ctx.projectPath 经容器映射后的容器侧路径>
```

- 有 `ctx.projectPath` 时注入，无则注入空并让脚本提示「未指定项目路径，仅做纯静态检查」。
- 入口脚本按 `SKILL_PROJECT_PATH` 先 `git -C "$P" rev-parse --show-toplevel` 定位仓库根，再基于
  `git -C "$P" status --porcelain` / `git -C "$P" diff HEAD --stat` 圈定改动面做 C/W 检查。
- 输出约定：UTF-8 纯文本报告，分「阻断项 / 建议项」，末尾给一行建议提交信息模板；有阻断项时退出码非 0。

## 6. 产物与反馈闭环

1. AI 在 commit 前调用本技能 → 得到报告。
2. 无阻断项：按建议提交信息格式组织 commit，继续原流程。
3. 存在阻断项：AI 依据 SKILL.md 指令逐条修复（改 strings.xml / 补模块文档 / 去掉手写版本号 /
   移除敏感信息等），修复后**重跑本技能**直至绿灯再提交。
4. 全部装进 `executionContext`，审批卡与审计归属当前会话，可被用户否决。

## 7. 落地清单

- **契约注入**：`SkillExecutor.kt` 组装 SCRIPT env 时追加 `SKILL_PROJECT_PATH`（取自 `ctx.projectPath`）。
- **技能资产**：`app/src/main/assets/skills/pre-commit-health/`
  - `SKILL.md`（Frontmatter：name/type=script/scope=common/entry=entry/run.sh，正文 C/W 检查口径 + 修复指引
    + 通用触发词 + 分层说明 + 故障口径「只读不修」）
  - `entry/run.sh`（容器内 busybox sh：按 `SKILL_PROJECT_PATH` 圈定改动面，B1 分层执行 C/W 检查并输出 UTF-8 报告）
- **首启引导**：`BuiltinSkillSeeder` 首启把 `assets/skills/*` 引导（copy）进 `skillsRoot`，标记 `source=BUILTIN`、
  只读、禁止卸载覆盖；**升级覆盖**——已落地内置技能按 `SKILL.md` version 与 assets 比对，不一致时干净重建为新版，
  保证内置技能 bug 修复/演进随新包自动到达用户。
- **CI 护栏**：`.github/workflows/ci.yml` 新增「Check skill script syntax (sh -n)」步骤，对 `assets/skills/**/*.sh`
  逐个 `sh -n` 校验（POSIX 语法，防止 busybox 不可解析的脚本合入）。
- **文档同步**：`docs/modules/agent.md` 技能章节（3.6.3 分层/兼容/护栏）、
  `app/src/main/assets/docs/mcp-and-skills.md` 使用说明更新。

## 8. 里程碑

- [x] M1：`SKILL_PROJECT_PATH` 契约注入（SkillExecutor 小改动）。
- [x] M2：内置技能资产（SKILL.md + entry/run.sh）与 BuiltinSkillSeeder 首启引导。
- [x] M3：文档同步 + 提交/CI/发版（v0.1.0-rc175 已出包）。
- [x] M4：busybox 兼容修复（C-2 awk 改字节 grep、统一 grep -E 防 BRE alternation 失效、修 W-4 前端扩展名匹配）
  + B1 分层检查 + CI 语法护栏 + SKILL.md 触发词/故障口径（v0.1.0-rc176）。
- [x] M5：内置技能**升级覆盖**机制（BuiltinSkillSeeder 按 version 比对重建）+ pre-commit-health v1.0.1
  （修复 153 行 `unexpected "("` 旧包副本问题，rc177）。
- [x] M6：**能力丰富 + 效率/逻辑优化**（全网调研借鉴 gitleaks/detect-secrets/pre-commit-hooks/claude-commit-skill/
  zizmor 等成熟工具，升级至 v1.7.0，见第 9 节演进记录）。

## 9. 演进记录（v1.7.0：能力丰富 / 效率强化 / 逻辑优化）

> 依据「全网搜索借鉴成熟技能与工具」的调研结论（gitleaks、detect-secrets、pre-commit-hooks、claude-commit-skill、
> zizmor/actionlint、lint-skills、commit-audit、hackforla 自检清单、IBM mcp-context-forge 等），对首版做一轮
> 能力丰富与效率/逻辑优化，升级为 **v1.7.0**。

### 9.1 新增强化能力（丰富能力）

- **敏感信息特征库扩充（C-4）**：新增 GitLab（`glpat-`）、Shopify（`shp(at|ca|ss|pa)_`）、Stripe 可撤销密钥
  （`rk_(live|test)_`）、SendGrid（`SG.`）、Telegram bot token、Heroku（`hHrS`）、Alibaba（`LTAI`）、age 加密
  密钥、证书与 PGP 块、`Authorization: Bearer` 等特征；新增 AWS 全系 key 前缀、GCP service_account、内嵌凭据
  DB URL、JWT、Anthropic/HuggingFace token 等（双层：已知特征正则 + 敏感命名赋长值启发，借鉴 gitleaks/
  detect-secrets）。
- **新增 10 项建议检查（W-15 ~ W-26）**：大小写冲突、损坏符号链接、AI 引用残留、子模块/嵌套仓库、硬编码
  绝对路径、shebang 一致性、编码/结构化文件雷区（BOM/JSON 尾逗号/YAML Tab）、依赖版本未锁定、大删除确认、
  .gitattributes 归一化、工作流供应链安全（action 未 pin SHA / pull_request_target 高危 / curl|sh）、内网
  私有 IP。均为零额外依赖的纯正则/字节启发式，busybox 兼容。
- **C-7/C-8/C-13 覆盖面补全**：临时备份/IDE/日志模式（`*.orig`/`*.rej`/`*.swp`/`.idea/`/`*.log`）、敏感文件
  类型补 `.env.local`/`.env.production`/`.aws/credentials` 等变体、高熵检测补锁文件/压缩产物/测试目录降噪。

### 9.2 效率强化

- **C-12 二进制判定提速**：整文件读取改为采样前 8KB（`head -c 8192 | grep -Iq`），避免超大文件整读。
- **W-9/W-14 合并遍历**：行尾空白/EOF 换行/CRLF 三检查合并为单次文件遍历，每文件只读一次，减少重复 IO。
- **C-13 预筛提速**：先 `grep` 过滤无 28+ 长串的文件，再对命中文件跑 awk 熵计算，避免全量熵计算开销。
- **CHANGED 收集优化**：`git status --porcelain | awk '{print $2}'`（对重命名/带空格路径解析不可靠）改为
  `git diff --name-only HEAD` + `git diff --cached --name-only`（覆盖无 HEAD 新建仓库）+ `git ls-files --others`
  三源合并去重；全局 `-c core.quotepath=false` 防路径转义。

### 9.3 逻辑优化

- **TMPB/TMPW 计数修复**：修复各检查项开头 `: > "$TMPB"` 清空累计文件导致「只累计到最后一项」的计数丢失
  bug——初始化统一清空一次，后续全部 `>>` 追加。
- **C-4 豁免词优化**：移除 "test"（防 `sk_test_` 等真实测试密钥被整行放行），新增 `changeme|xxxx` 占位符豁免。
- **C-8 不再按通配拦源码**：弃用 `*secret*`/`*credentials*` 文件名通配（误伤 `SecretService.kt`），源码内
  凭据由 C-13 兜底。
- **W-4 建议 type 优先级阶梯**：修复「先 feat 后被 fix 覆盖」的顺序 bug（feature 代码→feat，纯测试→test，
  CI→ci，纯文档→docs，构建→build，其余源码→fix，否则 chore）。
- **W-25 的 `pull_request_target` 匹配**：正则兼容 YAML 事件名后冒号与事件列表换行两种写法；配合
  `actions/checkout` 未指定 `ref:` 时判高危。
- **W-26 内网 IP 边界**：左右加数字边界避免误匹配版本号/时间戳；仅扫源码类文件（.md 文档常示例 IP 不拦）。
- **自伤修复**：拆分 66 行与 254 行超长正则/命令，避免触发自身 W-10；技能资产目录在 C-4/C-13/W-7/W-19/W-26
  中整体跳过，防安全正则字面量自引用误报。
