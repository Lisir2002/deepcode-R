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
 *
 * 实现采用**纯几何叠加**，无透明度/缩放动画：
 *  - 底层动作区固定贴卡片右缘（Arrangement.End，无缩进），滑开后稳定可见、不会自己消失；
 *  - 顶层内容层带不透明 surface 背景，左移 `settle` 距离来遮挡/露出动作区，
 *    展开态按钮始终在右缘、位置稳定，杜绝"消失 / 跑到卡片中间"两类缺陷。
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

    // 手指拖动时跟随位移；松开后按是否过半 snap 到 展开(-actionWidth) 或 收起(0)
    val open = dragOffset < -actionWidth / 2
    val settle by animateDpAsState(
        targetValue = if (dragging) dragOffset else if (open) -actionWidth else 0.dp,
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
        // 底层动作区：fixed 贴右，Arrangement.End 让按钮紧贴卡片右缘，
        // 不设透明度/缩放——显隐完全由上方内容层的几何偏移决定。
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight()
                .padding(start = AppSpacing.Sm),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }

        // 顶层内容滑层：带不透明 surface 背景以盖住底层动作区，
        // 左移 settle 露出右缘动作区，全部由几何呈现、稳定不消失。
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
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