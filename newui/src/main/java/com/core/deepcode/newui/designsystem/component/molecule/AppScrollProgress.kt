package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 进度线（分子组 · AppScrollProgress）：长文档 / Agent 多步执行时的顶部细进度条，
 * 传入 [fraction]（0..1）自动平滑推进填充，可贴在滚动容器顶部作阅读 / 执行进度。
 */
@Composable
fun AppScrollProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    barColor: Color = AppColor.BrandPrimary,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(220),
        label = "scrollProgress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(AppRadius.Pill))
                .background(barColor),
        )
    }
}