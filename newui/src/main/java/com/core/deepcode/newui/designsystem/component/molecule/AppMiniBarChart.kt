package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion

/**
 * 迷你柱状图（分子组 · AppMiniBarChart）：柱体自下而上交错回弹生长 + 纵向渐变，
 * 用于趋势对比 / 各分区占用 / 耗时分布等可视化微件。
 */
@Composable
fun AppMiniBarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = AppColor.BrandPrimary,
    highlightColor: Color = AppColor.BrandAccent,
    highlightIndex: Int? = null,
) {
    if (values.isEmpty()) return
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values.size) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = AppMotion.Slow.toInt(), easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val maxV = values.max().coerceAtLeast(0.001f)
        val n = values.size
        val gap = 4.dp.toPx()
        val slotW = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            // 交错序列：第 i 根柱在 reveal 进度达到 (i+1)/n 后开始生长
            val local = ((reveal.value * n) - i).coerceIn(0f, 1f)
            val grow = if (local <= 0f) 0f else local * local * (3f - 2f * local) // smoothstep
            val barH = size.height * (v / maxV) * grow
            val x = i * (slotW + gap)
            val y = size.height - barH
            val color = if (i == highlightIndex) highlightColor else barColor
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color, color.copy(alpha = 0.22f)),
                    startY = y,
                    endY = size.height,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = Size(slotW, barH),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}