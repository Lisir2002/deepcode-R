package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlinx.coroutines.launch

/** 滑扫锚点状态：Closed（收起）/ Open（展开露出操作区）。 */
internal enum class SwipeValue { Closed, Open }

/**
 * 滑扫操作的提升状态。
 *
 * 滑动 / fling / settle / clamp 全由 `AnchoredDraggableState` 托管（与 Material
 * `SwipeToDismissBox` 内部同源），此处仅暴露业务关心的收口 API，便于外部持有并在
 * 列表场景做「同批只开一项」协调。
 */
class AppSwipeActionState internal constructor(
    internal val anchored: AnchoredDraggableState<SwipeValue>,
) {
    /** 是否已稳定展开（仅 settle 完成后才翻转，拖动跟手过程保持 false）。 */
    val isOpen: Boolean
        get() = anchored.settledValue == SwipeValue.Open

    /** 当前内容层横向位移（px），Closed 为 0，Open 为 -actionWidth 对应 px。 */
    val offset: Float
        get() = anchored.requireOffset()

    /** 展开露出操作区。 */
    suspend fun open() = anchored.animateTo(SwipeValue.Open)

    /** 收起（弹回原位）。 */
    suspend fun close() = anchored.animateTo(SwipeValue.Closed)
}

/** 创建可提升的滑扫状态；`actionWidth` 必须与 AppSwipeAction 保持一致。 */
@Composable
fun rememberAppSwipeActionState(
    actionWidth: Dp = 96.dp,
): AppSwipeActionState {
    val density = LocalDensity.current
    // 展开边界（px）。actionWidth 恒定，锚点稳定不随重绘漂移。
    val maxOffset = remember(actionWidth) { with(density) { actionWidth.toPx() } }
    val velocityThreshold = remember(maxOffset) { maxOffset.coerceAtLeast(48f) }

    val anchors = remember(maxOffset) {
        DraggableAnchors {
            SwipeValue.Closed at 0f
            SwipeValue.Open at -maxOffset
        }
    }

    val anchored = remember(anchors, velocityThreshold) {
        AnchoredDraggableState(
            initialValue = SwipeValue.Closed,
            anchors = anchors,
            // settle 前半程非速度触发的宽松阈值：约 40% 处可翻转，左滑更易保持展开
            positionalThreshold = { distance -> distance * 0.4f },
            velocityThreshold = { velocityThreshold },
            snapAnimationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            decayAnimationSpec = exponentialDecay(),
            confirmValueChange = { true },
        )
    }
    return remember(anchored) { AppSwipeActionState(anchored) }
}

/**
 * 滑扫操作按钮（预留多按钮接口）：图标 + 文字垂直排布，等宽平分动作区宽度，
 * 单个按钮的次序即从靠右的方向开始填充（配合动作区 [Arrangement.End]），
 * 支持任意数量的操作按钮（删除 / 置顶 / 归档…）与独立色调定制。
 *
 * 内部以 [RowScope.weight] 平分 [AppSwipeAction] 的 `actionWidth`，保证按钮
 * 永远恰好铺满动作区、不溢出也不留白，最右侧按钮严格贴卡片右缘。
 */
@Composable
fun RowScope.AppSwipeButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(AppSizing.IconM),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AppSpacing.Xs),
        )
    }
}

/**
 * 滑扫操作（分子组 · AppSwipeAction）：横向拖出底层操作（锚定 snap 到 0 / -actionWidth），
 * 用于 ListRow / 会话卡片左滑露出删除/置顶等高频操作，替代突兀的长按菜单。
 *
 * 设计要点（对齐行业成熟实现与官方手势原语）：
 *  - 布局**纯几何叠加**：动作区固定贴右缘、内容层带不透明 surface 背景随 offset 平移遮挡 /
 *    露出右缘动作区 → 按钮位置稳定、不会莫名消失 / 跑到卡片中间；
 *  - 拖动跟手、松手后**弹簧归位 + 甩动速度判定前往目标锚点**、越界自动 clamp 均由
 *    `AnchoredDraggableState` 内建处理（`positionalThreshold` / `velocityThreshold` / decay）；
 *  - **滑动过程不预览操作按钮**：动作区 `alpha` 由 `settledValue == Open` 决定，拖动不改变
 *    `settledValue`，故跟手时按钮保持隐藏，松手并确认展开后才 tween 淡入；滑动不足直接弹回；
 *  - **点击已展开的内容可收起**：展开后再点内容即弹回，交互更完整；
 *  - **同批只开一项**（列表场景）：配合 `index` / `expandedIndex` / `onExpanded` 由外部协调，
 *    滑开任意一项时其它项自动收起（参照 revealable 库的 single-expansion 模式）。
 *
 * @param state 提升状态；不传则内部自动记住一份（单独使用）。
 * @param index / expandedIndex / onExpanded 列表协调用；仅当三者被提供才启用"只开一项"。
 */
@Composable
fun AppSwipeAction(
    modifier: Modifier = Modifier,
    actionWidth: Dp = 96.dp,
    state: AppSwipeActionState? = null,
    index: Int? = null,
    expandedIndex: Int? = null,
    onExpanded: ((Int?) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedState = state ?: rememberAppSwipeActionState(actionWidth)
    val scope = rememberCoroutineScope()

    // 用最新回调，避免闭包捕获过期 lambda。
    val currentOnExpanded by rememberUpdatedState(onExpanded)

    // 同批只开一项：响应式监听本项「是否已展开」的翻转，展开 -> 上报 index；收起且恰为选中项 -> 清空。
    LaunchedEffect(resolvedState, index, expandedIndex) {
        if (index == null) return@LaunchedEffect
        snapshotFlow { resolvedState.isOpen }
            .collect { nowOpen ->
                val cb = currentOnExpanded ?: return@collect
                when {
                    nowOpen && expandedIndex != index -> cb(index)
                    !nowOpen && expandedIndex == index -> cb(null)
                }
            }
    }
    // 其它项被选中展开时，本项自动收起。
    LaunchedEffect(resolvedState, index, expandedIndex) {
        if (index != null && expandedIndex != null && expandedIndex != index && resolvedState.isOpen) {
            resolvedState.close()
        }
    }

    // 只有 settle 稳定到 Open 才展示按钮；拖动不改变 settledValue -> 跟手过程不预览。
    val revealed by remember {
        derivedStateOf { resolvedState.isOpen }
    }
    val actionsAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "swipeActionsAlpha",
    )

    val shape = RoundedCornerShape(AppRadius.Md)

    Box(
        modifier = modifier
            .shadow(1.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        // 底层动作区：fixed 贴右，Arrangement.End 让按钮严格贴卡片右缘排列，透明度由展开态决定。
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth)
                .fillMaxHeight()
                .graphicsLayer { alpha = actionsAlpha },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }

        // 顶层内容滑层：带不透明 surface 背景盖住动作区，随锚点 offset 平移遮挡/露出右缘动作；
        // 展开后点击内容可收起。
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .graphicsLayer { translationX = resolvedState.offset }
                .clickable(
                    enabled = revealed,
                    onClick = { scope.launch { resolvedState.close() } },
                )
                .anchoredDraggable(
                    state = resolvedState.anchoredState(),
                    orientation = Orientation.Horizontal,
                    reverseDirection = false,
                ),
        ) {
            content()
        }
    }
}

/** 供 composable 内部取用底层 AnchoredDraggableState。 */
internal fun AppSwipeActionState.anchoredState(): AnchoredDraggableState<SwipeValue> = this.anchored