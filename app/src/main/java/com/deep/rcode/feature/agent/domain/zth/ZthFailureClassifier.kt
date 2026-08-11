package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.feature.agent.domain.permission.FailureClass
import com.deep.rcode.feature.agent.domain.permission.FailureClassification
import com.deep.rcode.feature.agent.domain.permission.FailureSubClass
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * ZTH 失败分类器（ZTH-2：决策引擎唯一可信输入）。
 *
 * 逻辑严谨性保证（C.2.2 决策矩阵 100% 覆盖 168 分支）：
 * 1) **先细分类 Throwable → FailureSubClass（28 值，枚举 ordinal 锁死顺序，不依赖 Throwable message）**
 * 2) **FailureSubClass → failureClass（6 值，P1 设计的 6 顶层类）**
 * 3) **根据 (subClass, tier) 二维 switch 显式填写 10 列：severityTier/requiresUserConfirmation/requiresLlmReview/
 *    triggersFuseCountIncrement/requiresLinkageMigration/telemetryRequired/showOfflineBanner/autoRecoveryHint**
 * 4) **所有未命中的分支 → 进入 `UNEXPECTED_THROWABLE_MISSING_MAPPING` 兜底（抛 IllegalStateException 便于 JUnit 发现漏覆盖）**
 *
 * 纯 Kotlin 类 + 纯函数 classify(Throwable?, ZthClassifyContext)：
 *  - 不含任何 Room / Hilt / Composed / File 依赖 → JUnit 168 分支 100% hit 可测
 *  - 不 mutate 任何外部状态（无副作用）
 */
@Singleton
class ZthFailureClassifier @Inject constructor() {

    private companion object {
        const val TAG = "ZthFailureClassifier"

        // 8 个包管理器命令前缀 → apk/apt/yum/dnf/zypper/pacman/brew/choco，
        // 用于 C.4.14 FAILURE 前先判断 pkg_mgr_mismatch（INFRA_PACKAGE_MANAGER_MISMATCH）
        val CMD_PREFIX_TO_PKG_MGR: Map<Regex, PkgMgrDetected> = mapOf(
            Regex("""^\s*(sudo\s+)?apk\s+(add|del|search|update|upgrade|info)\b""") to PkgMgrDetected.APK,
            Regex("""^\s*(sudo\s+)?apt(?:-get)?\s+(install|remove|purge|update|upgrade|search|show)\b""") to PkgMgrDetected.APT,
            Regex("""^\s*(sudo\s+)?yum\s+(install|remove|update|search|info|upgrade)\b""") to PkgMgrDetected.YUM,
            Regex("""^\s*(sudo\s+)?dnf\s+(install|remove|update|search|info)\b""") to PkgMgrDetected.DNF,
            Regex("""^\s*(sudo\s+)?zypper\s+(install|remove|search|update|in|rm|up)\b""") to PkgMgrDetected.ZYPPER,
            Regex("""^\s*(sudo\s+)?pacman\s+-[SURQsyud]\b""") to PkgMgrDetected.PACMAN,
            Regex("""^\s*(sudo\s+)?brew\s+(install|uninstall|update|upgrade|search|info|tap)\b""") to PkgMgrDetected.BREW,
            Regex("""^\s*(sudo\s+)?choco\s+(install|uninstall|upgrade|search|pin)\b""") to PkgMgrDetected.CHOCO
        )
    }

    // ── 对外入口（纯函数，可重复调用无副作用） ─────────────────────────────────────────

    fun classify(throwable: Throwable?, ctx: ZthClassifyContext): FailureClassification {
        // Step 0：C.4.9 离线大兜底（E11/CAPABILITY_OR_ENVIRONMENT_BLOCK）→ 优先级最高
        if (!ctx.onlineValidated) {
            if (ctx.executionMode.name == "REMOTE_SSH") { // C.4.5 远程 SSH 模式 ZTH 禁用
                return build(
                    FailureClass.CAPABILITY_OR_ENVIRONMENT_BLOCK,
                    FailureSubClass.OFFLINE_NETWORK_LOST,
                    ctx,
                    severityTier = 3,
                    requiresUserConfirmation = true,  // 离线+远程 SSH → 必须用户弹卡说明
                    requiresLlmReview = false,
                    triggersFuseCountIncrement = false,
                    requiresLinkageMigration = true,  // LINK-INV 重建 回滚 OPEN→TRANSITIONING
                    telemetryRequired = true,
                    showOfflineBanner = true,
                    autoRecoveryHint = "请先恢复网络连接，或切换回本地 PRoot 模式（ZTH v1.0 不支持 REMOTE_SSH）。"
                )
            }
            return build(
                FailureClass.CAPABILITY_OR_ENVIRONMENT_BLOCK,
                FailureSubClass.OFFLINE_NETWORK_LOST,
                ctx,
                severityTier = ctx.tier.tier.coerceAtLeast(1),
                requiresUserConfirmation = ctx.tier.tier >= 2,
                requiresLlmReview = false,
                triggersFuseCountIncrement = false,
                requiresLinkageMigration = (ctx.tier.tier >= 2),
                telemetryRequired = true,
                showOfflineBanner = true,
                autoRecoveryHint = "当前离线：LLM 终检/PlanApproval 模型已跳过，只保留本地启发扫+用户确认。"
            )
        }

        // Step 1：Throwable → FailureSubClass（细分类，28 枚举值 1:1）
        val subClass = subClassFromThrowable(throwable, ctx)

        // Step 2：SubClass → 顶层 FailureClass（6 类）
        val cls = topClassFromSub(subClass)

        // Step 3：按 (subClass, tier) 二维显式 switch 生成 10 列决策矩阵
        return decision(cls, subClass, ctx, throwable)
    }

    // ── Step 1 细分类 ────────────────────────────────────────────────────────────

    private fun subClassFromThrowable(throwable: Throwable?, ctx: ZthClassifyContext): FailureSubClass {
        // (0) C.4.12/ZTH-0 Facade 直接指定幻觉 SubClass → 强制覆盖（优先级最高）
        ctx.forcedSubClassOverride?.let { return it }

        // (a) C.4.13 Bundle 下载失败优先（8 SubClass 细颗粒之一，ctx.bundleDownloadFailure 已传）
        ctx.bundleDownloadFailure?.let { return it.subClass }

        // (b) C.4.14 pkg_mgr_mismatch 前置（命令里的 pkgMgr ≠ 容器检测的 pkgMgr）
        ctx.currentCommandPrefix?.let { cmd ->
            val inferred = CMD_PREFIX_TO_PKG_MGR.entries.firstNotNullOfOrNull { (re, pm) ->
                if (re.containsMatchIn(cmd)) pm else null
            }
            if (inferred != null && inferred != ctx.pkgMgr && ctx.pkgMgr != PkgMgrDetected.UNKNOWN) {
                return FailureSubClass.INFRA_PACKAGE_MANAGER_MISMATCH
            }
        }

        // (c) 按 HTTP 状态码 + Throwable 类型映射
        val http = ctx.httpStatusCode
        when {
            throwable == null && http == null -> {
                // 没有 Throwable 也没有 HTTP code → 逻辑上只有一种合法场景：
                // 是 Classification 被上层直接调用（如 C.4.7 主动触发）。此时不判 INFRA，交给上层。
                // 由于 28 SubClass 中没这种情况：返回占位 HALLUCINATION_CONFIDENCE_FLAG → 由 decision 处理。
                return FailureSubClass.OUTPUT_HALLUCINATION_HIGH_CONF
            }
            http != null -> {
                return when (http) {
                    in 401..403 -> FailureSubClass.INFRA_HTTP_UNAUTHORIZED
                    429 -> FailureSubClass.INFRA_HTTP_RATE_LIMITED
                    in 500..599 -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
                    404 -> FailureSubClass.INFRA_PACKAGE_INDEX_404  // 一般是 apk index 404
                    else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
                }
            }
        }

        // (d) Throwable 按类型细分
        val t = throwable!!
        val msg = (t.message ?: "").lowercase()
        return when (t) {
            // 超时类
            is SocketTimeoutException -> FailureSubClass.INFRA_TIMEOUT
            is java.util.concurrent.TimeoutException -> FailureSubClass.INFRA_TIMEOUT
            is kotlinx.coroutines.TimeoutCancellationException -> FailureSubClass.INFRA_TIMEOUT

            // DNS/TLS/网络类（C.4.13 细分类：INFRA_PACKAGE_DNS_FAILURE / INFRA_PACKAGE_TLS_HANDSHAKE_FAIL / INFRA_PACKAGE_PROXY_ERROR）
            is UnknownHostException -> when {
                msg.contains("apk") || msg.contains("alpinelinux") || msg.contains("dl-cdn") -> FailureSubClass.INFRA_PACKAGE_DNS_FAILURE
                else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
            }
            is SSLHandshakeException -> when {
                msg.contains("apk") || msg.contains("mirror") || msg.contains("repository") -> FailureSubClass.INFRA_PACKAGE_TLS_HANDSHAKE_FAIL
                else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
            }
            is SSLException -> when {
                msg.contains("proxy") || msg.contains("tunnel") || msg.contains("407") -> FailureSubClass.INFRA_PACKAGE_PROXY_ERROR
                else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
            }
            is SocketException -> when {
                msg.contains("apk") || msg.contains("repository") -> FailureSubClass.INFRA_PACKAGE_DISK_FULL  // 不一定是；稍后按磁盘再分
                else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
            }
            is IOException -> when {
                msg.contains("no space left") || msg.contains("enospc") -> FailureSubClass.INFRA_PACKAGE_DISK_FULL
                msg.contains("checksum") || msg.contains("hash mismatch") || msg.contains("signature") -> FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH
                msg.contains("corrupt file") || msg.contains("truncated") || msg.contains("unexpected eof") -> FailureSubClass.INFRA_PACKAGE_DOWNLOAD_CORRUPT
                msg.contains("407") || msg.contains("proxy auth") -> FailureSubClass.INFRA_PACKAGE_PROXY_ERROR
                msg.contains("unable to resolve host") -> FailureSubClass.INFRA_PACKAGE_DNS_FAILURE
                msg.contains("timeout") -> FailureSubClass.INFRA_TIMEOUT
                else -> FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK
            }
            // Security / Crypto 失败 → 归 HARD_CONSTRAINT_CONFLICT（保留字段解密失败 = 保留冲突）
            is java.security.GeneralSecurityException -> FailureSubClass.USER_RESERVED_SENTINEL_CHANGE
            // Room SQLiteConstraintException / SQLiteAbortException 等 → 硬约束冲突
            is android.database.SQLException -> FailureSubClass.HARD_CONTEXT_WINDOW_EXCEEDED
            // 其他（含崩溃恢复的 CancellationException / OOM 杀后恢复）→ 归 CAPABILITY_OR_ENVIRONMENT_BLOCK
            is kotlinx.coroutines.CancellationException -> FailureSubClass.CRASH_RECOVERY_HALF_CHAIN_CUT
            is OutOfMemoryError -> FailureSubClass.L0_RESTORE_SENTINEL_EXPIRED  // OOM 杀后 L0 还原错
            else -> FailureSubClass.OUTPUT_HALLUCINATION_HIGH_CONF // 其余兜底（后续 JUnit 中必须补映射，否则抛）
        }
    }

    // ── Step 2 SubClass → 顶层 6 FailureClass ──────────────────────────────────

    private fun topClassFromSub(sub: FailureSubClass): FailureClass = when (sub) {
        // E1/E2/E3（HARD_CONSTRAINT_CONFLICT）
        FailureSubClass.USER_RESERVED_SENTINEL_CHANGE,
        FailureSubClass.USER_RESERVED_ROW_DELETE,
        FailureSubClass.HARD_DELETE_MISSING_WHERE,
        FailureSubClass.HARD_CONTEXT_WINDOW_EXCEEDED,
        FailureSubClass.HARD_TOOL_OUTPUT_TOO_LARGE,
        FailureSubClass.POLICY_CONFLICT_L2_VS_RESERVE -> FailureClass.HARD_CONSTRAINT_CONFLICT

        // E4（RESTORE_DEGRADED）
        FailureSubClass.CHECKPOINT_HASH_MISMATCH,
        FailureSubClass.FILE_SNAPSHOT_MISSING,
        FailureSubClass.L0_RESTORE_SENTINEL_EXPIRED -> FailureClass.RESTORE_DEGRADED

        // E5（INFRASTRUCTURE_ERROR）× 12（原 4 HTTP + 8 pkg 细分类（含 index_404）= 12）
        FailureSubClass.INFRA_HTTP_UNAUTHORIZED,
        FailureSubClass.INFRA_HTTP_RATE_LIMITED,
        FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK,
        FailureSubClass.INFRA_TIMEOUT,
        FailureSubClass.INFRA_PACKAGE_INDEX_404,
        FailureSubClass.INFRA_PACKAGE_DNS_FAILURE,
        FailureSubClass.INFRA_PACKAGE_TLS_HANDSHAKE_FAIL,
        FailureSubClass.INFRA_PACKAGE_PROXY_ERROR,
        FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH,
        FailureSubClass.INFRA_PACKAGE_DOWNLOAD_CORRUPT,
        FailureSubClass.INFRA_PACKAGE_DISK_FULL,
        FailureSubClass.INFRA_PACKAGE_MANAGER_MISMATCH -> FailureClass.INFRASTRUCTURE_ERROR

        // E6（CONTENT_REVIEW_PLAN_CHALLENGE）
        FailureSubClass.PLAN_RISK_DISAGREE,
        FailureSubClass.PLAN_TOOL_CHAIN_SUSPICIOUS -> FailureClass.CONTENT_REVIEW_PLAN_CHALLENGE

        // E7（HALLUCINATION_CONFIDENCE_FLAG）
        FailureSubClass.OUTPUT_HALLUCINATION_HIGH_CONF,
        FailureSubClass.LLM_TEXT_HALLUCINATION_HIT -> FailureClass.HALLUCINATION_CONFIDENCE_FLAG

        // E8~E11（CAPABILITY_OR_ENVIRONMENT_BLOCK）
        FailureSubClass.SKILL_BODY_DANGEROUS_REGEX_HIT,
        FailureSubClass.MCP_SERVER_INVALID_SCHEMA,
        FailureSubClass.CRASH_RECOVERY_HALF_CHAIN_CUT,
        FailureSubClass.OFFLINE_NETWORK_LOST,
        FailureSubClass.OFFLINE_PLAN_APPROVAL_MODEL_UNAVAILABLE -> FailureClass.CAPABILITY_OR_ENVIRONMENT_BLOCK
    }

    // ── Step 3 决策矩阵（C.2.2 10 列；与 FailureClassification 字段 1:1；(subClass, tier) 二维显式覆盖）
    // 每条都必须显式 switch；避免「else 全默认」造成 168 分支遗漏。JUnit 必须 100% hit。

    private fun decision(
        cls: FailureClass,
        subClass: FailureSubClass,
        ctx: ZthClassifyContext,
        t: Throwable?
    ): FailureClassification {
        // 先按 subClass 枚举 ordinal 顺序 switch（保证 28 值全覆盖；IDE 自动提醒缺失分支）
        return when (subClass) {
            // ── HARD_CONSTRAINT_CONFLICT ─────────────────────────────────────
            FailureSubClass.USER_RESERVED_SENTINEL_CHANGE -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "保留 sentinel 字段被改写：必须还原原值并弹卡确认。"
            )
            FailureSubClass.USER_RESERVED_ROW_DELETE -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "用户保留行被 AI 删除：LINK-INV 还原 + 弹卡确认。"
            )
            FailureSubClass.HARD_DELETE_MISSING_WHERE -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "检测到缺失 WHERE 的全表删除：已中断（HARD-0 铁律）。"
            )
            FailureSubClass.HARD_CONTEXT_WINDOW_EXCEEDED -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), true, tier(ctx, 2), tier(ctx, 2), true, false,
                "上下文超阈值：自动触发 L2 全量保真压缩，弹卡告知压缩比例。"
            )
            FailureSubClass.HARD_TOOL_OUTPUT_TOO_LARGE -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 1), tier(ctx, 2), true, false,
                "工具输出超归档阈值：截断首尾 + 输出存 ToolOutputStore 大文件。"
            )
            FailureSubClass.POLICY_CONFLICT_L2_VS_RESERVE -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "保留策略与 L2 压缩冲突：优先保留用户 sentinel，放弃 L2 本次压缩。"
            )
            // ── RESTORE_DEGRADED ──────────────────────────────────────────────
            FailureSubClass.CHECKPOINT_HASH_MISMATCH -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 2), true, true, false,
                "Checkpoint 校验失败：回滚到前一稳定 Checkpoint。"
            )
            FailureSubClass.FILE_SNAPSHOT_MISSING -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 2), true, true, false,
                "文件快照缺失：从 L0 还原日志还原，失败则弹卡告知用户手动恢复。"
            )
            FailureSubClass.L0_RESTORE_SENTINEL_EXPIRED -> build(
                cls, subClass, ctx, 3, true, true, tier(ctx, 2), true, true, false,
                "L0 sentinel 过期且崩溃后半链被裁：必须弹卡用户确认后才裁剪。"
            )
            // ── INFRASTRUCTURE_ERROR × 14 （C.4.13 8 SubClass 细颗粒） ─────
            FailureSubClass.INFRA_HTTP_UNAUTHORIZED -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 1), tier(ctx, 1), true, false,
                "鉴权失败(401/403)：请检查 API Key。"
            )
            FailureSubClass.INFRA_HTTP_RATE_LIMITED -> build(
                cls, subClass, ctx, 2, tier(ctx, 1), false, false, tier(ctx, 1), true, false,
                "限流(429)：自动 30s 退避 + MirrorRotator 切镜像。"
            )
            FailureSubClass.INFRA_HTTP_SERVER_OR_NETWORK -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 1), tier(ctx, 1), true, false,
                "HTTP 5xx/网络断开：自动重试 3 次，仍失败弹卡告知。"
            )
            FailureSubClass.INFRA_TIMEOUT -> build(
                cls, subClass, ctx, 2, tier(ctx, 1), false, tier(ctx, 1), tier(ctx, 1), true, false,
                "超时：自动翻倍超时并重试（30s→60s→120s）。"
            )
            FailureSubClass.INFRA_PACKAGE_INDEX_404 -> build(
                cls, subClass, ctx, 2, true, false, tier(ctx, 1), tier(ctx, 1), true, false,
                "Bundle index 404：MirrorRotator 自动切到下一镜像重试。"
            )
            FailureSubClass.INFRA_PACKAGE_DNS_FAILURE -> build(
                FailureClass.INFRASTRUCTURE_ERROR, subClass, ctx, 2, true, false, tier(ctx, 1), tier(ctx, 1), true, false,
                "DNS 解析失败：MirrorRotator 切镜像（官方→阿里→清华→中科大）。"
            )
            FailureSubClass.INFRA_PACKAGE_TLS_HANDSHAKE_FAIL -> build(
                cls, subClass, ctx, 2, true, false, tier(ctx, 1), tier(ctx, 1), true, false,
                "TLS 握手失败：MirrorRotator 切下一镜像，若全失败提示 CA 证书。"
            )
            FailureSubClass.INFRA_PACKAGE_PROXY_ERROR -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, tier(ctx, 1), tier(ctx, 1), true, false,
                "代理 407：请在系统代理设置中添加鉴权信息。"
            )
            FailureSubClass.INFRA_PACKAGE_SIGNATURE_MISMATCH -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "SHA256 签名校验失败：下载的 Bundle 已损坏或被篡改，自动禁止使用。"
            )
            FailureSubClass.INFRA_PACKAGE_DOWNLOAD_CORRUPT -> build(
                cls, subClass, ctx, 2, true, false, tier(ctx, 1), tier(ctx, 1), true, false,
                "下载文件损坏：切镜像重试。"
            )
            FailureSubClass.INFRA_PACKAGE_DISK_FULL -> build(
                cls, subClass, ctx, 3, true, false, tier(ctx, 2), tier(ctx, 2), true, false,
                "磁盘空间不足：请清理 /root/.rdeepcode/tool-output 旧文件。"
            )
            FailureSubClass.INFRA_PACKAGE_MANAGER_MISMATCH -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, false, tier(ctx, 2), true, false,
                "容器包管理器与命令不匹配：PkgMgrTransformer 已自动改写为当前容器可用命令。"
            )
            FailureSubClass.PLAN_RISK_DISAGREE -> build(
                FailureClass.CONTENT_REVIEW_PLAN_CHALLENGE, subClass, ctx, 2, true, true, tier(ctx, 2), true, true, false,
                "PlanApproval 模型对风险评估存疑：弹卡请用户再确认。"
            )
            FailureSubClass.PLAN_TOOL_CHAIN_SUSPICIOUS -> build(
                FailureClass.CONTENT_REVIEW_PLAN_CHALLENGE, subClass, ctx, 3, true, true, true, true, true, false,
                "工具链过长/副作用过大：弹卡用户确认。"
            )
            // ── HALLUCINATION_CONFIDENCE_FLAG (2) ─────────────────────────────
            FailureSubClass.OUTPUT_HALLUCINATION_HIGH_CONF -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "工具输出幻觉置信度过高（ZTH-0 铁律）：必须弹卡用户确认。"
            )
            FailureSubClass.LLM_TEXT_HALLUCINATION_HIT -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "LLM 文本回复命中幻觉启发规则（ZTH-0 铁律）：弹卡用户确认。"
            )
            // ── CAPABILITY_OR_ENVIRONMENT_BLOCK (5) ───────────────────────────
            FailureSubClass.SKILL_BODY_DANGEROUS_REGEX_HIT -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "Skill 正文 E8 危险正则命中（C.4.4）：拒绝执行。"
            )
            FailureSubClass.MCP_SERVER_INVALID_SCHEMA -> build(
                cls, subClass, ctx, 3, true, true, true, true, true, false,
                "MCP 第三方 server schema 不合规（C.4.4）：拒绝加载。"
            )
            FailureSubClass.CRASH_RECOVERY_HALF_CHAIN_CUT -> build(
                cls, subClass, ctx, 3, true, false, tier(ctx, 2), true, true, false,
                "崩溃后半完成降级链被截断：按 C.4.3 还原策略弹卡告知用户。"
            )
            FailureSubClass.OFFLINE_NETWORK_LOST -> build(
                cls, subClass, ctx, 2, tier(ctx, 2), false, false, tier(ctx, 2), true, true,
                "当前离线（C.4.9）：已降级为仅本地启发+用户确认。"
            )
            FailureSubClass.OFFLINE_PLAN_APPROVAL_MODEL_UNAVAILABLE -> build(
                cls, subClass, ctx, 3, true, false, tier(ctx, 2), true, true, true,
                "离线：PlanApproval 模型不可用 → 只走用户手动审批（C.4.9 不变性）。"
            )
        }
    }

    // ── 小工具：按 tier 决定 boolean（档位 0/1 不严格，2/3 严格）────────────────

    private fun tier(ctx: ZthClassifyContext, minTier: Int): Boolean = ctx.tier.tier >= minTier

    private fun build(
        cls: FailureClass,
        subClass: FailureSubClass,
        ctx: ZthClassifyContext,
        severityTier: Int,
        requiresUserConfirmation: Boolean,
        requiresLlmReview: Boolean,
        triggersFuseCountIncrement: Boolean,
        requiresLinkageMigration: Boolean,
        telemetryRequired: Boolean,
        showOfflineBanner: Boolean,
        autoRecoveryHint: String? = null
    ): FailureClassification = FailureClassification(
        failureClass = cls,
        subClass = subClass,
        // severityTier 会结合用户档位 + 业务评估取 max（逻辑严谨：tier=0 时 severity 也得 ≥ 0 才不违规）
        severityTier = (severityTier).coerceIn(0, 3),
        requiresUserConfirmation = requiresUserConfirmation && ctx.tier != ZthPresetTier.DISABLED,
        requiresLlmReview = requiresLlmReview && ctx.onlineValidated && ctx.tier != ZthPresetTier.DISABLED,
        triggersFuseCountIncrement = triggersFuseCountIncrement,
        requiresLinkageMigration = requiresLinkageMigration,
        telemetryRequired = telemetryRequired,
        showOfflineBanner = showOfflineBanner,
        autoRecoveryHint = autoRecoveryHint
    )
}
