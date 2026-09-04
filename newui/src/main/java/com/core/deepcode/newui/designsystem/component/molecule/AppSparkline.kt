package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion

/**
 * 迷你趋势线（分子组 · AppSparkline）：细化到统计卡的不可见细节——用 PathMeasure 对折线
 * 做绘制进度裁剪（reveal），随底部渐变填充淡入，结尾圆点到位，动态读数级微交互。
 * 零依赖纯 Canvas，数据即 `List<Float>`。
 */
@Composable
fun AppSparkline(
    data: List<Float>,
    modifier: Modifier = Modifier,
    strokeColor: Color = AppColor.BrandPrimary,
    fillColor: Color = strokeColor.copy(alpha = 0.16f),
    strokeWidth: Dp = 2.dp,
    showDot: Boolean = true,
) {
    if (data.size < 2) return
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(data.size) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = AppMotion.Slow.toInt(), easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val maxV = data.max()
        val minV = data.min()
        val range = (maxV - minV).coerceAtLeast(0.001f)
        val stepX = size.width / (data.size - 1)
        val yFor: (Float) -> Float = { v -> size.height - ((v - minV) / range) * size.height }

        val linePath = Path()
        data.forEachIndexed { i, v ->
            val x = stepX * i
            if (i == 0) linePath.moveTo(x, yFor(v)) else linePath.lineTo(x, yFor(v))
        }

        // 渐隐渐显的面积填充（淡入）
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor.copy(alpha = fillColor.alpha * reveal.value), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
        )

        // 折线按 reveal 进度绘制（自左向右生长）
        val pathMeasure = PathMeasure()
        pathMeasure.setPath(linePath, false)
        val partial = Path()
        pathMeasure.getSegment(0f, pathMeasure.length * reveal.value, partial, true)
        drawPath(
            path = partial,
            color = strokeColor,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 结尾圆点
        if (showDot && reveal.value >= 0.985f) {
            val last = data.lastIndex
            val dot = Offset(stepX * last, yFor(data[last]))
            drawCircle(strokeColor, radius = strokeWidth.toPx() * 1.5f, center = dot)
            drawCircle(Color.White, radius = strokeWidth.toPx() * 0.85f, center = dot)
        }
    }
}