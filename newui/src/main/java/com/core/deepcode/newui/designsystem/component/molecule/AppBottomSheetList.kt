package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 抽屉选择列表（分子组 · AppBottomSheetList）：用 ModalBottomSheet 承载的
 * 底部抽屉式选择菜单，内置顶部标题 + 选项列表（复用 [AppSelectionList]）。
 *
 * 适用：模型选择、根目录选择、批量操作等需要"抽屉 + 列表"的横屏省空间场景。
 *
 * @param onDismiss 关闭回调（下滑/遮罩点击）。
 * @param content 底部抽屉内容；通常传 [AppSelectionList]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheetList(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
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
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = AppSpacing.Sm),
                )
            }
            content()
        }
    }
}