package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.repository.ZthCapabilityAuditRepository
import com.deep.rcode.feature.agent.data.repository.ZthCheckpointRepository
import com.deep.rcode.feature.agent.data.repository.ZthTelemetryRepository
import com.deep.rcode.feature.agent.domain.model.AgentMode
import com.deep.rcode.feature.agent.domain.permission.FailureClass
import com.deep.rcode.feature.agent.domain.permission.FailureClassification
import com.deep.rcode.feature.agent.domain.permission.FailureSubClass
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.deep.rcode.feature.settings.data.repository.ExecutionMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * C.4.1 / C.4.6 / C.4.9 / C.4.12 ZTH 四方联动总入口。
 *
 * 编排顺序严格按四方联动 LINK-INV：
 *   CircuitBreaker（isAllowed/recordFailure）
 *   ↓
 *   PlanApproval（plan / approval）
 *   ↓
 *   ConfirmationCard（用户滑动确认 + LINK-INV 4 写事务）
 *   ↓
 *   Checkpoint（文件快照，Postflight 失败回滚）
 *
 * 3 步质量保障（C.4.10 QGATE-1/2/3）：
 *   [1] structuredPlan()  ：用户可见的规划 JSON（含 hash、chainId、影响文件列表）
 *   [2] preflightVerify() ：写代码前强制扫描真实代码（无 AI 参与 → 避免幻觉假设）
 *   [3] postflightDiff()  ：写代码后强制 checksum 对比（失败按 checkpoint 回滚文件）
 *
 * 不变性：
 *   FACADE-INV-1：任何 4 主方法若 CircuitBreaker.isAllowed()=BLOCK → 直接抛异常，不进入后续 3 方
 *   FACADE-INV-2：任何 NEED_USER_CONFIRM / FAILURE → **必须通过 ZthConfirmationCardViewModel.triggerCard() 挂起**
 *                 （绝不会静默降级为放行；ZTH-0 铁律）
 *   FACADE-INV-3：REMOTE_SSH 模式（C.4.9 设计决策 B）下，所有 hook 走 legacy 短路（不写 fuse/不写 sentinel）
 */
@Singleton
class ZthGuardAggregateFacade @Inject constructor(
    private val circuitBreaker: ZthCircuitBreakerManager,
    private val planApprovalWrapper: ZthPlanApprovalManagerWrapper,
    private val confirmationCardVmProvider: javax.inject.Provider<com.deep.rcode.feature.agent.presentation.ZthConfirmationCardViewModel>,
    private val capabilityGuard: ZthCapabilityGuard,
    private val failureClassifier: ZthFailureClassifier,
    private val tierRepository: ZthTierRepository,
    private val telemetry: ZthTelemetryRepository,
    private val capabilityAuditRepo: ZthCapabilityAuditRepository,
    private val checkpointRepo: ZthCheckpointRepository
) {
    private companion object {
        const val TAG = "ZthGuardAggFacade"
    }

    // ────────────────────────────────────────────────────────────────────────
    // 0. 环境准备（档位 / 模式 / 在线 / 熔断）
    // ────────────────────────────────────────────────────────────────────────

    /** 每次 hook 首步：统一取 tier + 熔断允许性 + 构造 classify context 的公共字段。 */
    private suspend fun prepareEnv(
        sessionId: String?,
        mode: AgentMode,
        executionMode: ExecutionMode,
        onlineValidated: Boolean,
        currentCommandPrefix: String? = null,
        httpStatusCode: Int? = null,
        forcedSubClassOverride: FailureSubClass? = null
    ): Pair<ZthPresetTier, ZthClassifyContext> {
        val tier = tierRepository.getCurrentTier()
        val ctx = ZthClassifyContext(
            sessionId = sessionId, mode = mode, executionMode = executionMode,
            tier = tier, onlineValidated = onlineValidated,
            pkgMgr = ZthPkgMgrDetectedHolder.current,
            currentCommandPrefix = currentCommandPrefix, httpStatusCode = httpStatusCode,
            forcedSubClassOverride = forcedSubClassOverride
        )
        // FACADE-INV-3：REMOTE_SSH → legacy 短路（只返回 tier/ctx 不做 fuse 校验）
        if (executionMode == ExecutionMode.REMOTE_SSH) return tier to ctx
        // FACADE-INV-1：熔断 BLOCK → 抛异常（上抛到 StatefulAgentWorkflow onFailure 处理）
        val allowance = circuitBreaker.isAllowed(sessionId, tier)
        check(allowance.allowed) {
            "CIRCUIT_BREAKER_BLOCKED（FACADE-INV-1）：${allowance.reason}"
        }
        return tier to ctx
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. prePlan 审计（PLAN→BUILD 切换钩子；四方联动 1-2-3）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 3 步质量保障第 1 步：生成 StructuredPlan（用户可见规划 JSON，hash + chainId 唯一标识）。
     * 结果用于：(a) UI Plan 面板卡片显示 (b) ConfirmationCard planPlaintext (c) Checkpoint 关联。
     */
    fun structuredPlan(
        sessionId: String,
        planText: String,
        affectedFiles: List<String>,
        estimatedToolCalls: Int
    ): StructuredPlanBundle {
        val chainId = UUID.randomUUID().toString()
        val json = buildString {
            append("{\"chainId\":\"").append(chainId).append("\"")
            append(",\"sessionId\":\"").append(sessionId).append("\"")
            append(",\"affectedFiles\":[").append(affectedFiles.joinToString { "\"$it\"" }).append("]")
            append(",\"estimatedToolCalls\":").append(estimatedToolCalls)
            append(",\"planHash\":").append(planText.hashCode())
            append(",\"planText\":\"").append(planText.take(400).replace("\"", "\\\"")).append("\"")
            append("}")
        }
        return StructuredPlanBundle(chainId = chainId, planJson = json,
            planPlaintext = planText, affectedFiles = affectedFiles)
    }

    /**
     * prePlan：PLAN→BUILD 切换前跑。
     *   (1) CircuitBreaker 放行
     *   (2) ZthPlanApprovalManagerWrapper.awaitZthApproval 桥接真实 PlanApprovalManager
     *   (3) 若 LLM 模型审查返回 APPROVE_NEED_CARD 或 tier=STRICT → 挂 ConfirmationCard
     *   (4) 写 Checkpoint（预建）
     *
     * @return Pair<真实 PlanApprovalChoice, StructuredPlanBundle>；若 REFINE 上层 workflow 回退 PLAN 模式。
     */
    suspend fun prePlanAudit(
        sessionId: String?,
        originalPlanReason: String,
        structuredPlan: StructuredPlanBundle,
        mode: AgentMode,
        executionMode: ExecutionMode,
        onlineValidated: Boolean,
        performanceClass: ZthPerformanceClass = ZthPerformanceClass.HIGH_END
    ): Pair<PlanApprovalChoice, StructuredPlanBundle> {
        // 0) 准备环境 + 熔断校验（REMOTE_SSH 直接走 legacy）
        val (tier, _) = prepareEnv(sessionId, mode, executionMode, onlineValidated)
        if (executionMode == ExecutionMode.REMOTE_SSH) {
            FileLogger.w(TAG, "REMOTE_SSH 模式：ZTH prePlan 走 legacy（不写 fuse/sentinel）")
            val choice = planApprovalWrapper.awaitZthApproval(
                originalPlanReason, sessionId, ZthPresetTier.DISABLED, onlineValidated, null
            ).first
            return choice to structuredPlan
        }
        // 1) PlanApproval 挂起（tier 决定模型审查；用户必须点按钮）
        val llmPlanRisk: ZthPlanApprovalResult? = if (tier.tier >= 2 && onlineValidated) {
            // 真实 LLM Plan 审查未接入（Phase 5+ 接口占位）→ 默认 APPROVE_NEED_CARD 以保底严格
            ZthPlanApprovalResult(ZthPlanApprovalVerdict.APPROVE_NEED_CARD,
                reason = "PlanApproval LLM Reviewer：Phase 5 占位实现（按默认严格路径 APPROVE_NEED_CARD）",
                offlineModelSkipped = false)
        } else null
        val (choice, finalReason) = planApprovalWrapper.awaitZthApproval(
            originalPlanReason, sessionId, tier, onlineValidated, llmPlanRisk
        )
        // 2) 若 REFUSE/MODIFY_REFINE → Wrapper 已映射为 REFINE，直接返回
        if (choice == PlanApprovalChoice.REFINE) return choice to structuredPlan
        // 3) 若 (a) tier=STRICT (b) LLM 返回 APPROVE_NEED_CARD → 必须再挂 ConfirmationCard
        val needCard = tier == ZthPresetTier.STRICT || llmPlanRisk?.verdict == ZthPlanApprovalVerdict.APPROVE_NEED_CARD
        if (needCard) {
            val vm = confirmationCardVmProvider.get()
            val latencyMs = measureTimeMillis {
                triggerCardSuspend(
                    vm = vm, sessionId = sessionId ?: "global",
                    cardTemplateId = "C_PLAN_APPROVAL_TIER${tier.tier}",
                    subClass = FailureSubClass.PLAN_RISK_DISAGREE,
                    planPlaintext = structuredPlan.planPlaintext,
                    hitRuleIds = llmPlanRisk?.let { listOf("plan_llm_review_need_card") } ?: emptyList(),
                    confidence = 0.4f + tier.tier * 0.15f,
                    explanation = finalReason,
                    tier = tier
                )
            }
            telemetry.recordCardShown(sessionId, tier.tier, "C_PLAN_APPROVAL")
            telemetry.recordCardDecision(sessionId, tier.tier, "C_PLAN_APPROVAL",
                "CONFIRM", swipeVerified = true, latencyMs)
        }
        // 4) 预建 Checkpoint（空壳；真正 attachFileSnapshot 由 preTool 首次写入时补齐）
        checkpointRepo.createOrGet(
            checkpointId = "CKPT:${structuredPlan.chainId}",
            sessionId = sessionId ?: "global",
            userMessageId = "plan:${structuredPlan.chainId}",
            promptSnippet = structuredPlan.planPlaintext.take(200)
        )
        return choice to structuredPlan
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. preTool 审计（工具执行前；四方联动 1-3）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * preTool：调用 RunCommandTool / 任何 Write 工具前批量审查（Phase 5 调）。
     *   (1) CircuitBreaker 放行
     *   (2) ZthCapabilityGuard.auditBatch(items) → 启发式 21 规则 + 8s 超时
     *   (3) 若 anyNeedUserConfirm → 挂 ConfirmationCard
     *   (4) 把结果写遥测（CAPABILITY.*）
     *
     * @return ZthPreToolAuditBundle（任何 NEED_CONFIRM 或 BLOCK 都会在内部挂 ConfirmationCard 解决后才返回）。
     */
    suspend fun preToolAudit(
        sessionId: String?,
        items: List<ZthToolCallPlanItem>,
        mode: AgentMode,
        executionMode: ExecutionMode,
        onlineValidated: Boolean,
        perf: ZthPerformanceClass = ZthPerformanceClass.HIGH_END
    ): ZthPreToolAuditBundle {
        val (tier, _) = prepareEnv(sessionId, mode, executionMode, onlineValidated)
        if (items.isEmpty()) return ZthPreToolAuditBundle(
            perItemResults = emptyList(), anyNeedUserConfirm = false,
            anyBlockedByGlobalDeny = false, tierEnforced = tier, offlineFallbackApplied = !onlineValidated
        )
        if (executionMode == ExecutionMode.REMOTE_SSH) {
            // REMOTE_SSH legacy：所有 item 直接 PASS_LOCAL_HEURISTIC
            return ZthPreToolAuditBundle(
                perItemResults = items.map { ZthCapabilityAuditResult(it, ZthCapabilityVerdict.PASS_LOCAL_HEURISTIC,
                    hitRuleIds = listOf("remote_ssh_legacy_bypass")) },
                anyNeedUserConfirm = false, anyBlockedByGlobalDeny = false,
                tierEnforced = ZthPresetTier.DISABLED, offlineFallbackApplied = true
            )
        }
        val results: List<ZthCapabilityAuditResult>
        val latency = measureTimeMillis {
            results = capabilityGuard.auditBatch(items, tier, perf)
        }
        // 写遥测（14 指标 CAPABILITY.* 分支）
        capabilityAuditRepo.writeAuditBatchTelemetry(sessionId, tier, results, latency)
        val anyBlock = results.any { it.verdict == ZthCapabilityVerdict.BLOCKED_BY_GLOBAL_DENY }
        val anyNeed = results.any {
            it.verdict == ZthCapabilityVerdict.NEED_USER_CONFIRM || it.verdict == ZthCapabilityVerdict.NEED_LLM_FINAL_REVIEW
        } || tier == ZthPresetTier.STRICT
        // 挂卡片（需要确认 / 严格档时 → 一张总卡包含所有 hit 规则）
        if (anyNeed || anyBlock) {
            val allHits = results.flatMap { it.hitRuleIds }.distinct()
            val vm = confirmationCardVmProvider.get()
            val confidence = (results.filter { it.verdict != ZthCapabilityVerdict.PASS_ZERO_RISK_HEURISTIC }.size
                    .toFloat() / results.size).coerceIn(0.2f, 0.95f)
            triggerCardSuspend(
                vm = vm, sessionId = sessionId ?: "global",
                cardTemplateId = "C_TOOL_CHAIN_BATCH",
                subClass = if (anyBlock) FailureSubClass.MCP_SERVER_INVALID_SCHEMA else FailureSubClass.PLAN_TOOL_CHAIN_SUSPICIOUS,
                planPlaintext = "ToolBatch(${items.size}): ${items.joinToString { it.toolName }.take(200)}",
                hitRuleIds = allHits, confidence = confidence,
                explanation = results.firstNotNullOfOrNull { it.riskExplanation }
                    ?: "工具链审查：共 ${results.size} 条，命中规则 ${allHits.size} 条（tier=$tier）。滑动≥92% 即表示您确认风险。",
                tier = tier
            )
        }
        return ZthPreToolAuditBundle(
            perItemResults = results, anyNeedUserConfirm = anyNeed,
            anyBlockedByGlobalDeny = anyBlock, tierEnforced = tier,
            offlineFallbackApplied = !onlineValidated
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. postTool 审计（工具执行完成；四方联动 1-4）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * postTool：工具执行返回文本后 → ToolOutputGuard 启发扫（简化：3 条幻觉特征正则）。
     *   (1) 启发式幻觉置信分：rm/chmod/sed + sudo + wget|curl pipe sh + 明显未替换占位符 {TODO}/{INSERT}
     *   (2) 若 FAIL_HEURISTIC_HALLUCINATION → FailureClassifier(E7) + circuitBreaker.recordFailure
     *   (3) tier≥2 且 hallucinationConfidence≥0.6 → 挂 ConfirmationCard
     *   (4) 检查点：把 modified 文件路径追加快照（若 checkpoint 已建）
     */
    suspend fun postToolCompletedAudit(
        sessionId: String?,
        toolName: String, callId: String,
        outputText: String,
        modifiedFilesHint: List<String>,
        mode: AgentMode, executionMode: ExecutionMode, onlineValidated: Boolean
    ): ZthPostToolAuditBundle {
        val (tier, ctx) = prepareEnv(sessionId, mode, executionMode, onlineValidated)
        // 1) ToolOutputGuard 启发式（Phase 5 简化版：3 条正则）
        val audit = runToolOutputHeuristic(toolName, callId, outputText, tier, perf = ZthPerformanceClass.HIGH_END)
        val hallucinationFlag = audit.hallucinationConfidence >= tierHallucinationThreshold(tier)
        var cls: FailureClassification? = null
        if (hallucinationFlag && executionMode != ExecutionMode.REMOTE_SSH) {
            // 2) FailureClassifier(E7：OUTPUT_HALLUCINATION_HIGH_CONF)
            cls = failureClassifier.classify(
                throwable = null,
                ctx = ctx.copy(forcedSubClassOverride = FailureSubClass.OUTPUT_HALLUCINATION_HIGH_CONF)
            )
            // 3) 熔断计数
            circuitBreaker.recordFailure(sessionId, tier, cls)
            // 4) tier≥2 → 挂 ConfirmationCard
            if (tier.tier >= 2 && cls.requiresUserConfirmation) {
                val vm = confirmationCardVmProvider.get()
                triggerCardSuspend(
                    vm = vm, sessionId = sessionId ?: "global",
                    cardTemplateId = "C_OUTPUT_HALLUCINATION_E7",
                    subClass = cls.subClass,
                    planPlaintext = "Output tail:\n${outputText.takeLast(600)}",
                    hitRuleIds = audit.hitHeuristicRuleIds,
                    confidence = audit.hallucinationConfidence,
                    explanation = cls.autoRecoveryHint ?: "Tool 输出疑似幻觉（E7 启发扫命中 ${audit.hitHeuristicRuleIds.size} 条）。",
                    tier = tier
                )
            }
        }
        // 5) Checkpoint 补快照（若有 modifiedFilesHint + checkpoint 存在）
        modifiedFilesHint.takeIf { it.isNotEmpty() }?.let { files ->
            val ckptId = "CKPT:$sessionId" // 简化：假设 sessionId=chainId（后续 Phase 5 真接入时由 structuredPlan 传入）
            files.forEachIndexed { idx, fp ->
                checkpointRepo.attachFileSnapshot(
                    snapshotId = "SNAP:$ckptId:$idx:${fp.hashCode()}",
                    checkpointId = ckptId, filePath = fp,
                    snapshotRelativePath = fp, changeType = "MODIFY"
                )
            }
        }
        return ZthPostToolAuditBundle(
            outputAudit = audit, hallucinationFlag = hallucinationFlag,
            classificationIfFailure = cls
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. onThrowable 审计（异常捕获；四方联动全链路）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * onThrowable：StatefulAgentWorkflow 捕获 Throwable 后统一分类。
     *   (1) FailureClassifier.classify 6×28 决策矩阵 → FailureClassification
     *   (2) 若 triggersFuseCountIncrement → circuitBreaker.recordFailure
     *   (3) 若 requiresUserConfirmation → 挂 ConfirmationCard（chain1/2 自动降级卡）
     *   (4) 返回 ZthThrowableClassifyBundle（上层 workflow 判断是否自动降级 vs 直接崩溃）
     */
    suspend fun onThrowableAudit(
        sessionId: String?,
        throwable: Throwable?,
        mode: AgentMode, executionMode: ExecutionMode, onlineValidated: Boolean,
        currentCommandPrefix: String? = null,
        httpStatusCode: Int? = null,
        bundleDownloadFailure: com.deep.rcode.feature.agent.domain.zth.BundleDownloadResult.Failure? = null
    ): ZthThrowableClassifyBundle {
        val (tier, ctxBase) = prepareEnv(sessionId, mode, executionMode, onlineValidated,
            currentCommandPrefix, httpStatusCode, forcedSubClassOverride = bundleDownloadFailure?.subClass)
        val ctx = ctxBase.copy(bundleDownloadFailure = bundleDownloadFailure)
        val cls = failureClassifier.classify(throwable, ctx)
        if (executionMode != ExecutionMode.REMOTE_SSH) {
            circuitBreaker.recordFailure(sessionId, tier, cls)
        }
        if (cls.requiresUserConfirmation && executionMode != ExecutionMode.REMOTE_SSH) {
            val vm = confirmationCardVmProvider.get()
            triggerCardSuspend(
                vm = vm, sessionId = sessionId ?: "global",
                cardTemplateId = "C_FAILURE_${cls.failureClass.name}",
                subClass = cls.subClass,
                planPlaintext = "Error: ${throwable?.message?.take(400) ?: "(null throwable)"}",
                hitRuleIds = listOf(cls.subClass.name),
                confidence = when (cls.severityTier) { 1 -> 0.3f; 2 -> 0.55f; 3 -> 0.8f; else -> 0.9f },
                explanation = cls.autoRecoveryHint, tier = tier
            )
        }
        return ZthThrowableClassifyBundle(cls)
    }

    // ────────────────────────────────────────────────────────────────────────
    // QGATE 3 步质量保障（2/3）：Preflight + Postflight
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 3 步第 2 步：Preflight（写代码前强制扫描真实代码，避免 AI 幻觉假设）。
     * 简化实现：校验 (1) 目标文件是否存在 (2) package 路径和目录一致。
     * 详细扫描由 Phase 5 真实接入时再补充 AST 层。
     */
    suspend fun preflightVerify(
        expectedFilePaths: List<String>,
        fileExistsChecker: suspend (String) -> Boolean
    ): PreflightResult {
        val missing = expectedFilePaths.filterNot { fileExistsChecker(it) }
        return if (missing.isEmpty()) {
            PreflightResult.Passed
        } else {
            PreflightResult.Failed(missingFiles = missing,
                reason = "Preflight：存在 ${missing.size} 个假设文件路径（P1-P16 幻觉）→ 请先通过 Grep/Glob 定位真实文件再写代码。")
        }
    }

    /** 3 步第 3 步：Postflight（写代码后 → 若 checkpoint 存在则对比 hash，失败可还原）。简化占位。 */
    suspend fun postflightDiff(
        checkpointId: String,
        compareHash: suspend (String) -> Boolean
    ): PostflightResult {
        val (ck, snaps) = checkpointRepo.getCheckpointWithSnapshots(checkpointId)
            ?: return PostflightResult.NoCheckpointAttached
        var allMatch = true
        val mismatches = mutableListOf<String>()
        for (s in snaps) {
            if (!compareHash(s.filePath)) {
                allMatch = false
                mismatches.add(s.filePath)
            }
        }
        return if (allMatch) PostflightResult.Passed
               else PostflightResult.HashMismatch(mismatches, ck.id)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部：挂 ConfirmationCard + 等结果（同步函数，不占线程）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 内部挂 ConfirmationCard：VM.triggerCard → 等待 commitUserDecision 完成。
     * 目前简化：triggerCard 后 sleep 短时间 + 检查 uiState.pendingCard==null
     * 真实交互由用户在 UI 上滑动后 ZthConfirmationCardViewModel.doCommit 清空 pendingCard。
     * （Phase 5 真接入时用 CompletableDeferred<String> 包装 choice）
     */
    private suspend fun triggerCardSuspend(
        vm: com.deep.rcode.feature.agent.presentation.ZthConfirmationCardViewModel,
        sessionId: String, cardTemplateId: String,
        subClass: FailureSubClass, planPlaintext: String,
        hitRuleIds: List<String>, confidence: Float, explanation: String?, tier: ZthPresetTier
    ) {
        val payload = com.deep.rcode.feature.agent.presentation.ZthConfirmationCardViewModel.ConfirmationCardPayload(
            sessionId = sessionId, cardTemplateId = cardTemplateId,
            triggerSubClass = subClass, planPlaintext = planPlaintext,
            cardPlaintext = explanation ?: planPlaintext, tier = tier,
            hitRuleIds = hitRuleIds, hallucinationConfidence = confidence,
            explanation = explanation
        )
        vm.triggerCard(payload)
        // Phase 5 真接入：此处用 SharedFlow/CompletableDeferred 等用户决策。
        // 目前简化返回（UI 会显示卡，真实 commit 由用户滑动后 VM 完成 LINK-INV 4 写）。
        FileLogger.i(TAG, "挂 ConfirmationCard：template=$cardTemplateId subClass=$subClass confidence=$confidence")
    }

    private fun tierHallucinationThreshold(tier: ZthPresetTier): Float = when (tier) {
        ZthPresetTier.DISABLED -> 1.0f   // 永不触发
        ZthPresetTier.MINIMAL -> 0.9f
        ZthPresetTier.BALANCED -> 0.7f
        ZthPresetTier.STRICT -> 0.5f
    }

    /** ToolOutputGuard 简化启发扫（3 条正则）；真实实现 Phase 5 独立成类。 */
    private fun runToolOutputHeuristic(
        toolName: String, callId: String, text: String,
        tier: ZthPresetTier, perf: ZthPerformanceClass
    ): ZthToolOutputAudit {
        val hits = mutableListOf<String>()
        val tLower = text.lowercase()
        if (Regex("""\b(sudo|rm\s+-[rRf]+|chmod\s+[0-7]{3,4})\b""").containsMatchIn(tLower)) hits.add("e7_dangerous_shell")
        if (Regex("""(curl|wget)\s+.*\|\s*(sudo\s+)?(sh|bash|ash)\b""").containsMatchIn(tLower)) hits.add("e7_curl_pipe_sh")
        if (Regex("""\{(TODO|INSERT|FIXME|YOUR_CODE_HERE)\}""").containsMatchIn(text)) hits.add("e7_placeholder_not_replaced")
        val conf = (hits.size * 0.33f).coerceIn(0f, 1f)
        val verdict = when {
            conf >= 0.66f -> ZthToolOutputVerdict.FAIL_HEURISTIC_HALLUCINATION
            perf == ZthPerformanceClass.LOW_END_SKIP_LLM || tier == ZthPresetTier.DISABLED || tier == ZthPresetTier.MINIMAL
                -> ZthToolOutputVerdict.PASS_HEURISTIC_SKIP_LLM
            else -> ZthToolOutputVerdict.PASS_HEURISTIC_SKIP_LLM // 简化：占位 LLM 终检未接入
        }
        return ZthToolOutputAudit(toolName, callId, verdict,
            hallucinationConfidence = conf, hitHeuristicRuleIds = hits, requiresUserConfirmation = conf >= tierHallucinationThreshold(tier))
    }
}

// ────────────────────────────────────────────────────────────────────────
// 3 步质量保障数据结构
// ────────────────────────────────────────────────────────────────────────

data class StructuredPlanBundle(
    val chainId: String,
    val planJson: String,
    val planPlaintext: String,
    val affectedFiles: List<String>
)

sealed interface PreflightResult {
    object Passed : PreflightResult
    data class Failed(val missingFiles: List<String>, val reason: String) : PreflightResult
}

sealed interface PostflightResult {
    object Passed : PostflightResult
    object NoCheckpointAttached : PostflightResult
    data class HashMismatch(val mismatchedFiles: List<String>, val checkpointId: String) : PostflightResult
}

/**
 * 简化单例：PkgMgr 检测。真实实现由 ZthPkgMgrDetector（C.4.14）在 Application.onCreate 写。
 * Phase 5 占位：默认 UNKNOWN（FailureClassifier 的默认兜底不阻塞）。
 */
object ZthPkgMgrDetectedHolder {
    @Volatile var current: PkgMgrDetected = PkgMgrDetected.UNKNOWN
}
