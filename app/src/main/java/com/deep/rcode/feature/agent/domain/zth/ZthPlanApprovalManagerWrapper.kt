package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.deep.rcode.feature.agent.domain.tool.mode.PlanApprovalManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.2 / C.4.9 PlanApprovalManager 桥接。
 *
 * 逻辑严谨（Preflight P13：**真实 PlanApprovalChoice 只有 {APPROVE, REFINE} 两个值**，
 * 没有我们设计的 MODIFY / REJECT / CANCEL），因此本 Wrapper 提供两条桥接铁律：
 *
 * 桥接映射 BRIDGE-1：
 *   ZTH Verdict              → 真实 PlanApprovalChoice
 *   ───────────────────────────────────────────────────
 *   APPROVE_DIRECT           → APPROVE
 *   APPROVE_NEED_CARD        → APPROVE（但 Phase 5 会在 ZthGuardAggregateFacade
 *                              接收到该值后**再挂一层 ConfirmationCard 挂起**，不通过底层 PlanApprovalManager）
 *   REFUSE                   → REFINE（reason=「ZTH 拒绝：+ $reason」自动写 refineReason 注入到 PlanApprovalRequest.reason）
 *   MODIFY_AND_REQUIRE_REFINE → REFINE（refineSuggestion 追加到 PlanApprovalRequest.reason）
 *
 * 不变性 PLAN-INV-1：档位 0/1（DISABLED / MINIMAL）**跳过模型审批**但**不跳过用户**。
 * 不变性 PLAN-INV-2：**离线模式跳过模型审批**（不连 LLM），但**仍然调真实 PlanApprovalManager.awaitApproval() 挂起等用户点**（C.4.9 铁律）。
 * 不变性 PLAN-INV-3：Wrapper 不会自己创建 PlanApprovalRequest，只包装真实 PlanApprovalManager 的挂起流程（避免重复 UI）。
 */
@Singleton
class ZthPlanApprovalManagerWrapper @Inject constructor(
    private val realManager: PlanApprovalManager
) {
    private companion object {
        const val TAG = "ZthPlanApprovalWrapper"
    }

    /**
     * ZTH 统一入口：根据 (tier, online) 决定是否走模型，然后调真实 PlanApprovalManager 挂起。
     *
     * @param originalPlanReason 原始 SwitchModeTool(PLAN→BUILD) 传过来的 reason
     * @param sessionId 会话 id（传给真实 PlanApprovalManager）
     * @param tier 当前档位
     * @param onlineValidated C.4.9 离线标志
     * @param llmPlanRisk 可选：Phase 5 跑模型审查后（档位 ≥2 且在线时），把 ZthPlanApprovalResult 传进来
     *   若为 null → 表示跳过模型审批（档位 0/1 或离线）。
     * @return 桥接后的真实选择 + 最终给 UI 显示的 reason（已含 ZTH 注入信息）
     */
    suspend fun awaitZthApproval(
        originalPlanReason: String,
        sessionId: String?,
        tier: ZthPresetTier,
        onlineValidated: Boolean,
        llmPlanRisk: ZthPlanApprovalResult? = null,
    ): Pair<PlanApprovalChoice, String> {
        // (1) 决定最终传给 PlanApprovalManager.reason 字段的值
        val finalReason = buildFinalReason(originalPlanReason, tier, onlineValidated, llmPlanRisk)

        // (2) PLAN-INV-1：档位 0/1 跳过模型（llmPlanRisk == null），但仍然必须调真实 awaitApproval() → 用户确认
        //     PLAN-INV-2：离线 onlineValidated=false → 跳过模型（llmPlanRisk == null），仍然调真实挂起用户。
        // (3) 档位 2/3 且 在线 → llmPlanRisk 非 null，先按 ZTH verdict 决定是否短路直接拒绝（不用调真实 APPROVE）
        val verdict = llmPlanRisk?.verdict
        if (verdict == ZthPlanApprovalVerdict.REFUSE) {
            // ZTH 直接拒 → 只把 reason 注入后，仍必须调真实 PlanApprovalManager 让 UI 展示给用户（REFINE）
            FileLogger.i(TAG, "ZTH 直接拒绝 PlanApproval：注入 REFINE。reason 前 80 字 = ${finalReason.take(80)}")
            return realManager.awaitApproval(finalReason, sessionId) to finalReason
        }
        if (verdict == ZthPlanApprovalVerdict.MODIFY_AND_REQUIRE_REFINE) {
            // ZTH 要求 AI 重新细化 → 强制真实 PlanApprovalManager 返回 REFINE（即使点 APPROVE）
            FileLogger.i(TAG, "ZTH 要求 Plan refine：注入 REFINE。refineSuggestion 前 80 字 = ${llmPlanRisk?.refineSuggestion?.take(80)}")
            return PlanApprovalChoice.REFINE to finalReason
        }

        // (4) 其余情况（APPROVE_DIRECT / APPROVE_NEED_CARD / 跳过模型）→ 走真实 PlanApprovalManager
        //     若需要 ConfirmationCard（APPROVE_NEED_CARD）→ Phase 5 ZthGuardAggregateFacade 收到 Pair(APPROVE, reason)
        //     后再跑 CapabilityGuard 卡（不会跳过 ZTH）。
        val choice = realManager.awaitApproval(finalReason, sessionId)
        return choice to finalReason
    }

    private fun buildFinalReason(
        originalPlanReason: String,
        tier: ZthPresetTier,
        online: Boolean,
        llmPlanRisk: ZthPlanApprovalResult?
    ): String = buildString {
        appendLine(originalPlanReason.ifBlank { "(Plan 未传 reason，直接请求切换 BUILD 模式)" })
        appendLine()
        when {
            tier == ZthPresetTier.DISABLED -> appendLine("⚠️ ZTH 档位：关闭（仅 Plan 原生审批，无额外幻觉约束）。")
            !online -> {
                appendLine("📡 离线模式（C.4.9）：LLM 模型审批已自动跳过，**只进行用户手动审批**（不降级为直接放行）。")
                appendLine("    离线 banner（BANNER-INV-1 无关闭按钮）：OFFLINE_PLAN_APPROVAL_MODEL_UNAVAILABLE。")
            }
            tier.tier <= 1 -> {
                appendLine("🔰 ZTH 档位 $tier：LLM 模型审批已自动跳过（只启用幻觉触发时弹卡，ZTH-0）。")
            }
        }
        llmPlanRisk?.let { r ->
            appendLine()
            appendLine("🧠 ZTH LLM 二次 Plan 审查 verdict = ${r.verdict.name}")
            r.reason?.takeIf { it.isNotBlank() }?.let { appendLine("    审查说明：$it") }
            r.refineSuggestion?.takeIf { it.isNotBlank() }?.let { appendLine("    反馈建议（AI 切回 PLAN 时重写的方向）：$it") }
            if (r.offlineModelSkipped) appendLine("    （此值非空说明审查路径走了离线分支跳过模型）")
        }
    }
}
