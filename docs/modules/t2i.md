# 文生图（T2I）模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/t2i/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

提供 **Text-to-Image 文生图**能力（RC69 新增）：让 Agent/用户通过「文生图 Provider + 模型」生成图片并落盘展示。核心关注点：

- **Provider 抽象**：对齐 LLM `AIProvider` 思路但契约完全独立——T2I 接口的请求/响应体 shape、同步/异步形态、鉴权头与 LLM 完全不同，独立建模与建表。
- **三种 endpoint 形态**：SYNC（直接返回 base64 图）、ASYNC（返回远端 task_id 后轮询）、AUTO（首次最小成本请求探测并缓存）。
- **额度治理**：`T2IPermissionPolicyEngine` 六阶段（P1~P6）权限策略组合，按日/会话/月度/单次成本做 ALLOW / ASK / DENY 判定，失败按可退款语义回加额度。
- **任务状态机**：PENDING → RUNNING → SUCCESS / PENDING_RETRY / FAILED / DANGLING，支持重试与冷启动崩溃恢复。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/local/entity/T2IProviderEntity.kt` | 文生图 Provider 表 `t2i_providers`：type / baseUrl / encryptedApiKey / endpointMode / isActive / priority / isEnabled / extraHeadersJson |
| `data/local/entity/T2IProviderModelEntity.kt` | Provider 下具体模型表 `t2i_provider_models`：modelId / supportsHd / supportsInpaint / 默认尺寸与步数 / costPerImageTokens |
| `data/local/entity/T2ITaskEntity.kt` | 文生图任务表 `t2i_tasks`：提示词与参数、路由快照（providerId/modelId/endpointModeRef）、状态机、错误码、权限决策快照与已扣 token |
| `data/local/dao/T2IProviderDao.kt` | Provider 表访问：增删改查、激活/停用、优先排序、endpointMode 回写、加密 Key 更新 |
| `data/local/dao/T2IProviderModelDao.kt` | 模型表访问：按 provider 查模型、成本更新、批量删除 |
| `data/local/dao/T2ITaskDao.kt` | 任务表访问：会话/消息维度查询、悬垂任务扫描、状态机更新（markSuccess/markFailedOrRetry/setRemoteTaskId/setPermissionDecision）、额度统计（日/会话已扣 token、当日成功图数） |
| `data/remote/ImageGenerator.kt` | Provider 抽象接口（`generate` + `Request`/`Result`/`EndpointMode`/`ProviderException`）与 `OpenAiCompatibleImageGenerator` 实现 |
| `data/remote/T2IModelProbeService.kt` | T2I 专用探测服务：连通性探测（测速）、默认模型候选列表、AUTO 形态探测（SYNC/ASYNC 判定） |
| `domain/permission/T2IPermissionPolicyEngine.kt` | 权限策略组合引擎 P1~P6：强制确认/日额度/会话额度/月度额度/渐进保护/单次成本兜底 |

## 3. 核心架构与主流程

### 3.1 Provider 与模型数据模型

- **独立三张表**：`t2i_providers`（Provider）+ `t2i_provider_models`（模型，`(providerId, modelId)` 唯一索引）+ `t2i_tasks`（任务）。与 LLM `AIProviderEntity` 独立建表，避免 LLM 专属列语义污染。
- Provider `type`：`OPENAI_COMPATIBLE | ANTHROPIC | GEMINI | STABLE_DIFFUSION | CUSTOM`；`endpointMode`：`SYNC | ASYNC | AUTO`（默认 AUTO，探测后回写缓存）。
- Provider 互斥激活：全局最多 1 行 `isActive=true`（仓储级先 `deactivateAllProviders` 再 `activateProvider`）；`isEnabled` 为多选可用开关；`priority` 数字越大优先级越高（failover 排序）。
- API Key 用 Android Keystore + AES-256-GCM 加密后存 `encryptedApiKey`。

### 3.2 生成主流程（`ImageGenerator.generate`）

`OpenAiCompatibleImageGenerator`（`@Singleton`）实现 OpenAI Images API 兼容契约（覆盖 90%+ 的 T2I 兼容网关）：

1. 运行时配置注入：`setRuntimeConfig(baseUrl, apiKey)`（由 T2I Repository 路由时传入，避免 ImageGenerator 直接依赖 DAO 成环）。
2. 构造 `POST {baseUrl}/v1/images/generations` 请求（`Authorization: Bearer`），payload 含 `model/prompt/size/n=1/response_format=b64_json`，可选 `quality=hd/negative_prompt/steps/seed`。
3. 响应处理：
   - 非 2xx：解析 `error.code/message` → 抛 `ProviderException(errorCode, msg, refundable)`；**HTTP 4xx（内容审核/模型拒绝）不退款，5xx/429/NETWORK_ERROR 退款**。
   - 2xx：取 `data[0].b64_json`；兼容层只回 `url` 时回退下载。
4. 持久化：写 `{outputDir}/{taskId}.png` 主图 + `{taskId}_thumb.png` 缩略图（RC69 第一版缩略图与主图同内容，占位；注释说明后续用 `Bitmap.createScaledBitmap` 生成 ≤256px 缩小版，避免会话列表一次性 inflate 大图 OOM），`MAX_IMAGE_BYTES = 20MB` 安全兜底。
5. 返回 `Result(imagePath, thumbnailPath, modeUsed=SYNC, remoteTaskId="")`。

### 3.3 探测服务（`T2IModelProbeService`）

- `probeConnection`（测速）：最小中性请求 `prompt="a blue dot on white background"`、`size=256x256`、`response_format=b64_json`，返回结构化 `T2IProbeResult`（latencyMs / httpStatus / userMessage / reasonCode），`classify` 区分 `OK / KEY_INVALID(401,403) / PATH_404 / RATE_LIMIT(429) / NSFW / BAD_PARAM / SERVER_ERR(5xx)`；鉴权头按 `ProviderType` 适配（Bearer / x-api-key / x-goog-api-key）。
- `suggestDefaultModels`：拉模型接口 404 时的候选模型列表（StepFun/DALL·E/Flux/SDXL/万相/混元等），按 host 关键字对已知供应商轻量前置。
- `probeEndpointMode`：AUTO 形态探测——响应含 `b64_json|url` → SYNC；含 `task_id|id` → ASYNC；非 2xx → null（保持 AUTO 下次再探）。结果由调用方 `T2IProviderDao.updateEndpointMode` 持久化。
- 所有 URL 一律走 `joinUrl`，避免 "baseUrl 带 /v1 + 再拼 v1 = 双 v1 = 404"。

### 3.4 权限策略引擎（P1~P6，严格优先级短路）

`evaluate(Request): EvalResult`，任一阶段命中 DENY/ASK 即短路返回：

| 阶段 | 判定 | 结果 |
| --- | --- | --- |
| P1 强制确认开关（`forceConfirm`） | 设置页「每次生成都必须确认」 | 无条件 ASK |
| P2 日额度 | `sum(quotaDeductedTokens)`(今日, SUCCESS+FAILED) + 本次 > 日上限 | DENY `DAILY_QUOTA_EXCEEDED` |
| P3 会话额度 | 同 session 累计 + 本次 > 会话上限 | DENY `SESSION_QUOTA_EXCEEDED` |
| P4 月度供应商额度 | 当月累计 + 本次 > 月度总额度 | RC69 暂跳过（月度计数器未持久化，TODO(RC69+)，相当于月度不限） |
| P5 渐进保护 | 当日成功图数 ≥ 阈值 → 升级为 ASK | ASK（"今天已经用了不少"） |
| P6 兜底 | 单次成本 > 贵图阈值（=5 张标准图费用）→ ASK，否则 ALLOW | ASK / ALLOW |

- 额度解析优先级：`override > tier > default`。默认值：日 5000 / 会话 1500 / 渐进 20 张 / 贵图阈值 500 tokens。
- `tierQuotas()`（复用 `ZthTierRepository`，免费/Pro/Enterprise 可差异化额度）RC69 第一版全部返回 null（即 FREE 档回落默认值），注释说明避免 T2I 反向侵入 agent 安全模块的 ZTH 分层。
- `EvalResult` 返回 `verdict / tokensToDeduct / denyCode / denyMessage / askReason`；tokensToDeduct 供仓储预扣，失败按 `ProviderException.refundable` 回加。

### 3.5 任务状态机（`T2ITaskEntity` + `T2ITaskDao`）

```
PENDING ──► RUNNING ──► SUCCESS
               │  │
               │  └── 失败且 retryCount < maxRetries(3) ──► PENDING_RETRY ──► PENDING
               └── 失败且无重试 ──► FAILED
```

- 冷启动崩溃恢复（T2ITaskRecoveryWorker 语义）：`getDanglingTasks(cutoffMs)` 扫描 `PENDING/RUNNING/PENDING_RETRY 且 updatedAtMs < now-30min` 的悬垂任务 → ASYNC 形态轮询一次或标记 `DANGLING` 提供 UI 重试。
- **路由快照最小化**：任务只快照 `providerId + modelId + endpointModeRef`；**密钥永不快照**，恢复时从 `t2i_providers` 实时读取（用户改 key 后历史未完成任务用新 key）；`providerRef`（baseUrl）留空占位，恢复时按 providerId 实时查。
- 配额统计：`sumDeductedTokensSince` / `sumDeductedTokensForSession` 只算 `SUCCESS+FAILED`（FAILED 也可能扣了额度未退款，退款由仓储在失败路径回加 `quotaDeductedTokens`）；`countSuccessfulImagesSince` 供 P5 与 UI 统计卡。
- 失败/退款语义：`errorCode` 机器可读，权限引擎据此判断是否退款；任务记录 `permissionDecision` 与 `quotaDeductedTokens` 快照。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `ImageGenerator.generate(request)` | 文生图抽象接口；`Request`（prompt/negativePrompt/宽高/步数/seed/hd/model/outputDir/taskId）、`Result`、`EndpointMode`、`ProviderException(errorCode, refundable)` |
| `OpenAiCompatibleImageGenerator` | `@Singleton` OpenAI 兼容实现；`setRuntimeConfig(baseUrl, apiKey)` 由仓储路由时注入 |
| `T2IModelProbeService.probeConnection / suggestDefaultModels / probeEndpointMode` | 测速 / 候选模型 / AUTO 形态探测 |
| `T2IPermissionPolicyEngine.evaluate(Request)` | 权限策略评估（P1~P6）；`Request` 含 sessionId/costPerImageTokens/各 override 与开关 |
| `T2ITaskDao / T2IProviderDao / T2IProviderModelDao` | 任务、Provider、模型的 Room 数据访问 |

依赖的外部模块：core.util（`FileLogger`）、agent.domain.provider（`joinUrl`）、settings（`ZthTierRepository`、`ProviderType`）、core.security（API Key 加密，经仓储使用）。

## 5. 关键设计点与约束

- **与 LLM Provider 完全解耦**：独立接口契约、独立 3 张表、独立探测服务，不复用 `AIProvider` 的 request/response shape。
- **AUTO 探测缓存**：首次生成前最小成本探测 endpoint 形态并回写 `endpointMode`，之后直接用缓存值，避免每次生成都探测。
- **退款语义**：`ProviderException.refundable` 区分「用户没拿到图但额度被扣」与「用户违规 prompt 占用额度」；4xx 内容审核不退款，5xx/429/网络错误退款。
- **权限策略短路**：P1 最高优先级无条件挡；P2/P3 硬拒绝带机器可读 `denyCode` 供 UI 引导；P5 渐进提醒、P6 贵图确认。
- **月度额度待补**：P4 当前 no-op（月度计数器 DataStore 未持久化），`saveProvider` 已预留字段，RC70 补一行即可。
- **缩略图占位**：RC69 第一版缩略图与主图同内容，避免在非 UI 模块引入 `android.graphics.*`（便于 Provider 层单测），等 UI 需要时再替换为缩放实现。
- **快照最小化**：任务不保存密钥/baseUrl，恢复时实时读取，保证配置变更不影响历史任务且换 key 后任务用新 key。
- **崩溃恢复**：悬垂任务以 30 分钟为界扫描，ASYNC 轮询或标记 DANGLING 重试。

## 6. 维护与扩展指引

- **新增 Provider 类型**：在 `T2IProviderEntity.type` 扩展类型；若接口契约不同（如 ANTHROPIC/GEMINI 原生），新增实现 `ImageGenerator` 的 Adapter（参考 `OpenAiCompatibleImageGenerator` 的 `setRuntimeConfig` 注入模式，避免依赖 DAO 成环）。
- **启用月度额度（P4）**：在 T2I 仓储加 `t2i_monthly_used_tokens_${yyyyMM}` DataStore 持久化，恢复 `T2IPermissionPolicyEngine.evaluate` 中 P4 段的 TODO，并将 `tierQuotas().monthly` 与 ZTH Tier 打通。
- **接入 ZTH 分层额度**：`tierQuotas()` 当前全 null；启用 Tier 切换后按 FREE/PRO/ENTERPRISE 返回差异化 daily/session/monthly/progressive/singleExpensive。
- **实现真实缩略图**：将 `persistImage` 的缩略图写入替换为 `BitmapFactory.decodeByteArray` + `Bitmap.createScaledBitmap`（≤256px），注意模块间 android.graphics 依赖边界。
- **扩展异步轮询**：ASYNC 形态的轮询状态推进与超时策略、`remoteTaskId` 复用，可参考 `setRemoteTaskId` + `markSuccess/markFailedOrRetry` 接口扩展。
- **测试建议**：覆盖 P1~P6 短路顺序与额度临界值、SYNC/ASYNC/AUTO 探测判定、4xx 不退款 / 5xx 退款、状态机流转（含重试与悬垂恢复）、文件落盘与超大图拦截。
