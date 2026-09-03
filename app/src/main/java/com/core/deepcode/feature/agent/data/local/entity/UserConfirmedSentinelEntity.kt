package com.core.deepcode.feature.agent.data.local.entity

/**
 * ZTH 核心：用户已确认 sentinel 明细（C.4.1/C.4.3/C.4.6 LINK-INV-1 主表）。
 *
 * 每条 sentinel = 用户在 ConfirmationCard 上一次「滑动 SwipeToConfirm」落盘的原子承诺。
 * 由 ZthConfirmationCardViewModel.confirm(CardId, Choice) @Transaction 写本 +
 * HallucinationFuseEntity version 原子 + Checkpoint + SentinelPlanRejectionAudit = 四写事务。
 *
 * 所有含用户输入/原文敏感字段 = s_userTextCiphertext / s_cardPayloadCiphertext
 * 必须经 ZthSensitiveColumnCrypto.encrypt() Keystore AES-GCM 写（C.4.8 最严格模式），
 * Room 永远不存明文。
 */

data class UserConfirmedSentinelEntity(
    
    val id: String,
    /** 所属会话；C.4.6 session 级 fuse 隔离 → 查询过滤此字段。 */
    val sessionId: String,
    /** LINK-INV 四方联动版本号（同 session 下单调递增，乐观锁 compare-and-set）。 */
    val linkageVersion: Long,
    /** 同一条链（同次幻觉触发的 ConfirmationCard 降级链路）共用 chainId。 */
    val chainId: String,
    /** 链内顺序：1=首卡；2=自动降级后卡；3=再次降级兜底卡。 */
    val chainIndex: Int,
    /** 来源卡片模板 id：C.4.1 20 种卡片模板枚举名（存 TEXT）。 */
    val cardTemplateId: String,
    /** FailureSubClass.name（触发此 sentinel 的具体失败子类）。 */
    val triggerSubClass: String,

    // ── 敏感字段（Keystore AES-GCM 加密后存 base64，解密后 JSON 对象）──
    /** 加密后：用户可读「计划执行 X/Y/Z 操作」原 JSON 明文。 */
    val s_planPayloadCiphertext: String,
    /** 加密后：用户手写文本输入（若模板含自由文本）。 */
    val s_userTextCiphertext: String? = null,
    /** 加密后：原始 ConfirmationCard 显示的完整 payload（审计还原用）。 */
    val s_cardPayloadCiphertext: String,

    // ── 用户选择（枚举存 TEXT，无 TypeConverter）──
    /** 用户最终选择：CONFIRM / REJECT / MODIFY_AND_CONFIRM / CANCEL（字符串）。 */
    val userChoice: String,
    /** SwipeToConfirm 是否滑动完成：C.4.8 方案 C 最严格模式要求必须 true 才能是 CONFIRM/MODIFY。 */
    val swipeVerified: Boolean,
    /** 选择后要替换的原计划 JSON（MODIFY_AND_CONFIRM 时非空）；同样加密。 */
    val s_modifiedPlanCiphertext: String? = null,

    /** 过期时间（C.4.3 崩溃恢复过期策略）；-1 = 永不过期（ZTH-0 铁律）。 */
    val expireAtMs: Long = -1L,
    /** C.4.2 全局 FeatureFlag + kill-switch 激活时，能标记「已一键回滚」避免重复生效。 */
    val rollbackFlag: Boolean = false,

    val createdAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun composeId(sessionId: String, chainId: String, chainIndex: Int): String =
            "$sessionId:$chainId:$chainIndex"
    }
}
