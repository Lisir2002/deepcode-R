package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 按钮统一封装（§3.12 AppButton）：四种变体，新增页面默认首选，禁止裸 `Box.clickable` 当按钮。
 */
enum class AppButtonVariant { Primary, FilledTonal, Outlined, Text }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
) {
    when (variant) {
        AppButtonVariant.Primary -> Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
        AppButtonVariant.FilledTonal -> FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
        AppButtonVariant.Outlined -> OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
        AppButtonVariant.Text -> TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(text)
        }
    }
}

/** 按钮（带前导图标，用于"新建/导入"等带图入口）。 */
@Composable
fun AppIconButton(
    text: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
) {
    when (variant) {
        AppButtonVariant.Primary -> Button(onClick = onClick, modifier = modifier, enabled = enabled) {
            IconButtonContent(text, leadingIcon)
        }
        AppButtonVariant.FilledTonal -> FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            IconButtonContent(text, leadingIcon)
        }
        AppButtonVariant.Outlined -> OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            IconButtonContent(text, leadingIcon)
        }
        AppButtonVariant.Text -> TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            IconButtonContent(text, leadingIcon)
        }
    }
}

/** 带图标按钮通用内容：前导图标 + 间距 + 文字。 */
@Composable
private fun RowScope.IconButtonContent(
    text: String,
    leadingIcon: @Composable () -> Unit,
) {
    leadingIcon()
    Spacer(Modifier.padding(start = AppSpacing.Sm))
    Text(text)
}