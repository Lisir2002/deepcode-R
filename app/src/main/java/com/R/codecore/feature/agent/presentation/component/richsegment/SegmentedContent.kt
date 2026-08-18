package com.R.codecore.feature.agent.presentation.component.richsegment

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自研「富文本分段渲染」入口：对外签名与原 MarkdownContent 保持一致，内部直接走
 * [RichTextSegmenter] + [SegmentRenderer] 渲染管线，替换掉原先的 mikepenz.markdown。
 *
 * 优势：
 *  - URL / 文件路径 / 命令 / 代码块全部显式分段，永不出现「URL 吞掉中文标点+正文」这种贪心边界 bug；
 *  - 每类片段各自专属的美观容器（色条、角标、复制、展开收起、行号、主题渐变）；
 *  - URL 点击进内置浏览器（有 Controller 时）或系统浏览器兜底，路径点击打开文件预览或复制路径兜底。
 */
@Composable
internal fun SegmentedContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: SegmentedRenderCache? = null,
    compact: Boolean = false,
    onOpenUrl: ((url: String) -> Unit)? = null,
    onOpenFilePath: ((path: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 1. 计算分段结果（按 text 缓存，不包含 color 等无关维度）
    val cached = cache?.get(text)
    var segments by remember(text, cache) {
        mutableStateOf(cached ?: RichTextSegmenter.segment(text))
    }

    // 放到后台线程做 segment，避免超长文本卡主线程；第一次如果是 cache hit 则直接命中。
    if (cached == null) {
        LaunchedEffect(text) {
            val result = withContext(Dispatchers.Default) { RichTextSegmenter.segment(text) }
            segments = result
            cache?.put(text, result)
        }
    }

    // 2. 组装「点击行为」：URL / 路径各自响应动作
    val actions = remember(onOpenUrl, onOpenFilePath, context, clipboard) {
        SegmentationNavigationActions(
            onOpenUrl = { url ->
                when {
                    onOpenUrl != null -> onOpenUrl(url)
                    else -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                scope.launch {
                                    clipboard.setText(AnnotatedString(url))
                                    Toast.makeText(context, "已复制链接：$url", Toast.LENGTH_SHORT).show()
                                }
                            }
                    }
                }
            },
            onOpenFilePath = { path ->
                when {
                    onOpenFilePath != null -> onOpenFilePath(path)
                    else -> {
                        scope.launch {
                            clipboard.setText(AnnotatedString(path))
                            Toast.makeText(context, "已复制路径：$path", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    // 3. 渲染
    CompositionLocalProvider(LocalContentColor provides color) {
        LinkAwareSegmentColumn(
            segments = segments,
            color = color,
            compact = compact,
            actions = actions,
            modifier = modifier
        )
    }
}

/** 负责 URL / 文件路径的点击桥接。 */
@Composable
private fun LinkAwareSegmentColumn(
    segments: List<RichSegment>,
    color: Color,
    compact: Boolean,
    actions: SegmentationNavigationActions,
    modifier: Modifier = Modifier
) {
    // 注意：块级内容走 SegmentRenderer（含段落），段落内部行内元素已经带 AnnotatedString
    // 的 URL/FILE tag。但我们的 Text 不是 ClickableText，所以单独给段落/标题/列表/表格
    // 等内部含行内元素的卡片都换成 LinkableParagraphCard（通过 ClickableText + 注解匹配）。
    // 为了让 SegmentRenderer 用上这种「可点击文本」，我们通过 CompositionLocal 暴露 actions，
    // 并在 ParagraphCard/HeadingCard/List 内部用 ClickableText。
    //
    // 为降低改动，这里直接提供一个覆盖整段的 CompositionLocal，并让容器改用 LinkableParagraphCard。
    CompositionLocalProvider(LocalSegmentNavActions provides actions) {
        SegmentRenderer(
            segments = segments,
            color = color,
            compact = compact,
            nav = actions,
            modifier = modifier
        )
    }
}

@Immutable
data class SegmentedRenderCache(
    val maxEntries: Int = 80,
    val map: LinkedHashMap<String, List<RichSegment>> = object : LinkedHashMap<String, List<RichSegment>>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RichSegment>>?): Boolean {
            return size > maxEntries
        }
    }
) {
    fun get(text: String): List<RichSegment>? = map[text]
    fun put(text: String, segments: List<RichSegment>) {
        map[text] = segments
    }
}

val LocalSegmentNavActions = androidx.compose.runtime.compositionLocalOf<SegmentationNavigationActions> {
    error("SegmentNavActions not provided")
}
