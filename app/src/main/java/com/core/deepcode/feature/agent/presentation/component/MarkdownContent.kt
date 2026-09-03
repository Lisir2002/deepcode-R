package com.core.deepcode.feature.agent.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.core.deepcode.feature.agent.presentation.component.richsegment.SegmentedContent
import com.core.deepcode.feature.agent.presentation.component.richsegment.SegmentedRenderCache

/**
 * 渲染缓存：按文本缓存自研分段结果。对外命名与旧版本一致，保持调用点不迁移。
 */
typealias MarkdownRenderCache = SegmentedRenderCache

internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

/**
 * 富文本渲染入口。
 *
 * 对外签名与旧版本完全兼容（`text` / `color` / `cache` / `compact`），内部
 * 已从 mikepenz.markdown 切换到自研「富文本分段管线」：
 *  - 块级分段：CodeBlock / Heading / BulletList / OrderedList / Quote / Command /
 *    Table / Paragraph / Blank 按显式边界拆分，不再依赖第三方 parser 的贪心边界；
 *  - 行内分段：InlineCode / Url / FilePath / Bold / Italic / BoldItalic 各自拆分，
 *    URL 只吃到 URL 合法字符，不再被后面的中文句号 / 正文吞进链接；
 *  - 专属容器：URL 蓝+下划线→进内置浏览器，FilePath 绿色等宽→打开文件预览，
 *    Command 终端黑绿卡+复制，CodeBlock 渐变 header+复制+行号+超长度展开收起，
 *    Table 圆角+斑马纹+水平滚，引用块左侧色条，列表彩色圆点/序号。
 *
 * 可选回调 `onOpenUrl` / `onOpenFilePath` 未提供时，走 Intent + 复制兜底 Toast。
 */
@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    onOpenUrl: ((url: String) -> Unit)? = null,
    onOpenFilePath: ((path: String) -> Unit)? = null
) {
    SegmentedContent(
        text = text,
        color = color,
        modifier = modifier,
        cache = cache,
        compact = compact,
        onOpenUrl = onOpenUrl,
        onOpenFilePath = onOpenFilePath
    )
}

/**
 * 加载/出错时的降级文本：保持与段落同样式，让用户至少能看到纯文本。
 *
 * （保留旧函数名，未来如 SegmentedContent 有需要可再次作为降级兜底使用。）
 */
@Composable
internal fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = color,
            lineHeight = 20.sp,
            lineBreak = LineBreak.Simple
        )
    )
}