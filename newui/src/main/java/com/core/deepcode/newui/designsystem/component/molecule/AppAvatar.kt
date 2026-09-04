package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 首字母头像（分子组 · AppAvatar）：品牌渐变圆 + 首字母，可选右下"在线/离线"状态环。
 * 用于用户/仓库/人物入口，比原生圆形图标更精致。
 */
@Composable
fun AppAvatar(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = AppSizing.IconBlock,
    online: Boolean? = null,
) {
    Box(modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(AppColor.BrandPrimary, AppColor.BrandAccent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text.take(2).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        if (online != null) {
            val ring = 2.dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface), // 描边环
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(8.dp - ring)
                        .clip(CircleShape)
                        .background(
                            if (online) AppColor.StatusSuccess else MaterialTheme.colorScheme.outline,
                        ),
                )
            }
        }
    }
}