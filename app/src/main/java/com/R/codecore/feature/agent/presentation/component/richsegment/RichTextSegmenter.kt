package com.R.codecore.feature.agent.presentation.component.richsegment

/**
 * 自研富文本分段器：把一段原始文本按「块级类型」切成 [RichSegment] 列表，
 * 段落/标题/列表项/表格单元格内部再做「行内元素」切分（[Inline]）。
 *
 * 设计要点：
 *  - 用显式边界替代通用 Markdown 解析器的「贪心边界」，URL 只吃到合法 URL 字符为止，
 *    后续中文标点 / 正文不再被吞进链接。
 *  - 代码块（``` ）内的内容在块级阶段即被整段隔离，绝不进入行内解析，内部 URL/星号不误判。
 *  - 所有逻辑为纯函数、无 Android 依赖，可独立单测。
 */
object RichTextSegmenter {

    /** 分隔块：一行或多行文本 + 该块的类型。仅用于分词阶段的中间结构。 */
    private sealed interface BlockLine {
        val lines: List<String>
    }

    /** 对整段文本做块级分段。 */
    fun segment(text: String): List<RichSegment> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val result = mutableListOf<RichSegment>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            when {
                // 围栏代码块：``` 或 ~~~
                isCodeFence(line) -> {
                    val fenceChar = line.trimStart().take(1)
                    val language = line.trim().removePrefix(fenceChar.repeat(3)).trim().takeIf { it.isNotEmpty() }
                    val codeLines = mutableListOf<String>()
                    i++
                    var closed = false
                    while (i < lines.size) {
                        val l = lines[i]
                        if (l.trim().startsWith(fenceChar.repeat(3))) {
                            closed = true
                            i++
                            break
                        }
                        codeLines.add(l)
                        i++
                    }
                    result.add(RichSegment.CodeBlock(language, codeLines.joinToString("\n")))
                    if (!closed && i >= lines.size) {
                        // 未闭合：codeLines 已含余下全部内容，继续即可（i 已越界）
                    }
                }

                // 表格：连续 | 开头的行（至少表头 + 分隔行）
                isTableStart(line) && hasTableContinuation(lines, i) -> {
                    val table = parseTable(lines, i)
                    result.add(table.segment)
                    i = table.nextIndex
                }

                // 标题：# 空格
                isHeading(line) -> {
                    val trimmed = line.trimStart()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val headingText = trimmed.drop(level).trim()
                    result.add(RichSegment.Heading(level, InlineTokenizer.parse(headingText)))
                    i++
                }

                // 引用块：连续 > 行
                isQuote(line) -> {
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && isQuote(lines[i])) {
                        quoteLines.add(lines[i].substringAfter('>').trimStart())
                        i++
                    }
                    result.add(RichSegment.Quote(quoteLines))
                }

                // 命令：$ 前缀
                isCommand(line) -> {
                    result.add(RichSegment.Command(line.trimStart().removePrefix("$").trim()))
                    i++
                }

                // 无序列表：- / * / + （连续）
                isBulletItem(line) -> {
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size && isBulletItem(lines[i])) {
                        val markerRemoved = lines[i].substringAfter(' ').trim()
                        items.add(InlineTokenizer.parse(markerRemoved))
                        i++
                    }
                    result.add(RichSegment.BulletList(items))
                }

                // 有序列表：数字. / 数字) （连续）
                isOrderedItem(line) -> {
                    val items = mutableListOf<List<Inline>>()
                    var start = 1
                    while (i < lines.size && isOrderedItem(lines[i])) {
                        val trimmed = lines[i].trimStart()
                        if (items.isEmpty()) {
                            start = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                        }
                        val afterMarker = trimmed.substringAfter('.').substringAfter(')').trim()
                        // 针对 "1." 与 "1)"，统一取分隔符后内容
                        val content = trimmed.removePrefix(trimmed.takeWhile { it.isDigit() })
                            .removePrefix(".").removePrefix(")").trim()
                        items.add(InlineTokenizer.parse(content.ifBlank { afterMarker }))
                        i++
                    }
                    result.add(RichSegment.OrderedList(items, start))
                }

                // 空行
                line.isBlank() -> {
                    result.add(RichSegment.Blank)
                    i++
                }

                // 段落：连续非空普通行（合并，段内换行保留）
                else -> {
                    val paraLines = mutableListOf<String>()
                    while (i < lines.size && isPlainContentLine(lines[i])) {
                        paraLines.add(lines[i])
                        i++
                    }
                    val paraText = paraLines.joinToString("\n").trim()
                    if (paraText.isNotEmpty()) {
                        result.add(RichSegment.Paragraph(InlineTokenizer.parse(paraText)))
                    }
                }
            }
        }
        return result
    }

    // ---- 块级判定 ----

    private fun isCodeFence(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    private fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (!t.startsWith("#")) return false
        val level = t.takeWhile { it == '#' }.length
        return level in 1..6 && t.length > level && t[level] == ' '
    }

    private fun isQuote(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith(">") && (t.length == 1 || t[1] == ' ')
    }

    private fun isCommand(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("$ ") && t.length > 2
    }

    private fun isBulletItem(line: String): Boolean {
        val t = line.trimStart()
        return (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) && t.length > 2
    }

    private fun isOrderedItem(line: String): Boolean {
        val t = line.trimStart()
        val numLen = t.takeWhile { it.isDigit() }.length
        if (numLen == 0) return false
        if (t.length <= numLen) return false
        val sep = t[numLen]
        return (sep == '.' || sep == ')') && t.length > numLen + 1 && t[numLen + 1] == ' '
    }

    private fun isTableStart(line: String): Boolean = line.trim().startsWith("|")

    private fun hasTableContinuation(lines: List<String>, start: Int): Boolean {
        // 第二行必须是分隔行（| --- | --- |）
        if (start + 1 >= lines.size) return false
        val sep = lines[start + 1].trim()
        return sep.startsWith("|") && sep.contains("---")
    }

    private fun isPlainContentLine(line: String): Boolean {
        return line.isNotBlank() &&
            !isCodeFence(line) &&
            !isHeading(line) &&
            !isQuote(line) &&
            !isCommand(line) &&
            !isBulletItem(line) &&
            !isOrderedItem(line) &&
            !isTableStart(line)
    }

    // ---- 表格解析 ----

    private data class TableParseResult(val segment: RichSegment.Table, val nextIndex: Int)

    private fun parseTable(lines: List<String>, start: Int): TableParseResult {
        var i = start
        val rawRows = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().startsWith("|")) {
            rawRows.add(lines[i].trim())
            i++
        }
        val cells = rawRows.map { row -> splitTableRow(row).map { it.trim() } }

        // 跳过第二行分隔行（纯 --- 结构行）
        val header = cells.getOrNull(0)?.map { InlineTokenizer.parse(it) } ?: emptyList()
        val dataRows = mutableListOf<List<List<Inline>>>()
        for (r in 2 until cells.size) {
            val rowCells = cells[r]
            val parsed = rowCells.map { InlineTokenizer.parse(it) }
            // 对齐列数：不足补空，多余丢弃，保证矩形
            val aligned = (0 until header.size).map { col -> parsed.getOrElse(col) { emptyList() } }
            dataRows.add(aligned)
        }
        return TableParseResult(RichSegment.Table(header, dataRows), i)
    }

    private fun splitTableRow(row: String): List<String> {
        // 去掉首尾的 "|"，按剩余 "|" 切分
        var s = row
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split("|")
    }
}

/**
 * 行内元素分词器：把一行/一段普通文本切成 [Inline] 列表。
 */
object InlineTokenizer {

    // 优先级从高到低：行内代码 > 裸 URL > 文件路径 > 粗斜体 > 粗体 > 斜体。
    // alternation 靠左优先；URL 与文件路径之间靠「URL 含 :// 且排他字符集」自然区分。
    private val INLINE_TOKEN = Regex(
        "`[^`\\n]+`" +
            "|https?://[^\\s<>\"'`]+" +
            "|(?:~|\\.\\.?/|/)[^\\s()<>\"'`]*\\.[A-Za-z0-9]{1,8}" +
            "|[\\w.-]+(?:/[\\w.-]+)+\\.[A-Za-z0-9]{1,8}" +
            "|\\*\\*\\*[^*\\n]+\\*\\*\\*" +
            "|\\*\\*[^*\\n]+\\*\\*" +
            "|(?<![*\\w])\\*[^*\\n]+\\*(?!\\*)"
    )

    // URL 尾部需要剥离的标点：这些显然不属于 URL 本体。
    private val URL_TRAILING_PUNCT = Regex("[。，、；：？！「」『』《》〈〉【】\"'）)】,.;:!?…—]+$")

    fun parse(text: String): List<Inline> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<Inline>()
        var lastEnd = 0
        var m = INLINE_TOKEN.find(text)
        while (m != null) {
            if (m.range.first > lastEnd) {
                result.add(Inline.Plain(text.substring(lastEnd, m.range.first)))
            }
            result.add(parseToken(m.value))
            lastEnd = m.range.last + 1
            m = INLINE_TOKEN.find(text, lastEnd)
        }
        if (lastEnd < text.length) {
            result.add(Inline.Plain(text.substring(lastEnd)))
        }
        return result
    }

    private fun parseToken(token: String): Inline {
        return when {
            token.startsWith("`") && token.endsWith("`") && token.length >= 2 ->
                Inline.Code(token.substring(1, token.length - 1))

            token.startsWith("http://") || token.startsWith("https://") -> {
                val cleaned = stripUrlTrailing(token)
                Inline.Url(cleaned, null)
            }

            token.startsWith("***") && token.endsWith("***") -> {
                Inline.BoldItalic(token.substring(3, token.length - 3))
            }

            token.startsWith("**") && token.endsWith("**") -> {
                Inline.Bold(token.substring(2, token.length - 2))
            }

            token.startsWith("*") && token.endsWith("*") -> {
                Inline.Italic(token.substring(1, token.length - 1))
            }

            // 文件路径判定：含 / 且（以 ~\/.\/../ 开头 或 多段相对路径）且结尾为扩展名
            isFilePathToken(token) -> Inline.FilePath(token)

            else -> Inline.Plain(token)
        }
    }

    private fun isFilePathToken(token: String): Boolean {
        if (token.contains("://")) return false
        val isAbs = token.startsWith("/") || token.startsWith("~/") ||
            token.startsWith("./") || token.startsWith("../")
        val isRel = token.count { it == '/' } >= 1
        val hasExt = Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(token)
        return hasExt && (isAbs || isRel) && token.isNotBlank()
    }

    private fun stripUrlTrailing(url: String): String {
        var s = URL_TRAILING_PUNCT.replace(url, "")
        // 剥掉尾部的未配对右括号（如 http://a.b) 或 http://a.b] ）
        var depth = 0
        var cut = -1
        for ((idx, ch) in s.withIndex()) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth-- else if (cut < 0) cut = idx
            }
        }
        if (cut >= 0) s = s.substring(0, cut)
        // 二次剥离首层未剥尽的标点（截断后可能留下 . 或 ，）
        s = URL_TRAILING_PUNCT.replace(s, "")
        return s
    }
}