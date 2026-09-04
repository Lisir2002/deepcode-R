package com.core.deepcode.newui.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.component.atom.AppCard
import com.core.deepcode.newui.designsystem.component.atom.AppChip
import com.core.deepcode.newui.designsystem.component.atom.AppIcon
import com.core.deepcode.newui.designsystem.component.atom.IconContainer
import com.core.deepcode.newui.designsystem.layout.AppEmptyState
import com.core.deepcode.newui.designsystem.layout.AppErrorState
import com.core.deepcode.newui.designsystem.layout.AppLoadingState
import com.core.deepcode.newui.designsystem.layout.AppTopBar
import com.core.deepcode.newui.designsystem.layout.pageContentPadding
import com.core.deepcode.newui.designsystem.layout.pageMaxWidth
import com.core.deepcode.newui.designsystem.slot.SlotSet
import com.core.deepcode.newui.designsystem.theme.AppTheme
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppElevation
import com.core.deepcode.newui.designsystem.token.generated.AppLayout
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSizing
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 样板页（§7 S0）：在一个页面内陈列令牌 / 原子组件 / 布局 / 三态 / 槽位，
 * 供负责人检验并敲定"基本完整落地"。附 android @Preview 可独立预览。
 */

@Composable
fun DesignGallery() {
    AppTheme {
        SlotSet(
            topBar = {
                AppTopBar(
                    title = "Design Gallery",
                    navigationIcon = null,
                )
            },
            content = {
                GalleryBody()
            },
        )
    }
}

@Composable
private fun GalleryBody() {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .verticalScroll(scroll)
            .pageMaxWidth()
            .pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Lg),
    ) {
        Section("令牌 · 色板") {
            ColorRow(
                listOf(
                    "BrandPrimary" to AppColor.BrandPrimary,
                    "BrandSurface" to AppColor.BrandSurface,
                    "BrandAccent" to AppColor.BrandAccent,
                    "StatusSuccess" to AppColor.StatusSuccess,
                    "StatusDanger" to AppColor.StatusDanger,
                ),
            )
        }

        Section("令牌 · 度量/圆角/阴影") {
            Text("Spacing: xs=${AppSpacing.Xs} sm=${AppSpacing.Sm} md=${AppSpacing.Md} lg=${AppSpacing.Lg}")
            Text("Radius: sm=${AppRadius.Sm} md=${AppRadius.Md} lg=${AppRadius.Lg} pill=${AppRadius.Pill}")
            Text("Elevation: z0=${AppElevation.Z0} z1=${AppElevation.Z1} z2=${AppElevation.Z2} z4=${AppElevation.Z4}")
            Text("Sizing: touch=${AppSizing.TouchTarget} iconBlock=${AppSizing.IconBlock}")
            Text("Layout: pageH=${AppLayout.PageHorizontal} maxWidth=${AppLayout.ContentMaxWidth}")
        }

        Section("原子组件") {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(AppSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                    IconContainer(icon = Icons.Rounded.Code, tint = Color.White)
                    Text(
                        text = "AppCard · IconContainer",
                        modifier = Modifier.padding(start = AppSpacing.Lg),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppChip(text = "选中", icon = Icons.Rounded.Home)
                AppChip(text = "未选中", selected = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                AppIcon(icon = Icons.Rounded.Settings)
                AppIcon(icon = Icons.Rounded.Palette, size = AppSizing.IconL)
                AppIcon(icon = Icons.Rounded.Code, size = AppSizing.IconXs)
            }
        }

        Section("三态") {
            Box(Modifier.fillMaxWidth().size(96.dp), contentAlignment = Alignment.Center) {
                AppLoadingState()
            }
            AppEmptyState(
                icon = Icons.Rounded.Home,
                title = "暂无内容",
                description = "这是空态示例，展示空状态占位。",
            )
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppErrorState(message = "网络异常，请重试。")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ColorRow(list: List<Pair<String, Color>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        list.forEach { (name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(color, shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.Md)),
                )
                Text(text = name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}