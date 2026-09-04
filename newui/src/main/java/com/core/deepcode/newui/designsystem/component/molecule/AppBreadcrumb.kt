package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 面包屑（分子组 · AppBreadcrumb）：路径段以 ChevronRight 分隔，末级高亮加粗。
 * 用于深层结构的返回定位（远程 SSH 路径 / 知识库层级），比 Tab 更轻量。
 */
@Composable
fun AppBreadcrumb(
    items: List<String>,
    modifier: Modifier = Modifier,
    overflow: Int = 2,
) {
    val visible = if (items.size > overflow) items.take(1) + listOf("…") + items.takeLast(overflow) else items
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppSizing.IconS),
                )
            }
            val isLast = index == visible.lastIndex
            Text(
                text = segment,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.Xs),
            )
        }
    }
}