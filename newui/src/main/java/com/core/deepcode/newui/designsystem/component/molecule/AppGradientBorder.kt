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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 流动渐变边框（分子组 · AppGradientBorder）：绕中心缓慢旋转的锥形渐变色带，仅在卡片边缘
 * 露出一圈高光描边，内部以内容背景埋色——高级卡片的点睛强调，优于静态单色边框。
 */
@Composable
fun AppGradientBorder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.Lg),
    borderWidth: Dp = 2.dp,
    colors: List<Color> = listOf(
        AppColor.BrandPrimary,
        AppColor.BrandAccent,
        AppColor.StatusInfo,
        AppColor.BrandPrimary,
    ),
    durationMillis: Int = 4500,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "gradientBorder")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gradientAngle",
    )
    // 锥形渐变自转，让高光沿边框一圈圈流淌。
    val sweep = remember(colors) { Brush.sweepGradient(colors = colors) }

    Box(modifier = modifier.clip(shape)) {
        Canvas(Modifier.matchParentSize().rotate(angle)) {
            drawRect(brush = sweep)
        }
        // 内部埋色盖住中心，仅边缘露出一圈渐变描边。
        Box(
            Modifier
                .matchParentSize()
                .padding(borderWidth)
                .clip(shape)
                .background(background),
        ) {
            content()
        }
    }
}