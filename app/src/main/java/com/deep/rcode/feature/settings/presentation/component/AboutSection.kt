package com.deep.rcode.feature.settings.presentation.component

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.CyberColors
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.theme.CyberCard
import com.deep.rcode.core.theme.CyberSectionHeader
import com.deep.rcode.core.theme.CyberMenuRow
import com.deep.rcode.feature.settings.presentation.AboutStatsViewModel
import com.deep.rcode.feature.settings.presentation.UsageStats
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Hash
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Send
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Star
import compose.icons.feathericons.Tag
import compose.icons.feathericons.UploadCloud
import compose.icons.feathericons.Users
import compose.icons.feathericons.Calendar

// ============================================================
// Internal theme helpers for About page
// ============================================================

private val primaryGradient: Brush
    @Composable get() = Brush.horizontalGradient(
        listOf(CyberColors.Cyan, CyberColors.LineBlue)
    )

// ============================================================
// Entry point
// ============================================================

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aboutVM: AboutStatsViewModel = hiltViewModel()
    val stats by aboutVM.stats.collectAsStateWithLifecycle()

    val appInfo = remember {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATED")
                info.versionCode.toLong()
            }
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                info.applicationInfo?.minSdkVersion ?: Build.VERSION.SDK_INT
            } else {
                Build.VERSION.SDK_INT
            }
            AppInfo(
                name = info.versionName ?: "unknown",
                code = code,
                packageName = context.packageName,
                minSdk = minSdk
            )
        }.getOrDefault(AppInfo("unknown", 0L, context.packageName, Build.VERSION_CODES.P))
    }

    val appIcon = remember { loadAppIconBitmap(context) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberColors.DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        HeroSection(
            appName = stringResource(R.string.app_name),
            appIcon = appIcon,
            appInfo = appInfo
        )

        UsageStatsSection(stats = stats)

        FaqSection()

        CyberSectionHeader(text = stringResource(R.string.about_share))
        ActionButtonsRow(
            onShare = { shareApp(context) },
            onStar = { openUrl(context, GITHUB_REPO_URL) },
            onFeedback = { openUrl(context, ISSUES_URL) },
            onCommunity = { openUrl(context, COMMUNITY_URL) }
        )

        CyberSectionHeader(text = stringResource(R.string.about_version))
        LinksSection(
            context = context,
            appInfo = appInfo,
            onCheckUpdate = {
                if (updateDialog == null) {
                    updateDialog = UpdateDialogState.Checking
                    scope.launch {
                        updateDialog = checkUpdate(context, appInfo.name)
                    }
                }
            }
        )

        OpenSourceCreditsSection()

        Spacer(Modifier.height(Spacing.lg))
    }

    updateDialog?.let { state ->
        UpdateResultDialog(
            state = state,
            currentVersion = appInfo.name,
            onDismiss = { updateDialog = null },
            onDownloadApk = { releaseInfo ->
                scope.launch {
                    downloadApk(
                        context = context,
                        tag = releaseInfo.latestTag,
                        downloadUrl = releaseInfo.downloadUrl,
                        onProgress = { pct, downloaded, total, speed, path ->
                            updateDialog = UpdateDialogState.Downloading(
                                progressPct = pct,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBytesPerSec = speed,
                                filePath = path
                            )
                        },
                        onComplete = { path ->
                            updateDialog = UpdateDialogState.Downloaded(path)
                        },
                        onError = { msg ->
                            updateDialog = UpdateDialogState.Error(msg)
                        }
                    )
                }
            },
            onOpenReleases = {
                openUrl(context, RELEASES_URL)
                updateDialog = null
            },
            onInstallApk = { path ->
                installApk(context, path)
            }
        )
    }
}

// ============================================================
// 1. Hero Section - Left Icon + Right Details with Neon Top Bar
// ============================================================

@Composable
private fun HeroSection(
    appName: String,
    appIcon: ImageBitmap?,
    appInfo: AppInfo
) {
    // Neon glow decoration brush
    val neonTopBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                CyberColors.Cyan.copy(alpha = 0f),
                CyberColors.Cyan.copy(alpha = 0.8f),
                CyberColors.LineBlue.copy(alpha = 0.8f),
                CyberColors.Cyan.copy(alpha = 0f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
    ) {
        CyberCard {
            Column {
                // Neon glow top bar decoration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(brush = neonTopBrush)
                )

                Spacer(Modifier.height(Spacing.lg))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Big App Icon with double gradient border
                    Box(
                        modifier = Modifier
                            .size(108.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(primaryGradient)
                            .padding(3.5.dp)
                            .clip(RoundedCornerShape(24.5.dp))
                            .background(
                                brush = Brush.radialGradient(
                                    listOf(
                                        CyberColors.Cyan.copy(alpha = 0.22f),
                                        CyberColors.CardBg
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(Spacing.lg))

                    // Right: App Name + Slogan + Info Grid
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // Gradient App Name
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        brush = primaryGradient,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                ) {
                                    append(appName)
                                }
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )

                        // Slogan
                        Text(
                            text = stringResource(R.string.about_slogan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberColors.Cyan.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.height(Spacing.xs))

                        // Version info grid (2 columns)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                HeroInfoChip(
                                    label = stringResource(R.string.about_version),
                                    value = "v${appInfo.name}"
                                )
                                HeroInfoChip(
                                    label = stringResource(R.string.about_sdk_min),
                                    value = "API ${appInfo.minSdk}"
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                HeroInfoChip(
                                    label = stringResource(R.string.about_build_no),
                                    value = "#${appInfo.code}"
                                )
                                HeroInfoChip(
                                    label = stringResource(R.string.about_author_title),
                                    value = stringResource(R.string.about_author),
                                    isAccent = true
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun HeroInfoChip(
    label: String,
    value: String,
    isAccent: Boolean = false
) {
    val valueColor = if (isAccent) CyberColors.Cyan else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(CyberColors.Cyan.copy(alpha = 0.05f))
            .border(
                border = BorderStroke(0.8.dp, CyberColors.Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = Spacing.sm, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

// ============================================================
// 2. Usage Stats - 6 cell Cyber Dashboard Grid (3x2)
// ============================================================

@Composable
private fun UsageStatsSection(stats: UsageStats) {
    CyberSectionHeader(text = stringResource(R.string.about_stats))

    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Row 1: Sessions + Messages + Active Days
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            DashboardStatCell(
                icon = FeatherIcons.MessageSquare,
                label = stringResource(R.string.about_sessions),
                value = stats.totalSessions.toString(),
                unit = "次",
                modifier = Modifier.weight(1f)
            )
            DashboardStatCell(
                icon = FeatherIcons.Hash,
                label = stringResource(R.string.about_messages),
                value = stats.totalMessages.toString(),
                unit = "条",
                modifier = Modifier.weight(1f)
            )
            DashboardStatCell(
                icon = FeatherIcons.Calendar,
                label = stringResource(R.string.about_active_days),
                value = stats.activeDays.toString(),
                unit = "天",
                modifier = Modifier.weight(1f),
                pulseAccent = true
            )
        }

        // Row 2: Input Tokens + Output Tokens + First Used
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            DashboardStatCell(
                icon = FeatherIcons.UploadCloud,
                label = stringResource(R.string.about_input_tokens),
                value = compactNumber(stats.totalInputTokens),
                unit = "Tok",
                modifier = Modifier.weight(1f)
            )
            DashboardStatCell(
                icon = FeatherIcons.DownloadCloud,
                label = stringResource(R.string.about_output_tokens),
                value = compactNumber(stats.totalOutputTokens),
                unit = "Tok",
                modifier = Modifier.weight(1f)
            )
            DashboardStatCell(
                icon = FeatherIcons.Tag,
                label = stringResource(R.string.about_first_used),
                value = if (stats.firstUsedMs > 0L) formatShortDate(stats.firstUsedMs) else "--",
                unit = if (stats.firstUsedMs > 0L) "起" else "",
                modifier = Modifier.weight(1f),
                compactValue = true
            )
        }
    }
}

@Composable
private fun DashboardStatCell(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    pulseAccent: Boolean = false,
    compactValue: Boolean = false
) {
    val gradient = primaryGradient
    val infiniteTransition = rememberInfiniteTransition(label = "stat_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(containerColor = CyberColors.CardBg),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    CyberColors.Cyan.copy(alpha = if (pulseAccent) 0.6f else 0.35f),
                    CyberColors.LineBlue.copy(alpha = if (pulseAccent) 0.6f else 0.35f)
                )
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (pulseAccent) {
                        Modifier.drawBehind {
                            drawRect(
                                brush = Brush.radialGradient(
                                    listOf(
                                        CyberColors.Cyan.copy(alpha = pulseAlpha),
                                        Color.Transparent
                                    )
                                ),
                                topLeft = Offset.Zero,
                                size = size
                            )
                        }
                    } else Modifier
                )
                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                // Icon + Label Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = CyberColors.Cyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(2.dp))

                // Big gradient value
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(brush = gradient, fontWeight = FontWeight.ExtraBold)) {
                            append(value)
                        }
                        if (unit.isNotEmpty()) {
                            withStyle(
                                SpanStyle(
                                    color = CyberColors.CyanDim,
                                    fontWeight = FontWeight.SemiBold
                                )
                            ) {
                                append(" $unit")
                            }
                        }
                    },
                    style = if (compactValue) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    maxLines = 1
                )
            }
        }
    }
}

private fun compactNumber(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fK".format(n / 1_000.0)
    else -> "%,d".format(n)
}

private fun formatShortDate(ms: Long): String {
    val fmt = SimpleDateFormat("yy/MM/dd", Locale.getDefault())
    return fmt.format(Date(ms))
}

// ============================================================
// 3. FAQ Section - Accordion Cyber Cards with Gradient Inner BG
// ============================================================

@Composable
private fun FaqSection() {
    CyberSectionHeader(text = stringResource(R.string.about_faq))

    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        val faqs = listOf(
            R.string.about_faq_q1 to R.string.about_faq_a1,
            R.string.about_faq_q2 to R.string.about_faq_a2,
            R.string.about_faq_q3 to R.string.about_faq_a3,
            R.string.about_faq_q4 to R.string.about_faq_a4
        )
        faqs.forEach { (qRes, aRes) ->
            FaqAccordionItem(
                question = stringResource(qRes),
                answer = stringResource(aRes)
            )
        }
    }
}

@Composable
private fun FaqAccordionItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron_rot"
    )

    CyberCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            // Question row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Q prefix badge
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(CyberColors.Cyan, CyberColors.LineBlue)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Q",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberColors.Black
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = CyberColors.Cyan,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expandable answer area with gradient inner background
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.md,
                            end = Spacing.md,
                            bottom = Spacing.md
                        )
                        .clip(RoundedCornerShape(Radius.md))
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(
                                    CyberColors.Cyan.copy(alpha = 0.10f),
                                    CyberColors.LineBlue.copy(alpha = 0.04f)
                                )
                            )
                        )
                        .border(
                            border = BorderStroke(
                                0.8.dp,
                                CyberColors.Cyan.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(Radius.md)
                        )
                        .padding(Spacing.md)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(CyberColors.Cyan.copy(alpha = 0.15f))
                                .border(
                                    border = BorderStroke(0.8.dp, CyberColors.Cyan.copy(alpha = 0.4f)),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = CyberColors.Cyan
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 4. Action Buttons Row - 4 circular icon buttons
// ============================================================

@Composable
private fun ActionButtonsRow(
    onShare: () -> Unit,
    onStar: () -> Unit,
    onFeedback: () -> Unit,
    onCommunity: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ActionCircleButton(
            icon = FeatherIcons.Share2,
            label = stringResource(R.string.about_action_share),
            onClick = onShare,
            modifier = Modifier.weight(1f)
        )
        ActionCircleButton(
            icon = FeatherIcons.Star,
            label = stringResource(R.string.about_action_star),
            onClick = onStar,
            modifier = Modifier.weight(1f),
            accent = true
        )
        ActionCircleButton(
            icon = FeatherIcons.MessageSquare,
            label = stringResource(R.string.about_action_feedback),
            onClick = onFeedback,
            modifier = Modifier.weight(1f)
        )
        ActionCircleButton(
            icon = FeatherIcons.Users,
            label = stringResource(R.string.about_action_community),
            onClick = onCommunity,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false
) {
    val gradient = Brush.horizontalGradient(
        listOf(
            if (accent) Color(0xFFFFCC00) else CyberColors.Cyan,
            CyberColors.LineBlue
        )
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(CyberColors.Cyan.copy(alpha = 0.08f))
                .border(
                    border = BorderStroke(1.2.dp, gradient),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) Color(0xFFFFCC00) else CyberColors.Cyan,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================
// 5. Links Section (Version / GitHub / License)
// ============================================================

@Composable
private fun LinksSection(
    context: Context,
    appInfo: AppInfo,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        CyberCard {
            Column {
                CyberMenuRow(
                    icon = FeatherIcons.Tag,
                    title = stringResource(R.string.about_check_update),
                    subtitle = "当前 v${appInfo.name} · 点击查询 GitHub",
                    onClick = onCheckUpdate,
                    showDivider = true
                )
                CyberMenuRow(
                    icon = FeatherIcons.Github,
                    title = stringResource(R.string.about_github_repo),
                    subtitle = GITHUB_REPO_URL.removePrefix("https://"),
                    onClick = { openUrl(context, GITHUB_REPO_URL) },
                    showDivider = true
                )
                CyberMenuRow(
                    icon = FeatherIcons.Book,
                    title = stringResource(R.string.about_license),
                    subtitle = "MIT License",
                    onClick = { openUrl(context, LICENSE_URL) },
                    showDivider = false
                )
            }
        }
    }
}

// ============================================================
// 6. Open Source Credits Section
// ============================================================

@Composable
private fun OpenSourceCreditsSection() {
    CyberSectionHeader(text = stringResource(R.string.about_credits))

    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.about_credits_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )

        val credits = listOf(
            R.string.about_credit_kotlin to "JetBrains",
            R.string.about_credit_compose to "Google",
            R.string.about_credit_coroutines to "JetBrains",
            R.string.about_credit_hilt to "Google",
            R.string.about_credit_room to "Google",
            R.string.about_credit_material to "Google",
            R.string.about_credit_okhttp to "Square",
            R.string.about_credit_ktor to "JetBrains"
        )

        CyberCard {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                credits.forEach { (nameRes, author) ->
                    CreditChip(
                        name = stringResource(nameRes),
                        author = author
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditChip(name: String, author: String) {
    val gradient = Brush.horizontalGradient(
        listOf(CyberColors.Cyan.copy(alpha = 0.6f), CyberColors.LineBlue.copy(alpha = 0.6f))
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(CyberColors.Cyan.copy(alpha = 0.06f))
            .border(
                border = BorderStroke(0.8.dp, gradient),
                shape = RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(brush = gradient)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "· $author",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================
// 7. Update Result Dialog (Enhanced: size + speed)
// ============================================================

@Composable
private fun UpdateResultDialog(
    state: UpdateDialogState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownloadApk: (ReleaseInfo) -> Unit,
    onOpenReleases: () -> Unit,
    onInstallApk: (String) -> Unit
) {
    when (state) {
        UpdateDialogState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_update)) },
            text = { Text(stringResource(R.string.about_checking_update)) },
            confirmButton = {}
        )

        UpdateDialogState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_up_to_date)) },
            text = { Text(stringResource(R.string.about_up_to_date_detail, currentVersion)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_got_it))
                }
            }
        )

        is UpdateDialogState.NewVersion -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.about_new_version_found)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(
                            text = stringResource(
                                R.string.about_new_version_detail,
                                currentVersion,
                                state.latestTag
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.about_release_notes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(CyberColors.Cyan.copy(alpha = 0.05f))
                                .border(
                                    border = BorderStroke(
                                        0.8.dp,
                                        CyberColors.Cyan.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(Radius.md)
                                )
                        ) {
                            val notes = state.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: "（该 Release 未填写更新说明）"
                            androidx.compose.foundation.text.BasicText(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(Spacing.md)
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TextButton(onClick = onOpenReleases) {
                            Text(stringResource(R.string.about_open_releases_page))
                        }
                        TextButton(
                            onClick = {
                                onDownloadApk(
                                    ReleaseInfo(
                                        latestTag = state.latestTag,
                                        downloadUrl = state.downloadUrl
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.about_download_apk))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.about_later))
                    }
                }
            )
        }

        is UpdateDialogState.Downloading -> {
            val progressPct = state.progressPct
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.about_download_apk)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        CyberProgressIndicatorWithDetail(
                            progressPct = progressPct,
                            downloadedBytes = state.downloadedBytes,
                            totalBytes = state.totalBytes,
                            speedBytesPerSec = state.speedBytesPerSec
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        is UpdateDialogState.Downloaded -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_download_complete)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = state.filePath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(state.fileSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberColors.Cyan
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onInstallApk(state.filePath) }) {
                    Text(stringResource(R.string.about_install_apk))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )

        is UpdateDialogState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_failed)) },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.about_ok))
                }
            }
        )
    }
}

@Composable
private fun CyberProgressIndicatorWithDetail(
    progressPct: Int,
    downloadedBytes: Long,
    totalBytes: Long,
    speedBytesPerSec: Long
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (progressPct.coerceIn(0, 100) / 100f),
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "progress"
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Detail: downloaded / total
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.about_download_progress_detail,
                    formatFileSize(downloadedBytes),
                    if (totalBytes > 0) formatFileSize(totalBytes) else "?"
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (speedBytesPerSec > 0) {
                Text(
                    text = stringResource(
                        R.string.about_download_speed,
                        formatFileSize(speedBytesPerSec)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberColors.Cyan,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            color = CyberColors.Cyan,
            trackColor = CyberColors.Cyan.copy(alpha = 0.12f),
            drawStopIndicator = {}
        )

        Text(
            text = stringResource(R.string.about_downloading, progressPct),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

// ============================================================
// External actions + Networking
// ============================================================

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun shareApp(context: Context) {
    runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "R-DeepCode - Android 上的 AI 代码编辑器：\n$RELEASES_URL"
            )
            putExtra(Intent.EXTRA_TITLE, "分享 R-DeepCode")
        }
        val chooser = Intent.createChooser(sendIntent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

private fun formatDate(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(ms))
}

private suspend fun checkUpdate(
    context: Context,
    currentVersion: String
): UpdateDialogState = withContext(Dispatchers.IO) {
    runCatching {
        val req = okhttp3.Request.Builder()
            .url(GITHUB_LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@use UpdateDialogState.Error("HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val json = JsonParser.parseString(body).asJsonObject
                ?: return@use UpdateDialogState.Error(context.getString(R.string.about_parse_version_failed))

            val tag = json.get("tag_name")?.asString
                ?: return@use UpdateDialogState.Error(context.getString(R.string.about_parse_version_failed))
            val latest = parseVersionTag(tag) ?: tag
            val releaseNotes = json.get("body")?.asString
            val htmlUrl = json.get("html_url")?.asString ?: RELEASES_URL

            val downloadUrl = runCatching {
                json.getAsJsonArray("assets")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("browser_download_url")
                    ?.asString
            }.getOrNull() ?: htmlUrl

            if (isUpToDate(latest, currentVersion)) {
                UpdateDialogState.UpToDate
            } else {
                UpdateDialogState.NewVersion(
                    latestTag = latest,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl
                )
            }
        }
    }.getOrElse { UpdateDialogState.Error(it.message ?: context.getString(R.string.about_network_error)) }
}

private suspend fun downloadApk(
    context: Context,
    tag: String,
    downloadUrl: String,
    onProgress: (Int, Long, Long, Long, String?) -> Unit,
    onComplete: (String) -> Unit,
    onError: (String) -> Unit
) = withContext(Dispatchers.IO) {
    runCatching {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val fileName = "R-DeepCode-${tag}.apk"
        val targetFile = File(dir, fileName)
        val filePath = targetFile.absolutePath

        val req = okhttp3.Request.Builder().url(downloadUrl).build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                onError("HTTP ${resp.code}")
                return@withContext
            }
            val body = resp.body ?: run {
                onError(context.getString(R.string.about_network_error))
                return@withContext
            }
            val contentLength = body.contentLength()
            val source = body.source()
            val sink = targetFile.sink().buffer()
            var totalRead = 0L
            val buffer = okio.Buffer()
            var lastPct = -1
            var lastTickMs = System.currentTimeMillis()
            var lastTickBytes = 0L
            var currentSpeed: Long = 0

            while (true) {
                val read = source.read(buffer, 8192L)
                if (read == -1L) break
                sink.write(buffer, read)
                totalRead += read
                val pct = if (contentLength > 0) {
                    ((totalRead * 100) / contentLength).toInt()
                } else 0

                // Compute speed every ~200ms
                val now = System.currentTimeMillis()
                val elapsed = now - lastTickMs
                if (elapsed >= 200L) {
                    val deltaBytes = totalRead - lastTickBytes
                    currentSpeed = if (elapsed > 0) {
                        (deltaBytes * 1000L) / elapsed
                    } else 0L
                    lastTickMs = now
                    lastTickBytes = totalRead
                }

                if (pct != lastPct || currentSpeed >= 0) {
                    lastPct = pct
                    onProgress(pct, totalRead, contentLength, currentSpeed, filePath)
                }
            }
            sink.close()
            source.close()
            if (contentLength > 0) onProgress(100, contentLength, contentLength, 0L, filePath)
            onComplete(filePath)
        }
    }.onFailure {
        onError(it.message ?: context.getString(R.string.about_network_error))
    }
}

private fun installApk(context: Context, filePath: String) {
    runCatching {
        val apkFile = File(filePath)
        if (!apkFile.exists()) return
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}

internal fun parseVersionTag(tag: String): String? {
    val raw = tag.trim().removePrefix("v")
    val seg = raw.substringBefore(' ')
    return seg.ifBlank { null }
}

internal fun isUpToDate(latest: String, current: String): Boolean {
    val cmp = compareVersions(latest, current)
    return cmp <= 0
}

internal fun compareVersions(v1: String, v2: String): Int {
    if (v1 == v2) return 0
    val (base1, pre1) = splitVersion(v1)
    val (base2, pre2) = splitVersion(v2)
    val parts1 = base1.split('.').mapNotNull { it.toIntOrNull() }
    val parts2 = base2.split('.').mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }
    if (pre1.isEmpty() && pre2.isNotEmpty()) return 1
    if (pre1.isNotEmpty() && pre2.isEmpty()) return -1
    return pre1.compareTo(pre2)
}

internal fun splitVersion(v: String): Pair<String, String> {
    val clean = v.substringBefore('+')
    val base = clean.substringBefore('-')
    val pre = if (clean.contains('-')) clean.substringAfter('-') else ""
    return base to pre
}

// ============================================================
// Data classes + constants
// ============================================================

private data class AppInfo(
    val name: String,
    val code: Long,
    val packageName: String,
    val minSdk: Int
)

private data class ReleaseInfo(
    val latestTag: String,
    val downloadUrl: String
)

private sealed interface UpdateDialogState {
    data object Checking : UpdateDialogState
    data object UpToDate : UpdateDialogState
    data class NewVersion(
        val latestTag: String,
        val releaseNotes: String?,
        val downloadUrl: String
    ) : UpdateDialogState

    data class Downloading(
        val progressPct: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val filePath: String?
    ) : UpdateDialogState

    data class Downloaded(
        val filePath: String,
        val fileSizeBytes: Long = runCatching { File(filePath).length() }.getOrDefault(0L)
    ) : UpdateDialogState

    data class Error(val message: String) : UpdateDialogState
}

private val SHARED_CLIENT by lazy { OkHttpClient.Builder().build() }

private fun loadAppIconBitmap(context: Context): ImageBitmap? {
    return runCatching {
        val pm = context.packageManager
        val drawable: Drawable = pm.getApplicationInfo(context.packageName, 0).loadIcon(pm)
        val sizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            ICON_PX_DP.toFloat(),
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        Canvas(bitmap).also { drawable.draw(it) }
        bitmap.asImageBitmap()
    }.getOrNull()
}

private const val GITHUB_REPO_URL = "https://github.com/Lisir2002/deepcode-R"
private const val LICENSE_URL = "https://github.com/Lisir2002/deepcode-R/blob/main/LICENSE"
private const val RELEASES_URL = "https://github.com/Lisir2002/deepcode-R/releases/latest"
private const val GITHUB_LATEST_API = "https://api.github.com/repos/Lisir2002/deepcode-R/releases/latest"
private const val ISSUES_URL = "https://github.com/Lisir2002/deepcode-R/issues"
private const val COMMUNITY_URL = "https://discord.gg/"
private const val ICON_PX_DP = 96
