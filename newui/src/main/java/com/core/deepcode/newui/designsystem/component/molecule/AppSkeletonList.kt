package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppMotion
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import kotlinx.coroutines.delay

/**
 * 骨架列表（分子组 · AppSkeletonList）：行级 [AppShimmerBox] 拼接头像+多行，配合逐行
 * stagger（50ms 递增）入场淡入——对齐 Linear 的"列表项带节奏地浮现"而非全屏灰块。
 */
@Composable
fun AppSkeletonList(
    rows: Int = 4,
    modifier: Modifier = Modifier,
    staggerMs: Long = 50L,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        repeat(rows) { rowIndex ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(rowIndex) {
                delay(staggerMs * rowIndex)
                visible = true
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(AppMotion.Med.toInt())) + expandVertically(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppShimmerBox(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                        AppShimmerBox(Modifier.fillMaxWidth(0.55f).height(14.dp))
                        AppShimmerBox(Modifier.fillMaxWidth(0.85f).height(12.dp))
                    }
                }
            }
        }
    }
}