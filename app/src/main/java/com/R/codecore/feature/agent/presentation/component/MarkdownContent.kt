package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * 文本渲染前的 URL 预规范化。
 *
 * mikepenz.markdown / commonmark-java 的默认 autolink 会把 URL 后面紧跟的非 ASCII
 * 字符（中文句号、逗号、引号、书名号等）也吞进链接里，导致链接变成
 * `https://a.b/。我们可以设置…`。这里对输入文本做一次扫描：
 *  - 对「裸 URL」（未嵌在 Markdown 链接 `[text](url)` / `<url>` / 代码块里的 URL）
 *    先匹配其合法后缀（ASCII 字母数字和 URL 允许字符），把尾随的「中文/空白/非 URL
 *    ASCII 标点」剥离出来，放回外层文本。
 *  - 对规范后的裸 URL 包成 `<url>`（Markdown 显式自动链接），彻底锁定链接边界，
 *    避免下游解析器二次扩展。
 */
internal object MarkdownUrlPreprocessor {
    // 裸 URL 起点：不捕获 Markdown 链接 `](url)` / `<url>` 中已经有包裹的 URL；
    //         不捕获代码块/行内代码里的字符串（不处理代码块边界，仅简单跳过）。
    // 匹配策略：先定位一个 ASCII URL（http/https），再用「合法 URL 字符集合」限定结尾。
    private val BARE_URL_REGEX = Regex(
        pattern = """(?<!\()(?<!\]\()(?<!<)https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        option = RegexOption.IGNORE_CASE
    )

    // 尾随剥离：剥离末尾常见的中英文标点（不包括括号/方括号/花括号本身，这些
    // 可能出现在合法 URL 里；未配对的右括号由 stripUnmatchedRightBrackets 处理）。
    private val TRAILING_PUNCTUATION_REGEX = Regex(
        """[。，、；：？！「」『』《》〈〉【】""''""（）,.;:!?"'…—]+${'$'}"""
    )

    fun normalize(input: String): String {
        if (input.length < 10) return input
        return BARE_URL_REGEX.replace(input) { m ->
            val raw = m.value
            // 1. 先去掉尾部标点（中英文句号、逗号、引号、感叹号、问号等）
            val punctStripped = TRAILING_PUNCTUATION_REGEX.replace(raw, "")
            // 2. 处理未配对的右括号：尾部如果有多余的 ) ] }，在括号配对位置截断
            val bracketCleaned = stripUnmatchedRightBrackets(punctStripped)
            if (bracketCleaned.isEmpty()) {
                raw
            } else {
                val tail = raw.substring(bracketCleaned.length)
                "<$bracketCleaned>$tail"
            }
        }
    }

    private fun stripUnmatchedRightBrackets(url: String): String {
        var depth = 0
        var earliestCut = -1
        for ((i, ch) in url.withIndex()) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth-- else {
                    if (earliestCut < 0) earliestCut = i
                }
            }
        }
        // 遍历完后如果还有括号栈未归零（leftOver > 0），保持原样（像 Wikipedia
        // 这种有内嵌括号的合法 URL 本身是配对的，不应该动）。
        return if (earliestCut < 0) url else url.substring(0, earliestCut)
    }
}

internal class MarkdownRenderCache(
    private val maxEntries: Int = 80
) {
    private val parsedStates = object : LinkedHashMap<String, MarkdownParseState.Success>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState.Success>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(text: String): MarkdownParseState.Success? = parsedStates[text]

    fun put(state: MarkdownParseState.Success) {
        parsedStates[state.content] = state
    }
}

internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val mdColors = markdownColor(
        text = color,
        codeBackground = if (isDark) Color(0xFF152030) else Color(0xFFE8EDF3),
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = if (isDark) Color(0xFF2A3F56) else Color(0xFFCBD5E1),
        tableBackground = if (isDark) Color(0xFF152030) else Color(0xFFF1F5F9),
    )

    val typography = MaterialTheme.typography
    // compact：正文、列表用 bodySmall，标题降一档，用于思考气泡等次要文本区域
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    // 段落/列表文本：开启 LineBreak.Simple，让长 URL/长路径等无空格长文本自动换行，
    // 替代默认只会在空白处断行的行为（会导致单行溢出被裁剪）。
    val bodyLineBreak = LineBreak.Simple
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight, lineBreak = bodyLineBreak),
        code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = codeSize, color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
        inlineCode = TextStyle(fontFamily = FontFamily.Monospace, color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight, lineBreak = bodyLineBreak),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight, lineBreak = bodyLineBreak),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    val normalizedText = remember(text) { MarkdownUrlPreprocessor.normalize(text) }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        val cachedState = cache?.get(normalizedText)
        val parsedState = if (cachedState != null) {
            cachedState
        } else {
            val mdState = rememberMarkdownState(content = normalizedText, retainState = true)
            val state by mdState.state.collectAsState()
            state
        }

        if (parsedState is MarkdownParseState.Success) {
            LaunchedEffect(cache, parsedState) {
                cache?.put(parsedState)
            }
        }

        when (parsedState) {
            is MarkdownParseState.Success -> Markdown(
                state = parsedState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // 关闭段落文本的 animateContentSize：快速流式更新下它会持续追赶目标高度，反而弹性抖动。
                animations = markdownAnimations(animateTextSize = { this }),
                components = markdownComponents(
                    codeFence = {
                        MarkdownHighlightedCodeFence(
                            content = it.content,
                            node = it.node,
                            highlightsBuilder = highlightsBuilder,
                            showHeader = true,
                        )
                    },
                    codeBlock = {
                        MarkdownHighlightedCodeBlock(
                            content = it.content,
                            node = it.node,
                            highlightsBuilder = highlightsBuilder,
                            showHeader = true,
                        )
                    },
                    // 库默认 maxLines=1 + Ellipsis，单元格长文会被截断；这里放开为完整多行显示。
                    table = {
                        MarkdownTable(
                            content = it.content,
                            node = it.node,
                            style = it.typography.table,
                            headerBlock = { content, header, tableWidth, style ->
                                MarkdownTableHeader(
                                    content = content,
                                    header = header,
                                    tableWidth = tableWidth,
                                    style = style,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                )
                            },
                            rowBlock = { content, header, tableWidth, style ->
                                MarkdownTableRow(
                                    content = content,
                                    header = header,
                                    tableWidth = tableWidth,
                                    style = style,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                )
                            },
                        )
                    },
                ),
            )

            is MarkdownParseState.Loading -> PlainMarkdownText(
                text = normalizedText,
                color = color,
                modifier = modifier
            )

            is MarkdownParseState.Error -> PlainMarkdownText(
                text = normalizedText,
                color = color,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}

