package com.R.codecore.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.permission.FailureSubClass
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardStateMachine
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardStateMachine.CardAction
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardStateMachine.CardEvent
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardManager
import com.R.codecore.feature.agent.domain.zth.ZthConfirmationCardManager.UserCardChoice
import com.R.codecore.feature.agent.domain.zth.ZthPresetTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

/**
 * ConfirmationCard ViewModel（Hilt VM；单例注入到 AIAgentViewModel）。
 *
 * 薄代理层：只做三件事，不涉及 LINK-INV DB 事务（事务在 Manager）：
 *   1) 持有 pendingConfirmationCard StateFlow（AIChatPanel observe 显示 BottomSheet）
 *   2) 持有状态机 [ZthConfirmationCardStateMachine]，把 UI 事件转 CardEvent
 *   3) 把用户 CONFIRM/REJECT/MODIFY/CANCEL 调 [ZthConfirmationCardManager.commitUserDecision]
 *
 * 不变性：
 *   VM-INV-1：commit 成功后才把 pendingConfirmationCard 置空（失败留着，用户可再试）
 *   VM-INV-2：commit 进行期间 uiState.committing=true；UI 禁止 Swipe/点击（避免重复点）
 *   VM-INV-3：tier 来自 ZthTierRepository（Phase 3.4 写），UI 上默认 BALANCED
 */
@HiltViewModel
class ZthConfirmationCardViewModel @Inject constructor(
    private val manager: ZthConfirmationCardManager
) : ViewModel() {

    private companion object { const val TAG = "ZthConfirmCardVM" }

    data class ConfirmationCardPayload(
        val sessionId: String,
        val chainId: String = UUID.randomUUID().toString(),
        val chainIndex: Int = 1,
        val cardTemplateId: String,
        val triggerSubClass: FailureSubClass,
        val planPlaintext: String,
        val cardPlaintext: String,
        val tier: ZthPresetTier = ZthPresetTier.BALANCED,
        /** ContentReviewer / CapabilityGuard 命中的规则 ids（UI 显示）。 */
        val hitRuleIds: List<String> = emptyList(),
        /** ToolOutputGuard 幻觉置信分 0~1（UI 画色条）。 */
        val hallucinationConfidence: Float = 0f,
        /** 风险文案（FailureClassification.autoRecoveryHint）。 */
        val explanation: String? = null,
        /** 用户修改后的 plan 明文（仅 MODIFY 态非空）。 */
        val modifiedPlanPlaintext: String? = null,
        /** 用户手写拒绝理由（明文）。 */
        var rejectionReasonPlaintext: String? = null,
        /** Swipe 已达到 92% 阈值？（stateMachine SWIPE_VERIFIED 同步）。 */
        var swipeVerified: Boolean = false
    )

    data class UiState(
        val pendingCard: ConfirmationCardPayload? = null,
        val currentState: ZthConfirmationCardStateMachine.CardState = ZthConfirmationCardStateMachine.CardState.IDLE,
        val swipeProgressPct: Float = 0f,
        val confirmButtonEnabled: Boolean = false,
        val committing: Boolean = false,
        val lastError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 状态机：每个 card 一个实例（pendingCard 变化时重建）。 */
    private var stateMachine: ZthConfirmationCardStateMachine? = null

    // ── 对外 API 1：Phase 5 触发挂起卡片 ──────────────────────────────────

    fun triggerCard(payload: ConfirmationCardPayload) {
        val sm = ZthConfirmationCardStateMachine(payload.tier)
        stateMachine = sm
        val r = sm.onEvent(CardEvent.TRIGGER_CARD)
        viewModelScope.launch {
            if (r.action == CardAction.LOAD_SENTINEL_CONTEXT) {
                // 实际 Phase 5 可在这里读 DB sentinel 链；简化直接 SHOW
                val r2 = sm.onEvent(CardEvent.LOAD_CONTEXT_DONE)
                applyUiStateFromTransition(r2, payload.copy())
            } else {
                applyUiStateFromTransition(r, payload.copy())
            }
        }
    }

    // ── 对外 API 2：UI 事件（Swipe / Buttons） ──────────────────────────────

    fun onSwipe(progressPct: Float) {
        val sm = stateMachine ?: return
        val e = when {
            progressPct >= 0.92f -> CardEvent.SWIPE_THRESHOLD_HIT
            progressPct <= 0.001f -> CardEvent.SWIPE_RESET_BELOW
            else -> CardEvent.SWIPE_PROGRESS
        }
        val r = sm.onEvent(e, progressPct)
        val cur = _uiState.value.pendingCard ?: return
        cur.swipeVerified = cur.swipeVerified || progressPct >= 0.92f
        applyUiStateFromTransition(r, cur, overriddenProgress = progressPct.coerceIn(0f, 1f))
    }

    fun onClickConfirm() {
        val sm = stateMachine ?: return
        val r = sm.onEvent(CardEvent.USER_CLICK_CONFIRM)
        val payload = _uiState.value.pendingCard
        applyUiStateFromTransition(r, payload)
        if (r.newState == ZthConfirmationCardStateMachine.CardState.CONFIRMING_TX && r.action == CardAction.START_LINK_TX_4_WRITE) {
            doCommit(
                choice = if (payload?.modifiedPlanPlaintext.isNullOrBlank()) UserCardChoice.CONFIRM else UserCardChoice.MODIFY_AND_CONFIRM,
                payload = payload!!
            )
        }
    }

    fun onClickModifyPlan() {
        val sm = stateMachine ?: return
        val r = sm.onEvent(CardEvent.USER_CLICK_MODIFY_PLAN)
        applyUiStateFromTransition(r, _uiState.value.pendingCard)
    }
    fun onDoneEdit(modifiedPlan: String) {
        val sm = stateMachine ?: return
        val cur = _uiState.value.pendingCard?.copy(modifiedPlanPlaintext = modifiedPlan)
        val r = sm.onEvent(CardEvent.USER_DONE_EDIT)
        applyUiStateFromTransition(r, cur)
    }
    fun onAbortEdit() {
        val sm = stateMachine ?: return
        val r = sm.onEvent(CardEvent.USER_ABORT_EDIT)
        applyUiStateFromTransition(r, _uiState.value.pendingCard)
    }

    fun onClickReject(reason: String? = null) {
        val sm = stateMachine ?: return
        val payload = _uiState.value.pendingCard?.apply { rejectionReasonPlaintext = reason }
        val r = sm.onEvent(CardEvent.USER_CLICK_REJECT)
        applyUiStateFromTransition(r, payload)
        if (r.newState == ZthConfirmationCardStateMachine.CardState.REJECTING_AUDIT && r.action == CardAction.START_REJECT_AUDIT_WRITE) {
            doCommit(UserCardChoice.REJECT, payload!!)
        }
    }

    fun onClickCancel() {
        val sm = stateMachine ?: return
        val r = sm.onEvent(CardEvent.USER_CANCEL_CARD)
        val payload = _uiState.value.pendingCard
        applyUiStateFromTransition(r, payload)
        // tier≥2 的 cancel 仍然会让状态机走 REJECTING_AUDIT → 实际上 REFINE；tier 1 按 refine
        if (r.action == CardAction.WAKE_WORKFLOW_WITH_REFINE) {
            doCommit(UserCardChoice.CANCEL_TIER1_OR_LOWER, payload!!)
        }
    }

    fun onAutoDegradeNextCard(nextPayload: ConfirmationCardPayload) {
        val sm = stateMachine ?: return
        val r = sm.onEvent(CardEvent.AUTO_DEGRADE_TO_CHAIN_2)
        applyUiStateFromTransition(r, nextPayload)
    }

    // ── 内部：LINK-INV 事务提交（Manager） ─────────────────────────────────

    private fun doCommit(choice: UserCardChoice, payload: ConfirmationCardPayload) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(committing = true, lastError = null)
            val req = ZthConfirmationCardManager.CommitRequest(
                sessionId = payload.sessionId,
                chainId = payload.chainId,
                chainIndex = payload.chainIndex,
                cardTemplateId = payload.cardTemplateId,
                triggerSubClass = payload.triggerSubClass,
                planPayloadPlaintext = payload.planPlaintext,
                userTextPlaintext = null,
                cardPayloadPlaintext = payload.cardPlaintext,
                modifiedPlanPlaintext = payload.modifiedPlanPlaintext,
                choice = choice,
                swipeVerified = payload.swipeVerified || choice == UserCardChoice.REJECT || choice == UserCardChoice.CANCEL_TIER1_OR_LOWER,
                rejectionReasonPlaintext = payload.rejectionReasonPlaintext
            )
            val res = manager.commitUserDecision(req)
            if (res.success) {
                // Step 4 commit OK（Manager 内部已 resolve）→ 状态机 SUCCESS_CLOSED + dismiss
                val sm2 = stateMachine
                val rFinal = sm2?.onEvent(CardEvent.LINK_TX_COMMIT_OK)
                if (rFinal != null) applyUiStateFromTransition(rFinal, null)
                FileLogger.i(TAG, "事务成功 sentinelId=${res.sentinelId} choice=$choice")
                _uiState.value = UiState(currentState = ZthConfirmationCardStateMachine.CardState.SUCCESS_CLOSED)
            } else {
                val sm2 = stateMachine
                val rFail = sm2?.onEvent(CardEvent.LINK_TX_COMMIT_FAIL)
                if (rFail != null) applyUiStateFromTransition(rFail, payload)
                _uiState.value = _uiState.value.copy(committing = false, lastError = res.errorMessage)
            }
        }
    }

    // ── 内部：把 TransitionResult → UiState ──────────────────────────────────

    private fun applyUiStateFromTransition(
        r: ZthConfirmationCardStateMachine.TransitionResult,
        payload: ConfirmationCardPayload?,
        overriddenProgress: Float? = null
    ) {
        if (r.dropReason != null) FileLogger.w(TAG, "事件丢弃：${r.dropReason}")
        val confirmEnabled = when (r.action) {
            CardAction.ENABLE_CONFIRM_BUTTON -> true
            CardAction.DISABLE_CONFIRM_BUTTON -> false
            else -> _uiState.value.confirmButtonEnabled
        }
        val pct = overriddenProgress ?: when (r.action) {
            CardAction.ENABLE_CONFIRM_BUTTON -> 0.92f
            CardAction.DISABLE_CONFIRM_BUTTON -> 0f
            else -> _uiState.value.swipeProgressPct
        }
        _uiState.value = _uiState.value.copy(
            pendingCard = payload,
            currentState = r.newState,
            swipeProgressPct = pct.coerceIn(0f, 1f),
            confirmButtonEnabled = confirmEnabled,
            lastError = r.dropReason ?: _uiState.value.lastError
        )
    }
}
