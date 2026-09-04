package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 骨架屏微光（分子组 · AppShimmerBox）：surfaceVariant 底 + shape 圆角 + 周期性 shimmer 扫光。
 * 用作加载占位，比原生灰色块更高级（Linear 等设计系统同款骨架美学）。
 */
@Composable
fun AppShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.Sm),
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val tone = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val sweepDur = (AppMotion.Slow * 2).toInt()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = sweepDur, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shift",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(base)
            .drawBehind {
                val sweep = size.width * 1.8f
                val startX = -sweep + shift * (size.width + sweep)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, tone, Color.Transparent),
                        start = Offset(startX, 0f),
                        end = Offset(startX + sweep, size.height),
                    ),
                )
            },
    )
}