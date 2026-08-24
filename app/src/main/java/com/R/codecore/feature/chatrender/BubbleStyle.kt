package com.R.codecore.feature.chatrender

import androidx.annotation.StringRes
import com.R.codecore.R

/**
 * 聊天回复样式枚举：四款可切换的回复渲染风格。
 *
 * - [PURE_TEXT]    纯文字流：零容器零描边，靠色标与对齐区分角色；
 * - [THIN_BUBBLE]  细线气泡：透明底 + 1dp 描边气泡，靠轮廓不靠填色；
 * - [TERMINAL_LOG] 终端日志（默认）：等宽前缀 + 色条，开发者日志流；
 * - [TIMELINE]     时间线：左侧角色圆点轨道 + 内容右排。
 */
enum class BubbleStyle(@param:StringRes val labelRes: Int) {
    PURE_TEXT(R.string.bubble_style_pure_text),
    THIN_BUBBLE(R.string.bubble_style_thin_bubble),
    TERMINAL_LOG(R.string.bubble_style_terminal_log),
    TIMELINE(R.string.bubble_style_timeline);

    companion object {
        /** 默认样式：终端日志（主设计）。 */
        val DEFAULT: BubbleStyle = TERMINAL_LOG

        fun fromPersisted(value: String?): BubbleStyle? = entries.firstOrNull { it.name == value }
    }
}
