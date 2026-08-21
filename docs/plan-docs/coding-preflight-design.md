# coding-preflight-design

> 评审状态：📝 草案
>
> 主题：第二款内置 Skill「编程前准备」（coding-preflight）的设计。定义开工前体检范围（环境/仓库
> 就绪 + 任务理解与计划拆解 + 上下文与记忆加载 + 纪律与分支前置）、执行契约（SCRIPT 脚本快照 +
> PROMPT 引导双形态）与工程化落地清单。与第一款 pre-commit-health（提交后/提交前体检）形成
> 「编程前 → 提交前」全链路闭环。

## 1. 背景与问题

仓库已有第一款内置技能 **pre-commit-health**（提交前规范体检）：在 AI 准备 `git commit` 前对
**待提交改动**做仓库纪律体检（模块文档同步、strings.xml、版本号、敏感信息、依赖锁定、提交信息
格式等），拦截「提交后 CI 红」问题。它解决的是**编程完成之后、提交之前**的关口。

但「编程开始之前」目前**没有统一的准备流程**，AI 拿到一个任务往往直接动手写代码，常见返工与
事故包括：

- **未读现状就动手**：不先 `list`/`search`/`readFile` 看项目结构，凭记忆描述/修改项目，产出与
  实际不符（AGENTS.md 已要求「拿到任务第一动作是读相关文件」，但缺一个强制的开工流程兜底）。
- **环境未就绪**：容器内缺构建组件（Java/Gradle/Android SDK/Node/Python 等）就直接写代码，
  写完后构建失败，回头补环境再返工。
- **仓库状态脏**：在游离 HEAD、有未提交改动、有进行中 merge/rebase、或错的分支上开始新任务，
  导致新改动与旧改动混杂、提交落在错误分支/丢失。
- **上下文/记忆未加载**：不读项目记忆（`memory`）与相关模块文档（`docs/modules/`），忽略历史
  决策与踩坑经验，重复踩坑。
- **纪律/分支前置缺失**：大功能/重构直接在主分支上写（AGENTS.md 要求拉 `feat/*`/`refactor/*`
  分支），涉及多模块改动时未先规划文档/资产同步面，中途才发现要补。

这些在**动手写代码之前**如果能被一次「开工前体检 + 准备引导」拦截并给出指引，能显著减少返工。

## 2. 设计目标

1. **开工前主动把关**：AI 拿到新任务（尤其新功能/重构/跨模块改动）后，动手写代码前调用本技能，
   一次完成「现状采集 + 就绪判定 + 计划引导」，输出「阻断项 / 建议项 / 计划建议」。
2. **自动采集现状（SCRIPT 快照）**：入口脚本自动输出环境组件快照、git 仓库状态、项目结构/模块
   清单，AI 无需逐个手动跑 `check_environment`/`git status`/`ls`，省时省 token。
3. **引导任务理解与计划拆解（PROMPT 正文）**：SKILL.md 指令正文引导 AI 先理解需求、拆解为可
   执行步骤、明确验收标准，再动手。
4. **上下文与记忆加载**：引导 AI 读取项目记忆（`memory`）与相关模块文档、既有实现，避免凭记忆
   编造项目结构。
5. **纪律与分支前置**：识别项目类型与改动面，提示 AGENTS.md 边界纪律（strings.xml/prompts/docs/
   docs/modules 同步面）、是否需要先建分支、是否涉及敏感/危险操作需先询问。
6. **零侵入规范**：技能只读采集 + 输出引导，不改任何文件，不写库、不建表，纯资产。

## 3. 技能定位

| 维度 | 取值 | 说明 |
| --- | --- | --- |
| 名称 | `coding-preflight` | 大模型调用的唯一标识 |
| 类型 | SCRIPT | 由入口脚本在容器内采集现状 + 织出报告，正文引导计划 |
| 来源 | BUILTIN | 随 App 预置，只读 |
| 作用域 | COMMON | 默认所有 agent 可用，用户在设置可关 |
| 适用模式 | 全模式（不限定 `modes`） | 任意模式开始新任务前都可用 |
| 运行态 | 容器内 `sh` | 复用既有 SCRIPT 执行链 + 审批 + 审计 |

## 4. 体检范围（现状采集 + 就绪判定 + 计划引导）

### 4.0 报告结构

```
================ 编程前准备报告 ================
[项目] 仓库根: ...
[类型] Android 项目 / 非 Android 项目（前端/后端/通用）
[任务] 用户任务描述（SKILL_ARG_TASK，可空）
[环境] 组件快照: Java=installed(openjdk 17.0.12) | Gradle=installed(...) | Android SDK=missing | ...
[仓库] 分支: main（clean / 有 N 个未提交改动）| 是否游离 | 是否有中间操作 | 最近提交
[结构] feature 模块: agent, settings, terminal, ... | docs/modules 文档同步情况
[就绪] ❌ 阻断项: ... | ⚠️ 建议项: ...
[计划] 建议步骤: 1) ... 2) ...
==================================================
```

### 4.1 SCRIPT 自动采集（信息快照，非判定）

- **P-1 环境组件快照**：按项目栈推断（`app/build.gradle.kts`→Java/Gradle/Android SDK/NDK、
  `package.json`→Node/npm、`go.mod`→Go、`Cargo.toml`→Cargo、`pom.xml`→Java/Maven 等，与
  `CheckEnvironmentTool.inferComponentsFromProjectStack` 同口径）后批量探测，输出
  `NAME|STATUS|PATH|VERSION`（busybox `command -v` + `--version`，缺省降级）。Git 始终默认探测。
- **P-2 仓库状态**：`git rev-parse --show-toplevel` 定位根；`git branch --show-current` 取分支；
  `git status --porcelain` 计数未提交改动（已暂存/未暂存/未跟踪）；`git rev-parse --is-inside-work-tree`
  判仓库；`.git/MERGE_HEAD` 等判中间操作；`git log -1` 取最近提交。
- **P-3 项目结构与模块清单**：`app/src/main/java/com/R/codecore/feature/*` 列 feature 模块；
  `docs/modules/*.md` 列已有文档；`docs/plan-docs/`、`app/src/main/assets/prompts|docs` 存在性。
- **P-4 关键文件探测**：`AGENTS.md`、`.gitignore`、`.gitattributes`、`README.md`、`local.properties`
  等存在性，供 AI 判断纪律上下文。

### 4.2 就绪判定（基于快照的启发式，输出「阻断项 / 建议项」）

**阻断项（❌，存在则先处理再动手）**：

- **R-1 环境缺关键组件**：项目栈推断出的构建组件（Java/Gradle/Android SDK/Node 等）探测为
  `missing`。→ 先安装（`apk add`/装 JDK/Android SDK 等）或说明降级路径，避免写完构建失败。
- **R-2 仓库中间操作**：`.git/MERGE_HEAD`/`rebase-merge`/`CHERRY_PICK_HEAD`/`REVERT_HEAD` 存在。
  → 先完成或中止（`git merge --continue`/`--abort` 等）。
- **R-3 游离 HEAD**：`symbolic-ref HEAD` 失败。→ 先 `git checkout <branch>` 再开工（防提交丢失）。
- **R-4 有未提交改动**：待提交改动数 >0（尤其跨多模块/含未跟踪新文件）。→ 先 `git stash`/提交当前
  改动，避免新旧混杂（但新任务确属同一主题时提示可继续，不绝对阻断——输出为「需人工确认」语义）。
- **R-5 错误分支**：新任务为功能/重构但当前在主分支 `main`/`master`（且改动面将较大）时提示。
  → 建议先 `git checkout -b feat/xxx`。

**建议项（⚠️，提醒，不强阻断）**：

- **W-1 记忆未加载**：提示 AI 用 `memory(action="list")` 看项目记忆，相关条目用 `memory(action="read")`
  加载，尤其「坑」类记忆（历史踩坑经验）。
- **W-2 模块文档未读**：涉及 feature 模块时，提示先读 `docs/modules/<模块>.md` 六段式文档了解
  架构约束与对外接口。
- **W-3 资产同步面预判**：改动若涉 AI 工作流/UI 文案/新增模块，提示预先规划 `strings.xml`（中/英）、
  `assets/prompts/`、`assets/docs/`、`docs/modules/` 的同步（对应 AGENTS.md 资产同步纪律）。
- **W-4 危险/敏感操作前置询问**：任务若含删除/重构/改 schema/发版等 Ask First 项，提示先与用户确认。
- **W-5 无任务描述**：`SKILL_ARG_TASK` 为空时，提示 AI 先向用户澄清需求再拆解（用 `askUserQuestion`）。

### 4.3 PROMPT 引导（SKILL.md 正文，指导 AI 完成计划拆解）

- **任务理解**：先复述用户需求、识别目标文件/模块、列出未知信息（用只读工具核实，不凭记忆）。
- **计划拆解**：输出可执行步骤清单（1..N），每步含「做什么/涉及哪些文件/如何验证」；必要时用
  `todo` 工具登记。
- **验收标准**：明确「完成 = 什么可观测结果」（构建通过/测试全绿/文档同步等）。
- **纪律自检**：对照 AGENTS.md 边界规则（Always/Ask First/Never），规划改动面的同步项。

## 5. 执行契约（SKILL_PROJECT_PATH / SKILL_ARG_TASK）

复用 [SkillExecutor](../modules/agent.md) 既有 SCRIPT 链路：`cd $containerSkillDir && env ... $shell $entry`。

```bash
# SkillExecutor.executeScript 组装 env 时（已实现）：
SKILL_PROJECT_PATH=<容器侧项目路径，默认 /root/workspace>
# 新增：任务描述参数由 loadSkill 的 args 传入 → SKILL_ARG_TASK=<用户任务描述>
```

- 有 `ctx.projectPath` 时注入 `/root/workspace`，无则注入空（脚本提示「未指定项目路径，仅做静态快照」）。
- 入口脚本按 `SKILL_PROJECT_PATH` 先 `git -C "$P" rev-parse --show-toplevel` 定位仓库根，再采集
  P-1~P-4 快照、做 R/W 判定。
- 输出约定：UTF-8 纯文本报告（结构见 4.0），有阻断项时退出码非 0（复用 SCRIPT 审批/审计链路）。
- 兼容性：目标环境 Alpine busybox，禁用 gawk 专属正则（`\x{...}`、`\s`、`\d`）、统一 `grep -E`、
  全部命令 `-c core.quotepath=false` 防路径转义（沿用 pre-commit-health 的兼容纪律）。

## 6. 产物与反馈闭环

1. AI 接到新任务 → 判断是否为新功能/重构/跨模块/环境未知 → 调用本技能。
2. 脚本输出「现状快照 + 就绪判定」；SKILL.md 正文引导 AI 完成「任务理解 → 计划拆解 → 纪律自检」。
3. 有阻断项（R-*）：AI 先处理（装环境/清仓库/建分支），处理完重跑一次直到绿灯，再动手写代码。
4. 无阻断项：AI 依「建议步骤 + 验收标准」开始编程；编程完成后由 pre-commit-health 做提交前体检，
   构成「**开工前（coding-preflight）→ 编程 → 提交前（pre-commit-health）**」闭环。

## 7. 与第一款的分工对照

| 维度 | pre-commit-health（已有） | coding-preflight（本设计） |
| --- | --- | --- |
| 时机 | 提交前（编程完成后） | 开工前（编程开始前） |
| 关注点 | 待提交改动合规性（C-1~C-13/W-1~W-26） | 环境/仓库/结构就绪 + 任务理解/计划/纪律前置 |
| 输入 | 待提交改动（git diff） | 项目现状 + 任务描述 |
| 输出 | 阻断项/建议项报告 + 修复口径 | 现状快照 + 就绪判定 + 计划引导 |
| 形态 | SCRIPT（run.sh 检查） | SCRIPT（run.sh 快照）+ PROMPT（计划引导） |

两者不重复：pre-commit-health 看「改得对不对」，coding-preflight 看「能不能开始、从哪开始」。

## 8. 落地清单

- **技能资产**：`app/src/main/assets/skills/coding-preflight/`
  - `SKILL.md`（Frontmatter：name/type=script/scope=common/entry=entry/run.sh，正文含任务理解、
    计划拆解、验收标准、纪律自检引导 + 通用触发词 + 故障口径「只读不修」）
  - `entry/run.sh`（容器内 busybox sh：按 `SKILL_PROJECT_PATH` 采集 P-1~P-4 快照、做 R/W 判定、
    输出 UTF-8 报告）
- **内置引导**：`BuiltinSkillSeeder` 自动引导（无需额外代码，随 assets 打包即生效；版本升级覆盖机制
  已具备）。
- **CI 护栏**：`.github/workflows/ci.yml` 现有「Check skill script syntax (sh -n)」步骤会自动覆盖
  新脚本（`assets/skills/**/*.sh`），无需改动。
- **文档同步**：
  - `docs/modules/agent.md` §3.6.2/3.6.3 技能章节补充 coding-preflight；
  - `app/src/main/assets/docs/mcp-and-skills.md` §5 使用说明补充第二款内置技能；
  - `docs/modules/README.md` 索引（如技能资产清单）核对。
- **验证方式**：`sh -n` 语法校验；真实运行在 `SKILL_PROJECT_PATH=/workspace` 上验证快照/判定输出；
  Android 与非 Android 项目各跑一次确认分层正确；确认无阻断项时退出码 0。

## 9. 里程碑

- [ ] M1：设计定稿（本文档）。
- [ ] M2：技能资产（SKILL.md + entry/run.sh）+ 本地 `sh -n` 与真实运行验证。
- [ ] M3：文档同步（agent.md / mcp-and-skills.md）+ 提交/CI/发版。

> 注：本设计为草案，待评审确认后按 M2/M3 实施。实施完成前不修改任何编译型代码。
