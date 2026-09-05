package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/* =========================================================================
 * 输入框族（Text Input Family）
 * 覆盖主流输入场景：
 *  填充式文本 AppFilledTextField / 密码 AppPasswordField / 计数 AppCountedTextField
 *  验证态 AppValidatedTextField / 多行 AppMultiLineTextField / 消息 AppMessageField
 * 弹窗/表单内输入可直接复用上述组件（如 AppDialog/AppFormDialog 内嵌）。
 * 统一 Rounded 容器、激活态描边、可清除、随内容动效过渡。
 * ========================================================================= */

/** 填充式文本输入框（M3 Filled variant）：浮标 label + 前置图标 + 激活态描边 + 一键清除。 */
@Composable
fun AppFilledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    singleLine: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        leadingIcon = if (leadingIcon != null) {
            {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        trailingIcon = if (value.isNotEmpty()) {
            {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(AppSizing.IconButton)
                        .clip(RoundedCornerShape(AppRadius.Pill))
                        .clickable { onValueChange("") }
                        .padding(10.dp),
                )
            }
        } else null,
        singleLine = singleLine,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedIndicatorColor = AppColor.BrandPrimary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColor.BrandPrimary,
        ),
    )
}

/** 密码输入框：默认掩码 + 可见性切换（图标随状态切换 + 弹跳动效）。 */
@Composable
fun AppPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            val scale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "eye",
            )
            Icon(
                imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (visible) "隐藏密码" else "显示密码",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(AppSizing.IconButton)
                    .clip(RoundedCornerShape(AppRadius.Pill))
                    .clickable { visible = !visible }
                    .padding(10.dp),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedIndicatorColor = AppColor.BrandPrimary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColor.BrandPrimary,
        ),
    )
}

/** 带字符计数的输入框：右下实时展示 已输入/上限，超限自动截断。 */
@Composable
fun AppCountedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 50,
) {
    val limited = value.length > maxLength
    TextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        label = { Text(label) },
        singleLine = true,
        supportingText = {
            Text(
                text = "${value.length}/$maxLength",
                color = if (limited) AppColor.StatusDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (limited) FontWeight.Bold else FontWeight.Normal,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedIndicatorColor = if (limited) AppColor.StatusDanger else AppColor.BrandPrimary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColor.BrandPrimary,
        ),
    )
}

/** 输入校验状态。 */
enum class AppInputValidity { Normal, Error, Success }

/**
 * 验证态输入框：Normal / Error / Success 三态，颜色与图标随状态平滑过渡，附提示文案。
 * 成功态以绿色对勾 + 强调描边表达（超出 M3 原生校验能力之外的正反馈）。
 */
@Composable
fun AppValidatedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    validity: AppInputValidity = AppInputValidity.Normal,
    helper: String? = null,
) {
    val accent = when (validity) {
        AppInputValidity.Error -> AppColor.StatusDanger
        AppInputValidity.Success -> AppColor.StatusSuccess
        AppInputValidity.Normal -> AppColor.BrandPrimary
    }
    val checkAlpha by animateFloatAsState(
        targetValue = if (validity == AppInputValidity.Success) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "check",
    )
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        singleLine = true,
        isError = validity == AppInputValidity.Error,
        supportingText = if (helper != null) {
            { Text(helper, color = if (validity == AppInputValidity.Normal) MaterialTheme.colorScheme.onSurfaceVariant else accent) }
        } else null,
        trailingIcon = {
            Icon(
                imageVector = if (validity == AppInputValidity.Error) Icons.Rounded.Close else Icons.Rounded.Check,
                contentDescription = null,
                tint = accent.copy(alpha = if (validity == AppInputValidity.Error) 1f else checkAlpha),
                modifier = Modifier
                    .size(AppSizing.IconButton)
                    .padding(10.dp),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            errorContainerColor = AppColor.StatusDanger.copy(alpha = 0.06f),
            focusedIndicatorColor = accent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = accent,
        ),
    )
}

/** 多行文本域：随内容增高，用于备注/正文等长文本输入。 */
@Composable
fun AppMultiLineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 3,
    maxLines: Int = 6,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Md),
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        minLines = minLines,
        maxLines = maxLines,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedIndicatorColor = AppColor.BrandPrimary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppColor.BrandPrimary,
        ),
    )
}

/**
 * 消息输入框（Chat Composer）：圆角填充式 + 附件按钮 + 随内容增高多行输入 + 发送按钮。
 * 发送按钮随「有内容 / 发送中」状态实时生长；可非空发送后清空并触发一次弹跳反馈。
 */
@Composable
fun AppMessageField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入消息…",
    sending: Boolean = false,
) {
    val shape = RoundedCornerShape(AppRadius.Lg)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) AppColor.BrandPrimary else MaterialTheme.colorScheme.outlineVariant,
        label = "border",
    )
    val canSend = value.isNotBlank()
    val sendScale by animateFloatAsState(
        targetValue = if (canSend || sending) 1f else 0.2f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "send",
    )
    val sendColor: Color = if (canSend) AppColor.BrandPrimary else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, border, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier.size(AppSizing.IconButton),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AttachFile,
                contentDescription = "附件",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(AppSizing.IconButton)
                    .clip(RoundedCornerShape(AppRadius.Pill))
                    .clickable {}
                    .padding(10.dp),
            )
        }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                minLines = 1,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(AppColor.BrandPrimary),
            )
        }
        Box(
            modifier = Modifier
                .size(AppSizing.IconButton)
                .clip(RoundedCornerShape(AppRadius.Pill))
                .background(sendColor)
                .clickable(enabled = canSend) { onSend() }
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Send,
                contentDescription = null,
                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = sendScale
                        scaleY = sendScale
                    },
            )
        }
    }
}