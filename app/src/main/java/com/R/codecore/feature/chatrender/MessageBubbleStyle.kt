package com.R.codecore.feature.chatrender

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 聊天消息渲染样式抽象：每种款式负责「任务组外框 / 片段头 / 用户 / 助手 / 工具」五类外框。
 *
 * 与消息链路解耦的约定（延续全局「透明化」纪律）：
 * - 所有容器必须透明，绝不填充背景色，只允许色线 / 描边 / 节点 / 标签等线性元素；
 * - 正文内容由调用方以 content lambda 注入，本模块只负责外框，不感知 Markdown / 工具 / 附件内部实现；
 * - 样式切换只影响渲染层，不读写任何对话数据（数据零丢失）。
 *
 * 四款风格（[BubbleStyle]）：
 * - [BubbleStyle.PURE_TEXT]    纯文字流：零容器零描边，靠对齐与极简标签区分角色；
 * - [BubbleStyle.THIN_BUBBLE]  细线气泡：透明底 + 1dp 描边圆角框，靠轮廓不靠填色；
 * - [BubbleStyle.TERMINAL_LOG] 终端日志（默认）：等宽前缀 + 行首色线，开发者日志流；
 * - [BubbleStyle.TIMELINE]     时间线：左侧角色节点轨道（圆 / 方 / 菱）+ 内容右排 + 时间戳。
 */
interface MessageBubbleStyle {
    val id: BubbleStyle

    /**
     * 任务组（一轮回复）外框：含任务头（标题 / 时间 / 折叠箭头）。
     * 由款式决定是否出现贯穿竖条 / 顶部色线 / 细线框 / 时间线轨道。
     *
     * @param headerTrailing 任务头右侧追加元素（如流式状态文案）
     * @param content 任务内容：用户消息 + 过程内容 + 正式回复
     */
    @Composable
    fun TaskGroupContainer(
        isExpanded: Boolean,
        isStreaming: Boolean,
        title: String,
        timestamp: Long,
        onToggle: () -> Unit,
        modifier: Modifier = Modifier,
        headerTrailing: @Composable RowScope.() -> Unit = {},
        content: @Composable ColumnScope.() -> Unit
    )

    /** 二级片段头（用户 / 思考 / 回复 / 工具标签行）。 */
    @Composable
    fun SubGroupHeader(
        type: BubbleSubGroupType,
        label: String,
        isExpanded: Boolean,
        isUser: Boolean,
        onToggle: () -> Unit,
        modifier: Modifier = Modifier
    )

    /** 用户消息外框。 */
    @Composable
    fun UserContainer(
        timestamp: Long = 0L,
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    )

    /** 助手消息外框（[isFormal] 是否为正式回复，款式据此做轻微强调）。 */
    @Composable
    fun AssistantContainer(
        isFormal: Boolean,
        timestamp: Long = 0L,
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    )

    /** 工具消息外框（内容自带折叠）。 */
    @Composable
    fun ToolContainer(
        timestamp: Long = 0L,
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    )
}
