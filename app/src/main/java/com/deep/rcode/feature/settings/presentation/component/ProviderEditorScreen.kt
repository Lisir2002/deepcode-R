package com.deep.rcode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deep.rcode.core.theme.Radius
import com.deep.rcode.core.theme.Spacing
import com.deep.rcode.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.deep.rcode.feature.settings.data.remote.ModelTestResult
import com.deep.rcode.feature.settings.data.repository.CompatibilityPolicyRepository
import com.deep.rcode.feature.settings.data.repository.DefaultPolicy
import com.deep.rcode.feature.settings.data.repository.ViewImageUnknownGuardPolicy
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.settings.domain.model.ModelMetadata
import com.deep.rcode.feature.settings.domain.model.ProviderType
import com.deep.rcode.feature.settings.presentation.FetchState
import com.deep.rcode.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import com.deep.rcode.R


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderEditorScreen(
    viewModel: SettingsViewModel,
    initialProvider: AIProviderConfig?,
    onNavigateBack: () -> Unit,
    onSave: (AIProviderConfig) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialProvider?.name ?: "") }
    var apiKey by remember { mutableStateOf(initialProvider?.apiKey ?: "") }
    var baseUrl by remember { mutableStateOf(initialProvider?.baseUrl ?: "") }
    var useFullUrl by remember { mutableStateOf(initialProvider?.useFullUrl ?: false) }
    var useResponseApi by remember { mutableStateOf(initialProvider?.useResponseApi ?: false) }
    var isEnabled by remember { mutableStateOf(initialProvider?.isEnabled ?: true) }
    var type by remember { mutableStateOf(initialProvider?.type ?: ProviderType.OPENAI) }
    val providerId = remember { initialProvider?.id ?: System.currentTimeMillis().toString() }
    val models = remember { mutableStateListOf<String>().apply { addAll(initialProvider?.models ?: emptyList()) } }
    var showAddModelSheet by remember { mutableStateOf(false) }
    var showFetchDialog by remember { mutableStateOf(false) }
    var fetchDialogKey by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    // RC63 ④：当前「能力覆盖」面板正在编辑哪一个模型；null=关闭。
    var capabilityOverrideModel by remember { mutableStateOf<String?>(null) }

    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    /** RC63 ③ 兼容端点全局策略（下拉 + 两个 Switch）。 */
    val defaultPolicy by viewModel.compatibilityDefaultPolicyFlow.collectAsStateWithLifecycle()
    val autoDowngrade by viewModel.autoDowngradeOnSendFailureFlow.collectAsStateWithLifecycle()
    val viewImageGuard by viewModel.viewImageUnknownGuardPolicyFlow.collectAsStateWithLifecycle()
    val modelSnapshot = models.toList()

    /** RC63 ④ 单模型覆盖：ProviderModelRow 每个模型 hasOverride 的即时快照（Flow -> State）。 */
    val overridesMap: Map<String, ModelCapabilityOverrideEntity> by remember(type, models) {
        // 注意 1：下面每一步都显式标注类型，原因是 CI（Kotlin 2.1.20 + AGP 8.9.3）对
        // "combine(List<Flow<Pair<A,B?>>>)" 这种嵌套泛型 + lambda 的推断会失败，
        // 报错 "Cannot infer type for type parameter T / R / B / K / V"，IDE 的 Kotlin 插件反而可以过。
        // 注意 2：必须使用 combine(flowList) { values: Array<T> -> ... } 这种「Flow 列表 + transform」
        // 三参/二参重载，避免 combine(vararg flows: Flow<T>) { ... } 推断不出来。
        val flowsMap: Map<String, kotlinx.coroutines.flow.Flow<ModelCapabilityOverrideEntity?>> =
            models.associateWith { modelId -> viewModel.observeCapabilityOverride(type, modelId) }
        val modelIds: List<String> = flowsMap.keys.toList()
        val flowList: List<kotlinx.coroutines.flow.Flow<ModelCapabilityOverrideEntity?>> =
            modelIds.map { id -> flowsMap.getValue(id) }
        val combined: kotlinx.coroutines.flow.Flow<Map<String, ModelCapabilityOverrideEntity>> =
            kotlinx.coroutines.flow.combine(
                flows = flowList
            ) { values: Array<ModelCapabilityOverrideEntity?> ->
                val out: MutableMap<String, ModelCapabilityOverrideEntity> = linkedMapOf()
                values.forEachIndexed { index, entity ->
                    if (entity != null) out[modelIds[index]] = entity
                }
                out
            }
        combined
    }.collectAsState<Map<String, ModelCapabilityOverrideEntity>>(initial = emptyMap())

    DisposableEffect(Unit) {
        viewModel.resetFetchState()
        viewModel.clearTestResults()
        onDispose {
            viewModel.resetFetchState()
            viewModel.clearTestResults()
        }
    }

    LaunchedEffect(type, modelSnapshot) {
        viewModel.resolveModelMetadata(type, modelSnapshot)
    }

    fun currentConfig() = AIProviderConfig(
        id = providerId,
        name = name.ifEmpty { context.getString(R.string.provider_new) },
        type = type,
        apiKey = apiKey,
        baseUrl = baseUrl.ifBlank { defaultProviderBaseUrl(type) },
        useFullUrl = useFullUrl,
        isEnabled = isEnabled,
        defaultModel = initialProvider?.defaultModel ?: "",
        isActive = initialProvider?.isActive ?: false,
        models = models.toList(),
        selectedModel = initialProvider?.selectedModel ?: "",
        useResponseApi = useResponseApi
    )

    // 新建场景下判断用户是否填写了实质内容：名称、API Key、Base URL 任一非空白，或已添加模型。
    // 全空白时退出不应落库，否则会存入一条名为“新提供商”的空记录。
    fun hasSubstantiveInput(): Boolean =
        initialProvider != null ||
            name.isNotBlank() ||
            apiKey.isNotBlank() ||
            baseUrl.isNotBlank() ||
            models.isNotEmpty()

    fun saveCurrent() {
        if (!hasSubstantiveInput()) return
        onSave(currentConfig())
    }

    fun saveAndNavigateBack() {
        saveCurrent()
        onNavigateBack()
    }

    BackHandler {
        saveAndNavigateBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (initialProvider == null) stringResource(R.string.provider_add) else stringResource(R.string.provider_edit)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = { saveAndNavigateBack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (initialProvider != null) {
                        IconButton(onClick = { onDelete(initialProvider.id) }) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.provider_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = {
                        selectedTab = 1
                        showAddModelSheet = true
                    }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.provider_add_model))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(FeatherIcons.Sliders, contentDescription = stringResource(R.string.provider_config)) },
                    label = { Text(stringResource(R.string.provider_config)) },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(FeatherIcons.Cpu, contentDescription = stringResource(R.string.common_model)) },
                    label = { Text(stringResource(R.string.common_model)) },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.common_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.common_enabled))
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        FilterChip(
                            selected = type == ProviderType.OPENAI,
                            onClick = { type = ProviderType.OPENAI },
                            label = { Text("OpenAI") }
                        )
                        FilterChip(
                            selected = type == ProviderType.ANTHROPIC,
                            onClick = { type = ProviderType.ANTHROPIC },
                            label = { Text("Anthropic") }
                        )
                        FilterChip(
                            selected = type == ProviderType.GEMINI,
                            onClick = { type = ProviderType.GEMINI },
                            label = { Text("Gemini") }
                        )
                    }

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text(defaultProviderBaseUrl(type)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(stringResource(R.string.provider_full_url))
                            Text(
                                if (useFullUrl) stringResource(R.string.provider_full_url_on_desc) else stringResource(R.string.provider_full_url_off_desc),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useFullUrl,
                            onCheckedChange = { useFullUrl = it }
                        )
                    }

                    if (type == ProviderType.OPENAI) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.provider_response_api))
                            Switch(
                                checked = useResponseApi,
                                onCheckedChange = { useResponseApi = it }
                            )
                        }
                    }

                    // ───────────────────────────────────────────────────────
                    // RC63 ③ 兼容端点策略区块（下拉 + 两个开关）
                    // ───────────────────────────────────────────────────────
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
                    Text(
                        text = "兼容端点/未收录模型 · 能力判定策略",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CompatibilityPolicyDropdown(
                        currentPolicy = defaultPolicy,
                        onPolicySelected = { viewModel.setCompatibilityDefaultPolicy(it) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("发送失败自动降级（识图兜底）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "若当前聊天模型返回「不支持 image_url」，自动用识图模型生成文字摘要再重试。关闭后遇到此类错误将直接抛给用户（用于排查）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoDowngrade,
                            onCheckedChange = { viewModel.setAutoDowngradeOnSendFailure(it) }
                        )
                    }
                    ViewImageGuardDropdown(
                        current = viewImageGuard,
                        onChange = { viewModel.setViewImageUnknownGuardPolicy(it) }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // ── 模型管理区 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.provider_models_count, models.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                fetchDialogKey++
                                showFetchDialog = true
                            }
                        ) {
                            Icon(FeatherIcons.DownloadCloud, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.provider_fetch_models))
                        }
                    }

                    // 已添加模型列表
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        models.forEach { model ->
                            ProviderModelRow(
                                model = model,
                                metadata = modelMetadata[model],
                                hasOverride = overridesMap.containsKey(model),
                                testing = model in testing,
                                result = testResults[model],
                                onTest = { viewModel.testModel(currentConfig(), model) },
                                onRemove = {
                                    models.remove(model)
                                    saveCurrent()
                                },
                                onOpenCapabilityOverride = { capabilityOverrideModel = model }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddModelSheet) {
        AddModelSheet(
            existingModels = models,
            onAddModel = { model ->
                if (model !in models) {
                    models.add(model)
                    saveCurrent()
                }
            },
            onDismiss = { showAddModelSheet = false }
        )
    }

    // RC63 ④ 单模型「三能力覆盖」底部面板：点击齿轮按钮时打开。
    val overrideModel = capabilityOverrideModel
    if (overrideModel != null) {
        CapabilityOverrideSheet(
            viewModel = viewModel,
            providerType = type,
            modelId = overrideModel,
            metadata = modelMetadata[overrideModel],
            overrideFlow = viewModel.observeCapabilityOverride(type, overrideModel),
            onDismiss = { capabilityOverrideModel = null }
        )
    }

    // 模型拉取结果弹窗
    if (showFetchDialog) {
        key(fetchDialogKey) {
            FetchModelsDialog(
                fetchState = fetchState,
                modelMetadata = modelMetadata,
                existingModels = models,
                onFetchModels = { viewModel.fetchModels(currentConfig()) },
                onAddModel = { m ->
                    if (m !in models) {
                        models.add(m)
                        saveCurrent()
                    }
                },
                onDismiss = {
                    showFetchDialog = false
                    viewModel.resetFetchState()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelSheet(
    existingModels: List<String>,
    onAddModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var modelName by remember { mutableStateOf("") }
    val trimmedModel = modelName.trim()
    val duplicate = existingModels.any { it == trimmedModel }
    val canAdd = trimmedModel.isNotEmpty() && !duplicate

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.provider_add_model),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text(stringResource(R.string.provider_model_name)) },
                placeholder = { Text(stringResource(R.string.provider_model_name_hint)) },
                singleLine = true,
                isError = duplicate,
                modifier = Modifier.fillMaxWidth()
            )
            if (duplicate) {
                Text(
                    text = stringResource(R.string.provider_model_already_added),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(
                    enabled = canAdd,
                    onClick = {
                        onAddModel(trimmedModel)
                        onDismiss()
                    }
                ) {
                    Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.common_add))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FetchModelsDialog(
    fetchState: FetchState,
    modelMetadata: Map<String, ModelMetadata>,
    existingModels: List<String>,
    onFetchModels: () -> Unit,
    onAddModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val collapsedBrands = remember { mutableStateMapOf<String, Boolean>() }
    
    LaunchedEffect(Unit) {
        // Wait for bottom sheet animation to smooth out before firing network request
        delay(300)
        onFetchModels()
    }
    
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(screenHeight * 0.85f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg)
                        .padding(bottom = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.provider_filter_models_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50)
                    )

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (fetchState) {
                            is FetchState.Loading -> {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(Spacing.md))
                                    Text(stringResource(R.string.provider_fetching), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            is FetchState.Error -> {
                                Text(
                                    stringResource(R.string.provider_fetch_failed, fetchState.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            is FetchState.Success -> {
                                val newOnes = fetchState.models.filter { it !in existingModels && it.contains(searchQuery, ignoreCase = true) }
                                if (newOnes.isEmpty()) {
                                    Text(stringResource(R.string.provider_no_matching_models), style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    // 按品牌分组，分类 header 可折叠。"other" 分组永远在最后，其他按显示名称排序。
                                    val grouped = newOnes.groupBy { m -> modelBrandKey(m) }
                                        .toSortedMap(compareBy<String> { it == "other" }.thenBy { brandDisplayName(context, it) })

                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                                    ) {
                                        grouped.forEach { (brandKey, models) ->
                                            item(key = "header_$brandKey") {
                                                val expanded = collapsedBrands[brandKey] != true
                                                val brandName = brandDisplayName(context, brandKey)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 44.dp)
                                                        .clickable { collapsedBrands[brandKey] = expanded }
                                                        .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "$brandName (${models.size})",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Icon(
                                                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                        contentDescription = if (expanded) stringResource(R.string.provider_collapse_brand, brandName) else stringResource(R.string.provider_expand_brand, brandName),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            if (collapsedBrands[brandKey] != true) {
                                                items(models, key = { "${brandKey}_$it" }) { m ->
                                                    FetchModelRow(
                                                        model = m,
                                                        metadata = modelMetadata[m],
                                                        onAdd = { onAddModel(m) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.provider_please_wait), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun defaultProviderBaseUrl(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "https://api.anthropic.com/"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/"
    else -> "https://api.openai.com/"
}

// ————————————————————————————————————————————————————————————
// RC63 ③：兼容端点策略 & viewImage 守卫 两个下拉选择器
// ————————————————————————————————————————————————————————————

private fun policyDisplayName(p: DefaultPolicy): String = when (p) {
    DefaultPolicy.STRICT -> "严格模式（推荐·默认）—— 和官方收录模型走同一规则，避免 RC62e 那种「全部模型都支持多模态」的副作用"
    DefaultPolicy.HEURISTIC -> "启发式模式（=RC62d）—— 按 probablyVision / probablyTools / probablyReasoning 的名字匹配自动判定"
    DefaultPolicy.LAX -> "宽松模式（=RC62e）—— 所有未收录模型一律三能力开，小白拿不准先试试这个，不行再切回来"
    DefaultPolicy.MANUAL -> "完全手动—— 三能力默认全关，必须在单模型齿轮按钮里手动勾选你要的能力"
}

private fun viewImageGuardDisplayName(p: ViewImageUnknownGuardPolicy): String = when (p) {
    ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL -> "自动兜底识图模型（推荐·默认）—— 聊天模型不能识图时，自动用设置里的「识图专用模型」理解图片再把文本送回去"
    ViewImageUnknownGuardPolicy.FAIL_FAST -> "立即报错并提醒配置 —— 聊天模型不能识图时，直接提示用户去设置里配置专用识图模型或手动覆盖，方便排查"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompatibilityPolicyDropdown(
    currentPolicy: DefaultPolicy,
    onPolicySelected: (DefaultPolicy) -> Unit
) {
    val allPolicies = DefaultPolicy.values()
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = policyDisplayName(currentPolicy),
            onValueChange = { },
            readOnly = true,
            label = { Text("兼容端点 · 能力判定默认策略") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allPolicies.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                policyDisplayName(p).substringAfter("—— ").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onPolicySelected(p)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewImageGuardDropdown(
    current: ViewImageUnknownGuardPolicy,
    onChange: (ViewImageUnknownGuardPolicy) -> Unit
) {
    val all = ViewImageUnknownGuardPolicy.values()
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = viewImageGuardDisplayName(current),
            onValueChange = { },
            readOnly = true,
            label = { Text("viewImage 识图工具守卫策略") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            all.forEach { g ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(if (g == ViewImageUnknownGuardPolicy.FALLBACK_VISION_MODEL) "自动兜底识图模型（推荐）" else "立即报错", fontWeight = FontWeight.SemiBold)
                            Text(
                                viewImageGuardDisplayName(g).substringAfter("—— ").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onChange(g)
                        expanded = false
                    }
                )
            }
        }
    }
}