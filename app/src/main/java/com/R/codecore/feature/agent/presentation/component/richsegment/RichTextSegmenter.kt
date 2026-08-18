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
    //
    // URL regex 特别说明：
    //  - 前置 two negative lookbehind：(?<!\]\() 排除 [text](url) 内部 MD 链接场景（测试 urlInMarkdownLinkIsNotReSegmented）
    //                             (?<![^A-Za-z0-9]<) 排除 <https://url> 尖括号场景（测试 urlInAngleBracketsIsIgnored）
    //  - 字符集 [^\s<>"`\p{IsHan}] 显式排除中日韩汉字：防中文句号/正文被贪心吃进 URL
    //    （之前 URL 边界全靠 stripUrlTrailing 后置剥标点，但 URL 中间夹中文时它无能为力）
    //  - 右括号 )] 在 raw match 阶段先保留，后续 stripUrlTrailing 用配对法精确切
    private val INLINE_TOKEN = Regex(
        "`[^`\\n]+`" +
            "|(?<!\\]\\()(?<![^A-Za-z0-9]<)https?://[^\\s<>\"`\\u4e00-\\u9fff]+" +
            "|(?:~|\\.\\.?/|/)[^\\s()<>\"`]*\\.[A-Za-z0-9]{1,8}" +
            "|[\\w.-]+(?:/[\\w.-]+)+\\.[A-Za-z0-9]{1,8}" +
            "|\\*\\*\\*[^*\\n]+\\*\\*\\*" +
            "|\\*\\*[^*\\n]+\\*\\*" +
            "|(?<![*\\w])\\*[^*\\n]+\\*(?!\\*)"
    )

    // 第 1 轮标点剥离：只剥绝对不合法 URL 尾部的中文/全角标点。
    // 注意：此处不剥 ASCII 半角 ) ] ，留给括号配对逻辑精确处理（否则 Wiki (operating_system) 的尾 ) 被误吃）。
    private val URL_TRAILING_PUNCT_P1 = Regex("[。，、；：？！「」『』《》〈〉【】＂〝〞'）〕】,;:!?…—－]+$")

    // 第 2 轮标点剥离：括号配对完成后，再剥尾部残留的句点/逗号/分号等软边界。
    // 注意：这里不剥 ASCII ) ]，因为括号配对逻辑已保证尾部若有 ) ] 必然是成对合法的（例如 Wiki (operating_system)）。
    private val URL_TRAILING_PUNCT_P2 = Regex("[.,;:!?…—－]+$")

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
        if (token.isBlank()) return false
        // 显式前缀：~/  ./  ../  / —— 这些是用户明确写的路径信号，即使无扩展名也认（如 ./gradlew）
        val explicitPrefix = token.startsWith("/") || token.startsWith("~/") ||
            token.startsWith("./") || token.startsWith("../")
        val isRel = token.count { it == '/' } >= 1
        val hasExt = Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(token)
        return when {
            explicitPrefix && hasExt -> true
            explicitPrefix && isRel -> true    // 例：./gradlew  ~/.config/rcodecore  /data/local/tmp
            hasExt && isRel -> true            // 例：app/src/main/AndroidManifest.xml
            else -> false
        }
    }

    private fun stripUrlTrailing(url: String): String {
        // P1：剥全角/中文标点（绝对不会出现在合法 URL 尾部，同时不碰 ASCII 半角右括号）
        var s = URL_TRAILING_PUNCT_P1.replace(url, "")
        // 括号配对：仅把「未配对的右括号」及其后续内容切掉，合法配对（如 Wiki (operating_system)）原样保留
        var depth = 0
        var cut = -1
        for ((idx, ch) in s.withIndex()) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth-- else if (cut < 0) cut = idx
            }
        }
        if (cut >= 0) s = s.substring(0, cut)
        // P2：配对处理完，再剥尾部残留的半角右括号/点号/逗号等（都是 URL 非法尾字符）
        s = URL_TRAILING_PUNCT_P2.replace(s, "")
        // 再做一次 P1 兜底：P2 剥完后尾部若刚好露出中文标点再清掉
        s = URL_TRAILING_PUNCT_P1.replace(s, "")
        return s
    }
}