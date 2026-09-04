package com.core.deepcode.newui.designsystem.slot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/** 块级槽位协议（§2.3.4）：一块可挂子级/可被收纳的区域。S0 提供骨架契约，渲染由 SlotSet 编排。
 *  §2.5 扩展：`showOn`（断点可见性）+ `dock`（停靠强度），均带默认值，不改既有实现。 */
interface BlockSlot {
    val slot: BlockSlotKind
    val subKeys: List<SlotKey>
    val showOn: Set<AppBreakpoint> get() = AppBreakpoint.entries.toSet() // 断点驱动装配（§2.5.3）
    val dock: DockPlacement get() = DockPlacement.Pinned                 // 停靠强度（§2.5.5）
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

// ====== §2.5 槽位插件扩展骨架（P1：断点驱动装配 / 停靠 / 换型 / 调宽 / 重建）======

/** 窗口断点（对齐 M3 breakpoints，§2.5.3）。 */
enum class AppBreakpoint { Compact, Medium, Expanded, Large, ExtraLarge }

/** 由窗口宽度(Dp)映射断点（M3 阈值，§2.5.3）。 */
fun AppBreakpointFromWidth(width: Dp): AppBreakpoint = when {
    width < 600.dp -> AppBreakpoint.Compact
    width < 840.dp -> AppBreakpoint.Medium
    width < 1200.dp -> AppBreakpoint.Expanded
    width < 1600.dp -> AppBreakpoint.Large
    else -> AppBreakpoint.ExtraLarge
}

/** 断点装配上下文（§2.5.3）：承载当前断点，供槽位/策略读取。 */
class AppAdaptiveContext(val breakpoint: AppBreakpoint) {
    fun largerThan(other: AppBreakpoint): Boolean = breakpoint.ordinal > other.ordinal
}

/** 断点作用域（§2.5.3）：由 `BoxWithConstraints` 推导断点并注入上下文，替代散落的宽度判定。 */
@Composable
fun AppAdaptiveScope(content: @Composable AppAdaptiveContext.() -> Unit) {
    BoxWithConstraints {
        val bp = AppBreakpointFromWidth(maxWidth)
        val ctx = remember(bp) { AppAdaptiveContext(bp) }
        ctx.content()
    }
}

/** 导航容器换型（对齐 NavigationSuiteScaffold，§2.5.4）：目的地 SlotKey 复用，只换容器。 */
enum class NavigationContainer { BottomNav, NavigationRail, NavigationDrawer, TopTabs }

/** 停靠强度（对齐 VS Code / Dockview，§2.5.5）：Pinned↔AutoHide↔Floating 三态静态切换先落地。 */
enum class DockPlacement { Hidden, Pinned, AutoHide, Floating, Maximized }

/** 内容多栏规格（对齐 Pane Expansion / SplitLayout，§2.5.6）：fixed 与 flexible 混排。 */
data class PaneSpec(val id: String, val min: Dp, val flexible: Boolean, val weight: Float = 1f)

/** 装配子项（§2.5.7）：某 SlotKey 当前所在容器 + 停靠强度 + 栏位权重快照。 */
data class PlacementState(
    val container: NavigationContainer? = null,
    val dock: DockPlacement = DockPlacement.Pinned,
    val panes: List<PaneSpec> = emptyList(),
)

/** 装配图（§2.5.7）：可序列化还原的布局快照，可与固码装配融合。 */
data class SlotAssembly(
    val placements: Map<SlotKey, PlacementState>,
    val order: List<SlotKey>,
)

/** 形态切换编排节点（§2.5.9）：策略产出的一次原子编排动作。 */
sealed interface NodeChange {
    data class Move(val key: SlotKey, val container: NavigationContainer) : NodeChange
    data class Dock(val key: SlotKey, val dock: DockPlacement) : NodeChange
    data class Resize(val key: SlotKey, val weight: Float) : NodeChange
    data object Remount : NodeChange
}

/** 策略槽位（§2.5.9）：断点切换时决定"位移/换装/调宽"编排，P1 注入式，默认无动作。 */
interface SlotStrategy {
    fun step(from: AppBreakpoint, to: AppBreakpoint, key: SlotKey): NodeChange? = null
}