package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 分页控件（分子组 · AppPagination）：页码 + 省略号 + 前后翻页，
 * 活跃页码回弹放大强调，用于日志 / 命令历史 / 结果列表等分页场景。
 */
@Composable
fun AppPagination(
    page: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = AppColor.BrandPrimary,
    showPrevNext: Boolean = true,
) {
    if (pageCount <= 1) return
    val safePage = page.coerceIn(1, pageCount)
    Row(
        modifier = modifier.padding(vertical = AppSpacing.Sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPrevNext) {
            PageArrow(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                enabled = safePage > 1,
                onClick = { onPageChange(safePage - 1) },
                accentColor = accentColor,
            )
        }
        pageSequence(safePage, pageCount).forEachIndexed { _, item ->
            if (item == null) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.Xs),
                )
            } else {
                PageNumber(
                    number = item,
                    active = item == safePage,
                    onClick = { onPageChange(item) },
                    accentColor = accentColor,
                )
            }
        }
        if (showPrevNext) {
            PageArrow(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                enabled = safePage < pageCount,
                onClick = { onPageChange(safePage + 1) },
                accentColor = accentColor,
            )
        }
    }
}

@Composable
private fun PageNumber(
    number: Int,
    active: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pageNumScale",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .widthIn(min = 34.dp)
            .height(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(AppRadius.Sm))
            .background(if (active) accentColor else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
) {
    val tint = if (enabled) accentColor else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(AppRadius.Sm))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 页码序列（null 表示省略号）。 */
private fun pageSequence(page: Int, total: Int): List<Int?> {
    if (total <= 7) return (1..total).toList()
    val set = sortedSetOf<Int>()
    set.add(1)
    set.add(total)
    for (i in (page - 1)..(page + 1)) {
        if (i in 1..total) set.add(i)
    }
    val seq = ArrayList<Int?>()
    var prev = 0
    for (p in set) {
        if (p - prev > 1) seq.add(null)
        seq.add(p)
        prev = p
    }
    return seq
}