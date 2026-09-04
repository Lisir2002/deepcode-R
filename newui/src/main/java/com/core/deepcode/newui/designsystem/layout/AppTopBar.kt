package com.core.deepcode.newui.designsystem.layout

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.core.deepcode.newui.designsystem.component.atom.AppIcon

/**
 * 顶栏槽位（§2.4.1 TopAppBar）：标题 + 导航返回 + 操作按钮（Composable 插槽惯例，见 §2.3.4b）。
 * 高度默认 TouchTarget。滚动抬升（z2）在 S1 通过 M3 `TopAppBarScrollBehavior` 续接（依赖调用方滚动状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { if (navigationIcon != null) navigationIcon() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** 顶栏返回钮（占位插槽，避免每个 Screen 重复写）；启发式图标固定取 AutoMirrored.ArrowBack。 */
@Composable
fun TopBarBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        AppIcon(icon = Icons.AutoMirrored.Filled.ArrowBack)
    }
}