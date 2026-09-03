package com.core.deepcode.feature.agent.domain.input

import javax.inject.Inject

/**
 * 用户输入解析器（D0 语法层，对齐 Claude Code parseUserInput 思路）：
 * 把用户请求解析为结构化 [ParsedInput] —— 首 token 斜杠命令 + 剩余 args/text、
 * `!`/`?` marker（FORCE/CONSULT）、轻量意图分类（task/query/file/command/unknown）。
 *
 * 纯规则实现，无 IO / 无依赖，供发送前分流（SlashCommandRegistry.parseCommand 的意图
 * 标注）与意图形态判定（IntentAnalyzeTool 规则预分类）共用。分类为轻量参考，非硬约束。
 *
 * `@Inject` 构造：供 Hilt 注入到 [com.core.deepcode.feature.agent.domain.tool.intent.IntentAnalyzeTool]。
 */
class UserInputParser @Inject constructor() {

    /** 轻量意图分类标签（对齐 Claude Code 意图识别，供形态判定参考）。 */
    enum class IntentLabel { TASK, QUERY, FILE, COMMAND, UNKNOWN }

    /** 用户显式优先级/咨询标记：`!` 立即执行、`?` 仅咨询（不落盘）。 */
    enum class Marker { FORCE, CONSULT, NONE }

    /** 解析结果。 */
    data class ParsedInput(
        /** 首 token 斜杠命令（含 `/`）；无则 null。 */
        val command: String? = null,
        /** 命令后的第一个非空参数 token；无则空串。 */
        val args: String = "",
        /** 去除 marker / 命令后的剩余文本。 */
        val text: String,
        /** 意图分类标签。 */
        val intentLabel: IntentLabel,
        /** 优先级/咨询标记。 */
        val marker: Marker
    )

    /** 解析入口：trim 后按 [Marker] → 命令 token → 剩余文本 → 意图分类 顺序处理。 */
    fun parse(raw: String): ParsedInput {
        var input = raw.trim()
        var marker = Marker.NONE
        if (input.startsWith("!")) {
            marker = Marker.FORCE
            input = input.drop(1).trim()
        } else if (input.startsWith("?")) {
            marker = Marker.CONSULT
            input = input.drop(1).trim()
        }

        var command: String? = null
        var args = ""
        var text = input
        if (input.startsWith("/")) {
            val firstToken = input.substringBefore(' ').substringBefore('\n')
            command = firstToken
            val rest = input.removePrefix(firstToken).trim()
            args = rest.substringBefore(' ').substringBefore('\n').takeIf { it.isNotEmpty() } ?: ""
            text = rest
        }
        val intent = classify(command, text)
        return ParsedInput(command, args, text, intent, marker)
    }

    /** 轻量意图分类（规则关键词，供形态判定参考，非硬约束）。 */
    fun classify(command: String?, text: String): IntentLabel {
        if (command != null) return IntentLabel.COMMAND
        val t = text.trim()
        if (t.isEmpty()) return IntentLabel.UNKNOWN
        val taskVerbs = listOf("实现", "修复", "开发", "添加", "创建", "重构", "编写", "优化", "新增", "落地", "支持", "完成")
        val querySuffixes = listOf("?", "？", "吗", "呢")
        val queryWords = listOf("为什么", "怎么", "如何", "是什么", "哪些", "可否", "能否", "有什么区别", "怎么看")
        val fileVerbs = listOf("删除", "移动", "重命名", "复制", "打开", "编辑")
        return when {
            queryWords.any { t.contains(it) } || querySuffixes.any { t.trimEnd().endsWith(it) } -> IntentLabel.QUERY
            fileVerbs.any { t.contains(it) } && t.length <= 40 -> IntentLabel.FILE
            taskVerbs.any { t.contains(it) } -> IntentLabel.TASK
            else -> IntentLabel.UNKNOWN
        }
    }
}
