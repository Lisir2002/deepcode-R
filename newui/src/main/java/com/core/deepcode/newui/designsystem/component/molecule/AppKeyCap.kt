package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor

/**
 * 键盘键帽（分子组 · AppKeyCap / AppKeyCombo）：拟物键帽（底部暗边 + 轻投影），
 * 用于展示快捷键组合（如 ⌘K / Ctrl+⇧P）或短命令标签。
 */
@Composable
fun AppKeyCap(
    label: String,
    modifier: Modifier = Modifier,
    accentColor: Color = AppColor.BrandPrimary,
) {
    Box(
        modifier = modifier
            .shadow(1.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 底部暗边，模拟键帽下沉
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                .background(accentColor.copy(alpha = 0.55f)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 快捷键组合（分子组 · AppKeyCombo）：多个键帽以连接号串联。
 */
@Composable
fun AppKeyCombo(
    keys: List<String>,
    modifier: Modifier = Modifier,
    accentColor: Color = AppColor.BrandPrimary,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        keys.forEachIndexed { index, key ->
            if (index > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "+",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            AppKeyCap(label = key, accentColor = accentColor)
        }
    }
}