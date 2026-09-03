package com.core.deepcode.feature.agent.presentation.component.richsegment
import androidx.compose.ui.res.stringResource
import com.core.deepcode.R
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.core.deepcode.core.theme.LocalAppDarkMode
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.git.presentation.component.highlightCode
import com.core.deepcode.feature.git.presentation.component.inferSyntaxLanguage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Terminal
import dev.snipme.highlights.model.SyntaxLanguage

/** 对外暴露的「跳转行为」参数：渲染某个富文本点击时的联动动作。 */
data class SegmentationNavigationActions(
    /** 点击 URL 链接：打开内置服务浏览器。 */
    val onOpenUrl: (String) -> Unit,
    /** 点击文件路径：打开文件预览（若宿主未提供实现则走复制路径兜底）。 */
    val onOpenFilePath: (String) -> Unit,
)

private val codeBlockCorner = RoundedCornerShape(10.dp)
private val codeBlockHeaderHeight = 34.dp

@Composable
internal fun SegmentRenderer(
    segments: List<RichSegment>,
    color: Color,
    compact: Boolean,
    nav: SegmentationNavigationActions,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bodySize = if (compact) 13.sp else 14.sp
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val lineBreak = LineBreak.Simple
    val baseStyle = MaterialTheme.typography.bodyMedium.copy(
        color = color,
        fontSize = bodySize,
        lineHeight = bodyLineHeight,
        lineBreak = lineBreak
    )

    CompositionLocalProvider(LocalContentColor provides color) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
        ) {
            for (seg in segments) {
                when (seg) {
                    RichSegment.Blank -> Spacer(Modifier.height(if (compact) 2.dp else 4.dp))

                    is RichSegment.Paragraph -> ParagraphCard(seg.inlines, baseStyle, nav)

                    is RichSegment.Heading -> HeadingCard(seg.level, seg.inlines, color)

                    is RichSegment.Quote -> QuoteCard(seg.lines, color)

                    is RichSegment.BulletList -> BulletListCard(seg.items, baseStyle, nav)

                    is RichSegment.OrderedList -> OrderedListCard(seg.items, seg.start, baseStyle, nav)

                    is RichSegment.CodeBlock -> CodeBlockCard(seg, isDark)

                    is RichSegment.Table -> TableCard(seg, baseStyle, nav, isDark)

                    is RichSegment.Command -> CommandCard(seg.command, isDark)
                }
            }
        }
    }
}

// ============ 段落 / 行内 ============

@Composable
private fun ParagraphCard(
    inlines: List<Inline>,
    baseStyle: TextStyle,
    nav: SegmentationNavigationActions,
    modifier: Modifier = Modifier
) {
    LinkAwareText(inlines = inlines, baseStyle = baseStyle, nav = nav, modifier = modifier)
}

/** 渲染行内元素文本：对 URL / FILE 注解做出点击响应，支持复制 / 打开。 */
@Composable
private fun LinkAwareText(
    inlines: List<Inline>,
    baseStyle: TextStyle,
    nav: SegmentationNavigationActions,
    modifier: Modifier = Modifier
) {
    val codeBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val annotated = androidx.compose.runtime.remember(inlines, baseStyle, codeBackground) {
        renderInlines(inlines, baseStyle, codeBackground)
    }

    ClickableText(
        text = annotated,
        style = baseStyle,
        modifier = modifier,
    ) { offset ->
        // 先尝试 URL 注解
        val urlHit = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
            .firstOrNull()
        if (urlHit != null) {
            nav.onOpenUrl(urlHit.item)
            return@ClickableText
        }
        val fileHit = annotated.getStringAnnotations(tag = "FILE", start = offset, end = offset)
            .firstOrNull()
        if (fileHit != null) {
            nav.onOpenFilePath(fileHit.item)
            return@ClickableText
        }
    }
}

private fun renderInlines(
    inlines: List<Inline>,
    baseStyle: TextStyle,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        for (inline in inlines) {
            when (inline) {
                is Inline.Plain -> append(inline.text)
                is Inline.Bold -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(inline.text)
                    pop()
                }
                is Inline.Italic -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(inline.text)
                    pop()
                }
                is Inline.BoldItalic -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(inline.text)
                    pop()
                }
                is Inline.Code -> {
                    pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (baseStyle.fontSize.value - 0.5f).sp,
                        background = codeBackground
                    ))
                    append(" ")
                    append(inline.code)
                    append(" ")
                    pop()
                }
                is Inline.Url -> {
                    val label = inline.label ?: inline.url
                    val start = length
                    pushStringAnnotation(tag = "URL", annotation = inline.url)
                    pushStyle(SpanStyle(
                        color = Color(0xFF0984E3),
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium
                    ))
                    append(label)
                    pop()
                    pop()
                    val end = length
                    addStringAnnotation(tag = "URL", annotation = inline.url, start = start, end = end)
                }
                is Inline.FilePath -> {
                    val start = length
                    pushStringAnnotation(tag = "FILE", annotation = inline.path)
                    pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (baseStyle.fontSize.value - 0.5f).sp,
                        color = Color(0xFF00B894)
                    ))
                    append(inline.path)
                    pop()
                    pop()
                    val end = length
                    addStringAnnotation(tag = "FILE", annotation = inline.path, start = start, end = end)
                }
            }
        }
    }.takeUnless { it.isEmpty() } ?: AnnotatedString("")
}

// ============ 标题 ============

@Composable
private fun HeadingCard(level: Int, inlines: List<Inline>, color: Color) {
    val typography = MaterialTheme.typography
    val (style, spacingTop) = when (level) {
        1 -> typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = color) to 0.dp
        2 -> typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = color) to 0.dp
        3 -> typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = color) to 0.dp
        4 -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = color) to 0.dp
        5 -> typography.bodyLarge.copy(fontWeight = FontWeight.Medium, color = color) to 0.dp
        else -> typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = color) to 0.dp
    }
    if (spacingTop > 0.dp) Spacer(Modifier.height(spacingTop))
    val nav = LocalSegmentNavActions.current
    LinkAwareText(inlines = inlines, baseStyle = style, nav = nav)
}

// ============ 引用 ============

@Composable
private fun QuoteCard(lines: List<String>, color: Color) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(if (isDark) Color(0xFF3A506B) else Color(0xFF9CA3AF))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = color.copy(alpha = 0.75f),
                        fontStyle = FontStyle.Italic
                    )
                )
            }
        }
    }
}

// ============ 列表 ============

@Composable
private fun BulletListCard(
    items: List<List<Inline>>,
    baseStyle: TextStyle,
    nav: SegmentationNavigationActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { inlines ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp, start = 2.dp, end = 10.dp)) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                LinkAwareText(
                    inlines = inlines,
                    baseStyle = baseStyle,
                    nav = nav,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun OrderedListCard(
    items: List<List<Inline>>,
    start: Int,
    baseStyle: TextStyle,
    nav: SegmentationNavigationActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { idx, inlines ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${idx + start}. ",
                    style = baseStyle.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(end = 4.dp)
                )
                LinkAwareText(
                    inlines = inlines,
                    baseStyle = baseStyle,
                    nav = nav,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============ 代码块（含语言标签 + 复制 + 展开/收起） ============

@Composable
private fun CodeBlockCard(seg: RichSegment.CodeBlock, isDark: Boolean) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(seg) { mutableStateOf(true) }
    val syntaxLang = seg.language?.let { inferByLabel(it) }
    val lineCount = seg.code.count { it == '\n' } + 1
    val shouldCollapse = lineCount > 30

    val bg = if (isDark) Color(0xFF101722) else Color(0xFFF1F5F9)
    val fg = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val label = seg.language?.uppercase() ?: "CODE"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(codeBlockCorner)
            .background(bg)
            .border(BorderStroke(1.dp, Color(0xFFE4E7EC).copy(alpha = if (isDark) 0.12f else 0.5f)), codeBlockCorner)
            .animateContentSize(animationSpec = tween(160))
    ) {
        // Header：语言角标 + 复制 + 展开/收起
        Row(
            Modifier
                .fillMaxWidth()
                .height(codeBlockHeaderHeight)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF0984E3), Color(0xFF00B894))
                    )
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(Modifier.weight(1f))
            val plainText = seg.code
            androidx.compose.material3.IconButton(
                modifier = Modifier.size(30.dp),
                onClick = { clipboard.setText(AnnotatedString(plainText)) }
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.ui______224996c0),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(15.dp)
                )
            }
            if (shouldCollapse) {
                androidx.compose.material3.IconButton(
                    modifier = Modifier.size(30.dp),
                    onClick = { expanded = !expanded }
                ) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.ui____def9e98b) else stringResource(R.string.ui____e2edde5a),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        SelectionContainer {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (!expanded) Modifier.height(260.dp).verticalScroll(rememberScrollState())
                        else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                val rawStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = fg
                )
                val highlighted = syntaxLang?.let { highlightCode(seg.code, it) }
                if (highlighted != null) {
                    // 水平滚动：避免窄屏上长行被截断
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        // 行号
                        val gutterW = remember(lineCount) { (lineCount.toString().length * 8 + 10).dp }
                        Column(
                            modifier = Modifier
                                .width(gutterW)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.End
                        ) {
                            (1..lineCount).forEach { n ->
                                Text(
                                    text = "$n",
                                    style = rawStyle.copy(
                                        color = fg.copy(alpha = 0.42f),
                                        fontSize = 11.5.sp
                                    ),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Text(
                            text = highlighted,
                            style = rawStyle,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    Text(
                        text = seg.code,
                        style = rawStyle
                    )
                }
            }
        }
    }
}

private fun inferByLabel(label: String): SyntaxLanguage? {
    // 常见别名映射
    return when (label.lowercase()) {
        "sh", "bash", "shell", "zsh" -> SyntaxLanguage.SHELL
        "yml", "yaml" -> null
        "js", "javascript" -> SyntaxLanguage.JAVASCRIPT
        "ts", "typescript" -> SyntaxLanguage.TYPESCRIPT
        "kt", "kotlin" -> SyntaxLanguage.KOTLIN
        "py" -> SyntaxLanguage.PYTHON
        else -> inferSyntaxLanguage("dummy.$label")
    }
}

// ============ 命令卡片（终端黑） ============

@Composable
private fun CommandCard(command: String, isDark: Boolean) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark) Color(0xFF0B1018) else Color(0xFF0F172A))
            .border(
                BorderStroke(
                    1.dp,
                    Color(0xFF22C55E).copy(alpha = 0.6f)
                ),
                RoundedCornerShape(10.dp)
            )
            .clickable { clipboard.setText(AnnotatedString(command)) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧绿色提示色条
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Color(0xFF22C55E))
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Rounded.Terminal,
            contentDescription = null,
            tint = if (LocalAppDarkMode.current) Color(0xFF4ADE80) else Color(0xFF22C55E),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        SelectionContainer {
            Box(
                Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = "$ ",
                        color = Color(0xFF22C55E),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = command,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
        androidx.compose.material3.IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.ui______ee92cd5e),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ============ 表格 ============

@Composable
private fun TableCard(
    seg: RichSegment.Table,
    baseStyle: TextStyle,
    nav: SegmentationNavigationActions,
    isDark: Boolean
) {
    val colCount = seg.header.size
    if (colCount <= 0) return
    val headerBg = if (isDark) Color(0xFF152030) else Color(0xFF0984E3).copy(alpha = 0.1f)
    val rowBgAlt = if (isDark) Color(0xFF111A27) else Color(0xFFF8FAFC)
    val borderColor = if (isDark) Color(0xFF2A3F56) else Color(0xFFCBD5E1)

    Surface(
        color = Color.Transparent,
        border = BorderStroke(0.8.dp, borderColor),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // 外层横向滚动：表格宽度超屏宽时水平滚
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 表头
                Row(Modifier.fillMaxWidth().background(headerBg)) {
                    for (cell in seg.header) {
                        Box(
                            Modifier
                                .widthIn(min = 110.dp)
                                .padding(8.dp)
                                .border(
                                    BorderStroke(0.5.dp, borderColor.copy(alpha = 0.5f))
                                )
                        ) {
                            LinkAwareText(
                                inlines = cell,
                                baseStyle = baseStyle.copy(fontWeight = FontWeight.SemiBold),
                                nav = nav
                            )
                        }
                    }
                }
                // 数据行（交替底色）
                seg.rows.forEachIndexed { rIdx, row ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (rIdx % 2 == 1) Modifier.background(rowBgAlt) else Modifier)
                    ) {
                        for (cell in row) {
                            Box(
                                Modifier
                                    .widthIn(min = 110.dp)
                                    .padding(8.dp)
                                    .border(
                                        BorderStroke(
                                            0.5.dp,
                                            borderColor.copy(alpha = 0.5f)
                                        )
                                    )
                            ) {
                                LinkAwareText(inlines = cell, baseStyle = baseStyle, nav = nav)
                            }
                        }
                    }
                }
            }
        }
    }
}
