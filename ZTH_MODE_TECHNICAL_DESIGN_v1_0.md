# ZTH 零幻觉容忍模式 · 三层架构完整技术设计文档 v1.0
> 需求冻结版本 · 含模块结构 / 类图 / DB Schema / DataStore Proto / 状态机 / 决策矩阵 / 参数总表 / UI 分层 / 钩子落点 / 单测 Checklist
> 对应代码库：[deepcode-R](/workspace/deepcode-R)
> 现有核心参考结构位置（仅作挂接参考，本文档不要求改现有实现设计方向）：
> - [StatefulAgentWorkflow.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt)
> - [ModelMetadataService.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/remote/ModelMetadataService.kt)
> - [ContextCompactor.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/ContextCompactor.kt)
> - [CheckpointDao.java](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/data/local/dao/CheckpointDao.java)
> - [ProviderEditorScreen.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/ProviderEditorScreen.kt)
> - [SettingsViewModel.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/SettingsViewModel.kt)
> - [CompatibilityPolicyRepository.kt](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/repository/CompatibilityPolicyRepository.kt)

---

## 0. ZTH 顶层铁律（4 条 · 架构宪法）& 13 个拍板 Trade-off（需求冻结 sign-off）

### 0.1 4 条 ZTH 铁律（任何代码违反 = 架构违规）

| 铁律 ID | 内容 | 自我麻痹判据（代码上绝对禁止） |
|---|---|---|
| **ZTH-0** | 只要内容字节级非「用户原始输入」/「工具 100% 可重复结果」→ 默认带幻觉。风险等级只决定卡片信息密度，**绝不决定要不要问用户**。 | 「置信度<N 就静默放行 / 评分高=当事实 / LOW 级自动跳过卡片」 |
| **ZTH-1** | 所有可能带幻觉的内容（降级/压缩/重写/摘要）进主流程前，**必须阻塞式等用户显式确认**；预执行 / 默认同意 / 关 X=同意 全禁。 | 放 badge 但主流程继续跑 / 后台先执行再提示 / 肌肉记忆场景自动确认 |
| **ZTH-2** | 用户确认卡片 = 白盒。必须同时展示：系统结果、原始输入对照、**系统结果可编辑**；用户编辑内容 = 用户输入级事实。 | 只问「对吗 Y/N」不给对照 / 给了结果不能编辑 |
| **ZTH-3** | 一次 🔴 CRITICAL 幻觉风险（系统自曝不确定 + ctx 85%+ 且有矛盾）→ **本会话的自动降级/压缩功能永久关闭**；必须开新会话；重启 App 也不能恢复。 | CRITICAL 后负分拉回 / 重启工作流恢复权限 / 关了还能再试 |

### 0.2 13 个拍板 Trade-off（本技术文档所有数值/策略的直接来源）

| # | 内容 | 拍板结果 |
|---|---|---|
| #1 | RequiredCapability AND vs 可拆分 | 折中 C：REASONING/CONTEXT_COMPACTION=原子；VISION/TOOL/STREAMING=可拆分 |
| #2 | UNCLASSIFIED 是否降级 | 折中 C：三级门控（会话≤2 次 / 深度=1 / 强制 DETAILED 卡片） |
| #3 | TOOL 失败是否半手动草稿 | 折中 C：只读工具允许草稿+确认；写/执行工具=完全禁降级草稿 |
| #4 | 熔断 OPEN 阈值数字 | B：60=HALF_OPEN, **120=OPEN** |
| #5 | UID 已确认摘要复用策略 | 折中 C：UID 命中 + 配文否定词 < 1 → 复用 + 常驻 badge；否则重新识图出卡 |
| #6 | AUTH 失败是否加熔断分 | 折中 C：不加熔断分；独立累计连续配置错误，≥3 次后成功弹提示 |
| #7 | chainSpec 存储粒度全局 vs Per-Provider | 折中 C：存 Per-Provider；非 MANUAL 全局预设复制进所有 Provider；MANUAL 档可单独编辑 |
| #8 | 卡片阻塞等待超时策略 | 折中 C：永不超时；120s 放模型 socket；300s 放 wakelock；点确认自动重连 |
| #9 | DETAILED 模板 0 勾选处理 | 折中 C：底部自动加声明项「我不需要系统提取的约束，仅用我编辑的自由文本」（必须勾才能确认）；Layer 5 新增 `hardConstraintsRejectedExplicitly=true` |
| #10 | L0 还原入口可见性 | 折中 C：前 60 秒强提醒黄条，60 秒后变 toolbar 常驻图标 🗂️，永远可点 |
| #11 | L2 覆盖率 < 阈值时能否点确认 | 折中 C：高风险禁止句类必须处理完才能确认（阻塞）；低风险非禁止句类 + 二次确认 Modal |
| #12 | DEL-2 硬删除约束是否二次确认 Modal | 折中 C：所有 DEL-2 必弹 Modal；高风险禁止句 Modal=红色警告 + 2 秒按钮冷却 + 承认风险按钮文案；低风险 Modal=蓝色简洁 |
| #13 | Checkpoint 还原是否允许 LINK-INV-0 CB 分回退 | 折中 C：CB 分数绝对不扣回（LINK-INV-0 严格）；但上一次自动处理**不算进 execGlobalBudget 次数硬计数** + 自动重跑对应卡片给第二次确认机会；熔断分新确认正常算两次 |

---

## 1. 全景模块依赖图（包结构 / 类图 / 数据流向）

### 1.1 推荐 Kotlin 包结构（按三层 7 子系统拆分）

```
com.deep.rcode.feature.agent.zth                 ← ZTH 顶层包（与 feature/agent 平级或子包）
├─ invariants                                     ← 0 章铁律：断言类 + 异常类型
│   ├─ AgentZthInvariants.kt                       静态 assertZthXxx(state, op) 12 个断言
│   └─ ZthInvariantViolationException.kt          class ZthInvariantViolationException(reason: String, invId: String) : IllegalStateException
│
├─ capability                                     ← 第 2 章第一层：能力/失败/执行器
│   ├─ detector
│   │   └─ CapabilityDetector.kt                   suspend fun detect(sessionState, userMsg): Set<RequiredCapability>（纯函数可单测）
│   ├─ classifier
│   │   ├─ FailureClassifier.kt                    classify(e, context) → FailureClassification(FailureClass, SubClass, evidence, actionTableKey)
│   │   ├─ FailureKeywordEvidenceTable.kt          6 大类关键字证据表（多语言/模糊匹配+证据计数）
│   │   └─ HallucinationClassifier.kt              run(rawOutput, context) → HallucinationAssessment(cardDensity, scoreDelta, extractedHardConstraints, isCritical)
│   ├─ executor
│   │   ├─ DegradationChainExecutor.kt             execute(req) → DegradationResult<T,R>
│   │   ├─ ResolverResolver.kt                     resolveResolver(spec, ctx) → CallableLLMConfig?
│   │   └─ chain
│   │       ├─ ChainSpec.kt / ResolverSpec.kt       Data class（CSPEC-INV-1~3 校验函数 validate() 挂这里）
│   │       └─ ZthPresetConstants.kt               STRICT / BALANCED⭐ / LAX / MANUAL 4 预设常量 specs 值
│   └─ decision
│       ├─ ZthDecisionMatrix.kt                    6 FailureClass × 16 SubClass × 10 列 = List<DecisionRule> 纯数据表
│       └─ DecisionRule.kt                         data class(10 列字段，见 2.1)
│
├─ compaction                                     ← 第 3 章第二层：多级保真压缩
│   ├─ L0SoftCompactor.kt                          execute(messages, ctx, params) → L0Result（含截断/合并/审计元数据 + 4 条自动化断言）
│   ├─ L1SelectiveCompactor.kt                     execute(messages, A_keep, ctx) → L1Result（按段 Schema 约束 + 3 条闸门）
│   ├─ L2FullFidelityCompactor.kt                  execute(messages, A_keep, ctx) → L2Result（5 section Schema 约束 + 双闸门 + coverageGap 计算）
│   ├─ pipeline
│   │   └─ CompactionPipeline.kt                   严格单向 L0→L1→L2 状态机（升级式），含触发阈值判断
│   ├─ gap
│   │   ├─ LostHardConstraintMatcher.kt            最佳位置匹配算法（2 阶段打分，30 参数权重默认 0.7/0.2/0.1）
│   │   ├─ LostHardConstraintItem.kt               Data class（含 whyLost, suggestedPos, severity, sourceAnchor 等字段）
│   │   └─ ConstraintBidirectionalLinker.kt        GAP-INV-2 正向（插回→恢复）+ REV-INV-1 逆向（删约束→回滚删除状态）
│   └─ ui
│       └─ L0TinyIconHandler.kt                    [T]/[⊞]/[✅] 图标渲染 + 点击浮窗 + 还原联动 REV-INV-2
│
├─ linkage                                        ← 第 4 章第三层：四方联动（CB × 确认 × Plan × Checkpoint）
│   ├─ HallucinationCircuitBreaker.kt              6 态状态机 + addScore(+10/+50/+150/+∞) + 持久化同步写（LINK-INV-4 @Transaction）
│   ├─ PlanRejectionSentinelMarker.kt              LINK-INV-1：Plan Reject → sentinel 打 planRejectedAt + Planner prompt 前缀警告注入
│   ├─ FuseOpenGlobalCoordinator.kt                LINK-INV-2：WindowManager 全局悬浮横幅 + 自动 Reject Plan + 自动 Cancel Card
│   ├─ CheckpointRevertCoordinator.kt              LINK-INV-3：还原前先关 pending Card/Plan 再换 messages
│   ├─ PlanItemSourceAttacher.kt                   LINK-INV-5：每条 Plan Item 后处理注入 FactSource 列表 + 序列化存 DB
│   └─ recovery
│       └─ LinkInvViolationRecovery.kt             6 类 LINK_INV_VIOLATION(0~5) 结构化降级流程（异常时不 Crash）
│
├─ model.room                                     ← 第 5 章 Room DB 新实体
│   ├─ entity
│   │   ├─ UserConfirmedSentinelEntity.kt          Layer 5 消息（MSG-INV-1 追加写不可变）
│   │   ├─ SentinelPlanRejectionAudit.kt           LINK-INV-1 审计关联行
│   │   ├─ HardConstraintDeleteAudit.kt            DEL-2 硬删除审计（含 acknowledgedRisk 字段对应 Trade-off #12）
│   │   ├─ L0SoftCompactRestoreLogEntity.kt        REV-INV-2 L0 单条还原日志
│   │   ├─ HallucinationFuseEntity.kt              CB 永久锁 + OPEN 事件日志（LINK-INV-4 写 2）
│   │   └─ upgrade                                 现有表升级列（见 5.2）
│   │       ├─ AgentSessionEntityExt.kt            fuseScoreSnapshot / fuseCriticalHits / fuseStateEnum ...
│   │       ├─ PlanApprovalEntityExt.kt            checkpointId / rejectedDueToFuseOpen / planItemSourceRefsJson ...
│   │       └─ CheckpointEntityExt.kt              fuseScoreAtSave / fuseCriticalHitsAtSave / checkpointHasCriticalHit ...
│   └─ dao
│       ├─ UserConfirmedSentinelDao.kt             @Insert 只允许；@Update 仅限 confirmedAt；其余字段禁 UPDATE（MSG-INV-1）
│       ├─ SentinelPlanRejectionAuditDao.kt
│       ├─ HardConstraintDeleteAuditDao.kt
│       ├─ L0SoftCompactRestoreLogDao.kt
│       └─ HallucinationFuseDao.kt
│
├─ model.datastore                                ← 第 6 章 DataStore Proto
│   ├─ CompatibilityChainSpecs.proto               specs Map<String, ChainSpec> + activePreset
│   ├─ ZthParamsSnapshot.proto                     30 参数字段（方向 E，含夹取范围）
│   └─ ZthParamRanges.kt                           合理区间 [min,max] 常量 + clamp(v, range) 函数
│
├─ ui.compose                                     ← 第 8 章 UI 分层 Composable
│   ├─ settings                                    ProviderEditorScreen 的三段式重构区块
│   │   ├─ ZthPresetSelector.kt                    4 预设包单选（STRICT/BALANCED/LAX/MANUAL）
│   │   ├─ ZthCapabilityChainOverviewCard.kt       5 条能力链概览卡（非 MANUAL 只读；MANUAL 齿轮按钮）
│   │   ├─ ZthFuseDashboard.kt                     熔断仪表盘（只读 + 重置按钮高级）
│   │   ├─ ZthResolverEditorSheet.kt               MANUAL 档 Resolver 高级编辑 BottomSheet
│   │   └─ ZthParamsEditorSheet.kt                 MANUAL 档参数微调 Tab（6 组别 30 参数滑条）
│   ├─ card                                        ConfirmationCard 3 模板 + 压缩 4 扩展区
│   │   ├─ ZthConfirmationCard.kt                  根 Composable（按 density 渲染 INFO/STANDARD/DETAILED，密度=DETAILED 时挂 4 扩展区）
│   │   ├─ ZthConfirmationCardViewModel.kt         17 事件 × 12 状态状态机；suspendCancellableCoroutine 阻塞；IdleResourceReleaseManager(T=120/300s)
│   │   ├─ template
│   │   │   ├─ ZthCardInfoTemplate.kt
│   │   │   ├─ ZthCardStandardTemplate.kt          左右对比 + 编辑框 + 三按钮
│   │   │   └─ ZthCardDetailedTemplate.kt          分段对照 + checkbox 逐条（Trade-off #9 声明项自动加）
│   │   └─ compression
│   │       ├─ ZthCardCompressionHeaderWarning.kt  顶部丢失硬约束警告区（黄区，覆盖率<90%）
│   │       ├─ ZthCardCompressionDeletionList.kt   扩展区 2：删除/摘要清单（逐行恢复/取消按钮 + L2 DEL-2 联动逆向）
│   │       ├─ ZthCardCompressionSourceTracker.kt  扩展区 3：硬约束来源追踪面板（跳转链接+红色幻觉标）
│   │       └─ ZthCardCompressionRestoreButtons.kt 扩展区 4：4 档 Checkpoint 还原快捷入口
│   └─ global
│       ├─ ZthFuseOpenGlobalBanner.kt              LINK-INV-2 WindowManager 悬浮横幅（无关闭按钮）
│       ├─ ZthL0SoftCompactBadge.kt                L0 触发后聊天页底部 5 秒 badge + 60 秒黄条 → toolbar 🗂️ 图标
│       └─ ZthRevertCheckpointMenu.kt              聊天页菜单子菜单：4 档还原入口
│
└─ di                                             Hilt 模块（AgentModule 扩展）
    └─ ZthModule.kt                                提供 CapabilityDetector / FailureClassifier / DegradationChainExecutor / 3 Compactor / CB / 4 个 Coordinator 的 @Provides 函数
```

### 1.2 核心类依赖图（按三层）

```
【现有主入口】StatefulAgentWorkflow.processUserTurn()
        │
        ├─→【L1 能力路由管线】
        │      CapabilityDetector ──→ DegradationChainExecutor
        │                              │          ├─ ResolverResolver（查询 ModelMetadataService.applyCompatibilityPolicies）
        │                              │          ├─ ZthDecisionMatrix（查表动作）
        │                              │          └─ FailureClassifier / HallucinationClassifier
        │                              │
        │                              └─→ ZthConfirmationCardViewModel.suspendAwaitUserConfirm() ──阻塞──→ 写 UserConfirmedSentinelDao
        │                                                                        │
        │                                                                        └─→ HallucinationCircuitBreaker.addScore()
        │                                                                             │
        │                                                                             ├─ LINK-INV-2 ─→ FuseOpenGlobalCoordinator
        │                                                                             └─ LINK-INV-4 @Transaction ─→ 四处持久化
        │
        ├─→【L2 多级保真压缩】
        │      CompactionPipeline（L0→L1→L2 升级式）
        │         L0SoftCompactor → L1SelectiveCompactor → L2FullFidelityCompactor
        │              │                 │                       ├─ LostHardConstraintMatcher
        │              │                 │                       └─ ConstraintBidirectionalLinker（GAP+REV 双向）
        │              │                 │                                 │
        │              │                 │                                 └─→ ZthConfirmationCard (DETAILED + 4 扩展区) ─→ 写 sentinel
        │              │                 │
        │              │                 └─ CheckpointDao.save(PreL1)
        │              └─ CheckpointDao.save(PreL0)
        │
        └─→【L3 四方联动】
               ┌─────────────────────────────────────────────────────────┐
               │ HallucinationCircuitBreaker ←LINK-INV-0 max→ CheckpointDao │
               │         ↓ LINK-INV-2 → FuseOpenGlobalCoordinator          │
               │                            ↓                               │
               │ PlanApprovalViewModel ──LINK-INV-1→ sentinel 打标记        │
               │     ↓ LINK-INV-5 PlanItemSourceAttacher                   │
               │ ZthConfirmationCard ──LINK-INV-3──→ CheckpointRevert      │
               └─────────────────────────────────────────────────────────┘
                              所有联动异常 → LinkInvViolationRecovery
```

### 1.3 数据流向 6 条主路径（ZTH-1 合规检查点）

| 路径 ID | 方向 | ZTH 合规校验点 |
|---|---|---|
| P1 | 用户输入 → CapabilityDetector → DegradationChainExecutor → 卡片 → sentinel（Layer 5）→ LlmMessageBuilder.inline 进 user 消息（role=user，不是 role=system，MSG-INV-3/4）→ 主模型调用 | MSG-INV-3：L1 系统元数据不进上下文；MSG-INV-4：L5 sentinel inline 到 user，不发 role=system |
| P2 | L0/L1/L2 → 压缩卡片 DETAILED → 用户确认 → postCompactionMessages → 替换会话 messages 列表 → Checkpoint 保存 | ZTH-INV-1 hash：L5/L4引用/L3 压缩前后 hash 一致（MSG-INV-2） |
| P3 | L2 闸门覆盖率 < 90% → LostHardConstraintMatcher → 插回 section4 → ConstraintBidirectionalLinker（正向）→ 删除清单恢复对应行 → sentinel 写 restoreCount | GAP-INV-0：插回的条目必须携带原 sourceAnchorId（不变红色幻觉类）；GAP-INV-2：来源消息从「删除→保留」 |
| P4 | DEL-2 删除 section4 → ConstraintBidirectionalLinker（逆向 REV-INV-1）→ 删除清单行从「恢复→删除」→ 二次确认 Modal（高风险红色 + 2s 冷却）→ sentinel 写删除审计 | Trade-off #12 全弹 Modal；hardConstraintDeletedWithRiskAcknowledged 高风险=true |
| P5 | CB OPEN → LINK-INV-2（横幅 + 自动拒 Plan + 自动 Cancel Card）→ 用户点开始新会话 → 新建 session CB 清零（但原 session 永久锁写 HallucinationFuseEntity.permanentlyOpen=true） | 横幅无关闭按钮；原 Plan/Card 状态非 pending；App 重启后原 session 仍然是 OPEN |
| P6 | Checkpoint 还原 → LINK-INV-0 max(current, checkpointScore)（CB 不回退）→ LINK-INV-3（先关 Card/Plan 再换 messages）→ REV-INV-2（同步 L0 还原日志绿色 badge） | CB.score 不低于还原前；Card/Plan 状态非 pending；L0 [T] 图标状态和快照一致 |

---

## 2. 第一层：通用能力路由管线

### 2.1 ZthDecisionMatrix.kt 6 FailureClass × 16 SubClass × 10 列 完整决策矩阵（方向 B）

`DecisionRule` 10 列字段定义：
```kotlin
data class DecisionRule(
  val failureClass: FailureClass,                    // ① 一级枚举
  val subClass: FailureSubClass,                      // ② 二级枚举（16 子类全集）
  val evidenceChainMin: List<EvidencePredicate>,      // ③ 最低证据链（三层裁决命中条件）
  val standardActions: List<StandardAction>,          // ④ ZTH 标准动作链（枚举：RETRY_T0 / RETRY_T1 / RUN_FALLBACK1 / FORMAT_STRONG_PREFIX / SKIP_TO_CARD）
  val mustShowCard: Boolean,                          // ⑤ 是否必须出卡片
  val defaultCardDensity: CardDensity,                // ⑥ 密度（INFO/STANDARD/DETAILED/N_A）
  val circuitBreakerScoreDelta: Int,                  // ⑦ 熔断分 delta（10/50/150/+∞ Int.MAX_VALUE 表示 CRITICAL +∞）
  val outcomeClass: OutcomeClass,                     // ⑧ SUCCESS_NO_TOUCH / CONFIRMATION_REQUIRED / TERMINAL_FAILURE
  val terminalActionOnConfirm: TerminalAction,        // ⑨ 确认后动作
  val terminalActionOnCancel: TerminalAction          // ⑩ 取消后动作
)
```

**决策矩阵（16 行全量常量）**（行数 = 方向 B 讨论结果精化，编程时直接写成 ZthDecisionMatrix.rules 常量列表）：

| 行 # | failureClass | subClass | ⑤出卡？ | ⑥密度 | ⑦delta | ⑧Outcome | ⑨确认后 terminalAction | ⑩取消后 terminalAction |
|---|---|---|---|---|---|---|---|---|
| 1 | CAPABILITY_UNSUPPORTED | VISION_UNSUPPORTED | ✅是 | Hallucination 打分决定 | 打分决定 | CONFIRMATION_REQUIRED | WRITE_L5_SENTINEL + INLINE_TO_USER_MSG + CONTINUE | VISION.CONTINUE_WITHOUT（纯文本列附件名） |
| 2 | CAPABILITY_UNSUPPORTED | TOOL_CALL_UNSUPPORTED | ✅是 | INFO | +10 | CONFIRMATION_REQUIRED | NEXT_CALL_NO_TOOL_DEFS | TOOL.STOP_AND_REPROMPT（弹切换预设提示框） |
| 3 | CAPABILITY_UNSUPPORTED | REASONING_UNSUPPORTED | ✅是 | INFO | +10 | CONFIRMATION_REQUIRED | NEXT_CALL_REASONING_OFF | REASONING.REVERT_CHECKPOINT |
| 4 | TRANSIENT_FAILURE | RATE_LIMITED_429 | ❌否 | N/A | +0 | SUCCESS_NO_TOUCH | （重试成功，无确认） | — |
| 5 | TRANSIENT_FAILURE | SERVER_ERROR_5XX | ❌否 | N/A | +0 | SUCCESS_NO_TOUCH | （重试成功） | — |
| 6 | TRANSIENT_FAILURE | NETWORK_TIMEOUT | ✅是 | STANDARD | +50 | CONFIRMATION_REQUIRED（重试失败后出）| RETRY_NON_STREAM / USE_PARTIAL_FRAGMENT | ROLLBACK_TO_BEFORE_USER_MSG |
| 7 | RESPONSE_FORMAT_INVALID | TOOL_CALL_JSON_INVALID | ✅是 | STANDARD | +50 | 重试1次+失败才出卡 | NEXT_CALL_RETRY1 / SKIP_TOOL / USER_EDIT_JSON | ROLLBACK_AND_RESEND |
| 8 | RESPONSE_FORMAT_INVALID | REASONING_TAG_MALFORMED | ✅是 | STANDARD | +50 | 重试1次+失败才出卡 | NEXT_CALL_REASONING_OFF / CLEAN_TAGS_OUTPUT | ROLLBACK_AND_RESEND |
| 9 | RESPONSE_FORMAT_INVALID | SSE_FRAME_CORRUPTED | ✅是 | STANDARD | +50 | 重试流式+换非流式才出卡 | RETRY_NON_STREAM | ROLLBACK_AND_RESEND |
| 10 | AUTH_OR_BILLING_FAILURE | INVALID_API_KEY | ✅是 | INFO | +0（配置错，不加熔断分） | CONFIRMATION_REQUIRED | JUMP_TO_PROVIDER_EDITOR / TEST_NEW_KEY | STOP_CURRENT_TURN |
| 11 | AUTH_OR_BILLING_FAILURE | BILLING_QUOTA_EXCEEDED | ✅是 | INFO | +0 | CONFIRMATION_REQUIRED | SWITCH_TO_ANOTHER_PROVIDER_MODEL_OR_RECHARGE | STOP_CURRENT_TURN |
| 12 | AUTH_OR_BILLING_FAILURE | INSUFFICIENT_SCOPE | ✅是 | INFO | +0 | CONFIRMATION_REQUIRED | JUMP_TO_PERMISSION_CONFIG / TEST_KEY | STOP_CURRENT_TURN |
| 13 | USER_INTERRUPT | USER_CLICKED_STOP | ❌否 | N/A | +0 | TERMINAL_FAILURE(USER_CANCELLED) | — | ASSISTANT_INTERRUPTED_STATUS |
| 14 | USER_INTERRUPT | SESSION_TERMINATED | ❌否 | N/A | +0 | TERMINAL_FAILURE(SESSION_GONE) | — | CLEANUP_STATE_NO_WRITE |
| 15 | UNCLASSIFIED | LOW_CONFIDENCE（≤1关键字命中） | ✅是 | **强制 DETAILED**（unclassifiedEnforceDensity=3 写死）| **+150**（1次≥120直接HALF→下次OPEN） | CONFIRMATION_REQUIRED（门控：会话≤2次 + 深度=1）| 按同动作能力走默认 | 按同能力走失败 |
| 16 | UNCLASSIFIED | HIGH_CONF_CAPABILITY（≥2关键字命中疑似） | ✅是 | **强制 DETAILED** | +150 | CONFIRMATION_REQUIRED（同上三级门控）| 同上 | 同上 |

### 2.2 ZthConfirmationCardViewModel.kt：17 事件 × 12 状态 完整状态机（方向 F）

**状态枚举（12）**：INIT / LOADING / INFO_READY / STANDARD_READY / DETAILED_READY / DETAILED_PARTIALLY_CHECKED / USER_EDITING / RERUN_LOADING / REUSE_CONFIRMED / CONFIRMED / CANCELLED / ERROR_TERMINATED

**事件枚举（17 E1~E17）**：见方向 F 精确定义。

**确认时写 Layer 5 sentinel（A4 动作精确字段来源）**：
```kotlin
// A4 写入字段明细（写 UserConfirmedSentinelEntity，见 5.1.1）：
role = USER_CONFIRMED_SENTINEL
confirmsOf {
  capability = payload.capability
  originalDegradationTraceId = payload.degradationTraceId
  originalInputRefs = payload.originalInputRefs.map { it.dbUid }
}
finalContent = if DETAILED: applyUncheckedHardConstraintDeletions(payload.currentFinalContent, uncheckedCheckboxIds)
               else:         payload.currentEditedFinalContent  // INFO/STANDARD 用户编辑框
hardConstraints = if DETAILED: payload.checkedHardConstraints.map { it.toDbEntity() }
                 else:         payload.extractedHardConstraints
userEdited = (payload.rawOutputHash != finalContent.sha256()) || pendingUserEditedLocked  // CARD-INV-3（锁存后不允许改回false）
confirmedAt = System.currentTimeMillis()
rerunCount = payload.rerunCounter                       // A10_1/2/3/4 计数
reusedFromSentinelId = if REUSE path: payload.reusedSentinelId else null
cardDensityAtConfirmTime = payload.cardDensity.name
// ↓ L2 压缩卡片专属（null if not compression）
compressionLevel = payload.compressionLevel
compressionTokensSaved = payload.tokensSavedFinalValue
restorableCheckpointIds = payload.checkpointBundle.allIds
deletedMessagesTrace = payload.deletionList.finalState.serialize()
// ↓ DEL-2 / Trade-off #9 / #12 审计字段
hardConstraintsRejectedExplicitly = DETAILED and userCheckedRejectAllDeclarativeItem
hardConstraintDeletedWithRiskAcknowledged = DEL-2 deletions.any { it.severity == HIGH_RISK && userClickedAcknowledgedButtonOnModal }
```

**熔断加分（A5 精确特判）**：
```kotlin
// A5 仅 A8a（普通确认）路径执行；A8b（复用确认路径 = reusedFromSentinelId != null）→ 不加第二次（防止复用10次加10次熔断分）
if (reusedFromSentinelId == null) {
  circuitBreaker.addScore(assessment.scoreDelta)
}
// assessment.isCritical = true → score = Int.MAX_VALUE（代表 +∞），state 直接 OPEN_CRITICAL，criticalHits++（触发 LINK-INV-4 四写事务）
```

**主流程阻塞解除（A6 精确 Outcome 字段）**：
用 `suspendCancellableCoroutine { cont -> viewModel.onConfirmOrCancel = { cont.resume(it) } }` 实现阻塞，返回值类型 `DegradationChainResult`：
- CONFIRMED：`confirmedSentinelId / finalContent / hardConstraints / postCompactionMessages?`（压缩卡才有 postCompactionMessages）
- CANCELLED / ERROR：`failureClass / terminalAction`

### 2.3 chainSpec 存储 4 预设常量值（方向 C + CSPEC-INV 1~3 校验）

```kotlin
// ZthPresetConstants.kt 纯常量（单位置维护，预设切换时直接覆盖 Per-Provider 副本）
object ZthPresetConstants {
  val BALANCED_DEFAULT = CompatibilityChainSpecs(
    specs = mapOf(
      "VISION" to ChainSpec(
        resolvers = listOf(
          ResolverSpec(type = PRIMARY_LLM),
          ResolverSpec(type = DEDICATED_VISION_LLM)  // 长度=2，CSPEC-INV-1 PASS
        ), enabled = true, displayNameKey = "capability.vision"
      ),
      "CONTEXT_COMPACTION" to ChainSpec(
        resolvers = listOf(ResolverSpec(DEDICATED_COMPACT_LLM), ResolverSpec(SOFT_COMPACT_ONLY)),
        enabled = true, displayNameKey = "capability.compaction"
      ),
      "TOOL_RICH" to ChainSpec(resolvers = listOf(ResolverSpec(PRIMARY_LLM)), enabled = true, displayNameKey = "capability.tools"),  // 长度=1，CSPEC-INV-2 TOOL白名单
      "REASONING" to ChainSpec(resolvers = listOf(ResolverSpec(PRIMARY_LLM)), enabled = true, displayNameKey = "capability.reasoning"),  // 长度=1，CSPEC-INV-2 REASONING白名单
      "STREAMING_REALTIME" to ChainSpec(
        resolvers = listOf(ResolverSpec(PRIMARY_LLM_STREAM), ResolverSpec(PRIMARY_LLM_NON_STREAM)), enabled = true, displayNameKey = "capability.streaming"
      )
    ), activePreset = BALANCED
  )
  val STRICT = BALANCED_DEFAULT.copy(activePreset = STRICT, specs = specs.mapValues { (k, v) -> if (k in setOf("VISION","CONTEXT_COMPACTION")) v.copy(enabled=false) else v })
  val LAX = BALANCED_DEFAULT.copy(activePreset = LAX)   // 密度/阈值差异通过 ZthParamsSnapshot 的 LAX 预设值表来体现，chainSpec 结构同 BALANCED
  val MANUAL_INITIAL = BALANCED_DEFAULT.copy(activePreset = MANUAL)  // MANUAL 档初始值 = 切过来时的 BALANCED 值，用户齿轮编辑后再独立保存 Per-Provider
}
```

---

## 3. 第二层：多级保真上下文压缩（L0/L1/L2 + 丢失约束双向联动 + L0[T] 细节）

### 3.1 L0SoftCompactor.kt 执行后 4 条自动化断言（写在 return 前，违反则自动升级 L1）

```kotlin
// L0 自动化断言（纯函数，可单测），不通过 = return L0Result.aborted()，Pipeline 升级 L1：
assertion1_hash: sha256(L5 + L4_refs + L3 序列化后) == sha256(原始同集合)  字节级一致，条数相等（MSG-INV-2 L0 版）
assertion2_audit: 每一条候选消息软压缩后，输出前缀包含最小审计字段（tool_name + tool_call_id + exit_code / FILE_LOGGER 合并包含每个被合并消息 id）→ 逐字符串包含匹配
assertion3_savingPct: (originalTokens - afterTokens)/originalTokens >= compactL0MinSavingPct(默认10%)
assertion4_noLlm: 整个执行流程没有任何 LLM SDK 被调用（可以通过测试阶段的 FakeLlmClient.invocationCount == 0 验证，ZTH-COMP-1 豁免基础）
```

### 3.2 L1SelectiveCompactor 输入/输出 Schema（extraSystemPromptPrefix 强约束，格式错=RESPONSE_FORMAT_INVALID）

```kotlin
// Resolver 输入 JSON 强约束 prompt：
// 「你必须只返回严格 JSON，不允许任何其他文字解释。Schema：
// { segmentResults: [{ segmentId: String (输入段的 segmentId 完全一致，不允许新增/删除/重排),
//                      summarizedText: String,
//                      deletedByteCount: Int,
//                      retainedAuditFields: [String]（必须 100% 包含输入 minAuditFields） }] }」
// 3 条闸门：段独立性（段数一致不重排）/ 审计字段全保留（逐项包含匹配）/ 压缩比合理 [5%, 50%]
```

### 3.3 L2FullFidelityCompactor 5 section Schema + 双闸门（闸门1不过=熔断+∞，闸门2不过=黄色警告区但进卡片）

```kotlin
// 5 section Schema（section3/4 条目必须带来源 id，无来源=🔴 幻觉类，红色+未勾选默认）：
data class L2Output(
  val section1_userOriginalIntent: List<BulletWithSource>,
  val section2_workProgress: List<PlanProgressItem>,
  val section3_confirmedPlans: List<ConfirmedPlanRef>,   // 每个 planId 必须在 Layer 3 DB 存在
  val section4_keyPathsAndConstraints: List<HardConstraintWithSource>,  // sourceId 必须能查 Layer5/L4/L3
  val section5_notesAndCaveats: List<BulletWithSource>
)
// 闸门 #1（结构完整性 + 引用合法性）：5 section 齐全 + section3/4 id 全存在 → 通过
//   不通过 = TERMINAL + CB +∞ CRITICAL（L2 resolver 幻觉严重）
// 闸门 #2（硬约束覆盖率 ≥ 90% 默认）：section4 ∪ section3 约束条目数 / "必须保留全集"基数 ≥ compactL2HardConstraintCoverageAlertPct
//   不通过 = 不阻塞，但卡片顶部渲染 L2CoverageGapWarningData（丢失清单 + 一键插回 + 位置匹配算法）
```

### 3.4 LostHardConstraintMatcher.kt 最佳位置匹配算法（GAP-INV-1 · 2 阶段打分）

```kotlin
// 默认权重写死在 ZthParamRanges.kt（MANUAL 档可调：位置推荐权重 timeW=0.7, typeW=0.2, layerW=0.1）
// 阶段 A：抽 3 维特征（T 时间差归一化 / C 类型匹配=0或1 / L 来源层匹配=0/0.5/1）
// 阶段 B：Score = 0.7*T + 0.2*C + 0.1*L → top1/top2 → 按时间戳排序插在 top1.index 前后；连续 FILE_PATH ≥3 条 → 直接插进连续块末尾（聚类优先）
// 算法确定可复现：单测固定 mock → recoverySuggestedPositionIndex == 预期值（100% 确定不抖动）
```

### 3.5 ConstraintBidirectionalLinker.kt 正向（GAP-INV-2）+ 逆向（REV-INV-1）精确执行

```kotlin
// 正向插回（A4 + 删除清单联动）：
fun onInsertLostConstraintBack(item: LostHardConstraintItem) {
    // 1. section4 插入 item.suggestedPos → 绿色背景 + 默认勾选 + 来源 id 全保留（GAP-INV-0）+ 来源追踪面板同步
    // 2. 递归找来源消息：sentinel → originalInputRefs[] / layer4 msgId
    // 3. 删除清单里来源行 op∈{DELETE, TRUNCATE, MERGE, SUMMARIZE} → 状态改成 USER_RECOVERED，移动到「已恢复折叠面板」
    // 4. postCompactionMessages 对应消息从「截断/删除」→ 恢复完整原文（字节级 = Checkpoint 元数据 L0/L1 处理前的原始 hash）；tokens 计数器回收对应节省量
    // 5. 状态 hash 断言：还原后消息 hash == 原始 L0/L1 前 hash（字节级，REV-INV-1 校验）
}

// 逆向 DEL-2（用户删 section4 条目 → 删除清单回滚）（Trade-off #12 二次确认 Modal 后执行）
fun onHardDeleteSection4Constraint(entryId: String, sourceAnchorId: String, sourceLayer: SourceLayer, severity: ConstraintSeverity, userAcknowledgedRisk: Boolean) {
    // 1. 查 Checkpoint 元数据 originalOperationAtCompactTime（原本 opType/TRUNCATE target/size 信息）
    // 2. 按 opType 从 KEPT_FULL → 恢复回原操作类型（DELETE_WHOLE = 从 postCompactionMessages 删掉整条；TRUNCATE/MERGE/SUMMARIZE = 内容改回截断版 + 删除清单主区展示）
    // 3. tokens 节省计数器 = +(beforeSize - afterSize)
    // 4. sentinel.hardConstraintDeletedWithRiskAcknowledged = (severity == HIGH_RISK) && userAcknowledgedRisk → Audit 表写一行
    // 5. hash 断言：还原后消息 hash == L0/L1 后 hash（字节级一致，正向动作完全回滚）
}
```

### 3.6 L0SoftCompactRestoreLogEntity / L0TinyIconHandler.kt 精确闭环 REV-INV-2

```kotlin
// 用户点击 [↩️ 还原本条] → 同时执行 5 件事：
// 1. UI 气泡显示完整原文/T2 拆回多条；[T]/[⊞] 变绿 ✅
// 2. Room DB @Insert L0SoftCompactRestoreLogEntity(sessionId, messageIdList, restoreType, restoredAtMs, originalCompactType)
// 3. 下一轮 L0SoftCompactor 候选扫描前置过滤器：messageId in (select messageIdList from log where sessionId=?) → 直接 skip（不管 size 多大）
// 4. L0 badge 累计 tokens 节省计数器 = 旧值 - (原始大小 - 截断后大小)（精确对应）
// 5. 最近 CheckpointEntity.restoredL0MessageIds += messageIdList（还原快照时同步恢复绿色 badge 状态）
```

---

## 4. 第三层：四方联动状态机（6 LINK-INV × 完整迁移 + 异常恢复）

### 4.1 6 态 CircuitBreaker 枚举定义 + 状态跃迁条件

```kotlin
enum class FuseState {
  CLOSED,                       // score<60, criticalHits=0
  HALF_OPEN_WARNED,             // 60≤score<120, criticalHits=0 → 每张卡片顶部加黄色警告
  OPEN_NO_CRITICAL,             // score≥120, criticalHits=0 → LINK-INV-2 横幅黄色
  HALF_OPEN_CRITICAL_PENDING,   // score<120, criticalHits=1 → LINK-INV-4 三处写，下一条确认后可能超 120
  OPEN_CRITICAL,                // score≥120, criticalHits≥1 → LINK-INV-2 横幅红色
  OPEN_CRITICAL_PERMANENT       // criticalHits≥2 → HallucinationFuseEntity.permanentlyOpen=true（App 重启后仍然 OPEN）
}
```

### 4.2 6 LINK-INV 强制迁移表（精确动作 + 校验）

| LINK-INV # | 触发事件 | 必须执行动作 | 终态校验（不通过 = LinkInvViolationRecovery） |
|---|---|---|---|
| 0 | EVT_REVERT_TO_CHECKPOINT(X) | ① finalScore=max(currentScore, X.fuseScoreAtSave)；finalCriticalHits=max(当前, X.fuseCriticalHitsAtSave)；② 10 秒 toast「熔断计数未回退，建议开新会话」 | finalScore ≥ 还原前；finalCriticalHits ≥ 还原前；状态不降级（否则抛违规修正强行再 max 一次 + 横幅 30 秒） |
| 1 | Plan Reject（User/Auto） | ① 找到 Checkpoint 之后新 sentinels → 写 SentinelPlanRejectionAudit 每行(sentinelId, rejectedPlanId)；② Planner prompt 对有审计行的 sentinel 强制加 ⚠️ 前缀「上次基于此约束生成计划被拒，请重新验证不要直接信任摘要」；③ UI 对应条目加「🚫曾拒」badge | Planner prompt 有审计行的 sentinel 100% 带 ⚠️ 前缀（否则重跑 Planner + 加强前缀，再失败提示用户开新会话） |
| 2 | CB 从非 OPEN → OPEN 三态之一 | 并行：① App 级悬浮横幅（无 × 关闭按钮，仅 2 个按钮：查看原因 / 开始新会话）；② 正在 GENERATING/PENDING 的 Plan → 状态 AUTO_REJECTED_DUE_TO_FUSE_OPEN + 固定 reason；③ 所有 pending Card → cancelDueToFuseOpen() → Cancelled | 500ms 后 Plan 状态不能 pending/generating；Card 聚合态 NO_CARD_PENDING；横幅 attach；否则 finish 当前 Activity 直接强制开新会话 |
| 3 | 用户确认还原 Checkpoint X（在 messages 替换**之前**先执行）** | ① 读 X.pendingCardIdsSnapshot + X.pendingPlanApprovalId → ② 所有在快照后新卡片或旧 pending 卡片统一 cancelDueToCheckpointRevert()；③ Plan 如果 pending → 再弹 Modal「还原会拒绝当前计划，确认继续？」用户再确认才执行 → ④ 最后才替换 messages + 同步 restoredL0MessageIds 回 L0RestoreLog | messages 替换后，Card NO_PENDING；Plan 非 pending/generating（否则抛违规 + 强制 Modal 阻塞直到用户再点「我确认还原后当前计划已作废」） |
| 4 | criticalHits n→n+1（写操作瞬间） | @Transaction 包裹（4 写全成功或全回滚）：① AgentSessionEntity: fuseCriticalHits/current state/lastOpenReason → 写；② HallucinationFuseEntity: n≥2 → permanentlyOpen=true + openEventsLog append 一行；③ 会话列表刷新 🔴 小红点；④ CheckpointDao 立刻保存一份 type=FUSE_OPEN, hasCriticalHit=true 的快照 | 事务后反查 4 处字段全部对应（否则：立刻强制 state=OPEN_CRITICAL_PERMANENT + 横幅 + WTF 级上报） |
| 5 | Planner 生成完 Plan，进 UI 渲染前 | 每条 Plan Item 必须附：factSourceRefs[{factType, sourceId, sourceAnchorMs, quotedSnippet}]；非空校验；PlanApprovalEntity 落盘带 planItemSourceRefsJson；UI 每条 Item 展示来源链可点击跳转 | factSourceRefs 列表不能全空（空则重新生成 Plan + 强约束前缀，再失败 = 降级到用户手动编辑 Plan Item 模式） |

### 4.3 LINK_INV_VIOLATION(0-5) 异常恢复策略（在线不 Crash，上报 + 结构化降级）
见第三层 3.3 表（FATAL/ERROR/WTF 分级上报 + 对应动作 + 最糟情况强制开新会话兜底）。

---

## 5. Room DB Schema 升级 RC64 v33 → ZTH 版 v37（Migration v33→34→35→36→37 分 4 步，避免一次性迁移 SQL 太长）

### 5.1 新增 5 张 Entity/DAO（对应 SCHEMA v34/v35 两步）

#### 5.1.1 UserConfirmedSentinelEntity（Layer 5 消息 · MSG-INV-1 追加写不可变）
```kotlin
// v34 → 34.sql
@Entity(tableName = "user_confirmed_sentinel", indices = [Index("session_id"), Index("original_degradation_trace_id"), Index("reused_from_sentinel_id")])
data class UserConfirmedSentinelEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "session_id") val sessionId: String,
  // confirmsOf
  @ColumnInfo(name = "capability") val capability: String,              // RequiredCapability 枚举字符串
  @ColumnInfo(name = "original_degradation_trace_id") val originalDegradationTraceId: String,
  @ColumnInfo(name = "original_input_refs_json") val originalInputRefsJson: String,  // List<String> (UIDs) JSON serialize
  // 最终内容
  @ColumnInfo(name = "final_content_text") val finalContentText: String,
  @ColumnInfo(name = "hard_constraints_json") val hardConstraintsJson: String, // List<HardConstraint> JSON
  @ColumnInfo(name = "user_edited") val userEdited: Boolean,            // CARD-INV-3 字节 hash 或编辑锁存
  @ColumnInfo(name = "confirmed_at_ms") val confirmedAtMs: Long,
  // 审计
  @ColumnInfo(name = "rerun_count") val rerunCount: Int,
  @ColumnInfo(name = "reused_from_sentinel_id") val reusedFromSentinelId: Long?,
  @ColumnInfo(name = "card_density") val cardDensity: String,            // INFO/STANDARD/DETAILED
  @ColumnInfo(name = "unselected_constraint_ids_json") val unselectedConstraintIdsJson: String,
  @ColumnInfo(name = "unselected_reasons_json") val unselectedReasonsJson: String?,
  @ColumnInfo(name = "hard_constraints_rejected_explicitly") val hardConstraintsRejectedExplicitly: Boolean,  // Trade-off #9
  @ColumnInfo(name = "hard_constraint_deleted_with_risk_ack_json") val hardConstraintDeletedWithRiskAckJson: String, // Trade-off #12 per-deletion ack
  // 压缩专属（null if not compression）
  @ColumnInfo(name = "compression_level") val compressionLevel: String?,
  @ColumnInfo(name = "compression_tokens_saved") val compressionTokensSaved: Int?,
  @ColumnInfo(name = "restorable_checkpoint_ids_json") val restorableCheckpointIdsJson: String?,
  @ColumnInfo(name = "deleted_messages_trace_json") val deletedMessagesTraceJson: String?,
  // Plan rejection 标记（LINK-INV-1，冗余存储避免 join）
  @ColumnInfo(name = "plan_rejected_count") val planRejectedCount: Int = 0
)
// UserConfirmedSentinelDao.kt：只允许 @Insert（追加写不可变）；仅一个 @Update 函数允许改 confirmedAtMs。MSG-INV-1 单测覆盖「UPDATE final_content 失败」场景
```

#### 5.1.2 SentinelPlanRejectionAudit（LINK-INV-1 审计关联）
```sql
-- v34 → 34.sql：
CREATE TABLE sentinel_plan_rejection_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  sentinel_id INTEGER NOT NULL,
  rejected_plan_id INTEGER NOT NULL,
  rejected_at_ms INTEGER NOT NULL,
  rejection_reason_text TEXT NOT NULL,
  FOREIGN KEY(sentinel_id) REFERENCES user_confirmed_sentinel(id) ON DELETE CASCADE,
  FOREIGN KEY(rejected_plan_id) REFERENCES plan_approval_entity(id) ON DELETE CASCADE
);
CREATE INDEX idx_sentinel_plan_rejection_sentinel ON sentinel_plan_rejection_audit(sentinel_id);
CREATE INDEX idx_sentinel_plan_rejection_plan ON sentinel_plan_rejection_audit(rejected_plan_id);
```

#### 5.1.3 HardConstraintDeleteAudit（DEL-2 删除审计 · Trade-off #12）
```sql
-- v35 → 35.sql 新表：
CREATE TABLE hard_constraint_delete_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  sentinel_id INTEGER NOT NULL,
  hard_constraint_id TEXT NOT NULL,
  source_layer TEXT NOT NULL,
  source_anchor_id TEXT NOT NULL,
  severity TEXT NOT NULL,              // HIGH_RISK / LOW_RISK
  user_acknowledged_risk INTEGER NOT NULL,  // 1/0 boolean（High Risk Modal 承认风险）
  deleted_at_ms INTEGER NOT NULL,
  FOREIGN KEY(sentinel_id) REFERENCES user_confirmed_sentinel(id) ON DELETE CASCADE
);
CREATE INDEX idx_hc_delete_sentinel ON hard_constraint_delete_audit(sentinel_id);
```

#### 5.1.4 L0SoftCompactRestoreLogEntity（REV-INV-2）
```sql
-- v35 → 35.sql 新表：
CREATE TABLE l0_soft_compact_restore_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id TEXT NOT NULL,
  message_ids_json TEXT NOT NULL,        // 还原的消息 id 列表（JSON 数组，T2 合并是多个）
  restore_type TEXT NOT NULL,            // RESTORE_FULL_TEXT / RESTORE_MERGED_EXPANDED
  restored_at_ms INTEGER NOT NULL,
  original_compact_type TEXT NOT NULL    // TRUNCATE / MERGE_DEDUP / FILE_LOG_MERGE / OTHER
);
CREATE INDEX idx_l0_restore_session ON l0_soft_compact_restore_log(session_id);
```

#### 5.1.5 HallucinationFuseEntity（LINK-INV-4 写 2 · 跨会话持久化永久锁 + 事件日志）
```sql
-- v35 → 35.sql 新表：
CREATE TABLE hallucination_fuse_entity (
  session_id TEXT PRIMARY KEY NOT NULL,
  permanently_open INTEGER NOT NULL DEFAULT 0,  -- 1 = criticalHits>=2, App 重启后仍然 OPEN
  open_events_log_json TEXT NOT NULL DEFAULT '[]'  -- List<FuseOpenEvent>：时间、触发能力、delta、isCritical
);
```

### 5.2 现有 3 张表升级新增列（v36 → 37.sql），用 ALTER TABLE + DEFAULT 值，不重建表

#### 5.2.1 AgentSessionEntity（升级列 · LINK-INV-4 写 1 + 会话列表 🔴 小红点）
```sql
-- v36 → 37.sql：
ALTER TABLE agent_session ADD COLUMN fuse_score_snapshot INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_session ADD COLUMN fuse_critical_hits INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_session ADD COLUMN fuse_state_enum INTEGER NOT NULL DEFAULT 0;  -- 6 态枚举 0~5
ALTER TABLE agent_session ADD COLUMN last_fuse_opened_at_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_session ADD COLUMN last_fuse_open_reason TEXT;
```

#### 5.2.2 PlanApprovalEntity（升级列 · LINK-INV-1/2/5）
```sql
ALTER TABLE plan_approval_entity ADD COLUMN checkpoint_id INTEGER;        -- LINK-INV-5 溯源 + Plan 生成快照关联
ALTER TABLE plan_approval_entity ADD COLUMN rejected_due_to_fuse_open INTEGER NOT NULL DEFAULT 0;  -- LINK-INV-2 自动拒标记
ALTER TABLE plan_approval_entity ADD COLUMN auto_rejected_reason TEXT;
ALTER TABLE plan_approval_entity ADD COLUMN plan_item_source_refs_json TEXT;  -- LINK-INV-5 每条 Plan Item 来源链 JSON 序列化
```

#### 5.2.3 CheckpointEntity（升级列 · LINK-INV-0/3/4 + REV-INV-2 同步还原状态）
```sql
ALTER TABLE checkpoint_entity ADD COLUMN fuse_score_at_save INTEGER NOT NULL DEFAULT 0;
ALTER TABLE checkpoint_entity ADD COLUMN fuse_critical_hits_at_save INTEGER NOT NULL DEFAULT 0;
ALTER TABLE checkpoint_entity ADD COLUMN checkpoint_has_critical_hit INTEGER NOT NULL DEFAULT 0;  -- LINK-INV-4 ④
ALTER TABLE checkpoint_entity ADD COLUMN checkpoint_type TEXT NOT NULL DEFAULT 'MANUAL_USER_SAVE';  -- L0_PRE/L1_PRE/L2_PRE/L2_POST/FUSE_OPEN/PLAN_APPROVAL...
ALTER TABLE checkpoint_entity ADD COLUMN pending_card_ids_json TEXT;    -- LINK-INV-3 还原时先关 Card
ALTER TABLE checkpoint_entity ADD COLUMN pending_plan_approval_id INTEGER;  -- LINK-INV-3 还原时先关 Plan
ALTER TABLE checkpoint_entity ADD COLUMN restored_l0_message_ids_json TEXT;  -- REV-INV-2 还原快照后同步恢复 L0 [T]→✅ 状态
```

### 5.3 Migration 汇总（AgentDatabase SCHEMA_VERSION = 37）
```kotlin
// AgentDatabase.kt:
@Database(
  entities = [
    /* ... 原有 32 版实体 ... */,
    // ZTH v34 新增 2 张
    UserConfirmedSentinelEntity::class,
    SentinelPlanRejectionAudit::class,
    // ZTH v35 新增 3 张
    HardConstraintDeleteAudit::class,
    L0SoftCompactRestoreLogEntity::class,
    HallucinationFuseEntity::class
  ],
  version = 37,
  exportSchema = true
)
// migrations:
val MIGRATION_33_34 = object : Migration(33, 34) { /* 建 user_confirmed_sentinel + sentinel_plan_rejection_audit */ }
val MIGRATION_34_35 = object : Migration(34, 35) { /* 建 3 张审计表 + HallucinationFuseEntity */ }
val MIGRATION_35_36 = object : Migration(35, 36) { /* 空步占位，保留扩展位（如需 v36 改索引） */ }
val MIGRATION_36_37 = object : Migration(36, 37) { /* 3 张表 ALTER ADD 列共 13 列 */ }
```

---

## 6. DataStore Proto 设计（CompatibilityChainSpecs + ZthParamsSnapshot · 方向 C + 方向 E）

### 6.1 CompatibilityChainSpecs.proto（Per-Provider 存；方向 C 折中方案 C 生效）

```protobuf
syntax = "proto3";
package com.deep.rcode.zth.datastore;
option java_package = "com.deep.rcode.feature.agent.zth.model.datastore";

enum PresetId {
  PRESET_UNSPECIFIED = 0;
  STRICT = 1;
  BALANCED = 2;   // 默认⭐
  LAX = 3;
  MANUAL = 4;
}

enum ResolverTypeProto {
  RT_UNSPECIFIED = 0;
  PRIMARY_LLM = 1;
  DEDICATED_VISION_LLM = 2;
  DEDICATED_COMPACT_LLM = 3;
  PRIMARY_LLM_NON_STREAM = 4;
  PRIMARY_LLM_STREAM = 5;
  TEXT_ONLY_FALLBACK = 6;
  SOFT_COMPACT_ONLY = 7;
}

message ResolverSpecProto {
  ResolverTypeProto type = 1;
  // 仅 MANUAL 档非空
  string force_provider_id = 11;
  string force_model_name = 12;
  string extra_system_prompt_prefix = 13;
}

message ChainSpecProto {
  repeated ResolverSpecProto resolvers = 1;  // 长度 1~2（CSPEC-INV-1 运行时校验+存时校验）
  string display_name_key = 2;
  bool enabled = 3;
}

message CompatibilityChainSpecsProto {
  map<string, ChainSpecProto> specs_by_capability = 1;  // key: "VISION"/"CONTEXT_COMPACTION"/"TOOL_RICH"/"REASONING"/"STREAMING_REALTIME"
  PresetId active_preset = 2;
}
```

> **DataStore 存储粒度（方向 C 拍板）**：key = `chain_specs_provider_${ProviderId}`（Per-Provider 一份）；全局预设切换时，ProviderEditorScreen 对所有已配置 Provider 并行写相同的预设常量 specs（保证所有 Provider 一致），MANUAL 档用户单独编辑的 Provider 其 specs 独立保存（不再随全局预设切覆盖，需要弹覆盖提示）。

### 6.2 ZthParamsSnapshot.proto（30 参数方向 E 总表 + 10 个压缩专属 · 合理区间夹取）

```protobuf
// 每个参数对应方向 E 讨论 + 第二层新增 10 个；全部带范围，写回 DataStore 前先 clamp()（ZthParamRanges.kt）
message ZthParamsSnapshotProto {
  PresetId source_preset = 1;  // 这份参数值来源自哪个预设（MANUAL 档用户保存后 = MANUAL）
  // 组别 1：熔断 5
  int32 cb_half_open_threshold = 11;          // [20, 200]
  int32 cb_open_threshold = 12;               // [40, 500]  must >= cb_half_open (夹取强制联动)
  int32 cb_delta_info = 13;                   // [0, 50]
  int32 cb_delta_standard = 14;               // [10, 100]
  int32 cb_delta_detailed = 15;               // [20, 200]
  // 组别 2：重试退避 4（格式错 retryFormatAttempts=1 写死，不在 proto 里）
  int32 retry_transient_t0_ms = 21;           // [100, 5000]
  int32 retry_transient_t1_ms = 22;           // [200, 10000]
  int32 retry_network_timeout_ms = 23;        // [1000, 60000]
  // 组别 3：执行器 3（execMaxChainDepth=2 写死；execPerCapabilityBudget=1 写死；不在 proto）
  int32 exec_global_budget = 31;              // [1, 5]
  // 组别 4：卡片密度 4
  int32 density_ctx_info_pct = 41;            // [10, 50]
  int32 density_ctx_high_pct = 42;            // [50, 95]
  int32 density_hard_constraint_std_count = 43; // [1, 10]
  int32 density_hard_constraint_detail_count = 44; // [4, 20]
  // 组别 5：UNCLASSIFIED 门控 2（enforce_density=3 写死，不在 proto）
  int32 unclassified_max_hits_per_session = 51; // [1, 5]
  // 组别 6：UID 复用 2
  int32 reuse_negation_keywords_count = 61;   // [1, 3]
  int32 reuse_badge_visible_duration_ms = 62; // [0, 60000]  0=常驻

  // ↓ 第二层 10 个压缩专属参数（方向 E 第 2.4 章新增）
  int32 compact_l0_trigger_pct = 101;         // [50, 90]
  int32 compact_l0_tool_output_min_chars = 102;// [512, 8192]
  int32 compact_l0_min_saving_pct = 103;      // [5, 50]
  int32 compact_l1_trigger_pct = 104;         // [90, 150]
  int32 compact_l1_min_segments = 105;        // [1, 10]
  int32 compact_l1_max_compression_ratio_pct = 106; // [30, 80]
  int32 compact_l1_min_compression_ratio_pct = 107; // [2, 20]
  int32 compact_l2_trigger_pct = 108;         // [120, 200]
  int32 compact_l2_hard_constraint_coverage_alert_pct = 109; // [70, 98]
  int32 compact_l2_extra_unsource_hard_constraint_limit = 110; // [1, 5] → 超过直接 CRITICAL +∞

  // ↓ 位置匹配算法权重（MANUAL 档专属，默认 0.7/0.2/0.1，×100 存储 int）
  int32 gap_match_weight_time_x100 = 201;     // [0, 100]
  int32 gap_match_weight_type_x100 = 202;     // [0, 100]
  int32 gap_match_weight_layer_x100 = 203;    // [0, 100]  → 三者之和强制 == 100（写入前归一化夹取）
}
```

---

## 7. 30 参数总表 + 4 预设包交叉值（方向 E + 第二层 10 个新参）
（完整 30 参数默认值/范围/可调粒度表见原文方向 E；此处仅给 4 预设交叉值总览，代码里写 ZthPresetConstants.PARAMS 常量 map）

| 参数组 | BALANCED⭐默认 | STRICT | LAX | MANUAL 档默认值 = BALANCED |
|---|---|---|---|---|
| 熔断阈值（HALF/OPEN） | 60/120 | 不适用（自动降级关） | 100/200 | 60/120（可改） |
| 熔断分 delta（INFO/STD/DTL） | 10/50/150 | 0/0/0 | 8/40/120 | 10/50/150 |
| 重试 T0/T1（ms） | 500/1500 | 500/1500 | 300/900 | 500/1500 |
| 网络超时（ms） | 30000 | 30000 | 15000 | 30000 |
| execGlobalBudget | 3 | 1 | 5 | 3 |
| 密度判定（InfoPct/HighPct/stdCount/detailCount） | 30/85/2/8 | N/A | 40/90/3/12 | 30/85/2/8 |
| UNCLASSIFIED 最大会话数 | 2 | 0（STRICT 关未知错降级） | 3 | 2 |
| UID 复用（negKeywords/badgeDurationMs） | 1 / 0 常驻 | N/A | 0 / 10000 10 秒 | 1 / 0 |
| 压缩触发阈值 L0/L1/L2（%） | 80/100/150 | 80/不允许/不允许 | 70/90/130 | 80/100/150 |
| L0 工具 minChars | 2048 | 2048 | 2048 | 2048 |
| L0 min 节省 % | 10 | 10 | 10 | 10 |
| L1 段数/压缩比上下界（%） | 2 / 5~50 | — | 2 / 5~50 | 2 / 5~50 |
| L2 覆盖率警告阈值 % / 无来源上限 | 90 / 2 | — | 85 / 3 | 90 / 2 |

> **4 个写死参数（所有档位同值，连 MANUAL 档灰掉不能改，不进 proto 字段）**：
> 1. retryFormatAttempts = 1（格式错强约束重试最多 1 次）
> 2. execMaxChainDepth = 2（幻觉最多 1 跳，H-INV-2）
> 3. execPerCapabilityBudget = 1（同能力最多 1 次自动处理）
> 4. unclassifiedEnforceDensity = 3（UNKNOWN 成功强制 DETAILED，ZTH-0 核心）

---

## 8. UI Composable 分层清单（ProviderEditor 三段 + 熔断仪表盘 + 3 模板卡片 + 压缩 4 扩展区）

### 8.1 ProviderEditorScreen ZTH 三段式区块（替换现有兼容端点策略区块位置，见现有结构）
```
Section 1：ZthPresetSelector.kt
  单选 4 预设包（STRICT / ⭐BALANCED / LAX / MANUAL）+ 每个预设 1 行解释小字 + 切换预设时覆盖提示框
Section 2：ZthCapabilityChainOverviewCard.kt（5 条能力链，行高一致，每行：中文（英文）名 + 开关 + 降级链 1→2 文字示意 + 深度标记 + 熔断分信息 + MANUAL 才显⚙️齿轮）
  ├─ 识图（Vision）✅开 → 主模型 → 专用识图模型 → [纯文本兜底]
  ├─ 上下文压缩（Compaction）✅开 → 专用压缩模型 → [启发式软压缩兜底]
  ├─ 工具调用（Tools）✅开，不自动降级 → 主模型（仅此一个 · 🛡️ZTH）
  ├─ 思考（Reasoning）✅开，不自动降级 → 主模型（仅此一个 · 🛡️ZTH）
  └─ 流式输出（Streaming）✅开 → 流式主模型 → 非流式主模型
Section 3：ZthFuseDashboard.kt（只读仪表盘）
  ├─ 当前会话熔断：🟢 CLOSED | 🟡 HALF_OPEN(62/120) | 🔴 OPEN
  ├─ 累计分 X/120 + 高复杂度摘要 N 次 + 状态文字说明
  ├─ MANUAL 档可见按钮：「重置本会话熔断分」（⚠️ ZTH-0 免责警告：仅用于开发/调试，线上默认隐藏）
  └─ 「📝 查看熔断原因」→ 弹 openEventsLog JSON 人类可读列表
```

### 8.2 MANUAL 档专属两个 BottomSheet
```
ZthResolverEditorSheet.kt（方向 C 齿轮打开）：
  - Resolver #1 类型=PRIMARY（不可删除）+ 额外前缀编辑框
  - Resolver #2 类型下拉 + 手动指定 Provider/Model 展开折叠区 + 前缀编辑框 + 删除按钮（会变链长=1，CSPEC-INV-1 校验 toast）
  - 底部：ZTH 约束说明文字「最多 2 个 Resolver（幻觉 1 跳）；TOOL/REASONING 链长度固定=1（架构红线）」

ZthParamsEditorSheet.kt（方向 E 高级参数微调）：
  - Tab：熔断阈值 / 重试&执行器 / 密度判定 / 压缩专属 / 算法权重
  - 每个 Tab：滑条 + 数值输入框 + 合理区间 [min, max] 说明文字
  - 4 个写死参数：灰掉显示，旁边加 ℹ️ 图标 tooltip「ZTH 架构红线，不可修改」
  - 右上角「↺ 重置为 BALANCED 默认值」
```

### 8.3 ConfirmationCard 3 模板 + 压缩 4 扩展区（方向 F 精确映射）
```
ZthConfirmationCard.kt（根）：
  - density=INFO → ZthCardInfoTemplate（1 行编辑框 + 看原图按钮 + 2 按钮）
  - density=STANDARD → ZthCardStandardTemplate（左右分栏对照 + extractedHardConstraints 列表 + 重跑/手动/确认 三按钮）
  - density=DETAILED 分两路：
      - 非压缩：ZthCardDetailedTemplate + Trade-off #9 0勾选声明项自动加 + 全勾才亮按钮
      - 压缩卡片：ZthCardDetailedTemplate + 4 扩展区按顺序挂：
          ① ZthCardCompressionHeaderWarning（L2 覆盖率警告 + 丢失清单「一键全回」）
          ② ZthCardCompressionDeletionList（删除/摘要清单，行尾「恢复本条 / 取消摘要」按钮 → 触发逆向联动）
          ③ ZthCardCompressionSourceTracker（硬约束来源追踪面板 + 无来源红色背景 + 删除按钮）
          ④ ZthCardCompressionRestoreButtons（4 档还原快捷按钮 + 二次确认）
```

### 8.4 全局 UI 组件
```
ZthFuseOpenGlobalBanner.kt（LINK-INV-2）：
  - WindowManager TYPE_APPLICATION_OVERLAY 悬浮横幅（全局最顶）
  - 无 × 关闭按钮；仅 2 按钮：查看熔断原因 / 开始新会话
  - OPEN_NO_CRITICAL = 黄色 / OPEN_CRITICAL = 红色 / PERMANENT = 红底闪烁动画

ZthL0SoftCompactBadge.kt（L0 触发后的两种形态）：
  - 0-60s：聊天页底部 36dp 黄条（「🗂️ 已执行软压缩 1 次（节省 ≈X tokens · [查看删除内容] [还原]）」）
  - 60s 后：Toolbar 右上角固定 🗂️ 图标（点击展开还原浮窗 + 删除内容历史面板）

ZthRevertCheckpointMenu.kt（聊天页菜单子菜单）：
  - 🗂️ 还原压缩前上下文
      ↳ 还原到最近 L2 压缩前
      ↳ 还原到最近 L1 压缩前
      ↳ 还原到最近 L0 压缩前
      ↳ 还原到本会话最早一份未压缩
  - 每次点二级菜单都会弹二次确认 Modal（是否确认还原 + 熔断分不回退说明）
```

---

## 9. 与现有代码的钩子落点清单（StatefulAgentWorkflow / ModelMetadataService / ContextCompactor / AgentModule DI）

| 现有类/函数 | 钩子类型 | ZTH 模块接入点 | 需要的改动 |
|---|---|---|---|
| [StatefulAgentWorkflow.processUserTurn](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt) | 入口注入 | CapabilityDetector.detect() → 得 RequiredCapability 集合，按集合构造各 DegradationRequest | 函数开头加能力检测；调用 LLM 之前走 DegradationChainExecutor.execute() 而不是直接发（如果对应 capability enabled） |
| StatefulAgentWorkflow.CallLlm try-catch 块（RC64 vision 降级位置） | 异常捕获 | FailureClassifier.classify(e) → 查 ZthDecisionMatrix → 走 10 列动作（重试/跑 Fallback#1/直接出卡片） | 替换原有硬编码 VISION_UNSUPPORTED_HINTS 字符串匹配；统一走矩阵查表；异常捕获后新增 HallucinationClassifier → 卡片 → 熔断加分链路 |
| [ModelMetadataService.applyCompatibilityPolicies](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/data/remote/ModelMetadataService.kt) | 只读利用 | ResolverResolver.resolveResolver(DEDICATED_VISION/COMPACT 类型) 时调用 applyCompatibilityPolicies 过滤能力 | 无需改 ModelMetadataService；在 ResolverResolver 内部复用现有 metadata.supportsVision 结果 + 单模型覆盖齿轮按钮的 OverrideDao（✅ 兼容 RC64 已经做好的 ③④ 能力覆盖） |
| [ContextCompactor](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/agent/domain/workflow/ContextCompactor.kt) | 完整重写或 wrapper | 现有 ContextCompactor 包装成 CompactionPipeline，内部按 L0/L1/L2 单向级联；加上 Checkpoint 保存 + L2 双闸门 + 卡片阻塞等待 | 保留现有对外接口 suspend fun compact() 签名不变（内部改 ZTH 实现），这样 StatefulAgentWorkflow 不需要改 compact() 的调用点 |
| [AgentModule.provideAgentWorkflow](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/di/AgentModule.kt) | Hilt 扩展 | ZthModule.kt 的所有 Provides（CapabilityDetector / DegradationChainExecutor / 3 Compactors / CB / 4 Coordinators）作为 provideStatefulAgentWorkflow 新参数注入 | provideStatefulAgentWorkflow 函数签名新增 7 个参数（或注入 ZthFacade 一个门面类封装所有子系统，减少参数数量推荐做法） |
| [ProviderEditorScreen 兼容端点策略区块](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/component/ProviderEditorScreen.kt) | UI 替换 | 现有 3 条横项（DefaultPolicy/autoDowngrade/ViewImageUnknownGuard）整块替换成 ZTH 三段式 Section 1/2/3 | 读 SettingsViewModel 新增的 chainSpecsFlow / paramsSnapshotFlow / fuseDashboardFlow |
| [SettingsViewModel](file:///workspace/deepcode-R/app/src/main/java/com/deep/rcode/feature/settings/presentation/SettingsViewModel.kt) | StateFlow 扩展 | 新增 4 Flow：compatibilityChainSpecsPerProviderFlow / zthParamsSnapshotFlow / fuseDashboardFlow(sessionId) / presetSelectionFlow + 对应 setter（选择预设 / 保存 Resolver 编辑 / 保存参数编辑 / 重置熔断分） | 注入 CompatibilityChainSpecsRepository（Per-Provider DataStore 读写）+ ZthParamsRepository（参数快照读写）+ HallucinationCircuitBreakerDao |
| LlmMessageBuilder（现有或新建） | 过滤器 MSG-INV-3/4 | 发给主/压缩/降级/专用模型任何 LLM 前统一走 builder：L1 系统元数据过滤；L5 sentinel finalContent inline 到关联 user 消息末尾；如果找不到关联 → 虚拟 role=user 补充消息 | 新增单例 `ZthLlmMessageBuilder.build(rawMessages, targetModelPurpose)` → 所有 LLM 调用都必须用它组装 messages 列表（禁止直接传 raw messages 给 SDK） |

---

## 10. 单测 Checklist（必测 15 不变性 + 边界用例 + 35 决策矩阵行覆盖）

### 10.1 15 ZTH 不变性必测覆盖（断言违反时抛异常）
| 组别 | 必测用例 |
|---|---|
| H-INV（公理衍生 3） | H-INV-1：resolver 原始输出直接作为 tool_call 参数 → 被工具执行器拒绝；H-INV-2：chainSpec 长度=3 → CSPEC-INV-1 拒绝存/执行；H-INV-3：成功走 fallback#1 → assistant 消息带 degradationBadge（没有 → 渲染器断言挂） |
| ZTH-INV（执行器 3 + 4 卡状态机 4） | ZTH-INV-1：hash 变 → Outcome 不能 SUCCESS_NO_TOUCH；ZTH-INV-2：UIDs 未持久化 → 不许出卡片；ZTH-INV-3：确认时必须写 Layer 5 sentinel；CARD-INV-1：除 3 合法确认事件，其它交互不能 resume 主流程；CARD-INV-2：Confirmed 不能回编辑态；CARD-INV-3：编辑锁存 true 后即使文本改回原 hash 仍然 userEdited=true；CARD-INV-4：Cancelled 只能从 4 个合法路径进入 |
| CSPEC-INV（chainSpec 存/执行 3） | CSPEC-INV-1 长度 0/3 → 拒绝；CSPEC-INV-2 TOOL 链长度=2 → 拒绝；CSPEC-INV-3 TEXT_ONLY 不是 last → 自动 swap 并弹 toast |
| MSG-INV（消息层级 5） | MSG-INV-1：UPDATE finalContent → Dao 层抛 SQLiteConstraintException（单测直接调用 UserConfirmedSentinelDao 的 updateFinalContent）；MSG-INV-2 压缩前后 A 堆 hash 相等；MSG-INV-3 LlmMessageBuilder 输出不能有 SYSTEM_METADATA role；MSG-INV-4 L5 sentinel inline 后的消息 role 必须 = user；MSG-INV-5 同 UID 有 sentinel → 不重新调用 VISION resolver（FakeResolver 计数=0）|
| LINK-INV（四方联动 6） | LINK-INV-0：还原 Checkpoint 后 CB.score 必须 ≥ 还原前；LINK-INV-1：Plan Reject 后 Planner 重新生成 prompt 必须对相关 sentinel 加 ⚠️ 前缀；LINK-INV-2：CB OPEN 后横幅 attach + Plan 非 pending + Card 非 pending；LINK-INV-3：还原 Checkpoint 后 pending Card 被 cancel；LINK-INV-4：criticalHits 变 1 后的 4 处持久化字段全正确；LINK-INV-5：Planner 生成的每条 Plan Item factSourceRefs 非空 |

### 10.2 4 子系统边界用例
| 子系统 | 边界用例 |
|---|---|
| 第一层路由 | TOOL_UNSUPPORTED 出 INFO 卡片后取消 → 切预设提示框正确触发；UNCLASSIFIED 连续 3 次 → 第 3 次 TERMINAL（会话≤2门控生效）；UID 复用命中后用户点「重新处理」→ 不跳过 resolver，FakeResolver 计数+1 |
| 第二层压缩 | L0 断言失败（tokens 节省 < 10%）→ 自动升级 L1；L2 闸门 1 结构错 → 直接 OPEN_CRITICAL +∞；L2 丢失高风险禁止句 → 用户没插回就点确认 → Trade-off #11 阻塞 Modal 必须弹（禁止直接过）；DEL-2 删约束后来源消息 hash == L0/L1 后 hash（逆向完全回滚） |
| 四方联动 | criticalHits 变 2 → HallucinationFuseEntity.permanentlyOpen = true；杀进程重启 App 后打开同 session → CB 状态仍然 OPEN_CRITICAL_PERMANENT（LINK-INV-4 持久化生效）；还原 Checkpoint 时如果 pending Plan Approval → 再弹「还原会拒当前计划」Modal；用户取消 → 还原动作中止（消息列表不变） |
| 跨子系统联动 | 场景：L2 确认 → 熔断分 150 ≥120 → OPEN → 立刻横幅 + 如果有 Plan pending 自动拒 Plan；此时点击横幅「开始新会话」→ 新 session CB.score 清零（新会话是新的，熔断是会话级，正确） |

### 10.3 35 ZthDecisionMatrix 决策行全覆盖测试
- 对 ZthDecisionMatrix.rules 每一行（16 FailureSubClass × 至少 2 种边界场景：命中证据链全满足 / 少一条不命中），共 35 条以上用例：
  - 断言标准动作链顺序与 DecisionRule.standardActions 一致（例如 429 必须先 RETRY_T0 再 RETRY_T1）
  - 断言 mustShowCard / defaultCardDensity / delta / outcome / terminalAction 全部匹配
  - 断言 AUTH 3 子类熔断分永远 +0（10/11/12 行）
  - 断言 UNCLASSIFIED 两行（15/16）cardDensity 永远强制 DETAILED，delta=150

---

## 附：本 v1.0 文档后续增补规则
- 新增 Trade-off 讨论 → 本附录补「版本变更摘要」+ 对应数值/策略的修改条目
- 新参数 → 更新 6.2 Proto（字段号递增，不重复，不回收已弃字段号）+ 7 总表 + ZthParamRanges 常量
- 新不变性 → 直接追加到 10.1 必测清单，不允许删除已存在的 15 条（架构红线只能加，不能减）
- DB 新升级 → SCHEMA_VERSION 继续 +1（37→38→…），不跳号；Migration 必须独立写不合并，保证每步可回滚单测

— END OF DOCUMENT v1.0 —
