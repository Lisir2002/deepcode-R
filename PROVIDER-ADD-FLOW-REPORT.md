# 模型提供商「添加流程 + 页面」细化分析报告

> 生成时间：2026-08-16
> 阅读范围：Settings 模块提供商相关全部代码（入口 → 弹窗 → 编辑页 → ViewModel → Repository → DAO → Entity → 网络探测）

---

## 一、功能入口总览（两条添加路径）

模型提供商有 **两条进入"添加/编辑"的路径**，都由 [SettingsScreen.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt) 的 `section` 内部状态机驱动（`SettingsSection.Menu → Providers → ProviderEditor`）：

| 路径 | 触发点 | 载体 | 步数 | 适用场景 |
|------|--------|------|------|----------|
| **AddProviderSheet**（快速向导） | Providers 二级页顶栏「+」按钮（[SettingsScreen.kt L229-234](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt#L229-L234)） | 全屏高底部弹窗（屏占比 9/10） | 内置 3 步 / 自定义 2 步 | 新手、用内置供应商 |
| **ProviderEditorScreen**（完整编辑） | Providers 列表点击条目（[SettingsScreen.kt L294-300](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt#L294-L300)） | 独立全屏页（TopAppBar + 底部双 Tab） | 无分步，单表单 | 编辑已有 / 高级配置 |

> 关键接线：`SettingsScreen` 中 `ProviderEditorScreen` 是 **early-return**（[L186-200](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt#L186-L200)），不嵌套在外层 Scaffold 里；系统返回键在二级页先回 `Providers`（[L177-183](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt#L177-L183)）。

---

## 二、页面结构细化

### 2.1 AddProviderSheet（[AddProviderSheet.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/AddProviderSheet.kt)）

布局自顶向下：
1. **TabRow**：「内置供应商」|「自定义供应商」两个 Tab（切 Tab 保留各自 step 状态，互不干扰）；
2. **内容区**（weight=1f，可滚动）：按当前 Tab + 当前 step 渲染；
3. **底部固定按钮**：`上一步`（第 1 步禁用）+ `下一步/完成`，两 Tab 共用同一排按钮（`currentStep/totalSteps` 跟随当前 Tab）。

**步骤与可点"下一步"的条件（canGoNext）**：

| Tab | 步骤 | 内容 | canGoNext |
|-----|------|------|-----------|
| 内置 | 1 | 选择供应商（当前仅 `阶跃星辰` 一张卡片） | `selectedBuiltIn != null` |
| 内置 | 2 | 配置连接：协议（OpenAI 兼容 / Claude 兼容）+ 通道（Step Plan / 标准）+ 端点预览 + API Key | `apiKey.isNotBlank()` |
| 内置 | 3 | 选择模型（预填 `STEPFUN_DEFAULT_MODELS`，可逐行测试/删除） | true |
| 自定义 | 1 | 基础信息：名称 + 类型（OPENAI/ANTHROPIC/GEMINI）+ API Key + Base URL | 名称/Key/URL **任一非空** |
| 自定义 | 2 | 选择模型（空列表可，手动逐条添加/删除） | true |

### 2.2 ProviderEditorScreen（[ProviderEditorScreen.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/ProviderEditorScreen.kt)）

- **TopAppBar**：标题「添加/编辑提供商」；返回键=保存并返回（`BackHandler` 同逻辑）；右上角操作：删除（仅编辑态，垃圾桶图标，错误色）+ 添加模型（`+`，切到模型 Tab 并弹 AddModelSheet）；
- **底部 NavigationBar 双 Tab**：
  - **Tab0 配置**：名称 / 启用开关（isEnabled）/ 类型三选 FilterChip / API Key / Base URL（空则 placeholder 提示默认值）/ **完整 URL 开关 useFullUrl** / **Responses API 开关 useResponseApi**（仅 OPENAI 类型显示）/ **兼容端点能力判定策略**（STRICT/HEURISTIC/LAX/MANUAL 下拉）+ **发送失败自动降级** 开关 + **viewImage 识图守卫策略** 下拉；
  - **Tab1 模型**：模型数统计 + 「拉取模型」按钮（FetchModelsDialog）+ 模型列表（每行 ProviderModelRow：Logo + 能力 Tag + 手动覆盖齿轮 + 测试按钮 + 删除）。

---

## 三、添加流程逐步剖析

### 3.1 内置供应商（StepFun 3 步向导）—— 组装逻辑 [L171-193](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/AddProviderSheet.kt#L171-L193)

完成时组装 `AIProviderConfig`：
- `id = System.currentTimeMillis().toString()`（时间戳字符串，主键）；
- `type` 由协议推导：OpenAI 兼容 → `OPENAI`，Claude 兼容 → `ANTHROPIC`；
- `baseUrl = stepFunBaseUrl(protocol, channel)`（4 组合的端点映射）；
- `defaultModel / selectedModel = builtInModels.firstOrNull()`（默认第一个 step-3.7-flash）；
- `isActive = false`（新添加不自动激活）、`isEnabled = true`；
- 预填模型：step-3.7-flash / step-3.5-flash / step-3.5-flash-2603 / step-router-v1 / stepaudio-2.5-chat。

### 3.2 自定义供应商（2 步向导）—— 组装逻辑 [L194-214](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/AddProviderSheet.kt#L194-L214)

- 名称空 → 默认「自定义供应商」；Base URL 空 → `defaultProviderBaseUrl(type)` 兜底（OpenAI=api.openai.com、Anthropic=api.anthropic.com、Gemini=generativelanguage.googleapis.com）；
- `useFullUrl / useResponseApi` **固定 false**（弹窗里没有这两个开关，只在 Editor 页能配）；
- 模型可为空（用户后续在编辑页拉取/手动添加）。

### 3.3 保存链路（核心，含防数据丢失逻辑）

```
AddProviderSheet.onSave → SettingsViewModel.saveProvider → AIProviderRepositoryImpl.saveProvider → AIProviderDao.insertProvider(REPLACE)
```

**[AIProviderRepositoryImpl.saveProvider L45-64](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/repository/AIProviderRepositoryImpl.kt#L45-L64) 两个关键保护**：
1. **RC71 API Key 防覆盖**：先读库中已有 `encryptedApiKey`（`existingEncrypted`）传给 `toEntity`；`toEntity` 三分支——用户填了新 key → **必须加密成功否则抛异常中止**（绝不写空串覆盖旧密文）；没填但已有密文 → 保留；两者皆无 → 空串。
2. **active 互斥（DB-SHIELD-RC68 P0-1）**：`provider.isActive=true` 时先 `deactivateAllProviders()` 清所有行再 insert，保证 DB 中 active=1 最多 1 行（修复过"连点/误传 active=true 导致两条 active、切换模型下拉随机回跳"的脏数据 bug）。

**[SettingsViewModel.saveProvider L992-1003](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/SettingsViewModel.kt#L992-L1003) RC72**：`runCatching` 包裹，加密失败不闪退，错误写入 `_saveError` → [ProviderEditorScreen L184-190](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/ProviderEditorScreen.kt#L184-L190) 用 Toast 提示后 `consumeSaveError()`。

### 3.4 持久化形态（[AIProviderEntity.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/local/entity/AIProviderEntity.kt)）

| 字段 | 说明 |
|------|------|
| id | String 主键（时间戳生成） |
| encryptedApiKey | **AES-256-GCM 加密**（明文 apiKey 列已 RC68 SCHEMA38 DROP），默认 `''` |
| defaultModel | 语义已重命名为「当前选中模型」（selectedModel 冗余列已删，UI 同一语义） |
| models | 换行符分隔的模型列表 |
| isActive | 互斥激活（全局 ≤1） |
| isEnabled | 是否出现在「切换模型下拉」（可多选勾选，与 isActive 语义明确区分） |
| useFullUrl / useResponseApi | 布尔开关 |

---

## 四、模型管理流程

### 4.1 三种添加模型方式

1. **手动添加**：AddModelSheet —— 去重校验（`duplicate`），重名禁用添加并红字提示；
2. **拉取模型**：FetchModelsDialog（延迟 300ms 等弹窗动画后再发请求）→ `ModelApiService.fetchModels` → 按品牌分组、品牌 header 可折叠、搜索过滤（忽略大小写）、点击行 `+` 添加并立即 `saveCurrent()`；
3. **编辑页删除模型**：`onRemove → models.remove(model); saveCurrent()` 即时落库。

### 4.2 fetchModels 细节（[ModelApiService.kt L44-104](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/remote/ModelApiService.kt#L44-L104)）

- OpenAI 兼容 → `GET /v1/models`（取 `data[].id`）；Anthropic → `GET /v1/models`；Gemini → `GET /v1beta/models`（取 `models[].name` 并 `removePrefix("models/")`）；
- **T2I Failover**：非 Gemini/Anthropic 且返回 **404** 时，探测 `POST /v1/images/generations`——若通，则判定 baseUrl 是「仅文生图网关 / step_plan 这类含 v1 前缀但无 /v1/models 的网关」，回退返回一列常见文生图模型名（step-2x-large、dall-e-3、SDXL、flux 等）；
- 鉴权：Anthropic 用 `x-api-key` + `anthropic-version`，Gemini 用 `x-goog-api-key`，其余 `Authorization: Bearer`。

### 4.3 testModel（模型连通性测试，[L111-197](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/remote/ModelApiService.kt#L111-L197)）

- 每家一条极短请求：Anthropic `v1/messages`（max_tokens=1）、Gemini `v1beta/models/{model}:generateContent`（含 `baseUrl` 已以 model 结尾的拼接分支）、OpenAI `v1/chat/completions`（或 Responses API `input` 格式）；
- `useFullUrl=true` 时直接用 baseUrl 拼完整请求，不再拼接路径；
- **测试结果 UI**（ProviderModelRow）：成功显示绿色「连通 · xxms」；失败红色摘要（正则提取 HTTP 状态码），点击弹 Error Details 底部面板 + 一键复制完整错误（大厂排障友好）。

### 4.4 模型元数据与能力覆盖（RC62e/RC63 体系）

- `resolveModelMetadata(type, modelIds)` → ModelMetadataService（models.dev 目录 + 名称启发式 + 兼容策略修正 + 用户覆盖）→ 模型行展示 Chat/识图/工具/思考/上下文 token 标签；
- **CapabilityOverrideSheet**：单模型三能力（Vision/Tools/Reasoning）**三级复选框**（null=跟随自动 / true=开 / false=关），并展示「判定链路审计」（启发式匹配结果 + 兼容策略 + 手动覆盖），红点徽章标记"被手动覆盖"的项；保存写 `model_capability_overrides` 表。

---

## 五、关键实现细节与发现的问题

### 5.1 设计亮点
1. **RC71 加密失败中止保存**——避免任何异常路径下 API Key 被空串永久覆盖（历史严重 bug 修复）；
2. **active 互斥仓储级兜底**——两条路径（saveProvider / setActiveProvider）都先清后写；
3. **T2I 双 Failover**（拉取 + 测试）——把"网关 404"与"Key/网络错误"区分开，避免误报；
4. **编辑页"空输入不落库"**：`hasSubstantiveInput()`——新建且全空白时返回键不保存，避免存入「新提供商」空记录；
5. **编辑页主动保存策略**：加/删模型即 `saveCurrent()`，无需显式保存按钮；返回键同样自动保存。

### 5.2 潜在问题 / 限制
1. **id 用 `System.currentTimeMillis()`**：快速连续添加可能撞主键（REPLACE 覆盖）——低概率但非不可复现；
2. **内置向导的"测试"用空 API Key**：[BuiltInModelList L639-654](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/AddProviderSheet.kt#L639-L654) 硬编码 `apiKey = ""`、`baseUrl = stepPlan`，用户在第 2 步填的 key 未传入 → 内置 Tab 里的测试按钮**必然报"请先填写 API Key"**（功能未打通）；
3. **useFullUrl / useResponseApi 在快速向导不可配**：自定义 2 步里无此开关，用户走向导添加后仍需进编辑页开启；
4. **内置 Tab 协议默认值**：默认 OpenAI 兼容 + Step Plan 通道；切协议/通道不联动改已填 key 语义（正常）；
5. **`AIProviderConfig.effectiveModel`**：`selectedModel.ifBlank { defaultModel }`，但 DB 侧 selectedModel 已合并进 defaultModel，域模型仍保留双字段，读侧 `toDomainModel` 两字段同值，语义冗余。

### 5.3 新增后的下一步（激活）

- 新添加的 provider `isActive=false`，不会立即成为聊天用提供商；
- 主页模型选择胶囊（依赖 `activeProvider`）通过 `ensureActiveProvider()` 兜底——若库里无 active 则自动激活首个；
- 用户可在主页模型选择器或编辑页中 `setActiveProvider` 切换激活项（`deactivateAllProviders + activateProvider` 互斥）。

---

## 六、小结（一句话串联）

用户在 **设置 → 提供商** 页点「+」→ 弹 **AddProviderSheet**（内置 3 步选「阶跃星辰+协议+通道+Key+模型」或自定义 2 步填名称/类型/Key/URL/模型）→ 点「完成」→ `saveProvider` 走 **RC71 加密防覆盖 + RC68 active 互斥** 落库（`ai_providers` 表，key 加密、模型换行存储）→ 之后可进 **ProviderEditorScreen** 补充 useFullUrl/useResponseApi/兼容策略/能力覆盖，拉取或测试模型，最后在主页或编辑页**激活**该提供商投入使用。

---

*报告完 · 依据 [AddProviderSheet.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/AddProviderSheet.kt)、[ProviderEditorScreen.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/ProviderEditorScreen.kt)、[SettingsScreen.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/SettingsScreen.kt)、[SettingsViewModel.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/SettingsViewModel.kt)、[AIProviderRepositoryImpl.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/repository/AIProviderRepositoryImpl.kt)、[ModelApiService.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/remote/ModelApiService.kt) 等逐文件精读*
