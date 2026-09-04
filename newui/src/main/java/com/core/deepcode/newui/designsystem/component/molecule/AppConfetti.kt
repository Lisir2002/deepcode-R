package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class ConfettiParticle(
    val startX: Float,
    val drift: Float,
    val sizeFactor: Float,
    val speed: Float,
    val phase: Float,
    val freq: Float,
    val swing: Float,
    val rotationSpeed: Float,
    val color: Color,
)

/**
 * 庆祝彩带（分子组 · AppConfetti）：一簇彩色纸屑在卡片范围内飘落 + 往复摆动，循环播放下落，
 * 用于发版成功 / 任务完成 / 达成里程碑的庆祝反馈。
 */
@Composable
fun AppConfetti(
    modifier: Modifier = Modifier,
    count: Int = 26,
    durationMillis: Int = 3800,
    colors: List<Color> = listOf(
        AppColor.BrandPrimary,
        AppColor.BrandAccent,
        AppColor.StatusDanger,
        AppColor.StatusWarning,
        AppColor.StatusInfo,
    ),
) {
    if (count <= 0) return
    val particles = remember(count, colors) {
        val rnd = Random(7)
        List(count) {
            ConfettiParticle(
                startX = rnd.nextFloat(),
                drift = (rnd.nextFloat() - 0.5f) * 0.14f,
                sizeFactor = 0.035f + rnd.nextFloat() * 0.04f,
                speed = 0.28f + rnd.nextFloat() * 0.42f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
                freq = 1f + rnd.nextFloat() * 3f,
                swing = 0.03f + rnd.nextFloat() * 0.04f,
                rotationSpeed = (rnd.nextFloat() - 0.5f) * 3f,
                color = colors[rnd.nextInt(colors.size)],
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "confetti")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "confettiProgress",
    )

    Canvas(modifier) {
        particles.forEach { p ->
            val topY = -0.1f + t * p.speed
            if (topY in -0.35f..1.2f) {
                val x = p.startX + sin(t * 2f * PI.toFloat() * p.freq + p.phase) * p.swing + t * p.drift
                val px = x * size.width
                val py = topY * size.height
                val unit = p.sizeFactor * size.minDimension.coerceAtLeast(32f)
                withTransform({
                    rotate(t * 360f * p.rotationSpeed, pivot = Offset(px, py))
                }) {
                    drawRect(p.color, topLeft = Offset(px, py), size = Size(unit, unit * 1.6f))
                }
            }
        }
    }
}