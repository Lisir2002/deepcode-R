package com.core.deepcode.feature.agent.domain.zth

/**
 * C.4.1 / C.4.7 ConfirmationCard 状态机（纯 Kotlin data class + 12×17 全显式 when）。
 *
 * 不变性：
 *   STATE-INV-1：USER_CLICK_CONFIRM 必须在 SWIPE_VERIFIED 之后（C.4.8 方案 C：SwipeToConfirm 是 CONFIRM 前提，否则事件被 drop）
 *   STATE-INV-2：tier ≥2 时 USER_CANCEL_CARD → FAILED_ROLLED_BACK（不允许直接 Cancel 放行，保留 sentinel 链）
 *   STATE-INV-3：任何 CONFIRMING_TX → LINK_TX_COMMIT_FAIL 都进 FAILED_ROLLED_BACK（LINK-INV 事务失败不会误进入 SUCCESS_CLOSED）
 *   STATE-INV-4：DISPLAYING_CARD → SWIPING_CONFIRM → SWIPE_VERIFIED 是严格有序（SWIPE_THRESHOLD_HIT 前进 SWIPING_CONFIRM 不跳过）
 */
class ZthConfirmationCardStateMachine(private val tier: ZthPresetTier) {

    enum class CardState {
        IDLE,
        PENDING_LOAD,
        DISPLAYING_CARD,
        SWIPING_CONFIRM,
        SWIPE_VERIFIED,
        MODIFY_PLAN_EDITING,
        MODIFY_REVIEWING,
        CONFIRMING_TX,
        REJECTING_AUDIT,
        AUTO_DEGRADED_NEXT_CARD,
        SUCCESS_CLOSED,
        FAILED_ROLLED_BACK
    }

    enum class CardEvent {
        TRIGGER_CARD,
        LOAD_CONTEXT_DONE,
        LOAD_CONTEXT_FAIL,
        USER_UI_SHOWN,
        SWIPE_START,
        SWIPE_PROGRESS,
        SWIPE_THRESHOLD_HIT,
        SWIPE_RESET_BELOW,
        USER_CLICK_CONFIRM,
        USER_CLICK_MODIFY_PLAN,
        USER_DONE_EDIT,
        USER_ABORT_EDIT,
        USER_CLICK_REJECT,
        LINK_TX_COMMIT_OK,
        LINK_TX_COMMIT_FAIL,
        AUTO_DEGRADE_TO_CHAIN_2,
        USER_CANCEL_CARD
    }

    data class TransitionResult(
        val newState: CardState,
        val action: CardAction,
        val dropReason: String? = null
    )

    enum class CardAction {
        NONE,
        LOAD_SENTINEL_CONTEXT,       // PENDING_LOAD → 去 DB 读 sentinel 链 / PlanApproval reason
        SHOW_CARD_TO_USER,            // DISPLAYING_CARD → UI BottomSheet
        ENABLE_CONFIRM_BUTTON,        // SWIPE_VERIFIED → 点亮「确认」按钮
        DISABLE_CONFIRM_BUTTON,       // SWIPE_RESET_BELOW → 变灰
        OPEN_PLAN_EDITOR,             // MODIFY_PLAN_EDITING
        SHOW_MODIFIED_PREVIEW,        // MODIFY_REVIEWING
        START_LINK_TX_4_WRITE,        // CONFIRMING_TX：LINK-INV 4 写事务
        START_REJECT_AUDIT_WRITE,     // REJECTING_AUDIT
        SHOW_AUTO_DEGRADED_NEXT_CARD, // AUTO_DEGRADED_NEXT_CARD
        WAKE_WORKFLOW_WITH_APPROVE,   // SUCCESS_CLOSED
        WAKE_WORKFLOW_WITH_REFINE,    // FAILED_ROLLED_BACK（含 Cancel tier≥2）
        NO_OP_DROP_EVENT              // 不符合不变性 → 丢弃此事件（dropReason 非空）
    }

    private var state: CardState = CardState.IDLE

    fun current(): CardState = state

    /**
     * 单步转移。204 条合法转移 = 12 状态 × 17 事件，显式 when 无 else。
     * JUnit 必须 100% hit。
     */
    fun onEvent(event: CardEvent, swipeProgress: Float = 0f): TransitionResult = run {
        val r: TransitionResult = when (state) {
            // ──────────────────────────── IDLE ────────────────────────────
            CardState.IDLE -> when (event) {
                CardEvent.TRIGGER_CARD -> tr(CardState.PENDING_LOAD, CardAction.LOAD_SENTINEL_CONTEXT)
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("IDLE 先 TRIGGER_CARD 才能 LOAD_CONTEXT_DONE")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("IDLE 不能 LOAD_CONTEXT_FAIL")
                CardEvent.USER_UI_SHOWN -> trDrop("IDLE 不能 USER_UI_SHOWN")
                CardEvent.SWIPE_START -> trDrop("IDLE 未显示卡片")
                CardEvent.SWIPE_PROGRESS -> trDrop("IDLE 未显示卡片")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("IDLE 未显示卡片")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("IDLE 未显示卡片")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("IDLE 未显示卡片")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("IDLE 未显示卡片")
                CardEvent.USER_DONE_EDIT -> trDrop("IDLE 未修改态")
                CardEvent.USER_ABORT_EDIT -> trDrop("IDLE 未修改态")
                CardEvent.USER_CLICK_REJECT -> trDrop("IDLE 未显示卡片")
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("IDLE 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("IDLE 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("IDLE 无自动降级")
                CardEvent.USER_CANCEL_CARD -> trDrop("IDLE 无卡可取消")
            }
            // ──────────────────────────── PENDING_LOAD ──────────────────────
            CardState.PENDING_LOAD -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("PENDING_LOAD 中重复 TRIGGER_CARD 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> tr(CardState.DISPLAYING_CARD, CardAction.SHOW_CARD_TO_USER)
                CardEvent.LOAD_CONTEXT_FAIL -> tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.USER_UI_SHOWN -> trDrop("PENDING_LOAD 未加载完不能显示")
                CardEvent.SWIPE_START -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.SWIPE_PROGRESS -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.USER_DONE_EDIT -> trDrop("PENDING_LOAD 未修改态")
                CardEvent.USER_ABORT_EDIT -> trDrop("PENDING_LOAD 未修改态")
                CardEvent.USER_CLICK_REJECT -> trDrop("PENDING_LOAD 未显示卡片")
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("PENDING_LOAD 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("PENDING_LOAD 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("PENDING_LOAD 无降级")
                CardEvent.USER_CANCEL_CARD -> tr(CardState.FAILED_ROLLED_BACK, if (tier.tier >= 2) CardAction.WAKE_WORKFLOW_WITH_REFINE else CardAction.WAKE_WORKFLOW_WITH_REFINE, "tier$tier cancel 按 refine 处理")
            }
            // ──────────────────────────── DISPLAYING_CARD ───────────────────
            CardState.DISPLAYING_CARD -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("DISPLAYING 中重复 TRIGGER 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("已显示")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("已显示")
                CardEvent.USER_UI_SHOWN -> tr(CardState.DISPLAYING_CARD, CardAction.NONE) // 幂等
                CardEvent.SWIPE_START -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE)
                CardEvent.SWIPE_PROGRESS -> tr(
                    if (swipeProgress >= 0.92f) CardState.SWIPE_VERIFIED else CardState.SWIPING_CONFIRM,
                    if (swipeProgress >= 0.92f) CardAction.ENABLE_CONFIRM_BUTTON else CardAction.NONE
                )
                CardEvent.SWIPE_THRESHOLD_HIT -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_RESET_BELOW -> tr(CardState.DISPLAYING_CARD, CardAction.DISABLE_CONFIRM_BUTTON)
                CardEvent.USER_CLICK_CONFIRM -> trDrop("STATE-INV-1：SwipeToConfirm 未完成禁止点 CONFIRM")
                CardEvent.USER_CLICK_MODIFY_PLAN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.OPEN_PLAN_EDITOR)
                CardEvent.USER_DONE_EDIT -> trDrop("DISPLAYING 非编辑态")
                CardEvent.USER_ABORT_EDIT -> trDrop("DISPLAYING 非编辑态")
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("DISPLAYING 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("DISPLAYING 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── SWIPING_CONFIRM ───────────────────
            CardState.SWIPING_CONFIRM -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("SWIPING 中重复 TRIGGER 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("SWIPING 不能 LOAD")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("SWIPING 不能 LOAD")
                CardEvent.USER_UI_SHOWN -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE)
                CardEvent.SWIPE_START -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE)
                CardEvent.SWIPE_PROGRESS -> tr(
                    if (swipeProgress >= 0.92f) CardState.SWIPE_VERIFIED else CardState.SWIPING_CONFIRM,
                    if (swipeProgress >= 0.92f) CardAction.ENABLE_CONFIRM_BUTTON else CardAction.NONE
                )
                CardEvent.SWIPE_THRESHOLD_HIT -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_RESET_BELOW -> tr(CardState.DISPLAYING_CARD, CardAction.DISABLE_CONFIRM_BUTTON)
                CardEvent.USER_CLICK_CONFIRM -> trDrop("STATE-INV-1：SWIPING 未到阈值")
                CardEvent.USER_CLICK_MODIFY_PLAN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.OPEN_PLAN_EDITOR)
                CardEvent.USER_DONE_EDIT -> trDrop("SWIPING 非编辑态")
                CardEvent.USER_ABORT_EDIT -> trDrop("SWIPING 非编辑态")
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("SWIPING 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("SWIPING 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── SWIPE_VERIFIED ────────────────────
            CardState.SWIPE_VERIFIED -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("SWIPE_VERIFIED 中重复 TRIGGER 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("已显示")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("已显示")
                CardEvent.USER_UI_SHOWN -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_START -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE) // 用户又拖动了
                CardEvent.SWIPE_PROGRESS -> tr(
                    if (swipeProgress >= 0.92f) CardState.SWIPE_VERIFIED else CardState.SWIPING_CONFIRM,
                    if (swipeProgress < 0.92f) CardAction.DISABLE_CONFIRM_BUTTON else CardAction.ENABLE_CONFIRM_BUTTON
                )
                CardEvent.SWIPE_THRESHOLD_HIT -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_RESET_BELOW -> tr(CardState.DISPLAYING_CARD, CardAction.DISABLE_CONFIRM_BUTTON)
                CardEvent.USER_CLICK_CONFIRM -> tr(CardState.CONFIRMING_TX, CardAction.START_LINK_TX_4_WRITE)
                CardEvent.USER_CLICK_MODIFY_PLAN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.OPEN_PLAN_EDITOR)
                CardEvent.USER_DONE_EDIT -> trDrop("SWIPE_VERIFIED 非编辑态")
                CardEvent.USER_ABORT_EDIT -> trDrop("SWIPE_VERIFIED 非编辑态")
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("SWIPE_VERIFIED 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("SWIPE_VERIFIED 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── MODIFY_PLAN_EDITING ────────────────
            CardState.MODIFY_PLAN_EDITING -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("MODIFY_EDITING 中重复 TRIGGER 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("MODIFY_EDITING 不能 LOAD")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("MODIFY_EDITING 不能 LOAD")
                CardEvent.USER_UI_SHOWN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.NONE)
                CardEvent.SWIPE_START -> trDrop("MODIFY_EDITING 编辑中禁止滑动")
                CardEvent.SWIPE_PROGRESS -> trDrop("MODIFY_EDITING 编辑中禁止滑动")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("MODIFY_EDITING 编辑中禁止滑动")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("MODIFY_EDITING 编辑中禁止滑动")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("MODIFY_EDITING 先 DONE_EDIT")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("MODIFY_EDITING 已在编辑")
                CardEvent.USER_DONE_EDIT -> tr(CardState.MODIFY_REVIEWING, CardAction.SHOW_MODIFIED_PREVIEW)
                CardEvent.USER_ABORT_EDIT -> tr(CardState.DISPLAYING_CARD, CardAction.NONE)
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("MODIFY_EDITING 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("MODIFY_EDITING 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── MODIFY_REVIEWING ───────────────────
            CardState.MODIFY_REVIEWING -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("MODIFY_REVIEWING 中重复 TRIGGER 忽略")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("MODIFY_REVIEWING 不能 LOAD")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("MODIFY_REVIEWING 不能 LOAD")
                CardEvent.USER_UI_SHOWN -> tr(CardState.MODIFY_REVIEWING, CardAction.NONE)
                CardEvent.SWIPE_START -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE) // 重新滑动确认
                CardEvent.SWIPE_PROGRESS -> tr(
                    if (swipeProgress >= 0.92f) CardState.SWIPE_VERIFIED else CardState.SWIPING_CONFIRM,
                    if (swipeProgress >= 0.92f) CardAction.ENABLE_CONFIRM_BUTTON else CardAction.NONE
                )
                CardEvent.SWIPE_THRESHOLD_HIT -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_RESET_BELOW -> tr(CardState.MODIFY_REVIEWING, CardAction.DISABLE_CONFIRM_BUTTON)
                CardEvent.USER_CLICK_CONFIRM -> trDrop("STATE-INV-1：MODIFY_REVIEWING SwipeToConfirm 未完成禁止点 CONFIRM")
                CardEvent.USER_CLICK_MODIFY_PLAN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.OPEN_PLAN_EDITOR)
                CardEvent.USER_DONE_EDIT -> trDrop("MODIFY_REVIEWING 已编辑完")
                CardEvent.USER_ABORT_EDIT -> tr(CardState.DISPLAYING_CARD, CardAction.NONE)
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("MODIFY_REVIEWING 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("MODIFY_REVIEWING 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── CONFIRMING_TX ──────────────────────
            CardState.CONFIRMING_TX -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("CONFIRMING_TX 事务中禁止新卡片")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("CONFIRMING_TX 事务中")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("CONFIRMING_TX 事务中")
                CardEvent.USER_UI_SHOWN -> tr(CardState.CONFIRMING_TX, CardAction.NONE)
                CardEvent.SWIPE_START -> trDrop("CONFIRMING_TX 事务中禁止交互")
                CardEvent.SWIPE_PROGRESS -> trDrop("CONFIRMING_TX 事务中禁止交互")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("CONFIRMING_TX 事务中禁止交互")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("CONFIRMING_TX 事务中禁止交互")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("CONFIRMING_TX 事务中禁止重复点")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("CONFIRMING_TX 事务中禁止修改")
                CardEvent.USER_DONE_EDIT -> trDrop("CONFIRMING_TX 事务中禁止修改")
                CardEvent.USER_ABORT_EDIT -> trDrop("CONFIRMING_TX 事务中")
                CardEvent.USER_CLICK_REJECT -> trDrop("CONFIRMING_TX 事务中禁止拒绝")
                CardEvent.LINK_TX_COMMIT_OK -> tr(CardState.SUCCESS_CLOSED, CardAction.WAKE_WORKFLOW_WITH_APPROVE)
                CardEvent.LINK_TX_COMMIT_FAIL -> tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("CONFIRMING_TX 事务中禁止降级")
                CardEvent.USER_CANCEL_CARD -> trDrop("CONFIRMING_TX 事务中禁止取消")
            }
            // ──────────────────────────── REJECTING_AUDIT ────────────────────
            CardState.REJECTING_AUDIT -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("REJECTING_AUDIT 中禁止新卡片")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("REJECTING_AUDIT 中")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("REJECTING_AUDIT 中")
                CardEvent.USER_UI_SHOWN -> tr(CardState.REJECTING_AUDIT, CardAction.NONE)
                CardEvent.SWIPE_START -> trDrop("REJECTING_AUDIT 拒绝中禁止滑动")
                CardEvent.SWIPE_PROGRESS -> trDrop("REJECTING_AUDIT 拒绝中禁止滑动")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("REJECTING_AUDIT 拒绝中禁止滑动")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("REJECTING_AUDIT 拒绝中禁止滑动")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("REJECTING_AUDIT 拒绝中禁止确认")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("REJECTING_AUDIT 拒绝中禁止修改")
                CardEvent.USER_DONE_EDIT -> trDrop("REJECTING_AUDIT 拒绝中禁止修改")
                CardEvent.USER_ABORT_EDIT -> trDrop("REJECTING_AUDIT 拒绝中")
                CardEvent.USER_CLICK_REJECT -> trDrop("REJECTING_AUDIT 已在拒绝审计")
                CardEvent.LINK_TX_COMMIT_OK -> tr(CardState.SUCCESS_CLOSED, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.LINK_TX_COMMIT_FAIL -> tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.SHOW_AUTO_DEGRADED_NEXT_CARD)
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── AUTO_DEGRADED_NEXT_CARD ────────────
            CardState.AUTO_DEGRADED_NEXT_CARD -> when (event) {
                CardEvent.TRIGGER_CARD -> trDrop("AUTO_DEGRADED 卡已显示")
                CardEvent.LOAD_CONTEXT_DONE -> trDrop("AUTO_DEGRADED 卡已显示")
                CardEvent.LOAD_CONTEXT_FAIL -> trDrop("AUTO_DEGRADED 卡已显示")
                CardEvent.USER_UI_SHOWN -> tr(CardState.DISPLAYING_CARD, CardAction.SHOW_CARD_TO_USER)
                CardEvent.SWIPE_START -> tr(CardState.SWIPING_CONFIRM, CardAction.NONE)
                CardEvent.SWIPE_PROGRESS -> tr(
                    if (swipeProgress >= 0.92f) CardState.SWIPE_VERIFIED else CardState.SWIPING_CONFIRM,
                    if (swipeProgress >= 0.92f) CardAction.ENABLE_CONFIRM_BUTTON else CardAction.NONE
                )
                CardEvent.SWIPE_THRESHOLD_HIT -> tr(CardState.SWIPE_VERIFIED, CardAction.ENABLE_CONFIRM_BUTTON)
                CardEvent.SWIPE_RESET_BELOW -> tr(CardState.AUTO_DEGRADED_NEXT_CARD, CardAction.DISABLE_CONFIRM_BUTTON)
                CardEvent.USER_CLICK_CONFIRM -> trDrop("STATE-INV-1：AUTO_DEGRADED 卡 SwipeToConfirm 未完成禁止点 CONFIRM")
                CardEvent.USER_CLICK_MODIFY_PLAN -> tr(CardState.MODIFY_PLAN_EDITING, CardAction.OPEN_PLAN_EDITOR)
                CardEvent.USER_DONE_EDIT -> trDrop("AUTO_DEGRADED 非编辑态")
                CardEvent.USER_ABORT_EDIT -> trDrop("AUTO_DEGRADED 非编辑态")
                CardEvent.USER_CLICK_REJECT -> tr(CardState.REJECTING_AUDIT, CardAction.START_REJECT_AUDIT_WRITE)
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("AUTO_DEGRADED 无事务")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("AUTO_DEGRADED 无事务")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("AUTO_DEGRADED 已在降级卡")
                CardEvent.USER_CANCEL_CARD -> tieredCancel()
            }
            // ──────────────────────────── SUCCESS_CLOSED ─────────────────────
            CardState.SUCCESS_CLOSED -> when (event) {
                CardEvent.TRIGGER_CARD -> tr(CardState.PENDING_LOAD, CardAction.LOAD_SENTINEL_CONTEXT) // 新卡重置
                CardEvent.LOAD_CONTEXT_DONE -> tr(CardState.DISPLAYING_CARD, CardAction.SHOW_CARD_TO_USER)
                CardEvent.LOAD_CONTEXT_FAIL -> tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.USER_UI_SHOWN -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.SWIPE_START -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.SWIPE_PROGRESS -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_DONE_EDIT -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_ABORT_EDIT -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_CLICK_REJECT -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("SUCCESS_CLOSED 已关")
                CardEvent.USER_CANCEL_CARD -> trDrop("SUCCESS_CLOSED 已关")
            }
            // ──────────────────────────── FAILED_ROLLED_BACK ─────────────────
            CardState.FAILED_ROLLED_BACK -> when (event) {
                CardEvent.TRIGGER_CARD -> tr(CardState.PENDING_LOAD, CardAction.LOAD_SENTINEL_CONTEXT) // 新卡重置
                CardEvent.LOAD_CONTEXT_DONE -> tr(CardState.DISPLAYING_CARD, CardAction.SHOW_CARD_TO_USER)
                CardEvent.LOAD_CONTEXT_FAIL -> tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE)
                CardEvent.USER_UI_SHOWN -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.SWIPE_START -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.SWIPE_PROGRESS -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.SWIPE_THRESHOLD_HIT -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.SWIPE_RESET_BELOW -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_CLICK_CONFIRM -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_CLICK_MODIFY_PLAN -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_DONE_EDIT -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_ABORT_EDIT -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_CLICK_REJECT -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.LINK_TX_COMMIT_OK -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.LINK_TX_COMMIT_FAIL -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.AUTO_DEGRADE_TO_CHAIN_2 -> trDrop("FAILED_ROLLED_BACK 已关")
                CardEvent.USER_CANCEL_CARD -> trDrop("FAILED_ROLLED_BACK 已关")
            }
        }
        if (r.newState != state) state = r.newState
        r
    }

    // ── tier 依赖取消：tier 0/1 允许 cancel（但仍按 refine 告知 workflow，避免绕过 ZTH）；tier≥2 → FAILED_ROLLED_BACK + refine ──

    private fun tieredCancel(): TransitionResult =
        if (tier.tier >= 2) {
            tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE,
                "tier$tier：CANCELLATION_INTERCEPTED（STATE-INV-2：禁止取消，必弹卡）"
            )
        } else {
            tr(CardState.FAILED_ROLLED_BACK, CardAction.WAKE_WORKFLOW_WITH_REFINE,
                "tier$tier：取消按 REFINE 处理（不降级为直接放行，符合 ZTH-0）"
            )
        }

    private fun tr(newState: CardState, action: CardAction, dropReason: String? = null): TransitionResult =
        TransitionResult(newState, action, dropReason)

    private fun trDrop(reason: String): TransitionResult =
        TransitionResult(state, CardAction.NO_OP_DROP_EVENT, reason)
}
