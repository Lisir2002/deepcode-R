package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlinx.coroutines.delay

/**
 * 表单弹窗（AppFormDialog）：多字段录入型弹窗（Material 四大类型之一的 Form dialog）。
 * 与 [AppPromptDialog]（单框）互补——承载"标题 + 说明"等多字段的轻量录入，
 * 常用于新建条目 / 快捷反馈 / 双字段配置。
 *
 * 设计要点：
 *  - 主字段必填（确认键在为空时禁用）；
 *  - 组合作用域内用 [Spacer]/高度做字段间距，避免只读占位；
 *  - 破坏性 tone 用红底确认键表达风险操作。
 *
 * @param onConfirm 返回 `Pair(subject, body)`（两字段当前值）。
 */
@Composable
fun AppFormDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    subjectLabel: String = "标题",
    bodyLabel: String = "说明",
    subjectPlaceholder: String? = null,
    bodyPlaceholder: String? = null,
    confirmText: String = "保存",
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.Article,
    tone: AppDialogTone = AppDialogTone.Info,
    singleLine: ((String) -> Boolean)? = null,
) {
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val ready = subject.isNotBlank()
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
                enabled = ready,
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click { onConfirm(subject, body) },
            ) {
                Text(confirmText)
            }
        },
        text = {
            AppTextField(
                value = subject,
                onValueChange = { subject = it },
                label = subjectLabel,
                placeholder = subjectPlaceholder,
                singleLine = true,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            AppTextField(
                value = body,
                onValueChange = { body = it },
                label = bodyLabel,
                placeholder = bodyPlaceholder,
                singleLine = singleLine?.invoke(body) ?: false,
            )
        },
    )
}

/**
 * 权限向导弹窗（AppPermissionDialog）：请求系统/应用权限前的**说明型**弹窗，
 * 对齐 Android 推荐的两步(前置说明 rationale → 正式授权) 范式 + iOS `Permission.Reply` 的
 * "允许 / 暂不" 二元决策：
 *
 *  - 用通俗文案解释"为什么需要"与"将如何使用"（降低拒绝率，避免系统弹窗突兀）；
 *  - 主操作「允许/去开启」触发 [onAllow]（去系统设置或请求运行时权限）；
 *  - 次操作「暂不」只关闭，不重复打扰；
 *  - 可传 [onDeniedForever] 展示"不再询问"兜底跳转。
 *
 * @param permissionIcon 权限图标（如通知 / 定位 / 存储）。
 */
@Composable
fun AppPermissionDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    permissionIcon: ImageVector = Icons.Rounded.Notifications,
    permissionName: String = "通知权限",
    allowText: String = "允许",
    deniedText: String = "暂不",
    tone: AppDialogTone = AppDialogTone.Info,
) {
    var denyOnce by remember { mutableStateOf(false) }
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = permissionIcon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(deniedText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click { onAllow() },
            ) {
                Text(allowText)
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                text = "适用于：$permissionName",
                style = MaterialTheme.typography.labelMedium,
                color = tone.color(),
                fontWeight = FontWeight.Medium,
            )
        },
    )
}

/**
 * 结果反馈弹窗（AppSuccessDialog）：操作完成后的成功/结果确认弹窗，
 * 大号对勾 + 标题 + 摘要 + 可选明细行，主键「完成」收口。
 * 用于"导出完成 / 保存成功 / 发版通过"等正向结果传达。
 *
 * @param detail 可选明细说明（逐条 •）。
 */
@Composable
fun AppSuccessDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    detail: List<String> = emptyList(),
    confirmText: String = "完成",
    icon: ImageVector = Icons.Rounded.CheckCircle,
    tone: AppDialogTone = AppDialogTone.Success,
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
                colors = ButtonDefaults.buttonColors(containerColor = tone.color()),
                onClick = AppHaptics.click(onDismiss),
            ) {
                Text(confirmText)
            }
        },
        text = {
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detail.forEach { line ->
                        Row {
                            Text("• ", color = tone.color(), style = MaterialTheme.typography.bodySmall)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
    )
}

/**
 * 多选确认弹窗（AppMultiSelectDialog）：Simple+Confirmation 的融合——
 * 显示一组**可勾选**项，先选再点「确定」才收口（与 [AppSelectionDialog] 选中即生效不同）。
 * 用于批量导出字段 / 指定通知范围 / 多项目归档等需要"看清楚再确认"的场景。
 *
 * @param optionsData 选项列表（使用 label）。
 * @param selectedData 当前已选 label 集合。
 * @param onToggle 勾选切换回调（由调用方维护集合）。
 * @param onConfirm 确认回调（携带最终选中集）。
 */
@Composable
fun AppMultiSelectDialog(
    visible: Boolean,
    title: String,
    options: List<String>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "确定",
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.Security,
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
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click(onConfirm),
            ) {
                Text(confirmText)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, opt ->
                    val checked = index in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.Md))
                            .clickable(onClick = AppHaptics.click { onToggle(index) })
                            .padding(vertical = AppSpacing.Xs, horizontal = AppSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(index) },
                        )
                        Text(
                            text = opt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    )
}

/**
 * 错误/重试弹窗（AppErrorDialog）：报告失败原因并给出「重试」出口的阻断式弹窗
 * （对齐 Ant Design `Modal.error` / M3 Error Alert）。与 [AppAlertDialog]（单动作告知）不同，
 * 这里承载**失败 + 可行动的补救**：主键「重试」重试，次键「关闭」退出，可附明细 steps。
 *
 * @param detail 可选失败明细（逐条 •），如检查项/错误码清单。
 * @param onRetry 重试回调（主键）。
 */
@Composable
fun AppErrorDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    detail: List<String> = emptyList(),
    retryText: String = "重试",
    closeText: String = "关闭",
    icon: ImageVector = Icons.Rounded.WarningAmber,
    tone: AppDialogTone = AppDialogTone.Danger,
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
                Text(closeText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = tone.color()),
                onClick = AppHaptics.click { onRetry() },
            ) {
                Text(retryText)
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detail.forEach { line ->
                        Row {
                            Text("• ", color = tone.color(), style = MaterialTheme.typography.bodySmall)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
    )
}

/**
 * 条款/同意弹窗（AppConsentDialog）：首次进入需用户勾选同意的条款与隐私弹窗
 * （对齐 Ant Design 协议 Modal / 注册同意范式）。核心是**勾选即解锁**——未勾选时「同意/继续」
 * 禁用，防止"没看就点了"；退出走「暂不进入」。
 *
 * @param termsLabel 勾选文案（如「我已阅读并同意《服务协议》与《隐私政策》」）。
 * @param onAgree 勾选后点「同意并继续」回调。
 * @param onDecline 暂不进入/退出回调。
 */
@Composable
fun AppConsentDialog(
    visible: Boolean,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onAgree: () -> Unit,
    modifier: Modifier = Modifier,
    termsLabel: String = "我已阅读并同意《服务协议》与《隐私政策》",
    agreeText: String = "同意并继续",
    declineText: String = "暂不进入",
    icon: ImageVector = Icons.Rounded.Security,
    tone: AppDialogTone = AppDialogTone.Warning,
) {
    var agreed by remember { mutableStateOf(false) }
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(declineText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(AppSpacing.Sm))
            Button(
                enabled = agreed,
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click(onAgree),
            ) {
                Text(agreeText)
            }
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.Sm))
                    .clickable(onClick = AppHaptics.click { agreed = !agreed })
                    .padding(vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(
                    text = termsLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

/**
 * 不再询问提醒弹窗（AppReminderDialog）：重复性提示弹窗上提供「不再提示」勾选，
 * 降低对用户的打扰（对齐 Material 3 对重复 prompt 的"不再提醒"处理原则）。
 *
 * - 底部常驻勾选框，确认时把勾选态一并返回，调用方据此控制下次是否再弹；
 * - 主键「知道了」收口，与去重逻辑解耦（由调用方持久化 `rememberChecked`）。
 *
 * @param rememberChecked / onRememberChange 是否"不再提示"（受控，由调用方持久化）。
 * @param checkText 勾选框文案。
 */
@Composable
fun AppReminderDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rememberChecked: Boolean = false,
    onRememberChange: ((Boolean) -> Unit)? = null,
    confirmText: String = "知道了",
    checkText: String = "不再提示",
    icon: ImageVector = Icons.Rounded.Notifications,
    tone: AppDialogTone = AppDialogTone.Info,
) {
    var localChecked by remember(rememberChecked) { mutableStateOf(rememberChecked) }
    val checked = onRememberChange?.let { rememberChecked } ?: localChecked
    val setChecked: (Boolean) -> Unit = { v ->
        if (onRememberChange != null) onRememberChange(v) else localChecked = v
    }
    AppDialogSurface(
        visible = visible,
        onDismiss = onDismiss,
        tone = tone,
        title = title,
        icon = icon,
        modifier = modifier,
        actions = {
            Button(
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click { onConfirm(checked) },
            ) {
                Text(confirmText)
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.Sm))
                    .clickable(onClick = AppHaptics.click { setChecked(!checked) })
                    .padding(vertical = AppSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = setChecked)
                Text(
                    text = checkText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * 倒计时自动确认弹窗（AppCountdownDialog）：破坏性操作前的**安全防误**弹窗
 * （对齐「Slack 删除倒计时」「Etrade execute-with-delay」等防误范式）。
 *
 * 与 [AppConfirmDialog] 的不同：确认键在开头 [seconds] 秒内**禁用并闪烁倒计时**，
 * 倒计时结束才解锁——强制用户读完后果再确认，从根上降低误触吞删除的几率。
 *
 * 设计要点：
 *  - 可感知的倒计时数字（`删除 (3)`→`删除 (2)`→`删除`）；
 *  - 底部一条与倒计时同步推进的进度条，让"还剩多久"一目了然；
 *  - 倒计时期间允许离开（可取消），不会把人困住。
 */
@Composable
fun AppCountdownDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    seconds: Int = 3,
    confirmText: String = "确定",
    cancelText: String = "取消",
    icon: ImageVector = Icons.Rounded.WarningAmber,
    tone: AppDialogTone = AppDialogTone.Danger,
) {
    var remaining by remember(visible, seconds) { mutableStateOf(seconds) }
    LaunchedEffect(visible, seconds) {
        remaining = seconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    val unlocked = remaining <= 0
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
                enabled = unlocked,
                colors = if (tone == AppDialogTone.Danger) {
                    ButtonDefaults.buttonColors(containerColor = tone.color())
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = AppHaptics.click(onConfirm),
            ) {
                if (unlocked) {
                    Text(confirmText)
                } else {
                    Text("$confirmText ($remaining)")
                }
            }
            Spacer(Modifier.width(AppSpacing.Sm))
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingUnderActions = {
            if (!unlocked) {
                Spacer(Modifier.height(AppSpacing.Md))
                LinearProgressIndicator(
                    progress = { remaining.toFloat() / seconds.toFloat().coerceAtLeast(1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = AppSpacing.Md),
                    color = tone.color(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        },
    )
}

/**
 * 阻塞加载遮罩（AppLoadingOverlay）：整屏/整面板的模态加载罩，
 * 盖住主内容并随 [visible] 淡入淡出——用于导出 / 同步 / 批量处理等不可打断的长任务。
 *
 * 与 [AppProgressDialog]（居中弹窗）不同，这里是**满铺遮罩**：
 *  - 中心转圈（[progress] 为 null 时不限量）；传 [progress] 则转为定量进度条；
 *  - 底部可附说明文案（如「正在同步 3/5 …」）；
 *  - `scrimAlpha` 控制盖住内容的暗化程度。
 *
 * @param progress 0..1 定量进度；null 表示不限量加载。
 * @param scrimAlpha 遮罩不透明度（0..1），越大主内容越暗。
 */
@Composable
fun AppLoadingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String? = null,
    progress: Float? = null,
    scrimAlpha: Float = 0.42f,
    spinnerColor: Color = MaterialTheme.colorScheme.primary,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
            ) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .width(160.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(percent = 50)),
                        color = spinnerColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    CircularProgressIndicator(
                        color = spinnerColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp),
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}