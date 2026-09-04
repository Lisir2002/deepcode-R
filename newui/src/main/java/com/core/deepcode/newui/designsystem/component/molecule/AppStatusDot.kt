package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 状态圆点（§3.12 AppStatusDot）：状态点统一形态，语义色库见 AppColor.Status*。
 * 可选 label：点 + 文字。
 */
@Composable
fun AppStatusDot(
    color: Color,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(AppSpacing.Sm)
                .background(color, CircleShape),
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = AppSpacing.Xs),
            )
        }
    }
}