package com.R.codecore.feature.chatrender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.R.codecore.core.theme.LocalAppDarkMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 角色色板：取「主题深浅两档」——用户为深档强调色、助手为浅档强调色、工具为中性灰。
 * 亮 / 暗模式各自适配（[LocalAppDarkMode]），用于色线 / 箭头 / 节点 / 色块等线性元素。
 */
object BubblePalette {
    /** 用户 · 深档强调色。 */
    @Composable
    fun user(): Color = if (LocalAppDarkMode.current) Color(0xFF60A5FA) else Color(0xFF1D4ED8)

    /** 助手 · 浅档强调色。 */
    @Composable
    fun assistant(): Color = if (LocalAppDarkMode.current) Color(0xFF7DD3FC) else Color(0xFF93C5FD)

    /** 工具 · 中性灰。 */
    @Composable
    fun tool(): Color = if (LocalAppDarkMode.current) Color(0xFF94A3B8) else Color(0xFF64748B)

    /** 思考 · 淡紫。 */
    @Composable
    fun reasoning(): Color = if (LocalAppDarkMode.current) Color(0xFFA78BFA) else Color(0xFF7C3AED)

    /** 任务组贯穿竖条 · 靛蓝。 */
    @Composable
    fun spine(): Color = if (LocalAppDarkMode.current) Color(0xFFA5B4FC) else Color(0xFF818CF8)
}

/** 时间线节点的形状类型：按角色分配（用户=圆、助手=方、工具=菱）。 */
enum class BubbleNodeShape(val shape: Shape, val square: Boolean = false) {
    CIRCLE(CircleShape),
    SQUARE(RoundedCornerShape(3.dp)),
    DIAMOND(RoundedCornerShape(3.dp));

    val isSquare: Boolean get() = this != CIRCLE
}

/**
 * 时间线节点：形状 + 颜色双维度区分角色。
 * 菱形通过 45° 旋转的圆角方块呈现。
 */
@Composable
fun BubbleNode(
    shape: BubbleNodeShape,
    color: Color,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    if (shape == BubbleNodeShape.DIAMOND) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape.shape)
                .background(color)
                .rotate(45f)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape.shape)
                .background(color)
        )
    }
}

/** 时间线节点占位宽度：轨道竖线中心对齐于此列中线。 */
const val BUBBLE_TRACK_WIDTH_DP = 14

/**
 * 智能时间格式化：当天仅显示 HH:mm，跨天自动带上日期（如 M-d HH:mm）。
 * 用于时间线款式节点旁的小灰字时间戳。
 */
fun formatBubbleTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return runCatching {
        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }
        val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isToday) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("M-d HH:mm", Locale.getDefault()).format(date)
        }
    }.getOrDefault("")
}
