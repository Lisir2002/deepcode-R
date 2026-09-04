package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 扩展式浮动按钮（分子组 · AppFAB）：pill 品牌胶囊，[expanded] 时头部图标+文字横向展开（fade+expand），
 * 收起退为纯图标。用于"新建/发送"等主导动作，比固定标签 FAB 更灵活（Linear 同款）。
 */
@Composable
fun AppFAB(
    icon: ImageVector,
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = AppColor.BrandPrimary,
    contentColor: Color = Color.White,
) {
    val hPadding by animateDpAsState(
        targetValue = if (expanded) AppSpacing.Lg else AppSpacing.Sm,
        label = "fabPadding",
    )
    Row(
        modifier = modifier
            .height(AppSizing.TouchTarget)
            .heightIn(min = AppSizing.TouchTarget)
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = hPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(AppSizing.IconM),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                modifier = Modifier.padding(start = AppSpacing.Sm),
            )
        }
    }
}