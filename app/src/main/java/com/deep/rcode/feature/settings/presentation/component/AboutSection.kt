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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.deep.rcode.R
import com.deep.rcode.core.theme.Brand
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.settings.presentation.AboutStatsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Hash
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Star
import compose.icons.feathericons.Tag
import compose.icons.feathericons.UploadCloud

private val cyberGradient: Brush
    @Composable
    get() = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary
        ),
        start = Offset.Zero,
        end = Offset.Infinite
    )

private val cyberBorderGradient: Brush
    @Composable
    get() = Brush.horizontalGradient(
        colors = listOf(
            Brand.Sky,
            MaterialTheme.colorScheme.primary
        )
    )

@Composable
private fun CyberCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val gradient = remember { Brush.horizontalGradient(listOf(Brand.Sky, Brand.Blue)) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(gradient.copy(alpha = 0.15f))
            .padding(1.5.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg - 1.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun CyberSectionHeader(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(Radius.xs))
                .background(cyberBorderGradient)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CyberMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    CyberCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CyberProgressIndicator(progressPct: Int, modifier: Modifier = Modifier) {
    val animatedProgress by animateFloatAsState(
        targetValue = (progressPct.coerceIn(0, 100) / 100f),
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "progress"
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.about_downloading, progressPct),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {}
        )
    }
}

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
        }.getOrDefault(AppInfo("unknown", 0L, context.packageName, Build.VERSION.SDK_INT))
    }

    val appIcon = remember { loadAppIconBitmap(context) }

    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        HeroSection(appName = stringResource(R.string.app_name), appIcon = appIcon, appInfo = appInfo)

        UsageStatsSection(stats = stats)

        FaqSection()

        CyberSectionHeader(stringResource(R.string.about_share))
        ShareAndFeedbackSection(context)

        CyberSectionHeader(stringResource(R.string.about_version))
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
                        onProgress = { pct, path ->
                            updateDialog = UpdateDialogState.Downloading(pct, path)
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

@Composable
private fun HeroSection(
    appName: String,
    appIcon: ImageBitmap?,
    appInfo: AppInfo
) {
    CyberCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(cyberBorderGradient)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp)
                    )
                }
            }

            Spacer(Modifier.width(Spacing.lg))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                brush = cyberGradient,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(appName)
                        }
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.xs))
                InfoLine(
                    label = stringResource(R.string.about_version),
                    value = "v${appInfo.name}"
                )
                InfoLine(
                    label = stringResource(R.string.about_build_no),
                    value = appInfo.code.toString()
                )
                InfoLine(
                    label = stringResource(R.string.about_package),
                    value = appInfo.packageName
                )
                InfoLine(
                    label = stringResource(R.string.about_sdk_min),
                    value = "Android API ${appInfo.minSdk}"
                )
            }

            Spacer(Modifier.width(Spacing.md))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.about_author_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.about_author),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Sky
                )
                Icon(
                    imageVector = FeatherIcons.Github,
                    contentDescription = null,
                    tint = Brand.Sky,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun UsageStatsSection(stats: com.deep.rcode.feature.settings.presentation.UsageStats) {
    CyberSectionHeader(stringResource(R.string.about_stats))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            StatCard(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.about_sessions),
                value = stats.totalSessions.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = FeatherIcons.Hash,
                title = stringResource(R.string.about_messages),
                value = stats.totalMessages.toString(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            StatCard(
                icon = FeatherIcons.UploadCloud,
                title = stringResource(R.string.about_input_tokens),
                value = "%1$,d".format(stats.totalInputTokens),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = FeatherIcons.DownloadCloud,
                title = stringResource(R.string.about_output_tokens),
                value = "%1$,d".format(stats.totalOutputTokens),
                modifier = Modifier.weight(1f)
            )
        }
        CyberCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_first_used),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (stats.firstUsedMs > 0L) {
                            formatDate(stats.firstUsedMs)
                        } else "--",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.about_days_active, stats.activeDays),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Sky
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    CyberCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun FaqSection() {
    CyberSectionHeader(stringResource(R.string.about_faq))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        val faqs = listOf(
            R.string.about_faq_q1 to R.string.about_faq_a1,
            R.string.about_faq_q2 to R.string.about_faq_a2,
            R.string.about_faq_q3 to R.string.about_faq_a3,
            R.string.about_faq_q4 to R.string.about_faq_a4
        )
        faqs.forEach { (qRes, aRes) ->
            FaqItem(
                question = stringResource(qRes),
                answer = stringResource(aRes)
            )
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    CyberCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
                )
            }
        }
    }
}

@Composable
private fun ShareAndFeedbackSection(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        CyberMenuRow(
            icon = FeatherIcons.Share2,
            title = stringResource(R.string.about_share),
            subtitle = stringResource(R.string.about_share_subtitle),
            onClick = { shareApp(context) }
        )
        CyberMenuRow(
            icon = FeatherIcons.Star,
            title = stringResource(R.string.about_rate),
            subtitle = stringResource(R.string.about_rate_subtitle),
            onClick = { openUrl(context, GITHUB_REPO_URL) }
        )
        CyberMenuRow(
            icon = FeatherIcons.MessageSquare,
            title = stringResource(R.string.about_feedback),
            subtitle = stringResource(R.string.about_feedback_subtitle),
            onClick = { openUrl(context, ISSUES_URL) }
        )
        CyberMenuRow(
            icon = FeatherIcons.Globe,
            title = stringResource(R.string.about_community),
            subtitle = stringResource(R.string.about_community_subtitle),
            onClick = { openUrl(context, COMMUNITY_URL) }
        )
    }
}

@Composable
private fun LinksSection(
    context: Context,
    appInfo: AppInfo,
    onCheckUpdate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        CyberMenuRow(
            icon = FeatherIcons.Tag,
            title = stringResource(R.string.about_version),
            value = "v${appInfo.name}",
            onClick = onCheckUpdate
        )
        CyberMenuRow(
            icon = FeatherIcons.Github,
            title = stringResource(R.string.about_github_repo),
            onClick = { openUrl(context, GITHUB_REPO_URL) }
        )
        CyberMenuRow(
            icon = FeatherIcons.Book,
            title = stringResource(R.string.about_license),
            onClick = { openUrl(context, LICENSE_URL) }
        )
    }
}

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
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            val notes = state.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: "（该 Release 未填写更新说明）"
                            androidx.compose.foundation.text.BasicText(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

        is UpdateDialogState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_download_apk)) },
            text = {
                CyberProgressIndicator(progressPct = state.progressPct)
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )

        is UpdateDialogState.Downloaded -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_download_complete)) },
            text = {
                Text(
                    text = state.filePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    onProgress: (Int, String?) -> Unit,
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

            while (true) {
                val read = source.read(buffer, 8192L)
                if (read == -1L) break
                sink.write(buffer, read)
                totalRead += read
                val pct = if (contentLength > 0) {
                    ((totalRead * 100) / contentLength).toInt()
                } else 0
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct, filePath)
                }
            }
            sink.close()
            source.close()
            if (contentLength > 0) onProgress(100, filePath)
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
        val filePath: String?
    ) : UpdateDialogState
    data class Downloaded(val filePath: String) : UpdateDialogState
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
