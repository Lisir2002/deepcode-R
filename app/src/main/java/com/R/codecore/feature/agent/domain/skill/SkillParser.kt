package com.R.codecore.feature.agent.domain.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.model.AgentMode
import org.yaml.snakeyaml.Yaml
import java.io.File

object SkillParser {
    private const val TAG = "SkillParser"
    private const val MAX_DESC_CHARS = 500

    private val VALID_CHECKS = setOf(
        RuntimeProbe.CHECK_CMD, RuntimeProbe.CHECK_MOD, RuntimeProbe.CHECK_NPM,
        RuntimeProbe.CHECK_DPKG, RuntimeProbe.CHECK_FILE
    )

    /**
     * 解析一个 skill 目录；无 SKILL.md 或无 name 时视为非法，返回 null。
     *
     * RC74 元数据升级：解析 version/author/tags/modes/type/dependencies/entry/mcp_tool/icon
     * 等新字段，全部向后兼容——缺省字段给默认值，`type` 缺省按 [SkillType.PROMPT]。
     * 本次新增：auto_trigger（自动触发开关）+ trigger_conditions（自动触发条件，供工作流触发决策器）。
     * `enabled` 不在此处解析（运行时状态由 Room skill_state 表持久化，不写回技能文件），
     * 统一默认 [Skill.enabled] = true，由调用方（SkillStateRepository）叠加启用状态。
     */
    fun parse(dir: File, source: SkillSourceType = SkillSourceType.LOCAL): Skill? {
        // 优先查找 SKILL.md，如果没有则回退查找 CLAUDE.md（兼容某些只用 CLAUDE.md 的技能）
        var file = File(dir, "SKILL.md")
        if (!file.exists()) {
            file = File(dir, "CLAUDE.md")
        }
        if (!file.exists()) {
            // 兼容大小写情况
            file = dir.listFiles()?.firstOrNull {
                it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true)
            } ?: return null
        }

        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取 Skill 文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        // name 优先取 frontmatter，缺省回退到目录名
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: dir.name
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter["required_tools"]
            if (toolsRaw is List<*>) {
                toolsRaw.filterIsInstance<String>()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 required_tools 失败: ${file.absolutePath}", e)
            emptyList()
        }

        val tags = try {
            val tagsRaw = frontmatter["tags"]
            if (tagsRaw is List<*>) tagsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val dependencies = try {
            val depsRaw = frontmatter["dependencies"]
            if (depsRaw is List<*>) depsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val modes = try {
            val modesRaw = frontmatter["modes"]
            if (modesRaw is List<*>) {
                modesRaw.filterIsInstance<String>()
                    .mapNotNull { m -> runCatching { AgentMode.valueOf(m.trim().uppercase()) }.getOrNull() }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }

        val type = try {
            val typeRaw = frontmatter["type"]?.toString()?.trim()?.uppercase()
            if (typeRaw == null) SkillType.PROMPT
            else runCatching { SkillType.valueOf(typeRaw) }.getOrDefault(SkillType.PROMPT)
        } catch (e: Exception) {
            SkillType.PROMPT
        }

        val entry = frontmatter["entry"]?.toString()?.takeIf { it.isNotBlank() }
        val mcpTool = frontmatter["mcp_tool"]?.toString()?.takeIf { it.isNotBlank() }
        val icon = frontmatter["icon"]?.toString()?.takeIf { it.isNotBlank() }

        // 作用域 v2：scope 支持 global / agent / conversation（缺省 global）；旧值 common 兼容映射为 global。
        // agent 级需 agent-type。
        val scope = try {
            val raw = frontmatter["scope"]?.toString()?.trim()?.uppercase()
            when (raw) {
                null, "" -> SkillScope.GLOBAL
                "COMMON" -> SkillScope.GLOBAL // 旧枚举兼容：COMMON 并入 GLOBAL
                "GLOBAL" -> SkillScope.GLOBAL
                "AGENT" -> SkillScope.AGENT
                "CONVERSATION" -> SkillScope.CONVERSATION
                else -> SkillScope.GLOBAL
            }
        } catch (e: Exception) {
            SkillScope.GLOBAL
        }
        val agentType = when (scope) {
            SkillScope.AGENT -> frontmatter["agent_type"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }

        // S-3：requires_runtime 运行时预检求值树（完整布尔 DSL，见 RuntimeProbeExpr）。
        // 支持的声明形式（向后兼容）：
        //   1. expr 字符串："cmd:node>=18<=22 && (mod:numpy || cmd:python3)" —— 完整布尔 DSL
        //   2. expr 对象：{expr: "..."} —— 同上，包一层键
        //   3. YAML 对象列表：[{check: cmd, name: node, min_version: "18", max_version: "22", install_hint: "apk add nodejs"}]
        //   4. 字符串列表：[cmd:node>=18, mod:numpy] —— 兼容旧版，按 atom 解析
        //   5. 逗号分隔字符串："node, python3" —— 兼容旧版，按 atom 解析
        //   6. 单对象：{check: cmd, name: node} —— 视为单探针
        val requiresRuntime = try {
            val raw = frontmatter["requires_runtime"]
            when (raw) {
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) null
                    else SkillProbeExprParser.parse(trimmed)
                        // DSL 解析失败 → 回退旧逗号分隔格式（不破坏存量技能），仍失败则丢弃并告警
                        ?: SkillProbeExprParser.fromLeaves(
                            trimmed.split(',').mapNotNull { SkillProbeExprParser.parseAtomSegment(it) }
                        )?.also { expr ->
                            if (expr is RuntimeProbeExpr.And &&
                                expr.children.size != trimmed.split(',').size
                            ) {
                                FileLogger.w(TAG, "requires_runtime 部分项无法解析，已忽略: $trimmed")
                            }
                        }
                }
                is Map<*, *> -> {
                    val expr = raw["expr"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    if (expr != null) SkillProbeExprParser.parse(expr)
                    else parseProbeObject(raw)?.let { RuntimeProbeExpr.Leaf(it) }
                }
                is List<*> -> SkillProbeExprParser.fromLeaves(
                    raw.mapNotNull { item ->
                        when (item) {
                            is Map<*, *> -> parseProbeObject(item)
                            is String -> item.trim().takeIf { it.isNotBlank() }
                                ?.let { SkillProbeExprParser.parseAtomSegment(it) }
                            else -> null
                        }
                    }
                )
                else -> null
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 requires_runtime 失败", e)
            null
        }

        // 自动触发配置：auto_trigger（bool）+ trigger_conditions（自然语言条件，供工作流触发决策器判断）。
        val autoTrigger = runCatching {
            when (val raw = frontmatter["auto_trigger"]) {
                is Boolean -> raw
                is String -> raw.trim().equals("true", ignoreCase = true)
                else -> false
            }
        }.getOrDefault(false)
        val triggerConditions = frontmatter["trigger_conditions"]?.toString()?.takeIf { it.isNotBlank() }

        // 自动触发关键词（高置信规则）：支持 YAML list 或逗号分隔字符串，供工作流规则层快筛直接命中。
        val triggerKeywords = try {
            val raw = frontmatter["trigger_keywords"]
            if (raw is List<*>) raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotBlank() }
            else if (raw is String) raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return Skill(
            id = dir.name,
            name = name,
            description = description,
            version = frontmatter["version"]?.toString()?.takeIf { it.isNotBlank() } ?: "0.0.0",
            author = frontmatter["author"]?.toString()?.takeIf { it.isNotBlank() },
            tags = tags,
            modes = modes,
            type = type,
            dependencies = dependencies,
            enabled = true,
            source = source,
            requiredTools = requiredTools,
            requiresRuntime = requiresRuntime,
            dir = dir,
            entry = entry,
            mcpTool = mcpTool,
            icon = icon,
            scope = scope,
            agentType = agentType,
            autoTrigger = autoTrigger,
            triggerConditions = triggerConditions,
            triggerKeywords = triggerKeywords,
            instructions = body.trim()
        )
    }

    /** 从 YAML 对象解析单条探针（`check`/`name`/`min_version`/`max_version`/`install_hint`）；name 缺失返回 null。 */
    private fun parseProbeObject(item: Map<*, *>): RuntimeProbe? {
        val check = item["check"]?.toString()?.trim()?.lowercase()
            ?.takeIf { it in VALID_CHECKS } ?: RuntimeProbe.CHECK_CMD
        val name = item["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val minVersion = item["min_version"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val maxVersion = item["max_version"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val installHint = item["install_hint"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        return RuntimeProbe(check, name, minVersion, maxVersion, installHint)
    }

    /**
     * 利用 SnakeYAML 切分并解析 YAML frontmatter。
     *
     * 公开供其它资产解析方复用（R03 AgentAssetRegistry / R08 规则引擎 frontmatter 解析）。
     * @return (frontmatter 键值对, 正文)
     */
    fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}
