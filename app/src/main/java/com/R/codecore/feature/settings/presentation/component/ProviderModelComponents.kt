package com.R.codecore.feature.settings.presentation.component

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.R.codecore.R
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import com.R.codecore.feature.settings.data.remote.ModelTestResult
import com.R.codecore.feature.settings.domain.model.ModelMetadata
import com.R.codecore.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?, hasOverride: Boolean = false) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModelTag(text = "Chat")
        metadata?.let {
            if (it.supportsVision) {
                OverlayBadgeTag(label = "识图（Vision）", enabled = it.supportsVision, overridden = hasOverride && it.inferenceReason?.overrideVision != null)
            }
            if (it.supportsTools) {
                OverlayBadgeTag(label = "工具（Tools）", enabled = it.supportsTools, overridden = hasOverride && it.inferenceReason?.overrideTools != null)
            }
            val input = it.inputTokens?.takeIf { tokens -> tokens > 0 }
                ?: it.contextTokens.takeIf { tokens -> tokens > 0 }
            if (input != null) {
                ModelTag(text = "Input ${formatTokenLimit(input)}")
            }
            it.outputTokens?.takeIf { tokens -> tokens > 0 }?.let { output ->
                ModelTag(text = "Output ${formatTokenLimit(output)}")
            }
            if (it.supportsReasoning) {
                OverlayBadgeTag(label = "思考（Reasoning）", enabled = it.supportsReasoning, overridden = hasOverride && it.inferenceReason?.overrideReasoning != null)
            }
            if (hasOverride) {
                ModelTag(text = "已覆盖（Manual）", icon = Icons.Rounded.Settings)
            }
        }
    }
}

/**
 * 三复选框其中之一的展示 Tag：右上角有小红点，小白一眼就能认出「这个能力是我手动覆盖过的」，
 * 不是系统自动推荐。未覆盖时红点隐藏。
 */
@Composable
private fun OverlayBadgeTag(label: String, enabled: Boolean, overridden: Boolean) {
    Box {
        ModelTag(text = label)
        if (overridden) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
    // 「关」的场景（被用户手动覆盖成 false）也要露个小徽章，免得小白以为显示丢了
    if (!enabled && overridden) {
        ModelTag(text = "禁用$label（Manual Off）", icon = Icons.Rounded.Close)
    }
}

@Composable
private fun ModelTag(text: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (icon != null && text != null) Spacer(Modifier.width(4.dp))
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderModelRow(
    model: String,
    metadata: ModelMetadata?,
    hasOverride: Boolean,
    testing: Boolean,
    result: ModelTestResult?,
    onTest: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onOpenCapabilityOverride: (() -> Unit)? = null,
    selected: Boolean? = null,
    onToggleSelected: (Boolean) -> Unit = {}
) {
    var showErrorDetail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 选择复选框：仅内置供应商向导（已拉取候选列表）使用，勾选=入库；
            // 编辑页传 null 不显示（编辑页的模型本身已入库，语义不同）。
            selected?.let { checked ->
                Checkbox(checked = checked, onCheckedChange = onToggleSelected)
                Spacer(Modifier.width(Spacing.xs))
            }
            ModelLogoIcon(modelName = model, size = 24.dp)
            Spacer(Modifier.width(Spacing.md))

            // Center Content (Name & Tags)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                ModelMetadataTags(metadata = metadata, hasOverride = hasOverride)
            }

            Spacer(Modifier.width(Spacing.sm))

            // RC63 ④ 能力覆盖齿轮按钮（编辑页使用；向导传 null 隐藏，避免无意义死按钮）
            onOpenCapabilityOverride?.let { onOpen ->
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "手动覆盖模型能力（识图Vision/工具Tools/思考Reasoning）",
                        tint = if (hasOverride) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Right Actions
            Box(
                modifier = Modifier.width(56.dp),
                contentAlignment = Alignment.Center
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onTest, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
                        Text(stringResource(R.string.provider_test), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // 删除按钮（编辑页使用；向导传 null 隐藏，取消选择走复选框）
            onRemove?.let { onDel ->
                IconButton(onClick = onDel, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Test Result
        result?.let { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = Spacing.sm, start = 32.dp)
                    .then(
                        if (!r.success) Modifier.clickable { showErrorDetail = true } else Modifier
                    )
            ) {
                Icon(
                    if (r.success) Icons.Rounded.Check else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                val displayMsg = if (r.success) {
                    r.message
                } else {
                    val codeMatch = Regex("""(?i)(HTTP\s*\d{3}|code[:\s]+[a-zA-Z0-9_]+)""").find(r.message)
                    if (codeMatch != null) codeMatch.value
                    else r.message.lines().firstOrNull()?.let { if (it.length > 20) it.take(20) + "..." else it } ?: "Error"
                }
                Text(
                    text = displayMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showErrorDetail && result != null && !result.success) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val clipboard = LocalClipboard.current
        var copied by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(copied) {
            if (copied) {
                kotlinx.coroutines.delay(1500)
                copied = false
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showErrorDetail = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Error Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("error", result.message)))
                            copied = true
                        }
                    }) {
                        Icon(
                            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = "Copy Error",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radius.sm))
                        .padding(Spacing.sm)
                )
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}M".trimDecimal()
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}K".trimDecimal()
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FetchModelRow(
    model: String,
    metadata: ModelMetadata?,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata = metadata, hasOverride = false)
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ————————————————————————————————————————————————————————————
// RC63 ④：单模型「三能力复选框手动覆盖」底部面板
// ————————————————————————————————————————————————————————————

/**
 * 三级复选框（Indeterminate）：每一条的状态都可能是：
 *  - null（未覆盖：跟随系统自动推荐）
 *  - true（手动覆盖为开）
 *  - false（手动覆盖为关）
 *
 *  控件实现：文字「点击 null→true→false→null」循环，同时左边 FilterChip 三态切换
 *  （选中=开/不选=关/中间=不覆盖）。小白可直观看到「被覆盖的是哪一个」。
 */
@Composable
private fun TriStateCapabilityRow(
    label: String,
    englishTag: String,
    description: String,
    autoValue: Boolean,    // 系统自动判定的期望值（显示在副标题「自动推荐」里）
    overrideValue: Boolean?,  // null=未覆盖；true/false=覆盖
    onChange: (Boolean?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = when (overrideValue) {
                    null -> true
                    true -> false
                    false -> null
                }
                onChange(next)
            }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        FilterChip(
            selected = overrideValue == true,
            onClick = {
                val next = when (overrideValue) {
                    null -> true
                    true -> false
                    false -> null
                }
                onChange(next)
            },
            label = {
                Text(
                    text = when (overrideValue) {
                        true -> stringResource(R.string.ui_______9188121c)
                        false -> stringResource(R.string.ui_______73f7db5e)
                        null -> stringResource(R.string.ui________be725ae0)
                    }
                )
            },
            leadingIcon = if (overrideValue != null) {
                { Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(14.dp)) }
            } else null
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$label（$englishTag）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(description)
                    append(stringResource(R.string.ui______d8195afb))
                    append(if (autoValue) stringResource(R.string.ui____5f15d40d) else stringResource(R.string.ui____127dc326))
                    append(
                        when (overrideValue) {
                            true -> stringResource(R.string.ui________3eeb0c16)
                            false -> stringResource(R.string.ui________b9cce01f)
                            null -> ""
                        }
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CapabilityOverrideSheet(
    viewModel: com.R.codecore.feature.settings.presentation.SettingsViewModel,
    providerType: ProviderType,
    modelId: String,
    metadata: ModelMetadata?,
    overrideFlow: kotlinx.coroutines.flow.Flow<ModelCapabilityOverrideEntity?>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val override by overrideFlow.collectAsStateWithLifecycleCompat(initial = null)

    // 本地三态（UI 编辑的草稿）：初始值从 overrideFlow 读，避免打开面板时丢失已有的覆盖。
    var draftVision by remember(override) { mutableStateOf(override?.overrideVision) }
    var draftTools by remember(override) { mutableStateOf(override?.overrideTools) }
    var draftReasoning by remember(override) { mutableStateOf(override?.overrideReasoning) }
    var dirty by remember(override) { mutableStateOf(false) }
    fun markDirty() { dirty = true }

    val autoVision = metadata?.supportsVision == true && metadata.inferenceReason?.overrideVision == null
    val autoVisionStrict = (metadata?.inferenceReason?.byProbablyVision ?: false) || metadata?.supportsVision == true
    val autoTools = metadata?.supportsTools == true
    val autoReasoning = metadata?.supportsReasoning == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "模型能力覆盖（手动）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        viewModel.clearCapabilityOverride(providerType, modelId)
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("恢复自动推荐")
                }
            }

            Text(
                text = buildString {
                    append("模型：$modelId")
                    append(if (metadata?.source == ModelMetadata.Source.MODELS_DEV) "（官方收录）" else "（官方收录）")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            metadata?.inferenceReason?.let { reason ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(Spacing.sm)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            text = "🧭 判定链路审计（小白解释）：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = buildString {
                                append("· 启发式匹配：")
                                append("识图=${if (reason.byProbablyVision) "✅" else "❌"} ")
                                append("工具=${if (reason.byProbablyTools) "✅" else "❌"} ")
                                append("思考=${if (reason.byProbablyReasoning) "✅" else "❌"}")
                                appendLine()
                                append("· 兼容端点策略：${reason.appliedPolicy ?: "（收录模型，跳过）"}")
                                appendLine()
                                append("· 你的手动覆盖：")
                                append("识图=${reason.overrideVision?.let { if (it) "✅开" else "❌关" } ?: "未覆盖"} ")
                                append("工具=${reason.overrideTools?.let { if (it) "✅开" else "❌关" } ?: "未覆盖"} ")
                                append("思考=${reason.overrideReasoning?.let { if (it) "✅开" else "❌关" } ?: "未覆盖"}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            TriStateCapabilityRow(
                label = "多模态识图",
                englishTag = "Vision",
                description = "模型是否能接收图片/截图并理解内容；step-3.7-flash 官方文档=支持。",
                autoValue = autoVisionStrict,
                overrideValue = draftVision,
                onChange = { draftVision = it; markDirty() }
            )
            TriStateCapabilityRow(
                label = "工具调用",
                englishTag = "Tools / Function Calling",
                description = "模型是否能调用外部工具（查文件/跑命令/查知识库）。",
                autoValue = autoTools,
                overrideValue = draftTools,
                onChange = { draftTools = it; markDirty() }
            )
            TriStateCapabilityRow(
                label = "深度思考",
                englishTag = "Reasoning",
                description = "模型是否支持 extended thinking / reasoning effort（长推理链）。",
                autoValue = autoReasoning,
                overrideValue = draftReasoning,
                onChange = { draftReasoning = it; markDirty() }
            )

            Spacer(Modifier.height(Spacing.sm))

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                TextButton(
                    onClick = {
                        viewModel.saveCapabilityOverride(
                            type = providerType,
                            modelId = modelId,
                            vision = draftVision,
                            tools = draftTools,
                            reasoning = draftReasoning
                        )
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        if (!dirty) "保存覆盖"
                        else if (draftVision == null && draftTools == null && draftReasoning == null) "保存覆盖"
                        else "保存覆盖"
                    )
                }
            }
        }
    }
}

/** 兼容 collectAsStateWithLifecycle 在非 androidx.lifecycle:lifecycle-runtime-compose 场景下的兜底实现（直接用 viewModel 的 flow + initial 初值）。 */
@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateWithLifecycleCompat(initial: T): androidx.compose.runtime.State<T> {
    return collectAsStateWithLifecycle(initialValue = initial)
}
