package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.core.deepcode.newui.designsystem.component.atom.AppIcon
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 列表行（§3.12 AppMenuRow）：行高 TouchTarget、图标块用 [IconContainer]、尾随插槽可控。
 * 用于设置项/菜单入口行；破坏性操作在 onClick 外用 [AppHaptics] 二次确认。
 */
@Composable
fun AppMenuRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContainer: Boolean = false,
    iconColor: Color = AppColor.BrandPrimary,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = true, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSizing.TouchTarget)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)
            .then(clickableModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (iconContainer) {
                IconContainer(
                    icon = icon,
                    tint = Color.White,
                    background = iconColor,
                    modifier = Modifier.padding(end = AppSpacing.Md),
                )
            } else {
                AppIcon(
                    icon = icon,
                    size = AppSizing.IconL,
                    tint = iconColor,
                    modifier = Modifier.padding(end = AppSpacing.Md),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Xs))
        if (trailing != null) trailing()
    }
}