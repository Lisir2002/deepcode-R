package com.deep.rcode.feature.terminal.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.progress.AggregateProgressState
import com.deep.rcode.feature.agent.domain.container.progress.DownloadSlot
import com.deep.rcode.feature.agent.domain.container.progress.InstallPhase
import com.deep.rcode.feature.agent.domain.container.progress.LogLineKind
import com.deep.rcode.feature.agent.domain.container.progress.ProgressSource
import com.deep.rcode.feature.agent.domain.container.progress.SlotStatus
import com.deep.rcode.feature.terminal.data.bundle.BundleInstallState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
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
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onOpenLogDialog),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            // ── L1 ──
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
                            ProgressBarWithSourceTail(
                                progress = aggregate.total,
                                source = aggregate.source,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // 旧通路 fallback：LinearProgress indeterminate 或 100%
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
            // ── L2 ──
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
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = "展开日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // ── L3 ──
            Text(
                text = buildStatusLine(bundle, bundleState, aggregate),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
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

// ─────────────────────── L1 进度条 + 尾部来源颜色编码 ───────────────────────

@Composable
private fun ProgressBarWithSourceTail(
    progress: Float,
    source: ProgressSource,
    modifier: Modifier = Modifier,
) {
    val sourceColor: Color = when (source) {
        ProgressSource.PREFETCH_MANAGER_CONFIRMED -> Color(0xFF1976D2)  // Blue 700
        ProgressSource.TRAFFIC_STATS_ESTIMATED -> Color(0xFF64B5F6)    // Blue 300
        ProgressSource.APK_STDOUT_CONFIRMED -> Color(0xFF388E3C)       // Green 700
    }
    Box(modifier = modifier.height(6.dp)) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        // 尾部 4dp 条（source color 直涂 + 「估」字角标 —— Blue300 时显示）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight()
                .background(sourceColor, RoundedCornerShape(2.dp)),
        )
        if (source == ProgressSource.TRAFFIC_STATS_ESTIMATED) {
            Text(
                text = "估",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color(0xFF64B5F6),
            )
        }
    }
}

// ─────────────────────── L2 N 微下载槽块 ───────────────────────

@Composable
private fun MicroDownloadSlotsRow(slots: List<DownloadSlot>, modifier: Modifier = Modifier) {
    val size: Dp = when (slots.size) {
        in 0..3 -> 22.dp
        4, 5 -> 18.dp
        6, 7 -> 15.dp
        else -> 12.dp
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        slots.forEach { slot -> MicroSlotBlock(slot = slot, size = size) }
    }
}

/** 四态微槽方块颜色/边框/前景配置。 */
private data class SlotBlockLook(
    val bg: Color,
    val border: Color,
    val fg: Color,
    val overlayText: String?,
)

@Composable
private fun MicroSlotBlock(slot: DownloadSlot, size: Dp) {
    val look = when (slot.status) {
        SlotStatus.WAITING -> SlotBlockLook(
            bg = Color.Transparent,
            border = Color(0xFF9E9E9E), // gray 500
            fg = Color.Transparent,
            overlayText = null,
        )
        SlotStatus.DLING -> {
            val fill = (slot.bytesTotal?.let { t ->
                if (t <= 0L) 0.25f else slot.bytesGot.toFloat() / t
            } ?: 0.25f).coerceIn(0.1f, 1f)
            SlotBlockLook(
                bg = Color(0xFF1976D2).copy(alpha = fill),
                border = Color(0xFF1976D2),
                fg = Color.Transparent,
                overlayText = null,
            )
        }
        SlotStatus.DONE -> SlotBlockLook(
            bg = Color(0xFF388E3C),
            border = Color(0xFF388E3C),
            fg = Color.White,
            overlayText = "✓",
        )
        SlotStatus.FAILED -> SlotBlockLook(
            bg = Color(0xFFD32F2F),
            border = Color(0xFFD32F2F),
            fg = Color.White,
            overlayText = "✗",
        )
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(2.dp))
            .then(
                if (slot.status == SlotStatus.WAITING) {
                    Modifier.border(width = 1.dp, color = look.border, shape = RoundedCornerShape(2.dp))
                } else {
                    Modifier.background(color = look.bg)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (look.overlayText != null) {
            Text(
                text = look.overlayText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (size.value * 0.55f).sp,
                ),
                color = look.fg,
            )
        }
        if (slot.isApkNativeFallback && slot.status == SlotStatus.DLING) {
            // 左上浅蓝三角：标记「apk 自己 fetch，非预取」
            Box(
                modifier = Modifier
                    .size(size / 2)
                    .align(Alignment.TopStart)
                    .background(Color(0xFF81D4FA)),
            )
        }
    }
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

@Composable
private fun buildStatusLine(
    bundle: UiBundle,
    state: BundleInstallState,
    agg: AggregateProgressState?,
): String = buildString {
    if (agg == null) {
        // 旧通路 fallback：尽量复用旧 Installing.line 文本
        when (state) {
            is BundleInstallState.Installing -> {
                append(state.line ?: "准备安装 ${bundle.title}…")
            }
            BundleInstallState.Uninstalling -> append("正在卸载…")
            is BundleInstallState.Installed -> append("✨ ${bundle.title} 已就绪 (v${state.installedVersion})")
            is BundleInstallState.Failed -> append("失败：${state.reason.take(60)}")
            BundleInstallState.NotInstalled -> append("点击「安装」开始")
        }
        return@buildString
    }
    when (agg.phase) {
        InstallPhase.DOWNLOAD -> {
            val s = agg.currentSpeedBps
            if (s >= 1024f) {
                append("⬇ ").append(formatBps(s))
            } else {
                append("⬇ 下载中")
            }
            val dling = agg.slots.count { it.status == SlotStatus.DLING }
            val done = agg.slots.count { it.status == SlotStatus.DONE }
            val total = agg.slots.size
            if (total > 0) {
                append(" · 槽 D=")
                    .append(dling)
                    .append('/')
                    .append(total)
                    .append(' ')
                    .append("✓".repeat(max(0, done.coerceAtMost(8))))
                    .append("·".repeat(max(0, (total - done).coerceAtMost(8))))
            }
            val last = agg.logLines.lastOrNull { it.kind == LogLineKind.FETCH }
            if (last != null) append(' ').append(last.text.take(24))
        }
        InstallPhase.INSTALL -> {
            if (agg.installingTotal > 0) {
                append("⚙ (").append(agg.installingDone).append('/').append(agg.installingTotal).append(')')
                agg.installingCurrent?.let { append(' ').append(it.take(18)) }
            } else {
                append("⚙ 安装阶段…")
                agg.installingCurrent?.let { append(' ').append(it.take(18)) }
            }
        }
        InstallPhase.POST_HOOK -> append("🔧 后处理脚本…")
        InstallPhase.DONE -> {
            val st = agg.finishStats
            if (st == null) append("✨ 已就绪")
            else {
                val sec = st.elapsedMs / 1000f
                append("✨ 已就绪 · 耗时 ").append(String.format("%.1fs", sec))
                    .append(" · ").append(st.packagesInstalled).append(" 包")
            }
        }
        InstallPhase.FAILED -> append("✗ ").append(agg.failSummary?.take(48) ?: "安装失败")
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
