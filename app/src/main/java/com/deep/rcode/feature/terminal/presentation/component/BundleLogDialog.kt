package com.deep.rcode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.domain.container.progress.AggregateProgressState
import com.deep.rcode.feature.agent.domain.container.progress.InstallPhase
import com.deep.rcode.feature.agent.domain.container.progress.LogLine
import com.deep.rcode.feature.agent.domain.container.progress.LogLineKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.Flag
import compose.icons.feathericons.Package
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import kotlinx.coroutines.launch
import java.util.EnumMap

/**
 * 全量安装日志 Dialog（D 方向双管齐下）：
 *  - Filter Chips（FETCH/INSTALL/INFO/ERROR/POST_HOOK 多选 + 每类计数）
 *  - 时间线锚点（4 按钮：①下载 → ②安装 → ③后处理 → ④完成/失败，点即滚到对应 phase 首行）
 *  - ERROR 折叠卡片（默认 2 行红底 Banner，点展开查完整错误详情）
 *  - 新日志高亮（新 append 行在首次 render 的 0.3s 内叠黄色闪一下）
 *  - FETCH 行尾部 mini 进度条 + 速率/ETA chip
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
                            Icon(FeatherIcons.Copy, contentDescription = "复制全部")
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
                Spacer(Modifier.height(Spacing.sm))
                val lazyState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                // D.1 时间线锚点条（①下载 → ②安装 → ③后处理 → ④完成/失败）
                TimelineAnchors(
                    state = state,
                    onJumpToPhase = { idx ->
                        val target = state.indexOfFirstPhase(idx)
                        scope.launch {
                            when {
                                target == null -> Unit
                                target < 0 -> lazyState.animateScrollToItem(0)
                                else -> lazyState.animateScrollToItem(target.coerceAtMost(maxOf(0, state.logLines.size - 1)))
                            }
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.xs))
                // D.2 Filter Chips（5 类多选 + 每类条目计数）
                val enabledKinds = remember { mutableStateSetOf(*LogLineKind.entries.toTypedArray()) }
                FilterChipsRow(state = state, enabledKinds = enabledKinds)
                Spacer(Modifier.height(Spacing.xs))
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
                Spacer(Modifier.height(Spacing.xs))
                // 过滤日志 + tail 滚动
                val filtered = remember(query, enabledKinds, state) {
                    val q = query.trim()
                    state.logLines.filter { line ->
                        (line.kind in enabledKinds) &&
                            (q.isEmpty() || line.text.contains(q, ignoreCase = true))
                    }
                }
                LaunchedEffect(filtered.size) {
                    if (filtered.isNotEmpty() && state.phase.isTerminal.not()) {
                        runCatching { lazyState.animateScrollToItem(filtered.size - 1) }
                    }
                }
                // D.4：已渲染过的 id 集合（用于判定新行高亮 0.3s）
                val renderedIdsRef = remember { mutableStateOf<Set<Long>>(emptySet()) }
                val renderedIds: MutableSet<Long> = object : MutableSet<Long> {
                    private var s: Set<Long> get() = renderedIdsRef.value; set(v) { renderedIdsRef.value = v }
                    override val size get() = s.size
                    override fun isEmpty() = s.isEmpty()
                    override fun containsAll(es: Collection<Long>) = s.containsAll(es)
                    override fun contains(e: Long) = s.contains(e)
                    override fun iterator() = s.toMutableSet().iterator()
                    override fun addAll(es: Collection<Long>) = run { val was = s.size; s = s + es; s.size != was }
                    override fun clear() = run { s = emptySet() }
                    override fun removeAll(es: Collection<Long>) = run { val was = s.size; s = s - es; s.size != was }
                    override fun retainAll(es: Collection<Long>) = run { val before = s; s = s intersect es.toSet(); before != s }
                    override fun add(e: Long) = run { val b = !s.contains(e); if (b) s = s + e; b }
                    override fun remove(e: Long) = run { val b = s.contains(e); if (b) s = s - e; b }
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
                    items(
                        items = filtered,
                        key = { it.id },
                        contentType = { it.kind },
                    ) { line ->
                        val isNew = line.id !in renderedIds
                        if (isNew) renderedIds.add(line.id)
                        LogLineRow(line = line, isNewlyAppended = isNew)
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

/** 在 logLines 里找到某阶段最早出现的 phase 对应行 index（null → 滚到末尾）。 */
private fun AggregateProgressState.indexOfFirstPhase(anchorIdx: Int): Int? {
    val markerPhase = when (anchorIdx) {
        0 -> InstallPhase.DOWNLOAD
        1 -> InstallPhase.INSTALL
        2 -> InstallPhase.POST_HOOK
        3 -> if (phase == InstallPhase.FAILED) InstallPhase.FAILED else InstallPhase.DONE
        else -> return null
    }
    // 用最近 1 次 phase 切换的内部 timestamp 找不到，退而求其次：
    // 用每行 kind 的首次切换当锚点（INSTALL_CURR 行 = INSTALL；POST_HOOK 行 = POST_HOOK；DONE/FAIL 最后一行）
    return when (markerPhase) {
        InstallPhase.DOWNLOAD -> logLines.indexOfFirst { it.kind == LogLineKind.FETCH }.takeIf { it >= 0 }
        InstallPhase.INSTALL -> logLines.indexOfFirst {
            it.kind == LogLineKind.INSTALL_CURR || it.kind == LogLineKind.INSTALL_OK
        }.takeIf { it >= 0 }
        InstallPhase.POST_HOOK -> logLines.indexOfFirst { it.kind == LogLineKind.POST_HOOK }.takeIf { it >= 0 }
        InstallPhase.DONE, InstallPhase.FAILED -> (logLines.size - 1).takeIf { it >= 0 }
        else -> null
    }
}

// ───────────────── D.1 时间线锚点（横滑 + 跟随任务进度自动定位） ─────────────────

@Composable
private fun TimelineAnchors(
    state: AggregateProgressState,
    onJumpToPhase: (Int) -> Unit,
) {
    val p = state.phase
    val labels = listOf("①下载", "②安装", "③后处理", if (p == InstallPhase.FAILED) "④失败" else "④完成")
    val icons = listOf(
        FeatherIcons.Download,
        FeatherIcons.Package,
        FeatherIcons.Settings,
        if (p == InstallPhase.FAILED) FeatherIcons.AlertTriangle else FeatherIcons.Flag,
    )
    // Phase 定义顺序就是 DOWNLOAD → INSTALL → POST_HOOK → DONE → FAILED，用 ordinal 比大小
    val phaseIdx = p.ordinal
    val actives = listOf(
        phaseIdx >= InstallPhase.DOWNLOAD.ordinal,
        phaseIdx >= InstallPhase.INSTALL.ordinal,
        phaseIdx >= InstallPhase.POST_HOOK.ordinal,
        p == InstallPhase.DONE || p == InstallPhase.FAILED,
    )
    // 当前「最新进展」锚点索引（跟随任务进度自动滚动到它）
    val currentIdx = when {
        p == InstallPhase.DONE || p == InstallPhase.FAILED -> 3
        phaseIdx >= InstallPhase.POST_HOOK.ordinal -> 2
        phaseIdx >= InstallPhase.INSTALL.ordinal -> 1
        else -> 0
    }
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // 单枚 Chip 估算宽度 = min 108dp + 4dp spacing → 112dp 为一步，与 modifier.requiredWidthIn 对齐
    val stepDp = 112.dp
    LaunchedEffect(currentIdx) {
        val leftPadPx = with(density) { 8.dp.roundToPx() }
        val targetPx = with(density) { (stepDp * currentIdx).roundToPx() } - leftPadPx
        scope.launch {
            runCatching { scrollState.animateScrollTo(targetPx.coerceAtLeast(0)) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            AssistChip(
                onClick = { onJumpToPhase(i) },
                leadingIcon = { Icon(icons[i], null, modifier = Modifier.size(12.dp)) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        maxLines = 1,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (actives[i]) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    labelColor = if (actives[i]) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = if (actives[i]) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = if (actives[i]) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                // 最长的一枚是「③后处理」：③ + 后/处/理 3 字 ≈ 4 字符 * 14dp ≈ 56dp
                // 再叠加 icon 12dp + iconSpacing 8dp + start pad 12dp + end pad 12dp = 44dp
                // 合计 ≥ 100dp；留 8dp 余量 = 108dp（保证「后处理」「安装」「下载」「完成」全完整显示）
                modifier = Modifier.requiredWidthIn(min = 108.dp),
            )
        }
    }
}

// ───────────────── D.2 Filter Chips 行 + 计数 ─────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    state: AggregateProgressState,
    enabledKinds: MutableSet<LogLineKind>,
) {
    val counts: Map<LogLineKind, Int> = remember(state.logLines.size) {
        val m = EnumMap<LogLineKind, Int>(LogLineKind::class.java)
        state.logLines.forEach { line ->
            m[line.kind] = (m[line.kind] ?: 0) + 1
        }
        m
    }
    // 每个 Chip 可能对应多个 enum（比如 INSTALL = CURR + OK 两类），
    // 之前的 bug：entries 只绑 INSTALL_CURR → 关 Chip 后 INSTALL_OK 还显示！
    data class ChipSpec(
        val kinds: Set<LogLineKind>,
        val label: String,
        val tint: Color,
    )
    val entries = listOf(
        ChipSpec(setOf(LogLineKind.FETCH), "FETCH", Color(0xFF1976D2)),
        ChipSpec(setOf(LogLineKind.INSTALL_CURR, LogLineKind.INSTALL_OK), "INSTALL", Color(0xFF388E3C)),
        ChipSpec(setOf(LogLineKind.INFO), "INFO", Color(0xFF616161)),
        ChipSpec(setOf(LogLineKind.ERROR), "ERROR", Color(0xFFC62828)),
        ChipSpec(setOf(LogLineKind.POST_HOOK), "POST", Color(0xFF6A1B9A)),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { spec ->
            val cnt = spec.kinds.sumOf { counts[it] ?: 0 }
            // 只要该 chip 绑定的任意 kind 还在启用集合里，就视为"部分选中"
            val overlap = spec.kinds intersect enabledKinds
            val enabled = overlap.isNotEmpty()
            // 选中 = 该 chip 的所有 kind 都在 enabledKinds 里（保持原多选语义）
            // 部分选中时显示为未选，方便用户一键"全开启"；若要三态则用 indeterminate，这里保持简单
            val allIn = overlap.size == spec.kinds.size
            FilterChip(
                selected = allIn,
                onClick = {
                    if (allIn) {
                        // 当前全选中 → 全部移除
                        enabledKinds -= spec.kinds
                    } else {
                        // 当前全未选 or 部分选中 → 全部加入
                        enabledKinds += spec.kinds
                    }
                },
                label = {
                    Text(
                        text = "${spec.label} ($cnt)",
                        style = MaterialTheme.typography.labelSmall,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = if (enabled) spec.tint else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = allIn,
                    selectedBorderColor = spec.tint.copy(alpha = 0.5f),
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.requiredWidthIn(min = 90.dp),
            )
        }
    }
}

private fun tintColor(kind: LogLineKind): Color = when (kind) {
    LogLineKind.FETCH -> Color(0xFF1976D2)
    LogLineKind.INSTALL_CURR, LogLineKind.INSTALL_OK -> Color(0xFF388E3C)
    LogLineKind.INFO -> Color(0xFF616161)
    LogLineKind.ERROR -> Color(0xFFC62828)
    LogLineKind.POST_HOOK -> Color(0xFF6A1B9A)
}

// ───────────────── 进度摘要（L1 分色 + 终态强化） ─────────────────

@Composable
private fun ProgressSummary(state: AggregateProgressState) {
    val pct = (state.total * 100).toInt().coerceIn(0, 100)
    val phase = state.phase
    val brush = when (phase) {
        InstallPhase.DONE -> Brush.horizontalGradient(
            listOf(Color(0xFF66BB6A), Color(0xFF2E7D32)),
        )
        else -> Brush.horizontalGradient(
            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
        )
    }
    val isFailed = phase == InstallPhase.FAILED
    Column(modifier = Modifier.padding(horizontal = Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${phase.name}  $pct%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    isFailed -> Color(0xFFC62828)
                    phase == InstallPhase.DONE -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FeatherIcons.Clock,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = " %.1fs".format(state.elapsedMs / 1000f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "⏳ %s".format(state.etaMs?.let { "${it / 1000}s" } ?: "--"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 分色进度条 + 终态强化（DONE 中间✔ / FAILED 中间✖）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isFailed) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                ),
        ) {
            if (phase == InstallPhase.DONE || phase == InstallPhase.FAILED) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = brush,
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.total.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            brush = Brush.horizontalGradient(
                                when (phase) {
                                    InstallPhase.DOWNLOAD -> listOf(Color(0xFF42A5F5), Color(0xFF1976D2))
                                    InstallPhase.INSTALL -> listOf(Color(0xFF66BB6A), Color(0xFF388E3C))
                                    InstallPhase.POST_HOOK -> listOf(Color(0xFFFFB74D), Color(0xFFF57C00))
                                    else -> listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                                },
                            ),
                            shape = RoundedCornerShape(4.dp),
                        ),
                )
            }
            if (phase == InstallPhase.DONE) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(12.dp),
                )
            } else if (phase == InstallPhase.FAILED) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = null,
                    tint = Color(0xFFFFCDD2),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(12.dp),
                )
            }
        }
    }
}

// ───────────────── D.3/D.4/D.5 LogLineRow（新行高亮 + ERROR折叠 + FETCH mini进度） ─────────────────

@Composable
private fun LogLineRow(
    line: LogLine,
    isNewlyAppended: Boolean,
) {
    val ctx = LocalContext.current
    val (fg, bg) = when (line.kind) {
        LogLineKind.FETCH -> Color(0xFF1976D2) to Color(0xFF1976D2).copy(alpha = 0.05f)
        LogLineKind.INSTALL_CURR -> Color(0xFF455A64) to Color(0xFFCFD8DC).copy(alpha = 0.4f)
        LogLineKind.INSTALL_OK -> Color(0xFF2E7D32) to Color(0xFFA5D6A7).copy(alpha = 0.35f)
        LogLineKind.INFO -> Color(0xFF616161) to Color.Transparent
        LogLineKind.ERROR -> Color(0xFFC62828) to Color(0xFFEF9A9A).copy(alpha = 0.35f)
        LogLineKind.POST_HOOK -> Color(0xFF6A1B9A) to Color(0xFFCE93D8).copy(alpha = 0.25f)
    }
    // D.4 新行高亮：Animatable from 1 → 0 叠黄色 0.3s
    val flashAlpha = remember(line.id) { Animatable(if (isNewlyAppended) 1f else 0f) }
    LaunchedEffect(line.id) {
        if (isNewlyAppended) {
            flashAlpha.animateTo(
                0f, animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (line.kind == LogLineKind.ERROR) {
            ErrorRowCard(
                line = line, fg = fg, ctx = ctx,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableCopy(text = line.text, ctx)
                    .background(bg)
                    .then(
                        if (flashAlpha.value > 0.01f) {
                            Modifier.background(Color(0xFFFFF176).copy(alpha = flashAlpha.value * 0.35f))
                        } else Modifier,
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prefixFor(line.kind),
                    color = fg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = line.text,
                    color = fg,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                // D.5 FETCH 行尾部 mini 进度条 + 速率 chip
                if (line.kind == LogLineKind.FETCH && line.inlineProgress in 0f..1f) {
                    Spacer(Modifier.width(6.dp))
                    LinearProgressIndicator(
                        progress = { line.inlineProgress },
                        modifier = Modifier.width(64.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = fg,
                        trackColor = fg.copy(alpha = 0.2f),
                    )
                }
                if (line.inlineSpeedBps > 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatBpsShort(line.inlineSpeedBps),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .background(fg.copy(alpha = 0.08f), RoundedCornerShape(100))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                } else if (line.inlineProgress in 0f..1f) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "%d%%".format((line.inlineProgress * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                    )
                }
            }
        }
    }
}

// ───────────────── D.3 ERROR 折叠卡 ─────────────────

@Composable
private fun ErrorRowCard(line: LogLine, fg: Color, ctx: Context, modifier: Modifier = Modifier) {
    var expanded by remember(line.id) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .clickableCopy(text = line.text, ctx),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FeatherIcons.AlertTriangle, null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) line.text else line.text.take(80) + if (line.text.length > 80) "…" else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFFB71C1C),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = fg,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120)),
            ) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFF7F0000),
                    )
                }
            }
        }
    }
}

// ───────────────── 公共 ─────────────────

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

/** 没有 ripple 的 clickable：行内复制不需要涟漪效果，避免与 Dialog 滚动冲突。 */
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
