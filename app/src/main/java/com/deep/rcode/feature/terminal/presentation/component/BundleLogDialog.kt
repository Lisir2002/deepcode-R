package com.deep.rcode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.progress.AggregateProgressState
import com.deep.rcode.feature.agent.domain.container.progress.LogLineKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Search

/**
 * 全量安装日志 Dialog（200 行 RingBuffer + 搜索 + 复制）。
 *
 * 用户点 BundleInstallCard ▾ 打开；搜索支持按包名 / 关键字 substring；点击某行可复制，顶栏一键复制全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleLogDialog(
    bundle: UiBundle,
    state: AggregateProgressState,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "安装日志 · ${bundle.title}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            Text(
                                text = buildHeaderSubtitle(state),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        val ctx = LocalContext.current
                        IconButton(onClick = { copyAll(ctx, state) }) {
                            Icon(
                                FeatherIcons.Copy,
                                contentDescription = "复制全部",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize().padding(vertical = 24.dp, horizontal = 12.dp),
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                // 进度摘要（固定）
                ProgressSummary(state)
                Spacer(modifier = Modifier.height(Spacing.sm))
                // 搜索框
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    leadingIcon = { Icon(FeatherIcons.Search, contentDescription = null) },
                    placeholder = { Text("搜索包名/关键字…") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                // 日志列表（跟随最新 tail）
                val filtered by remember(query, state) {
                    derivedStateOf {
                        val q = query.trim()
                        if (q.isEmpty()) state.logLines
                        else state.logLines.filter { it.text.contains(q, ignoreCase = true) }
                    }
                }
                val lazyState = rememberLazyListState()
                LaunchedEffect(filtered.size) {
                    if (filtered.isNotEmpty()) lazyState.animateScrollToItem(filtered.size - 1)
                }
                LazyColumn(
                    state = lazyState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = Spacing.md)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                ) {
                    items(items = filtered, key = { it.id }) { line ->
                        LogLineRow(line = line)
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSummary(state: AggregateProgressState) {
    val pct = (state.total * 100).toInt().coerceIn(0, 100)
    Column(modifier = Modifier.padding(horizontal = Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${state.phase.name}  $pct%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "⏱ %.1fs / ⏳ %s".format(
                    state.elapsedMs / 1000f,
                    state.etaMs?.let { "${it / 1000}s" } ?: "--",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { state.total.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun LogLineRow(line: com.deep.rcode.feature.agent.domain.container.progress.LogLine) {
    val ctx = LocalContext.current
    val (fg, bg) = when (line.kind) {
        LogLineKind.FETCH -> Color(0xFF1976D2) to Color(0xFF1976D2).copy(alpha = 0.05f)
        LogLineKind.INSTALL_CURR -> Color(0xFF455A64) to Color(0xFFCFD8DC).copy(alpha = 0.4f)
        LogLineKind.INSTALL_OK -> Color(0xFF2E7D32) to Color(0xFFA5D6A7).copy(alpha = 0.35f)
        LogLineKind.INFO -> Color(0xFF616161) to Color.Transparent
        LogLineKind.ERROR -> Color(0xFFC62828) to Color(0xFFEF9A9A).copy(alpha = 0.35f)
        LogLineKind.POST_HOOK -> Color(0xFF6A1B9A) to Color(0xFFCE93D8).copy(alpha = 0.25f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableCopy(text = line.text, ctx)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prefixFor(line.kind),
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = line.text,
            color = fg,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        // 行内微进度条
        if (line.inlineProgress in 0f..1f) {
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { line.inlineProgress },
                modifier = Modifier.width(72.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = fg,
                trackColor = fg.copy(alpha = 0.2f),
            )
        }
        if (line.inlineSpeedBps > 0f) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatBpsShort(line.inlineSpeedBps),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
    }
}

private fun prefixFor(kind: LogLineKind): String = when (kind) {
    LogLineKind.FETCH -> "⬇"
    LogLineKind.INSTALL_CURR -> "⚙"
    LogLineKind.INSTALL_OK -> "✓"
    LogLineKind.INFO -> "ⓘ"
    LogLineKind.ERROR -> "✗"
    LogLineKind.POST_HOOK -> "🔧"
}

private fun formatBpsShort(bps: Float): String {
    val mb = bps / (1024f * 1024f)
    val kb = bps / 1024f
    return when {
        mb >= 1.0f -> String.format("%.1fM", mb)
        kb >= 1.0f -> String.format("%.0fK", kb)
        else -> String.format("%.0f", bps)
    }
}

private fun buildHeaderSubtitle(state: AggregateProgressState): String = buildString {
    append(state.slots.size).append(" 槽并行 · ")
    append(state.installingDone).append('/').append(if (state.installingTotal > 0) state.installingTotal else "?")
        .append(" 包 · ")
    val s = state.currentSpeedBps
    append(if (s >= 1024f) formatBpsShort(s) + "/s" else "速率 0")
}

// ───────────── 复制（行点击复制 / 顶栏复制全部） ─────────────

/** 没有 ripple 的 clickable：行内复制不需要涟漪效果，避免与 Dialog 滚动冲突。
 *  因为内部使用 remember(...)，函数本身必须标 @Composable。 */
@Composable
private fun Modifier.clickableWithoutRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
    )
}

@Composable
private fun Modifier.clickableCopy(text: String, ctx: Context): Modifier =
    this.then(
        Modifier.clickableWithoutRipple {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log-line", text))
        },
    )

private fun copyAll(ctx: Context, state: AggregateProgressState) {
    val text = buildString {
        state.logLines.forEach { line ->
            append(prefixFor(line.kind)).append(' ').appendLine(line.text)
        }
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("install-log", text))
}
