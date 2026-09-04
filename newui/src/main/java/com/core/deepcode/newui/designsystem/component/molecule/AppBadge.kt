package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 数字徽标（分子组 · AppBadge）：pill 角标，用于未读数/通知数。计数变化时弹簧回弹缩放，
 * 数量 > [maxShow] 显示 "N+"。覆盖在图标右上角（外层用 Box 定位）。
 */
@Composable
fun AppBadge(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = AppColor.StatusDanger,
    textColor: Color = Color.White,
    maxShow: Int = 99,
) {
    if (count <= 0) return
    val display = if (count > maxShow) "$maxShow+" else count.toString()
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "badgeScale",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(color)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = AppSpacing.Sm, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = display,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 纯圆点徽标（分子组 · AppBadgeDot）：8dp 红点，用于在线/未读红点，常与 [AppBadge] 二选一。
 */
@Composable
fun AppBadgeDot(
    modifier: Modifier = Modifier,
    color: Color = AppColor.StatusDanger,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(color)
            .size(size),
    )
}