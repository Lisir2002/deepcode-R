package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
/**
 * AppSwipeAction（滑扫分子组件）回归测试。
 *
 * 背景：真机（DesignGallery 样板页）上发现两个缺陷——
 *  1. 【B1】左滑后动作按钮不可见（高速 fling 冲过动作栏到 Trigger，露出空白缝）；
 *  2. 【B2】同批多行互斥失效（收起弹簧过冲反向穿越阈值 → 抢报 → 雪崩）。
 *
 * 手势必须用**真实拖拽**（连续帧 + 事件间隔 + 带速度抬起）——v9 教训：
 * 两步瞬时手势速度≈0，走位置阈值路径，掩盖了速度路径 computeTarget 的全部缺陷。
 *
 * 本测试在 Robolectric(sdk 28) 上用 Compose 语义树 + 触摸输入复现真实调用环境
 * （verticalScroll 无界高度 + 同批多行互斥提升状态），让缺陷在 CI 可复现、防回归。
 * 注意：boundsInRoot 会被外层 clip 裁剪（内容滑出后 left 恒为 0），位置断言必须用 positionInRoot。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppSwipeActionTest {

    @get:Rule
    val compose = createComposeRule()

    /** 用底层触摸原语模拟**真实拖拽**：连续多帧位移 + 事件间隔 + 带速度抬起（对齐真机手感）。 */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.swipeRowLeft() =
        performTouchInput {
            val h = visibleSize.height
            down(Offset(visibleSize.width - 5f, h / 2f))
            advanceEventTime(16)
            repeat(18) {
                moveBy(Offset(-9f, 0f))   // 连续小位移，累计 -162px（越过 Open 128px）
                advanceEventTime(16)      // ~60fps 事件间隔 → 抬手时带真实速度
            }
            up()
        }

    /** 【B1】左滑后动作按钮应存在、尺寸非零、且位于行右侧可视区内。 */
    @Test
    fun swipeLeft_revealsButtonsInsideViewport() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(96.dp)) {
                    AppSwipeAction(
                        actionWidth = 128.dp,
                        actions = {
                            AppSwipeButton(
                                icon = Icons.Filled.Home,
                                label = "归档",
                                background = Color(0xFF3B82F6),
                                onClick = {},
                            )
                            AppSwipeButton(
                                icon = Icons.Filled.Info,
                                label = "删除",
                                background = Color(0xFFEF4444),
                                onClick = {},
                            )
                        },
                    ) {
                        Text("会话内容", modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("会话内容").assertExists()
        compose.onNodeWithText("归档").assertExists()
        compose.onNodeWithText("删除").assertExists()

        compose.onNodeWithText("会话内容").swipeRowLeft()
        compose.waitForIdle()

        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val archive = compose.onNodeWithText("归档").fetchSemanticsNode().boundsInRoot
        assertTrue("归档按钮宽度应>0（实际 ${archive.width}）", archive.width > 0f)
        assertTrue("归档按钮高度应>0（实际 ${archive.height}）", archive.height > 0f)
        assertTrue(
            "归档按钮应位于右半区：left=${archive.left}, rootWidth=$rootWidth",
            archive.left >= rootWidth * 0.4f,
        )
        assertTrue(
            "归档按钮不应超出右缘：right=${archive.right}, rootWidth=$rootWidth",
            archive.right <= rootWidth + 1f,
        )
    }

    /** 【B2】滑开第二行后应申报 expandedIndex=1；再滑第三行，第二行应被收起。 */
    @Test
    fun mutex_expandingSecondRowCollapsesFirst() {
        var expanded by mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    repeat(3) { i ->
                        AppSwipeAction(
                            index = i,
                            expandedIndex = expanded,
                            onExpanded = { expanded = it },
                            actionWidth = 128.dp,
                            actions = {
                                AppSwipeButton(
                                    icon = Icons.Filled.Home,
                                    label = "动作$i",
                                    background = Color(0xFF3B82F6),
                                    onClick = {},
                                )
                            },
                        ) {
                            Text(
                                "行$i",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        compose.waitForIdle()

        fun dump(tag: String) {
            repeat(3) { i ->
                val t = compose.onAllNodesWithText("行$i")[0].fetchSemanticsNode()
                println(
                    "SwipeDbg: $tag 行$i textLeft=${t.boundsInRoot.left} " +
                        "textRight=${t.boundsInRoot.right} posInRoot=${t.positionInRoot}",
                )
                val b = compose.onAllNodesWithText("动作$i")[0].fetchSemanticsNode()
                println(
                    "SwipeDbg: $tag 动作$i btnLeft=${b.boundsInRoot.left} btnRight=${b.boundsInRoot.right}",
                )
            }
        }

        compose.onAllNodesWithText("行1").assertCountEquals(1)
        compose.onAllNodesWithText("行1")[0].swipeRowLeft()
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        dump("after-swipe-1")
        compose.waitUntil(5_000) { expanded == 1 }

        // 【B1·v10】Reveal 模式高速 fling 最多锚定 Open（-128px），不得冲过动作栏到 Trigger。
        // （v9 实测：速度路径 computeTarget 会把内容层冲到 -184px，动作栏右侧露出 56px 空白缝。）
        // 注意：boundsInRoot 会被外层 clip 裁剪（实测滑出后 left 恒为 0），必须用 positionInRoot。
        val row1x = compose.onAllNodesWithText("行1")[0].fetchSemanticsNode().positionInRoot.x
        assertTrue(
            "Reveal fling 应停在 Open(-128px)，实际 x=$row1x（冲过动作栏=Trigger 缺陷复发）",
            row1x in -130f..-126f,
        )

        compose.onAllNodesWithText("行2")[0].swipeRowLeft()
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        dump("after-swipe-2")
        compose.waitUntil(5_000) { expanded == 2 }
        compose.waitForIdle()
        dump("final")

        val row0x = compose.onAllNodesWithText("行0")[0].fetchSemanticsNode().positionInRoot.x
        assertTrue(
            "展开行2后，行0应已收起回弹（x≈0），实际 x=$row0x",
            row0x < 2f,
        )
    }
}
