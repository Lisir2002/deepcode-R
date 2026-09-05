package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.core.deepcode.newui.designsystem.token.generated.AppSpacing
import com.core.deepcode.newui.designsystem.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1:1 复刻 DesignGallery「分子组建族 · 滑扫操作」区块的调用环境：
 * AppTheme + AppMenuRow 内容 + AppSpacing.Sm 行间隔 + verticalScroll 无界高度 + 三行互斥提升状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppSwipeGalleryReplicaTest {

    @get:Rule
    val compose = createComposeRule()

    /** 用底层触摸原语模拟**真实拖拽**（连续帧 + 事件间隔 + 带速度抬起），对齐真机手感。 */
    private fun SemanticsNodeInteraction.swipeRowLeft() = performTouchInput {
        val h = visibleSize.height
        down(Offset(visibleSize.width - 5f, h / 2f))
        advanceEventTime(16)
        repeat(18) {
            moveBy(Offset(-9f, 0f))   // 连续小位移，累计 -162px（越过 Open 128px）
            advanceEventTime(16)      // ~60fps → 抬手带真实速度
        }
        up()
    }

    @Test
    fun galleryReplica_swipeRevealsAndMutexWorks() {
        var expanded by mutableStateOf<Int?>(null)
        val rows = listOf("会话 A · deepcode-agent", "会话 B · settings refactor", "会话 C · terminal local")
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, title ->
                        AppSwipeAction(
                            index = index,
                            expandedIndex = expanded,
                            onExpanded = { expanded = it },
                            actionWidth = 128.dp,
                            actions = {
                                AppSwipeButton(
                                    icon = Icons.Filled.Home,
                                    label = "归档",
                                    background = Color(0xFF1976D2),
                                    onClick = {},
                                )
                                AppSwipeButton(
                                    icon = Icons.Filled.Home,
                                    label = "删除",
                                    background = Color(0xFFC62828),
                                    onClick = {},
                                )
                            },
                        ) {
                            AppMenuRow(
                                title = title,
                                subtitle = "左滑露出操作 · 点击内容收起 · 同批只开一项",
                                icon = Icons.Filled.Code,
                            )
                        }
                        if (index != rows.lastIndex) Spacer(Modifier.height(AppSpacing.Sm))
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("会话 B · settings refactor").assertCountEquals(1)
        compose.onAllNodesWithText("会话 B · settings refactor")[0].swipeRowLeft()
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        compose.waitUntil(5_000) { expanded == 1 }
        compose.waitForIdle()

        val rootW = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val del = compose.onAllNodesWithText("删除")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("删除按钮宽>0（${del.width}）", del.width > 0f)
        assertTrue("删除按钮应在右半区 left=${del.left} rootW=$rootW", del.left >= rootW * 0.4f)

        compose.onAllNodesWithText("会话 C · terminal local")[0].swipeRowLeft()
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        compose.waitUntil(5_000) { expanded == 2 }
        compose.waitForIdle()

        // 注意：boundsInRoot 会被外层 clip 裁剪（滑出后 left 恒为 0），必须用 positionInRoot。
        val rowBx = compose.onAllNodesWithText("会话 B · settings refactor")[0].fetchSemanticsNode().positionInRoot.x
        assertTrue("行 B 应收起（x≈0）实际=$rowBx", rowBx < 2f)
    }
}
