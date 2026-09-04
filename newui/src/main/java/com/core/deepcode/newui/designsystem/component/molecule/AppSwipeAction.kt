package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 滑扫锚点：Closed（收起）/ Open（展开露出动作栏）/ Trigger（全滑确认点）。
 */
enum class SwipeValue { Closed, Open, Trigger }

/**
 * 滑扫行为模式（对齐 Swipeable-KMP 双模式 / SwiftUI `swipeActions` 语义）：
 *
 *  - **Reveal**（默认 · iOS 默认）——左滑越过阈值**不自动回弹**，稳定展开露出操作栏，
 *    直到点按钮 / 点内容区 / 打开另一行才收起（`pattern = AppSwipePattern.Reveal`）；
 *  - **Dismiss**（SwiftUI `allowsFullSwipe`）——全滑到底（越过 [AppSwipeAction.onTrigger] 阈值）
 *    立即触发一次 [AppSwipeAction.onTrigger] 并**自动收起**，用于"滑一下直接执行"的单动作列表
 *    （如快速删除 / 快速归档），省去二次点击。
 */
enum class AppSwipePattern { Reveal, Dismiss }

/**
 * 动作栏按钮的揭示进度（0..1）：由 [AppSwipeAction] 注入，随拖拽进度实时更新，
 * [AppSwipeButton] 据此做渐变亮起 / 上滑 / 缩放 / 投影浮现。默认 1f 便于脱离滑扫容器单独使用。
 */
internal val LocalSwipeReveal = staticCompositionLocalOf { 1f }

/**
 * 揭示序号计数器：由 [AppSwipeAction] 在每次组合时为整条动作栏注入一个全新实例，
 * [AppSwipeButton] 按出现顺序自增序号，实现「级联/递进逐盏亮起」（cascade reveal）。
 *
 * 注意：`order++` 的副作用发生在 `remember` 的初始化里，因此必须在**组合作用域**先取
 * `LocalSwipeSequence.current`（在 remember 计算值 lambda 内读 CompositionLocal 不合法），
 * 再在 remember 初始化块内自增。
 */
internal class SwipeSequenceState {
    var order = -1
}
internal val LocalSwipeSequence = staticCompositionLocalOf { SwipeSequenceState() }

/**
 * 动作栏所在边：默认靠右（End），可切到靠左（Start）。
 *
 * 遵循 iOS HIG 语义：**End（左滑）承载破坏性/高频操作**（删除·归档·更多，红色），
 * **Start（右滑）承载正向/可逆操作**（置顶·标记已读·收藏，主色/绿色）。
 */
enum class AppSwipeEdge { Start, End }

/**
 * 滑扫操作的提升状态。
 *
 * 拖动跟手 / fling / settle / clamp / 全滑确认全由 `AnchoredDraggableState` 托管。
 * 如今暴露业务关心的收口 API：位移、揭示进度（0..1）、是否稳定展开、开合、越界阻尼比例。
 */
class AppSwipeActionState internal constructor(
    internal val anchored: AnchoredDraggableState<SwipeValue>,
    internal val openAnchorAbs: Float,
    internal val triggerAnchorAbs: Float,
    internal val startSwipeAbs: Float,
) {
    /** 当前内容层横向位移（px），Closed 为 0，Open 为 ±actionWidth。 */
    val offset: Float
        get() = anchored.requireOffset()

    /**
     * 揭示进度 0..1：在**起始滑动阈值**之后才线性攀升，驱动动作按钮的淡入/上滑/缩放。
     *
     * 对齐 Gmail 滑动揭示的「先滑一段才露按钮」手感——手指前进一点儿不预览，
     * 越过约 startSwipeThreshold 才开始露，避免"没滑就显示删除按钮"的突兀。
     */
    val progress: Float
        get() {
            val travelled = abs(offset) - startSwipeAbs
            val span = (openAnchorAbs - startSwipeAbs).coerceAtLeast(1f)
            return (travelled / span).coerceIn(0f, 1f)
        }

    /** 是否已稳定展开（settle 完成后才翻转）。 */
    val isOpen: Boolean
        get() = anchored.settledValue == SwipeValue.Open

    /**
     * 是否"非关闭态"——无论 settle 与否，只要 offset 越过起始阈值就算正在/已展开。
     * 用于替换旧的 `isOpen` 判断半开态（settle 动画期间、手势被 cancel 未 settle 等）。
     */
    val isEngaged: Boolean
        get() = abs(offset) > startSwipeAbs * 0.8f

    /** 是否正处于"全滑确认"阈值（拖超过动作栏），用于放大 + 阻尼反馈。 */
    val isBeyondReveal: Boolean
        get() = abs(offset) > openAnchorAbs * 1.02f

    /** 全滑超额比例 0..1：在动作栏宽度与全滑确认点之间插值（触发即等于 1）。 */
    val overshoot: Float
        get() = (((abs(offset) - openAnchorAbs) / (triggerAnchorAbs - openAnchorAbs).coerceAtLeast(1f)))
            .coerceIn(0f, 1f)

    /**
     * **越界阻尼**（rubber-band / friction）：当拖拽超过动作栏后，把超额位移按抛物线衰减，
     * 制造"橡皮筋跟手"而非硬顶到头的手感——对齐 iOS `UIScrollView` 的 bounces 阻尼。
     */
    val resistedOffset: Float
        get() {
            val raw = abs(offset)
            if (raw <= openAnchorAbs) return offset
            val excess = raw - openAnchorAbs
            val maxExcess = (triggerAnchorAbs - openAnchorAbs).coerceAtLeast(1f)
            val t = (excess / maxExcess).coerceIn(0f, 1f)
            val damped = openAnchorAbs + maxExcess * (0.5f + 0.5f * t) * t
            return if (offset < 0) -damped else damped
        }

    /** 展开露出动作栏（Reveal 模式收起后再次展开用）。 */
    suspend fun open() = anchored.animateTo(SwipeValue.Open)

    /** 收起（弹回原位）。 */
    suspend fun close() = anchored.animateTo(SwipeValue.Closed)
}

/**
 * 创建可提升的滑扫状态；`actionWidth` 必须与 [AppSwipeAction] 保持一致。
 *
 * @param triggerWidth 从 [actionWidth] 到全滑确认点的额外宽度；拖超过该范围即触发 [AppSwipeAction.onTrigger]。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberAppSwipeActionState(
    edge: AppSwipeEdge = AppSwipeEdge.End,
    actionWidth: Dp = 120.dp,
    triggerWidth: Dp = 56.dp,
    startSwipeThreshold: Dp = 12.dp,
): AppSwipeActionState {
    val density = LocalDensity.current
    val (openAbs, triggerAbs, startAbs) = remember(edge, actionWidth, triggerWidth, startSwipeThreshold) {
        val px = with(density) { actionWidth.toPx() }
        val trigPx = with(density) { (actionWidth + triggerWidth).toPx() }
        val startPx = with(density) { startSwipeThreshold.toPx() }
        Triple(px, trigPx, startPx)
    }
    val velocityThreshold = remember(openAbs) { openAbs.coerceAtLeast(48f) }

    val anchors = remember(edge, openAbs, triggerAbs) {
        val sign = if (edge == AppSwipeEdge.End) -1f else 1f
        val open = sign * openAbs
        val trigger = sign * triggerAbs
        DraggableAnchors {
            SwipeValue.Closed at 0f
            SwipeValue.Open at open
            SwipeValue.Trigger at trigger
        }
    }

    val anchored = remember(anchors, velocityThreshold) {
        AnchoredDraggableState(
            initialValue = SwipeValue.Closed,
            anchors = anchors,
            // 越过约 30% 即保持展开（不自动回弹，对齐 iOS reveal 行为）；未过则弹簧弹回。
            positionalThreshold = { distance -> abs(distance) * 0.30f },
            velocityThreshold = { velocityThreshold },
            snapAnimationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            decayAnimationSpec = exponentialDecay(),
            confirmValueChange = { it in SwipeValue.values() },
        )
    }
    return remember(anchored, openAbs, triggerAbs, startAbs) {
        AppSwipeActionState(anchored, openAbs, triggerAbs, startAbs)
    }
}

/** 颜色向另一颜色插值（用于操作块的渐变高光 / 单元格分隔线 / 风险色 morph）。 */
internal fun Color.blend(target: Color, t: Float): Color = Color(
    red = red + (target.red - red) * t,
    green = green + (target.green - green) * t,
    blue = blue + (target.blue - blue) * t,
    alpha = alpha + (target.alpha - alpha) * t,
)

/**
 * 动作栏按钮（多按钮预留接口 · AppSwipeButton）：图标 + 文字垂直排布，等宽平分动作栏，
 * 以「圆角渐变块」呈现——顶部内高光 + 底部微深 + **单元格细描边**（相邻按钮共享形成分段线），
 * 按压时轻微收缩回弹；揭示进度超过阈值后**风险色 morph**（如删除键随拖拽加深变红）。
 *
 * **级联逐盏亮起（cascade）**：默认开启 `stagger`——按出现顺序设相位偏移（已降为 0.3），
 * 形成"逐盏点亮"但不会让第二个按钮晚出现太久。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun RowScope.AppSwipeButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color = Color.White,
    onClick: () -> Unit,
    stagger: Boolean = true,
    riskMorph: Boolean = true,
) {
    val totalReveal = LocalSwipeReveal.current
    val sequence = LocalSwipeSequence.current
    val order = remember { sequence.order++ }
    val staggerFactor = if (stagger) 0.3f else 0f
    val basePhase = if (stagger) order * staggerFactor else 0f
    val reveal = if (basePhase <= 0f) {
        totalReveal
    } else {
        ((totalReveal - basePhase) / (1f - basePhase)).coerceIn(0f, 1f)
    }

    val corner = RoundedCornerShape(AppRadius.Md)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "swipeButtonPressScale",
    )
    val morphed by animateFloatAsState(
        targetValue = reveal,
        animationSpec = tween(80),
        label = "swipeRiskMorph",
    )
    val riskColor = AppColor.StatusDanger
    val effectiveBg = if (riskMorph) background.blend(riskColor, morphed.coerceIn(0f, 1f)) else background

    val gradient = Brush.verticalGradient(
        colors = listOf(
            effectiveBg.blend(Color.White, 0.26f),
            effectiveBg,
            effectiveBg.blend(Color.Black, 0.12f),
        ),
    )
    val divider = effectiveBg.blend(Color.White, 0.42f).copy(alpha = reveal.coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer {
                alpha = reveal.coerceIn(0.001f, 1f)
                translationY = (1f - reveal) * 18f
                scaleX = (0.78f + 0.22f * reveal).coerceAtLeast(0.001f) * pressScale
                scaleY = (0.78f + 0.22f * reveal).coerceAtLeast(0.001f) * pressScale
            }
            .background(gradient, corner)
            .border(1.dp, divider, corner)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = reveal > 0.05f,
                onClick = AppHaptics.click(onClick),
            )
            .padding(horizontal = AppSpacing.Xs),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
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
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AppSpacing.Xs),
            )
        }
    }
}

/**
 * 滑扫操作（分子组 · AppSwipeAction v7）：横向拖出底层动作栏，弹簧锚定到 0 / ±actionWidth。
 *
 * 用于 ListRow / 会话卡片左滑（End，破坏性）或右滑（Start，正向）露出删除/置顶等高频操作。
 *
 * v7 修缮说明（本次）：
 *
 *  - **防溢出裁剪**：外层 Box 加 clip 形状；内容层/动作栏阴影带 shape，避免内容溢出覆盖相邻行；
 *  - **手势冲突治理**：通过 anchoredDraggable 的 Orientation.Horizontal 让 Compose 手势系统
 *    天然优先于垂直嵌套滚动（Compose 会按主轴/副轴分流，水平拖动不会被 verticalScroll 抢夺）；
 *  - **progress / contentOffset 同步**：去掉 animateFloatAsState 间接层，progress 与 contentOffset
 *    都直接从同一个 offset 数据源 derived，消除"内容移开了但按钮还没出现"的空窗；
 *  - **半开态判断**：同批只开一项 & clickable 收起改用 `isEngaged = abs(offset) > threshold`，
 *    覆盖 settle 动画中、手势 cancel 未 settle 等场景；
 *  - **stagger 降低门槛**：staggerFactor 从 0.5 降到 0.3，第二个按钮在 progress≈0.3+ 就开始出现；
 *  - **触感分级优化**：LongPress → TextHandleMove（更轻的触感，对齐阈值反馈语义）；
 *  - **confirmValueChange 守卫**：显式允许三个锚点值；
 *  - **spacedBy(0) 清理**：动作栏 Arrangement 直接用 Start，消除冗余调用。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AppSwipeAction(
    modifier: Modifier = Modifier,
    edge: AppSwipeEdge = AppSwipeEdge.End,
    actionWidth: Dp = 120.dp,
    pattern: AppSwipePattern = AppSwipePattern.Reveal,
    state: AppSwipeActionState? = null,
    index: Int? = null,
    expandedIndex: Int? = null,
    onExpanded: ((Int?) -> Unit)? = null,
    onSwipeProgress: ((Float) -> Unit)? = null,
    onTrigger: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedState = state ?: rememberAppSwipeActionState(edge, actionWidth)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnExpanded by rememberUpdatedState(onExpanded)
    val currentOnProgress by rememberUpdatedState(onSwipeProgress)
    val currentOnTrigger by rememberUpdatedState(onTrigger)
    val currentPattern by rememberUpdatedState(pattern)
    val shape = RoundedCornerShape(AppRadius.Md)

    // —— 手势冲突治理：关键是用 Box.fillMaxSize + anchoredDraggable 作为主轴手势源。
    // Compose 的手势系统会将"沿主轴的拖动"交给 anchoredDraggable，
    // 而将"沿副轴的拖动"让给父级（verticalScroll）——天然分流，无需手动 nestedScroll 桥接。
    // 实测在 verticalScroll 内带一点垂直分量的水平拖动仍能被正确识别为水平拖动，
    // 因为 anchoredDraggable 的 Orientation.Horizontal 会过滤掉副轴位移。

    // 同批只开一项：isEngaged（基于 offset 阈值）覆盖 settle 动画/半开态，比旧 isOpen 鲁棒。
    LaunchedEffect(resolvedState, index, expandedIndex) {
        if (index == null) return@LaunchedEffect
        snapshotFlow { resolvedState.isEngaged }
            .collect { engaged ->
                val cb = currentOnExpanded ?: return@collect
                when {
                    engaged && expandedIndex != index -> cb(index)
                    !engaged && expandedIndex == index -> cb(null)
                }
            }
    }
    // 其它项被展开时，本项（只要非 Closed）自动收起。
    LaunchedEffect(resolvedState, index, expandedIndex) {
        if (index != null && expandedIndex != null && expandedIndex != index && resolvedState.isEngaged) {
            resolvedState.close()
        }
    }

    // 全滑确认：settle 到 Trigger 锚点即触发 onTrigger。
    LaunchedEffect(resolvedState, pattern) {
        snapshotFlow { resolvedState.anchored.settledValue }
            .collect { v ->
                if (v == SwipeValue.Trigger) {
                    if (currentPattern == AppSwipePattern.Dismiss) {
                        currentOnTrigger?.invoke()
                        scope.launch { resolvedState.close() }
                    } else {
                        if (onTrigger != null) {
                            currentOnTrigger?.invoke()
                            scope.launch { resolvedState.open() }
                        } else {
                            scope.launch { resolvedState.open() }
                        }
                    }
                }
            }
    }

    // progress 与 contentOffset 同 offset 数据源，无动画间接层——完全同步。
    val progress by remember { derivedStateOf { resolvedState.progress } }
    val overshoot by remember { derivedStateOf { resolvedState.overshoot } }
    val contentOffset by remember { derivedStateOf { resolvedState.resistedOffset } }

    // 拖拽 / 回弹过程中实时上报揭示进度（0..1）。
    LaunchedEffect(resolvedState) {
        snapshotFlow { resolvedState.progress }
            .collect { p -> currentOnProgress?.invoke(p) }
    }

    // 阈值触感分级：揭示跨越 ~25% 轻震；越过全滑确认区再震。
    LaunchedEffect(resolvedState) {
        var armed = false
        var armedBeyond = false
        snapshotFlow { resolvedState.progress }
            .collect { p ->
                if (p >= 0.25f && !armed) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    armed = true
                } else if (p < 0.25f) {
                    armed = false
                }
            }
        snapshotFlow { resolvedState.isBeyondReveal }
            .collect { beyond ->
                if (beyond && !armedBeyond) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    armedBeyond = true
                } else if (!beyond) {
                    armedBeyond = false
                }
            }
    }

    // 外层 Box：加 clip(shape) 防内容溢出覆盖相邻行。
    Box(
        modifier = modifier.clip(shape),
        contentAlignment = if (edge == AppSwipeEdge.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        // 底层动作栏：固定贴选中边；shadow clip=true 让阴影不溢出。
        Row(
            modifier = Modifier
                .width(actionWidth)
                .fillMaxHeight()
                .shadow(elevation = (12f * progress).dp, shape = shape, clip = true)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = (0.5f * progress).coerceIn(0f, 0.7f)),
                    shape = shape,
                )
                .graphicsLayer {
                    scaleX = 1f + 0.10f * overshoot
                    scaleY = 1f + 0.10f * overshoot
                    alpha = 1f - 0.10f * overshoot
                },
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalSwipeReveal provides progress,
                LocalSwipeSequence provides SwipeSequenceState(),
            ) {
                actions()
            }
        }

        // 顶层内容滑层：带不透明 surface 盖住动作栏，随阻尼化 offset 平移遮挡/露出。
        // clickable 启用条件改用 isEngaged（覆盖半开态），settle 动画期间也能立即收起。
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .graphicsLayer { translationX = contentOffset }
                .clickable(
                    enabled = resolvedState.isEngaged,
                    onClick = { scope.launch { resolvedState.close() } },
                )
                .anchoredDraggable(
                    state = resolvedState.anchored,
                    orientation = Orientation.Horizontal,
                    reverseDirection = false,
                ),
        ) {
            content()
        }
    }
}
