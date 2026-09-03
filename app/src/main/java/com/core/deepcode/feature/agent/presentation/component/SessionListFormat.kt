package com.core.deepcode.feature.agent.presentation.component

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 对话列表时间分组与相对时间格式化工具（设计文档 chat-session-list-refactor-design A2/A3）。
 *
 * 纯逻辑、无 Compose 依赖；「昨天」「N 天前」等本地化文案由 UI 层经 stringResource 渲染。
 */
enum class SessionBucket { TODAY, YESTERDAY, WITHIN_7D, EARLIER }

private const val DAY_MS = 24L * 60 * 60 * 1000

/** 所在自然日零点（本地时区）。 */
private fun startOfDayMs(ms: Long): Long = Calendar.getInstance().run {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/**
 * 按 updatedAtMs 判定分组档位：
 * 今天 = 今日零点起；昨天 = 昨日零点至今日零点；7天内 = 7 个自然日前零点起；更早 = 其余。
 */
fun sessionBucket(updatedAtMs: Long, nowMs: Long): SessionBucket {
    val startOfToday = startOfDayMs(nowMs)
    return when {
        updatedAtMs >= startOfToday -> SessionBucket.TODAY
        updatedAtMs >= startOfDayMs(nowMs - DAY_MS) -> SessionBucket.YESTERDAY
        updatedAtMs >= startOfDayMs(nowMs - 7 * DAY_MS) -> SessionBucket.WITHIN_7D
        else -> SessionBucket.EARLIER
    }
}

/** 距今天数（0=今天，1=昨天…），用于「N 天前」。 */
fun sessionDaysAgo(updatedAtMs: Long, nowMs: Long): Int {
    val days = (startOfDayMs(nowMs) - startOfDayMs(updatedAtMs)) / DAY_MS
    return days.toInt().coerceAtLeast(0)
}

/** 今日会话时钟（HH:mm）。 */
fun formatSessionClock(updatedAtMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAtMs))

/** 更早会话日期（M月d日；跨年加 yyyy年）。 */
fun formatSessionDate(updatedAtMs: Long, nowMs: Long): String {
    val sameYear = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = updatedAtMs }.get(Calendar.YEAR)
    val pattern = if (sameYear) "M月d日" else "yyyy年M月d日"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(updatedAtMs))
}
