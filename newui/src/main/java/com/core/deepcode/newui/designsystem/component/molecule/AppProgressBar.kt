package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion

/**
 * 动画进度条（分子组 · AppProgressBar）：determinate，进度经 animateFloat 平滑过渡 + pill 圆角 cap。
 * 用 FastOutSlowIn 缓动让进度"追着"目标值走，比原生瞬时跳变更高级。
 */
@Composable
fun AppProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = AppColor.BrandPrimary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = AppMotion.Med.toInt(), easing = FastOutSlowInEasing),
        label = "progress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth(),
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
    )
}