package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 步骤进度条（分子组 · AppProgressSteps）：向导 / 安装 / 分步表单的进度提示，
 * 已完成节点对勾回弹、当前节点脉冲强调、连线从左向右填充。
 */
@Composable
fun AppProgressSteps(
    steps: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = AppColor.BrandPrimary,
) {
    val safeCurrent = currentIndex.coerceIn(0, steps.size)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        steps.forEachIndexed { index, label ->
            val done = index < safeCurrent
            val active = index == safeCurrent
            // 步骤节点 + 标签
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StepNode(
                        index = index,
                        done = done,
                        active = active,
                        activeColor = activeColor,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = AppSpacing.Sm),
                    )
                }
            }
            // 连线
            if (index < steps.lastIndex) {
                StepConnector(done = done, activeColor = activeColor)
            }
        }
    }
}

@Composable
private fun StepNode(
    index: Int,
    done: Boolean,
    active: Boolean,
    activeColor: Color,
) {
    val bg by animateColorAsState(
        targetValue = if (done || active) activeColor else MaterialTheme.colorScheme.surface,
        label = "stepBg",
    )
    val ringColor by animateColorAsState(
        targetValue = if (active) activeColor.copy(alpha = 0.25f) else Color.Transparent,
        label = "stepRing",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "stepCheck",
    )
    val nodeScale by animateFloatAsState(
        targetValue = if (active) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "stepNodeScale",
    )
    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        // 当前节点外圈光环
        Box(
            Modifier
                .size(34.dp)
                .graphicsLayer { alpha = ringColor.alpha }
                .clip(CircleShape)
                .background(ringColor),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer {
                    scaleX = nodeScale
                    scaleY = nodeScale
                }
                .clip(CircleShape)
                .background(bg)
                .border(if (done || active) 0.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp).graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    },
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.StepConnector(
    done: Boolean,
    activeColor: Color,
) {
    val fillFrac by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        label = "stepConnector",
    )
    Box(
        modifier = Modifier
            .weight(0.62f)
            .padding(top = 14.dp)
            .height(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
            Modifier
                .fillMaxWidth(if (fillFrac > 0f) fillFrac else 0.001f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(activeColor),
        )
    }
}