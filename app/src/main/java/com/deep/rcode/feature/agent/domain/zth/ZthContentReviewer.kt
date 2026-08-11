package com.deep.rcode.feature.agent.domain.zth

import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.4 Skill 正文 / MCP server.json / user 输入 2 步审查（E8 20+ 危险正则，C.4.4）。
 *
 * 2 步执行（2-step，符合 C.4.4 描述）：
 *   Step 1 = light scan（前 500 字 + 后 500 字，只查 8 条最高危 HIGH 规则）→ 命中立即 FAIL（省性能）
 *   Step 2 = full scan（全文 20 条全扫）→ 返回所有命中规则，供 UI 在 ZTH ConfirmationCard 上列出风险
 *
 * 与 ZthCapabilityGuard 的区别：
 *   - ZthCapabilityGuard = 审查「工具调用 + 参数」（toolName + capabilities + argsPreview）
 *   - ZthContentReviewer   = 审查「大文本内容」（Skill.md / MCP server.json / plan 正文）
 * 两者独立但互补，都挂 ZthGuardAggregateFacade。
 */
@Singleton
class ZthContentReviewer @Inject constructor() {

    private companion object {
        // 权重分同 ZthToolOutputGuard 风格 0.1~2.0
        data class Rule(val id: String, val regex: Regex, val weight: Float, val severity: Severity)
        enum class Severity { HIGH, MED, LOW }
    }

    private val allRules: List<Rule> = listOf(
        // ── 8 HIGH（Step 1 light scan 只查这些）── C.4.4 E8 1 级危险
        Rule("e8_curl_pipe_sh", Regex("""(curl|wget)\s+(-[A-Za-z0-9\-]+\s+)*[^\s|;&`]+(\.sh|\.bash|\.zsh)?\s*\|\s*(sudo\s+)?(sh|bash|ash|zsh|ksh|dash)\b"""), 2.0f, Severity.HIGH),
        Rule("e8_chmod_777_or_recursive_root", Regex("""chmod\s+(-R\s+)?(777|a\+rwx)\s+(/|/root|/etc|/usr|/var|/home|~)"""), 2.0f, Severity.HIGH),
        Rule("e8_rm_rf_slash", Regex("""rm\s+(-[rfR]+\s*|-\S*[rfR]\S*\s+)(/\*?|/\s*$|~|/root|/home|\$HOME)\b"""), 2.0f, Severity.HIGH),
        Rule("e8_sudo_prefix_without_confirm", Regex("""(^|[\n;&|`])\s*sudo\s+(chmod|chown|rm|shutdown|reboot|mkfs|dd|iptables|passwd\s+\-|useradd|usermod)"""), 2.0f, Severity.HIGH),
        Rule("e8_eval_base64_decode_exec", Regex("""(base64\s*-d\b|python3?\s+-c\s+['\"][^'\"]*base64[^'\"]*['\"]|echo\s+['\"]?[A-Za-z0-9+/=\s]{80,}['\"]?\s*\|\s*sh)"""), 2.0f, Severity.HIGH),
        Rule("e8_cryptsetup_or_dm_crypt", Regex("""\b(cryptsetup|dmsetup\s+create\s+.*--readonly|gpg\s+--batch\s+--passphrase-fd|openssl\s+enc\s+-aes-256-cbc\s+-k)"""), 2.0f, Severity.HIGH),
        Rule("e8_privilege_esc_suid_chmod_s", Regex("""chmod\s+(u\+s|4[0-7]{3}|[0-7]?[426][0-7]{2})\s+(/usr|/bin|/sbin|/usr/bin|/usr/sbin)"""), 2.0f, Severity.HIGH),
        Rule("e8_reverse_shell", Regex("""(nc|ncat|netcat|socat|bash\s+-i)\s+.*(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|connect\s*\(|\s*>&\s*/dev/tcp/)"""), 2.0f, Severity.HIGH),
        // ── 6 MED（Step 2 full 才查）
        Rule("e8_env_steal_etc_shadow", Regex("""\b(cat|less|head|tail|base64|xxd)\s+/etc/(shadow|passwd|gshadow|group|sudoers)\b"""), 1.4f, Severity.MED),
        Rule("e8_docker_mount_host_root", Regex("""docker\s+run\s+.*(-v|--mount)[^\n]*[:/]?/:/?(host|rootfs)?[^\n]*(--privileged)?\s+"""), 1.3f, Severity.MED),
        Rule("e8_iptables_flush", Regex("""iptables\s+(-F|--flush)\s*;?\s*iptables\s+(-X|--delete-chain)"""), 1.2f, Severity.MED),
        Rule("e8_crontab_persist_backdoor", Regex("""(crontab\s+-\s*<<|echo\s+.*>>\s*/etc/cron|systemctl\s+enable\s+\S+\.service\s*&&\s*systemctl\s+start)"""), 1.0f, Severity.MED),
        Rule("e8_ssh_key_overwrite", Regex("""(>|tee\s+)(~\.ssh|/root/.ssh|/home/\w+/.ssh)/(authorized_keys|id_rsa|id_ed25519)(\s|<|<<)"""), 1.8f, Severity.MED),
        Rule("e8_kernel_module_dmesg_unsafe", Regex("""(insmod|modprobe|rmmod)\s+\w+|dmesg\s+\-\-console\-level=err|sysctl\s+-w\s+kernel\.|echo\s+[0-3]\s*>\s*/proc/sys/kernel/"""), 1.0f, Severity.MED),
        // ── 6 LOW（文档/提示/注释/常见错用但不一定危险）
        Rule("e8_chmod_a_write_non_sensitive", Regex("""chmod\s+(-R\s+)?(o\+w|766|666)\s+[\w/.-]+"""), 0.5f, Severity.LOW),
        Rule("e8_exposed_docker_tcp_port", Regex("""dockerd\s+-H\s+tcp://0\.0\.0\.0|docker\s+daemon\s+--host\s+tcp://0\.0\.0\.0"""), 0.6f, Severity.LOW),
        Rule("e8_plaintext_password_in_script", Regex("""(passwd|password|pwd)\s*[:=]\s*["'][^"']{4,}["']"""), 0.8f, Severity.LOW),
        Rule("e8_pip_npm_install_without_pin", Regex("""(pip3?\s+install\s+\S+|npm\s+(install|add|i)\s+\S+)(?=\s*$|[;&|\n])(?![=~\^<>\d])"""), 0.3f, Severity.LOW),
        Rule("e8_force_git_push", Regex("""git\s+push\s+.*--force(-with-lease)?\b"""), 0.4f, Severity.LOW),
        Rule("e8_unsafe_tempfile", Regex("""mktemp|>\s*/tmp/[a-zA-Z0-9_\-.]+\s*;?\s*#\s*unsafe|>\s*/tmp/.*2>&1"""), 0.2f, Severity.LOW)
    )

    private val highIds = allRules.filter { it.severity == Severity.HIGH }.map { it.id }.toSet()

    data class ReviewResult(
        val passed: Boolean,
        val hitRuleIds: List<String>,
        val highCount: Int,
        val confidence: Float // ∈[0,1]
    ) {
        companion object {
            val PASS_NO_HIT = ReviewResult(true, emptyList(), 0, 0f)
        }
    }

    /** C.4.4 2 步审查：Step 1 light（快）→ Step 2 full（全文）。 */
    fun reviewSkillOrPlanBody(bodyText: String, tier: ZthPresetTier): ReviewResult {
        if (bodyText.isBlank() || tier == ZthPresetTier.DISABLED) return ReviewResult.PASS_NO_HIT
        // Step 1：light（前500+后500），8 HIGH only
        val lightSample = buildString {
            append(bodyText.take(500))
            if (bodyText.length > 1000) append("\n[...SKIP MIDDLE FOR LIGHT SCAN...]\n")
            if (bodyText.length > 500) append(bodyText.takeLast(500))
        }
        val lightHits = allRules.filter { it.severity == Severity.HIGH && it.regex.containsMatchIn(lightSample) }
        if (lightHits.isNotEmpty()) {
            val conf = (lightHits.sumOf { it.weight.toDouble() } / 16.0f).toFloat().coerceIn(0f, 1f)
            return ReviewResult(false, lightHits.map { it.id }, lightHits.size, conf)
        }
        if (tier == ZthPresetTier.MINIMAL) {
            // 档位 1：light 通过就算通过（C.4.2 MINIMAL 只查 8 HIGH）
            return ReviewResult.PASS_NO_HIT.copy(passed = true)
        }
        // Step 2：20 条全文扫（档位 2/3）
        val hits = allRules.filter { it.regex.containsMatchIn(bodyText) }
        val weighted = hits.sumOf { it.weight.toDouble() }.toFloat()
        val conf = (weighted / 20.0f).coerceIn(0f, 1f)
        val threshold = when (tier) {
            ZthPresetTier.STRICT -> 0.20f  // 档位 3：只要命中任何一条加权即 FAIL
            ZthPresetTier.BALANCED -> 0.50f // 档位 2：中等
            else -> 0.95f // 档位 0/1 不会到达这里
        }
        val highC = hits.count { it.id in highIds }
        val fail = highC > 0 || conf >= threshold // 命中 HIGH 立即 FAIL（权重 2.0）
        return ReviewResult(!fail, hits.map { it.id }, highC, conf)
    }
}
