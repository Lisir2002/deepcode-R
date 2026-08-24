package com.R.codecore.feature.chatrender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.R.codecore.core.theme.Spacing

/**
 * 款式 A · 纯文字流：零容器、零描边、零色块，只靠对齐区分角色。
 * - 用户消息靠右对齐，助手 / 工具消息靠左对齐；
 * - 工具调用折叠为纯文字行（内容自带折叠）；
 * - 任务头为灰阶日志行，无贯穿竖条、无顶部色线。
 */
object PureTextBubbleStyle : MessageBubbleStyle {
    override val id: BubbleStyle = BubbleStyle.PURE_TEXT

    @Composable
    override fun TaskGroupContainer(
        isExpanded: Boolean,
        isStreaming: Boolean,
        title: String,
        timestamp: Long,
        onToggle: () -> Unit,
        modifier: Modifier,
        headerTrailing: @Composable RowScope.() -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            BubbleTaskHeader(
                isExpanded = isExpanded,
                isStreaming = isStreaming,
                title = title,
                timestamp = timestamp,
                onToggle = onToggle,
                headerTrailing = headerTrailing
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xs, end = Spacing.xs),
                content = content
            )
        }
    }

    @Composable
    override fun SubGroupHeader(
        type: BubbleSubGroupType,
        label: String,
        isExpanded: Boolean,
        isUser: Boolean,
        onToggle: () -> Unit,
        modifier: Modifier
    ) {
        BubbleSubLabel(
            label = label,
            isExpanded = isExpanded,
            onToggle = onToggle,
            modifier = modifier,
            alignEnd = isUser
        )
    }

    @Composable
    override fun UserContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .padding(vertical = Spacing.xs),
                horizontalAlignment = Alignment.End,
                content = content
            )
        }
    }

    @Composable
    override fun AssistantContainer(
        isFormal: Boolean,
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            content = content
        )
    }

    @Composable
    override fun ToolContainer(
        timestamp: Long,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            content = content
        )
    }
}
