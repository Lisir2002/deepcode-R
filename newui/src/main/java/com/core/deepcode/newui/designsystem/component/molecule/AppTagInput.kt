package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppColor
import com.core.deepcode.newui.designsystem.token.generated.AppRadius
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing

/**
 * 标签输入器（分子组 · AppTagInput）：回车添加标签（chip 回弹进入、可删），
 * 用于项目标签 / 关键词 / 过滤条件等自由录入场景。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppTagInput(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入后回车添加…",
    accentColor: Color = AppColor.BrandPrimary,
) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
        ) {
            tags.forEach { tag ->
                AnimatedVisibility(
                    visible = true,
                    enter = scaleIn(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    ) + fadeIn() + expandHorizontally(),
                    exit = scaleOut() + fadeOut() + shrinkHorizontally(),
                ) {
                    AppTagChip(
                        label = tag,
                        accentColor = accentColor,
                        onRemove = { onRemove(tag) },
                    )
                }
            }
            // 录入框
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.Pill))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppRadius.Pill))
                    .padding(horizontal = AppSpacing.Md, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val v = text.trim()
                        if (v.isNotEmpty()) {
                            onAdd(v)
                            text = ""
                        }
                    }),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty() && tags.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .width(96.dp)
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AppTagChip(
    label: String,
    accentColor: Color,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.Pill))
            .background(accentColor.copy(alpha = 0.10f))
            .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(AppRadius.Pill))
            .padding(start = AppSpacing.Md, top = 6.dp, bottom = 6.dp, end = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = accentColor,
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "移除标签",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(AppRadius.Pill))
                .clickable { onRemove() },
        )
    }
}