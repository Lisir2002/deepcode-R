package com.core.deepcode.feature.settings.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.core.deepcode.R
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.settings.domain.model.AIProviderConfig
import com.core.deepcode.feature.settings.domain.model.ProviderType
import com.core.deepcode.feature.settings.presentation.FetchState
import com.core.deepcode.feature.settings.presentation.SettingsViewModel
import java.util.UUID

/** 阶跃星辰兼容协议。 */
enum class StepFunProtocol(val displayName: String) {
    OPENAI("OpenAI 兼容"),
    CLAUDE("Claude 兼容")
}

/** 阶跃星辰通道。 */
enum class StepFunChannel(val displayName: String) {
    STEP_PLAN("Step Plan 通道"),
    STANDARD("标准通道")
}

/** 阶跃星辰端点映射：协议 × 通道 → baseUrl。 */
fun stepFunBaseUrl(protocol: StepFunProtocol, channel: StepFunChannel): String = when (protocol) {
    StepFunProtocol.OPENAI -> when (channel) {
        StepFunChannel.STEP_PLAN -> "https://api.stepfun.com/step_plan/v1"
        StepFunChannel.STANDARD -> "https://api.stepfun.com/v1"
    }
    StepFunProtocol.CLAUDE -> when (channel) {
        StepFunChannel.STEP_PLAN -> "https://api.stepfun.com/step_plan"
        StepFunChannel.STANDARD -> "https://api.stepfun.com"
    }
}

/** 内置供应商枚举：目前仅阶跃星辰。 */
enum class BuiltInProvider(val displayName: String, val description: String) {
    STEPFUN("阶跃星辰", "原生多模态 · 识图优化 · 双协议兼容")
}

/** 弹窗内部 Tab。 */
private enum class AddProviderTab(val displayName: String) {
    BUILT_IN("内置供应商"),
    CUSTOM("自定义供应商")
}

/**
 * 添加供应商底部弹窗（屏占比 9/10）。
 *
 * 结构：
 * - 顶部 Tab：「内置供应商」|「自定义供应商」
 * - 内容区：按 Tab 与步骤切换
 * - 底部固定按钮：「上一步」（第 1 步置灰）|「下一步」，两 Tab 共用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderSheet(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onSave: (AIProviderConfig) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    var selectedTab by remember { mutableStateOf(AddProviderTab.BUILT_IN) }

    // 内置供应商状态
    var builtInStep by remember { mutableIntStateOf(1) }
    var selectedBuiltIn by remember { mutableStateOf<BuiltInProvider?>(null) }
    var protocol by remember { mutableStateOf(StepFunProtocol.OPENAI) }
    var channel by remember { mutableStateOf(StepFunChannel.STEP_PLAN) }
    var apiKey by remember { mutableStateOf("") }
    // 模型列表：初始为空，进入第 3 步时从服务器实时拉取，不依赖任何硬编码列表
    var builtInModels by remember { mutableStateOf<List<String>>(emptyList()) }

    // 内置供应商向导「选择模型」步骤的拉取状态（与编辑页隔离）
    val builtInFetchState by viewModel.builtInFetchState.collectAsStateWithLifecycle()

    // 进入第 3 步（或切换协议/通道/Key）时实时拉取模型列表，不再依赖硬编码
    LaunchedEffect(selectedTab, builtInStep, protocol, channel, apiKey) {
        if (selectedTab == AddProviderTab.BUILT_IN && builtInStep == 3 && apiKey.isNotBlank()) {
            val type = if (protocol == StepFunProtocol.OPENAI) ProviderType.OPENAI else ProviderType.ANTHROPIC
            viewModel.fetchBuiltInModels(stepFunBaseUrl(protocol, channel), apiKey, type)
        }
    }

    // 拉取成功后用服务器返回的列表作为唯一模型来源（保证不保存过时/硬编码模型）
    LaunchedEffect(builtInFetchState) {
        val state = builtInFetchState
        if (state is FetchState.Success) {
            builtInModels = state.models
        }
    }

    // 自定义供应商状态
    var customStep by remember { mutableIntStateOf(1) }
    var customName by remember { mutableStateOf("") }
    var customType by remember { mutableStateOf(ProviderType.OPENAI) }
    var customApiKey by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customModels by remember { mutableStateOf(listOf<String>()) }

    // 自定义供应商向导「选择模型」步骤的拉取状态（与编辑页/内置向导隔离）
    val customFetchState by viewModel.customFetchState.collectAsStateWithLifecycle()

    // 进入自定义步骤 2（或切换类型/Key/URL）且已填 API Key 时实时拉取模型列表
    LaunchedEffect(selectedTab, customStep, customApiKey, customBaseUrl, customType) {
        if (selectedTab == AddProviderTab.CUSTOM && customStep == 2 && customApiKey.isNotBlank()) {
            viewModel.fetchCustomModels(
                customBaseUrl.ifBlank { defaultProviderBaseUrl(customType) },
                customApiKey,
                customType
            )
        }
    }

    // 当前 Tab 的步骤与总步数
    val currentStep = if (selectedTab == AddProviderTab.BUILT_IN) builtInStep else customStep
    val totalSteps = if (selectedTab == AddProviderTab.BUILT_IN) 3 else 2

    // 「上一步」可用性：第 1 步置灰
    val canGoBack = currentStep > 1

    // 「下一步」可用性
    val canGoNext = when (selectedTab) {
        AddProviderTab.BUILT_IN -> when (builtInStep) {
            1 -> selectedBuiltIn != null
            2 -> apiKey.isNotBlank()
            // 步骤 3：模型列表仅来自实时拉取，需至少保留一个才能完成
            else -> builtInModels.isNotEmpty()
        }
        AddProviderTab.CUSTOM -> when (customStep) {
            1 -> customName.isNotBlank() || customApiKey.isNotBlank() || customBaseUrl.isNotBlank()
            // 步骤 2：需至少保留一个模型（拉取勾选或手动添加）才能完成
            else -> customModels.isNotEmpty()
        }
    }

    fun goBack() {
        when (selectedTab) {
            AddProviderTab.BUILT_IN -> if (builtInStep > 1) builtInStep--
            AddProviderTab.CUSTOM -> if (customStep > 1) customStep--
        }
    }

    fun goNext() {
        when (selectedTab) {
            AddProviderTab.BUILT_IN -> {
                if (builtInStep < 3) {
                    builtInStep++
                } else {
                    // 完成：组装阶跃星辰配置并保存
                    val provider = AIProviderConfig(
                        id = UUID.randomUUID().toString(),
                        name = selectedBuiltIn?.displayName ?: "阶跃星辰",
                        type = if (protocol == StepFunProtocol.OPENAI) ProviderType.OPENAI else ProviderType.ANTHROPIC,
                        apiKey = apiKey,
                        baseUrl = stepFunBaseUrl(protocol, channel),
                        defaultModel = builtInModels.firstOrNull().orEmpty(),
                        isActive = false,
                        models = builtInModels,
                        selectedModel = builtInModels.firstOrNull().orEmpty(),
                        isEnabled = true
                    )
                    onSave(provider)
                    onDismiss()
                }
            }
            AddProviderTab.CUSTOM -> {
                if (customStep < 2) {
                    customStep++
                } else {
                    // 完成：组装自定义配置并保存
                    val provider = AIProviderConfig(
                        id = UUID.randomUUID().toString(),
                        name = customName.ifEmpty { "自定义供应商" },
                        type = customType,
                        apiKey = customApiKey,
                        baseUrl = customBaseUrl.ifBlank { defaultProviderBaseUrl(customType) },
                        defaultModel = customModels.firstOrNull().orEmpty(),
                        isActive = false,
                        models = customModels,
                        selectedModel = customModels.firstOrNull().orEmpty(),
                        isEnabled = true
                    )
                    onSave(provider)
                    onDismiss()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        tonalElevation = 0.dp,
        // 禁用下滑手势：多步骤表单内容较长，误触下滑会直接关闭丢失已填内容；
        // 关闭统一走顶部 X 按钮（或系统返回键），保证有明确的退出操作
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // ── 标题栏：标题 + 关闭按钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.provider_add),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 顶部 Tab ──
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AddProviderTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.displayName) }
                    )
                }
            }

            // ── 内容区 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Spacing.lg)
            ) {
                when (selectedTab) {
                    AddProviderTab.BUILT_IN -> BuiltInProviderContent(
                        step = builtInStep,
                        selected = selectedBuiltIn,
                        onSelect = { selectedBuiltIn = it },
                        protocol = protocol,
                        onProtocolChange = { protocol = it },
                        channel = channel,
                        onChannelChange = { channel = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        models = builtInModels,
                        onModelsChange = { builtInModels = it },
                        fetchState = builtInFetchState,
                        onRetryFetchModels = {
                            val type = if (protocol == StepFunProtocol.OPENAI) ProviderType.OPENAI else ProviderType.ANTHROPIC
                            viewModel.fetchBuiltInModels(stepFunBaseUrl(protocol, channel), apiKey, type)
                        },
                        viewModel = viewModel
                    )
                    AddProviderTab.CUSTOM -> CustomProviderContent(
                        step = customStep,
                        name = customName,
                        onNameChange = { customName = it },
                        type = customType,
                        onTypeChange = { customType = it },
                        apiKey = customApiKey,
                        onApiKeyChange = { customApiKey = it },
                        baseUrl = customBaseUrl,
                        onBaseUrlChange = { customBaseUrl = it },
                        models = customModels,
                        onModelsChange = { customModels = it },
                        fetchState = customFetchState,
                        onFetchModels = {
                            viewModel.fetchCustomModels(
                                customBaseUrl.ifBlank { defaultProviderBaseUrl(customType) },
                                customApiKey,
                                customType
                            )
                        },
                        viewModel = viewModel
                    )
                }
            }

            // ── 底部按钮 ──
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.provider_step_previous))
                }
                TextButton(
                    onClick = { goNext() },
                    enabled = canGoNext,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (currentStep == totalSteps) stringResource(R.string.provider_step_finish)
                        else stringResource(R.string.provider_step_next)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 内置供应商内容区：3 步向导。 */
@Composable
private fun BuiltInProviderContent(
    step: Int,
    selected: BuiltInProvider?,
    onSelect: (BuiltInProvider) -> Unit,
    protocol: StepFunProtocol,
    onProtocolChange: (StepFunProtocol) -> Unit,
    channel: StepFunChannel,
    onChannelChange: (StepFunChannel) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    models: List<String>,
    onModelsChange: (List<String>) -> Unit,
    fetchState: FetchState,
    onRetryFetchModels: () -> Unit,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // 步骤指示器
        StepIndicator(current = step, total = 3)

        when (step) {
            1 -> {
                Text(
                    stringResource(R.string.provider_step_select_provider),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                BuiltInProviderCard(
                    provider = BuiltInProvider.STEPFUN,
                    selected = selected == BuiltInProvider.STEPFUN,
                    onClick = { onSelect(BuiltInProvider.STEPFUN) }
                )
            }
            2 -> {
                Text(
                    stringResource(R.string.provider_step_configure_connection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // 协议选择
                Text(
                    stringResource(R.string.provider_step_protocol),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StepFunProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { onProtocolChange(p) },
                            label = { Text(p.displayName) }
                        )
                    }
                }
                // 通道选择
                Text(
                    stringResource(R.string.provider_step_channel),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StepFunChannel.entries.forEach { c ->
                        FilterChip(
                            selected = channel == c,
                            onClick = { onChannelChange(c) },
                            label = { Text(c.displayName) }
                        )
                    }
                }
                // 端点预览
                val baseUrl = stepFunBaseUrl(protocol, channel)
                Text(
                    stringResource(R.string.provider_step_endpoint_preview),
                    style = MaterialTheme.typography.titleSmall
                )
                Surface(
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            baseUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            3 -> {
                Text(
                    stringResource(R.string.provider_step_select_model),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                BuiltInModelFetchList(
                    fetchState = fetchState,
                    models = models,
                    onModelsChange = onModelsChange,
                    apiKey = apiKey,
                    protocol = protocol,
                    channel = channel,
                    onRetryFetchModels = onRetryFetchModels,
                    viewModel = viewModel
                )
            }
        }
    }
}

/** 自定义供应商内容区：2 步向导。 */
@Composable
private fun CustomProviderContent(
    step: Int,
    name: String,
    onNameChange: (String) -> Unit,
    type: ProviderType,
    onTypeChange: (ProviderType) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    models: List<String>,
    onModelsChange: (List<String>) -> Unit,
    fetchState: FetchState,
    onFetchModels: () -> Unit,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        StepIndicator(current = step, total = 2)

        when (step) {
            1 -> {
                Text(
                    stringResource(R.string.provider_step_basic_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.common_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ProviderType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { onTypeChange(t) },
                            label = { Text(t.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL") },
                    placeholder = { Text(defaultProviderBaseUrl(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            2 -> {
                Text(
                    stringResource(R.string.provider_step_select_model),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                CustomModelFetchList(
                    fetchState = fetchState,
                    models = models,
                    onModelsChange = onModelsChange,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    type = type,
                    onFetchModels = onFetchModels,
                    viewModel = viewModel
                )
            }
        }
    }
}

/** 步骤指示器（圆点）。 */
@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val stepNo = index + 1
            val active = stepNo == current
            val done = stepNo < current
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .background(
                        color = when {
                            active -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

/** 内置供应商卡片。 */
@Composable
private fun BuiltInProviderCard(
    provider: BuiltInProvider,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 内置供应商模型列表（支持实时拉取 + 离线降级 + 测试）。 */
@Composable
private fun BuiltInModelFetchList(
    fetchState: FetchState,
    models: List<String>,
    onModelsChange: (List<String>) -> Unit,
    apiKey: String,
    protocol: StepFunProtocol,
    channel: StepFunChannel,
    onRetryFetchModels: () -> Unit,
    viewModel: SettingsViewModel
) {
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // ── 拉取状态指示 ──
        when (fetchState) {
            is FetchState.Loading -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            stringResource(R.string.provider_step_fetch_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is FetchState.Error -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.provider_fetch_failed, fetchState.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(onClick = onRetryFetchModels) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.provider_step_fetch_retry))
                        }
                    }
                }
            }
            is FetchState.Success -> {
                // 拉取成功：显示服务器返回的模型数
                if (models.isNotEmpty()) {
                    Text(
                        stringResource(R.string.provider_step_fetch_count, models.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is FetchState.Idle -> {
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.provider_step_fetch_idle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 模型列表（空时友好提示） ──
        // 完整候选列表取自拉取成功的结果；拉取失败/Idle 时退回到父级传入的已选列表（可能为空或离线兜底）。
        val displayModels = when (val state = fetchState) {
            is FetchState.Success -> state.models
            else -> models
        }
        // 已拉取成功但用户把勾选全部取消时给出引导
        if (fetchState is FetchState.Success && models.isEmpty() && displayModels.isNotEmpty()) {
            Text(
                stringResource(R.string.provider_step_model_select_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (displayModels.isEmpty()) {
            if (fetchState !is FetchState.Loading) {
                Text(
                    stringResource(R.string.provider_step_models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            displayModels.forEach { model ->
                val meta = modelMetadata[model]
                val isSelected = model in models
                ProviderModelRow(
                    model = model,
                    // 传入 resolved 元数据后，ModelMetadataTags 会展示真实能力标签（识图/工具/思考/Input/Output）
                    // 而非仅固定「Chat」标签。启发式逻辑参考 step-* 规则（ModelMetadataService.default）。
                    metadata = meta,
                    hasOverride = false,
                    testing = model in testing,
                    result = testResults[model],
                    // 内置向导：复选框即「选择」，勾选=入库、取消=不入库；
                    // 不需要删除/能力覆盖按钮（编辑页才保留），传 null 隐藏
                    selected = isSelected,
                    onToggleSelected = { checked ->
                        onModelsChange(if (checked) models + model else models - model)
                    },
                    onTest = {
                        val provider = AIProviderConfig(
                            id = "builtin-test",
                            name = "阶跃星辰",
                            type = if (protocol == StepFunProtocol.OPENAI) ProviderType.OPENAI else ProviderType.ANTHROPIC,
                            apiKey = apiKey,
                            baseUrl = stepFunBaseUrl(protocol, channel),
                            defaultModel = model,
                            isActive = false,
                            models = models,
                            selectedModel = model,
                            isEnabled = true
                        )
                        viewModel.testModel(provider, model)
                    },
                    onRemove = null,
                    onOpenCapabilityOverride = null
                )
            }
        }
    }
}

/**
 * 自定义供应商模型列表（支持实时拉取 + 手动添加 + 测试）。
 *
 * - 拉取：点击「拉取模型」从服务器拉取候选列表；进入本步骤时若已填 API Key 会自动拉取。
 * - 手动添加：输入模型名称点击「添加模型」即可入库（不入库则不会保存）。
 * - 展示：拉取候选 ∪ 已选列表（手动添加项即使不在候选里也保留展示）。
 */
@Composable
private fun CustomModelFetchList(
    fetchState: FetchState,
    models: List<String>,
    onModelsChange: (List<String>) -> Unit,
    apiKey: String,
    baseUrl: String,
    type: ProviderType,
    onFetchModels: () -> Unit,
    viewModel: SettingsViewModel
) {
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    var newModelName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // ── 拉取按钮 ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onFetchModels,
                enabled = apiKey.isNotBlank() && fetchState !is FetchState.Loading
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.provider_fetch_models))
            }
        }

        // ── 拉取状态指示 ──
        when (fetchState) {
            is FetchState.Loading -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            stringResource(R.string.provider_step_fetch_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is FetchState.Error -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.provider_fetch_failed, fetchState.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(onClick = onFetchModels) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.provider_step_fetch_retry))
                        }
                    }
                }
            }
            is FetchState.Success -> {
                if (models.isNotEmpty()) {
                    Text(
                        stringResource(R.string.provider_step_fetch_count, fetchState.models.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is FetchState.Idle -> {
                if (models.isEmpty() && apiKey.isBlank()) {
                    Text(
                        stringResource(R.string.provider_step_custom_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 手动添加 ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newModelName,
                onValueChange = { newModelName = it },
                label = { Text(stringResource(R.string.provider_model_name)) },
                placeholder = { Text(stringResource(R.string.provider_model_name_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    val name = newModelName.trim()
                    if (name.isNotEmpty() && name !in models) {
                        onModelsChange(models + name)
                    }
                    newModelName = ""
                },
                enabled = newModelName.isNotBlank()
            ) {
                Text(stringResource(R.string.provider_add_model))
            }
        }

        // ── 模型列表：拉取候选 ∪ 已选（含手动添加项） ──
        val fetchedCandidates = if (fetchState is FetchState.Success) fetchState.models else emptyList()
        val displayModels = (fetchedCandidates + models).distinct()
        if (displayModels.isEmpty()) {
            if (fetchState !is FetchState.Loading) {
                Text(
                    stringResource(R.string.provider_step_custom_models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 拉取成功但用户把勾选全部取消时给出引导
            if (fetchState is FetchState.Success && models.isEmpty() && displayModels.isNotEmpty()) {
                Text(
                    stringResource(R.string.provider_step_model_select_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            displayModels.forEach { model ->
                ProviderModelRow(
                    model = model,
                    metadata = modelMetadata[model],
                    hasOverride = false,
                    testing = model in testing,
                    result = testResults[model],
                    // 复选框即「选择」：勾选=入库、取消=不入库
                    selected = model in models,
                    onToggleSelected = { checked ->
                        onModelsChange(if (checked) models + model else models - model)
                    },
                    onTest = {
                        val provider = AIProviderConfig(
                            id = "custom-test",
                            name = "自定义供应商",
                            type = type,
                            apiKey = apiKey,
                            baseUrl = baseUrl.ifBlank { defaultProviderBaseUrl(type) },
                            defaultModel = model,
                            isActive = false,
                            models = models,
                            selectedModel = model,
                            isEnabled = true
                        )
                        viewModel.testModel(provider, model)
                    },
                    onRemove = null,
                    onOpenCapabilityOverride = null
                )
            }
        }
    }
}
