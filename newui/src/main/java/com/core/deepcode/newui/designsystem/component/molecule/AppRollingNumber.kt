package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlin.math.roundToInt

/**
 * 数字滚动（分子组 · AppRollingNumber）：目标值每次变化时，数字从旧值向新值快速滚动
 * 并对齐到最终值，KPI / 令牌 / 速率类统计卡片的数据跳动，替代死板的静态数字。
 *
 * @param animateFrom 入场起始值（组件首次组合时从此处滚向 [value]）。
 * @param format 渲染格式，默认取整；可传千分位 / 货币等本地化格式器。
 */
@Composable
fun AppRollingNumber(
    value: Number,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    durationMillis: Int = 700,
    animateFrom: Float = 0f,
    format: (Float) -> String = { it.roundToInt().toString() },
) {
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        label = "rollingNumber",
    )
    Text(
        text = format(animated),
        modifier = modifier,
        style = style,
        color = color,
    )
}