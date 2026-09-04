package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlin.math.roundToInt

/**
 * 上下文菜单项：图标 + 标签 + 可选破坏性标记。
 */
data class AppMenuAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
)

/** 命令面板分组：标题 + 一组命令。 */
data class AppCommandGroup(
    val title: String,
    val commands: List<AppMenuAction>,
)

/**
 * 上下文菜单（分子组 · AppContextMenu）：长按目标后在触点附近弹出的上下文操作菜单，
 * 对齐 iOS `contextMenu` / 桌面右键菜单 / Material 上下文按钮语义。
 *
 * 与 [AppMenu]（触发按钮锚定的下拉）不同，这里**不锚定在按钮**，而是在任意触点坐标
 * [position]（px，由 `detectTapGestures(onLongPress = …)` 上报）原位弹出，更贴近"作用于该对象"的直觉。
 *
 * 设计要点：
 *  - 玻璃态浮层：圆角卡 + 阴影 + 细描边，沿 [position] 轻微左偏上浮，避免遮挡手指；
 *  - 破坏性项红色标注并排顶；选中即收起；
 *  - 点任意遮罩外自动关闭；`focusable` 使其可拦截返回键。
 *
 * @param position 弹出锚点（px）。为便于对齐，建议 `position - Offset(0, 8.dp.toPx())`。
 */
@Composable
fun AppContextMenu(
    visible: Boolean,
    position: Offset,
    items: List<AppMenuAction>,
    onItemClick: (AppMenuAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val corner = RoundedCornerShape(AppRadius.Md)
    Popup(
        offset = IntOffset(position.x.roundToInt() - 8, position.y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .widthIn(min = 168.dp, max = 256.dp)
                .wrapContentWidth()
                .shadow(14.dp, corner)
                .clip(corner)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = AppSpacing.Xs),
        ) {
            val grouped = items.filter { it.danger } + items.filter { !it.danger }
            grouped.forEach { item ->
                val tint = if (item.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = AppHaptics.click {
                            onItemClick(item)
                            onDismiss()
                        })
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(AppSpacing.Md))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 可搜索下拉选择框（分子组 · AppComboBox）：文本框常驻显示当前值，点击展开候选，
 * **输入即过滤**（对 [options] 做不区分大小写子串匹配），选中后回填。
 *
 * 对齐 Material `ExposedDropdownMenu` + 可搜索 `Combobox` 范式：
 * 过滤时无匹配显示"无匹配项"空态；清空按钮一键还原已选值前的搜索。
 *
 * @param value 当前选中文本；不是候选时显示自身并可继续输入。
 * @param options 候选选项。
 * @param onSelect 选中回调（同时收起下拉）。
 * @param onQueryChange 过滤输入内容变化回调（可驱动外部 state，也可忽略让内部过滤）。
 */
@Composable
fun AppComboBox(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "输入或选择…",
    leadingIcon: ImageVector? = null,
    onQueryChange: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(value) }
    val filtered = if (query.isBlank()) options else options.filter {
        it.contains(query, ignoreCase = true)
    }
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
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange?.invoke(it)
                    if (!expanded) expanded = true
                },
                singleLine = true,
                placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = leadingIcon?.let {
                    { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        query = ""
                                        onQueryChange?.invoke("")
                                    },
                            )
                            Spacer(Modifier.width(AppSpacing.Sm))
                        }
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                shape = shape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AppMenu(expanded = expanded, onDismiss = { expanded = false }) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                ) {
                    Text(
                        text = "无匹配项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                filtered.forEach { opt ->
                    AppSelectMenuItem(
                        label = opt,
                        selected = opt == value,
                        onClick = {
                            query = opt
                            expanded = false
                            onSelect(opt)
                        },
                        radio = true,
                    )
                }
            }
        }
    }
}

/**
 * 命令面板（分子组 · AppCommandPalette）：类 Spotlight / ⌘K 的全屏命令检索浮层，
 * 居中宽卡 + 顶部聚焦输入框 + 分组命令列表（带图标、副标题、快捷键），
 * 输入即过滤当前组并实时高亮。对齐 Linear / Raycast / VS Code 的命令面板范式。
 *
 * 用法：以「快捷键命令 + 菜单项」的组织方式覆盖高频命令，成为高级用户的快捷中枢。
 * 需在足够高的父层组装（如页面根），点击遮罩关闭。
 *
 * @param groups 命令分组（可按输入实时过滤）。
 * @param onSelect 选中命令回调（如需收起面板，调用方置 visible=false）。
 * @param hint 底部快捷键提示文案。
 */
@Composable
fun AppCommandPalette(
    visible: Boolean,
    groups: List<AppCommandGroup>,
    onSelect: (AppMenuAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    hint: String = "↑↓ 选择 · Enter 执行 · Esc 关闭",
    onQueryChange: ((String) -> Unit)? = null,
) {
    if (!visible) return
    var localQuery by remember { mutableStateOf(query) }
    val q = if (onQueryChange != null) query else localQuery
    val setQ: (String) -> Unit = { s ->
        localQuery = s
        onQueryChange?.invoke(s)
    }
    val corner = RoundedCornerShape(AppRadius.Lg)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 遮罩：点空白关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(enabled = true, onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .padding(top = AppSpacing.Xl + 8.dp)
                .padding(horizontal = AppSpacing.Lg)
                .fillMaxWidth()
                .shadow(24.dp, corner)
                .clip(corner)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 搜索行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(AppSpacing.Sm))
                TextField(
                    value = q,
                    onValueChange = setQ,
                    singleLine = true,
                    placeholder = { Text("输入命令…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (q.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { setQ("") },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 命令列表
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs),
            ) {
                groups.forEach { group ->
                    val matches = if (q.isBlank()) group.commands else group.commands.filter {
                        it.label.contains(q, ignoreCase = true) || group.title.contains(q, ignoreCase = true)
                    }
                    if (matches.isEmpty()) return@forEach
                    Text(
                        text = group.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = AppSpacing.Lg, top = AppSpacing.Md, bottom = AppSpacing.Xs),
                    )
                    matches.forEach { cmd ->
                        val tint = if (cmd.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.Sm, vertical = 2.dp)
                                .clip(RoundedCornerShape(AppRadius.Md))
                                .clickable(onClick = AppHaptics.click { onSelect(cmd) })
                                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = cmd.icon,
                                contentDescription = cmd.label,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(AppSpacing.Md))
                            Text(
                                text = cmd.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tint,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            )
        }
    }
}

/**
 * 多选下拉菜单（分子组 · AppMultiSelectMenu）：对齐 Fluent「Checkbox Menu」——下拉展开一组
 * **可勾选**项，多次勾选即时生效，顶部提供「全选 / 清除」快捷操作，按钮上回显已选摘要。
 * 用于列筛选 / 批量标签 / 多条件过滤等 "pick many from a list" 场景。
 *
 * @param expanded 是否展开；由调用方控制（配合触发按钮点击翻转）。
 * @param onDismissRequest 点菜单外 / 返回键收起。
 * @param selected 已选 index 集合。
 * @param onToggle 勾选切换回调（参数为被 toggle 项 index）。
 * @param onSelectAll / onClear 顶部快捷操作（由调用方基于现有集合推导最终集合）。
 * @param headerText 顶部计数文案；默认「已选 n/m」。
 */
@Composable
fun AppMultiSelectMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: () -> Unit = {},
    onClear: () -> Unit = {},
    headerText: String? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 220.dp, max = 280.dp),
        shape = RoundedCornerShape(AppRadius.Md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerText ?: "已选 ${selected.size}/${options.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "全选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = AppHaptics.click(onSelectAll)),
            )
            Spacer(Modifier.width(AppSpacing.Md))
            Text(
                text = "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = AppHaptics.click(onClear)),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        options.forEachIndexed { index, opt ->
            val checked = index in selected
            DropdownMenuItem(
                text = {
                    Text(
                        text = opt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = AppHaptics.click { onToggle(index) },
                leadingIcon = {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(index) })
                },
                trailingIcon = if (checked) {
                    { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

/**
 * 拆分/组合按钮菜单（分子组 · AppSplitButtonMenu）：主按钮执行默认动作，右侧细分「▾」区
 * 展开同语义的其余相关选项（对齐 Carbon Combo Button / Ant Design「带下拉框的按钮」）。
 * 选中会将当前值回显到主按钮，实现「默认动作 + 更多选项」二合一。
 *
 * @param label 主按钮文案（随选中项更新）。
 * @param icon 主按钮图标。
 * @param onClick 主按钮点击（通常执行当前 [label] 对应的默认动作）。
 * @param options 下拉内可选动作。
 * @param onOptionSelected 选中某选项（回写 label 并收起菜单）。
 */
@Composable
fun AppSplitButtonMenu(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    options: List<AppMenuAction>,
    onOptionSelected: (AppMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AppRadius.Md)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = AppHaptics.click(onClick))
                .padding(horizontal = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.Xs))
            }
            Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(34.dp)
                .clickable(onClick = AppHaptics.click { expanded = true }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, "更多选项", tint = MaterialTheme.colorScheme.onPrimary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = shape,
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (opt.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = opt.icon,
                            contentDescription = opt.label,
                            tint = if (opt.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = AppHaptics.click {
                        expanded = false
                        onOptionSelected(opt)
                    },
                )
            }
        }
    }
}

/**
 * 快捷动作展开菜单（分子组 · AppQuickActionMenu）：核心 FAB + 上浮展开的放射式动作组，
 * 对齐 Material 3 **Speed Dial / Extended FAB**（把高频快捷动作聚集到核心按钮上）。
 *
 * 设计要点：
 *  - 每个动作项由底部按钮触发并回弹式逐个弹出（staggered），带图标 + 标签胶囊；
 *  - 展开态主 FAB 切换为关闭图标；点遮罩 / 再点主按钮收起；
 *  - `reverseOrder` 控制展开方向（向上展开为默认）。
 *
 * @param expanded 是否展开；由调用方控制。
 * @param onToggle embedded toggle（点主按钮切换）。
 * @param actions 动作项（从上至下）。
 * @param onAction 点中某项：返回该项并自动收起。
 * @param fabIcon 主按钮图标（未展开态）；展开态自动切换到 Close。
 */
@Composable
fun AppQuickActionMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: List<AppMenuAction>,
    onAction: (AppMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    fabContentDescription: String = "快捷操作",
) {
    // 整体展开进度：驱动每一项的"从底部滑入 + 放大回弹"。
    val reveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "quickActionReveal",
    )
    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        // 遮罩：只拦截点击以收起（不暗化，保持轻量）。
        if (reveal > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = expanded) { onToggle() },
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
            actions.forEachIndexed { idx, action ->
                val t = ((reveal - 0.15f * idx) / (1f - 0.15f * idx)).coerceIn(0f, 1f)
                val itemAlpha by animateFloatAsState(targetValue = t, animationSpec = tween(140))
                val scale by animateFloatAsState(
                    targetValue = if (t > 0f) 1f else 0.6f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                )
                Row(
                    modifier = Modifier
                        .padding(bottom = AppSpacing.Sm)
                        .graphicsLayer {
                            alpha = itemAlpha
                            scaleX = scale
                            scaleY = scale
                            translationY = (1f - t) * 16f
                        }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = t > 0.01f, onClick = AppHaptics.click {
                            onAction(action)
                        })
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (action.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = if (action.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.Sm))
        }
    }
}

/**
 * 级联子菜单节点：带 [children] 的是父节点（可再展开下一级飞墙），否则是可点击叶子项。
 * 构建多级嵌套菜单（如「文件 → 导出 → 格式」）时把子项挂到父节点上即可。
 */
data class AppCascadeNode(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val children: List<AppCascadeNode> = emptyList(),
)

/**
 * 级联子菜单（分子组 · AppCascadingMenu）：点父级菜单项，**平级飞墙（flyout）**在右侧依次铺开，
 * 各级之间以滑入/滑出动画衔接；父级个头带「返回」可逐级回退，叶子点击即收起并回调。
 *
 * 对齐 Cascade（Nested Popup Menu）/ Google Drive 的多级菜单：用**导航栈**维护当前级链，
 * 在同一个 Popup 内渲染多列，避免原生 DropdownMenu 不支持嵌套、切换生硬跳变的短板。
 *
 * 设计要点：
 *  - 同一玻璃浮层内多列飞墙：每点一个父项，新列从右侧滑入（≈220ms），收起时滑出；
 *  - 父项右侧缀 ChevronRight，叶子无箭头；进入子级后子列头部有「← 返回」；
 *  - 破坏性叶子红色标注；点外部 / 返回键关闭整套菜单；
 *  - 深度限 4 级（足够覆盖绝大多数多级菜单），避免过度嵌套。
 *
 * @param expanded 是否展开；由调用方控制（配合触发按钮点击翻转）。
 * @param offset 相对锚点的像素偏移（常用 Popup 位），默认从左上角弹出。
 * @param onItemClick 点中叶子项：返回被点节点并关闭菜单。
 */
@Composable
fun AppCascadingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<AppCascadeNode>,
    onItemClick: (AppCascadeNode) -> Unit,
    modifier: Modifier = Modifier,
    offset: IntOffset = IntOffset(0, 32),
    columnWidth: Dp = 200.dp,
) {
    if (!expanded) return
    val corner = RoundedCornerShape(AppRadius.Md)
    // 导航栈：path[0].children 渲染在第 1 列，path[1].children 渲染在第 2 列……
    var path by remember { mutableStateOf<List<AppCascadeNode>>(emptyList()) }
    val currentPath by rememberUpdatedState(path)
    // 每次打开时重置导航栈（回到根级）。
    LaunchedEffect(expanded) { if (expanded) path = emptyList() }

    Popup(
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(modifier) {
            // 根级（第 0 列）
            CascadeLevel(
                index = 0,
                items = items,
                active = true,
                corner = corner,
                columnWidth = columnWidth,
                onLeafClick = { node ->
                    onItemClick(node)
                    onDismiss()
                },
                onParentClick = { path = currentPath + it },
            )
            // 子级列：深度 <= 3 的飞墙，按导航栈取对应父节点；无父节点则该列隐藏。
            repeat(3) { depth ->
                val parent = currentPath.getOrNull(depth)
                CascadeLevel(
                    index = depth + 1,
                    items = parent?.children ?: emptyList(),
                    active = parent != null,
                    corner = corner,
                    columnWidth = columnWidth,
                    onLeafClick = { node ->
                        onItemClick(node)
                        onDismiss()
                    },
                    onParentClick = { path = currentPath + it },
                    onBack = { path = currentPath.take(depth) },
                )
            }
        }
    }
}

/** 级联菜单的单列飞墙（内部）：水平按 [index] 逐级右移，进出带滑入/滑出动画。 */
@Composable
private fun CascadeLevel(
    index: Int,
    items: List<AppCascadeNode>,
    active: Boolean,
    onLeafClick: (AppCascadeNode) -> Unit,
    onParentClick: (AppCascadeNode) -> Unit,
    corner: RoundedCornerShape,
    columnWidth: Dp,
    onBack: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slotPx = with(density) { (columnWidth + AppSpacing.Sm).toPx() }.roundToInt()
    AnimatedVisibility(
        visible = active,
        enter = slideInHorizontally(initialOffsetX = { it / 2 }, animationSpec = tween(220)) +
            fadeIn(animationSpec = tween(180)),
        exit = slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(170)) +
            fadeOut(animationSpec = tween(130)),
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(x = index * slotPx, y = 0) }
                .width(columnWidth)
                .shadow(16.dp, corner, clip = false)
                .clip(corner)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = AppSpacing.Xs),
        ) {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = AppHaptics.click(onBack))
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f },
                    )
                    Spacer(Modifier.width(AppSpacing.Sm))
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            items.forEach { node ->
                val hasChild = node.children.isNotEmpty()
                val tint = if (node.danger) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = AppHaptics.click {
                            if (hasChild) onParentClick(node) else onLeafClick(node)
                        })
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.icon != null) {
                        Icon(
                            imageVector = node.icon,
                            contentDescription = node.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(AppSpacing.Md))
                    }
                    Text(
                        text = node.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasChild) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .width(columnWidth)
                        .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                ) {
                    Text(
                        text = "无子选项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 导航/侧栏列表项：图标 + 标签 + 可选计数徽标，选中态以主色胶囊凸显。
 * `danger = true` 的行（如「退出登录」）用破坏性红色图标，但选中态仍给主色反馈。
 */
data class AppNavigationItem(
    val label: String,
    val icon: ImageVector,
    val badge: Int? = null,
    val danger: Boolean = false,
)

/**
 * 导航/侧栏列表（分子组 · AppNavigationMenu）：一组纵向排布的可选中导航项，
 * 是对 Material Navigation Drawer / Linear 侧栏导航的轻量封装，可嵌进任何宽卡片。
 *
 * 设计要点：
 *  - 选中项：整行主色胶囊底（brand 泛化）+ 主色图标 + 左侧强调条 / 圆点，未选顶多灰调图标；
 *  - 支持 [header]（顶部用户卡 / 工作区卡）与 [footer]（底部的设置 / 退出登录区）；
 *  - `badge` 在行尾显示计数胶囊，danger 项使用红色图标区分。
 *
 * @param header 顶部插槽（示例：AppAvatar + 工作区名）。
 * @param footer 底部插槽（示例：设置项 / 退出登录项）。
 */
@Composable
fun AppNavigationMenu(
    items: List<AppNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val corner = RoundedCornerShape(AppRadius.Lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(corner)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = AppSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
            ) { header() }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(AppSpacing.Xs))
        }
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val tint = when {
                item.danger && !selected -> AppColor.StatusDanger
                selected -> AppColor.BrandPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = AppSpacing.Sm)
                    .clip(RoundedCornerShape(AppRadius.Md))
                    .background(
                        color = if (selected) {
                            AppColor.BrandPrimary.copy(alpha = 0.14f)
                        } else Color.Transparent,
                        shape = RoundedCornerShape(AppRadius.Md),
                    )
                    .clickable(onClick = AppHaptics.click { onSelect(index) })
                    .padding(horizontal = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧强调条：选中时出现品牌色竖条。
                if (selected) {
                    Box(
                        Modifier
                            .padding(end = AppSpacing.Md)
                            .size(width = 4.dp, height = 18.dp)
                            .background(AppColor.BrandPrimary, RoundedCornerShape(percent = 50)),
                    )
                }
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(AppSpacing.Md))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        AppColor.BrandPrimary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.badge != null) {
                    Text(
                        text = if (item.badge > 99) "99+" else item.badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) AppColor.BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (selected) AppColor.BrandPrimary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(horizontal = AppSpacing.Sm, vertical = 2.dp),
                    )
                }
            }
        }
        if (footer != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(AppSpacing.Xs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
            ) { footer() }
        }
    }
}