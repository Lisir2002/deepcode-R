# 添加供应商 · 内置供应商（阶跃星辰）流程细化分析

> 生成时间：2026-08-16
> 核心文件：[AddProviderSheet.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/AddProviderSheet.kt)（全流程）、[ProviderModelComponents.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/ProviderModelComponents.kt)（模型行/测试 UI）、[SettingsViewModel.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/SettingsViewModel.kt)（testModel 入口）、[ModelApiService.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/data/remote/ModelApiService.kt)（网络测试实现）

---

## 一、内置供应商的当前形态

内置供应商 Tab 目前 **只有 1 个供应商：阶跃星辰（StepFun）**，通过枚举 `BuiltInProvider` 定义：

```kotlin
enum class BuiltInProvider(val displayName: String, val description: String) {
    STEPFUN("阶跃星辰", "原生多模态 · 识图优化 · 双协议兼容")
}
```

扩展新内置供应商 = 在该枚举加一项 + 在 `BuiltInProviderContent` 步骤 1 加一张 `BuiltInProviderCard`。当前步骤 1 硬编码只渲染 `BuiltInProvider.STEPFUN` 一张卡片。

---

## 二、内置供应商专属的 4 个数据模型（文件顶部定义）

### 2.1 协议枚举 `StepFunProtocol`（双协议兼容）
```kotlin
OPENAI("OpenAI 兼容") / CLAUDE("Claude 兼容")
```

### 2.2 通道枚举 `StepFunChannel`
```kotlin
STEP_PLAN("Step Plan 通道") / STANDARD("标准通道")
```

### 2.3 端点映射 `stepFunBaseUrl(protocol, channel)` —— 4 组合确定唯一 baseUrl

| 协议 | Step Plan 通道 | 标准通道 |
|------|----------------|----------|
| **OpenAI 兼容** | `https://api.stepfun.com/step_plan/v1` | `https://api.stepfun.com/v1` |
| **Claude 兼容** | `https://api.stepfun.com/step_plan` | `https://api.stepfun.com` |

要点：Claude 兼容模式下 baseUrl **不带 `/v1` 后缀**（Anthropic 适配器 / ModelApiService 内部会自行 join `v1/messages`，避免 `v1/v1` 重复）；OpenAI 兼容则带 `/v1`。这是 4 组合端点映射如此设计的原因。

### 2.4 预填模型列表 `STEPFUN_DEFAULT_MODELS`
```kotlin
step-3.7-flash / step-3.5-flash / step-3.5-flash-2603 / step-router-v1 / stepaudio-2.5-chat
```

---

## 三、三步向导的状态管理与步骤流转

### 3.1 状态定义（`AddProviderSheet` 顶层）

```kotlin
var builtInStep by remember { mutableIntStateOf(1) }          // 当前步骤 1/2/3
var selectedBuiltIn by remember { mutableStateOf<BuiltInProvider?>(null) }
var protocol by remember { mutableStateOf(StepFunProtocol.OPENAI) }     // 默认 OpenAI 兼容
var channel by remember { mutableStateOf(StepFunChannel.STEP_PLAN) }    // 默认 Step Plan 通道
var apiKey by remember { mutableStateOf("") }
var builtInModels by remember { mutableStateOf(STEPFUN_DEFAULT_MODELS) } // 预填 5 模型
```

### 3.2 步骤与「下一步」可用性（canGoNext）

| 步骤 | UI 内容 | 可点「下一步/完成」条件 |
|------|---------|------------------------|
| **1 选择供应商** | `BuiltInProviderCard`（选卡片高亮 primaryContainer + 边框 + 右侧 Check 勾） | `selectedBuiltIn != null` |
| **2 配置连接** | 协议 FilterChip × 2 + 通道 FilterChip × 2 + 端点预览（Surface 灰底 + 复制图标）+ API Key 输入 | `apiKey.isNotBlank()`（**必填**） |
| **3 选择模型** | `BuiltInModelList`（预填 5 模型，每行可测试/删除） | 恒 true |

### 3.3 步骤流转函数（goBack / goNext）

- `goBack`：`builtInStep > 1` 才 `builtInStep--`；第 1 步按钮 `enabled=false` 置灰；
- `goNext`：`builtInStep < 3` 时 `builtInStep++`；到第 3 步再点 = **完成**，组装 provider 并 `onSave(provider)` + `onDismiss()`。

### 3.4 与自定义 Tab 的状态隔离

内置/自定义各自维护独立的 step 与表单状态（`builtInStep` vs `customStep`、`apiKey` vs `customApiKey` 等），**切 Tab 不丢已填内容**；但底部按钮区共用（`currentStep/totalSteps` 跟随当前 Tab），切换 Tab 时步进状态各自独立复位到各自上次位置。

---

## 四、步骤 1：选择供应商

- 标题「选择供应商」+ `StepIndicator(current=1, total=3)`（圆点指示器：当前=实心大点、已完成=半透明小点、未到=灰点）；
- `BuiltInProviderCard`：Card 整卡可点，选中态 primaryContainer 底色 + primary 描边 + 右侧 Check 图标；非选中 surface 底色 + outlineVariant 描边；
- 左侧 40dp 圆形图标（`FeatherIcons.Cpu`，primaryContainer 底 + primary 色），右侧两行文字（名称 + 描述）。

---

## 五、步骤 2：配置连接（核心差异化步骤）

自顶向下顺序：
1. **协议 FilterChip**（`StepFunProtocol.entries.forEach` 渲染 2 个 chip）；
2. **通道 FilterChip**（`StepFunChannel.entries.forEach` 渲染 2 个 chip）；
3. **端点预览**：`val baseUrl = stepFunBaseUrl(protocol, channel)` 实时重算，显示在 `Surface(surfaceVariant 底色, RoundedCornerShape)` 灰底框内，右侧有 `FeatherIcons.Copy` 复制图标（contentDescription 复用 `chat_copy` 字符串）；
4. **API Key 输入**：`OutlinedTextField`，label "API Key"，placeholder "sk-..."，单行。

> 关键：端点预览随协议/通道选择**即时联动**，用户在点完成前就能看到将要保存的 baseUrl。

---

## 六、步骤 3：模型选择（含测试/删除）

`BuiltInModelList`（[L626-667](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/AddProviderSheet.kt#L626-L667)，**本次已修复**）：

- 对 `models`（预填 5 个）逐行渲染 `ProviderModelRow`；
- 每行结构（ProviderModelRow，[ProviderModelComponents.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/ProviderModelComponents.kt)）：
  - 左侧 `ModelLogoIcon`（按模型名取 logo）；
  - 中间模型名 + 能力标签（本内置流程传 `metadata=null`，因此只显示固定 "Chat" 标签，不显示识图/工具/思考标签——因为保存前还没 resolve 元数据）；
  - **能力覆盖齿轮按钮**：内置流程传 `onOpenCapabilityOverride = {}`（空操作，不弹覆盖面板）；
  - 右侧：`测试`按钮（测试中变 16dp CircularProgressIndicator）| `删除`（X 图标）；
  - 测试结果行：成功绿色「连通 · xxms」；失败红色摘要（正则提取 `HTTP \d{3}` 或 `code:xxx`），失败行可点击 → 弹 Error Details 底部面板（等宽字体完整错误 + 一键复制到剪贴板，复制后 1.5s 变 Check）。

---

## 七、模型测试链路（修复后完整闭环）

```
点「测试」→ ProviderModelRow.onTest
  → BuiltInModelList 组装 AIProviderConfig（复用步骤 2 的 apiKey/protocol/channel）
  → SettingsViewModel.testModel(provider, model)   [viewModelScope.launch]
      → _testing += model（UI 转圈）
      → ModelApiService.testModel(baseUrl, apiKey, type, useFullUrl=false, useResponseApi=false, model)
      → _testResults[model] = result（UI 渲染成功/失败）
      → _testing -= model
```

### 组装逻辑（修复后，[L646-660](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/AddProviderSheet.kt#L646-L660)）

```kotlin
type = if (protocol == StepFunProtocol.OPENAI) ProviderType.OPENAI else ProviderType.ANTHROPIC
baseUrl = stepFunBaseUrl(protocol, channel)   // 跟随用户所选协议×通道
apiKey = apiKey                               // 复用第 2 步已填 Key（此前硬编码 "" → 修复点）
useFullUrl = false / useResponseApi = false   // 快速向导固定 false（编辑页才能配）
```

### 网络实现（ModelApiService.testModel）

- **OpenAI 兼容**（step_plan/v1 或 /v1）→ `POST {baseUrl}/v1/chat/completions`，payload `{"model":..., "max_tokens":1, "messages":[...hi]}`，鉴权 `Authorization: Bearer`；
- **Claude 兼容**（step_plan 或裸域）→ `POST {baseUrl}/v1/messages`，payload `{"model", "max_tokens":1, ...}`，鉴权 `x-api-key` + `anthropic-version: 2023-06-01`；
- **T2I Failover**：OpenAI 兼容分支下 chat 接口 404 且非 useFullUrl → 探测 `POST /v1/images/generations`；该接口 404 → 报「聊天接口 404 · 文生图接口也 404」；否则报告「已自动 fall back 文生图接口」，仅 2xx 才算 success（401/403/429 算"可达但不成功"）；
- 成功 → `ModelTestResult(true, latency, "连通 · xxms")`；失败 → `ModelTestResult(false, latency, "HTTP xxx: ...")`；异常 → `ModelTestResult(false, latency, e.message)`。

---

## 八、完成：组装并保存

`goNext()` 第 3 步执行（[L179-192](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/presentation/component/AddProviderSheet.kt#L179-L192)）：

```kotlin
AIProviderConfig(
    id = UUID.randomUUID().toString(),            // 修复：时间戳 → UUID 防碰撞
    name = selectedBuiltIn?.displayName ?: "阶跃星辰",
    type = if (OPENAI) OPENAI else ANTHROPIC,     // 协议推导
    apiKey = apiKey,                              // 原样传入 → Repository 层加密
    baseUrl = stepFunBaseUrl(protocol, channel),  // 4 组合端点
    defaultModel = builtInModels.firstOrNull().orEmpty(),  // 默认第一个 step-3.7-flash
    isActive = false,                             // 新增不自动激活
    models = builtInModels,                       // 换行存储
    selectedModel = builtInModels.firstOrNull().orEmpty(),
    isEnabled = true                              // 出现在切换模型下拉
)
onSave(provider)  // → SettingsViewModel.saveProvider → RC71 加密防覆盖 + RC68 active 互斥 → Room
onDismiss()
```

---

## 九、边界与潜在改进点

1. **供应商只有 1 个**：步骤 1 对用户而言是「纯确认页」，扩展枚举即可新增；
2. **保存前不 resolve 模型能力标签**：步骤 3 的 `metadata=null`，识图/工具/思考标签不显示（保存后进编辑页才显示）——属设计取舍，非 bug；
3. **useFullUrl / useResponseApi 在向导不可配**：固定 false，需要高级配置须进编辑页；
4. **预填 5 个模型全保留**：即使某些模型用户不需要也入库，需在编辑页删除（步骤 3 支持逐行删除，可接受）；
5. **apiKey 必填才可到步骤 3**：`canGoNext` 强约束，防止空 Key 保存（与 Repository 层「空 Key 保留旧密文」策略一致，新 provider 空 Key 会被保存为空串）。

---

*报告完 · 依据 AddProviderSheet / ProviderModelComponents / SettingsViewModel / ModelApiService 逐函数精读*
