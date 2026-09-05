package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlin.math.roundToInt

/* =========================================================================
 * 筛选组件族（Filter Family）
 * 针对不同数据类型提供「个性化」筛选控件：
 *  文本 → AppFilterField / 枚举 → AppChecklistFilter / 数值 → AppRangeFilter
 *  布尔 → AppBooleanFilter / 日期 → AppDateFilter / 评分 → AppRatingFilter
 *  组合 → AppFilterSheet（内置「已选 chips 汇总条 + 重置/应用」）
 * 风格对齐检索到的 Linear 筛选界面与 SaaS 最佳实践：激活态可辨、可清除、可组合。
 * ========================================================================= */

/** 文本型筛选输入框：圆角填充式 + 前置筛选图标 + 激活态描边 + 一键清除。针对关键字/搜索数据。 */
@Composable
fun AppFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "筛选…",
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused || value.isNotEmpty()
    val border by animateColorAsState(
        targetValue = if (active) AppColor.BrandPrimary else MaterialTheme.colorScheme.surfaceVariant,
        label = "border",
    )
    val bg by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        label = "bg",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.55f,
        label = "iconAlpha",
    )
    val shape = RoundedCornerShape(AppRadius.Pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(width = 1.dp, color = border, shape = shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {}
            .padding(horizontal = AppSpacing.Md, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.FilterList,
            contentDescription = null,
            tint = AppColor.BrandPrimary.copy(alpha = iconAlpha),
            modifier = Modifier.size(AppSizing.IconM),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.Sm),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(AppColor.BrandPrimary),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "清除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(AppSizing.IconButton)
                    .clip(RoundedCornerShape(AppRadius.Pill))
                    .clickable { onValueChange("") }
                    .padding(AppSpacing.Sm),
            )
        }
    }
}

/** 枚举多选筛选：勾选列表 + 选项计数 + 顶部全选/清空。针对分类/枚举/标签数据。 */
@Composable
fun AppChecklistFilter(
    options: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<Int> = emptyList(),
) {
    val shape = RoundedCornerShape(AppRadius.Md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(AppSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
    ) {
        options.forEachIndexed { index, label ->
            val isChecked = index in selected
            val checkBg by animateColorAsState(
                targetValue = if (isChecked) AppColor.BrandPrimary else Color.Transparent,
                label = "checkBg",
            )
            val checkBorder by animateColorAsState(
                targetValue = if (isChecked) AppColor.BrandPrimary else MaterialTheme.colorScheme.outlineVariant,
                label = "checkBorder",
            )
            val tickAlpha by animateFloatAsState(
                targetValue = if (isChecked) 1f else 0f,
                animationSpec = tween(durationMillis = 120),
                label = "tick",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.Sm))
                    .clickable { onToggle(index) }
                    .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(AppRadius.Sm))
                        .background(checkBg)
                        .border(1.dp, checkBorder, RoundedCornerShape(AppRadius.Sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = tickAlpha),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(AppSpacing.Md))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (counts.getOrNull(index) != null) {
                    Text(
                        text = counts[index].toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 勾选进度指示：顶部「全选 / 清空」快捷行（配合 AppChecklistFilter 使用）。 */
@Composable
fun AppChecklistToolbar(
    selectedCount: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount/$total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text("全选") }
        TextButton(onClick = onClear, enabled = selectedCount > 0) { Text("清空") }
    }
}

/** 数值范围筛选：双滑块 + 两端实时数值标签。针对价格/尺寸/数值型数据。 */
@Composable
fun AppRangeFilter(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    format: (Float) -> String = { it.roundToInt().toString() },
    prefix: String = "",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RangeValuePill(text = "$prefix${format(value.start)}", modifier = Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            RangeValuePill(text = "$prefix${format(value.endInclusive)}")
        }
        RangeSlider(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                activeTrackColor = AppColor.BrandPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                thumbColor = AppColor.BrandPrimary,
            ),
        )
    }
}

@Composable
private fun RowScope.RangeValuePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AppRadius.Pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .border(1.dp, AppColor.BrandPrimary.copy(alpha = 0.35f), shape)
            .padding(horizontal = AppSpacing.Md, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = AppColor.BrandPrimary,
        )
    }
}

/** 布尔型筛选：三态分段开关（全部 / 是 / 否）。针对开关/完成状态等布尔数据。 */
@Composable
fun AppBooleanFilter(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSegmentedToggle(
        options = labels,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
    )
}

/** 评分型筛选：星级 + 不限（点击「不限」清除评分）。针对星级/评分数据。 */
@Composable
fun AppRatingFilter(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 5,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AppRatingBar(value = value, onValueChange = onValueChange, max = max)
        }
        TextButton(onClick = { onValueChange(0) }, enabled = value > 0) {
            Text("不限")
        }
    }
}

/** 日期型筛选：预设 chips（不限/今日/本周/本月）+ 当前选中范围文本。针对日期数据。 */
@Composable
fun AppDateFilter(
    presets: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedRangeText: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            presets.forEachIndexed { index, label ->
                AppFilterChip(
                    label = label,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                )
            }
        }
        if (selectedRangeText != null) {
            Text(
                text = selectedRangeText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 综合筛选面板（抽屉式卡片）：标题 + 已选计数 + 清除全部 + 内容区(Slot) + 底部重置/应用。
 *   Reuse：把若干具体筛选控件放入 [content]，构成「组合多选筛选 + 纯度汇总 + 应用/重置」完整链路。 */
@Composable
fun AppFilterSheet(
    title: String,
    activeCount: Int,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    onApply: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AppRadius.Lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(AppElevation.Z3, shape, clip = true)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(AppSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        // Header：标题 + 已选 badge + 清除全部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.FilterList,
                contentDescription = null,
                tint = AppColor.BrandPrimary,
                modifier = Modifier.size(AppSizing.IconM),
            )
            Spacer(Modifier.width(AppSpacing.Sm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (activeCount > 0) {
                Spacer(Modifier.width(AppSpacing.Sm))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.Pill))
                        .background(AppColor.BrandPrimary)
                        .padding(horizontal = AppSpacing.Sm, vertical = 2.dp),
                ) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearAll, enabled = activeCount > 0) { Text("清除全部") }
        }
        content()
        if (onApply != null || onReset != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            ) {
                if (onReset != null) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                    ) { Text("重置") }
                }
                if (onApply != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(AppRadius.Pill))
                            .background(AppColor.BrandPrimary)
                            .clickable { onApply() }
                            .padding(vertical = AppSpacing.Md),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "应用",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}