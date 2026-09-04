package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 选择项数据：标题 + 可选副标题/图标。 */
data class AppSelectionItem(
    val label: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
)

/** 选择模式：单选（radio）/ 多选（勾选）。 */
enum class AppSelectionMode { Single, Multiple }

/**
 * 选择列表（分子组 · AppSelectionList）：一组可选项的整列表，
 * 用于"选择模型 / 选择项目根目录 / 多选标签"等内联选择场景；
 * 配合 [AppBottomSheetList] 用于抽屉化选择。选中态用主色 radio/勾选 + 行高亮。
 *
 * @param mode 单选或多选。
 * @param selected 单选传 `Int?`，多选传 `Set<Int>`（内部按 mode 区分）。
 * @param onSelect 单选：选中 index；多选：toggle 后的新 Set。
 */
@Composable
fun AppSelectionList(
    items: List<AppSelectionItem>,
    mode: AppSelectionMode,
    selected: Any,
    onSelect: (Int) -> Unit,
    onToggle: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = when (mode) {
                AppSelectionMode.Single -> selected == index
                AppSelectionMode.Multiple -> (selected as? Set<*>)?.contains(index) == true
            }
            AppSelectionListRow(
                item = item,
                selected = isSelected,
                mode = mode,
                onClick = if (mode == AppSelectionMode.Single) {
                    // 单选用触觉反馈；在组合作用域调用 AppHaptics.click
                    AppHaptics.click { onSelect(index) }
                } else {
                    { onToggle(index) }
                },
            )
        }
    }
}

/** 单行选择项（AppSelectionListRow）：图标块/图标 + 标题副标题 + 尾随 radio/勾选框。 */
@Composable
private fun AppSelectionListRow(
    item: AppSelectionItem,
    selected: Boolean,
    mode: AppSelectionMode,
    onClick: () -> Unit,
) {
    val rowColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(160),
        label = "selectionRowColor",
    )
    val waveAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "selectionWaveAlpha",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "selectionCheckScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSizing.TouchTarget)
            .background(rowColor, shape = RoundedCornerShape(AppRadius.Md))
            .clickable(
                onClick = onClick,
                role = if (mode == AppSelectionMode.Multiple) Role.Checkbox else Role.RadioButton,
            )
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            IconContainer(
                icon = item.icon,
                tint = Color.White,
                background = if (selected) AppColor.BrandPrimary else AppColor.BrandSurfaceDim,
                modifier = Modifier.padding(end = AppSpacing.Md),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.subtitle != null) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Sm))
        SelectionIndicator(
            selected = selected,
            mode = mode,
            revealAlpha = waveAlpha,
            checkScale = checkScale,
        )
    }
}

/** 尾随选择指示器：单选 radio 圈（选中实心主色），多选方形勾（弹簧弹出白勾）。 */
@Composable
private fun SelectionIndicator(
    selected: Boolean,
    mode: AppSelectionMode,
    revealAlpha: Float,
    checkScale: Float,
) {
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    if (mode == AppSelectionMode.Single) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                drawCircle(
                    color = outlineVariant,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    radius = size.minDimension * 0.42f,
                )
                if (selected) {
                    drawCircle(color = AppColor.BrandPrimary, radius = size.minDimension * 0.26f)
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (selected) AppColor.BrandPrimary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .scale(checkScale)
                    .graphicsLayer {
                        alpha = revealAlpha
                    },
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}