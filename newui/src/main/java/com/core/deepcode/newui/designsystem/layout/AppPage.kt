package com.core.deepcode.newui.designsystem.layout

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.core.deepcode.newui.designsystem.token.generated.AppLayout as AppLayoutToken

/**
 * 页面级布局卫生（§3.7）：统一左右留白、块/行内间距、最大内容宽。
 * 组件内禁止出现表外数值，一律引用 AppLayout 令牌（经本对象 Dp 门面）。
 */
object AppPage {
    /** 页面左右留白（§3.7.1）：screen 根布局统一加此水平内边距 */
    val horizontalPadding: Dp get() = AppLayoutToken.PageHorizontal

    /** 区块间垂直间距（§3.7.2） */
    fun verticalBlock(spacing: Dp = AppLayoutToken.BlockGap): Dp = spacing

    /** 行内间距（§3.7.2） */
    val rowGap: Dp get() = AppLayoutToken.RowGap
}

/** 页面内容统一边距（左右留白 + 可配区块垂直间距）；宽屏常与 [pageMaxWidth] 搭配。 */
fun Modifier.pageContentPadding(
    verticalSpacing: Dp = AppLayoutToken.BlockGap,
): Modifier = this.padding(horizontal = AppLayoutToken.PageHorizontal, vertical = verticalSpacing)

/** 宽屏内容区最大宽度居中（§3.10 最大内容宽） */
fun Modifier.pageMaxWidth(): Modifier = widthIn(max = AppLayoutToken.ContentMaxWidth)