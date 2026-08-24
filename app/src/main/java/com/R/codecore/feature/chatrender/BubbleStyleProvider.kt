package com.R.codecore.feature.chatrender

/**
 * 款式工厂：由 [BubbleStyle] 解析出对应的 [MessageBubbleStyle] 渲染器。
 * 无状态单例对象，切换样式时按需重建（remember 缓存）。
 */
object BubbleStyleProvider {
    fun provide(style: BubbleStyle): MessageBubbleStyle = when (style) {
        BubbleStyle.PURE_TEXT -> PureTextBubbleStyle
        BubbleStyle.THIN_BUBBLE -> ThinBubbleStyle
        BubbleStyle.TERMINAL_LOG -> TerminalLogBubbleStyle
        BubbleStyle.TIMELINE -> TimelineBubbleStyle
    }
}
