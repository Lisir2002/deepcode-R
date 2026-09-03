package com.core.deepcode.core.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import kotlin.math.roundToInt

object CyberColors {
    val Cyan = Color(0xFF00B894)
    val Blue = Color(0xFF0984E3)
    val Black = Color(0xFF000000)
    val DarkBg = Color(0xFF050810)
    val CardBg = Color(0xFFFFFFFF)
    val LineBlue = Color(0xFF0984E3)
    val CyanDim = Color(0xFF636E72)
    val CardStroke = Color(0xFFE4E7EC)
    val IconBg = Color(0xFFF2F3F5)
    val Divider = Color(0xFFEEF0F2)
    val HeaderText = Color(0xFF1D2939)

    // ===== 深色模式配套色（跟随 LocalAppDarkMode，见 cyberColor） =====
    val DarkCardBg = Color(0xFF0D1B2E)
    val DarkCardStroke = Color(0xFF223B57)
    val DarkHeaderText = Color(0xFFEAF2FF)
    val DarkCyanDim = Color(0xFFB8C7DA)
    val DarkIconBg = Color(0xFF13273F)
    val DarkDivider = Color(0xFF223B57)
    val DarkTitle = Color(0xFFEAF2FF)
    val DarkSubtitle = Color(0xFFB8C7DA)
}

/** 赛博组件语义色：按当前日夜模式取浅色/深色值。 */
@Composable
internal fun cyberColor(light: Color, dark: Color): Color =
    if (LocalAppDarkMode.current) dark else light

@Composable
internal fun CyberCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cyberColor(CyberColors.CardBg, CyberColors.DarkCardBg),
        shape = shape,
        shadowElevation = 0.5.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(0.8.dp, cyberColor(CyberColors.CardStroke, CyberColors.DarkCardStroke))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun CyberSectionHeader(
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.lg + 4.dp,
                end = Spacing.lg,
                top = Spacing.md + 4.dp,
                bottom = Spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = TextStyle.Default.letterSpacing
            ),
            color = cyberColor(CyberColors.HeaderText, CyberColors.DarkHeaderText)
        )
    }
}

@Composable
internal fun CyberMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    highlightQuery: String = "",
    trailing: (@Composable () -> Unit)? = null,
    iconBg: Color? = null
) {
    val highlightColor = Color(0x330984E3) // 浅蓝高亮
    // iconBg 非空时走「彩色图标块」：背景用该色，图标本体固定白色（Material You 设置页风格）；
    // 否则保持原灰色图标块 + 灰色图标（兼容 About 等复用方）。
    val effectiveIconBg = iconBg ?: cyberColor(CyberColors.IconBg, CyberColors.DarkIconBg)
    val effectiveIconTint = if (iconBg != null) Color.White else cyberColor(Color(0xFF344054), CyberColors.DarkCyanDim)
    val titleColor = cyberColor(Color(0xFF101828), CyberColors.DarkTitle)
    val subtitleColor = cyberColor(Color(0xFF475467), CyberColors.DarkSubtitle)
    val dividerColor = cyberColor(CyberColors.Divider, CyberColors.DarkDivider)
    val effectiveTrailing = trailing ?: {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = cyberColor(CyberColors.CyanDim, CyberColors.DarkCyanDim)
        )
    }
    val bodyLargeStyle = MaterialTheme.typography.bodyLarge
    val bodyMediumStyle = MaterialTheme.typography.bodyMedium

    val highlightedTitle = remember(title, highlightQuery, bodyLargeStyle) {
        if (highlightQuery.isBlank()) {
            AnnotatedString(title)
        } else {
            buildAnnotatedString {
                append(title)
                val lower = title.lowercase()
                val tokens = highlightQuery.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                for (token in tokens) {
                    val lowerToken = token.lowercase()
                    var startIndex = lower.indexOf(lowerToken)
                    while (startIndex >= 0) {
                        addStyle(
                            bodyLargeStyle.toSpanStyle().copy(
                                background = highlightColor,
                                fontWeight = FontWeight.SemiBold
                            ),
                            startIndex,
                            startIndex + lowerToken.length
                        )
                        startIndex = lower.indexOf(lowerToken, startIndex + 1)
                    }
                }
            }
        }
    }

    val highlightedSubtitle = remember(subtitle, highlightQuery, bodyMediumStyle) {
        if (highlightQuery.isBlank() || subtitle.isEmpty()) {
            AnnotatedString(subtitle)
        } else {
            buildAnnotatedString {
                append(subtitle)
                val lower = subtitle.lowercase()
                val tokens = highlightQuery.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                for (token in tokens) {
                    val lowerToken = token.lowercase()
                    var startIndex = lower.indexOf(lowerToken)
                    while (startIndex >= 0) {
                        addStyle(
                            bodyMediumStyle.toSpanStyle().copy(
                                background = highlightColor,
                                fontWeight = FontWeight.Medium
                            ),
                            startIndex,
                            startIndex + lowerToken.length
                        )
                        startIndex = lower.indexOf(lowerToken, startIndex + 1)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color = effectiveIconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = effectiveIconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightedTitle,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = titleColor
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = highlightedSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            effectiveTrailing()
        }

        if (showDivider) {
            HorizontalDivider(
                color = dividerColor,
                thickness = 0.7.dp,
                modifier = Modifier.padding(start = 68.dp)
            )
        }
    }
}

@Composable
internal fun CyberSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    resultCount: Int? = null,
    onClear: (() -> Unit)? = null
) {
    // 紧凑单行搜索栏：44dp 高度、圆角、浅灰填充，占位文案单行省略，避免占用两行空间。
    val hasText = query.isNotEmpty()
    val bgColor = cyberColor(if (hasText) Color.White else Color(0xFFF2F3F5), if (hasText) Color(0xFF0D1B2E) else Color(0xFF13273F))
    val strokeColor = cyberColor(Color(0xFFE4E7EC), CyberColors.DarkCardStroke)
    val hintColor = cyberColor(Color(0xFF98A2B3), CyberColors.DarkSubtitle)
    val textColor = cyberColor(Color(0xFF101828), CyberColors.DarkTitle)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, strokeColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = hintColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (!hasText) {
                    Text(
                        text = placeholder,
                        color = hintColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                    cursorBrush = SolidColor(textColor),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (resultCount != null && hasText) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$resultCount",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = hintColor
                )
            }
            if (hasText && onClear != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        text = "✕",
                        color = hintColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun CyberStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    accentBrush: Brush?
) {
    val defaultBrush = Brush.horizontalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )

    CyberCard(
        modifier = Modifier
            .width(200.dp)
            .height(120.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (accentBrush == null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CyberColors.Cyan
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CyberColors.Cyan
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    brush = defaultBrush
                )
            )
        }
    }
}

@Composable
internal fun CyberChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.Blue)
    )

    val bgModifier = if (selected) {
        Modifier.background(brush = gradientBrush, shape = shape)
    } else {
        Modifier
            .background(color = Color.Transparent, shape = shape)
            .border(
                border = BorderStroke(1.dp, CyberColors.Cyan),
                shape = shape
            )
    }

    val textColor = if (selected) CyberColors.Black else CyberColors.Cyan

    Box(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .then(bgModifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = textColor
        )
    }
}

@Composable
internal fun CyberProgressIndicator(
    progress: Float = 0f,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )

    val progressPercent = (progress * 100).roundToInt()
    val isIndeterminate = progress <= 0f

    val infiniteTransition = rememberInfiniteTransition(label = "cyber_progress")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offsetX"
    )

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CyberColors.CardBg)
        ) {
            if (isIndeterminate) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val stripeWidth = size.width * 0.3f
                            val startX = -stripeWidth + (size.width + stripeWidth) * offsetX

                            drawRect(
                                brush = gradientBrush,
                                topLeft = Offset(startX, 0f),
                                size = Size(stripeWidth, size.height)
                            )
                        }
                )
            } else {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "progress_anim"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .background(brush = gradientBrush)
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = if (isIndeterminate) "..." else "$progressPercent%",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.End)
        )
    }
}

internal fun gradientTextStyle(
    brush: Brush = Brush.horizontalGradient(
        listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )
): TextStyle = TextStyle(brush = brush)
