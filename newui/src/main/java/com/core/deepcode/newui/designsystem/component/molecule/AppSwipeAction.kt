package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 滑扫操作（分子组 · AppSwipeAction）：横向拖出底层操作（锚定 snap 到 0 / -actionWidth），
 * 用于 ListRow / 会话卡片右滑露出删除/置顶等高频操作，替代突兀的长按菜单。
 *
 * 实现采用**纯几何叠加 + 展开态判定**：
 *  - 底层动作区固定贴卡片右缘（Arrangement.End，无缩进），滑开后稳定可见、不会自己消失；
 *  - **滑动跟手过程隐藏操作按钮**（alpha=0，内容层平移只露出空白底），
 *    松手且滑过阈值判定为展开后，按钮才 tween 淡入；滑不够则内容层直接弹回、按钮全程不出现，
 *    满足"不显示操作按钮，直接弹回"；
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

    // 展开态由"松手时"一次性判定（不是拖动跟手实时判定），
    // 因此滑动过程按钮保持隐藏，只有确定展开后才淡入。
    var expanded by remember { mutableStateOf(false) }

    // 手指拖动时跟随位移；松手后按 expanded snap 到 展开(-actionWidth) 或 收起(0)
    val settle by animateDpAsState(
        targetValue = if (dragging) dragOffset else if (expanded) -actionWidth else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "swipeSettle",
    )
    // 操作按钮显隐：仅在确认展开后淡入，用稳定的 tween（非弹簧），绝不随拖动抖动
    val actionsAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "swipeActionsAlpha",
    )
    val shape = RoundedCornerShape(AppRadius.Md)

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        // 底层动作区：fixed 贴右，Arrangement.End 让按钮紧贴卡片右缘，
        // 显隐仅由 expanded 的 alpha 控制——滑动跟手时不预览按钮，触发展开才淡入。
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight()
                .padding(start = AppSpacing.Sm)
                .graphicsLayer { alpha = actionsAlpha },
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
                        onDragStart = {
                            dragStart = settle
                            dragging = true
                        },
                        onDragEnd = {
                            dragging = false
                            // 滑过一半才算展开，否则直接弹回、按钮全程不出现
                            expanded = dragOffset < -actionWidth / 2
                        },
                        onDragCancel = {
                            dragging = false
                            expanded = dragOffset < -actionWidth / 2
                        },
                    ) { change, delta ->
                        change.consume()
                        dragOffset = (dragStart + delta.dp).coerceIn(-actionWidth, 0.dp)
                    }
                },
        ) {
            content()
        }
    }
}