package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 星级评分（分子组 · AppRatingBar）：逐颗点评亮，点选时弹簧回弹放大（Animatable 每次触发），
 * 空心/实心切换，CircleShape 点击热区，服务反馈 / 评价类场景。
 */
@Composable
fun AppRatingBar(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 5,
    color: Color = AppColor.StatusWarning,
    size: Dp = AppSizing.IconL,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
    ) {
        repeat(max) { i ->
            val filled = i < value
            val scale = remember { Animatable(0.86f) }
            LaunchedEffect(filled) {
                if (filled) {
                    scale.snapTo(0.86f)
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                } else {
                    scale.snapTo(0.86f)
                }
            }
            Icon(
                imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (filled) color else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable { onValueChange(i + 1) }
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
            )
        }
    }
}