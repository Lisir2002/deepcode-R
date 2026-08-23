package com.R.codecore.feature.settings.presentation.component

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.settings.domain.model.ModelMetadata

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    val visionProviderName = providers.firstOrNull { it.id == visionProviderId }?.name
    val visionSubtitle = if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        if (!visionProviderName.isNullOrBlank()) {
            stringResource(R.string.settings_vision_dedicated, visionProviderName, visionModel)
        } else {
            visionModel
        }
    }

    val compactionProviderName = providers.firstOrNull { it.id == compactionProviderId }?.name
    val compactionSubtitle = if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        if (!compactionProviderName.isNullOrBlank()) {
            stringResource(R.string.settings_compaction_dedicated, compactionProviderName, compactionModel)
        } else {
            compactionModel
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            MenuRow(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.settings_vision_model),
                subtitle = visionSubtitle,
                onClick = { showVisionSheet = true }
            )
        }
        item {
            MenuRow(
                icon = Icons.Rounded.FullscreenExit,
                title = stringResource(R.string.settings_compaction_model),
                subtitle = compactionSubtitle,
                onClick = { showCompactionSheet = true }
            )
        }
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            followChatModelText = stringResource(R.string.vision_follow_chat_model),
            followDescText = stringResource(R.string.vision_follow_desc),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false }
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            followChatModelText = stringResource(R.string.compaction_follow_chat_model),
            followDescText = stringResource(R.string.compaction_follow_desc),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false }
        )
    }
}

/**
 * 模型选择弹窗：风格与 [FetchModelsDialog] 保持一致（包含搜索框、Logo 图标、能力 Tag、提供商折叠分组）。
 * 识图模型与压缩模型共用此组件，仅文案不同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionSheet(
    title: String,
    followChatModelText: String,
    followDescText: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val collapsedProviders = remember { mutableStateMapOf<String, Boolean>() }

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
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.provider_filter_models_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (searchQuery.isBlank() || followChatModelText.contains(searchQuery, ignoreCase = true)) {
                            item(key = "header_follow_chat") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(Radius.md),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(
                                        1.dp,
                                        if (currentProviderId.isBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onClear() }
                                            .padding(Spacing.lg),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = followChatModelText,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = followDescText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = Spacing.xs)
                                            )
                                        }
                                        if (currentProviderId.isBlank()) {
                                            Spacer(Modifier.width(Spacing.sm))
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
                        if (activeProviders.isEmpty()) {
                            item {
                                Text(
                                    text = noModelsText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(Spacing.md)
                                )
                            }
                        } else {
                            activeProviders.forEach { provider ->
                                val filteredModels = provider.models.filter {
                                    searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                                }
                                if (filteredModels.isNotEmpty()) {
                                    item(key = "header_${provider.id}") {
                                        val expanded = collapsedProviders[provider.id] != true
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 44.dp)
                                                .clickable { collapsedProviders[provider.id] = expanded }
                                                .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${provider.name} (${filteredModels.size})",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                contentDescription = if (expanded) stringResource(R.string.provider_collapse_brand, provider.name) else stringResource(R.string.provider_expand_brand, provider.name),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    if (collapsedProviders[provider.id] != true) {
                                        items(filteredModels, key = { "${provider.id}_$it" }) { model ->
                                            ModelSelectionRow(
                                                model = model,
                                                selected = provider.id == currentProviderId && model == currentModel,
                                                metadata = modelMetadata[model],
                                                onClick = { onSelect(provider.id, model) }
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

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModelTag(text = "Chat")
        if (metadata != null) {
            if (metadata.supportsVision) {
                // 小白友好：把英文 Image 换成「识图（Vision）」，既保留技术术语，又不晦涩
                ModelTag(text = "识图（Vision）", isHighlight = true)
            }
            if (metadata.supportsTools) {
                // 同理：Tools → 「工具（Tools）」
                ModelTag(text = "工具（Tools）")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = "Input ${formatTokenLimit(input)}")
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = "Output ${formatTokenLimit(output)}")
            }
            if (metadata.supportsReasoning) {
                // Reasoning 单独加一枚「思考（Reasoning）」徽章，避免思考强度设置里只有「思考」看不到英文对照
                ModelTag(text = "思考（Reasoning）")
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
