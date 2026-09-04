package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 统计卡（分子组 · AppStatCard）：主数字用 animateFloat 平滑滚动到目标值（count-up），
 * 右下可带趋势徽标；左上可选图标块。Dashboard / 概览页核心数据单元。
 */
@Composable
fun AppStatCard(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { it.toInt().toString() },
    icon: ImageVector? = null,
    iconColor: Color = AppColor.BrandPrimary,
    trend: String? = null,
    trendUp: Boolean = true,
) {
    val animatedValue by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = AppMotion.Slow.toInt(), easing = FastOutSlowInEasing),
        label = "statValue",
    )
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = AppElevation.Z1,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(AppSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconContainer(icon = icon, tint = Color.White, background = iconColor)
                Spacer(Modifier.size(AppSpacing.Md))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(AppSpacing.Md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = format(animatedValue.toDouble()),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (trend != null) {
                val trendColor = if (trendUp) AppColor.StatusSuccess else AppColor.StatusDanger
                Box(
                    Modifier
                        .clip(RoundedCornerShape(AppRadius.Pill))
                        .background(trendColor.copy(alpha = 0.14f))
                        .padding(horizontal = AppSpacing.Sm, vertical = 2.dp),
                ) {
                    Text(
                        text = trend,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = trendColor,
                    )
                }
            }
        }
    }
}
}