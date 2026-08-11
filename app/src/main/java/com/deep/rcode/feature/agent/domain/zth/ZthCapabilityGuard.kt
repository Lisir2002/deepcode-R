package com.deep.rcode.feature.agent.domain.zth

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.tool.ToolCapability
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.4 + C.4.7 Capability审查：工具链调用前的「能力 + 安全 + 性能」三层审查。
 *
 * 逻辑严谨慎：
 * 1) 先跑 9 条「零风险快速通过规则」(zero_risk_*) → PASS_ZERO_RISK_HEURISTIC（C.4.7 性能）
 * 2) 再跑 12 条 MCP/Skill 危险规则（skill_* + mcp_*）→ 命中 → NEED_USER_CONFIRM
 * 3) 剩余通过本地启发式：PASS_LOCAL_HEURISTIC，但是最后一批 BATCH=20 需要走 LLM 终检（NEED_LLM_FINAL_REVIEW）
 * 4) 任何时候 8s 超时：所有未判的一律退化为 NEED_USER_CONFIRM（安全兜底）
 * 5) 低端机 LOW_END_SKIP_LLM：直接跳过 (3) 的 LLM 终检，PASS_LOCAL_HEURISTIC 就算通过（C.4.7）
 *
 * 纯 Kotlin 类（无 Room/无 Compose/无 Hilt 内部依赖），JUnit 单测 21 条规则 hit/miss 100%。
 */
@Singleton
class ZthCapabilityGuard @Inject constructor() {

    private companion object {
        const val TAG = "ZthCapabilityGuard"
        const val BATCH_SIZE = 20
        const val BATCH_TIMEOUT_MS = 8_000L
    }

    // ── 9 条 C.4.7 零风险启发式（C.4.7 快速跳过，不需卡）───────────────────────────────────

    private val zeroRiskRules: List<Pair<String, (ZthToolCallPlanItem) -> Boolean>> = listOf(
        // ZR1：纯 READ_WORKSPACE 单能力 + 非 MCP/Skill 内置工具（listFiles/readFile/searchCode(非网络正则) 等）
        "zero_risk_pure_read_builtin" to {
            it.capabilities == setOf(ToolCapability.READ_WORKSPACE)
                    && it.mcpServerName == null && it.skillBundleKey == null
        },
        // ZR2：参数预览文本里没有 shell/命令/网络行为（list、grep 纯本地）
        "zero_risk_arg_no_shell_tokens" to { item ->
            val text = item.argsPreviewText
            !text.contains(Regex("""(;|\|\||&&|`|\$\(|\$\{)""")) // 无 shell 串联符
                    && !text.contains(Regex("""\b(wget|curl|nc|ncat|ssh|scp|rsync|apk|apt|yum|pip|npm|pnpm|yarn|gem|brew)\b"""))
        },
        // ZR3：只读 USER_INTERACTION（AskUserQuestionTool 不会有任何副作用）
        "zero_risk_user_interaction_only" to {
            it.capabilities == setOf(ToolCapability.USER_INTERACTION)
        },
        // ZR4：只读 MODIFY_TODO_STATE（TodoTool / PlanTodo 非破坏性）
        "zero_risk_todo_only" to {
            it.capabilities == setOf(ToolCapability.MODIFY_TODO_STATE)
        },
        // ZR5：工具名白名单（searchCode / listFiles / readFile / getToolDefinitions）不会毁数据
        "zero_risk_toolname_safe_whitelist" to {
            it.toolName in SAFE_BUILTIN_TOOLS_WHITELIST && it.mcpServerName == null
        },
        // ZR6：ToolCapability 集合 ⊆ { READ, USER_INTERACTION, MODIFY_TODO }（零净破坏性）
        "zero_risk_cap_subset_no_write" to {
            it.capabilities.all { c ->
                c == ToolCapability.READ_WORKSPACE
                        || c == ToolCapability.USER_INTERACTION
                        || c == ToolCapability.MODIFY_TODO_STATE
                        || c == ToolCapability.NETWORK_READ  // 读 OK（写不行）
                        || c == ToolCapability.READ_AGENT_CONFIG
            }
        },
        // ZR7：参数预览为「空 JSON 或纯文本 help 列表」→ 无副作用
        "zero_risk_args_empty_or_help" to {
            it.argsPreviewText.isBlank()
                    || it.argsPreviewText.trim() == "{}"
                    || it.argsPreviewText.contains(Regex("""\b(help|usage|list(?!.*delete)|--version|-V)\b"""))
        },
        // ZR8：Capability 单值 EXTERNAL_TOOL 但 mcpServerName 命中已知「安全 MCP 白名单」（C.4.4）
        "zero_risk_external_tool_safe_mcp_whitelist" to {
            it.capabilities == setOf(ToolCapability.EXTERNAL_TOOL)
                    && it.mcpServerName in SAFE_MCP_SERVER_WHITELIST
        },
        // ZR9：WRITE_WORKSPACE 仅「新建文件路径不覆盖已有」的启发式（argsPreview 含 mode=create 或 parent_not_exists=1 信号）
        "zero_risk_write_workspace_create_only_hint" to {
            it.capabilities == setOf(ToolCapability.WRITE_WORKSPACE)
                    && it.argsPreviewText.contains(Regex("""("?mode"?\s*[:=]\s*"?create"?)|parent_not_exists|mkdir -p [\w/]+$"""))
        }
    )

    // ── 12 条 MCP/Skill 危险启发式（C.4.4：命中 NEED_USER_CONFIRM） ──────────────────────

    private val mcpSkillRiskRules: List<Pair<String, (ZthToolCallPlanItem) -> Boolean>> = listOf(
        // E1：Skill 正文中包含 base64 解码执行（eval base64）
        "mcp_skill_eval_base64" to { item ->
            item.skillBundleKey != null && item.argsPreviewText.contains(
                Regex("""base64\s*-d\b|echo\s+[A-Za-z0-9+/=]{40,}\s*\|\s*bash|python3?\s*-c\s+['\"]import.*base64""")
            )
        },
        // E2：Skill 调用 curl/wget + pipe 到 sh/bash（C.4.4 经典 curl|sh 攻击）
        "mcp_skill_curl_pipe_sh" to {
            (it.skillBundleKey != null || it.mcpServerName != null)
                    && it.argsPreviewText.contains(Regex("""(curl|wget)\s+.*\|\s*(sudo\s+)?(sh|bash|ash|zsh|ksh)\b"""))
        },
        // E3：MCP server 名称含未验证 user 输入（动态 install server 无签名：C.4.4）
        "mcp_unsigned_dynamic_install_server" to {
            it.capabilities.contains(ToolCapability.LOAD_MCP_SERVER)
                    && !it.argsPreviewText.contains(Regex("""signature=|signedHash=|trustedPublisher=|from=official-index"""))
        },
        // E4：Skill 文件含 chmod 777 / 递归改 /root（C.4.4）
        "skill_chmod_777_or_root_recursive" to {
            it.skillBundleKey != null && it.argsPreviewText.contains(
                Regex("""chmod\s+(-R\s+)?(777|a\+rwx)\b|chown\s+-R\s+\S+\s+/|chattr\s+[-+]i\b""")
            )
        },
        // E5：rm -rf /* 或 rm /root/.* （破坏性删除，C.4.4）
        "skill_rm_recursive_dangerous" to {
            (it.skillBundleKey != null || it.mcpServerName != null)
                    && it.argsPreviewText.contains(Regex("""rm\s+(-[rfR]+\s+|-\S*[rfR]\S*\s+)(/|/\*|/root|/home|${'$'}HOME|~)\b"""))
        },
        // E6：LOAD_SKILL_BUNDLE + 参数预览里没 .skill 文件 extension（可疑脚本伪装，C.4.4）
        "mcp_skill_skillbundle_without_extension" to {
            it.capabilities.contains(ToolCapability.LOAD_SKILL_BUNDLE)
                    && !it.argsPreviewText.contains(Regex("""\.(skill|md|markdown|json)\b"""))
        },
        // E7：MCP 第三方 schema 中未声明 endpoint 但 capabilities 有 NETWORK_WRITE（C.4.4 不合规，Phase 3 McpManager 再核）
        "mcp_network_write_without_endpoint" to {
            it.mcpServerName != null
                    && it.capabilities.contains(ToolCapability.NETWORK_WRITE)
                    && !it.argsPreviewText.contains(Regex("""endpoint=|url=|host=|baseUrl=""", RegexOption.IGNORE_CASE))
        },
        // E8：写入 /etc / /proc / /sys（rootfs 敏感路径，C.4.4）
        "mcp_skill_write_system_etc_proc_sys" to {
            it.capabilities.contains(ToolCapability.WRITE_WORKSPACE)
                    && it.argsPreviewText.contains(Regex("""write.*("|')?/(etc|proc|sys)/""", RegexOption.IGNORE_CASE))
        },
        // E9：含 `sudo` 前缀（非必要特权，C.4.4）
        "mcp_skill_sudo_prefix" to {
            (it.skillBundleKey != null || it.mcpServerName != null)
                    && it.argsPreviewText.contains(Regex("""(^|[;&|`(\n])\s*sudo\s+"""))
        },
        // E10：含 `openssl enc` / `gpg` / `cryptsetup` 等可疑加密行为（C.4.4）
        "skill_sensitive_crypto_tools" to {
            it.skillBundleKey != null && it.argsPreviewText.contains(
                Regex("""\b(openssl\s+enc|gpg\s+(--symmetric|--encrypt)|cryptsetup\s+(-c|--cipher)|ccrypt)\b""")
            )
        },
        // E11：MCP server 名称含未验证 IP/端口（C.4.4 内网横向风险）
        "mcp_server_raw_ip_or_port" to {
            it.mcpServerName != null && it.mcpServerName.matches(Regex(""".*\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}.*"""))
                    || it.argsPreviewText.contains(Regex("""ws?://\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?"""))
        },
        // E12：Capability 包含 MODIFY_USER_CONFIRMED_STATE 但未经过 ZthConfirmationCard（C.4.8 最严格）
        "skill_modify_user_confirmed_without_card" to {
            it.capabilities.contains(ToolCapability.MODIFY_USER_CONFIRMED_STATE)
                    && !it.argsPreviewText.contains("source=zth_confirmation_card")
        }
    )

    private val SAFE_BUILTIN_TOOLS_WHITELIST = setOf(
        "searchCode", "listFiles", "readFile", "todoList", "showTodo",
        "getToolDefinitions", "askUserQuestion", "switchMode", "viewImage"
    )

    private val SAFE_MCP_SERVER_WHITELIST = setOf(
        // C.4.4 Phase 4 设置页手工维护白名单（v1.0 默认空，用户不添加即「严格」符合 ZTH-1 零幻觉假设）
    )

    // ── 对外入口 ──────────────────────────────────────────────────────────────────

    /**
     * 批量审查入口。
     *
     * @param items 工具调用计划（BATCH 建议 ≤20；超过会被自动分片为 BATCH=20 组）
     * @param tier 当前档位（DISALBED 时直接返回全 PASS_ZERO_RISK_HEURISTIC 不走 LLM）
     * @param perf 性能级（LOW_END_SKIP_LLM 时跳过 LLM 终检）
     * @param llmReviewer 终检回调（Phase 5 接入真实实现；低端机传 null 也行）
     */
    suspend fun auditBatch(
        items: List<ZthToolCallPlanItem>,
        tier: ZthPresetTier,
        perf: ZthPerformanceClass,
        llmReviewer: ZthCapabilityLlmReviewer? = null
    ): List<ZthCapabilityAuditResult> = if (items.isEmpty()) {
        emptyList()
    } else if (tier == ZthPresetTier.DISABLED) {
        // 档位 0：全部 PASS（ZTH 关）。不变性 ZTH-3 兜底禁止。
        items.map { ZthCapabilityAuditResult(it, ZthCapabilityVerdict.PASS_ZERO_RISK_HEURISTIC, listOf("tier_disabled_bypass")) }
    } else withTimeoutOrNull(BATCH_TIMEOUT_MS) {
        // 分片：BATCH=20
        val batches = items.chunked(BATCH_SIZE)
        val aggregate = mutableListOf<ZthCapabilityAuditResult>()
        batches.forEachIndexed { idx, batch ->
            val partial = auditOneBatch(
                batch = batch,
                isLastBatch = idx == batches.lastIndex,
                tier = tier,
                perf = perf,
                llmReviewer = llmReviewer
            )
            aggregate.addAll(partial)
        }
        aggregate
    } ?: items.map {
        // 8s 超时安全兜底：所有未判 NEED_USER_CONFIRM（C.4.7 强安全）
        ZthCapabilityAuditResult(
            it,
            ZthCapabilityVerdict.NEED_USER_CONFIRM,
            timedOut = true,
            hitRuleIds = listOf("global_batch_timeout_8s_fallback")
        )
    }

    private suspend fun auditOneBatch(
        batch: List<ZthToolCallPlanItem>,
        isLastBatch: Boolean,
        tier: ZthPresetTier,
        perf: ZthPerformanceClass,
        llmReviewer: ZthCapabilityLlmReviewer?
    ): List<ZthCapabilityAuditResult> {
        val hitsPerItem: MutableMap<String, MutableList<String>> = mutableMapOf()
        val verdictMap = mutableMapOf<String, ZthCapabilityVerdict>()

        // Step 1：9 zero_risk 启发
        for (item in batch) {
            for ((rid, rule) in zeroRiskRules) {
                if (rule(item)) {
                    hitsPerItem.getOrPut(item.toolName) { mutableListOf() }.add(rid)
                    verdictMap[item.toolName] = ZthCapabilityVerdict.PASS_ZERO_RISK_HEURISTIC
                    break
                }
            }
        }

        // Step 2：12 mcp/skill 风险启发（对 Step 1 未判的跑）
        val remainingAfterZero = batch.filterNot { verdictMap.containsKey(it.toolName) }
        for (item in remainingAfterZero) {
            var blocked = false
            for ((rid, rule) in mcpSkillRiskRules) {
                if (rule(item)) {
                    hitsPerItem.getOrPut(item.toolName) { mutableListOf() }.add(rid)
                    verdictMap[item.toolName] = ZthCapabilityVerdict.NEED_USER_CONFIRM
                    blocked = true
                    break
                }
            }
            if (!blocked) {
                // 未命中 12 条的 → 本地启发通过
                hitsPerItem.getOrPut(item.toolName) { mutableListOf() }.add("local_heuristic_pass")
                verdictMap[item.toolName] = when {
                    !isLastBatch -> ZthCapabilityVerdict.PASS_LOCAL_HEURISTIC
                    perf == ZthPerformanceClass.LOW_END_SKIP_LLM -> ZthCapabilityVerdict.PASS_HEURISTIC_SKIP_LLM
                    tier.tier <= 1 -> ZthCapabilityVerdict.PASS_LOCAL_HEURISTIC
                    llmReviewer == null -> ZthCapabilityVerdict.PASS_LOCAL_HEURISTIC
                    else -> ZthCapabilityVerdict.NEED_LLM_FINAL_REVIEW
                }
            }
        }

        // Step 3：最后一批且有 NEED_LLM_FINAL_REVIEW → 跑 LLM 终检（C.4.7）
        if (isLastBatch && llmReviewer != null && perf != ZthPerformanceClass.LOW_END_SKIP_LLM && tier.tier >= 2) {
            val pendingLlm = batch.filter { verdictMap[it.toolName] == ZthCapabilityVerdict.NEED_LLM_FINAL_REVIEW }
            if (pendingLlm.isNotEmpty()) {
                runCatching {
                    val llmVerdictByTool = llmReviewer.reviewBatch(pendingLlm)
                    for (item in pendingLlm) {
                        val v = llmVerdictByTool[item.toolName] ?: ZthCapabilityVerdict.NEED_USER_CONFIRM // 默认保守
                        verdictMap[item.toolName] = v
                        if (v == ZthCapabilityVerdict.NEED_USER_CONFIRM) {
                            hitsPerItem.getOrPut(item.toolName) { mutableListOf() }.add("llm_final_review_challenged")
                        }
                    }
                }.onFailure { err ->
                    // LLM 自身失败：安全退化 NEED_USER_CONFIRM（C.4.2 强安全）
                    FileLogger.w(TAG, "LLM 终检失败（批量 ${pendingLlm.size} 项），退化为 NEED_USER_CONFIRM：${err.message}")
                    for (item in pendingLlm) {
                        verdictMap[item.toolName] = ZthCapabilityVerdict.NEED_USER_CONFIRM
                        hitsPerItem.getOrPut(item.toolName) { mutableListOf() }.add("llm_final_exception_fallback")
                    }
                }
            }
        }

        return batch.map { item ->
            val verdict = verdictMap[item.toolName]
                ?: ZthCapabilityVerdict.NEED_USER_CONFIRM  // 兜底
            val hitRules = hitsPerItem[item.toolName].orEmpty()
            ZthCapabilityAuditResult(
                item = item,
                verdict = verdict,
                hitRuleIds = hitRules,
                riskExplanation = buildRiskExplanation(item, verdict, hitRules)
            )
        }
    }

    private fun buildRiskExplanation(
        item: ZthToolCallPlanItem,
        verdict: ZthCapabilityVerdict,
        rules: List<String>
    ): String? = when (verdict) {
        ZthCapabilityVerdict.NEED_USER_CONFIRM ->
            "工具 ${item.toolName}（cap=${item.capabilities.joinToString()}）触发 ZTH 风险规则：" +
                    rules.joinToString(" / ").ifBlank { "批量审查超时兜底" }
        ZthCapabilityVerdict.BLOCKED_BY_GLOBAL_DENY ->
            "工具 ${item.toolName} 被全局权限策略（GLOBAL_DENY）禁止。"
        else -> null
    }
}
