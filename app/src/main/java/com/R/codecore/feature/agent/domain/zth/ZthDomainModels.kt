package com.R.codecore.feature.agent.domain.zth

import com.R.codecore.feature.agent.domain.model.AgentMode
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.R.codecore.feature.agent.domain.permission.FailureClass
import com.R.codecore.feature.agent.domain.permission.FailureClassification
import com.R.codecore.feature.agent.domain.permission.FailureSubClass
import com.R.codecore.feature.settings.data.repository.ExecutionMode

/**
 * Phase 2 纯域模型层：7 子系统之间的入参/出参统一在这里。
 * 禁止 7 子系统互相持有对方具体实现（只能通过接口 + 本层数据类通信），方便 JUnit 隔离单测。
 */

// ────────────────────────────────────────────────────────────────────────────────
// 2.2 ZthCapabilityGuard 输入输出
// ────────────────────────────────────────────────────────────────────────────────

/** 单次工具调用 + 参数（批量审查 20 条一组，BATCH=20，C.4.7）。 */
data class ZthToolCallPlanItem(
    val toolName: String,
    val capabilities: Set<ToolCapability>,
    /** 原始参数 JSON（用于启发扫：curl/wget/chmod/rm/... 字符串特征命中）。 */
    val argsPreviewText: String,
    /** MCP server 标识（若是 MCP 第三方工具）。null = 内置工具。 */
    val mcpServerName: String? = null,
    /** Skill bundle 标识（若是 LoadSkillTool 执行）。null = 非 Skill。 */
    val skillBundleKey: String? = null
)

/**
 * CapabilityGuard 审查结果（批量审查后单条输出）。
 * - PASS_ZERO_RISK_HEURISTIC = 9 条「零风险规则」直接通过，不进后续流程（C.4.7）
 * - PASS_LOCAL_HEURISTIC = 12 条 MCP/Skill 启发式通过，最后一批 LLM 再核
 * - NEED_USER_CONFIRM = 启发扫不通过 → 进 ZTH ConfirmationCard（必须用户确认）
 * - NEED_LLM_FINAL_REVIEW = 需要最后一批 LLM 终检（C.4.7 低端机 skip）
 * - BLOCKED_BY_GLOBAL_DENY = 被 PermissionRulesRepository GLOBAL_DENY 命中（C.4.1）
 */
enum class ZthCapabilityVerdict {
    PASS_ZERO_RISK_HEURISTIC,
    PASS_LOCAL_HEURISTIC,
    PASS_HEURISTIC_SKIP_LLM,
    NEED_USER_CONFIRM,
    NEED_LLM_FINAL_REVIEW,
    BLOCKED_BY_GLOBAL_DENY
}

data class ZthCapabilityAuditResult(
    val item: ZthToolCallPlanItem,
    val verdict: ZthCapabilityVerdict,
    /** 命中的启发式规则 id（调试/埋点用）。对 PASS_ZERO_RISK_HEURISTIC 非空。 */
    val hitRuleIds: List<String> = emptyList(),
    /** 给 ConfirmationCard 看的「风险说明」（若 NEED_USER_CONFIRM）。 */
    val riskExplanation: String? = null,
    /** 8s 超时批量处理时是否因 TIMEOUT 退化为 NEED_USER_CONFIRM（C.4.7 安全兜底）。 */
    val timedOut: Boolean = false
)

/** CapabilityGuard 的 LLM 终检请求（Phase 2.2 里定义为 interface，Phase 5 接入真实 StatefulAgentWorkflow 调用）。 */
fun interface ZthCapabilityLlmReviewer {
    suspend fun reviewBatch(items: List<ZthToolCallPlanItem>): Map<String, ZthCapabilityVerdict> // key=toolName
}

// ────────────────────────────────────────────────────────────────────────────────
// 2.4 PlanApproval
// ────────────────────────────────────────────────────────────────────────────────

/**
 * ZTH 档位 0/1/2/3（C.4.2 FeatureFlag）。
 * - 0 = 关闭 ZTH
 * - 1 = 幻觉触发时才弹卡（最小打扰，ZTH-0）
 * - 2 = 默认：所有 NEED_USER_CONFIRM + 档位 2 LLM 模型审批 Plan
 * - 3 = 最严格：所有工具链调用都弹卡 + LLM 双审 + SwipeToConfirm（C.4.8 安全 C）
 */
enum class ZthPresetTier {
    DISABLED, MINIMAL, BALANCED, STRICT;
    val tier: Int get() = ordinal
    companion object {
        fun fromTier(tier: Int) = entries.firstOrNull { it.tier == tier } ?: BALANCED
    }
}

/** ZthPlanApproval 内部结果（最后桥接回真实 PlanApprovalChoice={APPROVE,REFINE}）。 */
enum class ZthPlanApprovalVerdict {
    /** 批准，且允许直接执行。 */
    APPROVE_DIRECT,
    /** 批准但必须走 ZTH ConfirmationCard 用户滑动确认。 */
    APPROVE_NEED_CARD,
    /** 拒绝（真实桥接 → PlanApprovalChoice.REFINE）。 */
    REFUSE,
    /** 用户要求修改（真实桥接 → PlanApprovalChoice.REFINE + refineReason 注入）。 */
    MODIFY_AND_REQUIRE_REFINE
}

data class ZthPlanApprovalResult(
    val verdict: ZthPlanApprovalVerdict,
    val reason: String? = null,
    /** 若 MODIFY，此字段非空，说明需要 AI 回退到 PLAN 再重新生成 plan。 */
    val refineSuggestion: String? = null,
    /** 是否为离线模式下的「跳过模型审批」路径（C.4.9 不变性）。 */
    val offlineModelSkipped: Boolean = false
)

// ────────────────────────────────────────────────────────────────────────────────
// 2.5 ZthToolOutputGuard
// ────────────────────────────────────────────────────────────────────────────────

/** 两阶段终检 verdict。 */
enum class ZthToolOutputVerdict {
    /** 启发式扫描通过且 LLM 终检 → 通过。 */
    PASS_BOTH,
    /** 启发式通过但低端机 skip_llm_final=true（C.4.7 性能降级）。 */
    PASS_HEURISTIC_SKIP_LLM,
    /** 启发式增量扫命中幻觉特征（失败已直接归类为 E7）。 */
    FAIL_HEURISTIC_HALLUCINATION,
    /** LLM 终检质疑（E6 CONTENT_REVIEW_PLAN_CHALLENGE 扩展）。 */
    FAIL_LLM_REVIEW_CHALLENGE
}

data class ZthToolOutputAudit(
    val toolName: String,
    val callId: String,
    val verdict: ZthToolOutputVerdict,
    /** 幻觉触发分（0.0~1.0；≥tier 阈值时 FAIL_HEURISTIC_HALLUCINATION）。 */
    val hallucinationConfidence: Float = 0f,
    val hitHeuristicRuleIds: List<String> = emptyList(),
    val llmChallengeReason: String? = null,
    /** 最终是否认为需要用户确认（FailureClassifier 后续决策）。 */
    val requiresUserConfirmation: Boolean = verdict == ZthToolOutputVerdict.FAIL_LLM_REVIEW_CHALLENGE
            || verdict == ZthToolOutputVerdict.FAIL_HEURISTIC_HALLUCINATION
)

/** ToolOutputGuard 的 LLM 终检接口（Phase 5 接真实实现）。 */
fun interface ZthToolOutputLlmReviewer {
    suspend fun reviewAggregated(toolName: String, aggregatedText: String): ZthToolOutputVerdict
}

// ────────────────────────────────────────────────────────────────────────────────
// 2.3 FailureClassifier 上下文
// ────────────────────────────────────────────────────────────────────────────────

/** 传入 classify() 的环境上下文（纯值类，不依赖 Room/系统 API；JUnit 易构造）。 */
data class ZthClassifyContext(
    val sessionId: String?,
    val mode: AgentMode,
    val executionMode: ExecutionMode,
    val tier: ZthPresetTier,
    /** C.4.9 离线：ConnectivityWatcher.NET_CAP_VALIDATED=false（JUnit 构造简单传 Boolean）。 */
    val onlineValidated: Boolean,
    /** C.4.14 包管理器检测结果（当前容器/远程 SSH 下的包管理器枚举）。 */
    val pkgMgr: PkgMgrDetected,
    /** 当前执行的 Shell 命令前缀（FAILURE 分类用）。 */
    val currentCommandPrefix: String? = null,
    /** HTTP 状态码（INFRASTRUCTURE_ERROR 分类用）。 */
    val httpStatusCode: Int? = null,
    /** C.4.13 Bundle 下载失败时传非空。 */
    val bundleDownloadFailure: BundleDownloadResult.Failure? = null,
    /**
     * 非空时 subClassFromThrowable 直接返回该值（短路 Throwable/HTTP 细分类逻辑）。
     * 用于 Facade.postToolCompletedAudit：ToolOutputGuard FAIL 时直接把 OUTPUT_HALLUCINATION_HIGH_CONF / PLAN_TOOL_CHAIN_SUSPICIOUS 传进来，
     * 不走「无 throwable+无 http→占位」分支，避免 Facade 再 copy(subClass) 覆盖 decision 生成的 10 列。
     */
    val forcedSubClassOverride: FailureSubClass? = null
)

// ────────────────────────────────────────────────────────────────────────────────
// 2.7 Bundle Mirror & 2.8 Facade 通用封装
// ────────────────────────────────────────────────────────────────────────────────

/** C.4.14 检测到的包管理器（apk/apt/yum/pacman/dnf/zypper/brew/choco）。 */
enum class PkgMgrDetected { APK, APT_GET, APT, YUM, DNF, ZYPPER, PACMAN, BREW, CHOCO, UNKNOWN }

sealed interface BundleDownloadResult {
    data class Success(
        val bundleKey: String,
        val mirror: BundleMirror,
        val bytesTotal: Long,
        val sha256Verified: Boolean,
        val retryCount: Int
    ) : BundleDownloadResult

    data class Failure(
        val bundleKey: String,
        val subClass: FailureSubClass,   // C.4.13 8 细颗粒之一
        val failedMirror: BundleMirror? = null,
        val retryCount: Int = 0,
        val message: String? = null
    ) : BundleDownloadResult
}

/** C.4.13 4 镜像清单。 */
enum class BundleMirror(val stableKey: String, val humanName: String) {
    OFFICIAL("official", "Alpine 官方镜像"),
    ALIYUN("aliyun", "阿里云镜像"),
    TSINGHUA("tsinghua", "清华 TUNA 镜像"),
    USTC("ustc", "中科大镜像");
}

/** Phase 2.8 门面对外暴露的 4 方法返回聚合体（让 Phase 5 StatefulAgentWorkflow 无需拆判断）。 */
data class ZthPreToolAuditBundle(
    val perItemResults: List<ZthCapabilityAuditResult>,
    val anyNeedUserConfirm: Boolean,
    val anyBlockedByGlobalDeny: Boolean,
    val tierEnforced: ZthPresetTier,
    val offlineFallbackApplied: Boolean
)

data class ZthThrowableClassifyBundle(
    val classification: FailureClassification,
    /** true=需要 ConfirmationCard 挂起；否则走自动降级。 */
    val suspendForCard: Boolean = classification.requiresUserConfirmation,
    val autoRecoveryHint: String? = classification.autoRecoveryHint
)

data class ZthPostToolAuditBundle(
    val outputAudit: ZthToolOutputAudit,
    /** 若 hallucinationConfidence ≥ 阈值，Phase 5 必须二次调用 FailureClassifier(E7)。 */
    val hallucinationFlag: Boolean,
    val classificationIfFailure: FailureClassification?
)

// ────────────────────────────────────────────────────────────────────────────────
// 性能降级标志（C.4.7 低端机策略）
// ────────────────────────────────────────────────────────────────────────────────

/** C.4.7 PerformanceClass：低端机自动跳过 LLM 终检 + onTrimMemory 临时禁用 60s。 */
enum class ZthPerformanceClass {
    /** 正常档（≥6GB RAM 且 API ≥30）。 */
    HIGH_END,
    /** 中档（4~6GB）。 */
    MID_RANGE,
    /** 低端（≤4GB 或 API ≤28）→ skip_llm_final=true；只启发扫。 */
    LOW_END_SKIP_LLM
}
