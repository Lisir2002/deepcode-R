package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 时间线节点色调（分子组 · AppTimeline 的 item.tone）。 */
enum class AppTimelineTone { Default, Success, Info, Danger }

/** 时间线条目数据模型（分子组 · AppTimeline）。 */
data class AppTimelineItem(
    val title: String,
    val subtitle: String? = null,
    val time: String? = null,
    val icon: ImageVector? = null,
    val tone: AppTimelineTone = AppTimelineTone.Default,
)

/**
 * 垂直时间线（分子组 · AppTimeline）：节点色点 + 连接线 + 交错回弹浮现，
 * 用于会话历史 / 操作日志 / 版本记录等时序场景。
 */
@Composable
fun AppTimeline(
    items: List<AppTimelineItem>,
    modifier: Modifier = Modifier,
    defaultToneColor: Color = AppColor.BrandPrimary,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            TimelineRow(
                item = item,
                isLast = index == items.lastIndex,
                defaultToneColor = defaultToneColor,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    item: AppTimelineItem,
    isLast: Boolean,
    defaultToneColor: Color,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val pop by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "timelinePop",
    )
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "timelineFade",
    )
    val tone = when (item.tone) {
        AppTimelineTone.Default -> defaultToneColor
        AppTimelineTone.Success -> AppColor.StatusSuccess
        AppTimelineTone.Info -> AppColor.StatusInfo
        AppTimelineTone.Danger -> AppColor.StatusDanger
    }
    Row(
        modifier = Modifier.fillMaxWidth().alpha(fade),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧轨道：节点 + 连接线
        Box(
            modifier = Modifier.width(26.dp).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = 0.6f + 0.4f * pop
                        scaleY = 0.6f + 0.4f * pop
                    }
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
            }
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 26.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(start = AppSpacing.Md)
                .padding(bottom = AppSpacing.Xl)
                .weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = item.icon ?: when (item.tone) {
                        AppTimelineTone.Success -> Icons.Rounded.Check
                        AppTimelineTone.Danger -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.time != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.subtitle != null) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}