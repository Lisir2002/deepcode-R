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

    /** 完全匹配查找：发送时调用，命中返回对应 handler，否则 null。 */
    fun findExact(input: String): SlashCommandHandler? =
        all.firstOrNull { it.matches(input) }
}
