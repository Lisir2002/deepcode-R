package com.R.codecore.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.R.codecore.R
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.MessageAccent
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.theme.resolveLine
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Star

/** 模型思考中的临时指示：灰阶跳动点，无容器。 */
@Composable
internal fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 上下文压缩期间的临时状态，不落库。 */
@Composable
internal fun CompactionProgressBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.chat_compressing_context),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 网络重试期间的临时状态，不落库。 */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.chat_retrying, attempt, maxRetries),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 模型流式吐字时的实时输出：灰阶直出 Markdown，尾部带三个跳动的点表示仍在生成。
 * 本轮结束后由落库的助手消息接管。
 *
 * 流式阶段也渲染 Markdown，但使用采样文本降低解析频率；最终落库消息再走常规缓存渲染。
 */
@Composable
internal fun StreamingBubble(text: String) {
    Column {
        MarkdownContent(
            text = text,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.xs))
        TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant, dotSize = 5.dp)
    }
}

/** 思维链折叠阈值：超过此行数视为过长，自动折叠为前 N 行 + 「展开剩余 X 行」。 */
internal const val REASONING_COLLAPSE_LINE_LIMIT = 8

/**
 * 思考过程可折叠块：灰阶弱化（onSurfaceVariant 正文 + 小标签标题），无容器。
 * 点击标题栏折叠/展开。折叠判定按行数阈值：超过 [REASONING_COLLAPSE_LINE_LIMIT] 行视为
 * 「过长」，自动折叠为前 N 行 + 「展开剩余 X 行」。用户手动 toggle 后以用户选择为准。
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    initiallyExpanded: Boolean = true,
    cache: MarkdownRenderCache? = null
) {
    var userToggled by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val overThreshold = lineCount > REASONING_COLLAPSE_LINE_LIMIT
    // 自动折叠：仅在用户尚未手动 toggle 过时生效；用户手动展开/折叠后以用户选择为准
    val effectiveExpanded = if (userToggled) expanded else (initiallyExpanded && !overThreshold)
    val barColor = MessageAccent.Reasoning.resolveLine()
    // 淡紫竖条贯穿整个思考块 + 标题行短条，形成引用块式分层
    AccentBarContainer(barColor = barColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    expanded = !expanded
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShortAccentBar(barColor)
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = Brand.IconGray,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_thinking_process),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (effectiveExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                tint = Brand.IconGray,
                modifier = Modifier.size(16.dp)
            )
        }
        if (effectiveExpanded) {
            Spacer(Modifier.height(Spacing.xs))
            MarkdownContent(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                cache = cache,
                compact = true,
                modifier = Modifier.pointerInput(text) {
                    detectTapGestures(
                        onDoubleTap = {
                            userToggled = true
                            expanded = false
                        }
                    )
                }
            )
        } else if (overThreshold) {
            // 折叠态：显示最新内容（尾部 N 行）+「还有 X 行」
            Spacer(Modifier.height(Spacing.xs))
            val tailText = remember(text) {
                text.lines().takeLast(REASONING_COLLAPSE_LINE_LIMIT).joinToString("\n")
            }
            Text(
                text = tailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = REASONING_COLLAPSE_LINE_LIMIT,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = (REASONING_COLLAPSE_LINE_LIMIT * 18).dp)
            )
            val hidden = lineCount - REASONING_COLLAPSE_LINE_LIMIT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable {
                        userToggled = true
                        expanded = true
                    }
                    .padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.chat_expand_remaining, hidden),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 三个循环跳动的点：通用「正在输入/生成」指示器，取代转圈 spinner。
 * 三点以固定相位差依次上下弹跳，形成波浪式律动。
 *
 * 性能优化：用 graphicsLayer { translationY } 替代 offset(y)，动画值变化在 draw 阶段
 * 处理而不触发 compose/recompose，消除无限动画导致父布局每帧重组的开销。
 * 容器高度固定，防止布局波动传递到 LazyColumn。
 */
@Composable
internal fun TypingDots(
    color: Color,
    dotSize: Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(dotSize + 10.dp)
    ) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5f at 180
                        0f at 360
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            if (index < 2) Spacer(Modifier.width(4.dp))
        }
    }
}
