package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 跑马灯（分子组 · AppMarquee）：一行内容无缝无限横滑，顶部状态条 / 滚动日志 / 常驻提醒。
 * 通过测量单份内容宽度并平移"多个周期"实现原子级无缝循环，内容不足一行也照常流动。
 */
@Composable
fun AppMarquee(
    modifier: Modifier = Modifier,
    speedMillis: Int = 9000,
    gap: Dp = AppSpacing.Xxl,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "marquee")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(speedMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marqueeOffset",
    )
    var periodPx by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { gap.toPx() }

    Box(
        modifier = modifier
            .clipToBounds()
            .clip(RoundedCornerShape(AppRadius.Sm))
            .background(background),
    ) {
        Row(Modifier.graphicsLayer { translationX = -periodPx * offset }) {
            Box(Modifier
                .onSizeChanged { periodPx = (it.width + gapPx).toFloat() }
                .padding(AppSpacing.Sm)) { content() }
            Spacer(Modifier.width(gap))
            Box(Modifier.padding(AppSpacing.Sm)) { content() }
        }
    }
}