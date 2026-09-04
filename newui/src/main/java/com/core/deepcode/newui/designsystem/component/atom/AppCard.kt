package com.core.deepcode.newui.designsystem.component.atom

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 基础卡片（§3.12 AppCard）：surfaceVariant 底 + z1 阴影 + md 圆角。
 * 无 onClick 时纯展示卡片；有 onClick 走可点击 Surface（波纹在 scheme 色上正确显示）。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(AppRadius.Md)
    val elevation = AppElevation.Z1
    if (onClick != null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            shadowElevation = elevation,
            onClick = onClick,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = elevation,
            content = content,
        )
    }
}