package com.core.deepcode.core.util

/**
 * 日志行解析结果。
 * @param date 日期 "2026-08-08"
 * @param level 日志等级，解析失败为 null
 * @param tag 来源标签，如 "McpManager"
 * @param raw 原始行内容
 */
data class ParsedLogLine(
    val date: String,
    val level: LogLevel?,
    val tag: String,
    val raw: String
)

/**
 * 日志行解析工具。
 *
 * FileLogger 输出格式：`yyyy-MM-dd HH:mm:ss.SSS LEVEL [TAG] message`
 * 不匹配该格式的行（如堆栈跟踪、分隔线）视为「附属行」，
 * 过滤时始终保留（不单独判定），但不会用于提取 Tag/Level。
 */
object LogLineParser {

    // 2026-08-08 12:34:56.789 VERBOSE [SomeTag] message
    // 注意：LEVEL 全部大写，TAG 在方括号内
    private val LOG_PATTERN = Regex(
        """^(\d{4}-\d{2}-\d{2})\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(VERBOSE|DEBUG|INFO|WARN|ERROR)\s+\[([^\]]+)\]\s+(.*)$"""
    )

    /** 解析单行日志。若格式不匹配返回 null（附属行）。 */
    fun parse(line: String): ParsedLogLine? {
        val match = LOG_PATTERN.matchEntire(line) ?: return null
        return ParsedLogLine(
            date = match.groupValues[1],
            level = runCatching { LogLevel.valueOf(match.groupValues[2]) }.getOrNull(),
            tag = match.groupValues[3],
            raw = line
        )
    }

    /** 从多行日志中提取所有出现过的 Tag（去重、按首现顺序）。 */
    fun extractTags(lines: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (line in lines) {
            val parsed = parse(line)
            if (parsed != null) {
                seen.add(parsed.tag)
            }
        }
        return seen.toList()
    }
}