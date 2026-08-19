# 能力中心（Capability）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/capability/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

提供「能力中心」页面：系统聚合展示 Agent 的三类能力，供用户查看。

- **工具 Tab**：罗列全部已注册工具（名称、描述、权限策略、能力标签、参数定义）。
- **Agent Tab**：Agent 运行时概览（执行模式、激活 Provider 与模型、会话模式）与统计（工具数/技能数/授权规则数）。
- **技能 Tab**：技能中心（技能管理已从设置页迁移至此，复用 `SkillsScreen`）。

本质上是**只读聚合视图**：不产生业务变更，只把 agent / settings / skill 领域的现有数据（ToolRegistry、SkillStateRepository、ExecutionModeHolder、AIProviderRepository、PermissionRulesRepository）组合成 UI 模型。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `presentation/CapabilityCenterViewModel.kt` | 能力中心 ViewModel（`@HiltViewModel`）：聚合工具快照、Agent 概览响应式流、技能列表；定义 `ToolUiModel`、`ParameterUiModel`、`AgentInfoUi` |
| `presentation/component/CapabilityCenterScreen.kt` | 能力中心页 Compose UI：三 Tab 结构（`CapabilityTab` 枚举）、工具卡片/权限徽标/能力标签/参数行、Agent 概览卡与统计卡 |

## 3. 核心架构与主流程

### 3.1 数据来源与响应式聚合

- **工具**（一次性快照）：`ToolRegistry.getAvailableTools()` 在组合时读取（工具在 Agent 工作流启动时注册，非运行时不变化），按名称排序后 `toUiModel()` 映射为 `ToolUiModel`（name/description/permissionPolicy/capabilities/parameters）。
- **技能**（响应式）：`SkillStateRepository.skillsFlow` → `stateIn(WhileSubscribed(5000))`。
- **Agent 概览**（响应式聚合）：`combine(executionModeHolder.mode, aiProviderRepository.getActiveProvider(), permissionRulesRepository.globalRulesFlow, permissionRulesRepository.currentProjectRulesFlow, skillStateRepository.skillsFlow)` 计算 `AgentInfoUi`：
  - `executionMode`：`ExecutionMode.LOCAL_PROOT` / `REMOTE_SSH`；
  - `activeProviderName` / `activeProviderModel`（`provider?.effectiveModel`）；
  - `permissionRuleCount`：全局规则数 + 当前项目规则数；
  - `toolCount` / `skillCount` 计数。
  - 初始默认值：`LOCAL_PROOT` + 全空/0。

### 3.2 UI 结构

- `CapabilityTab` 枚举：`TOOLS / AGENT / SKILLS`（各自带标题资源）。
- `CapabilityCenterScreen(viewModel, currentSessionMode, onNavigateBack)`：`PrimaryTabRow` + 按选中 Tab 渲染：
  - `TOOLS` → `ToolsTab`（`ToolCard` 可展开，展示权限徽标 `PermissionBadge`（AUTO_APPROVE 绿 / ASK 橙）、能力标签 `CapabilityChip`（最多先显示 4 个）、参数行 `ParameterRow`）。
  - `AGENT` → `AgentTab`（运行时卡：执行模式/激活 Provider/会话模式 `currentSessionMode`；统计卡：工具数/技能数/授权规则数；权限策略说明卡）。
  - `SKILLS` → 内嵌 `hiltViewModel<SkillsViewModel>()` 渲染 `SkillsScreen`（技能中心迁移自设置页）。
- 空工具列表时显示 `AppEmptyState`。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `CapabilityCenterScreen(viewModel, currentSessionMode, onNavigateBack)` | Compose 页面；`currentSessionMode: AgentMode` 由调用方传入（BUILD/PLAN/AUTO 展示用） |
| `CapabilityCenterViewModel` | `@HiltViewModel`，暴露 `tools`（List）、`skills`（StateFlow）、`agentInfo`（StateFlow） |
| `CapabilityTab` | 三 Tab 枚举（TOOLS/AGENT/SKILLS） |

依赖的外部模块（只读消费，不修改）：agent 领域（`ToolRegistry`/`AgentTool`/`ToolCapability`/`ToolPermissionPolicy`、`SkillStateRepository`/`Skill`、`PermissionRulesRepository`、`AgentMode`）、settings（`ExecutionModeHolder`/`ExecutionMode`、`AIProviderRepository`）、settings.presentation（`SkillsViewModel` + `SkillsScreen` 复用技能中心）。

## 5. 关键设计点与约束

- **只读聚合**：模块不持有业务状态、不发写操作，全部数据来自上游领域仓库/流；工具列表为一次性快照（工具在 Agent 工作流启动时注册），其余响应式订阅。
- **技能中心复用**：SKILLS Tab 直接复用设置页的 `SkillsViewModel`/`SkillsScreen`，避免两套实现，迁移到能力中心后设置页不重复维护。
- **UI 模型解耦**：`ToolUiModel`/`ParameterUiModel`/`AgentInfoUi` 将领域模型投影为展示模型，页面不直接依赖 `AgentTool` 等内部结构。
- **权限策略展示**：`ToolPermissionPolicy` 目前仅 `AUTO_APPROVE`/`ASK` 两种，映射为绿/橙徽标；新增策略需同步扩展 `PermissionBadge`。

## 6. 维护与扩展指引

- **新增能力展示来源**：在 `CapabilityCenterViewModel` 增加对应领域仓库/流依赖与 UI 模型字段，并在 `CapabilityCenterScreen` 新增 Tab 或区块。
- **新增工具权限策略**：扩展 `ToolPermissionPolicy` 时同步 `PermissionBadge` 的 when 分支与文案资源。
- **工具列表实时化**：如未来工具改为运行时动态注册，需将 `tools` 从一次性快照改为 `ToolRegistry` 流 + `stateIn`，并同步 `AgentInfoUi.toolCount` 的聚合方式。
- **测试建议**：覆盖三 Tab 切换、空工具空态、Agent 概览聚合（执行模式/Provider/规则计数随上游流变化）、技能 Tab 复用渲染。
