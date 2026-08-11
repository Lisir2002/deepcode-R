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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search
import kotlin.math.roundToInt

object CyberColors {
    val Cyan = Color(0xFF00FFCC)
    val Blue = Color(0xFF0066FF)
    val Black = Color(0xFF000000)
    val DarkBg = Color(0xFF050810)
    val CardBg = Color(0xFF0A1020)
    val LineBlue = Color(0xFF0099FF)
    val CyanDim = Color(0xFF009999)
}

@Composable
internal fun CyberCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderBrush = Brush.horizontalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .clip(shape)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            CyberColors.Cyan.copy(alpha = 0.08f),
                            CyberColors.Cyan.copy(alpha = 0.02f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    border = BorderStroke(1.2.dp, borderBrush),
                    shape = shape
                )
                .clip(shape),
            color = CyberColors.CardBg,
            shape = shape,
            shadowElevation = 4.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun CyberSectionHeader(
    text: String
) {
    val verticalGradient = Brush.verticalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.Blue)
    )
    val horizontalGradient = Brush.horizontalGradient(
        colors = listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.lg,
                top = Spacing.lg,
                bottom = Spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(brush = verticalGradient)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                brush = horizontalGradient
            )
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
    trailing: @Composable () -> Unit = {
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = CyberColors.Cyan
        )
    }
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color = CyberColors.Cyan.copy(alpha = 0.08f))
                    .border(
                        border = BorderStroke(1.dp, CyberColors.Cyan.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CyberColors.Cyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            trailing()
        }

        if (showDivider) {
            HorizontalDivider(
                color = CyberColors.Cyan.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = 72.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CyberSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
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
            Icon(
                imageVector = FeatherIcons.Search,
                contentDescription = null,
                tint = CyberColors.Cyan
            )
        },
        placeholder = {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CyberColors.CardBg,
            unfocusedContainerColor = CyberColors.CardBg,
            focusedBorderColor = CyberColors.Cyan,
            unfocusedBorderColor = CyberColors.LineBlue.copy(alpha = 0.8f),
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = CyberColors.Cyan
        ),
        borderThickness = 1.2.dp
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
