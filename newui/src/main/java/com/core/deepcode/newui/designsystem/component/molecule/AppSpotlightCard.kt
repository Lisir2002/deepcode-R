package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 聚光卡（分子组 · AppSpotlightCard）：卡片内一团径向高光沿 Lissajous 轨迹缓慢游弋，
 * 模拟电影级舞台聚光，上方承载内容，重点项/高亮推荐卡的氛围营造。
 */
@Composable
fun AppSpotlightCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.Lg),
    durationMillis: Int = 7000,
    highlight: Color = AppColor.BrandPrimary,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "spotlight")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spotlightProgress",
    )
    Box(modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surface)) {
        Canvas(Modifier.matchParentSize()) {
            val cx = ((sin(t * 2f * PI.toFloat()) + 1f) / 2f) * size.width
            val cy = ((cos(t * 2f * PI.toFloat()) + 1f) / 2f) * size.height
            val radius = size.minDimension * 0.8f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        highlight.copy(alpha = 0.30f),
                        highlight.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        Box(Modifier.matchParentSize().padding(AppSpacing.Lg)) { content() }
    }
}