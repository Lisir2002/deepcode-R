package com.core.deepcode.feature.git.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.core.deepcode.R
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.git.domain.model.GitCommit
import com.core.deepcode.feature.git.domain.model.GitFileChange
import com.core.deepcode.feature.git.domain.model.GitGraph
import com.core.deepcode.feature.git.domain.model.GitGraphRef
import com.core.deepcode.feature.git.domain.model.GraphCommit
import com.core.deepcode.feature.git.domain.model.GraphEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tag

@Composable
internal fun LogTab(
    graph: GitGraph,
    expandedCommits: Set<String>,
    commitFiles: Map<String, List<GitFileChange>>,
    loadingCommit: String?,
    graphLoadingMore: Boolean,
    onToggleCommit: (String) -> Unit,
    onFileDiff: (String, String) -> Unit,
    onLoadMore: () -> Unit
) {
    val commits = graph.commits
    if (commits.isEmpty()) {
        EmptyState(stringResource(R.string.git_no_commits))
        return
    }
    // 泳道调色板：按列号循环取色，分支越多颜色越丰富。
    val laneColors = rememberLaneColors(graph.maxLane + 1)
    // 每个提交到其父提交的边列表（按提交索引分组），供 Canvas 绘制连线。
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // Canvas 宽度：每个泳道一列，加左右内边距。
    val laneWidth = 26.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + Spacing.sm * 2
    // 每行高度，用于计算连线纵向跨度（节点居中）。
    val rowHeight = 72.dp
    val listState = rememberLazyListState()
    // 滚到底且还有更多、且不在加载中时触发加载下一页。用 derivedStateOf 避免每帧回调。
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(reachedBottom) {
        if (reachedBottom && graph.hasMore && !graphLoadingMore) onLoadMore()
    }
    val overviewCommits = remember(commits) { commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = Spacing.xl)
    ) {
        item { LogOverview(commits = overviewCommits, expandedCount = expandedCommits.size) }
        item { SectionHeader(stringResource(R.string.git_commit_count, commits.size)) }
        commits.forEachIndexed { index, c ->
            val isExpanded = c.hash in expandedCommits
            item(key = "commit-${c.hash}") {
                GraphCommitRow(
                    commit = c,
                    lane = graph.lanes[c.hash] ?: 0,
                    edges = edgesByCommit[index].orEmpty(),
                    activeTopLanes = graph.activeTopLanes[c.hash].orEmpty(),
                    activeBottomLanes = graph.activeBottomLanes[c.hash].orEmpty(),
                    laneColors = laneColors,
                    canvasWidth = canvasWidth,
                    laneWidth = laneWidth,
                    rowHeight = rowHeight,
                    refs = graph.refs[c.hash].orEmpty(),
                    isExpanded = isExpanded,
                    onToggle = { onToggleCommit(c.hash) }
                )
            }
            if (isExpanded) {
                val files = commitFiles[c.hash]
                when {
                    loadingCommit == c.hash && files == null -> {
                        item(key = "loading-${c.hash}") { LoadingFilesRow(canvasWidth) }
                    }
                    files == null -> Unit
                    files.isEmpty() -> {
                        item(key = "empty-${c.hash}") { EmptyCommitFilesRow(canvasWidth) }
                    }
                    else -> {
                        item(key = "summary-${c.hash}") {
                            CommitFilesSummary(files.size, canvasWidth)
                        }
                        items(
                            items = files,
                            key = { f -> "file-${c.hash}-${f.statusCode}-${f.path}" }
                        ) { file ->
                            CommitFileRow(file, indent = canvasWidth + Spacing.sm, onClick = { onFileDiff(c.hash, file.path) })
                        }
                    }
                }
            }
        }
        // 末尾加载更多指示：hasMore 为真时显示，加载中转圈，否则静态「上拉加载」提示。
        if (graph.hasMore) {
            item(key = "load-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    if (graphLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(R.string.git_load_more_commits),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogOverview(commits: List<GitCommit>, expandedCount: Int) {
    val latest = commits.first()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.git_latest_commit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = latest.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusMetric(stringResource(R.string.git_recent_commit), commits.size, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_expanded), expandedCount, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                DateMetric(latest.date, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DateMetric(date: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.sm),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Text(
                text = date,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.git_latest_date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun GraphCommitRow(
    commit: GraphCommit,
    lane: Int,
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    rowHeight: Dp,
    refs: List<GitGraphRef>,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val isMerge = commit.isMerge
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Surface(
        color = if (isExpanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧拓扑图区域：Canvas 绘制节点 + 上下连线。
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                modifier = Modifier
                    .width(canvasWidth)
                    .fillMaxHeight()
            )
            // 右侧提交信息。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Spacing.lg)
            ) {
                // 引用标签行（分支/标签 pill）。
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(Spacing.xs))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = if (isExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = commit.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = nodeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(Radius.pill)
                            ) {
                                Text(
                                    text = commit.shortHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = nodeColor,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = commit.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = commit.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 拓扑图 Canvas：绘制当前提交的节点圆点 + 分支连线。
 *
 * 绘制顺序（后绘制覆盖先绘制，保证节点压在线上）：
 * 1. 分段竖线：结合 [activeTopLanes] 与 [activeBottomLanes]，对每个泳道按需画全长（0→height）、
 *    上半段（0→centerY）或下半段（centerY→height）竖线，避免在分叉/合并节点处竖线悬空延伸。
 * 2. 跨列连线：画从节点中心到下半段目标列的贝塞尔曲线。
 * 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
 */
@Composable
private fun GraphCanvas(
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    modifier: Modifier = Modifier
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = Spacing.sm.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        // 所有相关泳道（包含 top 和 bottom 的并集）
        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()

        // 跨列边（出边与入边）均在下半段绘制贝塞尔曲线。
        // curveBotLanes 记录被曲线接替下半段竖线的列：仅当该列上半段无竖线（非贯穿通道）时才排除，
        // 避免孤立半段；若该列上半段已有竖线（主干穿过），则保留下半段竖线使其贯穿，曲线叠画在上方。
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        // 1. 分段竖线绘制：精细控制 0->centerY 与 centerY->height
        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx

            if (inTop && inBot) {
                // 贯穿整行
                drawLine(
                    color = color,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            } else if (inTop) {
                // 仅上半段（到达节点终止，或下半段转为斜曲线）
                drawLine(
                    color = color,
                    start = Offset(x, 0f),
                    end = Offset(x, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            } else if (inBot) {
                // 仅下半段（从该节点新分出/延伸）
                drawLine(
                    color = color,
                    start = Offset(x, centerY),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }

        // 2. 跨列连线（均在下半段）：出边从节点中心→目标列底部，入边从目标列底部→节点中心
        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = Path().apply {
                if (edge.isMergeIn) {
                    // 合并入边：父支线从目标列底部弯入本节点中心
                    moveTo(toX, size.height)
                    cubicTo(
                        toX, midY,
                        fromX, midY,
                        fromX, centerY
                    )
                } else {
                    // 出边：本节点从中心向目标列底部分叉
                    moveTo(fromX, centerY)
                    cubicTo(
                        fromX, midY,
                        toX, midY,
                        toX, size.height
                    )
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 3. 节点圆点：合并提交画环形双圈，普通提交画实心圆。
        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = nodeColor,
                radius = nodeRadius / 2f,
                center = Offset(centerX, centerY)
            )
        } else {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

/** 提交引用标签行：当前分支高亮 primary，其余用各分支色。 */
@Composable
private fun RefPills(refs: List<GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        refs.forEach { ref ->
            val isCurrent = ref.isCurrent
            val bg = if (isCurrent) MaterialTheme.colorScheme.primary
                else if (ref.isBranch) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                else if (ref.isBranch) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Surface(
                color = bg,
                shape = RoundedCornerShape(Radius.xs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ref.isRemote) Icons.Rounded.Cloud else if (ref.isBranch) Icons.Rounded.AccountTree else Icons.Rounded.Tag,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = ref.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 泳道调色板：按列数生成一组区分度高的颜色，循环复用。
 * 颜色取自常见 IDE 分支配色，深浅适中以适配明暗主题。
 */
@Composable
private fun rememberLaneColors(count: Int): List<Color> {
    val palette = remember {
        listOf(
            Color(0xFF2563EB), // 蓝
            Color(0xFF16A34A), // 绿
            Color(0xFFD97706), // 琥珀
            Color(0xFF9333EA), // 紫
            Color(0xFF0891B2), // 青
            Color(0xFFDC2626), // 红
            Color(0xFF7C3AED), // 靛
            Color(0xFFCA8A04)  // 金
        )
    }
    return remember(count) {
        (0 until count).map { palette[it % palette.size] }
    }
}

/**
 * 把 [GitGraph.edges] 按来源提交索引分组。[GitRepository.computeLanes] 按提交顺序为每个提交
 * 生成 `parents.size` 条边（根提交 0 条），故 edges 是扁平有序的，按父数累积分组即可重建对应。
 * 返回 `Map<提交索引, List<GraphEdge>>`，供 [GraphCommitRow] 绘制本行连线。
 */
private fun groupEdgesByCommit(graph: GitGraph): Map<Int, List<GraphEdge>> {
    val result = mutableMapOf<Int, List<GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        // 根提交无边；非根提交有 parents.size 条边。
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) {
                list.add(graph.edges[edgeIdx++])
            }
        }
        result[commitIdx] = list
    }
    return result
}

@Composable
private fun CommitFilesSummary(count: Int, indent: Dp) {
    Text(
        text = stringResource(R.string.git_files_changed, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun EmptyCommitFilesRow(indent: Dp) {
    Text(
        text = stringResource(R.string.git_no_files_changed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm)
    )
}

@Composable
private fun LoadingFilesRow(indent: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.git_loading_files),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommitFileRow(file: GitFileChange, indent: Dp, onClick: () -> Unit = {}) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = indent, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(file.statusCode)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(start = indent + 44.dp)
        )
    }
}
