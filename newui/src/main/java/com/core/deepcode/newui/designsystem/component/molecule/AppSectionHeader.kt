package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 区块标题（§3.12 AppSectionHeader / §3.7.3）：左侧竖条装饰 + 标题 + 可选右侧插槽。
 */
@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    iconBarColor: androidx.compose.ui.graphics.Color = AppColor.BrandPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = AppSpacing.Sm)
                .size(width = SmallAccentBar, height = AppSizing.IconS)
                .background(iconBarColor, RoundedCornerShape(AppRadius.Sm)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** 左竖条固定宽（§3.7.3 装饰比例，非独立 token）。 */
private val SmallAccentBar = 4.dp