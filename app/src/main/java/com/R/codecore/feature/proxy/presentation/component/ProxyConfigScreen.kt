package com.R.codecore.feature.proxy.presentation.component
import androidx.compose.ui.res.stringResource
import com.R.codecore.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.proxy.domain.ProxyGroupInfo
import com.R.codecore.feature.proxy.domain.ProxyNodeInfo
import com.R.codecore.feature.proxy.domain.ProxySubscription
import com.R.codecore.feature.proxy.domain.ProxyTraffic
import com.R.codecore.feature.proxy.presentation.ProfileNodesView
import com.R.codecore.feature.proxy.presentation.ProxyGroupsView
import com.R.codecore.feature.proxy.presentation.ProxyPreview
import com.R.codecore.feature.proxy.presentation.ProxyViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入来源模式。 */
private enum class ImportMode { SUBSCRIPTION, MANUAL, FILE }

private const val FIXED_OVERRIDE_HINT =
    "external-controller 127.0.0.1:9090 · secret 随机会话 · mode rule · 内网 DIRECT 兜底" +
        "external-controller 127.0.0.1:9090 · secret 随机会话 · mode rule · 内网 DIRECT 兜底"

/**
 * 网络代理配置/导入页：管理已播种 profile、导入订阅/手动/文件、预检、开关。
 * 日常切节点/测速/监控由模型驱动 `network_proxy` 工具负责，本页只做播种 + 开关（§11）。
 */
@Composable
fun ProxyConfigScreen(
    viewModel: ProxyViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNodes: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val nodesView by viewModel.profileNodes.collectAsStateWithLifecycle()
    val groupsView by viewModel.groups.collectAsStateWithLifecycle()

    var expandedId by remember { mutableStateOf<String?>(null) }
    var expandedTab by remember { mutableStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            if (msg.isNotBlank()) snackbarHostState.showSnackbar(msg)
        }
    }

    var showImport by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.ui______d3ec5010),
                onNavigateBack = onNavigateBack,
                navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                navigationContentDescription = stringResource(R.string.ui____5f411223)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item {
                    MasterToggle(
                        enabled = enabled,
                        activeProfileId = activeProfileId,
                        onToggle = viewModel::toggleEnabled
                    )
                }

                // 独立入口：节点管理整页（分组/节点/状态/测速/切换 一站式操作）。
                item {
                    NodesEntryCard(
                        enabled = enabled,
                        profileCount = profiles.size,
                        onClick = onNavigateToNodes
                    )
                }

                item {
                    Button(
                        onClick = { showImport = !showImport },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (showImport) stringResource(R.string.ui________8e335748) else "导入配置（订阅 / 手动 / 文件）") }
                }

                if (showImport) {
                    item {
                        ImportEditor(
                            preview = preview,
                            onPreview = viewModel::runPreview,
                            onCommit = { name, kind, secret, enableNow ->
                                viewModel.commitProfile(name, kind, secret, enableNow)
                                showImport = false
                            }
                        )
                    }
                }

                item { OverrideCard() }

                if (profiles.isEmpty()) {
                    item {
                        Text(
                            text = "还没有已保存的配置。先「导入配置」播种一次，之后模型可用 network_proxy 接管启用/切节点/测速。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Text(
                        text = "已保存配置（${profiles.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(profiles, key = { it.id }) { p ->
                    ProfileRow(
                        profile = p,
                        isActive = p.id == activeProfileId,
                        isEnabled = enabled,
                        expanded = expandedId == p.id,
                        expandedTab = expandedTab,
                        nodesView = nodesView?.takeIf { it.profileId == p.id },
                        groupsView = groupsView?.takeIf { it.profileId == p.id },
                        onActivate = { viewModel.activate(p.id) },
                        onDelete = { viewModel.delete(p.id) },
                        onToggleExpand = {
                            if (expandedId == p.id) {
                                expandedId = null
                                viewModel.closeInspect()
                                viewModel.closeGroups()
                            } else {
                                expandedId = p.id
                                expandedTab = 0
                                viewModel.inspectProfile(p.id)
                            }
                        },
                        onTabChange = { t ->
                            if (t == 1 && expandedTab != 1) viewModel.openGroups(p.id)
                            if (t == 0 && expandedTab == 1) viewModel.closeGroups()
                            expandedTab = t
                        },
                        onTestLatency = { viewModel.testProfileLatency(p.id) },
                        onSelectGroupNode = { group, node ->
                            viewModel.selectGroupNode(group, node)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterToggle(
    enabled: Boolean,
    activeProfileId: String?,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) stringResource(R.string.ui_______4d1f56d6) else stringResource(R.string.ui_______b5fb1ee7),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = activeProfileId?.let { "活跃配置：#$it" } ?: stringResource(R.string.ui________ae157e74),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** 节点管理独立页入口卡：分组 / 节点 / 状态 / 测速 / 切换 一站式。 */
@Composable
private fun NodesEntryCard(
    enabled: Boolean,
    profileCount: Int,
    onClick: () -> Unit
) {
    val enabledClick = profileCount > 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabledClick, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui______b26d228a),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        profileCount == 0 -> stringResource(R.string.ui_______98d7e010)
                        enabled -> stringResource(R.string.ui____d934fb06)
                        else -> stringResource(R.string.ui_______20fa5aa5)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImportEditor(
    preview: ProxyPreview?,
    onPreview: (String?, String?) -> Unit,
    onCommit: (String, String, String, Boolean) -> Unit
) {
    var mode by remember { mutableStateOf(ImportMode.SUBSCRIPTION) }
    var nameField by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf("") }
    var yamlField by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                busy = false
                if (text.isNullOrBlank()) return@launch
                mode = ImportMode.MANUAL
                yamlField = text
                if (nameField.isBlank()) {
                    nameField = (uri.lastPathSegment ?: "").substringAfterLast('/').ifBlank { "file" }
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.ui______04522145),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ModeChip(stringResource(R.string.ui____701515e9), selected = mode == ImportMode.SUBSCRIPTION, enabled = !busy) { mode = ImportMode.SUBSCRIPTION }
                ModeChip(stringResource(R.string.ui____601a29b5), selected = mode == ImportMode.MANUAL, enabled = !busy) { mode = ImportMode.MANUAL }
                ModeChip(stringResource(R.string.ui_______d9a6706f), selected = false, enabled = !busy) { filePicker.launch("*/*") }
            }

            Spacer(Modifier.height(Spacing.sm))

            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                label = { Text(stringResource(R.string.ui____8d9d1723)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(Spacing.sm))

            when (mode) {
                ImportMode.SUBSCRIPTION -> {
                    OutlinedTextField(
                        value = urlField,
                        onValueChange = { urlField = it },
                        label = { Text(stringResource(R.string.ui____3208b62d)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { onPreview(urlField.trim(), null) },
                        enabled = urlField.isNotBlank() && !busy
                    ) { Text(stringResource(R.string.ui____bb872a0c)) }
                    SaveHint()
                }
                ImportMode.MANUAL -> {
                    OutlinedTextField(
                        value = yamlField,
                        onValueChange = { yamlField = it },
                        label = { Text(stringResource(R.string.ui______f388b5eb)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { onPreview(null, yamlField) },
                        enabled = yamlField.isNotBlank() && !busy
                    ) { Text(stringResource(R.string.ui____bb872a0c_2)) }
                    SaveHint()
                }
                ImportMode.FILE -> {
                    Text(
                        text = stringResource(R.string.ui_____24992b8d),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            preview?.let { p ->
                Spacer(Modifier.height(Spacing.sm))
                PreviewPanel(preview = p)
            }

            preview?.takeIf { it.ok }?.let { p ->
                Spacer(Modifier.height(Spacing.md))
                val isSubscription = !urlField.isBlank()
                val kind = if (isSubscription) ProxySubscription.KIND_SUBSCRIPTION else ProxySubscription.KIND_MANUAL
                val secret = if (isSubscription) urlField.trim() else yamlField
                val resolvedSource = !p.resolvedYaml.isBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        enabled = resolvedSource && !busy,
                        onClick = { onCommit(nameField, kind, secret, false) }
                    ) { Text(stringResource(R.string.ui_____ee99388d)) }
                    Button(
                        enabled = resolvedSource && !busy,
                        onClick = { onCommit(nameField, kind, secret, true) }
                    ) { Text(stringResource(R.string.ui_______3eb4be1b)) }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 预检按钮下方的保存提示：避免用户找不到只在预检通过后出现的保存按钮。 */
@Composable
private fun SaveHint() {
    Text(
        text = stringResource(R.string.ui___________717dcdaa),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PreviewPanel(preview: ProxyPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                (if (preview.ok) Color(0xFF2E7D32) else Color(0xFFC62828)).copy(alpha = 0.12f),
                RoundedCornerShape(8.dp)
            )
            .padding(Spacing.sm)
    ) {
        Text(
            text = preview.summary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        preview.warnings.forEach { w ->
            Text(
                text = w,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun OverrideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = stringResource(R.string.ui_______79cf38fa),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = FIXED_OVERRIDE_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ProxySubscription,
    isActive: Boolean,
    isEnabled: Boolean,
    expanded: Boolean,
    expandedTab: Int,
    nodesView: ProfileNodesView?,
    groupsView: ProxyGroupsView?,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onToggleExpand: () -> Unit,
    onTabChange: (Int) -> Unit,
    onTestLatency: () -> Unit,
    onSelectGroupNode: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name.ifBlank { profile.id },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(Spacing.sm))
                if (isActive) {
                    Text(
                        text = stringResource(R.string.ui____fe32def4),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = when (profile.kind) {
                    ProxySubscription.KIND_SUBSCRIPTION -> stringResource(R.string.ui____adfb869d)
                    ProxySubscription.KIND_MANUAL -> stringResource(R.string.ui____9c4530f5)
                    else -> "来源：${profile.kind}"
                } + (if (isEnabled && isActive) stringResource(R.string.ui_____0ddc086b) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onActivate, enabled = !isActive) {
                    Text(if (isActive) stringResource(R.string.ui____fe32def4_2) else stringResource(R.string.ui______f67c4924))
                }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.ui____2f4aaddd)) }
                OutlinedButton(onClick = onToggleExpand) {
                    Text(if (expanded) stringResource(R.string.ui____def9e98b_2) else stringResource(R.string.ui______47434b27))
                }
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TabChip(stringResource(R.string.ui______ce9e92bd), selected = expandedTab == 0) { onTabChange(0) }
                    TabChip(stringResource(R.string.ui____76992f37), selected = expandedTab == 1) { onTabChange(1) }
                }
                Spacer(Modifier.height(Spacing.sm))
                when (expandedTab) {
                    0 -> NodeListView(view = nodesView, onTestLatency = onTestLatency)
                    1 -> GroupsTrafficView(
                        view = groupsView,
                        isActive = isActive,
                        onSelectGroupNode = onSelectGroupNode
                    )
                }
            }
        }
    }
}

/** 展开区内的子 Tab（节点列表 / 分组·流量）。 */
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** 展开区：加载状态 / 节点列表 + 测速状态（对齐 Clash 配置详情）。 */
@Composable
private fun NodeListView(view: ProfileNodesView?, onTestLatency: () -> Unit) {
    when {
        view == null || view.loading -> {
            Text(
                text = stringResource(R.string.ui_______3fd48e2f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        view.error != null -> {
            Text(
                text = view.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        else -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            append("节点 ${view.summary.nodes.size}")
                            append(" · 分组 ${view.summary.groups.size}")
                            if (view.summary.providerCount > 0) {
                                append(" · provider ${view.summary.providerCount}")
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onTestLatency,
                        enabled = view.summary.nodes.isNotEmpty() && !view.testing
                    ) {
                        Text(if (view.testing) stringResource(R.string.ui_____7765b216) else stringResource(R.string.ui____c7f8d9cc))
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                if (view.summary.nodes.isEmpty()) {
                    Text(
                        text = if (view.summary.providerCount > 0) {
                            stringResource(R.string.ui______18fa8b71)
                        } else {
                            stringResource(R.string.ui___________ff57a25f)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(view.summary.nodes) { _, node ->
                                NodeRow(
                                    node = node,
                                    tested = view.latencies.containsKey(node.name),
                                    delay = view.latencies[node.name]
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(node: ProxyNodeInfo, tested: Boolean, delay: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${node.type} · ${node.server}:${node.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LatencyBadge(tested = tested, delayMs = delay)
    }
}

/** 测速状态徽标：未测（灰）→ 延迟 ms（绿）/ 超时（红），对齐 Clash 的节点健康色。 */
@Composable
private fun LatencyBadge(tested: Boolean, delayMs: Long?) {
    val (text, color) = when {
        !tested -> stringResource(R.string.ui____21df949d) to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        delayMs == null -> stringResource(R.string.ui____e944c7c9) to Color(0xFFC62828)
        else -> "${delayMs} ms" to Color(0xFF2E7D32)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

/** 展开区「分组 · 流量」：实时流量卡片 + 分组树（类型/选中项/成员），需代理运行中。 */
@Composable
private fun GroupsTrafficView(
    view: ProxyGroupsView?,
    isActive: Boolean,
    onSelectGroupNode: (String, String) -> Unit
) {
    when {
        view == null || view.loading -> {
            Text(
                text = stringResource(R.string.ui______6f516f09),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        !view.running -> {
            Text(
                text = stringResource(R.string.ui_______e1a5bd7a),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        view.error != null -> {
            Text(
                text = "加载失败：${view.error}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        else -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isActive) {
                    Text(
                        text = stringResource(R.string.ui__________154de4a1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
                TrafficCard(traffic = view.traffic)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "分组树（${view.snapshot?.groups?.size ?: 0}）· 点选切换节点",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        view.snapshot?.groups?.forEach { group ->
                            item(key = group.name) {
                                GroupNodeCard(group = group, onSelectGroupNode = onSelectGroupNode)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 实时流量卡（/traffic WS 推流，零轮询）。 */
@Composable
private fun TrafficCard(traffic: ProxyTraffic?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ui______c1fbcbfb),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (traffic == null) {
                    "—"
                } else {
                    "↓ ${formatSpeed(traffic.down)}/s · ↑ ${formatSpeed(traffic.up)}/s"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 单个分组卡：类型 + 健康检查延迟 + 当前选中项，成员可点选切换（对齐 Clash）。 */
@Composable
private fun GroupNodeCard(group: ProxyGroupInfo, onSelectGroupNode: (String, String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = group.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                group.delay?.let {
                    Text(
                        text = "${it} ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "当前：${group.now ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(Spacing.xs))
            group.all.forEach { member ->
                val selected = member == group.now
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroupNode(group.name, member) }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selected) "● " else "○ ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = member,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Text(
                            text = stringResource(R.string.ui____7bf54e28),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** 字节/秒 → 可读速率（B/KB/MB/s）。 */
private fun formatSpeed(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        b >= 1024 * 1024 -> "%.1f MB/s".format(b / (1024.0 * 1024.0))
        b >= 1024 -> "%.1f KB/s".format(b / 1024.0)
        else -> "%.0f B/s".format(b)
    }
}