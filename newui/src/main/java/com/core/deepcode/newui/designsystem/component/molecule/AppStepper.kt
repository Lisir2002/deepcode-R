package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 步进器（分子组 · AppStepper）：pill 卡片 + 加减按钮，数值经受控点击增减，数字用 AnimatedContent 过渡。
 * 用于数量/阈值/副本数等数值设置。
 */
@Composable
fun AppStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
) {
    val iconButtonSize = AppSizing.TouchTarget
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .height(iconButtonSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            icon = Icons.Rounded.Remove,
            contentDescription = "减少",
            enabled = value - step >= min,
            onClick = { onValueChange(value - step) },
        )
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(tween(AppMotion.Fast.toInt())) togetherWith fadeOut(tween(AppMotion.Fast.toInt())))
            },
            label = "stepperValue",
            modifier = Modifier.weight(1f),
        ) { v ->
            Box(Modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        StepButton(
            icon = Icons.Rounded.Add,
            contentDescription = "增加",
            enabled = value + step <= max,
            onClick = { onValueChange(value + step) },
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(AppSizing.TouchTarget)
            .clip(RoundedCornerShape(AppRadius.Sm))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AppColor.BrandPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(AppSizing.IconM),
        )
    }
}