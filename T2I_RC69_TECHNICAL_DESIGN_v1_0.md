# RC69 文生图 (Text-to-Image, T2I) 功能技术设计规格书 v1.0

> **文档代号**：`T2I_RC69_TECHNICAL_DESIGN_v1_0`
> **发布版本绑定**：v0.1.0-rc69 (SCHEMA 39)
> **对应目录**：`app/src/main/java/com/deep/rcode/feature/t2i/`（独立 feature 包）+ `app/src/main/java/com/deep/rcode/feature/agent/domain/tool/image/GenerateImageTool.kt`（Tool 留在 agent 下复用 Tool 事件链与权限引擎）
> **与现有架构文档关系**：本设计是《COMPLETE_TECHNICAL_ARCHITECTURE_v1_0》与《ZTH_MODE_TECHNICAL_DESIGN_v1_0》在 AI Agent 能力侧的新增子章节，不改动已有 DB-SHIELD / 持久化护盾 / SCHEMA 38 的任何约定。
> **设计日期**：2026-08-11
> **状态**：✅ 全部规格讨论完毕，待代码实现

---

## 版本记录

| 版本 | 日期 | 作者 | 变更内容 |
|------|------|------|----------|
| v1.0 | 2026-08-11 | Agent 讨论 + 用户拍板 | 首轮完整规格：7 层蓝图定稿 + 3 张新表 SCHEMA 39 DDL + 权限组合引擎 P1~P6 + 包树图 + 8 步实现排程 + DataStore 配额结构 + SYNC/ASYNC/AUTO 端点形态自动切换 |

---

## 决策矩阵总表（全部 40+ 规格点）

下表是本设计的权威依据（"规格是什么"），任何代码实现不得违背。每一条都对应了用户的明确选择。

### 核心架构决策（问题 1）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| Q1.1 | T2I 实现路线 | **路线 B**：AI 调 `GenerateImageTool` + 独立 `ImageGenerator` 接口 | 与 AIProvider.chat 解耦，完全复用现有 Tool 事件链、权限引擎、落库备份；多轮上下文天然感知。 |
| Q1.2 | Provider 端点形态 | **SYNC + ASYNC + AUTO 三态共存**，`T2IProvider.endpointMode` 列控制 | AUTO 模式先做一次探测并缓存形态；Tool 层统一走 `Flow<T2IProgressEvent>` 接口（同步 Provider 只推 0%→100% 两段，异步 Provider 真推进度）。 |
| Q1.3 | RC69 首版粒度 | **一步到位完整版**：3 张新表 + 状态机 + SCHEMA 39 迁移 + 崩溃恢复 + 清理 Worker | 避免 RC69→RC70 再迁一次 SCHEMA。 |

### 持久化 & SCHEMA（问题 2 + 规格细化 2/DDL）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| Q2.1 | 消息与 task 的行数 | **1:N**：1 张图 = 1 行 `t2i_tasks` | 同一次 Tool 调用 n=4 并行跑时每张独立状态、独立 seed、独立重试。 |
| Q2.2 | 冗余副本双写策略 | **双写**：SUCCESS 时同步写 `agent_messages.attachmentsJson` 列 | BackupManager / ContextCompactor **0 行代码改动**；即使 SCHEMA 升级失败，聊天气泡里至少还能看到图。 |
| Q2.3 | 重试/变体策略 | **追加式 NEW 行 + `parentTaskId`** | 不可变审计（失败 seed vs 成功 seed 可对比），UI 可展开"生成历史 N 张"。 |
| Q2.4 | 上下文压缩后图片 | **只标记 `isCompacted=1`，不删原图文件** | 用户展开压缩历史还能看原图；文件放 filesDir 不怕小。 |
| Q2.5 | 删除会话级联策略 | **硬级联**：FK ON DELETE CASCADE 删 `t2i_tasks` 行 + 同步 `rm -rf filesDir/generated_images/{sid}/` | 防僵尸图长期占空间。 |
| DDL-2.1 | apiKey 存储加密 | **`encryptedApiKey TEXT`**（Keystore AES-256-GCM） | 与现有 `ai_providers.encryptedApiKey` 完全同形态对称（SCHEMA 38 已有加密基础设施）。**禁止明文存 API Key。** |
| DDL-2.2 | Provider 已添加模型列表 | **连接表 `t2i_provider_models`**（替代单列 modelsJson） | 支持 isDefault/displayName/supportsSize(JSON)/priority 扩展列；精确索引单条。 |
| DDL-2.3 | t2i_tasks.seed 列类型 | **TEXT** | 兼容 Flux/SDXL 超大 Long 种子或字母后缀确定性 seed，无截断风险。 |
| DDL-2.4 | `t2i_tasks.messageId` ↔ `agent_messages.id` | **不加 FK，Repository 手动解绑 messageId** | 删消息基本是"隐藏气泡软删标记"；不加 FK 避免级联误伤用户已收藏图。 |
| DDL-2.5 | 悬垂任务索引 | **SQLite PARTIAL INDEX**：WHERE status IN ('PENDING','GENERATING') | 只索引几百行悬垂任务，index size 比普通全表小 10~50 倍。 |
| DDL-2.6 | 连接表扩展列 | **加** isDefault / displayName / supportsSize(JSON) / priority 四列 | 设置页可单独调模型默认值/排序/支持尺寸；不用每次运行时探测。 |

### 模型路由（问题 3）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| Q3.1 | T2I 配置存放位置 | **独立 Room 表 `t2i_providers`** | 完全解耦：聊天走一家，画图走另一家（不同 API Key / 余额）；`ai_providers` 表 0 改动。 |
| Q3.2 | 默认路由规则 | **多激活 + 优先级 + failover** | 允许多个 T2IProvider 同时 isActive=1，按 priority 排序；429/配额耗尽自动 failover 下一个。 |
| Q3.3 | LLM 传 `model="flux-schnell"` 路由 | **模型名精确匹配** → 找到 provider | 跨供应商同名模型 rare，可解决 99% 场景；找不到就 fallback 到 active list 第一位。 |

### Tool 流、同步异步切换（问题 4）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| Q4.1 | 端点形态切换依据 | **T2IProvider.endpointMode = SYNC / ASYNC / AUTO** | AUTO 模式跑一次探测，探测后缓存形态到 DB `endpointModeUsed`；下次直接用缓存。 |
| Q4.2 | 用户取消语义 | **双向取消**：本地 cancel coroutine + ASYNC 远端 `DELETE /tasks/{id}` | 防止远端继续跑扣用户看不到的任务额度；SYNC 走 OkHttp 的 Call.cancel() 断连即可。 |
| Q4.3 | 崩溃恢复悬垂 GENERATING/PENDING | **尽力恢复 + 兜底 FAILED + UI 重试按钮**：先按 `remoteTaskId` 回查远端 `/tasks/{id}`；SUCCESS 正常落文件；查不到/超时/SYNC → UPDATE FAILED(CRASH_RECOVERY) + UI 显示"生成被中断，点此重试"。 | 不自动重跑（防止一重启就开始刷额度），让用户手动确认。 |

### 文件存储策略（问题 5）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| Q5.1 | 产物目录 | **`context.filesDir/generated_images/{sessionId}/{taskId}_{idx}.png`** 作为默认；用户点 UI 💾 按钮时**再写一次**到 `MediaStore` 相册（`DCIM/RDeepCode/`）。 | 不默认进相册骚扰用户；filesDir 随应用升级保留、卸载才删。 |
| Q5.2 | 图片格式 | **全 PNG 无损** | 尊重生成图质量；单张 1024x1024 ≈ 1.2MB 可接受。缩略图 256px 同 PNG（约 100KB）。 |
| Q5.3 | 后台清理策略 | **用户高度自定义**：DataStore 字段 `cleanupUnfavoredAfterDays` + `cleanupTotalSizeCapMB`，旁边"永不清理"按钮一键把 days 设 0。⭐️收藏 = 永留。 | 默认值在最后规格细化阶段由 UI 初始值决定。 |

### 附带议题 & 规格细化（权限/上下文/NSFW/默认值）

| 编号 | 决策点 | 最终选择 | 说明 |
|------|--------|----------|------|
| S-1 | Tool 返回图下一轮上下文回放策略 | **默认只回放 `AgentImage.path`，不塞 base64**；用户点"🔍分析这张图"或 LLM 调 `viewImage` 工具时才 base64 注入。 | 防 128K 上下文被 2 张 PNG 吃 40%。 |
| S-2 | 失败原因展示 | **具体原因透明展示**：NSFW / 余额不足 / 尺寸不支持 / provider 返回码 400 文字原样。 | 用户能针对性改 prompt 或跳设置页调额度。 |
| S-3 | 三种权限设置形态 | **三个复选框可自由组合**（不是单选）；规则引擎按 P1~P6 优先级做 AND/OR 组合；设置页附 300 字小白引导说明。 | 符合用户"三种都可选 + 引导提示"的明确要求。 |
| S-4 | 清理策略默认值 | **用户完全自定义**：初始值在 DataStore 默认 Proto 里写 days=14 + sizeCapMB=2000，但 UI 进入即弹引导横幅"要不要调整自动清理？"。 | 尊重用户自定节奏。 |
| PERM-1.1 | 组合引擎优先级 | **P1 强制每次确认 > 所有其他规则**。即用户勾了 forceAskEveryTime，即使渐进/额度还在前 3 张范围内 → 一律 ASK。 | 安全开关优先级最高。 |
| PERM-1.2 | 额度耗尽行为 | **加开关 `quotaExhaustionBehavior` = ASK_TEMP_BOOST(默认) / REJECT**。默认 ASK 弹"临时追加 N 张？"；用户可切严格模式 REJECT 跳设置。 | 顺滑优先、可切严格。 |
| PERM-1.3 | 额度计数落地 | **日+会话额度全进 DataStore 单文件 `T2IPermissionSettings`**（不建 Room 配额表）。 | 与设置项同位置，跨会话读同一个文件即可。 |
| PKG-3.1 | 包结构 | **`feature/t2i/` 独立包 + Tool 放 `feature/agent/domain/tool/image/GenerateImageTool.kt`** | 最佳隔离；Tool 必须复用 AgentContext，所以在 agent 下；其余数据/设置/UI 独立。 |
| PKG-3.2 | Entity/DAO 归属 | **`feature/t2i/data/local/entity|dao` 本包**，AgentDatabase entities 照样跨包引用。 | Entity 归自己的 feature 管，代码不漂到 core.db。 |
| PKG-3.3 | Room 绑定方式 | **同形态**：AgentDatabase 加 3 个 abstract fun；AgentModule 加 3 个 @Provides；import 用短名防 KSP ERROR 雪崩。 | 和 RC68 新增 5 个 DAO 的模式完全一致，不踩 KSP 坑。 |
| PKG-3.4 | 命名前缀 | **统一 T2I 前缀**：T2IProviderEntity、T2ITaskDao、T2IPermissionSettings、T2ICleanupManager、T2IGenerationCard。 | 一眼识别，与现有 Chat/Backup/Checkpoint 前缀风格一致。 |

---

## 一、7 层架构总览（从 Provider → UI）

T2I 功能在现有 7 层 Android Clean Architecture 基础上作为**增量平行层**，绝不替换/重构/侵入已有聊天链路。每层的职责和落地位置如下：

```
┌─ L7 Provider API 契约层（完全独立于 AIProvider）
│  位置：feature/t2i/data/remote/
│  ├─ ImageGenerator (interface)
│  │   └─ fun generate(T2IRequest): Flow<T2IProgressEvent>
│  ├─ dto/
│  │   ├─ T2IRequest              (prompt/negativePrompt/size/n/steps/seed/model/style)
│  │   ├─ T2IProgressEvent        (Percent(stage,pct) / Result(imageBytes,seed,latency) / Error(code,msg))
│  │   └─ T2ICapabilityProbe      (AUTO 形态探测缓存结果：SYNC/ASYNC)
│  └─ impl/
│      ├─ OpenAICompatImageGenerator   — 兼容端点 /v1/images/generations（DALL-E 3 / 你的 API）
│      └─ AsyncPollingSupport          — ASYNC 模式 polling + DELETE /tasks/{id} 取消
│
├─ L6 Model Capability 元数据层
│  位置：feature/settings/domain/model/ModelMetadata.kt（✅ 已有代码，加 1 个字段）
│  变更：ModelMetadata 新增 `supportsImageGeneration: Boolean = false`
│  启发式匹配：模型名正则匹配 /(dall-e|flux|stable-diffusion|sdxl|wanx|imagen)/i → 自动标 true
│  设置页：新增复选框「文生图」与「工具/视觉/思考」并列
│
├─ L5 DB 持久化层（SCHEMA 39，见下一章完整 DDL）
│  3 张新表 + 0 张现有表列改动（attachmentsJson 已存在，复用双写）
│  ├─ t2i_providers                — 独立供应商配置（baseUrl/encryptedKey/endpointMode/priority/active）
│  ├─ t2i_provider_models          — 供应商已添加模型连接表（isDefault/priority/supportsSize JSON）
│  └─ t2i_tasks                    — 核心生命周期：1 图 = 1 行，parentTaskId 链式审计
│  ⚡ 冗余双写：SUCCESS 时同步写 agent_messages.attachmentsJson → BackupManager 自动备份。
│
├─ L4 Agent 调度 / 事件 / Tool 层
│  位置：feature/agent/domain/ （✅ 0 接口签名改动，0 Workflow 代码重写；纯新增 Tool）
│  ├─ tool/image/GenerateImageTool.kt    — StreamingAgentTool 子类
│  │   ├─ 参数：prompt/size/n/model/seed/negative_prompt/style_preset
│  │   ├─ capabilities: ToolCapability.GENERATE_MEDIA （新增枚举值）
│  │   ├─ 权限策略：由 T2IPermissionPolicyEngine 动态评估返回 ASK/AUTO/REJECT
│  │   └─ 事件：复用 ToolCallStarted → Progress → Finished（attachments 字段）
│  ├─ permission/T2IPermissionPolicyEngine.kt   — 组合规则 P1~P6 优先级判定
│  └─ failover/T2IProviderRouter.kt             — 精确模型匹配 + 多激活 priority 遍历 + failover
│  🌿 零侵入：AgentWorkflow / AgentEvent / StatefulAgentWorkflow **完全不改代码**，现有 Tool 事件链兜住全部流程。
│
├─ L3 Repository / 业务流程编排层
│  位置：feature/t2i/data/repository/
│  ├─ T2IProviderRepository  — provider CRUD / active 切换 / priority 更新 / 探测形态持久化
│  └─ T2ITaskRepository
│      ├─ 新建 PENDING 行 → 启动 → UPDATE GENERATING + percent → SUCCESS/FAILED
│      ├─ 悬垂任务扫描（冷启动 Application.onCreate 时跑一次协程，100ms 内完成）
│      ├─ 冗余写 attachmentsJson（双写 invariance）
│      ├─ 级联：删会话 → 删行 + `filesDir/generated_images/{sid}/` 目录
│      └─ 清理：扫未收藏且超阈值天数的 task 行 → deleteFile
│
├─ L2 存储 / 备份 / 清理层
│  路径规范：
│    filesDir/generated_images/{sessionId}/{taskId}_{idx}.png   ← 原图（PNG 无损）
│    filesDir/generated_images/{sessionId}/{taskId}_{idx}.thumb.png  ← 256px 缩略图
│    （用户点💾时再写一份到 MediaStore → DCIM/RDeepCode/IMG_yyyyMMdd_HHmmss_{taskId前8}.png）
│  BackupManagerImpl 增量：
│    exportZip 新增段落 "t2i_tasks.jsonl" + "generated_images/..." 子目录一起打包 zip
│    importZip 时反向恢复 3 张 Room 表 + 解图到 filesDir
│  清理 WorkManager（PeriodicWorkRequest，每周跑一次，设备 idle + 充电才执行）：
│    触发条件：DataStore cleanupUnfavoredAfterDays / cleanupTotalSizeCapMB
│    清理动作：删符合条件的 task 行 + 对应原图 + 缩略图
│
└─ L1 UI 渲染层（Compose）
   位置：feature/t2i/presentation/ + feature/agent/presentation/ 气泡卡片注入
   ├─ T2IGenerationCard.kt  — 状态机渲染：
   │   GENERATING: 骨架屏 + 进度条 + 阶段文案（排队中→编码→扩散→解码→保存文件）+ 取消按钮
   │   FAILED: 红色边框 + 具体错误原因 + [重试] [改 prompt 再试]
   │   SUCCESS: 图 (256 缩略图 Lazy) + 尺寸/模型/seed/耗时/积分 + 操作条：
   │      🔍放大(点击全屏 PhotoView) / 💾保存到相册 / 📋复制参数
   │      🔄再来一张(同 seed) / 🎲变体（新 seed，parentTaskId=本行）/ ⭐️收藏
   ├─ T2ISettingsScreen.kt   — 设置页（Tab 或 Settings 列表项）：
   │    (1) Provider 配置（加 T2I Provider 列表，和 ai_providers 列表 UI 完全同形态）
   │    (2) 权限策略（三复选框组合：强制每次确认 / 日额度 / 会话额度 / 渐进阈值 / 耗尽行为）
   │    (3) 清理策略（cleanupDays: 0=永不 / cleanupSizeCapMB: 输入框 / 『一键清理未收藏』按钮）
   │    (4) 引导文案（每块上方 2~3 行小白解释 + 安全提示）
   └─ AgentUIMessage 注入：messageId LEFT JOIN t2i_tasks 得到卡片列表，在 Tool 气泡 + 附件之后追加渲染。
```

---

## 二、路线 A/B 深度对比结论（为什么最终选型为路线 B）

### 2.1 对比矩阵

| 维度 | 路线 A：AIProvider 原生扩展 generateImage() | 路线 B：GenerateImageTool + 独立 ImageGenerator（✅ 选型） |
|------|---------------------------------------------|----------------------------------------------------------|
| **存量代码侵入** | 🔴🔴🔴🔴🔴 15+ 文件：AIProvider 接口签名、3 个 Adapter、2 个 Fake、Workflow 意图识别、AgentMessage Entity+DAO、SCHEMA 39 加列、BackupManager/ContextCompactor 等全部改 | 🟢🟢🟢🟢🟢 1~3 处小改动（AgentModule 3 个 @Provides；AgentDatabase entities+Dao；ToolRegistry.register）。所有功能代码是新增独立包。 |
| **SRP 单一职责** | ❌ AIProvider 同时负责"聊天补全"和"文生图"，是两种完全不同的 API 契约（messages→text vs prompt→image） | ✅ ImageGenerator 独立 interface，AIProvider 0 行改动。Provider 层各司其职。 |
| **多轮上下文感知（体验核心）** | ❌ generateImage() 只接收本次 prompt，不回见历史对话。需要在 Workflow 层加 NLU：检测"画图意图→调用生成→写回消息→续写描述"，等于手写一个意图分类器 + 指代消解器。 | ✅ **天然具备**：LLM 标准 function calling 工具循环。例子：用户说"把刚才那张夜景改成赛博朋克风"→ LLM 自动在上下文里读到刚才 ToolResult 里描述的"夜景图"→ 构造更准确的 prompt → 调 generateImage → 再写文字解释。整个链路 0 特殊处理。 |
| **流式/进度/取消** | ❌ generateImage 需要 2 个新接口（同步 + streaming），并且要扩展 AIStreamChunk 新子类，Workflow 层要分支处理 | ✅ 直接继承 StreamingAgentTool，`Progress`/`Completed` 事件已存在，Workflow 自动转 `AgentEvent.ToolCallProgress` / `ToolCallFinished`，**0 代码改动** |
| **权限 / 成本审批** | ❌ 要在 Provider 层加独立拦截 hook（AIProvider 里从没做过权限审批），额度计数要另外建一个 T2ICostKeeper，和现有 ToolPermissionPolicyEngine 不互通，审计日志不统一。 | ✅ Tool 体系已有的 [ToolPermissionManager](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/tool/ToolPermissionManager.kt) + `PendingToolPermission` 弹窗 + `rememberablePatterns` 自动复用。额度引擎作为 Pre-Execute Hook 放进 `GenerateImageTool.executeStream()` 开头。 |
| **单测/集成测试难度** | 🔴🔴🔴🔴 需要双轨 Workflow 测试：`should_detect_draw_intent` / `should_generate` / `should_continue_normal_chat` + 歧义句 20 条。 | 🟢🟢 0 新 Workflow 测试；所有测试集中在：GenerateImageTool.execute() 单测 + ImageGenerator Adapter 单测 + 权限引擎真值表。 |
| **SCHEMA / 迁移风险** | ❌ 必须给 AgentMessageEntity 加 generatedImagesJson 列（SCHEMA 39 再加一列），需要同步改 Domain Model AgentMessage + Entity↔Domain 转换 + Backup JSONL 导出。 | ✅ 双写复用 `AgentMessageEntity.attachmentsJson`（L36 已有，SCHEMA 19 加入），**0 列改动**。只加 3 张新表（增量追加式，不碰存量表）。 |
| **BackupManager 兼容** | ❌ BackupManagerImpl export 要新增段落 decode generatedImagesJson，漏写一处就备份看不到图。 | ✅ attachmentsJson 已自动被 JSON 化+备份。**0 代码改动**。 |
| **CrashRecovery / DB-SHIELD** | ❌ generateImage 悬垂请求没状态可扫，重启就丢了。 | ✅ `t2i_tasks` 状态机（PENDING/GENERATING/SUCCESS/FAILED/CANCELLED）配合 PARTIAL INDEX，应用冷启动 100ms 内扫完所有悬垂行并做恢复。 |

### 2.2 数据流时序图：路线 B 处理"用户画一张夜景并描述配色"

```
用户输入:  "帮我画一张2050年的上海夜景，并告诉我你的配色思路。"
 │
 ▼
ViewModel.sendRequest() → AgentContext
 │
 ▼
StatefulAgentWorkflow.executeEvents() 启动（✅ 0 代码改动，标准工具循环）
 │
 ▼ ① AIProvider.completeStream() + tools=[generateImage, viewImage, ...]
 │    LLM 返回 function calling:
 │      reasoning = "用户要一张2050上海夜景，我先用 generateImage 画，然后解释配色。"
 │      tool_calls = [{ id: call_abc, name: generateImage,
 │                     args: {prompt:"Cyberpunk Shanghai skyline 2050, neon lights, rain...", size:"1792x1024", n:1, model:"dall-e-3"} }]
 │
 ▼ ② Workflow 解析 toolCalls → AgentEvent.ToolCallStarted(call_abc, "generateImage")
 │    → ViewModel 渲染工具调用气泡骨架
 │
 ▼ ③ ToolRegistry.get("generateImage").executeStream() = GenerateImageTool
 │     ├─ 3.1 T2IPermissionPolicyEngine.evaluate() → ASK（额度/渐进策略命中）
 │     │       → 弹 PendingToolPermission 给用户：『确认花 1.2 积分生成 1 张 1792x1024？』
 │     ├─ 3.2 用户点确认 → T2IProviderRouter.route("dall-e-3")
 │     │       → 匹配到 active provider "MyOpenAICompatT2I" → OpenAICompatImageGenerator
 │     ├─ 3.3 形态探测 endpointMode = AUTO → 探测一次 → 发现 SYNC → 写回 t2i_providers.endpointMode
 │     ├─ 3.4 generate() → Flow<T2IProgressEvent>:
 │             emit Percent("排队", 10) → Percent("扩散", 45) → Percent("解码", 82) → Result(bytes, seed=42, 12300ms)
 │     ├─ 3.5 T2ITaskRepository 状态流转:
 │             INSERT PENDING → UPDATE GENERATING(progress/stage) → UPDATE SUCCESS(imagePath+thumbnail+seed+latency+costCredits)
 │     ├─ 3.6 T2IImageStorage.save(taskId, bytes):
 │             写 filesDir/generated_images/{sid}/{tid}_0.png + .thumb.png
 │     └─ 3.7 双写: UPDATE agent_messages SET attachmentsJson = [{AgentAttachment image card}]
 │
 ▼ ④ AgentEvent.ToolCallProgress(call_abc, percent 实时) → ViewModel 渲染进度条
 ▼ ⑤ AgentEvent.ToolCallFinished(call_abc, attachments=[AgentAttachment(path, isImage=true)])
 │
 ▼ ⑥ ToolResult 被塞回 messages 列表回放
 │    AIProvider.completeStream() 第二轮：LLM 看到上一轮的图片附件路径和 ToolResult 成功说明
 │    LLM 回复文本: "这张2050上海夜景我选了品红#FF00FF × 青蓝#00FFFF 的霓虹互补色对比……"  🟢 上下文天然连贯
 │
 ▼ ⑦ ViewModel.persist() 落所有事件行 + attachments；BackupManager 下次导出自动带上 attachmentsJson
 ▼ ⑧ UI：工具调用气泡 → 缩略图卡片 → ⭐️💾🔍🔄🎲 操作条 + 助手文本解释气泡
```

---

## 三、SCHEMA 39 数据库结构设计（3 张新表，完整 DDL）

> 迁移脚本对应文件：`app/src/main/assets/migrations/39_rc69_add_t2i.sql`
> 不变性：**0 张存量表列改动**；只做 CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS（追加式幂等，可重复执行）。
> 与 DB-SHIELD 的关系：SCHEMA-GAP 闸门会校验 `39_rc69_add_t2i.sql` 在 assets/migrations 目录中存在且文件名编号连续；ENTITY-COUNT 闸门会校验 `AgentDatabase.entities` 列表中 T2I*Entity 数量为 3。

### 3.1 完整 DDL（SQLite 方言 + 外键 + 部分索引）

```sql
-- ====================================================================
--  RC69 SCHEMA 39: 文生图（T2I）三张新表
--  发布版本：v0.1.0-rc69
--  遵循规则：幂等包裹 / 复合主键 / FK 级联 / PARTIAL INDEX / 不删现有列
-- ====================================================================

PRAGMA foreign_keys = ON;

-- =====================================================
-- 表 1: t2i_providers — 文生图供应商配置
--   （问题 3.1：完全独立于 ai_providers，允许聊天/画图走不同供应商）
-- =====================================================
CREATE TABLE IF NOT EXISTS t2i_providers (
    -- 主键与标识
    id                  TEXT PRIMARY KEY NOT NULL,       -- UUID，代码层生成
    name                TEXT NOT NULL,                   -- 显示名：如 "SiliconFlow Flux Pro"

    -- API 配置（与现有 ai_providers.encryptedApiKey 完全同形态，Keystore AES-256-GCM 加密）
    type                TEXT NOT NULL,                   -- T2IProviderType: OPENAI_COMPAT | REPLICATE | OLLAMA_SDXL | CUSTOM
    baseUrl             TEXT NOT NULL DEFAULT '',        -- 如 https://api.openai.com/v1
    encryptedApiKey     TEXT NOT NULL DEFAULT '',        -- ⚠️ 禁止明文存 key。加密方式与 ai_providers 完全一致
    endpointMode        TEXT NOT NULL DEFAULT 'AUTO',    -- SYNC（直接返回图）| ASYNC（返回 taskId → polling）| AUTO（先探测一次缓存）

    -- 路由 & 限流 & 熔断（问题 3.2 多激活 + 优先级 + failover）
    isActive            INTEGER NOT NULL DEFAULT 0,      -- 1=启用；0=停用
    priority            INTEGER NOT NULL DEFAULT 0,      -- 同 isActive=1 的多个 provider，按 priority DESC 遍历
    rateLimitPerMinute  INTEGER NOT NULL DEFAULT 60,     -- 每分钟请求数上限；0=不限
    failoverEnabled     INTEGER NOT NULL DEFAULT 1,      -- 1=允许被 failover 链使用（配额耗尽/429 时下一个）
    monthlyCreditLimit  REAL NOT NULL DEFAULT -1.0,      -- 月度额度上限积分；-1=不限制
    monthlyCreditsUsed  REAL NOT NULL DEFAULT 0.0,       -- 当月已用积分（每月 1 号 00:00 UTC 重置）
    lastMonthResetEpoch INTEGER NOT NULL DEFAULT 0,      -- 上次月度清零的 epoch day（UTC 第几天）

    -- 审计时间
    createdAtMs         INTEGER NOT NULL,
    updatedAtMs         INTEGER NOT NULL
);
-- 常用查询索引：按 isActive+priority 排序拿 active provider 列表
CREATE INDEX IF NOT EXISTS idx_t2i_providers_active_priority
    ON t2i_providers(isActive, priority DESC);

-- =====================================================
-- 表 2: t2i_provider_models — 供应商已添加模型（连接表）
--   （规格细化 DDL-2.2：替代单列 modelsJson；DDL-2.6 加 isDefault/priority/supportsSize 扩展列）
-- =====================================================
CREATE TABLE IF NOT EXISTS t2i_provider_models (
    id                  TEXT PRIMARY KEY NOT NULL,       -- UUID
    providerId          TEXT NOT NULL,                   -- FK → t2i_providers.id
    modelName           TEXT NOT NULL,                   -- 如 "dall-e-3"、"flux-schnell"
    displayName         TEXT,                            -- 显示名："DALL-E 3 超高清"（设置页展示）
    isDefault           INTEGER NOT NULL DEFAULT 0,      -- 1 = 该 provider 的默认图模型（一个 provider 只允许 1 行=1，Repo 层写入时互斥校验）
    priority            INTEGER NOT NULL DEFAULT 0,      -- 同 provider 内模型列表顺序（UI 下拉展示顺序）
    supportsSizeJson    TEXT NOT NULL DEFAULT '[]',      -- JSON Array<String>：支持的尺寸 ["1024x1024","1792x1024",...]；空=不限制
    supportsSteps       INTEGER NOT NULL DEFAULT 0,      -- 1=支持 steps 参数调步数；0=不支持
    supportsSeed        INTEGER NOT NULL DEFAULT 1,      -- 1=支持 seed 复现；0=不支持（Flux Schnell 通常不支持）
    supportsNegative    INTEGER NOT NULL DEFAULT 0,      -- 1=支持负面提示词；0=只有 prompt
    costPerImageCredits REAL NOT NULL DEFAULT 1.0,       -- 每生成 1 张 1024x1024 消耗多少积分（用于 UI 预估 & 额度引擎）
    createdAtMs         INTEGER NOT NULL,
    updatedAtMs         INTEGER NOT NULL,
    FOREIGN KEY (providerId) REFERENCES t2i_providers(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_t2i_provider_models_provider_priority
    ON t2i_provider_models(providerId, priority DESC, isDefault DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uniq_t2i_provider_models_provider_name
    ON t2i_provider_models(providerId, modelName);       -- 同一 provider 下模型名不重复

-- =====================================================
-- 表 3: t2i_tasks — 生成任务生命周期核心表（1 图 = 1 行）
--   （问题 2.x 所有不变性：1:N、追加式 parentTaskId、isCompacted、硬级联删会话）
--   key rules:
--     ① messageId 不加 FK（Repository 手动解绑）→ 防止删消息气泡时误删用户收藏图
--     ② 只有 sessionId FK → chat_sessions.id ON DELETE CASCADE；删会话同步删文件目录
--     ③ parentTaskId FK → 自身 ON DELETE SET NULL（祖宗行被删，子孙行的祖宗指针置空保留）
-- =====================================================
CREATE TABLE IF NOT EXISTS t2i_tasks (
    -- 主键 & 关联
    id                  TEXT PRIMARY KEY NOT NULL,       -- UUID（本地生成；远端异步另存 remoteTaskId）
    sessionId           TEXT NOT NULL,                   -- FK → chat_sessions.id，ON DELETE CASCADE（问题 2.5 硬级联）
    toolCallId          TEXT,                            -- 对应 AgentMessageEntity.toolCallId（关联工具调用气泡骨架）
    messageId           TEXT,                            -- 对应 agent_messages.id（⚠️ 不加 FK，Repository 手动解绑，DDL 2.4 决策）
    parentTaskId        TEXT,                            -- 追加式 NEW 行祖宗指针（问题 2.3）；NULL = 根任务
    generation          INTEGER NOT NULL DEFAULT 0,      -- 代数：根=0，第一次重试/变体=1，再一次=2……（UI 展示历史 N 张用）

    -- 状态机
    status              TEXT NOT NULL,                   -- PENDING | GENERATING | SUCCESS | FAILED | CANCELLED
    errorCode           TEXT,                            -- QUOTA_DAILY | QUOTA_SESSION | NSFW | UNSUPPORTED_SIZE | NETWORK_TIMEOUT |
                                                        --   PROVIDER_4XX | PROVIDER_5XX | CRASH_RECOVERY | USER_CANCEL | UNKNOWN
    errorMessage        TEXT,                            -- 人类可读具体错误（附带议题 2 透明展示；Provider 返回原文）
    userVisibleHint     TEXT,                            -- UI 额外展示引导："尝试把人物描述换成风景" / "请前往 T2I 设置页提升额度"

    -- 用户/工具输入参数（完整快照，供"再来一张"或"参数复制"，或审计追溯）
    prompt              TEXT NOT NULL,
    negativePrompt      TEXT,
    model               TEXT NOT NULL,                   -- 解析 & failover 后实际使用的模型名
    providerId          TEXT NOT NULL,                   -- 实际使用 provider id（崩溃恢复找远端结果用）
    sizeWidth           INTEGER NOT NULL,
    sizeHeight          INTEGER NOT NULL,
    steps               INTEGER,                         -- NULL = 用 Provider 默认
    seed                TEXT,                            -- TEXT（DDL 2.3 决策，兼容大数/字母）；NULL = 纯随机无固定 seed
    stylePreset         TEXT,                            -- 如 "anime" / "photographic" / "pixel-art"
    batchIndex          INTEGER NOT NULL DEFAULT 0,      -- n=4 时的 0..3 索引
    totalInBatch        INTEGER NOT NULL DEFAULT 1,      -- 本次 Tool 调用一共几张
    argsSnapshotJson    TEXT NOT NULL DEFAULT '{}',      -- LLM 原始 function calling args JSON 完整快照（审计用，不可变）

    -- Provider 同步/异步执行（问题 4.x）
    endpointModeUsed    TEXT NOT NULL,                   -- 本次实际用的 SYNC / ASYNC（AUTO 探测后写进来）
    remoteTaskId        TEXT,                            -- ASYNC 远端 task id；崩溃恢复时用（PENDING/GENERATING 扫 PARTIAL INDEX 按此回查）
    progressPercent     INTEGER NOT NULL DEFAULT 0,      -- 0..100，用于 UI 卡片渲染
    progressStage       TEXT,                            -- "排队中" / "编码提示词" / "扩散中" / "解码图像" / "保存文件"
    startedAtMs         INTEGER,                         -- GENERATING 开始的 epoch ms（latency 计算）
    cancelledAtMs       INTEGER,                         -- 用户点取消的时间

    -- 产物落库（问题 5.x）
    imageRelativePath   TEXT,                            -- SUCCESS：相对 {filesDir}/generated_images/ 的路径
    thumbnailRelativePath TEXT,                          -- 同目录下 .thumb.png（256px 占位/列表快速渲染）
    fileSizeBytes       INTEGER,                         -- 原图 PNG 字节数（清理 WorkManager 汇总容量用）
    mimeType            TEXT DEFAULT 'image/png',        -- 全 PNG（问题 5.2 决策）
    imageSha256         TEXT,                            -- SHA-256 校验（可选，防止重名文件覆盖；未算则 NULL）

    -- 用户标记（问题 5.3 清理策略 & 收藏 ⭐️）
    isFavorited         INTEGER NOT NULL DEFAULT 0,
    favoritedAtMs       INTEGER,
    lastViewedAtMs      INTEGER,                         -- 最近一次查看（UI 可做"最近生成"排序）

    -- 成本/性能审计（UI 卡片左下角展示："12.3s · 1.2 积分"）
    latencyMs           INTEGER,                         -- PENDING → SUCCESS/FAILED 的总耗时
    costCredits         REAL NOT NULL DEFAULT 0.0,       -- 实际扣减积分
    usageTokens         INTEGER,                         -- Provider 额外返回的 token usage（如果有）
    rateLimitHitCount   INTEGER NOT NULL DEFAULT 0,      -- 本次遇到 429 重试次数（路由引擎统计）

    -- 上下文压缩（问题 2.4：只标记不删文件）
    isCompacted         INTEGER NOT NULL DEFAULT 0,      -- 1 = 该消息被 ContextCompactor 压缩，UI 折叠隐藏

    -- 级联 / 时间列
    createdAtMs         INTEGER NOT NULL,
    updatedAtMs         INTEGER NOT NULL,

    -- FK 约束（只有 sessionId + parentTaskId；注意 messageId 不加 FK）
    FOREIGN KEY (sessionId)    REFERENCES chat_sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (parentTaskId) REFERENCES t2i_tasks(id)   ON DELETE SET NULL,
    FOREIGN KEY (providerId)   REFERENCES t2i_providers(id) ON DELETE RESTRICT   -- provider 有任务不能删，需先转移/清空
);

-- 索引策略（性能优先 + size 控制；悬垂行用 PARTIAL INDEX 只索引需要的）
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_session_status
    ON t2i_tasks(sessionId, status, createdAtMs DESC);   -- 进入会话加载 T2IGenerationCard 列表
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_message
    ON t2i_tasks(messageId);                             -- 消息 LEFT JOIN 找对应卡片
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_parent
    ON t2i_tasks(parentTaskId);                          -- 展开"生成历史 N 张"
-- DDL 2.5 决策：悬垂恢复 PARTIAL INDEX（只占 PENDING/GENERATING 几百行，size 最小）
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_pending_gen
    ON t2i_tasks(status)
    WHERE status IN ('PENDING','GENERATING');
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_provider_remote
    ON t2i_tasks(providerId, remoteTaskId)
    WHERE remoteTaskId IS NOT NULL;                      -- 按 provider+remoteTaskId 精准回查远端
-- 清理 WorkManager：快速扫"未收藏且超期"（PARTIAL INDEX 更小）
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_cleanup
    ON t2i_tasks(isFavorited, createdAtMs)
    WHERE isFavorited = 0;
CREATE INDEX IF NOT EXISTS idx_t2i_tasks_favorites
    ON t2i_tasks(sessionId, isFavorited DESC, createdAtMs DESC);
```

### 3.2 不变性 & 代码侧校验清单（写入前强制过）

```kotlin
// Repository 写入前强制校验（invariants，违反就抛 IllegalArgumentException，不进 DB）
object T2IInvariants {

    // ============ Provider 级别 ============
    // isDefault 互斥：同一 providerId 下只允许 1 行 t2i_provider_models.isDefault = 1
    fun assertSingleDefaultPerProvider(models: List<T2IProviderModelEntity>) {
        val defaults = models.count { it.isDefault }
        require(defaults <= 1) {
            "T2IProviderModels[$providerId] 出现 $defaults 个 isDefault=1，最多只能 1 个"
        }
    }

    // ============ Task 级别 ============
    // SUCCESS 必须提供 imageRelativePath + fileSizeBytes > 0
    fun assertSuccessHasArtifact(task: T2ITaskEntity) {
        if (task.status == "SUCCESS") {
            require(task.imageRelativePath != null) { "T2ITask SUCCESS 必须有 imageRelativePath" }
            require((task.fileSizeBytes ?: 0) > 0) { "T2ITask SUCCESS 必须 fileSizeBytes > 0" }
        }
    }

    // FAILED/CANCELLED 必须有 errorCode 或 CANCELLED 标志
    fun assertTerminalStatusHasReason(task: T2ITaskEntity) {
        if (task.status == "FAILED") {
            require(task.errorCode != null) { "T2ITask FAILED 必须有 errorCode" }
        }
    }

    // parentTaskId 指向的祖宗行必须同 sessionId（不允许跨会话挂亲属关系）
    fun assertParentInSameSession(child: T2ITaskEntity, parent: T2ITaskEntity?) {
        if (child.parentTaskId != null && parent != null) {
            require(parent.sessionId == child.sessionId) {
                "T2ITask parent 跨会话引用：parent sessionId=${parent.sessionId} child=${child.sessionId}"
            }
        }
    }

    // 进度 percent 只能在 0..100，且 GENERATING 才允许 <100，SUCCESS/FAILED/CANCELLED 才允许 100（或 0）
    fun assertProgressStageConsistent(task: T2ITaskEntity) {
        require(task.progressPercent in 0..100) { "progressPercent must ∈ [0,100], actual=${task.progressPercent}" }
    }

    // 尺寸必须是 Provider Model 支持的（当 supportsSizeJson 非空时）
    fun assertSizeSupported(model: T2IProviderModelEntity?, w: Int, h: Int) {
        if (model == null) return
        val supported = parseSizeList(model.supportsSizeJson)
        if (supported.isEmpty()) return   // 空列表 = 不限制
        require("${w}x${h}" in supported) {
            "模型 ${model.modelName} 不支持尺寸 ${w}x${h}，支持：$supported"
        }
    }
}
```

---

## 四、权限策略组合引擎 P1~P6（规格细化 1）

> 落地位置：`feature/t2i/domain/permission/T2IPermissionPolicyEngine.kt`
> 存储位置：`T2IPermissionSettings`（Proto DataStore，全进单文件，PERM-1.3 决策）
> 调用时机：`GenerateImageTool.executeStream()` 开头**先调** `evaluate()`，拿到决策再走后续流程。

### 4.1 决策优先级表（从上到下检查，命中即停；AND/OR 全部自由组合生效）

```
T2IPermissionPolicyEngine.evaluate(sessionId, providerId, model, n, estimatedCredits)
    → Decision { ASK | AUTO_APPROVE | REJECT(code,msg,actionButton) }

命中顺序（从上到下；越上越严格）
├─ P1. 强制确认开关【最高优先级】
│    IF settings.forceAskEveryTime == true
│        → DECISION = ASK（显示："画图确认，预计消耗 {n} × {credits} 积分"）
│        → （忽略渐进/额度/AUTO 等所有其他设置）
│
├─ P2. 日额度耗尽
│    IF settings.enableDailyQuota && dailyConsumedCount + n > settings.dailyQuotaLimit
│        ├─ P2a. IF settings.quotaExhaustionBehavior == REJECT
│        │        → DECISION = REJECT("今日额度 {used}/{limit} 已用完", code=QUOTA_DAILY)
│        │              actionButton = 跳 T2I 设置页
│        └─ P2b. IF settings.quotaExhaustionBehavior == ASK_TEMP_BOOST（默认）
│                 → DECISION = ASK（附加："今日额度已用完，是否临时追加 {defaultTempBoost=5} 张？"）
│                       用户点确认时：本次临时额度 += 5，后续正常累计进 dailyConsumedCount
│
├─ P3. 会话额度耗尽
│    IF settings.enableSessionQuota && sessionQuota(sessionId).count + n > settings.sessionQuotaLimit
│        → 同 P2 的 2a / 2b 分支，code=QUOTA_SESSION，文案改"会话额度"
│
├─ P4. 月度供应商额度（t2i_providers.monthlyCreditsUsed）
│    IF provider.monthlyCreditLimit > 0 AND
│       provider.monthlyCreditsUsed + estimatedCredits > provider.monthlyCreditLimit
│        → DECISION = REJECT("本供应商本月额度已用完（{used}/{limit}）", code=QUOTA_MONTHLY)
│              actionButton = 跳 T2I Provider 详情页
│
├─ P5. 渐进保护阈值
│    IF settings.enableProgressive
│        统计本会话已生成 + 本次 N 之后的 total = sessionQuota(sessionId).count + n
│        ├─ P5a. IF total > progressiveThreshold
│        │        → DECISION = ASK（显示："本会话第 {total} 张图，建议确认后继续"）
│        └─ P5b. ELSE（在前 progressiveThreshold 张内）
│                 → DECISION = AUTO_APPROVE（不弹窗，直接生成）
│
├─ P6. 兜底【三个开关全关的安全默认】
│    IF settings.enableAutoApproval == true  ← 设置页新增"允许自动审批（无额度限制，不推荐）"
│        → DECISION = AUTO_APPROVE
│    ELSE
│        → DECISION = ASK（最安全兜底：每次确认）
```

### 4.2 T2IPermissionSettings（Proto DataStore 结构）

```kotlin
// 位置：feature/t2i/data/datastore/T2IPermissionSettingsSerializer.kt
// 文件路径：{context.dataDir}/datastore/t2i_permission_settings.pb
// （所有开关 + 引导文案 + 额度计数）

data class T2IPermissionSettings(
    // ========== 策略开关（三个复选框 + 渐进阈值 + 耗尽行为 + 兜底 AUTO）==========
    val forceAskEveryTime: Boolean = false,                  // P1 强制每次确认（最严格）
    val enableDailyQuota: Boolean = true,                    // P2 启用日额度
    val dailyQuotaLimit: Int = 20,                           //   日额度默认 20 张
    val enableSessionQuota: Boolean = true,                  // P3 启用单会话额度
    val sessionQuotaLimit: Int = 10,                         //   单会话默认 10 张
    val enableProgressive: Boolean = false,                  // P5 启用渐进保护
    val progressiveThreshold: Int = 3,                       //   渐进阈值：前 3 张 AUTO
    val quotaExhaustionBehavior: String = "ASK_TEMP_BOOST",  // P2/P3：ASK_TEMP_BOOST(默认) | REJECT
    val tempBoostCountIfAsk: Int = 5,                        //   临时追加多少张（默认 5）
    val enableAutoApproval: Boolean = false,                 // P6：三个全关 + 这个 OFF → 兜底 ASK

    // ========== 审计计数（运行时累加；跨日/跨月/跨会话自动持久）==========
    val dailyConsumedEpochDay: Int = 0,                      // 日额度所属日期（UTC 第几天，跨日自动清零 count）
    val dailyConsumedCount: Int = 0,                         //   当日已用张数（仅图片 SUCCESS 时累加）
    val dailyTempBoostUsedCount: Int = 0,                    //   当日已用"临时追加"张（ASK_TEMP_BOOST 时累加）
    val sessionQuotaConsumedMapStr: String = "{}",           //   Map<sessionId, Int> JSON：各会话已用张（DataStore 内嵌 Map）
    val sessionTotalGenerationMapStr: String = "{}",         //   渐进 P5 使用：Map<sessionId, totalInt>（哪怕被临时 boost 也要算渐进次数）

    // ========== 清理策略（问题 5.3：用户完全自定义）==========
    val cleanupUnfavoredAfterDays: Int = 14,                 //   未收藏 N 天后删；0=永不
    val cleanupTotalSizeCapMB: Int = 2000,                   //   目录总容量阈值 MB；0=不按容量
    val cleanupFavoredAlwaysKeep: Boolean = true,            //   ⭐️收藏永留（默认 true；如果用户关闭就收藏也按天数）

    // ========== 成本防护 NSFW 透明展示 + 默认行为（附带议题 2/1）==========
    val showDetailedErrorCode: Boolean = true,               //   NSFW/余额不足…具体原因透明展示
    val contextReplayInjectBase64ByDefault: Boolean = false, //   默认只回放 path；如果用户关闭防爆默认，就每轮塞 base64

    // ========== 提示引导（进入设置页时的横幅）==========
    val hasSeenIntroBanner: Boolean = false,                 //   已看过"策略组合引导"横幅
    val hasSeenCleanupBanner: Boolean = false,               //   已看过"自动清理策略"横幅
)
```

### 4.3 额度引擎的写入时机（DataStore 只在 SUCCESS 时累加；避免 FAILED 刷额度）

```
T2ITaskRepository.onTaskTerminal(task: T2ITaskEntity) {
    when (task.status) {
        SUCCESS -> t2iPermissionSettingsDataStore.updateData {
            it.incrementDailyAndSession(task.sessionId, 1)
              .incrementProviderMonthlyCredits(task.providerId, task.costCredits)
        }
        FAILED / CANCELLED / CRASH_RECOVERY -> 不累加任何额度
        // ⚠️ PENDING/GENERATING 先"预占"（estimatedCredits 临时锁，防止并发超），
        //    到达 SUCCESS 时把预占换成实际扣减；到达非 SUCCESS 时释放预占。
        //    防止"用户瞬间点 10 张 generateImage → 10 张并发 P2 日额度 20→18→16"但 PENDING 阶段
        //    其实没扣到 → 10 张全生成完 → 日额度变成 30，超了。
    }
}
```

---

## 五、模块包树 & 8 步实现排程（规格细化 3）

### 5.1 完整包树（PKG-3.1 / 3.2 / 3.3 / 3.4 决策）

```
app/src/main/java/com/deep/rcode/
│
├── di/
│   └── AgentModule.kt                              🟠 改动：3 个 DAO @Provides 新增；ImageGenerator 多 binding；注册 GenerateImageTool
│
├── core/
│   └── db/
│       └── AgentDatabase.kt                        🟠 改动：entities += [T2IProviderEntity, T2IProviderModelEntity, T2ITaskEntity]
│                                                     abstract fun t2iProviderDao(): T2IProviderDao
│                                                     abstract fun t2iProviderModelDao(): T2IProviderModelDao
│                                                     abstract fun t2iTaskDao(): T2ITaskDao
│
├── feature/
│   ├── t2i/                                           🆕 独立 feature 包（数据 + 业务 + UI）
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── entity/
│   │   │   │   │   ├── T2IProviderEntity.kt
│   │   │   │   │   ├── T2IProviderModelEntity.kt
│   │   │   │   │   └── T2ITaskEntity.kt
│   │   │   │   └── dao/
│   │   │   │       ├── T2IProviderDao.kt
│   │   │   │       ├── T2IProviderModelDao.kt
│   │   │   │       └── T2ITaskDao.kt
│   │   │   ├── remote/
│   │   │   │   ├── ImageGenerator.kt                 🔴 interface：fun generate(request): Flow<T2IProgressEvent>
│   │   │   │   ├── dto/
│   │   │   │   │   ├── T2IRequest.kt
│   │   │   │   │   ├── T2IProgressEvent.kt           🔴 sealed class：Percent / Result / Error
│   │   │   │   │   ├── T2ICapabilityProbeResult.kt
│   │   │   │   │   └── T2IErrorCodes.kt              🔴 enum：所有 errorCode 常量
│   │   │   │   └── impl/
│   │   │   │       ├── OpenAICompatImageGenerator.kt   🔴 通用 /v1/images/generations（DALL-E 3 / 你的 API）
│   │   │   │       └── AsyncPollingSupport.kt          🔴 ASYNC 模式 polling coroutine + DELETE tasks/{id}
│   │   │   ├── datastore/
│   │   │   │   ├── T2IPermissionSettingsSerializer.kt  🔴 Proto DataStore 序列化器（第四章结构）
│   │   │   │   └── T2IPermissionSettingsExt.kt         🔴 incrementDaily/incrementSession 工具
│   │   │   ├── storage/
│   │   │   │   ├── T2IImageStorage.kt                  🔴 interface（save/deleteSession/cleanup/saveToMediaStore）
│   │   │   │   └── LocalFileImageStorage.kt            🔴 filesDir 实现 + MediaStore API 另存为相册
│   │   │   └── repository/
│   │   │       ├── T2IProviderRepository.kt            🔴 CRUD + active 切换 + isDefault 互斥校验 + 月度额度重置
│   │   │       ├── T2IProviderModelRepository.kt       🔴 连接表 CRUD
│   │   │       ├── T2ITaskRepository.kt                🔴 状态流转 UPDATE + 悬垂扫描 + 双写 attachmentsJson + 级联删文件
│   │   │       └── T2IInvariants.kt                    🔴 第三章 3.2 不变性校验
│   │   ├── domain/
│   │   │   ├── permission/
│   │   │   │   ├── T2IPermissionPolicyEngine.kt        🔴 P1~P6 决策真值表
│   │   │   │   └── T2IDecision.kt                      🔴 Decision { ASK | AUTO_APPROVE | REJECT(code,msg,btn) }
│   │   │   ├── failover/
│   │   │   │   ├── T2IProviderRouter.kt                🔴 问题 3.2/3.3：精确模型名匹配 → priority DESC 遍历 → 429 failover
│   │   │   │   └── T2IProviderCircuitBreaker.kt        🔴 每个 provider 的连续失败计数 + 熔断冷却（30s）
│   │   │   ├── cleanup/
│   │   │   │   ├── T2ICleanupManager.kt                🔴 清理策略评估 + 删 task + 删文件
│   │   │   │   └── T2ICleanupWorker.kt                 🔴 WorkManager CoroutineWorker（idle + 充电才执行）
│   │   │   └── model/
│   │   │       ├── T2IProviderType.kt                  🔴 enum（OPENAI_COMPAT/REPLICATE/OLLAMA_SDXL/CUSTOM）
│   │   │       ├── T2IEndpointMode.kt                  🔴 enum（SYNC/ASYNC/AUTO）
│   │   │       └── T2ITaskStatus.kt                    🔴 enum（PENDING/GENERATING/SUCCESS/FAILED/CANCELLED）
│   │   └── presentation/
│   │       ├── T2IGenerationCard.kt                    🔴 Compose 状态机卡片（GENERATING/FAILED/SUCCESS 三个分支）
│   │       ├── T2ISettingsScreen.kt                    🔴 设置页四分区（Provider/权限/清理/引导）
│   │       └── ui/
│   │           └── T2IGenerationDefaults.kt            🔴 默认参数、颜色、尺寸枚举
│   │
│   └── agent/
│       ├── domain/tool/image/                           🆕 新增 image 子目录（Tool 留在 agent 下复用上下文 + 权限弹窗）
│       │   └── GenerateImageTool.kt                    🔴 StreamingAgentTool 子类：权限→路由→生成→落库→双写→事件
│       └── presentation/
│           └── components/
│               └── AgentMessageT2IInject.kt             🟠 小改动：消息 LEFT JOIN t2i_tasks → 追加渲染 T2IGenerationCard
│
└── worker/  （core/worker/ 现有目录）
    └── T2ICleanupWorkerScheduler.kt                     🔴 PeriodicWorkRequest 注册（每周 1 次 + idle + charging）
```

### 5.2 8 步实现排程（串行；每一步本地 `assembleRelease + testReleaseUnitTest` 必须绿灯；避免 RC68 KSP 雪崩重演）

| 步骤 | 核心内容 | 依赖 | 验收标准 |
|------|----------|------|----------|
| **Step 1** | SCHEMA 39 迁移脚本 `39_rc69_add_t2i.sql`（第三章 DDL 全文，幂等包裹）+ T2IProviderEntity/T2IProviderModelEntity/T2ITaskEntity 3 个 Room Entity（列与 DDL 完全一致） | 无 | 1. assets/migrations 里文件名正确 <br> 2. Entity 含 `@Entity(tableName = "t2i_xxx")` <br> 3. SCHEMA-GAP 闸门通过（DbSCHIELDPreflightTest）：38 → 39 连续 |
| **Step 2** | 3 个 DAO 接口（T2IProviderDao / T2IProviderModelDao / T2ITaskDao）→ 全部 `@Dao` 注解；核心 SQL 列全；T2ITaskDao 的悬垂查询用 `@Query` 写 PARTIAL INDEX 友好的 WHERE status IN ('PENDING','GENERATING') | Step 1 | Room KSP 生成 3 个 DAO 实现；无 Unresolved reference |
| **Step 3** | AgentDatabase.kt `entities = [..., 3 个新 Entity]` + 3 个 `abstract fun xDao(): XDao`；AgentModule.kt 新增 3 个 `@Provides fun provideXDao(db: AgentDatabase)` → **所有 3 个 DAO import 短名，不写全限定名（防 KSP ERROR 雪崩）**；ToolRegistry.register("generateImage", GenerateImageTool) 占位（GenerateImageTool 先写 @Inject constructor 空 impl） | Step 2 | assembleRelease 绿灯 ✅ <br> 本地 testReleaseUnitTest 绿灯 ✅（SCHIELD 闸门 3 项全通过） |
| **Step 4** | DbSCHIELDPreflightTest 闸门更新：`ENTITY_COUNT_EXPECTED` 在原有基础上 +3；新增 1 个 SQL-SEMICOLON-CHECK-T2I 用例跑 assets/migrations/39 确保字符串无分号 | Step 3 | 闸门：`entitiesN == expectedN` + `SCHEMA-GAP 39` 连续 + `SQL 无分号` |
| **Step 5** | T2IProviderRepository + T2ITaskRepository（含不变性校验 T2IInvariants）；T2ITaskRepository 双写逻辑：`onTaskSuccess(task) → UPDATE agent_messages SET attachmentsJson = JSON_SERIALIZE([AgentAttachment]) WHERE id = task.messageId`（用现有 Dao 里的 updateAttachments 方法或原 SQL）；DataStore T2IPermissionSettings 结构 + serializer | Step 4 | 单测：Repository `assertSingleDefaultPerProvider` 抛异常 / 双写调用 DAO update ×1 次（Mockito verify） |
| **Step 6** | ImageGenerator interface + T2IRequest + T2IProgressEvent + OpenAICompatImageGenerator（SYNC / ASYNC / AUTO 三形态 + probeOnce）；T2IProviderRouter（精确匹配 + priority 遍历 failover）；T2IProviderCircuitBreaker | Step 5 | 单测：AUTO 模式 probeOnce → 第二次缓存直接走 endpointModeUsed；failover 连 3 次 429 → 切下一 provider |
| **Step 7** | **GenerateImageTool 完整实现**（最大块）：parameters 定义 → T2IPermissionPolicyEngine.evaluate 决策 →（ASK 时挂到 PendingToolPermission）→ Router 拿 provider → TaskRepo INSERT PENDING → ImageGenerator.generate() → Flow 映射 Percent → UPDATE GENERATING percent → Result 落库 + 双写 + attachments → ToolCallFinished；取消 → DELETE remoteTaskId；Error 映射 errorCode + userVisibleHint | Step 6 | 集成测试（FakeImageGenerator）：权限 ASK → AUTO → REJECT 三条分支各跑通；双写 agent_messages.attachmentsJson 成功 |
| **Step 8（UI+清理+备份）** | T2IGenerationCard + T2ISettingsScreen；T2ICleanupManager + T2ICleanupWorker PeriodicWorkRequest；BackupManagerImpl 增量：exportZip 追加 `t2i_tasks.jsonl` + `generated_images/` 整个子目录 + importZip 反向恢复；ModelMetadata.supportsImageGeneration 字段+启发式 | Step 7 | UI 预览 & assembleRelease 绿灯 <br> CI Release 发版（RC69 最终产物） |

---

## 六、核心接口契约 + 形态探测 + 崩溃恢复状态机（规格细化 2/4/5）

### 6.1 领域接口定义（ImageGenerator + DTO）

```kotlin
// ============================================================
// ① ImageGenerator 接口（统一 SYNC / ASYNC / AUTO 三种形态，Flow 输出）
// ============================================================
/**
 * 文生图 Provider 抽象。与 AIProvider 完全独立（SRP）。
 * 所有 Adapter 必须通过它生成 Flow<T2IProgressEvent>，即使是同步 HTTP 也要在
 * 发射 Percent(0) → Percent(100) → Result，UI 层只写一套进度条渲染逻辑。
 */
interface ImageGenerator {
    /**
     * 此实现类对应的 ProviderType（和 t2i_providers.type 列做匹配）
     */
    val providerType: T2IProviderType

    /**
     * 生成图片的统一入口（流式）。
     *
     * 取消语义：
     *   当调用方取消 collect 的协程（Job.cancel）：
     *   SYNC → OkHttp Call.cancel() 断连；
     *   ASYNC → 发 DELETE /tasks/{remoteTaskId} 通知远端取消排队，防止扣额度。
     */
    fun generate(request: T2IRequest): Flow<T2IProgressEvent>

    /**
     * AUTO 模式探测：发一个最小成本请求（通常 prompt="test", size="256x256"，
     * 部分 Provider 有专用 /models/{id} 元数据端点），判定对方是 SYNC 还是 ASYNC。
     * 结果会缓存回 DB t2i_providers.endpointMode（AUTO→SYNC/ASYNC）。
     *
     * @return AUTO 探测到的实际形态（不返回 AUTO）
     */
    suspend fun probeOnce(config: T2IProviderEntity): T2ICapabilityProbeResult

    /**
     * 崩溃恢复时回查远端任务（仅 ASYNC 有实现；SYNC 抛 UnsupportedOperationException）。
     * 冷启动扫到的 PENDING/GENERATING 行通过 (providerId, remoteTaskId) 回查结果。
     */
    suspend fun fetchRemoteTask(config: T2IProviderEntity, remoteTaskId: String): T2IProgressEvent?

    /**
     * 双向取消（问题 4.2 决策）：用户点取消时，如果有 remoteTaskId 就删远端排队。
     * SYNC 模式返回 false；ASYNC 成功删除返回 true。
     */
    suspend fun cancelRemoteTask(config: T2IProviderEntity, remoteTaskId: String): Boolean
}

// ============================================================
// ② T2IRequest：完整参数快照（1:1 对应 t2i_tasks 参数列）
// ============================================================
data class T2IRequest(
    val prompt: String,
    val negativePrompt: String? = null,
    val model: String,                       // 解析+failover 后实际使用的模型名
    val sizeWidth: Int,
    val sizeHeight: Int,
    val steps: Int? = null,                  // null = Provider 默认
    val seed: String? = null,                // TEXT，支持超长 Long/字母
    val stylePreset: String? = null,
    val n: Int = 1,                          // 批量张数（Batch 索引由调用方控制；每个 Result 是单张）
    val timeoutMs: Long = 180_000L,          // HTTP / ASYNC polling 整体超时
    val endpointMode: T2IEndpointMode,       // 来自 DB：SYNC / ASYNC / AUTO
    val extraHeadersJson: String? = null,    // Provider 自定义 header（审计）
)

// ============================================================
// ③ T2IProgressEvent：sealed class（Flow 发射对象）
// ============================================================
sealed class T2IProgressEvent {
    /**
     * 百分比进度：
     *   stage = "排队中 / 编码提示词 / 扩散 / 解码 / 保存到 CDN / 回写远端"
     *   percent = 0..100（整数）
     *   对于同步 HTTP：只发 Percent("连接", 10) → Percent("下载图像", 90) → Percent("保存", 100) 三档。
     */
    data class Percent(val stage: String, val percent: Int) : T2IProgressEvent()

    /**
     * 单张图片成功（n>1 时 emit N 次 Result）
     *   bytes: 原始 PNG 字节（调用方 T2IImageStorage 负责写磁盘）
     *   seed: 实际使用的种子（Provider 可能返回固定 seed）
     *   latencyMs: 单张耗时（SUCCESS - PENDING；用于 UI 展示）
     *   mimeType: 理论始终 image/png（问题 5.2），但 Adapter 仍返回供校验
     */
    data class Result(
        val bytes: ByteArray,
        val seed: String?,
        val latencyMs: Long,
        val mimeType: String = "image/png",
        val costCredits: Double = 0.0,
    ) : T2IProgressEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Result
            return seed == other.seed && latencyMs == other.latencyMs &&
                   mimeType == other.mimeType && costCredits == other.costCredits &&
                   bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int =
            31 * (31 * (31 * bytes.contentHashCode() + (seed?.hashCode() ?: 0)) +
                  latencyMs.hashCode()) + costCredits.hashCode()
    }

    /**
     * 失败：
     *   errorCode = T2IErrorCodes.* 枚举字符串（映射给 errorCode DB 列 + UI）
     *   errorMessage = Provider 返回原文（用户透明展示）
     *   userVisibleHint = 引导："建议换尺寸 1024x1024" / "去设置页提额度"
     */
    data class Error(
        val errorCode: String,
        val errorMessage: String,
        val userVisibleHint: String? = null,
        val retryable: Boolean = true,    // 429/5xx/网络超时 = 可重试；NSFW/UNSUPPORTED_SIZE = 不建议重试
    ) : T2IProgressEvent()

    /**
     * ASYNC 模式第一次返回时 emit RemoteTaskAssigned：把 remoteTaskId 传回 GenerateImageTool，
     * 让它立即 UPDATE 到 t2i_tasks.remoteTaskId，这样后续取消 / 崩溃恢复都能回查。
     * 对于 SYNC 不发射。
     */
    data class RemoteTaskAssigned(val remoteTaskId: String) : T2IProgressEvent()
}

// ============================================================
// ④ T2ICapabilityProbeResult：AUTO → 探测后缓存进 endpointMode
// ============================================================
data class T2ICapabilityProbeResult(
    val actualMode: T2IEndpointMode,       // SYNC 或 ASYNC（绝不再返回 AUTO）
    val supportedSizes: List<String>,      // 探测时顺便拿到的支持尺寸
    val supportsSeed: Boolean,
    val latencyEstimateMs: Long,           // 供 UI 预估（"预计需 13 秒"）
)

// ============================================================
// ⑤ T2IErrorCodes 枚举：所有失败原因统一码（UI + DB 共用）
// ============================================================
object T2IErrorCodes {
    const val QUOTA_DAILY = "QUOTA_DAILY"
    const val QUOTA_SESSION = "QUOTA_SESSION"
    const val QUOTA_MONTHLY = "QUOTA_MONTHLY"
    const val NSFW = "NSFW"
    const val UNSUPPORTED_SIZE = "UNSUPPORTED_SIZE"
    const val UNSUPPORTED_MODEL = "UNSUPPORTED_MODEL"
    const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val NETWORK_IO = "NETWORK_IO"
    const val PROVIDER_4XX = "PROVIDER_4XX"     // 其他 4xx（非 NSFW/尺寸，如 invalid_api_key）
    const val PROVIDER_5XX = "PROVIDER_5XX"
    const val PROVIDER_429 = "PROVIDER_429"
    const val CRASH_RECOVERY = "CRASH_RECOVERY"   // 冷启动后发现 PENDING/GENERATING 已经丢了远端 context
    const val USER_CANCEL = "USER_CANCEL"
    const val WRITE_FILE_FAIL = "WRITE_FILE_FAIL"   // 字节拿得到，但写 filesDir/generated_images/ 失败
    const val UNKNOWN = "UNKNOWN"
}
```

### 6.2 AUTO 形态探测流程图（Q4.1 决策）

```
GenerateImageTool.executeStream()
    │
    ├─ 读取 T2IProvider.endpointMode
    │    ├─ SYNC  → 直接走 OpenAICompatImageGenerator.syncGenerate(request)
    │    ├─ ASYNC → 直接走 AsyncPollingSupport.generateWithPolling(request)
    │    └─ AUTO  → 走下面探测流程一次：
    │
    ▼ probeOnce(config, request.minimalTestClone)
    │
    │   ┌─────────────────────────────────────────────┐
    │   │ POST {baseUrl}/images/generations            │
    │   │ (body: prompt="test probe", size="256x256") │  ← 发送最小成本探测
    │   └─────────────────────────────────────────────┘
    │                   │
    │                   ▼ HTTP response
    │          ┌────────┴─────────────────────┐
    │          │ code=200 body 里有没有       │
    │          │ { "task_id": "...",          │
    │          │   "status": "pending" } ?    │
    │          ├─ YES → 判定 ASYNC            │
    │          ├─ NO 且 data[0].b64_json      │
    │          │     或 data[0].url 存在 →    │
    │          │     判定 SYNC                │
    │          ├─ 429 / 探测失败 3 次 →       │
    │          │     fallback 保守 → ASYNC    │
    │          └─ timeout → 判定 ASYNC（慢）  │
    │
    ▼ probeOnce 返回 T2ICapabilityProbeResult(actualMode)
    │
    ▼ UPDATE t2i_providers SET endpointMode=actualMode（从此不再 AUTO，除非用户手动重设）
    │
    ▼ 按 actualMode 走实际 generate 流程（和用户请求一致，不再用 test probe 参数）
```

### 6.3 崩溃恢复 & 状态机（Q4.3 决策 + 问题 2.x）

```
Application.onCreate()
    │
    └─ launch(Dispatchers.IO + SupervisorJob()) {
       T2ITaskRepository.recoverDanglingOnColdStart()
    }
    │
    ▼ recoverDanglingOnColdStart()：
      ├─ SELECT * FROM t2i_tasks WHERE status IN ('PENDING','GENERATING')
      │     → 用 PARTIAL INDEX idx_t2i_tasks_pending_gen，极快
      │
      └─ 对每一行 task：
         ├─ A) task.remoteTaskId != null && endpointModeUsed == ASYNC
         │      尝试 ImageGenerator.fetchRemoteTask(provider, remoteTaskId)
         │       ├─ 返回 Result → 重走 SUCCESS 流程（落文件、双写、额度扣减）
         │       ├─ 返回 Error（任务不存在 404）→ UPDATE status=FAILED, errorCode=CRASH_RECOVERY
         │       └─ 超时/网络不可用（用户离线）→ UPDATE status=FAILED, errorCode=CRASH_RECOVERY，
         │                                             userVisibleHint="应用重启前生成被中断，点此重试"
         │
         ├─ B) task.remoteTaskId == null || endpointModeUsed == SYNC
         │      → 无法恢复（SYNC 没远端持久 task_id，HTTP 响应已丢）
         │      → UPDATE status=FAILED, errorCode=CRASH_RECOVERY
         │
         └─ UPDATE 完后，发一条 ApplicationScope Flow 通知 ViewModel：
            刷新对应 sessionId 的 UI → T2IGenerationCard FAILED 分支显示重试按钮

UI 用户点"重试"按钮：
    → GenerateImageTool.retryFromTask(parentTaskId)
    → 构造 NEW 任务行：
        generation = parent.generation + 1,
        parentTaskId = parent.id,
        status = PENDING,
        ...（参数快照从 parent.argsSnapshotJson 恢复，用户可改 prompt 再发）
```

### 6.4 存储细节 + 清理评估 + MediaStore 另存为（问题 5.x）

#### 6.4.1 路径 & 命名

```
{context.filesDir}/
└─ generated_images/
   ├─ {sessionId-abc123}/
   │   ├─ {taskId-uuid1}_0.png              ← 第一张原图（batchIndex=0）
   │   ├─ {taskId-uuid1}_0.thumb.png        ← 256x256 缩略图（PNG 同格式）
   │   ├─ {taskId-uuid2}_0.png              ← 另一次 Tool 调用根任务
   │   └─ {taskId-uuid3}_0.png              ← taskId-uuid2 的变体（parentTaskId=uuid2）
   └─ _meta/                                 ← 可选：sessionId→会话标题（用于清理日志追溯）
```

#### 6.4.2 清理评估（WorkManager 执行）

```kotlin
suspend fun T2ICleanupManager.evaluateAndClean() {
    val settings = t2iPermissionSettingsDataStore.data.first()

    // 1) 按天数（cleanupUnfavoredAfterDays > 0 才生效）
    if (settings.cleanupUnfavoredAfterDays > 0) {
        val cutoff = System.currentTimeMillis() - settings.cleanupUnfavoredAfterDays * 86400_000L
        val where = buildString {
            append("isFavorited = 0 AND createdAtMs < ?")
            if (!settings.cleanupFavoredAlwaysKeep) append("")
            // cleanupFavoredAlwaysKeep=false 时收藏也按天数清（用户关了永留），否则 where 自带 isFav=0 过滤
        }
        val tasksToDelete = t2iTaskDao.queryForCleanup(where, cutoff)
        tasksToDelete.forEach { task ->
            deleteFilesOf(task)  // 原图 + 缩略图
            t2iTaskDao.deleteById(task.id)
        }
    }

    // 2) 按容量阈值（cleanupTotalSizeCapMB > 0 才生效；FIFO 删最老的未收藏，直到 < 水位线 80%）
    if (settings.cleanupTotalSizeCapMB > 0) {
        val totalBytesCalc = t2iTaskDao.sumOfFileSizeBytes(isFavoredOnly = if (settings.cleanupFavoredAlwaysKeep) 0 else null)
        var currentMB = totalBytesCalc / (1024*1024)
        val high = settings.cleanupTotalSizeCapMB
        val low  = (high * 0.8).toInt()
        if (currentMB > high) {
            val oldest = t2iTaskDao.listByOldest(isFavored = 0, limit = 200)
            for (task in oldest) {
                val szMB = (task.fileSizeBytes ?: 0) / (1024*1024)
                deleteFilesOf(task)
                t2iTaskDao.deleteById(task.id)
                currentMB -= szMB
                if (currentMB <= low) break  // 低于 80% 水位线停
            }
        }
    }
}
```

#### 6.4.3 手动保存到系统相册（用户点 💾 时才执行，不骚扰用户）

```kotlin
suspend fun LocalFileImageStorage.saveToMediaStore(
    context: Context, task: T2ITaskEntity,
): Uri? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fname = "IMG_${timeStamp}_${task.id.take(8)}.png"

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fname)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RDeepCode/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)  // 先标记 PENDING，写完后置 0（Android Q+ 要求）
    }
    val uri = resolver.insert(collection, values) ?: return@withContext null
    runCatching {
        resolver.openOutputStream(uri, "w").use { os ->
            getFileStream(task).copyTo(os)  // filesDir 原图 → MediaStore
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }.onFailure {
        resolver.delete(uri, null, null)
        return@withContext null
    }
    uri
}
```

---

## 📎 文档索引（与现有项目权威位置对齐）

| 文档 | 路径 |
|------|------|
| ✅ 本设计规格书 | [T2I_RC69_TECHNICAL_DESIGN_v1_0.md](file:///workspace/deepcode-R/T2I_RC69_TECHNICAL_DESIGN_v1_0.md) |
| 已有总架构文档 | `COMPLETE_TECHNICAL_ARCHITECTURE_v1_0.md` |
| 已有 ZTH 模式设计 | `ZTH_MODE_TECHNICAL_DESIGN_v1_0.md` |
| 闪退复盘与工程教训（可作为 T2I 8 步串行的最佳实践依据） | `docs/engineering/startup-crash-lessons-RC61.md` |
| 设计绑定的 Room 迁移脚本对应物（后续 Step 1 生成） | `app/src/main/assets/migrations/39_rc69_add_t2i.sql` |

---







