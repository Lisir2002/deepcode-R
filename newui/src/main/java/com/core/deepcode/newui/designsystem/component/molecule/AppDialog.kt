package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 弹窗统一封装（§3.12 AppDialog / AppTokens 圆角边距）。
 * 破坏性确认：confirmText 用 StatusDanger，调用方在 onClick 外套 [AppHaptics.click]。
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissText: String? = null,
    confirmButtonColor: Color = Color.Unspecified,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(AppRadius.Lg),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = if (text != null) {
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmText, color = if (confirmButtonColor != Color.Unspecified) confirmButtonColor else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = if (dismissText != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(text = dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else null,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}