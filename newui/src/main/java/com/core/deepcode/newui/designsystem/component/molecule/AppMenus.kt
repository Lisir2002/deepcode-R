package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 动作面板（Action Sheet）行项：图标 + 文本。
 * `danger = true` 用破坏性红色渲染，并优先排到面板顶部（对齐 iOS / Android 动作面板规范）。
 */
data class AppActionSheetItem(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
)

/**
 * 动作面板（分子组 · AppActionSheet）：由底部弹出的半屏操作菜单，
 * 是 iOS ActionSheet / Android 底部动作列表的跨端统一形态。
 *
 * 用于：二次确认 / 上下文相关的多个选项（两个及以上）/ 分享与批量操作等"意图明确"的动作。
 * 与 [AppBottomSheetList]（选择列表）不同，这里承载的是"动作"，而非可勾选的数据。
 *
 * 设计要点（对齐 Material / HIG 规范）：
 *  - 破坏性项置顶并以红色标注（视觉警告，避免误触）；
 *  - 取消按钮与主操作区用分隔线隔开，恒置底部；
 *  - 点击遮罩 / 取消即可无副作用退出；
 *  - 每个动作整行大触达目标 + 主色/危险色图标，便于扫读。
 *
 * @param items 面板内的动作项（danger 自动排到顶部）。
 * @param onItemClick 点中某项：返回被点项，同时自动收起面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionSheet(
    visible: Boolean,
    items: List<AppActionSheetItem>,
    onItemClick: (AppActionSheetItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    cancelText: String = "取消",
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = containerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg)
                .padding(bottom = AppSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.Xs),
                    textAlign = TextAlign.Center,
                )
            }
            val grouped = items.filter { it.danger } + items.filter { !it.danger }
            grouped.forEach { item ->
                val tint = if (item.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.Md))
                        .clickable(onClick = AppHaptics.click {
                            onItemClick(item)
                            onDismiss()
                        })
                        .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md + 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Md))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tint,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
            Spacer(Modifier.height(AppSpacing.Sm))
            // 取消：与主动作区分隔，恒置底部。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.Md))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = AppSpacing.Md + 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = cancelText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 暴露式下拉选择框（分子组 · AppSelectField）：显示当前选中值 + 展开箭头，
 * 点击弹出选项菜单（单选 radio）。对齐 Material "Exposed Dropdown" —— 与常规下拉菜单
 * 的区别是：字段常驻展示当前选择，用户可一眼看到已选项。
 *
 * 适用：表单中的单选枚举（模型 / 主题 / 语言 …）。需要可搜索/可输入时改用 [AppSearchableSelectionList] 或自带输入框。
 *
 * @param value 当前选中文本；不在 options 中时展示 [placeholder]。
 * @param options 候选选项。
 * @param onSelect 选中回调（同时收起菜单）。
 */
@Composable
fun AppSelectField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择…",
    leadingIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it == value }
    val shape = RoundedCornerShape(AppRadius.Sm)

    Box(modifier) {
        Column {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.Xs),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = true }
                    .padding(horizontal = AppSpacing.Md + 4.dp, vertical = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                }
                Text(
                    text = selected ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppMenu(expanded = expanded, onDismiss = { expanded = false }) {
            options.forEach { opt ->
                AppSelectMenuItem(
                    label = opt,
                    selected = opt == selected,
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    },
                    radio = true,
                )
            }
        }
    }
}