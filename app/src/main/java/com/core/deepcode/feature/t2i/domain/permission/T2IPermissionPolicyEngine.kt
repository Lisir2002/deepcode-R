package com.core.deepcode.feature.t2i.domain.permission

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.settings.data.repository.ZthTierRepository
import com.core.deepcode.feature.t2i.domain.repository.T2IRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 文生图权限策略组合引擎 P1~P6（优先级严格从高到低；任一阶段命中 DENY / ASK 即短路返回，
 * 不再继续评估后续阶段）。与 [com.core.deepcode.feature.agent.domain.permission.ToolPermissionPolicyEngine]
 * 概念对齐，但策略维度换成 T2I 专用的「额度 + 强制确认」，而非 shell 命令段解析。
 *
 * ### 评估顺序（数字越小优先级越高，命中短路）
 * 1. **P1 强制确认开关（全局）**：`t2i_force_confirm=true` → 无论什么参数，永远 ASK 用户二次确认。
 *    对应设置页「每次生成都必须点一下确认才发请求」（家长控制 / 防止模型幻觉反复调用）。
 * 2. **P2 日额度耗尽**：今日 00:00 至今的 `sum(quotaDeductedTokens)` ≥ 全局日上限 → DENY。
 * 3. **P3 会话额度耗尽**：同 sessionId 的 `sum(quotaDeductedTokens)` ≥ 单会话上限 → DENY。
 * 4. **P4 月度供应商额度**：跨会话、跨天的「当月已扣 token + 本次将扣」> 供应商月度总额度 → DENY。
 *    （月度总额度读取自 DataStore `t2i_monthly_quota_tokens`，0 表示不限制）
 * 5. **P5 渐进保护阈值**：当日已成功生成图数 `countSuccessfulImagesSince(dayStart)` ≥ 阈值 N →
 *    接下来的请求从「自动放行」升级为 ASK（渐进提醒用户“今天已经用了不少了”）。
 * 6. **P6 兜底策略**：所有前置阶段都没命中 → 根据 provider.costPerImageTokens 是否超过「单次 token 阈值」
 *    决定 ASK 还是 ALLOW；超过阈值（贵图）→ ASK，否则 → ALLOW。
 *
 * ### 评估结果
 * - [Verdict.ALLOW]：允许，返回将扣的 token 数（= costPerImageTokens）。
 * - [Verdict.ASK]  ：需要用户确认，附原因 + 预览参数。
 * - [Verdict.DENY] ：硬拒绝（额度耗尽 / 开关关闭），附机器可读 [denyCode]（用于 UI 弹对应引导）。
 */
@Singleton
class T2IPermissionPolicyEngine @Inject constructor(
    private val t2iRepository: T2IRepository,
    private val zthTierRepo: ZthTierRepository, // 复用 ZTH 分层存储（免费/Pro/Enterprise 可差异化额度）
) {

    private companion object {
        const val TAG = "T2IPolicy"

        // ══ 默认额度（免费用户；ZTH Tier 为 PRO/ENTERPRISE 时按 tierQuotas 覆盖）══
        /** P2：免费用户日额度（token 数，对应“生成 50 张 1024×1024 标准图”）。 */
        const val DEFAULT_DAILY_QUOTA_TOKENS = 5000
        /** P3：单会话额度（避免模型幻觉在一条会话里打爆额度）。 */
        const val DEFAULT_SESSION_QUOTA_TOKENS = 1500
        /** P5：渐进阈值——免费用户当日成功 20 张后升级为“每次都要确认”。 */
        const val DEFAULT_PROGRESSIVE_THRESHOLD_IMAGES = 20
        /** P6：单次请求 token 阈值，超过算“贵图”需要 ASK（= 5 张标准图的费用）。 */
        const val DEFAULT_SINGLE_EXPENSIVE_TOKENS = 500
    }

    enum class Verdict { ALLOW, ASK, DENY }

    data class EvalResult(
        val verdict: Verdict,
        /** 需要从额度池扣除的 token 数（= P4/P2/P3 汇总池的扣除基准；失败后仓储按此数回加退款）。 */
        val tokensToDeduct: Int,
        val denyCode: String? = null,
        val denyMessage: String? = null,
        /** ASK 的原因摘要（UI 弹确认框时的副标题）。 */
        val askReason: String? = null,
    )

    // ══════════════════════════════════════════════════════════
    // P0 上下文：一次 evaluate() 的入参（便于各阶段读取）
    // ══════════════════════════════════════════════════════════
    data class Request(
        val sessionId: String,
        /** provider.model 的单次生成成本（= T2IProviderModelEntity.costPerImageTokens）。 */
        val costPerImageTokens: Int,
        /** 预期本次请求的图片数量（未来支持 batch 时用；目前固定 1）。 */
        val imageCount: Int = 1,
        /** P1 强制确认开关（从 DataStore `t2i_force_confirm` 读，默认 false）。 */
        val forceConfirm: Boolean = false,
        /** P5 渐进阈值开关（从 DataStore 读；默认 true）。 */
        val progressiveProtection: Boolean = true,
        /** 覆盖额度：0 表示走默认 / tier 额度。 */
        val overrideDailyQuotaTokens: Int = 0,
        val overrideSessionQuotaTokens: Int = 0,
        val overrideMonthlyQuotaTokens: Int = 0,
        val overrideProgressiveThreshold: Int = 0,
        val overrideSingleExpensiveTokens: Int = 0,
    )

    suspend fun evaluate(req: Request): EvalResult {
        val totalCost = req.costPerImageTokens.coerceAtLeast(0) * req.imageCount.coerceAtLeast(1)

        // ── P1 强制确认开关（最高优先级，即使额度全空也先挡）──
        if (req.forceConfirm) {
            FileLogger.i(TAG, "P1 命中：forceConfirm=true → ASK（全局强制确认开关）")
            return EvalResult(
                Verdict.ASK, totalCost,
                askReason = "已开启「每次生成必须确认」安全开关，请确认是否继续生成。"
            )
        }

        val dayStartMs = todayStartMillis()
        val dailyQuota = effectiveDailyQuota(req)
        val sessionQuota = effectiveSessionQuota(req)

        // ── P2 日额度耗尽 ──
        if (dailyQuota > 0) {
            val usedToday = t2iRepository.sumDeductedTokensSince(dayStartMs)
            if (usedToday + totalCost > dailyQuota) {
                val msg = "今日额度已用 $usedToday/$dailyQuota tokens，本次需 $totalCost，超出上限。"
                FileLogger.w(TAG, "P2 命中：日额度耗尽 — $msg")
                return EvalResult(Verdict.DENY, totalCost, denyCode = "DAILY_QUOTA_EXCEEDED", denyMessage = msg)
            }
        }

        // ── P3 会话额度耗尽 ──
        if (sessionQuota > 0) {
            val usedInSession = t2iRepository.sumDeductedTokensForSession(req.sessionId)
            if (usedInSession + totalCost > sessionQuota) {
                val msg = "本会话额度已用 $usedInSession/$sessionQuota tokens，本次需 $totalCost。"
                FileLogger.w(TAG, "P3 命中：会话额度耗尽 — $msg")
                return EvalResult(Verdict.DENY, totalCost, denyCode = "SESSION_QUOTA_EXCEEDED", denyMessage = msg)
            }
        }

        // ── P4 月度供应商额度（简化实现：从 zthTierRepo 获取 tier 月度额度；
        //    因 T2I 是 RC69 新增模块，配额月度计数器 DataStore 未存在时走 no-op（= 不限制），
        //    后续 RC70 可在 T2I 仓储里加 `monthly_used_tokens` DataStore 持久化。）
        val monthlyQuota = effectiveMonthlyQuota(req)
        if (monthlyQuota > 0) {
            // TODO(RC69+): monthlyUsedTokens = 读 DataStore `t2i_monthly_used_tokens_${yyyyMM}`
            //   目前暂时跳过 P4（相当于月度不限），避免 RC69 第一版引入 DataStore key 后又改命名。
            //   仓储层 saveProvider 已经预留字段，后续补一行即可。
        }

        // ── P5 渐进保护阈值 ──
        if (req.progressiveProtection) {
            val threshold = effectiveProgressiveThreshold(req)
            if (threshold > 0) {
                val successCount = t2iRepository.countSuccessfulImagesSince(dayStartMs)
                if (successCount >= threshold) {
                    FileLogger.i(
                        TAG,
                        "P5 命中：当日成功 $successCount/$threshold 张 ≥ 渐进阈值 → ASK（渐进提醒）"
                    )
                    return EvalResult(
                        Verdict.ASK, totalCost,
                        askReason = "今日已成功生成 ${successCount} 张图，达到渐进提醒阈值，确认后继续。"
                    )
                }
            }
        }

        // ── P6 兜底：超过单次“贵图阈值”→ ASK，否则 → ALLOW ──
        val expensiveThreshold = effectiveSingleExpensiveTokens(req)
        return if (expensiveThreshold in 1 until totalCost) {
            FileLogger.i(TAG, "P6 命中：单次成本 $totalCost tokens > 贵图阈值 $expensiveThreshold → ASK")
            EvalResult(
                Verdict.ASK, totalCost,
                askReason = "本次为高成本生成（$totalCost tokens，超过 $expensiveThreshold 阈值），请确认。"
            )
        } else {
            FileLogger.d(TAG, "P6 放行：单次成本 $totalCost tokens < 阈值 $expensiveThreshold → ALLOW")
            EvalResult(Verdict.ALLOW, totalCost)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 额度解析：override > tier > default
    // ══════════════════════════════════════════════════════════
    private suspend fun effectiveDailyQuota(req: Request): Int =
        if (req.overrideDailyQuotaTokens > 0) req.overrideDailyQuotaTokens
        else tierQuotas().daily ?: DEFAULT_DAILY_QUOTA_TOKENS

    private suspend fun effectiveSessionQuota(req: Request): Int =
        if (req.overrideSessionQuotaTokens > 0) req.overrideSessionQuotaTokens
        else tierQuotas().session ?: DEFAULT_SESSION_QUOTA_TOKENS

    private suspend fun effectiveMonthlyQuota(req: Request): Int =
        if (req.overrideMonthlyQuotaTokens > 0) req.overrideMonthlyQuotaTokens
        else tierQuotas().monthly ?: 0 // 默认月度 = 不限制（避免免费用户月度被误伤）

    private suspend fun effectiveProgressiveThreshold(req: Request): Int =
        if (req.overrideProgressiveThreshold > 0) req.overrideProgressiveThreshold
        else tierQuotas().progressiveImages ?: DEFAULT_PROGRESSIVE_THRESHOLD_IMAGES

    private suspend fun effectiveSingleExpensiveTokens(req: Request): Int =
        if (req.overrideSingleExpensiveTokens > 0) req.overrideSingleExpensiveTokens
        else tierQuotas().singleExpensive ?: DEFAULT_SINGLE_EXPENSIVE_TOKENS

    private data class TierQuotas(
        val daily: Int?, val session: Int?, val monthly: Int?,
        val progressiveImages: Int?, val singleExpensive: Int?
    )

    /**
     * 从 ZTH Tier 解析 T2I 专属额度。目前 ZTH 设计为 3 档：
     *   FREE（默认）→ null 回落到 DEFAULT_*
     *   PRO → 更高额度（覆盖免费的 2×~5×）
     *   ENTERPRISE → 不限（daily/session/monthly 都返回 0 = 跳过）
     *
     * RC69 第一版：为避免 T2I 改动反向侵入 ZTH Tier（ZTH 是 agent 安全模块，与 T2I 职责隔离），
     * 这里先全部返回 null（即 FREE 档），等 RC70 UI 添加 Tier 切换后再改。
     */
    private suspend fun tierQuotas(): TierQuotas {
        // val tier = zthTierRepo.getTier().first()
        // when (tier) { ... }
        return TierQuotas(null, null, null, null, null)
    }

    /** 返回「今日 00:00 本地时间」的毫秒时间戳（用于 P2/P5 的“当天”语义）。 */
    private fun todayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
