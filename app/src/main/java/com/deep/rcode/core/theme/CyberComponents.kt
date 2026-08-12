package com.deep.rcode.core.theme

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search
import compose.icons.feathericons.XCircle
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
}

@Composable
internal fun CyberCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CyberColors.CardBg,
        shape = shape,
        shadowElevation = 0.5.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(0.8.dp, CyberColors.CardStroke)
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
            color = CyberColors.HeaderText
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
    trailing: @Composable () -> Unit = {
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = CyberColors.CyanDim
        )
    }
) {
    val highlightColor = Color(0x330984E3) // 浅蓝高亮
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
                    .background(color = CyberColors.IconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF344054),
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
                    color = Color(0xFF101828)
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = highlightedSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF475467)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            trailing()
        }

        if (showDivider) {
            HorizontalDivider(
                color = CyberColors.Divider,
                thickness = 0.7.dp,
                modifier = Modifier.padding(start = 68.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CyberSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    resultCount: Int? = null,
    onClear: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FeatherIcons.Search,
                    contentDescription = null,
                    tint = Color(0xFF667085)
                )
                if (resultCount != null && query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$resultCount",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFF98A2B3)
                    )
                }
            }
        },
        trailingIcon = {
            if (query.isNotEmpty() && onClear != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = XCircle,
                        contentDescription = null,
                        tint = Color(0xFF667085),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        placeholder = {
            Text(
                text = placeholder,
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Color(0xFFD0D5DD),
            unfocusedBorderColor = Color(0xFFE4E7EC),
            focusedTextColor = Color(0xFF101828),
            unfocusedTextColor = Color(0xFF101828),
            cursorColor = Color(0xFF101828),
            focusedPlaceholderColor = Color(0xFF98A2B3),
            unfocusedPlaceholderColor = Color(0xFF98A2B3)
        )
    )
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
