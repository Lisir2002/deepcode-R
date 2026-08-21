# skill-management-design

> 评审状态：📝 草案 v2（已拍板，含缺陷修复；待评审后实施）
>
> 主题：技能管理页面重设计——技能列表优化、技能查看页、技能自定义上传/导出、用户技能结构化编辑器、作用域（SkillScope v2）重设计与对话级双向控制。

## 1. 背景与问题

当前技能中心（能力中心 → 技能 Tab，复用 [SkillsScreen.kt](../../app/src/main/java/com/R/codecore/feature/settings/presentation/component/SkillsScreen.kt)）功能单一，存在以下不足：

- **列表信息单薄**：纯卡片列表，无分组、无搜索/筛选，操作仅「启用开关 + 详情弹窗」，无法一眼区分内置/上传技能、自动触发状态。
- **无法查看技能内容**：用户无法查看技能的 SKILL.md 正文与脚本代码，技能对用户是黑盒。
- **无自定义上传/导出能力**：技能只能由内置 seeder 或外部文件系统导入，App 内无 ZIP / MD / URL 上传入口，也无导出分享能力。
- **无用户自编辑能力**：技能文件不可在 App 内编辑（「自我编译」缺失）。
- **作用域模型模糊**：[SkillScope](../../app/src/main/java/com/R/codecore/feature/agent/domain/skill/Skill.kt)（GLOBAL/COMMON/AGENT）中 COMMON 与 GLOBAL 语义重叠，且无「对话级」概念。
- **作用域未联动运行时**：[SystemPromptProvider](../../app/src/main/java/com/R/codecore/feature/agent/domain/prompt/SystemPromptProvider.kt) 技能清单仅按 `enabled` 过滤；自动触发候选筛选未感知作用域。

## 2. 设计目标

1. **列表信息增强**：分组（内置 / 我的技能）+ 搜索 + 筛选 + 卡片信息增强 + 操作按钮化。
2. **技能可视化**：独立查看页，默认渲染 SKILL.md，顶部按钮唤出半屏目录树查看全部文件。
3. **自定义上传与导出**：ZIP / 单 MD 文件 / 粘贴文本 / URL 下载 四来源统一导入管线 + LOCAL 技能 zip 导出。
4. **用户自我编译**：LOCAL 技能独立编辑器（frontmatter 表单 + 正文 + 脚本 + 新增文件），保存预校验；内置技能只读。
5. **作用域 v2**：GLOBAL / AGENT / CONVERSATION 三态，对话级双向控制（添加 + 对话内临时禁用），与「可见性 / 调用 / 自动触发」全链路联动。

## 3. 拍板决策记录（本次评审确定）

| # | 决策点 | 结论 |
|---|--------|------|
| D1 | ZIP 导入范围 | **仅单技能 ZIP**（根目录直接是技能内容，或单层技能目录） |
| D2 | 单 MD 导入 id | **id = 文件名去扩展名**（`my-skill.md` → `my-skill`），仅生成 PROMPT 类型 |
| D3 | 编辑器形态 | **结构化编辑**：frontmatter 表单 + 正文 Markdown + 脚本（语法高亮） |
| D4 | 作用域模型 | **GLOBAL / AGENT / CONVERSATION**，COMMON 并入 GLOBAL |
| D5 | 对话级入口 | **对话页加「技能」入口**，可添加对话级技能 |
| D6 | 作用域存储 | **声明 + 用户覆盖**：frontmatter 声明默认，Room 存覆盖（覆盖 > 声明），BUILTIN 只读 |
| D7 | 作用域过滤 | **严格隐藏**：不匹配的技能不进 prompt、不可 loadSkill、不自动触发 |
| D8 | 对话级行为 | **添加即全面生效**：清单可见 + 可 loadSkill + 可自动触发 |
| D9 | 技能改名 | **id（目录名）不可改**；提供「另存为新技能」副本 |
| D10 | 对话级双向 | **支持对话内临时禁用**：GLOBAL/AGENT 技能可在某对话内被禁用（per-conversation override） |
| D11 | 编辑器能力 | **支持新增文件**（md/脚本/文本）+ frontmatter 字段白名单 + type 切换管理 entry |
| D12 | 自动触发调度 | **上限 ≤2 + 优先级 GLOBAL > AGENT > CONVERSATION**，避免多审批卡/上下文膨胀 |
| D13 | 导出能力 | **LOCAL 技能 zip 导出**（查看页/列表入口），用于分享与备份 |

## 4. 方案

### 4.1 技能列表优化（SkillsScreen 重构）

- **搜索框**：顶部 `TextField`，按 `name` / `description` / `tags` 模糊匹配。
- **筛选条**：横向 chips——类型（全部/PROMPT/SCRIPT/MCP）、启用状态（全部/已启用/已禁用）、自动触发（全部/是/否）。
- **分组**：按 `skill.source` 分为「内置技能」「我的技能」两个分组头；组内按 name 排序。
- **卡片增强**：
  - 名称 + 类型徽章（复用现有 TypeBadge 配色）+ 来源角标（内置）/ 自动触发 ⚡ 标记 + 作用域徽章（全局/Agent/对话级）
  - tags 一行（最多 3 个，超出 +N）
  - 版本 · 作者
  - **对话级技能未启用时**：明示「对话级 · 未启用」状态 + 「添加到当前对话」快捷操作（见 §4.7 发现性）
- **操作按钮化**：主点击 → 查看页；卡片尾部操作区：启用开关、查看、编辑（仅 LOCAL）、导出（仅 LOCAL）、卸载（仅 LOCAL，弹确认）。
- **上传入口**：列表页顶部「+ 导入技能」按钮 → 弹窗选来源（§4.3）。

### 4.2 技能查看页（独立路由 `skill_detail/{id}`）

- **导航**：MainActivity 新增 `composable("skill_detail/{skillId}")`。
- **AppBar**：返回 + 技能名 + 右侧「目录」按钮；LOCAL 技能额外「编辑」「导出」按钮。
- **主内容**：默认渲染 `SKILL.md`（复用 [MarkdownContent](../../app/src/main/java/com/R/codecore/feature/agent/presentation/component/MarkdownContent.kt)）。
- **目录树弹窗**：「目录」→ 半屏 `ModalBottomSheet`，技能目录树（可折叠），列出全部文件（`SKILL.md`、`CLAUDE.md`、规则文档如 `RULES.md`、`scripts/` 脚本、图片等，隐藏 `.builtin`）；点击文件切换主内容。
- **文件渲染**（按扩展名分派）：
  - `.md` → Markdown 渲染
  - `.py/.sh/.js/.ts/.kt/.java/.go/.c/.cpp/.json/.yaml` 等 → 语法高亮（复用 `dev.snipme:highlights-jvm`，只读）
  - 图片（.png/.jpg/.jpeg/.gif/.webp/.svg）→ `Image`
  - 其他/二进制 → 文件名 + 大小元信息
- **只读约束**：`source == BUILTIN` 无「编辑/导出」入口。

### 4.3 技能自定义上传（统一导入管线）

```
入口(4选1) → 归一化为临时技能目录 → 预校验 → 冲突检测 → 安装 → 刷新 + 结果提示
  ├─ ZIP 压缩包      （解压 + 安全防护 + 结构识别）
  ├─ 选文件 MD       （读文本 → 解析）
  ├─ 粘贴文本 MD     （确认框展示解析预览后导入）
  └─ URL 下载        （下载后按 Content-Type / 扩展名分流 zip / md）
```

**4.3.1 ZIP 处理**（仅单技能）：`ZipInputStream` 解压到临时目录；根目录直接含 `SKILL.md/CLAUDE.md` → 整包即技能，仅一个子目录含 `SKILL.md` → 剥一层；其他 → 非法阻断。安全防护：Zip Slip（拒绝 `../`、绝对路径）、大小上限（50MB）、条目数上限。

**4.3.2 单 MD 处理**：`id = 文件名去扩展名`（粘贴文本导入需用户先输入技能名作文件名与 id）；生成单文件技能目录（仅 `SKILL.md`），**强制 PROMPT**；frontmatter 缺 `name` → 回退文件名（警告）。

**4.3.3 URL 下载**：OkHttp 下载到临时文件（超时 + 大小限制）；按 `Content-Type` / 扩展名分流：`.zip` → ZIP 流程，`.md/.txt` → 单 MD 流程，其他 → 非法提示。

**4.3.4 预校验分档**：

| 分档 | 场景 | 处理 |
|------|------|------|
| 非法（阻断） | 无 SKILL.md/CLAUDE.md；frontmatter 解析失败；`entry` 指向不存在的脚本；单 MD 声明 SCRIPT/MCP | 阻断并逐条说明 |
| 警告（可继续） | frontmatter 缺 `name`（回退文件名）；`description` 为空；缺 author/tags | 展示警告，确认后继续 |
| 合法 | 其余 | 进入冲突检测 |

**4.3.5 冲突检测**：id 已存在且 BUILTIN → 拒绝（只读）；已存在且 LOCAL → 询问「覆盖更新 / 取消」；不存在 → 直接安装。安装/更新后 `refreshTrigger++` 刷新列表 + Toast 结果。

**4.3.6 安全模型（边界明确）**：
- **导入仅做文件落盘，App 不执行任何上传/下载/粘贴的脚本**；SCRIPT 技能仅在模型发起调用后经用户审批（`SkillExecutor` 现有 `awaitApproval` 强制审批）才执行。
- 上传内容不自动注册为可执行能力；`mcp_tool` 字段仅作为元数据，实际调用仍需连接对应 MCP 服务器。

### 4.4 用户技能结构化编辑器（独立路由 `skill_edit/{id}`，仅 LOCAL）

- **frontmatter 字段白名单**：

| 类别 | 字段 | 说明 |
|------|------|------|
| 可编辑 | name / description / version / type / entry / auto_trigger / trigger_conditions / scope / agent_type / tags | 表单控件 |
| 需校验 | dependencies / requiredTools / requiresRuntime | 编辑时校验依赖技能存在、工具已注册 |
| 不可编辑 | id / source | id=目录名（D9），source=来源 |

- **新增文件**（D11）：编辑器支持向技能目录新增 markdown / 脚本 / 其他文本文件；可删除自己新增的文件（内置技能文件只读）。
- **type 切换**（D11）：PROMPT → SCRIPT 时需设置 `entry` 且目标脚本文件存在；SCRIPT → PROMPT 时清空 `entry`。
- **正文/脚本编辑**：SKILL.md 正文用 Markdown 文本区；脚本用语法高亮编辑区（可切换文件）。
- **保存流程**：预校验（SKILL.md 可解析、entry 存在、id 未变、依赖存在）→ 写回磁盘 → `refreshTrigger++` → 返回查看页并刷新。
- **另存为新技能**（D9）：复制当前技能目录到新 id（默认 `原名-copy`），用户可改 name/描述后独立保存。

### 4.5 作用域重设计（SkillScope v2）

```kotlin
enum class SkillScope {
    GLOBAL,        // 全局：所有 Agent、所有对话生效；用户可开关（enabled）
    AGENT,         // 指定 Agent：绑定 agentType（当前单 Agent 为 "coding"）；仅该 Agent 生效
    CONVERSATION,  // 对话级：仅在显式启用的对话中生效，未启用即休眠
}
```

- **COMMON 并入 GLOBAL**：旧 COMMON 语义「默认全 agent 可用、用户可开关」即新 GLOBAL；不再保留「系统强制不可关」档（内置只读由 `source == BUILTIN` 承载）。
- **frontmatter 兼容**：[SkillParser](../../app/src/main/java/com/R/codecore/feature/agent/domain/skill/SkillParser.kt) 解析 `scope` 时旧值 `common` 映射为 `GLOBAL`。
- **存储（D6）**：frontmatter 声明默认值 + Room 存用户覆盖（覆盖 > 声明）；BUILTIN 只读。
- **严格隐藏（D7）**：不匹配的技能不进 [SystemPromptProvider](../../app/src/main/java/com/R/codecore/feature/agent/domain/prompt/SystemPromptProvider.kt) 清单、不可 `loadSkill`、不进入自动触发候选（[StatefulAgentWorkflow](../../app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt) 候选筛选叠加作用域）。

### 4.6 联动矩阵（enabled 为前提，含 per-conversation override）

| 作用域 | system prompt 清单 | loadSkill 手动调用 | 自动触发 |
|--------|:---:|:---:|:---:|
| GLOBAL（未禁用） | 所有对话可见 | 所有对话可调 | 所有对话可触发 |
| GLOBAL（某对话禁用） | 该对话不可见 | 该对话不可调 | 该对话不触发 |
| AGENT(coding) | 仅 coding 对话可见 | 仅 coding 可调 | 仅 coding 触发 |
| CONVERSATION 未添加 | 不可见 | 不可调 | 不触发 |
| CONVERSATION 已添加 | 仅该对话可见 | 仅该对话可调 | 该对话内可触发 |

### 4.7 对话级双向控制 + 发现性（D5/D10）

- **入口**：对话页输入框上方「技能」按钮 → 打开对话技能面板：
  - **添加**：列出未启用的 CONVERSATION 级技能 → 添加后该对话立即全面生效（D8）。
  - **禁用**：列出本对话已生效的技能（GLOBAL/AGENT/CONVERSATION）→ 可对本对话临时禁用；禁用后该对话内严格隐藏（不进清单、不可调、不触发）。
- **持久化**：Room 表 `skill_conversation_state(skill_id, session_id, enabled)`——有记录且 `enabled=true` → 该对话启用（对 CONVERSATION 是添加，对 GLOBAL/AGENT 是默认生效）；`enabled=false` → 该对话禁用；无记录 → 跟随声明（CONVERSATION 休眠，GLOBAL/AGENT 生效）。
- **过滤实现**：SystemPromptProvider 与自动触发候选筛选中，先按 agent 过滤（AGENT 级），再按 `skill_conversation_state` 过滤（CONVERSATION 需存在 `enabled=true` 记录；GLOBAL/AGENT 需不存在 `enabled=false` 记录）。
- **发现性**：技能卡片显示「对话级 · 未启用」状态徽章 + 「添加到当前对话」快捷操作；列表筛选加「未启用」维度，避免对话级技能形同消失。
- **生效语义**：添加/禁用后，下一轮模型请求生效（SystemPromptProvider 每轮重建清单）。

### 4.8 自动触发调度（D12）

- 作用域候选筛选后的命中技能，按**优先级排序取前 ≤2 个**：`GLOBAL > AGENT > CONVERSATION`；同优先级由模型决策器按相关性在 catalog 中给出，最多选 2 个。
- 每轮任务最多触发 2 个技能，避免上下文膨胀与多审批卡；`ToolSessionState` 会话级去重逻辑保持不变（仅成功执行后标记）。

### 4.9 技能导出（D13）

- 入口：列表卡片「导出」或查看页 AppBar「导出」（仅 LOCAL）。
- 实现：`ZipOutputStream` 打包技能目录为 `{id}-v{version}.zip`，写入应用外部文件目录（或系统分享 Intent 分享 zip）。
- 导出内容仅技能文件（含 `.builtin` 标记的 BUILTIN 技能不可导出）。

### 4.10 数据迁移（Room v46 → v47）

迁移文件 `app/src/main/assets/migrations/47_add_skill_scope.sql`（**纯 SQL，不含注释与字符串字面量 `;`**）：

```sql
ALTER TABLE skill_state ADD COLUMN scope_override TEXT
ALTER TABLE skill_state ADD COLUMN agent_type_override TEXT
CREATE TABLE IF NOT EXISTS skill_conversation_state (
    skill_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY(skill_id, session_id)
)
```

- [AgentDatabase.kt](../../app/src/main/java/com/R/codecore/feature/agent/data/local/database/AgentDatabase.kt) 版本 46→47；新增 `SkillConversationStateDao`（upsert / 查会话启用与禁用集 / 移除）。
- **覆盖语义**：`install` 新建技能无覆盖（跟随声明）；`update` 覆盖更新时**保留** `scope_override / agent_type_override`（用户覆盖优先）。

## 5. 改动面核对

| 模块 | 文件 | 改动 |
|------|------|------|
| UI 列表 | `feature/settings/presentation/component/SkillsScreen.kt`、`SkillsViewModel.kt` | 分组/搜索/筛选/卡片增强/按钮化/导入与导出入口 |
| 查看页（新） | `feature/settings/presentation/component/SkillDetailScreen.kt` | 主内容渲染 + 半屏目录树 + 文件分派 + 导出 |
| 上传（新） | `feature/settings/presentation/SkillImportViewModel.kt`、`SkillImporter.kt` | 四来源管线 + 预校验 + 冲突检测 + 安全模型 |
| 导出（新） | `SkillExporter.kt` | LOCAL 技能 zip 打包 |
| 编辑器（新） | `feature/settings/presentation/component/SkillEditScreen.kt` | frontmatter 表单 + 正文 + 脚本 + 新增文件 + type 切换 + 另存 |
| 作用域模型 | `feature/agent/domain/skill/Skill.kt`、`SkillParser.kt` | SkillScope v2；`common` 兼容映射；解析 `conversation` |
| 运行时过滤 | `prompt/SystemPromptProvider.kt`、`tool/skill/LoadSkillTool.kt`、`workflow/StatefulAgentWorkflow.kt` | 作用域严格隐藏联动 + 自动触发调度（上限/优先级） |
| 对话级 | `feature/agent/presentation/component/AIChatPanel.kt` | 「技能」面板：添加 CONVERSATION + 对话内临时禁用 |
| 数据库 | `AgentDatabase.kt`、`SkillStateEntity.kt`、`SkillConversationStateDao`（新）、`migrations/47_add_skill_scope.sql` | v46→v47 |
| 导航 | `MainActivity.kt` | `skill_detail/{id}`、`skill_edit/{id}` 路由 |
| 资产同步 | `assets/docs/`、`strings.xml`(中/英) | 技能中心使用文档、UI 文案 string 化 |

> 按 [AGENTS.md](../../AGENTS.md) 纪律：所有 UI 文案走 `strings.xml`；UI 变化同步 `assets/docs/`；跨模块结构变化同步 `docs/modules/` 与 `core.md`。

## 6. 风险与注意

- **COMMON 并入 GLOBAL**：存量内置技能 frontmatter 若含 `scope: common`，由 parser 兼容映射兜底；存量 skill_state 无 scope 列，迁移新增为 NULL（跟随声明）。
- **严格隐藏**：CONVERSATION 技能未添加时对模型不可见，交互闭环必须完整（对话技能面板 + 卡片发现性），否则技能形同消失。
- **编辑器改写 frontmatter**：`auto_trigger` 开启但 `trigger_conditions` 为空仅警告不阻断；`type` 切换必须同步管理 `entry`。
- **自动触发调度**：上限 ≤2 可能使第 3 个合理命中的技能本轮不触发——通过「未触发的候选技能在系统提示词中以清单形式可见，模型仍可主动 loadSkill」兜底。
- **Zip Slip / 大小限制** 是上传安全底线，校验失败必须回滚临时目录。
- **导出内容**：技能目录可能含路径/配置等敏感信息，导出前提示用户检查。
- **迁移 SQL**：保持纯 SQL（无注释、无字面量 `;`），与 `MigrationLoader` 按 `;` 切分语义兼容。
- **多 Agent 演进**：AGENT 作用域已按 agentType 预留，未来仅需将当前 agentType 从常量 `"coding"` 换为动态值。
