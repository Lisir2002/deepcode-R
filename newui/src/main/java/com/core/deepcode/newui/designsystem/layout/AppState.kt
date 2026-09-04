package com.core.deepcode.newui.designsystem.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.core.deepcode.newui.designsystem.component.atom.AppIcon
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 三态（§3.8）：统一 under a sealed UiState，页面级 when 强穷尽。
 * Loading / Empty / Error 组件收口到 core（新：designsystem.layout），禁各页自造三态。
 */
sealed interface AppUiState<out T> {
    data object Loading : AppUiState<Nothing>
    data object Empty : AppUiState<Nothing>
    data class Error(val message: String, val detail: String? = null) : AppUiState<Nothing>
    data class Content<T>(val data: T) : AppUiState<T>
}

/** 加载态：居中转圈（局部区块传入 data 模式空内容）。 */
@Composable
fun AppLoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 空态：图标 + 主文案 + 可选次文案 + 可选操作。 */
@Composable
fun AppEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(AppSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        AppIcon(icon = icon, size = AppSpacing.Xxl)
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) action()
    }
}

/** 错误态：错误图标 + 主文案 + 重试按钮。 */
@Composable
fun AppErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(AppSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        AppIcon(
            icon = Icons.Rounded.Error,
            size = AppSpacing.Xxl,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onRetry != null) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text(text = "重试")
            }
        }
    }
}

/** 页面级四态模板：Loading/Empty/Error/Content 强穷尽。 */
@Composable
fun <T> AppStateSwitch(
    state: AppUiState<T>,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            AppUiState.Loading -> AppLoadingState(Modifier.fillMaxSize())
            AppUiState.Empty -> emptyContent()
            is AppUiState.Error -> AppErrorState(state.message, Modifier.fillMaxSize(), onRetry)
            is AppUiState.Content -> content(state.data)
        }
    }
}