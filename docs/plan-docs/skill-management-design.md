# skill-management-design

> 评审状态：📝 草案（已拍板，待评审后实施）
>
> 主题：技能管理页面重设计——技能列表优化、技能查看页、技能自定义上传、用户技能结构化编辑器、作用域（SkillScope v2）重设计。

## 1. 背景与问题

当前技能中心（能力中心 → 技能 Tab，复用 [SkillsScreen.kt](../../app/src/main/java/com/R/codecore/feature/settings/presentation/component/SkillsScreen.kt)）功能单一，存在以下不足：

- **列表信息单薄**：纯卡片列表，无分组、无搜索/筛选，操作仅「启用开关 + 详情弹窗」，无法一眼区分内置/上传技能、自动触发状态。
- **无法查看技能内容**：用户无法查看技能的 SKILL.md 正文与脚本代码，技能对用户是黑盒。
- **无自定义上传能力**：技能只能由内置 seeder 或外部文件系统导入，App 内无 ZIP / MD / URL 上传入口。
- **无用户自编辑能力**：技能文件不可在 App 内编辑（「自我编译」缺失）。
- **作用域模型模糊**：[SkillScope](../../app/src/main/java/com/R/codecore/feature/agent/domain/skill/Skill.kt)（GLOBAL/COMMON/AGENT）中 COMMON 与 GLOBAL 语义重叠（「全 agent 可用、可开关」vs「系统强制」实际未强制），且无「对话级」概念，无法表达「某技能只在特定对话生效」。
- **作用域未联动运行时**：[SystemPromptProvider](../../app/src/main/java/com/R/codecore/feature/agent/domain/prompt/SystemPromptProvider.kt) 构建技能清单仅按 `enabled` 过滤，未按作用域过滤；自动触发候选筛选也未感知作用域。

## 2. 设计目标

1. **列表信息增强**：分组（内置 / 我的技能）+ 搜索 + 筛选 + 卡片信息增强 + 操作按钮化。
2. **技能可视化**：独立查看页，默认渲染 SKILL.md，顶部按钮唤出半屏目录树查看全部文件（规则文档/脚本/图片）。
3. **自定义上传**：ZIP / 单 MD 文件 / 粘贴文本 / URL 下载 四来源统一导入管线，含预校验与冲突检测。
4. **用户自我编译**：LOCAL 技能独立编辑器（frontmatter 表单 + 正文 + 脚本），保存预校验；内置技能只读。
5. **作用域 v2**：GLOBAL / AGENT / CONVERSATION 三态，与「系统提示词可见性 / loadSkill 调用 / 自动触发」全链路联动，支撑多 Agent 演进。

## 3. 拍板决策记录（本次评审确定）

| # | 决策点 | 结论 |
|---|--------|------|
| D1 | ZIP 导入范围 | **仅单技能 ZIP**（根目录直接是技能内容，或单层技能目录） |
| D2 | 单 MD 导入 id | **id = 文件名去扩展名**（`my-skill.md` → `my-skill`），仅生成 PROMPT 类型 |
| D3 | 编辑器形态 | **结构化编辑**：frontmatter 表单 + 正文 Markdown + 脚本（语法高亮） |
| D4 | 作用域模型 | **重新设计为 GLOBAL / AGENT / CONVERSATION**，COMMON 并入 GLOBAL |
| D5 | 对话级入口 | **对话页加「+技能」入口**，把对话级技能添加进当前对话 |
| D6 | 作用域存储 | **声明 + 用户覆盖**：frontmatter 声明默认值，Room 存用户覆盖（覆盖 > 声明），BUILTIN 只读 |
| D7 | 作用域过滤 | **严格隐藏**：作用域不匹配的技能不进 system prompt、不可 loadSkill、不自动触发 |
| D8 | 对话级行为 | **添加即全面生效**：清单可见 + 可 loadSkill + 可自动触发 |
| D9 | 技能改名 | **id（目录名）不可改**；提供「另存为新技能」副本 |

## 4. 方案

### 4.1 技能列表优化（SkillsScreen 重构）

- **搜索框**：顶部 `TextField`，按 `name` / `description` / `tags` 模糊匹配。
- **筛选条**：横向 chips——类型（全部/PROMPT/SCRIPT/MCP）、启用状态（全部/已启用/已禁用）、自动触发（全部/是/否）。
- **分组**：按 `skill.source` 分为「内置技能」「我的技能」两个分组头；组内按 name 排序。
- **卡片增强**：
  - 名称 + 类型徽章（复用现有 TypeBadge 配色）+ 来源角标（内置）/ 自动触发 ⚡ 标记 + 作用域徽章（全局/Agent/对话级）
  - tags 一行（最多展示 3 个，超出 +N）
  - 版本 · 作者
- **操作按钮化**：
  - 主点击 → 进入查看页（`skill_detail/{id}`）
  - 卡片尾部操作区：启用开关、查看、编辑（仅 LOCAL）、卸载（仅 LOCAL，弹确认，复用现有卸载确认弹窗）
- **上传入口**：列表页顶部「+ 导入技能」按钮 → 弹窗选来源（见 §4.3）。

### 4.2 技能查看页（独立路由 `skill_detail/{id}`）

- **导航**：MainActivity 新增 `composable("skill_detail/{skillId}")`，参数为技能 id（目录名）。
- **AppBar**：返回 + 技能名 + 右侧「目录」按钮；LOCAL 技能额外「编辑」按钮 → `skill_edit/{id}`。
- **主内容**：默认渲染该技能目录下 `SKILL.md`（复用 [MarkdownContent](../../app/src/main/java/com/R/codecore/feature/agent/presentation/component/MarkdownContent.kt)，`mikepenz` 渲染器）。
- **目录树弹窗**：点「目录」→ 半屏 `ModalBottomSheet`，展示技能目录树（树形可折叠）：
  - 列出技能目录全部文件（`SKILL.md`、`CLAUDE.md`、规则文档如 `RULES.md`、`scripts/` 下脚本、图片等），隐藏 `.builtin` 标记文件。
  - 点击文件 → 关闭弹窗并切换主内容。
- **文件内容渲染**（按扩展名分派）：
  - `.md` → Markdown 渲染
  - `.py/.sh/.js/.ts/.kt/.java/.go/.c/.cpp/.json/.yaml` 等 → 语法高亮（复用 `dev.snipme:highlights-jvm`，只读）
  - 图片（.png/.jpg/.jpeg/.gif/.webp/.svg）→ `Image` 加载
  - 其他/二进制 → 文件名 + 大小元信息展示
- **只读约束**：内置技能（`source == BUILTIN`）无「编辑」入口；LOCAL 技能可编辑。

### 4.3 技能自定义上传（统一导入管线）

```
入口(4选1) → 归一化为临时技能目录 → 预校验 → 冲突检测 → 安装 → 刷新 + 结果提示
  ├─ ZIP 压缩包      （解压 + 安全防护 + 结构识别）
  ├─ 选文件 MD       （读文本 → 解析）
  ├─ 粘贴文本 MD     （确认框展示解析预览后导入）
  └─ URL 下载        （下载后按 Content-Type / 扩展名分流 zip / md）
```

**4.3.1 ZIP 处理**（仅单技能）：
- 用 `ZipInputStream` 解压到临时目录。
- 结构识别：根目录直接含 `SKILL.md/CLAUDE.md` → 整包即技能；根目录仅一个子目录含 `SKILL.md` → 剥一层取子目录为技能；其他 → 非法阻断。
- 安全防护：Zip Slip（拒绝 `../`、绝对路径条目）；大小上限（如 50MB）与条目数上限。
- 解压后进入通用预校验。

**4.3.2 单 MD 处理**：
- `id = 文件名去扩展名`（粘贴文本导入需用户先输入技能名，作为文件名与 id）。
- 生成单文件技能目录（仅 `SKILL.md`），**强制为 PROMPT 类型**：若 frontmatter 声明 `type: script/mcp` 且无配套脚本 → 非法阻断（D 类警告见 §4.3.4）。
- frontmatter 缺 `name` → 回退使用文件名（警告可继续）。

**4.3.3 URL 下载**：
- 走现有网络栈（OkHttp）下载到临时文件，带超时与大小限制。
- 按 `Content-Type` / 扩展名分流：`.zip` → ZIP 流程；`.md/.txt` → 单 MD 流程；其他 → 非法提示。

**4.3.4 预校验分档**：

| 分档 | 场景 | 处理 |
|------|------|------|
| 非法（阻断） | 无 SKILL.md/CLAUDE.md；frontmatter 解析失败；`entry` 指向不存在的脚本；单 MD 声明 SCRIPT/MCP | 阻断并逐条说明原因 |
| 警告（可继续） | frontmatter 缺 `name`（回退文件名）；`description` 为空；缺 author/tags | 展示警告，用户确认后继续 |
| 合法 | 其余 | 直接进入冲突检测 |

**4.3.5 冲突检测**：
- 目标 id（目录名）已存在且 `source == BUILTIN` → 拒绝（内置只读，提示）。
- 已存在且 `source == LOCAL` → 询问「覆盖更新 / 取消」；覆盖复用 [LocalDirectorySkillSource.update](../../app/src/main/java/com/R/codecore/feature/agent/domain/skill/LocalDirectorySkillSource.kt)。
- 不存在 → 直接安装（复用 `install`）。
- 安装/更新后 `refreshTrigger++` 刷新列表，Toast 提示结果。

### 4.4 用户技能结构化编辑器（独立路由 `skill_edit/{id}`，仅 LOCAL）

- **导航**：MainActivity 新增 `composable("skill_edit/{skillId}")`。
- **编辑内容**：
  - `SKILL.md`：**frontmatter 表单**（name / description / version / type / entry（SCRIPT 时）/ auto_trigger 开关 / trigger_conditions / scope / agent_type / tags）+ **正文 Markdown 文本区**。
  - 脚本文件（仅 SCRIPT 技能，`scripts/` 下）：语法高亮编辑区，可切换文件；内置只读约束同样适用于脚本。
- **id 不可改**（D9）：id = 目录名是稳定标识（Room 主键、对话绑定依赖）；提供「另存为新技能」入口——复制当前技能到新目录生成新 id，用户可改 name/描述。
- **保存流程**：预校验（SKILL.md 可解析、entry 存在、id 未变）→ 写回磁盘 → `refreshTrigger++` → 返回查看页并刷新。

### 4.5 作用域重设计（SkillScope v2）

```kotlin
enum class SkillScope {
    GLOBAL,        // 全局：所有 Agent、所有对话生效；用户可开关（enabled）
    AGENT,         // 指定 Agent：绑定 agentType（当前单 Agent 场景为 "coding"）；仅该 Agent 生效
    CONVERSATION,  // 对话级：仅在显式启用的对话中生效，未启用即休眠
}
```

- **COMMON 并入 GLOBAL**：旧 COMMON 语义「默认全 agent 可用、用户可开关」即新 GLOBAL 语义；不再保留「系统强制不可关」档（内置只读由 `source == BUILTIN` 独立承载，与开关无关）。
- **frontmatter 兼容**：[SkillParser](../../app/src/main/java/com/R/codecore/feature/agent/domain/skill/SkillParser.kt) 解析 `scope` 时，旧值 `common` 映射为 `GLOBAL`（向后兼容已落地技能）。
- **存储（D6）**：frontmatter 声明默认值（`scope` / `agent_type`）+ Room 存用户覆盖（优先级：用户覆盖 > 声明）。BUILTIN 只读（不改 frontmatter、不接受覆盖）。
- **严格隐藏（D7）**：作用域不匹配的技能不进入 [SystemPromptProvider](../../app/src/main/java/com/R/codecore/feature/agent/domain/prompt/SystemPromptProvider.kt) 技能清单、不可 `loadSkill`（[LoadSkillTool](../../app/src/main/java/com/R/codecore/feature/agent/domain/tool/skill/LoadSkillTool.kt) 加载前校验作用域）、不进入自动触发候选（[StatefulAgentWorkflow](../../app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt) 候选筛选叠加作用域）。

### 4.6 联动矩阵（enabled 为前提）

| 作用域 | system prompt 清单 | loadSkill 手动调用 | 自动触发 |
|--------|:---:|:---:|:---:|
| GLOBAL | 所有对话可见 | 所有对话可调 | 所有对话可触发 |
| AGENT(coding) | 仅 coding 对话可见 | 仅 coding 可调 | 仅 coding 触发 |
| CONVERSATION 未添加 | 不可见 | 不可调 | 不触发 |
| CONVERSATION 已添加 | 仅该对话可见 | 仅该对话可调 | 该对话内可触发 |

### 4.7 对话级交互闭环（D5/D8）

- **入口**：对话页输入框上方「+技能」按钮 → 列出未启用的 CONVERSATION 级技能 → 添加后该对话立即生效（D8：清单可见 + 可 loadSkill + 可自动触发）。
- **可见性**：对话页有入口展示「当前对话已启用 N 个对话级技能」，可随时移除；移除后立即从该对话的清单与自动触发候选中消失。
- **持久化**：新增 Room 表 `skill_conversation_state(skill_id, session_id, enabled)`，与现有 [ChatSession](../../app/src/main/java/com/R/codecore/feature/agent/domain/model/ChatSession.kt) 的 sessionId 关联。
- **过滤实现**：SystemPromptProvider 与自动触发候选筛选中，CONVERSATION 技能需额外查「当前 session 是否启用」；AGENT 技能查当前 agentType。

### 4.8 数据迁移（Room v46 → v47）

迁移文件 `app/src/main/assets/migrations/47_add_skill_scope.sql`（注意：SQL 字符串字面量中禁止出现 `;`，需用 `char(59)`）：

```sql
ALTER TABLE skill_state ADD COLUMN scope_override TEXT  -- 可空，NULL=跟随声明
ALTER TABLE skill_state ADD COLUMN agent_type_override TEXT  -- 可空
CREATE TABLE IF NOT EXISTS skill_conversation_state (
    skill_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY(skill_id, session_id)
)
```

同时更新 [AgentDatabase.kt](../../app/src/main/java/com/R/codecore/feature/agent/data/local/database/AgentDatabase.kt) 版本号 46→47，新增 `SkillConversationStateDao`（upsert / 查会话启用集 / 移除）。

## 5. 改动面核对

| 模块 | 文件 | 改动 |
|------|------|------|
| UI 列表 | `feature/settings/presentation/component/SkillsScreen.kt`、`SkillsViewModel.kt` | 分组/搜索/筛选/卡片增强/按钮化/导入入口 |
| 查看页（新） | `feature/settings/presentation/component/SkillDetailScreen.kt`（新建） | 主内容渲染 + 半屏目录树弹窗 + 文件分派 |
| 上传（新） | `feature/settings/presentation/SkillImportViewModel.kt`、`SkillImporter.kt`（新建） | 四来源导入管线 + 预校验 + 冲突检测 |
| 编辑器（新） | `feature/settings/presentation/component/SkillEditScreen.kt`（新建） | frontmatter 表单 + 正文 + 脚本编辑 + 另存为新技能 |
| 作用域模型 | `feature/agent/domain/skill/Skill.kt`、`SkillParser.kt` | SkillScope v2；`common` 兼容映射；parser 解析 `conversation` |
| 运行时过滤 | `prompt/SystemPromptProvider.kt`、`tool/skill/LoadSkillTool.kt`、`workflow/StatefulAgentWorkflow.kt` | 作用域严格隐藏联动 |
| 对话级 | `feature/agent/presentation/component/AIChatPanel.kt`（对话页入口） | 「+技能」入口 + 已启用展示 |
| 数据库 | `AgentDatabase.kt`、`SkillStateEntity.kt`、`SkillConversationStateDao`（新）、`migrations/47_add_skill_scope.sql` | v46→v47 |
| 导航 | `MainActivity.kt` | `skill_detail/{id}`、`skill_edit/{id}` 路由 |
| 资产同步 | `assets/docs/`、`strings.xml`(中/英) | 技能中心使用文档、UI 文案 string 化 |

> 按 [AGENTS.md](../../AGENTS.md) 纪律：所有 UI 文案走 `strings.xml`；UI 变化同步 `assets/docs/` 使用文档；跨模块结构变化需同步 `docs/modules/` 与 `core.md`。

## 6. 风险与注意

- **COMMON 并入 GLOBAL** 会影响既有内置技能 frontmatter（若含 `scope: common`），已通过 parser 兼容映射兜底；存量 skill_state 数据无 scope 列，迁移新增列为 NULL（跟随声明），不破坏。
- **CONVERSATION 技能严格隐藏**：用户在未添加的对话中无法手动调用，需依赖对话页「+技能」入口，交互闭环必须完整，否则技能形同消失。
- **编辑器改写 frontmatter**：用户可能把 `auto_trigger` 打开但 `trigger_conditions` 为空——校验仅警告不阻断；`type` 切换 PROMPT↔SCRIPT 需同步管理 `entry` 字段。
- **Zip Slip 与大小限制** 是上传安全底线，校验失败必须回滚临时目录。
- **多 Agent 演进**：AGENT 作用域已按 agentType 预留，未来仅需把「当前 agentType」从常量 `"coding"` 换为动态值。
