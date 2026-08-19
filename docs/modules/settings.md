# Settings（设置）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/settings/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

负责 R-CodeCore 的**全部应用设置**：AI Provider（供应商/API Key/模型）管理与模型能力元数据解析、日志（等级/查看器/筛选/实时尾随）、主题、语言、后台保活、MCP 服务器、权限规则、容器 Profile 与执行模式（本地 PRoot / 远程 SSH）、安全设置（凭据加密状态/密钥轮换/ZTH 档位）、技能中心、关于/更新等。

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
| `data/repository/LanguageSettingsRepository.kt` | 应用语言（BCP-47 tag，null 跟随系统），双写 DataStore+SharedPreferences |
| `data/repository/LogSettingsRepository.kt` | 日志最低记录等级 |
| `data/repository/LogFilterSettingsRepository.kt` | 日志查看器筛选偏好（等级/Tag/日期范围模式） |
| `data/repository/VisionModelSettingsRepository.kt` | 识图专用模型（providerId+model） |
| `data/repository/CompactionModelSettingsRepository.kt` | 上下文压缩专用模型 |
| `data/repository/DefaultModelSettingsRepository.kt` | 新会话默认模型（providerId+model） |
| `data/repository/CompatibilityPolicyRepository.kt` | 兼容端点策略：DefaultPolicy(STRICT/HEURISTIC/LAX/MANUAL)、发送失败自动降级、viewImage 守卫策略 |
| `data/repository/ZthTierRepository.kt` | ZTH 档位 + 性能等级 + Swipe 开关（供安全设置与 ZthGuard 门禁） |
| `data/repository/SyncSettingsRepository.kt` | 同步忽略模式/useGitignore/批次大小（SharedPreferences） |
| `data/repository/SshConfigResolver.kt` | `RemoteConnectionSettings.resolveSshConfigOrNull` 扩展：v2 占位配置 → 真实 `RemoteConnectionConfig` |
| `domain/model/AIProviderConfig.kt` | 供应商领域模型 + `ProviderType`(OPENAI/ANTHROPIC/GEMINI) + 默认 API 路径 |
| `domain/model/ModelMetadata.kt` | 模型元数据（上下文/输入输出 token、三能力）+ `Source`(MODELS_DEV/INFERRED) + `InferenceReason` 审计信息 |
| `domain/model/ModelContextPolicy.kt` | 上下文 token 分配常量与估算工具（usableInputTokens / preserveRecentTokens / estimateTokens） |
| `domain/repository/AIProviderRepository.kt` | AI 供应商仓储接口（流式 provider 列表 + 同步 active 读取 + `ensureActiveProvider`） |
| `presentation/SettingsViewModel.kt` | 设置页编排：所有 StateFlow、Provider CRUD/模型拉取/测试、MCP、权限、容器与执行模式、日志查看器（含 live tail） |
| `presentation/SecuritySettingsViewModel.kt` | 安全设置：凭据加密状态/迁移/密钥轮换/重置 + ZTH 三字段合成 UI 状态 |
| `presentation/SkillsViewModel.kt` | 技能中心：启用/禁用/卸载技能 |
| `presentation/AboutStatsViewModel.kt` | 关于页使用统计 |
| `presentation/component/SettingsScreen.kt` | 设置主屏：`SettingsSection` 二级分区路由、顶栏、各 section 分发、跨屏 openSection 信号 |
| `presentation/component/ProvidersAndLogSection.kt` | 供应商列表 `ProvidersSection` + `ProviderItem`、空态 |
| `presentation/component/ProviderEditorScreen.kt` | 供应商编辑页（Tab0 兼容端点策略 / Tab1 模型列表与三能力覆盖） |
| `presentation/component/ProviderModelComponents.kt` | `ProviderModelRow`/`FetchModelRow`/`CapabilityOverrideSheet`/能力徽章 |
| `presentation/component/AddProviderSheet.kt` | 内置供应商向导（StepFun 协议/渠道、内置供应商卡片、分步拉取模型） |
| `presentation/component/ProviderLogo.kt` | 供应商/品牌 Logo 图标与显示名映射 |
| `presentation/component/McpSettingsSection.kt` / `McpEditorDialog.kt` / `McpHttpFields.kt` / `McpStdioFields.kt` | MCP 服务器列表、编辑弹窗、HTTP/stdio 字段表单 |
| `presentation/component/LogSettingsSection.kt` | 日志等级卡片 + 日志查看器（筛选面板/日期/搜索/实时尾随/着色） |
| `presentation/component/DefaultModelsSection.kt` | 默认模型选择 |
| `presentation/component/ContainerSettingsSection.kt` | 容器 profile 管理与共享存储开关 |
| `presentation/component/PermissionsSettingsSection.kt` | 权限规则（全局/项目）管理与提升 |
| `presentation/component/LanguageSettingsSection.kt` / `ThemeSelectionSheet.kt` | 语言选择 / 主题选择 |
| `presentation/component/SkillsScreen.kt` | 技能中心 UI |
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
- 模型拉取/测试：`ModelApiService.fetchModels`（OpenAI/Anthropic 用 `/v1/models`，Gemini 用 `/v1beta/models`）；列表接口 404 时探测 `POST /v1/images/generations`，通则回退常见文生图模型名列表；`testModel` 按 ProviderType 构造最小请求并测延迟。

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
| `ThemeSettingsRepository` / `LanguageSettingsRepository` | `AIEditorApp` 监听应用主题 / `AppCompatDelegate.setApplicationLocales` |
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

- **新增一个 DataStore 设置项**：仿 `VisionModelSettingsRepository`/`ThemeSettingsRepository` 模式——私有 `preferencesDataStore` 扩展 + `@Singleton` 构造注入 + `Flow` 暴露 + suspend 写方法（无 DI module），并同步在 `SettingsViewModel` 加 StateFlow 订阅与 `setXxx` 方法。
- **新增供应商类型**：改 `ProviderType` 枚举、`ModelApiService.applyAuth`/路径分支、`defaultProviderApiPath`、`ModelMetadataService.findMetadata` 的 preferredProviders 列表，并考虑备份/还原兼容。
- **新增 Provider 字段**：同步 `AIProviderEntity`（注意 `@ColumnInfo(defaultValue)` 与迁移脚本一致，避免 Room TableInfo 校验失败）、`AIProviderConfig`、`toEntity`/`toDomain`、备份 DTO。
- **数据库 schema 升级**：`ai_providers` 相关迁移须与 `AIProviderDao`/Entity 对齐（历史教训：RC68 SCHEMA 38 删除明文列）。
- **新增二级分区**：在 `SettingsSection` 枚举加项 + `SettingsScreen` 的 when 分发 + `SettingsViewModel` 状态。
- **测试建议**：覆盖加密失败不覆盖密文、active 互斥、模型元数据决策链各策略分支、执行模式切换（本地/远程）、日志筛选与 live tail。
