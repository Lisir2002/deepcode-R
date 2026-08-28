package com.R.codecore.feature.agent.data.local.entity

/**
 * RC68 SCHEMA 38：`createdAt` 统一 `createdAtMs` 后缀，避免「单位是秒还是毫秒？」的跨代码块歧义。
 */

data class CheckpointEntity(
     val id: String,
    val sessionId: String,
    val userMessageId: String,
    val promptSnippet: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
