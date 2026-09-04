package com.core.deepcode.newui.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius

/**
 * :newui 独立 M3 主题（不碰旧版 theme）。
 *
 * S0 最小自证：亮/暗两套 ColorScheme 由 DTCG 令牌（AppColor）映射到 M3 primary/surface/…，
 * 形状由 AppRadius 组装；Typography 暂用默认，待 §3.5 排版族补全。
 */
private val AppLightScheme = lightColorScheme(
    primary = AppColor.BrandPrimary,
    onPrimary = Color.White,
    background = AppColor.BrandSurface,
    onBackground = AppColor.BrandInk,
    surface = AppColor.BrandSurface,
    onSurface = AppColor.BrandInk,
    surfaceVariant = AppColor.BrandSurfaceDim,
    onSurfaceVariant = AppColor.BrandInk.copy(alpha = 0.62f),
    secondary = AppColor.BrandAccent,
    onSecondary = Color.White,
    error = AppColor.StatusDanger,
    onError = Color.White,
    outline = AppColor.BrandSurfaceDim,
)

private val AppDarkScheme = darkColorScheme(
    primary = AppColor.OnDarkPrimary,
    onPrimary = Color(0xFF1A1C20),
    background = AppColor.OnDarkSurface,
    onBackground = AppColor.OnDarkInk,
    surface = AppColor.OnDarkSurface,
    onSurface = AppColor.OnDarkInk,
    surfaceVariant = AppColor.BrandSurfaceDim.copy(alpha = 0.15f),
    onSurfaceVariant = AppColor.OnDarkInk.copy(alpha = 0.68f),
    secondary = AppColor.BrandAccent,
    onSecondary = Color(0xFF16181C),
    error = AppColor.StatusDanger,
    onError = Color.White,
)

/** 圆角令牌 → M3 Shapes（§3.10 对齐 M3 形状尺度） */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.Sm),
    small = RoundedCornerShape(AppRadius.Sm),
    medium = RoundedCornerShape(AppRadius.Md),
    large = RoundedCornerShape(AppRadius.Lg),
    extraLarge = RoundedCornerShape(AppRadius.Lg),
)

/** 独立 T 排版：暂用 M3 默认（§3.5 排版族落地后替换为 AppType） */
val AppTypography: Typography = Typography()

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AppDarkScheme else AppLightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}