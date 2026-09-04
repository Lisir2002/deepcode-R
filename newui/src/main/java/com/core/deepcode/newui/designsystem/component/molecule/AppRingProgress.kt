package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion

/**
 * 环形进度（分子组 · AppRingProgress）：品牌渐变扫环 + 中心百分比 count-up，
 * 用于存储/任务/额度等仪表类指标，替代朴素的 M3 ProgressIndicator。
 */
@Composable
fun AppRingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    boxSize: Dp = 96.dp,
    strokeWidth: Dp = 8.dp,
    progressColor: Color = AppColor.BrandPrimary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    showLabel: Boolean = true,
    labelFormat: (Float) -> String = { "${(it * 100).toInt()}%" },
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = AppMotion.Slow.toInt(), easing = FastOutSlowInEasing),
        label = "ringProgress",
    )
    Box(
        modifier = modifier.size(boxSize),
        contentAlignment = Alignment.Center,
    ) {
        val surfaceColor = MaterialTheme.colorScheme.surface
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(
                size.width - stroke,
                size.height - stroke,
            )
            val topLeft = Offset(inset, inset)
            // 轨道
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // 进度渐变扫环
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(progressColor, surfaceColor.copy(alpha = 0f), AppColor.BrandAccent, progressColor),
                    center = Offset(size.width / 2f, size.height / 2f),
                ),
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (showLabel) {
            Text(
                text = labelFormat(animated),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}