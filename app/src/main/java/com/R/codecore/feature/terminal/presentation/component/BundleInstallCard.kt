package com.R.codecore.feature.terminal.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.container.progress.AggregateProgressState
import com.R.codecore.feature.agent.domain.container.progress.DownloadSlot
import com.R.codecore.feature.agent.domain.container.progress.FinishStats
import com.R.codecore.feature.agent.domain.container.progress.InstallPhase
import com.R.codecore.feature.agent.domain.container.progress.LogLine
import com.R.codecore.feature.agent.domain.container.progress.LogLineKind
import com.R.codecore.feature.agent.domain.container.progress.ProgressSource
import com.R.codecore.feature.agent.domain.container.progress.SlotStatus
import com.R.codecore.feature.terminal.data.bundle.BundleInstallState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Download
import compose.icons.feathericons.Package
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import compose.icons.feathericons.XCircle
import compose.icons.feathericons.Zap
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 3 行极简 Bundle 安装卡片（Spec v1.0 L1/L2/L3 + N 微槽块）。
 *
 * L1：图标 + 标题 + 进度条（蓝/青/绿三段颜色，尾部 4dp 条编码来源）
 * L2：N 个微下载方块（14dp×14dp） + ⏱ elapsed / ⏳ ETA
 * L3：速率/安装进度聚合行（⬇ 3.7MB/s · (5/24) libssl ✓）
 */
@Composable
fun BundleInstallCard(
    bundle: UiBundle,
    bundleState: BundleInstallState,
    /** Aggregator 发出的深度进度（如果还没走新通路为 null，UI 自动降级成旧单行 chip 模式）。 */
    aggregate: AggregateProgressState?,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onOpenLogDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyError: ((String) -> Unit)? = null,
) {
    // F：卡片背景 phase tint（DOWNLOAD=蓝5% / INSTALL=绿5% / FAILED=红10% / DONE=绿8%）
    val baseBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val targetTint = when (aggregate?.phase) {
        InstallPhase.DOWNLOAD -> Color(0xFF1976D2).copy(alpha = 0.05f)
        InstallPhase.INSTALL -> Color(0xFF388E3C).copy(alpha = 0.05f)
        InstallPhase.POST_HOOK -> Color(0xFFF57C00).copy(alpha = 0.06f)
        InstallPhase.DONE -> Color(0xFF2E7D32).copy(alpha = 0.08f)
        InstallPhase.FAILED -> Color(0xFFD32F2F).copy(alpha = 0.10f)
        null -> baseBg
    }
    val tintAnim = remember { Animatable(0f) }
    LaunchedEffect(aggregate?.phase) {
        tintAnim.animateTo(1f, animationSpec = tween(600, easing = FastOutSlowInEasing))
    }
    val tintColor = lerp(baseBg, targetTint, tintAnim.value)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onOpenLogDialog),
        colors = CardDefaults.cardColors(containerColor = tintColor),
    ) {
        // E：终态（DONE/FAILED）→ 摘要看板；其他 phase → 经典 L1/L2/L3
        val phase = aggregate?.phase
        if (phase == InstallPhase.DONE) {
            DoneSummaryBoard(
                bundle = bundle,
                stats = aggregate.finishStats,
                onOpenLogDialog = onOpenLogDialog,
                onUninstallClick = onUninstallClick,
                installedChip = bundleState is BundleInstallState.Installed,
            )
        } else if (phase == InstallPhase.FAILED) {
            FailedSummaryBoard(
                bundle = bundle,
                reason = aggregate.failSummary.orEmpty(),
                onRetry = onInstallClick,
                onCopyError = onCopyError,
                onOpenLogDialog = onOpenLogDialog,
            )
        } else {
            InstallingProgressLayout(
                bundle = bundle,
                bundleState = bundleState,
                aggregate = aggregate,
                onInstallClick = onInstallClick,
                onUninstallClick = onUninstallClick,
                onOpenLogDialog = onOpenLogDialog,
            )
        }
    }
}

// ─────────────────────── 安装中：经典 L1/L2/L3 ───────────────────────

@Composable
private fun InstallingProgressLayout(
    bundle: UiBundle,
    bundleState: BundleInstallState,
    aggregate: AggregateProgressState?,
    onInstallClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onOpenLogDialog: () -> Unit,
) {
    Column(modifier = Modifier.padding(Spacing.lg)) {
        // ── L1：标题 + 分段进度条 + 尾部百分比 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = bundle.icon,
                    contentDescription = bundle.title,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bundle.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                val pct = aggregate?.total?.let { (it * 100).toInt().coerceIn(0, 100) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (aggregate != null && pct != null) {
                        SegmentedProgressBar(
                            progress = aggregate.total,
                            source = aggregate.source,
                            phase = aggregate.phase,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        when (bundleState) {
                            is BundleInstallState.Installing,
                            BundleInstallState.Uninstalling,
                            -> {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp),
                                )
                            }
                            is BundleInstallState.Installed -> {
                                LinearProgressIndicator(
                                    progress = { 1f },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp),
                                    color = SemanticColors.Success,
                                )
                            }
                            else -> Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            InstallActionsChip(
                state = bundleState,
                onInstall = onInstallClick,
                onUninstall = onUninstallClick,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        // ── L2：N 微槽方块 + 时间 + Chevron 动画 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val slots = aggregate?.slots.orEmpty()
            if (slots.isNotEmpty()) {
                MicroDownloadSlotsRow(
                    slots = slots,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(Spacing.md))
            } else {
                Spacer(modifier = Modifier.weight(0.35f))
            }
            Text(
                text = buildTimeRowText(aggregate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            // F：Chevron 动画 —— Installing 中轻微 bounce（可点击提示），点击 Dialog 后变为 up
            val installing = aggregate?.phase?.isTerminal?.not() == true
            val chevronRot by animateFloatAsState(
                targetValue = 0f,
                animationSpec = tween(250, easing = FastOutSlowInEasing),
                label = "chev_${bundle.id.stableKey}",
            )
            val infinite = rememberInfiniteTransition(label = "chev_bounce")
            val bounceScale by infinite.animateFloat(
                initialValue = 1f, targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "chev_bounce_${bundle.id.stableKey}",
            )
            Icon(
                imageVector = FeatherIcons.ChevronDown,
                contentDescription = "展开日志",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRot)
                    .then(if (installing) Modifier.scale(bounceScale) else Modifier),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // ── L3：彩色 Chip 组合聚合行（C 方向） ──
        TokenizedStatusLine(bundle, bundleState, aggregate)
    }
}

// ─────────────────────── E.1 完成态：4 格数据看板 ───────────────────────

@Composable
private fun DoneSummaryBoard(
    bundle: UiBundle,
    stats: FinishStats?,
    onOpenLogDialog: () -> Unit,
    onUninstallClick: () -> Unit,
    installedChip: Boolean,
) {
    Column(modifier = Modifier.padding(Spacing.lg)) {
        // ── 标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(bundle.icon, bundle.title, tint = SemanticColors.Success)
            }
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = "${bundle.title} 已就绪",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SemanticColors.Success,
            )
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.Check, contentDescription = "done", tint = SemanticColors.Success, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(Spacing.md))
        // ── 4 格数据块 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 蓝块：下载量
            StatMiniTile(
                icon = FeatherIcons.Download,
                label = "下载",
                value = stats?.bytesDownloaded?.let { formatBytesShort(it) } ?: "—",
                bg = Color(0xFFE3F2FD), fg = Color(0xFF1565C0),
                modifier = Modifier.weight(1f),
            )
            // 绿块：耗时
            StatMiniTile(
                icon = FeatherIcons.Clock,
                label = "耗时",
                value = stats?.let { String.format("%.1fs", it.elapsedMs / 1000f) } ?: "—",
                bg = Color(0xFFE8F5E9), fg = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f),
            )
            // 青块：包数
            StatMiniTile(
                icon = FeatherIcons.Package,
                label = "包数",
                value = stats?.let { "${it.packagesInstalled}" } ?: "—",
                bg = Color(0xFFE0F7FA), fg = Color(0xFF00838F),
                modifier = Modifier.weight(1f),
            )
            // 紫块：均速
            val avg = stats?.averageSpeedBps()
            StatMiniTile(
                icon = FeatherIcons.Zap,
                label = "均速",
                value = avg?.let { formatBps(it) } ?: "—",
                bg = Color(0xFFF3E5F5), fg = Color(0xFF6A1B9A),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // ── 底部操作行：卸载 + 查看日志 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (installedChip) {
                ElevatedAssistChip(
                    onClick = onUninstallClick,
                    leadingIcon = { Icon(FeatherIcons.Trash2, null, modifier = Modifier.size(14.dp)) },
                    label = { Text("卸载", style = MaterialTheme.typography.labelMedium) },
                    colors = AssistChipDefaults.elevatedAssistChipColors(
                        containerColor = Color(0xFFFFEBEE),
                        labelColor = Color(0xFFC62828),
                        leadingIconContentColor = Color(0xFFC62828),
                    ),
                )
            }
            ElevatedAssistChip(
                onClick = onOpenLogDialog,
                leadingIcon = { Icon(FeatherIcons.ChevronUp, null, modifier = Modifier.size(14.dp).rotate(180f)) },
                label = { Text("查看全量日志", style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

// ─────────────────────── E.2 失败态：红 Banner + 内嵌重试/复制 ───────────────────────

@Composable
private fun FailedSummaryBoard(
    bundle: UiBundle,
    reason: String,
    onRetry: () -> Unit,
    onCopyError: ((String) -> Unit)?,
    onOpenLogDialog: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(Spacing.lg)) {
        // ── 标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(bundle.icon, bundle.title, tint = SemanticColors.Error)
            }
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = "${bundle.title} 安装失败",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SemanticColors.Error,
            )
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.XCircle, contentDescription = "failed", tint = SemanticColors.Error, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(Spacing.md))
        // ── 错误卡片（可折叠） ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
            shape = RoundedCornerShape(Radius.md),
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(FeatherIcons.AlertTriangle, null, tint = Color(0xFFC62828), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = if (expanded) reason else reason.take(80) + if (reason.length > 80) "…" else "",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFB71C1C)),
                        maxLines = if (expanded) 10 else 2,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        // ── 操作 Chip 行 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ElevatedAssistChip(
                onClick = onRetry,
                leadingIcon = { Icon(FeatherIcons.RefreshCw, null, modifier = Modifier.size(14.dp)) },
                label = { Text("一键重试", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)) },
                colors = AssistChipDefaults.elevatedAssistChipColors(
                    containerColor = Color(0xFFFFF3E0),
                    labelColor = Color(0xFFE65100),
                    leadingIconContentColor = Color(0xFFE65100),
                ),
            )
            ElevatedAssistChip(
                onClick = { onCopyError?.invoke(reason) },
                enabled = onCopyError != null,
                leadingIcon = { Icon(FeatherIcons.Copy, null, modifier = Modifier.size(14.dp)) },
                label = { Text("复制错误详情", style = MaterialTheme.typography.labelMedium) },
            )
            ElevatedAssistChip(
                onClick = onOpenLogDialog,
                leadingIcon = { Icon(FeatherIcons.Settings, null, modifier = Modifier.size(14.dp)) },
                label = { Text("查看日志", style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

// ─────────────────────── E：完成/失败看板 通用迷你数据方块 ───────────────────────

@Composable
private fun StatMiniTile(
    icon: ImageVector,
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(Radius.md),
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = fg.copy(alpha = 0.85f)))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = fg),
            )
        }
    }
}

private fun FinishStats.averageSpeedBps(): Float {
    if (elapsedMs <= 0L) return 0f
    return bytesDownloaded / (elapsedMs / 1000f)
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

// ─────────────────────── 按钮 Chip（安装/卸载/处理中/已安装 四态） ───────────────────────

@Composable
private fun InstallActionsChip(
    state: BundleInstallState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    when (state) {
        is BundleInstallState.Installed -> DangerOutlinedButton(
            onClick = onUninstall,
            icon = FeatherIcons.Trash2,
            text = "卸载",
        )
        is BundleInstallState.Installing, BundleInstallState.Uninstalling ->
            ProgressPlaceholderButton(text = "处理中…")
        is BundleInstallState.Failed -> PrimaryButton(
            onClick = onInstall,
            icon = FeatherIcons.Plus,
            text = "重试",
        )
        BundleInstallState.NotInstalled -> PrimaryButton(
            onClick = onInstall,
            icon = FeatherIcons.Plus,
            text = "安装",
        )
    }
}

// ─────────────────────── B：L1 分段进度条（4 phase + 终态✔✖ + 估算虚线+shimmer） ───────────────────────

private val PHASE_DOWNLOAD_END = 0.45f
private val PHASE_INSTALL_END = 0.95f

private val COL_DOWNLOAD = Color(0xFF1976D2)    // Blue 700
private val COL_INSTALL = Color(0xFF388E3C)     // Green 700
private val COL_POST = Color(0xFFF57C00)        // Orange 700
private val COL_DONE_BG = listOf(Color(0xFF4CAF50), Color(0xFF2E7D32))
private val COL_FAILED = Color(0xFFD32F2F)      // Red 700

@Composable
private fun SegmentedProgressBar(
    progress: Float,
    source: ProgressSource,
    phase: InstallPhase,
    modifier: Modifier = Modifier,
) {
    // F：进度条 animateFloatAsState 插值
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(200, easing = LinearEasing),
        label = "progress_anim",
    )
    val estimated = source == ProgressSource.TRAFFIC_STATS_ESTIMATED
    val isDone = phase == InstallPhase.DONE
    val isFailed = phase == InstallPhase.FAILED
    Box(modifier = modifier.height(8.dp)) {
        val track = MaterialTheme.colorScheme.surfaceVariant
        // Track（背景灰）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(track)
                .then(
                    if (estimated) Modifier.dashedBorder(
                        width = 1.dp,
                        color = Color(0xFF64B5F6),
                        shape = RoundedCornerShape(4.dp),
                    ) else Modifier,
                ),
        )
        // Progress fill（根据 phase 的 4 段颜色 + animated 进度 clip）
        if (isDone) {
            // 终态 DONE：渐变绿 + 居中白✔浮层
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(COL_DONE_BG)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else if (isFailed) {
            // FAILED：整段红 + ✖ 浮层 + 尾部闪烁
            val blink = rememberInfiniteTransition(label = "fail_blink")
            val blinkAlpha by blink.animateFloat(
                initialValue = 0.5f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "fail_blink_alpha",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(COL_FAILED.copy(alpha = blinkAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            // 常规：根据 animated 进度，分 4 段 paint 不同颜色；外加 marker；外加 shimmer（估算时）
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                val w = size.width
                val h = size.height
                val fillW = w * animated
                // 计算每段终点的宽度
                val downloadEnd = w * PHASE_DOWNLOAD_END
                val installEnd = w * PHASE_INSTALL_END
                // Draw download segment
                if (fillW > 0f) {
                    val p1 = minOf(downloadEnd, fillW)
                    drawRect(color = COL_DOWNLOAD, size = androidx.compose.ui.geometry.Size(p1, h))
                }
                // Draw install segment
                if (fillW > downloadEnd) {
                    val p2Start = downloadEnd
                    val p2End = minOf(installEnd, fillW)
                    drawRect(
                        color = COL_INSTALL,
                        topLeft = Offset(x = p2Start, y = 0f),
                        size = androidx.compose.ui.geometry.Size(p2End - p2Start, h),
                    )
                }
                // Draw post-hook segment
                if (fillW > installEnd) {
                    val p3Start = installEnd
                    drawRect(
                        color = COL_POST,
                        topLeft = Offset(x = p3Start, y = 0f),
                        size = androidx.compose.ui.geometry.Size(fillW - p3Start, h),
                    )
                }
                // Phase markers（小圆点）
                val markerR = 2.dp.toPx()
                val markerY = h / 2
                if (animated > PHASE_DOWNLOAD_END) {
                    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = markerR, center = Offset(downloadEnd, markerY))
                }
                if (animated > PHASE_INSTALL_END) {
                    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = markerR, center = Offset(installEnd, markerY))
                }
                // 来源颜色编码的 4dp 尾条（source code）
                val tailW = 4.dp.toPx().coerceAtMost(w)
                val tailColor: Color = when (source) {
                    ProgressSource.PREFETCH_MANAGER_CONFIRMED -> COL_DOWNLOAD
                    ProgressSource.TRAFFIC_STATS_ESTIMATED -> Color(0xFF64B5F6)
                    ProgressSource.APK_STDOUT_CONFIRMED -> COL_INSTALL
                }
                drawRect(
                    color = tailColor,
                    topLeft = Offset(x = w - tailW, y = 0f),
                    size = androidx.compose.ui.geometry.Size(tailW, h),
                )
            }
            // Estimated 时整段叠一层 shimmer（非精确信号的视觉提示）
            if (estimated) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerOverlay(COL_DOWNLOAD.copy(alpha = 0.35f), COL_INSTALL.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

// ─────────────────────── L2 N 微下载槽块 + A 方向（shimmer/Tooltip/放大） ───────────────────────

@Composable
private fun MicroDownloadSlotsRow(slots: List<DownloadSlot>, modifier: Modifier = Modifier) {
    val size: Dp = when (slots.size) {
        in 0..3 -> 24.dp
        4, 5 -> 20.dp
        6, 7 -> 16.dp
        else -> 13.dp
    }
    // 点击放大浮层的当前 slot（key = slot.id，null=关闭）
    var expandedSlotId by remember { mutableStateOf<Int?>(null) }
    val expandedSlot = expandedSlotId?.let { id -> slots.firstOrNull { it.id == id } }
    Box(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            slots.forEach { slot ->
                MicroSlotBlock(
                    slot = slot,
                    size = size,
                    onClick = { expandedSlotId = if (expandedSlotId == slot.id) null else slot.id },
                )
            }
        }
        // 放大浮层
        if (expandedSlot != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expandedSlotId = null },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Card(
                    modifier = Modifier
                        .size(size * 3.2f)
                        .padding(start = (size.value / 2).dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(Radius.md),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MicroSlotBlock(slot = expandedSlot, size = size * 2.6f, onClick = { })
                    }
                }
            }
        }
    }
}

/** 四态微槽方块颜色/边框/前景配置。 */
private data class SlotBlockLook(
    val bg: Color,
    val border: Color,
    val borderDashed: Boolean,
    val fg: Color,
    val overlayText: String?,
    val shimmer: Boolean,
)

@Composable
private fun MicroSlotBlock(slot: DownloadSlot, size: Dp, onClick: () -> Unit) {
    val tooltipState = rememberTooltipState(isPersistent = false)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(
                        text = slot.pkgName ?: "waiting…",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (slot.status == SlotStatus.DLING || slot.status == SlotStatus.DONE) {
                        val got = slot.bytesGot
                        val tot = slot.bytesTotal
                        val bps = slot.speedBps
                        Text(
                            text = buildString {
                                append(formatBytesShort(got))
                                if (tot != null && tot > 0L) append(" / ${formatBytesShort(tot)}")
                                if (bps > 0f) append(" · ${formatBps(bps)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (slot.status == SlotStatus.FAILED) {
                        Text(
                            text = slot.failReason?.take(32) ?: "预取失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        state = tooltipState,
    ) {
        val fill = (slot.bytesTotal?.let { t ->
            if (t <= 0L) 0.25f else slot.bytesGot.toFloat() / t
        } ?: when (slot.status) {
            SlotStatus.DLING -> 0.25f
            SlotStatus.DONE -> 1f
            SlotStatus.FAILED -> 1f
            SlotStatus.WAITING -> 0f
        }).coerceIn(0f, 1f)

        val baseLook = when (slot.status) {
            SlotStatus.WAITING -> {
                // WAITING：alpha 呼吸（0.3↔0.5）无限循环
                val breathing = rememberInfiniteTransition(label = "wait_breath")
                val alpha by breathing.animateFloat(
                    initialValue = 0.3f, targetValue = 0.55f,
                    animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                    label = "wait_alpha",
                )
                SlotBlockLook(
                    bg = Color(0xFFE0E0E0).copy(alpha = alpha),
                    border = Color(0xFF9E9E9E),
                    borderDashed = true,
                    fg = Color.Transparent,
                    overlayText = null,
                    shimmer = false,
                )
            }
            SlotStatus.DLING -> SlotBlockLook(
                bg = COL_DOWNLOAD.copy(alpha = 0.15f), // 仅底色，填充在 Canvas 底部窄条
                border = COL_DOWNLOAD,
                borderDashed = false,
                fg = Color.Transparent,
                overlayText = null,
                shimmer = true,
            )
            SlotStatus.DONE -> SlotBlockLook(
                bg = COL_INSTALL,
                border = COL_INSTALL,
                borderDashed = false,
                fg = Color.White,
                overlayText = "✓",
                shimmer = false,
            )
            SlotStatus.FAILED -> {
                // FAILED：pulse 红闪（1 次，key=slot.id+time 切换就重触发）
                val pulseAlpha = remember { Animatable(1f) }
                LaunchedEffect(slot.id, slot.status) {
                    pulseAlpha.snapTo(1f)
                    runCatching {
                        pulseAlpha.animateTo(
                            targetValue = 0.6f,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                        )
                    }
                }
                SlotBlockLook(
                    bg = COL_FAILED.copy(alpha = pulseAlpha.value),
                    border = COL_FAILED,
                    borderDashed = false,
                    fg = Color.White,
                    overlayText = "✗",
                    shimmer = false,
                )
            }
        }

        val scope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(3.dp))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = {
                        scope.launch {
                            @OptIn(ExperimentalMaterial3Api::class)
                            runCatching { tooltipState.show() }
                        }
                    },
                )
                .background(baseLook.bg, RoundedCornerShape(3.dp))
                .then(
                    if (baseLook.borderDashed) {
                        Modifier.dashedBorder(
                            width = 1.dp, color = baseLook.border, shape = RoundedCornerShape(3.dp),
                        )
                    } else {
                        Modifier.border(width = 1.dp, color = baseLook.border, shape = RoundedCornerShape(3.dp))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A：底部 1dp 窄条（精确进度），不做整块填充
            if (slot.status == SlotStatus.DLING || slot.status == SlotStatus.DONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fill)
                        .height(1.dp)
                        .background(
                            color = if (slot.status == SlotStatus.DONE) Color.White else Color(0xFF64B5F6),
                            shape = RoundedCornerShape(0.5.dp),
                        ),
                )
            }
            // Shimmer overlay 仅 DLING
            if (baseLook.shimmer && slot.status == SlotStatus.DLING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shimmerOverlay(
                            Color.White.copy(alpha = 0.05f),
                            Color.White.copy(alpha = 0.30f),
                        ),
                )
            }
            // 左上浅蓝三角：标记「apk 自己 fetch，非预取」
            if (slot.isApkNativeFallback && slot.status == SlotStatus.DLING) {
                TriangleTopStart(tint = Color(0xFF81D4FA), size = size / 2.2f)
            }
            // Overlay 文本（✓/✗）
            if (baseLook.overlayText != null) {
                val anim = remember { Animatable(0.7f) }
                LaunchedEffect(slot.id, slot.status) {
                    runCatching {
                        anim.animateTo(
                            1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        )
                    }
                }
                Text(
                    text = baseLook.overlayText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (size.value * 0.58f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = baseLook.fg,
                    modifier = Modifier.scale(anim.value),
                )
            }
        }
    }
}

@Composable
private fun TriangleTopStart(tint: Color, size: Dp) {
    val px = with(LocalDensity.current) { size.toPx() }
    Canvas(modifier = Modifier.size(size)) {
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(px, 0f)
                lineTo(0f, px)
                close()
            },
            color = tint,
        )
    }
}

// ─────────────────────── 通用 shimmer 工具（InfiniteTransition + drawBehind 渐变平移） ───────────────────────

private fun Modifier.shimmerOverlay(startColor: Color, endColor: Color): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val shift by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_shift",
    )
    this.then(
        Modifier.drawBehind {
            val w = this.size.width
            val h = this.size.height
            val brush = Brush.linearGradient(
                colors = listOf(startColor, endColor, startColor),
                start = androidx.compose.ui.geometry.Offset(x = -w, y = 0f),
                end = androidx.compose.ui.geometry.Offset(x = w * 2f, y = 0f),
            )
            val dx = w * shift
            drawRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(x = dx, y = 0f),
                size = androidx.compose.ui.geometry.Size(width = w * 2f, height = h),
            )
        }
    )
}

// ─────────────────────── dashed border 工具（drawBehind + drawLine + PathEffect.dashPathEffect） ───────────────────────

private fun Modifier.dashedBorder(width: Dp, color: Color, shape: Shape, onDp: Dp = 4.dp, offDp: Dp = 2.dp): Modifier = composed {
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val px = with(density) { width.toPx() }
    val onPx = with(density) { onDp.toPx() }
    val offPx = with(density) { offDp.toPx() }
    this.then(
        Modifier.drawBehind {
            val w = this.size.width
            val h = this.size.height
            val half = px / 2f
            val stroke = Stroke(
                width = px,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(onPx, offPx), 0f),
                cap = StrokeCap.Round,
            )
            // top
            drawLine(
                color = color,
                start = Offset(half, half),
                end = Offset(w - half, half),
                strokeWidth = stroke.width,
                pathEffect = stroke.pathEffect,
                cap = stroke.cap,
            )
            // bottom
            drawLine(
                color = color,
                start = Offset(half, h - half),
                end = Offset(w - half, h - half),
                strokeWidth = stroke.width,
                pathEffect = stroke.pathEffect,
                cap = stroke.cap,
            )
            // left
            drawLine(
                color = color,
                start = Offset(half, half),
                end = Offset(half, h - half),
                strokeWidth = stroke.width,
                pathEffect = stroke.pathEffect,
                cap = stroke.cap,
            )
            // right
            drawLine(
                color = color,
                start = Offset(w - half, half),
                end = Offset(w - half, h - half),
                strokeWidth = stroke.width,
                pathEffect = stroke.pathEffect,
                cap = stroke.cap,
            )
        }
    )
}

// ─────────────────────── L2 + L3 文案组装 ───────────────────────

private fun buildTimeRowText(agg: AggregateProgressState?): String {
    if (agg == null) return ""
    val elapsedSec = agg.elapsedMs / 1000f
    val sb = StringBuilder("⏱ ").append(String.format("%.1fs", elapsedSec))
    val eta = agg.etaMs
    if (eta != null) {
        val s = eta / 1000f
        if (s in 0f..3_600f) sb.append(" / ⏳ ~").append(String.format("%.0fs", s))
    }
    return sb.toString()
}

// ─────────────────────── C 方向 L3 TokenizedStatusLine（彩色 Chip 组合 + Crossfade） ───────────────────────

@Composable
private fun TokenizedStatusLine(
    bundle: UiBundle,
    state: BundleInstallState,
    agg: AggregateProgressState?,
) {
    // phase 切 Crossfade
    Crossfade(
        targetState = agg?.phase?.name ?: state.javaClass.simpleName,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "statusline_crossfade_${bundle.id.stableKey}",
    ) { _ ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                agg == null -> {
                    // 旧通路 fallback：最小化 Chip 呈现
                    val (color, icon, text) = when (state) {
                        is BundleInstallState.Installing -> Triple(
                            Color(0xFF1976D2), FeatherIcons.Download,
                            state.line?.take(40) ?: "准备安装 ${bundle.title}…",
                        )
                        BundleInstallState.Uninstalling -> Triple(
                            Color(0xFFC62828), FeatherIcons.Trash2, "正在卸载…",
                        )
                        is BundleInstallState.Installed -> Triple(
                            Color(0xFF2E7D32), FeatherIcons.Check,
                            "${bundle.title} 已就绪 (v${state.installedVersion})",
                        )
                        is BundleInstallState.Failed -> Triple(
                            Color(0xFFC62828), FeatherIcons.AlertTriangle,
                            state.reason.take(40),
                        )
                        BundleInstallState.NotInstalled -> Triple(
                            MaterialTheme.colorScheme.onSurfaceVariant, FeatherIcons.Package,
                            "点击「安装」开始",
                        )
                    }
                    SmallRoundedChip(icon = icon, text = text, bg = color.copy(alpha = 0.08f), fg = color)
                }
                agg.phase == InstallPhase.DOWNLOAD -> {
                    // Icon
                    Icon(
                        imageVector = FeatherIcons.Download,
                        contentDescription = null,
                        tint = Color(0xFF1976D2),
                        modifier = Modifier.size(14.dp),
                    )
                    // 速率 pill
                    val bps = agg.currentSpeedBps
                    if (bps >= 1024f) {
                        SmallRoundedChip(
                            text = formatBps(bps),
                            bg = Color(0xFFE3F2FD), fg = Color(0xFF1565C0),
                        )
                    } else {
                        SmallRoundedChip(text = "下载中", bg = Color(0xFFE3F2FD), fg = Color(0xFF1565C0))
                    }
                    // 槽小点组（5 小圆点，绿=DONE / 蓝=DLING / 灰=WAITING）
                    if (agg.slots.isNotEmpty()) {
                        val dots = agg.slots.take(8)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dots.forEach { s ->
                                val c = when (s.status) {
                                    SlotStatus.DONE -> Color(0xFF2E7D32)
                                    SlotStatus.DLING -> Color(0xFF1976D2)
                                    SlotStatus.FAILED -> Color(0xFFC62828)
                                    SlotStatus.WAITING -> Color(0xFFBDBDBD)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(c),
                                )
                            }
                        }
                    }
                    // 末尾包名 pill（灰）
                    val last = agg.logLines.lastOrNull { it.kind == LogLineKind.FETCH }
                    if (last != null) {
                        val s = last.text.take(28)
                        SmallRoundedChip(
                            text = s,
                            bg = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            fg = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                agg.phase == InstallPhase.INSTALL -> {
                    Icon(
                        imageVector = FeatherIcons.Settings,
                        contentDescription = null,
                        tint = Color(0xFF388E3C),
                        modifier = Modifier.size(14.dp),
                    )
                    val total = agg.installingTotal
                    if (total > 0) {
                        SmallRoundedChip(
                            text = "(${agg.installingDone}/$total) Installing",
                            bg = Color(0xFFE8F5E9), fg = Color(0xFF2E7D32),
                        )
                    } else {
                        SmallRoundedChip(text = "安装阶段…", bg = Color(0xFFE8F5E9), fg = Color(0xFF2E7D32))
                    }
                    agg.installingCurrent?.let { cur ->
                        SmallRoundedChip(
                            text = cur.take(20),
                            bg = Color(0xFFE0F2F1), fg = Color(0xFF00695C),
                        )
                    }
                }
                agg.phase == InstallPhase.POST_HOOK -> {
                    Icon(
                        imageVector = FeatherIcons.Settings,
                        contentDescription = null,
                        tint = Color(0xFFF57C00),
                        modifier = Modifier.size(14.dp),
                    )
                    // L3 后处理：显示 hook 日志数；若没提供 postHook 信息则只显示「后处理中」文本
                    val hookDone = agg.logLines.count { l ->
                        l.kind == LogLineKind.POST_HOOK
                    }
                    val hookTotal = hookDone + 1 // 保守：表示至少还剩 1 条，避免 0/0
                    if (hookDone > 0) {
                        SmallRoundedChip(
                            text = "$hookDone/?",
                            bg = Color(0xFFFFF3E0), fg = Color(0xFFE65100),
                        )
                    }
                    SmallRoundedChip(
                        text = "后处理脚本…",
                        bg = Color(0xFFFFF3E0), fg = Color(0xFFE65100),
                    )
                }
                agg.phase == InstallPhase.DONE -> {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(14.dp),
                    )
                    SmallRoundedChip(
                        text = "已就绪",
                        bg = Color(0xFFE8F5E9), fg = Color(0xFF2E7D32),
                    )
                    val st = agg.finishStats
                    if (st != null) {
                        SmallRoundedChip(
                            text = "${st.packagesInstalled} 包",
                            bg = Color(0xFFE0F2F1), fg = Color(0xFF00695C),
                        )
                        SmallRoundedChip(
                            text = String.format("%.1fs", st.elapsedMs / 1000f),
                            bg = Color(0xFFF3E5F5), fg = Color(0xFF6A1B9A),
                        )
                    }
                }
                agg.phase == InstallPhase.FAILED -> {
                    Icon(
                        imageVector = FeatherIcons.AlertTriangle,
                        contentDescription = null,
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(14.dp),
                    )
                    SmallRoundedChip(
                        text = "失败",
                        bg = Color(0xFFFFEBEE), fg = Color(0xFFC62828),
                    )
                    SmallRoundedChip(
                        text = agg.failSummary?.take(28) ?: "安装失败",
                        bg = Color(0xFFFFEBEE), fg = Color(0xFFB71C1C),
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallRoundedChip(
    text: String,
    bg: Color,
    fg: Color,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(100)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 12.sp,
            ),
            color = fg,
        )
    }
}

private fun formatBps(bps: Float): String {
    val mb = bps / (1024f * 1024f)
    val kb = bps / 1024f
    return when {
        mb >= 1.0f -> String.format("%.1f MB/s", mb)
        kb >= 1.0f -> String.format("%.0f KB/s", kb)
        else -> String.format("%.0f B/s", bps)
    }
}
