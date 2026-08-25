package com.R.codecore.feature.agent.domain.ext

import com.R.codecore.feature.agent.domain.command.SlashCommandContext
import com.R.codecore.feature.agent.domain.command.SlashCommandHandler
import com.R.codecore.feature.agent.domain.skill.SkillParser

/**
 * 声明式斜杠命令（方向 B1，对齐 Claude Code commands）。
 *
 * 文件形态：`commands/<name>.md`，frontmatter 声明 `name / description / argument-hint`，
 * 正文（frontmatter 之后）为命令触发时注入给 Agent 的指令。与硬编码 [SlashCommandHandler]
 * 并存、由 [ExtensionLoader] 统一扫描：
 * - 内置：`assets/ext/commands/<name>.md`（打包只读）
 * - 用户：`<rcodecore>/ext/commands/<name>.md`（同名覆盖、热加载）
 *
 * 触发：`/name` 或 `/name <参数>`。执行时把渲染后的正文作为用户消息送入 workflow
 * （走 [SlashCommandContext.sendAgentRequest]，由 ViewModel 落库并驱动 Agent 循环）。
 *
 * 参数渲染：正文中出现 `$ARGUMENTS` 占位符则原位替换为参数；无占位符时参数追加在正文末尾。
 */
class ExtensionCommand(
    val name: String,
    val summary: String,
    val argumentHint: String,
    val body: String
) : SlashCommandHandler {

    override val trigger: String get() = "/$name"
    override val label: String get() = name
    override val description: String get() = summary

    override fun matches(input: String): Boolean {
        val t = input.trim()
        return t == trigger || t.startsWith("$trigger ")
    }

    /** 无参执行空实现（实际统一走 [executeWithInput] 解析参数）。 */
    override fun execute(context: SlashCommandContext) = Unit

    override fun executeWithInput(context: SlashCommandContext, input: String) {
        val args = input.trim().removePrefix(trigger).trim()
        context.sendAgentRequest(render(args))
    }

    /** 渲染指令正文：`$ARGUMENTS` 占位符原位替换；无占位符时非空参数追加在末尾。 */
    fun render(args: String): String {
        if (ARGUMENTS_PLACEHOLDER in body) {
            return body.replace(ARGUMENTS_PLACEHOLDER, args)
        }
        return if (args.isBlank()) body else "$body\n\n$args"
    }

    companion object {
        const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

        /** 命令名合法字符：字母/数字/中划线/下划线（用于 `/name` 触发文本）。 */
        private val VALID_NAME = Regex("^[a-zA-Z0-9_-]+$")

        /**
         * 从文件解析命令；正文为空视为非法返回 null。frontmatter 无 `name` 时回退文件名（去 `.md`）。
         */
        fun parse(fileName: String, content: String): ExtensionCommand? {
            val (frontmatter, rawBody) = SkillParser.splitAndParseFrontmatter(content)
            val body = rawBody.trim()
            if (body.isBlank()) return null
            val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: fileName.removeSuffix(".md")
            if (!VALID_NAME.matches(name)) return null
            return ExtensionCommand(
                name = name,
                summary = frontmatter["description"]?.toString()?.trim() ?: "",
                argumentHint = frontmatter["argument-hint"]?.toString()?.trim() ?: "",
                body = body
            )
        }
    }
}
