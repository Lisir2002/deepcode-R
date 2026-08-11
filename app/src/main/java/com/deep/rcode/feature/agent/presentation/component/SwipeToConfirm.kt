package com.deep.rcode.feature.agent.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * C.4.8 方案 C 最严格安全：滑动确认（SwipeToConfirm）组件。
 *
 * 不变性：
 *  SWIPE-INV-1：进度 progress% ∈ [0, 1]，必须 ≥ 0.92 才回调 [onConfirmed]（硬锁阈值 92%，不可配置）
 *  SWIPE-INV-2：松手后若 < 0.92 → 自动 animate 回弹到 0 （不会停在中间；防止用户半滑造成 UI 卡死）
 *  SWIPE-INV-3：onConfirmed 最多回调一次（通过 remember confirmed 锁防止重复触发）
 *
 * 实现：pointerInput + detectDragGestures + Canvas 画圆角进度条 + 圆形滑块。
 * 不依赖 Swipeable/AnchoredDraggable（防止 Gradle 需加 foundation 影响 CI）。
 *
 * @param enabled true=可滑动；false=整体置灰（tier 1 允许 false，但 Phase 5 默认 true）
 * @param onProgressChange 0~1 实时进度（给 ConfirmationCardStateMachine 发 SWIPE_PROGRESS / SWIPE_THRESHOLD_HIT）
 * @param onConfirmed 进度 ≥ 0.92 且松手时回调一次（STATE-INV-1 必须在这个回调后才能 USER_CLICK_CONFIRM 生效）
 */
@Composable
fun SwipeToConfirm(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "滑动确认以执行（C.4.8 最严格模式）",
    thumbSizeDp: Dp = 56.dp,
    trackHeightDp: Dp = 64.dp,
    cornerRadiusDp: Dp = 28.dp,
    onProgressChange: (Float) -> Unit = {},
    onConfirmed: () -> Unit = {}
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeightDp),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbSizeDp.toPx() }
        val maxDx = (trackWidthPx - thumbPx).coerceAtLeast(0f)

        var offsetX by remember { mutableFloatStateOf(0f) }     // 当前滑快 x（px）
        var isDragging by remember { mutableStateOf(false) }
        var confirmed by remember { mutableStateOf(false) }     // SWIPE-INV-3：只触发一次 onConfirmed

        // 松手回弹（SWIPE-INV-2）：animate 从当前 offset → 0 或 → maxDx（若 ≥92% 则固定 end）
        val animateTarget = when {
            confirmed -> maxDx
            isDragging -> offsetX
            offsetX / (if (maxDx == 0f) 1f else maxDx) >= 0.92f -> maxDx
            else -> 0f
        }
        val animatedOffsetDp: Dp by animateDpAsState(
            targetValue = with(androidx.compose.ui.platform.LocalDensity.current) { animateTarget.toDp() },
            animationSpec = tween(durationMillis = if (confirmed) 120 else 220, delayMillis = 0),
            label = "swipe_back_or_lock"
        )
        val animatedOffsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { animatedOffsetDp.toPx() }

        // 动画结束后，如果达到阈值 → 触发 onConfirmed（幂等锁）
        LaunchedEffect(animatedOffsetPx, confirmed) {
            val progress = if (maxDx == 0f) 0f else (animatedOffsetPx / maxDx).coerceIn(0f, 1f)
            onProgressChange(progress)
            if (progress >= 0.92f && !confirmed && !isDragging) {
                confirmed = true
                onConfirmed()
            }
        }

        // 背景 Surface 圆角槽
        Surface(
            modifier = Modifier.fillMaxWidth().height(trackHeightDp),
            shape = RoundedCornerShape(cornerRadiusDp),
            color = if (enabled) MaterialTheme.colorScheme.surfaceVariant
                     else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            tonalElevation = 0.dp,
            content = {}
        )

        // Canvas：前景进度条 + 滑块
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .pointerInput(Unit) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        val p = if (maxDx == 0f) 0f else (offsetX / maxDx).coerceIn(0f, 1f)
                        if (p >= 0.92f) {
                            // SWIPE-INV-1 命中
                            offsetX = maxDx
                            if (!confirmed) {
                                confirmed = true
                                onConfirmed()
                            }
                        }
                        // < 0.92：交给 animateDpAsState 回弹
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                ) { _, dragAmount ->
                    if (!enabled || confirmed) return@detectDragGestures
                    val next = (offsetX + dragAmount.x).coerceIn(0f, maxDx)
                    offsetX = next
                    val p = if (maxDx == 0f) 0f else (next / maxDx).coerceIn(0f, 1f)
                    onProgressChange(p)
                    if (p >= 0.92f) {
                        // 滑动过程中已达到阈值 → 状态机启用按钮（点亮）
                        // 松手才 onConfirmed；这里只报告进度
                    }
                }
            }
        ) {
            val trackW = size.width
            val trackH = size.height
            val cornerPx = with(density) { cornerRadiusDp.toPx() }
            val thumb = with(density) { thumbSizeDp.toPx() }
            val drawX = if (isDragging) offsetX else animatedOffsetPx
            val progress = if (maxDx == 0f) 0f else (drawX / maxDx).coerceIn(0f, 1f)

            // 1) 进度填充（从左到当前 thumb 右端）
            val fillW = (drawX + thumb).coerceIn(0f, trackW)
            val progressColor = when {
                !enabled -> Color(0xFFCCCCCC)
                progress >= 0.92f -> Color(0xFF2E7D32)  // 深绿：已达阈值
                progress >= 0.6f -> Color(0xFF558B2F)   // 草绿
                progress >= 0.3f -> Color(0xFF827717)   // 橄榄
                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            }
            drawRoundRect(
                color = progressColor,
                topLeft = Offset.Zero,
                size = Size(fillW, trackH),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                alpha = if (progress >= 0.92f) 1f else 0.95f
            )
            // 2) 未填充部分边框描边
            drawRoundRect(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = 1.2f)
            )
            // 3) 圆角滑块 thumb（圆形；进度≥92 画 checkmark）
            val thumbColor = if (enabled) Color.White else Color(0xFFEEEEEE)
            val thumbTopLeft = Offset(
                x = drawX.coerceIn(0f, trackW - thumb),
                y = (trackH - thumb) / 2f
            )
            drawCircle(
                color = thumbColor,
                radius = thumb / 2f,
                center = Offset(thumbTopLeft.x + thumb / 2f, thumbTopLeft.y + thumb / 2f),
                alpha = 1f
            )
            drawCircle(
                color = progressColor.copy(alpha = 0.15f),
                radius = thumb / 2f - 2f,
                center = Offset(thumbTopLeft.x + thumb / 2f, thumbTopLeft.y + thumb / 2f)
            )
            // 4) checkmark：只有 ≥92% 才显示
            if (progress >= 0.92f) {
                drawCircle(
                    color = Color(0xFF2E7D32),
                    radius = thumb / 2f - 8f,
                    center = Offset(thumbTopLeft.x + thumb / 2f, thumbTopLeft.y + thumb / 2f)
                )
            }
        }

        // 文字标签（始终显示；右半部分被滑快遮罩不影响，因为透明度低）
        Text(
            text = if (confirmed) "✓ 已滑动确认（SwipeVerified）" else label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}
