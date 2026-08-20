package com.R.codecore.feature.proxy.presentation.component
import androidx.compose.ui.res.stringResource
import com.R.codecore.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.core.theme.AppEmptyState
import com.R.codecore.core.theme.AppLoadingState
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.proxy.domain.ProxyGroupInfo
import com.R.codecore.feature.proxy.domain.ProxyNodeInfo
import com.R.codecore.feature.proxy.domain.ProxyTraffic
import com.R.codecore.feature.proxy.presentation.ProfileNodesView
import com.R.codecore.feature.proxy.presentation.ProxyGroupsView
import com.R.codecore.feature.proxy.presentation.ProxyViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Zap

/** 页面强调色：青 → 蓝 渐变（对齐 App 级 Cyber 视觉）。 */
private val AccentGradient = Brush.horizontalGradient(
    listOf(Color(0xFF00B894), Color(0xFF0984E3))
)

private val LatencyGood = Color(0xFF2E7D32)
private val LatencyWarn = Color(0xFFF9A825)
private val LatencyBad = Color(0xFFC62828)

/**
 * 节点管理（独立页）：把「分组 / 节点 / 状态 / 测速 / 切换」从配置页的内联展开区提升为
 * 独立整页。数据与 `network_proxy list_proxies / flow / latency` 共用同一 [ProxyViewModel]
 * 链路（manager 的 REST /proxies、WS /traffic、REST /delay），只做展示编排，不引入第二套实现。
 */
@Composable
fun ProxyNodesScreen(
    viewModel: ProxyViewModel,
    onNavigateBack: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val groupsView by viewModel.groups.collectAsStateWithLifecycle()
    val nodesView by viewModel.profileNodes.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val activeName = remember(profiles, activeProfileId) {
        val id = activeProfileId ?: return@remember ""
        profiles.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            if (msg.isNotBlank()) snackbarHostState.showSnackbar(msg)
        }
    }

    // 进入页面：加载活跃配置的节点列表；代理运行中则一并拉取分组 + 流量。
    LaunchedEffect(activeProfileId, enabled) {
        val id = activeProfileId ?: return@LaunchedEffect
        viewModel.inspectProfile(id)
        if (enabled) viewModel.openGroups(id)
    }

    // 离开页面：停掉 /traffic WS 订阅，避免后台泄漏。
    DisposableEffect(Unit) {
        onDispose { viewModel.closeGroups() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.ui______b26d228a_2),
                onNavigateBack = onNavigateBack,
                navigationIcon = FeatherIcons.ArrowLeft,
                navigationContentDescription = stringResource(R.string.ui____5f411223_2),
                actions = {
                    IconButton(onClick = {
                        val id = activeProfileId ?: return@IconButton
                        viewModel.inspectProfile(id)
                        if (enabled) viewModel.openGroups(id)
                    }) {
                        Icon(
                            imageVector = FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.ui____694fc5ef),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            profiles.isEmpty() -> AppEmptyState(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.ui_________cf00a0da),
                subtitle = stringResource(R.string.ui_____94418224)
            )
            activeProfileId == null -> AppEmptyState(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.ui__________8e8c2508),
                subtitle = stringResource(R.string.ui_____bab1bef4)
            )
            else -> NodeManagerContent(
                viewModel = viewModel,
                enabled = enabled,
                reachable = runtime.controllerReachable,
                mode = runtime.mode,
                activeName = activeName,
                groupsView = groupsView,
                nodesView = nodesView,
                tab = tab,
                onTabChange = { tab = it },
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun NodeManagerContent(
    viewModel: ProxyViewModel,
    enabled: Boolean,
    reachable: Boolean,
    mode: String,
    activeName: String,
    groupsView: ProxyGroupsView?,
    nodesView: ProfileNodesView?,
    tab: Int,
    onTabChange: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = groupsView?.snapshot?.groups.orEmpty()
    val nodes = nodesView?.summary?.nodes.orEmpty()
    val latencies = nodesView?.latencies.orEmpty()
    val testing = nodesView?.testing == true

    val filteredGroups = remember(groups, query) {
        groups.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        }
    }
    val filteredNodes = remember(nodes, query) {
        nodes.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.server.contains(query, ignoreCase = true)
        }.sortedWith(
            compareBy({ latencies.containsKey(it.name) }, { latencies[it.name] })
        )
    }

    Column(
        modifier = modifier.padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        StatusHero(
            enabled = enabled,
            reachable = reachable,
            mode = mode,
            activeName = activeName,
            traffic = groupsView?.traffic,
            onEnable = { viewModel.toggleEnabled(true) }
        )

        ToolbarRow(
            tab = tab,
            onTabChange = onTabChange,
            testing = testing,
            canTest = nodes.isNotEmpty(),
            onTestAll = {
                viewModel.activeProfileId.value?.let(viewModel::testProfileLatency)
            }
        )

        NodeSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            resultCount = if (query.isBlank()) null else {
                if (tab == 0) filteredGroups.size else filteredNodes.size
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (tab == 0) {
                GroupsTab(
                    viewModel = viewModel,
                    enabled = enabled,
                    groupsView = groupsView,
                    groups = filteredGroups,
                    query = query,
                    onSelectGroupNode = { g, n -> viewModel.selectGroupNode(g, n) }
                )
            } else {
                NodesTab(
                    nodes = filteredNodes,
                    totalNodes = nodes.size,
                    latencies = latencies,
                    loading = nodesView?.loading == true,
                    error = nodesView?.error,
                    hasProviderNodes = (nodesView?.summary?.providerCount ?: 0) > 0,
                    query = query
                )
            }
        }
    }
}

// ─────────────────────────── 顶部状态卡片 ───────────────────────────

@Composable
private fun StatusHero(
    enabled: Boolean,
    reachable: Boolean,
    mode: String,
    activeName: String,
    traffic: ProxyTraffic?,
    onEnable: () -> Unit
) {
    val dotColor = when {
        enabled && reachable -> Color(0xFF7CFC9B)
        enabled -> Color(0xFFFFC857)
        else -> Color.White.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AccentGradient)
            .padding(Spacing.lg)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (enabled) stringResource(R.string.ui_______4d1f56d6_2) else stringResource(R.string.ui_______b5fb1ee7_2),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "mode · $mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FeatherIcons.Zap,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = activeName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Row {
                Text(
                    text = "↓ ${formatSpeed(traffic?.down)}/s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                Spacer(Modifier.width(Spacing.lg))
                Text(
                    text = "↑ ${formatSpeed(traffic?.up)}/s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    enabled && !reachable -> stringResource(R.string.ui_______950d8300)
                    enabled -> stringResource(R.string.ui_mixed_76b25e4d)
                    else -> stringResource(R.string.ui_______019bfe60)
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f)
            )
            if (!enabled) {
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = onEnable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0984E3)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.ui______06fef6bd), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────── 工具条：分段切换 + 全部测速 ───────────────────────────

@Composable
private fun ToolbarRow(
    tab: Int,
    onTabChange: (Int) -> Unit,
    testing: Boolean,
    canTest: Boolean,
    onTestAll: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SegmentedControl(
            tab = tab,
            onTabChange = onTabChange,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onTestAll,
            enabled = canTest && !testing,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 8.dp)
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_____33aac613))
            } else {
                Text(stringResource(R.string.ui______98322500))
            }
        }
    }
}

@Composable
private fun SegmentedControl(
    tab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(4.dp)
    ) {
        listOf(stringResource(R.string.ui____829abe5a) to 0, stringResource(R.string.ui____3bf3c0a8) to 1).forEach { (label, idx) ->
            val selected = tab == idx
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .then(if (selected) Modifier.background(AccentGradient) else Modifier)
                    .clickable { onTabChange(idx) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ─────────────────────────── 搜索栏 ───────────────────────────

@Composable
private fun NodeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int?
) {
    val hasText = query.isNotEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (!hasText) {
                    Text(
                        text = stringResource(R.string.ui______2cf95afa),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (hasText && resultCount != null) {
                Text(
                    text = "$resultCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────── 分组 Tab ───────────────────────────

@Composable
private fun GroupsTab(
    viewModel: ProxyViewModel,
    enabled: Boolean,
    groupsView: ProxyGroupsView?,
    groups: List<ProxyGroupInfo>,
    query: String,
    onSelectGroupNode: (String, String) -> Unit
) {
    // 用户手动展开前自动展开第一个分组，便于直接上手「点选切换节点」。
    var userToggled by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val expandedSet = if (userToggled) {
        expanded
    } else {
        groups.firstOrNull()?.name?.let { setOf(it) } ?: emptySet()
    }

    when {
        groupsView?.loading == true -> AppLoadingState(stringResource(R.string.ui_______5f2b08e6))
        !enabled || !(groupsView?.running == true) -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.ui_______82079093),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            Button(onClick = { viewModel.toggleEnabled(true) }) { Text(stringResource(R.string.ui________fc797319)) }
        }
        groupsView?.error != null -> Text(
            text = groupsView.error!!,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(Spacing.md)
        )
        groups.isEmpty() -> Text(
            text = if (query.isNotBlank()) stringResource(R.string.ui_________cdfdedf4) else stringResource(R.string.ui___________b04bfd3c),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.md)
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(groups, key = { it.name }) { group ->
                GroupCard(
                    group = group,
                    expanded = group.name in expandedSet,
                    onToggle = {
                        userToggled = true
                        expanded = if (group.name in expanded) expanded - group.name else expanded + group.name
                    },
                    onSelect = { node -> onSelectGroupNode(group.name, node) }
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: ProxyGroupInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        TypeChip(group.type)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "当前：${group.now ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                group.delay?.let { d ->
                    Text(
                        text = "${d} ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = latencyColor(d),
                        modifier = Modifier
                            .background(latencyColor(d).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(Spacing.xs))
                group.all.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        selected = member == group.now,
                        onClick = { onSelect(member) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "●" else "○",
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) {
                Color(0xFF0984E3)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = member,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(
                text = stringResource(R.string.ui____7bf54e28_2),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF0984E3),
                modifier = Modifier
                    .background(Color(0xFF0984E3).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ─────────────────────────── 节点 Tab ───────────────────────────

@Composable
private fun NodesTab(
    nodes: List<ProxyNodeInfo>,
    totalNodes: Int,
    latencies: Map<String, Long?>,
    loading: Boolean,
    error: String?,
    hasProviderNodes: Boolean,
    query: String
) {
    when {
        loading -> AppLoadingState(stringResource(R.string.ui_______3fd48e2f_2))
        error != null -> Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(Spacing.md)
        )
        totalNodes == 0 -> Text(
            text = if (hasProviderNodes) {
                stringResource(R.string.ui________1eb92978)
            } else {
                stringResource(R.string.ui_____________5831cb18)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.md)
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            item {
                NodeStatsBar(total = totalNodes, latencies = latencies)
            }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        text = "没有匹配「$query」的节点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }
            } else {
                items(nodes, key = { it.name }) { node ->
                    NodeRowItem(
                        node = node,
                        tested = latencies.containsKey(node.name),
                        delay = latencies[node.name]
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeStatsBar(total: Int, latencies: Map<String, Long?>) {
    val tested = latencies.count { it.value != null }
    val avg = latencies.values.filterNotNull().average().takeIf { it.isFinite() }?.toLong()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        StatChip(label = stringResource(R.string.ui____3bf3c0a8_2), value = "$total")
        StatChip(label = stringResource(R.string.ui____f7c02897), value = "$tested")
        StatChip(label = stringResource(R.string.ui____33875c86), value = if (avg != null) "$avg ms" else "—")
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun NodeRowItem(
    node: ProxyNodeInfo,
    tested: Boolean,
    delay: Long?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${node.type} · ${node.server}:${node.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        LatencyPill(tested = tested, delayMs = delay)
    }
}

@Composable
private fun LatencyPill(tested: Boolean, delayMs: Long?) {
    val (text, color) = when {
        !tested -> stringResource(R.string.ui____21df949d_2) to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        delayMs == null -> stringResource(R.string.ui____e944c7c9_2) to LatencyBad
        else -> "${delayMs} ms" to latencyColor(delayMs)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun TypeChip(type: String) {
    Text(
        text = type,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ─────────────────────────── 工具函数 ───────────────────────────

private fun latencyColor(delayMs: Long): Color = when {
    delayMs < 150 -> LatencyGood
    delayMs < 300 -> LatencyWarn
    else -> LatencyBad
}

/** 字节/秒 → 可读速率（B/KB/MB/s）。 */
private fun formatSpeed(bytes: Long?): String {
    val b = (bytes ?: 0L).toDouble()
    return when {
        b >= 1024 * 1024 -> "%.1f MB/s".format(b / (1024.0 * 1024.0))
        b >= 1024 -> "%.1f KB/s".format(b / 1024.0)
        else -> "%.0f B/s".format(b)
    }
}
