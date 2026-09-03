package com.core.deepcode.feature.agent.domain.permission

/**
 * ZTH 幻觉容忍顶层 FailureClass（决策矩阵第一维，共 6 类）。
 * 与 C.2.2 6×20 SubClass 决策矩阵一一对应。
 */
enum class FailureClass {
    /** E1~E3：用户保留/硬约束/删除冲突（必须弹用户确认）。 */
    HARD_CONSTRAINT_CONFLICT,
    /** E4：上下文还原/保留策略执行失败（自动降级但弹卡）。 */
    RESTORE_DEGRADED,
    /** E5：工具失败/API 错误（含网络 HTTP 4xx/5xx）。 */
    INFRASTRUCTURE_ERROR,
    /** E6：审查通过但 PlanApproval 模型质疑（只档位 2/3 进入）。 */
    CONTENT_REVIEW_PLAN_CHALLENGE,
    /** E7：LLM 输出/工具输出通过但是幻觉置信触发（ZTH-0 铁律）。 */
    HALLUCINATION_CONFIDENCE_FLAG,
    /** E8/E9/E10/E11：离线/Skill 正文非法/MCP 路径异常/崩溃恢复。 */
    CAPABILITY_OR_ENVIRONMENT_BLOCK
}

/**
 * ZTH 幻觉 SubClass（决策矩阵第二维）。
 * Phase 1.1 先定义 C.4 已讨论过的 20（原）+ 4（新增 INFRA_PACKAGE_*）+ 1（INFRA_PACKAGE_MANAGER_MISMATCH）= 25 个值。
 * 后续 C.4.x 新增值必须加在末尾（避免 ordinal 变化破坏 Room ENUM 持久化顺序）。
 */
enum class FailureSubClass {
    // ── HARD_CONSTRAINT_CONFLICT（E1/E2/E3）──
    /** E1.1 用户保留字段被改写。 */
    USER_RESERVED_SENTINEL_CHANGE,
    /** E1.2 用户保留行被 AI 删除/覆盖。 */
    USER_RESERVED_ROW_DELETE,
    /** E2.1 硬约束：DELETE 缺失 WHERE。 */
    HARD_DELETE_MISSING_WHERE,
    /** E2.2 硬约束：超过 context_window 阈值。 */
    HARD_CONTEXT_WINDOW_EXCEEDED,
    /** E2.3 硬约束：工具输出 chunk 超 size 拒绝归档。 */
    HARD_TOOL_OUTPUT_TOO_LARGE,
    /** E3.1 保留策略 vs L2 压缩策略冲突。 */
    POLICY_CONFLICT_L2_VS_RESERVE,

    // ── RESTORE_DEGRADED（E4）──
    /** E4.1 Checkpoint 哈希不匹配（CheckpointManager verify 失败）。 */
    CHECKPOINT_HASH_MISMATCH,
    /** E4.2 文件快照丢失（CheckpointFileSnapshotEntity 少 row）。 */
    FILE_SNAPSHOT_MISSING,
    /** E4.3 L0 软压缩还原时 sentinel 过期（C.4.3 崩溃恢复过期）。 */
    L0_RESTORE_SENTINEL_EXPIRED,

    // ── INFRASTRUCTURE_ERROR（E5）──
    /** E5.1 HTTP 401 / 403 鉴权失败。 */
    INFRA_HTTP_UNAUTHORIZED,
    /** E5.2 HTTP 429 限流。 */
    INFRA_HTTP_RATE_LIMITED,
    /** E5.3 HTTP 5xx / 网络不可达（C.4.9 有网）。 */
    INFRA_HTTP_SERVER_OR_NETWORK,
    /** E5.4 超时（含工具 TimeoutCancellationException）。 */
    INFRA_TIMEOUT,
    /** C.4.13.1 Terminal Bundle 404。 */
    INFRA_PACKAGE_INDEX_404,
    /** C.4.13.1 Terminal Bundle DNS 失败。 */
    INFRA_PACKAGE_DNS_FAILURE,
    /** C.4.13.1 Terminal Bundle TLS 握手失败。 */
    INFRA_PACKAGE_TLS_HANDSHAKE_FAIL,
    /** C.4.13.1 Terminal Bundle HTTP 代理失败。 */
    INFRA_PACKAGE_PROXY_ERROR,
    /** C.4.13.1 Terminal Bundle 下载 SHA256 校验签名失败。 */
    INFRA_PACKAGE_SIGNATURE_MISMATCH,
    /** C.4.13.1 Terminal Bundle 下载文件损坏。 */
    INFRA_PACKAGE_DOWNLOAD_CORRUPT,
    /** C.4.13.1 Terminal Bundle 磁盘空间不足。 */
    INFRA_PACKAGE_DISK_FULL,
    /** C.4.14 当前会话容器包管理器与命令不匹配（apk 容器收到 apt）。 */
    INFRA_PACKAGE_MANAGER_MISMATCH,

    // ── CONTENT_REVIEW_PLAN_CHALLENGE（E6，档位 2/3 only）──
    /** E6.1 PlanApproval 模型质疑风险评估。 */
    PLAN_RISK_DISAGREE,
    /** E6.2 PlanApproval 模型质疑工具链长度/副作用。 */
    PLAN_TOOL_CHAIN_SUSPICIOUS,

    // ── HALLUCINATION_CONFIDENCE_FLAG（E7，ZTH-0 核心）──
    /** E7.1 ToolOutput 幻觉置信度 ≥ 阈值（档位 1/2/3 阈值不同）。 */
    OUTPUT_HALLUCINATION_HIGH_CONF,
    /** E7.2 LLM 文本回复含 hallucination 关键词 + 启发扫 命中。 */
    LLM_TEXT_HALLUCINATION_HIT,

    // ── CAPABILITY_OR_ENVIRONMENT_BLOCK（E8/E9/E10/E11 离线）──
    /** E8.1 Skill 正文危险正则 命中（C.4.4 ContentReviewer）。 */
    SKILL_BODY_DANGEROUS_REGEX_HIT,
    /** E9.1 MCP 第三方 server schema 不合规。 */
    MCP_SERVER_INVALID_SCHEMA,
    /** E10.1 崩溃恢复 sentinel 半完成链 CUT。 */
    CRASH_RECOVERY_HALF_CHAIN_CUT,
    /** C.4.9 离线：E11 NetworkCallback 返回 NET_CAP_VALIDATED=false。 */
    OFFLINE_NETWORK_LOST,
    /** C.4.9 离线：PlanApproval 无法调用模型审批（只离线存在）。 */
    OFFLINE_PLAN_APPROVAL_MODEL_UNAVAILABLE
}

/**
 * C.4.3 / C.4.6 HallucinationCircuitBreaker 状态机 6 态 + TRANSITIONING 原子中间态。
 * 共 7 值（C.4.3 加 TRANSITIONING，FuseState 新增 1）。
 * 状态机合法转移（Phase 3 单测必须 100% 覆盖）：
 *   CLOSED → HALF_OPEN（C.4.6 冷却 T_cool 到期）
 *   CLOSED → OPEN（FUSE_TRIP 事件）
 *   HALF_OPEN → OPEN（HALF_TRIP 阈值 hit）
 *   HALF_OPEN → CLOSED（HALF_RESET_SUCCESS）
 *   OPEN → TRANSITIONING（RESET 命令，原子 optimistic lock）
 *   TRANSITIONING → HALF_OPEN（迁移成功）
 *   TRANSITIONING → OPEN（迁移失败 + 自动回滚，C.4.2 Double kill-switch）
 */
enum class FuseState {
    /** 正常放行（初始态；全局/会话默认 CLOSED）。 */
    CLOSED,
    /** 单探针放行（冷却结束进入；失败 N 次回 OPEN，成功 1 次回 CLOSED）。 */
    HALF_OPEN,
    /** 熔断拦截（所有用户确认弹卡前 Block；RESET 命令先经 TRANSITIONING）。 */
    OPEN,
    /**
     * C.4.3 原子中间态（C.4.6 session 级隔离的乐观锁迁移）：
     * 从 OPEN → HALF_OPEN 必经此态；LINK-INV 四方迁移成功进入 HALF_OPEN，
     * 失败自动回滚 OPEN 并 kill-switch-1 置位（Phase 3 LINK-INV-0~6 实现）。
     */
    TRANSITIONING
}

/**
 * ZTH FailureClassifier 统一输出（决策矩阵输入）。
 * Phase 2 ZthFailureClassifier.classify(Throwable, Context) → FailureClassification。
 * 对应 C.2.2 决策矩阵 10 列的「机器可读」承载结构。
 */
data class FailureClassification(
    val failureClass: FailureClass,
    val subClass: FailureSubClass,
    /** C.2.2 风险档位评估（0~3，对齐档位 0~3；数值越大越要弹 ConfirmationCard）。 */
    val severityTier: Int,
    /** C.2.2 Col.2：是否需要「用户确认」（true → 走 ConfirmationCard 挂起）。 */
    val requiresUserConfirmation: Boolean,
    /** C.2.2 Col.3：true → 必须 LLM 审查（ToolOutputGuard/ContentReviewer）。 */
    val requiresLlmReview: Boolean,
    /** C.2.2 Col.4：true → 熔断 OPEN 状态 转 HALF_OPEN 的重置计数器+1。 */
    val triggersFuseCountIncrement: Boolean,
    /** C.2.2 Col.5：true → 四方联动 LINK-INV 重跑（对 sentinel/熔断/PlanApproval/Checkpoint 做事务级迁移）。 */
    val requiresLinkageMigration: Boolean,
    /** C.2.2 Col.6：true → 写 zth_telemetry_event。 */
    val telemetryRequired: Boolean,
    /** C.2.2 Col.7：true → 离线 banner 显示（BANNER-INV-1 无关闭按钮）。 */
    val showOfflineBanner: Boolean,
    /** C.2.2 Col.8：成功建议操作（自动降级路径）。 */
    val autoRecoveryHint: String? = null
) {
    init {
        require(severityTier in 0..3) { "severityTier 必须 ∈ {0,1,2,3}" }
    }
}
