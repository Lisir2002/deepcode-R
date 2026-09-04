package com.core.deepcode.newui.designsystem.component.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 触觉反馈封装（§3.12 AppHaptics）：破坏性操作二次确认等统一走此处。
 * 用法：`val onClick = AppHaptics.click { doDestructive() }`，然后绑定到可点击控件。
 */
object AppHaptics {
    @Composable
    fun click(onClick: () -> Unit): () -> Unit {
        val haptics = LocalHapticFeedback.current
        return remember(onClick) {
            {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        }
    }
}