package com.core.deepcode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.core.deepcode.R
import com.core.deepcode.core.theme.AppSectionHeader
import com.core.deepcode.core.theme.AppTopAppBar
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.domain.container.ContainerInitState
import com.core.deepcode.feature.agent.domain.container.GlobalInstallArchiveStore
import com.core.deepcode.feature.terminal.data.bundle.BundleInstallState
import com.core.deepcode.feature.terminal.data.bundle.TerminalBundleId
import com.core.deepcode.feature.terminal.presentation.TerminalSettingsViewModel
import com.core.deepcode.feature.terminal.presentation.component.toUi
import kotlinx.coroutines.launch

/**
 * 功能包管理子页面。
 *  - 顶部容器状态卡片
 *  - Tab 菜单：
 *      「预设独立功能包」：AI 推荐组合 + 6 张官方 Bundle 卡片（安装/卸载/进度/失败重试）
 *      「自定义功能包」：常用快捷包 + 自定义包名安装 + 已安装自定义包列表
 * 原「自定义 APK 包」独立页面已废弃，其内容整合到本页 Tab 2。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalBundleManagerScreen(
    viewModel: TerminalSettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val containerInstalled by viewModel.containerInstalled.collectAsStateWithLifecycle()
    val storageUsedMb by viewModel.storageUsedMb.collectAsStateWithLifecycle()
    val bundleStates by viewModel.bundleStates.collectAsStateWithLifecycle()
    val customPkgs by viewModel.customPackages.collectAsStateWithLifecycle()
    val aiAllInstalled by viewModel.aiRecommendedAllInstalled.collectAsStateWithLifecycle()
    val errorToast by viewModel.errorToast.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(errorToast) {
        errorToast?.let {
            scope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long) }
            viewModel.consumeErrorToast()
        }
    }

    // 自定义包安装/卸载状态由全局 containerInit 的 bundleId==null 推导
    //   bundleId != null → 官方 Bundle 操作；bundleId == null → 自定义包操作
    val customInstallState: BundleInstallState? = when (val p = containerInit) {
        is ContainerInitState.BundleInstalling ->
            if (p.bundleId == null) BundleInstallState.Installing(line = p.line) else null
        is ContainerInitState.BundleUninstalling ->
            if (p.bundleId == null) BundleInstallState.Uninstalling else null
        else -> null
    }

    var selectedTab by remember { mutableStateOf(0) }
    var customInstallInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.ui_______dbda4e51),
                onNavigateBack = onNavigateBack,
                navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                navigationContentDescription = stringResource(R.string.common_back),
                actions = {
                    // 手动刷新：从容器真实 apk 世界重新同步 bundle 安装状态（联动检测）
                    IconButton(onClick = viewModel::refreshBundles) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.ui________22653573),
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            item {
                SharedContainerEnvCard(
                    containerInstalled = containerInstalled,
                    initProgress = containerInit,
                    storageUsedMb = storageUsedMb,
                    mode = ContainerCardMode.INIT_ONLY,
                    onInit = viewModel::ensureContainerInstalled
                )
            }

            // Tab 菜单吸附顶部：上滑时容器卡片滚走，Tab 吸顶不随内容滚动
            stickyHeader {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.ui_________b6349a50), fontSize = 14.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.ui________2d661dab), fontSize = 14.sp) }
                    )
                }
            }

            item {
                when (selectedTab) {
                0 -> {
                    // ── Tab 1：预设独立功能包（原功能包管理页内容） ──────────
                    AppSectionHeader(text = stringResource(R.string.ui_ai_5dcf4fe7))
                    SharedAiRecommendationStrip(
                        allInstalled = aiAllInstalled,
                        containerReady = containerInstalled,
                        onInstallAll = viewModel::installAiRecommended
                    )

                    AppSectionHeader(text = stringResource(R.string.ui_______15dd3f3e))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        var openDialogFor by remember { mutableStateOf<TerminalBundleId?>(null) }
                        val agg by viewModel.aggregateProgress.collectAsStateWithLifecycle()
                        val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
                        val ctx = LocalContext.current
                        val onCopyError: (String) -> Unit = { reason ->
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("install-error", reason))
                        }
                        // 判断某个 bundleId 是否为「当前正在进行的会话」
                        fun isBundleInProgress(id: TerminalBundleId): Boolean {
                            val s = containerInit
                            return s is ContainerInitState.BundleInstalling && s.bundleId == id
                        }
                        // 监听 GlobalInstallArchiveStore 的 globalRevision，让"有没有存档"在有新写入时 recompose
                        val archiveRev by remember(GlobalInstallArchiveStore.globalRevision) {
                            mutableStateOf(GlobalInstallArchiveStore.globalRevision)
                        }
                        viewModel.bundles().forEach { b ->
                            val bState = bundleStates[b.id] ?: BundleInstallState.NotInstalled
                            val inProgress = isBundleInProgress(b.id)
                            BundleInstallCard(
                                bundle = b.toUi(),
                                bundleState = bState,
                                aggregate = agg.takeIf { inProgress },
                                onInstallClick = { viewModel.installBundle(b.id) },
                                onUninstallClick = { viewModel.uninstallBundle(b.id) },
                                onOpenLogDialog = { openDialogFor = b.id },
                                onCopyError = onCopyError,
                                modifier = Modifier.padding(horizontal = Spacing.md),
                            )
                            val dialogBundle = openDialogFor
                            if (dialogBundle != null && dialogBundle == b.id) {
                                val isCurrent = isBundleInProgress(b.id)
                                val archiveSnapshot = run {
                                    archiveRev // 强制 snapshot read：revision 变 archive 就重新取
                                    GlobalInstallArchiveStore.getSnapshot(b.id)
                                }
                                BundleLogDialog(
                                    bundle = b.toUi(),
                                    state = agg,
                                    isCurrentSession = isCurrent,
                                    archiveSnapshot = archiveSnapshot,
                                    onDismiss = { openDialogFor = null },
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // ── Tab 2：自定义功能包（原自定义 APK 包页内容整合） ─────
                    AppSectionHeader(text = "常用快捷包")
                    QuickPacksChipRow(
                        containerReady = containerInstalled,
                        customInstallState = customInstallState,
                        installed = customPkgs.toSet(),
                        onInstall = { names ->
                            viewModel.installCustom(names.joinToString(" "))
                        }
                    )

                    AppSectionHeader(text = "自定义包名安装")
                    CustomInstallCard(
                        customInstallInput = customInstallInput,
                        onInputChange = { customInstallInput = it },
                        onInstallClick = {
                            viewModel.installCustom(customInstallInput)
                            customInstallInput = ""
                        },
                        containerReady = containerInstalled,
                        installIdle = customInstallState == null,
                        customInstallState = customInstallState
                    )

                    AppSectionHeader(text = "已安装自定义包")
                    run {
                        val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
                        Card(
                            modifier = Modifier
                                .padding(horizontal = Spacing.md)
                                .fillMaxWidth()
                                .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TerminalCardsSpec.BgSoftAlpha)),
                            elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
                            shape = RoundedCornerShape(Radius.md)
                        ) {
                            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Inventory2,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.sm))
                                    Text(
                                        text = if (customPkgs.isEmpty()) "暂无自定义包" else "共 ${customPkgs.size} 个",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    NeutralTextButton(
                                        onClick = viewModel::refreshCustom,
                                        icon = Icons.Rounded.Refresh,
                                        text = "刷新"
                                    )
                                }
                                when {
                                    !containerInstalled -> {
                                        Text(
                                            "容器未初始化，无法查询自定义包",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    customPkgs.isEmpty() -> {
                                        Text(
                                            "可以从上面的快捷包一键安装，或自己输入 apk 包名。常用：htop / neofetch / tmux / fzf / rsync / lf / openssh",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    else -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                            customPkgs.forEach { pkg ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(DotSize.Chip)
                                                            .background(SemanticColors.Success, shape = RoundedCornerShape(50))
                                                    )
                                                    Spacer(modifier = Modifier.width(Spacing.sm))
                                                    Text(
                                                        text = pkg,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    DangerTextButton(
                                                        onClick = { viewModel.uninstallCustom(pkg) },
                                                        enabled = customInstallState == null,
                                                        icon = Icons.Rounded.Delete,
                                                        text = "卸载"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun CustomInstallCard(
    customInstallInput: String,
    onInputChange: (String) -> Unit,
    onInstallClick: () -> Unit,
    containerReady: Boolean,
    installIdle: Boolean,
    customInstallState: BundleInstallState?
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
        shape = RoundedCornerShape(Radius.md)
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedTextField(
                    value = customInstallInput,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(stringResource(R.string.ui________02fc9934)) }
                )
                PrimaryButton(
                    onClick = onInstallClick,
                    enabled = containerReady && customInstallInput.isNotBlank() && installIdle,
                    icon = Icons.Rounded.Add,
                    text = stringResource(R.string.ui____e655a410)
                )
            }
            if (customInstallState is BundleInstallState.Installing) {
                SharedLinearProgress()
                Text(
                    text = "正在安装：${customInstallState.line?.take(40) ?: "准备中…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.InProgress,
                    fontFamily = FontFamily.Monospace
                )
            } else if (customInstallState is BundleInstallState.Failed) {
                Text(
                    text = "安装失败：${customInstallState.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.Error
                )
            }
            if (!containerReady) {
                Text(
                    text = stringResource(R.string.ui________b9d9ca01),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickPacksChipRow(
    containerReady: Boolean,
    customInstallState: BundleInstallState?,
    installed: Set<String>,
    onInstall: (List<String>) -> Unit
) {
    val quickPacks = remember {
        listOf(
            "htop" to listOf("htop"),
            "neofetch" to listOf("neofetch"),
            "openssh" to listOf("openssh", "openssh-client", "openssh-server"),
            "rsync" to listOf("rsync"),
            "fzf" to listOf("fzf"),
            "tmux" to listOf("tmux"),
            "lf 文件管理器" to listOf("lf"),
            "zsh" to listOf("zsh", "zsh-vcs")
        )
    }
    val processing = customInstallState is BundleInstallState.Installing || customInstallState is BundleInstallState.Uninstalling
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = TerminalCardsSpec.BorderAlpha)
    Card(
        modifier = Modifier
            .padding(horizontal = Spacing.md)
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Radius.md)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = TerminalCardsSpec.Elevation),
        shape = RoundedCornerShape(Radius.md)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                stringResource(R.string.ui______d33d3880),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                quickPacks.forEach { (label, pkgs) ->
                    val already = pkgs.all { it in installed }
                    AssistChip(
                        onClick = { onInstall(pkgs) },
                        enabled = containerReady && !processing && !already,
                        leadingIcon = {
                            when {
                                already -> Icon(
                                    Icons.Default.Check,
                                    null,
                                    modifier = Modifier.size(ButtonSpec.ChipIndicatorSize)
                                )
                                processing -> CircularProgressIndicator(
                                    modifier = Modifier.size(ButtonSpec.ChipIndicatorSize),
                                    strokeWidth = 2.dp
                                )
                                else -> Icon(
                                    Icons.Rounded.Add,
                                    null,
                                    modifier = Modifier.size(ButtonSpec.ChipIndicatorSize)
                                )
                            }
                        },
                        label = { Text(label, fontSize = ButtonSpec.TextFontSize) }
                    )
                }
            }
        }
    }
}
