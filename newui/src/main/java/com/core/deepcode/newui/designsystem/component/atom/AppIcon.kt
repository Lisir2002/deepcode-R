package com.core.deepcode.newui.designsystem.component.atom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing

/**
 * 图标（§3.6）：统一 Rounded 基调 + 四档尺寸刻度（§3.6.2），tint 取 LocalContentColor。
 * contentDescription 语义（§3.6.7）：无内容描述时标记为装饰（null）。
 */
@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = AppSizing.IconM,
    tint: androidx.compose.ui.graphics.Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * 图标块（§3.6.3 IconContainer）：40dp 色块 + 白色图标，用于设置项/入口行前的色块符。
 */
@Composable
fun IconContainer(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    size: Dp = AppSizing.IconBlock,
    background: androidx.compose.ui.graphics.Color = AppColor.BrandPrimary,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background, shape = RoundedCornerShape(AppRadius.Sm)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(AppSizing.IconM))
    }
}