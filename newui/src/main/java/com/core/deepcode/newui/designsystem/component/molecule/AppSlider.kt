package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * 带数值气泡的滑杆（分子组 · AppSlider）：拖动手感跟手（M3 Slider），顶部数值气泡随
 * 拇指横向平移（frac 映射），把关 Loadouts / 阈值 / 字体大小等连续量调节的精确反馈。
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0,
    labelFormat: (Float) -> String = { it.toInt().toString() },
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        // 气泡轨道（在 thumb 正上方浮动）
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(30.dp),
        ) {
            val trackWidth = maxWidth
            val bubbleW = 42.dp
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (trackWidth - bubbleW) * (frac - 0.5f))
                    .size(width = bubbleW, height = 26.dp)
                    .clip(RoundedCornerShape(AppRadius.Sm)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.inverseSurface),
                )
                Text(
                    text = labelFormat(value),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}