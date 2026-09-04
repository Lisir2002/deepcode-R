package com.core.deepcode.newui.designsystem.slot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面槽位模型（§2.3）。S0 最小自证框架：
 *  - 块级槽位 BlockSlotKind：五种标准容器
 *  - 槽位键 SlotKey：标识"哪个槽位挂了哪个插件"，用于收纳/搬移（渲染靠插槽 lambda，见 §2.3.4b）
 *  - 子级描述符 SlotContent（描述轨，可持久化）；渲染轨由调用方传 @Composable lambda
 *  - SlotSet：声明式装配顶栏/内容/底栏，对标官方 Scaffold 插槽语言
 *
 * 扩展方向见设计文档 §2.5（槽位插件扩展方案，全网检索对照）：
 * 断点驱动装配（AppBreakpoint）/ 导航四态换型 / 停靠强度（DockPlacement）/ 多栏调宽（PaneSpec）
 * / 装配图序列化（SlotAssembly），P1 起按该节落地；不重写官方 Scaffold 布局骨架。
 */

/** 五种标准块级槽位（§2.3.2） */
enum class BlockSlotKind { TOP_APP_BAR, TOP_TABS, CONTENT, BOTTOM_TABS, SIDE_RAIL }

/** 槽位键：唯一标识一个可装配的插件（渲染内容由插槽 lambda 提供，键仅作定位/收纳）。 */
data class SlotKey(val id: String)

/** 子级槽位描述符（数据轨）：用于收纳持久化/装配图；真正渲染走插槽 lambda。 */
sealed interface SlotContent {
    data class Text(val value: String) : SlotContent
    data class Button(val label: String?, val slotRef: SlotKey) : SlotContent
    object Divider : SlotContent
}

/** 块级槽位协议（§2.3.4）：一块可挂子级/可被收纳的区域。S0 提供骨架契约，渲染由 SlotSet 编排。 */
interface BlockSlot {
    val slot: BlockSlotKind
    val subKeys: List<SlotKey>
}

/**
 * 声明式装配（§2.3.5 / §2.3.4b）：落成官方 Scaffold 插槽形式——
 * topBar/title/content/bottomBar 均为 @Composable 命名插槽，Screen 只挂载不写布局骨架。
 */
/** 无底栏占位：Scaffold 的 bottomBar 为非空 @Composable，空占位不渲染任何内容。 */
@Composable
private fun SlotNoBottomBar() {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotSet(
    topBar: (@Composable () -> Unit)? = null,
    title: String = "",
    content: @Composable () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val fallbackTopBar: @Composable () -> Unit = {
        androidx.compose.material3.TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        )
    }
    val effectiveTopBar: @Composable () -> Unit = if (topBar != null) topBar else fallbackTopBar
    val effectiveBottomBar: @Composable () -> Unit = if (bottomBar != null) bottomBar else ::SlotNoBottomBar
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = effectiveTopBar,
        bottomBar = effectiveBottomBar,
        content = { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) { content() }
        },
    )
}