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

### 4.2 建议项（提醒，不强阻断）

- **W-1 prompts/docs 资产同步**：改动涉及 AI 工作流/工具签名变化时，提示检查
  `app/src/main/assets/prompts/` 与 `assets/docs/` 是否需同步。
- **W-2 模块文档是否已记录本次行为变化**：功能行为变化应体现在 `docs/modules/<module>.md` 六段式文档中。
- **W-3 迁移 SQL 字面量**：`assets/migrations/*.sql` 内字符串字面量若含 `;` 需用 `char(59)`。
- **W-4 提交信息格式**：给出建议的 Conventional Commits 首行（`<type>(<scope>): <subject>`），
  并校验 `git log` 上一条是否用了非规范格式。
- **W-5 分支纪律提醒**：若当前分支为 `feat/*`/`refactor/*`/`hotfix/*` 且本次要打 Tag，提示「禁在功能分支发版」。

> C-1 与 `.githooks/pre-commit`、W-4/C-1 与 `.githooks/commit-msg` 口径一致；本技能是在 hook 之外
> 的**更早、更全**的一次体检，把「提交失败」前移到「提交前主动把关」。

### 4.3 B1 分层检查（非 Android 项目不误报）

脚本入口先判定项目类型并打印 `[类型]` 行：

- 识别为 **Android 项目**（项目根存在 `app/build.gradle.kts` 或 `app/build.gradle`）→ 执行全部
  Android 专属检查（C-1/C-2/C-3/C-5/W-1/W-2/W-3）+ 通用检查（C-4/W-4/W-5）。
- 识别为 **非 Android 项目**（纯前端/后端/脚本仓库等）→ 自动跳过 Android 专属项，仅执行
  C-4 敏感信息、W-4 提交信息格式、W-5 分支纪律；W-4 会按前端扩展名（js/ts/css/html/vue/jsx/tsx）
  建议 `feat`/`fix`，避免"纯前端仓库被 Android 规则误报"。

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