package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.core.util.FileLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.12 ToolOutput 幻觉审查两阶段：
 *   - 阶段 1（chunk 增量）：每 64KB 切片 * 最多 10 个 chunk，用启发扫 URL/IP/邮箱/路径/凭证正则（PII 类幻觉 = 输出里出现不该出现的敏感内容 C.4.8）
 *   - 阶段 2（聚合终检）：阶段 1 完成后，把所有通过 chunk 的聚合摘要 ≤ 4_000 chars 送 LLM 终检（由 ZthToolOutputLlmReviewer 接口，Phase 5 接真实实现）
 *
 * 逻辑严谨：
 * 1) 任何时候阶段 1 命中 ≥ 1 条「高风险规则」→ 立即 FAIL_HEURISTIC_HALLUCINATION，不再走阶段 2（C.4.12 时间优化）
 * 2) chunk 数量超过 10（工具输出超大 640KB+）→ 只扫前 10 个 chunk + 尾 1 个 chunk（C.4.12：类似 HEAD/TAIL 策略，避免内存 OOM）
 * 3) 低端机跳过阶段 2 LLM 终检 → PASS_HEURISTIC_SKIP_LLM（C.4.7）
 * 4) 阶段 2 超时 10s：退化为 PASS_LOCAL（只显示「LLM 审超时，未检查」提示，不影响工具流程）
 * 5) 幻觉置信分 hallucinationConfidence ∈ [0,1]：累计命中规则的「风险分」加权值，档位阈值：
 *      tier=1 ≥ 0.9 FAIL；tier=2 ≥ 0.6 FAIL；tier=3 ≥ 0.3 FAIL（C.4.2 档位差异）
 */
@Singleton
class ZthToolOutputGuard @Inject constructor() {

    private companion object {
        const val TAG = "ZthToolOutputGuard"
        const val CHUNK_BYTES = 64_000
        const val MAX_HEAD_CHUNKS = 10
        const val LLM_REVIEW_TIMEOUT_MS = 10_000L
        const val MAX_AGGREGATE_CHARS = 4_000
    }

    // 规则风险分（加权总和 = hallucinationConfidence；最高值 = 6.68 → 除以 6.68 归一化到 [0,1]）
    private data class HeuristicRule(
        val id: String,
        val pattern: Regex,
        val weight: Float, // 0.1~2.0
        val severity: String // HIGH/MED/LOW（HIGH 立即 FAIL，其他累计加权）
    )

    private val rules: List<HeuristicRule> = listOf(
        // 4 条 PII 敏感（C.4.8 输出了不该出的凭据 / 私钥 / API Key）
        HeuristicRule("pii_aws_access_key", Regex("""AKIA[0-9A-Z]{16}"""), 2.0f, "HIGH"),
        HeuristicRule("pii_gcp_service_account_pem_header", Regex("""-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"""), 2.0f, "HIGH"),
        HeuristicRule("pii_api_key_bearer", Regex("""(sk-[A-Za-z0-9_\-]{20,}|ghp_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_\-]{20,})"""), 2.0f, "HIGH"),
        HeuristicRule("pii_jwt", Regex("""eyJ[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+"""), 1.8f, "HIGH"),
        // 6 条隐私信息（C.4.8）
        HeuristicRule("pii_email", Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""), 0.6f, "MED"),
        HeuristicRule("pii_cn_phone", Regex("""(\+?86[\s\-.]?)?1[3-9]\d{9}"""), 0.7f, "MED"),
        HeuristicRule("pii_us_ssn_like", Regex("""\b\d{3}-\d{2}-\d{4}\b"""), 1.2f, "MED"),
        HeuristicRule("pii_ipv4_public", Regex("""\b((?!10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b"""), 0.4f, "LOW"),
        HeuristicRule("pii_file_path_unix", Regex("""(^|[\s'"])(/root/|/home/[\w.]+/|~\S+)[\w/_.\-]+"""), 0.5f, "MED"),
        HeuristicRule("pii_seed_phrase", Regex("""\b(bip39|mnemonic|seed)\s*[:=]\s*["']?[A-Za-z]{3,8}(\s+[A-Za-z]{3,8}){7,23}"""), 2.0f, "HIGH"),
        // 10 条幻觉型（工具输出中出现「不存在但 AI 说存在的文件/URL/数字」启发式特征）
        HeuristicRule("hall_url_404_like", Regex("""https?://[^\s"'<>)]+(not\s+found|page\s+not\s+exist|404|this\s+url|invalid\s+resource)""", RegexOption.IGNORE_CASE), 0.9f, "MED"),
        HeuristicRule("hall_citation_nonexistent", Regex("""\b\[(\d{1,2})\]\s*[:：]?\s*(https?://|see\s+(chapter|section|page)\s+\d+)"""), 0.6f, "LOW"),
        HeuristicRule("hall_too_many_exact_decimal_places", Regex("""\b\d+\.\d{9,}\b"""), 0.4f, "LOW"),
        HeuristicRule("hall_fake_hash_sha256", Regex("""\bsha256:[A-Fa-f0-9]{64}\b(?=.*(imaginary|fake|placeholder|example|demo))""", RegexOption.IGNORE_CASE), 1.2f, "MED"),
        HeuristicRule("hall_uncertainty_phrases", Regex("""\b(may have|it is possible that|likely|probably|I believe|I think|I assume|presumably)\b""", RegexOption.IGNORE_CASE), 0.3f, "LOW"),
        HeuristicRule("hall_timestamp_format_mismatch", Regex("""\b\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z?\b.*(before|after|earlier|later)\s+than\s+\d{4}-\d{2}-\d{2}"""), 0.7f, "MED"),
        HeuristicRule("hall_fake_person", Regex("""\b(Dr\.|Prof\.|Mr\.|Ms\.)\s+[A-Z][a-z]+\s+[A-Z][a-z]+(?=\s*\(?(fictional|hypothetical|created for|does not (actually|really) exist)""", RegexOption.IGNORE_CASE), 1.0f, "MED"),
        HeuristicRule("hall_hallucinated_package_version", Regex("""\b(npm|pip|apt|apk|gem|yarn|pnpm)\s+(install|add)\s+\S+@(\d+\.\d+\.\d+)(?=.*(not\s+in\s+registry|package\s+not\s+found|404))""", RegexOption.IGNORE_CASE), 1.5f, "HIGH"),
        HeuristicRule("hall_made_up_command_flag", Regex("""(--[a-zA-Z0-9\-]{6,}|/[A-Za-z]{3,})\s+(?=.*(unknown option|invalid option|unrecognized option|illegal option))""", RegexOption.IGNORE_CASE), 0.8f, "MED"),
        HeuristicRule("hall_stacktrace_synthetic", Regex("""(com\.example|org\.demo|io\.placeholder|fake\.)\w+Exception\b"""), 1.3f, "HIGH")
    )

    // 阈值（档位：1/2/3 → 高/中/低严格度）
    private val failureThresholdByTier: Map<Int, Float> = mapOf(
        1 to 0.90f,
        2 to 0.60f,
        3 to 0.30f
    )

    // ── 对外入口 1：流式 ToolStreamEvent（C.4.12 增量 chunk 10 次） ────────────

    suspend fun auditStream(
        toolName: String,
        callId: String,
        stream: Flow<String>,
        tier: ZthPresetTier,
        perf: ZthPerformanceClass,
        llmReviewer: ZthToolOutputLlmReviewer? = null
    ): ZthToolOutputAudit {
        val channel = Channel<String>(capacity = Channel.UNLIMITED)
        val chunks = mutableListOf<String>()
        // 从 Flow 消费 → 最多 11 chunk（HEAD 10 + TAIL 1）
        stream.fold(0) { accBytes, line ->
            val next = accBytes + line.length
            val chunkIdx = accBytes / CHUNK_BYTES
            if (chunks.size <= chunkIdx && chunkIdx < MAX_HEAD_CHUNKS + 1) chunks.add(line)
            next
        }
        // 限制只取 HEAD 10 + TAIL 1
        val head10 = chunks.take(MAX_HEAD_CHUNKS)
        val tail1 = if (chunks.size > MAX_HEAD_CHUNKS) listOf(chunks.last()) else emptyList()
        return auditBatchesInternal(toolName, callId, head10 + tail1, tier, perf, llmReviewer)
    }

    // ── 对外入口 2：聚合文本（最终 ToolResult.Success.data） ─────────────────────

    suspend fun auditFinal(
        toolName: String,
        callId: String,
        aggregatedText: String,
        tier: ZthPresetTier,
        perf: ZthPerformanceClass,
        llmReviewer: ZthToolOutputLlmReviewer? = null
    ): ZthToolOutputAudit {
        val chunked = aggregatedText.chunked(CHUNK_BYTES).let { all ->
            if (all.size <= MAX_HEAD_CHUNKS + 1) all
            else all.take(MAX_HEAD_CHUNKS) + listOf(all.last()) // HEAD 10 + TAIL 1
        }
        return auditBatchesInternal(toolName, callId, chunked, tier, perf, llmReviewer, aggregatedText)
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private suspend fun auditBatchesInternal(
        toolName: String,
        callId: String,
        chunks: List<String>,
        tier: ZthPresetTier,
        perf: ZthPerformanceClass,
        llmReviewer: ZthToolOutputLlmReviewer?,
        aggregatedOverride: String? = null
    ): ZthToolOutputAudit {
        if (tier == ZthPresetTier.DISABLED) {
            // ZTH 关：直接通过（不变性 ZTH-3 兜底：禁止幻觉触发）
            return ZthToolOutputAudit(toolName, callId, ZthToolOutputVerdict.PASS_BOTH, 0f)
        }
        val hitRuleIds = mutableListOf<String>()
        var weighted = 0f
        var highHit = false

        // Phase 1：启发扫（chunk 10+1）
        for (text in chunks) {
            for (r in rules) {
                if (r.pattern.containsMatchIn(text)) {
                    hitRuleIds.add(r.id)
                    weighted += r.weight
                    if (r.severity == "HIGH") {
                        highHit = true
                    }
                }
            }
            if (highHit) break // 立即停
        }
        val confidence = (weighted / 6.68f).coerceIn(0f, 1f)
        val threshold = failureThresholdByTier[tier.tier.coerceAtLeast(1)] ?: 0.9f
        if (highHit || confidence >= threshold) {
            return ZthToolOutputAudit(
                toolName, callId,
                ZthToolOutputVerdict.FAIL_HEURISTIC_HALLUCINATION,
                confidence, hitRuleIds
            )
        }
        // Phase 2：LLM 终检（C.4.7 低端机直接跳过）
        if (perf == ZthPerformanceClass.LOW_END_SKIP_LLM || tier.tier <= 1 || llmReviewer == null) {
            return ZthToolOutputAudit(
                toolName, callId,
                ZthToolOutputVerdict.PASS_HEURISTIC_SKIP_LLM,
                confidence, hitRuleIds
            )
        }
        val aggregate = aggregatedOverride
            ?: chunks.joinToString(separator = "\n…[chunk boundary]…\n").take(MAX_AGGREGATE_CHARS)
        val verdict = withTimeoutOrNull(LLM_REVIEW_TIMEOUT_MS) {
            runCatching { llmReviewer.reviewAggregated(toolName, aggregate) }
                .onFailure { FileLogger.w(TAG, "LLM 终检异常 tool=$toolName callId=$callId: ${it.message}", it) }
                .getOrDefault(ZthToolOutputVerdict.PASS_HEURISTIC_SKIP_LLM)
        } ?: ZthToolOutputVerdict.PASS_HEURISTIC_SKIP_LLM

        val reason = if (verdict == ZthToolOutputVerdict.FAIL_LLM_REVIEW_CHALLENGE) {
            "LLM 终检对 $toolName 输出存疑（档位 $tier），需用户确认。"
        } else null
        return ZthToolOutputAudit(toolName, callId, verdict, confidence, hitRuleIds, llmChallengeReason = reason)
    }
}
