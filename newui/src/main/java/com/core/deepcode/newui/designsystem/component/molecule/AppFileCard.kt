package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/** 文件状态（分子组 · AppFileCard 的 state）。 */
enum class AppFileState { Ready, Uploading, Downloaded, Error }

/**
 * 文件卡片（分子组 · AppFileCard）：状态着色图标 + 上传动态进度条 + 终态图标，
 * 用于文件传输 / 附件列表 / 资源上传等场景。
 */
@Composable
fun AppFileCard(
    fileName: String,
    fileSize: String,
    modifier: Modifier = Modifier,
    state: AppFileState = AppFileState.Ready,
    icon: ImageVector = Icons.Rounded.InsertDriveFile,
    progress: Float? = null,
    accentColor: Color = AppColor.BrandPrimary,
    onClick: (() -> Unit)? = null,
) {
    val stateColor: Color = when (state) {
        AppFileState.Ready -> accentColor
        AppFileState.Uploading -> accentColor
        AppFileState.Downloaded -> AppColor.StatusSuccess
        AppFileState.Error -> AppColor.StatusDanger
    }
    val animatedStateColor by animateColorAsState(
        targetValue = stateColor,
        label = "fileStateColor",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.Md))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadius.Md))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(AppSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconContainer(
            icon = icon,
            tint = Color.White,
            background = animatedStateColor.copy(alpha = 0.90f),
        )
        Spacer(Modifier.padding(start = AppSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (state == AppFileState.Uploading && progress != null) {
                Spacer(Modifier.padding(top = AppSpacing.Sm))
                AppProgressBar(
                    progress = progress,
                    color = animatedStateColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state != AppFileState.Ready) {
                Text(
                    text = fileSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.padding(start = AppSpacing.Sm))
        when (state) {
            AppFileState.Uploading -> {
                Text(
                    text = progress?.let { "${(it * 100).toInt()}%" } ?: "…",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = animatedStateColor,
                )
            }
            AppFileState.Downloaded -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "已完成",
                    tint = AppColor.StatusSuccess,
                    modifier = Modifier.size(20.dp),
                )
            }
            AppFileState.Error -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "失败",
                    tint = AppColor.StatusDanger,
                    modifier = Modifier.size(20.dp),
                )
            }
            AppFileState.Ready -> {
                Text(
                    text = fileSize,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}