package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 分组容器（§3.12 AppSectionGroup）：可选区块标题 + 卡片化内容区，行与行间用 [AppDivider] 分隔。
 */
@Composable
fun AppSectionGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (header != null) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
        Surface(
            modifier = Modifier,
            shape = RoundedCornerShape(AppRadius.Md),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = AppElevation.Z1,
        ) {
            Column(content = content)
        }
    }
}

/** 分组内行分隔（§3.12 分割 → AppLayout.DividerThickness）。 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = com.core.deepcode.newui.designsystem.token.generated.AppLayout.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}