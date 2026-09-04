package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 滑扫操作（分子组 · AppSwipeAction）：横向拖出底层操作（锚定 snap 到 0 / -actionWidth），
 * 用于 ListRow / 会话卡片右滑露出删除/置顶等高频操作，替代突兀的长按菜单。
 */
@Composable
fun AppSwipeAction(
    modifier: Modifier = Modifier,
    actionWidth: Dp = 96.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var dragOffset by remember { mutableStateOf(0.dp) }
    var dragStart by remember { mutableStateOf(0.dp) }
    var dragging by remember { mutableStateOf(false) }
    val settle by animateDpAsState(
        targetValue = if (dragging) dragOffset else if (dragOffset < -actionWidth / 2) -actionWidth else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "swipeSettle",
    )
    val shape = RoundedCornerShape(AppRadius.Md)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        // 底层动作区（右滑后露出）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight()
                .padding(end = AppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
        // 顶层内容滑层
        Box(
            Modifier
                .fillMaxSize()
                .offset(x = settle)
                .pointerInput(actionWidth) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragStart = settle },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        dragging = true
                        dragOffset = (dragStart + delta.dp).coerceIn(-actionWidth, 0.dp)
                    }
                },
        ) {
            content()
        }
    }
}