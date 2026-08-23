package com.R.codecore.feature.settings.presentation.component

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.WebView
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.R.codecore.BuildConfig
import com.R.codecore.R
import com.R.codecore.core.theme.CyberColors
import com.R.codecore.core.theme.LocalAppDarkMode
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.core.theme.CyberCard
import com.R.codecore.core.theme.CyberSectionHeader
import com.R.codecore.core.theme.CyberMenuRow
import com.R.codecore.feature.proxy.domain.ClashProxyManager
import com.R.codecore.feature.settings.presentation.AboutStatsViewModel
import com.R.codecore.feature.settings.presentation.UsageStats

// ============================================================
// Entry point
// ============================================================

/** 关于页语义色：Light/Dark 双值，由 LocalAppDarkMode 决定当前取值。 */
private data class AboutColors(
    val bg: Color,
    val card: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val desc: Color,
    val faint: Color,
    val iconGray: Color,
    val heroBgStart: Color,
    val heroBgEnd: Color,
    val heroBorder: Color,
    val blueBg: Color,
    val blueBorder: Color,
    val blueAccent: Color,
    val greenBg: Color,
    val greenAccent: Color,
    val greenText: Color,
    val amberBg: Color,
    val amberAccent: Color,
    val skyBg: Color,
    val skyAccent: Color,
    val orangeBg: Color,
    val orangeBorder: Color,
    val orangeText: Color,
    val selectedBg: Color,
    val selectedBorder: Color,
    val selectedText: Color,
    val chipBg: Color,
    val statGrayBg: Color
)

@Composable
private fun aboutColors(): AboutColors {
    val dark = LocalAppDarkMode.current
    fun c(light: Color, darkV: Color) = if (dark) darkV else light
    return AboutColors(
        bg = c(Color.White, Color(0xFF0D1B2E)),
        card = c(Color.White, Color(0xFF0D1B2E)),
        border = c(Color(0xFFE4E7EC), Color(0xFF223B57)),
        title = c(Color(0xFF101828), Color(0xFFEAF2FF)),
        subtitle = c(Color(0xFF475467), Color(0xFFB8C7DA)),
        desc = c(Color(0xFF667085), Color(0xFF8FA3BF)),
        faint = c(Color(0xFF98A2B3), Color(0xFF6E829C)),
        iconGray = c(Color(0xFF344054), Color(0xFFB8C7DA)),
        heroBgStart = c(Color(0xFFEFF4FF), Color(0xFF13273F)),
        heroBgEnd = c(Color(0xFFE3EDFF), Color(0xFF0F3A63)),
        heroBorder = c(Color(0xFFD6E4FF), Color(0xFF223B57)),
        blueBg = c(Color(0xFFEFF6FF), Color(0xFF0F3A63)),
        blueBorder = c(Color(0xFFBFDBFE), Color(0xFF1E4E8C)),
        blueAccent = c(Color(0xFF2563EB), Color(0xFF60A5FA)),
        greenBg = c(Color(0xFFECFDF5), Color(0xFF0B3B2E)),
        greenAccent = c(Color(0xFF059669), Color(0xFF34D399)),
        greenText = c(Color(0xFF047857), Color(0xFF6EE7B7)),
        amberBg = c(Color(0xFFFFFBEB), Color(0xFF451A03)),
        amberAccent = c(Color(0xFFD97706), Color(0xFFFBBF24)),
        skyBg = c(Color(0xFFF0F9FF), Color(0xFF0C4A6E)),
        skyAccent = c(Color(0xFF0284C7), Color(0xFF38BDF8)),
        orangeBg = c(Color(0xFFFFF3E0), Color(0xFF7C2D12)),
        orangeBorder = c(Color(0xFFFFD6A5), Color(0xFF9A3412)),
        orangeText = c(Color(0xFFB45309), Color(0xFFFDBA74)),
        selectedBg = c(Color(0xFFF0F6FF), Color(0xFF0F3A63)),
        selectedBorder = c(Color(0xFFBFDBFE), Color(0xFF1E4E8C)),
        selectedText = c(Color(0xFF2563EB), Color(0xFF60A5FA)),
        chipBg = c(Color(0xFFDBEAFE), Color(0xFF0F3A63)),
        statGrayBg = c(Color(0xFFF2F3F5), Color(0xFF13273F))
    )
}

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aboutVM: AboutStatsViewModel = hiltViewModel()
    val stats by aboutVM.stats.collectAsStateWithLifecycle()
    val proxyState by aboutVM.proxyState.collectAsStateWithLifecycle()
    val terminalReady by aboutVM.terminalReady.collectAsStateWithLifecycle()

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
    val webViewVersion = remember {
        runCatching {
            WebView.getCurrentWebViewPackage()?.versionName?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "--"
    }
    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }
    val ac = aboutColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ac.bg)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        // ===== 模块 1：软件介绍（Hero，独立卡片） =====
        HeroCard(
            appName = stringResource(R.string.app_name),
            appIcon = appIcon,
            appInfo = appInfo,
            isDebug = BuildConfig.DEBUG,
            ac = ac
        )

        // ===== 模块 2：核心组件（宿主 / 终端 / 代理 / 浏览器） =====
        CoreComponentsSection(
            appVersion = appInfo.name,
            terminalReady = terminalReady,
            proxyRunning = proxyState.enabled && proxyState.controllerReachable,
            proxyPort = proxyState.mixedPort,
            webViewVersion = webViewVersion,
            ac = ac
        )

        // ===== 模块 3：使用统计（独立卡片） =====
        UsageStatsSection(stats = stats, ac = ac)

        // ===== 模块 4：版本更新（独立卡片） =====
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

        // ===== 模块 5：开源致谢 =====
        OpenSourceCreditsSection(ac = ac)

        // 版权底栏
        Text(
            text = stringResource(R.string.about_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = ac.faint,
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
            },
            ac = ac
        )
    }
}

// ============================================================
// 1. Hero：软件介绍（App 图标 + 名称 + 版本信息）
// ============================================================

@Composable
private fun HeroCard(
    appName: String,
    appIcon: ImageBitmap?,
    appInfo: AppInfo,
    isDebug: Boolean,
    ac: AboutColors
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
    ) {
        CyberCard {
            Column {
                Spacer(Modifier.height(Spacing.lg))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：App Icon（浅冰蓝渐变底，圆角大方块）
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(ac.heroBgStart, ac.heroBgEnd)
                                )
                            )
                            .border(
                                border = BorderStroke(0.8.dp, ac.heroBorder),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(12.dp),
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

                    // 右：名称 + 口号 + 版本胶囊
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = ac.title
                        )
                        Text(
                            text = stringResource(R.string.about_slogan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ac.subtitle,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            VersionPill(text = "v${appInfo.name}", ac = ac)
                            VersionPill(text = "#${appInfo.code}", outline = true, ac = ac)
                            VariantPill(isDebug = isDebug, ac = ac)
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.lg))

                HorizontalDivider(
                    thickness = 0.8.dp,
                    color = ac.border,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )

                // 版本信息 2×2（浅灰纸感 chip）
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        HeroInfoChip(
                            label = stringResource(R.string.about_version),
                            value = "v${appInfo.name}",
                            ac = ac
                        )
                        HeroInfoChip(
                            label = stringResource(R.string.about_sdk_min),
                            value = "API ${appInfo.minSdk}",
                            ac = ac
                        )
                        HeroInfoChip(
                            label = stringResource(R.string.about_variant),
                            value = stringResource(
                                if (isDebug) R.string.about_variant_debug
                                else R.string.about_variant_release
                            ),
                            ac = ac
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        HeroInfoChip(
                            label = stringResource(R.string.about_build_no),
                            value = "#${appInfo.code}",
                            ac = ac
                        )
                        HeroInfoChip(
                            label = stringResource(R.string.about_package),
                            value = appInfo.packageName,
                            ac = ac
                        )
                        HeroInfoChip(
                            label = stringResource(R.string.about_author_title),
                            value = stringResource(R.string.about_author),
                            ac = ac
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun VersionPill(text: String, outline: Boolean = false, ac: AboutColors) {
    val shape = RoundedCornerShape(Radius.pill)
    val bgModifier = if (outline) {
        Modifier.background(ac.card, shape)
    } else {
        Modifier.background(
            Brush.horizontalGradient(listOf(ac.blueAccent, ac.skyAccent)),
            shape
        )
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .then(bgModifier)
            .border(
                border = BorderStroke(
                    0.8.dp,
                    if (outline) ac.border else Color.Transparent
                ),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (outline) ac.subtitle else Color.White
        )
    }
}

/**
 * 构建变体胶囊：Debug 版（com.R.codecore.debug）与 Release 版（com.R.codecore）数据目录相互隔离。
 * 展示在版本号旁，提醒用户勿混装 debug/release 包导致"历史对话消失"。
 */
@Composable
private fun VariantPill(isDebug: Boolean, ac: AboutColors) {
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (isDebug) ac.amberBg else ac.blueBg)
            .border(
                border = BorderStroke(
                    0.8.dp,
                    if (isDebug) ac.orangeBorder else ac.blueBorder
                ),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                if (isDebug) R.string.about_variant_debug else R.string.about_variant_release
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isDebug) ac.orangeText else ac.blueAccent
        )
    }
}

@Composable
private fun HeroInfoChip(
    label: String,
    value: String,
    ac: AboutColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(ac.statGrayBg)
            .border(
                border = BorderStroke(0.8.dp, ac.border),
                shape = RoundedCornerShape(Radius.sm)
            )
            .padding(horizontal = Spacing.sm, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = ac.desc,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = ac.title
        )
    }
}

// ============================================================
// 2. 核心组件：宿主 / 终端 / 代理 / 浏览器（Bento 2×2）
// ============================================================

private data class CoreItem(
    val icon: ImageVector,
    val accent: Color,
    val accentBg: Color,
    val name: String,
    val desc: String,
    val status: String,
    val statusOk: Boolean
)

@Composable
private fun CoreComponentsSection(
    appVersion: String,
    terminalReady: Boolean,
    proxyRunning: Boolean,
    proxyPort: Int,
    webViewVersion: String,
    ac: AboutColors
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        CyberSectionHeader(text = stringResource(R.string.about_core_components))
        Text(
            text = stringResource(R.string.about_core_components_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = ac.desc,
            modifier = Modifier.padding(start = Spacing.lg + 4.dp, bottom = Spacing.xs)
        )

        val cores = listOf(
            CoreItem(
                icon = Icons.Rounded.Smartphone,
                accent = ac.blueAccent,
                accentBg = ac.blueBg,
                name = stringResource(R.string.about_host_core),
                desc = stringResource(R.string.about_host_core_desc, appVersion),
                status = stringResource(R.string.about_core_status_running),
                statusOk = true
            ),
            CoreItem(
                icon = Icons.Rounded.Terminal,
                accent = ac.greenAccent,
                accentBg = ac.greenBg,
                name = stringResource(R.string.about_terminal_core),
                desc = stringResource(R.string.about_terminal_core_desc),
                status = if (terminalReady) {
                    stringResource(R.string.about_core_status_ready)
                } else {
                    stringResource(R.string.about_core_status_not_installed)
                },
                statusOk = terminalReady
            ),
            CoreItem(
                icon = Icons.Rounded.Public,
                accent = ac.amberAccent,
                accentBg = ac.amberBg,
                name = stringResource(R.string.about_proxy_core),
                desc = stringResource(
                    R.string.about_proxy_core_desc,
                    ClashProxyManager.MIHOMO_VERSION
                ) + " · :$proxyPort",
                status = if (proxyRunning) {
                    stringResource(R.string.about_core_status_running)
                } else {
                    stringResource(R.string.about_core_status_stopped)
                },
                statusOk = proxyRunning
            ),
            CoreItem(
                icon = Icons.Rounded.Language,
                accent = ac.skyAccent,
                accentBg = ac.skyBg,
                name = stringResource(R.string.about_browser_core),
                desc = stringResource(R.string.about_browser_core_desc, webViewVersion),
                status = stringResource(R.string.about_core_status_ready),
                statusOk = true
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            CoreComponentCard(item = cores[0], modifier = Modifier.weight(1f), ac = ac)
            CoreComponentCard(item = cores[1], modifier = Modifier.weight(1f), ac = ac)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            CoreComponentCard(item = cores[2], modifier = Modifier.weight(1f), ac = ac)
            CoreComponentCard(item = cores[3], modifier = Modifier.weight(1f), ac = ac)
        }
    }
}

@Composable
private fun CoreComponentCard(item: CoreItem, modifier: Modifier = Modifier, ac: AboutColors) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = ac.card),
        border = BorderStroke(0.8.dp, ac.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(item.accentBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = item.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusPill(text = item.status, ok = item.statusOk, ac = ac)
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ac.title
            )
            Text(
                text = item.desc,
                style = MaterialTheme.typography.labelSmall,
                color = ac.desc,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean, ac: AboutColors) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (ok) ac.greenBg else ac.statGrayBg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (ok) ac.greenAccent else ac.faint)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) ac.greenText else ac.desc
        )
    }
}

// ============================================================
// 3. 使用统计（独立卡片，大数字 + 渐变强调）
// ============================================================

@Composable
private fun UsageStatsSection(stats: UsageStats, ac: AboutColors) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        CyberSectionHeader(text = stringResource(R.string.about_stats))
        Text(
            text = stringResource(R.string.about_stats_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = ac.desc,
            modifier = Modifier.padding(start = Spacing.lg + 4.dp, bottom = Spacing.xs)
        )

        CyberCard {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    UsageStatCell(
                        icon = Icons.Rounded.ChatBubble,
                        label = stringResource(R.string.about_sessions),
                        value = stats.totalSessions.toString(),
                        unit = stringResource(R.string.about_stats_unit_sessions),
                        modifier = Modifier.weight(1f),
                        ac = ac
                    )
                    UsageStatCell(
                        icon = Icons.Rounded.Tag,
                        label = stringResource(R.string.about_messages),
                        value = stats.totalMessages.toString(),
                        unit = stringResource(R.string.about_stats_unit_messages),
                        modifier = Modifier.weight(1f),
                        ac = ac
                    )
                    UsageStatCell(
                        icon = Icons.Rounded.CalendarMonth,
                        label = stringResource(R.string.about_active_days),
                        value = stats.activeDays.toString(),
                        unit = stringResource(R.string.about_stats_unit_days),
                        modifier = Modifier.weight(1f),
                        highlighted = true,
                        ac = ac
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    UsageStatCell(
                        icon = Icons.Rounded.CloudUpload,
                        label = stringResource(R.string.about_input_tokens),
                        value = compactNumber(stats.totalInputTokens),
                        unit = stringResource(R.string.about_stats_unit_tokens),
                        modifier = Modifier.weight(1f),
                        ac = ac
                    )
                    UsageStatCell(
                        icon = Icons.Rounded.CloudDownload,
                        label = stringResource(R.string.about_output_tokens),
                        value = compactNumber(stats.totalOutputTokens),
                        unit = stringResource(R.string.about_stats_unit_tokens),
                        modifier = Modifier.weight(1f),
                        ac = ac
                    )
                    UsageStatCell(
                        icon = Icons.Rounded.Schedule,
                        label = stringResource(R.string.about_first_used),
                        value = if (stats.firstUsedMs > 0L) formatShortDate(stats.firstUsedMs) else "--",
                        unit = if (stats.firstUsedMs > 0L) stringResource(R.string.about_stats_unit_since) else "",
                        modifier = Modifier.weight(1f),
                        compactValue = true,
                        gradient = false,
                        ac = ac
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageStatCell(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    compactValue: Boolean = false,
    highlighted: Boolean = false,
    gradient: Boolean = true,
    ac: AboutColors
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) ac.selectedBg else ac.card
        ),
        border = BorderStroke(
            0.8.dp,
            if (highlighted) ac.selectedBorder else ac.border
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (highlighted) ac.chipBg else ac.statGrayBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (highlighted) ac.selectedText else ac.iconGray,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ac.desc,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val baseStyle = if (compactValue) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineSmall
                }
                Text(
                    text = value,
                    style = baseStyle.copy(
                        brush = if (gradient) {
                            Brush.horizontalGradient(listOf(ac.blueAccent, ac.skyAccent))
                        } else {
                            Brush.horizontalGradient(listOf(ac.title, ac.title))
                        },
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ac.desc,
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
// 4. 独立检查更新卡片
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
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.about_check_update),
                subtitle = stringResource(R.string.about_check_update_subtitle, appInfo.name),
                onClick = onCheckUpdate,
                showDivider = false
            )
        }
    }
}

// ============================================================
// 5. Open Source Credits Section（扁平 chip）
// ============================================================

@Composable
private fun OpenSourceCreditsSection(ac: AboutColors) {
    CyberSectionHeader(text = stringResource(R.string.about_credits))

    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.about_credits_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = ac.desc,
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
                        author = author,
                        ac = ac
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditChip(name: String, author: String, ac: AboutColors) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(ac.statGrayBg)
            .border(
                border = BorderStroke(0.8.dp, ac.border),
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
                .background(ac.faint)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ac.title
        )
        Text(
            text = "· $author",
            style = MaterialTheme.typography.labelSmall,
            color = ac.desc
        )
    }
}

// ============================================================
// 6. Update Result Dialog
// ============================================================

@Composable
private fun UpdateResultDialog(
    state: UpdateDialogState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownloadApk: (ReleaseInfo) -> Unit,
    onOpenReleases: () -> Unit,
    onInstallApk: (String) -> Unit,
    ac: AboutColors
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
                            color = ac.title
                        )
                        Text(
                            text = stringResource(R.string.about_release_notes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ac.title
                        )
                        // Release Notes 文本框：浅灰底深灰描边
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(ac.statGrayBg)
                                .border(
                                    border = BorderStroke(0.8.dp, ac.border),
                                    shape = RoundedCornerShape(Radius.md)
                                )
                        ) {
                            val notes = state.releaseNotes?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.about_release_notes_empty)
                            androidx.compose.foundation.text.BasicText(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = ac.subtitle,
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
                            speedBytesPerSec = state.speedBytesPerSec,
                            ac = ac
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
                        color = ac.subtitle
                    )
                    Text(
                        text = formatFileSize(state.fileSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = ac.title,
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
    speedBytesPerSec: Long,
    ac: AboutColors
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
                color = ac.title
            )
            if (speedBytesPerSec > 0) {
                Text(
                    text = stringResource(
                        R.string.about_download_speed,
                        formatFileSize(speedBytesPerSec)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ac.subtitle,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(Radius.pill)),
            color = ac.iconGray,
            trackColor = ac.statGrayBg,
            drawStopIndicator = {}
        )

        Text(
            text = stringResource(R.string.about_downloading, progressPct),
            style = MaterialTheme.typography.bodySmall,
            color = ac.desc
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
        val fileName = "R-CodeCore-${tag}.apk"
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

private const val GITHUB_REPO_URL = "https://github.com/Lisir2002/deepcode-R"
private const val GITHUB_LATEST_API =
    "https://api.github.com/repos/Lisir2002/deepcode-R/releases/latest"
private const val RELEASES_URL = "$GITHUB_REPO_URL/releases"
