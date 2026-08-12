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
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.deep.rcode.R
import com.deep.rcode.core.theme.CyberColors
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.core.theme.CyberCard
import com.deep.rcode.core.theme.CyberSectionHeader
import com.deep.rcode.core.theme.CyberMenuRow
import com.deep.rcode.feature.settings.presentation.AboutStatsViewModel
import com.deep.rcode.feature.settings.presentation.UsageStats
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Hash
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Send
import compose.icons.feathericons.Tag
import compose.icons.feathericons.Calendar

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
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        // ===== 模块 1：Hero + 使用统计 合并 =====
        HeroWithStatsCard(
            appName = stringResource(R.string.app_name),
            appIcon = appIcon,
            appInfo = appInfo,
            stats = stats
        )

        // ===== 模块 2：FAQ =====
        FaqSection()

        // ===== 模块 3：只保留检查更新功能（独立卡片，无分享/无 GitHub/无 License） =====
        CyberSectionHeader(text = stringResource(R.string.about_check_update))
        CheckUpdateOnlyCard(
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

        // ===== 模块 4：开源致谢 =====
        OpenSourceCreditsSection()

        // 版权底栏
        Text(
            text = stringResource(R.string.about_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF98A2B3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )

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
// 1. Hero + Stats 合并卡片（去掉霓虹装饰 → 纸感）
// ============================================================

@Composable
private fun HeroWithStatsCard(
    appName: String,
    appIcon: ImageBitmap?,
    appInfo: AppInfo,
    stats: UsageStats
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
    ) {
        CyberCard {
            Column {
                Spacer(Modifier.height(Spacing.lg))

                // ===== Hero Row：左图标 + 右信息 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：App Icon（纸感：浅灰描边，不发光）
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(CyberColors.IconBg)
                            .border(
                                border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(Modifier.width(Spacing.lg))

                    // 右：App Name + Slogan + Info Grid
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // 标题：纯深色粗体，不渐变
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF101828)
                        )
                        // Slogan
                        Text(
                            text = stringResource(R.string.about_slogan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF475467),
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(Modifier.height(2.dp))

                        // 版本信息（2列，纸感：浅灰背景）
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
                                    value = stringResource(R.string.about_author)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.lg))

                // 分割线（浅灰）
                HorizontalDivider(
                    thickness = 0.8.dp,
                    color = CyberColors.Divider,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )

                // ===== 使用统计（纸感：静态卡片，无脉冲无渐变） =====
                Spacer(Modifier.height(Spacing.md))
                PaperUsageStats(stats = stats)
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun HeroInfoChip(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(CyberColors.IconBg)
            .border(
                border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                shape = RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = Spacing.sm, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF667085),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF101828)
        )
    }
}

@Composable
private fun PaperUsageStats(stats: UsageStats) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 第一行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PaperStatCell(
                icon = FeatherIcons.MessageSquare,
                label = stringResource(R.string.about_sessions),
                value = stats.totalSessions.toString(),
                unit = stringResource(R.string.about_stats_unit_sessions),
                modifier = Modifier.weight(1f)
            )
            PaperStatCell(
                icon = FeatherIcons.Hash,
                label = stringResource(R.string.about_messages),
                value = stats.totalMessages.toString(),
                unit = stringResource(R.string.about_stats_unit_messages),
                modifier = Modifier.weight(1f)
            )
            PaperStatCell(
                icon = FeatherIcons.Calendar,
                label = stringResource(R.string.about_active_days),
                value = stats.activeDays.toString(),
                unit = stringResource(R.string.about_stats_unit_days),
                modifier = Modifier.weight(1f)
            )
        }

        // 第二行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PaperStatCell(
                icon = FeatherIcons.Send,
                label = stringResource(R.string.about_input_tokens),
                value = compactNumber(stats.totalInputTokens),
                unit = stringResource(R.string.about_stats_unit_tokens),
                modifier = Modifier.weight(1f)
            )
            PaperStatCell(
                icon = FeatherIcons.DownloadCloud,
                label = stringResource(R.string.about_output_tokens),
                value = compactNumber(stats.totalOutputTokens),
                unit = stringResource(R.string.about_stats_unit_tokens),
                modifier = Modifier.weight(1f)
            )
            PaperStatCell(
                icon = FeatherIcons.Tag,
                label = stringResource(R.string.about_first_used),
                value = if (stats.firstUsedMs > 0L) formatShortDate(stats.firstUsedMs) else "--",
                unit = if (stats.firstUsedMs > 0L) stringResource(R.string.about_stats_unit_since) else "",
                modifier = Modifier.weight(1f),
                compactValue = true
            )
        }
    }
}

@Composable
private fun PaperStatCell(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    compactValue: Boolean = false
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(0.8.dp, CyberColors.CardStroke),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // Icon + Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(CyberColors.IconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF475467),
                        modifier = Modifier.size(11.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF667085),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(2.dp))

            // 数值 + 单位（纯深灰粗体，不渐变）
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = value,
                    style = if (compactValue) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF101828),
                    maxLines = 1
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF667085),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
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
// 2. FAQ Section（扁平无装饰，Q/A 徽章浅灰）
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
                // Q 徽章：浅灰底，无渐变
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(CyberColors.IconBg)
                        .border(
                            border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Q",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF344054)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101828),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = Color(0xFF667085),
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expandable answer（纸感：浅灰底，无发光无渐变）
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
                        .background(CyberColors.IconBg)
                        .border(
                            border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                            shape = RoundedCornerShape(Radius.md)
                        )
                        .padding(Spacing.md)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(
                                    border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF475467)
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF475467),
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 3. 独立检查更新卡片（只保留这一项，无分享/无 GitHub/无 License）
// ============================================================

@Composable
private fun CheckUpdateOnlyCard(
    appInfo: AppInfo,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        CyberCard {
            CyberMenuRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.about_check_update),
                subtitle = stringResource(R.string.about_check_update_subtitle, appInfo.name),
                onClick = onCheckUpdate,
                showDivider = false
            )
        }
    }
}

// ============================================================
// 4. Open Source Credits Section（扁平 chip）
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
            color = Color(0xFF667085),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(CyberColors.IconBg)
            .border(
                border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                shape = RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 小圆点：中灰（不使用渐变）
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF98A2B3))
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF101828)
        )
        Text(
            text = "· $author",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF667085)
        )
    }
}

// ============================================================
// 5. Update Result Dialog（纸感：纯 Material 默认样式）
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
                            color = Color(0xFF101828)
                        )
                        Text(
                            text = stringResource(R.string.about_release_notes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF101828)
                        )
                        // Release Notes 文本框：浅灰底深灰描边
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(CyberColors.IconBg)
                                .border(
                                    border = BorderStroke(0.8.dp, CyberColors.CardStroke),
                                    shape = RoundedCornerShape(Radius.md)
                                )
                        ) {
                            val notes = state.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.about_release_notes_empty)
                            androidx.compose.foundation.text.BasicText(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(0xFF475467),
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
                        PaperProgressIndicatorWithDetail(
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
                        color = Color(0xFF475467)
                    )
                    Text(
                        text = formatFileSize(state.fileSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF101828),
                        fontWeight = FontWeight.SemiBold
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
private fun PaperProgressIndicatorWithDetail(
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
                color = Color(0xFF101828)
            )
            if (speedBytesPerSec > 0) {
                Text(
                    text = stringResource(
                        R.string.about_download_speed,
                        formatFileSize(speedBytesPerSec)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF475467),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 纸感进度条：Material primary 色，track 用浅灰
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            color = Color(0xFF344054),
            trackColor = CyberColors.IconBg,
            drawStopIndicator = {}
        )

        Text(
            text = stringResource(R.string.about_downloading, progressPct),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF667085)
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

// shareApp 已移除（分享卡片已删除）

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
                return@use UpdateDialogState.Error(context.getString(R.string.about_http_error, resp.code))
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
                onError(context.getString(R.string.about_http_error, resp.code))
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
// Helper: load app icon bitmap
// ============================================================

private fun loadAppIconBitmap(context: Context): ImageBitmap? {
    return runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, 0)
        val drawable: Drawable = pm.getApplicationIcon(appInfo)
        val bmp = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    }.getOrNull()
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

// ============================================================
// Network constants
// ============================================================

private val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .build()

// GITHUB_REPO_URL 保留（被 RELEASES_URL 依赖）
// ISSUES_URL / COMMUNITY_URL / LICENSE_URL 已移除（原来只在分享卡片的 GitHub/License/反馈/加群入口用）

private const val GITHUB_REPO_URL = "https://github.com/Lisir2002/deepcode-R"
private const val GITHUB_LATEST_API =
    "https://api.github.com/repos/Lisir2002/deepcode-R/releases/latest"
private const val RELEASES_URL = "$GITHUB_REPO_URL/releases"
