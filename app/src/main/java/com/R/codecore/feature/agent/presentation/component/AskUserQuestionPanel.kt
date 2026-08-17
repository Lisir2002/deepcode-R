package com.R.codecore.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.Elevation
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.agent.domain.tool.question.PendingUserQuestion
import com.R.codecore.feature.agent.domain.tool.question.QuestionItem
import com.R.codecore.feature.agent.domain.tool.question.SingleAnswer
import com.R.codecore.feature.agent.domain.tool.question.UserQuestionAnswer
import androidx.compose.ui.res.stringResource
import com.R.codecore.R


/** 「其他」选项的固定 label，不与 AI 传入的选项重复。 */
private const val OTHER_LABEL = "Other"

/**
 * AI 向用户提问的面板：展示 1-4 个结构化问题，每个带 2-4 个预设选项 + 一个「其他」自由输入选项。
 *
 * 风格对齐 [ToolPermissionPanel]：内联 Surface，不用 AlertDialog。
 *
 * @param question 待回答的问题请求。
 * @param onConfirm 用户点击确认后回传答案。
 * @param onSkip 用户点击「补充」，返回空答案——表示用户想补充说明而非在预设选项中做选择。
 */
@Composable
fun AskUserQuestionPanel(
    question: PendingUserQuestion,
    onConfirm: (UserQuestionAnswer) -> Unit,
    onSkip: () -> Unit
) {
    // 每个问题的已选 label 集合
    // Q-1：初始即预选中标记了 default=true 的选项（默认/推荐项高亮），用户可直接确认或再调整
    val selectedMap = remember(question.id) {
        mutableStateMapOf<Int, MutableList<String>>().apply {
            question.questions.forEachIndexed { idx, q ->
                this[idx] = mutableStateListOf<String>().apply {
                    addAll(q.options.filter { it.default }.map { it.label })
                }
            }
        }
    }
    // 每个问题的「其他」自由文本
    val customTexts = remember(question.id) {
        mutableStateMapOf<Int, String>().apply {
            question.questions.forEachIndexed { idx, _ -> this[idx] = "" }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        shadowElevation = Elevation.z2,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.md)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 面板标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.ask_confirm_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "askUserQuestion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // 逐个渲染问题
            question.questions.forEachIndexed { idx, q ->
                if (idx > 0) Spacer(Modifier.height(Spacing.md))
                QuestionCard(
                    
                    item = q,
                    selected = selectedMap[idx] ?: mutableListOf(),
                    customText = customTexts[idx] ?: "",
                    onSelectionChanged = { newSelection ->
                        selectedMap[idx] = newSelection.toMutableList() as MutableList<String>
                    },
                    onCustomTextChanged = { customTexts[idx] = it }
                )
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentActionButton(
                    text = stringResource(R.string.ask_supplement),
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Neutral
                )
                AgentActionButton(
                    text = stringResource(R.string.ask_confirm),
                    onClick = {
                        val answers = question.questions.mapIndexed { i, q ->
                            val sel = selectedMap[i] ?: emptyList<String>()
                            val custom = customTexts[i]?.takeIf { it.isNotBlank() && OTHER_LABEL in sel }
                            SingleAnswer(
                                question = q.question,
                                selected = sel.filter { it != OTHER_LABEL },
                                customText = custom
                            )
                        }
                        onConfirm(UserQuestionAnswer(answers))
                    },
                    modifier = Modifier.weight(1f),
                    tone = AgentActionTone.Success
                )
            }
        }
    }
}

/**
 * 单个问题的卡片区域。
 */
@Composable
private fun QuestionCard(
    
    item: QuestionItem,
    selected: List<String>,
    customText: String,
    onSelectionChanged: (List<String>) -> Unit,
    onCustomTextChanged: (String) -> Unit
) {
    Column {
        // 标题行：header 标签 + 问题文本
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.header.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Radius.xs)
                ) {
                    Text(
                        text = item.header,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            // Q-2：问题文本支持轻量 markdown（**加粗**、`代码`、链接等）
            MarkdownContent(
                text = item.question,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                compact = true
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // 选项列表
        val allOptions = item.options.map { it.label } + OTHER_LABEL

        allOptions.forEachIndexed { optIdx, label ->
            val isOther = label == OTHER_LABEL
            val isSelected = label in selected
            // Q-1：是否为默认/推荐选项（"其他" 不是）
            val isDefault = !isOther && (item.options.getOrNull(optIdx)?.default == true)
            val description = if (!isOther) {
                item.options.getOrNull(optIdx)?.description ?: ""
            } else {
                stringResource(R.string.ask_custom_answer)
            }

            // Q-1：默认选项用主色底 + 细边框高亮，让推荐项一眼可见
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xs))
                    .then(
                        if (isDefault) {
                            Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(Radius.xs))
                        } else {
                            Modifier
                        }
                    )
                    .clickable {
                        val newSelection = if (item.multiSelect) {
                            if (isSelected) selected - label else selected + label
                        } else {
                            listOf(label)
                        }
                        onSelectionChanged(newSelection)
                    }
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.multiSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            val newSelection = if (checked) selected + label else selected - label
                            onSelectionChanged(newSelection)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectionChanged(listOf(label)) },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isOther) stringResource(R.string.common_other) else label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        // Q-1：推荐角标
                        if (isDefault) {
                            Spacer(Modifier.width(Spacing.xs))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(Radius.xs)
                            ) {
                                Text(
                                    text = stringResource(R.string.common_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        // Q-2：选项说明支持轻量 markdown
                        MarkdownContent(
                            text = description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            compact = true
                        )
                    }
                }
            }

            // 「其他」被选中时展开文本输入框
            if (isOther && isSelected) {
                Spacer(Modifier.height(Spacing.xs))
                TextField(
                    value = customText,
                    onValueChange = onCustomTextChanged,
                    placeholder = { Text(stringResource(R.string.ask_input_hint), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(Radius.sm)
                )
            }
        }
    }


}
