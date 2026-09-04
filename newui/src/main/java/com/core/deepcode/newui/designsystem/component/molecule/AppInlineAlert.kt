package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 内联提示色调（分子组 · AppInlineAlert 的 tone）。 */
enum class AppAlertTone { Info, Success, Warning, Danger }

/** 内联提示条（分子组 · AppInlineAlert）：四色调低饱和底色 + 品牌图标 + 可选关闭，滑入淡出。 */
@Composable
fun AppInlineAlert(
    message: String,
    modifier: Modifier = Modifier,
    tone: AppAlertTone = AppAlertTone.Info,
    title: String? = null,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(true) }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(AppMotion.Fast.toInt()), initialOffsetY = { -it }) + fadeIn(tween(AppMotion.Fast.toInt())),
    ) {
        val (fg, iconVec) = when (tone) {
            AppAlertTone.Info -> AppColor.StatusInfo to Icons.Rounded.Info
            AppAlertTone.Success -> AppColor.StatusSuccess to Icons.Rounded.CheckCircle
            AppAlertTone.Warning -> AppColor.StatusWarning to Icons.Rounded.Warning
            AppAlertTone.Danger -> AppColor.StatusDanger to Icons.Rounded.ErrorOutline
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.Md))
                .background(fg.copy(alpha = 0.10f))
                .border(1.dp, fg.copy(alpha = 0.28f), RoundedCornerShape(AppRadius.Md))
                .padding(start = AppSpacing.Lg, top = AppSpacing.Md, bottom = AppSpacing.Md, end = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon ?: iconVec,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(AppSizing.IconM),
            )
            Spacer(Modifier.padding(start = AppSpacing.Md))
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDismiss != null) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(AppSpacing.Sm)
                        .clip(RoundedCornerShape(AppRadius.Pill))
                        .clickable {
                            visible = false
                        },
                )
            }
        }
    }
}