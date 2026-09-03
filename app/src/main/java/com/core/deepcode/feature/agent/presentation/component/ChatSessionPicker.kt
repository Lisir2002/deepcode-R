package com.core.deepcode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.deepcode.R
import com.core.deepcode.core.theme.Radius
import com.core.deepcode.core.theme.Spacing
import com.core.deepcode.feature.agent.data.local.dao.ChatSessionWithCount

/**
 * 单条会话行（两行布局）：短按选中，长按弹出功能菜单（重命名/删除）。
 * - 第一行：执行中呼吸点 + 标题（单行 Ellipsis）。
 * - 第二行：智能分档时间 · N 条消息（设计文档 chat-session-list-refactor-design A2）。
 * 选中态整行背景高亮 primaryContainer。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSessionRow(
    session: ChatSessionWithCount,
    selected: Boolean,
    isExecuting: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val bucket = sessionBucket(session.updatedAtMs, nowMs)
    val timeText = when (bucket) {
        SessionBucket.TODAY -> formatSessionClock(session.updatedAtMs)
        SessionBucket.YESTERDAY -> stringResource(R.string.chat_session_yesterday)
        SessionBucket.WITHIN_7D ->
            stringResource(R.string.chat_session_days_ago, sessionDaysAgo(session.updatedAtMs, nowMs))
        SessionBucket.EARLIER -> formatSessionDate(session.updatedAtMs, nowMs)
    }
    val countText = if (session.messageCount > 0) {
        stringResource(R.string.chat_session_msg_count, session.messageCount)
    } else {
        stringResource(R.string.chat_session_no_msg)
    }
    val metaText = stringResource(R.string.chat_session_meta, timeText, countText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isExecuting) {
                val transition = rememberInfiniteTransition(label = "tool-status-dot")
                val alpha by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
                    label = "tool-status-dot-alpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E).copy(alpha = alpha))
                )
                Spacer(Modifier.width(Spacing.md))
            }
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = metaText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 有执行中呼吸点时，第二行与标题左对齐（呼吸点 8dp + 间距 md）
            modifier = Modifier.padding(start = if (isExecuting) 8.dp + Spacing.md else 0.dp)
        )
    }
}
