# Settings（设置）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/settings/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

负责 R-CodeCore 的**全部应用设置**：AI Provider（供应商/API Key/模型）管理与模型能力元数据解析、日志（等级/查看器/筛选/实时尾随）、主题、后台保活、MCP 服务器、权限规则、容器 Profile 与执行模式（本地 PRoot / 远程 SSH）、安全设置（凭据加密状态/密钥轮换/ZTH 档位）、技能中心、关于/更新等。

核心是 **Activity 级复用单例 `SettingsViewModel`**：一次性注入十几个 DataStore/Room 仓库 + 跨模块服务（MCP、权限、容器、远程 SSH），以多个 StateFlow 驱动设置页各二级分区（`SettingsSection`）。数据层统一两种持久化：**Room（`ai_providers` 表）** 存 AI 供应商，**DataStore/SharedPreferences** 存各类设置项，均以 `Flow` 对外暴露。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/local/dao/AIProviderDao.kt` | `ai_providers` 表 Room DAO：CRUD、active 互斥激活、defaultModel 更新 |
| `data/local/entity/AIProviderEntity.kt` | 供应商实体，`encryptedApiKey` 唯一存密钥（Keystore AES-256-GCM） |
| `data/remote/ModelApiService.kt` | 拉取供应商模型列表 + 测试连通性（OkHttp 直连），404 时自动 Failover 到文生图端点探测 |
| `data/remote/ModelMetadataService.kt` | models.dev 模型元数据 catalog：解析/内存-磁盘-内置 assets 三级缓存/启动刷新 + 三能力决策链 |
| `data/repository/AIProviderRepositoryImpl.kt` | `AIProviderRepository` 实现：Entity↔Domain 转换、API Key 加解密、active 互斥不变量 |
| `data/repository/ExecutionModeHolder.kt` | 执行模式内存同步缓存（解决 DataStore 异步 vs Hilt `@Provides` 同步的矛盾） |
| `data/repository/ExecutionModeRepository.kt` | 执行模式（LOCAL_PROOT/REMOTE_SSH）+ 远程连接配置持久化（v1 legacy / v2 统一数据源双路径） |
| `data/repository/ContainerSettingsRepository.kt` | 容器 profile 选择、自定义 profile 列表、共享设备存储开关（DataStore） |
| `data/repository/KeepaliveSettingsRepository.kt` | 后台保活常驻通知开关（AIEditorApp 监听后启停 Service） |
| `data/repository/ThemeSettingsRepository.kt` | 应用主题模式（AUTO/DARK/LIGHT），兼容旧深色开关 |
| `data/repository/LogSettingsRepository.kt` | 日志最低记录等级 |
| `data/repository/LogFilterSettingsRepository.kt` | 日志查看器筛选偏好（等级/Tag/日期范围模式） |
| `data/repository/VisionModelSettingsRepository.kt` | 识图专用模型（providerId+model） |
| `data/repository/CompactionModelSettingsRepository.kt` | 上下文压缩专用模型 |
| `data/repository/DefaultModelSettingsRepository.kt` | 新会话默认模型（providerId+model） |
| `data/repository/CompatibilityPolicyRepository.kt` | 兼容端点策略：DefaultPolicy(STRICT/HEURISTIC/LAX/MANUAL)、发送失败自动降级、viewImage 守卫策略 |
| `data/repository/ZthTierRepository.kt` | ZTH 档位 + 性能等级 + Swipe 开关（供安全设置与 ZthGuard 门禁） |
| `data/repository/SyncSettingsRepository.kt` | 同步忽略模式/useGitignore/批次大小（SharedPreferences） |
| `data/repository/NormFlowSettingsRepository.kt` | **规范流程统一开关**（norm-chain D1，DataStore）：总开关 `norm_flow_enabled` + 子开关 `step_inject_enabled`（step 前注入纪律）/`tool_guard_enabled`（guard 护栏链），Flow 暴露 + `isStepInjectActive()`/`isToolGuardActive()` 供 `StatefulAgentWorkflow` 判定 |
| `data/repository/SshConfigResolver.kt` | `RemoteConnectionSettings.resolveSshConfigOrNull` 扩展：v2 占位配置 → 真实 `RemoteConnectionConfig` |
| `domain/model/AIProviderConfig.kt` | 供应商领域模型 + `ProviderType`(OPENAI/ANTHROPIC/GEMINI) + 默认 API 路径 |
| `domain/model/ModelMetadata.kt` | 模型元数据（上下文/输入输出 token、三能力）+ `Source`(MODELS_DEV/INFERRED) + `InferenceReason` 审计信息 |
| `domain/model/ModelContextPolicy.kt` | 上下文 token 分配常量与估算工具（usableInputTokens / preserveRecentTokens / estimateTokens） |
| `domain/repository/AIProviderRepository.kt` | AI 供应商仓储接口（流式 provider 列表 + 同步 active 读取 + `ensureActiveProvider`） |
| `domain/SkillImporter.kt` | 技能导入管线：ZIP 压缩包 / 单 MD 文件 / 粘贴文本 / URL 下载四来源统一准备（预校验 + Zip Slip 防护 + 冲突检测）→ 确认落地 |
| `domain/SkillExporter.kt` | 技能导出：LOCAL 技能目录 zip 打包（供分享/迁移） |
| `presentation/SettingsViewModel.kt` | 设置页编排：所有 StateFlow、Provider CRUD/模型拉取/测试、MCP、权限、容器与执行模式、日志查看器（含 live tail）；**规范流程开关（D1）**：收集 `NormFlowSettingsRepository` 三开关 Flow → `normFlowEnabled/stepInjectEnabled/toolGuardEnabled` StateFlow + `setNormFlowEnabled/setStepInjectEnabled/setToolGuardEnabled` 操作方法 |
| `presentation/SecuritySettingsViewModel.kt` | 安全设置：凭据加密状态/迁移/密钥轮换/重置 + ZTH 三字段合成 UI 状态 |
| `presentation/SkillsViewModel.kt` | 技能中心：启用/禁用/卸载、作用域覆盖、统一导入管线（ZIP/MD/粘贴/URL 准备→确认）、导出与分享 |
| `presentation/SkillDetailViewModel.kt` | 技能查看页：加载技能、构建目录树、文件内容读取/语法高亮分派 |
| `presentation/SkillEditViewModel.kt` | 技能编辑器：frontmatter 表单解析/回填、正文编辑、新增/删除文件、另存为新技能 |
| `presentation/AboutStatsViewModel.kt` | 关于页使用统计 |
| `presentation/component/SettingsScreen.kt` | 设置主屏：`SettingsSection` 二级分区路由、顶栏、各 section 分发、跨屏 openSection 信号；首页 16 个菜单项用 Material Rounded 图标 + 每项专属日夜间彩色图标块（`iconBgLight`/`iconBgDark`，随 `LocalAppDarkMode` 切换，见 §5）；**规范流程分区（D1）**：新增 `NormFlow` 菜单项与 `NormFlowSection` 渲染分支（总开关 + 两个子开关，`GroupSwitchRow`） |
| `presentation/component/ProvidersAndLogSection.kt` | 供应商列表 `ProvidersSection` + `ProviderItem`、空态 |
| `presentation/component/ProviderEditorScreen.kt` | 供应商编辑页（Tab0 兼容端点策略 / Tab1 模型列表与三能力覆盖） |
| `presentation/component/ProviderModelComponents.kt` | `ProviderModelRow`/`FetchModelRow`/`CapabilityOverrideSheet`/能力徽章 |
| `presentation/component/AddProviderSheet.kt` | 供应商添加向导：内置供应商（StepFun 协议/渠道、内置卡片、分步拉取模型）+ 自定义供应商（2 步：基本信息 → 拉取/手动添加/测试模型），拉取状态与编辑页隔离 |
| `presentation/component/ProviderLogo.kt` | 供应商/品牌 Logo 图标与显示名映射 |
| `presentation/component/McpSettingsSection.kt` / `McpEditorDialog.kt` / `McpHttpFields.kt` / `McpStdioFields.kt` | MCP 服务器列表、编辑弹窗、HTTP/stdio 字段表单 |
| `presentation/component/LogSettingsSection.kt` | 日志等级卡片 + 日志查看器（筛选面板/日期/搜索/实时尾随/着色） |
| `presentation/component/DefaultModelsSection.kt` | 默认模型选择 |
| `presentation/component/ContainerSettingsSection.kt` | 容器 profile 管理与共享存储开关 |
| `presentation/component/PermissionsSettingsSection.kt` | 权限规则（全局/项目）管理与提升 |
| `presentation/component/ThemeSelectionSheet.kt` | 主题选择 |
| `presentation/component/SkillsScreen.kt` | 技能中心 UI（分组/搜索/筛选/卡片增强/操作按钮化/导入导出入口） |
| `presentation/component/SkillDetailScreen.kt` | 技能查看页 UI（「文件/详情」Tab + Hero 详情卡 + 当前文件面包屑 + 可折叠目录树弹窗 + 代码高亮/图片预览 + 空/加载态） |
| `presentation/component/SkillEditScreen.kt` | 技能编辑器 UI（frontmatter 表单 + Markdown 正文 + 新增文件 + 另存为新技能） |
| `presentation/component/AboutSection.kt` | 关于页：统计、FAQ、开源致谢、版本比较工具 |
| `presentation/component/SearchUtils.kt` | 通用搜索工具 |
| `presentation/components/SecuritySettingsScreen.kt` | 安全设置 UI（生物识别/密钥轮换/ZTH） |
| `presentation/components/RemoteAuditLogsScreen.kt` | 远程审计日志 UI |
| `presentation/components/BackupEncryptOptionsSection.kt` / `SecurityEmergencyChannelSection.kt` | 备份加密选项 / 安全应急通道 UI |

## 3. 核心架构与主流程

### 3.1 AI Provider CRUD 主流程

```
SettingsScreen / ProviderEditorScreen / AddProviderSheet
  → SettingsViewModel（saveProvider / setActiveProvider / setProviderEnabled / deleteProvider / selectModel）
    → AIProviderRepositoryImpl
      → AIProviderConfig.toEntity(existingEncrypted)  // RC71：非空新 Key 必须加密成功，失败抛异常防空串覆盖
      → AIProviderDao.insertProvider(REPLACE)  // isActive=true 时先 deactivateAllProviders 保证互斥
      → Room ai_providers 表（仅存 encryptedApiKey）
```

- 读取路径：`getAllProviders()` / `getActiveProvider()` 将 Entity 解密还原为 `AIProviderConfig`，解密失败回退空串不崩 UI。
- **active 互斥不变量**：`saveProvider`/`setActiveProvider` 都先 `deactivateAllProviders()` 再激活，保证 DB 中 `isActive=1` 最多 1 行。
- 启动兜底：`ensureActiveProvider()` 保证库中存在供应商时必有激活项，避免主页模型胶囊消失。
- 模型拉取/测试：`ModelApiService.fetchModels`（OpenAI/Anthropic 用 `/v1/models`，Gemini 用 `/v1beta/models`）；列表接口 404 时探测 `POST /v1/images/generations`，通则回退常见文生图模型名列表；`testModel` 按 ProviderType 构造最小请求并测延迟。UI 侧拉取状态分三路隔离：编辑页 `_fetchState`、内置向导 `_builtInFetchState`、自定义供应商向导 `_customFetchState`（`fetchCustomModels`），互不覆盖。

### 3.2 模型元数据决策链（ModelMetadataService）

`resolve(type, modelId)` 的完整链路：

1. `loadCatalog()`：内存缓存 → 磁盘缓存（`models-dev-api.json`，<24h）→ 内置 assets `api.official.json`，全程只读不发网络。
2. `findMetadata()`：按 provider 优先级在 catalog 中查模型，命中则 `Source=MODELS_DEV`。
3. 未命中 → `default()`：用 modelId 启发式推断 `probablyVision/probablyReasoning/probablyTools`，`Source=INFERRED`。
4. `applyCompatibilityPolicies()`（RC63 决策链末两步）：
   - INFERRED 源先应用 `DefaultPolicy`（STRICT/HEURISTIC 保持启发式、LAX 全 true、MANUAL 全 false）；
   - 最后应用单模型复选框覆盖（`ModelCapabilityOverrideDao`，优先级最高，MODELS_DEV 与 INFERRED 都允许）。
5. 结果写入 `InferenceReason` 供 UI 的「来源徽章 / 智能预填 banner」审计展示。

catalog 刷新：App 启动调 `refreshFromNetworkIfStale()`；`init` 中监听 `ClashProxyManager.state`，代理就绪后自动补拉一次（解决启动时代理未就绪首拉失败的问题）。

### 3.3 执行模式 / 容器 Profile 切换

`SettingsViewModel.setActiveContainerProfile(id)`：

0. 默认值：首次启动未持久化 profile 时，`ContainerSettingsRepository.activeProfileIdFlow` 按宿主架构自动选内置容器（x86_64 宿主 → 内置 x86_64，其余 → 内置 arm64，见 `EnvironmentDetector.defaultProfileId()`），真机与模拟器零配置可用。
1. 定位 profile（自定义 / 内置 arm64 / 内置 x86_64）。
2. `ContainerSettingsRepository.setActiveProfile` 持久化选择。
3. 本地镜像 → `ExecutionModeRepository.setExecutionMode(LOCAL_PROOT)` + `ExecutionModeHolder.setMode`。
4. 远程 SSH 镜像 → 从 `RootfsSource.RemoteSsh` 取 `connectionId`，组装 v2 `RemoteConnectionSettings(activeConnectionId=...)` 持久化，置 REMOTE_SSH 模式，并主动 `RemoteSshConnection.connect`。
5. `ExecutionModeHolder` 是内存同步缓存，供 DI `@Provides` 同步读模式决定注入实现；模式切换后已注入单例不自动切换，提示重启。

### 3.4 日志查看器

- `loadLogs`：列文件 → 按日期筛选 → `readTailLines`（RandomAccessFile 从尾部向前读，UTF-8 安全）→ 缓存原始行 → 多维过滤（server/等级/Tag/关键词）→ 尾部截断 `MAX_LOG_LINES=1200`。
- 筛选防抖：`_filterTrigger.debounce(300)` 后对**已缓存行**做局部过滤，不重复读文件。
- 实时尾随：`FileObserver` 监听日志目录 `CLOSE_WRITE` 事件置 `hasNewLogs` 标志。

### 3.5 跨屏跳转信号

`openSection(Section)` 用 tick 对比机制（`pendingOpenSectionTick` vs `lastConsumedSectionTick`）实现「外部设置页跳转 SettingsScreen 内部二级分区」，纯状态机保证不漏不重入（如 TerminalSettingsScreen 跳 SSH 主机配置）。

### 3.6 技能中心（SkillsScreen → SkillsViewModel + 详情页 + 编辑器）

设置页以 `SkillsSection` 承接技能管理：`SkillsViewModel` 负责技能启用/禁用/卸载、作用域覆盖、统一导入管线与导出分享（agent 侧 `SkillStateRepository` 的 Room 状态），`SkillsScreen.kt` 为列表 UI，`SkillDetailScreen`/`SkillEditScreen` 为查看页与编辑器（路由 `skill_detail/{id}` / `skill_edit/{id}`）。技能加载/执行语义由 agent 模块承载，见 [agent.md §3.6](./agent.md#36-技能skill)。

**列表页能力**（本期重构）：按来源分组（内置 / 用户）/ 关键词搜索 / 执行形态筛选；卡片增强展示（作用域徽章、自动触发徽章、类型 Pill、版本）；操作按钮化（查看 / 编辑 / 启用开关 / 卸载 / 导出）；顶部「导入」入口（ZIP / 单 MD / 粘贴文本 / URL 四来源）。

**查看页**（仅查看，内置技能只读）：页内「文件 / 详情」两个 Tab——「详情」为 Hero 元信息卡片（名称/类型/来源/作用域/自动触发/版本/描述/标签，可滚动）；「文件」默认文件智能选择（SKILL.md → CLAUDE.md → 第一个文件），Markdown 经 `verticalScroll` 包裹可滚动阅读；「目录」按钮或当前文件面包屑条唤出半屏目录树弹窗（目录节点可折叠/展开，当前文件高亮）；脚本/代码文件按扩展名推断语言做基础语法高亮、图片采样解码预览防 OOM；文件读取与导出异步化（IO 线程）避免卡顿；LOCAL 技能可从查看页进入编辑或导出。目录树折叠状态由 `collapsedPaths` 维护，`SkillDetailViewModel` 提供 loading 态与 `findDefaultPath()`。

**编辑器**（仅 LOCAL 用户技能）：结构化 frontmatter 表单（名称/描述/版本/作者/标签/执行形态/入口脚本/MCP 工具/作用域/agent 类型/自动触发与触发条件/依赖等）+ Markdown 正文编辑；支持新增文件、删除文件、另存为新技能（目录名自动加 `-copy`）；`id` 为目录名不可改。

**导入管线**（`SkillImporter`）：ZIP / 单 MD（选文件）/ 粘贴文本 / URL 下载四来源统一 `prepare` → 预校验（解析 frontmatter、Zip Slip 防护、大小上限、单技能约束）→ `Ready`/`Illegal`/`Conflict` 三态 → 用户确认（含 overwrite 冲突决策）→ 落地安装；无 SKILL.md 的目录自动补默认 frontmatter。

**导出**（`SkillExporter`）：LOCAL 技能目录 zip 打包，经系统分享面板导出。

**作用域分级在设置侧的体现**（多 Agent 演进 + 对话级控制）：技能按 `SkillScope` v2（GLOBAL / AGENT / CONVERSATION）分级，见 [agent.md §3.6.1](./agent.md#361-技能作用域分级skillscope-v2多-agent-演进--对话级控制)：
- `GLOBAL 全局`：技能中心**默认展示用户开关**（默认启用，可关）；
- `AGENT`：仅绑定 agentType 匹配当前 agent 时可见/可开关；
- `CONVERSATION 对话级`：默认休眠，用户需在对话页「技能」面板显式添加后才在该对话生效。

即设置侧暴露「能关」的开关 + 作用域覆盖；对话级双向控制（添加/临时禁用）由对话页 `ConversationSkillsSheet` 承接（agent 模块），运行时按作用域动态注册/回收仍由 `SkillToolBindingManager` 在 agent 激活时执行。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `AIProviderRepository` | agent 聊天模块消费：`getActiveProviderSync()`/`getProviderById()`/`ensureActiveProvider()` 等决定当前模型接口调用 |
| `ModelApiService` | 模型列表拉取与连通性测试（ProviderEditor、内置供应商向导共用） |
| `ModelMetadataService` | `resolveAll`/`observeOverride`/`saveOverride`/`clearOverride` 被聊天工作流与设置页消费；`refreshFromNetworkIfStale` 由 App 启动调用 |
| `ExecutionModeHolder` / `ExecutionModeRepository` | DI `@Provides` 同步读取决定注入本地或远程实现；`AIEditorApp` 启动时按 v2 配置连接远程 SSH |
| `RemoteConnectionSettings.resolveSshConfigOrNull` | 供启动连接链路把 v2 占位配置解析成真实 `RemoteConnectionConfig` |
| `ZthTierRepository` | 被 `ZthGuardAggregateFacade.prepareEnv` 与安全设置页消费 |
| `KeepaliveSettingsRepository` | `AIEditorApp` 监听 `enabledFlow` 启停 `TerminalKeepaliveService` |
| `ThemeSettingsRepository` | `AIEditorApp` 监听应用主题 |
| 备份模块 | 备份/还原各设置项调用本模块各仓库的 `snapshot()`/`restore()`（Theme/Log/Keepalive/Vision/Compaction/Default/Compatibility 等） |

## 5. 关键设计点与约束

- **密钥仅密文存储**：RC68 SCHEMA 38 删除明文 `apiKey` 列，只存 `encryptedApiKey`；RC71 起「用户输入新 Key 必须加密成功，否则抛异常中止保存」，杜绝加密失败静默空串覆盖；`selectedModel` 冗余列合并进 `defaultModel`。
- **active 互斥在仓储层保证**：不依赖 UI 传参，`saveProvider`/`setActiveProvider` 统一先清后激活，修复过「两条 active 脏数据导致下拉回跳旧 provider」问题。
- **ExecutionModeHolder 必要性**：DataStore Flow 异步、Hilt `@Provides` 同步，二者无法直接桥接，故引入内存缓存中间层。
- **SSH 配置 v1/v2 双路径**：v2 只存 `activeConnectionId`（凭据以 Room 为唯一数据源）；v1 legacy 字段仅作老用户回退；`decryptCredentialCompat` 只用前缀判断、**绝不在冷启动 Flow 上触发加密子系统初始化**（曾导致启动期两线程争用伪死锁 ANR）。
- **兼容端点策略默认 STRICT**：catalog 已收录模型零影响；INFERRED 才应用策略，未设置一律回退保守值，避免影响整体。
- **`CompatibilityPolicyRepository` 的 enum 用 class-level 嵌套**：规避 Kotlin 版本差异导致 CI 解析失败的坑。
- **模型拉取 Failover**：`/v1/models` 404 → 探测文生图端点，避免用户把「仅文生图网关」误判为 Key 错误。

## 6. 维护与扩展指引

- **图标主题化（全应用）**：设置页 `MenuItem` 扩展 `iconBgLight`/`iconBgDark` 两个语义色字段（`Color.Unspecified` 表示沿用灰色默认），渲染时按 `LocalAppDarkMode` 取亮/暗色传给 `CyberMenuRow.iconBg`；`iconBg` 非空时 `CyberMenuRow` 走「彩色圆角图标块 + 固定白色图标」（Material You 风格），为空则保持原灰色图标块 + 灰色图标（About 等复用方不受影响）。该模式已推广到侧边栏（`ChatDrawer`）、聊天顶栏（`ChatHeaderComponents`）及各子页面（终端/工作区/Git/凭据/备份/代理/能力中心/浏览器），图标统一用 `material-icons-extended` 的 Rounded 系列替换旧 Feather 单色图标，方向性图标（ArrowBack/ArrowForward/Send/Menu/KeyboardArrowLeft/Right 等）用 `Icons.AutoMirrored.Rounded.*` 规避弃用警告；每个分区配独立色相（如供应商蓝、模型紫、MCP 青、安全红、备份琥珀），日夜间两套模式下均有足够对比度。
- **新增一个 DataStore 设置项**：仿 `VisionModelSettingsRepository`/`ThemeSettingsRepository` 模式——私有 `preferencesDataStore` 扩展 + `@Singleton` 构造注入 + `Flow` 暴露 + suspend 写方法（无 DI module），并同步在 `SettingsViewModel` 加 StateFlow 订阅与 `setXxx` 方法。
- **新增供应商类型**：改 `ProviderType` 枚举、`ModelApiService.applyAuth`/路径分支、`defaultProviderApiPath`、`ModelMetadataService.findMetadata` 的 preferredProviders 列表，并考虑备份/还原兼容。
- **新增 Provider 字段**：同步 `AIProviderEntity`（注意 `@ColumnInfo(defaultValue)` 与迁移脚本一致，避免 Room TableInfo 校验失败）、`AIProviderConfig`、`toEntity`/`toDomain`、备份 DTO。
- **数据库 schema 升级**：`ai_providers` 相关迁移须与 `AIProviderDao`/Entity 对齐（历史教训：RC68 SCHEMA 38 删除明文列）。
- **新增二级分区**：在 `SettingsSection` 枚举加项 + `SettingsScreen` 的 when 分发 + `SettingsViewModel` 状态。
- **测试建议**：覆盖加密失败不覆盖密文、active 互斥、模型元数据决策链各策略分支、执行模式切换（本地/远程）、日志筛选与 live tail。

## 7. 版本演进记录

> 本模块开发维度演进；用户可见变更见仓库根 [CHANGELOG.md](../../CHANGELOG.md)。

- **v0.3.0-rc2（2026-08-26）**：设置页「规范流程」二级页补齐 playbook / SOP / 轨迹等子开关的说明（修复 D5/D4/D2 子开关说明缺失）。
- **v0.3.0-rc1（2026-08-25）**：规范流程统一开关（D1 norm-flow，总开关 `norm_flow_enabled` + `step_inject_enabled` / `tool_guard_enabled` 子开关）。
- **v0.2.0（2026-08-25）**：四款回复气泡样式与设置切换（chatrender）；能力中心入口移入设置页，侧边栏底部改两图标贴右。
- **v0.1.x（2026-08-22 ~ 23）**：设置页保活图标 Pulse 改 Favorite；设置页与关于页深色模式白底改主题语义色。
- **v0.1.0（早期）**：设置模块整体落地（AI Provider、日志、主题、保活、MCP、权限、容器、执行模式、安全、技能中心、关于）。
