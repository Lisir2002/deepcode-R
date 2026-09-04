package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 轻提示（分子组 · AppToast）：底部滑入+淡出的小卡片（icon + 文案），比系统 Toast 更一致。
 * 由宿主持有 [visible]，配合 `LaunchedEffect(message){ delay(...); onDismiss() }` 实现自动消失。
 */
@Composable
fun AppToast(
    message: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.inverseOnSurface,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.heightIn(min = AppSizing.TouchTarget),
        enter = slideInVertically(
            animationSpec = tween(AppMotion.Fast.toInt()),
            initialOffsetY = { it * 2 },
        ) + fadeIn(tween(AppMotion.Fast.toInt())),
        exit = slideOutVertically(
            animationSpec = tween(AppMotion.Fast.toInt()),
            targetOffsetY = { it * 2 },
        ) + fadeOut(tween(AppMotion.Fast.toInt())),
    ) {
        Surface(
            shape = RoundedCornerShape(AppRadius.Md),
            color = containerColor,
            contentColor = contentColor,
            shadowElevation = AppElevation.Z4,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .size(AppSizing.IconM)
                            .padding(end = AppSpacing.Sm),
                    )
                }
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}