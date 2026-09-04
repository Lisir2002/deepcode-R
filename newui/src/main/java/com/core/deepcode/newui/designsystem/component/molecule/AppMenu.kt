package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 下拉菜单（分子组 · AppMenu）：M3 `DropdownMenu` 的玻璃态统一封装。
 *
 * 组成（经 [AppMenuItem] / [AppSelectMenuItem] / [AppMenuHeader] / [AppMenuDivider] 组装）：
 * 分组标题、分隔线、带图标项、单选/复选态（radio/对勾尾随）、破坏性项（tint 传 StatusDanger）。
 *
 * @param expanded 由触发按钮的点击状态控制。
 * @param onDismiss 关闭回调（点外 / 选中后主动置 false）。
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = containerColor,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 12.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/**
 * 下拉菜单项（AppMenuItem）：图标 + 标题 + 可选尾随（勾/radio/快捷键），
 * `tint` 传 StatusDanger 可做破坏性项；选中态标题用主色加粗。
 */
@Composable
fun AppMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary else tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        onClick = AppHaptics.click(onClick),
        modifier = modifier,
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp)) }
        },
        trailingIcon = trailingIcon?.let {
            { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        },
    )
}

/** 下拉选择项（AppSelectMenuItem）：单选显示 radio，复选显示主色对勾。 */
@Composable
fun AppSelectMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    radio: Boolean = true,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        onClick = AppHaptics.click(onClick),
        modifier = modifier,
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        },
        trailingIcon = {
            Icon(
                imageVector = if (radio) {
                    if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked
                } else {
                    Icons.Rounded.Check
                },
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/** 下拉菜单分组标题（AppMenuHeader）：灰字小标题，不带点击。 */
@Composable
fun AppMenuHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
    )
}

/** 下拉菜单分隔线（AppMenuDivider）。 */
@Composable
fun AppMenuDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = AppSpacing.Xs),
        color = MaterialTheme.colorScheme.surfaceVariant,
        thickness = 1.dp,
    )
}