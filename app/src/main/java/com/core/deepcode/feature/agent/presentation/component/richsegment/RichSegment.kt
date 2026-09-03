package com.core.deepcode.feature.agent.presentation.component.richsegment

/**
 * 富文本分段渲染的块级片段。
 *
 * 自研文本分段器把一段原始文本按「块级类型」切成独立片段；每个片段有专属的渲染容器。
 * 目的：用显式边界替代通用 Markdown 解析器的「贪心边界」，从根上杜绝 URL 被中文标点 /
 * 后续正文吞掉这类问题，并让不同文本类型拥有各自的美观样式与独立交互。
 */
sealed interface RichSegment {

    /** 普通段落（内部含行内元素）。 */
    data class Paragraph(val inlines: List<Inline>) : RichSegment

    /** 标题，[level] 1..6。 */
    data class Heading(val level: Int, val inlines: List<Inline>) : RichSegment

    /** 引用块（> 开头的一行或多行）。 */
    data class Quote(val lines: List<String>) : RichSegment

    /** 无序列表。 */
    data class BulletList(val items: List<List<Inline>>) : RichSegment

    /** 有序列表，[start] 为起始序号。 */
    data class OrderedList(val items: List<List<Inline>>, val start: Int) : RichSegment

    /** 围栏代码块（``` 包裹），[language] 为围栏后标注的语言标识（可能为 null）。 */
    data class CodeBlock(val language: String?, val code: String) : RichSegment

    /** Markdown 表格。 */
    data class Table(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>
    ) : RichSegment

    /** 终端命令（行首 `$` / `#` 前缀的行）。 */
    data class Command(val command: String) : RichSegment

    /** 空行（用于块间距）。 */
    data object Blank : RichSegment
}

/**
 * 行内元素，出现在段落 / 标题 / 列表项 / 表格单元格内部。
 */
sealed interface Inline {

    /** 纯文本。 */
    data class Plain(val text: String) : Inline

    /** 裸 URL / 自动链接，[label] 为可选显示文本。 */
    data class Url(val url: String, val label: String?) : Inline

    /** 本地文件路径。 */
    data class FilePath(val path: String) : Inline

    /** 行内代码（`...`）。 */
    data class Code(val code: String) : Inline

    /** 粗体。 */
    data class Bold(val text: String) : Inline

    /** 斜体。 */
    data class Italic(val text: String) : Inline

    /** 粗斜体。 */
    data class BoldItalic(val text: String) : Inline
}