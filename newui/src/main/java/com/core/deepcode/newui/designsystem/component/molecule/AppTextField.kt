package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 文本输入统一封装（§3.12 AppTextField）：OutlinedTextField + 统一填充/描边/圆角。
 * 紧凑场景可自行用 BasicTextField，但需对齐本节形制。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.Sm),
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        singleLine = singleLine,
        isError = isError,
        supportingText = if (supportingText != null) {
            { Text(supportingText, fontWeight = FontWeight.Normal) }
        } else null,
    )
}