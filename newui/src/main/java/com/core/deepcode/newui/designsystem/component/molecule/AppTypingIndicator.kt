package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlin.math.PI
import kotlin.math.sin

/**
 * AI 输入中（分子组 · AppTypingIndicator）：三点正弦错峰跳动 + 呼吸透明度，聊天气泡内
 * 的"正在思考"微妙动效，替代朴素的省略号。
 */
@Composable
fun AppTypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = AppColor.BrandPrimary,
    dotSize: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val phase = i * 0.18f
            val anim by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "dot$i",
            )
            val wave = ((anim + phase) % 1f)
            val bounce = sin(wave * 2f * PI.toFloat())
            Box(
                modifier = Modifier
                    .offset(y = (-5.dp) * bounce)
                    .size(dotSize)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = 0.35f + 0.65f * bounce }
                    .background(dotColor),
            )
        }
    }
}