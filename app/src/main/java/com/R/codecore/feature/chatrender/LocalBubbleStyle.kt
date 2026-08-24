package com.R.codecore.feature.chatrender

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前生效的消息回复样式（由 AIChatPanel 依据设置注入）。
 * 切换时仅触发重组，不读写对话数据。
 */
val LocalBubbleStyle = staticCompositionLocalOf { BubbleStyle.DEFAULT }
