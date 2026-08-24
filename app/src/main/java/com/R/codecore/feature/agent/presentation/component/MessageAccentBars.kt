package com.R.codecore.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.R.codecore.feature.agent.presentation.AgentUIMessage

/**
 * 整行色线：横贯全宽的淡色细线，用于「模型一轮回复」正文顶部的横向锚点。
 */
@Composable
internal fun FullWidthAccentBar(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(color)
    )
}

/**
 * 左侧短条 + 竖条贯穿容器：把子块（思考 / 工具 / 技能）渲染为「引用块」式结构。
 * - 最左侧一条 [barColor] 竖条贯穿整个子块高度；
 * - 内容整体右移，标题行可再叠加 [ShortAccentBar] 短条做色标提示。
 * 竖条不参与父布局尺寸计算（[BoxScope.matchParentSize]），高度随内容伸缩。
 */
@Composable
internal fun AccentBarContainer(
    barColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .width(2.dp)
                .align(Alignment.CenterStart)
                .background(barColor)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp),
            content = content
        )
    }
}

/**
 * 标题行左侧的短色条：3dp 宽 × [height] 高的小色块，作为子块标题前的色标提示。
 */
@Composable
internal fun ShortAccentBar(color: Color, height: Dp = 14.dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(1.dp))
            .background(color)
    )
}

/** 是否为技能类工具（loadSkill / runSkillScript / 自动触发技能消息）。 */
internal fun isSkillToolName(toolName: String?): Boolean =
    toolName == "loadSkill" || toolName == "runSkillScript"

/** 是否为技能类工具消息（按工具名或自动触发技能消息 id 前缀识别）。 */
internal fun isSkillMessage(message: AgentUIMessage): Boolean =
    isSkillToolName(message.toolName) || message.id.startsWith("skill_auto_")
