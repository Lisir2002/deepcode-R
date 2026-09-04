package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 图标坞项：图标 + 悬浮标签 + 点击回调。 */
data class AppDockItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit = {},
)

/**
 * 图标坞（分子组 · AppDock）：类 macOS Dock 的胶囊形图标列，按压 / 选中项弹簧放大并浮现
 * 标签，矮胖的启动面板 / 快速切换栏。
 */
@Composable
fun AppDock(
    items: List<AppDockItem>,
    modifier: Modifier = Modifier,
    selectedIndex: Int = -1,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(background)
            .padding(AppSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        items.forEachIndexed { index, item ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val active = pressed || index == selectedIndex
            val scale by animateFloatAsState(
                targetValue = if (active) 1.28f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "dockScale",
            )
            Column(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable(interactionSource = interaction, indication = null) { item.onClick() }
                    .padding(horizontal = AppSpacing.Xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = AppColor.BrandPrimary,
                    modifier = Modifier.size(AppSizing.IconL),
                )
                if (active) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}