package com.R.codecore.feature.chatrender

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Brand
import com.R.codecore.core.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三点流式脉冲：标题后的「…」呼吸点（依次错峰，0/200/400ms）。
 * 决策 D3：纯文字流在标题后展示三点脉冲作为「思考中 / 执行中」状态。
 */
@Composable
fun StreamingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streamingDots")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

/**
 * 各款式共用的灰阶「任务头」行：标题（折叠态显示摘要）/ 流式三点脉冲（标题后）/ 流式文案 /
 * 时间戳 / 展开折叠箭头。款式通过 [leading] 附加节点或色块（如时间线的任务头节点）。
 */
@Composable
fun BubbleTaskHeader(
    isExpanded: Boolean,
    isStreaming: Boolean,
    title: String,
    timestamp: Long,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    headerTrailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        leading()
        if (isExpanded) {
            Box(Modifier.weight(1f))
        } else {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isStreaming) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = 20.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (isStreaming) {
            StreamingDots(color = MaterialTheme.colorScheme.primary)
        }
        headerTrailing()
        if (timestamp > 0) {
            Text(
                text = formatHm(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(
                if (isExpanded) R.string.chat_task_collapse else R.string.chat_task_expand
            ),
            tint = Brand.IconGray,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 各款式共用的灰阶「片段标签」行：小标签 + 展开折叠箭头。
 * [leading] 用于附加短色条 / 节点；[trailing] 用于附加时间戳（时间线款式）。
 */
@Composable
fun BubbleSubLabel(
    label: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    alignEnd: Boolean = false,
    bold: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) {
            Arrangement.spacedBy(Spacing.sm, Alignment.End)
        } else {
            Arrangement.spacedBy(Spacing.sm)
        }
    ) {
        leading()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (alignEnd) Modifier else Modifier.weight(1f)
        )
        trailing()
        Icon(
            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 短时间（HH:mm），任务头 / 部分款式用。 */
internal fun formatHm(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("")
}
