package com.R.codecore.core.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ──────────────────────────────────────────────
// Z 轴层次常量（elevation）
// ──────────────────────────────────────────────
object Elevation {
    /** 背景层：页面背景、容器背景 */
    val z0: Dp = 0.dp
    /** 内容层：列表、卡片、正文区域 */
    val z1: Dp = 1.dp
    /** 交互层：输入栏、按钮、可交互卡片 */
    val z2: Dp = 3.dp
    /** 浮层：面板、弹出 Sheet、Dialog */
    val z3: Dp = 8.dp
    /** 通知层：状态横幅、Toast */
    val z4: Dp = 12.dp
}

// ──────────────────────────────────────────────
// 统一紧凑型 AppTopAppBar：
//   - 与 ChatHeader 代码结构完全一致，保证高度、图标比例 100% 对齐
//   - 背景色 surface + statusBarsPadding()
//   - 内容行固定 height(44dp)
//   - 导航/action 图标按钮 size(40dp)，图标 size(20dp)
//   - 不再依赖 Material3 TopAppBar（其默认 minHeight 64dp 无法紧凑化）
// ──────────────────────────────────────────────
@Composable
fun AppTopAppBar(
    title: String,
    onNavigateBack: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    navigationContentDescription: String? = null,
    actions: @Composable () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onNavigateBack != null && navigationIcon != null) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = navigationIcon,
                        contentDescription = navigationContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(Spacing.md))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            actions()
            Spacer(Modifier.width(Spacing.sm))
        }
    }
}

// ──────────────────────────────────────────────
// 统一空状态组件（图标 + 标题 + 副标题 + 可选操作按钮）
// ──────────────────────────────────────────────
@Composable
fun AppEmptyState(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(Spacing.lg))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

// ──────────────────────────────────────────────
// 统一加载状态组件
// ──────────────────────────────────────────────
@Composable
fun AppLoadingState(
    loadingText: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        if (loadingText != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = loadingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ──────────────────────────────────────────────
// 带左侧装饰条的 SectionHeader
// ──────────────────────────────────────────────
@Composable
fun AppSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(Radius.xs))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ──────────────────────────────────────────────
// 分组背景容器（设置页分组用）
//   - 浅色背景块 + 圆角
//   - 内部子项无边框，靠间距区分
// ──────────────────────────────────────────────
@Composable
fun AppSectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            content()
        }
    }
}

// ──────────────────────────────────────────────
// 统一页面过渡动画
//
// 注意：Terminal 页面使用 AndroidView（TerminalView），
//   fade 过渡会导致新旧 composable 共存时 TerminalView 覆盖新页面。
//   因此 terminal 路由使用纯 slide 过渡，其他页面用 slide+fade。
// ──────────────────────────────────────────────
val pageEnterTransition: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(250),
        initialOffsetX = { it / 4 }
    ) + fadeIn(animationSpec = tween(250))

val pageExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(200))

val pagePopEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(200))

val pagePopExitTransition: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(250),
        targetOffsetX = { it / 4 }
    ) + fadeOut(animationSpec = tween(200))

val terminalEnterTransition: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(250),
        initialOffsetX = { it / 4 }
    )

val terminalExitTransition: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(250),
        targetOffsetX = { it / 4 }
    )

