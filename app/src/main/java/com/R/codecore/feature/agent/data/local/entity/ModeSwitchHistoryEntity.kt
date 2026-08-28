package com.R.codecore.feature.agent.data.local.entity

/**
 * 模式切换历史（G-1）：记录一次 PLAN/BUILD 等模式的切换 {from, to, reason, timestamp}，
 * 供 AI/用户回溯切换原因与频次，也用于 G-3 频率限制的判定依据。
 */

data class ModeSwitchHistoryEntity(
     val id: Long = 0,
    val sessionId: String,
    val fromMode: String,
    val toMode: String,
    val reason: String,
    val timestampMs: Long
)
