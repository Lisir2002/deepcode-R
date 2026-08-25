package com.R.codecore.feature.agent.domain.command

import com.R.codecore.feature.agent.domain.ext.ExtensionLoader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 斜杠命令注册表。通过 Hilt multibinding 在构造时汇集所有 [SlashCommandHandler]，
 * 并叠加 [ExtensionLoader] 扫描到的声明式命令（`assets/ext/commands/` + 用户目录，方向 B1）。
 *
 * 用法：
 * - 输入框实时过滤：[filterByPrefix] 返回 [SlashCommandHandler.trigger] 以输入文本开头的命令。
 * - 发送时完全匹配：[findExact] 返回 [SlashCommandHandler.matches] 命中的命令。
 *
 * 新增硬编码命令无需改动此类——实现 [SlashCommandHandler] + `@Binds @IntoSet` 即自动纳入；
 * 新增声明式命令只需在扩展目录放一个 frontmatter 的 `.md`，同名时硬编码命令优先。
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards SlashCommandHandler>,
    private val extensionLoader: ExtensionLoader
) {
    /**
     * 所有已注册命令（硬编码 + 声明式），按 trigger 排序，保证菜单顺序稳定。
     * 每次访问实时合并，声明式命令热加载后立即生效。
     */
    val all: List<SlashCommandHandler>
        get() = (handlers + extensionLoader.commands()).sortedBy { it.trigger }

    /**
     * 前缀过滤：用于输入框实时匹配。
     * 当 [input] 为 "/" 时返回全部；否则返回 trigger 以 input 开头的命令。
     * 仅当 input 以 '/' 开头且不含换行时有意义，调用方负责此前提。
     */
    fun filterByPrefix(input: String): List<SlashCommandHandler> {
        if (input == "/") return all
        return all.filter { it.trigger.startsWith(input) }
    }

/**
 * 命令匹配结果：前缀命中的命令 + 解析出的参数（D0 前缀 + 参数匹配）。
 * 命令内部仍可用 [SlashCommandHandler.executeWithInput] 拿到原始输入自行解析。
 */
data class SlashCommandMatch(
    val command: SlashCommandHandler,
    /** 命令后第一个非空参数 token；无则空串。 */
    val args: String,
    /** 命令后的完整剩余文本（已 trim）。 */
    val text: String
)

    /** 完全匹配查找：发送时调用，命中返回对应 handler，否则 null。 */
    fun findExact(input: String): SlashCommandHandler? =
        all.firstOrNull { it.matches(input) }

    /**
     * 前缀 + 参数匹配：发送时解析斜杠命令（如 `/playbook start release`）。
     *
     * - 输入首个 token 恰等于某命令 [SlashCommandHandler.trigger] 即命中（兼容 [findExact]
     *   的完全相等语义；无参数时 behavior 一致，args 为空串）；
     * - 命中后剩余部分作为参数（args = 第一个 token，text = 完整剩余）。
     * 无命令命中返回 null。
     */
    fun parseCommand(input: String): SlashCommandMatch? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val head = trimmed.substringBefore(' ').substringBefore('\n')
        val command = all.firstOrNull { it.trigger == head } ?: return null
        val rest = trimmed.removePrefix(head).trim()
        val args = rest.substringBefore(' ').substringBefore('\n').takeIf { it.isNotEmpty() } ?: ""
        return SlashCommandMatch(command, args, rest)
    }
}
