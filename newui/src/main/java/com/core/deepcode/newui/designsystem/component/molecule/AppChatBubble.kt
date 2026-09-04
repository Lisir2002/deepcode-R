package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 聊天气泡（分子组 · AppChatBubble）：用户侧品牌渐变胶囊 / AI 侧表面卡片奶白描边，
 * 入场弹簧缩放+淡入，支撑 AI 对话/Agent 审批等高信息密度会话流。
 */
@Composable
fun AppChatBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = AppColor.BrandPrimary,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "chatBubble",
    )
    val radius = if (isUser) AppRadius.Sm else AppRadius.Md
    val shape = RoundedCornerShape(
        topStart = if (isUser) AppRadius.Lg else radius,
        topEnd = if (isUser) radius else AppRadius.Lg,
        bottomStart = AppRadius.Lg,
        bottomEnd = AppRadius.Lg,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val s = 0.92f + 0.08f * progress
                    scaleX = s
                    scaleY = s
                    alpha = 0.5f + 0.5f * progress
                }
                .clip(shape)
                .background(if (isUser) accent else MaterialTheme.colorScheme.surface)
                .then(if (!isUser) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}