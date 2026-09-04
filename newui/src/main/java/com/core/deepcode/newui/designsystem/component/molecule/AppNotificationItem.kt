package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 通知项（分子组 · AppNotificationItem）：左滑浮现 + 未读色点 + 圆角图标底，
 * 用于通知中心 / 审批提醒 / 变更推送等场景。
 */
@Composable
fun AppNotificationItem(
    title: String,
    time: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    body: String? = null,
    unread: Boolean = false,
    accentColor: Color = AppColor.BrandPrimary,
    onClick: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(
            animationSpec = tween(280),
            initialOffsetX = { -it / 3 },
        ) + fadeIn(tween(280)),
        exit = slideOutHorizontally(animationSpec = tween(180), targetOffsetX = { -it / 3 }) + fadeOut(tween(180)),
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.Md))
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconContainer(
                icon = icon,
                tint = Color.White,
                background = accentColor.copy(alpha = 0.12f),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.Md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AppSpacing.Sm),
                    )
                }
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .alpha(if (unread) 1f else 0.8f),
                        maxLines = 2,
                    )
                }
            }
            if (unread) {
                Spacer(Modifier.padding(start = AppSpacing.Md))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            }
        }
    }
}