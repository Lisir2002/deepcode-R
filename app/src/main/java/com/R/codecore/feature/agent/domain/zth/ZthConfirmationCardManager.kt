package com.R.codecore.feature.agent.domain.zth

import com.R.codecore.core.security.ZthSensitiveColumnCrypto
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.HallucinationFuseDao
import com.R.codecore.feature.agent.data.local.dao.SentinelPlanRejectionAuditDao
import com.R.codecore.feature.agent.data.local.dao.UserConfirmedSentinelDao
import com.R.codecore.feature.agent.data.local.entity.HallucinationFuseEntity
import com.R.codecore.feature.agent.data.local.entity.SentinelPlanRejectionAuditEntity
import com.R.codecore.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import com.R.codecore.feature.agent.domain.permission.FailureSubClass
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.R.codecore.feature.agent.domain.tool.mode.PlanApprovalManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * C.4.1 ConfirmationCard 业务 Manager（纯 Kotlin，无 ViewModel/Compose 依赖）。
 *
 * 核心：[commitUserDecision] LINK-INV 4 写事务，顺序严格锁死（LINK-INV-0~6 不变性）：
 *   Step 1: HallucinationFuseDao.casUpdateState 会话级 fuse linkageVersion +1
 *           → CAS 返回 0 = 版本冲突 → 立刻抛异常，Sentinel/Audit/resolve 一个都不写
 *   Step 2: 加密 3 个 s_* 敏感列 → upsert UserConfirmedSentinelEntity（主表）
 *   Step 3: 若 userChoice 属于 REJECT/MODIFY → 加密 s_reason + s_rejectedPlanSnapshot →
 *           upsert SentinelPlanRejectionAuditEntity（外键 sentinelId 已存在）
 *   Step 4: PlanApprovalManager.resolve(APPROVE / REFINE) 唤醒挂起 workflow
 *
 * 不变性：
 *   LINK-INV-1：4 步顺序不可逆；任何中间失败 → 抛异常由 ViewModel 处理 state=FAILED_ROLLED_BACK
 *   LINK-INV-2：SEC-INV-1 必须 swipeVerified=true 才能走 CONFIRM / MODIFY_AND_CONFIRM
 *   LINK-INV-3：Session 级 fuse 的 linkageVersion 单调递增（只增不减）
 *   LINK-INV-4：RejectAudit 必须有 sentinelId 外键（不能比 Sentinel 先写）
 *   LINK-INV-5：所有 4 步必须在 Dispatchers.IO 内（Room + Keystore 都是 IO 阻塞）
 *   LINK-INV-6：ZthSensitiveColumnCrypto.ensureReady() 首次调用必须提前在 Application.onCreate 完成
 */
@Singleton
class ZthConfirmationCardManager @Inject constructor(
    private val fuseDao: HallucinationFuseDao,
    private val sentinelDao: UserConfirmedSentinelDao,
    private val rejectionAuditDao: SentinelPlanRejectionAuditDao,
    private val planApprovalManager: PlanApprovalManager,
    private val crypto: ZthSensitiveColumnCrypto
) {
    private companion object {
        const val TAG = "ZthConfirmationCardMgr"
    }

    enum class UserCardChoice {
        /** 用户滑完 + 点确认（无修改）→ PlanApproval APPROVE。 */
        CONFIRM,
        /** 用户在编辑器里改计划后再滑完 + 点「用修改后的版本执行」→ PlanApproval REFINE（实际用修改版跑）。 */
        MODIFY_AND_CONFIRM,
        /** 用户直接点「拒绝/我不认同 ZTH 的幻觉识别」→ PlanApproval REFINE（弹回 PLAN 模式重写）。 */
        REJECT,
        /** 用户取消（tier 0/1 允许；tier≥2 进 FAILED_ROLLED_BACK 且最终仍按 REFINE）。 */
        CANCEL_TIER1_OR_LOWER
    }

    data class CommitRequest(
        val sessionId: String,
        val chainId: String,
        val chainIndex: Int,
        val cardTemplateId: String,
        val triggerSubClass: FailureSubClass,
        /** 原文计划 JSON（明文，内部加密后存）。 */
        val planPayloadPlaintext: String,
        /** 用户手写自由文本（可为空字符串）。 */
        val userTextPlaintext: String? = null,
        /** 卡片 UI payload 完整快照（明文 JSON，内部加密后存作审计用）。 */
        val cardPayloadPlaintext: String,
        /** MODIFY 时：修改后的计划 JSON（明文；其他 choice 传 null）。 */
        val modifiedPlanPlaintext: String? = null,
        val choice: UserCardChoice,
        /** SwipeToConfirm 完成？CONFIRM / MODIFY 必须 true；REJECT/CANCEL 可 false。 */
        val swipeVerified: Boolean,
        /** 拒绝/修改时的手写理由（明文，内部加密后存 s_reason）。 */
        val rejectionReasonPlaintext: String? = null
    )

    data class CommitResult(
        val success: Boolean,
        val planApprovalChoice: PlanApprovalChoice,
        val sentinelId: String? = null,
        val errorMessage: String? = null
    )

    /**
     * LINK-INV 4 写事务主入口（suspend；ViewModel 调用）。
     */
    suspend fun commitUserDecision(req: CommitRequest): CommitResult = withContext(Dispatchers.IO) {
        runCatching {
            // SEC-INV-1：CONFIRM / MODIFY 必须 swipeVerified=true
            when (req.choice) {
                UserCardChoice.CONFIRM,
                UserCardChoice.MODIFY_AND_CONFIRM -> check(req.swipeVerified) {
                    "SWIPE-NOT-VERIFIED（SEC-INV-1 违规）：choice=${req.choice} swipeVerified=false，不允许 commit。"
                }
                else -> {}
            }

            val sessionFuseId = HallucinationFuseEntity.composeSessionId(req.sessionId)
            val nowMs = System.currentTimeMillis()
            // Step 1：LINK-INV CAS 会话级 fuse linkageVersion + 1（确保整个 4 写只有一个赢家）
            val version = fuseDao.getVersion(sessionFuseId) ?: run {
                // Session 级 fuse 还没创建 → 先插（linkageVersion=0）
                fuseDao.upsert(
                    HallucinationFuseEntity(
                        id = sessionFuseId,
                        scope = "SESSION",
                        scopeId = req.sessionId,
                        state = com.R.codecore.feature.agent.domain.permission.FuseState.CLOSED.name,
                        linkageVersion = 0L
                    )
                )
                0L
            }
            val rows = fuseDao.casUpdateState(
                id = sessionFuseId,
                expectedVersion = version,
                newState = com.R.codecore.feature.agent.domain.permission.FuseState.CLOSED.name,
                nowMs = nowMs
            )
            check(rows == 1) { "LINK-INV CAS fuse 失败：预期 version=$version rows=0（并发冲突），请重试。" }

            // Step 2：加密 sentinel 3 列 + 可选 modifiedPlan
            crypto.assertSensitiveColumnName("s_planPayloadCiphertext")
            val s_plan = crypto.encrypt(req.planPayloadPlaintext)
            val s_user = req.userTextPlaintext?.let { crypto.encrypt(it) }
            crypto.assertSensitiveColumnName("s_cardPayloadCiphertext")
            val s_card = crypto.encrypt(req.cardPayloadPlaintext)
            val s_mod = req.modifiedPlanPlaintext?.let { crypto.encrypt(it) }

            val sentinelId = UserConfirmedSentinelEntity.composeId(req.sessionId, req.chainId, req.chainIndex)
            val userChoiceStr = when (req.choice) {
                UserCardChoice.CONFIRM -> "CONFIRM"
                UserCardChoice.MODIFY_AND_CONFIRM -> "MODIFY_AND_CONFIRM"
                UserCardChoice.REJECT -> "REJECT"
                UserCardChoice.CANCEL_TIER1_OR_LOWER -> "CANCEL"
            }
            val sentinel = UserConfirmedSentinelEntity(
                id = sentinelId,
                sessionId = req.sessionId,
                linkageVersion = version + 1,
                chainId = req.chainId,
                chainIndex = req.chainIndex,
                cardTemplateId = req.cardTemplateId,
                triggerSubClass = req.triggerSubClass.name,
                s_planPayloadCiphertext = s_plan,
                s_userTextCiphertext = s_user,
                s_cardPayloadCiphertext = s_card,
                userChoice = userChoiceStr,
                swipeVerified = req.swipeVerified,
                s_modifiedPlanCiphertext = s_mod,
                createdAtMs = nowMs
            )
            sentinelDao.upsert(sentinel)
            FileLogger.i(TAG, "Sentinel 写入 id=$sentinelId choice=$userChoiceStr subClass=${req.triggerSubClass.name}")

            // Step 3：Reject / Modify → 写审计（LINK-INV-4 sentinelId 已存在）
            if (req.choice == UserCardChoice.REJECT || req.choice == UserCardChoice.MODIFY_AND_CONFIRM) {
                crypto.assertSensitiveColumnName("s_reasonCiphertext")
                val s_reason = req.rejectionReasonPlaintext?.let { crypto.encrypt(it) }
                crypto.assertSensitiveColumnName("s_rejectedPlanSnapshotCiphertext")
                val s_snapshot = s_mod ?: crypto.encrypt(req.planPayloadPlaintext) // 没修改则存原
                val audit = SentinelPlanRejectionAuditEntity(
                    id = "AUD:${UUID.randomUUID()}",
                    sentinelId = sentinelId,
                    rejectionType = userChoiceStr,
                    s_reasonCiphertext = s_reason,
                    s_rejectedPlanSnapshotCiphertext = s_snapshot
                )
                rejectionAuditDao.upsert(audit)
                FileLogger.i(TAG, "RejectAudit 写入 sentinelId=$sentinelId type=$userChoiceStr")
            }

            // Step 4：唤醒 PlanApproval（LINK-INV 最后一步，DB 写成功后才让 workflow 继续）
            val resolveTo = when (req.choice) {
                UserCardChoice.CONFIRM -> PlanApprovalChoice.APPROVE
                UserCardChoice.MODIFY_AND_CONFIRM,
                UserCardChoice.REJECT,
                UserCardChoice.CANCEL_TIER1_OR_LOWER -> PlanApprovalChoice.REFINE
            }
            planApprovalManager.resolve(resolveTo)
            FileLogger.i(TAG, "PlanApproval.resolve=$resolveTo 已唤醒 (sentinel=$sentinelId)")
            CommitResult(true, resolveTo, sentinelId)
        }.getOrElse { t ->
            FileLogger.e(TAG, "LINK-INV 4 写事务失败：${t.message}", t)
            // 失败 → 按 REFINE 唤醒 workflow（ZTH-0：绝不会静默放行）
            runCatching { planApprovalManager.resolve(PlanApprovalChoice.REFINE) }
            CommitResult(false, PlanApprovalChoice.REFINE, errorMessage = t.message)
        }
    }
}
