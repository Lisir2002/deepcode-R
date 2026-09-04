package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.core.deepcode.newui.designsystem.component.atom.AppIcon
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 弹窗强调色语义：Info / Success / Warning / Danger。 */
enum class AppDialogTone { Info, Success, Warning, Danger }

@Composable
internal fun AppDialogTone.color(): Color = when (this) {
    AppDialogTone.Info -> AppColor.StatusInfo
    AppDialogTone.Success -> AppColor.StatusSuccess
    AppDialogTone.Warning -> AppColor.StatusWarning
    AppDialogTone.Danger -> AppColor.StatusDanger
}

/**
 * 弹窗玻璃面板（AppDialogSurface）：圆角卡片 + 顶部强调图标 + 动画入场。
 * Confirm / Prompt / Update 三档弹窗的基础载体，保证样式一致。
 */
@Composable
internal fun AppDialogSurface(
    visible: Boolean,
    onDismiss: () -> Unit,
    tone: AppDialogTone,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit,
    text: @Composable (ColumnScope.() -> Unit)? = null,
    trailingUnderActions: (@Composable ColumnScope.() -> Unit)? = null,
    dismissable: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, animationSpec = tween(160)),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(120)),
    ) {
        val shape = RoundedCornerShape(AppRadius.Lg)
        BasicAlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = dismissable,
                dismissOnClickOutside = dismissable,
            ),
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, shape, clip = true)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Xl),
            ) {
                if (icon != null || title.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(tone.color().copy(alpha = 0.14f), RoundedCornerShape(AppRadius.Md)),
                                contentAlignment = Alignment.Center,
                            ) {
                                AppIcon(
                                    icon = icon,
                                    tint = tone.color(),
                                    size = 22.dp,
                                )
                            }
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = if (icon != null) AppSpacing.Md else 0.dp),
                        )
                    }
                }
                if (text != null) {
                    SpacerV(AppSpacing.Md)
                    Column { text() }
                }
                if (trailingUnderActions != null) {
                    SpacerV(AppSpacing.Sm)
                    Column { trailingUnderActions() }
                }
                SpacerV(AppSpacing.Lg)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun SpacerV(size: Dp) {
    Spacer(Modifier.height(size))
}

/**
 * 确认弹窗（AppConfirmDialog）：带图标强调的确认对话框，
 * `tone = Danger` 用红底确认键表达破坏性操作，需二次确认。
 */
@Composable
fun AppConfirmDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.WarningAmber,
    tone: AppDialogTone = AppDialogTone.Info,
) {
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                onClick = AppHaptics.click(onConfirm),
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmText)
            }
        },
        text = if (message != null) {
            {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
    )
}

/**
 * 输入弹窗（AppPromptDialog）：标题 + 文本框，回车/确认回调返回输入值，
 * 用于"命名会话 / 输入远端地址 / 添加自定义模型"等轻量录入。
 */
@Composable
fun AppPromptDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    initialValue: String = "",
    confirmText: String = "确定",
    icon: ImageVector = Icons.Rounded.Info,
    tone: AppDialogTone = AppDialogTone.Info,
) {
    var input by remember(initialValue) { mutableStateOf(initialValue) }
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                onClick = AppHaptics.click { onConfirm(input) },
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmText)
            }
        },
        text = {
            AppTextField(
                value = input,
                onValueChange = { input = it },
                label = title,
                placeholder = placeholder,
            )
        },
    )
}

/**
 * 更新弹窗（AppUpdateDialog）：版本号 + 更新说明 + 可选下载进度条，
 * 提供「立即更新 / 稍后」两类操作。
 */
@Composable
fun AppUpdateDialog(
    visible: Boolean,
    title: String,
    version: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    notes: List<String> = emptyList(),
    progress: Float? = null,
    confirmText: String = "立即更新",
    tone: AppDialogTone = AppDialogTone.Info,
) {
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = Icons.Rounded.SystemUpdate,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("稍后", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                onClick = AppHaptics.click(onUpdate),
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (progress == null) confirmText else if (progress > 0f && progress < 1f) "${(progress * 100).toInt()}%" else confirmText)
            }
        },
        text = {
            Text(
                text = "版本 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notes.isNotEmpty()) {
                SpacerV(AppSpacing.Sm)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    notes.forEach { note ->
                        Row {
                            Text("• ", color = tone.color(), style = MaterialTheme.typography.bodySmall)
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (progress != null) {
                SpacerV(AppSpacing.Md)
                AppProgressBar(progress = progress.coerceIn(0f, 1f))
            }
        },
    )
}

/**
 * 提示/警示弹窗（AppAlertDialog）：同确认弹窗但仅单动作（默认「知道了」）。
 * 对齐 Material Alert：用于告知用户无需决策的中断性信息——错误阻断、关键提醒、授权说明。
 *
 * @param tone Success/Warning/Danger/Info 决定强调图标与按钮色。
 */
@Composable
fun AppAlertDialog(
    visible: Boolean,
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "知道了",
    icon: ImageVector = Icons.Rounded.Info,
    tone: AppDialogTone = AppDialogTone.Info,
) {
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            Button(
                onClick = AppHaptics.click(onDismiss),
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmText)
            }
        },
        text = if (message != null) {
            {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
    )
}

/**
 * 列表选择弹窗（AppSelectionDialog）：Simple Dialog —— 标题 + 一组选项，
 * 选中即生效并收起（不要求二次确认）。用于"加入收藏 / 移动到分组 / 模型切换"等轻量选择。
 *
 * @param options 候选文本列表。
 * @param onSelect 返回被选 index（同时收起弹窗）。
 * @param selectedIndex 当前高亮的项。
 */
@Composable
fun AppSelectionDialog(
    visible: Boolean,
    title: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    cancelText: String = "取消",
) {
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = AppDialogTone.Info,
        title = title,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = selectedIndex == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.Md))
                            .clickable(onClick = AppHaptics.click { onSelect(index) })
                            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Rounded.RadioButtonChecked
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * 进度/加载弹窗（AppProgressDialog）：中心加载指示 + 标题 + 可选副标题。
 * 可选 `progress` 转**定量**进度条（0..1），否则不定量转圈。
 * 默认**不可**通过点外部/返回键关闭（阻塞式任务进行中）；仅提供 [onCancel] 时才允许取消。
 * 对齐 Gmail/文件传输等"任务进行中"的统一处理——用户心智可预期、避免误关丢失进度。
 */
@Composable
fun AppProgressDialog(
    visible: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    tone: AppDialogTone = AppDialogTone.Info,
    cancelText: String = "取消",
    onCancel: (() -> Unit)? = null,
) {
    if (!visible) return
    val shape = RoundedCornerShape(AppRadius.Lg)
    Dialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onCancel != null,
            dismissOnClickOutside = onCancel != null,
        ),
    ) {
        Column(
            modifier = modifier
                .shadow(16.dp, shape, clip = true)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = AppSpacing.Xl, vertical = AppSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    color = tone.color(),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                CircularProgressIndicator(
                    color = tone.color(),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(44.dp),
                )
            }
            SpacerV(AppSpacing.Md)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                SpacerV(AppSpacing.Xs)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCancel != null) {
                SpacerV(AppSpacing.Lg)
                TextButton(onClick = AppHaptics.click(onCancel)) {
                    Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 全屏弹窗（AppFullScreenDialog）：占据整屏的编辑型弹窗（Material 四大弹窗类型之一）。
 * 顶部内置标题栏（关闭 + 标题 + 可选确认键），主体承载一长串/复杂任务，底部不再承载动作——
 * 与 `BasicAlertDialog` 的小卡片形成互补：前者"打断并决策"，本组件"进入并完成任务"。
 *
 * 适用：新建/编辑长表单、多步骤创建（如新建会话+配置模型+选择工作区）在移动端需充足空间的场景。
 *
 * @param confirmText / onConfirm 可选右上角确认键。
 * @param content 主体内容（ColumnScope，可滚动）。
 */
@Composable
fun AppFullScreenDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Xs, vertical = AppSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = AppHaptics.click(onDismiss)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.Sm),
                )
                if (confirmText != null && onConfirm != null) {
                    TextButton(onClick = AppHaptics.click(onConfirm)) {
                        Text(confirmText, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Lg),
            ) {
                content()
            }
        }
    }
}

/**
 * 评分弹窗（AppRatingDialog）：星级评分 + 可选反馈文本的轻量收集弹窗，
 * 是「应用评分 / 本轮会话体验 / 功能满意度」等态度的统一入口。
 *
 * 对齐主流反馈范式：星级可点选（半星见 [allowHalf]），确认后返回评分与留言；
 * 默认 `tone = Warning`（琥珀强调），契合"给出反馈"的积极语境。
 *
 * @param rating 当前星级（0..[max]）。
 * @param onRatingChange 点星回调。
 * @param onConfirm 返回 `data class`（星级 + 留言）；不传则确认键禁用。
 */
@Composable
fun AppRatingDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onRatingChange: (Int) -> Unit,
    rating: Int,
    modifier: Modifier = Modifier,
    max: Int = 5,
    allowHalf: Boolean = false,
    messagePlaceholder: String = "说说你的想法（可选）…",
    confirmText: String = "提交",
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.Star,
    tone: AppDialogTone = AppDialogTone.Warning,
    onConfirm: ((Int, String) -> Unit)? = null,
) {
    var feedback by remember { mutableStateOf("") }
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                enabled = onConfirm != null && rating > 0,
                onClick = AppHaptics.click { onConfirm?.invoke(rating, feedback) },
            ) {
                Text(confirmText)
            }
        },
        text = {
            // 星级：可点选；可半星模式下单击半星与整星间切换。
            val currentRating = if (allowHalf && (rating % 2 != 0)) (rating / 2f) else rating.toFloat()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(max) { i ->
                    val idx = i + 1
                    Icon(
                        imageVector = if (i < currentRating.toInt()) {
                            Icons.Rounded.Star
                        } else if (allowHalf && i == currentRating.toInt() && currentRating - i >= 0.5f) {
                            Icons.Rounded.StarHalf
                        } else {
                            Icons.Rounded.StarBorder
                        },
                        contentDescription = "$idx 星",
                        tint = AppColor.StatusWarning,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable(onClick = AppHaptics.click {
                                if (allowHalf && (rating % 2 != 0) && idx == (rating / 2) + 1) {
                                    onRatingChange(rating + 1)
                                } else {
                                    onRatingChange(idx)
                                }
                            }),
                    )
                    if (i < max - 1) Spacer(Modifier.width(AppSpacing.Xs))
                }
            }
            SpacerV(AppSpacing.Md)
            AppTextField(
                value = feedback,
                onValueChange = { feedback = it },
                label = "反馈",
                placeholder = messagePlaceholder,
                singleLine = false,
            )
        },
    )
}

/**
 * 破坏性输入确认弹窗（AppDestructiveConfirmDialog）：需键入指定短语才能激活确认键，
 * 是「删除账号 / 清空仓库 / 重置数据」等**高破坏性、难撤销**操作的强制注意力闸门。
 *
 * 对齐 uianatomy `type-to-confirm` / Radix 等的"防点头式误操作"范式：
 *  - 确认键初始禁用，`typed == [phrase]` 后才可用；
 *  - 标题陈述后果而非反问（如"删除账号？"而非"确定吗？"）；
 *  - 破坏性确认键此前默认焦点在取消（键盘误触不会破坏）；
 *  - 点遮罩可安全退出（等价取消）。
 *
 * @param phrase 需键入的文字（如 "delete"）；不相等则确认不可点。
 */
@Composable
fun AppDestructiveConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    phrase: String = "delete",
    confirmText: String = "确认删除",
    icon: ImageVector = Icons.Rounded.WarningAmber,
    tone: AppDialogTone = AppDialogTone.Danger,
) {
    var typed by remember { mutableStateOf("") }
    val enabled = typed.trim() == phrase
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = tone.color()),
                onClick = AppHaptics.click(onConfirm),
            ) {
                Text(if (enabled) confirmText else "输入\"$phrase\"以确认")
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SpacerV(AppSpacing.Md)
            AppTextField(
                value = typed,
                onValueChange = { typed = it },
                label = "输入 \"$phrase\"",
                placeholder = phrase,
                isError = typed.isNotEmpty() && !enabled,
                supportingText = "此操作不可撤销，请键入短语确认",
            )
        },
    )
}