package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppMotion

/**
 * 滑动指示器标签栏（分子组 · AppTabs）：品牌色下划线指示块随选择滑动
 * （animateDpAsState），承载顶栏/底栏等子级槽位的自适应 Tab 导航。
 */
@Composable
fun AppTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    indicatorColor: Color = AppColor.BrandPrimary,
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.size - 1)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / tabs.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * safeIndex.toFloat(),
            animationSpec = tween(durationMillis = AppMotion.Med.toInt()),
            label = "tabIndicator",
        )
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == safeIndex
                    val textColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "tabText",
                    )
                    Box(
                        modifier = Modifier
                            .width(tabWidth)
                            .height(40.dp)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                Box(
                    Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .height(3.dp)
                        .background(indicatorColor),
                )
            }
        }
    }
}